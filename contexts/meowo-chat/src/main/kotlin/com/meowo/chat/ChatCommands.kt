package com.meowo.chat

import com.meowo.chat.domain.Delivery
import com.meowo.chat.domain.EmojiParser
import com.meowo.chat.domain.Mail
import com.meowo.chat.domain.Participant
import com.meowo.platform.ChatLine
import com.meowo.platform.LineOrigin
import com.meowo.platform.Messages
import com.meowo.platform.PlatformHost
import com.meowo.platform.Route
import com.meowo.platform.Timestamps
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Two separate channels, deliberately:
 *
 * - `/meo msg <target> <text>` — a private message. Online means pushed straight into chat; offline
 *   means the mailbox. The delivery receipt **is** the rendered line: its `✔` / `〇` says it all, and
 *   an action bar would be too easy to miss while a second "sent to X" line only repeats it.
 * - `/meo mail` — the mailbox, which holds only what would otherwise be lost.
 *
 * The `✉` badge leads every message. The **name** is what carries the reply shortcut — the badge is
 * plain text — so a line on a system speaker prefills a message to the cat, which is a real target,
 * while a private line only offers a reply to the *other* party (see `ChatService.renderPrivate`).
 */
fun ChatService.registerCommands(host: PlatformHost): AutoCloseable {
    val messages = host.messages
    val handles: List<AutoCloseable> = listOf(
        // Diagnostics, not gameplay: staff only. Players get /meo msg and /meo mail.
        host.commands.register("chat", "chat", object : Route {
            override val permission = "meowo.chat.status"
            override fun execute(sender: CommandSender, args: List<String>) {
                // No argument is the status line; a name is the moderation view. Two jobs in one
                // command because they answer the same question from different ends: "is the filter
                // working" and "what did that player actually say".
                if (args.isEmpty()) {
                    messages.send(sender, "chat-status", speakerCount(), ruleCount(), auditCapacity())
                    return
                }
                val name = args[0]
                val limit = args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 50) ?: 20
                val recent = recentOf(name, limit)
                if (recent.isEmpty()) {
                    messages.sendPlain(sender, "chat-log-empty", name)
                    return
                }
                messages.sendPlain(sender, "chat-log-header", name, recent.size)
                // Same shape as `/meo replay`: a head line, then the words. And no `✉ MeowCat` on
                // every row — this is a dump, not something a cat said. Each head carries its code so
                // the next question is one `/meo replay` away.
                recent.forEach { record ->
                    messages.sendPlain(sender, "chat-log-head", record.code, Timestamps.full(record.atEpochMillis), record.participants())
                    messages.sendPlain(sender, "chat-log-text", record.body)
                }
            }
            override fun complete(sender: CommandSender, args: List<String>): List<String> =
                if (args.size == 1) Bukkit.getOnlinePlayers().map { it.name }.sorted() else emptyList()
        }),
        host.commands.register("msg", "chat", object : Route {
            override val permission = "meowo.chat.use"
            override fun execute(sender: CommandSender, args: List<String>) = send(sender, args, messages)
            override fun complete(sender: CommandSender, args: List<String>): List<String> =
                if (args.size == 1) complete(args[0], 32).map { it.handle } else emptyList()
        }),
        host.commands.register("mail", "chat", object : Route {
            override val permission = "meowo.chat.use"
            override fun execute(sender: CommandSender, args: List<String>) = mailbox(sender, messages)
        }),
        // The emoji list, and the only place TAB completion actually reaches a server: Paper's
        // suggestion packet belongs to the command box, not the chat box (see EmojiParser).
        host.commands.register("em", "chat", object : Route {
            override val permission = "meowo.chat.use"
            override fun execute(sender: CommandSender, args: List<String>) = emoji(sender, args, messages)
            override fun complete(sender: CommandSender, args: List<String>): List<String> =
                // `complete` reads a fragment **with** its backtick; the command line has none.
                if (args.size == 1) EmojiParser.complete(EmojiParser.TRIGGER + args[0]).map { it.dropLast(1) }
                else emptyList()
        }),
        host.commands.register("replay", "chat", object : Route {
            override val permission = "meowo.chat.use"
            override fun execute(sender: CommandSender, args: List<String>) {
                val player = sender as? Player
                if (player == null) messages.send(sender, "player-only") else replayThread(player, args.firstOrNull())
            }
            override fun complete(sender: CommandSender, args: List<String>): List<String> =
                if (args.size == 1) journal().liveCodes().map { "#$it" }.sorted().take(32) else emptyList()
        }),
        host.commands.register("who", "chat", object : Route {
            override val permission = "meowo.chat.use"
            override fun execute(sender: CommandSender, args: List<String>) {
                for (speaker in speakers()) {
                    messages.send(
                        sender, "chat-speaker",
                        speaker.handle,
                        speaker.summary.ifBlank { speaker.id },
                        if (speaker.answerable) "chat-yes" else "chat-no",
                    )
                }
            }
        }),
    )
    return AutoCloseable { handles.forEach { it.close() } }
}

