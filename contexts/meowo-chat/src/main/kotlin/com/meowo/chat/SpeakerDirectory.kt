package com.meowo.chat

import com.meowo.chat.domain.Directory
import com.meowo.chat.domain.Participant
import com.meowo.platform.PlatformHost
import org.bukkit.Bukkit

/**
 * Builds a [com.meowo.chat.domain.Directory] out of the plugin's speaker table.
 *
 * `Messages` stays the single owner of who the cats are; this only reads it. Names match on both
 * the display name and the raw id, so `/msg EconCat` and `/msg economy` reach the same place.
 */
class SpeakerDirectory(private val host: PlatformHost) {

    fun build(playerFirst: Boolean) = Directory(
        speakers = host.messages.speakerIds.sorted().map {
            Directory.SpeakerInfo(
                id = it,
                handle = host.messages.speaker(it),
                summary = host.messages.speakerSummary(it),
            )
        },
        resolvePlayer = ::resolvePlayer,
        playerFirst = playerFirst,
    )

    /**
     * Resolves a name to a real player without ever blocking the main thread on a network lookup —
     * the same discipline `EconomyCommand.resolve` already follows. An unknown name yields null
     * rather than a fabricated player.
     */
    fun resolvePlayer(input: String): Participant.Player? =
        Bukkit.getPlayerExact(input)?.let { Participant.Player(it.name, it.uniqueId, true) }
            ?: runCatching { Bukkit.getOfflinePlayerIfCached(input) }.getOrNull()
                ?.let { Participant.Player(input, it.uniqueId, false) }

    fun onlinePlayers(): List<Participant.Player> =
        Bukkit.getOnlinePlayers().map { Participant.Player(it.name, it.uniqueId, true) }
}
