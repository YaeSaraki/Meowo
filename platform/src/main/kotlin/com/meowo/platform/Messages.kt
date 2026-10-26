package com.meowo.platform

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Path
import java.util.logging.Level

/** Loads an entire language bundle before swapping it, so failed reloads retain valid messages.
 *
 * Every player-facing line is prefixed with its cat, so a prompt reads like
 * `✉ EconCat 已向 Steve 转账 ฅ2.50 Meo` rather than a bare server line.
 *
 * ## Why the name is drawn by us and not by the client
 *
 * The obvious route is `sendMessage(component, ChatType.CHAT.bind(name))`, which puts the name in the
 * `minecraft:chat` sender slot. **The brackets are then drawn by the client, not by us**: that slot is
 * rendered through the vanilla `chat.type.text` translation, whose value is literally `"<%s> %s"`.
 * No server-side string ever contains a `<`, and no amount of stripping in our code removes the pair
 * — it is applied after we hand the message over. See docs/minimax/09-模拟玩家发言可行性.md.
 *
 * So the name is composed into the component here and sent as an ordinary message. Nothing is lost
 * by dropping the chat type: these lines were never signed or real profiles to begin with, and the
 * decoration was the only thing the sender slot bought us — the wrong decoration.
 *
 * The console, RCON and command blocks have no colour and no chat box, so they keep the previous
 * behaviour: colour codes stripped and one plain line per `\n` segment. Non-player senders never see
 * a speaker.
 *
 * [action] builds clickable components for players. Commands are supplied by the caller (never by the
 * language file) and run as the clicking player, so normal permission checks still apply.
 */
class Messages(private val plugin: JavaPlugin) {
    /** Every supported locale is loaded up front so a single reload can switch any player at once. */
    @Volatile private var bundles: Map<String, Map<String, String>> = emptyMap()

    /** Console and any sender without a client language fall back to this. Set from `config.yml`. */
    @Volatile var defaultLocale: String = "zh_CN"
        private set

    /** `player` honours each client's language; `server` pins everyone to [defaultLocale]. */
    @Volatile var perPlayer: Boolean = true
        private set

    fun load(locale: String, languageMode: String = "player") {
        require(locale in LocaleResolver.SUPPORTED) { "Unsupported locale: $locale" }
        require(languageMode in setOf("player", "server")) { "Unsupported language mode: $languageMode" }
        defaultLocale = locale
        perPlayer = languageMode == "player"
        bundles = LocaleResolver.SUPPORTED.associateWith { readBundle(it) }
    }

