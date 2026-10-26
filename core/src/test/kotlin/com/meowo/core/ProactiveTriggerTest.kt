package com.meowo.core

import com.meowo.chat.ChatContext
import com.meowo.chat.ChatSettings
import com.meowo.agents.AgentsContext
import com.meowo.agents.AgentsSettings
import com.meowo.platform.CommandRouter
import com.meowo.platform.Messages
import com.meowo.platform.PlatformHost
import com.meowo.platform.Route
import com.meowo.shared.event.EventBus
import com.meowo.shared.lifecycle.ContextManager
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.Locale
import java.util.UUID

/**
 * The proactive feature, end to end: a player runs a command, and the cat that owns it may speak up.
 *
 * Everything else about this feature could be tested in a unit test and still be **dead** — which is
 * exactly how it spent its life until now. `offerProactive` was complete, tested in the abstract and
 * called by nobody; a mutation that disabled the whole trigger (`if (true) return`) left every test
 * in the repository green. This file is the one that would have caught it.
 *
 * The assertions are about **attempts**, not about a model having answered. There is no LLM behind
 * this fixture, and that is deliberate: "did the cat decide to speak" and "did the model reply" are
 * different questions, and only the first one is about this plugin.
 *
 * The 5% roll is driven from both sides — `roll` is forced to `0.0` (always) or `1.0` (never) — so
 * the test never depends on luck, which is the same rule `Guard.proactive`'s own tests follow.
 */
class ProactiveTriggerTest {

