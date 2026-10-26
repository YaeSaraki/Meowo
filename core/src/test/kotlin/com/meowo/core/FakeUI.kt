package com.meowo.core

import com.meowo.api.UIAPI
import com.meowo.api.UIBindings
import com.meowo.platform.MenuChrome
import com.meowo.platform.Messages
import com.meowo.ui.UIButton
import com.meowo.ui.UIButtons
import com.meowo.ui.UICommand
import com.meowo.ui.UILoader
import com.meowo.ui.UIPaging
import com.meowo.ui.UIScreen
import com.meowo.ui.UIText
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * A stand-in for the UI service that records what a context asked for, and draws it.
 *
 * Named for what it is. It replaced a test-only *mirror* of the old TabooLib adapter — a second copy of the
 * layout that had to be kept in step by hand, and did not stay in step.
 *
 * The production renderer needs a real server (TabooLib), so a test cannot use it — but it still has to know
 * what a screen would look like, because the questions worth asking are things like "is the exit named" and
 * "does the start pillar say *resume* while the timer is held".
 *
 * So this draws from the **shipped `ui.yml`**, the same file the renderer reads, plus the overrides and
 * elements the context supplies. That is the important difference from the mirror it replaces: the layout is
 * never restated here, so it cannot drift from the config — a screen that moves a cell moves it for both.
 * The drawing order matches the renderer's (chrome, rail, pillars, buttons) for the same reason.
 *
 * Clicks are dispatched the way the renderer dispatches them: a `@`-action through the bindings, anything
 * else as a command, with `{index}`/`{name}` filled from the element that was clicked.
 */
class FakeUI(private val messages: Messages) : UIAPI {

    /** A cell as drawn: the item, and the action each gesture would run. */
    class Cell(val item: ItemStack, val action: String, val right: String?, val shift: String?)

    class Page(val title: String, val rows: Int, val buttons: Map<Int, Cell>)

    var page: Page? = null
    var opens = 0
    var refreshes = 0
    var elements: List<UIAPI.UIElement> = emptyList()
    var lastScreen: String? = null

    /**
     * How a command action is run.
     *
     * The production renderer calls `player.performCommand`, but MockBukkit's dispatch adds the alias prefix a
     * second time, so the tests route through the same `CommandRouter` path they use for typed commands. The
     * point being tested is which command a cell names, not Bukkit's argument splitting.
     *
     * The **viewer** is handed over as well, because that is who the renderer runs the command as: a cell
     * that belongs to one player must not be answered on another player's behalf. A double that always
     * answered as the same player would quietly turn every second player's click into the first player's.
     */
    var dispatch: (UUID, String) -> Unit = { _, _ -> }

    /** The per-open values the renderer would fill into commands. */
    private var values: Map<String, String> = emptyMap()

    private val layout = UILoader.load(javaClass.getResourceAsStream("/ui.yml")!!, null)
    private var bindings = UIBindings()
    private var overrides: Map<Int, UIAPI.UIElement> = emptyMap()
    private var current: UIScreen? = null
    private var titleOverride: String? = null

    override fun open(
        viewer: UUID,
        screen: String,
        overrides: Map<Int, UIAPI.UIElement>,
        bindings: UIBindings,
        title: String?,
        values: Map<String, String>,
    ) {
        opens++
        this.viewer = viewer
        this.bindings = bindings
        this.overrides = overrides
        this.elements = emptyList()
        this.titleOverride = title
        this.values = values
        draw(screen)
    }

    override fun openList(
        viewer: UUID,
        screen: String,
        elements: List<UIAPI.UIElement>,
        page: Int,
        overrides: Map<Int, UIAPI.UIElement>,
        bindings: UIBindings,
        values: Map<String, String>,
    ) {
        opens++
        this.viewer = viewer
        this.bindings = bindings
        this.overrides = overrides
        this.elements = elements
        this.titleOverride = null
        this.values = values
        draw(screen, page)
    }

    override fun refresh(viewer: UUID, screen: String, overrides: Map<Int, UIAPI.UIElement>) {
        refreshes++
        // A repaint rewrites cells of the menu already open, so the screen and title stay as they were.
        this.overrides = this.overrides + overrides
        draw(screen)
    }

