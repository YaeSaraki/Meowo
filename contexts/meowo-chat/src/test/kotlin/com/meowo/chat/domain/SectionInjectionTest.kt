package com.meowo.chat.domain

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A player must not be able to put colour in their own message.
 *
 * `§` is U+00A7, not a control character, so `normalise` keeps it — and the pipeline's output is
 * handed to the legacy deserialiser, which reads `§c` as "red from here". Measured on this pipeline:
 * ``§c§lADMIN 管理员 §r`sad` `` came out as `§c§lADMIN 管理员 §r😢`, changed, and therefore
 * legacy-deserialised into **red bold text the player typed themselves**.
 *
 * The reason it is easy to miss by reading the code is the last part: it only fires when the
 * decoration changes the string. A message with no emoji and no markup is passed through untouched
 * and stays plain, so a naive test with just `§c` looks perfectly safe.
 *
 * ## These tests describe the pipeline, which is no longer the barrier
 *
 * Two things were true at once and this file used to conflate them:
 *
 * 1. **The pipeline keeps `§`** — it is a character as far as normalisation is concerned, and it
 *    still is. The test that states this is `a section escape survives normalisation as a live code`,
 *    and it is a statement about the pipeline, not an endorsement.
 * 2. **The fix was at `ChatContext.onChat`**, which *removed* the sign. (This KDoc said it "doubled"
 *    it, which was never true and described the approach that measurement rejected.)
 *
 * Both are still in place, but they are now belt and braces: since body text is rendered by
 * `ChatLine.body`, which is not a legacy deserialiser and never interprets `§`, a `§` cannot become
 * colour on **any** channel even if a future boundary forgets. That matters because the whispers had
 * no boundary at all — `renderPrivate` deserialised every body unconditionally, so `/meo msg Bob §c`
 * needed no emoji.
 *
 * The property that actually has to hold is pinned in `ChatLineBodyTest`.
 */
class SectionInjectionTest {

    private fun pipeline() = ChatPipeline(
        gate = ModerationGate(RuleSet.fromConfig(emptyList(), emptyList()), RiskRegistry(), GateSettings(256)),
        rules = RuleSet.fromConfig(emptyList(), emptyList()),
    )

    private fun message(text: String) = ChatMessage(UUID.randomUUID(), "global", text, 0L)

    /** What the chat context hands the pipeline: the player's text with section signs removed. */
    private fun neutralise(text: String) = text.replace("\u00A7", "")

    /** The round trip that actually matters — would a reader see colour? */
    private fun rendersColoured(sent: String): Boolean {
        val rendered = LegacyComponentSerializer.legacySection().deserialize(sent)
        fun coloured(c: net.kyori.adventure.text.Component): Boolean =
            c.color() != null || c.children().any { coloured(it) }
        return coloured(rendered)
    }

    @Test fun `a section escape survives normalisation as a live code`() {
        // The hole itself, stated plainly: nothing in the pipeline stops this today.
        val hostile = "§c§lADMIN 管理员 §r`sad`"
        val sent = (pipeline().process(message(hostile)) as Outcome.Send).text

        assertEquals("§c§lADMIN 管理员 §r😢", sent, "the pipeline hands the escape straight on")
    }

    @Test fun `an emoji is what turns an escape into rendered colour`() {
        val withEmoji = (pipeline().process(message("§c§lADMIN `sad`")) as Outcome.Send)
        val withoutEmoji = (pipeline().process(message("§c§lADMIN")) as Outcome.Send)

        assertTrue(withEmoji.text != "§c§lADMIN", "decoration changed the string, so it is deserialised")
        assertEquals("§c§lADMIN", withoutEmoji.text, "nothing changed, so it is passed through as plain text")
    }

    @Test fun `after neutralising, the player sees what they typed and nothing more`() {
        val typed = "§c§lADMIN 管理员 §r`sad`"

        val sent = (pipeline().process(message(neutralise(typed))) as Outcome.Send).text

        assertFalse(rendersColoured(sent), "a player's own escape must never become colour: $sent")
        assertEquals(
            "clADMIN 管理员 r😢",
            PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacySection().deserialize(sent),
            ),
            "the sign is gone and what it guarded is harmless — which is what vanilla would have shown",
        )
    }

    @Test fun `an ordinary message is untouched by the doubling`() {
        val plain = "你好 everyone，今天天气不错"

        val sent = (pipeline().process(message(neutralise(plain))) as Outcome.Send).text

        assertEquals(plain, sent, "nothing to escape means nothing changed")
    }
}
