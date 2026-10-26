package com.meowo.pomodoro.interfaces

import com.meowo.api.UIAPI
import com.meowo.platform.Messages
import com.meowo.pomodoro.domain.PomodoroSession
import com.meowo.pomodoro.domain.Preset
import org.bukkit.entity.Player

/**
 * The pomodoro screen's **variable** cells, as data.
 *
 * Layout, icons and wording live in `ui.yml`; this supplies only what the config cannot know: the label that
 * changes with the session, a toggle's dye, the running readout, the presets a player actually has. Passing
 * them as [UIAPI.UIElement] rather than as `ItemStack` is what keeps this module out of the rendering
 * business — it says what a cell *says*, never how a menu is built.
 *
 * ### Why some values carry a colour code
 *
 * A name is treated as a language key when it looks like one (`pomodoro-action-start`). Player data — a
 * preset's own name — can look like that too, and a preset called `pause` would otherwise be looked up in the
 * language file. Data-derived strings therefore carry an explicit `§f`, which both marks them as literal and
 * is the colour they were going to get anyway.
 */
class PomodoroCells(private val messages: Messages) {

    /** The session's own readout: what is running, or that nothing is. */
    fun status(session: PomodoroSession?): UIAPI.UIElement {
        if (session == null || !session.isActive) {
            return element(MaterialNames.AMETHYST_BLOCK, "pomodoro-status-idle", listOf("pomodoro-status-idle-lore"))
        }
        return element(
            MaterialNames.AMETHYST_BLOCK, "pomodoro-status-name",
            listOf("pomodoro-status-running", "pomodoro-status-hint"),
        )
    }

    /** The start pillar, which becomes "resume" while the timer is held. */
    fun start(session: PomodoroSession?): UIAPI.UIElement {
        val paused = session?.state == com.meowo.pomodoro.domain.SessionState.PAUSED
        return UIAPI.UIElement(
            icon = if (paused) "LIME_DYE" else "AMETHYST_CLUSTER",
            name = if (paused) "pomodoro-action-resume" else "pomodoro-action-start",
            lore = listOf(
                if (paused) "pomodoro-do-resume" else "pomodoro-do-start",
                if (session?.isActive == true) "pomodoro-do-start-replaces" else "",
            ).filter { it.isNotEmpty() },
            // Resume a held timer; otherwise the pillar's job is to reach a preset, which is the gallery.
            action = if (paused) "pomodoro resume" else "pomodoro presets",
            glint = true,
        )
    }

    /** A readout switch: the same crystal is clicked to turn it off and on, so the verb is `toggle`. */
    fun toggle(on: Boolean, knob: String): UIAPI.UIElement = UIAPI.UIElement(
        icon = if (on) "LIME_DYE" else "GRAY_DYE",
        name = "pomodoro-toggle-$knob-" + if (on) "on" else "off",
        lore = listOf("pomodoro-click-left"),
        action = "pomodoro visuals $knob toggle",
    )

    /** The gallery's readout: how many presets, on which page, and which gestures apply. */
    fun galleryCore(player: Player, count: Int, limit: Int, page: Int, pages: Int): UIAPI.UIElement =
        UIAPI.UIElement(
            icon = "AMETHYST_BLOCK",
            name = "pomodoro-gallery-core",
            lore = listOf(
                text(player, "pomodoro-gallery-count", count, limit),
                text(player, "pomodoro-gallery-page", page, pages),
                text(player, "pomodoro-gallery-hints"),
            ),
            action = "@back",
            glint = true,
        )

    /**
     * A preset, with its three gestures.
     *
     * `{index}` rather than the name: the cell was clicked in a list, and the index is what the command
     * vocabulary takes. The name is for the player to read, the index is for the button to send.
     */
    fun preset(player: Player, preset: Preset, index: Int): UIAPI.UIElement {
        val builtin = preset.builtin
        val badge = text(player, if (builtin) "pomodoro-preset-badge-builtin" else "pomodoro-preset-badge-custom")
        return UIAPI.UIElement(
            icon = preset.icon.uppercase(),
            name = "$badge §f${preset.name}",
            lore = listOf(
                text(player, "pomodoro-preset-work", preset.workSeconds / 60),
                text(player, "pomodoro-preset-break", preset.breakSeconds / 60),
                text(player, "pomodoro-preset-long-break", preset.longBreakSeconds / 60),
                text(player, "pomodoro-preset-sessions", preset.sessions),
                text(player, "pomodoro-gallery-start-hint"),
                text(player, if (builtin) "pomodoro-gallery-copy-hint" else "pomodoro-gallery-edit-hint"),
            ),
            action = "pomodoro preset start {index}",
            glint = builtin || preset.enchanted,
            actionRight = if (builtin) "pomodoro preset copy {index}" else "pomodoro preset edit {index}",
            actionShift = if (builtin) null else "pomodoro preset delete {index}",
        )
    }

    /** The editor's preview: the preset as it will look once saved. */
    fun preview(player: Player, preset: Preset, drafting: Boolean): UIAPI.UIElement = UIAPI.UIElement(
        icon = preset.icon.uppercase(),
        name = "pomodoro-editor-preview",
        lore = listOf(
            text(player, "pomodoro-preset-work", preset.workSeconds / 60),
            text(player, "pomodoro-preset-break", preset.breakSeconds / 60),
            text(player, "pomodoro-preset-long-break", preset.longBreakSeconds / 60),
            text(player, "pomodoro-preset-sessions", preset.sessions),
            if (drafting) text(player, "pomodoro-editor-drafting") else "",
        ).filter { it.isNotEmpty() },
        action = "@back",
        glint = true,
    )

    /**
     * The bottom-right cell on the last page.
     *
     * There is no next page to turn to, so the cell carries the position instead of an arrow — the paging
     * spot is never empty and never unexplained.
     */
    fun lastPageTile(player: Player, page: Int, pages: Int): UIAPI.UIElement = UIAPI.UIElement(
        icon = "PURPLE_STAINED_GLASS_PANE",
        name = text(player, "pomodoro-gallery-page", page, pages),
        lore = listOf(text(player, "pomodoro-do-prev")),
        action = "@page-previous",
    )

    private fun element(icon: String, name: String, lore: List<String>) =
        UIAPI.UIElement(icon, name, lore, "@back", glint = true)

    private fun text(player: Player, key: String, vararg args: Any): String = messages.text(player, key, *args)

    private object MaterialNames {
        const val AMETHYST_BLOCK = "AMETHYST_BLOCK"
    }
}
