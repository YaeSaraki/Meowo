package com.meowo.chat.domain

import java.util.Locale
import java.util.UUID

/**
 * Resolves a typed name to a [Participant].
 *
 * ## System speakers win by default
 *
 * If a player renames themselves `EconCat`, `/msg EconCat` still reaches the system speaker unless
 * the operator opts into [playerFirst]. Letting a player capture a system identity means any
 * message sent to a cat could be diverted, which is a far worse failure than "a player called
 * EconCat cannot reach it under that name".
 *
 * Player lookup is a *resolver*, not a lookup: the platform supplies it, so the domain never has to
 * ask Bukkit whether an account exists — and in particular never triggers a blocking name lookup on
 * the main thread.
 */
class Directory(
    private val speakers: List<SpeakerInfo>,
    /** Speaker ids that currently have somebody listening. */
    private val answerable: Set<String> = emptySet(),
    private val resolvePlayer: (String) -> Participant.Player?,
    val playerFirst: Boolean = false,
) {

    /** The static facts about a speaker; [Participant.System] is derived from these. */
    data class SpeakerInfo(val id: String, val handle: String, val summary: String)

    private fun participants(): List<Participant.System> = speakers.map {
        Participant.System(it.id, it.handle, it.summary, it.id in answerable)
    }

    fun find(input: String): Participant? {
        val name = input.trim()
        if (name.isEmpty()) return null
        // Match on the display name (`EconCat`) or the raw id (`economy`); both reach the same cat.
        val speaker = participants().firstOrNull {
            it.handle.equals(name, ignoreCase = true) || it.id.equals(name, ignoreCase = true)
        }
        val player = resolvePlayer(name)
        return when {
            playerFirst -> player ?: speaker
            else -> speaker ?: player
        }
    }

    /** Names offered by tab completion. [online] is supplied by the platform. */
    fun complete(prefix: String, online: List<Participant.Player>, limit: Int): List<Participant> {
        val needle = prefix.trim().lowercase(Locale.ROOT)
        fun matches(handle: String) = needle.isEmpty() || handle.lowercase(Locale.ROOT).startsWith(needle)
        val systems = participants().filter { matches(it.handle) || matches(it.id) }
        val pool: List<Participant> =
            if (playerFirst) online.filter { matches(it.handle) } + systems
            else systems + online.filter { matches(it.handle) }
        return pool.take(limit.coerceAtLeast(0))
    }

    fun speakerIds(): Set<String> = speakers.map { it.id }.toSet()

    fun speaker(id: String): Participant.System? = participants().firstOrNull { it.id == id }

    /** Returns a copy; the original is never mutated, so a claim can never race a read. */
    fun withAnswerable(ids: Set<String>): Directory =
        Directory(speakers, ids.toSet(), resolvePlayer, playerFirst)

    companion object {
        fun offline(id: UUID, name: String) = Participant.Player(name, id, online = false)
    }
}
