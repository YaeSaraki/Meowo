package com.meowo.core

import com.meowo.core.config.ConfigVersionMerger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The rule that makes a config file trustworthy across an update: **add what is missing, change nothing else.**
 *
 * Every case here is a way that rule could be broken quietly. The dangerous one is the second: an operator who
 * tuned a value must still have it after the plugin writes to their file, because the moment a version bump
 * can reset a setting is the moment nobody edits the config again.
 */
class ConfigVersionMergerTest {

    private val shipped = """
        # shipped header
        config-version: 9
        locale: zh_CN
        teleport:
          # how long a request stays open
          request-seconds: 60
          particles: true
        pomodoro:
          # stay time for the on-screen title
          title-refresh-seconds: 5
          brand-new-key: "fresh"
    """.trimIndent()

    private fun operator(body: String): String = body.trimIndent()

    private fun merge(file: File) =
        ConfigVersionMerger.merge(file, shipped.byteInputStream())

    private fun read(file: File) = org.bukkit.configuration.file.YamlConfiguration().apply { load(file) }

    @Test fun `a missing key is written in with its value`(@TempDir dir: File) {
        val file = File(dir, "config.yml")
        file.writeText(operator("locale: en_US"))

        val result = merge(file)

        assertTrue(result.added.contains("teleport.request-seconds"), "the new nested key must be added: ${result.added}")
        assertEquals(60, read(file).getInt("teleport.request-seconds"))
    }

    @Test fun `a value the operator changed is never overwritten`(@TempDir dir: File) {
        val file = File(dir, "config.yml")
        file.writeText(operator("locale: en_US\nteleport:\n  request-seconds: 300"))

        merge(file)

        // The whole point. A version bump must not be a way to lose a tuned setting.
        assertEquals(300, read(file).getInt("teleport.request-seconds"), "the operator's value must survive")
        assertEquals("en_US", read(file).getString("locale"), "an unrelated override must survive too")
    }

    @Test fun `an added key carries its comment`(@TempDir dir: File) {
        val file = File(dir, "config.yml")
        file.writeText(operator("locale: en_US"))

        merge(file)

        val comments = read(file).getComments("teleport.request-seconds")
        assertTrue(
            comments.any { it.contains("how long a request stays open") },
            "an added key must arrive explained, or it is an option nobody edits: $comments",
        )
    }

    @Test fun `the version is stamped after a merge`(@TempDir dir: File) {
        val file = File(dir, "config.yml")
        file.writeText(operator("locale: zh_CN"))

        val result = merge(file)

        assertEquals(0, result.fromVersion, "a file with no version key reads as version 0")
        assertEquals(ConfigVersionMerger.CURRENT_VERSION, read(file).getInt(ConfigVersionMerger.VERSION_KEY))
    }

    @Test fun `a current file is left completely alone`(@TempDir dir: File) {
        val file = File(dir, "config.yml")
        // Current version, and deliberately missing a key: an operator who deleted a line to fall back to
        // the default must not have it added back on the next restart.
        file.writeText(operator("config-version: ${ConfigVersionMerger.CURRENT_VERSION}\nlocale: zh_CN"))
        val before = file.readText()

        val result = merge(file)

        assertFalse(result.changed, "nothing to do means nothing written")
        assertEquals(before, file.readText(), "the file must be byte-identical")
    }

    @Test fun `a file from the future is never rewritten`(@TempDir dir: File) {
        val file = File(dir, "config.yml")
        file.writeText(operator("config-version: 999\nlocale: zh_CN"))
        val before = file.readText()

        val result = merge(file)

        assertFalse(result.changed, "a newer file is not ours to edit")
        assertEquals(before, file.readText())
    }

    @Test fun `merging twice is idempotent`(@TempDir dir: File) {
        val file = File(dir, "config.yml")
        file.writeText(operator("locale: en_US"))

        merge(file)
        val afterFirst = file.readText()
        val second = merge(file)

        assertFalse(second.changed, "the second pass has nothing left to add")
        assertEquals(afterFirst, file.readText(), "a second merge must not rewrite the file")
    }

    @Test fun `an absent file is left for saveDefaultConfig to create`(@TempDir dir: File) {
        val file = File(dir, "config.yml")

        val result = merge(file)

        assertFalse(file.exists(), "the merger must not create the file; saveDefaultConfig owns that")
        assertFalse(result.changed)
    }

    @Test fun `a scalar the operator put where a section now lives is not clobbered`(@TempDir dir: File) {
        val file = File(dir, "config.yml")
        // The operator has `teleport` as a plain string. Recursing in would have to overwrite it.
        file.writeText(operator("locale: zh_CN\nteleport: \"my own thing\""))

        val result = merge(file)

        assertEquals("my own thing", read(file).getString("teleport"), "their value must survive")
        // And it must be reported rather than swallowed: the new sub-keys under it did NOT arrive, and
        // somebody has to decide what that key is now.
        assertTrue(result.skipped.contains("teleport"), "the shape mismatch must be reported: ${result.skipped}")
        assertTrue(
            result.added.none { it.startsWith("teleport.") },
            "nothing under a clobbered key may be claimed as added: ${result.added}",
        )
    }
}
