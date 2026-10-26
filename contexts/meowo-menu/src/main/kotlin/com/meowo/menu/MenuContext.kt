package com.meowo.menu

import com.meowo.api.AvatarAPI
import com.meowo.api.HomeMenuAPI
import com.meowo.api.MenuAPI
import com.meowo.api.UIAPI
import com.meowo.api.UIBindings
import com.meowo.api.event.AvatarChanged
import com.meowo.platform.PlatformHost
import com.meowo.platform.Route
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.plugin.ServicePriority
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The main menu and the homes overview.
 *
 * Both screens are declared in `ui.yml`; this context supplies only what a config file cannot know — which
 * homes a player owns, how far away they are, what colour each one is drawn in — and every cell's action is
 * a command, so a menu shortcut and a typed command take the same path. Menu owns composition no longer:
 * that moved to the `ui` module, which is why this file has no item-building left in it.
 *
 * The layout for both screens used to live in `config.yml` under `menu.items`. See `docs/ui-module.md`.
 */
class MenuContext(private val host: PlatformHost) : Context, Listener, MenuAPI {
    override val id = "menu"
    private val active = AtomicBoolean(false)
    private val resources = mutableListOf<AutoCloseable>()

    /** Resolved per open, so the composition root's ordering cannot leave a stale null behind. */
    private val ui: () -> UIAPI? = { Bukkit.getServicesManager().load(UIAPI::class.java) }

    override fun start() {
        active.set(true)
        Bukkit.getPluginManager().registerEvents(this, host.plugin)
        Bukkit.getServicesManager().register(MenuAPI::class.java, this, host.plugin, ServicePriority.Normal)
        resources += AutoCloseable {
            HandlerList.unregisterAll(this)
            Bukkit.getServicesManager().unregister(MenuAPI::class.java, this)
        }
        resources += host.commands.register("menu", "menu", object : Route {
            override val permission = "meowo.menu"
            override fun execute(sender: CommandSender, args: List<String>) {
                if (sender !is Player) host.messages.send(sender, "player-only")
                else if (args.firstOrNull() == "homes") {
                    openHomes(sender.uniqueId, args.getOrNull(1)?.toIntOrNull()?.minus(1) ?: 0)
                } else {
                    main(sender)
                }
            }
        })
        // A cell that says which way a switch is set has to be redrawn when the switch moves. The switch
        // lives in the `avatar` context and this one may not import it (红线 §2), so the change arrives as
        // a notification and the menu redraws itself.
        resources += host.events.subscribe(AvatarChanged::class.java) { event ->
            val player = Bukkit.getPlayer(event.player) ?: return@subscribe
            if (!active.get() || !player.isOnline) return@subscribe
            // Only the menu is repainted, and only if the player is looking at it: the UI service drops a
            // repaint for a screen that is not on show, so a player who walked off to another screen is
            // left alone.
            ui()?.refresh(event.player, SCREEN_MENU, avatarCells(player))
        }
    }

    private fun main(player: Player) {
        if (!active.get() || !player.isOnline) return
        ui()?.open(
            player.uniqueId, SCREEN_MENU,
            overrides = avatarCells(player),
            bindings = UIBindings(
                onClose = { player.closeInventory() },
                // The bedrock floor leads to the main menu; on the main menu itself that is a reopen, which
                // is what the floor means everywhere else and is harmless here.
                onMainMenu = { main(player) },
            ),
        )
    }

    /**
     * The two cells whose **content** depends on the player's own avatar.
     *
     * The layout, the icons and the actions are the config's; what changes from one player to the next
     * is whether the night phantoms are looking for them and how big the player is. That is state the
     * config cannot know, so the context supplies it — and only it, so the screen stays editable in
     * `ui.yml`.
     *
     * When the `avatar` context is not running there is no service to ask, and the two cells are
     * **hidden** rather than left showing a state nobody can change: a button that answers "unknown
     * command" teaches a player that the menu lies.
     *
     * ### Why the names are keys and not text
     *
     * A name that looks like a language key is resolved by the UI module, and **that** is the path
     * that understands the `§` colour codes. A name handed over as finished text goes down the
     * literal path instead, which reads `&` codes — so a resolved `§`-coded string would reach the
     * player with its codes still in it. The keys are therefore passed as keys, exactly as the
     * pomodoro screen passes its toggle labels.
     */
    private fun avatarCells(player: Player): Map<Int, UIAPI.UIElement> {
        val avatar = Bukkit.getServicesManager().load(AvatarAPI::class.java)
            ?: return mapOf(
                // Hidden, not blank: a hidden cell is not drawn at all, so the slot goes back to the
                // chrome. The icon and the name are placeholders that are never read.
                CELL_PHANTOM to UIAPI.UIElement(icon = "STONE", name = "hidden", hidden = true),
                CELL_SIZE to UIAPI.UIElement(icon = "STONE", name = "hidden", hidden = true),
            )
        val blocked = avatar.phantomsBlocked(player.uniqueId)
        val scale = avatar.scaleOf(player.uniqueId)
        return mapOf(
            CELL_PHANTOM to UIAPI.UIElement(
                icon = "PHANTOM_MEMBRANE",
                name = if (blocked) "menu-item-phantom-on" else "menu-item-phantom-off",
                lore = listOf("menu-item-phantom-lore", "menu-item-phantom-hint"),
            ),
            CELL_SIZE to UIAPI.UIElement(
                icon = "SLIME_BLOCK",
                name = "menu-item-size",
                // The one line that carries a number, and therefore the one line that cannot be a key:
                // a key is looked up without arguments. Its text is plain, because a line that is
                // resolved here goes down the literal path, where `&` — not `§` — is the colour code.
                lore = listOf(text(player, "menu-item-size-lore", format(scale)), "menu-item-size-hint"),
            ),
        )
    }

