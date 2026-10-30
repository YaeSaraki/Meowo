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
 * On start, if `config-version` in the file is behind the version the **shipped** `config.yml` declares
 * (see [currentVersion]), every key the shipped file has and the operator's does not is **written in with its
 * comment**, and the version is then stamped. A key the operator already has is never read, never compared and
 * never rewritten — their value and their comment both survive, because a version bump must not be a way to
 * lose a tuned setting.
 *
 * That is the whole contract: **add what is missing, change nothing else.** Writing back a merged file that
 * also "refreshed" known keys would be a silent reset of every server that customised anything.
 *
 * ### Where the version number lives
 *
 * **In the shipped `config.yml`, and nowhere else.** The number is read from the file it versions, so the
 * two can never drift apart: there is no second copy left to forget when a key is added.
 *
 * ### One number for the whole configuration
 *
 * The plugin ships **two** files the operator can edit: `config.yml` (the options) and `ui.yml` (the
 * menu layouts). Both are governed by the same version, and both are brought up to it.
 *
 * That is the point of a *global* version. When only `config.yml` had one, the version meant "the
 * options are at version N", and a release that added a button to a menu screen changed the plugin's
 * configuration without changing the version that governs it. The button then never reached a server
 * that had its own `ui.yml`, because the loader lets the operator's screen win outright — so the
 * layout silently stayed behind, with nothing in the log to say so.
 *
 * The language bundles are deliberately **not** merged, and that is a different contract rather than
 * an oversight: they hold the plugin's wording, not its settings, and the jar's copy already fills
 * every key the operator's file lacks. Merging them would write the shipped wording into the
 * operator's file and freeze it there, so a wording fix in a later release could never reach a
 * server that had the earlier one. A key that is added and never customised is better served by the
 * fallback than by a copy.
 *
 * ### Why it is not a diff-and-patch
 *
 * Deleting a key cannot be represented here at all, and that is intentional. A removed option is worth a line in
 * the log and a word in the release notes; silently deleting a line out of somebody's config file is not
 * something a plugin should do to a file that also holds their API key and their prices.
 */
object ConfigVersionMerger {

    /**
     * The key the version lives under. Namespaced so it cannot collide with a real option.
     *
     * The **value** of that key is not written here: it lives in the shipped `config.yml`, and only
     * there. See [currentVersion].
     */
    const val VERSION_KEY = "config-version"

    /**
     * What a merge did, for the log line and for tests.
     *
     * @param added the keys written in, dotted.
     * @param fromVersion the version the file carried before, or 0 when it had none.
     * @param toVersion the version the file carries now — the one the shipped `config.yml` declares.
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
        val toVersion: Int,
        val skipped: List<String> = emptyList(),
        val wrote: Boolean = false,
    ) {
        val changed: Boolean get() = wrote
    }

    /**
     * The version the **shipped** `config.yml` declares.
     *
     * ### Why this is read rather than written down
     *
     * The version number used to be written in two places: `config-version: 5` in the shipped YAML and
     * a constant named `CURRENT_VERSION` next to this class. Two copies of one number are two facts that
     * can disagree, and the way they disagreed was quiet: adding a key and bumping only the YAML left
     * the constant behind, so the merger saw a file that was "already current" and added nothing —
     * the new option simply never arrived in anybody's config file. Nothing failed. The option was
     * documented, shipped, and invisible.
     *
     * The shipped file is the single place that knows what the plugin's configuration looks like, so
     * it is the single place that knows its version. Bumping it is now one edit in one file, and there
     * is no second copy left to forget.
     */
    private fun currentVersion(shipped: ConfigurationSection): Int =
        shipped.getInt(VERSION_KEY).takeIf { it > 0 }
            ?: error(
                "内置 config.yml 里没有 $VERSION_KEY（或它不是正整数）。" +
                    "版本号是配置文件结构的唯一来源，缺了它插件无法判断该不该补键——这是插件包的缺陷，请报告。",
            )

    /**
     * Merges [shipped] into the operator's [file] when the file is behind.
     *
     * Returns without writing anything when the file is absent — `saveDefaultConfig()` owns creating it, and
     * writing a fresh copy here would race it. Returns without writing when the file is already current: an
     * operator who deliberately deleted a key to fall back to the default should not have it added back on
     * every restart, which is exactly what an unconditional "add everything missing" would do.
     */
    /**
     * The version the plugin's configuration is at, read from the **shipped `config.yml`**.
     *
     * ### One number for the whole configuration
     *
     * The plugin ships two files the operator can edit — `config.yml` (the options) and `ui.yml` (the
     * menu layouts) — and they used to be governed differently: only `config.yml` had a version, so
     * only `config.yml` was ever brought up to date. A release that added a button to a screen left
     * the operator's own `ui.yml` exactly as it was, and since a screen the operator has customized
     * wins **outright** in the loader, that button simply never appeared. The version was not global;
     * it was one file's version wearing a global name.
     *
     * So the number is read from one place — the shipped `config.yml` — and it governs **every**
     * versioned file. The operator's copies each record the version they were synced to, which is
     * what lets one number describe several files: they are all at the same version, because they
     * are all part of the same configuration.
     *
     * @param shipped the shipped `config.yml`. The only file that declares the version.
     */
    fun globalVersion(shipped: InputStream): Int {
        val defaults = YamlConfiguration.loadConfiguration(shipped.reader(Charsets.UTF_8))
        return currentVersion(defaults)
    }

    /**
     * Merges one shipped file into the operator's copy of it, at the plugin's global [version].
     *
     * The version is a parameter rather than something read from [shipped] because the shipped
     * `ui.yml` does not declare one: there is one number for the whole configuration, and it lives
     * in the shipped `config.yml`. Passing it in keeps that fact in one place instead of letting
     * each file claim a version of its own.
     *
     * Returns without writing anything when the file is absent — a file the operator never made is
     * not a file to update. The shipped copy stays the fallback, which is what keeps the layouts
     * evolving for the servers that did not customise them.
     */
    fun merge(file: File, shipped: InputStream, version: Int): Result {
        val defaults = YamlConfiguration.loadConfiguration(shipped.reader(Charsets.UTF_8))
        val target = version
        if (!file.isFile) return Result(emptyList(), 0, target, wrote = false)
        val current = YamlConfiguration().apply { load(file) }

        val fromVersion = if (current.isSet(VERSION_KEY)) current.getInt(VERSION_KEY) else 0
        // A file from the future is left completely alone. Downgrading a server, or hand-editing the number
        // upwards to skip a migration, must not result in us rewriting a file newer than the code understands.
        if (fromVersion >= target) return Result(emptyList(), fromVersion, target, wrote = false)
        val root = defaults.getConfigurationSection("") ?: return Result(emptyList(), fromVersion, target, wrote = false)

        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        mergeSection(root, current, "", added, skipped)

        current.set(VERSION_KEY, target)
        current.options().copyHeader(true)
        current.options().copyDefaults(false)
        current.save(file)

        return Result(added, fromVersion, target, skipped, wrote = true)
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
            // The version number is bookkeeping, not an option. It is stamped separately, and listing it
            // among the added keys would put "added config-version" in the operator's log, which reads
            // like a new setting arrived when nothing about their configuration changed.
            if (path != VERSION_KEY) added += path
        }
    }
}
