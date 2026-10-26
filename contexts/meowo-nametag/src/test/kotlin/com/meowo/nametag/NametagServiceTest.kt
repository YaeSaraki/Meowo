package com.meowo.nametag

import com.meowo.nametag.application.NametagService
import com.meowo.nametag.domain.NametagState
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The marker is drawn with scoreboard teams, because `Player#customName` has no effect on players.
 * These tests pin that wiring: the team a player lands on, the suffix it carries, and the fact
 * that disabling the context takes the teams off the scoreboard again.
 */
class NametagServiceTest {
    private lateinit var server: ServerMock
    private lateinit var service: NametagService
    private val now = 1_000_000L

    @BeforeEach fun setUp() {
        server = MockBukkit.mock()
        service = NametagService(NametagSettings(afkAfterSeconds = 1, checkIntervalSeconds = 1))
        service.install(board)
    }

    @AfterEach fun tearDown() { MockBukkit.unmock() }

    private fun player(name: String) = server.addPlayer(name)

    /** MockBukkit exposes the main scoreboard through the manager, not directly on the server. */
    private val board get() = server.scoreboardManager.mainScoreboard

    /** Fails the test loudly if a team is missing, instead of silently comparing against null. */
    private fun team(name: String) = requireNotNull(board.getTeam(name)) { "team $name is missing" }

    @Test fun `install registers a team per state with a marker suffix`() {
        for (state in NametagState.entries) {
            val suffix = team("mw_" + state.name.lowercase()).suffix()
            assertTrue(plain(suffix).contains("'"), "the ${state.name.lowercase()} team must carry the mark")
        }
    }

    private fun plain(component: net.kyori.adventure.text.Component) =
        PlainTextComponentSerializer.plainText().serialize(component)

    @Test fun `every team carries a different real colour, not the default`() {
        // Regression: a null colour renders in the client's default, which looked like "the marker
        // never changes colour". Assert the actual value that reaches the client.
        val colours = NametagState.entries.associateWith { state ->
            val colour = team("mw_" + state.name.lowercase()).suffix().color()
            assertNotNull(colour, "the ${state.name.lowercase()} mark must carry a real colour")
            colour!!.value()
        }
        assertEquals(0xC9A063, colours[NametagState.AWAY])
        assertEquals(0xA3D9A0, colours[NametagState.PRESENT])
        assertEquals(0xD64545, colours[NametagState.STUDY], "the study mark must be red")
        assertEquals(colours.size, colours.values.toSet().size, "no two states may share a colour")
    }

    @Test fun `the mark is bold, because vanilla nametags are italic`() {
        val mark = team("mw_away").suffix()
        assertEquals(TextDecoration.State.TRUE, mark.decoration(TextDecoration.BOLD),
            "the mark must be bold to read against the italic nametag")
        assertEquals(TextDecoration.State.FALSE, mark.decoration(TextDecoration.ITALIC))
    }

    @Test fun `apply puts a player on the team matching the state`() {
        val p = player("Idle1")
        service.track(p, now)
        service.apply(p, NametagState.AWAY)
        assertTrue(p.name in team("mw_away").entries)
        assertTrue(p.name !in team("mw_present").entries)

        service.apply(p, NametagState.PRESENT)
        assertTrue(p.name in team("mw_present").entries, "flipping must move the player, not copy it")
        assertTrue(p.name !in team("mw_away").entries)
    }

    @Test fun `a player is never in more than one team at once`() {
        val p = player("Idle2")
        repeat(6) { service.apply(p, NametagState.entries[it % NametagState.entries.size]) }
        val held = NametagState.entries.filter { p.name in team("mw_" + it.name.lowercase()).entries }
        assertEquals(1, held.size, "a player belongs to exactly one team, was on $held")
    }

    @Test fun `a studying player is drawn in red rather than as away`() {
        // The requested behaviour: mid-pomodoro shows as study, and study is red.
        val focused = NametagService(NametagSettings(afkAfterSeconds = 1)) { true }
        focused.install(board)
        val p = player("Focused")
        focused.track(p, now)
        // Idle by the 1s threshold, so the idle rules alone would say "away".
        focused.apply(p, focused.stateOf(p.uniqueId, now + 2_000L, studying = true))
        assertTrue(p.name in team("mw_study").entries, "studying must win over the idle clock")
        assertTrue(p.name !in team("mw_away").entries)
        assertEquals(NametagState.STUDY, focused.appliedState(p.uniqueId), "the applied state must be study")
        assertEquals(0xD64545, team("mw_study").suffix().color()!!.value(), "the study mark must be red")
    }

    @Test fun `ending the focus session puts the mark back to whatever idle says`() {
        val toggled = AtomicBoolean(true)
        val live = NametagService(NametagSettings(afkAfterSeconds = 1)) { toggled.get() }
        live.install(board)
        val p = player("Finished")
        live.track(p, now)
        live.apply(p, live.stateOf(p.uniqueId, now + 2_000L, studying = true))
        assertTrue(p.name in team("mw_study").entries)

        // The session ends while the player is still idle: they fall back to away, not to green.
        toggled.set(false)
        val after = live.stateOf(p.uniqueId, now + 2_000L, studying = false)
        assertEquals(NametagState.AWAY, after)
        live.apply(p, after)
        assertTrue(p.name in team("mw_away").entries)
        assertTrue(p.name !in team("mw_study").entries, "the stale study entry must be removed")
    }

