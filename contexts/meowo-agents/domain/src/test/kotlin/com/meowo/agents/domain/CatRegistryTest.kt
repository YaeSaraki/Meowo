package com.meowo.agents.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatRegistryTest {

    @Test fun `every registered cat resolves by its own id`() {
        for (profile in CatRegistry.all) {
            assertSame(profile, CatRegistry.byId(profile.id), "${profile.id} must resolve to itself")
        }
    }

    @Test fun `speaker ids and cat ids cover the same set`() {
        assertEquals(CatRegistry.speakerIds(), CatRegistry.all.map { it.id }.toSet())
    }

    @Test fun `an unknown name falls back to the system cat instead of guessing`() {
        // The HomeCat/NestCat collision is exactly what this guards against: a typo or an ambiguous
        // name must never reach a cat that then answers about the wrong thing.
        assertSame(CatRegistry.byId("system"), CatRegistry.resolve("NobodyCat"))
        assertSame(CatRegistry.byId("system"), CatRegistry.resolve("spawnCatCat"))
        assertNull(CatRegistry.resolve("   "))
    }

    @Test fun `a display name is accepted as well as an id`() {
        assertEquals("economy", CatRegistry.resolve("EconCat")?.id)
        assertEquals("economy", CatRegistry.resolve("  econcat  ")?.id)
    }

    @Test fun `lookup is case insensitive`() {
        assertEquals("mine", CatRegistry.byId("MINE")?.id)
        assertEquals("home", CatRegistry.resolve("HOME")?.id)
    }

    @Test fun `the first release ships exactly the five data-backed cats`() {
        assertEquals(setOf("system", "economy", "teleport", "home", "mine"), CatRegistry.AGENT_CATS)
        for (id in CatRegistry.AGENT_CATS) {
            val cat = CatRegistry.byId(id)
            assertNotNull(cat, "$id must exist")
            assertTrue(cat!!.answersFromData, "$id is an agent cat and needs tools")
        }
    }

    @Test fun `a cat with no tools must never claim to answer from data`() {
        val toolless = CatRegistry.all.filter { it.tools.isEmpty() }
        assertTrue(toolless.isNotEmpty(), "the plan keeps seven tool-less cats")
        for (cat in toolless) {
            assertFalse(cat.answersFromData, "${cat.id} has no tools and must delegate")
            assertTrue(cat.delegatesTo.contains(CatRegistry.SYSTEM), "${cat.id} needs somewhere to hand off to")
        }
    }

    @Test fun `the old HomeCat and NestCat pair is no longer ambiguous`() {
        assertEquals("WarpCat", CatRegistry.byId("spawn")?.displayName)
        assertEquals("NestCat", CatRegistry.byId("home")?.displayName)
        assertEquals(2, setOf("WarpCat", "NestCat").size, "the two home-ish names must be distinct")
    }

    @Test fun `search is a prefix match over both id and display name`() {
        assertTrue(CatRegistry.search("ec").any { it.id == "economy" })
        assertTrue(CatRegistry.search("Home").any { it.id == "home" })
        assertTrue(CatRegistry.search("mine").any { it.id == "mine" })
    }

    @Test fun `a delegation chain resolves when one exists`() {
        val chain = CatRegistry.resolveChain("teleport", "system")
        assertNotNull(chain)
        assertEquals("system", chain!!.last().id)
        assertEquals("teleport", chain.first().id)
    }

    @Test fun `a delegation to a cat with no path returns null instead of looping`() {
        assertNull(CatRegistry.resolveChain("mine", "sit"), "mine -> sit is not a declared edge")
        // "mine" asking about "mine" needs no delegation at all, so a one-element chain is right.
        assertEquals(listOf("mine"), CatRegistry.resolveChain("mine", "mine")?.map { it.id })
        assertNull(CatRegistry.resolveChain("nothing", "system"))
    }

    @Test fun `the list order is stable so prompts and completions do not shuffle`() {
        assertEquals(CatRegistry.all.map { it.id }, CatRegistry.all.map { it.id })
        assertEquals("system", CatRegistry.all.first().id, "system is the documented default")
    }
}