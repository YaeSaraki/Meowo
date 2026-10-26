package com.meowo.avatar

import com.meowo.avatar.domain.ScaleLimits

/**
 * The tunables for the avatar context, read once in `start()` so a reload re-reads them.
 *
 * ## What this context is for
 *
 * Two things a player owns and nobody else does:
 *
 * - being **left alone by the night phantoms** — the vanilla insomnia mechanic stops choosing them, and
 * - the **size of their own body**.
 *
 * Both are per player, both are free, and both are switched on from the main menu.
 *
 * ## Where the numbers live
 *
 * The *rules* — what a step is, how far the size may go — are in the pure `domain` half. The *numbers*
 * are here, because they are settings an operator may want to change.
 *
 * @param enabled whether the context does anything at all.
 * @param scale the size grid: the range, the step and the default.
 */
data class AvatarSettings(
    val enabled: Boolean = true,
    val scale: ScaleLimits = ScaleLimits.DEFAULT,
)
