package com.meowo.shared.event

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Instance-scoped synchronous dispatcher. Exact event types, registration order, isolated subscriber exceptions.
 * Callbacks run on the publisher's thread. Events are best-effort notifications, not a durable queue or a
 * transaction mechanism. Publishing after close is a no-op; this cannot turn a committed operation into a failed result.
 *
 * ## The lock is not held while subscribers run
 *
 * Delivery used to happen inside the bus's own lock, which meant a slow subscriber held every other
 * publisher — and every subscribe and unsubscribe — hostage for as long as it took. On a server where
 * one subscriber does a database read, that is the whole event system waiting on one row.
 *
 * So the handler list is **snapshotted** under the lock and the callbacks run outside it. Two
 * consequences, both deliberate:
 *
 * - A subscriber may publish again from inside its own callback. It did before too, and a nested
 *   publish is dispatched immediately rather than queued.
 * - A subscription that is closed stops receiving **new** events. A delivery already in flight may
 *   still complete, because the callback is already running and nothing can un-run it; what is
 *   guaranteed is that no *new* delivery starts for a closed subscription.
 */
class EventBus(private val onFailure: (Any, Exception) -> Unit) : AutoCloseable {
    private class Handler(val type: Class<*>, val invoke: (Any) -> Unit) { @Volatile var active = true }
    private val lock = ReentrantLock()
    private val handlers = CopyOnWriteArrayList<Handler>()
    private var closed = false

    fun <E : Any> subscribe(type: Class<E>, handler: (E) -> Unit): AutoCloseable = lock.withLock {
        check(!closed) { "Event bus is closed" }
        val entry = Handler(type) { handler(type.cast(it)) }
        handlers.add(entry)
        AutoCloseable { lock.withLock { entry.active = false; handlers.remove(entry) } }
    }

    fun publish(event: Any) {
        // The snapshot is taken under the lock, so a subscribe or a close cannot tear the list in
        // half; the callbacks then run with the lock released.
        val snapshot = lock.withLock {
            if (closed) return
            handlers.toList()
        }
        for (entry in snapshot) {
            if (!entry.active || entry.type != event.javaClass) continue
            try { entry.invoke(event) } catch (error: Exception) {
                try { onFailure(event, error) } catch (reportError: Exception) {
                    error.addSuppressed(reportError)
                    System.getLogger(EventBus::class.java.name).log(System.Logger.Level.ERROR, "Event handler and error reporter failed", error)
                }
            }
        }
    }

    val subscriberCount: Int get() = lock.withLock { handlers.size }
    override fun close() = lock.withLock { closed = true; handlers.forEach { it.active = false }; handlers.clear() }
}
