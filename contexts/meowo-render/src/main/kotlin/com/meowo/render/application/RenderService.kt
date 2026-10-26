package com.meowo.render.application

import com.meowo.render.domain.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin

/** Per-viewer bounded fake entities. Main-thread confined; never adds real world entities. */
class RenderService(private val renderer: FakeEntityRenderer, private val maxPerPlayer: Int = 200) : AutoCloseable {
    private val entities = mutableMapOf<UUID, MutableList<Int>>()
    init { require(maxPerPlayer in 1..1000) }
    fun spawn(viewer: UUID, count: Int, x: Double, y: Double, z: Double) {
        if (count !in 1..maxPerPlayer || (entities[viewer]?.size ?: 0) + count > maxPerPlayer) throw RenderLimitException()
        val ids = entities.getOrPut(viewer) { mutableListOf() }
        val added = mutableListOf<Int>()
        try {
            repeat(count) { index ->
                val id = sequence.getAndDecrement()
                check(id < 0) { "Entity ID range exhausted" }
                ids += id; added += id
                val angle = index * 2 * Math.PI / count
                renderer.spawn(viewer, FakeEntity(id, UUID.randomUUID(), x + cos(angle) * 3, y, z + sin(angle) * 3))
            }
        } catch (e: Exception) {
            try { renderer.clear(viewer, added) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            ids.removeAll(added.toSet())
            if (ids.isEmpty()) entities.remove(viewer)
            throw e
        }
    }
    fun clear(viewer: UUID) { entities.remove(viewer)?.let { renderer.clear(viewer, it) } }
    fun forget(viewer: UUID) { entities.remove(viewer) }
    fun count(viewer: UUID) = entities[viewer]?.size ?: 0
    override fun close() {
        var error: Exception? = null
        entities.keys.toList().forEach { try { clear(it) } catch (e: Exception) { if (error == null) error = e else error!!.addSuppressed(e) } }
        error?.let { throw it }
    }
    companion object { private val sequence = AtomicInteger(-1) }
}
