package com.meowo.ui

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The loader is where a layout mistake becomes visible.
 *
 * A button outside its inventory was the silent kind of bug: the home pages asked for four rows while the
 * rail lived at slots 45–52, so the exit was never drawn and nothing said why. Each test here pins such a
 * failure to a load-time error instead of a menu that quietly does nothing.
 */
class UILoaderTest {

    private fun shipped(screens: String) = YamlConfiguration.loadConfiguration(
        ("defaults:\n  rows: 6\nscreens:\n$screens").reader(),
    )

    private fun parse(screens: String) = UILoader.parse(shipped(screens), null)

    private fun operator(text: String): File =
        File.createTempFile("ui-test", ".yml").apply { writeText(text); deleteOnExit() }

    private fun load(override: String? = null) = UILoader.load(
        """defaults:
  rows: 6
screens:
  pomodoro:
    title: pomodoro-main-title
    buttons:
      10: { icon: CLOCK, name: pomodoro-action-start, action: "pomodoro start" }
""".byteInputStream(),
        override?.let { operator(it) },
    )

    @Test fun `the shipped file parses into the pomodoro screens`() {
        val layout = UILoader.load(javaClass.getResourceAsStream("/ui.yml")!!, null)

        assertEquals(6, layout.defaultRows)
        assertNotNull(layout.screen("pomodoro"), "the colonnade must be shipped")
        assertEquals(28, layout.screen("pomodoro-gallery")!!.contentSlots.size, "four rows of seven")
        assertTrue(layout.screen("pomodoro-gallery")!!.isList)
        assertNotNull(layout.screen("pomodoro-editor"))
        assertNotNull(layout.screen("confirm"))
    }

    @Test fun `every shipped screen fits its own menu`() {
        // The invariant the home pages broke: a slot that cannot be drawn must not load.
        val layout = UILoader.load(javaClass.getResourceAsStream("/ui.yml")!!, null)
        layout.screens.values.forEach { screen ->
            val limit = screen.rows * 9
            screen.buttons.keys.forEach { slot ->
                assertTrue(slot in 0 until limit, "${screen.id}: button $slot does not fit ${screen.rows} rows")
            }
            assertTrue(screen.rows in UILoader.ROW_RANGE)
        }
    }

    @Test fun `an unknown screen id names what exists`() {
        val error = assertThrows(IllegalArgumentException::class.java) { load().require("nope") }
        assertTrue(error.message!!.contains("pomodoro"), "should list the known screens: ${error.message}")
    }

