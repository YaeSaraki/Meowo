package com.meowo.core

import com.meowo.agents.AgentsContext
import com.meowo.agents.AgentsSettings
import com.meowo.api.chat.ChatModerationAPI
import com.meowo.api.chat.SystemSpeakerSink
import com.meowo.chat.ChatContext
import com.meowo.chat.ChatSettings
import com.meowo.platform.Messages
import com.meowo.platform.PlatformHost
import com.meowo.shared.event.EventBus
import com.meowo.shared.lifecycle.ContextManager
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID

/**
 * The assembly, exercised on a real (mock) server.
 *
 * Everything below the wiring is already unit tested. What this file covers is the part that only
 * exists once Bukkit is involved: that the context starts with no provider configured, that it
 * takes a speaker from the chat context, that a question with no model behind it comes back as a
 * refusal rather than a hang, and — the one that actually matters operationally — that closing it
 * hands the speaker **back** rather than leaving the cats mute.
 */
class AgentsAssemblyTest {

    open class TestPlugin : JavaPlugin()

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var host: PlatformHost
    private lateinit var contexts: ContextManager
    private lateinit var player: org.mockbukkit.mockbukkit.entity.PlayerMock
    private var chatContext: ChatContext? = null

    /**
     * A sink that records what it was asked.
     *
     * The point of it is that "who is holding this cat" has to be **observable**. The directory
     * cannot answer that — it lists every speaker the plugin knows about, whoever holds them — and
     * so a test that asked the directory was asking the wrong question.
     */
    private class Recording : SystemSpeakerSink {
        val asked = mutableListOf<String>()
        override fun onDirectMessage(sender: UUID, speakerId: String, text: String): Boolean {
            asked += text
            return true
        }
    }

    /** The command a player actually types, so the whole route is exercised. */
    private fun whisper(speaker: String) {
        host.commands.onCommand(player, ROOT, "meo", arrayOf("msg", speaker, "我还有多少钱？"))
        server.scheduler.performTicks(3)
    }