private fun ChatService.send(sender: CommandSender, args: List<String>, messages: Messages) {
    val player = sender as? Player
    if (player == null) { messages.send(sender, "player-only"); return }
    if (args.size < 2) { messages.send(sender, "msg-usage"); return }

    when (val target = find(args[0])) {
        null -> messages.send(sender, "msg-unknown", args[0])
        // A successful send is **silent**: `postToPlayer` already rendered the line, and its ✔ / 〇
        // carries the outcome. A second "已把私信发给 X" only repeated it. Failures still speak,
        // because no line was rendered for them.
        is Participant.Player -> when (val result = postToPlayer(player.uniqueId, player.name, target, args.drop(1).joinToString(" "))) {
            is Delivery.Pushed, is Delivery.Stored -> Unit
            is Delivery.Refused -> messages.send(sender, "msg-refused-${result.reason.name.lowercase()}")
        }
        // A system speaker always answers here and now: nothing is stored, and the badge is not
        // clickable because there is no reply target.
        is Participant.System -> {
            if (!target.answerable) { messages.send(sender, "msg-nobody-home", target.handle); return }
            if (!directMessage(player.uniqueId, player.name, target.id, args.drop(1).joinToString(" "))) {
                messages.send(sender, "msg-refused-blocked")
            }
            // Delivered quietly: the answering cat's own ✉ … ✔ line is the receipt.
        }
    }
}

/**
 * The mailbox. Listing marks everything shown as read, so the unread badge only ever counts mail the
 * player has not actually looked at yet.
 */
private fun ChatService.mailbox(sender: CommandSender, messages: Messages) {
    val player = sender as? Player
    if (player == null) { messages.send(sender, "player-only"); return }
    val all = mailboxOf(player.uniqueId)
    if (all.isEmpty()) { messages.send(sender, "mail-empty"); return }

    markMailRead(player.uniqueId, all.mapTo(mutableSetOf(), Mail::id))
    val pending = unread(player.uniqueId)
    messages.send(sender, "mail-header", all.size, pending)
    for (line in all) {
        // Each row is re-stamped on every listing: a mailbox is read whenever the player opens it, and
        // minting here means the code in this row is the code this listing recorded.
        val reply = ChatLine.replyCommand(line.fromName, "")
        val record = journal().record(
            LineOrigin(LineOrigin.Kind.PLAYER, line.fromName, player.name),
            line.text,
            null,
            authorId = null,
            recipientId = player.uniqueId,
            // **Not persisted.** Listing the mailbox is a reading; writing a durable row for every
            // message in it would put a second copy of everybody's mail into the chat archive, and
            // the archive's whole purpose is to hold what was said once.
            persist = false,
        )
        messages.privateMessage(
            player,
            com.meowo.platform.ChatLine.who(line.fromName, profileCardFor(player, line.fromName), ChatLine.msgCommand(line.fromName)),
            com.meowo.platform.ChatLine.receiver(player.name, profileCard(player, player.uniqueId)),
            delivered = !line.read,
            com.meowo.platform.ChatLine.plain(line.text),
            replyCommand = ChatLine.replyCommand(line.fromName, record.code),
            anchorCode = record.code,
            anchorHover = journal().hoverText(player, record),
            refCode = journal().refCode(record),
            refHover = journal().refHover(player, record),
            origin = LineOrigin(LineOrigin.Kind.PLAYER, line.fromName, player.name),
            text = record.body,
        )
    }
    messages.send(sender, "mail-foot", pending)
}

/**
 * `/meo em` — every emoji key, or what one of them resolves to.
 *
 * The list exists **because the chat box cannot offer one**: a player cannot Tab their way to a name
 * mid-message, so there has to be a place the whole vocabulary is readable. Three keys per line, so it
 * is a list and not a wall.
 */
private fun ChatService.emoji(sender: CommandSender, args: List<String>, messages: Messages) {
    val typed = args.firstOrNull()?.trim()
    if (typed.isNullOrBlank()) {
        messages.send(sender, "em-header", EmojiParser.DEFAULTS.size)
        messages.send(sender, "em-body", EmojiParser.DEFAULTS.keys.sorted().chunked(3).joinToString("\n") {
            it.joinToString(" ")
        })
        return
    }
    val name = typed.removePrefix(EmojiParser.TRIGGER.toString())
    // The same two rules `EmojiParser.resolve` uses, so what this prints is what chat will do.
    val key = EmojiParser.DEFAULTS.keys.firstOrNull { it.equals(name, ignoreCase = true) }
        ?: EmojiParser.DEFAULTS.keys.singleOrNull { name.length >= 2 && it.startsWith(name.lowercase()) }
    if (key == null) {
        messages.send(sender, "em-unknown", typed)
        return
    }
    val emoji = EmojiParser.DEFAULTS.getValue(key)
    val aliases = EmojiParser.DEFAULTS.entries.filter { it.value == emoji }.joinToString(" ") { it.key }
    messages.send(sender, "em-resolved", aliases, key, emoji)
}

/** The badge doubles as the state marker: new, answered, or simply read. */
private fun ChatService.markerOf(mail: Mail): String = when {
    !mail.read -> "mail-mark-new"
    mail.replied -> "mail-mark-replied"
    else -> "mail-mark-read"
}
