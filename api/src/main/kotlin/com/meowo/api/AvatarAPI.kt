package com.meowo.api

import java.util.UUID

/**
 * What the menu needs to know about the player's **own** avatar.
 *
 * Two features live in the `avatar` context, and both are **per player** — one player's choice never
 * touches another's:
 *
 * - **no night phantoms**: the vanilla insomnia mechanic stops picking this player, so staying up
 *   all night no longer sends a phantom after them;
 * - **the size of their own body**.
 *
 * Both are read by the main menu, which is a different context. A contract in `api` is what makes
 * that legal: the menu resolves this service by type, so neither context imports the other. See
 * [UIAPI] for the same shape, and `HomeMenuAPI` for the one that already existed for the homes
 * screen.
 *
 * The values are plain data with no `Player` in them, so a caller cannot hold a stale player
 * object — the same reason [UIAPI] takes a `UUID` rather than a player.
 */
interface AvatarAPI {
    /**
     * Whether [player] has switched the **insomnia phantoms** off for themselves.
     *
     * It is the vanilla mechanic that is being cancelled, not a mob that is being added: a player
     * who stays up too long is picked out of the sky by a phantom, and this says "not you".
     */
    fun phantomsBlocked(player: UUID): Boolean

    /** The body size of [player]; `1.0` is an ordinary body. */
    fun scaleOf(player: UUID): Double
}
