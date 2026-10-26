package com.meowo.teleport

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BiomePaletteTest {
    @Test fun `biome families use distinct muted hues with lighter harmonised outlines`() {
        val names=listOf("forest","ocean","snowy_plains","desert","swamp","cherry_grove","mushroom_fields",
            "crimson_forest","warped_forest","soul_sand_valley","nether_wastes","the_end")
        val palettes=names.map(BiomePalette::forBiome)
        assertEquals(names.size,palettes.map { it.primary }.toSet().size)
        for (palette in palettes) {
            val base=listOf(16,8,0).map { (palette.primary shr it) and 255 }
            val outline=listOf(16,8,0).map { (palette.highlight shr it) and 255 }
            assertTrue(base.all { it in 120..225 },"colours should stay in a restrained pastel range")
            assertTrue((base.max()-base.min()).toDouble()/base.max()<0.5,"avoid highly saturated colours")
            assertTrue(base.zip(outline).all { (a,b) -> b>a },"the outer ring should be a lighter companion shade")
        }
    }
    @Test fun `namespaces and overlapping biome names resolve to their intended family`() {
        assertEquals(BiomePalette.forBiome("DESERT"),BiomePalette.forBiome("minecraft:desert"))
        assertEquals(BiomePalette.forBiome("swamp"),BiomePalette.forBiome("mangrove_swamp"))
        assertEquals(BiomePalette.forBiome("snowy_plains"),BiomePalette.forBiome("frozen_ocean"))
        assertEquals(BiomePalette.forBiome("the_end"),BiomePalette.forBiome("small_end_islands"))
        assertNotEquals(BiomePalette.forBiome("forest"),BiomePalette.forBiome("crimson_forest"))
        assertNotEquals(0,BiomePalette.forBiome("custom:unknown").primary)
    }
}
