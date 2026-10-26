package com.meowo.mine.domain

/**
 * The registry of rare materials this server has produced.
 *
 * Every find is reported, so this is no longer a one-shot gate — it is a **discovery register**:
 * it answers "which rare materials has this server ever produced, and who got there first".
 *
 * That question is what fixes the granularity. Per material kind the size is a hard constant
 * (five, the enum's size). Per region would grow without bound and would answer a different —
 * and worse — question, because vanilla's own statistics already track where each block was mined.
 * Re-recording that here with less fidelity, plus a cleanup policy, buys nothing.
 *
 * @param discovered kinds ever produced, which is also what `/mine` lists.
 * @param totals running counts per kind. **Deliberately kept out of the broadcast**: the vanilla
 *   statistics screen already reports per-player totals, and a second number in chat would only
 *   compete with it. It exists so an operator can ask the plugin what it saw.
 * @param firstBy who produced each kind first, for the discovery announcement.
 */
data class DiscoveryLedger(
    val discovered: Set<RareKind> = emptySet(),
    val totals: Map<RareKind, Int> = emptyMap(),
    val firstBy: Map<RareKind, String> = emptyMap(),
) {
    /**
     * Records [amount] of [kind] found by [player].
     *
     * Returns a report for **every** find, with [Discovery.isFirst] telling the caller whether this
     * is the material's first appearance. A non-positive amount is the only rejection.
     */
    fun record(kind: RareKind, amount: Int, player: String): Discovery? {
        if (amount <= 0) return null
        val isFirst = kind !in discovered
        val next = copy(
            discovered = discovered + kind,
            totals = totals + (kind to (totals[kind] ?: 0) + amount),
            firstBy = if (isFirst) firstBy + (kind to player) else firstBy,
        )
        return Discovery(kind, amount, isFirst, next)
    }

    /** Whether [kind] has ever been produced here. */
    fun isDiscovered(kind: RareKind): Boolean = kind in discovered

    /** Running total seen for [kind]; operator-facing, not broadcast. */
    fun totalOf(kind: RareKind): Int = totals[kind] ?: 0

    /** Who produced [kind] first, or null if it never appeared. */
    fun firstFinder(kind: RareKind): String? = firstBy[kind]
}
