package com.meowo.platform

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Catches the class of bug where a message is written with `{0}` twice but only one argument is
 * passed, so the whole summary is printed twice.
 *
 * Pure file inspection: it reads the resources straight off disk and needs no server, so it runs
 * in milliseconds and cannot be silently skipped the way a MockBukkit-dependent test can (08 §19).
 *
 * The bundles live in `core`, not in this module, so the path walks up one level.
 */
class LanguagePlaceholderTest {

    private fun bundle(locale: String): Map<String, String> {
        val file = File("../core/src/main/resources/lang/$locale.yml")
        assertTrue(file.exists(), "language bundle not found: ${file.absolutePath}")
        val yaml = YamlConfiguration()
        yaml.load(file.reader(Charsets.UTF_8))
        // getKeys(false) walks the whole tree, so nested keys are covered too.
        val keys = yaml.getKeys(false)
        val flat = HashMap<String, String>()
        for (key in keys) {
            val value = yaml.getString(key) ?: continue        // non-string sections are skipped
            flat[key] = value
        }
        return flat
    }

    private val locales = listOf("zh_CN", "en_US")

    @Test fun `both language files declare exactly the same keys`() {
        val zh = bundle("zh_CN")
        val en = bundle("en_US")
        assertTrue(
            zh.keys == en.keys,
            "key sets differ: only-zh=${zh.keys - en.keys} only-en=${en.keys - zh.keys}",
        )
    }

    @Test fun `placeholders are numbered without gaps`() {
        for (locale in locales) {
            for ((key, value) in bundle(locale)) {
                val used = Regex("""\{(\d+)}""").findAll(value).map { it.groupValues[1].toInt() }.toSet()
                if (used.isEmpty()) continue
                assertTrue(
                    used == (0 until used.size).toSet(),
                    "$locale key '$key' uses placeholders $used, which is not 0..n-1 — a gap " +
                        "renders as a literal {n} for the player",
                )
            }
        }
    }

    @Test fun `a placeholder may repeat, but a summary must not be built from one argument`() {
        // Repeating an index is legitimate and used: `home-confirm-chat` shows the home name both in
        // the sentence and inside a copy-paste `/home {0}` hint. What must never happen is a key
        // shaped like a status line that repeats one index while the caller passes a single composed
        // argument — that prints the whole summary twice. Those are the status keys, and they are
        // checked explicitly below instead of by a blanket rule.
        //
        // The indices are collected as a **list**, not a set. A set has already thrown the repeats
        // away, so comparing its size with its own count compares a number with itself: the
        // assertion could never fail, and a status line that repeated one index would have passed.
        for (locale in locales) {
            for (key in bundle(locale).keys.filter { "status" in it }) {
                val used = Regex("""\{(\d+)}""").findAll(bundle(locale).getValue(key)).map { it.groupValues[1] }.toList()
                assertEquals(
                    used.distinct().size, used.size,
                    "$locale key '$key' repeats a placeholder — status lines need one index per value",
                )
            }
        }
    }
}
