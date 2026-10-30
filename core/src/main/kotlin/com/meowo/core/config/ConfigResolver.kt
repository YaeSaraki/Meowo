package com.meowo.core.config

import org.bukkit.configuration.ConfigurationSection
import java.math.BigDecimal

/**
 * Reads an operator's `config.yml` against the copy that shipped in the jar, and reports every
 * discrepancy it finds.
 *
 * ### The two documents
 *
 * - **shipped** — the `config.yml` inside the jar. It holds the **default value** of every option
 *   and the comment that explains it.
 * - **operator** — the file in `plugins/Meowo/`. It holds what the operator actually chose.
 *
 * The shipped file is the single source of truth for defaults. That is the whole point of this
 * class: before it, every default was written **twice** — once as a literal in
 * `GlobalConfig.read()` and once in the YAML — and the two copies were free to disagree. They
 * did. `shadowbox.give-permission` shipped as `true` in the file and defaulted to `false` in the
 * code, so an operator who deleted the line got the *opposite* of the behaviour the file's own
 * comment promised, and the only symptom was that `/shadowbox give` stopped working for them.
 *
 * A default that lives in exactly one place cannot drift. So the YAML says what the option is,
 * and the code asks for it by name.
 *
 * ### What it does with a wrong value
 *
 * It does not throw. A value of the wrong shape falls back to the shipped default and becomes an
 * [Issue]; a value that is the right shape but out of range is caught by the option's own
 * `require` in the context's `Settings` class and repaired the same way — see [guard].
 *
 * The plugin therefore starts with a working configuration no matter what the operator wrote.
 * The alternative — the previous behaviour — meant one wrong number in a 170-key file took the
 * whole server's twenty features down with it, and the stack trace in the console was the only
 * clue that a `chat.max-length` line was to blame.
 *
 * ### What it reports
 *
 * - **unknown keys** — the operator wrote something the plugin does not know (a typo, or a key
 *   from an older release). Without this, such a key is invisible: not an error, not a warning,
 *   just a line that does nothing.
 * - **deprecated keys** — still honoured where a replacement exists, and said out loud.
 * - **wrong types** — a value of the wrong shape, with the default used instead.
 * - **environment overrides** — a secret read from the environment, so that editing the file
 *   appears to do nothing.
 */
