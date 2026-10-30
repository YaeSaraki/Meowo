package com.meowo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The shipped `config.yml` and the code that reads it cannot drift apart.
 *
 * ### Why this is a gate and not a comment
 *
 * A configuration file has exactly one reader, and that reader is the plugin. The plugin is written
 * in Kotlin and the file is written in YAML, in two different places, by two different hands — and
 * nothing in the build connects them. So they drift, and the drift is always quiet:
 *
 * | what drifts | what the operator sees |
 * |---|---|
 * | the code reads a key the file does not ship | a crash at start-up: `内置 config.yml 缺少配置项 …` |
 * | the file ships a key nobody reads | an option that is documented, present, and does nothing |
 * | a context exists in Gradle but not in the file | a feature that never starts, with no error anywhere |
 * | a context exists in the file but not in Gradle | the same, in the other direction |
 *
 * The third and fourth rows are the ones this gate exists for. The context list used to be written
 * down in three places — the YAML, `settings.gradle.kts`, and the runtime that enables them — and a
 * context added to two of them was silently absent from the third. `GlobalConfig` even claimed that
 * "the build gate checks that the YAML and the Gradle settings agree". It did not: the rule read as
 * enforced and was not, which is the worse half.
 *
 * So the list is **derived**, never written down:
 *
 * - the contexts the plugin ships → the `contexts:` block of the shipped file;
 * - the contexts that exist → the `contexts:meowo-*` modules in `settings.gradle.kts`;
 * - the contexts that actually run → the `in next.contexts` branches in `MeowoRuntime`;
 * - the options the code reads → the accessor calls in the config package;
 * - the options the file offers → the keys it declares.
 *
 * Every list is read from the tree, so a list added later is checked without anyone editing this
 * file. A hand-written list would be the same mistake this gate is about.
 *
 * ## The scan is a heuristic, and it errs towards failing
 *
 * The paths the code reads are found by matching the accessor calls in the config package. That
 * misses a path built at runtime — `contexts.$it` is the only one, and it is checked separately —
 * and it could in principle match a call that is not a config read. Both are acceptable directions:
 * a false *failure* is a nuisance somebody fixes in a minute, while a false *pass* is the silent
 * drift this file exists to prevent.
 */
class ConfigSchemaTest {

    /** The shipped file. Gradle runs this module's tests with `core/` as the working directory. */
    private val shippedFile = File("src/main/resources/config.yml")

    /** Where the context list is written down twice more. */
    private val settingsFile = File("../settings.gradle.kts")
    private val runtimeFile = File("src/main/kotlin/com/meowo/core/MeowoRuntime.kt")

    /** The package that owns every config read. */
    private val configPackage = File("src/main/kotlin/com/meowo/core/config")

    /** Every module's main sources, so a config read outside the config package is still found. */
    private val moduleRoots = listOf(".", "../api", "../shared", "../platform", "../ui", "../contexts")

    // ────────────────────────────────────────────────────────────────────────────
    // The shipped file, read as text
    // ────────────────────────────────────────────────────────────────────────────

    /** One `key:` line of the shipped file. */
    private data class Declared(val path: String, val isSection: Boolean)

