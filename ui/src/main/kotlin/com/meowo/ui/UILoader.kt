package com.meowo.ui

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStream

/**
 * Reads `ui.yml`.
 *
 * Everything a screen is — title, height, which slot holds which icon, what each cell says and does — comes
 * from this file. The shipped defaults live in the jar as a resource; the operator's file on disk overrides
 * them **per screen**, so a new shipped screen appears without the operator's file having to be regenerated
 * (the same "new keys fall back to the built-in" rule the language files follow).
 *
 * ### Why this validates instead of tolerating
 *
 * A button whose slot is outside the menu is the failure this whole file exists to prevent. A home page
 * asked for four rows while the rail lived at slots 45–52, and `set(45, …)` on a 36-slot chest does nothing
 * — silently. The exit was never drawn, and nothing in the log said so. So a slot outside `rows * 9`, a row
 * count outside 1..6, an unknown `@` action or a blank action is an **error at load**, naming the screen and
 * slot. A layout that cannot be drawn should not load at all.
 */
object UILoader {

    /** Paper's menu height range, mirrored here so the loader can reject a bad value before rendering. */
    val ROW_RANGE = 1..6

    fun load(defaults: InputStream, override: File?): UILayout {
        val shipped = YamlConfiguration.loadConfiguration(defaults.reader(Charsets.UTF_8))
        val operator = override?.takeIf { it.isFile }?.let { YamlConfiguration.loadConfiguration(it) }
        return parse(shipped, operator)
    }

    /** Parsed from two raw documents; split out so a test can feed a string. */
    fun parse(shipped: YamlConfiguration, operator: YamlConfiguration?): UILayout {
        // `getInt(path)` answers 0 for a missing key rather than null, so "absent" has to be asked for.
        val defaultRows = if (operator?.isSet("defaults.rows") == true) {
            operator.getInt("defaults.rows")
        } else {
            shipped.getInt("defaults.rows", 6)
        }
        require(defaultRows in ROW_RANGE) { "defaults.rows must be in $ROW_RANGE, got $defaultRows" }

        val ids = linkedSetOf<String>()
        shipped.getConfigurationSection("screens")?.let { ids += it.getKeys(false) }
        operator?.getConfigurationSection("screens")?.let { ids += it.getKeys(false) }

        val screens = ids.associateWith { id ->
            // Per screen: the operator's section wins outright, so "replace this screen" is one block rather
            // than a merge the operator has to reason about.
            val section = operator?.getConfigurationSection("screens.$id")
                ?: shipped.getConfigurationSection("screens.$id")
                ?: invalid("screen '$id' has no definition")
            parseScreen(id, section, defaultRows)
        }
        require(screens.isNotEmpty()) { "ui.yml defines no screens" }
        return UILayout(screens, defaultRows)
    }

    /**
     * A bad layout is an argument problem, so one exception type covers it: a caller can catch "this file
     * cannot be drawn" without knowing which field was wrong, while the message still names screen and slot.
     */
    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)

    private fun parseScreen(id: String, section: ConfigurationSection, defaultRows: Int): UIScreen {
        val rows = section.getInt("rows", defaultRows)
        require(rows in ROW_RANGE) { "screen '$id': rows must be in $ROW_RANGE, got $rows" }
        val limit = rows * 9

        val buttons = linkedMapOf<Int, UIButton>()
        section.getConfigurationSection("buttons")?.let { buttonSection ->
            buttonSection.getKeys(false).forEach { key ->
                val slot = key.toIntOrNull()
                    ?: invalid("screen '$id': button key '$key' is not a slot number")
                require(slot in 0 until limit) {
                    "screen '$id': button at slot $slot does not fit a $rows-row menu (slots 0..${limit - 1}); " +
                        "the click would land outside the inventory and never fire"
                }
                val entry = buttonSection.getConfigurationSection(key)
                    ?: invalid("screen '$id': button $slot is not a block")
                val action = entry.getString("action")
                    ?: invalid("screen '$id': button $slot has no action")
                // Validated now, so a broken action is a startup error rather than a dead cell in a menu.
                UIAction.parse(action)
                val actionRight = entry.getString("action-right")?.also { UIAction.parse(it) }
                val actionShift = entry.getString("action-shift")?.also { UIAction.parse(it) }
                buttons[slot] = UIButton(
                    slot = slot,
                    icon = entry.getString("icon") ?: invalid("screen '$id': button $slot has no icon"),
                    name = entry.getString("name") ?: invalid("screen '$id': button $slot has no name"),
                    lore = entry.getStringList("lore"),
                    action = action,
                    glint = entry.getBoolean("glint", false),
                    actionRight = actionRight,
                    actionShift = actionShift,
                    permission = entry.getString("permission") ?: "",
                    color = if (entry.isSet("color")) entry.getInt("color") else null,
                )
            }
        }

        val contentSlots = section.getIntegerList("content-slots")
        contentSlots.forEach { slot ->
            require(slot in 0 until limit) {
                "screen '$id': content slot $slot does not fit a $rows-row menu (slots 0..${limit - 1})"
            }
        }

        return UIScreen(
            id = id,
            title = section.getString("title") ?: invalid("screen '$id' has no title"),
            rows = rows,
            buttons = buttons,
            contentSlots = contentSlots,
            railName = section.getString("rail.name") ?: "",
            railLore = section.getString("rail.lore") ?: "",
            plinthName = section.getString("plinth.name") ?: "",
            plinthLore = section.getString("plinth.lore") ?: "",
            // On by default: the colonnade is the house style, and a screen has to ask not to have it.
            pillars = section.getBoolean("pillars", true),
        )
    }
}
