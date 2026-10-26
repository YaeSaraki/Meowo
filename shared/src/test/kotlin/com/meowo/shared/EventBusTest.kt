package com.meowo.shared

import com.meowo.shared.event.EventBus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class EventBusTest {
    data class Notice(val value: Int)
    @Test fun `ordered dispatch isolates failure and supports unsubscribe`() {
        val failures = mutableListOf<Exception>(); val calls = mutableListOf<Int>()
        val bus = EventBus { _, e -> failures += e }
        val subscription = bus.subscribe(Notice::class.java) { calls += it.value }
        bus.subscribe(Notice::class.java) { throw IllegalStateException("broken") }
        bus.subscribe(Notice::class.java) { calls += it.value * 2 }
        bus.publish(Notice(3)); subscription.close(); subscription.close(); bus.publish(Notice(4))
        assertEquals(listOf(3,6,8), calls); assertEquals(2, failures.size)
        assertEquals(2, bus.subscriberCount)
        bus.close(); bus.close(); assertEquals(0, bus.subscriberCount)
        bus.publish(Notice(0)); assertEquals(listOf(3,6,8), calls)
        assertThrows(IllegalStateException::class.java) { bus.subscribe(Notice::class.java) {} }
    }
    @Test fun `exact type only and reentrant cancellation`() {
        val bus = EventBus { _, e -> throw e }; val calls = mutableListOf<Int>()
        bus.subscribe(Any::class.java) { fail<Unit>("Must not match a superclass") }
        lateinit var second: AutoCloseable
        bus.subscribe(Notice::class.java) { second.close(); calls += it.value }
        second = bus.subscribe(Notice::class.java) { fail<Unit>("Cancelled subscription") }
        bus.publish(Notice(1)); assertEquals(listOf(1), calls)
    }
    @Test fun `concurrent publishers do not lose notifications`() {
        val bus = EventBus { _, e -> throw e }; val count = AtomicInteger()
        bus.subscribe(Notice::class.java) { count.incrementAndGet() }
        val pool = Executors.newFixedThreadPool(4)
        try { val jobs = (1..200).map { pool.submit { bus.publish(Notice(it)) } }; jobs.forEach { it.get(5, TimeUnit.SECONDS) } }
        finally { pool.shutdownNow(); bus.close() }
        assertEquals(200, count.get())
    }
    /**
     * A slow subscriber must not hold up anybody else.
     *
     * Delivery used to happen inside the bus's own lock, so one subscriber doing a database read
     * held every other publisher — and every subscribe — for the length of that read. The second
     * publisher below is the proof: with the lock held it cannot get in until the first one is
     * released.
     */
    @Test fun `a slow subscriber does not hold up another publisher`() {
        val bus = EventBus { _, e -> throw e }
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        // Only the first notice is slow; the second one has to get through.
        bus.subscribe(Notice::class.java) {
            if (it.value == 1) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val slow = Thread { bus.publish(Notice(1)) }
        slow.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val through = AtomicInteger()
        val fast = Thread { bus.publish(Notice(2)); through.incrementAndGet() }
        fast.start()
        fast.join(2_000)
        assertEquals(1, through.get(), "the bus lock was held while a subscriber ran")
        release.countDown()
        slow.join(5_000)
        bus.close()
    }

    /** A subscriber may subscribe from inside its own callback. */
    @Test fun `a subscriber may subscribe while it is being called`() {
        val bus = EventBus { _, e -> throw e }
        val calls = mutableListOf<Int>()
        bus.subscribe(Notice::class.java) {
            calls += it.value
            bus.subscribe(Notice::class.java) { calls += it.value * 10 }
        }
        bus.publish(Notice(1))
        assertEquals(listOf(1), calls, "the new subscriber starts with the next event")
        bus.publish(Notice(2))
        assertEquals(listOf(1, 2, 20), calls)
        bus.close()
    }

    @Test fun `nested publishing preserves documented immediate order`() {
        val calls = mutableListOf<Int>(); val bus = EventBus { _, e -> throw e }
        bus.subscribe(Notice::class.java) { calls += it.value; if (it.value == 1) bus.publish(Notice(2)) }
        bus.publish(Notice(1)); assertEquals(listOf(1,2), calls)
    }
}
