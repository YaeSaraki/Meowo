package com.meowo.ui

import com.meowo.api.UIAPI
import com.meowo.api.UIBindings
import com.meowo.platform.PlatformHost
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * The `UIAPI` service: resolves a viewer, keeps the layout, and hands the work to [UIRenderer].
 *
 * This is the seam a module calls. It takes a [java.util.UUID] rather than a `Player` because the `api`
 * module is a pure module — it may not import Bukkit — and that constraint turns out to be useful anyway:
 * a caller cannot accidentally hold a stale `Player`, and everything happens on the main thread because
 * menus must.
 *
 * Paging state lives here rather than in the renderer: the renderer rebuilds a window from scratch, while
 * "which page was this player on" has to outlive one open so a `@back` from a detail view returns to the
 * page the player was reading.
 */
class UIService(
    private val host: PlatformHost,
    private val layout: UILayout,
    private val renderer: UIRenderer = UIRenderer(host.messages),
) : UIAPI, AutoCloseable {

    /**
     * Where each reader was on each list.
     *
     * The memory itself lives in [UIPageMemory], outside this service, so the rule — "the caller's page
     * wins when it names one, otherwise resume where the reader left off" — can be checked without a
     * server. It used to be a map and an expression in here, which meant it could not be.
     */
    private val pages = UIPageMemory()

    /** The screen every confirmation uses; an operator may rename it in `ui.yml`. */
    var confirmScreen: String = CONFIRM_SCREEN

    override fun open(
        viewer: java.util.UUID,
        screen: String,
        overrides: Map<Int, UIAPI.UIElement>,
        bindings: UIBindings,
        title: String?,
        values: Map<String, String>,
    ) {
        val def = layout.require(screen)
        onPlayer(viewer) { player ->
            renderer.open(
                player, def,
                overrides = overrides,
                bindings = bindings,
                onPage = { who, page -> pages.remember(who.uniqueId, screen, page) },
                titleOverride = title,
                values = values,
            )
        }
    }

    override fun openList(
        viewer: java.util.UUID,
        screen: String,
        elements: List<UIAPI.UIElement>,
        page: Int,
        overrides: Map<Int, UIAPI.UIElement>,
        bindings: UIBindings,
        values: Map<String, String>,
    ) {
        val def = layout.require(screen)
        require(def.isList) { "screen '$screen' has no content-slots, so it cannot take a list; see ui.yml" }
        onPlayer(viewer) { player ->
            renderer.open(
                player, def,
                elements = elements,
                // The caller's page wins when it names one; otherwise resume where the player left off.
                page = pages.resume(viewer, screen, page),
                overrides = overrides,
                bindings = bindings,
                onPage = { who, changed -> pages.remember(who.uniqueId, screen, changed) },
                values = values,
            )
        }
    }

    override fun refresh(viewer: java.util.UUID, screen: String, overrides: Map<Int, UIAPI.UIElement>) {
        // A repaint of a window that is not showing this screen is a no-op rather than an error: the player
        // may have walked away from it while a timer was still ticking.
        if (renderer.openScreen(viewer) != screen) return
        host.mainThread({ Bukkit.getPlayer(viewer)?.isOnline == true }) { renderer.refresh(viewer, overrides) }
    }

    override fun confirm(
        viewer: java.util.UUID,
        title: String,
        detail: String,
        onYes: () -> Unit,
        onNo: () -> Unit,
    ) {
        val def = layout.require(confirmScreen)
        onPlayer(viewer) { player ->
            renderer.open(
                player, def,
                titleOverride = title,
                // The question goes on both pillars: whichever one the player is reading, it says what is
                // about to happen. The icons and labels stay the config's.
                overrides = def.buttons.values.associate { button ->
                    button.slot to UIAPI.UIElement(button.icon, button.name, listOf(detail), button.action, button.glint)
                },
                bindings = UIBindings(onConfirm = onYes, onCancel = onNo, onBack = onNo),
            )
        }
    }

    override fun close() {
        pages.clear()
    }

    private fun onPlayer(viewer: java.util.UUID, action: (Player) -> Unit) {
        host.mainThread({ Bukkit.getPlayer(viewer)?.isOnline == true }) {
            Bukkit.getPlayer(viewer)?.let(action)
        }
    }

    private companion object {
        const val CONFIRM_SCREEN = "confirm"
    }
}
