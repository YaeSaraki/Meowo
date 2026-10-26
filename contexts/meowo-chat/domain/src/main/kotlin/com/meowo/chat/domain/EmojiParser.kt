package com.meowo.chat.domain

/**
 * `` `name `` to a unicode emoji.
 *
 * ## Why a backtick and not `::`
 *
 * `::` needed **two** colons, and `:` is already doing duty in `/meo chat` arguments, in timestamps
 * and in "10:30". A player typing `` `s `` mid-sentence gets an emoji and moves on; one character is
 * also one keystroke, which matters for the whole point of the feature — typing fast.
 *
 * ## Why there is no TAB completion
 *
 * Paper 26.2 **has no chat-tab-completion packet**. `ServerboundChatPacket` carries only the finished
 * message; `ServerboundCommandSuggestionPacket` is the only suggestion request and it belongs to the
 * command box. `AsyncPlayerSendSuggestionsEvent` fires from `ServerGamePacketListenerImpl
 * .sendServerSuggestions` — commands only. So a plugin cannot offer a list while a player is typing in
 * the chat box, short of a client mod or a packet-level library, both of which this project rules out
 * ([01 §8](01-架构红线.md)).
 *
 * [resolve] is the substitute: type a little more and it resolves itself, no keystroke and no client
 * support. `/meo em` is where the full list lives.
 *
 * ## Why pinyin
 *
 * Chinese players reach for `sad` / `happy` only if their IME is already in English mode, so each row
 * carries the initials people actually type — `sx`, `kx`, `wx` — next to the English name. Both
 * resolve to the same character and TAB lists them together, so nobody has to remember which
 * vocabulary a given key lives in.
 *
 * ## Why an unknown name is left alone
 *
 * Blanking it would silently delete what a player typed, and `` `not_an_emoji `` is far more likely to
 * be a typo than an intent to send nothing.
 */
object EmojiParser {

    /** The character that opens an emoji. One keystroke, and unused by anything else on a chat line. */
    const val TRIGGER: Char = '`'

    /**
     * Character to the words that reach it, **canonical name first**.
     *
     * The first word is the readable one, and it is what `/meo em sx` suggests alongside the initials:
     * a player who types `sx` should be handed the readable form too, not the initials again.
     */
    private val TABLE: List<Pair<String, List<String>>> = listOf(
        // Faces
        "😄" to listOf("smile", "happy", "wx", "kx"),
        "😁" to listOf("grin", "gx"),
        "😂" to listOf("joy", "jx"),
        "😉" to listOf("wink", "wy"),
        "😊" to listOf("blush", "hx"),
        "😢" to listOf("sad", "sx"),
        "😭" to listOf("cry", "ku"),
        "😠" to listOf("angry", "n", "sq"),
        "😆" to listOf("laugh", "dx"),
        "😮" to listOf("surprised", "jy"),
        "🤔" to listOf("thinking", "si"),
        "😎" to listOf("cool", "ks"),
        "😕" to listOf("confused"),
        "😴" to listOf("sleep", "shui"),
        "😓" to listOf("sweat"),
        "😇" to listOf("innocent", "tianzhen"),

        // Hands
        "👍" to listOf("thumbsup", "dy", "hao"),
        "👎" to listOf("thumbsdown", "ca"),
        "👌" to listOf("ok"),
        "👋" to listOf("wave", "hw", "zs"),
        "👏" to listOf("clap", "bp"),
        "🙏" to listOf("pray", "qidao"),
        "💪" to listOf("muscle"),
        "👉" to listOf("point"),

        // Hearts and marks
        "❤" to listOf("heart", "ax", "love"),
        "💔" to listOf("broken"),
        "🔥" to listOf("fire", "huo"),
        "⭐" to listOf("star", "xing"),
        "✨" to listOf("sparkles"),
        "✅" to listOf("check", "gz", "dui"),
        "❌" to listOf("cross", "cuo", "no"),
        "⚠" to listOf("warning", "jg"),
        "❓" to listOf("question", "siwen"),
        "❗" to listOf("exclamation"),
        "💯" to listOf("100"),
        "👀" to listOf("eyes", "kan"),
        "➡" to listOf("arrow", "cs"),
        "↩" to listOf("reply", "hf"),

        // Things
        "🐱" to listOf("cat", "mao", "meow"),
        "🐶" to listOf("dog"),
        "🎉" to listOf("party", "qing", "hl"),
        "🎁" to listOf("gift", "li"),
        "🎂" to listOf("cake", "dan"),
        "🏆" to listOf("trophy"),
        "☕" to listOf("coffee", "kf"),
        "🍺" to listOf("beer", "jiu"),
        "💰" to listOf("money", "qian"),
        "💩" to listOf("poop", "fs"),
        "🕐" to listOf("clock", "time"),
        "☀" to listOf("sun", "ty"),
        "🌙" to listOf("moon", "yue"),
        "🌧" to listOf("rain", "yu"),
        "❄" to listOf("snow", "xue"),
    )

