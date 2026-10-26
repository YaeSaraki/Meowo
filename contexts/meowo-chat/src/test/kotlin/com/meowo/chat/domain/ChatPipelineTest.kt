package com.meowo.chat.domain

import com.meowo.api.chat.ModerationAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Stage ordering and the two parsers.
 *
 * The ordering test is the important one: swapping the gate and the filters would still compile,
 * still pass a naive smoke test, and would silently leave the 风纪 AI with nothing but `***`.
 */
class ChatPipelineTest {

    /**
     * The pipeline takes **no length argument** any more.
     *
     * It used to take its own `maxLength` and the gate took another one, both set from the same
     * config value but in two separate constructor calls. The pipeline applied its copy *before* the
     * gate saw the text, so the gate's `too-long` verdict could never fire — the text had already
     * been trimmed to the limit by the time it was asked about. The limit now lives in the gate
     * alone, and this factory says so by construction: there is nowhere else to put a second one.
     */
    private fun pipeline(filters: List<String> = emptyList(), maxLength: Int = 256) = ChatPipeline(
        gate = ModerationGate(RuleSet.fromConfig(filters, emptyList()), RiskRegistry(), GateSettings(maxLength)),
        rules = RuleSet.fromConfig(filters, emptyList()),
    )

    private fun message(text: String) = ChatMessage(UUID.randomUUID(), "global", text, 0L)

    @Test fun `a clean message is passed through untouched`() {
        val outcome = pipeline().process(message("hello world"))
        assertTrue(outcome is Outcome.Send)
        assertEquals("hello world", (outcome as Outcome.Send).text)
        assertFalse(outcome.redacted)
    }

    @Test fun `a filtered message reaches the player masked`() {
        val outcome = pipeline(filters = listOf("badword")).process(message("you badword person"))
        assertTrue(outcome is Outcome.Send)
        assertEquals("you *** person", (outcome as Outcome.Send).text)
        assertTrue(outcome.redacted)
    }

    /**
     * The order is the contract: **redact before decorate**.
     *
     * The filter word here is one the **emoji** stage would replace. If the order were swapped the
     * word would already be an emoji by the time the filter saw it: it would reach the screen
     * unfiltered, and the audit trail would record it as never filtered — which is exactly what an
     * operator bans a word to prevent.
     *
     * The other test in this file filtered "badword", which no decoration touches, so the order
     * could be swapped and every test in the file would still have been green. The stage order is
     * written down in the pipeline's own KDoc; nothing here was checking it.
     */
    @Test fun `the filter runs before the emoji stage`() {
        val outcome = pipeline(filters = listOf("smile")).process(message("you `smile please"))
        assertTrue(outcome is Outcome.Send)
        val text = (outcome as Outcome.Send).text
        assertFalse(text.contains("😄"), "the emoji must not have eaten the word before the filter saw it: $text")
        assertTrue(text.contains("***"), "the filtered word must be masked: $text")
        assertTrue(outcome.redacted)
    }

    @Test fun `markup and emoji are applied after redaction`() {
        val outcome = pipeline().process(message("**hi** `smile badword"))
        val text = (outcome as Outcome.Send).text
        assertTrue(text.contains("[b]hi[/b]"), "bold was not applied: $text")
        assertTrue(text.contains("😄"), "emoji was not applied: $text")
    }

    @Test fun `an emoji name containing an underscore survives the markup pass`() {
        // Regression: markup used to run first and italicised the name, leaving `not[i]an[/i]emoji`
        val text = (pipeline().process(message("`not_an_emoji")) as Outcome.Send).text
        assertTrue(text.contains("`not_an_emoji"), "the underscore ate the emoji name: $text")
    }

    @Test fun `an unknown emoji name is left as typed rather than deleted`() {
        val text = (pipeline().process(message("hello `not_an_emoji world")) as Outcome.Send).text
        assertTrue(text.contains("`not_an_emoji"), "a typo must not delete the player's text: $text")
    }

    @Test fun `an unclosed marker does not swallow the rest of the message`() {
        val text = (pipeline().process(message("**hello there friend")) as Outcome.Send).text
        assertTrue(text.contains("hello there friend"), "an unclosed marker ate the message: $text")
    }

    @Test fun `an empty pair is not styled`() {
        val text = (pipeline().process(message("a ** b")) as Outcome.Send).text
        assertTrue(text.contains("**"), "an empty body should be left alone: $text")
    }

    @Test fun `a backslash escapes a marker`() {
        val text = (pipeline().process(message("""\*\*not bold\*\*""")) as Outcome.Send).text
        assertFalse(text.contains("[b]"), "the escaped marker must not become bold: $text")
        assertTrue(text.contains("**not bold**"), "the literal text must survive: $text")
    }

    @Test fun `normalise collapses runs of spaces`() {
        val p = pipeline()
        assertEquals("a b", p.normalise("a     b"))
        assertEquals("a b", p.normalise("   a     b   "))
    }

    @Test fun `normalise does not cap the length - the gate owns that`() {
        // The regression this pins: the pipeline used to truncate to its own copy of the limit
        // *before* the gate was asked, so the gate's `too-long` verdict was unreachable and a
        // message over the cap was sent cut off mid-sentence as if that were what was typed.
        val p = pipeline()
        assertEquals(50, p.normalise("x".repeat(50)).length, "normalising must not shorten the text")
    }

    @Test fun `a message over the cap is dropped rather than truncated silently`() {
        val p = ChatPipeline(
            gate = ModerationGate(RuleSet.of(), RiskRegistry(), GateSettings(maxLength = 16, warnAt = 1, blockAt = 2)),
            rules = RuleSet.of(),
        )
        val outcome = p.process(message("x".repeat(200)))
        assertTrue(outcome is Outcome.Drop, "the gate blocks on length, not the normaliser")
        assertEquals("too-long", (outcome as Outcome.Drop).reason)
    }

    @Test fun `a rewrite replaces the whole body and is still decorated`() {
        val gate = ModerationGate(RuleSet.of(), RiskRegistry(), GateSettings(maxLength = 100, warnAt = 1, blockAt = 2))
        gate.register(object : com.meowo.api.chat.ChatModerator {
            override fun inspect(message: com.meowo.api.chat.ChatMessageView) =
                com.meowo.api.chat.ModerationVerdict(ModerationAction.REWRITE, "spoiler", "**redacted**")
        })
        val p = ChatPipeline(gate, RuleSet.of())
        val outcome = p.process(message("something")) as Outcome.Send
        assertTrue(outcome.text.contains("[b]redacted[/b]"), outcome.text)
        assertTrue(outcome.redacted)
    }
}
