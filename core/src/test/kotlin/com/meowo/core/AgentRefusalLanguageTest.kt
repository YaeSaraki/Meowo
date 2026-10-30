package com.meowo.core

import com.meowo.agents.AgentRefusals
import com.meowo.agents.domain.Apology
import com.meowo.agents.domain.CallRefusal
import com.meowo.agents.domain.RedactionRefusal
import com.meowo.agents.domain.Refusal
import com.meowo.agents.domain.ToolRefusal
import com.meowo.agents.domain.TurnedAway
import com.meowo.platform.Messages
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.bukkit.plugin.java.JavaPlugin
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

/**
 * A refusal reaches the player **as a sentence**, and the sentence is in the player's language.
 *
 * The three defects this pins down all looked the same from the outside: the plugin rendered, the
 * build stayed green, and an English player read Chinese.
 *
 * - The pure layer carried the refusal as a Chinese sentence, so there was nowhere for a language to
 *   come from. (Now a reason; see `Refusal.kt`.)
 * - The prompt told the model to repeat a refusal as it is, so the Chinese could not even be
 *   translated on the way out.
 * - The wording lived in the code, so no operator could change it and no language pack could hold
 *   it.
 */
class AgentRefusalLanguageTest {

    open class TestPlugin : JavaPlugin()

    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin

    @BeforeEach fun setUp() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(TestPlugin::class.java)
    }

    @AfterEach fun tearDown() { MockBukkit.unmock() }

    private fun messages(mode: String = "player"): Messages =
        Messages(plugin).apply { load("zh_CN", mode) }

    /**
     * Every reason, with the arguments its sentence needs.
     *
     * The arguments are the ones the production code passes: a cat's name, a tool's name, and the
     * cat's own deflection.
     */
    private val reasons: List<Pair<Refusal, List<Any>>> = listOf(
        RedactionRefusal.PRIVATE to emptyList(),
        RedactionRefusal.OPTED_OUT to emptyList(),
        RedactionRefusal.NOT_ALLOWED to emptyList(),
        ToolRefusal.PENDING to emptyList(),
        ToolRefusal.FAILED to emptyList(),
        ToolRefusal.BAD_TICKET to emptyList(),
        ToolRefusal.DAILY_IS_YOURS to emptyList(),
        ToolRefusal.UNKNOWN_PLAYER to emptyList(),
        CallRefusal.NO_CAPABILITY to listOf("EconCat", "economy.balance"),
        CallRefusal.NOT_WIRED to listOf("economy.balance"),
        CallRefusal.BAD_ARGUMENTS to emptyList(),
        CallRefusal.THREW to listOf("economy.balance"),
        TurnedAway.NOT_A_CAT to emptyList(),
        TurnedAway.COOLDOWN to listOf("这个我没账。"),
        TurnedAway.QUOTA to emptyList(),
        TurnedAway.NOT_ALLOWED to emptyList(),
        TurnedAway.BUSY to emptyList(),
        Apology.PROVIDER_FAILED to emptyList(),
    )

    private val cjk = Regex("[\\u4e00-\\u9fff]")

    /**
     * An English player reads English.
     *
     * The one exception is the cooldown line, which carries the cat's own deflection. That is the
     * cat's **persona** — the same words the Chinese prompt gives it — so it stays in the cat's own
     * language on purpose. The policy half around it is localized; the cat's voice is not.
     */
    @Test fun `an English player is answered in English`() {
        val refusals = AgentRefusals(messages())

        for ((reason, args) in reasons) {
            val line = refusals.render("en_US", reason, args)
            assertFalse(line.startsWith("agents-"), "$reason rendered as a raw key: $line")
            if (reason == TurnedAway.COOLDOWN) {
                assertFalse(cjk.containsMatchIn(line.replace("这个我没账。", "")),
                    "the policy half of the cooldown line must be English: $line")
            } else {
                assertFalse(cjk.containsMatchIn(line), "$reason is not in English: $line")
            }
        }
    }

    /** And a Chinese player reads Chinese. The two bundles are not the same sentence. */
    @Test fun `a Chinese player is answered in Chinese`() {
        val refusals = AgentRefusals(messages())

        for ((reason, args) in reasons) {
            val line = refusals.render("zh_CN", reason, args)
            assertTrue(cjk.containsMatchIn(line), "$reason is not in Chinese: $line")
            assertNotEquals(
                line,
                refusals.render("en_US", reason, args),
                "$reason reads the same in both languages, so one of them is missing",
            )
        }
    }

    /**
     * The server's language mode decides, not the client.
     *
     * A server that pins everybody to one language gets one language — including the refusals. The
     * cat's own answer still follows the client, which is a pre-existing difference and not this
     * change's business.
     */
    @Test fun `a server that pins one language pins the refusals too`() {
        val refusals = AgentRefusals(messages("server"))

        for ((reason, args) in reasons) {
            val line = refusals.render("en_US", reason, args)
            assertTrue(cjk.containsMatchIn(line), "$reason ignored the server's language mode: $line")
        }
    }

    /**
     * The arguments reach the sentence.
     *
     * A refusal that names no tool names nothing, so a player who sees "something went wrong" has
     * no idea which capability failed.
     */
    @Test fun `the arguments reach the sentence`() {
        val refusals = AgentRefusals(messages())

        assertTrue(
            refusals.render("en_US", CallRefusal.NO_CAPABILITY, listOf("EconCat", "economy.balance"))
                .contains("economy.balance"),
            "the tool's name must be in the sentence",
        )
        assertTrue(
            refusals.render("zh_CN", CallRefusal.THREW, listOf("economy.balance"))
                .contains("economy.balance"),
            "the tool's name must be in the sentence",
        )
        assertTrue(
            refusals.render("zh_CN", ToolRefusal.DAILY_IS_YOURS, emptyList()).contains("/eco daily"),
            "the daily subsidy has to point at the command the player can actually run",
        )
    }

    /**
     * A refusal with no deflection still reads as a sentence.
     *
     * The cooldown line carries the cat's own words, and a cat with none would leave an unfilled
     * `{0}` in the middle of the player's chat. The plain variant exists for exactly that.
     */
    @Test fun `a cat with no deflection still says something whole`() {
        val refusals = AgentRefusals(messages())

        val line = refusals.render("en_US", TurnedAway.COOLDOWN, emptyList())
        assertFalse(line.contains("{0}"), "an unfilled placeholder reached the player: $line")
        assertFalse(cjk.containsMatchIn(line), "the plain cooldown line must be English: $line")
    }
}