class ConfigResolver(
    /** The `config.yml` from the jar: defaults plus comments. */
    private val shipped: ConfigurationSection,
    /** The operator's file, or null when there is none yet. */
    private val operator: ConfigurationSection?,
    /** Where secrets come from. Injectable so a test can supply one. */
    private val environment: (String) -> String? = { System.getenv(it) },
) {

    /** Everything the operator should be told about their file. */
    val issues: MutableList<ConfigIssue> = mutableListOf()

    /**
     * When false, only the shipped defaults are read.
     *
     * This is the repair switch: [guard] flips it off for the one option whose value turned out
     * to be unusable, rebuilds **that** option from the shipped default, and flips it back on.
     * Every other option keeps the value the operator chose.
     */
    var useOperator: Boolean = true
        internal set

    // ────────────────────────────────────────────────────────────────────────────
    // The parts of the contract that are about *this* plugin, not about YAML
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Keys an older release used, and where they went.
     *
     * A null value means the option was **removed**: the key is ignored and the operator is told
     * so, because a line in a config file that does nothing is a small trap — the operator keeps
     * tuning it and keeps seeing no effect.
     *
     * `home.actionbar` was renamed to `home.bossbar`. The old name still works, because a rename
     * that silently stops working is how operators lose the setting they tuned.
     */
    private val renamed: Map<String, String?> = mapOf(
        "home.actionbar" to "home.bossbar",
        "menu.items" to null,
    )

    /**
     * Secrets that may be supplied through the environment instead of the file.
     *
     * A key in the API belongs in a file the operator can edit; a key that belongs to a *machine*
     * belongs in the machine's environment. Putting `MEOWO_AGENTS_API_KEY` in a file that a
     * world-readable backup, a `world/` folder or a git repository can pick up is how that key
     * ends up somewhere it should not be. The file still works — the environment simply wins,
     * and the operator is told which one was used so that editing the file is not a mystery.
     *
     * `webapi.secret` is the same kind of key and was missing here: it is the shared secret the
     * Cloudflare Worker sends in `X-Meow-Secret`, and the web API refuses to start without it, so
     * the only way to supply it used to be editing a YAML file — exactly what this map exists to
     * avoid. Two secrets, two rules, one table: anything that authenticates something belongs here.
     */
    private val secrets: Map<String, String> = mapOf(
        "agents.api-key" to "MEOWO_AGENTS_API_KEY",
        "webapi.secret" to "MEOWO_WEBAPI_SECRET",
    )

    /**
     * Sections the operator may add keys of their own.
     *
     * `shop.prices` is a price per material and `chat.emoji` is a name per character: their keys
     * are data, not options, so "unknown key" does not apply to what is inside them.
     */
    private val openSections: Set<String> = setOf("shop.prices", "chat.emoji")

    // ────────────────────────────────────────────────────────────────────────────
    // Reading
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * The value the plugin will actually use for [path].
     *
     * The order is: environment (for secrets), then the operator's file, then the shipped
     * default. A key the operator never wrote is not an error — the whole point of shipping a
     * default is that most keys are never written.
     */
    fun raw(path: String): Any? = resolve(path, report = true)

    /** The same lookup, but silent — used to render the effective configuration. */
    private fun resolve(path: String, report: Boolean): Any? {
        secrets[path]?.let { variable ->
            environment(variable)?.takeIf { it.isNotBlank() }?.let { value ->
                if (report) issues += ConfigIssue(path, ConfigIssue.Kind.ENV_OVERRIDE, variable)
                return value
            }
        }
        if (useOperator && operator != null) {
            operator.get(path)?.let { return it }
            // A renamed key still works. The old name is honoured only when the new name is
            // absent, so a file that already has both uses the new one.
            renamed.entries.firstOrNull { it.value == path }?.let { (old, _) ->
                operator.get(old)?.let { legacy ->
                    if (report) {
                        issues += ConfigIssue(old, ConfigIssue.Kind.DEPRECATED, "已改名为 $path，仍然生效，但请尽快改名")
                    }
                    return legacy
                }
            }
        }
        return shipped.get(path)
    }

    /** Text. A number or a boolean is accepted and stringified; a block is not. */
    fun text(path: String): String {
        val value = raw(path) ?: return shippedText(path)
        return when (value) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> {
                issues += ConfigIssue(path, ConfigIssue.Kind.WRONG_TYPE, "这里应该是一段文字")
                shippedText(path)
            }
        }
    }

    /** A boolean. `true/false`, `yes/no`, `on/off` and `1/0` are all accepted. */
    fun bool(path: String): Boolean {
        val value = raw(path)
        return when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true", "yes", "on", "1" -> true
                "false", "no", "off", "0" -> false
                else -> {
                    issues += ConfigIssue(path, ConfigIssue.Kind.WRONG_TYPE, "这里应该是 true 或 false")
                    shippedBool(path)
                }
            }
            else -> {
                issues += ConfigIssue(path, ConfigIssue.Kind.WRONG_TYPE, "这里应该是 true 或 false")
                shippedBool(path)
            }
        }
    }

    /** A whole number. A numeric string is accepted; anything else falls back. */
    fun int(path: String): Int {
        val value = raw(path)
        val parsed = when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
        if (parsed == null) {
            issues += ConfigIssue(path, ConfigIssue.Kind.WRONG_TYPE, "这里应该是一个整数")
            return shippedInt(path)
        }
        return parsed
    }

    /**
     * A non-negative amount with at most eight decimals.
     *
     * Amounts are money, so they are read as [BigDecimal] and never as a double: `0.1 + 0.2` is
     * not `0.3` in binary floating point, and a wallet that drifts by a fraction of a coin on
     * every transfer is a wallet nobody trusts.
     */
    fun decimal(path: String): BigDecimal {
        val value = raw(path)
        val text = value?.toString()?.trim()
        if (text == null || !UNSIGNED_DECIMAL.matches(text)) {
            issues += ConfigIssue(path, ConfigIssue.Kind.WRONG_TYPE, "这里应该是一个非负数字，例如 \"12.50\"")
            return shippedDecimal(path)
        }
        return BigDecimal(text)
    }

    /** A signed offset, used where the default is negative. */
    fun offset(path: String): Double {
        val value = raw(path)
        val text = value?.toString()?.trim()
        if (text == null || !SIGNED_DECIMAL.matches(text)) {
            issues += ConfigIssue(path, ConfigIssue.Kind.WRONG_TYPE, "这里应该是一个数字，例如 \"-0.15\"")
            return shippedDouble(path)
        }
        val parsed = text.toDouble()
        if (parsed !in -2.0..2.0) {
            issues += ConfigIssue(path, ConfigIssue.Kind.OUT_OF_RANGE, "必须在 -2 到 2 之间")
            return shippedDouble(path)
        }
        return parsed
    }

    /** A forward offset, which must never be negative. */
    fun forward(path: String): Double {
        val value = raw(path)
        val text = value?.toString()?.trim()
        if (text == null || !UNSIGNED_SMALL.matches(text)) {
            issues += ConfigIssue(path, ConfigIssue.Kind.WRONG_TYPE, "这里应该是一个非负数字，例如 \"0.35\"")
            return shippedDouble(path)
        }
        val parsed = text.toDouble()
        if (parsed !in 0.0..2.0) {
            issues += ConfigIssue(path, ConfigIssue.Kind.OUT_OF_RANGE, "必须在 0 到 2 之间")
            return shippedDouble(path)
        }
        return parsed
    }

    /**
     * A list of strings.
     *
     * A single string where a list belongs is read as a one-element list rather than dropped: the
     * operator wrote `hat.deny: BEDROCK` and meant exactly one entry, and an empty list would be a
     * silent no-op. The mistake is still reported.
     */
    fun stringList(path: String): List<String> {
        val value = raw(path)
        return when (value) {
            is List<*> -> value.map { it?.toString().orEmpty() }
            is String -> {
                issues += ConfigIssue(path, ConfigIssue.Kind.WRONG_TYPE, "这里应该是一个列表，用 - 开头分行")
                listOf(value)
            }
            null -> emptyList()
            else -> {
                issues += ConfigIssue(path, ConfigIssue.Kind.WRONG_TYPE, "这里应该是一个列表")
                emptyList()
            }
        }
    }

    /** A block, or null when there is none. */
    fun section(path: String): ConfigurationSection? {
        val value = raw(path)
        return value as? ConfigurationSection
    }

    /**
     * The keys of a section in the **shipped** file.
     *
     * The set of options the plugin knows about is therefore read from the same place their
     * defaults come from. The context list used to be written down in three places — here, in the
     * YAML, and in `settings.gradle.kts` — and a context added to two of them was silently absent
     * from the third.
     */
    fun shippedKeys(path: String): Set<String> =
        shipped.getConfigurationSection(path)?.getKeys(false)?.toSet() ?: emptySet()

    /** The effective value of [path], rendered for a human. Used by `/meowo config get`. */
    fun effective(path: String): String? {
        val value = resolve(path, report = false) ?: return null
        return render(value)
    }

    /**
     * Every option the plugin knows about, with the value it will actually use.
     *
     * This is what `/meowo config list` prints, and it is the answer to the question a config
     * file cannot answer on its own: **what did the plugin make of what I wrote?** A file shows
     * what was intended; this shows what was understood.
     */
    fun snapshot(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (path in leafPaths(shipped)) {
            val value = resolve(path, report = false)
            out[path] = value?.let { render(it) } ?: ""
        }
        return out
    }

    /** Every leaf path in [section] — the options, not the blocks that hold them. */
    private fun leafPaths(section: ConfigurationSection, prefix: String = ""): List<String> {
        val out = mutableListOf<String>()
        for (key in section.getKeys(false)) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val child = section.getConfigurationSection(key)
            if (child != null) out += leafPaths(child, path) else out += path
        }
        return out
    }

    private fun render(value: Any): String = when (value) {
        is ConfigurationSection -> value.getKeys(false).joinToString(", ") { it }
        is List<*> -> value.joinToString(", ") { it?.toString().orEmpty() }
        else -> value.toString()
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Repairing one broken option without touching the others
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Builds one option, and repairs it if the operator's value makes it impossible.
     *
     * The validation itself lives in the option's own `Settings` class — in the context that
     * owns it, next to the rule it enforces. This method does not duplicate a single range; it
     * only catches the failure and rebuilds that one option from the shipped default.
     *
     * The rebuild reads the **shipped** default for every key the option uses, so an option whose
     * value was `spawn.warmup-seconds: 999` comes back as the shipped `3` while
     * `spawn.cooldown-seconds` keeps whatever the operator set.
     *
     * If the shipped default is *itself* invalid, the file in the jar is broken — that is a
     * build defect, not an operator mistake, and it is raised loudly rather than papered over.
     */
    fun <T> guard(section: String, build: () -> T): T {
        try {
            return build()
        } catch (error: IllegalArgumentException) {
            issues += ConfigIssue(section, ConfigIssue.Kind.OUT_OF_RANGE, error.message ?: "值不在允许范围内")
        } catch (error: java.time.DateTimeException) {
            issues += ConfigIssue(section, ConfigIssue.Kind.WRONG_TYPE, error.message ?: "不是合法的时间/时区")
        }
        val previous = useOperator
        useOperator = false
        try {
            return build()
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException(
                "内置 config.yml 里 $section 的默认值本身不合法：${error.message}。这是插件包的缺陷，请报告。",
                error,
            )
        } finally {
            useOperator = previous
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Auditing the operator's file against the shipped one
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Compares the two documents and records everything the operator should know.
     *
     * Called once, after the file has been read. It answers the question a config file cannot
     * answer by itself: **"did the plugin understand what I wrote?"**
     */
    fun audit() {
        val op = operator ?: return
        val reported = mutableSetOf<String>()

        // 1. Keys the operator wrote that the plugin does not know.
        for ((path, isSection) in walk(op)) {
            if (path in reported) continue
            if (shipped.get(path) != null) continue
            if (isUnderOpenSection(path)) continue
            val old = renamed[path]
            if (renamed.containsKey(path)) {
                reported += path
                issues += if (old == null) {
                    ConfigIssue(path, ConfigIssue.Kind.DEPRECATED, "这个配置项已经移除，不再生效，可以删掉了")
                } else {
                    ConfigIssue(path, ConfigIssue.Kind.DEPRECATED, "已改名为 $old，仍然生效，但请尽快改名")
                }
                continue
            }
            // A deprecated leaf deeper down covers its unknown parent: `menu.items` is the story,
            // and "unknown section menu" on top of it is just noise about the same mistake.
            if (reported.any { it.startsWith("$path.") }) continue
            reported += path
            issues += ConfigIssue(
                path,
                ConfigIssue.Kind.UNKNOWN_KEY,
                if (isSection) "插件不认识这个配置段，里面的一切都不会生效" else "插件不认识这个配置项",
            )
        }

        // 2. A block the operator replaced with a plain value: the nested options under it are
        //    now unreachable, and nothing would say so.
        for (path in shipped.getKeys(false).flatMap { section -> sectionsUnder(section) }) {
            val theirs = op.get(path)
            if (theirs == null) continue
            if (theirs is ConfigurationSection) continue
            issues += ConfigIssue(path, ConfigIssue.Kind.WRONG_TYPE, "这里应该是一个配置段，写成了单个值，段里的选项都不生效")
        }
    }

    /** Every path in [section], deepest last. */
    private fun sectionsUnder(path: String): List<String> {
        val section = shipped.getConfigurationSection(path) ?: return emptyList()
        val out = mutableListOf(path)
        for (key in section.getKeys(false)) {
            val child = section.getConfigurationSection(key) ?: continue
            out += sectionsUnder("$path.$key")
        }
        return out
    }

    /** Every leaf and section path in [section], as (path, isSection). */
    private fun walk(section: ConfigurationSection, prefix: String = ""): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        for (key in section.getKeys(false)) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val child = section.getConfigurationSection(key)
            if (child != null) {
                out += path to true
                out += walk(child, path)
            } else {
                out += path to false
            }
        }
        return out
    }

    private fun isUnderOpenSection(path: String): Boolean =
        openSections.any { path == it || path.startsWith("$it.") }

    // ────────────────────────────────────────────────────────────────────────────
    // The shipped defaults, read strictly
    // ────────────────────────────────────────────────────────────────────────────

    private fun shippedValue(path: String): Any? =
        shipped.get(path) ?: throw IllegalStateException("内置 config.yml 缺少配置项 $path")

    private fun shippedText(path: String): String = when (val value = shippedValue(path)) {
        is String -> value
        else -> value.toString()
    }

    private fun shippedBool(path: String): Boolean = when (val value = shippedValue(path)) {
        is Boolean -> value
        else -> throw IllegalStateException("内置 config.yml 里 $path 应该是 true 或 false")
    }

    private fun shippedInt(path: String): Int = when (val value = shippedValue(path)) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
            ?: throw IllegalStateException("内置 config.yml 里 $path 应该是一个整数")
        else -> throw IllegalStateException("内置 config.yml 里 $path 应该是一个整数")
    }

    private fun shippedDecimal(path: String): BigDecimal {
        val text = shippedValue(path).toString().trim()
        if (!UNSIGNED_DECIMAL.matches(text)) {
            throw IllegalStateException("内置 config.yml 里 $path 应该是一个非负数字")
        }
        return BigDecimal(text)
    }

    private fun shippedDouble(path: String): Double {
        val text = shippedValue(path).toString().trim()
        if (!SIGNED_DECIMAL.matches(text) && !UNSIGNED_SMALL.matches(text)) {
            throw IllegalStateException("内置 config.yml 里 $path 应该是一个数字")
        }
        return text.toDouble()
    }

    private companion object {
        /** Money: non-negative, at most eight decimals. */
        val UNSIGNED_DECIMAL = Regex("[0-9]{1,12}([.][0-9]{1,8})?")

        /** Offsets: signed, at most four decimals. */
        val SIGNED_DECIMAL = Regex("-?[0-9]{1,3}([.][0-9]{1,4})?")

        /** Forward offsets: non-negative, at most four decimals. */
        val UNSIGNED_SMALL = Regex("[0-9]{1,3}([.][0-9]{1,4})?")
    }
}
