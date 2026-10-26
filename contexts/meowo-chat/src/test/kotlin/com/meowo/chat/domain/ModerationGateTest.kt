package com.meowo.chat.domain

import com.meowo.api.chat.ChatMessageView
import com.meowo.api.chat.ChatModerator
import com.meowo.api.chat.ModerationAction
import com.meowo.api.chat.ModerationVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The moderation gate, and above all the stage ordering.
 *
 * Nothing here starts a server: MockBukkit has no chat events at all, so this package is the only
 * place the pipeline can actually be proven (docs/minimax/15).
 */
class ModerationGateTest {

    private fun gate(
        rules: RuleSet = RuleSet.of(),
        warn: Int = 40,
        block: Int = 80,
        failOpen: Boolean = false,
        onFailure: (String, Throwable) -> Unit = { _, _ -> },
    ) = ModerationGate(
        rules = rules,
        risk = RiskRegistry(),
        settings = GateSettings(maxLength = 100, warnAt = warn, blockAt = block,
            failMode = if (failOpen) GateSettings.FailMode.OPEN else GateSettings.FailMode.CLOSED),
        onFailure = onFailure,
    )

    private fun message(text: String, player: UUID = UUID.randomUUID()) =
        ChatMessage(player, "global", text, System.currentTimeMillis())

    // ── the ordering contract ───────────────────────────────────────────────────────────────

    @Test fun `the moderator is handed the unredacted original`() {
        val secret = "192.168.1.1"
        var seen: String? = null
        val gate = gate(rules = RuleSet.fromConfig(listOf(secret), emptyList()))
        gate.register(object : ChatModerator {
            override fun inspect(message: ChatMessageView): ModerationVerdict {
                seen = message.rawText
                return ModerationVerdict(ModerationAction.ALLOW, "ok")
            }
        })

        gate.decide(message("my ip is $secret please"))

        assertEquals("my ip is $secret please", seen,
            "the gate must run before redaction, or a moderator only ever sees the mask")
    }

    @Test fun `a redacted message is reported as redacted rather than allowed`() {
        val gate = gate(rules = RuleSet.fromConfig(listOf("badword"), emptyList()))
        val decision = gate.decide(message("this is a badword"))
        assertEquals(ModerationAction.REDACT, decision.action)
        assertEquals(1, decision.hits.size)
        assertEquals("badword", decision.hits.first().matched)
    }

    // ── thresholds ──────────────────────────────────────────────────────────────────────────

    @Test fun `the risk thresholds are inclusive and warn sits below block`() {
        val id = UUID.randomUUID()
        val gate = gate(warn = 40, block = 80)
        val risk = RiskRegistry()

        // exercise the same registry the gate reads
        val gateWithRisk = ModerationGate(RuleSet.of(), risk, GateSettings(maxLength = 100, warnAt = 40, blockAt = 80))
        risk.set(id, 39)
        assertEquals(ModerationAction.ALLOW, gateWithRisk.decide(message("hi", id)).action)
        risk.set(id, 40)
        assertEquals(ModerationAction.REDACT, gateWithRisk.decide(message("hi", id)).action, "warn-at is inclusive")
        risk.set(id, 80)
        assertEquals(ModerationAction.BLOCK, gateWithRisk.decide(message("hi", id)).action, "block-at is inclusive")
        assertNotNull(gate.takeIf { true })
    }

    @Test fun `an over-long message is blocked before anybody sees it`() {
        val gate = ModerationGate(RuleSet.of(), RiskRegistry(), GateSettings(maxLength = 16, warnAt = 1, blockAt = 2))
        assertEquals("too-long", gate.decide(message("x".repeat(17))).reason)
        assertEquals(ModerationAction.ALLOW, gate.decide(message("x".repeat(16))).action)
    }

    @Test fun `warn must be strictly below block`() {
        assertThrows(IllegalArgumentException::class.java) { GateSettings(warnAt = 80, blockAt = 40) }
    }

    // ── auditors ───────────────────────────────────────────────────────────────────────────

    @Test fun `the first auditor with an opinion wins`() {
        val gate = gate()
        val calls = mutableListOf<String>()
        gate.register(object : ChatModerator {
            override fun inspect(message: ChatMessageView) =
                ModerationVerdict(ModerationAction.ALLOW, "ok").also { calls += "lenient" }
        })
        gate.register(object : ChatModerator {
            override fun inspect(message: ChatMessageView) =
                ModerationVerdict(ModerationAction.BLOCK, "toxic").also { calls += "strict" }
        })
        gate.register(object : ChatModerator {
            override fun inspect(message: ChatMessageView) =
                ModerationVerdict(ModerationAction.ALLOW, "ok").also { calls += "never" }
        })

        val decision = gate.decide(message("anything"))

        assertEquals(ModerationAction.BLOCK, decision.action)
        assertEquals("toxic", decision.reason)
        assertEquals(listOf("lenient", "strict"), calls, "a block must short-circuit the rest")
    }

