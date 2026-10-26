package com.meowo.ui

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every language key `ui.yml` names must exist in **both** language bundles.
 *
 * ## Why this is a separate test from the Kotlin one
 *
 * `LanguageKeyExistsTest` in `platform` scans the **Kotlin** sources for `send(sender, "key", …)`
 * and checks the keys it finds. It cannot see `ui.yml`, because that file names its keys in YAML —
 * and it lives in `ui`, whose rule for "is this a key or literal text?" is
 * [UIText.isKey]. Duplicating that rule in the `platform` test would give two answers to one
 * question, and the one that drifts is the one nobody notices.
 *
 * So the check lives here, in the module that owns the rule, and it asks the module's own code.
 *
 * ## What is checked
 *
 * Only the places a screen actually *says* something: the title, a cell's name, a cell's lore, and
 * the rail and plinth labels. The rest of the file is structure — slot numbers, icons, commands,
 * built-in `@` actions — and none of it is ever rendered as text.
 *
 * ## Why a missing key is worth a failing build
 *
 * [UIText.resolve] resolves a key through the bundle and a literal through the ampersand
 * serialiser, and it tells them apart by **shape**. A key that is not in the bundle does not throw:
 * `Messages.text` falls back to the raw key, so the button reads `pomodoro-action-start` to the
 * player — the key, in English, on a Chinese server, with no error anywhere. That is the failure
 * this test exists to prevent.
 */
class UILanguageKeyTest {

    /** The shipped screen file, next to this module's sources. */
    private val uiFile = File("src/main/resources/ui.yml")

    /** The bundles live in `core`, one level up. */
    private val bundles = listOf(
        "zh_CN" to File("../core/src/main/resources/lang/zh_CN.yml"),
        "en_US" to File("../core/src/main/resources/lang/en_US.yml"),
    )

    private fun layout(): UILayout {
        assertTrue(uiFile.exists(), "missing ui.yml: ${uiFile.absolutePath}")
        uiFile.inputStream().use { return UILoader.load(it, null) }
    }

    /** Every value the screens actually show, in the order a reader would meet them. */
    private fun displayedText(): List<String> = layout().screens.values.flatMap { screen ->
        buildList {
            add(screen.title)
            add(screen.railName)
            add(screen.railLore)
            add(screen.plinthName)
            add(screen.plinthLore)
            screen.buttons.values.forEach { button ->
                add(button.name)
                addAll(button.lore)
            }
        }.filter { it.isNotBlank() }
    }

    private fun declared(locale: File): Set<String> {
        assertTrue(locale.exists(), "missing bundle: ${locale.path}")
        val yaml = YamlConfiguration()
        yaml.load(locale.reader(Charsets.UTF_8))
        // getKeys(false) walks the whole tree, so nested keys are covered too.
        return yaml.getKeys(false).toSet()
    }

    @Test fun `every language key the screens name exists in both bundles`() {
        val referenced = displayedText().filter { UIText.isKey(it) }.toSet()
        assertTrue(referenced.isNotEmpty(), "the scan found nothing; either ui.yml has no text or the rule changed")

        for ((name, file) in bundles) {
            val missing = referenced - declared(file)
            assertTrue(
                missing.isEmpty(),
                "$name is missing ${missing.size} key(s) ui.yml names: ${missing.sorted()}. " +
                    "A key that is not in the bundle is shown to the player as the key itself, " +
                    "with no error anywhere.",
            )
        }
    }

    @Test fun `the screens really do name language keys rather than only literal text`() {
        // The guard on the guard: if every value were literal text the check above would pass for the
        // wrong reason, and the day someone replaced a key with a literal nobody would notice.
        val keys = displayedText().filter { UIText.isKey(it) }
        assertTrue(keys.isNotEmpty(), "no screen names a language key, so nothing is being translated")
    }
}
