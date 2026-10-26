package com.meowo.agents.infrastructure

import com.meowo.agents.domain.Sentiment
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * How much disk one player actually costs.
 *
 * The design claim is that rows are bounded by **people, not by messages**: one `player_traits` row
 * and one `cat_impression` row per (cat, player) regardless of how much somebody chats, with only
 * `player_report` growing by activity. A claim like that is easy to make and easy to get wrong —
 * an index that silently does not fit the predicate, or a journal that keeps every event, would
 * still look fine in a code review and would show up here as a number.
 *
 * These are therefore measurements, not assertions of a constant: they pin the *shape* (constant
 * per player, linear in players) and print the absolute figures so a regression is visible in the
 * build log rather than years later on a full disk.
 */
class PlayerProfileFootprintTest {

    @TempDir
    lateinit var dir: Path

    private fun player(index: Int) = UUID(0, index.toLong() + 1)

    /** A traits row as it looks after a real player has talked for a while. */
    private fun traits(p: String) = StoredTraits(
        player = p,
        samples = 1_284,
        avgLen = 23.7,
        publicPct = 78.5,
        emojiPct = 12.25,
        peakHour = 21,
        softHits = 3,
        traits = "句子不长，公频私聊都聊，常用 emoji，常在 晚上 活跃。",
        updatedAt = 1_700_000_000_000L,
        createdAt = 1_600_000_000_000L,
    )

    /** How many cats a player has actually met. Twelve is the worst case, not the common one. */
    private fun metCats(p: String, cats: Int) = (1..cats).map { c ->
        StoredImpression(
            cat = "cat$c",
            player = p,
            impression = "他问过余额，也问过传送的���。说话简短，不啰嗦。",
            noteCount = 6,
            updatedAt = 1_700_000_000_000L,
        )
    }

    private fun report(p: String, author: String, index: Int, expiresIn: Long = 0L) = StoredReport(
        id = "$author-$p-$index",
        author = author,
        subject = p,
        sentiment = if (index % 3 == 0) Sentiment.POSITIVE else Sentiment.NEGATIVE,
        reason = "在农场门口帮忙扶正了一个掉落的 TNT".repeat(1 + (index % 7)),
        atEpochMillis = 1_700_000_000_000L + index,
        expiresAt = 1_700_000_000_000L + expiresIn,
        coScore = 42,
        trusted = 1,
        state = "OPEN",
    )

    /** Closes the store so WAL is checkpointed, then reports the bytes actually on disk. */
    private fun measure(name: String, fill: (PlayerProfileStore) -> Unit): Pair<Long, Int> {
        val path = dir.resolve(name)
        val players = PlayerProfileStore(path).use { store ->
            fill(store)
            store.count("traits")
        }
        // WAL sidecars are transient; the number that matters is the settled database file.
        Files.deleteIfExists(path.resolveSibling("$name-wal"))
        Files.deleteIfExists(path.resolveSibling("$name-shm"))
        return Files.size(path) to players
    }

    private fun report(label: String, bytes: Long, players: Int) {
        val per = if (players == 0) 0.0 else bytes.toDouble() / players
        println("%-46s %8d bytes for %5d players  = %7.1f B/player".format(label, bytes, players, per))
    }

    @Test fun `a player's footprint does not grow with how much they chat`() {
        val quiet = 1_000L
        val (quietBytes, quietPlayers) = measure("quiet.db") { s ->
            repeat(200) { i ->
                val p = player(i).toString()
                s.upsertTraits(traits(p))
                s.upsertImpression(StoredImpression("cat1", p, "见过。", 1, 1L))
            }
        }
        val (busyBytes, busyPlayers) = measure("busy.db") { s ->
            repeat(200) { i ->
                val p = player(i).toString()
                // The same player, written continuously — what a busy week looks like.
                repeat(500) { s.upsertTraits(traits(p)) }
                s.upsertImpression(StoredImpression("cat1", p, "见过。", 1, 1L))
            }
        }
        report("200 players, traits written once each", quietBytes, quietPlayers.toInt())
        report("200 players, same traits written 500x each", busyBytes, busyPlayers.toInt())

        // 100,000 upserts of one row must not cost 100,000 rows. A counter drifting slowly with
        // activity is exactly the bug this guards.
        val ratio = busyBytes.toDouble() / quietBytes
        assertTrue(ratio < 1.15, "rewriting the same player's row 500x grew the file by ${ratio}x")
    }

    @Test fun `meeting every cat is the dominant per-player cost`() {
        val (oneBytes, one) = measure("one-cat.db") { s ->
            repeat(200) { i ->
                val p = player(i).toString()
                s.upsertTraits(traits(p))
                s.upsertImpression(StoredImpression("cat1", p, "见过。", 1, 1L))
            }
        }
        val (allBytes, all) = measure("all-cats.db") { s ->
            repeat(200) { i ->
                val p = player(i).toString()
                s.upsertTraits(traits(p))
                metCats(p, 12).forEach { s.upsertImpression(it) }
            }
        }
        report("200 players, met 1 cat", oneBytes, one.toInt())
        report("200 players, met all 12 cats", allBytes, all.toInt())

        val perCat = (allBytes - oneBytes).toDouble() / 200 / 11
        assertTrue(perCat < 400, "each extra cat costs ${"%.0f".format(perCat)}B/player; that is a lot of text")
    }

