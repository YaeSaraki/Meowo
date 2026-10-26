package com.meowo.render.domain

import java.util.UUID

data class FakeEntity(val id: Int, val uuid: UUID, val x: Double, val y: Double, val z: Double)
interface FakeEntityRenderer {
    fun spawn(viewer: UUID, entity: FakeEntity)
    fun clear(viewer: UUID, ids: List<Int>)
}

class RenderLimitException : IllegalArgumentException("render-limit")
