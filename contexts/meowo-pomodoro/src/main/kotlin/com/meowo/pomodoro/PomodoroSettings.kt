package com.meowo.pomodoro

import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Tunables for the Pomodoro context. Read once on `start()`; a reload restarts the context and
 * every running session keeps ticking with the new visual colour and sound.
 *
 * Defaults are the same numbers the upstream Java plugin ships with — 25/5/15 minutes, four
 * sessions before a long break — so an operator who doesn't touch `config.yml` gets the canonical
 * Pomodoro behaviour. Tuning means editing the file, not editing code.
 */
data class PomodoroSettings(
    /** Hard cap on how far a WORK-session player may drift from the start block before the
     *  context auto-pauses and warns them. The plugin never moves the player back. */
    val maxDistance: Double = 16.0,
    /** Whether BossBar is shown by default; players can toggle individually in the menu. */
    val bossbarDefault: Boolean = true,
    /** Whether Title is shown by default; players can toggle individually in the menu. */
    val titleDefault: Boolean = true,
    /** Vertical offset for the per-player TextDisplay floating above their head. */
    val textDisplayOffsetY: Double = 2.4,
    /**
     * How long the on-screen title stays up if it is ever re-sent, in seconds.
     *
     * The title is refreshed **every tick** so the countdown always matches the clock, which means
     * the stay time is no longer an update interval — it only has to be long enough that a dropped
     * tick or a lag spike cannot leave the screen empty before the next refresh lands. Five seconds
     * absorbs a hiccup of that size while still guaranteeing the title is gone shortly after the
     * session ends, rather than lingering.
     *
     * The fades are zero, so a per-second re-send **replaces** the title instead of restarting a
     * fade; that is what keeps the readout steady rather than pulsing.
     */
    val titleRefreshSeconds: Int = 5,
    val bossbarColors: BossbarColors = BossbarColors(),
    val sounds: PomodoroSounds = PomodoroSounds(),
    /** Maximum number of presets a single player may store (builtin presets do not count). */
    val customPresetLimit: Int = 16,
    /** Preset definitions bundled with the plugin; built-in and never editable. */
    val builtinPresets: List<BuiltinPreset> = emptyList(),
) {
    init {
        require(maxDistance in 1.0..64.0) { "pomodoro.max-distance must be 1..64" }
        require(customPresetLimit in 1..100) { "pomodoro.custom-preset-limit must be 1..100" }
        require(textDisplayOffsetY in -2.0..6.0) { "pomodoro.text-display.offset-y must be -2..6" }
        require(titleRefreshSeconds in 1..30) { "pomodoro.title-refresh-seconds must be 1..30" }
    }

    /** Built-in presets parsed from the operator-supplied YAML block. */
    fun builtin(id: String): BuiltinPreset? = builtinPresets.firstOrNull { it.id == id }

    data class BossbarColors(
        val work: String = "GREEN",
        val breakColor: String = "BLUE",
        val longBreakColor: String = "YELLOW",
        val pausedColor: String = "RED",
    ) {
        init {
            listOf(work, breakColor, longBreakColor, pausedColor).forEach {
                require(it in ALLOWED) { "bossbar color must be one of $ALLOWED, got $it" }
            }
        }
        companion object {
            val ALLOWED = setOf("BLUE", "GREEN", "PINK", "PURPLE", "RED", "WHITE", "YELLOW")
        }
    }

    /**
     * One Bukkit sound name per cue. An empty name silences that cue without failing the reload.
     *
     * Only cues the context actually plays are listed. The upstream plugin also shipped `ui-back` and
     * `session-fail`; this port has no back-navigation and no way to abort a session, so those knobs
     * would have been configuration that silently did nothing.
     */
    data class PomodoroSounds(
        val uiClick: String = "ui.button.click",
        val uiSuccess: String = "entity.experience_orb.pickup",
        val uiFail: String = "entity.villager.no",
        val timerEndWarning: String = "block.note_block.hat",
        val nextCycle: String = "entity.player.levelup",
        val leaveWarning: String = "entity.villager.no",
        val completeAllRounds: String = "entity.firework_rocket.launch",
        val newRoundStart: String = "block.bell.use",
    )

    /** A preset shipped with the plugin. Immutable so it can safely cross worker boundaries. */
    data class BuiltinPreset(
        val id: String,
        val name: String,
        val icon: String,
        val enchanted: Boolean,
        val workSeconds: Int,
        val breakSeconds: Int,
        val longBreakSeconds: Int,
        val sessions: Int,
    ) {
        init {
            require(id.matches(Regex("[a-z0-9_-]{1,32}"))) { "preset id must match [a-z0-9_-]{1,32}, got $id" }
            require(name.isNotBlank() && name.length <= 32) { "preset name must be 1..32 chars" }
            require(icon.isNotBlank()) { "preset icon must be a Bukkit material name" }
            require(workSeconds in MIN..MAX) { "work seconds must be $MIN..$MAX" }
            require(breakSeconds in MIN..MAX) { "break seconds must be $MIN..$MAX" }
            require(longBreakSeconds in MIN..MAX) { "long-break seconds must be $MIN..$MAX" }
            require(sessions in 1..12) { "sessions must be 1..12" }
        }
        companion object {
            const val MIN = 60
            const val MAX = 3 * 60 * 60
        }
    }

    companion object {
        /**
         * Parses a YAML stream that ships with the jar — kept here, not in `core`, because the
         * default presets are a property of the pomodoro context, not of the platform.
         *
         * `seconds` accepts a fractional minute (`25`, `25.0`, `0.5`) and converts it with ceiling
         * rounding. A zero-minute preset is a refusal, not a silent default: it is the kind of
         * typo a reload has to catch instead of paper over.
         */
        fun defaults(stream: InputStream): List<BuiltinPreset> {
            // Paper 26.2's Configuration exposes load(File)/load(Reader)/load(String) only — there is
            // no load(InputStream). Reading the resource through a Reader keeps this jar-relative load
            // working without staging the file on disk first.
            val yaml = YamlConfiguration().apply { load(stream.reader(Charsets.UTF_8)) }
            val section = yaml.getConfigurationSection("presets")
                ?: throw IllegalArgumentException("pomodoro-default-presets.yml must have a 'presets' block")
            return section.getKeys(false).map { id ->
                val preset = requireNotNull(section.getConfigurationSection(id)) { "preset '$id' must be a block" }
                fun seconds(key: String): Int {
                    val raw = preset.get(key)?.toString()
                        ?: throw IllegalArgumentException("preset '$id' is missing $key")
                    require(Regex("[0-9]{1,4}([.][0-9]+)?").matches(raw)) { "preset '$id' $key must be a non-negative decimal" }
                    val minutes = BigDecimal(raw)
                    require(minutes > BigDecimal.ZERO) { "preset '$id' $key must be greater than 0" }
                    return minutes.multiply(BigDecimal(60)).setScale(0, RoundingMode.CEILING).toInt()
                }
                BuiltinPreset(
                    id = id,
                    name = preset.getString("name") ?: id,
                    icon = preset.getString("icon") ?: "CLOCK",
                    enchanted = preset.getBoolean("enchanted", false),
                    workSeconds = seconds("work"),
                    breakSeconds = seconds("break"),
                    longBreakSeconds = seconds("long-break"),
                    sessions = preset.getInt("sessions"),
                )
            }
        }
    }
}
