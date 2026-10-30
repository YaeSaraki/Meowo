package com.meowo.agents.application

import com.google.gson.JsonObject
import com.meowo.api.chat.ChatMessageView
import com.meowo.api.chat.ParticipantInfo
import com.meowo.agents.domain.Redaction
import com.meowo.agents.domain.ToolAnswer
import com.meowo.agents.domain.ToolContext
import com.meowo.agents.domain.ToolRefusal
import com.meowo.agents.domain.plain
import com.meowo.platform.BalanceReader
import com.meowo.platform.HomeReader
import com.meowo.platform.SettlementReader
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * The read-only tools, assembled from the narrowed views in `com.meowo.platform`.
 *
 * ## What this file cannot do
 *
 * Every dependency below is a `BalanceReader`, `SettlementReader` or `HomeReader` — never an
 * `EconomyAPI` or a `SettlementAPI`. That is the point of the projection: the write methods are not
 * on any type this file can name, so no amount of editing here can produce a cat that moves money.
 * It is a property of the imports, not of a review checklist.
 *
 * ## Waiting is bounded, and happens on the agent pool
 *
 * The readers are `CompletionStage`-shaped, so a tool has to wait for its answer. That wait is a
 * bounded one, taken on a thread from the dedicated agent pool — never a tick thread, which is the
 * actual red line. A database read is expected to land in well under [WAIT_MILLIS]; when it does
 * not, the tool refuses and the model is free to try something else rather than a thread being held
 * open indefinitely on behalf of a chat message.
 */
object CatTools {

    /** How a value is rendered into a sentence a cat can say. */
    fun interface Money {
        fun render(amount: BigDecimal): String
    }

    /** Long enough for a wallet read, short enough that a stuck database cannot pile up threads. */
    const val WAIT_MILLIS = 2_000L


