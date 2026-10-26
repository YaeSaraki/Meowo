package com.meowo.platform

import kotlin.math.*

/** Thin outlines and brighter rune strokes are rendered separately. */
enum class RuneInk { PAPER, INK }
data class RunePoint(val x: Double, val y: Double, val z: Double, val ink: RuneInk = RuneInk.PAPER)

/** Geometry is independent of the world; animation ticks are elapsed server ticks. */
object TeleportGeometry {
    const val ARRIVAL_TICKS = 60

    /** One compact string of glyphs, rather than disconnected paper charms. */
    fun runeChain(tick: Int): List<RunePoint> = buildList {
        val phase = tick * 0.18
        fun centre(index: Double): Triple<Double, Double, Double> {
            val angle = phase - index * 0.33
            return Triple(cos(angle) * 0.52, 1.20 - index * 0.06 + sin(tick * 0.15 + index * 0.4) * 0.04,
                sin(angle) * 0.52)
        }
        repeat(6) { rune ->
            val angle = phase - rune * 0.33
            val (cx, cy, cz) = centre(rune.toDouble())
            fun stroke(ax: Double, ay: Double, bx: Double, by: Double) {
                repeat(3) { i ->
                    val f = i / 2.0
                    val u = ax + (bx - ax) * f; val v = ay + (by - ay) * f
                    add(RunePoint(cx - sin(angle) * u, cy + v, cz + cos(angle) * u, RuneInk.INK))
                }
            }
            when (rune % 3) {
                0 -> {
                    stroke(0.0,-0.10,0.0,0.10)
                    stroke(0.0,0.08,0.07,0.03)
                    stroke(0.0,0.01,0.07,-0.05)
                }
                1 -> {
                    stroke(-0.06,-0.09,0.06,0.09)
                    stroke(0.06,-0.09,-0.06,0.09)
                }
                else -> {
                    stroke(0.0,-0.10,0.0,0.10)
                    stroke(0.0,0.04,-0.07,-0.02)
                    stroke(0.0,0.04,0.07,-0.02)
                }
            }
            if (rune < 5) repeat(4) { link ->
                val (x,y,z) = centre(rune + (link + 1) / 5.0)
                add(RunePoint(x,y,z))
            }
        }
    }

    /** A small ground ring and two interlocking triangles rotate slowly around the landing point. */
    fun magicCircle(tick: Int): List<RunePoint> = buildList {
        val phase = tick.coerceIn(0, ARRIVAL_TICKS) * 0.009
        repeat(60) { i ->
            val angle = phase + i * 2 * PI / 60
            add(RunePoint(cos(angle) * 0.86, 0.055, sin(angle) * 0.86))
        }
        val vertices = (0..5).map { i ->
            val angle = phase + PI / 6 + i * PI / 3
            Pair(cos(angle) * 0.70, sin(angle) * 0.70)
        }
        repeat(2) { triangle -> repeat(3) { edge ->
            val a = vertices[triangle + edge * 2]
            val b = vertices[triangle + ((edge + 1) % 3) * 2]
            repeat(13) { i ->
                val f = i / 12.0
                add(RunePoint(a.first + (b.first - a.first) * f, 0.055,
                    a.second + (b.second - a.second) * f, RuneInk.INK))
            }
        } }
    }
}
