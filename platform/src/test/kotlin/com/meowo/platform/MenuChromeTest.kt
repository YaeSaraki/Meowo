package com.meowo.platform

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * The chrome is scenery, but its **geometry** is a contract: content lives in `10..43` and navigation
 * in `45/49/50/53`. Getting that wrong would not throw — it would silently paint over a button, and
 * the screen would look fine while one feature became unclickable. These assertions are that contract.
 */
class MenuChromeTest {

    @BeforeEach
    fun mock() {
        MockBukkit.mock()
    }

    @AfterEach
    fun unmock() {
        MockBukkit.unmock()
    }

    private val contentGrid = (10..43).filter { it % 9 in 1..7 }

    @Test fun `the frame never paints the content grid`() {
        val frame = MenuChrome.frame(6)
        contentGrid.forEach { assertFalse(frame.containsKey(it), "slot $it is content and must stay free") }
    }

    @Test fun `the frame keeps the navigation slots usable`() {
        // 45/49/50/53 are set by the caller (page turns, back, close); the chrome must place nothing
        // there that a caller could fail to override.
        val frame = MenuChrome.frame(6)
        listOf(45, 49, 50, 53).forEach {
            assertTrue(frame.containsKey(it), "the rail should dress slot $it so it is not a hole")
        }
    }

    @Test fun `the border covers exactly the ring around the grid`() {
        val frame = MenuChrome.frame(6)
        val expected = (0..53).filter { it < 9 || it >= 45 || it % 9 == 0 || it % 9 == 8 }
        assertEquals(expected.toSet(), frame.keys)
    }

    @Test fun `the flanking columns read as a pillar with head shaft and foot`() {
        val style = CrystalStyle.DEFAULT
        val frame = MenuChrome.frame(6)
        for (col in listOf(0, 8)) {
            assertEquals(style.corner, frame[col]!!.type, "corner at row 0")
            assertEquals(style.head, frame[col + 9]!!.type, "crystal head under the corner")
            assertEquals(style.plinth, frame[col + 45]!!.type, "the column ends on the bedrock plinth")
        }
    }

    @Test fun `both rails are the same glass, and the rail tile is that glass with a tooltip`() {
        val style = CrystalStyle.DEFAULT
        val frame = MenuChrome.frame(6)
        // The bottom rail was briefly a stone plinth and was reverted: the glass reads better, and the
        // rail does not need to look inert because a page dresses it as the way back.
        assertEquals(style.rail, frame[46]!!.type, "the bottom rail is the rail glass")
        assertEquals(style.plinth, frame[45]!!.type, "the bottom corners are the bedrock floor")
        assertEquals(style.plinth, frame[53]!!.type)
        assertEquals(frame[1]!!.type, frame[46]!!.type, "top and bottom rails match")

        val tile = MenuChrome.railTile(
            net.kyori.adventure.text.Component.text("back"),
            listOf(net.kyori.adventure.text.Component.text("goes up")),
        )
        assertEquals(style.rail, tile.type, "a rail tile is the rail's own material")
        assertNotNull(tile.itemMeta?.displayName(), "but unlike the scenery it is named")
        assertEquals(1, tile.itemMeta?.lore()?.size, "and explained")
    }

    @Test fun `a function pillar returns the shaft and foot but leaves the head to the caller`() {
        val style = CrystalStyle.DEFAULT
        val pillar = MenuChrome.pillar(headSlot = 10, height = 3)
        assertEquals(setOf(19, 28), pillar.keys, "shaft below the head, foot below that")
        assertEquals(style.base, pillar[28]!!.type)
        assertFalse(pillar.containsKey(10), "the head is the caller's, so the chrome must not fill it")
    }

    @Test fun `every chrome item hides its name so tooltips stay about the button`() {
        val items = MenuChrome.frame(6).values + MenuChrome.pillar(10).values + MenuChrome.shard()
        items.forEach { item ->
            val name = item.itemMeta?.displayName()
            assertTrue(name == null || name == net.kyori.adventure.text.Component.empty()) {
                "chrome is scenery and must not add a tooltip, found $name on ${item.type}"
            }
        }
    }

    @Test fun `three-row confirm pages get the same chrome and stay within bounds`() {
        val frame = MenuChrome.frame(3)
        assertTrue(frame.keys.all { it in 0..26 })
        assertEquals(Material.PURPLE_STAINED_GLASS_PANE, frame[1]!!.type)
        assertNull(frame[45], "a three-row page has no bottom rail to put a plinth on")
    }

    @Test fun `an out-of-range row count is refused rather than drawn off the edge`() {
        assertThrows(IllegalArgumentException::class.java) { MenuChrome.frame(0) }
        assertThrows(IllegalArgumentException::class.java) { MenuChrome.frame(7) }
        assertThrows(IllegalArgumentException::class.java) { MenuChrome.pillar(10, height = 9) }
    }

    @Test fun `head slots walk the first content row of the grid`() {
        assertEquals(listOf(10, 11, 12, 13, 14, 15, 16), MenuChrome.headSlots((1..7).toList()))
    }
}
