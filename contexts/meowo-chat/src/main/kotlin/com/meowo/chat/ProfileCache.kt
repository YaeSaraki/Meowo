package com.meowo.chat

import com.meowo.api.EconomyAPI
import com.meowo.platform.ChatLine
import com.meowo.platform.Durations
import com.meowo.platform.Messages
import com.meowo.platform.PlatformHost
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The profile card behind a hovered name.
 *
 * ## Why this is a cache and not a lookup
 *
 * A balance read is **asynchronous** (it crosses to the economy worker) and a playtime read touches
 * the player's stats file. Doing either while rendering a chat line would put both on the chat path
 * — the exact stall the project forbids (红线 §3). Values are gathered once per join and refreshed
 * when `BalanceChanged` arrives; rendering then costs one map lookup.
 *
 * ## Why every string goes through the language bundle
 *
 * A hard-coded label here would be Chinese-only, and a hover card is exactly the surface a
 * per-locale server notices. Labels are keys, resolved per viewer like any other message.
 */
class ProfileCache(private val host: PlatformHost) {

    private data class Profile(
        val name: String,
        val uuid: UUID,
        val firstJoined: Long,
        val playtimeMinutes: Int,
        val sessionStart: Long,
        val balance: String?,
    )

    private val profiles = ConcurrentHashMap<UUID, Profile>()

    /** Rendering knows names, not ids, so the card is looked up by lower-cased name. */
    private val byName = ConcurrentHashMap<String, UUID>()

    private val messages: Messages get() = host.messages

    /** Loads everything readable on the main thread, then asks the economy thread. */
    fun load(player: Player) {
        val now = System.currentTimeMillis()
        // A name is not permanent: a premium player may rename, and the old name then belongs to
        // somebody else — or to nobody. The previous name is dropped first, so the index cannot
        // keep pointing a stale name at this player and answer a hover card for the wrong person.
        profiles[player.uniqueId]?.let { previous ->
            if (!previous.name.equals(player.name, ignoreCase = true)) {
                byName.remove(previous.name.lowercase(), player.uniqueId)
            }
        }
        byName[player.name.lowercase()] = player.uniqueId
        profiles[player.uniqueId] = Profile(
            name = player.name,
            uuid = player.uniqueId,
            firstJoined = runCatching { player.firstPlayed }.getOrDefault(now),
            playtimeMinutes = runCatching { player.getStatistic(Statistic.PLAY_ONE_MINUTE) }.getOrDefault(0),
            sessionStart = now,
            balance = null,
        )
        refreshBalance(player.uniqueId)
    }

    /** Called on `BalanceChanged`, so a hovered balance is never stale after a transfer. */
    fun onBalanceChanged(player: UUID, after: java.math.BigDecimal) {
        val shown = host.money(after)
        profiles.computeIfPresent(player) { _, old -> old.copy(balance = shown) }
    }

    fun forget(player: UUID) {
        profiles.remove(player)?.let { byName.remove(it.name.lowercase()) }
    }

    fun forgetAll() { profiles.clear(); byName.clear() }

    /**
     * The card for a player id, or null when we have never seen them online.
     *
     * [viewer] decides the **language**, not the data: every label is resolved per viewer, so a card
     * on an English client is English. It was previously always the server default, which is how a
     * Chinese card followed an English player around the whole server.
     */
    fun card(viewer: CommandSender?, id: UUID): Component? = profiles[id]?.let { card(viewer, it) }

    /** The card for a name, or null when we have never seen that player online. */
    fun cardFor(viewer: CommandSender?, name: String): Component? {
        val id = byName[name.lowercase()] ?: return null
        val profile = profiles[id] ?: return null
        return card(viewer, profile)
    }

    /**
     * The card for a system speaker. A cat has no account, so it gets its purpose instead — but the
     * **shape is the player's**, so hovering `EconCat` feels the same as hovering a name.
     *
     * [summary] is the `speaker-summary-<id>` language value, read by the caller so the chat context
     * owns which id maps to which sentence.
     */
    fun speakerCard(viewer: CommandSender?, name: String, summary: String): Component = card(
        viewer,
        buildList {
            add(Row(messages.text(viewer, "profile-name"), name))
            if (summary.isNotBlank()) add(Row(messages.text(viewer, "profile-purpose"), summary))
            // A cat can be answered — `/meo msg <cat>` reaches the helpdesk — so say so here rather
            // than leaving the reader to guess whether the name is a real account.
            add(Row(messages.text(viewer, "profile-reply"), "${ChatLine.DM} $name"))
        },
    )

    private fun card(viewer: CommandSender?, profile: Profile) = card(
        viewer,
        listOf(
            Row(messages.text(viewer, "profile-name"), profile.name),
            // Clicking the value copies **the UUID on that line**. The card used to end with a
            // "点击可私聊" footer wired to `/meo msg <name>`, which meant hovering your own card and
            // clicking it whispered to yourself; and a name-shaped "copy" never contained a UUID.
            Row(
                messages.text(viewer, "profile-uuid"),
                profile.uuid.toString(),
                hint = messages.text(viewer, "profile-copy-uuid-hint"),
                click = ClickEvent.copyToClipboard(profile.uuid.toString()),
            ),
            Row(messages.text(viewer, "profile-joined"), stamp(viewer, profile.firstJoined)),
            Row(messages.text(viewer, "profile-session"), Durations.since(profile.sessionStart)),
            Row(messages.text(viewer, "profile-playtime"), Durations.short(profile.playtimeMinutes.toLong() * 60_000L)),
            Row(messages.text(viewer, "profile-balance"), profile.balance ?: messages.text(viewer, "profile-unknown")),
        ),
    )

    /** One line of a card. [hint] and [click] make the **value** interactive, never the whole card. */
    private class Row(val label: String, val value: String, val hint: String? = null, val click: ClickEvent<*>? = null)

    /**
     * One card shape for every speaker, cat or player.
     *
     * The heading goes through [Messages.component] rather than `Component.text`: it is a language
     * value carrying `§b`, and the plain-text constructor would push that in as two literal
     * characters, so the card opened with "§b玩家资料" on screen.
     *
     * No footer and no clickable prompt. A hint that says "click to do X" is a promise, and any
     * promise about *whom* you will message is wrong for at least one reader of a shared line.
     * The only affordance left is the UUID value, which is unambiguous because the UUID is right
     * there in the same row.
     */
    private fun card(viewer: CommandSender?, rows: List<Row>): Component {
        var card = messages.component(viewer, "profile-heading").decorate(TextDecoration.BOLD)
        for (row in rows) {
            var value = Component.text(row.value, NamedTextColor.WHITE)
            if (row.hint != null) value = value.hoverEvent(HoverEvent.showText(Component.text(row.hint, NamedTextColor.GRAY)))
            if (row.click != null) value = value.clickEvent(row.click)
            card = card
                .append(Component.newline())
                .append(Component.text("${row.label} ", NamedTextColor.GRAY))
                .append(value)
        }
        return card
    }

    private fun refreshBalance(player: UUID) {
        val economy = Bukkit.getServicesManager().load(EconomyAPI::class.java) ?: return
        val viewer = Bukkit.getPlayer(player) ?: return
        host.reply(viewer, economy.balance(player), { true }) { amount: java.math.BigDecimal ->
            onBalanceChanged(player, amount)
        }
    }

    private fun stamp(viewer: CommandSender?, millis: Long): String =
        runCatching {
            java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatterHolder.SHORT)
        }.getOrDefault(messages.text(viewer, "profile-unknown"))

    private object DateTimeFormatterHolder {
        val SHORT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}