    @Test fun `a button outside the menu is refused, naming screen and slot`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parse("  home:\n    title: t\n    rows: 4\n    buttons:\n      45: { icon: BEDROCK, name: n, action: \"menu\" }\n")
        }
        assertTrue(error.message!!.contains("45") && error.message!!.contains("home"), error.message)
    }

    @Test fun `a row count outside the range is refused`() {
        assertThrows(IllegalArgumentException::class.java) { parse("  s:\n    title: t\n    rows: 7\n") }
    }

    @Test fun `an unknown or blank action is refused at load`() {
        val unknown = assertThrows(IllegalArgumentException::class.java) {
            parse("  s:\n    title: t\n    buttons:\n      4: { icon: CLOCK, name: n, action: \"@nope\" }\n")
        }
        assertTrue(unknown.message!!.contains("@nope"), unknown.message)
        assertThrows(IllegalArgumentException::class.java) {
            parse("  s:\n    title: t\n    buttons:\n      4: { icon: CLOCK, name: n, action: \" \" }\n")
        }
    }

    @Test fun `a button without an icon or without a name is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("  s:\n    title: t\n    buttons:\n      4: { name: n, action: \"@back\" }\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("  s:\n    title: t\n    buttons:\n      4: { icon: CLOCK, action: \"@back\" }\n")
        }
    }

    @Test fun `the operator replaces a screen outright`() {
        val layout = load("""screens:
  pomodoro:
    title: my own title
    buttons:
      4: { icon: CLOCK, name: mine, action: "pomodoro start" }
""")
        assertEquals("my own title", layout.require("pomodoro").title)
        assertEquals("mine", layout.require("pomodoro").button(4)!!.name)
        assertEquals(1, layout.screens.size, "the operator's block replaces the shipped screen")
    }

    @Test fun `rails and content slots are read`() {
        val screen = parse(
            "  list:\n    title: t\n    content-slots: [10, 11]\n" +
                "    rail: { name: pomodoro-action-back, lore: pomodoro-do-back }\n" +
                "    plinth: { name: pomodoro-action-main-menu, lore: pomodoro-do-main-menu }\n" +
                "    buttons:\n      4: { icon: CLOCK, name: n, action: \"@back\" }\n",
        ).require("list")
        assertEquals(listOf(10, 11), screen.contentSlots)
        assertTrue(screen.isList)
        assertEquals("pomodoro-action-back", screen.railName)
        assertEquals("pomodoro-do-main-menu", screen.plinthLore)
    }

    @Test fun `a content slot outside the menu is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("  s:\n    title: t\n    rows: 2\n    content-slots: [40]\n")
        }
    }

    @Test fun `a cell may carry three gestures, each validated`() {
        val screen = parse(
            "  list:\n    title: t\n    content-slots: [10]\n    buttons:\n      4: { icon: EMERALD, name: n, " +
                "action: \"pomodoro create\", action-right: \"pomodoro copy\", action-shift: \"@cancel\" }\n",
        ).require("list")
        val button = screen.button(4)!!
        assertEquals("pomodoro create", button.action)
        assertEquals("pomodoro copy", button.actionRight)
        assertEquals("@cancel", button.actionShift)
    }

    @Test fun `a bad second gesture is refused like the first`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("  s:\n    title: t\n    buttons:\n      4: { icon: CLOCK, name: n, action: \"a\", action-right: \"@nope\" }\n")
        }
    }

    @Test fun `a permission is read off a button, and defaults to everyone`() {
        val screen = parse(
            "  s:\n    title: t\n    buttons:\n" +
                "      4: { icon: CLOCK, name: n, action: \"a\", permission: \"meowo.admin\" }\n" +
                "      5: { icon: CLOCK, name: m, action: \"b\" }\n",
        ).require("s")
        assertEquals("meowo.admin", screen.button(4)!!.permission)
        assertEquals("", screen.button(5)!!.permission, "no permission means everyone sees the cell")
    }

    @Test fun `the shipped menu screens fit their own menus too`() {
        // The loader already refuses an out-of-bounds slot; this pins that the menu screens are valid, since
        // they came from a module that used to draw its own layout.
        val layout = UILoader.load(javaClass.getResourceAsStream("/ui.yml")!!, null)
        assertEquals(6, layout.require("menu").rows)
        assertTrue(layout.require("menu-homes").isList)
        assertEquals(28, layout.require("menu-homes").contentSlots.size)
    }

    @Test fun `a command template takes the element it was clicked on`() {
        val preset = com.meowo.api.UIAPI.UIElement("CLOCK", "深度工作", emptyList(), "pomodoro start {index}")
        assertEquals("pomodoro start 3", UICommand.fill("pomodoro start {index}", preset, 3))
        assertEquals("pomodoro start \"深度工作\"", UICommand.fill("pomodoro start \"{name}\"", preset, 0))
    }

    @Test fun `a template also takes values the module knows and the config does not`() {
        // A home screen's commands need the slot of the home being shown, which only the module holds; the
        // command still lives in `ui.yml`, so the layout stays in the config.
        val filled = UICommand.fill("home {slot}", null, -1, mapOf("slot" to "7"))
        assertEquals("home 7", filled)
        // Element placeholders still work alongside them.
        val preset = com.meowo.api.UIAPI.UIElement("CLOCK", "深度工作", emptyList(), "x")
        assertEquals("home 7/深度工作", UICommand.fill("home {slot}/{name}", preset, 0, mapOf("slot" to "7")))
    }

    @Test fun `a template with an unknown placeholder is left alone rather than blanked`() {
        // Blanking it would run a different command than the one written, which is worse than a visible typo.
        val preset = com.meowo.api.UIAPI.UIElement("CLOCK", "n", emptyList(), "x {nope}")
        assertEquals("x {nope}", UICommand.fill("x {nope}", preset, 0))
    }

    @Test fun `a plain action is a command and an at-sign action is a built-in`() {
        assertEquals(UIAction.Command("pomodoro start"), UIAction.parse("/pomodoro start"))
        assertEquals(UIAction.Builtin(UIBuiltin.BACK), UIAction.parse("@back"))
        assertEquals(UIAction.Builtin(UIBuiltin.PAGE_NEXT), UIAction.parse("@PAGE-NEXT"), "case should not matter")
        val error = assertThrows(IllegalArgumentException::class.java) { UIAction.parse("@nope") }
        assertTrue(error.message!!.contains("@back"), "the error should list the tokens: ${error.message}")
        assertThrows(IllegalArgumentException::class.java) { UIAction.parse("  ") }
    }
}
