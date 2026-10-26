package com.meowo.platform

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Nobody may bind a `minecraft:chat` sender slot again.
 *
 * Binding one is the tidier-looking way to show a speaker, and it silently puts the brackets around
 * the line: the slot is rendered through the vanilla `chat.type.text`, whose value is `"<%s> %s"`.
 * Players reported messages as "wrapped in `<>`" — and no server-side string ever contained a `<`,
 * which is why sanitising the text changed nothing.
 *
 * The damage is invisible to unit tests: [ChatLine] composes correctly either way, and the bracket
 * only appears once the component is on the wire. So the guard has to sit at the source level. The
 * name is composed and delivered whole instead — see `ChatLine.say` and `Messages.deliver`.
 */
class NoChatTypeDecorationTest {

    private val self = "NoChatTypeDecorationTest.kt"

    // Gradle runs this module's tests with `platform/` as the working directory, so the sibling
    // modules are one level up. `platform`'s own sources are below it.
    private val sourceRoots = listOf(".", "../contexts", "../core", "../api", "../shared")

    /** This file names the banned API on purpose; everything else must not. */
    private fun sources(): List<File> = sourceRoots.flatMap { root ->
        File(root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != self }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    @Test fun `no source binds a chat type`() {
        val offenders = sources().filter { file ->
            file.readLines().any { line ->
                val code = line.trimStart()
                !code.startsWith("*") && !code.startsWith("//") && !code.startsWith("/*") && code.contains("ChatType")
            }
        }.map { it.path }

        assertTrue(
            offenders.isEmpty(),
            "these sources reference ChatType; compose the line instead (ChatLine.say):\n" +
                offenders.joinToString("\n") { "  $it" },
        )
    }
}