package com.meowo.hat

/**
 * Tunables for `/hat` and the head-slot behaviour in the player inventory.
 */
data class HatSettings(
    /** When false (the default) tools are refused and everything else may be worn. */
    val allowTools: Boolean = false,
    /** Extra materials refused even though they would otherwise be wearable, e.g. `BEDROCK`. */
    val deny: Set<String> = emptySet(),
    /** When true, clicking an item onto the head slot in the player inventory wears it. */
    val inventorySlot: Boolean = true,
) {
    init {
        require(deny.none { it.isBlank() }) { "hat.deny must not contain blank entries" }
    }
}
