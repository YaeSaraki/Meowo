package com.meowo.agents.application

import com.meowo.agents.domain.ConversationKey
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The rule the two cooldown clocks share.
 *
 * A stamp may only be given back by the call that took it. It sounds like a detail and it is the
 * difference between "a refused question costs the player nothing" and "a refused question costs
 * the player the rate limit that a *later* question had already started".
 */
class CooldownsTest {

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val key = ConversationKey(alice, "economy")

    @Test fun `a stamp starts a cooldown`() {
        val c = Cooldowns()
        c.stamp(key, 1_000L)
        assertTrue(c.cooling(key, 1_000L, 10_000L))
        assertFalse(c.cooling(key, 11_001L, 10_000L), "the cooldown expires")
    }

    /**
     * The rule itself: giving a stamp back must not take somebody else's with it.
     */
    @Test fun `a released stamp is not another call's stamp`() {
        val c = Cooldowns()
        c.stamp(key, 1_000L)
        c.stamp(key, 2_000L)
        assertFalse(c.release(key, 1_000L), "the first stamp is not there any more")
        assertTrue(c.contains(key), "the later stamp must survive")
    }

    /** Giving back your own stamp works, and only then. */
    @Test fun `a stamp can be given back by the call that took it`() {
        val c = Cooldowns()
        c.stamp(key, 1_000L)
        assertTrue(c.release(key, 1_000L))
        assertFalse(c.contains(key))
    }

    /** One player's cooldown is nobody else's business. */
    @Test fun `releasing a player leaves the others alone`() {
        val c = Cooldowns()
        val other = ConversationKey(bob, "economy")
        c.stamp(key, 1_000L)
        c.stamp(other, 1_000L)
        c.releasePlayer(alice)
        assertFalse(c.contains(key))
        assertTrue(c.contains(other))
    }
}
