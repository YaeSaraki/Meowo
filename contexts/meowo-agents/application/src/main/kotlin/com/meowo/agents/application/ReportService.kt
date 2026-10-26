package com.meowo.agents.application

import com.meowo.agents.domain.ReportGuard
import com.meowo.agents.domain.Sentiment
import com.meowo.agents.infrastructure.PlayerProfileStore
import com.meowo.agents.infrastructure.StoredReport
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Accepting a player's account of another player, and deciding what it is worth.
 *
 * ## The cap is at the door, not in the store
 *
 * Earlier reasoning settled on **rate-limiting admissions rather than evicting rows**: a hard
 * "keep the newest N per subject" would quietly delete the oldest reports, and the oldest are
 * exactly the ones an open work order is standing on. So [maxPerAuthorPerDay] limits how fast one
 * person can add to one person's file, the 30-day retention does the bounding, and nothing is ever
 * overwritten or dropped to make room. An old accusation stays exactly where it was put.
 *
 * ## Nothing here is a verdict
 *
 * A report opens a work order and nothing more. [submit] returns what it decided so the caller can
 * tell the reporter honestly whether it counted; the ladder is a separate, later, periodic thing.
 */
class ReportService(
    private val store: PlayerProfileStore,
    /** Shared hours of play, which is what makes a complaint worth hearing at all. */
    private val coScoreOf: (author: UUID, subject: UUID) -> Int,
    private val now: () -> Instant = Instant::now,
    private val maxPerAuthorPerDay: Int = 1,
) {
    /** What happened, phrased for the reporter rather than for the log. */
    sealed interface Outcome {
        /**
         * The report is on file.
         *
         * [why] is a **language key**, never text. This layer has no language file and must not
         * have one, and a Chinese sentence hard-coded here is a sentence an English player cannot
         * read — which is a bug that only shows up on a server nobody testing it speaks the
         * language of. The command layer resolves the key.
         */
        data class Stored(val counted: Boolean, val why: String) : Outcome

        /** The report was not admitted. [why] is a language key, as above. */
        data class Refused(val why: String) : Outcome
    }

    private val admitted = AtomicInteger()
    private val refused = AtomicInteger()
    private val distrusted = AtomicInteger()

    /**
     * Xbox's rule, applied first and without exception: a complaint from somebody who has never
     * played with you carries almost nothing. This is the single most effective anti-brigading rule
     * in the whole design, and it costs one integer to check.
     */
    fun submit(author: UUID, subject: UUID, sentiment: Sentiment, reason: String): Outcome {
        if (author == subject) return refuse("report-refuse-self")
        val co = coScoreOf(author, subject)
        if (!ReportGuard.admits(author, subject, co)) {
            return refuse("report-refuse-coplay")
        }
        val trusted = ReportGuard.trusted(co)
        val at = now()

        // The rate limit is per author *and* per subject, so one enthusiastic person cannot file
        // against fifty people and a coordinated group cannot each file against the same one.
        if (countSince(author, subject, at) >= maxPerAuthorPerDay) {
            return refuse("report-refuse-rate")
        }
        // A burst distrusts the whole window rather than weighting it: a pile-on must not escalate.
        val negativeTimes = store.reportsAbout(subject.toString(), at.toEpochMilli())
            .filter { it.sentiment == Sentiment.NEGATIVE }
            .map { Instant.ofEpochMilli(it.atEpochMillis) }
        val burst = sentiment == Sentiment.NEGATIVE &&
            ReportGuard.isBurst(negativeTimes, ReportGuard.burstWindowStart(at))
        val counted = trusted && !burst

        store.insertReport(
            StoredReport(
                id = "r-$author-$subject-${at.toEpochMilli()}",
                author = author.toString(),
                subject = subject.toString(),
                sentiment = sentiment,
                reason = reason.take(MAX_REASON_CHARS),
                atEpochMillis = at.toEpochMilli(),
                expiresAt = ReportGuard.expiresAt(at).toEpochMilli(),
                coScore = co,
                trusted = if (counted) 1 else 0,
                state = "OPEN",
            ),
        )
        /**
         * The discipline cat's counter moves with the complaint.
         *
         * Only a complaint that **counted** moves it: one that was distrusted — too little shared
         * play, or one of a pile-on — is on file as a thing that was said, and counting it as
         * evidence is exactly the brigading the guard exists to stop. The counter is what the 风纪
         * score is computed from, so this line is what connects "a player complained" to "the chat
         * gate looks harder at them".
         */
        if (counted) store.bumpSoftHits(subject.toString(), 1, at.toEpochMilli())
        admitted.incrementAndGet()
        if (burst) distrusted.incrementAndGet()
        return Outcome.Stored(
            counted = counted,
            why = when {
                burst -> "report-stored-burst"
                !trusted -> "report-stored-untrusted"
                else -> "report-stored"
            },
        )
    }

    /** Trusted, independent negative authors on file right now. */
    fun independentAccusations(subject: UUID): Int {
        val now = now().toEpochMilli()
        val rows = store.reportsAbout(subject.toString(), now)
        return ReportGuard.independentAuthors(
            rows.filter { it.sentiment == Sentiment.NEGATIVE }.map { UUID.fromString(it.author) to (it.trusted == 1) },
        )
    }

    /**
     * Whether enough *distinct people* have complained to justify telling the subject.
     *
     * People, not messages: three identical accusations from one account is one accusation.
     */
    fun worthTelling(subject: UUID): Boolean = independentAccusations(subject) >= NOTIFY_THRESHOLD

    fun worthAnOrder(subject: UUID): Boolean = independentAccusations(subject) >= ReportGuard.REVIEW_THRESHOLD

    /**
     * How many this author has filed against this subject in the last day.
     *
     * `reportsAbout` filters by **expiry**, not by when a report was written, so passing a
     * one-day-ago timestamp here would have counted every unexpired report in the file — turning
     * "one a day" into "one ever", and quietly making the rate limit far stricter than it reads.
     */
    private fun countSince(author: UUID, subject: UUID, at: Instant): Int {
        val since = at.minusSeconds(86_400).toEpochMilli()
        // Not `reportsAbout`: this counts an author's conduct, and a subject who has asked to be
        // forgotten must not be able to launder it. See the store method for the long form.
        return store.reportsByAuthorSince(author.toString(), subject.toString(), since)
    }

    private fun refuse(why: String): Outcome {
        refused.incrementAndGet()
        return Outcome.Refused(why)
    }

    fun admitted(): Int = admitted.get()
    fun refused(): Int = refused.get()
    fun distrusted(): Int = distrusted.get()

    companion object {
        /** One account is enough to open a conversation; the ladder's own L1 gate is separate. */
        const val NOTIFY_THRESHOLD = 3
        const val MAX_REASON_CHARS = 200
    }
}