    /**
     * A string argument, or null when it is absent or empty.
     *
     * A malformed value reads as absent rather than throwing, so a model that sends a number where
     * a name was asked for gets "我不认识这个人" rather than a tool failure.
     */
    private fun JsonObject.string(name: String): String? =
        runCatching { get(name)?.asString }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * The schema a tool is told about, as **JSON text**.
     *
     * Text, because the domain's tool contract carries the schema as text and the domain must not
     * know what a JSON library is. The codec parses it once on the way out.
     */
    private fun objectSchema(vararg required: String): String = JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject())
        add("required", com.google.gson.JsonArray().apply { required.forEach { add(it) } })
    }.toString()

    /**
     * Waits a bounded time for a stage and renders it.
     *
     * Three outcomes, all distinguished: a value, a refusal because the wait ran out, and a refusal
     * because the stage failed. A failure is never rendered as a number — an economy lookup that
     * blew up must not be reported to a player as "0".
     */
    private fun <T> render(future: CompletionStage<T>, money: (T) -> String): ToolAnswer = try {
        val done = future.toCompletableFuture().get(WAIT_MILLIS, TimeUnit.MILLISECONDS)
        plain(money(done))
    } catch (timeout: java.util.concurrent.TimeoutException) {
        ToolAnswer.Refused(ToolRefusal.PENDING)
    } catch (failure: Exception) {
        ToolAnswer.Refused(ToolRefusal.FAILED)
    }

    // ── economy ──────────────────────────────────────────────────────────────────────────────

    fun balance(reader: BalanceReader, money: Money): ReadTool =
        readTool("economy.balance", "查这个玩家自己的余额。只读。", objectSchema()) { _, ctx ->
            render(reader.balance(ctx.asker)) { money.render(it) }
        }

    fun treasury(reader: SettlementReader, money: Money): ReadTool =
        readTool("economy.treasury", "查国库总额。只读。", objectSchema()) { _, ctx ->
            // The treasury is a fact about the server rather than about a person, so there is no
            // subject to disclose — but the gate is still consulted rather than assumed away.
            when (val verdict = ctx.disclose(ctx.asker)) {
                is Redaction.Verdict.Refused -> ToolAnswer.Refused(verdict.reason)
                is Redaction.Verdict.Allowed -> render(reader.treasury()) { money.render(it) }
            }
        }

    /**
     * The fee the *asker* would pay to teleport right now.
     *
     * There is no `target` argument on purpose: `SettlementReader.teleportFee` prices a journey for
     * one player, and accepting a destination it cannot price would invite the model to answer
     * "to the village costs 5" for a village it never looked at.
     */
    fun teleportFee(reader: SettlementReader, money: Money): ReadTool =
        readTool("economy.teleportFee", "查这个玩家现在传送一次要多少钱。只读。", objectSchema()) { _, ctx ->
            render(reader.teleportFee(ctx.asker)) { money.render(it) }
        }

    fun chargeStatus(reader: SettlementReader, money: Money): ReadTool =
        readTool("economy.chargeStatus", "查一张票的扣费状态。只读。", objectSchema("ticket")) { args, _ ->
            val ticket = runCatching { UUID.fromString(args.get("ticket")?.asString ?: "") }.getOrNull()
                ?: return@readTool ToolAnswer.Refused(ToolRefusal.BAD_TICKET)
            render(reader.chargeStatus(ticket)) { charge ->
                if (charge == null) "这张票已经不在了。" else "这张票扣了 ${money.render(charge.fee)}，状态 ${charge.state}。"
            }
        }

    /**
     * Deliberately a refusal.
     *
     * `claimDaily` is one of the ten write methods and is not on [SettlementReader], so a cat
     * cannot claim for a player however it phrases the request. The tool exists only so the model
     * has somewhere honest to send that question instead of inventing a result.
     */
    fun dailyStatus(): ReadTool = readTool(
        "economy.dailyStatus",
        "查询签到状态。**不能代替玩家领取**——领取只能玩家自己执行。",
        objectSchema(),
    ) { _, _ -> ToolAnswer.Refused(ToolRefusal.DAILY_IS_YOURS) }

    // ── 风纪委员（MeowCat）────────────────────────────────────────────────────────────────────

    /**
     * The three things the discipline cat is allowed to look at.
     *
     * **None of them is another player's identity.** `moderation.risk` is a number about a player
     * the *asker* is already allowed to ask about, `history` reads the audit buffer, and
     * `participants` lists who is addressable. What is deliberately absent is any way to turn
     * "this person is risky" into "this person is bad" — the tools report a score and the records
     * behind it, and the wording below never renders either as a verdict.
     *
     * ## Every subject is gated, and that had to be written
     *
     * `moderation.risk` and `moderation.history` both take a player's name, and for a long time
     * neither asked [ToolContext.disclose] — it was safe to skip only because the resolver was
     * missing, so **every** named lookup failed and the tool always said "我不认识这个人". Wiring
     * the resolver in, which is what makes these two tools work at all, would have turned them from
     * useless into "ask the cat for anybody's risk score". So the gate is now part of the tool
     * rather than an assumption about its dependencies.
     *
     * @param history trimmed to a few lines on purpose. A discipline cat that can page through
     *   everything anybody said is one report away from being a surveillance tool.
     */
    fun moderation(
        risk: (UUID) -> Int,
        history: (UUID?, Int) -> List<ChatMessageView>,
        participants: (String, Int) -> List<ParticipantInfo>,
    ): List<ReadTool> = listOf(
        readTool("moderation.risk", "查一个玩家的风纪风险分。只读。", objectSchema("player")) { args, ctx ->
            val name = args.string("player")
            val target = resolve(name, ctx)
                ?: return@readTool ToolAnswer.Refused(ToolRefusal.UNKNOWN_PLAYER)
            // Self is always allowed, so this only ever refuses a *third* party.
            when (val verdict = ctx.disclose(target)) {
                is Redaction.Verdict.Refused -> ToolAnswer.Refused(verdict.reason)
                is Redaction.Verdict.Allowed -> plain("风纪风险分：${risk(target)}（满分 100，只是一个记分，不是判决）")
            }
        },
        readTool(
            "moderation.history",
            "查最近的聊天记录。只读，且只回最近几条。",
            objectSchema("player"),
        ) { args, ctx ->
            val name = args.string("player")
            val target = if (name == null) ctx.asker else resolve(name, ctx)
            // No name means "my own", and that path has no gate: it is the asker's own history.
            if (name != null) {
                when (val verdict = ctx.disclose(target ?: ctx.asker)) {
                    is Redaction.Verdict.Refused -> return@readTool ToolAnswer.Refused(verdict.reason)
                    is Redaction.Verdict.Allowed -> Unit
                }
            }
            val rows = history(target, HISTORY_LINES)
            if (rows.isEmpty()) return@readTool plain("最近没有记录。")
            // The raw line is shown because a moderator who cannot see what was said cannot judge
            // it, and this buffer is the only record a deleted line leaves. It is never paraphrased.
            plain(
                rows.joinToString("\n") { "· [${it.channel}] ${it.rawText}" } +
                    "\n（共 ${rows.size} 条）",
            )
        },
        readTool("chat.participants", "列出现在能私聊的人。只读。", objectSchema("prefix")) { args, _ ->
            val rows = participants(args.string("prefix").orEmpty(), PARTICIPANT_LIMIT)
            if (rows.isEmpty()) return@readTool plain("现在没有别人在线。")
            plain(
                rows.joinToString("；") { p ->
                    if (p.kind == "SYSTEM") "${p.handle}（猫，在线）" else "${p.handle}（在线）"
                },
            )
        },
    )

    /**
     * Resolves a name the model typed into a UUID.
     *
     * Refuses rather than guessing: answering about the wrong player is the one mistake a
     * discipline tool must not make, and a model that has the wrong name in hand cannot be talked
     * out of it.
     */
    private fun resolve(name: String?, ctx: ToolContext): UUID? =
        name?.trim()?.takeIf { it.isNotBlank() }?.let { ctx.lookupPlayer(it) }

    private const val HISTORY_LINES = 5
    private const val PARTICIPANT_LIMIT = 12

    // ── general ──────────────────────────────────────────────────────────────────────────────

    /**
     * What commands this server actually has, with their permissions.
     *
     * MeowCat is the 总务; "有什么指令" is the single most natural thing to ask it, and answering
     * that with "I cannot" because a tool was never written is a bad first impression. The list
     * comes from [com.meowo.platform.CommandRouter]'s own registrations, so it cannot drift from
     * what the server really has.
     *
     * **Not an admin surface, and the gate is the rendering.** A permission node is shown so the
     * player knows what to ask for; a player cannot gain it by asking a cat.
     *
     * There used to be a filter in front of the list — `ctx.askerIsAdmin || !isAdmin(...) || true` —
     * whose `|| true` made it a no-op while looking exactly like a permission check. That is the
     * worst of both worlds: a reader would take the code's word for a protection that did not exist,
     * and the dead `isAdmin` lambda would keep looking like one in review. The decision is made per
     * item below, from [ToolContext.askerIsAdmin], which is the one value the tool actually has.
     */
    fun commands(known: () -> List<CommandInfo>): ReadTool =
        readTool("commands.list", "列出本服有的指令，以及各自需要什么权限。只读。", objectSchema()) { _, ctx ->
            val all = known()
            if (all.isEmpty()) return@readTool plain("现在没有登记任何指令。")
            plain(
                all.joinToString("；") { c ->
                    if (ctx.askerIsAdmin) "/${c.name}（${c.permission}）" else "/${c.name}"
                },
            )
        }

    // ── home ─────────────────────────────────────────────────────────────────────────────────

    fun homeCapacity(reader: HomeReader): ReadTool =
        readTool("home.capacity", "查这个玩家能同时拥有几个家。只读。", objectSchema()) { _, ctx ->
            plain("你最多能放 ${reader.capacity(ctx.asker)} 个家。")
        }

    fun homeList(reader: HomeReader): ReadTool =
        readTool("home.list", "列出这个玩家自己家的名字和坐标。只读。", objectSchema()) { _, ctx ->
            val homes = reader.entries(ctx.asker)
            if (homes.isEmpty()) plain("你还没有家。")
            else plain(
                homes.joinToString("；") {
                    "${it.name}（${it.world} ${it.x.toInt()}, ${it.y.toInt()}, ${it.z.toInt()}）"
                },
            )
        }

    // ── the assembles ────────────────────────────────────────────────────────────────────────

    fun economy(balance: BalanceReader, settlement: SettlementReader, money: Money) = ToolRegistry(
        listOf(
            balance(balance, money),
            treasury(settlement, money),
            teleportFee(settlement, money),
            dailyStatus(),
            chargeStatus(settlement, money),
        ),
    )

    /**
     * The settlement half on its own.
     *
     * [economy] needs both a wallet and a settlement, so a server running teleport fees without
     * wallets would otherwise get no economy tools at all. Splitting the assemble keeps each half
     * usable on its own; a cat is only offered the tools its profile actually names.
     */
    fun settlementOnly(settlement: SettlementReader, money: Money) = ToolRegistry(
        listOf(
            treasury(settlement, money),
            teleportFee(settlement, money),
            dailyStatus(),
            chargeStatus(settlement, money),
        ),
    )

    fun home(reader: HomeReader) = ToolRegistry(listOf(homeCapacity(reader), homeList(reader)))
}

/** One registered command, as a cat is allowed to describe it. */
data class CommandInfo(val name: String, val permission: String)