    /** The size as a player reads it: `1`, `1.25`, `2.5` — and never a bare `1.00`. */
    private fun format(value: Double): String =
        java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()

    /**
     * The homes overview.
     *
     * A list, so it goes through `openList`: the config owns the frame, the paging and the rail; this builds
     * one element per home slot. Empty-but-owned slots are shown as placeholders that set a home, and slots
     * beyond the player's capacity are not shown at all — a list of things a player cannot have is noise.
     */
    override fun openHomes(owner: UUID, page: Int) {
        val player = Bukkit.getPlayer(owner) ?: return
        if (!player.hasPermission("meowo.home")) {
            host.messages.send(player, "no-permission")
            return
        }
        val service = Bukkit.getServicesManager().load(HomeMenuAPI::class.java)
            ?: run { host.messages.send(player, "unavailable"); return }
        val entries = service.entries(owner).associateBy { it.slot }
        val capacity = service.capacity(owner)
        val count = maxOf(capacity, entries.keys.maxOrNull() ?: 0)
        val pages = ((count + PER_PAGE - 1) / PER_PAGE).coerceAtLeast(1)
        val current = page.coerceIn(0, pages - 1)

        // Every home, not just this page's: the framework owns paging, and it can only do that — including
        // counting the pages for the title — if it is given the whole list. Handing it one page made the
        // title read "3/1" and left nothing to turn to.
        val elements = (1..count).map { slot ->
            val home = entries[slot]
            // `if`, not a subject-less `when`: without an `else` that is a *statement* in Kotlin, so the
            // lambda would type as Unit and the list would arrive empty.
            if (home == null) UIAPI.UIElement(
                    icon = "LIGHT_GRAY_DYE",
                    name = text(player, "menu-homes-empty", slot),
                    lore = listOf(text(player, "menu-homes-empty-lore1"), text(player, "menu-homes-empty-lore2")),
                    action = "meowo home gui set $slot",
                )
            else UIAPI.UIElement(
                    icon = home.item,
                    name = text(player, "menu-homes-entry", slot, home.name),
                    // The distance line differs by world, so the key is chosen first and the numbers stay
                    // placeholders rather than formatted text glued into the language file.
                    lore = buildList {
                        add(text(player, "menu-homes-lore-coords", home.world, home.x.toInt(), home.y.toInt(), home.z.toInt()))
                        if (home.world == player.world.name) {
                            add(
                                text(
                                    player, "menu-homes-lore-sameworld",
                                    kotlin.math.hypot(home.x - player.location.x, home.z - player.location.z).toInt(),
                                    (home.y - player.location.y).toInt(),
                                ),
                            )
                        } else {
                            add(text(player, "menu-homes-lore-crossworld"))
                        }
                        add(text(player, if (home.visible) "menu-homes-visible" else "menu-homes-hidden"))
                        add(text(player, if (home.style == "bowtie") "menu-homes-style-bowtie" else "menu-homes-style-default"))
                        add(text(player, "menu-homes-lore-hint"))
                    },
                    action = "meowo home gui $slot",
                    actionRight = "meowo home gui edit $slot",
                    // The colour the owner chose for this home; the config cannot carry it per element.
                    color = home.color,
            )
        }

        ui()?.openList(
            owner, SCREEN_HOMES, elements, page = current,
            overrides = buildMap {
                put(
                    CELL_SUMMARY,
                    UIAPI.UIElement(
                        icon = "PAPER",
                        name = text(player, "menu-homes-summary", entries.size, capacity, current + 1, pages),
                        lore = listOf(text(player, "menu-homes-capacity", "<N>")),
                        action = "@none",
                    ),
                )
                put(
                    CELL_NAV_OFF,
                    UIAPI.UIElement("COMPASS", text(player, "menu-item-nav-off"), emptyList(), "meowo home nav off"),
                )
            },
            bindings = UIBindings(
                onBack = { main(player) },
                onMainMenu = { main(player) },
                onClose = { player.closeInventory() },
            ),
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun swap(event: PlayerSwapHandItemsEvent) {
        if (!event.player.isSneaking || !event.player.hasPermission("meowo.menu")) return
        event.isCancelled = true
        main(event.player)
    }

    override fun close() {
        active.set(false)
        resources.asReversed().forEach { it.close() }
        resources.clear()
    }

    private fun text(player: Player, key: String, vararg args: Any): String = host.messages.text(player, key, *args)

    private companion object {
        /** The screen ids in `ui.yml`. */
        const val SCREEN_MENU = "menu"
        const val SCREEN_HOMES = "menu-homes"

        /** How many homes one page shows; must match `content-slots` in `ui.yml`. */
        const val PER_PAGE = 28

        /** The homes screen's own readouts, supplied by this context rather than the config. */
        const val CELL_SUMMARY = 49
        const val CELL_NAV_OFF = 48

        /**
         * The main menu's two avatar cells, in the heart.
         *
         * They are the cells whose content depends on the player, so the context fills them in. The
         * numbers are the heart's, not the menu's: see `ui.yml`.
         */
        const val CELL_PHANTOM = 30
        const val CELL_SIZE = 32
    }
}
