package com.meowo.pomodoro.interfaces

import com.meowo.platform.PlatformHost
import com.meowo.platform.Route
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale

/**
 * Routes `/meowo pomodoro ...` plus the `/pomodoro` and `/pomo` aliases.
 *
 * The GUI is the primary surface; these verbs exist for chat users, for keybinds and for scripts.
 * The vocabulary follows the upstream Pomodoro plugin where it can (`pause`, `resume`, `stop`,
 * `next`), so muscle memory carries over, and adds `visuals` for the two per-player readouts.
 */
class PomodoroCommand(
    private val host: PlatformHost,
    private val context: PomodoroCommandContext,
) : Route {

    override val permission: String = "meowo.pomodoro"

    override fun execute(sender: CommandSender, args: List<String>) {
        if (sender !is Player) {
            host.messages.send(sender, "player-only")
            return
        }
        if (!context.isActive(sender)) return
        when ((args.firstOrNull() ?: "gui").lowercase(Locale.ROOT)) {
            "gui" -> context.openMainMenu(sender)
            "presets" -> context.openPresets(sender)
            "start" -> context.start(sender, args.getOrNull(1))
            "pause" -> context.pause(sender)
            "resume" -> context.resume(sender)
            "stop" -> context.stop(sender)
            "next" -> context.next(sender)
            "stats" -> context.stats(sender)
            "create" -> context.newPreset(sender)
            "help" -> context.help(sender)
            "visuals" -> visuals(sender, args)
            "preset" -> preset(sender, args)
            "editor" -> editor(sender, args)
            else -> host.messages.send(sender, "pomodoro-usage")
        }
    }

    /**
     * `preset <start|edit|delete> <index>` — the gallery's cells, by position.
     *
     * Position rather than name because that is what the menu knows: a cell was clicked, and the list it came
     * from is the player's current gallery. The name is for humans, the index is for the button.
     */
    private fun preset(player: Player, args: List<String>) {
        val verb = args.getOrNull(1)?.lowercase(Locale.ROOT)
        val index = args.getOrNull(2)?.toIntOrNull()
        if (verb == null || index == null || index < 0) {
            host.messages.send(player, "pomodoro-preset-usage"); return
        }
        val handled = when (verb) {
            "start" -> context.presetStart(player, index)
            "edit" -> context.presetEdit(player, index)
            "delete" -> context.presetDelete(player, index)
            "copy" -> context.presetCopy(player, index)
            else -> false
        }
        if (!handled) host.messages.send(player, "pomodoro-preset-usage")
    }

    /**
     * `editor <field> <up|down>`, and `editor <name|save|duplicate|delete|reset>`.
     *
     * The two-part form exists because the editor's number cells answer to the mouse button, and a command
     * cannot see it: the direction is spelled out in the command the cell runs.
     */
    private fun editor(player: Player, args: List<String>) {
        val verb = args.getOrNull(1)?.lowercase(Locale.ROOT) ?: run {
            host.messages.send(player, "pomodoro-editor-usage"); return
        }
        when (verb) {
            "name" -> context.editorName(player)
            "save" -> context.editorSave(player)
            "duplicate" -> context.editorDuplicate(player)
            "delete" -> context.editorDelete(player)
            "reset" -> context.editorReset(player)
            else -> {
                val up = when (args.getOrNull(2)?.lowercase(Locale.ROOT)) {
                    "up" -> true
                    "down" -> false
                    else -> {
                        host.messages.send(player, "pomodoro-editor-usage"); return
                    }
                }
                if (!context.editorField(player, verb, up)) host.messages.send(player, "pomodoro-editor-usage")
            }
        }
    }

    /**
     * `visuals <bossbar|title> <on|off|toggle>`.
     *
     * The switch is parsed strictly rather than treating a missing word as "off": a player who types
     * `/pomodoro visuals bossbar` by accident should get the usage line, not a silently disabled
     * readout they then have to work out how to restore.
     */
    private fun visuals(player: Player, args: List<String>) {
        val which = args.getOrNull(1)?.lowercase(Locale.ROOT)
        val on = when (args.getOrNull(2)?.lowercase(Locale.ROOT)) {
            "on" -> true
            "off" -> false
            // A menu crystal cannot name the state it wants; it says "toggle" and the context flips.
            "toggle" -> {
                if (!context.toggleVisual(player, which)) host.messages.send(player, "pomodoro-visuals-usage")
                return
            }
            else -> {
                host.messages.send(player, "pomodoro-visuals-usage")
                return
            }
        }
        if (!context.visuals(player, which, on)) host.messages.send(player, "pomodoro-visuals-usage")
    }

    override fun complete(sender: CommandSender, args: List<String>): List<String> {
        if (sender !is Player) return emptyList()
        return when (args.size) {
            1 -> VERBS.filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> when (args[0].lowercase(Locale.ROOT)) {
                "start" -> context.completePresetName(sender, args[1])
                "visuals" -> VISUAL_KNOBS.filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            3 -> if (args[0].equals("visuals", ignoreCase = true) &&
                args[1].lowercase(Locale.ROOT) in VISUAL_KNOBS
            ) {
                listOf("on", "off").filter { it.startsWith(args[2], ignoreCase = true) }
            } else {
                emptyList()
            }
            else -> emptyList()
        }
    }

    private companion object {
        val VERBS = listOf("gui", "presets", "start", "pause", "resume", "stop", "next", "stats", "visuals")
        val VISUAL_KNOBS = listOf("bossbar", "title")
    }
}

/**
 * The surface the command needs from its owning context.
 *
 * Keeping the command unaware of services and repositories preserves the layering: only
 * [com.meowo.pomodoro.PomodoroContext] wires the concrete pieces together.
 */
interface PomodoroCommandContext {
    fun isActive(player: Player): Boolean
    fun openMainMenu(player: Player)

    /** The preset gallery, reachable on its own so `/pomodoro presets` does not need the colonnade first. */
    fun openPresets(player: Player)

    fun start(player: Player, presetName: String?)
    fun pause(player: Player)
    fun resume(player: Player)
    fun stop(player: Player)
    fun next(player: Player)
    fun stats(player: Player)

    /** Starts a new preset in the editor: what the gallery's create crystal and its menu cell both run. */
    fun newPreset(player: Player)

    /** The help text, so a menu cell and `/pomodoro help` do the same thing. */
    fun help(player: Player)

    /** Toggles `bossbar` or `title` for [player]; false when the knob name is unknown. */
    fun visuals(player: Player, which: String?, on: Boolean): Boolean

    /**
     * Flips `bossbar` or `title` for [player]; false when the knob name is unknown.
     *
     * A menu cell that switches a readout cannot name the state it wants — the same crystal is clicked to
     * turn it off and on — so the verb is "toggle" rather than "on"/"off".
     */
    fun toggleVisual(player: Player, which: String?): Boolean

    /** The gallery's three gestures, by preset position in the list the player is looking at. */
    fun presetStart(player: Player, index: Int): Boolean
    fun presetEdit(player: Player, index: Int): Boolean
    fun presetDelete(player: Player, index: Int): Boolean

    /** Copies a preset, which is what a built-in offers instead of editing: it cannot be changed in place. */
    fun presetCopy(player: Player, index: Int): Boolean

    /**
     * The editor's fields and verbs.
     *
     * [field] is `work`, `break`, `long-break`, `sessions` or `icon`; [up] is which way to step it. The
     * editor's cells carry two gestures (`action` up, `action-right` down), and a command cannot see the
     * mouse button, so the direction is part of the command instead.
     */
    fun editorField(player: Player, field: String, up: Boolean): Boolean
    fun editorName(player: Player)
    fun editorSave(player: Player)
    fun editorDuplicate(player: Player)
    fun editorDelete(player: Player)
    fun editorReset(player: Player)

    /** Preset ids matching [partial], for tab completion. */
    fun completePresetName(player: Player, partial: String): List<String>
}