    /**
     * Every key the file declares, with the path it lives at.
     *
     * Read from the **text** rather than from a parsed YAML tree, because a parsed tree cannot
     * tell "this key has an empty value" from "this key is not here". `chat.emoji` ships as an
     * empty block — the only thing under it is a commented-out example — and a parser that drops
     * null values would report it as missing, which is exactly the false alarm this avoids.
     *
     * The format this file uses is the one its own comments describe: two-space indentation, one
     * `key: value` or `key:` per line, `- ` for list items. A construct outside that vocabulary
     * would be misread, so a misread shows up as a failing gate rather than as a silent pass.
     */
    private fun declared(): List<Declared> {
        val out = mutableListOf<Declared>()
        val stack = mutableListOf<Pair<Int, String>>()
        val lines = shippedFile.readLines()
        lines.forEachIndexed { index, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
            // A list item is the value of the key above it, not a key of its own.
            if (trimmed.startsWith("- ")) return@forEachIndexed
            val colon = trimmed.indexOf(':')
            if (colon < 0) return@forEachIndexed
            val indent = raw.length - raw.trimStart().length
            val key = trimmed.substring(0, colon).trim().trim('"', '\'')
            val inline = trimmed.substring(colon + 1).trim()
            // A key with no value of its own is a **block** — unless what follows it is a list
            // item, in which case the list is the value and the key is a leaf.
            val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
            val isListValue = next != null && next.trimStart().startsWith("- ")
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.size - 1)
            val path = (stack.map { it.second } + key).joinToString(".")
            out += Declared(path, isSection = inline.isEmpty() && !isListValue)
            stack += (indent to key)
        }
        return out
    }

    // ────────────────────────────────────────────────────────────────────────────
    // The code, read as text
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * The config paths the source actually reads.
     *
     * Two scans, because the calls come in two shapes and the shapes do not overlap safely:
     *
     * - `resolver.text("…")` — found anywhere in the tree, because a resolver used from another
     *   module is still a config read;
     * - the bare `text("…")` helpers — found **only in the config package**, because the same
     *   names exist elsewhere for entirely different things. `Component.text("✉", …)` in the chat
     *   module is not a config read, and a scan that cannot tell the difference would report it as
     *   a missing option.
     */
    private fun readPaths(): Set<String> {
        val names = "text|bool|int(?:eger)?|decimal|offset|forward|stringList|section|shippedKeys"
        val prefixed = Regex("""resolver\.($names)\(\s*"([^"]*)"""")
        // The lookbehind keeps `resolver.text(…)` out of the bare scan: the dot before the name
        // means the call is the prefixed one, already found.
        val bare = Regex("""(?<![\w.])($names)\(\s*"([^"]*)"""")
        val out = mutableSetOf<String>()
        for (file in sourceFiles()) {
            prefixed.findAll(file.readText()).forEach { out += it.groupValues[2] }
        }
        for (file in configPackage.listFiles { f -> f.extension == "kt" }?.toList().orEmpty()) {
            bare.findAll(file.readText()).forEach { out += it.groupValues[2] }
        }
        // A path built at runtime (`contexts.$it`) is checked by hand, not by this scan.
        return out.filterNot { it.contains('$') }.toSet()
    }

    /**
     * Every module's main sources, so a config read outside the config package is still found.
     *
     * The roots are walked rather than pointed at `src/main/kotlin`, because the contexts are not
     * laid out that way: `contexts/` holds `meowo-<name>/src/main/kotlin` one level down, and
     * there is no `contexts/src/main/kotlin` to point at.
     */
    private fun sourceFiles(): List<File> {
        val out = mutableListOf<File>()
        for (root in moduleRoots) {
            val dir = File(root)
            if (!dir.isDirectory) throw IllegalStateException("source root does not exist: ${dir.absolutePath}")
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.path.contains("/build/") }
                // Test sources are excluded: they read the config too, but the ones that matter are
                // the ones the plugin reads it with.
                .filterNot { it.path.contains("/src/test/") }
                .filter { it.path.contains("/src/main/kotlin/") }
                .forEach { out += it }
        }
        return out
    }

    /**
     * The sections whose keys are **data**, not options.
     *
     * `shop.prices` is a price per material and `chat.emoji` is a name per character: their keys
     * are chosen by the operator, so "nobody reads this key" is not a defect there. The list is
     * read from [ConfigResolver] rather than copied here — a copy would be another list that can
     * drift, which is the thing this gate is about.
     */
    private fun openSections(): Set<String> {
        val source = File(configPackage, "ConfigResolver.kt").readText()
        val block = Regex("""openSections[^=]*=\s*setOf\(([^)]*)\)""").find(source)
            ?: throw IllegalStateException("could not find the openSections set in ConfigResolver.kt")
        return Regex(""""([^"]+)"""").findAll(block.groupValues[1]).map { it.groupValues[1] }.toSet()
    }

    // ────────────────────────────────────────────────────────────────────────────
    // The gates
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * The context list is written down in the file and in `settings.gradle.kts`. They have to be
     * the same list, because a context in one and not the other is a feature that never starts.
     */
    @Test fun `the contexts the plugin ships are the contexts Gradle builds`() {
        val shipped = declared().filter { it.path.startsWith("contexts.") }
            .map { it.path.removePrefix("contexts.") }.toSet()
        val gradle = Regex("""contexts:meowo-([a-z]+)""")
            .findAll(settingsFile.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(shipped.isNotEmpty(), "the shipped file declares no contexts; the scan is broken")
        assertTrue(gradle.isNotEmpty(), "settings.gradle.kts declares no contexts; the scan is broken")

        assertEquals(
            gradle, shipped,
            "the `contexts:` block of the shipped config.yml and the `contexts:meowo-*` modules in " +
                "settings.gradle.kts are different lists. A context in one and not the other is a " +
                "feature that never starts, and nothing says so at runtime. " +
                "only in the file: ${shipped - gradle}; only in Gradle: ${gradle - shipped}",
        )
    }

    /**
     * A context the file offers but the runtime never enables is the same defect with the file
     * alone as the witness.
     */
    @Test fun `every context the file offers is enabled by the runtime`() {
        val shipped = declared().filter { it.path.startsWith("contexts.") }
            .map { it.path.removePrefix("contexts.") }.toSet()
        val wired = Regex(""""([a-z][a-z-]*)"\s+in\s+next\.contexts""")
            .findAll(runtimeFile.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(wired.isNotEmpty(), "no context is enabled by the runtime; the scan is broken")

        val never = shipped - wired
        assertTrue(
            never.isEmpty(),
            "the config.yml offers ${never.sorted()} but MeowoRuntime never enables " +
                "them (`\"<name>\" in next.contexts`). A context nobody enables is an option that " +
                "does nothing.",
        )
    }

    /**
     * Every path the code reads has to exist in the shipped file.
     *
     * The reader does not fall back for a missing key: `ConfigResolver.shippedValue` throws, so a
     * key the code reads and the file does not ship takes the whole plugin down at start-up. That
     * is a loud failure, and it belongs in the build rather than on a running server.
     */
    @Test fun `every config key the code reads is shipped`() {
        val declared = declared().map { it.path }.toSet()
        val read = readPaths()

        assertTrue(read.isNotEmpty(), "the scan found no config reads; the pattern is broken")

        val missing = read - declared
        assertTrue(
            missing.isEmpty(),
            "the code reads ${missing.sorted()} but the shipped config.yml does not declare them. " +
                "A key the file does not ship makes the plugin throw at start-up " +
                "(\"内置 config.yml 缺少配置项 …\"). Either the key belongs in the file or the read " +
                "belongs in the code.",
        )
    }

    /**
     * Every option the file offers has to be read by somebody.
     *
     * The other direction, and the one an operator feels: a key that is documented, shipped and
     * explained — and then never read — looks exactly like a setting that silently does nothing.
     */
    @Test fun `every option the file offers is read by the code`() {
        val open = openSections()
        val version = "config-version"
        val read = readPaths()

        val unread = declared()
            .filter { !it.isSection }
            .map { it.path }
            .filter { it != version }
            // The context list is read one key at a time, by name, at runtime.
            .filterNot { it.startsWith("contexts.") }
            .filterNot { path -> open.any { path == it || path.startsWith("$it.") } }
            .filterNot { it in read }

        assertTrue(
            unread.isEmpty(),
            "the shipped config.yml offers ${unread.sorted()} and nothing reads them. An option " +
                "that is shipped and ignored is a setting that looks real and does nothing. " +
                "Either the code reads it or the file does not ship it.",
        )
    }

    /**
     * The version number lives in the file and nowhere else.
     *
     * It used to live in the file **and** in a `const val` next to the merger, and the two were
     * free to disagree. Adding a key and bumping only the YAML left the constant behind, so the
     * merger believed every file was already current and added nothing — the new option never
     * reached anybody's config file, and nothing failed. One number, one place, and this test
     * says so out loud.
     */
    @Test fun `the version number is written down once`() {
        val merger = File(configPackage, "ConfigVersionMerger.kt").readText()
        // Anchored to the start of a line and followed by `=`, so that a **mention** of the old
        // constant in the prose cannot trip the check — only a second declaration can.
        assertTrue(
            !Regex("""(?m)^\s*const\s+val\s+CURRENT_VERSION\s*=""").containsMatchIn(merger),
            "ConfigVersionMerger declares a CURRENT_VERSION constant again. The version lives in " +
                "the shipped config.yml; a second copy can disagree with the first, and when it " +
                "does, new keys silently stop reaching operators' files.",
        )
        assertTrue(
            shippedFile.readText().contains("config-version:"),
            "the shipped config.yml does not declare a config-version; the merger cannot tell " +
                "whether a file is current.",
        )
    }

    /**
     * The version is **global**, and that is a claim about one number governing several files.
     *
     * It holds only while there is exactly one declaration of it. A `config-version` in the shipped
     * `ui.yml` would be a second number that could be bumped on its own — and the day the two
     * disagree, the files stop moving together, which is the bug this whole arrangement exists to
     * prevent.
     *
     * So the shipped `config.yml` declares the version and the other shipped file does not. The
     * operator's copies each *record* the version they were synced to; that is a record, not a
     * second source.
     */
    @Test fun `the version is declared once and governs every versioned file`() {
        val uiFile = File("../ui/src/main/resources/ui.yml")
        assertTrue(uiFile.isFile, "the shipped layout file is missing: ${uiFile.absolutePath}")

        assertTrue(
            !Regex("""(?m)^\s*config-version\s*:""").containsMatchIn(uiFile.readText()),
            "the shipped ui.yml declares a config-version. The version is one number for the whole " +
                "configuration and it is declared in the shipped config.yml; a second declaration " +
                "is a second source that can disagree with the first.",
        )
        // The two files the operator can edit, and the runtime has to bring both up to the version.
        val runtime = runtimeFile.readText()
        for (resource in listOf("/config.yml", "/ui.yml")) {
            assertTrue(
                runtime.contains("\"$resource\""),
                "MeowoRuntime never syncs $resource, so the version does not govern it. A file " +
                    "outside the version is a file that silently falls behind.",
            )
        }
    }
}