    @Test fun `a throwing auditor is isolated and the other auditors still run`() {
        val failures = mutableListOf<String>()
        // Explicitly fail-**open** here, because this test is about isolation: the throwing auditor
        // is skipped so the next one gets its turn. The default is fail-closed, and that is a
        // separate claim with its own test below.
        val gate = gate(failOpen = true, onFailure = { who, _ -> failures += who })
        gate.register(object : ChatModerator {
            override fun inspect(message: ChatMessageView): ModerationVerdict = error("model timed out")
        })
        gate.register(object : ChatModerator {
            override fun inspect(message: ChatMessageView) =
                ModerationVerdict(ModerationAction.BLOCK, "toxic")
        })

        val decision = gate.decide(message("hi"))

        assertEquals(1, failures.size, "the failure must be reported")
        assertEquals(ModerationAction.BLOCK, decision.action, "the other auditors must still run")
        assertEquals("toxic", decision.reason, "the surviving auditor's verdict must be the one that counts")
    }

    @Test fun `the gate fails closed by default`() {
        // The default used to be OPEN, which meant an auditor that was unreachable, mis-keyed or
        // throwing on every call was indistinguishable from no moderation at all — except that the
        // operator's log said the feature was on. A gate that opens itself when the watcher breaks
        // is not a gate.
        assertEquals(GateSettings.FailMode.CLOSED, GateSettings().failMode)
        val gate = gate()
        gate.register(object : ChatModerator {
            override fun inspect(message: ChatMessageView): ModerationVerdict = error("down")
        })
        val decision = gate.decide(message("hi"))
        assertEquals(ModerationAction.BLOCK, decision.action)
        assertEquals("auditor-failed", decision.reason)
    }

    @Test fun `an Error from an auditor is contained too`() {
        // `StackOverflowError` is an `Error`, not an `Exception`. Catching only `Exception` let it
        // escape the chat thread and take the whole context down — one catastrophic regex in
        // `config.yml` and the chat was dead for everybody.
        val failures = mutableListOf<Throwable>()
        val gate = gate(onFailure = { _, error -> failures += error })
        gate.register(object : ChatModerator {
            override fun inspect(message: ChatMessageView): ModerationVerdict = throw StackOverflowError("regex")
        })
        val decision = gate.decide(message("hi"))
        assertEquals(1, failures.size, "the error must be reported, not swallowed")
        assertTrue(failures.single() is StackOverflowError)
        assertEquals(ModerationAction.BLOCK, decision.action)
    }

    @Test fun `fail-closed blocks the message when an auditor dies`() {
        val gate = gate(failOpen = false)
        gate.register(object : ChatModerator {
            override fun inspect(message: ChatMessageView): ModerationVerdict = error("down")
        })
        assertEquals(ModerationAction.BLOCK, gate.decide(message("hi")).action)
    }

    @Test fun `unsubscribing stops delivery`() {
        val gate = gate()
        var calls = 0
        val handle = gate.register(object : ChatModerator {
            override fun inspect(message: ChatMessageView) =
                ModerationVerdict(ModerationAction.ALLOW, "ok").also { calls++ }
        })
        gate.decide(message("one"))
        handle.close()
        gate.decide(message("two"))
        assertEquals(1, calls, "a closed handle must stop the auditor")
    }

    // ── rules ──────────────────────────────────────────────────────────────────────────────

    @Test fun `a literal filter ignores case and masks every occurrence`() {
        val rule = LiteralRule("l", "LEGO")
        val result = rule.apply("i hope you step on a lego, LEGO again")
        assertNotNull(result)
        assertFalse(result!!.text.contains("lego", ignoreCase = true))
        assertEquals(2, result.hits.size)
    }

    @Test fun `a rule that does not apply returns null so the caller can skip it`() {
        assertNull(LiteralRule("l", "absent").apply("nothing here"))
    }

    @Test fun `a regex filter masks what it matches`() {
        val result = RegexRule("r", "^dog").apply("dogs are fine but dogpile")
        assertNotNull(result)
        assertFalse(result!!.text.contains("dog"))
        assertEquals(1, result.hits.size)
    }

    /**
     * A regular expression filter behaves like a literal one.
     *
     * The literal rule has always ignored case, and the regex one did not — so the same word was
     * caught in lower case and walked straight through in capitals, and the operator had no way to
     * tell from the config that the two kinds of rule behaved differently.
     */
    @Test fun `a regex filter ignores case like a literal one does`() {
        val result = RegexRule("r", "bad").apply("this is BAD news")
        assertNotNull(result)
        assertFalse(result!!.text.contains("BAD"), "the mask must cover the match")
        assertEquals(1, result.hits.size)
    }

    /** An operator who wants case sensitivity can still say so. */
    @Test fun `a regex filter can be told to be case sensitive`() {
        assertNull(RegexRule("r", "bad", ignoreCase = false).apply("this is BAD news"))
        assertNotNull(RegexRule("r", "bad", ignoreCase = false).apply("this is bad news"))
    }

    @Test fun `a nested quantifier is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { RegexRule("bad", "(a+)+") }
    }

    @Test fun `an empty literal is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { LiteralRule("bad", "") }
    }
}
