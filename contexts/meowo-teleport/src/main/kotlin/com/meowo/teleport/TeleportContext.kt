package com.meowo.teleport

import com.meowo.api.*
import com.meowo.platform.*
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Particle
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.scheduler.BukkitTask
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * Only the requester moves. All Bukkit access and request state live on the tick thread.
 *
 * Acceptance flow, in order:
 *  1. the accepter agrees (command or clicking the chat action) — nothing is charged before this;
 *  2. the fee is reserved into escrow, paid by the requester;
 *  3. particles and brief level-I effects start, then the configurable grace period (default 3s) elapses;
 *  4. only then does the actual move happen, followed by commit or refund.
 *
 * If the requester cannot pay, the request goes back into the book and the accepter is offered the
 * chance to sponsor it: accepting again reserves the fee against the accepter instead.
 */
class TeleportContext(
    private val host: PlatformHost,
    private val settings: TeleportSettings,
    private val move: (Player, Player) -> Boolean = { from, to ->
        val destination = to.location.clone()
        // A player can accept a request and then walk out of the room; a synchronous teleport into
        // their now-unloaded chunk would either generate it on the tick or fail, and the request
        // that was accepted a moment ago would appear to do nothing. `teleportAsync` loads it.
        from.teleportAsync(destination).get(TeleportContext.MOVE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    },
    /**
     * The move a `/back` performs.
     *
     * Injected for the same reason [move] is — the branch that decides whether a stranded fare is
     * refunded cannot be reached through a real teleport on purpose — but it stays a
     * [CompletionStage] rather than a `Boolean` so the tick thread is never blocked waiting for a
     * chunk nobody has visited in months.
     */
    private val moveBack: (Player, Location) -> CompletionStage<Boolean> = { player, destination ->
        player.teleportAsync(destination)
    },
) : Context, Listener {
    override val id = "teleport"
    private companion object {
        const val SPEAKER = "teleport"
        /** Bounded: a teleport that has not landed in a moment is a failure, not a hang. */
        const val MOVE_TIMEOUT_MS = 5_000L
        // Ignore tiny client/server position corrections while standing still. This is checked
        // against the fixed countdown origin, so slow movement cannot reset the tolerance.
        const val POSITION_TOLERANCE = 1.0 / 16.0
    }
    private val active = AtomicBoolean(false)
    private val book = RequestBook(settings.requestSeconds)
    /** Reserved requests keyed by ticket. Presence means the fee is currently held in escrow. */
    private val flights = mutableMapOf<UUID, TeleportRequest>()
    /** Pending grace-period tasks keyed by ticket, so a refund can cancel the queued move. */
    private val countdowns = mutableMapOf<UUID, BukkitTask>()
    /** Fixed starting positions, independent of the client's current camera direction. */
    private val origins = mutableMapOf<UUID, Location>()
    private val presentation = TravelPresentation(host, active::get)
    /** Requesters whose reservation failed for lack of funds and now need the accepter to pay. */
    private val awaitingSponsor = mutableSetOf<UUID>()
    private val resources = mutableListOf<AutoCloseable>()
    private lateinit var economy: SettlementAPI

    /**
     * Where every player was before the thing that moved them.
     *
     * Owned here because `/back` lives here, but the *recording* is done by whichever context did
     * the moving — a context may not import another, which is why the journal itself sits in
     * `platform` and every context is handed the same instance.
     */
    private val backFlights = mutableMapOf<UUID, BukkitTask>()
    private val backTickets = mutableMapOf<UUID, UUID>()
    /**
     * Where a player stood when their `/back` fare was reserved.
     *
     * Fixed for the whole countdown on purpose: comparing against the player's own last position
     * would let a slow walk carry the tolerance along with them, and the countdown would never
     * cancel. Same rule, and the same [POSITION_TOLERANCE], as the `/tpa` countdown above.
     */
    private val backOrigins = mutableMapOf<UUID, Location>()
    override fun start() {
        economy = requireNotNull(Bukkit.getServicesManager().load(SettlementAPI::class.java)) { "Teleport requires economy" }
        active.set(true)
        Bukkit.getPluginManager().registerEvents(this, host.plugin)
        resources += AutoCloseable { HandlerList.unregisterAll(this) }
        resources += host.commands.register("tpa", "teleport", object : Route {
            override val permission = "meowo.teleport"
            override fun execute(sender: CommandSender, args: List<String>) {
                if (sender !is Player) { host.messages.send(sender, "player-only"); return }
                when (args.firstOrNull()?.lowercase(java.util.Locale.ROOT)) {
                    "accept", "deny" -> if (args.size <= 2) answer(sender, args.first().equals("accept", true), args.getOrNull(1)) else usage(sender)
                    "cancel" -> if (args.size == 1) cancel(sender) else usage(sender)
                    null -> usage(sender)
                    else -> if (args.size == 1) request(sender, args[0]) else usage(sender)
                }
            }
            override fun complete(sender: CommandSender, args: List<String>): List<String> =
                (if (args.size == 1) listOf("accept", "deny", "cancel") + Bukkit.getOnlinePlayers().map { it.name }
                else if (args.size == 2 && sender is Player && args[0] in listOf("accept", "deny")) book.incoming(sender.uniqueId).mapNotNull { Bukkit.getPlayer(it.from)?.name }
                else emptyList()).filter { it.startsWith(args.lastOrNull().orEmpty(), true) }
        })
        resources += host.commands.register("back", "teleport", object : Route {
            override val permission = "meowo.teleport.back"
            override fun execute(sender: CommandSender, args: List<String>) {
                if (sender !is Player) { host.messages.send(sender, "player-only"); return }
                // `/back` takes nothing. Answering with the `/tpa` usage — which is what this did —
                // told a player who mistyped `/back` to name somebody to teleport to.
                if (args.isNotEmpty()) { host.messages.send(sender, "back-usage"); return }
                goBack(sender)
            }
        })
        val expiry = Bukkit.getScheduler().runTaskTimer(host.plugin, Runnable {
            book.expire().forEach { withdraw(it, "tpa-expired") }
        }, 20L, 20L)
        resources += AutoCloseable { expiry.cancel() }
    }
    // ── /back ────────────────────────────────────────────────────────────────────────────────

    /**
     * Returns the player to where they were before their last death or travel.
     *
     * ## Why it costs anything at all
     *
     * EssentialsX's `/back` is free, and for a plain server that is right: it is a rescue from
     * losing your items. This server charges for travel everywhere else — home travel and `/tpa`
     * both reserve a fee — so a free `/back` would be the cheapest way to move, which would make the
     * other routes pointless. The fee is small (0.1%, 10..233) because `/back` is still mostly a
     * rescue, and a fee that punishes losing your gear teaches players not to play rather than not to
     * use the command.
     *
     * ## The record is overwritten
     *
     * Going back records where you were before going back, so it is reversible. That permits a
     * paid ping-pong between two spots; each hop costs, which is what bounds it. Not overwriting
     * would let one payment buy unlimited returns to the same place.
     *
     * The record is written **after** the fare is reserved, not when the command is typed: a
     * refused trip must leave the return point exactly where it was.
     *
     * ## The countdown can be broken by walking away
     *
     * Same rule as `/tpa`, `/home` and `/spawn`: the player has to stand through the grace period,
     * and a real step cancels the trip and refunds the fare. The origin is fixed when the fare is
     * reserved rather than read from the player each tick, so a slow walk cannot drag the tolerance
     * along and make the countdown impossible to break.
     */
    private fun goBack(player: Player) {
        // Dead or spectating is refused up front, the way `/spawn` and `/home` do it. Without this
        // the fare was reserved and the player was told "charged, back in 3s", only to be refunded
        // three seconds later by the arrival guard — one refusal spread over two confusing lines.
        if (player.isDead || player.gameMode == GameMode.SPECTATOR) { host.messages.send(player, "back-state"); return }
        val destination = host.travelJournal.destinationFor(player.uniqueId)
        if (destination == null) { host.messages.send(player, "back-nowhere"); return }
        val world = destination.world
        if (world == null) { host.messages.send(player, "back-nowhere"); return }
        if (backTickets.containsKey(player.uniqueId)) { host.messages.send(player, "back-busy"); return }

        val ticket = UUID.randomUUID()
        backTickets[player.uniqueId] = ticket
        // Where the player stands now, kept for the record — see the note at the reservation.
        val origin = player.location.clone()

        val reservation = economy.reserveTax(ticket, player.uniqueId, settings.backAssessment())
        reservation.whenComplete { charge, error -> host.mainThread(active::get) {
            // A quit or a second command may have dropped the ticket while the reserve was in
            // flight; settling then would move a player who is no longer waiting.
            if (backTickets[player.uniqueId] != ticket) return@mainThread
            if (error != null) {
                backTickets.remove(player.uniqueId)
                val cause = (if (error is CompletionException) error.cause else error) as? EconomyException
                if (cause?.code == EconomyError.INSUFFICIENT_FUNDS) {
                    // A back fare is only ever unaffordable when the balance sits below the floor,
                    // which makes the floor the exact price. Naming it is the difference between
                    // "you are broke" and "you need ten more", so `{0}` must not be left empty.
                    host.messages.send(player, "back-broke", host.money(settings.backAssessment().minimum))
                } else host.messages.send(player, "back-failed")
                return@mainThread
            }
            // The player may have taken a step while the fare was being reserved; the countdown's
            // own rule, applied now so the answer arrives immediately instead of in three seconds.
            if (changedPosition(origin, player.location)) { cancelBack(player, ticket, "back-moved"); return@mainThread }
            backOrigins[player.uniqueId] = origin
            // Recorded before the move, exactly as a normal teleport would be — but only once the
            // fare is actually reserved. Recording it up front meant a refused `/back` (broke, or a
            // failed reservation) overwrote the return point with the spot the player was already
            // standing on, so the next successful `/back` charged them to stay where they were.
            host.travelJournal.departed(player.uniqueId, origin)
            host.messages.send(player, "back-charged", host.money(charge.fee), settings.countdownSeconds)
            if (settings.particles) runCatching { presentation.applyTravelEffects(player, settings.countdownSeconds * 20) }
            if (settings.countdownSeconds <= 0) { arriveBack(player, ticket, destination); return@mainThread }
            backFlights[player.uniqueId] = Bukkit.getScheduler().runTaskLater(
                host.plugin,
                Runnable { arriveBack(player, ticket, destination) },
                settings.countdownSeconds * 20L,
            )
        } }
    }

    /**
     * The move itself, on the agent's own async teleport so a return point nobody has visited for
     * months still works.
     */
    private fun arriveBack(player: Player, ticket: UUID, destination: Location) {
        if (backTickets[player.uniqueId] != ticket) return
        backFlights.remove(player.uniqueId)?.cancel()
        backTickets.remove(player.uniqueId)
        val origin = backOrigins.remove(player.uniqueId)
        // A disabled context or a quit has nobody to move; the fare must not stay in escrow.
        if (!active.get() || !player.isOnline) { refundBack(player, ticket); return }
        // Dying during the countdown makes the death the return point, and a dead player cannot be
        // moved anyway. Refund and say so rather than charging for a trip that did not happen.
        if (player.isDead) { host.messages.send(player, "back-failed"); refundBack(player, ticket); return }
        // Holds even when the move event was not delivered: standing somewhere else at the end of
        // the countdown is the same refusal as walking away in the middle of it.
        if (origin != null && changedPosition(origin, player.location)) {
            host.messages.send(player, "back-moved"); refundBack(player, ticket); return
        }
        val settled = try {
            // Settlement follows the move. Asking for it up front committed the fare before the
            // outcome was known, so a teleport that failed was still charged in full.
            moveBack(player, destination).thenCompose { moved ->
                economy.settleTeleport(ticket, moved).thenApply { charge -> moved to charge }
            }
        } catch (error: Exception) {
            host.plugin.logger.log(Level.WARNING, "Back teleport failed", error)
            host.messages.send(player, "back-failed")
            refundBack(player, ticket); return
        }
        settled.whenComplete { result, error -> host.mainThread(active::get) {
            if (error != null) {
                // The move may or may not have landed and the charge is neither committed nor
                // refunded for certain, so this is a console-logged failure rather than a promise
                // about the money. Escrow recovery settles the row on the next start.
                host.plugin.logger.log(Level.SEVERE, "Back settlement failed", error)
                host.messages.send(player, "internal-error")
                return@mainThread
            }
            val (moved, charge) = result
            if (moved) {
                host.messages.send(player, "back-done", host.money(charge.fee))
                runCatching { presentation.arrive(player) }
            } else {
                // `settleTeleport(ticket, false)` already refunded the escrow; cancelling again
                // would be a second refund of the same ticket.
                host.messages.send(player, "back-failed")
            }
        } }
    }

    /** A quit, a disable or a blocked move must not leave the fee stranded in escrow. */
    private fun refundBack(player: Player, ticket: UUID) {
        economy.cancelTeleport(ticket).whenComplete { _, error ->
            if (error != null) host.plugin.logger.log(Level.SEVERE, "Back refund deferred to recovery", error)
        }
    }

    /**
     * Drops a queued `/back` because the player would not stand still, and gives the fare back.
     *
     * The ticket is compared rather than looked up: a late callback from an older attempt must not
     * be able to cancel a trip the player has since started.
     */
    private fun cancelBack(player: Player, ticket: UUID, key: String) {
        if (backTickets[player.uniqueId] != ticket) return
        backFlights.remove(player.uniqueId)?.cancel()
        backTickets.remove(player.uniqueId)
        backOrigins.remove(player.uniqueId)
        if (player.isOnline) host.messages.send(player, key)
        refundBack(player, ticket)
    }

    /**
     * Records where a player fell.
     *
     * EssentialsX hooks the `DeathComponent`; the event is the equivalent and carries the location
     * the player actually died at, including inside a vehicle or falling.
     */
    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        if (settings.backOnDeath) host.travelJournal.died(event.entity.uniqueId, event.entity.location)
    }

    private fun usage(player: Player) = host.messages.send(player, "tpa-usage")

    /**
     * `/tpacancel` withdraws either a still-pending request or a reservation that is waiting out its
     * countdown. Only the requester may cancel, matching the command's meaning ("withdraw your own
     * request"); the accepter simply never accepts. Cancelling a reservation cancels the queued move
     * and refunds the escrow, so the fee never survives a cancelled teleport.
     */
    private fun cancel(player: Player) {
        val pending = book.cancel(player.uniqueId)
        if (pending != null) { withdraw(pending, "tpa-cancelled"); return }
        val flight = flights.values.firstOrNull { it.from == player.uniqueId }
        if (flight == null) { host.messages.send(player, "tpa-none"); return }
        refund(flight)
        notifyBoth(flight, "tpa-countdown-cancelled")
    }

    private fun request(from: Player, name: String) {
        val to = Bukkit.getPlayerExact(name)
        if (to == null || !from.canSee(to)) { host.messages.send(from, "unknown-player"); return }
        // Distinguish the three rejection reasons so the player knows whether to wait, cancel or pick someone else.
        if (from.uniqueId == to.uniqueId) { host.messages.send(from, "tpa-self"); return }
        if (flights.values.any { it.from == from.uniqueId } || book.hasOutgoing(from.uniqueId)) {
            host.messages.send(from, "tpa-busy-out"); return
        }
        if (book.incoming(to.uniqueId).size >= RequestBook.MAX_INCOMING) { host.messages.send(from, "tpa-busy-in"); return }
        val request = book.create(from.uniqueId, to.uniqueId)
        if (request == null) { host.messages.send(from, "tpa-busy"); return }
        host.messages.send(from, "tpa-sent", to.name, settings.requestSeconds)
        host.messages.send(to, "tpa-incoming", from.name, settings.requestSeconds)
        sendActions(to, from.name, "tpa-click-accept", "tpa-click-accept-hint", "tpa-click-deny", "tpa-click-deny-hint")
        host.reply(from, economy.teleportFee(from.uniqueId), active::get) { fee ->
            if (book.incoming(to.uniqueId).any { it.ticket == request.ticket }) host.messages.send(from, "tpa-estimate", host.money(fee))
        }
    }

    private fun answer(to: Player, accept: Boolean, name: String?) {
        val from = name?.let { Bukkit.getPlayerExact(it)?.uniqueId }
        // Naming someone who is not online is a lookup failure, not an empty request inbox.
        if (name != null && from == null) { host.messages.send(to, "unknown-player"); return }
        val request = book.take(to.uniqueId, from)
        if (request == null) { host.messages.send(to, if (book.incoming(to.uniqueId).size > 1 && name == null) "tpa-choose" else "tpa-none"); return }
        if (!accept) { withdraw(request, "tpa-denied"); return }
        val requester = Bukkit.getPlayer(request.from)
        // The request was just consumed, so saying "no matching request" here would be plainly wrong.
        if (requester == null) { awaitingSponsor.remove(request.from); host.messages.send(to, "tpa-offline"); return }

        // The fee is reserved only now, after the accepter agreed. A previous failed attempt for
        // lack of funds flips the payer: this second accept covers the requester instead.
        val sponsoring = awaitingSponsor.remove(request.from)
        val payer = if (sponsoring) to else requester
        if (!host.claimTravel(requester.uniqueId, request.ticket)) { notifyBoth(request, "tpa-busy"); return }
        flights[request.ticket] = request
        val reservation = economy.reserveTeleport(request.ticket, requester.uniqueId, payer.uniqueId)
        // Standard error text and storage-failure logging; the insufficient-funds case is tailored below.
        host.reply(payer, reservation, active::get) { }
        reservation.whenComplete { charge, failure -> host.mainThread(active::get) {
            if (flights[request.ticket] == null) return@mainThread
            // The flight stays registered for the whole grace period: it is the guard that tells
            // runCountdown a quit/close/cancel has already refunded and the move must not happen.
            if (failure != null) { flights.remove(request.ticket); host.releaseTravel(request.from, request.ticket); onReservationFailed(request, to, sponsoring, failure); return@mainThread }
            val seconds = settings.countdownSeconds
            host.messages.send(to, "tpa-accepted", requester.name)
            if (sponsoring) {
                host.messages.send(requester, "tpa-charged-sponsored", to.name, host.money(charge.fee), seconds)
                host.messages.send(payer, "tpa-sponsor-charge-detail", host.money(charge.teleportTax), host.money(charge.paymentTax), host.money(charge.fee))
            } else host.messages.send(requester, "tpa-charged", host.money(charge.fee), seconds)
            try { startCountdown(request) }
            catch (error: Exception) {
                refund(request)
                host.plugin.logger.log(Level.SEVERE, "Teleport preparation failed", error)
                notifyBoth(request, "tpa-failed")
            }
        } }
    }

    /** Restores the request for a sponsoring accept, or reports the failure to both parties. */
    private fun onReservationFailed(request: TeleportRequest, accepter: Player, sponsoring: Boolean, failure: Throwable) {
        val cause = (if (failure is CompletionException) failure.cause else failure) ?: failure
        val broke = (cause as? EconomyException)?.code == EconomyError.INSUFFICIENT_FUNDS
        val requesterName = Bukkit.getPlayer(request.from)?.name
        if (!broke || requesterName == null) { notifyBoth(request, "tpa-failed"); return }
        if (sponsoring) {
            // The accepter cannot cover it either; do not keep offering sponsorship in a loop.
            host.messages.send(accepter, "tpa-sponsor-failed", requesterName)
            notifyBoth(request, "tpa-failed")
            return
        }
        if (!book.restore(request)) { notifyBoth(request, "tpa-failed"); return }
        awaitingSponsor += request.from
        host.messages.send(accepter, "tpa-sponsor-offer", requesterName)
        sendActions(accepter, requesterName, "tpa-click-sponsor", "tpa-click-sponsor-hint", "tpa-click-refuse", "tpa-click-refuse-hint")
    }

    private fun startCountdown(request: TeleportRequest) {
        val source = Bukkit.getPlayer(request.from)
        if (source == null || !source.isOnline || source.isDead) {
            refund(request)
            notifyBoth(request, "tpa-failed")
            return
        }
        val delay = settings.countdownSeconds * 20L
        origins[request.ticket] = source.location.clone()
        // Give feedback at the departure point, before scheduling the actual move.
        applyTravelEffects(source, delay.toInt())
        if (settings.particles) animate(source, delay.toInt().coerceAtLeast(1))
        countdowns[request.ticket] = Bukkit.getScheduler().runTaskLater(host.plugin, Runnable { runCountdown(request) }, delay)
    }

    private fun applyTravelEffects(player: Player, ticks: Int) = presentation.applyTravelEffects(player, ticks)

    private fun runCountdown(request: TeleportRequest) {
        countdowns.remove(request.ticket)
        // A quit, close or cancellation during the grace period already refunded and dropped the flight.
        if (!active.get() || flights[request.ticket] == null) return
        presentation.stopWaiting(request.from)
        val source = Bukkit.getPlayer(request.from)
        val target = Bukkit.getPlayer(request.to)
        val origin = origins.remove(request.ticket)
        if (source != null && origin != null && changedPosition(origin, source.location)) {
            refund(request)
            notifyBoth(request, "tpa-moved")
            return
        }
        var moved = false
        try {
            if (source != null && target != null && source.isOnline && target.isOnline && !source.isDead && !target.isDead)
                moved = move(source, target)
        } catch (error: Exception) { host.plugin.logger.log(Level.SEVERE, "Teleport failed", error) }
        // Movement is synchronous on the main thread; close/quit cannot interleave before settlement is queued.
        val result = economy.settleTeleport(request.ticket, moved)
        flights.remove(request.ticket)
        host.releaseTravel(request.from, request.ticket)
        if (moved && source != null) {
            if (settings.particles) try { arrive(source) }
            catch (error: Exception) { host.plugin.logger.log(Level.WARNING, "Arrival particles failed", error) }
            host.reply(source, result, active::get) { settled -> host.messages.send(source, "tpa-success", host.money(settled.fee)) }
        } else {
            result.whenComplete { _, error -> if (error != null) host.plugin.logger.log(Level.SEVERE, "Teleport refund failed: ${request.ticket}", error) }
            notifyBoth(request, "tpa-failed")
        }
    }

    /** Emits the "» [primary] · [secondary]" action line; both run `/tpaccept|deny <name>` as the reader. */
    private fun sendActions(target: Player, otherName: String, primary: String, primaryHint: String, secondary: String, secondaryHint: String) {
        host.messages.say(target, SPEAKER, host.messages.component(target, "action-prefix").append(host.messages.actionLine(target,
            host.messages.action(target, primary, "/tpaccept $otherName", primaryHint, otherName),
            host.messages.action(target, secondary, "/tpdeny $otherName", secondaryHint, otherName),
        )))
    }

    private fun notifyBoth(request: TeleportRequest, key: String) {
        listOf(request.from, request.to).forEach { Bukkit.getPlayer(it)?.let { player -> host.messages.send(player, key) } }
    }
    /** Drops a still-pending request and tells both sides, without touching escrow. */
    private fun withdraw(request: TeleportRequest, key: String) {
        awaitingSponsor.remove(request.from)
        book.cancel(request.from)
        notifyBoth(request, key)
    }
    private fun refund(request: TeleportRequest) {
        countdowns.remove(request.ticket)?.cancel()
        origins.remove(request.ticket)
        presentation.stopWaiting(request.from)
        awaitingSponsor.remove(request.from)
        // Queued immediately behind reserve, even if reserve has not completed. Missing reservations are a no-op.
        economy.cancelTeleport(request.ticket).whenComplete { _, error ->
            if (error != null) host.plugin.logger.log(Level.SEVERE, "Teleport cancellation failed; escrow will recover on restart: ${request.ticket}", error)
        }
        flights.remove(request.ticket)
        host.releaseTravel(request.from, request.ticket)
    }
    private fun changedPosition(from: Location, to: Location): Boolean =
        from.world != to.world || from.distanceSquared(to) > POSITION_TOLERANCE * POSITION_TOLERANCE

    /**
     * Camera-only events never cancel; meaningful displacement from the origin refunds escrow.
     *
     * `/back` answers to the same rule as `/tpa`, against the origin fixed when its fare was
     * reserved, so a player cannot walk the destination along with them. Its own arrival clears the
     * ticket before moving, so the teleport that lands them is not mistaken for walking away.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun movement(event: PlayerMoveEvent) {
        val to = event.to ?: return
        if (!event.hasChangedPosition()) return
        flights.values.filter { it.from == event.player.uniqueId && it.ticket in countdowns }.toList().forEach {
            val origin = origins[it.ticket] ?: return@forEach
            if (!changedPosition(origin, to)) return@forEach
            refund(it)
            notifyBoth(it, "tpa-moved")
        }
        val ticket = backTickets[event.player.uniqueId] ?: return
        val backOrigin = backOrigins[event.player.uniqueId] ?: return
        if (changedPosition(backOrigin, to)) cancelBack(event.player, ticket, "back-moved")
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun otherTeleport(event: PlayerTeleportEvent) = movement(event)

    @EventHandler fun quit(event: PlayerQuitEvent) {
        // A return point from a previous session is worse than none: the player would pay to go
        // somewhere they have not been in a week.
        host.travelJournal.forget(event.player.uniqueId)
        val id = event.player.uniqueId
        book.removePlayer(id).forEach { withdraw(it, "tpa-cancelled") }
        flights.values.filter { it.from == id || it.to == id }.toList().forEach(::refund)
        presentation.stop(id)
    }
    private fun animate(player: Player, ticks: Int) = presentation.animate(player, ticks)
    private fun arrive(player: Player) = presentation.arrive(player)
    override fun close() {
        active.set(false)
        flights.values.toList().forEach(::refund)
        book.clear()
        awaitingSponsor.clear()
        countdowns.values.forEach { it.cancel() }; countdowns.clear()
        origins.clear()
        presentation.close()
        resources.asReversed().forEach { it.close() }; resources.clear()
    }
}
