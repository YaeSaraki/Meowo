package com.meowo.pomodoro.infrastructure.visuals

import com.meowo.pomodoro.PomodoroSettings
import com.meowo.pomodoro.domain.PomodoroSession
import com.meowo.pomodoro.domain.Preset
import com.meowo.pomodoro.domain.SessionState
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.time.Duration
import java.util.UUID

/**
 * What the client actually receives for the on-screen countdown.
 *
 * The flicker came from Adventure's **default fade times**: sending a title every second restarted a
 * fade-in every second. A mocked player records only the title *text*, so the times are read through the
 * [TitleSender] seam instead — which is the only way to assert the thing that was wrong.
 */
class PomodoroVisualsTest {

    private lateinit var server: ServerMock
    private lateinit var player: PlayerMock
    private val sent = mutableListOf<Title>()
    /** The screen wipe, which the player itself cannot show: a mock records titles, not clears. */
    private val cleared = mutableListOf<org.bukkit.entity.Player>()

    private val settings = PomodoroSettings(titleRefreshSeconds = 5)

    private val preset = Preset("standard", "Standard", "CLOCK", false, 60, 60, 120, 2, builtin = true)

    private fun session(state: SessionState = SessionState.WORK, secondsLeft: Int = 42) = PomodoroSession(
        state = state, previousState = null, preset = preset, workSession = 1,
        secondsLeft = secondsLeft, extraSeconds = 0, owner = UUID.randomUUID(), startLocation = null,
    )

    private fun visuals() = PomodoroVisuals(settings, object : TitleSender {
        override fun send(player: org.bukkit.entity.Player, title: Title) { sent += title }
        override fun clear(player: org.bukkit.entity.Player) { cleared += player }
    })

    private fun frame(stay: Long = 5, showTitle: Boolean = true) = VisualFrame(
        bossbarText = "bar", titleText = "Standard", subtitleText = "working 00:42",
        showBossbar = true, showTitle = showTitle, titleStay = Duration.ofSeconds(stay),
    )

    /** [subtitleSeconds] overrides the hard-coded frame text so a re-send can show a new time. */
    private fun frameWith(secondsLeft: Int, stay: Long = 5) = VisualFrame(
        bossbarText = "bar", titleText = "Standard",
        subtitleText = "working %02d:%02d".format(secondsLeft / 60, secondsLeft % 60),
        showBossbar = true, showTitle = true, titleStay = Duration.ofSeconds(stay),
    )

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
        player = server.addPlayer("Timer")
    }

    @AfterEach
    fun teardown() {
        MockBukkit.unmock()
    }

    @Test fun `the title is sent with no fade and a stay equal to the refresh interval`() {
        visuals().render(player, session(), frame(stay = 5))

        val title = sent.single()
        val times = title.times()
        assertNotNull(times, "a title without explicit times gets Adventure's fades, which is the bug")
        assertEquals(Duration.ZERO, times!!.fadeIn(), "no fade in: the readout must not pulse")
        assertEquals(Duration.ZERO, times.fadeOut(), "no fade out")
        assertEquals(Duration.ofSeconds(5), times.stay(), "the stay must outlast the gap between updates")
        assertEquals("Standard", plain(title.title()))
    }

    @Test fun `a shorter configured interval shortens the stay to match`() {
        visuals().render(player, session(), frame(stay = 2))
        assertEquals(Duration.ofSeconds(2), sent.single().times()!!.stay())
    }

    @Test fun `every frame re-sends the title, so the countdown never blinks out`() {
        // Regression for the reported bug: the title used to be throttled to one send per refresh
        // interval, and Adventure clears a title the instant its stay expires. That left a visible
        // gap on every cycle, so the readout flashed a few times a minute instead of sitting there.
        // The fix is to send on every tick, which is only safe because the fades are zero.
        val v = visuals()
        repeat(12) { second -> v.render(player, session(secondsLeft = 60 - second), frame(stay = 5)) }

        assertEquals(12, sent.size, "the title must be re-sent on every tick, not throttled")
        // A fresh stay each time is what keeps the screen covered: it is always re-armed before the
        // previous one can lapse, so the client never sees the title removed.
        assertTrue(sent.all { it.times()!!.stay() == Duration.ofSeconds(5) })
        assertTrue(sent.all { it.times()!!.fadeIn() == Duration.ZERO && it.times()!!.fadeOut() == Duration.ZERO },
            "zero fades are what make a per-second re-send a replacement rather than a pulse")
    }

    @Test fun `the countdown text tracks the clock on each re-send`() {
        val v = visuals()
        v.render(player, session(secondsLeft = 60), frameWith(60))
        v.render(player, session(secondsLeft = 59), frameWith(59))
        assertEquals(listOf("working 01:00", "working 00:59"), sent.map { plain(it.subtitle()) },
            "each send must carry the current time, or the countdown would sit still")
    }

    @Test fun `a disabled title clears the screen instead of leaving the last frame up`() {
        visuals().render(player, session(), frame(showTitle = false))
        assertTrue(sent.isEmpty(), "nothing should be shown when the player turned the title off")
        // The clear is the point of this test, and it used to be unobservable: `clearTitle` went
        // straight to the player, and a mocked player records titles but never clears one — so
        // `nextTitle()` was null whether or not the screen had been wiped. Deleting the clear from
        // the production code left this test green.
        assertEquals(1, cleared.size, "the previous title must be cleared, not left on the screen")
        assertEquals(player.uniqueId, cleared.single().uniqueId)
    }

    private fun plain(component: net.kyori.adventure.text.Component): String =
        PlainTextComponentSerializer.plainText().serialize(component)
}
