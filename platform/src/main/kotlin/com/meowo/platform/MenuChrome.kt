package com.meowo.platform

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack

/**
 * The crystal palette every Meowo screen is drawn in.
 *
 * One shared vocabulary of materials rather than per-menu taste, because the whole point of a house
 * style is that a player recognises it before reading anything: an amethyst head is a *function*, a
 * deepslate foot is a *floor*, and the pane shaft between them is just the pillar they stand on.
 *
 * Colours are decoration, never the only signal — every item still carries a name and a lore line
 * saying what it does, and left/right click are always written out. A player who cannot tell purple
 * from magenta loses nothing.
 */
data class CrystalStyle(
    /** The glowing head on top of a pillar. Reserved for things a player can click. */
    val head: Material = Material.AMETHYST_CLUSTER,
    /** The crystal that crowns the middle of the top rail. */
    val crown: Material = Material.BUDDING_AMETHYST,
    /** Corner blocks. */
    val corner: Material = Material.AMETHYST_BLOCK,
    /** Long horizontal rails, top and bottom. */
    val rail: Material = Material.PURPLE_STAINED_GLASS_PANE,
    /** Pillar shafts, alternating with [shaftAlt] to read as facets. */
    val shaft: Material = Material.PURPLE_STAINED_GLASS_PANE,
    val shaftAlt: Material = Material.MAGENTA_STAINED_GLASS_PANE,
    /** The foot of a pillar. */
    val base: Material = Material.POLISHED_DEEPSLATE,
    /**
     * The two bottom corners.
     *
     * Bedrock, not another crystal: the corners are the *floor* of the menu — the ground everything stands
     * on — and they carry the one destination every screen shares, the main menu. A material that cannot be
     * broken reads as a foundation rather than as a button, which is exactly what it is.
     */
    val plinth: Material = Material.BEDROCK,
    /** Small decorative crystal, used for the secondary row. */
    val shard: Material = Material.AMETHYST_SHARD,
    /**
     * An empty list cell.
     *
     * Deliberately **not** the pillar material: the shafts are the structure a player is meant to
     * read, and filling the content grid with the same pane turns a mostly-empty list into a wall
     * of purple with two items lost inside it. A quiet grey recess reads as "nothing here yet".
     */
    val socket: Material = Material.GRAY_STAINED_GLASS_PANE,
) {
    companion object {
        val DEFAULT = CrystalStyle()
    }
}

/**
 * Draws the house style into a chest menu.
 *
 * Everything here returns `slot → ItemStack` instead of touching a menu builder, so the chrome stays
 * independent of TabooLib and can be asserted in a plain unit test. Adapters merge the result **under**
 * their own buttons, which is what lets the same frame serve the config-driven main menu, the home
 * list and the pomodoro screens without any of them knowing about each other.
 *
 * ### Geometry
 *
 * A `rows`-row chest keeps the contract every existing screen relies on: **columns 1..7 of rows 1..4
 * are content** (`10..43`, the grid home and pomodoro lists page through), and the bottom row holds
 * the navigation slots (`45` previous, `49` page or close, `50` back, `53` next). The chrome only ever
 * paints the border and the two flanking columns, so a caller's button always wins its own slot.
 *
 * The left and right columns are **pillars**: a crystal head under each corner, a faceted shaft, and a
 * deepslate foot. That is the shape the whole plugin is recognised by.
 */
object MenuChrome {

    /** Rows a Meowo chest menu may have. Six is the full form; three is the smallest confirm page. */
    val ROW_RANGE = 1..6

    /**
     * The border and the two flanking pillars for a [rows]-row menu.
     *
     * The content grid is deliberately left untouched: an empty `slot 20` must stay empty so a screen
     * can hide an item by simply not placing one.
     */
    fun frame(rows: Int, style: CrystalStyle = CrystalStyle.DEFAULT): Map<Int, ItemStack> {
        require(rows in ROW_RANGE) { "a Meowo menu must have ${ROW_RANGE.first}..${ROW_RANGE.last} rows, got $rows" }
        val out = LinkedHashMap<Int, ItemStack>()
        val lastRow = rows - 1

        for (col in 0 until 9) {
            val atCorner = col == 0 || col == 8
            // The top rail carries the crown; the bottom rail is where navigation lands.
            out[col] = when {
                atCorner -> block(style.corner)
                col == 4 -> block(style.crown, glint = true)
                else -> pane(style.rail)
            }
            // The bottom rail is the same glass as the top one. A stone plinth was tried for it and
            // reverted: the glass rail reads better, and the rail does not need to look inert because
            // it is not inert — every cell a page leaves alone on this row is the way back, dressed by
            // [railTile] with a name and a lore line so it never looks like scenery.
            out[lastRow * 9 + col] = if (atCorner) block(style.plinth) else pane(style.rail)
        }

        // The flanking pillars: head just under the corner, then shaft, then foot on the bottom rail.
        for (col in listOf(0, 8)) {
            if (rows >= 2) out[col + 9] = block(style.head, glint = true)
            for (row in 2 until lastRow) out[col + row * 9] = pane(shaftAt(style, row))
        }
        return out
    }

