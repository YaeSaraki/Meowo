package com.meowo.core.config

import com.meowo.platform.Messages
import org.bukkit.command.CommandSender
import java.io.File

/**
 * `/meowo config` — what the plugin actually made of the operator's file.
 *
 * ## Why this command exists
 *
 * A configuration file has one reader, and that reader is the plugin. Everything the plugin did
 * not understand stayed invisible: a misspelled key, a value of the wrong shape, a setting that
 * was never applied because a *different* setting was broken first. The operator's file was a
 * place where a typo cost nothing and taught nothing.
 *
 * This command closes that gap. It answers three questions an operator actually has:
 *
 * - **"Did the plugin understand my file?"** — `/meowo config warnings`
 * - **"What is the value of this setting, really?"** — `/meowo config get <key>`
 * - **"What does the whole thing look like now?"** — `/meowo config list [section]`
 *
 * ## Why it is read-only
 *
 * It changes nothing. A command that edits the configuration file is a command that can lose it,
 * and the one thing this file must never do is lose what the operator wrote. Repairing the file
 * is the merger's job at start-up, and it only ever **adds** what is missing.
 */
class ConfigCommand(
    private val messages: Messages,
    private val configFile: File,
    private val current: () -> GlobalConfig,
) {

    /** The routes' shared body. Returns false when the arguments do not make sense. */
    fun run(sender: CommandSender, args: List<String>) {
        when (args.firstOrNull()?.lowercase() ?: "") {
            "" -> summary(sender)
            "get" -> get(sender, args.drop(1))
            "list" -> list(sender, args.drop(1))
            "warnings", "warn" -> warnings(sender)
            else -> messages.send(sender, "config-usage")
        }
    }

    /** Tab completion for the sub-arguments. */
    fun complete(sender: CommandSender, args: List<String>): List<String> {
        if (args.size == 1) {
            return listOf("get", "list", "warnings").filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 && args[0].equals("get", ignoreCase = true)) {
            val prefix = args[1].lowercase()
            return current().effective.keys.filter { it.startsWith(prefix) }.sorted()
        }
        return emptyList()
    }

    private fun summary(sender: CommandSender) {
        val config = current()
        messages.send(sender, "config-summary", configFile.absolutePath, config.effective.size)
        if (config.issues.isEmpty()) {
            messages.send(sender, "config-clean")
        } else {
            messages.send(sender, "config-issues-found", config.issues.size)
            config.issues.take(MAX_LISTED).forEach { messages.sendPlain(sender, "config-issue-line", it.describe()) }
            if (config.issues.size > MAX_LISTED) {
                messages.send(sender, "config-issues-more", config.issues.size - MAX_LISTED)
            }
        }
    }

    private fun get(sender: CommandSender, args: List<String>) {
        val key = args.firstOrNull()
        if (key == null) {
            messages.send(sender, "config-usage")
            return
        }
        val config = current()
        val exact = config.effective[key]
        if (exact != null) {
            messages.send(sender, "config-get", key, exact)
            return
        }
        // Not an exact option. A prefix may still name a block, and a near-miss is usually a
        // typo worth pointing at: `chat.max-lenght` should be told that it is `chat.max-length`.
        val candidates = config.effective.keys.filter { it.startsWith(key) || key.startsWith(it) }
        if (candidates.isEmpty()) {
            messages.send(sender, "config-unknown-key", key)
            return
        }
        messages.send(sender, "config-key-ambiguous", key)
        candidates.take(MAX_LISTED).forEach { messages.send(sender, "config-get", it, config.effective.getValue(it)) }
    }

    private fun list(sender: CommandSender, args: List<String>) {
        val prefix = args.firstOrNull()?.let { if (it.isEmpty()) "" else "$it." } ?: ""
        val config = current()
        val entries = config.effective.filterKeys { it.startsWith(prefix) }
        if (entries.isEmpty()) {
            messages.send(sender, "config-unknown-section", prefix.trimEnd('.'))
            return
        }
        messages.send(sender, "config-list-header", prefix.trimEnd('.'), entries.size)
        entries.entries.take(MAX_LISTED).forEach { (key, value) ->
            messages.sendPlain(sender, "config-list-line", key, value)
        }
        if (entries.size > MAX_LISTED) {
            messages.send(sender, "config-issues-more", entries.size - MAX_LISTED)
        }
    }

    private fun warnings(sender: CommandSender) {
        val issues = current().issues
        if (issues.isEmpty()) {
            messages.send(sender, "config-clean")
            return
        }
        messages.send(sender, "config-issues-found", issues.size)
        issues.forEach { messages.sendPlain(sender, "config-issue-line", it.describe()) }
    }

    private companion object {
        /** How many lines one answer may print before it says "and more". */
        const val MAX_LISTED = 40
    }
}
