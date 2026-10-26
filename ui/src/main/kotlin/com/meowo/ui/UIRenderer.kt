package com.meowo.ui

import com.meowo.api.UIAPI
import com.meowo.api.UIBindings
import com.meowo.platform.MenuChrome
import com.meowo.platform.Messages
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import taboolib.module.ui.ClickEvent
import taboolib.module.ui.buildMenu
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.Chest
import taboolib.module.ui.type.PageableChest
import java.util.UUID

/**
 * The one place in the plugin that builds a menu.
 *
 * Everything else hands it data: a [UIScreen] from `ui.yml`, the elements of a list, the cells whose look
 * depends on state, and the callbacks for this screen's own buttons. That is what makes a screen editable
 * without a Kotlin change — and it is enforced, because `verifyArchitecture` fails if any other module
 * imports TabooLib's menu classes.
 *
 * ### The drawing order is the whole trick
 *
 * Items are placed chrome first, then the rail, then the pillars, then the configured buttons — all through
 * `set` on the same menu, where later writes win. So **a page can always claim a cell and keep it.** The
 * earlier version bound the rail last and painted over the page turns and the create button, which produced
 * a menu that looked right and could not be used. `onBuild` is deliberately not used for this: the paged menu
 * declares a different callback shape than the plain one, and depending on which overload wins is how that
 * bug happened in the first place.
 *
 * ### What cannot be tested here
 *
 * TabooLib needs a real server, so the order above is verified on a running one. What *is* tested without it:
 * the config model and its validation, the action grammar, and the one interpretation the renderer would
 * otherwise hide — that `@back` means "previous page" on a list (see [UIEffect]).
 */
class UIRenderer(private val messages: Messages) {

    private val sessions = mutableMapOf<UUID, Session>()

    private data class Session(
        val screen: UIScreen,
        val inventory: Inventory,
        val overrides: Map<Int, UIAPI.UIElement>,
        val bindings: UIBindings,
    )

    /**
     * Builds [screen] for [player] and opens it.
     *
     * [elements] fill [UIScreen.contentSlots] and the framework pages them. [overrides] replace the cells
     * whose content depends on state — a label that follows the session, a toggle's dye — while an overridden
     * cell keeps the config's action unless the override names another. [bindings] supply what this screen's
     * own callbacks do; [onPage] is told when a list turns a page.
     */
    fun open(
        player: Player,
        screen: UIScreen,
        elements: List<UIAPI.UIElement> = emptyList(),
        page: Int = 0,
        overrides: Map<Int, UIAPI.UIElement> = emptyMap(),
        bindings: UIBindings = UIBindings(),
        onPage: (Player, Int) -> Unit = { _, _ -> },
        titleOverride: String? = null,
        values: Map<String, String> = emptyMap(),
    ): Inventory {
        val inventory = if (screen.isList) {
            openList(player, screen, elements, page, overrides, bindings, onPage, titleOverride, values)
        } else {
            openStatic(player, screen, overrides, bindings, titleOverride, values)
        }
        sessions[player.uniqueId] = Session(screen, inventory, overrides, bindings)
        player.openMenu(inventory)
        return inventory
    }

    /**
     * Rewrites the cells whose content depends on state, leaving the window where it is.
     *
     * Reopening would also *work*, and is what the framework makes easiest, but it swaps the inventory out
     * from under the cursor: on a click whose whole point is that one crystal changed, that reads as a flash.
     */
    fun refresh(viewer: UUID, overrides: Map<Int, UIAPI.UIElement>) {
        val session = sessions[viewer] ?: return
        val player = session.inventory.viewers.filterIsInstance<Player>().firstOrNull() ?: return
        if (player.openInventory.topInventory !== session.inventory) return
        // An override carries content only; the config keeps owning the click, so a refresh cannot drop a
        // cell's action by accident.
        overrides.forEach { (slot, element) -> session.inventory.setItem(slot, item(player, element, slot)) }
        sessions[viewer] = session.copy(overrides = session.overrides + overrides)
    }

    /** The id of the screen [viewer] has open, or null. */
    fun openScreen(viewer: UUID): String? = sessions[viewer]?.screen?.id

    fun forget(viewer: UUID) {
        sessions.remove(viewer)
    }

