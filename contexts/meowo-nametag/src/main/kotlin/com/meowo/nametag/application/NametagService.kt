package com.meowo.nametag.application

import com.meowo.nametag.NametagSettings
import com.meowo.nametag.domain.IdleState
import com.meowo.nametag.domain.MarkerStyle
import com.meowo.nametag.domain.NametagState
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the per-player idle state and the marker the server advertises for it.
 *
 * ## Why a scoreboard team and not `Player#customName`
 *
 * The obvious implementation — `player.customName(...)` plus
 * `player.isCustomNameVisible = true` — **does nothing for players**. The API documents
 * both accessors with the same sentence:
 *
 * > "This value has no effect on players, they will always display their name."
 * > — `Entity#setCustomNameVisible` / `Entity#isCustomNameVisible`
 *
 * A team's prefix and suffix, by contrast, are rendered by the client as part of the
 * nameplate above the head (and in the tab list), and that path works for players with
 * no packet work at all. So the marker is a **team suffix**: one team per state, and a
 * player is moved between them when the state flips.
 *
 * Two consequences worth knowing:
 *
 * - A player can only belong to **one** team, so this context owns the teams it registers.
 *   Their names are prefixed `mw_` to stay out of everyone else's way, and all of them are
 *   unregistered on `close()`.
 * - A team suffix also shows in the tab list and in vanilla chat. That is inherent to the
 *   mechanism; the marker is a single tick, and `nametag.enabled: false` turns the whole
 *   thing off.
 *
 * ## Three states, not two
 *
 * `PRESENT`, `AWAY` and `STUDY` each own a team. [STUDY] exists because a player mid-pomodoro
 * is idle *and* concentrating, and the old boolean could only say one of those — it showed an
 * amber away dot for someone who was in fact heads-down working. The focus session is asked
 * through the injected [studying] predicate, which the context resolves from `FocusAPI` on
 * every sweep; see [NametagState.resolve] for why studying outranks idle.
 *
 * All state is main-thread confined; the sweep runs from the context's own scheduled task.
 */
