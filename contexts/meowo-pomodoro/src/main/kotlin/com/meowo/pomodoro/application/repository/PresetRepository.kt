package com.meowo.pomodoro.application.repository

import com.meowo.pomodoro.domain.Preset
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Domain port for player-defined presets.
 *
 * Built-in presets do not live here — they are read once from `pomodoro-default-presets.yml` and
 * passed to the application layer as immutable [com.meowo.pomodoro.PomodoroSettings.BuiltinPreset]
 * values. The repository owns only the rows that players create, edit and delete.
 *
 * Operations return [CompletableFuture] because the implementation is SQLite-backed on a dedicated
 * worker thread, exactly the model `meowo-home.HomeStore` follows.
 */
interface PresetRepository : AutoCloseable {
    /** All of [owner]'s custom presets. Empty list if the player has none. */
    fun all(owner: UUID): CompletableFuture<List<Preset>>

    /**
     * Inserts or replaces a preset owned by [owner]. Throws when [limit] is exceeded.
     *
     * The cap is on how many a player **has**: replacing a preset they already own does not
     * consume a slot, so it must not be refused when they are already at the cap.
     */
    fun save(owner: UUID, preset: Preset, limit: Int): CompletableFuture<Preset>

    /** Deletes [presetId] if and only if it belongs to [owner]. No-op otherwise. */
    fun delete(owner: UUID, presetId: String): CompletableFuture<Boolean>

    /** Single lookup, used by the GUI editor to confirm the preset still belongs to the player. */
    fun find(owner: UUID, presetId: String): CompletableFuture<Preset?>

    /** True when [owner] has already stored [limit] presets. Used as a fast-fail before save. */
    fun isFull(owner: UUID, limit: Int): CompletableFuture<Boolean>
}
