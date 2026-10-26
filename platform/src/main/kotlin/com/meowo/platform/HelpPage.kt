package com.meowo.platform

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Renders the command list, **filtered by what the reader may actually run**.
 *
 * The help used to be one fixed block of text in the language bundle, so it advertised commands the
 * reader had no permission for — `/afk` to everybody once it became op-only, `reload` and the
 * economy admin verbs likewise. Tab completion already filtered by permission; the help did not,
 * so the two disagreed.
 *
 * ## What the old flat list got wrong
 *
 * It was one undifferentiated wall of nineteen lines in the order the contexts happened to register,
 * and every line read `info`, `eco`, `msg` — a bare name with no prefix, so nothing on the page could
 * be pasted back into chat. It also listed what the plugin could do rather than what a person was
 * trying to do, and it quietly omitted five real routes (`back`, `replay`, `render`, `em`, `help`)
 * while the **footer advertised `/meo replay` as though the list were complete**. A help page that
 * half-works is worse than a short one, because the parts that are missing are the parts you needed.
 *
 * ## The list format
 *
 * One `help-commands` block, one entry per line, three optional fields:
 *
 * ```
 * @钱与账户
 * eco|§7余额、转账、每日补助
 * tpaccept|/meo tpa accept|§7接受别人的传送请求
 * ```
 *
 * `@` starts a **group heading**, which is not a command and is never permission-filtered. The
 * second field is the **invocation** and defaults to `/meo <route>`; it exists because an alias such
 * as `/meo tpa accept` is not itself a route, and rendering that as `/meo tpaccept` would advertise
 * a command that answers "unknown". `/meo` is the safe default because the root command's alias
 * prefix is empty, so `/meo <route>` reaches every registered route.
 *
 * Copy still lives in the language files — `help-header`, `help-group`, `help-line`, `help-footer`
 * and `help-commands` — so a single multi-line value survives the flat string map [Messages] keeps.
 * Only the parsing and the filtering live in code.
 *
 * ## Paging
 *
 * A reader who may run everything got six headings and twenty-five commands off one command — about
 * thirty lines of chat, which is a wall rather than a reference, and the kind nobody scrolls.
 *
 * Pages are whole **groups**, never a group split across two pages: a heading stranded at the bottom
 * of one page and its commands at the top of the next is worse than a longer page. The count depends
 * on the reader, because the groups depend on it — an operator genuinely has more pages here than a
 * new player, and a shared number would be wrong for one of them.
 *
 * The footer repeats on every page on purpose: the emoji and replay hints are how the server works,
 * not where in the list you are, and landing on page 2 with no explanation reads as a different
 * command. Only the navigation line is conditional, because it is the only part that can be stale.
 */
class HelpPage(private val messages: Messages, private val router: CommandRouter) {

    /** One listed command: what to type, and what it does. */
    private data class Entry(val invocation: String, val label: String)

    /** A heading and the entries under it. A [title] of null is the group before the first heading. */
    private class Group(val title: String?, val lines: MutableList<Entry> = mutableListOf())

    /** Renders the whole page for [sender]. Page numbers are 1-based; an out-of-range one is refused. */
    fun send(sender: CommandSender, page: Int = 1) {
        val visible = groups(sender)
        if (visible.isEmpty()) {
            messages.sendText(sender, "help", messages.text(sender, EMPTY))
            return
        }
        val pages = (visible.size + GROUPS_PER_PAGE - 1) / GROUPS_PER_PAGE
        if (page !in 1..pages) {
            messages.send(sender, "help-no-page", pages)
            return
        }
        val block = buildString {
            appendLine(messages.text(sender, HEADER))
            for (group in visible.drop((page - 1) * GROUPS_PER_PAGE).take(GROUPS_PER_PAGE)) {
                if (group.title != null) appendLine(messages.text(sender, GROUP, group.title))
                for (entry in group.lines) appendLine(messages.text(sender, LINE, entry.invocation, entry.label))
            }
            appendLine(messages.text(sender, FOOTER))
            append(messages.text(sender, NAV, page, pages))
        }
        // Keyed "help" only so the speaker resolver picks the system cat; the text is ours.
        messages.sendBlock(sender, "help", block, navigation(sender, page, pages))
    }