    /** Every key, aliases included. First key for a character wins, so the canonical one is kept. */
    val DEFAULTS: Map<String, String> =
        TABLE.flatMap { (char, keys) -> keys.map { it to char } }.toMap()

    /** Key to the readable name of its row, which is the row's first word. */
    private val CANONICAL: Map<String, String> =
        TABLE.flatMap { (_, keys) -> keys.map { it to keys.first() } }.toMap()

    /**
     * A name, an optional closing backtick, and nothing else in between.
     *
     * The closing mark is **optional on purpose**: `` `sad `` has to work, because that is what the
     * player has typed before they press Tab. The optional one is part of the match, so `` `sad` ``
     * leaves nothing behind either.
     */
    private val PATTERN = Regex("`([A-Za-z0-9_+-]{1,32})`?")

    /**
     * Longest a fragment may be before we stop guessing.
     *
     * A prefix is only resolved when it is **unambiguous** — exactly one key starts with it — and
     * this is the window where that is still cheap to check. Past it the player is typing a word, not
     * reaching for a name, and the message is simply left alone.
     */
    const val PREFIX_LIMIT = 12

    /**
     * Exact match first, then a **unique prefix**.
     *
     * This is the feature a player actually feels: typing `` `sa `` turns into 😢 with no Tab anywhere.
     * Tab cannot help them — see the class comment — but "type a bit more and it resolves" needs
     * nothing from the client, which is the same idea without the keystroke.
     *
     * **Ambiguity resolves to nothing.** `` `s `` matches sad, sx, sleep, snow, star and more, so it is
     * left exactly as typed. Guessing there would silently send the wrong face, and a chat plugin that
     * guesses is worse than one that does nothing.
     */
    fun apply(text: String, table: Map<String, String> = DEFAULTS): Rule.Result {
        val hits = mutableListOf<RuleHit>()
        val out = PATTERN.replace(text) { match ->
            val name = match.groupValues[1]
            val emoji = resolve(name, table) ?: return@replace match.value
            hits += RuleHit("emoji:$name", name, RuleHit.Kind.EMOJI)
            emoji
        }
        return if (hits.isEmpty()) Rule.Result(text, emptyList()) else Rule.Result(out, hits)
    }

    /** The emoji for [fragment], or null when it is unknown or ambiguous. */
    fun resolve(fragment: String, table: Map<String, String> = DEFAULTS): String? {
        table[fragment]?.let { return it }
        table[fragment.lowercase()]?.let { return it }
        if (fragment.length < 2 || fragment.length > PREFIX_LIMIT) return null
        val matches = table.keys.filter { it.startsWith(fragment.lowercase()) }
        // Exactly one, or nothing. Two candidates means a coin flip, and a coin flip on someone's
        // face is worse than no face at all.
        return if (matches.size == 1) table[matches.first()] else null
    }

    /**
     * The still-open `` `fragment `` at the end of [buffer], or null when there is none.
     *
     * This is what `/meo em` reads. Walking back over name characters finds the backtick that
     * opened the fragment — but a backtick at the **end** of the buffer whose previous character is
     * part of a name is the **closing** one, and offering completions after it would make the client
     * insert text after the emoji instead of replacing the name.
     *
     * A closed fragment followed by more typing (`` `smile`x ``) is reported as open. That is a false
     * positive and it costs a suggestion the player can ignore; every alternative misbehaves worse.
     */
    fun fragment(buffer: String): String? {
        var start = buffer.length
        while (start > 0 && NAME.contains(buffer[start - 1])) start--
        if (start == 0 || buffer[start - 1] != TRIGGER) return null
        if (start == buffer.length && start >= 2 && NAME.contains(buffer[start - 2])) return null
        return buffer.substring(start - 1)
    }

    /**
     * The names `/meo em` should offer for [fragment] (the backtick included).
     *
     * Two rules, in order:
     *
     * 1. **Exact alias** → offer that row's canonical name *and* the alias. `sx` completes to `sad`
     *    because both are the same character and `sad` is the one a reader wants to see.
     * 2. **Otherwise prefix** → offer every key starting with what was typed.
     *
     * Every entry carries its closing backtick, so a name copied out of the list still goes through the
     * normal [apply] pass. Offering the character itself would be shorter but loses the spelling, and a
     * list nobody can search by name is not a list.
     */
    fun complete(fragment: String, table: Map<String, String> = DEFAULTS): List<String> {
        val prefix = fragment.drop(1)
        val canonical = CANONICAL[prefix]
        val keys = if (canonical != null) listOf(canonical, prefix)
        else table.keys.filter { it.startsWith(prefix) }
        return keys.distinct().sorted().map { "$it$TRIGGER" }
    }

    private val NAME: Set<Char> =
        (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_', '+', '-')).toSet()
}