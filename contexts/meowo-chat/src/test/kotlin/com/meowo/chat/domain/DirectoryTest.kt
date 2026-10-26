package com.meowo.chat.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Name resolution. The point of the default ordering is that a player who renames themselves
 * `EconCat` cannot capture the system speaker.
 */
class DirectoryTest {

    private val econId = UUID.randomUUID()
    private val steveId = UUID.randomUUID()

    private fun directory(playerFirst: Boolean = false, answerable: Set<String> = emptySet()) = Directory(
        speakers = listOf(
            Directory.SpeakerInfo("economy", "EconCat", "balance and transfers"),
            Directory.SpeakerInfo("teleport", "TeleCat", "teleport requests"),
        ),
        answerable = answerable,
        resolvePlayer = { name ->
            when (name) {
                "EconCat" -> Participant.Player("EconCat", econId, online = true)
                "Steve" -> Participant.Player("Steve", steveId, online = true)
                else -> null
            }
        },
        playerFirst = playerFirst,
    )

    @Test fun `a speaker is found by display name or by raw id`() {
        val d = directory()
        assertEquals("economy", (d.find("EconCat") as Participant.System).id)
        assertEquals("economy", (d.find("economy") as Participant.System).id)
        assertEquals("economy", (d.find("ECONCAT") as Participant.System).id, "matching must ignore case")
    }

    @Test fun `a speaker wins over an identically named player by default`() {
        val found = directory(playerFirst = false).find("EconCat")
        assertTrue(found is Participant.System, "a player must not be able to capture a system identity")
    }

    @Test fun `opt-in flips the precedence`() {
        val found = directory(playerFirst = true).find("EconCat")
        assertTrue(found is Participant.Player)
    }

    @Test fun `a player with no name clash still resolves`() {
        val found = directory().find("Steve")
        assertTrue(found is Participant.Player)
        assertEquals(steveId, (found as Participant.Player).uuid)
    }

    @Test fun `an unknown name resolves to nothing rather than a fabricated player`() {
        assertNull(directory().find("Nobody"))
        assertNull(directory().find("  "))
    }

    @Test fun `answerability is reflected on the speaker and survives a copy`() {
        val d = directory(answerable = setOf("economy"))
        assertTrue((d.find("EconCat") as Participant.System).answerable)
        assertFalse((d.find("TeleCat") as Participant.System).answerable)
        assertTrue(d.withAnswerable(setOf("teleport")).find("TeleCat")!!.let { (it as Participant.System).answerable })
        assertTrue((d.find("EconCat") as Participant.System).answerable, "the original must not be mutated")
    }

    @Test fun `completion offers speakers first by default and filters by prefix`() {
        val online = listOf(Participant.Player("Steve", steveId, true))
        val names = directory().complete("E", online, 32).map { it.handle }
        assertEquals(listOf("EconCat"), names, "an ambiguous prefix must still list the system speaker")
        assertTrue(directory().complete("", online, 32).size >= 3)
    }

    @Test fun `speaker ids are exposed for the API`() {
        assertEquals(setOf("economy", "teleport"), directory().speakerIds())
    }

    @Test fun `a speaker missing from the table is reported as unknown`() {
        assertNull(directory().speaker("nothing"))
        assertNotNull(directory().speaker("economy"))
    }
}
