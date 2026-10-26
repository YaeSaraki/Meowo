package com.meowo.platform

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent

/**
 * The shape every chat line takes.
 *
 * ```
 * ✉ Alice → Steve ✔ 你在吗
 * ```
 *
 * Parts are separated by single spaces so the line stays scannable. The **name** carries the
 * interaction — hovering shows a profile card, clicking prefills a reply — and the `✉` leads as the
 * identity marker rather than the action. A reply shortcut is offered only where it has a target:
 * see [who] for why the sender's own copy of the line must not carry one.
 *
 * Profiles are **cached by the caller**, never fetched here: a balance read is asynchronous and a
 * playtime read touches the stats file, so building this per message would put both on the chat
 * path.
 */
object ChatLine {

    /**
     * The command a click prefills — **the only place `/meo msg` is written down.**
     *
     * It used to have a twin in `Messages.DM_COMMAND`, and the KDoc on this one used to promise it
     * "matches `Messages.DM_COMMAND`". By the time the promise was written the string had been
     * copied four times across the plugin, two of the copies dead and one of them re-implementing
     * [msgCommand] by hand. A comment is not a constraint: renaming the whisper command would have
     * turned every reply button into a command that does not exist, and nothing would have failed
     * until a player clicked it.
     *
     * Everyone who needs the command calls [msgCommand] or [replyCommand].
     */
    const val DM = "/meo msg"

    /** Pulls a `#k3f9` thread back up. */
    const val REPLAY = "/meo replay"

    /** Opens the code on every line, and opens the reference on a reply. */
    const val PREFIX = "#"

    /** `✔` once the recipient has it, `〇` while it is only waiting in the mailbox. */
    const val DELIVERED = "✔"
    const val STORED = "〇"

    /** A real arrow. `->` reads as terminal output; this is what a message line should look like. */
    const val ARROW = "\u2192"

    private val BADGE_COLOR = NamedTextColor.GRAY
    private val ARROW_COLOR = NamedTextColor.DARK_GRAY
    private val SENT_COLOR = NamedTextColor.GREEN
    private val STORED_COLOR = NamedTextColor.GRAY

    /** Darker than the badge, so the code recedes until a reader goes looking for it. */
    private val CODE_COLOR = NamedTextColor.DARK_GRAY

    /** Brighter than [CODE_COLOR]: this one is something to read, not just to identify. */
    private val REF_COLOR = NamedTextColor.AQUA

    /**
     * A line from one participant to another. Shown to both sides, so both can re-read it.
     *
     * ## What is clickable, and what each one does
     *
     * | part | click |
     * |---|---|
     * | the **sender's name** | `/meo msg Alice ` — start something new with that person |
     * | the **body** | `/meo msg Alice #k3f9 ` — reply to **this** message |
     *
     * The two do different jobs and must not be collapsed. A name is a *person*, so clicking it opens
     * a blank message to them; a body is a *moment*, so clicking it answers that moment, code and all.
     * There is no trailing `↩ Reply` button: it said the same thing as the body, in five extra
     * characters, on every line.
     *
     * A blank command drops the click rather than hanging a dead target off it — the sender's own copy
     * has their own name at the sender's position, and a shortcut to oneself is how "click and end up
     * messaging myself" happens (see [who]).
     *
     * ## No quote glyph
     *
     * A reply used to print the words it answered behind a `❝`, looked up in the log. Players read it
     * as clutter, because the line **already opens with its own `#k3f9`** — and that code *is* the
     * reference: hover it for who and when, `/meo replay` for the whole exchange. Two references on
     * one line is one too many, and the long one was the ugly one. The code stands on its own.
     */
    fun private(
        from: Component,
        to: Component,
        delivered: Boolean,
        body: Component,
        /** Prefilled by the body. */
        replyCommand: String = "",
        /** The `#k3f9` this line opens with; blank renders no anchor and no leading gap. */
        anchorCode: String = "",
        /** What hovering [anchorCode] says. */
        anchorHover: String = "",
        /** The referenced `#8i5f` for a reply; blank renders nothing. See [replyRef]. */
        refCode: String = "",
        /** What hovering [refCode] shows: the chain of messages it answers. */
        refHover: String = "",
    ): Component = join(
        code(anchorCode, anchorHover), if (anchorCode.isBlank()) Component.empty() else space(),
        badge(), space(), from, space(), Component.text(ARROW, ARROW_COLOR), space(), to, space(),
        status(delivered), space(),
        // The reference and its trailing gap travel together: an unconditional space here left every
        // un-replied line with a double gap, which is the kind of thing a chat line must not have.
        join(replyRef(refCode, refHover), if (refCode.isBlank()) Component.empty() else space()),
        clickable(body, replyCommand),
    )

