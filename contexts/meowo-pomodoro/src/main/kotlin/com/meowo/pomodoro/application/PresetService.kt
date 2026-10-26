package com.meowo.pomodoro.application

import com.meowo.pomodoro.PomodoroSettings
import com.meowo.pomodoro.application.repository.PresetRepository
import com.meowo.pomodoro.domain.Preset
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Orchestrates player-defined presets on top of [PresetRepository]. Built-in presets are read
 * straight from [settings] — they never touch the repository — and only the custom ones flow
 * through the SQLite-backed port.
 *
 * The service exposes a single in-memory cache per player so a GUI render doesn't have to go
 * through `CompletableFuture`. Cache invalidation is conservative: every write also clears the
 * cache entry, so a reload that mutates the database is read on next access.
 */
class PresetService(
    private val repository: PresetRepository,
    private val settings: () -> PomodoroSettings,
) : AutoCloseable {

    private val cache = mutableMapOf<UUID, List<Preset>>()

    /** The player's full preset list, builtin first (by id) then custom (by id). */
    fun list(owner: UUID): CompletableFuture<List<Preset>> {
        cache[owner]?.let { return CompletableFuture.completedFuture(it) }
        val builtins = settings().builtinPresets.map(::toPreset)
        return repository.all(owner).thenApply { customs ->
            (builtins + customs).also { cache[owner] = it }
        }
    }

    /** Single lookup across builtin + custom. Returns null when not found. */
    fun find(owner: UUID, presetId: String): CompletableFuture<Preset?> {
        val builtin = settings().builtin(presetId)?.let(::toPreset)
        if (builtin != null) return CompletableFuture.completedFuture(builtin)
        return repository.find(owner, presetId)
    }

    /** Inserts a new custom preset. The caller supplies a freshly-generated UUID. */
    fun save(owner: UUID, preset: Preset): CompletableFuture<Preset> {
        require(!preset.builtin) { "cannot save a builtin preset" }
        val future = repository.save(owner, preset.asCustom(), settings().customPresetLimit)
        future.thenRun { cache.remove(owner) }
        return future
    }

    /** Deletes a custom preset. Builtins are silently refused. */
    fun delete(owner: UUID, presetId: String): CompletableFuture<Boolean> {
        val builtin = settings().builtin(presetId)
        if (builtin != null) return CompletableFuture.completedFuture(false)
        val future = repository.delete(owner, presetId)
        future.thenRun { cache.remove(owner) }
        return future
    }

    override fun close() {
        cache.clear()
        repository.close()
    }

    private fun toPreset(builtin: PomodoroSettings.BuiltinPreset): Preset = Preset(
        id = builtin.id,
        name = builtin.name,
        icon = builtin.icon,
        enchanted = builtin.enchanted,
        workSeconds = builtin.workSeconds,
        breakSeconds = builtin.breakSeconds,
        longBreakSeconds = builtin.longBreakSeconds,
        sessions = builtin.sessions,
        builtin = true,
    )
}
