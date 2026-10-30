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
        /**
         * The cache is invalidated **inside** the returned future, not beside it.
         *
         * `repository.save(…).thenRun { cache.remove(owner) }` looks equivalent and is not: the
         * callback of `thenRun` runs in a **later** stage of the chain, so it is not guaranteed to
         * have run by the time the caller awaits the future it got back. The caller then reads the
         * cache and sees the preset they just saved missing — the write succeeded and the read
         * disagrees with it, which is the worst shape a cache bug can have.
         *
         * Invalidating inside `thenApply` puts the removal **before** the future completes, so
         * awaiting the returned future means the cache is already gone.
         */
        return repository.save(owner, preset.asCustom(), settings().customPresetLimit)
            .thenApply { saved ->
                cache.remove(owner)
                saved
            }
    }

    /** Deletes a custom preset. Builtins are silently refused. */
    fun delete(owner: UUID, presetId: String): CompletableFuture<Boolean> {
        val builtin = settings().builtin(presetId)
        if (builtin != null) return CompletableFuture.completedFuture(false)
        return repository.delete(owner, presetId)
            .thenApply { deleted ->
                cache.remove(owner)
                deleted
            }
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
