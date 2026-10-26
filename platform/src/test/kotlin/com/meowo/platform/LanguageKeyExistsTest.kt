package com.meowo.platform

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every language key the source actually sends must exist in both bundles.
 *
 * `Messages.text` falls back to the raw key instead of throwing, so a missing key is **silent**:
 * the build stays green and the player just reads `nametag-afk-on` as a sentence. That is exactly
 * the trap in 08 §2, and it is how a bulk edit of the bundles once deleted a live key without any
 * test noticing.
 *
 * The scan is a heuristic — it reads the second argument of the send-shaped calls — so it errs
 * towards false positives rather than false negatives. Call sites that pass a key the caller
 * supplies the text for (the help page keys its output as `help`) are excluded by path.
 *
 * ## `ui.yml` is checked elsewhere, on purpose
 *
 * The screens name their keys in **YAML**, not in Kotlin, so this scan cannot see them — and the
 * rule that tells a key from literal text belongs to the `ui` module
 * (`UIText.isKey`), not to a copy of it here. The check therefore lives in
 * `ui/src/test/kotlin/com/meowo/ui/UILanguageKeyTest.kt`, which asks the `ui` module's own code
 * which values are keys and checks them against the same two bundles.
 */
class LanguageKeyExistsTest {

    private val langFiles = listOf(
        "zh_CN" to File("../core/src/main/resources/lang/zh_CN.yml"),
        "en_US" to File("../core/src/main/resources/lang/en_US.yml"),
    )

    // Gradle runs this module's tests with `platform/` as the working directory, so the sibling
    // modules are one level up. `platform`'s own sources are below it.
    private val sourceRoots = listOf("../core", ".", "../contexts", "../api", "../shared")

    /** send(sender, "key", …) and friends. Deliberately simple — see the class comment. */
    private val callPattern =
        Regex("""(?:send|sendText|actionBar|component|text|notifyBoth|action)\s*\([^,()]*,\s*"([a-z0-9][a-z0-9_-]*)"""")

    private fun referencedKeys(): Set<String> {
        val keys = mutableSetOf<String>()
        for (root in sourceRoots) {
            val dir = File(root)
            if (!dir.isDirectory) {
                throw IllegalStateException("source root does not exist: ${dir.absolutePath}")
            }
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && !it.path.contains("/build/") }
                // Test sources are excluded: this very file matches its own scan pattern.
                .filterNot { it.path.contains("/src/test/") }
                // Config parses `locale` and `language-mode`, whose second argument is a default
                // value rather than a language key; HelpPage keys output the caller supplies.
                .filterNot { it.path.contains("config/GlobalConfig.kt") || it.name == "HelpPage.kt" }
                .forEach { file ->
                    callPattern.findAll(file.readText()).forEach { keys += it.groupValues[1] }
                }
        }
        return keys
    }

    private fun declared(locale: File) =
        Regex("^([a-z0-9_-]+):", RegexOption.MULTILINE)
            .findAll(locale.readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test fun `every key the source sends exists in both language bundles`() {
        val referenced = referencedKeys()
        assertTrue(referenced.isNotEmpty(), "the scan found nothing; the pattern is broken")

        for ((name, file) in langFiles) {
            assertTrue(file.exists(), "missing bundle: ${file.path}")
            val missing = referenced - declared(file)
            assertTrue(
                missing.isEmpty(),
                "$name is missing ${missing.size} key(s) the source sends: ${missing.sorted()}. " +
                    "Messages.text falls back to the raw key, so this fails silently for players.",
            )
        }
    }
}