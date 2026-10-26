package com.meowo.ui

import com.meowo.api.UIAPI
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The permission rule, which used to be tested through the menu module's own config tests.
 *
 * When the layout moved into `ui.yml` those tests lost their subject — the config section they exercised no
 * longer exists. The *behaviour* they pinned is still required, so it moved here with the layout instead of
 * disappearing along with the file.
 */
class UIButtonsTest {

    private val plain = UIButton(slot = 4, icon = "CLOCK", name = "n", action = "@close")

    @Test fun `a cell with no requirement is visible to everyone`() {
        assertTrue(UIButtons.visible(plain, null) { false }, "an empty permission is not a permission check")
    }

    @Test fun `a required permission hides the cell from a player who lacks it`() {
        val guarded = plain.copy(permission = "meowo.admin")
        assertTrue(UIButtons.visible(guarded, null) { it == "meowo.admin" })
        assertFalse(UIButtons.visible(guarded, null) { false })
    }

    @Test fun `an override may restate the requirement, including making it stricter`() {
        val override = UIAPI.UIElement("CLOCK", "n", emptyList(), "@close", permission = "meowo.vip")
        assertFalse(
            UIButtons.visible(plain, override) { it == "meowo.admin" },
            "the override replaces the requirement, it does not add to it",
        )
        assertTrue(UIButtons.visible(plain, override) { it == "meowo.vip" })
    }

    @Test fun `a cell the module hid is not drawn, permission or not`() {
        // "Force travel" must not appear when the server disables it: a button whose only answer is a refusal
        // teaches the player that the menu lies.
        val hidden = UIAPI.UIElement("CLOCK", "n", emptyList(), "x", hidden = true)
        assertFalse(UIButtons.visible(plain, hidden) { true }, "hidden means hidden")
    }

    @Test fun `a blank requirement on an override falls back to the config's`() {
        val override = UIAPI.UIElement("CLOCK", "n", emptyList(), "@close")
        assertFalse(UIButtons.visible(plain.copy(permission = "meowo.admin"), override) { false })
    }
}
