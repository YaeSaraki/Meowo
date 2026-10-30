package com.meowo.agents

import com.meowo.agents.application.AgentRuntime
import com.meowo.agents.application.AgentScheduler
import com.meowo.agents.application.CallBudget
import com.meowo.agents.application.Lane
import com.meowo.agents.application.CatSink
import com.meowo.agents.application.CatTools
import com.meowo.agents.application.ToolRegistry
import com.meowo.agents.application.Question
import com.meowo.agents.domain.CatProfile
import com.meowo.agents.domain.CatRegistry
import com.meowo.agents.domain.CatTurn
import com.meowo.agents.domain.ConversationKey
import com.meowo.agents.domain.Guard
import com.meowo.agents.domain.Redaction
import com.meowo.agents.infrastructure.HttpLlmClient
import com.meowo.agents.infrastructure.PlayerProfileStore
import com.meowo.agents.infrastructure.SqliteSessionJournal
import com.meowo.api.EconomyAPI
import com.meowo.api.HomeMenuAPI
import com.meowo.api.SettlementAPI
import com.meowo.api.chat.ChatDirectoryAPI
import com.meowo.api.chat.ChatModerationAPI
import com.meowo.api.event.ChatMessagePublished
import com.meowo.platform.PlatformHost
import com.meowo.platform.ReadOnly
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Wires the cats into a running server.
 *
 * ## What this claims, and what it deliberately does not
 *
 * It claims **speakers** (`claimSpeaker`) and nothing else. It does **not** register a
 * [com.meowo.api.chat.ChatModerator], because that interface runs on the hot path for every single
 * message with a sub-millisecond budget and an explicit ban on blocking IO. Registering one would
 * put a model round trip in front of every line of chat. The discipline cat's judgements travel out
 * of band through `setRisk` instead (docs/minimax/15-chat §8).
 *
 * ## Threads
 *
 * [CatSink.onDirectMessage] is called synchronously by `ChatService.directMessage` on the
 * caller's thread and returns `true` or `false` immediately. Everything expensive happens on
 * [scheduler]'s own threads, and only the final `messages.say` hops back to the main thread
 * through `host.mainThread`. No model call and no database read is ever on a tick.
 *
 * ## Degrading
 *
 * With no `baseUrl` configured the context still starts, still claims the speakers, and every
 * answer is a short in-character apology. A server with the feature switched on but unconfigured
 * behaves like a server with the feature off, which is far better than one that hangs.
 */