    override fun confirm(viewer: UUID, title: String, detail: String, onYes: () -> Unit, onNo: () -> Unit) {
        opens++
        this.viewer = viewer
        this.bindings = UIBindings(onConfirm = onYes, onCancel = onNo, onBack = onNo)
        this.overrides = layout.require(CONFIRM).buttons.values.associate { button ->
            button.slot to UIAPI.UIElement(button.icon, button.name, listOf(detail), button.action, button.glint)
        }
        this.titleOverride = title
        this.elements = emptyList()
        draw(CONFIRM)
    }

    /**
     * Performs a click the way the renderer would.
     *
     * The gesture rule is [UIButtons.gesture]'s, not a copy of it: shift wins over right, so a shift+right
     * click still counts as the shift gesture. It used to be written out here, and a second copy of one
     * rule is one rule too many.
     */
    fun click(slot: Int, right: Boolean = false, shift: Boolean = false) {
        val cell = page?.buttons?.get(slot) ?: return
        val action = UIButtons.gesture(
            UIAPI.UIElement(icon = "STONE", name = "", lore = emptyList(), action = cell.action,
                actionRight = cell.right, actionShift = cell.shift),
            shift = shift,
            right = right,
        )
        val element = elementAt(slot)
        if (action.startsWith("@")) {
            when (action) {
                "@close" -> bindings.onClose?.invoke()
                "@back" -> bindings.onBack?.invoke()
                "@main-menu" -> bindings.onMainMenu?.invoke()
                "@confirm" -> bindings.onConfirm?.invoke()
                "@cancel" -> bindings.onCancel?.invoke()
                // The framework owns the page turns; nothing to dispatch in a recording double.
                "@page-previous", "@page-next" -> Unit
            }
            return
        }
        dispatch(viewer ?: return, UICommand.fill(action, element, elementIndex(slot), values))
    }

    private fun elementIndex(slot: Int): Int {
        val screen = current ?: return -1
        val position = screen.contentSlots.indexOf(slot)
        if (position < 0) return -1
        val perPage = screen.contentSlots.size
        return UIPaging.index(lastPage, perPage, position)
    }

    private var lastPage = 0

    /** Whose screen this is: the viewer's language decides the wording. */
    private var viewer: java.util.UUID? = null

    private fun elementAt(slot: Int): UIAPI.UIElement? {
        val screen = current ?: return null
        val position = screen.contentSlots.indexOf(slot)
        if (position < 0) return null
        val perPage = screen.contentSlots.size
        return elements.getOrNull(UIPaging.index(lastPage, perPage, position))
    }

    // ── drawing ──────────────────────────────────────────────────────────────────────────────

