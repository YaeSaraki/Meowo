package com.meowo.home

import com.meowo.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.util.UUID

class HomeRulesTest {
    private fun home()=Home(UUID.randomUUID(),1,UUID.randomUUID(),"世界",0.0,80.0,0.0,0f,0f,"家的名字",0x91B7A2)
    @Test fun `horizontal distance excludes height and fees clamp and round up`() {
        val h=home();val s=HomeSettings()
        assertEquals(5000.0,h.distance(3000.0,4000.0))
        assertEquals(BigDecimal("500.00"),s.travelAssessment(h.distance(3000.0,4000.0)).amount(BigDecimal.ZERO))
        assertEquals(BigDecimal("100.00"),s.travelAssessment(0.0).amount(BigDecimal.ZERO))
        assertEquals(BigDecimal("2333.00"),s.travelAssessment(1e8).amount(BigDecimal.ZERO))
        assertEquals(BigDecimal("100.01"),s.travelAssessment(1000.001).amount(BigDecimal.ZERO))
        assertEquals(BigDecimal("2333.00"),s.travelAssessment(null).amount(BigDecimal.ZERO))
        assertEquals(BigDecimal("100.00"),s.setAssessment().amount(BigDecimal("999")))
        assertEquals(BigDecimal("500.01"),s.setAssessment().amount(BigDecimal("10000.01")))
        assertEquals(BigDecimal("2333.00"),s.setAssessment().amount(BigDecimal("100000")))
    }
    @Test fun `settings reject invalid bounds divisor rate and excessive capacity`() {
        assertThrows(IllegalArgumentException::class.java) { HomeSettings(limit=1001) }
        assertThrows(IllegalArgumentException::class.java) { HomeSettings(distanceDivisor=BigDecimal.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { HomeSettings(setRate=BigDecimal("1.1")) }
        assertThrows(IllegalArgumentException::class.java) { HomeSettings(maximum=BigDecimal("1")) }
        assertThrows(IllegalArgumentException::class.java) { HomeSettings(crossWorldFee=BigDecimal("2500")) }
    }
    @Test fun `encoding retains unicode precision appearance and stable private marker ids`() {
        val h=home().copy(x=-12345.123,y=95.5,z=0.04,yaw=152f,visible=false,style="bowtie",item="BELL")
        assertEquals(h,Home.decode(h.encode()))
        assertEquals(h.markerId,h.copy(name="改名",color=0xFFFFFF).markerId)
        assertNotEquals(h.markerId,h.copy(owner=UUID.randomUUID()).markerId)
        assertTrue(Home.validName("家 🏠"))
        listOf("", "§c家", "a\n", "a".repeat(25)).forEach { assertFalse(Home.validName(it)) }
        assertEquals(0x91B7A2,Home.parseColor("#91b7a2"));assertNull(Home.parseColor("oops"))
    }
    @Test fun `navigation points relative to view direction`() {
        val h=home().copy(z=100.0)
        assertEquals("↑",h.direction(0.0,0.0,0f));assertEquals("←",h.direction(0.0,0.0,90f))
        assertEquals("↓",h.direction(0.0,0.0,180f))
    }
    @Test fun `native waypoint keeps private stable id color style and negative floor coordinates`() {
        val h=home().copy(x=-0.5,y=65.8,z=0.9,color=0x91B7A2,style="bowtie")
        val marker=PacketHomeNavigator.waypoint(h)
        assertEquals(h.markerId,marker.identifier.left)
        assertEquals("minecraft:bowtie",marker.icon.style.toString())
        assertEquals(h.color,marker.icon.color!!.asRGB())
        val position=(marker.info as com.github.retrooper.packetevents.protocol.world.waypoint.Vec3iWaypointInfo).position
        assertEquals(-1,position.x);assertEquals(65,position.y);assertEquals(0,position.z)
    }
}
