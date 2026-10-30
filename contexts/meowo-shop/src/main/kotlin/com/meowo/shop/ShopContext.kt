package com.meowo.shop

import com.meowo.api.EconomyAPI
import com.meowo.api.EconomyException
import com.meowo.api.UIAPI
import com.meowo.api.UIBindings
import com.meowo.platform.*
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * 喵达 ModaCat 商店：把矿石换成钱。
 *
 * | 调用 | 做什么 |
 * |---|---|
 * | `/sell` | 卖掉背包里所有矿石，**先问一次** |
 * | `/sell all <矿石>` | 只卖这一种（价目表左键） |
 * | `/sell hand` | 只卖主手那一叠（价目表右键） |
 * | `/sell gui` | 打开价目表 |
 *
 * ### 为什么卖光要问一次
 *
 * `/sell` 没有参数，意思只能是"背包里所有能卖的东西"，而背包里常常还有别的东西。一次误触
 * 把整包矿换成钱之后要一个一个买回来，所以这一条走确认页；**明确点了某一格**则不问——
 * 那一格写的就是"这一种全卖"，玩家已经说清楚了。
 *
 * ### 钱和物品的顺序
 *
 * 先取货、再付款，付款失败就把货原样还回去，而不是反过来。少付一次比多付一次轻：物品会
 * 回到玩家手里，而钱不会自己长回来。取货与付款都只在主线程动背包，[host] 的
 * [PlatformHost.mainThread] 是唯一一次跨线程的边界。
 *
 * 价格表在 [ShopSettings]，回收算法在 [ShopMath]；两者都不碰 Bukkit 的事件，所以价格的规则
 * 能用纯数据钉住。
 */
