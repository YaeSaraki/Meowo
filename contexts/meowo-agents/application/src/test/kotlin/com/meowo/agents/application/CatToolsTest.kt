package com.meowo.agents.application

import com.meowo.agents.domain.CatRegistry
import com.meowo.agents.domain.CatTurn
import com.meowo.agents.domain.Redaction
import com.meowo.agents.domain.ToolAnswer
import com.meowo.agents.domain.ToolContext
import com.meowo.platform.BalanceReader
import com.meowo.platform.HomeEntry
import com.meowo.platform.HomeReader
import com.meowo.platform.SettlementReader
import com.meowo.api.TeleportCharge
import com.meowo.api.ChargeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class CatToolsTest {

    private val alice = UUID.randomUUID()
    private val econ = CatRegistry.byId("economy")!!
    private val home = CatRegistry.byId("home")!!
    private val money = CatTools.Money { "$it" }

    private class Readers(
        payer: UUID,
        balance: BigDecimal = BigDecimal("100.00"),
        treasury: BigDecimal = BigDecimal("5000.00"),
        fee: BigDecimal = BigDecimal("5.00"),
        homes: List<HomeEntry> = emptyList(),
        capacity: Int = 5,
    ) {
        var balanceValue = balance
        var treasuryValue = treasury
        var feeValue = fee
        var homeEntries = homes
        var capacityValue = capacity

        /** Held back so a test can make a lookup that never lands. */
        var neverCompletes = false

        private fun <T> value(now: T): CompletionStage<T> =
            if (neverCompletes) CompletableFuture() else CompletableFuture.completedFuture(now)

        val balances = object : BalanceReader {
            override fun balance(player: UUID) = value(balanceValue)
        }

        val settlements = object : SettlementReader {
            override fun teleportFee(player: UUID) = value(feeValue)

            override fun treasury() = value(treasuryValue)

            override fun chargeStatus(ticket: UUID): CompletionStage<TeleportCharge?> =
                value(TeleportCharge(ticket, payer, BigDecimal("5.00"), ChargeState.PENDING))
        }

        val homes = object : HomeReader {
            override fun capacity(owner: UUID) = capacityValue

            override fun entries(owner: UUID) = homeEntries
        }
    }

    private fun ctx(allowed: Boolean = true) = ToolContext(
        asker = alice, cat = econ, askerIsAdmin = false,
        disclose = { if (allowed) Redaction.Verdict.Allowed(Redaction.Scope.SELF) else Redaction.Verdict.Refused(Redaction.PRIVATE_REFUSAL) },
    )

    /** A context that resolves names, and a disclosure rule that only lets an admin look at others. */
    private fun disciplineCtx(
        asker: UUID,
        known: Map<String, UUID>,
        askerIsAdmin: Boolean = false,
    ) = ToolContext(
        asker = asker,
        cat = CatRegistry.byId("system")!!,
        askerIsAdmin = askerIsAdmin,
        disclose = { subject ->
            when {
                subject == asker -> Redaction.Verdict.Allowed(Redaction.Scope.SELF)
                askerIsAdmin -> Redaction.Verdict.Allowed(Redaction.Scope.FULL)
                else -> Redaction.Verdict.Refused(Redaction.NOT_ALLOWED_REFUSAL)
            }
        },
        lookupPlayer = { name -> known[name] },
    )

    private fun disciplineTools(
        known: Map<String, UUID>,
        risk: (UUID) -> Int = { 42 },
        history: (UUID?, Int) -> List<com.meowo.api.chat.ChatMessageView> = { _, _ ->
            listOf(
                com.meowo.api.chat.ChatMessageView(alice, "private", "他昨天说了句什么", 1L, 0),
            )
        },
    ) = ToolRegistry(
        CatTools.moderation(
            risk = risk,
            history = history,
            participants = { _, _ -> emptyList() },
        ),
    )

    private fun run(registry: ToolRegistry, cat: com.meowo.agents.domain.CatProfile, name: String, allowed: Boolean = true): CatTurn =
        registry.invoke(cat, com.meowo.agents.domain.ToolCall("c1", name), ctx(allowed))

    private fun text(turn: CatTurn): String = when (turn) {
        is CatTurn.Reply -> turn.text
        is CatTurn.Refuse -> turn.text
        is CatTurn.Delegate -> turn.reason
    }

    // ── the guarantee ────────────────────────────────────────────────────────────────────────

    /**
     * The narrowness is not documentation. These tools are built from `BalanceReader` and
     * `SettlementReader`, so `deposit`, `transfer` and the rest are not on any type the tool file
     * can name — this asserts the assembled registry offers nothing that could move money.
     */
    @Test fun `no tool in the economy set can move money`() {
        val names = CatTools.economy(Readers(alice).balances, Readers(alice).settlements, money).names
        assertEquals(
            setOf("economy.balance", "economy.chargeStatus", "economy.dailyStatus", "economy.teleportFee", "economy.treasury"),
            names,
        )
        for (write in listOf("deposit", "withdraw", "setBalance", "transfer", "claimDaily", "reserveTax", "settleTeleport")) {
            assertTrue(names.none { it.contains(write, ignoreCase = true) }, "$write must not be callable")
        }
    }

    @Test fun `a daily claim is refused rather than performed`() {
        val readers = Readers(alice)
        val registry = CatTools.economy(readers.balances, readers.settlements, money)
        val turn = run(registry, econ, "economy.dailyStatus")
        assertTrue(turn is CatTurn.Refuse, "claiming for a player must never be attempted: $turn")
        assertTrue(text(turn).contains("/eco daily"), "the cat should point at the command instead")
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────────

    @Test fun `a balance is read and rendered through the money formatter`() {
        val readers = Readers(alice, balance = BigDecimal("1234.50"))
        val registry = CatTools.economy(readers.balances, readers.settlements, CatTools.Money { "$it 元" })
        assertEquals("1234.50 元", text(run(registry, econ, "economy.balance")))
    }

    @Test fun `a treasury lookup still asks the disclosure gate`() {
        val readers = Readers(alice)
        val registry = CatTools.economy(readers.balances, readers.settlements, money)
        assertEquals("5000.00", text(run(registry, econ, "economy.treasury")))
        val refused = run(registry, econ, "economy.treasury", allowed = false)
        assertTrue(refused is CatTurn.Refuse, "the gate must be consulted, not assumed: $refused")
    }

    @Test fun `a player with no homes is told so rather than given an empty list`() {
        val registry = CatTools.home(Readers(alice, homes = emptyList()).homes)
        assertEquals("你还没有家。", text(run(registry, home, "home.list")))
    }

    @Test fun `homes are listed with a place, not just a name`() {
        val readers = Readers(
            alice,
            homes = listOf(
                HomeEntry("农场", "world", 1.9, 64.0, -3.2),
                HomeEntry("矿洞", "nether", 10.0, 31.0, 20.0),
            ),
        )
        val out = text(run(CatTools.home(readers.homes), home, "home.list"))
        assertTrue(out.contains("农场（world 1, 64, -3）"), out)
        assertTrue(out.contains("矿洞（nether 10, 31, 20）"), out)
    }

    @Test fun `capacity is answered`() {
        val readers = Readers(alice, capacity = 7)
        assertEquals("你最多能放 7 个家。", text(run(CatTools.home(readers.homes), home, "home.capacity")))
    }

    // ── bounded waiting ──────────────────────────────────────────────────────────────────────

    /**
     * A database that does not answer must not hold a pool thread open indefinitely, and it must
     * not be reported as a number. Both matter: the first is a resource leak, the second is a lie.
     */
    @Test fun `a lookup that never lands refuses instead of blocking forever`() {
        val readers = Readers(alice).apply { neverCompletes = true }
        val registry = CatTools.economy(readers.balances, readers.settlements, money)
        val started = System.nanoTime()
        val turn = run(registry, econ, "economy.balance")
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(turn is CatTurn.Refuse, "got $turn")
        assertTrue(elapsed < CatTools.WAIT_MILLIS + 2_000, "waited ${elapsed}ms; the wait is not bounded")
    }

    @Test fun `a failed lookup is not rendered as a number`() {
        val broken = object : BalanceReader {
            override fun balance(player: UUID): CompletionStage<BigDecimal> =
                CompletableFuture.failedFuture(IllegalStateException("database is gone"))
        }
        val registry = CatTools.economy(broken, Readers(alice).settlements, money)
        val turn = run(registry, econ, "economy.balance")
        assertTrue(turn is CatTurn.Refuse, "got $turn")
        assertFalse(text(turn).contains("0"), "a failure must not become a balance of zero")
    }

    // ── arguments ────────────────────────────────────────────────────────────────────────────

    @Test fun `an unreadable ticket is refused rather than looked up`() {
        val registry = CatTools.economy(Readers(alice).balances, Readers(alice).settlements, money)
        val turn = registry.invoke(econ, com.meowo.agents.domain.ToolCall("c1", "economy.chargeStatus", """{"ticket":"not-a-uuid"}"""), ctx())
        assertTrue(turn is CatTurn.Refuse, "got $turn")
    }

    @Test fun `a tool a cat does not own is refused before it is looked up`() {
        val registry = CatTools.economy(Readers(alice).balances, Readers(alice).settlements, money)
        val turn = registry.invoke(home, com.meowo.agents.domain.ToolCall("c1", "economy.balance"), ctx())
        assertTrue(turn is CatTurn.Refuse)
    }

    // ── the discipline tools and other people's records ──────────────────────────────────────

    /**
     * The gate is part of the tool, not an accident of its dependencies.
     *
     * `moderation.risk` used to skip the disclosure check entirely, and that was safe only because
     * the name resolver was missing — so every named lookup failed with "我不认识这个人" and the
     * tool could never return anybody's score. Wiring the resolver in is what makes the tool work,
     * and working it without the gate would have handed every player the ability to ask the cat for
     * any other player's risk score. The two changes had to land together.
     */
    @Test fun `an ordinary player cannot ask for somebody else's risk score`() {
        val bob = UUID.randomUUID()
        val tools = disciplineTools(mapOf("Bob" to bob))
        val turn = tools.invoke(
            CatRegistry.byId("system")!!,
            com.meowo.agents.domain.ToolCall("c1", "moderation.risk", """{"player":"Bob"}"""),
            disciplineCtx(alice, mapOf("Bob" to bob)),
        )
        assertTrue(turn is CatTurn.Refuse, "a risk score about a third party is refused: $turn")
        assertEquals(Redaction.NOT_ALLOWED_REFUSAL, text(turn))
    }

    @Test fun `an admin can ask for somebody else's risk score`() {
        val bob = UUID.randomUUID()
        val tools = disciplineTools(mapOf("Bob" to bob))
        val turn = tools.invoke(
            CatRegistry.byId("system")!!,
            com.meowo.agents.domain.ToolCall("c1", "moderation.risk", """{"player":"Bob"}"""),
            disciplineCtx(alice, mapOf("Bob" to bob), askerIsAdmin = true),
        )
        assertTrue(turn is CatTurn.Reply, "an admin is the person this tool is for: $turn")
        assertTrue(text(turn).contains("42"), text(turn))
    }

    /** Asking about yourself is never gated: it is your own record. */
    @Test fun `your own risk score is always available to you`() {
        val tools = disciplineTools(mapOf("Alice" to alice), risk = { if (it == alice) 7 else 99 })
        val turn = tools.invoke(
            CatRegistry.byId("system")!!,
            com.meowo.agents.domain.ToolCall("c1", "moderation.risk", """{"player":"Alice"}"""),
            disciplineCtx(alice, mapOf("Alice" to alice)),
        )
        assertTrue(text(turn).contains("7"), text(turn))
    }

    /** A name that belongs to nobody is refused, never guessed at. */
    @Test fun `a risk lookup for a name nobody has is refused`() {
        val tools = disciplineTools(emptyMap())
        val turn = tools.invoke(
            CatRegistry.byId("system")!!,
            com.meowo.agents.domain.ToolCall("c1", "moderation.risk", """{"player":"Ghost"}"""),
            disciplineCtx(alice, emptyMap(), askerIsAdmin = true),
        )
        assertTrue(turn is CatTurn.Refuse, turn.toString())
    }

    /** No name means "my own history", and that path is not somebody else's data. */
    @Test fun `asking for your own history needs no name and no gate`() {
        val tools = disciplineTools(emptyMap())
        val turn = tools.invoke(
            CatRegistry.byId("system")!!,
            com.meowo.agents.domain.ToolCall("c1", "moderation.history", "{}"),
            disciplineCtx(alice, emptyMap()),
        )
        assertTrue(turn is CatTurn.Reply, "your own history is yours: $turn")
    }

    /** Naming somebody else is the surveillance case, and it is refused. */
    @Test fun `reading another player's chat history is refused for an ordinary player`() {
        val bob = UUID.randomUUID()
        val tools = disciplineTools(mapOf("Bob" to bob))
        val turn = tools.invoke(
            CatRegistry.byId("system")!!,
            com.meowo.agents.domain.ToolCall("c1", "moderation.history", """{"player":"Bob"}"""),
            disciplineCtx(alice, mapOf("Bob" to bob)),
        )
        assertTrue(turn is CatTurn.Refuse, "a copy of somebody else's chat is not public: $turn")
    }

    // ── the command list ─────────────────────────────────────────────────────────────────────

    /**
     * The permission node is what an ordinary player must not see.
     *
     * There was a filter in front of this list whose `|| true` made it a no-op while reading like
     * a permission check, so the tests below are the only thing that can catch its removal now.
     */
    @Test fun `the command list shows permission nodes only to an admin`() {
        val tool = ToolRegistry(
            listOf(CatTools.commands({ listOf(CommandInfo("meo", "meowo.admin"), CommandInfo("sit", "meowo.sit")) })),
        )
        val plain = ToolContext(
            asker = alice, cat = CatRegistry.byId("system")!!, askerIsAdmin = false,
            disclose = { Redaction.Verdict.Allowed(Redaction.Scope.SELF) },
        )
        val admin = plain.copy(askerIsAdmin = true)
        val asPlayer = text(tool.invoke(CatRegistry.byId("system")!!, com.meowo.agents.domain.ToolCall("c1", "commands.list"), plain))
        val asAdmin = text(tool.invoke(CatRegistry.byId("system")!!, com.meowo.agents.domain.ToolCall("c1", "commands.list"), admin))
        assertTrue(asPlayer.contains("/meo") && asPlayer.contains("/sit"), asPlayer)
        assertFalse(asPlayer.contains("meowo.admin"), "a player must not be shown permission nodes: $asPlayer")
        assertTrue(asAdmin.contains("meowo.admin"), "an admin is told what to ask for: $asAdmin")
    }
}