    /**
     * A vertical function pillar whose clickable head sits at [headSlot]: the shaft and foot are
     * returned, the head is the caller's to build.
     *
     * The head is excluded on purpose — it is the only part that differs per function, and a chrome
     * helper that also produced the button would have to know each function's name and lore.
     */
    fun pillar(
        headSlot: Int,
        height: Int = 3,
        style: CrystalStyle = CrystalStyle.DEFAULT,
    ): Map<Int, ItemStack> {
        require(height in 2..4) { "a pillar is 2..4 cells tall, got $height" }
        val out = LinkedHashMap<Int, ItemStack>()
        for (step in 1 until height) {
            val slot = headSlot + step * 9
            out[slot] = if (step == height - 1) block(style.base) else pane(shaftAt(style, step))
        }
        return out
    }

    /**
     * A bottom-rail tile: the rail's own glass, but **named and explained**.
     *
     * The chrome's scenery panes hide their name on purpose — a tooltip on a wall is noise. The rail is
     * the one place that is wrong: it is clickable on every menu (the whole row goes back), and glass
     * with no tooltip reads as "not a button" to exactly the player looking for the exit. So the rail
     * uses the same material and drops the silence.
     */
    fun railTile(
        name: Component,
        lore: List<Component>,
        style: CrystalStyle = CrystalStyle.DEFAULT,
    ): ItemStack = ItemStack(style.rail).also { item ->
        item.itemMeta = item.itemMeta?.apply {
            displayName(name.decoration(TextDecoration.ITALIC, false))
            lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        }
    }

    /**
     * A plinth tile: the bedrock corner, named and explained, always leading to the main menu.
     *
     * Built from the style's own material rather than a rail tile with its type swapped — `ItemStack.type` is
     * deprecated and not every server version honours the setter, which is the sort of thing that works in
     * one test and not on the machine that matters.
     */
    fun plinthTile(
        name: Component,
        lore: List<Component>,
        style: CrystalStyle = CrystalStyle.DEFAULT,
    ): ItemStack = ItemStack(style.plinth).also { item ->
        item.itemMeta = item.itemMeta?.apply {
            displayName(name.decoration(TextDecoration.ITALIC, false))
            lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        }
    }

    /** The decorative shard used to fill a secondary row without pretending it is a button. */
    fun shard(style: CrystalStyle = CrystalStyle.DEFAULT): ItemStack = block(style.shard)

    /**
     * An empty socket for a list cell with nothing in it.
     *
     * A list page that leaves its unused cells as holes looks broken; a list page that fills them with
     * a *button* lies. A nameless, actionless socket is neither — it reads as a place where an entry
     * could go, which is exactly what it is.
     */
    fun socket(style: CrystalStyle = CrystalStyle.DEFAULT): ItemStack = pane(style.socket)

    /**
     * The two bottom corners: the bedrock plinth, which every screen binds to "back to the main menu".
     *
     * Published here rather than re-derived per screen, because "which two cells are the floor" is part of
     * the house style — a screen that guessed differently would put its exit somewhere else.
     */
    val PLINTH_SLOTS = listOf(45, 53)

    /** The glass between the two corners: the rail's working cells (page turn, or "up one level"). */
    val RAIL_GLASS_SLOTS = (46..52).toList()

    /** The whole bottom row, corners included. */
    val RAIL_SLOTS = (45..53).toList()

    /** The column of the content grid a slot sits in, or null when it is outside the grid. */
    fun columnOf(slot: Int): Int? = slot.takeIf { it in 0..53 }?.rem(9)

    /** The head slots of [columns] function pillars on the first content row. */
    fun headSlots(columns: List<Int>): List<Int> = columns.map { 9 + it }

    private fun shaftAt(style: CrystalStyle, row: Int): Material =
        if (row % 2 == 0) style.shaft else style.shaftAlt

    /** Chrome items are scenery: they must never show a name, so the tooltip stays about the button. */
    private fun pane(material: Material): ItemStack = ItemStack(material).also { item ->
        item.itemMeta = item.itemMeta?.apply { displayName(Component.empty()) }
    }

    private fun block(material: Material, glint: Boolean = false): ItemStack = ItemStack(material).also { item ->
        item.itemMeta = item.itemMeta?.apply { displayName(Component.empty()) }
        if (glint) item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1)
    }
}
