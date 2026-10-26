package com.meowo.platform

import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import org.bukkit.Location
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The journal of places players have been before something moved them.
 *
 * The rule worth pinning is the **conditional** forget: a context whose move never happened has to
 * take its own record back, and has to leave everybody else's record alone.
 */
class TravelJournalTest {

    private lateinit var server: ServerMock
    private lateinit var world: WorldMock
    private val player = UUID.randomUUID()

    @BeforeEach fun setUp() {
        server = MockBukkit.mock()
        // One world for the whole test, and it matters.
        //
        // `addSimpleWorld` builds a **new** world on every call, and MockBukkit's world does not
        // override `equals`, so two locations that share a name are still two different worlds. The
        // journal refuses a record from another world *before* it compares coordinates, so a helper
        // that asked for a world per location would never reach the comparison at all — the test
        // would have been green for the wrong reason, and dropping the coordinate check from
        // `forgetDeparture` would not have made it red.
        world = server.addSimpleWorld("world")
    }

    @AfterEach fun tearDown() { MockBukkit.unmock() }

    private fun at(x: Double, z: Double) = Location(world, x, 64.0, z)

    @Test fun `a departure is remembered`() {
        val journal = TravelJournal()
        journal.departed(player, at(1.0, 2.0))
        assertTrue(journal.has(player))
        assertEquals(1.0, journal.destinationFor(player)!!.x, 0.001)
    }

    /** The record a failed move left behind is taken back — the player never went anywhere. */
    @Test fun `a departure can be taken back`() {
        val journal = TravelJournal()
        val origin = at(1.0, 2.0)
        journal.departed(player, origin)
        assertTrue(journal.forgetDeparture(player, origin))
        assertFalse(journal.has(player), "the record of a move that never happened must be gone")
    }

    /**
     * Somebody else's record must survive.
     *
     * Between one context recording a departure and its flight failing, another context may have
     * recorded a departure of its own. Clearing that one would take away a `/back` that has
     * nothing to do with the flight that failed.
     */
    @Test fun `only the record that was written is taken back`() {
        val journal = TravelJournal()
        journal.departed(player, at(1.0, 2.0))
        assertFalse(journal.forgetDeparture(player, at(9.0, 9.0)), "a different place is not our record")
        assertTrue(journal.has(player), "the record must survive")
    }

    /** The other half of the guard: a record from another world is not ours either. */
    @Test fun `a record in another world is not taken back`() {
        val journal = TravelJournal()
        journal.departed(player, at(1.0, 2.0))
        val elsewhere = Location(server.addSimpleWorld("elsewhere"), 1.0, 64.0, 2.0)
        assertFalse(journal.forgetDeparture(player, elsewhere), "another world is not our record")
        assertTrue(journal.has(player), "the record must survive")
    }

    /** A death supersedes a departure, and the other way round. */
    @Test fun `a death replaces a departure`() {
        val journal = TravelJournal()
        journal.departed(player, at(1.0, 2.0))
        journal.died(player, at(5.0, 6.0))
        assertEquals(5.0, journal.destinationFor(player)!!.x, 0.001)
        journal.departed(player, at(7.0, 8.0))
        assertEquals(7.0, journal.destinationFor(player)!!.x, 0.001)
    }

    /** Nothing recorded means nothing to go back to. */
    @Test fun `a player nobody moved has nowhere to go back to`() {
        val journal = TravelJournal()
        assertFalse(journal.has(player))
        assertNull(journal.destinationFor(player))
    }
}
