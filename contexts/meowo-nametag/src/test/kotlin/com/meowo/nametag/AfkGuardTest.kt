package com.meowo.nametag

import com.meowo.nametag.application.AfkGuard
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock

/**
 * The away shield: immunity, a position lock, and cleared mob targets.
 *
 * The lock is the important one. Several published AFK plugins carry the note *"Animals/Players can
 * cancel your AFK status by pushing you"* — a push moves the player, movement clears the idle
 * timer, and the away state ends on its own. These tests pin the mechanism that stops that.
 */
class AfkGuardTest {
    private lateinit var server: ServerMock
    private lateinit var guard: AfkGuard

    @BeforeEach fun setUp() {
        server = MockBukkit.mock()
        guard = AfkGuard(mobSweepRadius = 8)
    }

    @AfterEach fun tearDown() { MockBukkit.unmock() }

    private fun player(name: String) = server.addPlayer(name) as PlayerMock

    @Test fun `engaging makes the player invulnerable and non collidable`() {
        val p = player("Away")
        guard.engage(p)
        assertTrue(p.isInvulnerable, "an away player must not be damageable")
        assertFalse(p.isCollidable, "collidable players get pushed, which ends the AFK")
        assertTrue(guard.isGuarded(p.uniqueId))
    }

    @Test fun `releasing restores the flags the player actually had`() {
        val p = player("Back")
        p.isInvulnerable = false
        p.isCollidable = true
        guard.engage(p)
        guard.release(p)
        assertFalse(p.isInvulnerable, "a player who was not invulnerable must not stay invulnerable")
        assertTrue(p.isCollidable, "collidable must come back or the player is a ghost forever")
        assertFalse(guard.isGuarded(p.uniqueId))
    }

    @Test fun `a player who was already invulnerable keeps it`() {
        // Creative-mode OP and the spawn-protection case: restoring `false` would take a protection
        // the plugin never granted away.
        val p = player("Creative")
        p.isInvulnerable = true
        guard.engage(p)
        guard.release(p)
        assertTrue(p.isInvulnerable, "a prior invulnerable state must be restored, not cleared")
    }

    @Test fun `engaging twice does not overwrite the remembered state`() {
        val p = player("Twice")
        p.isCollidable = true
        guard.engage(p)
        guard.engage(p) // a second sweep must not record the already-locked value
        guard.release(p)
        assertTrue(p.isCollidable, "the original value must survive a repeated engage")
    }

    @Test fun `a mob hunting an away player loses its target`() {
        val p = player("Hunted")
        val zombie = p.world.spawn(p.location, org.bukkit.entity.Zombie::class.java)
        zombie.target = p
        guard.clearMobTargets(p)
        assertTrue(zombie.target == null, "the mob must forget the away player")
    }

    @Test fun `a mob hunting somebody else is left alone`() {
        val p = player("Bystander")
        val other = player("Other")
        val zombie = p.world.spawn(p.location, org.bukkit.entity.Zombie::class.java)
        zombie.target = other
        guard.clearMobTargets(p)
        assertTrue(zombie.target === other, "the sweep must not steal other mobs' targets")
    }

    @Test fun `walking freely does not end the away state`() {
        // Movement is deliberately not an exit: two blocks is well inside the leash, and the
        // plugin must leave the player exactly where they walked.
        val p = player("Wanderer")
        val anchor = p.location.clone()
        guard.engage(p)
        p.teleport(anchor.clone().add(2.0, 0.0, 0.0))
        assertFalse(guard.hasDrifted(p), "two blocks is well inside the five-block leash")
        assertEquals(2.0, p.location.distance(anchor), 0.001, "the player must be left where they walked")
    }

    @Test fun `being carried past the leash reports a drift and never moves the player`() {
        // The plugin must never relocate an away player: past the leash it only *reports*, and the
        // caller ends the away state.
        val p = player("Drifter")
        val anchor = p.location.clone()
        guard.engage(p)
        p.teleport(anchor.clone().add(9.0, 0.0, 0.0))
        assertTrue(guard.hasDrifted(p), "nine blocks is past the five-block leash")
        assertEquals(9.0, p.location.distance(anchor), 0.001, "the player must NOT be teleported back")
    }

    @Test fun `staying put is not a drift`() {
        val p = player("Still")
        guard.engage(p)
        assertFalse(guard.hasDrifted(p), "an unmoved away player is not drifting")
    }

    @Test fun `releasing forgets the anchor so a later walk is never a drift`() {
        val p = player("Released")
        guard.engage(p)
        guard.release(p)
        p.teleport(p.location.clone().add(9.0, 0.0, 0.0))
        assertFalse(guard.hasDrifted(p), "a player who left the away state must be free to move")
    }

}
