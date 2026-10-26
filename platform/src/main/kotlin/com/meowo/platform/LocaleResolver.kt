package com.meowo.platform

import java.util.Locale

/**
 * Maps a Minecraft client language onto one of the locales this plugin ships.
 *
 * This is deliberately a pure object with no Bukkit or plugin dependency, because the resolution
 * rules are the part of the message layer that silently degrades when wrong: a mismatch does not
 * throw, it just hands the player the wrong language. Keeping it pure lets it be unit tested
 * directly instead of only through MockBukkit end-to-end runs.
 *
 * Minecraft reports tags such as `en_us`, `en_gb` or `zh_hans_cn`. Any region we do not ship a
 * separate file for collapses onto the base language, so an unlisted region still gets its
 * language rather than the server default.
 */
object LocaleResolver {

    /** Locales this plugin ships. Every one is loaded on each reload, so switching is instant. */
    val SUPPORTED: Set<String> = linkedSetOf("zh_CN", "en_US")

    /**
     * Region variants folded onto a shipped locale. Only regions that need *reassignment* belong
     * here; a plain prefix match already handles `en_gb` and `zh_hans_cn` because they share their
     * language with a shipped file. Listing a locale that is not in [SUPPORTED] would be a dead
     * mapping, because nothing would match it.
     */
    private val REGION_ALIASES: Map<String, String> = mapOf(
        "zh_hk" to "zh_CN",
        "en_ca" to "en_US", "en_au" to "en_US", "en_gb" to "en_US", "en_nz" to "en_US",
    )

    /**
     * Reduces any client language tag to the language it belongs to, e.g. `zh-Hans` or `EN_gb`
     * both become `zh` / `en`. Returns null when no language can be read from [tag].
     */
    fun languageOf(tag: String): String? =
        tag.lowercase().replace('-', '_').substringBefore('_').takeIf { it.isNotBlank() }

    /**
     * Picks the bundle for a client language. Tries an exact locale match first so an exact
     * `zh_cn` never depends on iteration order, then falls back to matching the language only —
     * `Locale.ENGLISH` reports the bare tag `en`, which a strict equals would never match.
     * Returns null when nothing matches, leaving the caller to apply its default.
     */
    fun pick(clientLocale: String?, supported: Set<String> = SUPPORTED): String? {
        val tag = clientLocale ?: return null
        val exact = REGION_ALIASES[tag.lowercase().replace('-', '_')]
        if (exact != null && supported.any { it.equals(exact, ignoreCase = true) }) return exact
        supported.firstOrNull { it.equals(tag, ignoreCase = true) }?.let { return it }
        val language = languageOf(tag) ?: return null
        return supported.firstOrNull { languageOf(it).equals(language, ignoreCase = true) }
    }

    /**
     * The tag a player should be resolved by. [Player.locale] is read as a [Locale], and its
     * [Locale.toLanguageTag] form is what [pick] expects; a bare `Locale.ENGLISH` yields `en`.
     */
    fun tagOf(locale: Locale): String = locale.toLanguageTag().lowercase().replace('-', '_')
}
