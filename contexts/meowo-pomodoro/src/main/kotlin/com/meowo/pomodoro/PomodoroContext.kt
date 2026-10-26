package com.meowo.pomodoro

import com.meowo.platform.PlatformHost
import com.meowo.pomodoro.application.PomodoroEvent
import com.meowo.pomodoro.application.PresetService
import com.meowo.pomodoro.application.SessionService
import com.meowo.pomodoro.domain.LocationSnapshot
import com.meowo.pomodoro.domain.PomodoroSession
import com.meowo.pomodoro.domain.Preset
import com.meowo.pomodoro.domain.SessionState
import com.meowo.pomodoro.infrastructure.persistence.SqliteFocusLogRepository
import com.meowo.pomodoro.infrastructure.persistence.SqlitePresetRepository
import com.meowo.pomodoro.infrastructure.visuals.PomodoroVisuals
import com.meowo.pomodoro.infrastructure.visuals.TextDisplayRenderer
import com.meowo.pomodoro.infrastructure.visuals.VisualFrame
import com.meowo.pomodoro.interfaces.PomodoroCommand
import com.meowo.pomodoro.interfaces.PomodoroCommandContext
import com.meowo.api.FocusAPI
import com.meowo.api.FocusState
import com.meowo.api.FocusStatus
import com.meowo.api.UIAPI
import com.meowo.api.UIBindings
import com.meowo.pomodoro.interfaces.PomodoroCells
import com.meowo.shared.lifecycle.Context
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.scheduler.BukkitTask
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * Composes the Pomodoro context: storage, the session state machine, the three readouts, the GUI
 * and the per-second ticker.
 *
 * ### What this context deliberately does not do
 *
 * The upstream plugin this is modelled on can freeze a player in place and drag them back when they
 * wander. Meowo does not withhold control from a player, so the distance rule here only **warns and
 * auto-pauses** — `pomodoro.max-distance` is a guard rail, not a leash. The player is never moved.
 *
 * ### Threading
 *
 * The ticker runs on the main thread and touches Bukkit objects only there. Storage runs on its own
 * worker inside the repositories and is reached exclusively through `CompletableFuture`, so a slow
 * disk never stalls a tick.
 *
 * ### Lifecycle
 *
 * Every resource this context acquires is registered in [resources] and released in reverse on
 * [close]. The repositories are created in [start], not in the constructor: a context whose
 * construction fails part-way would otherwise leak the connection it had already opened, and the
 * composition root builds contexts outside its own rollback.
 */
