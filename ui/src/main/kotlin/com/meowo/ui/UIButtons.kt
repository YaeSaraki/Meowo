package com.meowo.ui

import com.meowo.api.UIAPI
import java.util.UUID

/**
 * The rules about a single cell that do not need a menu, or a server, to decide.
 *
 * Kept apart from [UIRenderer] because they are the parts worth testing: the renderer needs a real server
 * (TabooLib), and a rule that lives inside it is a rule nobody checks until a player finds it. The permission
 * check is here because it *was* covered by a test before the menu's layout moved into `ui.yml` — moving the
 * layout must not quietly move the rule out of reach.
 *
 * ## Why the merging rule is here too
 *
 * A module may replace a cell's **content** — the pomodoro start pillar says "resume" while the timer is
 * held — and leave the **click** to the config. That merge used to live in the renderer as a private
 * extension, and the test double in `core` had its own copy of it, written out longhand. Two copies of
 * one rule is one rule too many: the copy is the one nobody re-reads, and a cell that looked clickable
 * and did nothing is exactly the bug that copy was hiding.
 *
 * So there is one implementation, here, and both the renderer and the double call it.
 */
object UIButtons {

    /**
     * Whether a player may see a cell.
     *
     * An override may restate the requirement — a screen can make one of its cells stricter than the config
     * did — and a blank requirement means everyone sees it. Hidden means hidden: the slot goes back to the
     * chrome, rather than showing a button whose only answer is "no permission".
     */
    fun visible(button: UIButton, override: UIAPI.UIElement?, hasPermission: (String) -> Boolean): Boolean {
        // A cell the module hid is not drawn at all, whatever the config says about permissions.
        if (override?.hidden == true) return false
        val required = override?.permission?.takeIf { it.isNotBlank() } ?: button.permission
        return required.isBlank() || hasPermission(required)
    }

    /**
     * Fills in whatever an override left blank, so a content-only override keeps the config's behaviour.
     *
     * A **blank** action means "I am only changing what this cell says" — that is how a module says it is
     * replacing content and not behaviour. A **null** right/shift action means "this cell has no such
     * gesture", so the config's is used; but when the config has none either, the cell simply has one
     * gesture, and that is not the same as having two.
     */
    fun withActionFrom(element: UIAPI.UIElement, button: UIButton): UIAPI.UIElement = element.copy(
        action = element.action.ifBlank { button.action },
        actionRight = element.actionRight ?: button.actionRight,
        actionShift = element.actionShift ?: button.actionShift,
        permission = element.permission.ifBlank { button.permission },
    )

    /**
     * Which of a cell's actions a click means.
     *
     * **Shift is checked before right**, because a shift+right click is still a shift click as far as a cell
     * with only two gestures is concerned — and "the more specific gesture wins" is the rule a player
     * expects.
     */
    fun gesture(element: UIAPI.UIElement, shift: Boolean, right: Boolean): String = when {
        shift -> element.actionShift ?: element.action
        right -> element.actionRight ?: element.action
        else -> element.action
    }
}

/**
 * The arithmetic of a paged list, and the memory of where each reader was.
 *
 * Both were inside [UIRenderer] and [UIService], where neither could be checked without a server — and
 * both are the kind of thing that is quietly wrong: a page count of zero makes a title read "page 1 of
 * 0", and a page index that forgets where the reader was sends them back to the start of the list.
 */
object UIPaging {

    /**
     * How many pages a list of [elementCount] items takes.
     *
     * **At least one**, always: an empty list is still a page the reader is looking at, and a title that
     * says "1 / 0" is worse than one that says "1 / 1".
     */
    fun pages(elementCount: Int, perPage: Int): Int {
        if (perPage <= 0) return 1
        return ((elementCount + perPage - 1) / perPage).coerceAtLeast(1)
    }

    /** The page to actually show: the caller's number, clamped into the range that exists. */
    fun clamp(page: Int, pages: Int): Int = page.coerceIn(0, (pages - 1).coerceAtLeast(0))

    /**
     * The index of one cell in the **whole** list, not on its page.
     *
     * A command argument needs the element's place in the list, so `pomodoro start {index}` starts the
     * preset the reader actually clicked rather than the one that happens to sit at that slot on page one.
     */
    fun index(page: Int, perPage: Int, position: Int): Int = page * perPage + position
}

/**
 * Where each reader was on each list.
 *
 * Lives apart from [UIService] so it can be checked without a server: "the caller's page wins when it
 * names one, otherwise resume where the reader left off" is a rule, and a rule that lives inside a
 * service that needs Bukkit is a rule nobody checks.
 */
class UIPageMemory {

    private val pages = mutableMapOf<Pair<UUID, String>, Int>()

    /** Remembers the page a reader is on. */
    fun remember(viewer: UUID, screen: String, page: Int) {
        pages[viewer to screen] = page
    }

    /**
     * The page to open.
     *
     * The caller's page wins **when it names one**; page zero is a legitimate page, so the test is "did
     * they ask for something", not "is it non-zero". A reader who has never opened the list starts at
     * the beginning.
     */
    fun resume(viewer: UUID, screen: String, requested: Int): Int =
        if (requested > 0) requested else (pages[viewer to screen] ?: 0)

    fun clear() = pages.clear()
}
