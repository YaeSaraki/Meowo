package com.meowo.platform

import java.util.Locale
import kotlin.math.roundToInt

data class RunePalette(val primary: Int, val highlight: Int)

/** Muted biome colours with a pale companion shade; no random or fully saturated hues. */
object BiomePalette {
    fun forBiome(key: String): RunePalette {
        val name = key.substringAfter(':').lowercase(Locale.ROOT)
        val primary = when {
            name.contains("cherry") -> 0xD5A5BB
            name.contains("mushroom") -> 0xB5A2CD
            name.contains("warped") -> 0x88C2B6
            name.contains("crimson") -> 0xC89092
            name.contains("soul_sand") || name.contains("basalt") -> 0xA39FC7
            name.contains("nether") -> 0xC4A084
            name.contains("end") && (name == "the_end" || name.startsWith("end_") || name == "small_end_islands") -> 0xB2A2D2
            name.contains("snow") || name.contains("frozen") || name.contains("ice") || name == "grove" ||
                name.contains("jagged") || name.contains("stony_peaks") -> 0xA9C9DE
            name.contains("desert") || name.contains("badlands") || name.contains("savanna") -> 0xD7B77A
            name.contains("ocean") || name.contains("river") || name.contains("beach") -> 0x86B6CF
            name.contains("swamp") -> 0x88AEA6
            name.contains("forest") || name.contains("taiga") || name.contains("jungle") ||
                name.contains("plains") || name.contains("meadow") -> 0x91B7A2
            else -> 0xA7BCC9
        }
        fun lighten(channel: Int) = (channel + (255 - channel) * 0.42).roundToInt()
        val highlight = (lighten((primary shr 16) and 255) shl 16) or
            (lighten((primary shr 8) and 255) shl 8) or lighten(primary and 255)
        return RunePalette(primary, highlight)
    }
}
