package com.meowo.teleport

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlin.math.*

class TeleportGeometryTest {
    @Test fun `white rune chain stays near the body and travels faster than a quarter turn per second`() {
        val first=TeleportGeometry.runeChain(0)
        val later=TeleportGeometry.runeChain(8)
        assertTrue((first+later).all { hypot(it.x,it.z) in 0.4..0.65 && it.y in 0.65..1.4 })
        assertTrue(first.any { it.ink==RuneInk.INK })
        assertTrue(first.any { it.ink==RuneInk.PAPER },"glyphs must have a fine connecting thread")
        val a=first.first(); val b=later.first()
        val turn=atan2(a.x*b.z-a.z*b.x,a.x*b.x+a.z*b.z)
        assertTrue(turn in 1.0..2.0,"the string should move visibly quickly in eight ticks")
    }
    @Test fun `landing circle remains at foot level and contains six distinct star vertices`() {
        val shape=TeleportGeometry.magicCircle(0)
        assertTrue(shape.all { hypot(it.x,it.z)<=0.861 && it.y in 0.04..0.07 })
        val vertices=shape.filter { it.ink==RuneInk.INK && abs(hypot(it.x,it.z)-0.70)<0.00001 }
            .map { Pair((it.x*10000).roundToInt(),(it.z*10000).roundToInt()) }.toSet()
        assertEquals(6,vertices.size)
        assertTrue(shape.any { it.ink==RuneInk.PAPER && hypot(it.x,it.z)>0.8 },"a delicate halo frames the star")
    }
    @Test fun `magic circle rotates slowly with stable bounds and clamps out of lifetime frames`() {
        val first=TeleportGeometry.magicCircle(0); val later=TeleportGeometry.magicCircle(20)
        assertEquals(first.size,later.size)
        assertNotEquals(first,later)
        val a=first.first(); val b=later.first()
        val turn=atan2(a.x*b.z-a.z*b.x,a.x*b.x+a.z*b.z)
        assertTrue(turn in 0.1..0.3,"arrival rotation must be much gentler than the travelling runes")
        assertEquals(first,TeleportGeometry.magicCircle(-1))
        assertEquals(TeleportGeometry.magicCircle(TeleportGeometry.ARRIVAL_TICKS),TeleportGeometry.magicCircle(999))
    }
}