class NametagService(
    private val settings: NametagSettings,
    private val guard: AfkGuard = AfkGuard(),
    /**
     * Whether the player currently has a live focus session.
     *
     * A function rather than a stored reference so a pomodoro context enabled after this one is
     * picked up on the next sweep instead of being frozen as "never studying" at construction.
     */
    private val studying: (UUID) -> Boolean = { false },
) {

    private val states = ConcurrentHashMap<UUID, IdleState>()
    /** What we last told the server, so we only touch a player when the marker actually changes. */
    private val applied = ConcurrentHashMap<UUID, NametagState>()

    /** Resolved lazily so a plain unit test can build the service without a server. */
    private var scoreboard: Scoreboard? = null
    private val teams = mutableMapOf<NametagState, Team?>()

    /**
     * Registers all three teams. Safe to call again: an existing team is reused and its suffix
     * rewritten, so a reload never leaves two teams behind.
     */
    fun install(board: Scoreboard) {
        scoreboard = board
        NametagState.entries.forEach { state ->
            teams[state] = team(board, teamName(state)).also { it.suffix(dot(MarkerStyle.of(state))) }
        }
    }

    private fun team(board: Scoreboard, name: String): Team =
        board.getTeam(name) ?: board.registerNewTeam(name)

    /**
     * The mark itself: a bold coloured apostrophe after the name.
     *
     * Bold is set explicitly because vanilla renders nametags in italics, and the italic would
     * otherwise fight the colour. The colour is built here, from the domain's **number** — never
     * from `TextColor.fromHexString`, which silently returns null for anything but a `#` prefix
     * and leaves the mark in the client's default colour with no error at all.
     */
    private fun dot(style: MarkerStyle): Component =
        Component.text(style.gap + style.glyph, TextColor.color(style.rgbValue()))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

    /** Seeds a freshly seen player. Existing state is kept so a reload does not reset the clock. */
    fun track(player: Player, nowMillis: Long) {
        states.putIfAbsent(player.uniqueId, IdleState(nowMillis, settings.afkAfterMillis))
    }

    fun state(id: UUID): IdleState? = states[id]

    fun isAfk(id: UUID, nowMillis: Long): Boolean = states[id]?.isAfk(nowMillis) ?: false

    /**
     * Records deliberate activity and reports whether the player had already gone away.
     *
     * The clock is reset **either way**. That is the whole point: talking, waving, eating or
     * switching slot while still inside the idle window must count as being at the keyboard, or a
     * player who chats every minute still pops into the away state three minutes later and the
     * title appears for no visible reason.
     */
    fun registerActivity(player: Player, nowMillis: Long): Boolean {
        val wasAway = isAfk(player.uniqueId, nowMillis)
        moved(player, nowMillis)
        return wasAway
    }

    /** Records a real movement: clears the idle run and any manual `/afk`. */
    fun moved(player: Player, nowMillis: Long) {
        states.computeIfAbsent(player.uniqueId) { IdleState(nowMillis, settings.afkAfterMillis) }
            .let { states[player.uniqueId] = it.moved(nowMillis) }
    }

    /** Flips the manual away flag used by `/afk`. Returns the new value. */
    fun toggleManual(player: Player, nowMillis: Long): Boolean {
        val current = states.computeIfAbsent(player.uniqueId) { IdleState(nowMillis, settings.afkAfterMillis) }
        val next = current.toggledManual(nowMillis)
        states[player.uniqueId] = next
        return next.manualAway
    }

    /** Re-checks every online player and moves the marker only where the state flipped. */
    fun sweep(nowMillis: Long) {
        for (player in Bukkit.getOnlinePlayers()) {
            states.computeIfAbsent(player.uniqueId) { IdleState(nowMillis, settings.afkAfterMillis) }
            val resolved = resolve(player, nowMillis)
            if (applied.put(player.uniqueId, resolved) != resolved) apply(player, resolved) else maintain(player, resolved)
        }
    }

    /**
     * The state to advertise for [player] right now: idle rules and the focus session combined.
     *
     * The focus lookup is wrapped because it crosses into another context. A service that is being
     * disabled underneath us must not turn into a nametag sweep failure for everybody else in the
     * chunk — losing the study mark is a cosmetic loss, taking down the sweep is not.
     */
    private fun resolve(player: Player, nowMillis: Long): NametagState {
        val afk = isAfk(player.uniqueId, nowMillis)
        val focused = runCatching { studying(player.uniqueId) }.getOrDefault(false)
        return NametagState.resolve(afk = afk, studying = focused)
    }

    /**
     * The state [id] should be advertised at [nowMillis], with [studying] supplied by the caller.
     *
     * Public so the join path can resolve a state **before** a `Player` is needed and so a test can
     * pin the decision table without a server. The sweep uses the same rule through [resolve].
     */
    fun stateOf(id: UUID, nowMillis: Long, studying: Boolean): NametagState =
        NametagState.resolve(afk = isAfk(id, nowMillis), studying = studying)

    /**
     * Moves the player onto the team for [state]. Vanilla owns nametag visibility — a team marker
     * is not hidden by sneaking, so there is nothing to re-assert there.
     *
     * The away shield is keyed on **actually being away**, not on the advertised state: a studying
     * player draws a red mark but is still shielded if they have gone idle, because a focus session
     * that drifts far enough away auto-pauses and the player is still standing there.
     */
    fun apply(player: Player, state: NametagState) {
        if (!settings.enabled) { clear(player); return }
        val board = scoreboard
        if (board == null) return
        val target = teams[state]
        val afk = state == NametagState.AWAY || (state == NametagState.STUDY && isAfk(player.uniqueId, System.currentTimeMillis()))
        // Remove from every *other* team first: a player belongs to exactly one, and a stale entry
        // left behind would render the mark twice.
        teams.forEach { (other, team) -> if (other != state) team?.removeEntry(player.name) }
        target?.addEntry(player.name)
        applied[player.uniqueId] = state
        if (afk) { guard.engage(player); guard.clearMobTargets(player) } else guard.release(player)
    }

    /**
     * The old boolean entry point, kept because the idle-only paths (a manual `/afk`, an activity
     * event) do not know anything about focus sessions and should not have to.
     */
    fun apply(player: Player, afk: Boolean) = apply(player, if (afk) NametagState.AWAY else NametagState.PRESENT)

    /** Re-asserts the shield for an AFK player; mobs re-acquire targets, so this runs each sweep. */
    fun maintain(player: Player, state: NametagState) {
        if (state == NametagState.AWAY && guard.isGuarded(player.uniqueId)) guard.clearMobTargets(player)
    }

    /** True while the player is shielded. */
    fun isGuarded(id: UUID): Boolean = guard.isGuarded(id)

    /** True once an away player has been carried beyond the leash; the caller ends the away state. */
    fun hasDrifted(player: Player): Boolean = guard.hasDrifted(player)

    /** Drops the shield from everyone, restoring the prior flags. */
    fun releaseAll() = guard.releaseAll(Bukkit.getOnlinePlayers())

    /** Takes the player off all teams, restoring the vanilla nametag. */
    fun clear(player: Player) {
        teams.values.forEach { it?.removeEntry(player.name) }
        guard.release(player)
        applied.remove(player.uniqueId)
    }

    /** Drops a player on quit or on context close; `reset` also un-does the server-side name. */
    fun forget(player: Player, reset: Boolean) {
        if (reset) clear(player)
        states.remove(player.uniqueId)
        applied.remove(player.uniqueId)
    }

    fun forgetAll() {
        states.clear()
        applied.clear()
    }

    /** Unregisters the teams so a disabled context leaves no team behind on the main scoreboard. */
    fun uninstall() {
        releaseAll()
        teams.values.forEach { it?.unregister() }
        teams.clear()
        scoreboard = null
    }

    /** Records what the server was last told, so the first sweep does not re-apply. */
    fun markApplied(id: UUID, state: NametagState) { applied[id] = state }

    /** Records an idle-only state, for callers that have no focus information to offer. */
    fun markApplied(id: UUID, afk: Boolean) = markApplied(id, if (afk) NametagState.AWAY else NametagState.PRESENT)

    /** The state this service last advertised, or null if it never applied one. Exposed for tests. */
    fun appliedState(id: UUID): NametagState? = applied[id]

    /** The team backing [state], for assertions about the wiring. Null before [install]. */
    fun teamOf(state: NametagState): Team? = teams[state]

    private companion object {
        /** One team per state; the `mw_` prefix keeps them out of everyone else's way. */
        fun teamName(state: NametagState) = "mw_" + state.name.lowercase()
    }
}
