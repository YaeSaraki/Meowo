package com.meowo.invsee

/**
 * Tunables for `/invsee`.
 *
 * Deliberately one knob. Everything else about the window is a rule about **item conservation**, and
 * a setting that could switch one of those off would be a setting that could duplicate a stack; the
 * only thing worth an operator's choice is whether the transfers are written down.
 */
data class InvseeSettings(
    /**
     * Write every take and give to the server log.
     *
     * On by default. An admin moving items between two players is exactly the action a complaint
     * ("they took my diamonds") will be argued from, and a log line naming both players and the
     * stack is the difference between settling it and not believing anybody.
     */
    val audit: Boolean = true,
)