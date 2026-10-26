package com.meowo.teleport

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.*
import java.util.UUID

class RequestBookTest {
    private class MutableClock(var now: Instant = Instant.EPOCH) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant() = now
    }
    @Test fun `requests require exact recipient and explicit selection when ambiguous`() {
        val book=RequestBook(60); val a=UUID.randomUUID(); val b=UUID.randomUUID(); val target=UUID.randomUUID()
        val first=book.create(a,target)!!; book.create(b,target)
        assertNull(book.create(a,b)); assertNull(book.create(target,target))
        assertNull(book.take(a,target)); assertNull(book.take(target,null))
        assertEquals(first,book.take(target,a)); assertNotNull(book.take(target,null))
        assertTrue(book.incoming(target).isEmpty())
    }
    @Test fun `expiration cancellation disconnect and clear remove requests`() {
        val clock=MutableClock(); val book=RequestBook(60,clock); val a=UUID.randomUUID(); val b=UUID.randomUUID()
        book.create(a,b); clock.now=clock.now.plusSeconds(60)
        assertNull(book.take(b,a)); assertEquals(1,book.expire().size)
        book.create(a,b); assertNotNull(book.cancel(a)); assertNull(book.cancel(a))
        book.create(a,b); assertEquals(1,book.removePlayer(b).size)
        book.create(a,b); book.clear(); assertTrue(book.incoming(b).isEmpty())
    }
    @Test fun `recipient queue is bounded and expired requests free capacity`() {
        val clock=MutableClock(); val book=RequestBook(5,clock); val target=UUID.randomUUID()
        repeat(10) { assertNotNull(book.create(UUID.randomUUID(),target)) }
        assertNull(book.create(UUID.randomUUID(),target))
        clock.now=clock.now.plusSeconds(6); assertNotNull(book.create(UUID.randomUUID(),target))
        assertEquals(1,book.incoming(target).size)
        assertThrows(IllegalArgumentException::class.java) { TeleportSettings(1) }
        assertThrows(IllegalArgumentException::class.java) { TeleportSettings(60,true,-1) }
        assertThrows(IllegalArgumentException::class.java) { TeleportSettings(60,true,31) }
    }
    @Test fun `a consumed request can be restored for a sponsoring accept but never resurrected`() {
        val clock=MutableClock(); val book=RequestBook(60,clock); val a=UUID.randomUUID(); val b=UUID.randomUUID()
        val request=book.create(a,b)!!
        assertEquals(request,book.take(b,a))
        assertTrue(book.incoming(b).isEmpty())
        // A sponsoring accept finds the very same request again.
        assertTrue(book.restore(request))
        assertEquals(listOf(request),book.incoming(b))
        // Restoring must not duplicate or overwrite while the requester still holds one.
        assertFalse(book.restore(request.copy(ticket=UUID.randomUUID())))
        assertEquals(1,book.incoming(b).size)
        // Sponsorship must not extend the original deadline.
        assertEquals(request,book.take(b,a))
        clock.now=clock.now.plusSeconds(61)
        assertFalse(book.restore(request),"an expired request must not be resurrected")
        assertTrue(book.incoming(b).isEmpty())
    }
}