    /**
     * The page buttons, or null when there is only one page.
     *
     * Clickable because the reader already has the page open and already knows where they want to
     * go — typing `/meo help 3` to replace what is on screen is busywork. The console has no cursor,
     * and does not need one: [Messages.deliver] renders a component's **label** for a non-player
     * either way, so the same buttons arrive as plain text with nothing to click.
     *
     * The last page wraps to the first rather than offering a dead end. "You have reached the end" is
     * not an action, and a reader who runs out of options here is a reader who stops reading.
     */
    private fun navigation(sender: CommandSender, page: Int, pages: Int): Component? {
        if (pages <= 1) return null
        val last = page >= pages
        // The console has no cursor and no tab completion, so a button is a dead end there — it
        // would be told there is a "next" and given no way to take it. It gets the command spelled
        // out instead, exactly as the pre-button page did. Both audiences are reading the same
        // information; only the medium differs, and the medium is the one that differs.
        if (sender !is Player) {
            return messages.component(
                sender, NAV_TEXT, page, pages,
                if (last) "/meo help" else "/meo help ${page + 1}",
            )
        }
        val buttons = mutableListOf<Component>()
        if (page > 1) buttons += messages.action(sender, PREVIOUS, "/meo help ${page - 1}", HOVER, page - 1)
        buttons += if (last) {
            messages.action(sender, FIRST, "/meo help", HOVER, 1)
        } else {
            messages.action(sender, NEXT, "/meo help ${page + 1}", HOVER, page + 1)
        }
        return messages.actionLine(sender, *buttons.toTypedArray())
    }

    /** How many pages this reader has. Zero is possible — with no permissions at all, there is nothing to say. */
    fun pageCount(sender: CommandSender): Int =
        (groups(sender).size + GROUPS_PER_PAGE - 1) / GROUPS_PER_PAGE

    /** The page numbers worth offering after `help `, so tab completion never offers page 4 of 2. */
    fun pageNumbers(sender: CommandSender): List<String> =
        (1..pageCount(sender)).map { it.toString() }

    /**
     * The page as this reader may run it, **in the reader's own language**.
     *
     * `text(key)` with no sender always answers the default locale, so the list used to be Chinese
     * for everybody while the header, the template and the footer followed the reader — an English
     * client got an English heading over a Chinese command list, and neither half was right. The
     * reader is standing right there, so their language is the one to ask for.
     */
    private fun groups(sender: CommandSender): List<Group> {
        val groups = mutableListOf<Group>()
        // Entries before any heading still render. A group mark is decoration, so a partial or
        // stale operator file that lost one must not lose the commands underneath it as well.
        var current = Group(null)
        groups += current

        for (raw in messages.text(sender, LIST).lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith(MARK)) {
                current = Group(line.drop(1).trim())
                groups += current
                continue
            }
            val fields = line.split('|')
            val route = fields[0]
            // An unknown route is listed rather than hidden, so a typo in the language file cannot
            // silently delete a command from the help.
            if (!router.hasRoute(route) || router.isVisible(sender, route)) {
                val hasInvocation = fields.size >= 3 && fields[1].isNotBlank()
                current.lines += Entry(
                    invocation = if (hasInvocation) fields[1] else "/meo $route",
                    // Rejoined rather than indexed: a label is free text and may itself contain the
                    // separator, and dropping half a sentence is a worse failure than a missing one.
                    label = fields.drop(if (hasInvocation) 2 else 1).joinToString("|"),
                )
            }
        }
        return groups.filter { it.lines.isNotEmpty() }
    }

    private companion object {
        /** Groups per page. Three reads as a page; six is a wall. */
        const val GROUPS_PER_PAGE = 3
        const val HEADER = "help-header"
        const val GROUP = "help-group"
        const val FOOTER = "help-footer"
        const val LINE = "help-line"
        const val LIST = "help-commands"
        const val NAV = "help-nav"
        const val NAV_TEXT = "help-nav-text"
        const val EMPTY = "help-empty"
        const val MARK = "@"
        const val PREVIOUS = "help-nav-previous"
        const val NEXT = "help-nav-next"
        const val FIRST = "help-nav-first"
        const val HOVER = "help-nav-hover"
    }
}
