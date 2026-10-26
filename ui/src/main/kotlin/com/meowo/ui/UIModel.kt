package com.meowo.ui

import com.meowo.api.UIAPI.UIElement

/**
 * One button of a screen, exactly as the config declares it.
 *
 * A screen is data: which slot, which icon, what it says, and what it does. Nothing here knows about
 * TabooLib, about a player, or about the module on the other side of the action — that is the point of the
 * split, because it is what makes the whole layout editable in `ui.yml` without touching code.
 *
 * [action] is either a **command** (`"pomodoro start"`, run as the player so its permission still applies)
 * or one of the `@`-prefixed built-ins the UI module implements itself ([UIBuiltin]).
 */
data class UIButton(
    val slot: Int,
    val icon: String,
    val name: String,
    val lore: List<String> = emptyList(),
    val action: String,
    /** A glint marks the cell the screen wants the eye to land on first. */
    val glint: Boolean = false,
    /**
     * The other two gestures a cell may offer.
     *
     * A menu button is not one action: the gallery starts a preset on a left click, edits it on a right
     * click, and deletes it on shift+left. Without those, every such screen would have to stay in Kotlin,
     * which is exactly what this module exists to prevent.
     */
    val actionRight: String? = null,
    val actionShift: String? = null,
    /**
     * Hides the cell from a player who lacks it, instead of leaving a button that answers "no permission".
     *
     * The same rule the command itself enforces, applied before the click rather than after it: a row a
     * player can never use is one they should not have to try.
     */
    val permission: String = "",
    /** A colour for the name, as `0xRRGGBB`; null means the screen's default. */
    val color: Int? = null,
)

/**
 * A screen: a title, a height, and the buttons that are always there.
 *
 * [id] is what a caller asks for (`ui.open(player, "pomodoro")`). [dynamic] marks a screen whose content is
 * supplied by the owning module at open time rather than listed in config — a preset list cannot be written
 * into a YAML file, because its length comes from the database.
 */
data class UIScreen(
    val id: String,
    val title: String,
    val rows: Int,
    val buttons: Map<Int, UIButton>,
    /** Where a dynamic list's elements go, in reading order. Empty for a static screen. */
    val contentSlots: List<Int> = emptyList(),
    /** The rail says "back"; on a list it says "previous page". One cell, two labels, chosen by the screen. */
    val railName: String = "",
    val railLore: String = "",
    val plinthName: String = "",
    val plinthLore: String = "",
    /**
     * Whether a function on the head row stands on a house pillar.
     *
     * The colonnade is the plugin's shape, so it is on by default — but a screen whose own layout is
     * a **shape** cannot also be a colonnade: the main menu draws a heart, and a pillar shaft under
     * each of its top lobes would cut straight across it. A screen that draws its own shape turns
     * this off, in `ui.yml`, next to the cells it draws.
     */
    val pillars: Boolean = true,
) {
    val isList: Boolean get() = contentSlots.isNotEmpty()

    fun button(slot: Int): UIButton? = buttons[slot]
}

/**
 * The built-in actions the UI module handles itself.
 *
 * They are not commands because no module owns them: "close this window" and "go up one level" are
 * properties of the UI, and sending them through the command router would mean inventing commands for
 * things that are not features.
 */
enum class UIBuiltin(val token: String) {
    CLOSE("@close"),
    BACK("@back"),
    MAIN_MENU("@main-menu"),
    PAGE_PREVIOUS("@page-previous"),
    PAGE_NEXT("@page-next"),

    /** Markers on the confirmation's two pillars; the caller supplies what they do. */
    CONFIRM("@confirm"),
    CANCEL("@cancel"),

    /** A readout: the cell is there to be read, and clicking it does nothing. */
    NONE("@none"),
    ;

    companion object {
        private val byToken = entries.associateBy { it.token }

        fun of(action: String): UIBuiltin? = byToken[action.trim().lowercase()]
    }
}

/**
 * What a button click means, decided once so the renderer only has to act on it.
 *
 * Parsing is separated from doing because this is the half that can be tested without a server: an
 * `action` string in a config file is exactly the kind of input that arrives malformed.
 */
sealed interface UIAction {
    data class Command(val command: String) : UIAction
    data class Builtin(val kind: UIBuiltin) : UIAction

    companion object {
        /**
         * Reads an `action` value.
         *
         * A leading `@` means a built-in and must name one; anything else is a command, accepted with or
         * without its leading slash so both `pomodoro start` and `/pomodoro start` read naturally in a file.
         */
        fun parse(action: String): UIAction {
            val trimmed = action.trim()
            require(trimmed.isNotEmpty()) { "an action must not be blank" }
            if (!trimmed.startsWith("@")) return Command(trimmed.removePrefix("/").trim())
            return Builtin(
                UIBuiltin.of(trimmed)
                    ?: throw IllegalArgumentException(
                        "unknown built-in action '$trimmed'; expected one of ${UIBuiltin.entries.joinToString { it.token }}",
                    ),
            )
        }
    }
}

/**
 * Fills a command template with the element it was clicked on.
 *
 * A list's cells cannot name a fixed command: "start *this* preset" has to carry which one. Templates keep
 * the command in the config — `pomodoro start {index}` — while the element supplies the value, so an operator
 * can reword or reroute it without a code change.
 *
 * `{index}` is the element's zero-based position in the whole list, `{name}` its label. [values] carry what
 * the *module* knows and the config cannot — a home's own slot number, say — so such a screen still writes its
 * command in `ui.yml` instead of the module replacing the whole cell. An unknown placeholder is left alone
 * rather than blanked: a silently emptied argument would run a *different* command than the one written.
 */
object UICommand {
    fun fill(
        template: String,
        element: UIElement?,
        index: Int,
        values: Map<String, String> = emptyMap(),
    ): String {
        var out = template
        values.forEach { (key, value) -> out = out.replace("{$key}", value) }
        if (element != null) {
            out = out.replace("{index}", index.toString()).replace("{name}", element.name)
        }
        return out
    }
}

/** The whole file: every screen, by id. */
data class UILayout(
    val screens: Map<String, UIScreen>,
    /** Rows every screen gets unless it says otherwise; the rail needs a bottom row. */
    val defaultRows: Int,
) {
    fun screen(id: String): UIScreen? = screens[id]

    /** The shipped screen, or an error naming what exists — a typo in an `open` call is a config problem. */
    fun require(id: String): UIScreen = screens[id]
        ?: throw IllegalArgumentException("no screen '$id' in ui.yml; known: ${screens.keys.sorted().joinToString()}")
}
