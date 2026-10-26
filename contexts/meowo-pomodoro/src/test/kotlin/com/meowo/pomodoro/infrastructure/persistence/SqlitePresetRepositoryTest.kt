package com.meowo.pomodoro.infrastructure.persistence

import com.meowo.pomodoro.domain.Preset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class SqlitePresetRepositoryTest {
    @TempDir lateinit var dir: Path

    private fun <T> java.util.concurrent.CompletableFuture<T>.await(): T = get(5, TimeUnit.SECONDS)

    private fun preset(id: String = UUID.randomUUID().toString(), name: String = "My preset") = Preset(
        id = id, name = name, icon = "CLOCK", enchanted = false,
        workSeconds = 1500, breakSeconds = 300, longBreakSeconds = 900, sessions = 2,
        builtin = false,
    )

    @Test fun `save insert and find round-trip`() {
        val owner = UUID.randomUUID()
        SqlitePresetRepository(dir.resolve("p.db")).use { repo ->
            val stored = repo.save(owner, preset(), limit = 16).await()
            assertFalse(stored.builtin)
            val read = repo.find(owner, stored.id).await()
            assertEquals(stored, read)
        }
    }

    @Test fun `save replaces existing preset with same id`() {
        val owner = UUID.randomUUID()
        SqlitePresetRepository(dir.resolve("p.db")).use { repo ->
            val first = repo.save(owner, preset(), 16).await()
            val updated = repo.save(owner, first.copy(name = "Renamed", workSeconds = 600), 16).await()
            assertEquals("Renamed", repo.find(owner, first.id).await()?.name)
            assertEquals(600, repo.find(owner, first.id).await()?.workSeconds)
            assertEquals(updated.id, first.id)
        }
    }

    @Test fun `delete returns true only when owner matches`() {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        SqlitePresetRepository(dir.resolve("p.db")).use { repo ->
            val p = repo.save(owner, preset(), 16).await()
            assertFalse(repo.delete(other, p.id).await())
            assertEquals(1, repo.all(owner).await().size)
            assertTrue(repo.delete(owner, p.id).await())
            assertEquals(0, repo.all(owner).await().size)
        }
    }

    @Test fun `limit is enforced per player and presets do not leak across owners`() {
        val owner = UUID.randomUUID()
        SqlitePresetRepository(dir.resolve("p.db")).use { repo ->
            repeat(3) { repo.save(owner, preset(), 3).await() }
            assertTrue(repo.isFull(owner, 3).await())
            // The worker's refusal surfaces as the cause of the ExecutionException, matching the
            // economy repository tests.
            val rejected = assertThrows(ExecutionException::class.java) { repo.save(owner, preset(), 3).await() }
            assertTrue(rejected.cause is IllegalStateException)
            // Another owner has their own quota.
            val other = UUID.randomUUID()
            assertFalse(repo.isFull(other, 3).await())
        }
    }

    @Test fun `editing a preset the player already has still works when they are at the limit`() {
        // The cap is on how many a player **has**, so renaming one of them must not be refused:
        // afterwards they still have exactly the same number. Counting every row made this fail
        // for a player who was full, and the player was told they had reached the limit.
        val owner = UUID.randomUUID()
        SqlitePresetRepository(dir.resolve("p.db")).use { repo ->
            val first = repo.save(owner, preset(), 3).await()
            repo.save(owner, preset(), 3).await()
            repo.save(owner, preset(), 3).await()
            assertTrue(repo.isFull(owner, 3).await())

            val renamed = repo.save(owner, first.copy(name = "Renamed"), 3).await()
            assertEquals("Renamed", repo.find(owner, first.id).await()?.name)
            assertEquals(3, repo.all(owner).await().size, "editing must not add a row")
            assertEquals(renamed.id, first.id)
        }
    }

    @Test fun `all returns empty for a new player`() {
        SqlitePresetRepository(dir.resolve("p.db")).use { repo ->
            assertEquals(emptyList<Preset>(), repo.all(UUID.randomUUID()).await())
        }
    }
}
