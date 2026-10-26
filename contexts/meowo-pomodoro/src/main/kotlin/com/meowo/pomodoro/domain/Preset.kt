package com.meowo.pomodoro.domain

/**
 * One Pomodoro recipe — work/break durations, how many cycles before a long break, and the look.
 *
 * Two flavours exist:
 *  - **Builtin**: shipped with the plugin, identified by a stable id (e.g. `standard`); read-only.
 *  - **Custom**: stored in SQLite, identified by a UUID string.
 *
 * Whether a preset is builtin determines **what the player can do with it** (delete? rename?),
 * not how the timer behaves. The state machine is identical either way.
 */
data class Preset(
    val id: String,
    val name: String,
    val icon: String,
    val enchanted: Boolean,
    val workSeconds: Int,
    val breakSeconds: Int,
    val longBreakSeconds: Int,
    val sessions: Int,
    val builtin: Boolean,
) {
    init {
        require(id.matches(Regex("[a-z0-9_-]{1,32}")) || isUuid(id)) {
            "preset id must be a builtin slug or a UUID, got '$id'"
        }
        require(name.isNotBlank() && name.length <= 32) { "preset name must be 1..32 chars" }
        require(icon.isNotBlank()) { "preset icon must be a Bukkit material name" }
        require(workSeconds in DURATION_MIN..DURATION_MAX) { "work seconds must be $DURATION_MIN..$DURATION_MAX" }
        require(breakSeconds in DURATION_MIN..DURATION_MAX) { "break seconds must be $DURATION_MIN..$DURATION_MAX" }
        require(longBreakSeconds in DURATION_MIN..DURATION_MAX) { "long-break seconds must be $DURATION_MIN..$DURATION_MAX" }
        require(sessions in 1..12) { "sessions must be 1..12" }
    }

    /** Builds a player-defined preset from the same fields. */
    fun asCustom(): Preset = copy(builtin = false)

    companion object {
        const val DURATION_MIN = 60           // one minute — a zero-second cycle is a refusal, not a glitch
        const val DURATION_MAX = 3 * 60 * 60  // three hours — long enough for an exam, short enough to stay sane

        /** Longest display name a preset may carry, matching the constructor's own limit. */
        const val NAME_MAX = 32

        private val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private fun isUuid(id: String) = UUID_REGEX.matches(id)

        /**
         * Whether [name] may be used as a preset name.
         *
         * Exposed so an editor can refuse a bad name **before** the constructor throws inside a click
         * handler, where the only available reaction would be a generic error. `§` and control
         * characters are rejected because the name is rendered as a GUI title and a BossBar label, and
         * colour codes there are a formatting escape hatch rather than text.
         */
        fun validName(name: String): Boolean =
            name.isNotBlank() && name.length <= NAME_MAX && name.none { it.isISOControl() || it == '§' }
    }
}