    private fun openStatic(
        player: Player,
        screen: UIScreen,
        overrides: Map<Int, UIAPI.UIElement>,
        bindings: UIBindings,
        titleOverride: String?,
        values: Map<String, String>,
    ): Inventory = buildMenu<Chest>(titleOverride ?: title(player, screen)) {
        rows(screen.rows)
        handLocked(true)
        onClick(lock = true)

        MenuChrome.frame(screen.rows).forEach { (slot, chrome) -> set(slot, chrome) }
        defaults(player, screen).forEach { (slot, cell) -> set(slot, cell) }
        pillars(screen).forEach { (slot, cell) -> set(slot, cell) }

        screen.buttons.values.filter { visible(player, it, overrides[it.slot]) }.forEach { button ->
            // An override may replace the whole cell — the pomodoro start pillar says "resume" and *does*
            // resume while the timer is held — or only its content, leaving the config's action in place.
            // A blank action is how it says "I am only changing what this cell says".
            val element = UIButtons.withActionFrom(overrides[button.slot] ?: button.asElement(), button)
            set(button.slot, item(player, element, button.slot))
            onClick(button.slot) { click ->
                dispatch(player, effectOf(gestureOf(element, click), screen), bindings, click, element, -1, values)
            }
        }
        // A cell the module supplies that the config did not declare: content only, and no click — an override
        // says what a cell *shows*, not what it does.
        overrides.filterKeys { screen.button(it) == null && it in 0 until screen.rows * 9 }
            .forEach { (slot, element) -> set(slot, item(player, element, slot)) }
    }

    private fun openList(
        player: Player,
        screen: UIScreen,
        elements: List<UIAPI.UIElement>,
        page: Int,
        overrides: Map<Int, UIAPI.UIElement>,
        bindings: UIBindings,
        onPage: (Player, Int) -> Unit,
        titleOverride: String?,
        values: Map<String, String>,
    ): Inventory {
        val perPage = screen.contentSlots.size
        // The arithmetic lives in `UIPaging`, where it can be checked without a server.
        val pages = UIPaging.pages(elements.size, perPage)
        val current = UIPaging.clamp(page, pages)
        return buildMenu<PageableChest<UIAPI.UIElement>>(
            titleOverride ?: title(player, screen, current + 1, pages),
        ) {
            rows(screen.rows)
            handLocked(true)
            menuLocked(true)

            MenuChrome.frame(screen.rows).forEach { (slot, chrome) -> set(slot, chrome) }
            defaults(player, screen).forEach { (slot, cell) -> set(slot, cell) }
            // Placeholders first; the framework overwrites the cells an element lands on, so an unused cell
            // reads as room for an entry rather than as a hole.
            screen.contentSlots.forEach { slot -> set(slot, MenuChrome.socket()) }

            slots(screen.contentSlots)
            elements { elements }
            page(current)
            onGenerate { _, element, _, slot -> item(player, overrides[slot] ?: element, slot) }
            onPageChange { viewer -> onPage(viewer, this.page) }
            onClick { event, element ->
                // A list cell may carry three gestures. The index is the element's place in the whole list,
                // which is what a command argument needs, not the page it happens to be on.
                val index = elements.indexOf(element)
                val gesture = gestureOf(element, event)
                dispatch(player, effectOf(gesture, screen), bindings, event, element, index, values)
            }

            screen.buttons.values.filter { visible(player, it, overrides[it.slot]) }.forEach { button ->
                when (effectOf(button.action, screen)) {
                    // The framework owns the turn, including whether it is available at all.
                    UIEffect.PagePrevious -> setPreviousPage(button.slot, true) { _, _ ->
                        item(player, overrides[button.slot] ?: button.asElement(), button.slot)
                    }
                    UIEffect.PageNext -> setNextPage(button.slot, true) { _, _ ->
                        item(player, overrides[button.slot] ?: button.asElement(), button.slot)
                    }
                    else -> {
                        val element = UIButtons.withActionFrom(overrides[button.slot] ?: button.asElement(), button)
                        set(button.slot, item(player, element, button.slot))
                        onClick(button.slot) { click ->
                            dispatch(player, effectOf(gestureOf(element, click), screen), bindings, click, element, -1, values)
                        }
                    }
                }
            }
        }
    }

    /**
     * The rail and plinth, for the cells the config left alone.
     *
     * This is style, not screen data: every Meowo menu has the same bottom row, so it is drawn even when a
     * screen's file forgets to declare it. A screen that *does* declare a rail cell keeps its own wording.
     */
    private fun defaults(player: Player, screen: UIScreen): Map<Int, ItemStack> {
        if (screen.rows != MenuChrome.ROW_RANGE.last) return emptyMap()
        val out = linkedMapOf<Int, ItemStack>()
        MenuChrome.PLINTH_SLOTS.filter { screen.button(it) == null }.forEach { slot ->
            out[slot] = MenuChrome.plinthTile(
                label(player, screen.plinthName.ifBlank { "menu-item-main-menu" }),
                listOf(label(player, screen.plinthLore.ifBlank { "menu-item-main-menu-lore" })),
            )
        }
        MenuChrome.RAIL_GLASS_SLOTS.filter { screen.button(it) == null }.forEach { slot ->
            out[slot] = MenuChrome.railTile(
                label(player, screen.railName.ifBlank { "menu-item-back-up" }),
                listOf(label(player, screen.railLore.ifBlank { "menu-item-back-up-lore" })),
            )
        }
        return out
    }