    @Test fun `a focus lookup that throws costs the study mark, not the whole sweep`() {
        // The nametag crosses into another context; a service being disabled underneath us must not
        // take down every other player's marker.
        val broken = NametagService(NametagSettings(afkAfterSeconds = 1)) { error("pomodoro context is gone") }
        broken.install(board)
        val p = player("Broken")
        broken.track(p, now)
        broken.sweep(now + 2_000L)
        assertTrue(p.name in team("mw_away").entries, "it must still fall back to the idle rules")
        assertTrue(p.name !in team("mw_study").entries)
    }

    @Test fun `forget takes a quitting player off every team`() {
        val p = player("Leaver")
        service.track(p, now)
        service.apply(p, NametagState.STUDY)
        service.forget(p, reset = true)
        for (state in NametagState.entries) {
            assertTrue(p.name !in team("mw_" + state.name.lowercase()).entries)
        }
    }

    @Test fun `uninstall removes the teams from the scoreboard entirely`() {
        service.uninstall()
        for (state in NametagState.entries) {
            assertNull(board.getTeam("mw_" + state.name.lowercase()), "a disabled context must not leave its team behind")
        }
    }

    @Test fun `installing twice reuses the same teams instead of duplicating them`() {
        val again = NametagService(NametagSettings())
        again.install(board)
        assertEquals(NametagState.entries.size, board.teams.size, "a reload must not accumulate teams")
    }

    @Test fun `sweep moves a player onto the away team once the idle threshold passes`() {
        val p = player("Sleeper")
        service.track(p, now)
        service.apply(p, service.stateOf(p.uniqueId, now, studying = false))
        service.markApplied(p.uniqueId, NametagState.PRESENT)
        // afkAfterSeconds is 1, so one second later the player counts as away.
        service.sweep(now + 1_001L)
        assertTrue(p.name in team("mw_away").entries, "the sweep must move the marker")
    }

    @Test fun `the sweep picks up a focus session that started after the last one`() {
        // The integration that motivated the third state: the sweep is what notices a pomodoro
        // starting, so it has to consult the live source rather than a value captured at install.
        var focused = false
        val live = NametagService(NametagSettings(afkAfterSeconds = 180)) { focused }
        live.install(board)
        val p = player("StartsLater")
        live.track(p, now)
        live.apply(p, live.stateOf(p.uniqueId, now, studying = false))
        live.markApplied(p.uniqueId, NametagState.PRESENT)
        assertTrue(p.name in team("mw_present").entries)

        focused = true
        live.sweep(now + 1_000L)
        assertTrue(p.name in team("mw_study").entries, "starting a pomodoro must repaint the nametag")

        focused = false
        live.sweep(now + 2_000L)
        assertTrue(p.name in team("mw_present").entries, "ending it must repaint it back")
        assertTrue(p.name !in team("mw_study").entries, "the stale study entry must be removed")
    }

    @Test fun `activity while not yet away resets the idle clock anyway`() {
        // Regression: the context used to bail out before resetting the clock whenever the player
        // was not away yet, so talking/waving/eating counted for nothing. A player who chatted
        // every minute still popped into the away state three minutes later and the title appeared
        // with no visible cause. This uses the real 180s threshold, not the 1s one above.
        val three = NametagService(NametagSettings(afkAfterSeconds = 180, checkIntervalSeconds = 1))
        three.install(board)
        val p = player("Chatter")
        val start = 1_000_000L
        three.track(p, start)

        // Something every 30s for ten minutes: always inside the idle window.
        var now = start
        repeat(20) {
            now += 30_000L
            assertFalse(three.registerActivity(p, now), "still inside the idle window at $now")
        }
        // Ten minutes of continuous activity, and the player was never marked away.
        assertFalse(three.isAfk(p.uniqueId, now), "an actively chatting player must never go away")
        // A genuine three-minute silence afterwards does go away, so the clock really was running.
        assertTrue(three.isAfk(p.uniqueId, now + 180_000L), "a real silence must still count")
    }

    @Test fun `activity reports whether the player had been away`() {
        val p = player("Sleeper2")
        val start = 5_000L
        service.track(p, start)
        assertFalse(service.registerActivity(p, start), "not away yet")
        assertTrue(service.registerActivity(p, start + 5_000L), "past the 1s threshold, so away")
    }

    @Test fun `a disabled service never puts anybody on a team`() {
        val off = NametagService(NametagSettings(enabled = false))
        off.install(board)
        val p = player("Off")
        off.track(p, now)
        off.apply(p, NametagState.AWAY)
        assertTrue(p.name !in team("mw_away").entries)
    }
}