    /**
     * Reads one bundle: the operator's file on disk wins, the jar's copy fills every missing key.
     * A stale or partial file therefore still yields a complete bundle instead of raw keys.
     */
    private fun readBundle(locale: String): Map<String, String> {
        val resource = "lang/$locale.yml"
        if (!plugin.dataFolder.resolve(resource).exists()) plugin.saveResource(resource, false)
        val config = YamlConfiguration().apply { load(plugin.dataFolder.resolve(resource)) }
        val defaults = plugin.getResource(resource)!!.bufferedReader(Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
        val stale = mutableListOf<String>()
        val out = defaults.getKeys(false).associateWith { key ->
            require(!config.contains(key) || config.isString(key)) { "Language key $key must be text" }
            val fallback = defaults.getString(key)!!
            val override = config.getString(key) ?: return@associateWith fallback
            // A stale operator file must not be able to shadow a format that changed shape.
            //
            // Overriding a sentence is the point of these files. Overriding a **layout** is not: when a
            // key grows an argument, an old copy keeps its old placeholders and silently renders the
            // wrong fields — a reply tree that drops its sender column and its indentation, with no
            // error anywhere. An override whose placeholders do not match the shipped one is a leftover,
            // not a choice, so the shipped value wins.
            if (placeholders(override) == placeholders(fallback)) override
            else {
                // Say it out loud. A silent fallback is the worst kind of bug: the plugin renders, the
                // build is green, and a column of the replay tree simply is not there until a player
                // reports it. One line at load is cheaper than that round trip.
                stale += key
                fallback
            }
        }
        if (stale.isNotEmpty()) {
            plugin.logger.warning(
                "Language file '$locale' overrides ${stale.size} key(s) with a different shape; " +
                    "the bundled version was used instead: ${stale.take(8).joinToString(", ")}" +
                    (if (stale.size > 8) " …" else "") +
                    ". Re-copy lang/$locale.yml after editing the plugin.",
            )
        }
        return out
    }

    /** `{0}`, `{1}`, … present in [text]; the shape a value will fill in. */
    private fun placeholders(text: String): Set<String> =
        PLACEHOLDER.findAll(text).map { it.value }.toSet()

    /**
     * Maps a Minecraft client language onto one of our bundles. Delegates to [LocaleResolver] so
     * the rules are unit testable without a plugin instance.
     */
    private fun resolve(sender: CommandSender?): Map<String, String> {
        // `locale()` is called explicitly: Player also exposes getLocale(), and Kotlin would
        // otherwise synthesise a String property that shadows the Locale accessor.
        val client = if (perPlayer && sender is Player) LocaleResolver.tagOf(sender.locale()) else null
        val match = LocaleResolver.pick(client)
        return bundles[match] ?: bundles[defaultLocale] ?: bundles.values.first()
    }

    /** Text in the sender's own language. Console and command blocks get [defaultLocale]. */
    fun text(sender: CommandSender?, key: String, vararg args: Any): String {
        var result = resolve(sender)[key] ?: bundles[defaultLocale]?.get(key) ?: key
        args.forEachIndexed { index, value -> result = result.replace("{$index}", value.toString()) }
        return result
    }

    /** Text in [defaultLocale]. Use only where no recipient exists, such as log lines. */
    fun text(key: String, vararg args: Any): String = text(null, key, *args)

    /** The display name of a speaker, overridable per language file via `speaker-<id>`. */
    fun speaker(sender: CommandSender?, id: String): String {
        val key = "speaker-$id"
        val configured = resolve(sender)[key]
        return if (configured != null && configured != key && configured.isNotBlank()) configured else DEFAULT_SPEAKERS[id] ?: "UnknownCat"
    }

    fun speaker(id: String): String = speaker(null, id)

    /**
     * Every speaker id the plugin knows, in declaration order. Exposed so a directory can be built
     * without reaching into [DEFAULT_SPEAKERS] from another module.
     */
    val speakerIds: Set<String> get() = DEFAULT_SPEAKERS.keys

    /**
     * Supplies the profile card behind a speaker's name.
     *
     * Set by whichever context knows what speakers are — today the chat context. `platform` must not
     * import a context, so the card arrives as a callback; without one, cat lines render exactly as
     * before, just with no hover. Cleared on close so a disabled chat context leaves nothing behind.
     */
    @Volatile var speakerCard: ((CommandSender?, String) -> Component?)? = null

    /**
     * Mints the `#k3f9` anchor every line opens with, and records the line behind it.
     *
     * Set by the chat context. When it is null — chat disabled, or a context that has closed — every
     * line renders exactly as before, just with no code. That is the whole failure mode: nothing here
     * is required for a line to be delivered.
     */
    @Volatile var stamper: LineStamper? = null

    /** One-line description of a speaker, from `speaker-summary-<id>`. Empty when unset. */
    fun speakerSummary(sender: CommandSender?, id: String): String = text(sender, "speaker-summary-$id")

    /** One-line description of a speaker, from `speaker-summary-<id>`. Empty when unset. */
    fun speakerSummary(id: String): String = text(null, "speaker-summary-$id")

    /**
     * Resolves which cat speaks a message: exact key first, then key prefix, then the system cat.
     *
     * Public because the routing table is a contract of its own: a caller holding a message key
     * needs to know which cat owns it. It is public mostly so that a **test** can assert the
     * routing against this table rather than against a copy of it — a copy drifts, and a drifted
     * copy agrees with a drifted table, so the test keeps passing while the plugin signs its
     * messages with the wrong cat.
     */
    fun speakerOf(key: String): String = when {
        SPEAKER_KEYS.containsKey(key) -> SPEAKER_KEYS.getValue(key)
        else -> SPEAKER_PREFIXES.firstOrNull { key.startsWith(it.first) }?.second ?: "system"
    }
    /**
     * Players get one chat-formatted message attributed to the owning cat. Non-player senders get
     * uncoloured plain text split per line, because neither chat types nor colour exist there.
     * Missing keys fall back to the raw key, never throw.
     */
    fun send(sender: CommandSender, key: String, vararg args: Any) =
        sendText(sender, key, text(sender, key, *args))

    /**
     * A multi-line block whose tail carries click events — the help page's page buttons.
     *
     * [sendText] cannot do this: it takes a legacy string, and a legacy string has nowhere to put a
     * click. So the body is deserialised exactly as before and [tail] is appended as a component.
     *
     * [text] stays the caller's **plain** block rather than a flattening of the component, because
     * that string is what the chat archive stores. Flattening would drop the colour codes and change
     * what `/meo replay` reads back, so a console that cannot click still gets the same command text
     * in the record, and only the click is the part players keep.
     */
    fun sendBlock(
        sender: CommandSender,
        key: String,
        text: String,
        tail: Component? = null,
        /** Prefilled by the body; see [ChatLine.msgCommand]. */
        bodyCommand: String = "",
    ) {
        val speakerId = speakerOf(key)
        val body = LEGACY.deserialize(text).let { if (tail == null) it else it.append(tail) }
        sayAs(sender, systemName(sender, speakerId), body, LineOrigin(LineOrigin.Kind.CAT, speakerId), text, bodyCommand)
    }

    /**
     * Sends text that is already rendered, with the same rules as [send]: one chat-formatted
     * message with a speaker for players, colour stripped and split per line for everything else.
     *
     * Callers that build their own multi-line copy (the help page) must go through here rather than
     * calling `sendMessage` directly, or the console gets raw colour codes.
     */
    fun sendText(sender: CommandSender, key: String, rendered: String) {
        val speakerId = speakerOf(key)
        sayAs(
            sender,
            systemName(sender, speakerId),
            LEGACY.deserialize(rendered),
            LineOrigin(LineOrigin.Kind.CAT, speakerId),
            rendered,
            // The body answers the same way the name does: ask this cat about this line.
            ChatLine.msgCommand(speaker(sender, speakerId)),
        )
    }
    /**
     * Shows the big centre-screen title, or clears it with an empty call. Paper's own overload, so
     * the legacy colour codes in the language bundle keep working.
     */
    fun showTitle(sender: CommandSender, title: String, subtitle: String = "", fadeIn: Int = 5, stay: Int = 60, fadeOut: Int = 10) {
        if (sender is Player) sender.sendTitle(title, subtitle, fadeIn, stay, fadeOut)
    }

    /** Clears whatever [showTitle] put on screen. */
    fun clearTitle(sender: CommandSender) {
        if (sender is Player) sender.sendTitle("", "", 0, 0, 0)
    }

    /** Sends an already-composed component under an explicit speaker. */
    fun say(sender: CommandSender, speaker: String, message: Component) =
        sayAs(
            sender, systemName(sender, speaker), message,
            LineOrigin(LineOrigin.Kind.CAT, speaker), bodyCommand = ChatLine.msgCommand(speaker(sender, speaker)),
        )

    /**
     * One private line, `✉ <发件人> -> <收件人> ✔ 消息`.
     *
     * The line is already whole, so it is **delivered as the message itself** with no name slot in
     * front of it. Passing the same components as both name and body duplicated the sentence under
     * the client's chat-type decoration, which is where the stray `<>` the players saw came from.
     *
     * The **sender's name** is wired by the caller through [ChatLine.who] and opens a blank private
     * message; the **body** carries [replyCommand] and answers this specific line. A blank [replyCommand]
     * leaves the body plain, which is what the sender's own copy wants — there is nobody to answer.
     */
    fun privateMessage(
        sender: CommandSender,
        from: Component,
        to: Component,
        delivered: Boolean,
        body: Component,
        /** Prefilled by the body. See `ChatLine.private` for the name/body split. */
        replyCommand: String = "",
        /**
         * The `#k3f9` this line opens with; blank renders no anchor.
         *
         * Passed **in** rather than minted here: one private line is delivered to both sides, and a
         * code minted per delivery would give the sender and the recipient two different codes for the
         * same sentence. The chat context allocates it once, where the record is written.
         */
        anchorCode: String = "",
        /** What hovering [anchorCode] says. */
        anchorHover: String = "",
        /** The referenced `#8i5f` for a reply; blank renders nothing. See `ChatLine.replyRef`. */
        refCode: String = "",
        /** What hovering [refCode] shows: the chain of messages it answers. */
        refHover: String = "",
        /** Who wrote the line, for the record behind it. Ignored when the chat context is off. */
        origin: LineOrigin? = null,
        /** The rendered text stored with the record; defaults to the flattened line. */
        text: String? = null,
    ) = deliver(
        sender,
        ChatLine.private(from, to, delivered, body, replyCommand, anchorCode, anchorHover, refCode, refHover),
        // A line that already carries a code has **already been recorded**: the chat context mints
        // the code where the record is written, and hands the same code to both sides of the
        // conversation so they point at one row. Stamping it again here would write a **second
        // copy** of the same sentence into the archive — one per reader, so a private line landed
        // in the log three times: once with the ids, twice without them.
        //
        // The origin exists for the record behind the line; a line that has one does not need
        // another. So the stamp is skipped exactly when the code says the line is already stored.
        if (anchorCode.isBlank()) origin else null,
        text,
    )

    /** Sends an already-composed component under a caller-composed name, so a badge can lead it. */
    fun sayAs(
        sender: CommandSender,
        name: Component,
        message: Component,
        origin: LineOrigin? = null,
        text: String? = null,
        /** Prefilled by the body; see `ChatLine.say`. */
        bodyCommand: String = "",
    ) = deliver(sender, ChatLine.say(name, message, bodyCommand = bodyCommand), origin, text)

    /**
     * A plain line: no speaker, no `#code`.
     *
     * For **dumps** — `/meo replay` prints a recorded conversation, and every row already carries the
     * code of the message it shows. Routing those rows through [send] gave each one its own
     * `#k3f9 ✉ MeowCat` prefix, so a six-line conversation repeated `MeowCat` six times and minted
     * six fresh codes for messages nobody wrote. A dump is not a speaker.
     */
    fun sendPlain(sender: CommandSender, key: String, vararg args: Any) {
        deliver(sender, LEGACY.deserialize(text(sender, key, *args)), null, null)
    }

    /** One component in, one chat line out. The only place a line actually reaches a sender. */
    private fun deliver(sender: CommandSender, message: Component, origin: LineOrigin?, text: String?) {
        // Stamping happens **before** delivery and exactly once per call, so the code a player reads is
        // the code that was recorded. A stamper that throws must not take the message down with it.
        val stamped = if (origin == null) message else {
            val anchor = stamp(sender, origin, message, text)
            // The stamper returns the **anchor only**; the line is the anchor and the message. Sending
            // the anchor on its own is how a whole notice once reduced to a bare code.
            if (anchor.children().isEmpty()) message
            else Component.empty().append(anchor).append(Component.space()).append(message)
        }
        if (sender is Player) {
            sender.sendMessage(stamped)
            return
        }
        // Console, RCON and command blocks have neither a chat box nor colour, so they get the same
        // plain, per-line treatment `sendText` has always used — not a single `§`-laden blob.
        val flat = PlainTextComponentSerializer.plainText().serialize(stamped)
        LEGACY_COLOR.replace(flat, "").split('\n').forEach { sender.sendMessage(it) }
    }

    /** Never let the archive take a chat line down with it. */
    private fun stamp(sender: CommandSender, origin: LineOrigin, message: Component, text: String?): Component {
        val stamper = stamper ?: return message
        return try {
            stamper.stamp(sender, origin, text ?: PlainTextComponentSerializer.plainText().serialize(message))
        } catch (error: RuntimeException) {
            plugin.logger.log(Level.WARNING, "chat log stamp failed for ${origin.label()}", error)
            Component.empty()
        }
    }

    /**
     * Every system speaker leads with the mail badge, so a notice reads as a private message even
     * though there is nobody on the other end to answer it.
     *
     * The name itself is interactive: hovering opens the speaker's card, clicking prefills a
     * private message. [profile] is supplied by the chat context, which caches it — this must never
     * trigger an asynchronous balance read on the chat path.
     */
    private fun systemName(sender: CommandSender?, speakerId: String, profile: Component? = null): Component {
        val name = speaker(sender, speakerId)
        // The prefilled command comes from [ChatLine.msgCommand] rather than being spelled out
        // again: it is the same string the chat line offers, and a second copy of it is a second
        // place to break when the whisper command is ever renamed.
        var interactive = Component.text(name).clickEvent(ClickEvent.suggestCommand(ChatLine.msgCommand(name)))
        // Explicit profile first, then the chat context's. A cat is addressable, so its name must
        // explain itself on hover just like a player's does.
        val card = profile ?: speakerCard?.invoke(sender, speakerId)
        if (card != null) interactive = interactive.hoverEvent(HoverEvent.showText(card))
        return Component.empty()
            .append(component(sender, BADGE))
            .append(Component.space())
            .append(interactive)
    }

    /**
     * The name on a player's private message. The badge is **clickable**: hovering explains who wrote
     * it, and clicking prefills a reply, so the mail marker is an action rather than decoration.
     *
     * A blank [replyCommand] leaves the badge inert, matching `ChatLine.who`. A prefilled command is
     * a promise about **whom** the reader will message, and the reader reading their own name is
     * exactly how "点一下是给自己私信" happens — see [08 §26](08-坑与反模式.md).
     */
    fun privateName(
        sender: CommandSender?,
        fromName: String,
        replyCommand: String,
        badgeKey: String = BADGE,
    ): Component {
        val badge = component(sender, badgeKey)
        return Component.empty()
            .append(if (replyCommand.isBlank()) badge else badge.clickEvent(ClickEvent.suggestCommand(replyCommand)))
            .append(Component.space())
            .append(Component.text(fromName))
    }
    /**
     * Renders a language value as a component, keeping its legacy colour codes.
     *
     * `Component.text` does **not** read `§`, so a bundle line written `"§7上锁的箱子"` reached an item
     * tooltip as those literal characters while the same line looked right in chat. Anything building an
     * item name, a lore line or a title from a language key must come through here.
     */
    fun component(sender: CommandSender?, key: String, vararg args: Any): Component = LEGACY.deserialize(text(sender, key, *args))
    fun component(key: String, vararg args: Any): Component = component(null, key, *args)
    /** A clickable label. [command] must start with `/`; [hoverKey] adds a tooltip. */
    fun action(sender: CommandSender?, labelKey: String, command: String, hoverKey: String? = null, vararg args: Any): Component =
        component(sender, labelKey, *args)
            .clickEvent(ClickEvent.runCommand(command))
            .let { if (hoverKey == null) it else it.hoverEvent(HoverEvent.showText(component(sender, hoverKey, *args))) }
    fun action(labelKey: String, command: String, hoverKey: String? = null, vararg args: Any): Component =
        action(null, labelKey, command, hoverKey, *args)
    /** Joins actions with the shared [ACTION_SEPARATOR] language value. */
    fun actionLine(sender: CommandSender?, vararg actions: Component): Component {
        val separator = component(sender, ACTION_SEPARATOR)
        return actions.fold<Component, Component?>(null) { acc, a ->
            if (acc == null) a else acc.append(separator).append(a)
        } ?: Component.empty()
    }
    fun actionLine(vararg actions: Component): Component = actionLine(null, *actions)
    private companion object {
        val LEGACY_COLOR = Regex("§[0-9A-FK-ORa-fk-orxX]")

        /** `{0}`, `{1}`, … — the shape a language value will fill in. */
        val PLACEHOLDER = Regex("""\{\d+}""")
        val LEGACY = LegacyComponentSerializer.legacySection()
        const val ACTION_SEPARATOR = "action-separator"
        const val BADGE = "mail-badge"

        /** Naming rule: 4 characters + `Cat`, so every speaker reads the same width. Overridable in
         *  the language files via `speaker-<id>`; these are the fallbacks. `spawn` uses the domain
         *  synonym "home" because a 4-character truncation of "spawn" is not a readable word. */
        val DEFAULT_SPEAKERS = mapOf(
            "system" to "MeowCat", "economy" to "EconCat", "menu" to "MenuCat",
            "render" to "RendCat", "spawn" to "WarpCat", "sit" to "SitCat",
            "hat" to "HatCat", "nametag" to "IdleCat", "mine" to "MineCat",
            "teleport" to "TeleCat", "shadowbox" to "ShadCat", "home" to "NestCat",
            "invsee" to "InvCat",
            // A shop is a cat with a price list, and the name says what it is the way the others do.
            "shop" to "ShopCat",
            // The pomodoro cat was named in the language files before this table knew about it, so
            // every focus message was signed by the system cat while the bundle said "PomoCat".
            "pomodoro" to "PomoCat",
        )
        /** Exact keys whose prefix does not already identify the owner. */
        val SPEAKER_KEYS = mapOf(
            "balance" to "economy", "treasury" to "economy",
            "daily-paid" to "economy", "daily-empty" to "economy",
            "pay-sent" to "economy", "pay-received" to "economy",
            "unavailable" to "menu", "menu-title" to "menu",
            "menu-balance" to "menu", "menu-refresh" to "menu",
        )
        /** Longest/most specific first; `action-` is only produced by the teleport context today. */
        val SPEAKER_PREFIXES = listOf(
            "eco-" to "economy", "error-" to "economy",
            "render-" to "render", "spawn-" to "spawn",
            "seat-" to "sit", "hat-" to "hat", "nametag-" to "nametag", "mine-" to "mine", "rare-" to "mine", "shadowbox-" to "shadowbox",
            "home-" to "home", "tpa-" to "teleport", "action-" to "teleport",
            "invsee-" to "invsee",
            "shop-" to "shop",
            "pomodoro-" to "pomodoro",
        )
    }
}