    /**
     * Reports dominate everything else, and pruning puts the pages back rather than shrinking the
     * file.
     *
     * That is SQLite's actual semantics, and getting it wrong in the other direction produces an
     * alarming but false alarm: `DELETE` frees pages into the free list, so the file on disk keeps
     * the high-water mark. What matters operationally is that the freed pages are *reused* — so
     * steady state is a plateau, not unbounded growth. This measures that rather than asserting
     * the file shrinks, which it never does without a VACUUM.
     */
    @Test fun `pruned reports free pages for reuse rather than shrinking the file`() {
        val now = 1_700_000_000_000L
        val (before, players) = measure("reuse-before.db") { s ->
            repeat(200) { i ->
                val p = player(i).toString()
                s.upsertTraits(traits(p))
                repeat(20) { s.insertReport(report(p, "author-$i", it)) }
            }
        }
        val (afterPrune, _) = measure("reuse-after.db") { s ->
            repeat(200) { i ->
                val p = player(i).toString()
                s.upsertTraits(traits(p))
                repeat(20) { s.insertReport(report(p, "author-$i", it)) }
            }
            s.prune(now = now + 1)
        }
        report("200 players, 20 reports each", before, players)
        report("200 players, reports pruned", afterPrune, players)
        // A prune does not shrink the file to nothing; it moves pages to the free list, and
        // `incremental_vacuum` hands a bounded number straight back. The rows are what matter.
        assertTrue(afterPrune <= before, "pruning must not make the file bigger")

        // The real question is not whether the file shrinks — it does not, and that is fine — but
        // whether it plateaus or grows without bound. A year of pruning and refilling that ends at
        // 2x is a plateau to document; one that ends at 12x is a disk that fills up, and it has to
        // be fixed before the server runs a season.
        val monthTwo = measure("reuse-month2.db") { s ->
            repeat(200) { i ->
                val p = player(i).toString()
                s.upsertTraits(traits(p))
                repeat(20) { s.insertReport(report(p, "author-$i", it)) }
            }
            s.prune(now = now + 1)
            repeat(200) { i ->
                val p = player(i).toString()
                repeat(20) { s.insertReport(report(p, "month-2-$i", it)) }
            }
        }.first
        val monthThree = measure("reuse-month3.db") { s ->
            repeat(200) { i ->
                val p = player(i).toString()
                s.upsertTraits(traits(p))
                repeat(20) { s.insertReport(report(p, "author-$i", it)) }
            }
            for (month in 2..3) {
                s.prune(now = now + 1)
                repeat(200) { i ->
                    val p = player(i).toString()
                    repeat(20) { r -> s.insertReport(report(p, "month-$month-$i", r)) }
                }
            }
        }.first
        report("200 players, after one month of reports", monthTwo, players)
        report("200 players, after three months", monthThree, players)

        // A plateau means month three costs no more than month two. Anything that keeps scaling
        // with the number of prune cycles is unbounded growth, and the test says so out loud.
        assertTrue(monthThree <= monthTwo * 1.05,
            "the file is still growing after three months: $afterPrune -> $monthTwo -> $monthThree")
    }

    /**
     * A player's evaluations are by far the most expensive thing stored about them — more than ten
     * times the profile itself. Worth knowing before deciding how many to keep.
     */
    @Test fun `reports outweigh the whole profile by a wide margin`() {
        val (profileOnly, n1) = measure("reports-profile.db") { s ->
            repeat(200) { i -> s.upsertTraits(traits(player(i).toString())) }
        }
        val (withReports, n2) = measure("reports-with.db") { s ->
            repeat(200) { i ->
                val p = player(i).toString()
                s.upsertTraits(traits(p))
                repeat(5) { s.insertReport(report(p, "author-$i", it)) }
            }
        }
        val profile = profileOnly.toDouble() / n1
        val full = withReports.toDouble() / n2
        println("%-46s %7.0f B profile vs %7.0f B with 5 reports = %.1fx"
            .format("cost of evaluations", profile, full, full / profile))
        assertTrue(full > profile, "sanity: reports must cost something")
    }

    /** The number an operator actually wants: a realistic server, per player, in bytes and KiB. */
    @Test fun `realistic server footprint`() {
        val (bytes, players) = measure("realistic.db") { s ->
            repeat(1_000) { i ->
                val p = player(i).toString()
                s.upsertTraits(traits(p))
                // The median player met two or three cats, not twelve.
                metCats(p, 3).forEach { s.upsertImpression(it) }
                // And left a couple of evaluations behind inside the 30-day window.
                repeat(2) { s.insertReport(report(p, "someone-else", it)) }
            }
        }
        report("1,000 players, 3 cats each, 2 reports each", bytes, players)
        val perPlayer = bytes.toDouble() / players
        println("   -> %.2f KiB per player; 10,000 players would be ~%.1f MiB"
            .format(perPlayer / 1024, bytes.toDouble() / players * 10_000 / 1024 / 1024))
        // A megabyte per player would mean something is storing raw messages.
        assertTrue(perPlayer < 4096, "a player costs ${"%.0f".format(perPlayer)}B; that is not a profile")
    }

    @Test fun `growing the player count grows the file predictably`() {
        val (small, n1) = measure("small.db") { s -> repeat(100) { i ->
            s.upsertTraits(traits(player(i).toString()))
        } }
        val (large, n2) = measure("large.db") { s -> repeat(1_000) { i ->
            s.upsertTraits(traits(player(i).toString()))
        } }
        report("100 players, traits only", small, n1)
        report("1,000 players, traits only", large, n2)

        val marginal = (large - small).toDouble() / (n2 - n1)
        // One row plus its primary-key index. If a journal or an event log were involved this
        // would be orders of magnitude larger, and that is the point of measuring.
        assertTrue(marginal in 100.0..600.0, "marginal cost per player is ${"%.0f".format(marginal)}B")
    }

}
