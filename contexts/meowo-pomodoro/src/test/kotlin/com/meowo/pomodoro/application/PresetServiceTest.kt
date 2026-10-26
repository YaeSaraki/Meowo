package com.meowo.pomodoro.application

import com.meowo.pomodoro.PomodoroSettings
import com.meowo.pomodoro.application.repository.PresetRepository
import com.meowo.pomodoro.domain.Preset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * In-memory stand-in for the SQLite repository. The service under test is orchestration — merging
 * built-ins with custom rows and invalidating its cache — so the storage itself is the wrong thing
 * to involve here; [com.meowo.pomodoro.infrastructure.persistence.SqlitePresetRepositoryTest]
 * covers that against a real file.
 */
private class FakePresetRepository : PresetRepository {
    val rows = linkedMapOf<Pair<UUID, String>, Preset>()
    var closed = false

    override fun all(owner: UUID): CompletableFuture<List<Preset>> =
        CompletableFuture.completedFuture(rows.filterKeys { it.first == owner }.values.toList())

    /**
     * Fails from inside the future rather than before it, exactly as the SQLite repository does when
     * its worker refuses a write — so the service's propagation is exercised against the same shape
     * of failure it meets in production.
     */
    override fun save(owner: UUID, preset: Preset, limit: Int): CompletableFuture<Preset> =
        CompletableFuture.supplyAsync {
            val owned = rows.keys.count { it.first == owner }
            // Mirrors `SqlitePresetRepository.save`: the cap counts the rows **other** than this
            // one, so editing a preset the player already has still works when they are at the cap.
            // The two used to disagree, and the disagreement hid a real write failure.
            val existing = rows.containsKey(owner to preset.id)
            check(owned - (if (existing) 1 else 0) < limit) { "preset limit reached" }
            rows[owner to preset.id] = preset
            preset
        }

    override fun delete(owner: UUID, presetId: String): CompletableFuture<Boolean> =
        CompletableFuture.completedFuture(rows.remove(owner to presetId) != null)

    override fun find(owner: UUID, presetId: String): CompletableFuture<Preset?> =
        CompletableFuture.completedFuture(rows[owner to presetId])

    override fun isFull(owner: UUID, limit: Int): CompletableFuture<Boolean> =
        CompletableFuture.completedFuture(rows.keys.count { it.first == owner } >= limit)

    override fun close() {
        closed = true
    }
}

class PresetServiceTest {
    private val owner = UUID.randomUUID()

    private val builtin = PomodoroSettings.BuiltinPreset(
        id = "standard", name = "Standard", icon = "CLOCK", enchanted = false,
        workSeconds = 1500, breakSeconds = 300, longBreakSeconds = 900, sessions = 4,
    )

    private fun settings(limit: Int = 3) = PomodoroSettings(customPresetLimit = limit, builtinPresets = listOf(builtin))

    private fun custom(id: String = "11111111-1111-1111-1111-111111111111", name: String = "Mine") = Preset(
        id = id, name = name, icon = "BOOK", enchanted = false,
        workSeconds = 600, breakSeconds = 60, longBreakSeconds = 300, sessions = 2, builtin = false,
    )

    private fun <T> CompletableFuture<T>.await(): T = get(5, java.util.concurrent.TimeUnit.SECONDS)

    @Test fun `list returns built-ins before custom presets`() {
        val repo = FakePresetRepository()
        val service = PresetService(repo) { settings() }
        service.save(owner, custom()).await()
        val list = service.list(owner).await()
        assertEquals(listOf("standard", custom().id), list.map { it.id })
        assertTrue(list.first().builtin)
        assertFalse(list.last().builtin)
    }

    @Test fun `find resolves a built-in without touching storage`() {
        val repo = FakePresetRepository()
        val service = PresetService(repo) { settings() }
        assertEquals("Standard", service.find(owner, "standard").await()?.name)
        assertNull(service.find(owner, "nope").await())
    }

    @Test fun `save stores a custom preset and invalidates the cache`() {
        val repo = FakePresetRepository()
        val service = PresetService(repo) { settings() }
        assertEquals(1, service.list(owner).await().size) // built-in only, cached
        service.save(owner, custom()).await()
        // A cached list would still show one entry here.
        assertEquals(2, service.list(owner).await().size)
    }

    @Test fun `save refuses a builtin preset`() {
        val repo = FakePresetRepository()
        val service = PresetService(repo) { settings() }
        val asBuiltin = custom().copy(builtin = true)
        assertThrows(IllegalArgumentException::class.java) { service.save(owner, asBuiltin).await() }
    }

    @Test fun `delete removes a custom preset but refuses a builtin`() {
        val repo = FakePresetRepository()
        val service = PresetService(repo) { settings() }
        val stored = service.save(owner, custom()).await()
        assertFalse(service.delete(owner, "standard").await(), "a builtin preset must not be deletable")
        assertTrue(service.delete(owner, stored.id).await())
        assertEquals(listOf("standard"), service.list(owner).await().map { it.id })
    }

    @Test fun `the per-player limit is enforced`() {
        val repo = FakePresetRepository()
        val service = PresetService(repo) { settings(limit = 1) }
        service.save(owner, custom(name = "First")).await()
        val second = custom(id = "22222222-2222-2222-2222-222222222222", name = "Second")
        assertThrows(java.util.concurrent.ExecutionException::class.java) { service.save(owner, second).await() }
    }

    @Test fun `saving an existing preset still works when the player is at the limit`() {
        // The cap counts how many a player **has**. Renaming one of them leaves the count
        // unchanged, so the save must not be refused — and the repository it reaches has to agree,
        // which it used not to: it counted every row, so a player who was full could not edit.
        val repo = FakePresetRepository()
        val service = PresetService(repo) { settings(limit = 1) }
        val stored = service.save(owner, custom(name = "First")).await()
        val renamed = service.save(owner, stored.copy(name = "Renamed")).await()
        assertEquals("Renamed", renamed.name)
        assertEquals(listOf("standard", stored.id), service.list(owner).await().map { it.id })
    }

    @Test fun `close closes the repository underneath`() {
        val repo = FakePresetRepository()
        PresetService(repo) { settings() }.close()
        assertTrue(repo.closed)
    }
}
