package com.meowo.ui

import com.meowo.api.UIBindings

/**
 * What the UI module can do about a click, once the action string has been read.
 *
 * Turning [UIAction] into one of these is separated from acting on it for a reason that is not tidiness:
 * **the renderer needs a server, this does not.** The mapping — including the rule that `@back` means
 * "previous page" on a list and "up one level" elsewhere — is the part most likely to be got wrong, so it is
 * the part that gets tested.
 */
sealed interface UIEffect {
    /** Runs a command as the viewer, so its permission still applies. */
    data class Command(val command: String) : UIEffect
    data object Close : UIEffect
    data object Back : UIEffect
    data object MainMenu : UIEffect
    data object PagePrevious : UIEffect
    data object PageNext : UIEffect
    data object Confirm : UIEffect
    data object Cancel : UIEffect

    /** A readout cell: nothing happens, and that is the whole behaviour. */
    data object None : UIEffect

    companion object {
        /**
         * Reads an action in the context of the screen it sits on.
         *
         * The one piece of interpretation is [Back]: on a **list** the way back is the previous page, because
         * "back" on a list of things means the page before it. Everywhere else it is the previous screen,
         * which the caller supplies through [UIBindings].
         */
        fun of(action: UIAction, screen: UIScreen): UIEffect = when (action) {
            is UIAction.Command -> Command(action.command)
            is UIAction.Builtin -> when (action.kind) {
                UIBuiltin.CLOSE -> Close
                UIBuiltin.BACK -> if (screen.isList) PagePrevious else Back
                UIBuiltin.MAIN_MENU -> MainMenu
                UIBuiltin.PAGE_PREVIOUS -> PagePrevious
                UIBuiltin.PAGE_NEXT -> PageNext
                UIBuiltin.CONFIRM -> Confirm
                UIBuiltin.CANCEL -> Cancel
                UIBuiltin.NONE -> None
            }
        }
    }
}