    open class TestPlugin : JavaPlugin()

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var host: PlatformHost
    private lateinit var contexts: ContextManager
    private lateinit var player: PlayerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPlugin::class.java)
        val messages = Messages(plugin).apply { load("zh_CN") }
        host = PlatformHost(plugin, EventBus { _, e -> throw e }, CommandRouter(messages), messages)
        contexts = ContextManager { id, e -> server.logger.log(java.util.logging.Level.SEVERE, "$id failed", e) }
        contexts.enable(ChatContext(host, ChatSettings()))
        player = server.addPlayer("meov0")
        player.setLocale(Locale.SIMPLIFIED_CHINESE)
        for (perm in listOf("meowo.use", "meowo.chat.use")) player.addAttachment(plugin, perm, true)
    }

    @AfterEach
    fun tearDown() {
        contexts.close()
        MockBukkit.unmock()
    }

    private fun agents(proactive: Boolean, dailyCap: Int = 500) =
        AgentsContext(host, AgentsSettings(proactive = proactive, dailyCap = dailyCap)) { "Tester" }

    /**
     * A route owned by `economy`, which is also a cat's id — so `/meo eco` reaches EconCat.
     *
     * Registered **with an owner** rather than through the two-argument overload: a route nobody
     * declared belongs to is deliberately unowned, and the trigger must ignore it rather than guess.
     */
    private fun economyRoute() {
        host.commands.register("eco", "economy", object : Route {
            override val permission: String = "meowo.use"
            override fun execute(sender: CommandSender, args: List<String>) = Unit
        })
    }

    private fun runCommand(vararg args: String) {
        host.commands.onCommand(player, object : Command("meowo") {
            override fun execute(sender: CommandSender, label: String, args: Array<out String>) = true
        }, "meowo", args)
        server.scheduler.performTicks(3)
    }

    // ── the feature is off by default ─────────────────────────────────────────────────────────

    /**
     * The default matters more than the feature.
     *
     * A server that did not ask for unsolicited chatter must not get any, so the switch defaults off
     * and this pins it there. A plugin that greets players unasked is uninstalled the same day.
     */
    @Test fun `a server that did not ask for chatter gets none`() {
        val context = agents(proactive = false)
        contexts.enable(context)
        economyRoute()

        runCommand("eco", "balance")

        assertEquals(0, context.proactiveOffers.get(), "a cat spoke without being asked to")
    }

    // ── it fires ─────────────────────────────────────────────────────────────────────────────

    @Test fun `using a cat's own command lets that cat start a conversation`() {
        val context = agents(proactive = true)
        contexts.enable(context)
        economyRoute()
        forceRoll(context, always = true)

        runCommand("eco", "balance")

        assertEquals(
            1, context.proactiveOffers.get(),
            "EconCat owns `eco` and the player used it; nothing happened",
        )
    }

    /** The roll is a real gate: a cat that rolled "no" stays quiet even on its own command. */
    @Test fun `the 5 percent roll can still say no`() {
        val context = agents(proactive = true)
        contexts.enable(context)
        economyRoute()
        forceRoll(context, always = false)

        runCommand("eco", "balance")

        assertEquals(0, context.proactiveOffers.get(), "the roll did not stop it")
    }

    /**
     * **This is the resource condition the feature was specified with**: the greeting may only go out
     * while the day's proactive share has something left in it.
     *
     * `dailyCap = 0` is the honest way to say "no calls today" — not a mock, not a stub, the same
     * arithmetic a real empty allowance goes through.
     */
    @Test fun `no allowance means no greeting`() {
        val context = agents(proactive = true, dailyCap = 0)
        contexts.enable(context)
        economyRoute()
        forceRoll(context, always = true)

        runCommand("eco", "balance")

        assertEquals(0, context.proactiveOffers.get(), "a cat spent a call on an empty day")
    }

    /** A route nobody declared an owner for is not anybody's business. */
    @Test fun `an unowned route does not make any cat speak`() {
        val context = agents(proactive = true)
        contexts.enable(context)
        forceRoll(context, always = true)
        host.commands.register("orphan", object : Route {
            override val permission: String = "meowo.use"
            override fun execute(sender: CommandSender, args: List<String>) = Unit
        })

        runCommand("orphan")

        assertEquals(0, context.proactiveOffers.get(), "an unowned command guessed at an owner")
    }

    /** Only the five data-backed cats speak; the other seven would be talking without facts. */
    @Test fun `a context with no cat behind it stays quiet`() {
        val context = agents(proactive = true)
        contexts.enable(context)
        forceRoll(context, always = true)
        host.commands.register("sit", "sit", object : Route {
            override val permission: String = "meowo.use"
            override fun execute(sender: CommandSender, args: List<String>) = Unit
        })

        runCommand("sit")

        assertEquals(0, context.proactiveOffers.get(), "a cat with no tools guessed at a command")
    }

    /**
     * The console is not somebody to talk to. A cat addressing it would put a private line in the
     * server log, which reads as the server talking to itself.
     */
    @Test fun `the console never gets a greeting`() {
        val context = agents(proactive = true)
        contexts.enable(context)
        economyRoute()
        forceRoll(context, always = true)

        host.commands.onCommand(
            server.consoleSender, object : Command("meowo") {
                override fun execute(sender: CommandSender, label: String, args: Array<out String>) = true
            }, "meowo", arrayOf("eco", "balance"),
        )

        assertEquals(0, context.proactiveOffers.get(), "a cat addressed the console")
    }

    /** A route the player may not use did not run, so no cat reacts to it. */
    @Test fun `a command refused on permission does not make a cat speak`() {
        val context = agents(proactive = true)
        contexts.enable(context)
        forceRoll(context, always = true)
        host.commands.register("tpa", "teleport", object : Route {
            override val permission: String = "meowo.teleport.tpa"
            override fun execute(sender: CommandSender, args: List<String>) = Unit
        })

        runCommand("tpa", "Alice")

        assertEquals(0, context.proactiveOffers.get(), "a cat reacted to a command that never ran")
    }

    private fun forceRoll(context: AgentsContext, always: Boolean) {
        val field = AgentsContext::class.java.getDeclaredField("roll")
        field.isAccessible = true
        field.set(context, { if (always) 0.0 else 1.0 })
    }
}