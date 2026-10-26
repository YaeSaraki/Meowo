package com.meowo.pomodoro.infrastructure.persistence

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class SqliteFocusLogRepositoryTest {
    @TempDir lateinit var dir: Path

    private fun <T> java.util.concurrent.CompletableFuture<T>.await(): T = get(5, TimeUnit.SECONDS)

    @Test fun `logWork then totalSeconds sums all durations`() {
        SqliteFocusLogRepository(dir.resolve("f.db")).use { repo ->
            val owner = UUID.randomUUID()
            repo.logWork(owner, "p1", "Standard", 1, 1500).await()
            repo.logWork(owner, "p1", "Standard", 2, 600).await()
            assertEquals(2100L, repo.totalSeconds(owner).await())
        }
    }

    @Test fun `secondsOnDate scopes the sum to one calendar day`() {
        SqliteFocusLogRepository(dir.resolve("f.db")).use { repo ->
            val owner = UUID.randomUUID()
            val today = LocalDate.now()
            repo.logWork(owner, "p1", "Standard", 1, 1500).await()
            repo.logWork(owner, "p1", "Standard", 2, 900).await()
            assertEquals(2400L, repo.secondsOnDate(owner, today.toString()).await())
            assertEquals(0L, repo.secondsOnDate(owner, "1999-01-01").await())
        }
    }

    @Test fun `logWork rejects zero and negative durations`() {
        SqliteFocusLogRepository(dir.resolve("f.db")).use { repo ->
            // A future completed exceptionally reports the cause through ExecutionException, which
            // is the same shape the economy persistence tests assert on.
            val zero = assertThrows(ExecutionException::class.java) { repo.logWork(UUID.randomUUID(), "p", "n", 1, 0).await() }
            assertTrue(zero.cause is IllegalArgumentException)
            val negative = assertThrows(ExecutionException::class.java) { repo.logWork(UUID.randomUUID(), "p", "n", 1, -10).await() }
            assertTrue(negative.cause is IllegalArgumentException)
        }
    }

    @Test fun `totalSeconds for an unknown owner is zero`() {
        SqliteFocusLogRepository(dir.resolve("f.db")).use { repo ->
            assertEquals(0L, repo.totalSeconds(UUID.randomUUID()).await())
        }
    }
}