class AgentsContext(
    private val host: PlatformHost,
    private val settings: AgentsSettings = AgentsSettings(),
    /** Display names for prompts. Injected so a test never touches Bukkit. */
    private val nameOf: (UUID) -> String = { Bukkit.getPlayer(it)?.name ?: it.toString().take(8) },
) : Context, Listener {

    override val id = "agents"

    private companion object {
        /** How often the daily call budget is checked for a new day. An hour, deliberately. */
        const val BUDGET_DAY_TICKS = 20L * 60L * 60L
    }

    private val active = AtomicBoolean(false)
    private val resources = mutableListOf<AutoCloseable>()

    /**
     * The one place a refusal becomes a sentence.
     *
     * Built here rather than inside the runtime and the sink separately, so the copy the model
     * reads and the copy the player reads are rendered by the same rule — and a refusal cannot end
     * up in two languages.
     */
    private val refusals = AgentRefusals(host.messages)

    private lateinit var store: PlayerProfileStore
    private lateinit var memory: SqliteSessionJournal

    /** Who has been on the public channel lately. Names and counts; never anything private. */
    private val presence = com.meowo.agents.domain.PresenceBoard()
    private lateinit var budget: CallBudget
    private lateinit var impressions: com.meowo.agents.application.ImpressionService
    private lateinit var scheduler: AgentScheduler
    private lateinit var runtime: AgentRuntime
    private lateinit var sink: CatSink
    private lateinit var profiles: com.meowo.agents.application.ProfileTracker
    private lateinit var discipline: com.meowo.agents.application.DisciplineLoop
    private lateinit var risk: com.meowo.agents.application.RiskScorer
    private var flushTask: org.bukkit.scheduler.BukkitTask? = null
    private var disciplineTask: org.bukkit.scheduler.BukkitTask? = null
    private var budgetDayTask: org.bukkit.scheduler.BukkitTask? = null

    /**
     * Co-play, from the live counters.
     *
     * [com.meowo.agents.domain.TraitMath.coScore] wants a 24-hour histogram, and the histograms
     * live in the tracker rather than the database — the stored row keeps only the summary they
     * produced. Both players are online for a report to be filed, so both are being counted.
     */
    private fun coScoreOf(author: UUID, subject: UUID): Int {
        val a = profiles.hourly(author) ?: return 0
        val b = profiles.hourly(subject) ?: return 0
        return com.meowo.agents.domain.TraitMath.coScore(a, b)
    }

    private fun reportService(): com.meowo.agents.application.ReportService =
        com.meowo.agents.application.ReportService(store, ::coScoreOf)

    private fun onlineIds(): Collection<UUID> = Bukkit.getOnlinePlayers().map { it.uniqueId }

    /** Cats that can actually answer something, as opposed to the seven that only delegate. */
    private val agentCats: List<CatProfile> get() = CatRegistry.AGENT_CATS.mapNotNull { CatRegistry.byId(it) }

    /**
     * Which context owns each route.
     *
     * Filled by **the same `observe` callback** that records routes for `commands.list`, so a route
     * cannot be known to one and unknown to the other — which is the whole reason this is a map built
     * from an event rather than a hand-written `route -> cat` table. Route names and cat ids routinely
     * differ (`eco` is not `economy`), and `CommandRouter` documents exactly that.
     */
    private val routeOwners = ConcurrentHashMap<String, String>()

    /** The roll source, injectable so a test can drive both sides of the 5% threshold. */
    private var roll: () -> Double = { Random.nextDouble() }

    /**
     * How many times a cat has **tried** to start a conversation.
     *
     * An attempt, not a success: the lanes that can refuse it are the scheduler's, the budget's and
     * the queue's, and they all report through [com.meowo.agents.application.AgentScheduler] rather
     * than through here. This counts the moment the decision to spend something was taken, which is
     * the only number that can tell an operator "the cats never speak" apart from "they keep being
     * refused" — the two look identical from inside a server log otherwise.
     */
    val proactiveOffers = AtomicInteger()

    override fun start() {
        if (!active.compareAndSet(false, true)) return
        try {
            val dataFolder = host.plugin.dataFolder.toPath()
            store = PlayerProfileStore(dataFolder.resolve(settings.storePath))
            resources += store

            scheduler = AgentScheduler(workers = settings.workers, maxBacklog = settings.backlog)
            resources += scheduler

            val tools = buildTools()
            // The durable half of a cat's memory. Opened before the runtime so a failing database is
            // a startup failure rather than a cat that mysteriously forgets mid-session.
            memory = SqliteSessionJournal(
                path = dataFolder.resolve(settings.storePath.replace(".db", "-memory.db")),
                retentionDays = settings.memoryDays,
            ).also { resources += it }
            // Two clients, both closed with the context: each one owns an HTTP transport, and a
            // transport is a thread of its own from Java 21 on. A context that is closed and
            // started again — which a reload does — must not leave one behind every time.
            val llm = HttpLlmClient(
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                maxTokens = settings.maxTokens,
                onError = { message, error ->
                    if (error == null) host.plugin.logger.warning(message)
                    else host.plugin.logger.log(Level.WARNING, message, error)
                },
            ).also { resources += it }
            runtime = AgentRuntime(
                client = llm,
                tools = tools,
                model = settings.model,
                nameOf = nameOf,
                memory = memory,
                // The directory, not Bukkit: a 风纪 cat most often needs to look somebody up who is
                // *not* here right now, and `getOfflinePlayer` would have been a blocking lookup.
                lookupPlayer = ::resolveName,
                // A refusal reaches the model as a tool result and the player as a line; both have
                // to be in the player's language, so both are rendered here.
                renderRefusal = refusals::render,
            )

            // Layer 2 of the memory, built before the sink because the sink reads from it. Paid for
            // out of the proactive lane: an impression is the cat's own out-of-band thinking, and it
            // must never come from the allowance players' questions are spending.
            budget = CallBudget(dailyCap = settings.dailyCap, perPlayerCap = settings.perPlayerCap)
            impressions = com.meowo.agents.application.ImpressionService(
                client = HttpLlmClient(
                    baseUrl = settings.baseUrl,
                    apiKey = settings.apiKey,
                    maxTokens = settings.maxTokens,
                    onError = { message, error ->
                        if (error == null) host.plugin.logger.warning(message)
                        else host.plugin.logger.log(Level.WARNING, message, error)
                    },
                ).also { resources += it },
                store = store,
                model = settings.model,
                nameOf = nameOf,
                traitsOf = { profiles.traitsOf(it) },
                localeOf = { localeOf(it) },
                spend = { budget.acquire(Lane.PROACTIVE, it) },
                onFailure = { message, error ->
                    if (error == null) host.plugin.logger.warning(message)
                    else host.plugin.logger.log(Level.WARNING, message, error)
                },
            )
            sink = CatSink(
                presence = presence,
                runtime = runtime,
                scheduler = scheduler,
                budget = budget,
                policy = ::decide,
                cooldownMillis = settings.cooldownSeconds * 1000,
                traitsOf = { profiles.traitsOf(it) },
                localeOf = { localeOf(it) },
                nameOf = nameOf,
                adminOf = ::isAdmin,
                disclose = ::disclose,
                impressions = impressions,
                // The cat's excuse is a sentence, and the sentence is the server's wording rather
                // than the cat's: the same renderer the runtime uses for the prompt's copy.
                renderRefusal = refusals::render,
                // A worker thread that throws has nowhere to go: the player gets nothing and the
                // log says nothing either. The failure is reported instead.
                onFailure = { message, error ->
                    if (error == null) host.plugin.logger.warning(message)
                    else host.plugin.logger.log(Level.WARNING, message, error)
                },
            )
            sink.onReply = ::deliver

            // The writer. Until this existed the three profile layers had no way to be filled:
            // a player could talk for an hour and no cat would know anything about them.
            profiles = com.meowo.agents.application.ProfileTracker(store)
            resources += AutoCloseable { profiles.close() }
            resources += host.events.subscribe(ChatMessagePublished::class.java, profiles::onMessage)
            // The writer for [PresenceBoard]: who is on the public channel right now. Same event,
            // same cost — an O(1) in-memory increment on a path that is already paying for one.
            // The board itself refuses non-public traffic, so a whisper cannot reach it.
            resources += host.events.subscribe(ChatMessagePublished::class.java) { event ->
                presence.note(nameOf(event.player), event.isPublic, event.atEpochMillis)
            }
            // One timer for the three out-of-band passes. Sharing it is not tidiness: pruning,
            // risk and the ladder all read the same rows, and separate timers let them disagree
            // about which evidence still exists.
            risk = com.meowo.agents.application.RiskScorer(store, online = { onlineIds() })
            discipline = com.meowo.agents.application.DisciplineLoop(
                store = store,
                risk = risk,
                reaper = com.meowo.agents.application.WorkOrderReaper(
                    store = store,
                    onlinePlayers = { Bukkit.getOnlinePlayers().size },
                    adminOnline = { Bukkit.getOnlinePlayers().any { it.hasPermission("meowo.admin") } },
                ),
                reports = reportService(),
                pushRisk = ::pushRisk,
                impressionDays = settings.impressionDays,
                // The chat log owns the login history; asking it keeps one answer to "when did they
                // last log in" instead of a second copy that would eventually disagree.
                lastSeen = { id ->
                    Bukkit.getServicesManager().load(ChatDirectoryAPI::class.java)?.lastSeen(id)
                },
            )

            // Flushed on the server's own timer rather than per message: the row is a summary and
            // does not change meaning between two flushes.
            //
            // **Asynchronous**, because a flush is a database round trip and the tick thread is the
            // one place a plugin must never wait. It used to run on the timer itself — on the main
            // thread — so every ten minutes the whole server paused for the length of a SQLite
            // write, at a moment nobody could predict from the outside.
            flushTask = host.plugin.server.scheduler.runTaskTimerAsynchronously(
                host.plugin,
                Runnable {
                    if (!active.get()) return@Runnable
                    runCatching { profiles.flush() }.onFailure { error ->
                        host.plugin.logger.log(Level.WARNING, "profile flush failed", error)
                    }
                },
                20L * 60L, 20L * 60L,
            )
            resources += AutoCloseable { flushTask?.cancel() }
            // The discipline pass reads and writes several tables and recomputes a score for every
            // watched player. Asynchronous for the same reason as the flush above, and it is safe
            // to do: everything it touches is either this store (one connection per thread) or a
            // hop back to the main thread.
            disciplineTask = host.plugin.server.scheduler.runTaskTimerAsynchronously(
                host.plugin,
                Runnable {
                    if (!active.get()) return@Runnable
                    runCatching { discipline.run() }.onFailure { error ->
                        host.plugin.logger.log(Level.WARNING, "discipline pass failed", error)
                    }
                },
                settings.disciplineIntervalSeconds * 20L, settings.disciplineIntervalSeconds * 20L,
            )
            resources += AutoCloseable { disciplineTask?.cancel() }

            // The daily cap, and the only thing that makes it *daily*.
            //
            // `CallBudget.reset` had no caller outside the tests, so `agents.daily-cap` was in fact
            // a ceiling for as long as the server stayed up: a server that quietly ran out of
            // allowance looked exactly like one whose cats had stopped answering, with nothing in
            // the logs to say why. An hour of granularity rather than a timer aimed at midnight,
            // because midnight moves twice a year and an hourly tick cannot drift into firing at
            // 23:00 every day; the day stamp is the server's local date, which is what "a day" means
            // to the players on it.
            budgetDayTask = host.plugin.server.scheduler.runTaskTimer(
                host.plugin,
                Runnable {
                    if (!active.get()) return@Runnable
                    runCatching {
                        if (sink.advanceBudgetDay(java.time.LocalDate.now().toEpochDay())) {
                            host.plugin.logger.info("Agent call budget rolled over to a new day.")
                        }
                    }
                },
                20L, BUDGET_DAY_TICKS,
            )
            resources += AutoCloseable { budgetDayTask?.cancel() }

            resources += host.commands.observe { contextId, name, route ->
                known.add(com.meowo.agents.application.CommandInfo(name, route.permission))
                routeOwners[name] = contextId
            }
            // The other half of the proactive feature: a cat may start a conversation when the
            // player uses one of *its* commands. Fired by the router after the route has run, so
            // this never sees a command that was rejected or that the sender was not allowed to use.
            resources += host.commands.onRun { sender, route -> onRouteRun(sender, route) }
            resources += registerCommands(host, store) { active.get() }
            resources += registerForgetCommand(host, store) { active.get() }
            // The one input the 风纪 side had no way to receive. See `registerReportCommand`.
            resources += registerReportCommand(host, discipline) { active.get() }
            claimSpeakers()
            reportUnwiredTools(tools)
            Bukkit.getPluginManager().registerEvents(this, host.plugin)
            resources += AutoCloseable { org.bukkit.event.HandlerList.unregisterAll(this) }
        } catch (error: Exception) {
            active.set(false)
            resources.forEach { runCatching { it.close() } }
            resources.clear()
            throw error
        }
    }

    /**
     * Builds the tool set from **narrowed views** of the other contexts' APIs.
     *
     * The economy and settlement types it takes are `BalanceReader` and `SettlementReader` from
     * `com.meowo.platform`, never `EconomyAPI` or `SettlementAPI`. The write methods are not on
     * any type this method can name, so no cat can be given one.
     */
    private fun buildTools(): ToolRegistry {
        val services = Bukkit.getServicesManager()
        val money = CatTools.Money { host.money(it) }
        // Each half is optional: a server may run homes without wallets, or fees without wallets.
        // Folding rather than `+=` because merging returns a new registry.
        return listOfNotNull(
            services.load(EconomyAPI::class.java)?.let { wallet ->
                CatTools.economy(
                    ReadOnly.balance(wallet),
                    ReadOnly.settlementOrNull { services.load(SettlementAPI::class.java) } ?: return@let null,
                    money,
                )
            } ?: services.load(SettlementAPI::class.java)?.let { CatTools.settlementOnly(ReadOnly.settlement(it), money) },
            services.load(HomeMenuAPI::class.java)?.let { CatTools.home(ReadOnly.homes(it)) },
            // The command list, from the router's own registrations — the observer replays whatever
            // was registered before us, so load order does not decide what MeowCat knows.
            ToolRegistry(listOf(CatTools.commands({ knownCommands() }))),
            // The discipline cat's three lookups. `moderation.risk` and `moderation.history` are
            // the only tools in the project that read a *judgement* about a player, and both are
            // read-only by construction: the write side is `setRisk`, which this context owns.
            moderationTools(),
        ).fold(ToolRegistry()) { acc, next -> acc + next }
    }

    /**
     * Whether a player may be told about [subject].
     *
     * Delegates to [Redaction.verdict] rather than deciding here, so the rule about opt-out and
     * permission lives in exactly one place and a future opt-out feature changes a boolean instead
     * of a comparison. **Profiling is assumed on**: there is no opt-out data source on this server
     * yet, and inventing a default of "off" would make every cat mute about everyone. With sharing
     * [Guard.Sharing.OFF] that reads as: yourself always, an admin in full, anybody else refused —
     * which is what the discipline tools need, and the reason they are usable at all.
     */
    private fun disclose(asker: UUID, subject: UUID): Redaction.Verdict =
        Redaction.verdict(
            asker = asker,
            subject = subject,
            subjectProfilingEnabled = true,
            askerIsAdmin = isAdmin(asker),
            sharing = com.meowo.agents.domain.Guard.Sharing.OFF,
        )

    /**
     * A typed name to a player id, through the chat context's directory.
     *
     * Null when nobody goes by that name, which every tool turns into a refusal. Going through the
     * directory rather than `Bukkit` also means the same name cannot mean two different people in
     * `/msg` and in a cat's answer.
     */
    private fun resolveName(name: String): UUID? =
        Bukkit.getServicesManager().load(ChatDirectoryAPI::class.java)?.uuidOf(name)

    /**
     * Whether this player may ask this cat anything at all.
     *
     * A single decision point for mute, opt-out and permission, so the three can never drift apart
     * in the place that matters. Everything about *another player's* data is decided later, inside
     * the tools, against [Redaction] — this only answers "may they talk to a cat".
     */
    private fun decide(player: UUID, cat: CatProfile, text: String): com.meowo.agents.application.Admission {
        val moderation = Bukkit.getServicesManager().load(ChatModerationAPI::class.java)
        if (moderation != null && moderation.isMuted(player)) {
            return com.meowo.agents.application.Admission.Refused(
                com.meowo.agents.domain.TurnedAway.NOT_ALLOWED,
            )
        }
        if (text.isBlank()) {
            return com.meowo.agents.application.Admission.Refused(
                com.meowo.agents.domain.TurnedAway.NOT_ALLOWED,
            )
        }
        return com.meowo.agents.application.Admission.Accepted(cat)
    }

    /**
     * Says out loud which cats were promised tools that do not exist.
     *
     * A cat's system prompt is built from its [CatProfile], so it is told about every tool it was
     * *declared* with — and the model will call them, and [ToolRegistry] will refuse, and the player
     * sees a cat insisting it has no ability for a capability the prompt named one line earlier.
     * That is a silent, confusing failure, so it is reported at startup instead. [ToolRegistry.missingFor]
     * exists for exactly this and had no caller until now.
     */
    private fun reportUnwiredTools(tools: ToolRegistry) {
        for (cat in agentCats) {
            val missing = tools.missingFor(cat)
            if (missing.isNotEmpty()) {
                host.plugin.logger.warning(
                    "${cat.displayName} (${cat.id}) claims ${missing.size} tool(s) this build cannot serve: " +
                        missing.joinToString(", ") + ". It will tell players it cannot do these things.",
                )
            }
        }
    }

    /**
     * Takes the speakers this context can actually answer for.
     *
     * `claimSpeaker` replaces the previous holder and restores it on close, so disabling the agents
     * hands the cats back to the helpdesk stand-in rather than leaving them mute.
     */
    /**
     * The player's client language, which is what decides the language a reply comes back in.
     *
     * Falls back to the server default for anybody offline, which is the same rule the message
     * layer uses, so a cat never answers in one language and the surrounding plugin renders in
     * another.
     */
    private fun localeOf(player: UUID): String =
        Bukkit.getPlayer(player)?.let { com.meowo.platform.LocaleResolver.pick(com.meowo.platform.LocaleResolver.tagOf(it.locale())) }
            ?: host.messages.defaultLocale

    private fun claimSpeakers() {
        val moderation = Bukkit.getServicesManager().load(ChatModerationAPI::class.java) ?: return
        for (cat in agentCats) {
            // Not a lambda: `SystemSpeakerSink` is a plain Kotlin interface, so there is no SAM
            // conversion and a trailing lambda would not compile.
            // `CatSink` is itself the sink, and it implements `ReplyCapable`, so the chat context
            // injects its private-message rendering straight into it — including for a refusal,
            // which is delivered as an ordinary line rather than a bare `false`.
            val handler: com.meowo.api.chat.SystemSpeakerSink = sink
            resources += moderation.claimSpeaker(cat.speakerId, handler)
        }
    }

    /**
     * A cat may start a conversation when the player uses one of its own commands.
     *
     * ## The trigger, and why it is a command rather than "the player went quiet"
     *
     * The plan's rule is that chatter follows **use of the feature**: somebody runs `/meo home`, and
     * NestCat — which owns `home` — may say something about it. That is a far better trigger than a
     * silence timer, because it is the only moment where the player demonstrably wants something from
     * that cat, and it needs no new bookkeeping to explain to an operator.
     *
     * ## Why the owner comes from the router
     *
     * `routeOwners[route]` is filled by the same callback that feeds `commands.list`. A hand-written
     * `route -> cat` table would be a second place to forget, and `CommandRouter` exists precisely
     * because route names and context ids differ (`eco` is not `economy`, `tpa` is not `teleport`).
     *
     * ## Every gate, cheapest first
     *
     * All of them are cheap and all of them must pass. The two that decide whether the model is even
     * worth asking are `hasRoom(PROACTIVE)` — the reserved share of the day's calls — and the idle
     * check, because a greeting that jumps ahead of a real question is exactly the starvation the
     * reserved quota exists to prevent.
     */
    private fun onRouteRun(sender: CommandSender, route: String) {
        if (!active.get() || !settings.proactive) return
        // The console is not somebody to talk to, and a cat addressing it would land in the log.
        val player = sender as? Player ?: return
        val contextId = routeOwners[route] ?: return
        // Only the data-backed cats speak up; the other seven have no tools and would be guessing.
        val cat = CatRegistry.byId(contextId) ?: return
        if (cat.id !in CatRegistry.AGENT_CATS) return
        if (!budget.hasRoom(Lane.PROACTIVE)) return
        if (scheduler.backlogDepth() > 0) return
        if (!Guard.proactive(
            roll = roll(),
            idle = true,
            inCooldown = false,
            proactiveQuotaLeft = true,
            // There is no per-player "do not speak to me unprompted" storage on this server yet, so
            // this gate has no input to read. The server-wide opt-out is `settings.proactive`, checked
            // at the top; when a per-player preference lands, **this is the line that reads it**.
            quiet = false,
            enabled = true,
            public = false,
            othersOnline = 0,
        )) return
        proactiveOffers.incrementAndGet()
        sink.offerProactive(player.uniqueId, cat, host.messages.text(player, "agents-proactive", "/meo $route"))
    }

    /**
     * A finished turn, back on the main thread.
     *
     * `active` is the guard: a cat that answers after the context was disabled, or after the player
     * logged out, must not speak. `host.mainThread` only runs when the plugin is still enabled.
     */
    private fun deliver(turn: CatTurn, key: ConversationKey, cat: CatProfile) {
        val text = when (turn) {
            is CatTurn.Reply -> turn.text
            // A refusal is a reason until it is rendered, and the rendering happens here — in the
            // player's language, by the same rule the prompt's copy was rendered with.
            is CatTurn.Refuse -> refusals.render(localeOf(key.player), turn.reason, turn.args)
            is CatTurn.Delegate -> "${turn.cat}：${turn.reason}"
        }
        // Through the rendering chat injected at claim time, **not** `Messages.say`.
        //
        // A player who whispers a cat never sees their own message echoed — the answer is the
        // receipt — so it only reads as a conversation if the answer arrives as the same
        // `✉ from -> to ✔` line a player-to-player whisper uses. Sent as an ordinary chat line it
        // wore the wrong shape, which is exactly what was reported.
        if (!active.get()) return
        sink.deliver(key.player, nameOf(key.player), cat.speakerId, text)
    }

    /**
     * The discipline cat's tools, read from the chat context's own services.
     *
     * Deliberately assembled here rather than in `CatTools` so nothing else can obtain them: this
     * is the only place that can see both a risk score and an audit buffer.
     */
    private fun moderationTools(): ToolRegistry {
        val services = Bukkit.getServicesManager()
        val moderation = services.load(ChatModerationAPI::class.java)
        val directory = services.load(com.meowo.api.chat.ChatDirectoryAPI::class.java)
        if (moderation == null || directory == null) return ToolRegistry()
        return ToolRegistry(
            CatTools.moderation(
                risk = { moderation.risk(it) },
                history = { player, limit -> moderation.history(player, limit) },
                participants = { prefix, limit -> directory.participants(prefix, limit) },
            ),
        )
    }

    /**
     * Pushes a risk score back to the chat gate.
     *
     * This is the out-of-band half of the discipline design, and it is why the agents context does
     * not register a [com.meowo.api.chat.ChatModerator]: the gate reads the score with one map
     * lookup, so an AI that takes eight seconds to decide costs the chat path nothing (15-chat §8).
     */
    private fun pushRisk(player: UUID, value: Int) {
        val moderation = Bukkit.getServicesManager().load(ChatModerationAPI::class.java) ?: return
        host.mainThread(active::get) { moderation.setRisk(player, value) }
    }

    private val known = java.util.concurrent.CopyOnWriteArrayList<com.meowo.agents.application.CommandInfo>()

    /**
     * Filled by the router's observer rather than by asking contexts what they registered.
     *
     * A second hand-written table here would be exactly the drift the observer exists to prevent:
     * a command renamed in one context and not here, and MeowCat confidently tells a player about
     * a command that no longer exists.
     */
    private fun knownCommands() = known.toList()

    private fun isAdmin(player: UUID): Boolean =
        Bukkit.getPlayer(player)?.hasPermission("meowo.admin") ?: false

    /**
     * Drops every **in-memory** copy of a player: the conversation window the cats are holding, and
     * the impression cache in front of [com.meowo.agents.application.ImpressionService].
     *
     * This is what makes `/meo forget` a deletion rather than a request. The rows are gone either
     * way, but a cached impression survives in the heap until the next restart — long enough for a
     * cat to quote, in its own words, a sentence somebody asked to have deleted. The database and
     * the heap have different lifetimes, and only the store knows about the first one.
     */
    internal fun forgetCaches(player: UUID) {
        if (this::impressions.isInitialized) impressions.forget(player)
        if (this::sink.isInitialized) sink.forget(player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player.uniqueId
        sink.forget(player)
        runtime.forget(player)
        // Their last words are the most recent thing anyone learned about them; write before the
        // counters go, or the last few minutes are simply lost.
        //
        // The counters are **detached first** and written afterwards, on another thread. Both
        // halves matter: the write is a database round trip and the quit path is the busiest the
        // tick thread will be all session, and detaching first means the asynchronous write cannot
        // race the removal — a write that started after the counters were gone would save nothing
        // and report nothing.
        val pending = profiles.detach(player)
        if (pending != null) {
            host.plugin.server.scheduler.runTaskAsynchronously(host.plugin, Runnable {
                runCatching { profiles.writePending(player, pending) }.onFailure { error ->
                    host.plugin.logger.log(Level.WARNING, "profile flush failed on quit for $player", error)
                }
            })
        }
        risk.forget(player)
        profiles.forget(player)
    }

    override fun close() {
        if (!active.compareAndSet(true, false)) return
        resources.asReversed().forEach { runCatching { it.close() } }
        resources.clear()
    }
}
