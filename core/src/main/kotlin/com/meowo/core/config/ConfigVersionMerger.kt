package com.meowo.core.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStream

/**
 * Brings an operator's `config.yml` up to the shipped version **without touching the values they set**.
 *
 * ### The problem this solves
 *
 * `plugin.saveDefaultConfig()` only writes the file when it is missing, so a server that has been running for a
 * month keeps the config it started with. When a release adds a key, that key is simply absent — and every
 * reader here supplies its own fallback, so the plugin keeps working on the default while the operator has no
 * idea the option exists. There is no error and nothing in the log. The key is only discovered by reading the
 * update notes, which is exactly the moment somebody stops trusting their own config.
 *
 * ### The rule
 *
 * On start, if `config-version` in the file is behind [CURRENT_VERSION], every key the shipped file has and
 * the operator's does not is **written in with its comment**, and the version is then stamped. A key the
 * operator already has is never read, never compared and never rewritten — their value and their comment both
 * survive, because a version bump must not be a way to lose a tuned setting.
 *
 * That is the whole contract: **add what is missing, change nothing else.** Writing back a merged file that
 * also "refreshed" known keys would be a silent reset of every server that customised anything.
 *
 * ### Why it is not a diff-and-patch
 *
 * Deleting a key cannot be represented here at all, and that is intentional. A removed option is worth a line in
 * the log and a word in the release notes; silently deleting a line out of somebody's config file is not
 * something a plugin should do to a file that also holds their API key and their prices.
 */
object ConfigVersionMerger {

    /** Bumped whenever a key is added to the shipped `config.yml`. One line per release that adds one. */
    const val CURRENT_VERSION = 4

    /** The key the version lives under. Namespaced so it cannot collide with a real option. */
    const val VERSION_KEY = "config-version"

    /**
     * What a merge did, for the log line and for tests.
     *
     * @param added the keys written in, dotted.
     * @param fromVersion the version the file carried before, or 0 when it had none.
     * @param skipped paths where the operator's file has a different shape than the shipped one. Kept apart
     *   from [added] because these were **not** written, and a log line that lists them as added would be
     *   claiming a change that did not happen.
     * @param wrote whether the file was actually written. Distinguishes "there was nothing to do" from
     *   "we looked and decided not to": an absent file and a file from the future both report their own
     *   version, but neither was touched, and the log must not claim otherwise.
     */
    data class Result(
        val added: List<String>,
        val fromVersion: Int,
        val skipped: List<String> = emptyList(),
        val wrote: Boolean = false,
    ) {
        val changed: Boolean get() = wrote
    }

    /**
     * Merges [shipped] into the operator's [file] when the file is behind.
     *
     * Returns without writing anything when the file is absent — `saveDefaultConfig()` owns creating it, and
     * writing a fresh copy here would race it. Returns without writing when the file is already current: an
     * operator who deliberately deleted a key to fall back to the default should not have it added back on
     * every restart, which is exactly what an unconditional "add everything missing" would do.
     */
    fun merge(file: File, shipped: InputStream): Result {
        if (!file.isFile) return Result(emptyList(), 0, wrote = false)
        val defaults = YamlConfiguration.loadConfiguration(shipped.reader(Charsets.UTF_8))
        val current = YamlConfiguration().apply { load(file) }

        val fromVersion = if (current.isSet(VERSION_KEY)) current.getInt(VERSION_KEY) else 0
        // A file from the future is left completely alone. Downgrading a server, or hand-editing the number
        // upwards to skip a migration, must not result in us rewriting a file newer than the code understands.
        if (fromVersion >= CURRENT_VERSION) return Result(emptyList(), fromVersion, wrote = false)
        val root = defaults.getConfigurationSection("") ?: return Result(emptyList(), fromVersion, wrote = false)

        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        mergeSection(root, current, "", added, skipped)

        current.set(VERSION_KEY, CURRENT_VERSION)
        current.options().copyHeader(true)
        current.options().copyDefaults(false)
        current.save(file)

        return Result(added, fromVersion, skipped, wrote = true)
    }

    /**
     * Copies keys [from] has and [into] does not, recursing into sections.
     *
     * `getKeys(false)` rather than `getKeys(true)` so a section is walked once, at its own depth, and the
     * ordering stays the file's own. A key is "missing" when `isSet` says so, which is what makes an operator
     * who deliberately set a key to the shipped default still count as having it — `isSet` asks whether the
     * path resolves, not whether it differs.
     */
    private fun mergeSection(
        from: ConfigurationSection,
        into: ConfigurationSection,
        prefix: String,
        added: MutableList<String>,
        skipped: MutableList<String>,
    ) {
        for (key in from.getKeys(false)) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val child = from.getConfigurationSection(key)
            if (child != null) {
                val existing = if (into.isSet(key)) into.getConfigurationSection(key) else null
                when {
                    // The operator has a block where we now expect one: recurse, adding only what inside it
                    // is missing.
                    existing != null -> mergeSection(child, existing, path, added, skipped)
                    // The operator has this key in **some other shape** — most often a plain scalar where the
                    // shipped file now has a section. Recursing would mean `createSection` over their value,
                    // which silently replaces it with an empty block. Leaving it alone costs us the new
                    // nested keys under it; overwriting it costs them their setting. So it is left alone,
                    // and the mismatch is reported so it is visible rather than silent.
                    into.isSet(key) -> skipped += path
                    // Absent entirely: build the section and fill it.
                    else -> {
                        added += path
                        mergeSection(child, into.createSection(key), path, added, skipped)
                    }
                }
                continue
            }
            if (into.isSet(key)) continue
            // The comment travels with the value. A key that arrives with no explanation is a key nobody
            // edits, which is the same invisible-default problem one release later.
            into.set(key, from.get(key))
            val comments = from.getComments(key)
            if (comments.isNotEmpty()) into.setComments(key, comments)
            added += path
        }
    }
}
