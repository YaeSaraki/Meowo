package com.meowo.mine.domain

/**
 * The rare materials worth reporting, independent of any Bukkit type.
 *
 * Deepslate ore is deliberately its **own** kind rather than folded into the normal ore: a player
 * grinding deepslate and one working the overworld surface are doing different things, and the
 * report should not blur them together.
 *
 * Naming uses what a player thinks in terms of, so a rename in Paper cannot silently change what
 * gets announced.
 */
enum class RareKind(val labelKey: String) {
    /** 钻石 — `DIAMOND_ORE`. */
    DIAMOND("rare-diamond"),

    /** 深层钻石 — `DEEPSLATE_DIAMOND_ORE`. */
    DEEPSLATE_DIAMOND("rare-deepslate-diamond"),

    /** 绿宝石 — `EMERALD_ORE`. */
    EMERALD("rare-emerald"),

    /** 深层绿宝石 — `DEEPSLATE_EMERALD_ORE`. */
    DEEPSLATE_EMERALD("rare-deepslate-emerald"),

    /**
     * 下界合金 — there is **no** `NETHERITE_ORE` block in this version. The netherite source block
     * is `ANCIENT_DEBRIS` (下界古残骸), which drops netherite scrap. Verified against
     * paper-api 26.2: `Material` has no `NETHERITE_ORE` at all, so writing one would not compile.
     */
    NETHERITE("rare-netherite"),
}
