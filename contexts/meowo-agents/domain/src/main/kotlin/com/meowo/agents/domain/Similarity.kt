package com.meowo.agents.domain

/**
 * One scoring dimension: two players, one number in `0.0..1.0`.
 *
 * `1.0` means "as alike as this dimension can measure", `0.0` means "as unalike".
 */
private typealias Dimension = (ChatShape, ActionShape, ChatShape, ActionShape) -> Double

/**
 * Friend recommendations, computed from the **descriptive** half of a profile only.
 *
 * ## The exclusion is the feature
 *
 * `soft_hits`, risk and anything else evaluative are hard-excluded. A recommendation engine that
 * read them would stop being a suggestion and become a blacklist that happens to be phrased
 * politely — "we think you'd get on" would really mean "nobody has been punished around here".
 * [RecommendationTest] asserts that changing `soft_hits` cannot move a single score.
 */
object Similarity {

    /**
     * The dimensions a recommendation may look at, all of them descriptive.
     *
     * Each entry returns a **similarity directly**. An earlier version compared "how alike a and b
     * are" against "how alike b and a are" and then took the difference; because every dimension
     * is symmetric by construction, that difference was always exactly zero and the score was a
     * constant `1.0`. Comparing a value with itself tells you nothing.
     */
    private val DIMENSIONS: List<Pair<Double, Dimension>> = listOf(
        // Active-hour overlap: the strongest, cheapest signal that two people are around together.
        0.30 to { a, _, b, _ -> hourOverlap(a.peakHour, b.peakHour) },
        0.25 to { a, _, b, _ -> publicPreference(a.publicPct, b.publicPct) },
        0.20 to { a, _, b, _ -> verbosity(a.avgLen, b.avgLen) },
        0.10 to { a, _, b, _ -> emoji(a.emojiPct, b.emojiPct) },
        0.15 to { _, aa, _, ab -> buildingStyle(aa.netBuild, ab.netBuild) },
    )

    /**
     * Circular closeness of two peak hours on a 24-hour clock.
     *
     * `1.0` means the same hour, `0.0` means twelve hours apart — the furthest two hours can be on
     * a clock, so no arrangement of times can look worse. A missing peak on either side is "no
     * idea", not "identical": returning `1.0` for an unknown hour once made this dimension
     * constant, which the tests caught.
     */
    internal fun hourOverlap(a: Int?, b: Int?): Double {
        if (a == null || b == null) return 0.0
        val raw = kotlin.math.abs(a - b).coerceAtMost(24)
        val distance = minOf(raw, 24 - raw)
        return (1.0 - distance / 12.0).coerceIn(0.0, 1.0)
    }

    private fun publicPreference(a: Double, b: Double): Double =
        1.0 - kotlin.math.abs(a / 100.0 - b / 100.0)

    private fun verbosity(a: Double, b: Double): Double =
        1.0 - kotlin.math.abs(
            (a / 120.0).coerceIn(0.0, 1.0) - (b / 120.0).coerceIn(0.0, 1.0),
        )

    private fun emoji(a: Double, b: Double): Double = 1.0 - kotlin.math.abs(a / 100.0 - b / 100.0)

    /**
     * Builders against clearers, on a single 0..1 axis.
     *
     * `netBuild` is the only action counter used, deliberately: the others are evaluative.
     */
    private fun buildingStyle(a: Int, b: Int): Double {
        fun norm(n: Int): Double = ((n.toDouble() / 200.0).coerceIn(-1.0, 1.0) + 1.0) / 2.0
        return 1.0 - kotlin.math.abs(norm(a) - norm(b))
    }

    /**
     * Weighted 0..1 over the descriptive dimensions. Identical profiles score 1; nothing here
     * rewards or punishes behaviour, only style.
     */
    fun score(a: ChatShape, aa: ActionShape, b: ChatShape, ab: ActionShape): Double {
        var total = 0.0
        var weight = 0.0
        for ((w, dimension) in DIMENSIONS) {
            total += w * dimension(a, aa, b, ab).coerceIn(0.0, 1.0)
            weight += w
        }
        return if (weight == 0.0) 0.0 else total / weight
    }

    /**
     * Top matches for the asker among [candidates], highest first.
     *
     * Excludes anyone already matched and anyone with too little data to compare fairly — a player
     * with three messages should not be declared dissimilar to everyone.
     */
    fun recommend(
        me: ChatShape,
        meActions: ActionShape,
        candidates: List<Profile>,
        limit: Int,
        exclude: Set<String> = emptySet(),
    ): List<Scored> = candidates.asSequence()
        .filter { it.id !in exclude }
        .filter { it.shape.samples >= TraitMath.MIN_SAMPLES }
        .map { Scored(it.id, it.name, score(me, meActions, it.shape, it.actions)) }
        .sortedByDescending { it.score }
        .take(limit)
        .toList()

    /** One candidate's identity and its score. [id] is a UUID string; it is never shown to a model. */
    data class Scored(val id: String, val name: String, val score: Double)

    /** A candidate as the recommender needs it. */
    data class Profile(
        val id: String,
        val name: String,
        val shape: ChatShape,
        val actions: ActionShape,
    )
}
