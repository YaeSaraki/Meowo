package com.meowo.teleport

import java.time.Clock
import java.time.Instant
import java.util.UUID

data class TeleportRequest(val ticket: UUID, val from: UUID, val to: UUID, val expires: Instant)
/** Main-thread owned. One outgoing request and at most ten incoming requests per player. */
class RequestBook(private val seconds: Int, private val clock: Clock = Clock.systemUTC()) {
    private val requests = linkedMapOf<UUID, TeleportRequest>()
    fun create(from: UUID, to: UUID): TeleportRequest? {
        expire()
        if (from == to || requests.containsKey(from) || incoming(to).size >= MAX_INCOMING) return null
        return TeleportRequest(UUID.randomUUID(), from, to, clock.instant().plusSeconds(seconds.toLong())).also { requests[from] = it }
    }
    /** Lets callers tell "you already asked" apart from "their inbox is full" and report a precise reason. */
    fun hasOutgoing(from: UUID): Boolean {
        expire()
        return requests.containsKey(from)
    }
    fun incoming(to: UUID): List<TeleportRequest> = requests.values.filter { it.to == to && it.expires.isAfter(clock.instant()) }
    fun take(to: UUID, from: UUID?): TeleportRequest? {
        val matching = incoming(to).filter { from == null || it.from == from }
        return matching.singleOrNull()?.also { requests.remove(it.from) }
    }
    /**
     * Puts a consumed request back so a second accept can pick it up. Used when the requester could
     * not pay: the accepter is offered the chance to sponsor, and that accept finds the request again.
     * The original deadline is kept — an expired request is never resurrected, so sponsoring cannot
     * be used to extend a request indefinitely. Fails if the requester already has one or the target
     * is at the incoming cap.
     */
    fun restore(request: TeleportRequest): Boolean {
        expire()
        if (!request.expires.isAfter(clock.instant())) return false
        if (requests.containsKey(request.from) || incoming(request.to).size >= MAX_INCOMING) return false
        requests[request.from] = request
        return true
    }
    fun cancel(from: UUID): TeleportRequest? = requests.remove(from)
    fun removePlayer(player: UUID): List<TeleportRequest> = removeWhere { it.from == player || it.to == player }
    fun expire(): List<TeleportRequest> = removeWhere { !it.expires.isAfter(clock.instant()) }
    fun clear() { requests.clear() }
    private fun removeWhere(predicate: (TeleportRequest) -> Boolean): List<TeleportRequest> {
        val removed = requests.values.filter(predicate)
        removed.forEach { requests.remove(it.from) }
        return removed
    }

    companion object { const val MAX_INCOMING = 10 }
}
