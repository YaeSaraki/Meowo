package com.meowo.platform

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One wall-clock format for the whole plugin.
 *
 * It lives here rather than in whichever context happened to need it first: `chat/log.db` and
 * `agents/profile.db` are separate databases that can never read each other (红线), so the only way
 * two contexts can agree on what a timestamp reads as is to share the formatter. Two copies of
 * `yyyy-MM-dd HH:mm:ss` would drift the first time one of them was changed for a locale.
 *
 * Server zone, not UTC: these timestamps exist so a player can place an event relative to their own
 * day ("this morning", "just now"), and the server's day is the day they are living in.
 */
object Timestamps {

    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Never throws: a row with an impossible timestamp shows a dash rather than breaking a command. */
    fun full(epochMillis: Long): String = runCatching {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(FORMAT)
    }.getOrDefault("—")
}
