package com.meowo.render

import com.meowo.render.application.RenderService
import com.meowo.render.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class RenderServiceTest {
    private class Renderer : FakeEntityRenderer {
        val spawned=mutableListOf<FakeEntity>(); val cleared=mutableListOf<Int>(); var fail=false
        override fun spawn(viewer: UUID, entity: FakeEntity) { spawned+=entity; if(fail) error("packet") }
        override fun clear(viewer: UUID, ids: List<Int>) { cleared.addAll(ids) }
    }
    @Test fun `per viewer limit and cleanup`() {
        val renderer=Renderer(); val service=RenderService(renderer,10); val a=UUID.randomUUID(); val b=UUID.randomUUID()
        service.spawn(a,10,0.0,64.0,0.0); service.spawn(b,1,0.0,64.0,0.0)
        assertEquals(11,renderer.spawned.map { it.id }.toSet().size); assertTrue(renderer.spawned.all { it.id<0 })
        assertThrows(IllegalArgumentException::class.java) { service.spawn(a,1,0.0,0.0,0.0) }
        assertThrows(IllegalArgumentException::class.java) { service.spawn(b,0,0.0,0.0,0.0) }
        service.clear(a); assertEquals(0,service.count(a)); assertEquals(1,service.count(b))
        service.close(); service.close(); assertEquals(11,renderer.cleared.size)
    }
    @Test fun `failed spawn rolls back newly sent packets and quit forgets connection`() {
        val renderer=Renderer(); val service=RenderService(renderer); val id=UUID.randomUUID()
        renderer.fail=true
        assertThrows(IllegalStateException::class.java) { service.spawn(id,2,0.0,0.0,0.0) }
        assertEquals(0,service.count(id)); assertEquals(1,renderer.cleared.size)
        renderer.fail=false; service.spawn(id,2,0.0,0.0,0.0); service.forget(id); service.close()
        assertEquals(1,renderer.cleared.size)
    }
}
