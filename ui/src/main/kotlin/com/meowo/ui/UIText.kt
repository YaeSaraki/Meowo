package com.meowo.ui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

/**
 * Turns a value out of `ui.yml` into something a client can read.
 *
 * A `name` or a `lore` line may be written either way, and both are natural:
 *
 * ```yaml
 * name: pomodoro-action-start     # a key from the language file — translated per player
 * name: "&d◆ 开始专注"            # literal text, `&` for colour
 * ```
 *
 * A key is recognised by shape: no spaces, no `&`, lowercase with dashes. Anything else is literal, and
 * `&`-codes are read as colours. Keeping both is what lets a screen ship translated while still allowing an
 * operator to type a label straight into the file.
 *
 * The literal path uses the **ampersand** serialiser (`&d`), not the section one (`§d`), because a config
 * file is written by a human: `§` in a YAML file is an invisible character away from being a bug.
 */
object UIText {

    /** Blank stays blank rather than becoming an empty tooltip line. */
    private val KEY = Regex("^[a-z0-9][a-z0-9-]*$")

    private val AMPERSAND = LegacyComponentSerializer.legacyAmpersand()

    fun isKey(raw: String): Boolean = raw.isNotBlank() && !raw.contains('&') && KEY.matches(raw.trim())

    /**
     * Resolves [raw], using [keys] for a language key.
     *
     * [keys] is passed in rather than looked up here so this stays free of a server: the same function is
     * used for a real player and in a test with a stub.
     */
    fun resolve(raw: String, color: NamedTextColor, keys: (String) -> Component): Component =
        if (isKey(raw)) {
            keys(raw).decoration(TextDecoration.ITALIC, false)
        } else {
            AMPERSAND.deserialize(raw).colorIfAbsent(color).decoration(TextDecoration.ITALIC, false)
        }

    /**
     * A value as a legacy `§` string, for the places that take text rather than a component.
     *
     * A menu title is one: TabooLib reads the codes itself. A key resolves to the bundle value (already
     * `§`-coded); literal text has its `&` turned into `§`, because `&` is what a person types in a file.
     */
    fun toLegacy(raw: String, keys: (String) -> String): String =
        if (isKey(raw)) keys(raw) else raw.replace('&', '\u00A7')

    /**
     * A blank line resolves to null, i.e. it is **dropped**.
     *
     * An empty tooltip line is worse than no line: the client draws a blank row and the player reads it as a
     * missing entry. Spacing is therefore not available through blank lines — a choice made deliberately, and
     * the reason this returns null rather than an empty component.
     */
    fun resolveLore(raw: String, color: NamedTextColor, keys: (String) -> Component): Component? =
        if (raw.isBlank()) null else resolve(raw, color, keys)
}
