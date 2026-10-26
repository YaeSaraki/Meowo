package com.meowo.agents.domain

import java.util.UUID

/** How much of a report a viewer may see. Mirrors the plan's 可见性 table. */
enum class ReportVisibility {
    /** The subject: everything about themselves, author included, so an appeal is possible. */
    SELF,

    /** Everyone else: counts only. Neither the wording nor who said it. */
    AGGREGATE,

    /** A moderator: the full record, for triage. */
    ADMIN,
}

/** One player's view of one report. */
data class VisibleReport(
    val id: String,
    val sentiment: Sentiment,
    val reason: String,
    val author: String?,
    val atEpochMillis: Long,
)

enum class Sentiment { POSITIVE, NEGATIVE }

/** An aggregated tally, the only thing a non-subject ever receives. */
data class ReportSummary(val positive: Int, val negative: Int)

/**
 * Cuts a report list down to what [viewer] is allowed to see.
 *
 * Two separate limits are enforced here rather than at the call site, because getting one right
 * and the other wrong is exactly how a privacy bug happens: the subject sees their own record in
 * full (so a false report can be contested), everyone else sees only a tally, and an admin sees
 * the record but without the subject's other data.
 */
object ReportVisibilityCut {

    fun forViewer(
        viewer: UUID,
        subject: UUID,
        isAdmin: Boolean,
        reports: List<VisibleReport>,
    ): List<VisibleReport> = when (visibilityOf(viewer, subject, isAdmin)) {
        ReportVisibility.SELF, ReportVisibility.ADMIN -> reports
        ReportVisibility.AGGREGATE -> emptyList()
    }

    fun visibilityOf(viewer: UUID, subject: UUID, isAdmin: Boolean): ReportVisibility = when {
        viewer == subject -> ReportVisibility.SELF
        isAdmin -> ReportVisibility.ADMIN
        else -> ReportVisibility.AGGREGATE
    }

    /**
     * The only rendering a non-subject, non-admin ever receives: two numbers.
     *
     * [summary] is what other players see, and it deliberately carries no author, no wording and no
     * timestamps — a count cannot be used to run a pile-on.
     */
    fun summaryOf(reports: List<VisibleReport>): ReportSummary = ReportSummary(
        positive = reports.count { it.sentiment == Sentiment.POSITIVE },
        negative = reports.count { it.sentiment == Sentiment.NEGATIVE },
    )
}