    /**
     * The body, clickable. An empty command leaves the text plain and selectable.
     *
     * The click sits on the **body only**, never on its children, so the text stays copyable and a
     * reader can still select a word out of a long sentence before deciding what to do with it.
     */
    private fun clickable(body: Component, command: String): Component {
        // Stripped here as well as at the chat boundary, on purpose. The boundary stops the world;
        // this stops the next caller who passes something they did not think about. A line renderer
        // that says "a body goes in here" and renders whatever event it carries is not a renderer,
        // it is a pass-through — and the only caller who remembered to clean up is the caller who
        // has not been written yet.
        val clean = withoutEvents(body)
        return if (command.isBlank()) clean else clean.clickEvent(ClickEvent.suggestCommand(command))
    }

    /**
     * Removes every click and hover event from a component, **including the ones on its children**.
     *
     * ## Why this has to be recursive, and why it has to exist
     *
     * A chat message arrives as a [Component], and the client chose it. Vanilla clients send plain
     * text; a modified one sends whatever it likes, including
     * `ClickEvent.runCommand("/op attacker")` on a single word.
     *
     * The click handler runs on the **clicker's** client with the **clicker's** permissions, so the
     * attacker needs none of their own: they post an innocuous-looking message, an operator clicks a
     * word in it, and the operator has just op'd them. Every player who clicks is a shell.
     *
     * Measured on this class, not assumed: a hostile body put through [publicLine] came out the
     * other side with `{"click_event":{"action":"run_command","command":"/op attacker"}}` still
     * nested inside it. [clickable] adds *our* click on the root and does nothing about theirs —
     * `clickEvent()` sets one node and leaves every other node exactly as it found it.
     *
     * Hover goes for the same reason and a softer abuse: a forged tooltip claiming to be a moderator
     * notice is impersonation with no server involvement.
     *
     * Text and styling are **kept**. Another plugin may legitimately have coloured the line before
     * this handler ran, and colour is not an execution surface. If it ever becomes one, that is a
     * different decision with a different trade-off — not something to smuggle in here.
     */
    fun withoutEvents(component: Component): Component {
        val children = component.children()
        val rebuilt = if (children.isEmpty()) component else component.children(children.map { withoutEvents(it) })
        // Null clears rather than skips: these are the two setters that accept "no event" at all.
        val noClick: ClickEvent<*>? = null
        val noHover: HoverEvent<*>? = null
        return rebuilt.clickEvent(noClick).hoverEvent(noHover)
    }


    /**
     * Body text that may carry `§` colour codes.
     *
     * It has to go through the legacy deserialiser: `Component.text` would push `§a` in as literal
     * characters and the client would print "§a✔" instead of a green tick.
     *
     * **Language bundle only.** Every caller is rendering an operator-authored string, where `§` is
     * exactly what was meant. Anything that came from a player goes through [body] instead, which is
     * not a stylistic preference: `§` in a player's message is an escape attempt, and this function
     * would carry it out. See [body] for what replaced that.
     */
    fun plain(text: String): Component =
        LegacyComponentSerializer.legacySection().deserialize(text)

