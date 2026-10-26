package com.meowo.mine.domain

/** One find of a rare material, worth reporting. */
data class Discovery(
    val kind: RareKind,
    /** How much was in **this** event, which is what the broadcast shows. */
    val amount: Int,
    /** True the first time this material ever appears on the server. */
    val isFirst: Boolean,
    val next: DiscoveryLedger,
)
