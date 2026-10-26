package com.meowo.ui

import com.meowo.api.UIAPI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The rules the renderer and the test double both obey, checked without a server.
 *
 * Every one of these used to live inside [UIRenderer] or [UIService], where TabooLib and Bukkit made
 * them unreachable — and one of them was copied into the test double in `core`, which is how a rule ends
 * up with two answers. They are here now, so a change to any of them is a change both sides see.
 */
class UIRulesTest {

    private fun button(
        action: String = "menu open",
        actionRight: String? = null,
        actionShift: String? = null,
        permission: String = "",
    ) = UIButton(
        slot = 4,
        icon = "CLOCK",
        name = "n",
        action = action,
        actionRight = actionRight,
        actionShift = actionShift,
        permission = permission,
    )

    private fun element(
        action: String = "",
        actionRight: String? = null,
        actionShift: String? = null,
        permission: String = "",
    ) = UIAPI.UIElement(
        icon = "CLOCK",
        name = "n",
        lore = emptyList(),
        action = action,
        actionRight = actionRight,
        actionShift = actionShift,
        permission = permission,
    )

    // ── the merge rule ───────────────────────────────────────────────────────────────────────

    /**
     * A module may replace what a cell **says** and leave what it **does** to the config.
     *
     * A blank action is how it says so. Dropping it instead would leave a cell that looks clickable and
     * does nothing — which is the bug the renderer's private copy of this rule used to hide from the
     * test double.
     */
    @Test fun `a content-only override keeps the config's action`() {
        val merged = UIButtons.withActionFrom(element(action = ""), button(action = "menu open"))
        assertEquals("menu open", merged.action)
    }

    @Test fun `an override that names its own action wins`() {
        val merged = UIButtons.withActionFrom(element(action = "pomodoro resume"), button(action = "menu open"))
        assertEquals("pomodoro resume", merged.action)
    }

    @Test fun `an override may replace only the right-click gesture`() {
        val merged = UIButtons.withActionFrom(
            element(actionRight = "pomodoro stop"),
            button(action = "menu open", actionRight = "menu close", actionShift = "menu back"),
        )
        assertEquals("pomodoro stop", merged.actionRight)
        assertEquals("menu back", merged.actionShift, "the gesture it did not name stays the config's")
    }

    @Test fun `a blank permission falls back to the config's`() {
        val merged = UIButtons.withActionFrom(element(permission = ""), button(permission = "meowo.vip"))
        assertEquals("meowo.vip", merged.permission)
    }

    @Test fun `an override may make a cell stricter than the config did`() {
        val merged = UIButtons.withActionFrom(element(permission = "meowo.admin"), button(permission = "meowo.vip"))
        assertEquals("meowo.admin", merged.permission)
    }

    // ── the gesture rule ─────────────────────────────────────────────────────────────────────

    /**
     * Shift wins over right, because a shift+right click is still a shift click as far as a cell with
     * two gestures is concerned — and "the more specific gesture wins" is what a player expects.
     */
    @Test fun `shift wins over right`() {
        val cell = element(action = "a", actionRight = "b", actionShift = "c")
        assertEquals("c", UIButtons.gesture(cell, shift = true, right = true))
        assertEquals("c", UIButtons.gesture(cell, shift = true, right = false))
        assertEquals("b", UIButtons.gesture(cell, shift = false, right = true))
        assertEquals("a", UIButtons.gesture(cell, shift = false, right = false))
    }

    @Test fun `a cell without that gesture falls back to its plain one`() {
        val cell = element(action = "a")
        assertEquals("a", UIButtons.gesture(cell, shift = true, right = false))
        assertEquals("a", UIButtons.gesture(cell, shift = false, right = true))
    }

    // ── paging ───────────────────────────────────────────────────────────────────────────────

    @Test fun `an empty list is still one page`() {
        assertEquals(1, UIPaging.pages(0, 5), "a title that reads '1 / 0' is worse than '1 / 1'")
    }

    @Test fun `pages are counted up, not down`() {
        assertEquals(1, UIPaging.pages(5, 5))
        assertEquals(2, UIPaging.pages(6, 5))
        assertEquals(3, UIPaging.pages(11, 5))
    }

    @Test fun `a screen with no room for content is one page, not none`() {
        assertEquals(1, UIPaging.pages(10, 0))
    }

    @Test fun `a page beyond the list is clamped to the last one`() {
        assertEquals(1, UIPaging.clamp(9, 2))
        assertEquals(0, UIPaging.clamp(-3, 2))
        assertEquals(1, UIPaging.clamp(1, 2))
    }

    @Test fun `a cell's index is its place in the whole list, not on its page`() {
        assertEquals(13, UIPaging.index(2, 5, 3))
        assertEquals(3, UIPaging.index(0, 5, 3))
    }

    // ── where the reader was ─────────────────────────────────────────────────────────────────

    @Test fun `a reader resumes where they left off`() {
        val memory = UIPageMemory()
        val alice = UUID.randomUUID()
        memory.remember(alice, "pomodoro-gallery", 3)
        assertEquals(3, memory.resume(alice, "pomodoro-gallery", 0))
    }

    @Test fun `a page the caller names wins over the memory`() {
        val memory = UIPageMemory()
        val alice = UUID.randomUUID()
        memory.remember(alice, "pomodoro-gallery", 3)
        assertEquals(1, memory.resume(alice, "pomodoro-gallery", 1))
    }

    @Test fun `a reader who has never opened the list starts at the beginning`() {
        val memory = UIPageMemory()
        assertEquals(0, memory.resume(UUID.randomUUID(), "pomodoro-gallery", 0))
    }

    @Test fun `one reader's page is not another's`() {
        val memory = UIPageMemory()
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        memory.remember(alice, "pomodoro-gallery", 2)
        assertEquals(0, memory.resume(bob, "pomodoro-gallery", 0))
    }

    // ── the visibility rule the double shares ───────────────────────────────────────────────

    @Test fun `a hidden cell is not drawn whatever the config says`() {
        val hidden = element().copy(hidden = true)
        assertTrue(!UIButtons.visible(button(permission = "meowo.vip"), hidden) { true })
    }

    @Test fun `a cell that needs a permission is drawn only for somebody who has it`() {
        assertTrue(!UIButtons.visible(button(permission = "meowo.vip"), null) { false })
        assertTrue(UIButtons.visible(button(permission = "meowo.vip"), null) { true })
    }

    @Test fun `a cell with no requirement is drawn for everybody`() {
        assertTrue(UIButtons.visible(button(), null) { false })
        assertNull(button().permission.ifBlank { null })
    }
}