    /**
     * A player's words, rendered — **the one place body text becomes a component**.
     *
     * ## Why this exists rather than a legacy deserialisation
     *
     * `ChatPipeline` decorates with BBCode (`**粗体**` → `[b]粗体[/b]`), and that string used to be
     * handed to `LegacyComponentSerializer`, which reads `§`. Two vocabularies, neither reading the
     * other, so the pipeline's own markup reached players as literal `[b]` — measured, not guessed.
     *
     * The tempting one-word fix is to make `MarkupFormatter` emit `§l`. It was rejected, because the
     * string a player is about to send would then be indistinguishable from a serialisation
     * instruction: `§` is U+00A7, not a control character, so it survives normalisation and the
     * deserialiser reads it as a colour code. A player typing `§c§lADMIN` would get red bold text
     * that reads as the server's own. Emitting format codes into player-controlled text is the bug,
     * not the cure.
     *
     * So the split is by **who owns the string** instead:
     *
     * - [plain] — the language bundle. `§` is the intended format. Operator-authored, trusted.
     * - [body] — anything a player typed or a model produced. `§` is **a character**. Only the three
     *   tags [MarkupFormatter] emits are interpreted, and nothing else is.
     *
     * That makes "a player cannot put colour in their own message" true by construction on every
     * channel, rather than by remembering to sanitise at each boundary — there were three, and only
     * the public-chat one was guarded.
     *
     * **Only a balanced pair is a tag.** `[b]` with no `[/b]` anywhere after it stays literal, and so
     * does a `[/b]` with nothing open. MarkupFormatter only ever emits matched pairs, so this costs
     * nothing real and buys two things: a cat can answer a question *about* this markup without
     * turning its own sentence bold, and a player cannot lose a character to a bracket they typed.
     * It is the rule MarkupFormatter already follows for an unclosed `**`.
     */
    fun body(text: String): Component {
        if (text.indexOf(TAG_OPEN) < 0) return Component.text(text)
        val parts = mutableListOf<Component>()
        val literal = StringBuilder()
        var bold = false
        var italic = false
        var underlined = false

        // Flushed **before** a style changes, so a segment carries the style that was in force where
        // it was written rather than the one the tag left behind.
        fun flush() {
            if (literal.isEmpty()) return
            var part: Component = Component.text(literal.toString())
            if (bold) part = part.decoration(TextDecoration.BOLD, true)
            if (italic) part = part.decoration(TextDecoration.ITALIC, true)
            if (underlined) part = part.decoration(TextDecoration.UNDERLINED, true)
            parts += part
            literal.setLength(0)
        }

        var i = 0
        while (i < text.length) {
            val tag = tagAt(text, i)
            if (tag != null) {
                if (tag.closing) {
                    val on = when (tag.style) {
                        "b" -> bold
                        "i" -> italic
                        else -> underlined
                    }
                    // A close with nothing open stays literal. Half-consuming it would swallow a
                    // character the player typed — the exact failure MarkupFormatter is written to
                    // avoid when it leaves an unclosed `**` alone.
                    if (on) {
                        flush()
                        bold = bold and (tag.style != "b")
                        italic = italic and (tag.style != "i")
                        underlined = underlined and (tag.style != "u")
                        i += tag.length
                        continue
                    }
                } else {
                    // First close ahead wins, which is how MarkupFormatter pairs them. No close, no
                    // styling: this is what stops a cat answering about the markup syntax itself —
                    // or a player typing it — from turning the rest of the line bold.
                    //
                    // The scan continues **past** the open tag rather than jumping to the close:
                    // jumping skips every character in between, which is a silent hole in the message.
                    if (text.indexOf(closeTag(tag.style), i + tag.length) >= 0) {
                        flush()
                        when (tag.style) {
                            "b" -> bold = true
                            "i" -> italic = true
                            else -> underlined = true
                        }
                        i += tag.length
                        continue
                    }
                }
            }
            literal.append(text[i])
            i++
        }
        flush()
        return parts.fold(Component.empty()) { acc, part -> acc.append(part) }
    }

    /** A parsed `[b]` or `[/i]`, or null when this `[` does not open a tag this class knows. */
    private data class Tag(val style: String, val closing: Boolean) {
        val length: Int get() = style.length + if (closing) 3 else 2
    }

    private fun tagAt(text: String, i: Int): Tag? {
        if (text[i] != TAG_OPEN) return null
        val close = text.indexOf(TAG_CLOSE, i + 1)
        // Measured `[` to `]`: `[b]` is 2 and `[/b]` is 3. A longer run is not a tag we produced.
        if (close <= i || close - i > LONGEST_TAG) return null
        val raw = text.substring(i + 1, close)
        val closing = raw.startsWith("/")
        val style = if (closing) raw.substring(1) else raw
        return if (style in STYLES) Tag(style, closing) else null
    }

    private fun closeTag(style: String) = "[/$style]"

    private val STYLES = setOf("b", "i", "u")

    private const val TAG_OPEN = '['
    private const val TAG_CLOSE = ']'

    /** Measured `[` to `]`: `[b]` is 2 and `[/b]` is 3. Nothing longer is a tag we produced. */
    private const val LONGEST_TAG = 3

    /**
     * The receiver as it appears after the arrow.
     *
     * **The one person on the line you can always talk to.** On the sender's own copy it is exactly
     * who they wrote to, so it prefills `/meo msg Bob `; on the recipient's copy it is their own name
     * and goes quiet. Leaving it hover-only — as it was — meant the sender's copy had no way to reply
     * at all, which is the half of the line a sender reads most carefully.
     */
    fun receiver(name: String, profile: Component?, msgCommand: String = ""): Component =
        member(Component.text(name), profile, msgCommand)

