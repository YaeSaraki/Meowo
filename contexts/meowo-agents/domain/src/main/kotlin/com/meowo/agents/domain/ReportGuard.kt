package com.meowo.agents.domain

import java.time.Instant
import java.util.UUID

/**
 * Anti-brigading for player reports, following the Xbox Live public write-up.
 *
 * The single most useful rule there: a complaint from someone who has never played with you counts
 * for almost nothing. [TraitMath.coScore] supplies the shared hours; this decides what they are
 * worth.
 */
object ReportGuard {

    /** Below this the report is stored but never counted. */
    const val MIN_CO_PLAY = 10

    /** Negative reports at the same subject inside an hour that trips the burst check. */
    const val BURST_THRESHOLD = 5

    /** Trusted negative reports that open a work order. */
    const val REVIEW_THRESHOLD = 5

    /** Reports expire so the table cannot grow forever. */
    const val RETENTION_DAYS = 30L

    /** Work orders expire unhandled. */
    const val WORK_ORDER_RETENTION_DAYS = 14L

    /**
     * Never write a report from someone with no shared play time.
     *
     * Xbox: *"if you're complaining about their behaviour and you never played with them, all of
     * that feedback doesn't matter as much as a single person who spent fifteen minutes with them."*
     */
    fun admits(author: UUID, subject: UUID, coScore: Int): Boolean =
        author != subject && coScore > 0

    /** Stored but not counted. */
    fun trusted(coScore: Int): Boolean = coScore >= MIN_CO_PLAY

    /**
     * True when the negative reports on [subject] inside [window] show a coordinated spike.
     *
     * Xbox: *"if a dozen people suddenly report a single user, the system will look at a variety of
     * factors before docking their reputation."* Here the whole window is distrusted rather than
     * weighted, which is the conservative reading — a pile-on must not be able to escalate.
     */
    fun isBurst(negativeAt: List<Instant>, subjectWindowStart: Instant): Boolean {
        val inWindow = negativeAt.count { !it.isBefore(subjectWindowStart) }
        return inWindow >= BURST_THRESHOLD
    }

    /** One-hour window starting at [now]. */
    fun burstWindowStart(now: Instant): Instant = now.minusSeconds(3600)

    /** Distinct trusted negative authors; the work order counts people, not messages. */
    fun independentAuthors(reports: List<Pair<UUID, Boolean>>): Int =
        reports.filter { it.second }.map { it.first }.distinct().size

    fun expiresAt(at: Instant): Instant = at.plusSeconds(RETENTION_DAYS * 86_400)

    fun workOrderExpiresAt(at: Instant): Instant = at.plusSeconds(WORK_ORDER_RETENTION_DAYS * 86_400)
}