class PomodoroContext(
    private val host: PlatformHost,
    private val settings: PomodoroSettings,
    /**
     * The UI service, resolved when a screen is opened rather than when the context is built.
     *
     * A constructor parameter would fix whatever was registered at that instant, and the composition root is
     * allowed to enable contexts in any order; a function cannot go stale.
     */
    private val ui: () -> UIAPI? = { Bukkit.getServicesManager().load(UIAPI::class.java) },
) : Context, Listener, PomodoroCommandContext, FocusAPI {

    override val id: String = "pomodoro"

    private val active = AtomicBoolean(false)
    private val resources = mutableListOf<AutoCloseable>()

    private var presetService: PresetService? = null
    private var focusLog: SqliteFocusLogRepository? = null

    private val sessionService = SessionService()
    private val visuals = PomodoroVisuals(settings)

    /** The cells whose content follows the session; the rest of every screen is `ui.yml`. */
    private val cells = PomodoroCells(host.messages)
    private val textDisplay = TextDisplayRenderer(host.plugin, settings.textDisplayOffsetY)

    /** Where each WORK round started, for the drift warning. Main-thread only. */
    private val startLocations = mutableMapOf<UUID, LocationSnapshot>()

    /** Per-player readout preferences, seeded from `pomodoro.bossbar-default` / `title-default`. */
    private val bossbarPrefs = mutableMapOf<UUID, Boolean>()
    private val titlePrefs = mutableMapOf<UUID, Boolean>()

    /**
     * The preset a player is editing, keyed by editor session. Held as a **working copy** so an
     * abandoned edit changes nothing: the row is only written on save, and cancel simply drops the
     * copy.
     */
    private val drafts = mutableMapOf<UUID, Draft>()

    /** Players who are expected to type a preset name into chat next, with the deadline. */
    private val namePrompts = mutableMapOf<UUID, UUID>()

    /**
     * The preset list most recently shown to each player.
     *
     * Kept because two things need it without a database round trip: a copy is named so it does not
     * collide with what the player already has, and the delete path has to know whether the row is
     * built-in. Both are reads of the list the player is looking at, so holding it is not a cache with a
     * staleness problem — it is the screen.
     */
    private val galleryPages = mutableMapOf<UUID, List<Preset>>()

    /** The gallery page the menu framework last reported, so a reopen resumes where the player was. */
    private val galleryPage = mutableMapOf<UUID, Int>()

    /**
     * Which pomodoro screen each player is looking at, so a change that happens behind it can be
     * painted into it.
     *
     * Without this the colonnade was a snapshot: flipping a readout, or the session moving from work
     * to break, changed the chat and the BossBar but left the menu showing the state it was opened
     * with. A player then had to close and reopen it to see whether their click had worked.
     */
    private val openScreens = mutableMapOf<UUID, Screen>()

    private enum class Screen { MAIN, GALLERY, EDITOR, CONFIRM }

    private var ticker: BukkitTask? = null

    override fun start() {
        val dbPath = host.plugin.dataFolder.toPath().resolve("pomodoro/pomodoro.db")
        val presets = SqlitePresetRepository(dbPath)
        // If the second store fails to open, the first must not be left holding a connection.
        val focus = try {
            SqliteFocusLogRepository(dbPath)
        } catch (error: Exception) {
            presets.close()
            throw error
        }
        val service = PresetService(presets) { settings }
        presetService = service
        focusLog = focus

        resources += AutoCloseable { service.close() }
        resources += AutoCloseable { focus.close() }
        resources += AutoCloseable { visuals.clearAll() }
        resources += AutoCloseable { textDisplay.close() }
        resources += host.commands.register("pomodoro", "pomodoro", PomodoroCommand(host, this))
        // `/pomo` is a second route so `/meowo pomo` works alongside the root alias declared in
        // `plugin.yml`; both reach the same command object.
        resources += host.commands.register("pomo", "pomodoro", PomodoroCommand(host, this))

        Bukkit.getPluginManager().registerEvents(this, host.plugin)
        resources += AutoCloseable { HandlerList.unregisterAll(this) }

        // Published so the nametag can mark a player who is mid-session as studying. Registered after
        // the events because a consumer resolving the service must never find a context whose
        // sessions are not yet reachable.
        Bukkit.getServicesManager().register(FocusAPI::class.java, this, host.plugin, org.bukkit.plugin.ServicePriority.Normal)
        resources += AutoCloseable { Bukkit.getServicesManager().unregister(FocusAPI::class.java, this) }

        active.set(true)
        ticker = Bukkit.getScheduler().runTaskTimer(host.plugin, Runnable { tick() }, TICK_PERIOD, TICK_PERIOD)
        resources += AutoCloseable { ticker?.cancel() }
        if (!textDisplay.available) {
            host.plugin.logger.info(
                "Pomodoro floating timer needs PacketEvents 2.14.0; BossBar and Title remain available.",
            )
        }
    }

    override fun close() {
        active.set(false)
        resources.asReversed().forEach { resource ->
            try {
                resource.close()
            } catch (error: Exception) {
                host.plugin.logger.log(Level.WARNING, "Pomodoro resource cleanup failed", error)
            }
        }
        resources.clear()
        // Sessions belong to a live context: dropping them here stops a re-enable from resuming a
        // readout for a session nobody can pause any more.
        sessionService.clear()
        startLocations.clear()
        bossbarPrefs.clear()
        titlePrefs.clear()
        drafts.clear()
        namePrompts.clear()
        galleryPages.clear()
        galleryPage.clear()
        openScreens.clear()
        presetService = null
        focusLog = null
        ticker = null
    }

    // ── ticking ──────────────────────────────────────────────────────────────────────────────

    private fun tick() {
        if (!active.get()) return
        for (result in sessionService.tick()) {
            val player = Bukkit.getPlayer(result.owner) ?: continue
            if (!player.isOnline) continue
            result.events.forEach { onEvent(player, it) }
            // The last few seconds of any counting phase get a tick-tock, matching the upstream
            // plugin's "wrap up" cue. One beep per second, and only while the clock is running.
            if (result.session.state.isCounting && result.session.secondsLeft in 1..WARN_AT_SECONDS) {
                play(player) { it.timerEndWarning }
            }
            // A tick that crossed a phase boundary changes what the menu should say, so the colonnade
            // is repainted here as well as on a command.
            if (result.crossedBoundary) repaint(player)
            refresh(player, result.session)
            warnIfDrifting(player, result.session, player.location)
        }
    }

    /** Fans one state-machine event out into chat, sound and cleanup. */
    private fun onEvent(player: Player, event: PomodoroEvent) {
        when (event) {
            is PomodoroEvent.Started -> {
                play(player) { it.newRoundStart }
                host.messages.send(player, "pomodoro-started", event.session.preset.name)
            }
            is PomodoroEvent.Paused -> {
                host.messages.send(player, "pomodoro-paused")
            }
            is PomodoroEvent.FocusEarned -> recordFocus(event)
            is PomodoroEvent.StateChanged -> onStateChanged(player, event)
        }
    }

    private fun onStateChanged(player: Player, event: PomodoroEvent.StateChanged) {
        when (event.session.state) {
            SessionState.WORK_COMPLETED -> {
                play(player) { it.nextCycle }
                host.messages.send(player, "pomodoro-cycle-complete")
            }
            SessionState.BREAK -> {
                play(player) { it.nextCycle }
                host.messages.send(player, "pomodoro-break-start")
            }
            SessionState.LONG_BREAK -> {
                play(player) { it.completeAllRounds }
                host.messages.send(player, "pomodoro-long-break")
            }
            SessionState.WORK -> {
                // Resuming out of a pause is not a new round, so it gets no fanfare.
                if (!event.isResuming) {
                    play(player) { it.newRoundStart }
                    host.messages.send(player, "pomodoro-work-start")
                }
            }
            SessionState.STOPPED -> {
                cleanup(player)
                host.messages.send(player, "pomodoro-stopped")
            }
            SessionState.PAUSED -> Unit // reported through PomodoroEvent.Paused
        }
    }

    /**
     * Writes one completed focus round. The event carries the outgoing `WORK_COMPLETED` snapshot, so
     * the credit names the round that actually earned it and is written exactly once — this used to
     * be derived from the tick loop, where it fired every second and credited the wrong round.
     */
    private fun recordFocus(event: PomodoroEvent.FocusEarned) {
        val store = focusLog ?: return
        val session = event.session
        store.logWork(
            event.owner,
            session.preset.id,
            session.preset.name,
            session.workSession,
            event.durationSeconds,
        ).whenComplete { _, error ->
            if (error != null) host.plugin.logger.log(Level.WARNING, "Pomodoro focus log write failed", error)
        }
    }

    // ── readouts ─────────────────────────────────────────────────────────────────────────────

    private fun refresh(player: Player, session: PomodoroSession) {
        if (session.state == SessionState.STOPPED) {
            cleanup(player)
            return
        }
        val status = host.messages.text(player, session.state.messageKey)
        val time = session.timeLabel()
        // The title is re-sent on **every** tick, so the countdown on screen always matches the clock
        // and the readout never blinks out between updates. It used to be throttled to one send per
        // `title-refresh-seconds` with a matching stay time, and that is what produced the visible
        // gap: Adventure clears a title the instant its stay expires, so the readout disappeared for
        // a frame or two on every cycle and appeared to flash several times a minute.
        //
        // Re-sending per second is safe precisely because the fades are zero — see `TitleSender.times`.
        // With a non-zero fade this would restart that fade every second and pulse instead, which is
        // the bug the fades were introduced for.
        val frame = VisualFrame(
            bossbarText = host.messages.text(
                player, "pomodoro-bossbar",
                session.preset.name, status, time, session.workSession, session.preset.sessions,
            ),
            titleText = host.messages.text(player, "pomodoro-title", session.preset.name),
            subtitleText = host.messages.text(
                player, "pomodoro-subtitle",
                status, time, session.workSession, session.preset.sessions,
            ),
            showBossbar = bossbarOn(player),
            showTitle = titleOn(player),
            titleStay = java.time.Duration.ofSeconds(settings.titleRefreshSeconds.toLong()),
        )
        visuals.render(player, session, frame)
        textDisplay.update(
            player,
            host.messages.text(player, "pomodoro-text-display", session.preset.name, status, time),
        )
    }

    private fun bossbarOn(player: Player): Boolean =
        bossbarPrefs.getOrPut(player.uniqueId) { settings.bossbarDefault }

    private fun titleOn(player: Player): Boolean =
        titlePrefs.getOrPut(player.uniqueId) { settings.titleDefault }

    private fun cleanup(player: Player) {
        visuals.clear(player)
        textDisplay.clear(player)
    }

    /** Plays a configured cue. An empty sound name is how an operator silences one event. */
    private fun play(player: Player, key: (PomodoroSettings.PomodoroSounds) -> String) {
        val name = key(settings.sounds)
        if (name.isEmpty()) return
        player.playSound(player.location, name, SOUND_VOLUME, SOUND_PITCH)
    }

    private fun click(player: Player) = play(player) { it.uiClick }

    // ── drift guard ──────────────────────────────────────────────────────────────────────────

    /**
     * Warns and pauses when the player is past `pomodoro.max-distance` from where their WORK round
     * began, then forgets the start point so the warning does not repeat every tick.
     *
     * [at] is the location being evaluated rather than always `player.location`, because a
     * `PlayerMoveEvent`/`PlayerTeleportEvent` carries the **destination** and is fired before the
     * player is there. Reading `player.location` inside those handlers would measure where the player
     * still is and let the first step out of range go unnoticed until the next tick.
     *
     * The player is **never moved**: Meowo does not take movement away from anyone. Pausing is the
     * strongest thing this does, and the player decides whether to come back and resume.
     */
    private fun warnIfDrifting(player: Player, session: PomodoroSession, at: Location) {
        if (session.state != SessionState.WORK) return
        val start = startLocations[player.uniqueId] ?: return
        if (at.world.uid == start.worldId) {
            val dx = at.x - start.x
            val dy = at.y - start.y
            val dz = at.z - start.z
            if (dx * dx + dy * dy + dz * dz <= settings.maxDistance * settings.maxDistance) return
        }
        startLocations.remove(player.uniqueId)
        sessionService.pause(player.uniqueId)
        play(player) { it.leaveWarning }
        host.messages.send(player, "pomodoro-too-far", settings.maxDistance.toInt())
        // Repaint straight away so the readout shows "paused" on the same tick as the warning,
        // instead of showing a running clock for one more second after telling them it stopped.
        sessionService.get(player.uniqueId)?.let { refresh(player, it) }
    }

    @EventHandler(ignoreCancelled = true)
    fun move(event: PlayerMoveEvent) {
        if (!event.hasChangedPosition() || !active.get()) return
        val session = sessionService.get(event.player.uniqueId) ?: return
        warnIfDrifting(event.player, session, event.to)
    }

    @EventHandler(ignoreCancelled = true)
    fun teleport(event: PlayerTeleportEvent) {
        if (!active.get()) return
        val session = sessionService.get(event.player.uniqueId) ?: return
        warnIfDrifting(event.player, session, event.to)
    }

    @EventHandler
    fun quit(event: PlayerQuitEvent) {
        // Only the focus credit matters on the way out: chat and sounds would be delivered to a
        // player who has already left. Stopping from WORK_COMPLETED still settles the round.
        sessionService.stop(event.player.uniqueId)
            .filterIsInstance<PomodoroEvent.FocusEarned>()
            .forEach(::recordFocus)
        cleanup(event.player)
        startLocations.remove(event.player.uniqueId)
        bossbarPrefs.remove(event.player.uniqueId)
        titlePrefs.remove(event.player.uniqueId)
        // An abandoned edit must not stay bound to the id: a returning player would otherwise have
        // their first chat line swallowed as a preset name.
        drafts.remove(event.player.uniqueId)
        namePrompts.remove(event.player.uniqueId)
        galleryPages.remove(event.player.uniqueId)
        galleryPage.remove(event.player.uniqueId)
        openScreens.remove(event.player.uniqueId)
    }

    // ── Gui ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The player's live session, or null when they have none.
     *
     * Public for the same reason `HomeContext.homes(owner)` is: a context's read-only state is
     * legitimately useful to whoever owns the player-facing flow, and an accessor here keeps callers
     * from reaching into the service through a test-only seam.
     */
    fun sessionOf(owner: UUID): PomodoroSession? = sessionService.get(owner)

    // ── FocusAPI ────────────────────────────────────────────────────────────────────────────

    /**
     * The frozen reading another context gets. Returns null once the session is stopped, which is
     * how a consumer learns the player is no longer studying.
     *
     * `WORK_COMPLETED` reports the work length rather than the extra seconds a lingering player has
     * accumulated: it is a waiting state, not a countdown, and a nametag showing a growing number
     * would imply the clock is still running.
     */
    override fun statusOf(owner: UUID): FocusStatus? {
        val session = sessionService.get(owner)?.takeIf { it.state != SessionState.STOPPED } ?: return null
        return FocusStatus(
            state = session.toFocusState(),
            secondsLeft = session.secondsLeft.coerceAtLeast(0),
            totalSeconds = session.phaseTotalSeconds.coerceAtLeast(1),
            presetName = session.preset.name,
            workSession = session.workSession,
            sessions = session.preset.sessions,
        )
    }

    /**
     * A paused session reports the phase it will resume into, not a state of its own.
     *
     * A consumer asking "what should I show" wants the answer a person would give — someone paused
     * halfway through work is still working, not in a separate mode — and [FocusStatus.counting] is
     * what tells a caller the clock is held. A pause with no remembered phase (a case the state
     * machine does not produce) falls back to WORK rather than inventing one.
     */
    private fun PomodoroSession.toFocusState(): FocusState = when (state) {
        SessionState.WORK -> FocusState.WORK
        SessionState.WORK_COMPLETED -> FocusState.WORK_COMPLETED
        SessionState.BREAK -> FocusState.BREAK
        SessionState.LONG_BREAK -> FocusState.LONG_BREAK
        SessionState.PAUSED -> when (previousState) {
            SessionState.BREAK -> FocusState.BREAK
            SessionState.LONG_BREAK -> FocusState.LONG_BREAK
            SessionState.WORK_COMPLETED -> FocusState.WORK_COMPLETED
            else -> FocusState.WORK
        }
        SessionState.STOPPED -> FocusState.WORK
    }

    /**
     * A preset being edited. [original] is the stored value (null for a brand-new preset) and
     * [working] is what the editor is showing. Save writes [working]; cancel drops the draft.
     */
    private data class Draft(val original: Preset?, val working: Preset) {
        val creating: Boolean get() = original == null
    }

    override fun isActive(player: Player): Boolean = active.get()

    /**
     * The colonnade: seven crystal heads, one per function.
     *
     * Unlike the gallery this page needs no preset list, so it opens **synchronously** — a pillar
     * screen that waits on a database read before it appears feels broken in a way a list does not.
     */
    override fun openMainMenu(player: Player) {
        openScreens[player.uniqueId] = Screen.MAIN
        ui()?.open(
            player.uniqueId, SCREEN_MAIN,
            overrides = colonnadeCells(player),
            bindings = UIBindings(
                // The glass says "back" and the bedrock floor says "main menu"; on the root both lead to the
                // plugin's main menu, because there is no parent above it.
                onBack = { openMainMenuOfPlugin(player) },
                onMainMenu = { openMainMenuOfPlugin(player) },
                onClose = { click(player); player.closeInventory() },
            ),
        )
    }

    /**
     * The colonnade's variable cells.
     *
     * The seven functions themselves come from `ui.yml` (icon, label, lore and the command each runs); only
     * what depends on the session or the preferences is supplied here.
     */
    private fun colonnadeCells(player: Player): Map<Int, UIAPI.UIElement> {
        val session = sessionService.get(player.uniqueId)
        return mapOf(
            CELL_STATUS to cells.status(session),
            CELL_START to cells.start(session),
            CELL_BOSSBAR to cells.toggle(bossbarOn(player), "bossbar"),
            CELL_TITLE to cells.toggle(titleOn(player), "title"),
        )
    }

    // ── the verbs a menu cell runs ───────────────────────────────────────────────────────────
    //
    // The screens dispatch commands rather than callbacks, so every button and the matching typed command
    // take the same path. That is what keeps the layout in `ui.yml` honest: a cell can only do what the
    // command vocabulary can express, and an operator can reroute a cell without a Kotlin change.

    override fun toggleVisual(player: Player, which: String?): Boolean {
        return when (which?.lowercase(Locale.ROOT)) {
            "bossbar" -> { click(player); toggle(player, bossbar = true); true }
            "title" -> { click(player); toggle(player, bossbar = false); true }
            else -> false
        }
    }

    override fun presetStart(player: Player, index: Int): Boolean {
        val preset = presetAt(player, index) ?: return false
        click(player)
        startWith(player, preset)
        return true
    }

    override fun presetEdit(player: Player, index: Int): Boolean {
        val preset = presetAt(player, index) ?: return false
        click(player)
        openEditor(player, preset)
        return true
    }

    override fun presetDelete(player: Player, index: Int): Boolean {
        val preset = presetAt(player, index) ?: return false
        // A built-in preset is the same on every server, so deleting it would only be undone by the next
        // restart — the refusal is the same one the gallery's shift-click gives.
        if (preset.builtin) {
            play(player) { it.uiFail }
            host.messages.send(player, "pomodoro-preset-builtin-protected")
            return true
        }
        click(player)
        confirmDelete(player, preset)
        return true
    }

    /**
     * The preset at [index] in the list the player is looking at.
     *
     * The index comes from a menu cell, so it refers to the player's *current* gallery; the cached list is
     * what the cell was drawn from, which is why this reads it rather than querying again — a query could
     * have moved the entry out from under the click.
     */
    private fun presetAt(player: Player, index: Int): Preset? {
        val presets = galleryPages[player.uniqueId] ?: return null
        return presets.getOrNull(index)
    }

    override fun presetCopy(player: Player, index: Int): Boolean {
        val preset = presetAt(player, index) ?: return false
        click(player)
        duplicatePreset(player, preset)
        return true
    }

    override fun editorField(player: Player, field: String, up: Boolean): Boolean {
        val target = when (field.lowercase(Locale.ROOT)) {
            "work" -> Field.WORK
            "break" -> Field.BREAK
            "long-break" -> Field.LONG_BREAK
            "sessions" -> Field.SESSIONS
            "icon" -> null
            else -> return false
        }
        click(player)
        return when (target) {
            // Minutes for the three durations; one step for the count and for the icon.
            Field.SESSIONS -> { adjust(player, target, if (up) 1 else -1); true }
            null -> { adjust(player, null, if (up) 1 else -1, icon = true); true }
            else -> { adjust(player, target, if (up) STEP_SECONDS else -STEP_SECONDS); true }
        }
    }

    override fun editorName(player: Player) = run { click(player); promptForName(player) }

    override fun editorSave(player: Player) = run { click(player); savePreset(player) }

    override fun editorDuplicate(player: Player) = run { click(player); duplicateDraft(player) }

    override fun editorDelete(player: Player) = run { click(player); confirmDeleteDraft(player) }

    override fun editorReset(player: Player) = run { click(player); resetToDefault(player) }

    /** `/pomodoro presets` — straight to the gallery, without passing through the colonnade. */
    override fun openPresets(player: Player) = openGallery(player, 0)

    /** The gallery is a list, so it waits for the database rather than blocking the click. */
    private fun openGallery(player: Player, requested: Int) {
        val service = presetService ?: return
        openScreens[player.uniqueId] = Screen.GALLERY
        galleryPage[player.uniqueId] = requested
        service.list(player.uniqueId).whenComplete { presets, error ->
            host.mainThread(active::get) {
                if (error != null) {
                    host.plugin.logger.log(Level.WARNING, "Pomodoro preset load failed", error)
                    host.messages.send(player, "internal-error")
                    return@mainThread
                }
                galleryPages[player.uniqueId] = presets
                val pages = ((presets.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
                ui()?.openList(
                    player.uniqueId, SCREEN_GALLERY,
                    elements = presets.mapIndexed { index, preset -> cells.preset(player, preset, index) },
                    page = requested,
                    overrides = buildMap {
                        // The count and page number are data, so they are an override rather than config.
                        put(
                            CELL_STATUS,
                            cells.galleryCore(player, presets.size, settings.customPresetLimit, requested + 1, pages),
                        )
                        // On the last page the next cell has nowhere to go, so it says where paging stands.
                        if (requested + 1 >= pages) put(CELL_LAST_PAGE, cells.lastPageTile(player, requested + 1, pages))
                    },
                    bindings = UIBindings(
                        onBack = { openMainMenu(player) },
                        onMainMenu = { openMainMenuOfPlugin(player) },
                        onClose = { click(player); player.closeInventory() },
                    ),
                )
            }
        }
    }

    /** Left-click on the start pillar: resume a held timer, otherwise choose a preset to begin. */
    private fun startOrResume(player: Player) {
        val session = sessionService.get(player.uniqueId)
        when {
            session?.state == SessionState.PAUSED -> resume(player)
            session != null && session.isActive -> {
                play(player) { it.uiFail }
                host.messages.send(player, "pomodoro-already-running")
                openGallery(player, 0)
            }
            else -> openGallery(player, 0)
        }
    }

    /**
     * The bedrock plinth's destination: the plugin's own main menu.
     *
     * Run as the player rather than opened through another context, so the entry is a normal command — it
     * goes through Bukkit's dispatch, its permission check still applies, and this context keeps not knowing
     * that `meowo-menu` exists. A context that reached into `/menu`'s implementation would be the cross-context
     * import the architecture forbids.
     */
    private fun openMainMenuOfPlugin(player: Player) {
        click(player)
        player.performCommand("menu")
    }

    override fun newPreset(player: Player) = run { click(player); createPreset(player) }

    override fun help(player: Player) = sendHelp(player)

    private fun sendHelp(player: Player) {
        host.messages.send(player, "pomodoro-help")
        host.messages.send(player, "pomodoro-help-visuals")
    }

    /**
     * Runs the events of one state change, then repaints, so a command or a menu click updates both
     * chat and screen from a single path.
     *
     * [refusalKey] is what a player is told when the state machine declined — pausing a waiting
     * session, or advancing when there is nothing to advance to.
     */
    private fun dispatch(player: Player, events: List<PomodoroEvent>, refusalKey: String? = null) {
        if (events.isEmpty()) {
            if (refusalKey != null) {
                play(player) { it.uiFail }
                host.messages.send(player, refusalKey)
            }
            return
        }
        events.forEach { onEvent(player, it) }
        sessionService.get(player.uniqueId)?.let { refresh(player, it) }
        repaint(player)
    }

    // ── the preset editor ────────────────────────────────────────────────────────────────────

    /** Starts a brand-new preset from a copy of the first built-in, so every field has a sane start. */
    private fun createPreset(player: Player) {
        drafts[player.uniqueId] = Draft(original = null, working = blankPreset(player))
        renderEditor(player)
    }

    private fun blankPreset(player: Player): Preset {
        val seed = settings.builtinPresets.firstOrNull()
        val name = host.messages.text(player, "pomodoro-editor-default-name")
        return if (seed != null) {
            Preset(UUID.randomUUID().toString(), name, seed.icon, seed.enchanted,
                seed.workSeconds, seed.breakSeconds, seed.longBreakSeconds, seed.sessions, builtin = false)
        } else {
            Preset(UUID.randomUUID().toString(), name, "CLOCK", false,
                25 * 60, 5 * 60, 15 * 60, 4, builtin = false)
        }
    }

    /** Opens the editor on a stored preset, loaded fresh so a stale menu cannot edit an old value. */
    private fun openEditor(player: Player, preset: Preset) {
        val service = presetService ?: return
        service.find(player.uniqueId, preset.id).whenComplete { stored, error ->
            host.mainThread(active::get) {
                if (error != null || stored == null) {
                    host.messages.send(player, "internal-error")
                    return@mainThread
                }
                drafts[player.uniqueId] = Draft(original = stored, working = stored)
                renderEditor(player)
            }
        }
    }

    /** Opens the editor on a copy of [preset] under a new id, ready to be saved as a new row. */
    private fun duplicatePreset(player: Player, preset: Preset) {
        val copy = preset.copy(
            id = UUID.randomUUID().toString(),
            name = copyName(player, preset.name),
            builtin = false,
        )
        drafts[player.uniqueId] = Draft(original = null, working = copy)
        renderEditor(player)
    }

    /**
     * A name for a copy that does not collide.
     *
     * Two presets with the same label are indistinguishable in the gallery, and the only thing telling
     * them apart would be a UUID — so the copy is numbered until it is unique among what the player
     * already has.
     */
    private fun copyName(player: Player, base: String): String {
        val taken = galleryPages[player.uniqueId].orEmpty().map { it.name }.toSet()
        val label = host.messages.text(player, "pomodoro-editor-copy-suffix")
        for (n in 1..MAX_COPY_ATTEMPTS) {
            val candidate = "$base $label$n"
            if (Preset.validName(candidate) && candidate !in taken) return candidate
        }
        // Every numbered candidate was taken or too long; fall back to something unique rather than
        // looping forever or handing back a duplicate label.
        return base.take(Preset.NAME_MAX - 5) + " #" + UUID.randomUUID().toString().take(4)
    }

    private fun renderEditor(player: Player) {
        val draft = drafts[player.uniqueId] ?: return
        openScreens[player.uniqueId] = Screen.EDITOR
        ui()?.open(
            player.uniqueId, SCREEN_EDITOR,
            // A new preset and an edited one are the same screen; the wording is what differs, and that is
            // state rather than config.
            title = if (draft.creating) host.messages.text(player, "pomodoro-editor-new-title", draft.working.name) else null,
            overrides = mapOf(CELL_STATUS to cells.preview(player, draft.working, draft.creating)),
            bindings = UIBindings(
                onBack = { click(player); cancelEdit(player) },
                onMainMenu = { openMainMenuOfPlugin(player) },
                onClose = { click(player); player.closeInventory() },
            ),
        )
    }

    /**
     * Applies one click's worth of change to the working copy and repaints.
     *
     * The [Preset] constructor is the backstop, but the clamps run first: a value that failed validation
     * would throw here, inside a click handler, where the only possible reply is a generic error. Staying
     * inside the bounds means the editor cannot produce one.
     */
    private fun adjust(
        player: Player,
        field: Field?,
        delta: Int,
        icon: Boolean = false,
    ) {
        val draft = drafts[player.uniqueId] ?: return
        val working = draft.working
        val changed = when {
            icon -> working.copy(icon = cycleIcon(working.icon, delta))
            field == Field.WORK -> working.copy(workSeconds = clampSeconds(working.workSeconds + delta))
            field == Field.BREAK -> working.copy(breakSeconds = clampSeconds(working.breakSeconds + delta))
            field == Field.LONG_BREAK -> working.copy(longBreakSeconds = clampSeconds(working.longBreakSeconds + delta))
            field == Field.SESSIONS -> working.copy(sessions = (working.sessions + delta).coerceIn(1, MAX_SESSIONS))
            else -> working
        }
        drafts[player.uniqueId] = draft.copy(working = changed)
        renderEditor(player)
    }

    /**
     * Resets every duration to the first built-in preset, keeping the name and icon.
     *
     * A reset that also renamed the preset would destroy the one field the player is most likely to have
     * typed before they realised the numbers were wrong.
     */
    private fun resetToDefault(player: Player) {
        val draft = drafts[player.uniqueId] ?: return
        val seed = settings.builtinPresets.firstOrNull() ?: return
        drafts[player.uniqueId] = draft.copy(
            working = draft.working.copy(
                workSeconds = seed.workSeconds,
                breakSeconds = seed.breakSeconds,
                longBreakSeconds = seed.longBreakSeconds,
                sessions = seed.sessions,
            ),
        )
        play(player) { it.uiSuccess }
        host.messages.send(player, "pomodoro-editor-reset-done")
        renderEditor(player)
    }

    private fun clampSeconds(seconds: Int) = seconds.coerceIn(Preset.DURATION_MIN, Preset.DURATION_MAX)

    private fun cycleIcon(icon: String, delta: Int): String {
        val index = ICONS.indexOf(icon.uppercase())
        val next = if (index < 0) 0 else ((index + delta) % ICONS.size + ICONS.size) % ICONS.size
        return ICONS[next]
    }

    /** Asks the player to type a name in chat, mirroring how `meowo-home` prompts for a rename. */
    private fun promptForName(player: Player) {
        val draft = drafts[player.uniqueId] ?: return
        player.closeInventory()
        namePrompts[player.uniqueId] = player.uniqueId
        host.messages.send(player, "pomodoro-name-prompt", draft.working.name)
    }

    /**
     * Intercepts the next chat line as a preset name.
     *
     * Cancelled, so a name typed here never reaches public chat — the player is filling in a form, not
     * talking. The same shape `meowo-home` uses for renames.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun nameChat(event: AsyncChatEvent) {
        if (!active.get() || namePrompts.remove(event.player.uniqueId) == null) return
        event.isCancelled = true
        val typed = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()
        host.mainThread(active::get) {
            val draft = drafts[event.player.uniqueId] ?: return@mainThread
            if (typed.equals("cancel", ignoreCase = true)) {
                renderEditor(event.player)
                return@mainThread
            }
            if (!Preset.validName(typed)) {
                play(event.player) { it.uiFail }
                host.messages.send(event.player, "pomodoro-name-invalid")
            } else {
                drafts[event.player.uniqueId] = draft.copy(working = draft.working.copy(name = typed))
            }
            renderEditor(event.player)
        }
    }

    /** Saves a draft that is not yet in the gallery, then opens it in the editor so it can be edited. */
    private fun duplicateDraft(player: Player) {
        val service = presetService ?: return
        val draft = drafts[player.uniqueId] ?: return
        val copy = draft.working.copy(
            id = UUID.randomUUID().toString(),
            name = copyName(player, draft.working.name),
            builtin = false,
        )
        service.save(player.uniqueId, copy).whenComplete { saved, error ->
            host.mainThread(active::get) {
                if (error != null) {
                    reportSaveFailure(player, error)
                    return@mainThread
                }
                drafts[player.uniqueId] = Draft(original = saved, working = saved)
                play(player) { it.uiSuccess }
                host.messages.send(player, "pomodoro-preset-duplicated", saved.name)
                renderEditor(player)
            }
        }
    }

    private fun confirmDeleteDraft(player: Player) {
        val draft = drafts[player.uniqueId] ?: return
        val stored = draft.original ?: return
        confirmDelete(player, stored)
    }

    /** Two pillars, then the row is gone. A destructive action never fires on a single stray click. */
    private fun confirmDelete(player: Player, preset: Preset) {
        openScreens[player.uniqueId] = Screen.CONFIRM
        ui()?.confirm(
            player.uniqueId,
            title = host.messages.text(player, "pomodoro-confirm-delete-title"),
            detail = host.messages.text(player, "pomodoro-confirm-delete-detail", preset.name),
            onYes = { deletePreset(player, preset) },
            // Backing out of a question is an answer, and the rail that leads back is the same refusal.
            onNo = { click(player); openGallery(player, 0) },
        )
    }

    private fun deletePreset(player: Player, preset: Preset) {
        val service = presetService ?: return
        if (preset.builtin) {
            play(player) { it.uiFail }
            host.messages.send(player, "pomodoro-preset-builtin-protected")
            return
        }
        service.delete(player.uniqueId, preset.id).whenComplete { removed, error ->
            host.mainThread(active::get) {
                if (error != null) {
                    host.plugin.logger.log(Level.WARNING, "Pomodoro preset delete failed", error)
                    host.messages.send(player, "internal-error")
                    return@mainThread
                }
                // The draft may have been the preset just deleted; leaving it behind would let a later
                // save resurrect it under the same id, which is not what "delete" means.
                drafts.remove(player.uniqueId)
                if (removed) {
                    play(player) { it.uiSuccess }
                    host.messages.send(player, "pomodoro-preset-deleted", preset.name)
                } else {
                    play(player) { it.uiFail }
                    host.messages.send(player, "pomodoro-preset-gone")
                }
                openGallery(player, 0)
            }
        }
    }

    private fun savePreset(player: Player) {
        val service = presetService ?: return
        val draft = drafts[player.uniqueId] ?: return
        service.save(player.uniqueId, draft.working.asCustom()).whenComplete { saved, error ->
            host.mainThread(active::get) {
                if (error != null) {
                    reportSaveFailure(player, error)
                    return@mainThread
                }
                drafts[player.uniqueId] = Draft(original = saved, working = saved)
                play(player) { it.uiSuccess }
                host.messages.send(player, "pomodoro-preset-saved", saved.name)
                openGallery(player, 0)
            }
        }
    }

    /**
     * A refused write is either the per-player cap or a validation failure. Both are the player's to fix,
     * so both get a sentence naming the limit rather than a stack trace.
     */
    private fun reportSaveFailure(player: Player, error: Throwable) {
        play(player) { it.uiFail }
        val cause = error.cause ?: error
        val key = if (cause.message?.contains("limit") == true) "pomodoro-preset-limit" else "pomodoro-name-invalid"
        host.messages.send(player, key, settings.customPresetLimit)
    }

    /** The editable fields, in the vocabulary the `editor` command speaks. */
    private enum class Field { WORK, BREAK, LONG_BREAK, SESSIONS }

    private fun cancelEdit(player: Player) {
        val draft = drafts.remove(player.uniqueId) ?: run { openGallery(player, 0); return }
        if (draft.creating) host.messages.send(player, "pomodoro-preset-discarded")
        openGallery(player, 0)
    }

    // ── commands ─────────────────────────────────────────────────────────────────────────────

    override fun start(player: Player, presetName: String?) {
        if (presetName == null) {
            openMainMenu(player)
            return
        }
        val service = presetService ?: return
        service.list(player.uniqueId).whenComplete { presets, error ->
            host.mainThread(active::get) {
                if (error != null) {
                    host.messages.send(player, "internal-error")
                    return@mainThread
                }
                val target = presets.firstOrNull {
                    it.id.equals(presetName, ignoreCase = true) || it.name.equals(presetName, ignoreCase = true)
                }
                if (target == null) {
                    host.messages.send(player, "pomodoro-preset-not-found", presetName)
                    return@mainThread
                }
                startWith(player, target)
            }
        }
    }

    private fun startWith(player: Player, preset: Preset) {
        startLocations[player.uniqueId] = snapshotOf(player)
        textDisplay.ensure(player)
        player.closeInventory()
        play(player) { it.uiSuccess }
        dispatch(player, sessionService.start(player.uniqueId, preset, startLocations[player.uniqueId]))
    }

    override fun pause(player: Player) {
        // Called once: pausing is stateful, so probing first and acting second would report the
        // probe's success and then act on an already-paused session.
        dispatch(player, sessionService.pause(player.uniqueId), "pomodoro-cannot-pause")
    }

    override fun resume(player: Player) = dispatch(player, sessionService.resume(player.uniqueId), "pomodoro-not-paused")

    override fun stop(player: Player) = dispatch(player, sessionService.stop(player.uniqueId), "pomodoro-not-active")

    override fun next(player: Player) {
        dispatch(player, sessionService.next(player.uniqueId), "pomodoro-cannot-next")
    }

    /**
     * `visuals <bossbar|title> <on|off>`. Reports false for an unknown knob so the command can print
     * its usage instead of silently accepting a typo.
     */
    override fun visuals(player: Player, which: String?, on: Boolean): Boolean {
        when (which) {
            "bossbar" -> {
                bossbarPrefs[player.uniqueId] = on
                host.messages.send(player, if (on) "pomodoro-bossbar-on" else "pomodoro-bossbar-off")
            }
            "title" -> {
                titlePrefs[player.uniqueId] = on
                host.messages.send(player, if (on) "pomodoro-title-on" else "pomodoro-title-off")
            }
            else -> return false
        }
        sessionService.get(player.uniqueId)?.let { refresh(player, it) }
        // The crystal that was just clicked must show the state it switched to. Repainting in place
        // rather than reopening keeps the window still.
        repaint(player)
        return true
    }

    /**
     * Repaints whatever pomodoro screen [player] has open, if any.
     *
     * Only the colonnade is rebuilt, because it is the screen that *displays* state: the gallery lists
     * presets, the editor shows a draft, and the confirmation asks a question — none of them changes
     * when a timer ticks. Rebuilding those would also discard in-progress input.
     */
    private fun repaint(player: Player) {
        if (!active.get() || !player.isOnline) return
        when (openScreens[player.uniqueId]) {
            // Only the colonnade: a repaint of a list or the editor would discard input in progress.
            Screen.MAIN -> ui()?.refresh(player.uniqueId, SCREEN_MAIN, colonnadeCells(player))
            null -> Unit
            else -> Unit
        }
    }

    private fun toggle(player: Player, bossbar: Boolean) {
        val next = if (bossbar) !bossbarOn(player) else !titleOn(player)
        visuals(player, if (bossbar) "bossbar" else "title", next)
    }

    override fun stats(player: Player) {
        val store = focusLog ?: return
        store.totalSeconds(player.uniqueId).whenComplete { total, error ->
            host.mainThread(active::get) {
                if (error != null) {
                    host.plugin.logger.log(Level.WARNING, "Pomodoro stats query failed", error)
                    host.messages.send(player, "internal-error")
                } else {
                    host.messages.send(player, "pomodoro-stats", total / 60, total % 60)
                }
            }
        }
    }

    override fun completePresetName(player: Player, partial: String): List<String> {
        val service = presetService ?: return emptyList()
        // getNow returns the fallback when the load is still in flight and throws when it failed;
        // neither may take a tab-completion down.
        val presets = runCatching { service.list(player.uniqueId).getNow(emptyList()) }.getOrDefault(emptyList())
        return presets.map { it.id }.filter { it.startsWith(partial, ignoreCase = true) }
    }

    private fun snapshotOf(player: Player): LocationSnapshot {
        val location = player.location
        return LocationSnapshot(
            worldId = player.world.uid,
            x = location.x,
            y = location.y,
            z = location.z,
            yaw = location.yaw,
            pitch = location.pitch,
        )
    }

    private companion object {
        /** The screen ids in `ui.yml`; a typo here fails at open time with the list of known ids. */
        const val SCREEN_MAIN = "pomodoro"
        const val SCREEN_GALLERY = "pomodoro-gallery"
        const val SCREEN_EDITOR = "pomodoro-editor"

        /** The four cells whose content follows the session or the preferences. */
        const val CELL_STATUS = 4
        const val CELL_START = 10
        const val CELL_BOSSBAR = 37
        const val CELL_TITLE = 38

        /** How many presets the gallery shows at once; must match `content-slots` in `ui.yml`. */
        const val PAGE_SIZE = 28

        /** The gallery's bottom-right cell, which shows the page position when there is no next page. */
        const val CELL_LAST_PAGE = 52

        /** Every 20 ticks, i.e. once a second — the granularity of the countdown itself. */
        const val TICK_PERIOD = 20L

        /** One step of a duration field: the editor adjusts minutes, not seconds. */
        const val STEP_SECONDS = 60

        /** How many seconds before a phase ends the wrap-up cue starts beeping. */
        const val WARN_AT_SECONDS = 5
        const val SOUND_VOLUME = 1.0f
        const val SOUND_PITCH = 1.0f

        /** Upper bound on rounds per cycle, matching the domain's own limit. */
        const val MAX_SESSIONS = 12

        /** How many numbered names to try before falling back to a random suffix. */
        const val MAX_COPY_ATTEMPTS = 99

        /**
         * Icons the editor cycles through. A short fixed list rather than free text: `Material` names
         * are an API surface a player cannot be expected to spell, and an unknown one would silently
         * render as a clock.
         */
        val ICONS = listOf(
            "CLOCK", "BOOK", "WRITABLE_BOOK", "PAPER", "COMPASS", "MAP", "LANTERN", "TORCH",
            "CAMPFIRE", "BREWING_STAND", "ENCHANTING_TABLE", "BEACON", "DIAMOND", "EMERALD",
            "GOLD_INGOT", "EXPERIENCE_BOTTLE", "HONEY_BOTTLE", "CAKE", "COOKIE", "SWEET_BERRIES",
        )
    }
}