    /**
     * A name that opens a card when hovered, and prefills [replyCommand] when clicked.
     *
     * A blank [replyCommand] leaves it hover-only, so a component can never hand a reader a command
     * that targets the reader themselves.
     */
    fun member(name: Component, profile: Component?, replyCommand: String = ""): Component {
        var out = if (profile == null) name else name.hoverEvent(HoverEvent.showText(profile))
        if (replyCommand.isNotBlank()) out = out.clickEvent(ClickEvent.suggestCommand(replyCommand))
        return out
    }

    /**
     * A public chat line: `<名字> <消息>` — the same shape a cat's line takes, so a player's words and
     * a plugin's words read alike.
     *
     * The brackets are gone **because** this is composed here and installed as a `ChatRenderer`.
     * Paper only applies the client's `chat.type.text` decoration when the renderer is its own
     * `ChatRenderer.Default`; any renderer a plugin supplies switches it to `paper:raw`, which is
     * registered with no decoration at all. Installing a renderer that returns a bare message is
     * therefore not enough — it would render as `<名字> <名字> 消息`. See `ChatContext.onChat`.
     */
    /**
     * A public chat line: `#k3f9 <名字> <消息>`.
     *
     * Same split as a private line: the **name** starts a blank private message, the **body** replies
     * to that exact message with its code. A player is as addressable as a cat, and quoted as often —
     * the two used to differ here and only here, which read as an oversight rather than a decision.
     *
     * Both commands are **prefixes**, not whole commands: the reader types the message themselves.
     * Either may be blank, which leaves that part hover-only.
     */
    fun publicLine(
        displayName: Component,
        profile: Component?,
        body: Component,
        anchorCode: String = "",
        anchorHover: String = "",
        msgCommand: String = "",
        replyCommand: String = "",
        /** The referenced `#8i5f` for a reply; blank renders nothing. See [replyRef]. */
        refCode: String = "",
        /** What hovering [refCode] shows: the chain of messages it answers. */
        refHover: String = "",
    ): Component = join(
        code(anchorCode, anchorHover), if (anchorCode.isBlank()) Component.empty() else space(),
        member(displayName, profile, msgCommand), space(),
        join(replyRef(refCode, refHover), if (refCode.isBlank()) Component.empty() else space()),
        clickable(body, replyCommand),
    )

    /**
     * A speaker line: the cat's name, then what it said.
     *
     * The separator is ours because the name is ours. Nothing here may widen into an `<名字> 消息`
     * pair: that shape was never ours to draw — it was the client's `chat.type.text` decoration, and
     * reaching for it meant handing the bracket back to the client (see `Messages.sayAs`).
     */
    /**
     * A notice line: `#k3f9 ✉ EconCat 已向 Steve 转账`.
     *
     * **A cat's line answers to the same rule as everybody else's** — click the name for `/meo msg
     * EconCat `, click the body for the same thing. A notice whose text cannot be clicked is the one
     * kind of line where the reader has to aim at a five-character name to ask about it, and that
     * inconsistency is what makes a plugin feel like it was assembled rather than designed.
     */
    fun say(
        name: Component,
        body: Component,
        anchorCode: String = "",
        anchorHover: String = "",
        /** Prefilled by the body; usually the same command the name already carries. */
        bodyCommand: String = "",
    ): Component = join(code(anchorCode, anchorHover), if (anchorCode.isBlank()) Component.empty() else space(),
        name, space(), clickable(body, bodyCommand))

    /** A system notice: no receiver, because nobody is on the other end. */
    fun system(
        from: Component,
        body: Component,
        anchorCode: String = "",
        anchorHover: String = "",
    ): Component = join(code(anchorCode, anchorHover), if (anchorCode.isBlank()) Component.empty() else space(),
        badge(), space(), from, space(), Component.text(DELIVERED), space(), body)

