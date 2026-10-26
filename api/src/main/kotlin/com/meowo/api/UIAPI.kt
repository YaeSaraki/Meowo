package com.meowo.api

import java.util.UUID

/**
 * How a module hands a screen to the UI module.
 *
 * The UI module owns rendering, layout and click dispatch; it knows nothing about presets, homes or money.
 * A module knows its own data and nothing about menus. This is the seam between them, and it is the same
 * shape as [HomeMenuAPI] and [MenuAPI]: a service on the `ServicesManager`, resolved by type, so no module
 * imports another.
 *
 * ### Two kinds of screen
 *
 * [open] shows a screen that `ui.yml` describes completely — the main menu, a colonnade, a confirmation.
 *
 * [openList] shows one whose **elements** cannot live in a config file, because their number comes from data
 * (the presets a player has saved, the homes they own). The config still owns the frame: which slots hold
 * elements, the title, the rail, the fixed buttons. The module supplies only what each element says and
 * does, and the UI module handles filling, paging and the click.
 */
interface UIAPI {

    /** Opens the shipped screen [screen] for [viewer]; unknown ids fail loudly rather than opening nothing. */
    /**
     * Opens the shipped screen [screen] for [viewer]; unknown ids fail loudly rather than opening nothing.
     *
     * [overrides] are the cells whose *content* depends on state — "开始" against "继续", a toggle's on/off
     * label and dye, the running session's own readout. Layout and wording come from config; the module says
     * only what is different right now. A cell that is not overridden keeps everything the config gave it,
     * including its action, so the screen stays editable without the module restating it.
     */
    fun open(
        viewer: UUID,
        screen: String,
        overrides: Map<Int, UIElement> = emptyMap(),
        bindings: UIBindings = UIBindings(),
        /**
         * The window title, when it depends on state rather than on the screen.
         *
         * A screen's title comes from `ui.yml`; editing an existing preset and creating a new one are the same
         * screen with different words, and that difference is not something the config can name.
         */
        title: String? = null,
        /**
         * Values for the `{...}` placeholders in this screen's commands.
         *
         * A screen whose actions need data the config cannot hold — the slot number of the home being shown —
         * keeps its commands in `ui.yml` and is handed the values here, rather than the module replacing whole
         * cells and taking the layout back out of the config.
         */
        values: Map<String, String> = emptyMap(),
    )

    /**
     * Opens the list screen [screen] with [elements].
     *
     * [page] is the zero-based page the caller wants to resume on; the UI module clamps it to what fits.
     */
    fun openList(
        viewer: UUID,
        screen: String,
        elements: List<UIElement>,
        page: Int = 0,
        overrides: Map<Int, UIElement> = emptyMap(),
        bindings: UIBindings = UIBindings(),
        values: Map<String, String> = emptyMap(),
    )

    /**
     * Replaces the **variable** cells of the screen currently open for [viewer], without reopening it.
     *
     * A click that flips a readout should change that readout, not swap the window out from under the cursor.
     */
    fun refresh(viewer: UUID, screen: String, overrides: Map<Int, UIElement> = emptyMap())

    /**
     * Asks the two pillars a yes/no question.
     *
     * The layout, icons and labels are the `confirm` screen's config; [onYes] and [onNo] are what the two
     * cells *do*, which only the caller knows. The screen's `@confirm` / `@cancel` actions mark which cell is
     * which, so a config can move or restyle them without the caller being told.
     */
    fun confirm(viewer: UUID, title: String, detail: String, onYes: () -> Unit, onNo: () -> Unit)

    /** An element of a dynamic list. Its [action] follows the same rules as a configured button's. */
    data class UIElement(
        val icon: String,
        val name: String,
        val lore: List<String> = emptyList(),
        val action: String = "",
        val glint: Boolean = false,
        /** The cell's right-click and shift+left-click actions, when it offers more than one gesture. */
        val actionRight: String? = null,
        val actionShift: String? = null,
        /** Hides the cell from a player who lacks it; blank means everyone sees it. */
        val permission: String = "",
        /**
         * A colour for the name, as `0xRRGGBB`.
         *
         * Some names are data with a colour attached — a home is drawn in the colour its owner chose — and a
         * language key cannot carry that. Null means "use the screen's default".
         */
        val color: Int? = null,
        /**
         * Draws nothing, leaving the slot to the chrome.
         *
         * Some cells are conditional on a decision only the module can make — a "force travel" button that
         * must not appear when the server disables force travel — and a button that always answers with a
         * refusal teaches the player the menu lies. Hiding is the honest option, and it is not the same as a
         * permission: the config cannot name the condition.
         */
        val hidden: Boolean = false,
    )
}

/**
 * The callbacks a screen's own cells need, supplied by whoever opened it.
 *
 * Layout is config; what "back" and "confirm" *do* belongs to the module that opened the screen, because only
 * it knows where the player came from and what the question was. A callback left unset is not an error: a
 * screen with no `@back` cell simply never asks for one.
 */
data class UIBindings(
    val onBack: (() -> Unit)? = null,
    val onClose: (() -> Unit)? = null,
    val onMainMenu: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null,
    val onCancel: (() -> Unit)? = null,
)