    /** A configured function on the head row stands on the house pillar. Style, so not in the config. */
    private fun pillars(screen: UIScreen): Map<Int, ItemStack> {
        // A screen that draws its own shape says so; the colonnade would cut across it.
        if (!screen.pillars) return emptyMap()
        val heads = MenuChrome.headSlots((1..7).toList()).toSet()
        val out = linkedMapOf<Int, ItemStack>()
        screen.buttons.values.filter { it.slot in heads }.forEach { button ->
            MenuChrome.pillar(button.slot).forEach { (slot, shaft) -> out[slot] = shaft }
        }
        return out
    }

    /**
     * The window title, as text.
     *
     * A title is not an item name: TabooLib takes a String and reads its codes, so this path stays legacy
     * rather than going through a component. A list title carries its page numbers.
     */
    private fun title(player: Player, screen: UIScreen, page: Int = 1, pages: Int = 1) =
        UIText.toLegacy(screen.title) { key ->
            messages.text(player, key, *if (screen.isList) arrayOf(page, pages) else emptyArray())
        }

    /**
     * Which of a cell's actions the click means.
     *
     * The rule itself lives in [UIButtons.gesture], where it can be checked without a server; this only
     * reads the two flags off the click.
     */
    private fun gestureOf(element: UIAPI.UIElement, click: ClickEvent?): String {
        val raw = click?.clickEventOrNull()
        return UIButtons.gesture(element, shift = raw?.isShiftClick == true, right = raw?.isRightClick == true)
    }

    private fun effectOf(action: String, screen: UIScreen): UIEffect = UIEffect.of(UIAction.parse(action), screen)

    private fun dispatch(
        player: Player,
        effect: UIEffect,
        bindings: UIBindings,
        click: ClickEvent?,
        element: UIAPI.UIElement? = null,
        index: Int = -1,
        values: Map<String, String> = emptyMap(),
    ) {
        when (effect) {
            // The command may name the element it belongs to: `pomodoro start {index}`.
            is UIEffect.Command -> player.performCommand(UICommand.fill(effect.command, element, index, values))
            UIEffect.Close -> (bindings.onClose ?: { player.closeInventory() }).invoke()
            UIEffect.Back -> (bindings.onBack ?: { player.closeInventory() }).invoke()
            // Closing is the fallback: a screen that offers "main menu" without a handler should still get the
            // player out rather than leaving the click dead.
            UIEffect.MainMenu -> (bindings.onMainMenu ?: { player.performCommand("menu") }).invoke()
            UIEffect.Confirm -> bindings.onConfirm?.invoke()
            UIEffect.Cancel -> (bindings.onCancel ?: bindings.onBack)?.invoke()
            // Page turns are the framework's, and a readout does nothing at all.
            UIEffect.PagePrevious, UIEffect.PageNext, UIEffect.None -> Unit
        }
    }

    private fun label(player: Player, raw: String) =
        UIText.resolve(raw, NamedTextColor.GRAY) { key -> messages.component(player, key) }

    private fun visible(player: Player, button: UIButton, override: UIAPI.UIElement?): Boolean =
        UIButtons.visible(button, override, player::hasPermission)

    private fun UIButton.asElement() =
        UIAPI.UIElement(icon, name, lore, action, glint, actionRight, actionShift, permission, color)


    private fun item(player: Player, element: UIAPI.UIElement, slot: Int): ItemStack {
        // An unknown icon is a config error the loader catches; this fallback exists so an element a *module*
        // supplies at runtime cannot blank a cell out entirely.
        val material = Material.matchMaterial(element.icon.uppercase()) ?: Material.STONE
        return ItemStack(material).also { stack ->
            // A cell may carry its own colour, which is how a home keeps the colour its owner chose.
            val name = UIText.resolve(element.name, NamedTextColor.LIGHT_PURPLE) { messages.component(player, it) }
            stack.itemMeta = stack.itemMeta?.apply {
                // Read once: a cross-module property cannot be smart-cast.
                val colour = element.color
                displayName(if (colour == null) name else name.color(TextColor.color(colour)))
                lore(
                    element.lore.mapNotNull {
                        UIText.resolveLore(it, NamedTextColor.GRAY) { key -> messages.component(player, key) }
                    },
                )
            }
            if (element.glint) stack.addUnsafeEnchantment(Enchantment.UNBREAKING, 1)
        }
    }
}
