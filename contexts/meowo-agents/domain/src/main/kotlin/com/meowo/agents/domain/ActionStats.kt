package com.meowo.agents.domain

/**
 * Counters for what a player actually *did*, not what they said.
 *
 * ## Why totals are not recorded
 *
 * "Blocks broken" carries almost no information on its own: a farmer breaking ten thousand dirt is
 * doing their job, and so is someone with Efficiency V breaking ten thousand stone, while a griefer
 * breaks two hundred obsidian that belonged to somebody else. So this records **categorised**
 * counts and never a bare total.
 *
 * `brokenOthersPlacedValuable` is the one number that separates the two — it asks not *how much*
 * but *whose*. It cannot be derived without knowing a block's original owner, so it stays at zero
 * unless a CoreProtect-backed source fills it in (see the plan's 操作日记 section).
 */
class ActionCounters {
    /** Broken by ordinary material — dirt, stone, wood. The bulk of honest labour. */
    var brokenCommon: Int = 0; private set

    /** Broken by valuable material — diamonds, obsidian, valuables in someone else's build. */
    var brokenValuable: Int = 0; private set

    /** **Filled only by an ownership-aware source.** Zero without CoreProtect. */
    var brokenOthersPlacedValuable: Int = 0; private set

    var placed: Int = 0; private set
    var containerTouches: Int = 0; private set
    var pvpKills: Int = 0; private set
    var pvpDeaths: Int = 0; private set

    fun onBreak(valuable: Boolean, othersPlaced: Boolean = false) {
        if (valuable) brokenValuable++ else brokenCommon++
        if (othersPlaced) brokenOthersPlacedValuable++
    }

    fun onPlace() { placed++ }
    fun onContainer() { containerTouches++ }
    fun onPvp(killed: Boolean) { if (killed) pvpKills++ else pvpDeaths++ }

    /** Net building. A large negative number is a signal; on its own it is not a verdict. */
    fun netBuild(): Int = placed - (brokenCommon + brokenValuable)
}

/**
 * Reduces action counters to a few comparable facts.
 *
 * **Everything here is evaluative and therefore excluded from friend recommendations**
 * (see [Similarity]) and from any prompt: a profile that fed these into "who should I befriend"
 * would be a blacklist wearing a friendly hat.
 */
data class ActionShape(
    val brokenCommon: Int,
    val brokenValuable: Int,
    val brokenOthersPlacedValuable: Int,
    val placed: Int,
    val pvpKills: Int,
    val netBuild: Int,
) {
    /**
     * True when the ownership-aware signal is strong enough to corroborate a report.
     *
     * Without CoreProtect this is always false, which is why the work order's third gate
     * (事实印证) can never be passed on opinion alone.
     */
    val corroborates: Boolean
        get() = brokenOthersPlacedValuable >= CORROBORATION_THRESHOLD
            && brokenOthersPlacedValuable * 3 >= brokenValuable

    companion object {
        const val CORROBORATION_THRESHOLD = 20
    }
}

object ActionStats {

    fun shapeOf(c: ActionCounters) = ActionShape(
        brokenCommon = c.brokenCommon,
        brokenValuable = c.brokenValuable,
        brokenOthersPlacedValuable = c.brokenOthersPlacedValuable,
        placed = c.placed,
        pvpKills = c.pvpKills,
        netBuild = c.netBuild(),
    )

    /**
     * The counter-example that keeps this honest.
     *
     * A farmer and a griefer can break the *same number* of blocks. If the summary only looked at
     * `broken`, they would score identically. This is what the plan means by 分类且带语境.
     */
    fun describe(s: ActionShape): String? {
        if (s.brokenCommon + s.brokenValuable + s.placed == 0) return null
        val parts = mutableListOf<String>()
        if (s.brokenValuable > s.brokenCommon) parts += "破坏以贵重方块为主"
        else if (s.brokenCommon > 0) parts += "主要在处理普通方块"
        if (s.netBuild > 0) parts += "净建造 +${s.netBuild}"
        else if (s.netBuild < 0) parts += "净破坏 ${s.netBuild}"
        return parts.takeIf { it.isNotEmpty() }?.joinToString("，") + "。"
    }

    /** Materials worth protecting, used to bucket [ActionCounters.onBreak]. */
    fun isValuable(materialName: String): Boolean = materialName.endsWith("_HELMET") ||
        materialName.endsWith("_CHESTPLATE") ||
        materialName.endsWith("_LEGGINGS") ||
        materialName.endsWith("_BOOTS") ||
        materialName.endsWith("_SWORD") ||
        materialName.endsWith("_PICKAXE") ||
        materialName.endsWith("_AXE") ||
        materialName.endsWith("_SHOVEL") ||
        materialName.endsWith("_HOE") ||
        materialName == "OBSIDIAN" ||
        materialName.endsWith("_BLOCK") && materialName.startsWith("NETHERITE")
}