class ShopContext(
    private val host: PlatformHost,
    private val settings: ShopSettings = ShopSettings(ShopSettings.DEFAULT),
    /** Resolved per open, so the composition root's ordering cannot leave a stale null behind. */
    private val ui: () -> UIAPI? = { Bukkit.getServicesManager().load(UIAPI::class.java) },
) : Context {

    override val id = "shop"
    private val active = AtomicBoolean(false)
    private lateinit var economy: EconomyAPI
    private val resources = mutableListOf<AutoCloseable>()

    /** Players currently looking at the price list, so a sale can redraw it. */
    private val viewing = mutableSetOf<UUID>()

    override fun start() {
        economy = requireNotNull(Bukkit.getServicesManager().load(EconomyAPI::class.java)) { "Shop requires economy" }
        active.set(true)
        resources += host.commands.register("sell", "shop", object : Route {
            override val permission = "meowo.shop"
            override fun execute(sender: CommandSender, args: List<String>) = sell(sender, args)
            override fun complete(sender: CommandSender, args: List<String>): List<String> = complete(sender, args)
        })
    }

    private fun sell(sender: CommandSender, args: List<String>) {
        val player = sender as? Player ?: run { host.messages.send(sender, "player-only"); return }
        if (!active.get() || !player.isOnline) return
        val head = args.firstOrNull()
        when {
            args.isEmpty() -> confirmSellAll(player)
            head.equals("gui", true) -> openShop(player)
            head.equals("hand", true) && args.size == 1 -> sellHand(player)
            head.equals("all", true) && args.isEmpty() -> confirmSellAll(player)
            head.equals("all", true) && args.size == 2 -> sellKind(player, ore(args[1]))
            args.size == 1 -> sellKind(player, ore(args[0]))
            else -> usage(player)
        }
    }

    /**
     * The verbs are offered before the ore names, because the verbs are the short list and a
     * player typing `/sell <TAB>` is usually after one of the four shapes.
     */
    private fun complete(sender: CommandSender, args: List<String>): List<String> {
        val head = args.firstOrNull()
        if (head == null) return listOf("gui", "hand", "all")
        if (args.size == 1) return oreNames().filter { it.startsWith(head, true) }
        return emptyList()
    }

    private fun usage(player: Player) {
        host.messages.send(player, "shop-usage")
        host.messages.send(player, "shop-usage-forms")
    }

    /** The material behind a typed name, or null when it is not something this shop buys. */
    private fun ore(name: String): Material? = Material.matchMaterial(name)?.takeIf { settings.priceOf(it) != null }

    /** Every ore name a player may sell, cheapest first. */
    private fun oreNames(): List<String> = settings.catalogue().map { it.first.name }

    // ── the price list ─────────────────────────────────────────────────────────────────────

    /**
     * One cell per ore, cheapest first.
     *
     * The left click sells every stack of that ore and the right click sells the stack in the
     * main hand — two gestures because "把手上这叠卖掉" and "这一种我都不想留了" are different
     * intentions, and forcing both through one of them makes the other wrong.
     */
    private fun openShop(player: Player) {
        if (ui() == null) { host.messages.send(player, "shop-unavailable"); return }
        viewing.add(player.uniqueId)
        val elements = settings.catalogue().map { (ore, price) ->
            val held = held(player, ore)
            UIAPI.UIElement(
                icon = ore.name,
                name = text(player, "shop-cell-name", label(player, ore)),
                lore = listOf(
                    text(player, "shop-cell-price", host.money(price)),
                    text(player, "shop-cell-held", held),
                    text(player, "shop-cell-gestures"),
                ),
                action = "sell all ${ore.name}",
                actionRight = "sell hand",
                // Glint only when the player actually has some: it is the "this one is worth
                // clicking" signal, and lighting up every cell for an empty bag is noise.
                glint = held > 0,
            )
        }
        ui()?.openList(
            player.uniqueId, SCREEN,
            elements = elements,
            bindings = UIBindings(
                onBack = { openShop(player) },
                onMainMenu = { mainMenu(player) },
                onClose = { viewing.remove(player.uniqueId); player.closeInventory() },
            ),
        )
    }

    /**
     * Where "@main-menu" leads from here.
     *
     * Through the `menu` route rather than by opening a screen: the main menu belongs to the menu
     * context, and re-opening it from here would put two copies of that layout in two modules.
     */
    private fun mainMenu(player: Player) {
        viewing.remove(player.uniqueId)
        player.closeInventory()
        player.performCommand("menu")
    }

    // ── selling ────────────────────────────────────────────────────────────────────────────

    /**
     * `/sell` and `/sell all` ask first.
     *
     * The question carries the numbers, because a confirmation that does not say what it is
     * confirming is a second click with no information in it. [settle] re-quotes at the moment of
     * the answer rather than trusting this one: the player may have dropped a stack in between,
     * and paying for an ore that is no longer there is worse than quoting twice.
     */
    private fun confirmSellAll(player: Player) {
        val quote = quote(player, null)
        if (quote.isEmpty) { host.messages.send(player, "shop-empty"); return }
        if (ui() == null) { host.messages.send(player, "shop-unavailable"); return }
        ui()?.confirm(
            player.uniqueId,
            title = text(player, "shop-confirm-title"),
            detail = text(player, "shop-confirm-detail", quote.amount, host.money(quote.total)),
            onYes = { sellAll(player) },
            // Backing out of a shop question lands on the price list, which is where a player who
            // wanted to look rather than commit would already be.
            onNo = { openShop(player) },
        )
    }

    private fun sellAll(player: Player) {
        val quote = quote(player, null)
        if (quote.isEmpty) { host.messages.send(player, "shop-empty"); return }
        settle(player, quote)
    }

    /** `/sell hand`: the one stack under the cursor, or a sentence saying what is missing. */
    private fun sellHand(player: Player) {
        val held = player.inventory.itemInMainHand
        if (held.type == Material.AIR) { host.messages.send(player, "shop-hand-empty"); return }
        val ore = settings.priceOf(held.type)
        if (ore == null) {
            host.messages.send(player, "shop-not-mine", label(player, held.type))
            return
        }
        // The hand is inventory slot 0, and the quote is taken in slot order, so a single-element
        // quote built from that slot is exactly the stack the player is holding.
        val quote = ShopMath.quote(listOf(OreStack(0, held.type, held.amount)), settings.prices, held.type)
        settle(player, quote)
    }

    private fun sellKind(player: Player, ore: Material?) {
        if (ore == null) { host.messages.send(player, "shop-unknown-ore"); return }
        val quote = quote(player, ore)
        if (quote.isEmpty) { host.messages.send(player, "shop-none-held", label(player, ore)); return }
        settle(player, quote)
    }

    /** Everything in the player's own inventory the table prices, as plain data. */
    private fun quote(player: Player, only: Material?): ShopQuote = ShopMath.quote(stacks(player), settings.prices, only)

    private fun stacks(player: Player): List<OreStack> =
        player.inventory.contents.mapIndexedNotNull { slot, item ->
            if (item == null || item.type == Material.AIR || item.amount <= 0) null
            else OreStack(slot, item.type, item.amount)
        }

    /** `sumOf` rather than `filter { it.amount }`: a null slot is a hole in the bag, not an error. */
    private fun held(player: Player, ore: Material): Int =
        player.inventory.contents.sumOf { stack -> if (stack != null && stack.type == ore) stack.amount else 0 }

    /**
     * Takes the ore, then pays for it.
     *
     * The removal is the part that cannot be undone by a retry, so it happens first and the
     * payment is what can fail: a failure puts every stack back exactly where it was. Paying
     * first and removing second would be the other way round, and the failure there — money out,
     * ore still in the bag — is the one a player would notice.
     */
    private fun settle(player: Player, quote: ShopQuote) {
        if (quote.isEmpty) { host.messages.send(player, "shop-empty"); return }
        val taken = remove(player, quote)
        economy.deposit(player.uniqueId, quote.total).whenComplete { balance, failure ->
            if (failure != null) {
                val cause = if (failure is CompletionException) failure.cause ?: failure else failure
                if (cause !is EconomyException) host.plugin.logger.log(Level.SEVERE, "Shop payment failed", cause)
                host.mainThread(active::get) {
                    restore(player, taken)
                    host.messages.send(player, economyKey(cause))
                }
                return@whenComplete
            }
            host.mainThread(active::get) {
                if (!player.isOnline) return@mainThread
                for (line in quote.lines) {
                    host.messages.send(player, "shop-sold-line", label(player, line.material), line.amount, host.money(line.sum))
                }
                host.messages.send(player, "shop-sold", quote.amount, host.money(quote.total), host.money(balance))
                // The counts on the price list are now stale, so a player looking at it sees the
                // new numbers instead of having to close and reopen it.
                if (player.uniqueId in viewing) openShop(player)
            }
        }
    }

    /** The economy failure a player is shown, mirroring [PlatformHost.reply]'s own wording. */
    private fun economyKey(cause: Throwable): String =
        if (cause is EconomyException) "error-${cause.code.name.lowercase(Locale.ROOT)}" else "internal-error"

    /** Empties each quoted stack by exactly what the quote counted, leaving any remainder. */
    private fun remove(player: Player, quote: ShopQuote): List<Pair<Int, OreStack>> {
        val taken = mutableListOf<Pair<Int, OreStack>>()
        for (line in quote.lines) for (stack in line.taken) {
            val current = player.inventory.getItem(stack.slot) ?: continue
            // Another plugin may have changed the slot between the quote and the removal; taking a
            // stack that is no longer the same ore would sell a player's sword for ore money.
            if (current.type != stack.material || current.amount <= 0) continue
            val count = minOf(current.amount, stack.amount)
            taken += stack.slot to OreStack(stack.slot, stack.material, count)
            val left = current.amount - count
            if (left <= 0) player.inventory.setItem(stack.slot, null)
            else {
                val shrunk = current.clone()
                shrunk.amount = left
                player.inventory.setItem(stack.slot, shrunk)
            }
        }
        return taken
    }

    /** Hands back everything a failed payment took, at the scale the inventory has room for. */
    private fun restore(player: Player, taken: List<Pair<Int, OreStack>>) {
        for ((slot, stack) in taken) {
            player.inventory.setItem(slot, ItemStack(stack.material, stack.amount))
        }
        host.messages.send(player, "shop-refunded")
    }

    /**
 * The ore's name, in the player's own language.
 *
 * A language key per material rather than the client's own item name, because this server already
 * names everything else through the bundle — and an ore an operator adds to `shop.prices` has no
 * key yet. That case falls back to the readable material name instead of printing `shop-item-…`
 * at the player.
 */
private fun label(viewer: CommandSender, material: Material): String {
        val key = "shop-item-${material.name.lowercase(Locale.ROOT)}"
        val text = host.messages.text(viewer, key)
        return if (text == key) material.name.lowercase(Locale.ROOT).replace('_', ' ') else text
    }

    private fun text(player: Player, key: String, vararg args: Any): String = host.messages.text(player, key, *args)

    override fun close() {
        active.set(false)
        viewing.clear()
        resources.asReversed().forEach { it.close() }
        resources.clear()
    }

    private companion object {
        /** The screen id in `ui.yml`. */
        const val SCREEN = "shop"
    }
}