    /**
     * Minimal economy and settlement so the fixture is a server *with* those contexts.
     *
     * Without them every economy tool is missing for a different reason — the context that would
     * serve it is not running — and the test could not tell "nobody implemented this" from "you
     * turned the economy off", which are very different things to tell an operator.
     */
    /** Every write path on this stub fails, on purpose: nothing here may ever be reachable. */
    private fun <T> no(): java.util.concurrent.CompletionStage<T> =
        java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("write path must be unreachable"))

    private fun registerServices() {
        server.servicesManager.register(
            com.meowo.api.EconomyAPI::class.java,
            object : com.meowo.api.EconomyAPI {
                override fun balance(player: UUID) = java.util.concurrent.CompletableFuture.completedFuture(java.math.BigDecimal("100.00"))
                override fun deposit(player: UUID, amount: java.math.BigDecimal) = java.util.concurrent.CompletableFuture.completedFuture(amount)
                override fun withdraw(player: UUID, amount: java.math.BigDecimal) = java.util.concurrent.CompletableFuture.completedFuture(amount)
                override fun setBalance(player: UUID, amount: java.math.BigDecimal) = java.util.concurrent.CompletableFuture.completedFuture(amount)
            },
            plugin, org.bukkit.plugin.ServicePriority.Normal,
        )
        server.servicesManager.register(
            com.meowo.api.SettlementAPI::class.java,
            object : com.meowo.api.SettlementAPI {
                override fun transfer(from: UUID, to: UUID, amount: java.math.BigDecimal) =
                    no<com.meowo.api.TransferReceipt>()
                override fun teleportFee(player: UUID) = java.util.concurrent.CompletableFuture.completedFuture(java.math.BigDecimal("5.00"))
                override fun reserveTeleport(ticket: UUID, player: UUID) = no<com.meowo.api.TeleportCharge>()
                override fun reserveTeleport(ticket: UUID, requester: UUID, payer: UUID) = no<com.meowo.api.TeleportCharge>()
                override fun settleTeleport(ticket: UUID, success: Boolean) = no<com.meowo.api.TeleportCharge>()
                override fun cancelTeleport(ticket: UUID) = no<Void>()
                override fun claimDaily(player: UUID) = no<java.math.BigDecimal>()
                override fun treasury() = java.util.concurrent.CompletableFuture.completedFuture(java.math.BigDecimal("5000.00"))
                override fun reserveTax(ticket: UUID, player: UUID, assessment: com.meowo.api.TaxAssessment) = no<com.meowo.api.TeleportCharge>()
                override fun chargeStatus(ticket: UUID): java.util.concurrent.CompletionStage<com.meowo.api.TeleportCharge?> =
                    java.util.concurrent.CompletableFuture.completedFuture(null)
            },
            plugin, org.bukkit.plugin.ServicePriority.Normal,
        )
    }

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPlugin::class.java)
        val messages = Messages(plugin).apply { load("zh_CN") }
        host = PlatformHost(plugin, EventBus { _, e -> throw e }, com.meowo.platform.CommandRouter(messages), messages)
        contexts = ContextManager { id, e -> server.logger.log(java.util.logging.Level.SEVERE, "$id failed", e) }
        chatContext = ChatContext(host, ChatSettings()).also { contexts.enable(it) }
        registerServices()
        player = server.addPlayer("Asker").also {
            it.setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
            for (perm in listOf("meowo.use", "meowo.chat.use")) it.addAttachment(plugin, perm, true)
        }
    }

    @AfterEach
    fun tearDown() {
        contexts.close()
        MockBukkit.unmock()
    }

    private fun agents(settings: AgentsSettings = AgentsSettings()) =
        AgentsContext(host, settings) { "Tester" }

    /** With no provider configured the context must still start, not refuse to enable. */
    @Test fun `the context starts with no provider configured`() {
        val context = agents()
        assertTrue(contexts.enable(context), "an unconfigured provider must not stop the context from starting")
        assertTrue(contexts.isEnabled("agents"))
        assertTrue(host.plugin.dataFolder.toPath().resolve("agents").toFile().exists(),
            "the profile database directory is created on start")
    }

    /** The point of `claimSpeaker`: the cats stop being the helpdesk's. */
    @Test fun `the agents take the agent speakers from the chat context`() {
        // The spy holds the cat first, so the agents have something to take it **from** — and so
        // "who is answering" becomes observable.
        //
        // The previous version asked the directory whether it knew a speaker called "economy". The
        // directory lists every cat the plugin has, whoever is holding them, so the answer was
        // yes whether the agents had taken the cat or not.
        val spy = Recording()
        server.servicesManager.load(ChatModerationAPI::class.java)!!.claimSpeaker("economy", spy)
        contexts.enable(agents())

        whisper("EconCat")
        assertTrue(spy.asked.isEmpty(), "the agents must have taken the cat: ${spy.asked}")
    }

    /**
     * Unconfigured means every answer is a short in-character apology.
     *
     * The timing is the part worth pinning: `directMessage` calls the sink on the caller's thread
     * while holding the chat path, so a sink that waited on a model would stall the command. With
     * no provider the whole turn has to come back immediately.
     */
    @Test fun `an unconfigured cat answers immediately rather than hanging`() {
        // The spy holds the cat first, so the agents take it and the spy is out of the picture:
        // otherwise the helpdesk would answer and this would be green for the wrong reason.
        val spy = Recording()
        server.servicesManager.load(ChatModerationAPI::class.java)!!.claimSpeaker("economy", spy)
        contexts.enable(agents())
        drain(player)

        val started = System.nanoTime()
        whisper("EconCat")
        val elapsed = (System.nanoTime() - started) / 1_000_000

        assertTrue(spy.asked.isEmpty(), "the agents hold the cat, so the spy must not have seen it")
        assertTrue(drain(player).isNotEmpty(), "an unconfigured cat still answers")
        assertTrue(elapsed < 1_000, "admission took ${elapsed}ms; it must not wait on a model")
    }

    /** An unknown speaker is refused rather than silently routed somewhere. */
    @Test fun `an unknown speaker is not claimed and is refused`() {
        contexts.enable(agents())
        // Claiming an id no cat has must be refused outright. A claim that quietly succeeded would
        // put a sink in that slot, and the next claim of the same id would silently replace it.
        assertThrows(IllegalStateException::class.java) {
            server.servicesManager.load(ChatModerationAPI::class.java)!!.claimSpeaker(
                "not-a-cat",
                object : SystemSpeakerSink {
                    override fun onDirectMessage(sender: UUID, speakerId: String, text: String) = true
                },
            )
        }
    }

    /**
     * The operational one. `claimSpeaker` restores the previous holder on close, so disabling the
     * agents must hand the cats back to the helpdesk rather than leaving them mute — a reload with
     * the feature off is the most likely way a server ever gets here.
     */
    @Test fun `closing the context hands the speakers back`() {
        val spy = Recording()
        server.servicesManager.load(ChatModerationAPI::class.java)!!.claimSpeaker("economy", spy)
        val context = agents()
        contexts.enable(context)

        // The agents took the cat, so the spy is not being asked.
        whisper("EconCat")
        assertTrue(spy.asked.isEmpty(), "the agents hold the cat while they are enabled")

        context.close()

        // And now the spy is holding it again, which is the whole point: the agents are gone and
        // the cat answers once more.
        //
        // The previous version claimed the id a second time and checked that the returned handle
        // had a non-blank `toString()`. That is true of every object ever made, and `claimSpeaker`
        // *replaces* the holder rather than refusing a second claim — so neither half of it was
        // evidence that anything had been handed back.
        whisper("EconCat")
        assertTrue(spy.asked.isNotEmpty(), "the cat must answer again once the agents are closed")
    }

    /**
     * The mismatch has to be loud.
     *
     * A cat's system prompt is built from its profile, so a cat promised `mine.lastFind` will be
     * told it can do that, will call it, and will be refused — and the player is left staring at a
     * cat that insisted it had a capability and then denied it. Startup says so instead.
     */
    @Test fun `a cat promised tools this build cannot serve is reported at startup`() {
        val seen = mutableListOf<String>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord?) {
                record?.message?.let { seen += it }
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        plugin.logger.addHandler(handler)
        try {
            contexts.enable(agents())
        } finally {
            plugin.logger.removeHandler(handler)
        }
        val unwired = seen.filter { it.contains("cannot serve") }
        assertTrue(
            unwired.any { it.contains("MineCat") || it.contains("TeleCat") || it.contains("MeowCat") },
            "expected a startup warning naming a cat with unwired tools, got: $seen",
        )
        assertTrue(
            unwired.none { it.contains("EconCat") },
            "EconCat is fully wired and must not be reported: $unwired",
        )
    }

    @Test fun `starting twice is a no-op and closing twice is safe`() {
        val context = agents()
        contexts.enable(context)
        context.start()
        assertTrue(contexts.isEnabled("agents"), "a second start must not throw or half-register")
        // The manager owns enable/disable; closing the context directly would leave the manager
        // still believing it is up, which is the state a stray shutdown produces.
        contexts.disable("agents")
        context.close()
        assertFalse(contexts.isEnabled("agents"))
    }

    // ── `/meo known` ─────────────────────────────────────────────────────────────────────────

    /**
     * An impression nobody can read is indistinguishable from one the server does not have.
     *
     * A player who suspects a cat is keeping notes about them has to be able to find out; this is
     * that door. Asserted through the real router so the wiring — route registered, help page able
     * to list it, permission checked — is what is being tested rather than the handler alone.
     */
    @Test fun `the impressions command is registered and listed for someone who may use it`() {
        contexts.enable(agents())
        assertTrue(host.commands.hasRoute("known"), "the route must exist once the context is up")

        // Compared rather than matched on text: the help page renders **labels**, not command names,
        // and a hardcoded string would couple this to one language and to copy changes.
        //
        // The gap is not one line. `meowo.chat.use` also opens `msg` and `mail`, so the difference is
        // every chat route — which is the point: the permission a reader already has is the only
        // thing deciding whether the door is there.
        val permitted = server.addPlayer().also { it.setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
        permitted.addAttachment(plugin, "meowo.chat.use", true)
        val withIt = helpLinesFor(permitted)
        val withoutIt = helpLinesFor(server.addPlayer().also { it.setLocale(java.util.Locale.SIMPLIFIED_CHINESE) })
        assertTrue(withIt.size > withoutIt.size, "the chat routes must appear: $withIt vs $withoutIt")
    }

    /**
     * The command lines on the help page — the `▸` template line, which is not locale data.
     *
     * Walks every page: the list is paged, so one page is no longer the whole help, and a
     * discoverability check that only reads page one would pass or fail for reasons that have
     * nothing to do with whether the command is listed.
     */
    private fun helpLinesFor(player: org.mockbukkit.mockbukkit.entity.PlayerMock): List<String> {
        while (player.nextMessage() != null) { /* discard anything from an earlier command */ }
        val help = com.meowo.platform.HelpPage(host.messages, host.commands)
        val lines = mutableListOf<String>()
        for (page in 1..8) {
            help.send(player, page)
            val onThisPage = generateSequence<String> { player.nextMessage() }.toList()
                .flatMap { message -> message.split("\n") }
                .filter { line -> line.trimStart().startsWith("▸") }
            if (onThisPage.isEmpty()) break
            lines += onThisPage
        }
        return lines
    }

    /** Without the permission it is hidden, which is exactly what the help page filters on. */
    @Test fun `the command is hidden from a player who may not use chat`() {
        contexts.enable(agents())
        val player = server.addPlayer()
        assertFalse(host.commands.isVisible(player, "known"), "no attachment, so no visibility")
    }

    // ── `/meo forget` ───────────────────────────────────────────────────────────────────────

    /**
     * A deletion nobody can find is not a deletion. Same argument as `/meo known`, and the test is
     * the same shape on purpose: the router registers it, the permission decides whether the help
     * page shows it, and nobody has to know the command name to use it.
     */
    @Test fun `the forget command is registered and listed for someone who may use it`() {
        contexts.enable(agents())
        assertTrue(host.commands.hasRoute("forget"), "the route must exist once the context is up")

        val permitted = server.addPlayer().also { it.setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
        permitted.addAttachment(plugin, "meowo.chat.use", true)
        assertTrue(
            helpLinesFor(permitted).size > helpLinesFor(server.addPlayer().also { it.setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }).size,
            "the forget route must appear alongside the other chat routes",
        )
    }

    /**
     * The confirmation is the whole safety property of an irreversible command.
     *
     * Asserted on the outcome rather than on a copy string: the expected text is read back out of
     * the language bundle, so this fails when the behaviour changes and stays green when somebody
     * rewrites the sentence or ships a second locale. It is rendered with the same arguments the
     * command passes, because the template is not the sentence a player sees.
     */
    @Test fun `a name that is not the player's own deletes nothing`() {
        contexts.enable(agents())
        val player = server.addPlayer().also { it.setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
        player.addAttachment(plugin, "meowo.chat.use", true)
        val refusal = host.messages.text(player, "forget-name-mismatch", player.name)

        host.commands.onCommand(player, ROOT, "meo", arrayOf("forget", "SomebodyElse"))

        val said = drain(player)
        assertTrue(said.any { it.contains(refusal) }, "the mismatch must be refused in words: $said")
        assertFalse(said.any { it.contains(host.messages.text(player, "forget-done")) },
            "and nothing may be deleted on the way past: $said")
    }

    /**
     * The complement of the test above, which is the only way to say it without pinning copy: the
     * same player, typing their own name, is not refused.
     */
    @Test fun `typing your own name runs the forget`() {
        contexts.enable(agents())
        val player = server.addPlayer().also { it.setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
        player.addAttachment(plugin, "meowo.chat.use", true)
        val refusal = host.messages.text(player, "forget-name-mismatch", player.name)

        host.commands.onCommand(player, ROOT, "meo", arrayOf("forget", player.name))

        val said = drain(player)
        assertTrue(said.isNotEmpty(), "typing your own name must run the command, not leave it silent")
        assertFalse(said.any { it.contains(refusal) },
            "their own name is the confirmation, so it must not be refused: $said")
    }

    /**
     * Tab completion may only ever offer the player's own name.
     *
     * Completing somebody else's name into a delete box would make this command one careless keypress
     * away from wiping the wrong person — which is exactly the outcome the typed-name confirmation
     * exists to make impossible.
     */
    @Test fun `tab completion suggests nothing but your own name`() {
        contexts.enable(agents())
        val player = server.addPlayer().also { it.setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
        player.addAttachment(plugin, "meowo.chat.use", true)

        val offered = host.commands.onTabComplete(player, ROOT, "meo", arrayOf("forget", ""))
        assertTrue(offered.isEmpty() || offered == listOf(player.name),
            "completing must not type a forget into somebody else's hands: $offered")
    }

    /** The router takes the command object off the server; here it is only passed through. */
    private val ROOT = object : org.bukkit.command.Command("meo") {
        override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<out String>) = true
    }

    /**
     * The promise a player can check, at the level a player sees it.
     *
     * Somebody else's complaint is not deleted — and if the command does not say so, the player has
     * been told their data is gone while their name is still sitting in the one table a moderator
     * would look at. Seeded through a second connection to the live database, because the point is
     * the words on the screen, not the store's return value.
     */
    @Test fun `when something is kept the player is told it was kept`() {
        contexts.enable(agents())
        val player = server.addPlayer().also { it.setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
        player.addAttachment(plugin, "meowo.chat.use", true)
        seedComplaintAbout(player.uniqueId)

        host.commands.onCommand(player, ROOT, "meo", arrayOf("forget", player.name))

        val said = drain(player)
        // Rendered with the arguments the command passes: the template is not what a player sees,
        // and the counts are fixed by the single complaint [seedComplaintAbout] writes.
        assertTrue(said.any { it.contains(host.messages.text(player, "forget-kept", 1, 0)) },
            "a kept complaint has to be announced, not folded into the totals: $said")
        assertTrue(said.any { it.contains(host.messages.text(player, "forget-kept-hint")) },
            "and it has to say what 'kept' means: $said")
    }

    /**
     * And the other direction: with nothing to keep, saying it anyway is noise that teaches players
     * to ignore the line that matters.
     */
    @Test fun `when nothing is kept the player is not told anything was`() {
        contexts.enable(agents())
        val player = server.addPlayer().also { it.setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
        player.addAttachment(plugin, "meowo.chat.use", true)

        host.commands.onCommand(player, ROOT, "meo", arrayOf("forget", player.name))

        assertFalse(drain(player).any { it.contains(host.messages.text(player, "forget-kept")) },
            "there was nothing to keep; announcing it would be crying wolf")
    }

    /** One complaint from another player, written straight into the running store's file. */
    private fun seedComplaintAbout(subject: UUID) {
        com.meowo.agents.infrastructure.PlayerProfileStore(
            plugin.dataFolder.toPath().resolve("agents/profile.db"),
        ).use { store ->
            store.insertReport(
                com.meowo.agents.infrastructure.StoredReport(
                    id = "seed-${UUID.randomUUID()}",
                    author = UUID.randomUUID().toString(),
                    subject = subject.toString(),
                    sentiment = com.meowo.agents.domain.Sentiment.NEGATIVE,
                    reason = "test",
                    atEpochMillis = System.currentTimeMillis(),
                    expiresAt = Long.MAX_VALUE,
                    coScore = 5,
                    trusted = 0,
                    state = "OPEN",
                ),
            )
        }
    }

    /** Everything the player was told, flattened out of the message queue. */
    private fun drain(player: org.mockbukkit.mockbukkit.entity.PlayerMock): List<String> =
        generateSequence<String> { player.nextMessage() }.toList()
            .flatMap { message -> message.split("\n") }
}
