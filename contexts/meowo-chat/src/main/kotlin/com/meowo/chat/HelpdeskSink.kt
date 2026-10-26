package com.meowo.chat

import com.meowo.api.EconomyAPI
import com.meowo.api.chat.ChatMessageView
import com.meowo.api.chat.ChatModerator
import com.meowo.api.chat.ModerationAction
import com.meowo.api.chat.ModerationVerdict
import com.meowo.api.chat.SystemSpeakerSink
import com.meowo.chat.domain.Delivery
import com.meowo.chat.domain.Intent
import com.meowo.chat.domain.IntentRouter
import com.meowo.chat.domain.Participant
import com.meowo.platform.ChatLine
import com.meowo.platform.LineOrigin
import com.meowo.platform.Messages
import com.meowo.platform.PlatformHost
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Makes `/msg <cat> …` actually answer, so the feature is usable before any AI exists.
 *
 * **This class is the stand-in for the 风纪委员 AI, not a separate feature.** It holds every
 * speaker by implementing exactly the two interfaces the AI will implement, so swapping the real
 * thing in is one `claimSpeaker` call and nothing else changes:
 *
 * - [SystemSpeakerSink] — answers private messages
 * - [ChatModerator] — a permissive judge, proving the seam really is drop-in
 *
 * Because `claimSpeaker` replaces the previous holder and restores it on close, an AI that dies
 * mid-session hands the cats back here instead of leaving them mute.
 *
 * Every reply leaves through [Messages.say], so it gets the same speaker name, the same colours
 * and the same per-recipient language as every other message the plugin sends.
 */
class HelpdeskSink(
    private val host: PlatformHost,
    private val service: ChatService,
) : SystemSpeakerSink, ChatModerator {

    private val messages: Messages get() = host.messages

    /** `转告 Steve 你好` / `tell Steve hi` — verb first, then the name, then the words. */
    private val RELAY = Regex(
        "^(?:转告|转达|告诉|捎个话给|tell|forward\\s+to)\\s+(\\S+)\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )

    override fun onDirectMessage(sender: UUID, speakerId: String, text: String): Boolean {
        val player = Bukkit.getPlayer(sender) ?: return false
        when (IntentRouter.detect(text)) {
            Intent.GREETING -> say(player, speakerId, "helpdesk-hello")
            Intent.THANKS -> say(player, speakerId, "helpdesk-thanks")
            Intent.HELP -> say(player, "system", "helpdesk-help")
            Intent.CAPABILITIES -> say(player, "system", "helpdesk-about", messages.speaker("system"))
            Intent.BALANCE -> balance(player, sender)
            Intent.FALLBACK -> fallback(player, speakerId, text)
        }
        return true
    }

    /**
     * "转告 Steve 你好" — the cat carries the words to a player on the sender's behalf.
     *
     * This is the relay half of 传递: the player hands the message to the cat, the cat hands it on.
     * It travels the same path as a direct message — same gate, same mailbox — but is marked
     * [com.meowo.chat.domain.DirectMessage.relayed] so the reader knows it arrived second-hand.
     */
    private fun fallback(player: Player, speakerId: String, text: String) {
        val relay = RELAY.matchEntire(text.trim())
        if (relay == null) { say(player, speakerId, "helpdesk-unknown"); return }
        val (handle, body) = relay.destructured
        if (body.isBlank()) { say(player, speakerId, "helpdesk-relay-usage"); return }
        val target = service.find(handle)
        if (target !is Participant.Player) { say(player, speakerId, "helpdesk-relay-unknown", handle); return }
        val result = service.postToPlayer(player.uniqueId, messages.speaker(speakerId), target, body)
        when (result) {
            is Delivery.Stored -> say(player, speakerId, "helpdesk-relay-stored", target.handle)
            is Delivery.Pushed -> say(player, speakerId, "helpdesk-relay-sent", player.name, target.handle)
            is Delivery.Refused -> say(player, speakerId, "helpdesk-relay-${reasonKey(result.reason)}")
        }
    }

    private fun reasonKey(reason: Delivery.Reason): String = when (reason) {
        Delivery.Reason.SELF -> "self"
        Delivery.Reason.RATE_LIMIT -> "cooldown"
        else -> "failed"
    }

    /**
     * The one intent that needs another context. It goes through [EconomyAPI] — the same approved
     * route any context uses — instead of importing the economy implementation, and the reply
     * hops back to the main thread via [PlatformHost.reply] rather than blocking the caller.
     */
    private fun balance(player: Player, sender: UUID) {
        val economy = Bukkit.getServicesManager().load(EconomyAPI::class.java)
        if (economy == null) { say(player, "economy", "helpdesk-economy-off"); return }
        host.reply(player, economy.balance(sender), { true }) { amount ->
            messages.send(player, "helpdesk-balance", host.money(amount))
        }
    }

    /**
     * A cat's reply, drawn in the private-message shape: `✉ <猫> -> <玩家> ✔ 内容`.
     *
     * It is rendered now and stored nowhere — a system notice has no other end to arrive at, and
     * the reader has no reason to collect it.
     */
    private fun say(player: Player, speakerId: String, key: String, vararg args: Any) {
        val name = messages.speaker(player, speakerId)
        // The summary was passed as "" before, so every cat's card was a bare name. It is what the
        // card is *for* — `/meo who` prints the same sentence.
        val record = service.journal().record(
            LineOrigin(LineOrigin.Kind.CAT, speakerId, player.name),
            messages.text(player, key, *args),
            null,
            recipientId = player.uniqueId,
        )
        messages.privateMessage(
            player,
            ChatLine.who(name, service.speakerCard(player, name, messages.speakerSummary(player, speakerId)),
                ChatLine.msgCommand(name)),
            ChatLine.receiver(player.name, service.profileCard(player, player.uniqueId)),
            delivered = true,
            messages.component(player, key, *args),
            replyCommand = ChatLine.replyCommand(name, record.code),
            anchorCode = record.code,
            anchorHover = service.journal().hoverText(player, record),
            refCode = service.journal().refCode(record),
            refHover = service.journal().refHover(player, record),
            origin = LineOrigin(LineOrigin.Kind.CAT, speakerId, player.name),
            text = record.body,
        )
    }

    /** Deliberately permissive: the helpdesk never blocks anyone, it only answers. */
    override fun inspect(message: ChatMessageView) = ModerationVerdict(ModerationAction.ALLOW, "helpdesk")

    override fun onPublished(message: ChatMessageView) = Unit

    override fun onQuit(player: UUID) = Unit
}
