package com.meowo.core.config

/**
 * One thing the operator should know about their `config.yml`.
 *
 * ## Why this type exists at all
 *
 * A configuration file has exactly one reader: the plugin. And a plugin that reads a file is
 * **silent by construction** — a key it does not recognise is ignored, a value of the wrong shape
 * falls back to a default, and neither fact is visible anywhere. The operator's file is therefore
 * a place where a typo costs nothing and teaches nothing:
 *
 * ```yaml
 * chat:
 *   max-lenght: 100   # a typo. Never read. The limit stays 256.
 * ```
 *
 * Nothing is wrong. Nothing is logged. The operator believes they raised the limit, and the
 * server quietly ignores them — which is the worst outcome a config system can have, because it
 * is indistinguishable from the plugin working.
 *
 * So every discrepancy between the file and what the plugin actually understands becomes an
 * [Issue], is reported to the operator (`/meowo config warnings`, and the startup log), and never
 * takes the server down. The rule the rest of this package follows:
 *
 * > **A wrong value is worth a message, not a crash.**
 *
 * A single mistyped number must not cost a server its economy, its menus and its twenty other
 * features. That is what this type is for: the plugin says what it did and why, and keeps running.
 */
data class ConfigIssue(val path: String, val kind: Kind, val detail: String) {

    enum class Kind {
        /**
         * A key the operator wrote that the plugin does not know. Almost always a typo, and
         * almost always the reason a setting "does not work".
         */
        UNKNOWN_KEY,

        /**
         * The value is not the shape the option expects — a string where a number belongs, or a
         * plain value where a block belongs.
         */
        WRONG_TYPE,

        /**
         * The value has the right shape but is outside the allowed range. The plugin used the
         * default instead and says so.
         */
        OUT_OF_RANGE,

        /**
         * A key that still works but has a new name, or that no longer works at all.
         */
        DEPRECATED,

        /**
         * The value came from an environment variable rather than the file, so editing the file
         * will appear to do nothing. Said out loud, because that is a confusing thing to hit.
         */
        ENV_OVERRIDE,
    }

    /** A one-line, operator-facing rendering. */
    fun describe(): String = when (kind) {
        Kind.UNKNOWN_KEY -> "$path：未知配置项，已忽略（是不是拼错了？）"
        Kind.WRONG_TYPE -> "$path：值格式不对，已改用默认值 —— $detail"
        Kind.OUT_OF_RANGE -> "$path：$detail"
        Kind.DEPRECATED -> "$path：$detail"
        Kind.ENV_OVERRIDE -> "$path：来自环境变量 $detail，文件里的值不会生效"
    }
}
