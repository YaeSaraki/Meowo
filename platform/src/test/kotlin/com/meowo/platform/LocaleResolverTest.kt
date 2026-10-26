package com.meowo.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Unit tests for the locale resolution rules. These run without MockBukkit on purpose: this is the
 * logic that silently hands a player the wrong language when it is wrong, so it is checked
 * directly rather than only through the end-to-end suite in `core`.
 */
class LocaleResolverTest {

    @Test fun `languageOf reduces any regional tag to its language`() {
        assertEquals("en", LocaleResolver.languageOf("en_us"))
        assertEquals("en", LocaleResolver.languageOf("en"))
        assertEquals("en", LocaleResolver.languageOf("EN-GB"))
        assertEquals("zh", LocaleResolver.languageOf("zh_hans_cn"))
        assertNull(LocaleResolver.languageOf(""))
        assertNull(LocaleResolver.languageOf("_us"), "a tag with no language part is unusable")
    }

    @Test fun `a bare English locale still reaches the English bundle`() {
        // Regression: Locale.ENGLISH reports the tag "en" with no region, so a strict equals
        // never matched "en_US" and English clients silently kept receiving Chinese.
        assertEquals("en", LocaleResolver.tagOf(Locale.ENGLISH))
        assertEquals("en_US", LocaleResolver.pick(LocaleResolver.tagOf(Locale.ENGLISH)))
    }

    @Test fun `every region variant of a shipped language resolves to that language`() {
        for (tag in listOf("en_us", "en_gb", "en_ca", "en_au", "en_nz", "en", "en_gb_wtf")) {
            assertEquals("en_US", LocaleResolver.pick(tag), "$tag should read the English bundle")
        }
        for (tag in listOf("zh_cn", "zh_hans_cn", "zh_hans", "zh", "zh_hk", "zh_tw")) {
            assertEquals("zh_CN", LocaleResolver.pick(tag), "$tag should read the Chinese bundle")
        }
    }

    @Test fun `an unshipped language returns null so the caller can apply its default`() {
        assertNull(LocaleResolver.pick("fr_fr"), "a missing locale must not guess")
        assertNull(LocaleResolver.pick("ja_jp"))
        assertNull(LocaleResolver.pick(null), "a sender without a client language has no preference")
    }

    @Test fun `no region alias points at a locale we do not ship`() {
        // A dead alias silently falls through to the default, which looks like "we support that
        // language" but is not. Keep the table honest.
        val shipped = LocaleResolver.SUPPORTED
        val dead = listOf("zh_tw", "fr", "de", "ja")
        for (tag in dead) {
            val picked = LocaleResolver.pick(tag)
            assertTrue(
                picked == null || picked in shipped,
                "$tag resolved to $picked, which is not a shipped locale",
            )
        }
    }

    @Test fun `the shipped set is the single source of truth for both bundles and validation`() {
        assertEquals(setOf("zh_CN", "en_US"), LocaleResolver.SUPPORTED)
        // Anything offered by pick() must exist as a loadable bundle.
        for (tag in listOf("en", "en_us", "zh", "zh_cn", "zh_hant_cn")) {
            val picked = LocaleResolver.pick(tag)!!
            assertTrue(picked in LocaleResolver.SUPPORTED, "$tag picked a locale with no bundle: $picked")
        }
    }
}
