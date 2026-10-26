package com.meowo.pomodoro.infrastructure.visuals

import com.meowo.pomodoro.PomodoroSettings
import com.meowo.pomodoro.domain.PomodoroSession
import com.meowo.pomodoro.domain.SessionState
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import java.util.UUID

/**
 * What a player should see right now, already localised.
 *
 * The strings are composed by the context (which owns the language bundle) and handed here, so the
 * renderer stays free of message keys and a second language cannot appear in the middle of one
 * readout. [showBossbar] and [showTitle] are the player's own preferences.
 */
data class VisualFrame(
    val bossbarText: String,
    val titleText: String,
    val subtitleText: String,
    val showBossbar: Boolean,
    val showTitle: Boolean,
    /**
     * How long the title stays on screen.
     *
     * Passed in rather than defaulted, because the countdown readout is **replaced**, not faded: with
     * Adventure's default fade times a title that is re-sent every second restarts its fade-in every
     * second, which is exactly the blink this field exists to remove.
     */
    val titleStay: java.time.Duration,
)

/**
 * Renders the BossBar and Title. Both are per-player and entirely client-side, so they need no
 * dependency beyond Bukkit — the optional floating `TextDisplay` lives in [TextDisplayRenderer].
 *
 * The BossBar is created once per player and reused; [clear] removes it, which matters because a
 * leaked BossBar stays over the player's screen for the rest of their session.
 */
/**
 * Where the on-screen title is delivered.
 *
 * A seam, because the fade times are the whole point of the title fix and they are otherwise
 * unobservable: Adventure's `showTitle` reaches the client through a packet, and a mocked player only
 * records the text. Injecting the delivery lets a test read exactly what a client would receive —
 * including the stay time that stops the countdown from pulsing.
 */
fun interface TitleSender {
    fun send(player: Player, title: Title)

    /**
     * Clears the title, if there is one.
     *
     * A body, not an abstract method, so the interface stays a SAM and `TitleSender { … }` still
     * compiles. It is here because clearing is otherwise unobservable: a mocked player records
     * titles but has no `clearTitle`, so a test could only ever see "no title was sent" — which is
     * true whether or not the screen was actually wiped.
     */
    fun clear(player: Player) {
        player.clearTitle()
    }

    companion object {
        /** Production: straight to the player's client. */
        val PLAYER = TitleSender { player, title -> player.showTitle(title) }

        /** The times every Meowo title uses: no fade, replaced when it expires or when the readout changes. */
        fun times(stay: java.time.Duration): Title.Times = Title.Times.times(java.time.Duration.ZERO, stay, java.time.Duration.ZERO)
    }
}

class PomodoroVisuals(
    private val settings: PomodoroSettings,
    private val titles: TitleSender = TitleSender.PLAYER,
) {

    private val bossBars = mutableMapOf<UUID, BossBar>()

    fun render(player: Player, session: PomodoroSession, frame: VisualFrame) {
        renderBossBar(player, session, frame)
        renderTitle(player, frame)
    }

    private fun renderBossBar(player: Player, session: PomodoroSession, frame: VisualFrame) {
        if (!frame.showBossbar) {
            bossBars.remove(player.uniqueId)?.let { bar ->
                bar.removePlayer(player)
            }
            return
        }
        val bar = bossBars[player.uniqueId] ?: Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID).also {
            it.addPlayer(player)
            bossBars[player.uniqueId] = it
        }
        bar.setVisible(session.isActive)
        bar.setTitle(frame.bossbarText)
        bar.setProgress(session.progress())
        bar.setColor(colorFor(session))
    }

    private fun renderTitle(player: Player, frame: VisualFrame) {
        if (!frame.showTitle) {
            titles.clear(player)
            return
        }
        // No fade in or out: the next frame replaces this one instantly, so the readout is steady
        // instead of pulsing. Only the stay time matters, and the caller keeps it in step with how
        // often it re-sends.
        titles.send(
            player,
            Title.title(
                render(frame.titleText, NamedTextColor.WHITE),
                render(frame.subtitleText, NamedTextColor.GRAY),
                TitleSender.times(frame.titleStay),
            ),
        )
    }

    /**
     * A readout line as a Component with its `§` codes interpreted.
     *
     * The same trap as the item tooltips: these strings come from the language bundle, and
     * `Component.text` would print `§d◆` literally.
     */
    private fun render(text: String, fallback: NamedTextColor): Component =
        LegacyComponentSerializer.legacySection().deserialize(text).colorIfAbsent(fallback)

    /**
     * Colour per state: the paused colour overrides the underlying phase, so a paused work session
     * never looks like a running one.
     */
    private fun colorFor(session: PomodoroSession): BarColor {
        if (session.state == SessionState.PAUSED) return parseColor(settings.bossbarColors.pausedColor)
        val named = when (session.state) {
            SessionState.WORK, SessionState.WORK_COMPLETED -> settings.bossbarColors.work
            SessionState.BREAK -> settings.bossbarColors.breakColor
            SessionState.LONG_BREAK -> settings.bossbarColors.longBreakColor
            else -> settings.bossbarColors.work
        }
        return parseColor(named)
    }

    /** An unknown colour name falls back rather than throwing: a typo must not break a session. */
    private fun parseColor(name: String): BarColor =
        runCatching { BarColor.valueOf(name) }.getOrDefault(BarColor.GREEN)

    fun clear(player: Player) {
        bossBars.remove(player.uniqueId)?.let { bar -> bar.removePlayer(player) }
    }

    fun clearAll() {
        bossBars.values.forEach { bar -> bar.players.toList().forEach { bar.removePlayer(it) } }
        bossBars.clear()
    }
}
