package com.meowo.agents

import com.meowo.agents.domain.CatRegistry
import com.meowo.agents.domain.Sentiment
import com.meowo.api.chat.ChatDirectoryAPI
import com.meowo.platform.PlatformHost
import com.meowo.platform.Route
import com.meowo.platform.Timestamps
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/meo known` — what the cats think of **you**.
 *
 * ## Why this exists
 *
 * An impression is written out of band, by a model, from statistics, with no player present. That is
 * a lot of machinery aimed at one person, and the player was never told. The retention rules are
 * honest about how long those words are kept; nothing was honest about the fact that a sentence
 * about them existed at all.
 *
 * So it is readable, to the person it is about, in their own language. That is also the cheapest
 * check there is: if this page ever shows something a player would object to, the objection is
 * actionable, and without the page it would not be.
 *
 * ## Read-only on purpose
 *
 * This lists and nothing else. Deleting is a separate command with its own confirmation, because a
 * list that can also wipe is a list people will not open.
 */
fun AgentsContext.registerCommands(
    host: PlatformHost,
    profile: com.meowo.agents.infrastructure.PlayerProfileStore,
    isActive: () -> Boolean,
): AutoCloseable {
    val messages = host.messages
    return host.commands.register("known", "agents", object : Route {
        override val permission = "meowo.chat.use"

        override fun execute(sender: CommandSender, args: List<String>) {
            val player = sender as? Player
            if (player == null) {
                messages.sendPlain(sender, "known-console")
                return
            }
            // A command that answers after the context is disabled would be a cat's opinion of
            // somebody arriving after the cats went home.
            if (!isActive()) return
            val rows = runCatching { profile.impressionsOf(player.uniqueId.toString()) }
                .onFailure { error ->
                    host.plugin.logger.log(java.util.logging.Level.WARNING, "impression listing failed", error)
                }
                .getOrDefault(emptyList())
            if (rows.isEmpty()) {
                messages.sendPlain(sender, "known-empty")
                return
            }
            messages.sendPlain(sender, "known-header", rows.size)
            for (row in rows) {
                // The display name a player would recognise, falling back to the id: a row whose cat
                // no longer exists must still be visible here rather than silently disappear.
                val who = CatRegistry.byId(row.cat)?.displayName ?: row.cat
                messages.sendPlain(sender, "known-row", who, row.impression, Timestamps.full(row.updatedAt))
            }
            messages.sendPlain(sender, "known-footer")
        }
    })
}

/**
 * `/meo report <玩家> <negative|positive> <原因>` — tell the cats about another player.
 *
 * ## Why this command had to exist
 *
 * The 风纪 side of this plugin is a machine with four moving parts: a complaint is filed, the
 * complaint is counted, the count becomes a risk score, and the score is pushed into the chat gate
 * so the gate looks harder at that player. **Three of those four had no way to start.**
 *
 * - `RiskScorer.watch` had no caller anywhere, so the set of watched players was empty and the
 *   scoring pass iterated nothing.
 * - `PlayerProfileStore.bumpSoftHits` had no caller, so the counter the score is computed from was
 *   always zero.
 * - `upsertTraits` overwrote `soft_hits` with zero on every factual flush, so even a counter that
 *   *had* been incremented would have been erased ten minutes later.
 *
 * The result was a discipline system that ran on schedule, read its tables, pushed scores and
 * opened work orders — and decided nothing, because nothing ever fed it. It looked alive because
 * every part of it executed. This command is the missing input.
 *
 * ## The sentiment is written out, not guessed
 *
 * `negative` and `positive` are both spelled out in the command line, because a complaint that
 * quietly defaulted to one of them would be a complaint whose meaning depends on a default nobody
 * chose. The two are also genuinely different: a negative complaint is evidence and feeds the risk
 * score, a positive one is a tally and does not.
 *
 * ## The reply comes from the language file
 *
 * [com.meowo.agents.application.ReportService] answers with a **language key**, not with a
 * sentence. It has no language file and must not have one — a Chinese string hard-coded in the
 * domain is a string an English player cannot read, and that bug only shows up on a server nobody
 * testing it speaks the language of.
 */
fun AgentsContext.registerReportCommand(
    host: PlatformHost,
    discipline: com.meowo.agents.application.DisciplineLoop,
    isActive: () -> Boolean,
): AutoCloseable {
    val messages = host.messages
    return host.commands.register("report", "agents", object : Route {
        override val permission = "meowo.chat.use"

        override fun execute(sender: CommandSender, args: List<String>) {
            val player = sender as? Player
            if (player == null) {
                messages.sendPlain(sender, "report-console")
                return
            }
            // A complaint filed after the context was disabled would be a complaint about a machine
            // that is no longer running.
            if (!isActive()) return
            if (args.size < 3) {
                messages.sendPlain(sender, "report-usage")
                return
            }
            val sentiment = when (args[1].lowercase()) {
                "negative" -> Sentiment.NEGATIVE
                "positive" -> Sentiment.POSITIVE
                else -> {
                    messages.sendPlain(sender, "report-bad-sentiment", args[1])
                    return
                }
            }
            val reason = args.drop(2).joinToString(" ").trim()
            if (reason.isBlank()) {
                messages.sendPlain(sender, "report-usage")
                return
            }
            // Through the chat context's own directory, so the name means the same person here as
            // it does in `/msg`. A name that belongs to nobody is refused rather than guessed at.
            val directory = Bukkit.getServicesManager().load(ChatDirectoryAPI::class.java)
            val subject = directory?.uuidOf(args[0])
            if (subject == null) {
                messages.sendPlain(sender, "report-unknown-player", args[0])
                return
            }
            val outcome = discipline.report(player.uniqueId, subject, sentiment, reason)
            when (outcome) {
                // The reports service is not wired up at all — a different failure from "refused",
                // and it must not be reported as a refusal.
                null -> messages.sendPlain(sender, "report-unavailable")
                // Both carry a **language key** rather than text; see the note above.
                is com.meowo.agents.application.ReportService.Outcome.Refused ->
                    messages.sendPlain(sender, outcome.why)
                is com.meowo.agents.application.ReportService.Outcome.Stored ->
                    messages.sendPlain(sender, outcome.why)
            }
        }

        override fun complete(sender: CommandSender, args: List<String>): List<String> = when (args.size) {
            // Who is here, plus who was here — the directory knows offline players too.
            1 -> {
                val directory = Bukkit.getServicesManager().load(ChatDirectoryAPI::class.java)
                val prefix = args[0].lowercase()
                directory?.participants(prefix, 64)?.map { it.handle }?.sorted() ?: emptyList()
            }
            // The two sentiments, spelled out.
            2 -> listOf("negative", "positive").filter { it.startsWith(args[1].lowercase()) }
            else -> emptyList()
        }
    })
}

/**
 * `/meo forget <your name>` — ask the cats to stop remembering you.
 *
 * ## The name is the confirmation
 *
 * This is irreversible, and a `/meo forget` with no argument would be one mis-click away from
 * destroying a player's own history. Typing your own name is a check that the reader read the
 * prompt and meant it — stronger than "yes", and it leaves no way to do it to somebody else.
 * Self-service only, deliberately: forgetting is the one operation in this plugin where "an admin
 * did it to you" would be a different feature with a different threat model.
 *
 * ## It reports what it actually did
 *
 * [com.meowo.agents.infrastructure.PlayerProfileStore.Forgot] is a per-table count, and the chat log
 * reports its own rows, because the two halves live in databases that cannot see each other. The
 * output therefore says *what* went, not just "done": a privacy command that answers "done" while
 * missing a table is the version of this feature nobody should ship.
 *
 * ## What it refuses to delete, and says so
 *
 * Impressions, traits and the reports the player filed are deleted. Reports **other people filed
 * about them** and the discipline ladder's rows are not — those are someone else's speech and the
 * server's own record, and a player typing their own name must not be able to erase an accusation
 * that has not been read yet. They are marked out of use instead, and `forget-kept` appears only
 * when something actually stayed, so the player is never left believing a deletion happened that did
 * not. See `PlayerProfileStore.forgetAll` and docs/minimax/08 §39.
 */
fun AgentsContext.registerForgetCommand(
    host: PlatformHost,
    profile: com.meowo.agents.infrastructure.PlayerProfileStore,
    isActive: () -> Boolean,
): AutoCloseable {
    val messages = host.messages
    return host.commands.register("forget", "agents", object : Route {
        override val permission = "meowo.chat.use"

        override fun execute(sender: CommandSender, args: List<String>) {
            val player = sender as? Player
            if (player == null) {
                messages.sendPlain(sender, "forget-console")
                return
            }
            if (!isActive()) return
            val typed = args.firstOrNull()
            if (typed == null) {
                messages.sendPlain(sender, "forget-usage", player.name)
                return
            }
            if (!typed.equals(player.name, ignoreCase = true)) {
                messages.sendPlain(sender, "forget-name-mismatch", player.name)
                return
            }

            val id = player.uniqueId.toString()
            // `null` means "could not ask": the chat context is off, or it threw. That is not the same
            // as "there was nothing", and this command exists to answer exactly that question — an
            // unanswered one reported as zero is the bug this whole feature is about, reintroduced
            // in the one place nobody re-reads.
            val chat: Int? = runCatching {
                Bukkit.getServicesManager().load(ChatDirectoryAPI::class.java)?.forgetPlayer(id)
            }.onFailure { error ->
                host.plugin.logger.log(java.util.logging.Level.WARNING, "chat forget failed for $id", error)
            }.getOrNull()
            val forgot = runCatching { profile.forgetAll(id) }
                .onFailure { error ->
                    host.plugin.logger.log(java.util.logging.Level.WARNING, "forget failed for $id", error)
                }
                .getOrNull()
            if (forgot == null) {
                messages.sendPlain(sender, "forget-failed")
                return
            }
            // Whatever the command just removed, the cached copy must not survive it — a cat quoting
            // a sentence whose row no longer exists is exactly the failure this command is for.
            forgetCaches(player.uniqueId)
            val profile = messages.text(sender, if (forgot.traits) "forget-profile-gone" else "forget-profile-none")
            if (chat == null) {
                messages.sendPlain(sender, "forget-partial", forgot.impressions, forgot.reportsFiled, profile)
            } else {
                messages.sendPlain(sender, "forget-done", chat, forgot.impressions, forgot.reportsFiled, profile)
            }
            // Said only when something stayed behind. The rows that survived are other people's
            // words and the moderation side's record, and a player who cannot tell the difference
            // between "deleted" and "kept on disk" has been told something false.
            if (forgot.anythingKept) {
                messages.sendPlain(sender, "forget-kept", forgot.reportsMarked, forgot.workOrdersMarked)
                messages.sendPlain(sender, "forget-kept-hint")
            }
        }

        override fun complete(sender: CommandSender, args: List<String>): List<String> =
            // One argument, and it is their own name: suggesting anything else would be the plugin
            // typing a forget into somebody else's hands.
            if (args.size == 1) (sender as? Player)?.name?.let { listOf(it) } ?: emptyList() else emptyList()
    })
}