    private fun draw(screenId: String, pageNumber: Int = 0) {
        val screen = layout.require(screenId)
        // The language of the player who is looking, not the server default: the renderer resolves every
        // line per player, and a test asserting an English client's title needs the same.
        val viewer = this.viewer?.let { Bukkit.getPlayer(it) }
        current = screen
        lastPage = pageNumber
        lastScreen = screenId

        val cells = linkedMapOf<Int, Cell>()
        MenuChrome.frame(screen.rows).forEach { (slot, chrome) -> cells[slot] = Cell(chrome, "", null, null) }

        if (screen.rows == MenuChrome.ROW_RANGE.last) {
            MenuChrome.PLINTH_SLOTS.filter { screen.button(it) == null }.forEach { slot ->
                cells[slot] = Cell(plinth(screen, viewer), "@main-menu", null, null)
            }
            MenuChrome.RAIL_GLASS_SLOTS.filter { screen.button(it) == null }.forEach { slot ->
                cells[slot] = Cell(rail(screen, viewer), "@back", null, null)
            }
        }

        // Head-row functions stand on the house pillar, exactly as the renderer draws them — unless the
        // screen draws a shape of its own and says so.
        if (screen.pillars) {
            screen.buttons.values.filter { it.slot in MenuChrome.headSlots((1..7).toList()) }.forEach { button ->
                MenuChrome.pillar(button.slot).forEach { (slot, shaft) -> cells[slot] = Cell(shaft, "", null, null) }
            }
        }

        screen.contentSlots.forEach { slot -> cells[slot] = Cell(MenuChrome.socket(), "", null, null) }
        if (screen.isList) {
            val perPage = screen.contentSlots.size
            elements.drop(pageNumber * perPage).take(perPage).forEachIndexed { offset, element ->
                val slot = screen.contentSlots[offset]
                val shown = overrides[slot] ?: element
                cells[slot] = Cell(item(shown, viewer), element.action, element.actionRight, element.actionShift)
            }
        }

        // A cell the module hid is not drawn at all, exactly as the renderer decides it: the slot goes
        // back to the chrome rather than showing a button whose only answer would be "no permission".
        screen.buttons.values.filter { visible(button = it, override = overrides[it.slot], viewer = viewer) }.forEach { button ->
            val shown = overrides[button.slot] ?: UIAPI.UIElement(
                button.icon, button.name, button.lore, button.action, button.glint, button.actionRight, button.actionShift,
            )
            // The override replaces the cell's *content*; a blank action means "I am only changing what this
            // cell says", so the config's action stays in place. That merge is [UIButtons.withActionFrom]'s,
            // the same one the renderer uses — dropping it would leave a cell that looks clickable and does
            // nothing, and a second copy of the rule is a second thing to get wrong.
            val merged = UIButtons.withActionFrom(shown, button)
            cells[button.slot] = Cell(
                item(merged, viewer),
                merged.action,
                merged.actionRight,
                merged.actionShift,
            )
        }
        // A cell the context supplies that the config did not declare: content only, no click.
        overrides.filterKeys { screen.button(it) == null && it !in screen.contentSlots }.forEach { (slot, element) ->
            cells[slot] = Cell(item(element, viewer), "", null, null)
        }

        // A list's title carries its page numbers, exactly as the renderer passes them. Only a list has
        // content slots to divide by, so the maths stays inside that branch.
        val title = titleOverride ?: UIText.toLegacy(screen.title) { key ->
            if (!screen.isList) {
                messages.text(viewer, key)
            } else {
                val per = screen.contentSlots.size
                val pages = UIPaging.pages(elements.size, per)
                messages.text(viewer, key, pageNumber + 1, pages)
            }
        }
        page = Page(title, screen.rows, cells)
    }

    /**
     * Whether a cell is drawn, as the renderer decides it.
     *
     * The rule is [UIButtons.visible]'s — the production one, not a copy of it. A cell the module **hid**
     * is not drawn at all, and a cell that names a permission is drawn only for a player who has it. The
     * permission check is what makes "everyone may use this by default" a claim the tests can check
     * rather than a claim in a comment.
     */
    private fun visible(button: UIButton, override: UIAPI.UIElement?, viewer: Player?): Boolean =
        UIButtons.visible(button, override) { node -> viewer?.hasPermission(node) == true }

    private fun item(element: UIAPI.UIElement, viewer: Player?): ItemStack = ItemStack(
        Material.matchMaterial(element.icon.uppercase()) ?: Material.STONE,
    ).also { stack ->
        stack.itemMeta = stack.itemMeta?.apply {
            displayName(UIText.resolve(element.name, NamedTextColor.LIGHT_PURPLE) { messages.component(viewer, it) })
            lore(
                element.lore.mapNotNull {
                    UIText.resolveLore(it, NamedTextColor.GRAY) { key -> messages.component(viewer, key) }
                },
            )
        }
    }

    private fun plinth(screen: UIScreen, viewer: Player?) = MenuChrome.plinthTile(
        UIText.resolve(screen.plinthName.ifBlank { "menu-item-main-menu" }, NamedTextColor.GRAY) { messages.component(viewer, it) },
        listOf(UIText.resolve(screen.plinthLore.ifBlank { "menu-item-main-menu-lore" }, NamedTextColor.GRAY) { messages.component(viewer, it) }),
    )

    private fun rail(screen: UIScreen, viewer: Player?) = MenuChrome.railTile(
        UIText.resolve(screen.railName.ifBlank { "menu-item-back-up" }, NamedTextColor.GRAY) { messages.component(viewer, it) },
        listOf(UIText.resolve(screen.railLore.ifBlank { "menu-item-back-up-lore" }, NamedTextColor.GRAY) { messages.component(viewer, it) }),
    )

    private companion object {
        const val CONFIRM = "confirm"
    }
}