    /**
     * The `#k3f9` anchor that opens every line.
     *
     * **Hover** says who wrote it and exactly when — the question a player actually has when a line
     * scrolls past ("was that you? when?"). **Click** runs [replayCommand] to pull the thread up.
     *
     * It leads the line rather than trailing it so the eye can find it in a wall of chat, and so a
     * reply can quote the code without the reader having to scroll back. Empty [code] renders nothing:
     * a bare gap where a code should be reads as a rendering bug.
     *
     * **Hover only — deliberately not clickable.** A click that opened `/meo replay` read as "this is
     * how you read a message", which is not it: the line is already on screen, and the replay exists to
     * answer "what else was said about this". Two characters on every line that mostly do nothing is
     * worse than no affordance at all. The command still works when typed, and a reply carries the
     * code so the tree is one click away from the `↩ 回复` button that does lead somewhere.
     */
    /** The line's own `#k3f9`. Hover says who and when; it is not clickable. */
    fun code(code: String, hover: String): Component {
        if (code.isBlank()) return Component.empty()
        val text = Component.text("$PREFIX$code", CODE_COLOR)
        return if (hover.isBlank()) text else text.hoverEvent(HoverEvent.showText(plain(hover)))
    }

    /**
     * The **referenced** code, in front of a reply's words.
     *
     * ```
     * #bbbb ✉ Bob → Alice  #8i5f 收到
     *                     ^^^^^^^ hover: what #8i5f said
     * ```
     *
     * This is the one mark that makes a reply a reply. Earlier attempts: no mark at all, and a reply
     * rendered identically to a fresh message — the reader could not tell anything had been referenced.
     * Then a `❝` and the quoted sentence, which said the same thing in a paragraph and read as clutter.
     * A code is the reference the plugin already speaks, hovering it shows the words, and clicking it
     * runs `/meo replay` — so the one token carries identity, content and navigation.
     *
     * **The hover goes through [body], not [plain], while [code]'s hover goes through [plain].** That
     * asymmetry is deliberate and looks wrong at a glance. This hover is the thread listing built in
     * `ChatJournal.refHover` — codes, names and the quoted bodies, none of it from the language
     * bundle, and the quoted body is a **player's** words. [code]'s hover *is* a language key
     * (`code-hover`), where `§` is intended. The rule is the same one [plain] documents: legacy codes
     * only for strings the operator wrote.
     */
    fun replyRef(code: String, hover: String): Component {
        if (code.isBlank()) return Component.empty()
        val text = Component.text("$PREFIX$code", REF_COLOR)
        return when {
            hover.isBlank() -> text
            else -> text.hoverEvent(HoverEvent.showText(body(hover)))
                .clickEvent(ClickEvent.runCommand(replayCommand(code)))
        }
    }

    /** The command a click on a code runs. Written once so the button and `/meo replay` agree. */
    fun replayCommand(code: String): String = "$REPLAY $PREFIX$code"

    /**
     * What a reply prefills: who to answer, and the code being answered.
     *
     * **No `❝` in the prefilled command.** The code is enough — the renderer looks the original up in
     * the log and prints it, so quoting it into the command too would only duplicate it on screen and
     * hand the player a second copy to edit. See `ChatService.renderPrivate`.
     */
    fun replyCommand(sender: String, code: String): String = buildString {
        append("$DM ").append(sender).append(' ')
        if (code.isNotBlank()) append(PREFIX).append(code).append(' ')
    }

    /**
     * The name in a chat line: hovering opens [profile], clicking prefills a **new** private message.
     *
     * A name is a person, not a moment — see [private] for why that distinction is the whole design.
     *
     * **A blank [msgCommand] drops the click and keeps only the hover.** The same line is rendered for
     * both sides, so the sender reads their own name here, and a shortcut pointing at it is a command
     * to message oneself. The caller knows which side it is rendering and passes an empty command for
     * the sender; see `ChatService.renderPrivate`.
     */
    fun who(name: String, profile: Component?, msgCommand: String = ""): Component {
        var text = Component.text(name)
        if (profile != null) text = text.hoverEvent(HoverEvent.showText(profile))
        return if (msgCommand.isBlank()) text else text.clickEvent(ClickEvent.suggestCommand(msgCommand))
    }

    /** `/meo msg <name> ` — the blank conversation with someone, no reference attached. */
    fun msgCommand(name: String): String = "$DM $name "

    private fun badge(): Component = Component.text("✉", BADGE_COLOR)

    private fun status(delivered: Boolean): Component =
        Component.text(if (delivered) DELIVERED else STORED, if (delivered) SENT_COLOR else STORED_COLOR)

    private fun join(vararg parts: Component): Component =
        parts.fold(Component.empty()) { acc, part -> acc.append(part) }

    private fun space(): Component = Component.space()
}