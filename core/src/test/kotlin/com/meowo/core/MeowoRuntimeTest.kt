package com.meowo.core

import com.meowo.api.EconomyAPI
import com.meowo.api.SettlementAPI
import com.meowo.teleport.TeleportContext
import com.meowo.teleport.TeleportSettings
import com.meowo.platform.*
import com.meowo.spawn.SpawnContext
import com.meowo.spawn.SpawnSettings
import com.meowo.hat.HatContext
import com.meowo.hat.HatSettings
import com.meowo.shared.event.EventBus
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.util.Locale
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.mockbukkit.mockbukkit.world.WorldMock
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.inventory.*
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.mockbukkit.mockbukkit.inventory.PlayerInventoryViewMock
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.inventory.ItemStack
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.command.MessageTarget
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

open class TestPlugin : JavaPlugin()

/** Slots of the top half standing in for the crafting grid of the own-inventory view. */
private const val CRAFTING_GRID = 9

/** Hotbar slot 0 is player inventory index 0, which is also the slot the main hand reads. */
private const val HOTBAR_FIRST_INDEX = 0

/** Player inventory indices: the three storage rows, then the hotbar. */
private val OWN_SLOTS = listOf(0, 8, 9, 26, 31, 35)

/** Shared across test instances: JUnit creates a new instance per method, and
 *  MockBukkit#addSimpleWorld throws on a duplicate name. */
private var spawnWorldCounter = 0

class MeowoRuntimeTest {
    private lateinit var server: ServerMock
    private lateinit var plugin: TestPlugin
    private lateinit var runtime: MeowoRuntime
    private lateinit var ui: FakeUI
    @BeforeEach fun setup() {
        server=MockBukkit.mock()
        plugin=MockBukkit.loadWith(TestPlugin::class.java, java.io.File("build/resources/main/plugin.yml"))
        // MockBukkit does not register descriptor permissions like the real Paper loader.
        plugin.description.permissions.forEach { server.pluginManager.addPermission(it) }
        plugin.saveDefaultConfig()
        configure { it.set("contexts.render",false) }
        // The double resolves text through the runtime's own language state, so a locale reload is visible
        // to it — a separate Messages instance would keep the language it was created with.
        runtime=MeowoRuntime(plugin) { ui }
        ui=FakeUI(runtime.messages)
        runtime.start()
    }
    @AfterEach fun teardown() { try { if (::runtime.isInitialized) runtime.close() } finally { MockBukkit.unmock() } }
    private fun configure(action: (YamlConfiguration)->Unit) {
        val file=plugin.dataFolder.resolve("config.yml")
        val yaml=YamlConfiguration.loadConfiguration(file); action(yaml); yaml.save(file)
    }
    private fun api() = requireNotNull(Bukkit.getServicesManager().load(EconomyAPI::class.java))
    /**
     * MockBukkit gives every PlayerMock a ENGLISH client locale, which under `language-mode:
     * player` would turn this suite's Chinese assertions into English. Tests are written against
     * zh_CN, so the fixture pins that explicitly and [englishPlayer] opts in per test.
     */
    private fun addPlayer(name: String): PlayerMock {
        val player = server.addPlayer(name)
        player.setLocale(Locale.SIMPLIFIED_CHINESE)
        return player
    }
    private fun PlayerMock.asEnglishClient() { setLocale(Locale.ENGLISH) }
    private fun balance(id: UUID): BigDecimal {
        val result=api().balance(id).toCompletableFuture().get(5,TimeUnit.SECONDS)
        server.scheduler.performTicks(1)
        return result
    }
    @Test fun `commands deposit withdraw set and permission checks`() {
        val player=addPlayer("Steve")
        assertEquals(BigDecimal("0.00"),balance(player.uniqueId))
        server.dispatchCommand(server.consoleSender,"meowo eco give Steve 100")
        assertEquals(BigDecimal("100.00"),balance(player.uniqueId))
        server.dispatchCommand(player,"meowo eco give Steve 100")
        assertEquals(BigDecimal("100.00"),balance(player.uniqueId))
        server.dispatchCommand(server.consoleSender,"meowo eco take Steve 20.50")
        assertEquals(BigDecimal("79.50"),balance(player.uniqueId))
        server.dispatchCommand(server.consoleSender,"meowo eco take Steve 100")
        assertEquals(BigDecimal("79.50"),balance(player.uniqueId))
        server.dispatchCommand(server.consoleSender,"meowo eco set Steve 12.34")
        assertEquals(BigDecimal("12.34"),balance(player.uniqueId))
        for (amount in listOf("-1","NaN","1e3","0.001","9999999999999")) server.dispatchCommand(server.consoleSender,"meowo eco give Steve $amount")
        assertEquals(BigDecimal("12.34"),balance(player.uniqueId))
        server.dispatchCommand(player,"meowo eco balance")
        balance(player.uniqueId)
        assertNotNull(player.nextMessage())
    }
    @Test fun `menu refresh and context disable release API and routes`() {
        val player=addPlayer("Alex")
        api().deposit(player.uniqueId,BigDecimal("5")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        server.dispatchCommand(player,"meowo menu"); balance(player.uniqueId)
        val page=requireNotNull(ui.page) { "the menu must be opened through the UI service" }
        assertEquals(6,page.rows)
        assertEquals(Material.GOLD_NUGGET,page.buttons[24]!!.item.type)
        // zh_CN must not fall back to the English title.
        assertTrue(page.title.contains("菜单"), "the menu title must follow the language file, was: ${page.title}")
        configure { it.set("contexts.economy",false); it.set("contexts.menu",false) }
        server.dispatchCommand(server.consoleSender,"meowo reload")
        assertNull(Bukkit.getServicesManager().load(EconomyAPI::class.java))
        assertNotEquals(54,player.openInventory.topInventory?.size)
        server.dispatchCommand(player,"meowo eco balance")
        configure { it.set("contexts.economy",true); it.set("contexts.menu",true); it.set("locale","en_US") }
        server.dispatchCommand(server.consoleSender,"meowo reload")
        assertEquals(BigDecimal("5.00"),balance(player.uniqueId))
        // Per-player mode: an English client reads the English title even though the server
        // default is still zh_CN until the config change above.
        player.asEnglishClient()
        server.dispatchCommand(player,"meowo menu"); balance(player.uniqueId)
        // The window is the UI service's, so its title is what the service was asked for.
        val english=requireNotNull(ui.page) { "the menu must be opened through the UI service" }
        assertTrue(english.title.contains("Menu"),"an English client must get the English title, was: ${english.title}")
        player.setLocale(Locale.SIMPLIFIED_CHINESE)
        server.dispatchCommand(player,"meowo menu"); balance(player.uniqueId)
        val chinese=requireNotNull(ui.page) { "the menu must be reopened through the UI service" }
        assertTrue(chinese.title.contains("菜单"),"switching the client back must restore the Chinese title, was: ${chinese.title}")
    }
    @Test fun `each player reads messages in their own client language`() {
        val zh = addPlayer("ZhUser")
        val en = addPlayer("EnUser")
        en.asEnglishClient()
        api().deposit(zh.uniqueId, BigDecimal("1.00")).toCompletableFuture().get(5, TimeUnit.SECONDS)
        api().deposit(en.uniqueId, BigDecimal("2.00")).toCompletableFuture().get(5, TimeUnit.SECONDS)

        server.dispatchCommand(zh, "meowo eco balance"); balance(zh.uniqueId)
        server.dispatchCommand(en, "meowo eco balance"); balance(en.uniqueId)
        val zhLine = generateSequence { zh.nextMessage() }.toList().joinToString("\n")
        val enLine = generateSequence { en.nextMessage() }.toList().joinToString("\n")
        assertTrue(zhLine.contains("余额"), "a zh_CN client must be answered in Chinese: $zhLine")
        assertTrue(enLine.contains("balance"), "an en_US client must be answered in English: $enLine")
        assertFalse(enLine.contains("余额"), "the English client must not fall back to Chinese: $enLine")
    }

    @Test fun `server mode pins every client to the configured locale`() {
        val en = addPlayer("EnUser2")
        en.asEnglishClient()
        configure { it.set("language-mode", "server"); it.set("locale", "zh_CN") }
        server.dispatchCommand(server.consoleSender, "meowo reload")
        api().deposit(en.uniqueId, BigDecimal("3.00")).toCompletableFuture().get(5, TimeUnit.SECONDS)
        server.dispatchCommand(en, "meowo eco balance"); balance(en.uniqueId)
        val line = generateSequence { en.nextMessage() }.toList().joinToString("\n")
        assertTrue(line.contains("余额"), "server mode must ignore the client language: $line")
    }

    @Test fun `an unknown client locale falls back to the configured default`() {
        val p = addPlayer("Weird")
        p.setLocale(Locale.forLanguageTag("fr"))
        api().deposit(p.uniqueId, BigDecimal("4.00")).toCompletableFuture().get(5, TimeUnit.SECONDS)
        server.dispatchCommand(p, "meowo eco balance"); balance(p.uniqueId)
        val line = generateSequence { p.nextMessage() }.toList().joinToString("\n")
        assertTrue(line.contains("余额"), "an unshipped locale must fall back to zh_CN: $line")
    }

    @Test fun `an invalid language mode is rejected without disturbing the running state`() {
        val original = api()
        configure { it.set("language-mode", "klingon") }
        server.dispatchCommand(server.consoleSender, "meowo reload")
        assertSame(original, api())
        assertNotNull(server.consoleSender.nextMessage(), "the rejected reload must be reported")
    }

    @Test fun `invalid reload preserves running services and repeated reload does not duplicate subscriptions`() {
        val original=api()
        configure { it.set("locale","invalid") }
        server.dispatchCommand(server.consoleSender,"meowo reload")
        assertSame(original,api())
        configure { it.set("locale","en_US") }
        repeat(3) { server.dispatchCommand(server.consoleSender,"meowo reload") }
        assertSame(original,api())
        server.dispatchCommand(server.consoleSender,"meowo info")
        server.dispatchCommand(server.consoleSender,"meowo help")
        assertNotNull(server.consoleSender.nextMessage())
    }
    @Test fun `unprivileged users cannot inspect other wallets or reload`() {
        val a=addPlayer("Reader"); val b=addPlayer("Other")
        server.dispatchCommand(a,"meowo eco balance Other")
        assertTrue(a.nextMessage()!!.contains("权限"))
        server.dispatchCommand(a,"meowo reload")
        assertTrue(a.nextMessage()!!.contains("权限"))
        assertEquals(BigDecimal("0.00"),balance(b.uniqueId))
    }
    /**
     * Waits in **real time only**, moving the server clock by a single tick.
     *
     * The economy and teleport paths complete on other threads, so real time is what makes their work
     * visible — but a countdown is measured in ticks, and tests assert both that nothing has moved yet and
     * that a potion effect has an exact remaining duration. So this must not advance the clock.
     */
    private fun settle() {
        repeat(FLUSH_MILLIS) { Thread.sleep(1) }
        server.scheduler.performTicks(1)
    }

    /**
     * [settle], plus enough *interleaved* server time for a countdown to elapse and its move to finish.
     *
     * Interleaved matters: sleeping first and then running 60 ticks at once lets a future complete *after*
     * the ticks, so the continuation it schedules never runs. Alternate them.
     */
    private fun flush(@Suppress("UNUSED_PARAMETER") id: java.util.UUID) {
        repeat(FLUSH_ROUNDS) {
            Thread.sleep(1)
            server.scheduler.performTicks(1)
        }
    }

    /** Empties a recipient's message queue. */
    private fun drain(target: MessageTarget) {
        while (target.nextMessage() != null) { }
    }

    private fun settlements(): SettlementAPI = requireNotNull(Bukkit.getServicesManager().load(SettlementAPI::class.java))

    private companion object {
        /** ~150 ms of real time: long enough for the async half of a command to land. */
        const val FLUSH_MILLIS = 150

        /** Sixty rounds of "sleep a millisecond, tick once". */
        const val FLUSH_ROUNDS = 60
    }

    @Test fun `pay aliases transfer funds reject self and display configured currency`() {
        val payer=addPlayer("Payer"); val receiver=addPlayer("Receiver")
        api().setBalance(payer.uniqueId,BigDecimal("20")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        server.dispatchCommand(payer,"pay Receiver 2.50"); flush(payer.uniqueId)
        assertEquals(BigDecimal("17.47"),balance(payer.uniqueId))
        assertEquals(BigDecimal("2.50"),balance(receiver.uniqueId))
        assertTrue(payer.nextMessage()!!.contains("2.50 ฅ"), "the symbol must follow the number")
        assertTrue(receiver.nextMessage()!!.contains("2.50 ฅ"), "the symbol must follow the number")
        // pay-sent / pay-received are now two lines each; clear the remainder before the next command.
        drain(payer); drain(receiver)
        server.dispatchCommand(payer,"pay Payer 1"); flush(payer.uniqueId)
        assertTrue(payer.nextMessage()!!.contains("自己"))
        server.dispatchCommand(payer,"pay Receiver 0"); flush(payer.uniqueId)
        assertEquals(BigDecimal("17.47"),balance(payer.uniqueId))
        configure { it.set("economy.currency.name","Coins"); it.set("economy.currency.symbol","$") }
        server.dispatchCommand(server.consoleSender,"meowo reload")
        while (payer.nextMessage() != null) { }
        server.dispatchCommand(payer,"meowo eco balance"); flush(payer.uniqueId)
        assertTrue(payer.nextMessage()!!.contains("17.47 $"), "symbol follows the number; the full name is only in the introduction")
        server.dispatchCommand(server.consoleSender,"pay Receiver 1")
    }
    @Test fun `daily subsidy comes only from treasury and is not repeated by reconnect or reload`() {
        val taxpayer=addPlayer("Taxpayer"); flush(taxpayer.uniqueId)
        api().setBalance(taxpayer.uniqueId,BigDecimal("5000")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        val ticket=UUID.randomUUID()
        settlements().reserveTeleport(ticket,taxpayer.uniqueId).toCompletableFuture().get(5,TimeUnit.SECONDS)
        settlements().settleTeleport(ticket,true).toCompletableFuture().get(5,TimeUnit.SECONDS)
        val visitor=addPlayer("Subsidy"); flush(visitor.uniqueId)
        assertEquals(BigDecimal("1.00"),balance(visitor.uniqueId))
        server.dispatchCommand(visitor,"meowo eco daily"); flush(visitor.uniqueId)
        assertEquals(BigDecimal("1.00"),balance(visitor.uniqueId))
        assertEquals(BigDecimal("4.00"),settlements().treasury().toCompletableFuture().get(5,TimeUnit.SECONDS))
        server.dispatchCommand(server.consoleSender,"meowo eco treasury"); flush(visitor.uniqueId)
        configure { it.set("contexts.economy",false) }; server.dispatchCommand(server.consoleSender,"meowo reload")
        assertNull(Bukkit.getServicesManager().load(SettlementAPI::class.java))
        configure { it.set("contexts.economy",true) }; server.dispatchCommand(server.consoleSender,"meowo reload")
        flush(visitor.uniqueId)
        assertEquals(BigDecimal("1.00"),balance(visitor.uniqueId))
    }
    @Test fun `invalid tax currency and timezone reloads preserve the active policy`() {
        val player=addPlayer("Config"); flush(player.uniqueId)
        val original=api()
        for ((key,value) in listOf("economy.teleport-tax.rate" to "1.01", "economy.teleport-tax.minimum" to "999",
            "economy.currency.name" to "", "economy.daily-subsidy.timezone" to "wrong", "teleport.request-seconds" to 0)) {
            configure { it.set(key,value) }; server.dispatchCommand(server.consoleSender,"meowo reload")
            assertSame(original,api()); configure { it.set(key,null) }
        }
        configure { it.set("economy.teleport-tax.rate","0.01"); it.set("economy.teleport-tax.minimum","0"); it.set("economy.daily-subsidy.enabled",false) }
        server.dispatchCommand(server.consoleSender,"meowo reload")
        api().setBalance(player.uniqueId,BigDecimal("1500")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        val ticket=UUID.randomUUID()
        assertEquals(BigDecimal("15.00"),settlements().reserveTeleport(ticket,player.uniqueId).toCompletableFuture().get(5,TimeUnit.SECONDS).fee)
        settlements().settleTeleport(ticket,true).toCompletableFuture().get(5,TimeUnit.SECONDS)
        assertEquals(BigDecimal("0.00"),settlements().claimDaily(player.uniqueId).toCompletableFuture().get(5,TimeUnit.SECONDS))
    }
    private fun teleportHarness(move: (Player,Player)->Boolean, particles: Boolean = false, countdown: Int = 0, action: (CommandRouter,TeleportContext)->Unit) {
        val bus=EventBus { _,error -> throw error }; val messages=Messages(plugin).apply { load("zh_CN") }
        val router=CommandRouter(messages)
        val context=TeleportContext(PlatformHost(plugin,bus,router,messages),TeleportSettings(5,particles,countdown),move)
        context.start()
        try { action(router,context) } finally { context.close(); bus.close() }
    }
    private fun command(router: CommandRouter, sender: org.bukkit.command.CommandSender, text: String) {
        router.onCommand(sender,plugin.getCommand("meowo")!!,"meowo",text.split(" ").toTypedArray())
    }
    private fun spawnHarness(
        settings: SpawnSettings = SpawnSettings(),
        move: (Player, Location) -> Boolean = { _, _ -> true },
        action: (CommandRouter, SpawnContext, org.bukkit.World) -> Unit,
    ) {
        val bus=EventBus { _,error -> throw error }; val messages=Messages(plugin).apply { load("zh_CN") }
        val router=CommandRouter(messages)
        val context=SpawnContext(PlatformHost(plugin,bus,router,messages),settings,move).also { it.useMove(move); it.loadChunksSynchronously() }
        val world=server.addSimpleWorld("spawn-test-${spawnWorldCounter++}")
        // A brand new WorldMock has neither a spawn location nor any loaded chunk, and both
        // getSpawnLocation() and the safety guard would blow up before the test even starts.
        world.setSpawnLocation(buildSpawn(world))
        world.getChunkAt(0,0)
        context.start()
        try { action(router,context,world) } finally { context.close(); bus.close() }
    }
    /** Builds a small pillar at [x]/[z] and returns a standable world-spawn location on top of it. */
    private fun buildSpawn(world:org.bukkit.World, x:Int=0, z:Int=0, top:Int=4): Location {
        repeat(top) { y -> world.getBlockAt(x,y,z).type=Material.STONE }
        world.getBlockAt(x,top,z).type=Material.GRASS_BLOCK
        repeat(2) { y -> world.getBlockAt(x,top+1+y,z).type=Material.AIR }
        return Location(world,x.toDouble(),(top+1).toDouble(),z.toDouble())
    }
    @Test fun `accepted tpa charges requester once and failed movement refunds`() {
        val from=addPlayer("Traveller"); val to=addPlayer("Host")
        flush(from.uniqueId)
        api().setBalance(from.uniqueId,BigDecimal("2000")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        var moved=0
        teleportHarness({ a,b -> assertEquals(from,a); assertEquals(to,b); moved++; true }) { router,_ ->
            command(router,from,"tpa Host"); command(router,to,"tpa accept Traveller"); command(router,to,"tpa accept Traveller")
            flush(from.uniqueId)
            assertEquals(1,moved); assertEquals(BigDecimal("1998.00"),balance(from.uniqueId))
            assertEquals(BigDecimal("2.00"),settlements().treasury().toCompletableFuture().get(5,TimeUnit.SECONDS))
            assertEquals(BigDecimal("0.00"),balance(to.uniqueId))
        }
        teleportHarness({ _,_ -> false }) { router,_ ->
            command(router,from,"tpa Host"); command(router,to,"tpa accept"); flush(from.uniqueId)
            assertEquals(BigDecimal("1998.00"),balance(from.uniqueId))
            assertEquals(BigDecimal("2.00"),settlements().treasury().toCompletableFuture().get(5,TimeUnit.SECONDS))
        }
    }
    @Test fun `tpa denial cancellation ambiguous requests and insufficient money never move players`() {
        val a=addPlayer("Zero"); val b=addPlayer("Second"); val target=addPlayer("Target")
        var moved=0
        teleportHarness({ _,_ -> moved++; true }) { router,_ ->
            command(router,a,"tpa Target"); command(router,b,"tpa Target"); command(router,target,"tpa accept")
            assertTrue(target.nextMessage()!!.contains("Zero"))
            command(router,target,"tpa deny Zero"); command(router,b,"tpa cancel")
            command(router,a,"tpa Target"); command(router,target,"tpa accept Zero"); settle()
            assertEquals(0,moved); assertEquals(BigDecimal("0.00"),balance(a.uniqueId))
            command(router,a,"tpa Zero"); command(router,a,"tpa Nobody"); command(router,a,"tpa")
            command(router,server.consoleSender,"tpa Target")
            assertTrue(router.onTabComplete(a,plugin.getCommand("meowo")!!,"meowo",arrayOf("tpa", "T")).contains("Target"))
        }
    }
    @Test fun `closing teleport during reserved payment refunds and suppresses later movement`() {
        val a=addPlayer("Closing"); val b=addPlayer("Destination"); settle()
        api().setBalance(a.uniqueId,BigDecimal("100")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        var moved=0
        teleportHarness({ _,_ -> moved++; true }) { router,context ->
            command(router,a,"tpa Destination"); command(router,b,"tpa accept Closing")
            context.close(); settle()
            assertEquals(0,moved); assertEquals(BigDecimal("100.00"),balance(a.uniqueId))
        }
    }

    /**
     * `/back` on its own router, with the move injected.
     *
     * MockBukkit's `teleportAsync` throws `UnimplementedOperationException`, so without the seam the
     * only reachable branch would be the one where the move explodes — which is exactly the branch
     * a harness would then mistake for the normal path. The fare has its own decision after that:
     * it follows the outcome of the move, so both outcomes need to be reachable.
     */
    private fun backHarness(
        countdown: Int = 0,
        moveBack: (Player, Location) -> CompletionStage<Boolean> = { _, _ -> CompletableFuture.completedFuture(true) },
        action: (CommandRouter, PlatformHost) -> Unit,
    ) {
        val bus=EventBus { _,error -> throw error }; val messages=Messages(plugin).apply { load("zh_CN") }
        val router=CommandRouter(messages)
        val host=PlatformHost(plugin,bus,router,messages)
        val context=TeleportContext(host,TeleportSettings(5,false,countdown),{ _,_ -> true },moveBack)
        context.start()
        try { action(router,host) } finally { context.close(); bus.close() }
    }
    /** A point in the player's world to record as the return destination. */
    private fun awayPoint(player: Player, x: Double = 100.0) = Location(player.world,x,64.0,100.0)

    @Test fun `back charges the return trip once and moves the player to the recorded point`() {
        val player=addPlayer("Homeward"); flush(player.uniqueId)
        api().setBalance(player.uniqueId,BigDecimal("10000")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        val seen=mutableListOf<Location>()
        backHarness(moveBack={ _,destination -> seen+=destination; CompletableFuture.completedFuture(true) }) { router,host ->
            host.travelJournal.died(player.uniqueId,awayPoint(player))
            command(router,player,"back"); flush(player.uniqueId)
            val said=generateSequence { player.nextMessage() }.toList().joinToString("\n")
            assertTrue(said.contains("回来了"),"a completed trip must say so: $said")
            assertEquals(100.0,seen.single().x,"the move must go to the recorded death point")
            // 0.1% of 10000 is 10, which is also the floor: exactly one fare, no double charge.
            assertEquals(BigDecimal("9990.00"),balance(player.uniqueId))
        }
    }

    /**
     * The fare used to be committed up front, in parallel with the move, so a teleport that came
     * back `false` still charged in full while telling the player it had failed. The money has to
     * follow the outcome, not precede it.
     */
    @Test fun `a back teleport that does not land is not charged`() {
        val player=addPlayer("Stuck"); flush(player.uniqueId)
        api().setBalance(player.uniqueId,BigDecimal("10000")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        backHarness(moveBack={ _,_ -> CompletableFuture.completedFuture(false) }) { router,host ->
            host.travelJournal.died(player.uniqueId,awayPoint(player))
            command(router,player,"back"); flush(player.uniqueId)
            val said=generateSequence { player.nextMessage() }.toList().joinToString("\n")
            assertTrue(said.contains("退回"),"the player must be told the fare came back: $said")
            assertEquals(BigDecimal("10000.00"),balance(player.uniqueId),"a failed move must be refunded")
        }
    }

    /**
     * The record was written when the command was typed, before anybody checked whether the player
     * could pay. A refused trip therefore overwrote the return point with the spot the player was
     * already standing on, and the next `/back` charged them to stand still. It also passed `{0}`
     * to no one, so the price came out as a literal placeholder.
     */
    @Test fun `a broke player is told the price and keeps the return point`() {
        val player=addPlayer("Pauper"); flush(player.uniqueId)
        api().setBalance(player.uniqueId,BigDecimal("5")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        backHarness { router,host ->
            host.travelJournal.died(player.uniqueId,awayPoint(player))
            command(router,player,"back"); flush(player.uniqueId)
            val said=generateSequence { player.nextMessage() }.toList().joinToString("\n")
            assertTrue(said.contains("10.00"),"the price must be named, not left as a placeholder: $said")
            assertFalse(said.contains("{0}"),"an unfilled placeholder is not a price: $said")
            assertEquals(100.0,host.travelJournal.destinationFor(player.uniqueId)!!.x,
                "a refused trip must leave the return point exactly where it was")
            assertEquals(BigDecimal("5.00"),balance(player.uniqueId))
        }
    }

    /**
     * `/spawn` and `/home` both refuse a dead or spectating player before they touch escrow; `/back`
     * did not, so the player was told "charged, back in 3s" and refunded three seconds later. The
     * refusal belongs at the command, where it costs one line.
     */
    @Test fun `a dead or spectating player is refused before any fare is reserved`() {
        val player=addPlayer("Ghost"); flush(player.uniqueId)
        api().setBalance(player.uniqueId,BigDecimal("10000")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        backHarness { router,host ->
            host.travelJournal.died(player.uniqueId,awayPoint(player))
            player.gameMode=org.bukkit.GameMode.SPECTATOR
            command(router,player,"back"); flush(player.uniqueId)
            val said=generateSequence { player.nextMessage() }.toList().joinToString("\n")
            assertTrue(said.contains("无法回溯"),"the refusal must say why: $said")
            assertEquals(BigDecimal("10000.00"),balance(player.uniqueId),"nothing may be reserved")
            assertEquals(100.0,host.travelJournal.destinationFor(player.uniqueId)!!.x)
        }
    }

    /**
     * The other three travel commands all cancel a warm-up when the player walks off the spot, and
     * `/back` did not — a rescue that follows a running player is a free chase, and the three-second
     * countdown meant nothing. The origin is fixed when the fare is reserved, so a head turn or the
     * client's own drift correction cannot cancel, and neither can a slow walk drag the tolerance
     * along until the countdown is unbreakable.
     */
    @Test fun `walking during the back countdown cancels the trip and refunds the fare`() {
        val player=addPlayer("Fidget"); flush(player.uniqueId)
        api().setBalance(player.uniqueId,BigDecimal("10000")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        val moves=mutableListOf<Location>()
        backHarness(countdown=3, moveBack={ _,destination -> moves+=destination; CompletableFuture.completedFuture(true) }) { router,host ->
            host.travelJournal.died(player.uniqueId,awayPoint(player))
            command(router,player,"back"); settle()
            assertEquals(BigDecimal("9990.00"),balance(player.uniqueId),"the fare is reserved for the countdown")

            val here=player.location
            server.pluginManager.callEvent(PlayerMoveEvent(player,here.clone(),here.clone().apply { yaw+=50f }))
            server.scheduler.performTicks(2)
            assertEquals(BigDecimal("9990.00"),balance(player.uniqueId),"a head turn is not walking")

            server.pluginManager.callEvent(PlayerMoveEvent(player,here.clone(),here.clone().add(5.0,0.0,0.0)))
            flush(player.uniqueId)
            server.scheduler.performTicks(80)

            assertTrue(moves.isEmpty(),"a cancelled countdown must not move the player")
            assertEquals(BigDecimal("10000.00"),balance(player.uniqueId),"a cancelled trip must be refunded")
            val said=generateSequence { player.nextMessage() }.toList().joinToString("\n")
            assertTrue(said.contains("移动"),"the cancellation must say why: $said")
        }
    }

    @Test fun `players get one chat formatted message while the console stays plain ascii`() {
        val a=addPlayer("Localized"); flush(a.uniqueId)
        // The treasury is empty, so this answers with the multi-line "not available today" notice.
        server.dispatchCommand(a,"meowo eco daily"); flush(a.uniqueId)
        val lines=generateSequence { a.nextMessage() }.toList()
        // Players get ONE chat message carrying the line breaks inside it, not one message per line:
        // the speaker prefix belongs to the message, so repeating it on every line would be noise.
        assertEquals(1,lines.size,"a language value must be delivered as a single chat message: $lines")
        val message=lines.single()
        assertTrue(message.contains("\n"),"line breaks must survive inside the chat message: $message")
        assertTrue(message.contains("§"),"player messages must carry legacy colour codes: $message")
        assertFalse(message.contains("{0}"),"an unreplaced placeholder leaked: $message")
        // The console has no chat type and no colour, so it still receives plain split lines.
        server.dispatchCommand(server.consoleSender,"meowo eco treasury"); flush(a.uniqueId)
        val console=server.consoleSender.nextMessage()!!
        assertFalse(console.contains("§"), "console must not receive raw colour codes: $console")
        assertTrue(console.contains("eco"), "console text lost its content: $console")
        server.dispatchCommand(server.consoleSender,"meowo help"); flush(a.uniqueId)
        var helpLines=0
        while (true) {
            val line=server.consoleSender.nextMessage() ?: break
            assertFalse(line.contains("§"), "console help line leaked colour: $line")
            helpLines++
        }
        assertTrue(helpLines>1,"the help text should reach the console as several lines, got $helpLines")
    }

    /**
     * The help page must carry **readable text**, in one language, for one reader.
     *
     * Both halves of this have actually broken. A leading placeholder landed on the `{0}` of the
     * `help-line` template, so every line — header, footer and all twenty command descriptions —
     * rendered as the bare character `0`: a page that looked full and read as empty. And the command
     * list was read without a sender, so it always came from the default locale while the heading
     * followed the reader, leaving an English client with half a page in each language.
     *
     * Asserting the concrete wording is the point: a page of `▸ 0` still satisfies any check for
     * "several lines", which is what let it through the first time.
     */
    @Test fun `the help page renders readable text in the reader's own language`() {
        val reader = addPlayer("Reader")
        val zh = fullHelp(reader)
        assertFalse(zh.contains("▸ 0"), "the template placeholder must be replaced, not printed: $zh")
        assertFalse(zh.contains("{0}"), "no placeholder may leak into the page: $zh")
        assertTrue(zh.contains("命令帮助"), "the Chinese header must be there: $zh")
        assertTrue(zh.contains("查看版本与实际启用的模块"), "and so must a command description: $zh")

        val english = addPlayer("Anglo")
        english.asEnglishClient()
        val en = fullHelp(english)
        assertTrue(en.contains("command reference"), "the header must follow the reader: $en")
        assertTrue(en.contains("version and actually active contexts"), "and so must the command list: $en")
        assertFalse(en.contains("查看版本"), "an English reader must not get the Chinese list: $en")
        assertFalse(en.contains("命令帮助"), "nor the Chinese header: $en")
    }

    @Test fun `speaker names follow the four-letter XxxCat rule and never leak a raw key`() {
        val messages=Messages(plugin).apply { load("zh_CN") }
        // Every id the plugin knows, read from the table itself. A hand-written list would have to
        // be kept in step with the table by hand, and would quietly stop covering the newest
        // contexts — which is exactly how the shop and pomodoro cats went missing.
        for (id in messages.speakerIds) {
            val speaker=messages.speaker(id)
            assertFalse(speaker.startsWith("speaker-"),"id $id resolved to a raw key: $speaker")
            assertTrue(speaker.endsWith("Cat"),"id $id resolved to a non-Cat speaker: $speaker")
        }
        // The width rule is four characters plus `Cat`, so the chat column lines up.
        //
        // Three cats cannot meet it: "sit" and "hat" have no four-character synonym, and "invsee"
        // has no shorter one. They are named here rather than left to chance, so a **new** speaker
        // that breaks the rule still fails. The old version of this test looked at six ids, and all
        // six happened to be the right width, so the three that are not went unnoticed.
        val narrower = mapOf("sit" to "SitCat", "hat" to "HatCat", "invsee" to "InvCat")
        for (id in messages.speakerIds) {
            val speaker=messages.speaker(id)
            val expected = narrower[id]?.length ?: 7
            assertEquals(expected,speaker.length,"id $id resolved to a speaker of unexpected width: $speaker")
        }
        // An id with no mapping must not render as a raw language key.
        assertEquals("UnknownCat",messages.speaker("nope"))
    }

    @Test fun `every context key resolves to its own cat rather than the system cat`() {
        val messages=Messages(plugin).apply { load("zh_CN") }
        // The routing table is the production one, on purpose.
        //
        // This test used to keep its own copy of the table and feed that copy in as the *input*:
        // `messages.speaker(ownerOf(key))` never asked Messages who owns the key, so the production
        // table was never read. A copy that drifts agrees with the table that drifted with it, and
        // the test stayed green while every `spawn-*` message was signed by the system cat — the
        // accident `08 §6b` records.
        //
        // Every entry of the production table is listed here: the exact keys first, then one key per
        // prefix. Drop a prefix from `SPEAKER_PREFIXES` and this goes red.
        val expected=mapOf(
            "balance" to "EconCat", "treasury" to "EconCat",
            "daily-paid" to "EconCat", "daily-empty" to "EconCat",
            "pay-sent" to "EconCat", "pay-received" to "EconCat",
            "unavailable" to "MenuCat", "menu-title" to "MenuCat",
            "menu-balance" to "MenuCat", "menu-refresh" to "MenuCat",
            "eco-usage" to "EconCat", "error-invalid_amount" to "EconCat",
            "render-spawned" to "RendCat",
            "spawn-warmup" to "WarpCat", "spawn-unsafe" to "WarpCat",
            "seat-sit" to "SitCat", "hat-equip" to "HatCat", "nametag-marker" to "IdleCat",
            "mine-announce" to "MineCat", "rare-diamond" to "MineCat",
            "shadowbox-open" to "ShadCat",
            "home-list" to "NestCat",
            "tpa-incoming" to "TeleCat", "action-back" to "TeleCat",
            "invsee-opened" to "InvCat",
            "shop-price" to "ShopCat",
            "pomodoro-started" to "PomoCat",
        )
        for ((key,cat) in expected) {
            assertEquals(cat,messages.speaker(messages.speakerOf(key)),"key $key has the wrong owner")
        }
    }

    @Test fun `teleport rejections name the actual reason`() {
        val a=addPlayer("Solo"); val b=addPlayer("Target")
        flush(a.uniqueId); flush(b.uniqueId)
        teleportHarness({ _,_ -> true }) { router,_ ->
            command(router,a,"tpa Solo")
            assertTrue(a.nextMessage()!!.contains("自己"), "a self request must say so")
            command(router,a,"tpa Target")
            drain(a); drain(b)
            command(router,a,"tpa Target")
            assertTrue(a.nextMessage()!!.contains("待处理"), "a duplicate request must say so")
            // Naming someone who is not online is a lookup failure, not an empty inbox.
            command(router,a,"tpa accept Nobody")
            assertTrue(a.nextMessage()!!.contains("无法解析"), "an unresolvable name must be reported as such")
        }
    }

    @Test fun `the fee is reserved on accept and the move waits for the countdown`() {
        val from=addPlayer("Waiter"); val to=addPlayer("Anchor")
        flush(from.uniqueId); flush(to.uniqueId)
        api().setBalance(from.uniqueId,BigDecimal("2000")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        var moved=0
        // One second of grace period so we can observe the window before the move happens.
        teleportHarness({ _,_ -> moved++; true }, countdown=1) { router,_ ->
            command(router,from,"tpa Anchor"); drain(from); drain(to)
            assertEquals(BigDecimal("2000.00"),balance(from.uniqueId))
            command(router,to,"tpa accept Waiter")
            settle()
            // The fee is already held in escrow, but nobody has moved yet.
            assertEquals(0,moved,"the teleport must not happen before the countdown elapses")
            assertEquals(BigDecimal("1998.00"),balance(from.uniqueId))
            assertEquals(BigDecimal("0.00"),settlements().treasury().toCompletableFuture().get(5,TimeUnit.SECONDS))
            server.scheduler.performTicks(25L)   // past one second
            flush(from.uniqueId)
            assertEquals(1,moved)
            assertEquals(BigDecimal("2.00"),settlements().treasury().toCompletableFuture().get(5,TimeUnit.SECONDS))
        }
    }

    @Test fun `white chain starts before departure and nausea and darkness last through one second after arrival`() {
        val from=addPlayer("Effects"); val to=addPlayer("Destination")
        settle()
        api().setBalance(from.uniqueId,BigDecimal("100")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        val world=from.world as WorldMock
        world.clearSpawnedParticles()
        var moved=0
        teleportHarness({ _,_ -> moved++; true }, particles=true, countdown=3) { router,_ ->
            command(router,from,"tpa Destination")
            settle()
            assertTrue(world.spawnedParticles.isEmpty(),"a pending request must not show a teleport animation")
            assertFalse(from.hasPotionEffect(PotionEffectType.NAUSEA))
            command(router,to,"tpa accept Effects")
            settle()
            assertEquals(0,moved)
            assertTrue(world.spawnedParticles.isNotEmpty(),"particles must appear while waiting, not after teleport")
            for (type in listOf(PotionEffectType.NAUSEA,PotionEffectType.DARKNESS,PotionEffectType.SLOWNESS)) {
                val effect=requireNotNull(from.getPotionEffect(type))
                assertEquals(0,effect.amplifier,"only the weakest level is applied")
                assertTrue(effect.duration in 1..80,"effects must cover the three second wait plus one second")
                assertFalse(effect.hasParticles())
                assertFalse(to.hasPotionEffect(type),"the accepting player must not receive travel effects")
            }
            assertTrue(from.getPotionEffect(PotionEffectType.DARKNESS)!!.duration in 70..80)
            assertTrue(from.getPotionEffect(PotionEffectType.NAUSEA)!!.duration in 70..80)
            assertFalse(from.hasPotionEffect(PotionEffectType.BLINDNESS))
            server.scheduler.performTicks(20L)
            assertEquals(0,moved)
            for (type in listOf(PotionEffectType.NAUSEA,PotionEffectType.DARKNESS)) assertTrue(from.hasPotionEffect(type))
            server.scheduler.performTicks(45L)
            settle()
            assertEquals(1,moved)
            for (type in listOf(PotionEffectType.NAUSEA,PotionEffectType.DARKNESS)) {
                assertTrue(from.getPotionEffect(type)!!.duration in 1..20,"effects must remain after arriving")
            }
            val charmCount=world.spawnedParticles.count { (it.data() as org.bukkit.Particle.DustOptions).color == org.bukkit.Color.WHITE }
            server.scheduler.performTicks(60L)
            assertEquals(charmCount,world.spawnedParticles.count { (it.data() as org.bukkit.Particle.DustOptions).color == org.bukkit.Color.WHITE },"white travelling runes must stop at arrival")
            val finished=world.spawnedParticles.size
            server.scheduler.performTicks(12L)
            assertEquals(finished,world.spawnedParticles.size,"arrival runes must finish too")
            assertFalse(from.hasPotionEffect(PotionEffectType.NAUSEA))
            assertFalse(from.hasPotionEffect(PotionEffectType.DARKNESS))
            assertFalse(from.hasPotionEffect(PotionEffectType.SLOWNESS))
        }
    }

    @Test fun `cancelling or closing the countdown stops its particles and preserves existing potion effects`() {
        val from=addPlayer("CancelEffects"); val to=addPlayer("AnchorEffects")
        flush(from.uniqueId); flush(to.uniqueId)
        api().setBalance(from.uniqueId,BigDecimal("100")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        val original=PotionEffect(PotionEffectType.SLOWNESS,600,2)
        from.addPotionEffect(original)
        val world=from.world as WorldMock
        var moved=0
        // Six seconds, not three: the flush below is sixty ticks, and a three-second countdown is
        // exactly sixty ticks too — so the teleport could land *during* the flush and the test would
        // be measuring the arrival animation instead of the cancellation. Longer than the flush, the
        // cancel always lands in the middle of the countdown, which is what the test is about.
        for (close in listOf(false,true)) teleportHarness({ _,_ -> moved++; true }, particles=true, countdown=6) { router,context ->
            command(router,from,"tpa AnchorEffects"); command(router,to,"tpa accept CancelEffects")
            flush(from.uniqueId)
            val remaining=from.getPotionEffect(PotionEffectType.SLOWNESS)!!
            assertEquals(original.amplifier,remaining.amplifier)
            assertTrue(remaining.duration in 300..600,"the original long effect must not be replaced or cleared")
            assertTrue(remaining.hasParticles())
            val beforeCancel=world.spawnedParticles.size
            if (close) context.close() else command(router,from,"tpa cancel")
            server.scheduler.performTicks(80L); settle()
            assertEquals(beforeCancel,world.spawnedParticles.size)
            assertEquals(0,moved)
            assertEquals(BigDecimal("100.00"),balance(from.uniqueId))
            val afterCancel=from.getPotionEffect(PotionEffectType.SLOWNESS)!!
            assertEquals(original.amplifier,afterCancel.amplifier)
            assertTrue(afterCancel.duration in 300..600)
            assertTrue(afterCancel.hasParticles())
        }
    }

    @Test fun `a broke requester can be sponsored by the accepter`() {
        val broke=addPlayer("Broke"); val host=addPlayer("Host")
        flush(broke.uniqueId); flush(host.uniqueId)
        // The applicant owes the minimum 1.00; the sponsor pays 1.00 plus 0.01 payment tax.
        api().setBalance(host.uniqueId,BigDecimal("2000")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        var moved=0
        teleportHarness({ _,_ -> moved++; true }) { router,_ ->
            command(router,broke,"tpa Host"); drain(broke); drain(host)
            command(router,host,"tpa accept Broke")
            settle()
            // The requester has nothing, so nobody moved and the accepter was offered sponsorship.
            assertEquals(0,moved)
            assertEquals(BigDecimal("0.00"),balance(broke.uniqueId))
            val offer=host.nextMessage()!!
            assertTrue(offer.contains("余额不够"), "the accepter must be told the requester is broke: $offer")
            // Accepting again reserves the fee against the accepter instead.
            command(router,host,"tpa accept Broke")
            flush(host.uniqueId); flush(broke.uniqueId)
            assertEquals(1,moved,"the sponsoring accept should complete the teleport")
            assertEquals(BigDecimal("0.00"),balance(broke.uniqueId))
            assertEquals(BigDecimal("1998.99"),balance(host.uniqueId))
            assertEquals(BigDecimal("1.01"),settlements().treasury().toCompletableFuture().get(5,TimeUnit.SECONDS))
        }
    }

    @Test fun `sponsoring is not offered again when the accepter is also broke`() {
        val broke=addPlayer("Poor"); val host=addPlayer("AlsoPoor")
        flush(broke.uniqueId); flush(host.uniqueId)
        var moved=0
        teleportHarness({ _,_ -> moved++; true }) { router,_ ->
            command(router,broke,"tpa AlsoPoor"); drain(broke); drain(host)
            command(router,host,"tpa accept Poor"); settle(); drain(host)
            command(router,host,"tpa accept Poor"); settle()
            val lines=generateSequence { host.nextMessage() }.toList()
            assertTrue(lines.any { it.contains("也不够替") }, "a second failure must not re-offer sponsorship: $lines")
            assertEquals(0,moved)
            assertEquals(BigDecimal("0.00"),balance(broke.uniqueId))
            assertEquals(BigDecimal("0.00"),balance(host.uniqueId))
        }
    }

    @Test fun `cancelling during the countdown refunds the fee and blocks the move`() {
        val from=addPlayer("Changer"); val to=addPlayer("Landing")
        flush(from.uniqueId); flush(to.uniqueId)
        api().setBalance(from.uniqueId,BigDecimal("2000")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        var moved=0
        teleportHarness({ _,_ -> moved++; true }, countdown=3) { router,_ ->
            command(router,from,"tpa Landing"); drain(from); drain(to)
            command(router,to,"tpa accept Changer")
            settle()
            assertEquals(BigDecimal("1998.00"),balance(from.uniqueId))
            // The fee is held, the move is queued: /tpacancel must undo both.
            command(router,from,"tpa cancel")
            settle(); server.scheduler.performTicks(80L); settle()
            assertEquals(0,moved,"a cancelled countdown must never move the player")
            assertEquals(BigDecimal("2000.00"),balance(from.uniqueId))
            assertEquals(BigDecimal("0.00"),settlements().treasury().toCompletableFuture().get(5,TimeUnit.SECONDS))
            // Nothing left to cancel.
            drain(from); drain(to)
            command(router,from,"tpa cancel"); flush(from.uniqueId)
            assertTrue(from.nextMessage()!!.contains("没有符合条件"))
        }
    }

    @Test fun `incoming requests publish a clickable accept and decline action`() {
        val from=addPlayer("Clicker"); val to=addPlayer("Clicked")
        flush(from.uniqueId); flush(to.uniqueId)
        teleportHarness({ _,_ -> true }) { router,_ ->
            command(router,from,"tpa Clicked"); drain(from)
            val announcement=to.nextMessage()!!
            assertTrue(announcement.contains("Clicker"), "the announcement must name the requester: $announcement")
            val actions=to.nextMessage()!!
            assertTrue(actions.contains("接受") && actions.contains("拒绝"), "both actions must be offered: $actions")
            assertFalse(actions.contains("{0}"), "an unreplaced placeholder leaked: $actions")
            command(router,from,"tpa accept Clicked"); flush(from.uniqueId)
        }
    }

    @Test fun `spawn teleports to a safe standing spot and centres the player`() {
        var arrived:Location?=null
        spawnHarness(SpawnSettings(warmupSeconds=0,cooldownSeconds=0,invulnerabilitySeconds=0),{ _,to -> arrived=to.clone(); true }) { router,_,world ->
            world.setSpawnLocation(buildSpawn(world))
            val player=addPlayer("Returner"); player.teleport(Location(world,40.0,4.0,40.0))
            command(router,player,"spawn")
            assertNotNull(arrived,"the player should have been teleported")
            assertEquals(0.5,arrived!!.x,0.001,"must centre on the destination block")
            assertEquals(0.5,arrived!!.z,0.001)
            assertTrue(player.nextMessage()!!.contains("已传送回出生点"))
        }
    }

    @Test fun `warmup survives head turns and sub-block drift but not a real step`() {
        var moved=0
        spawnHarness(SpawnSettings(warmupSeconds=2,cooldownSeconds=0,invulnerabilitySeconds=0),{ _,_-> moved++; true }) { router,_,world ->
            world.setSpawnLocation(buildSpawn(world))
            val player=addPlayer("Mover"); val here=Location(world,10.0,4.0,10.0); player.teleport(here)
            command(router,player,"spawn")
            // Same block: a head turn and a small drift must NOT cancel.
            server.pluginManager.callEvent(PlayerMoveEvent(player,here.clone(),here.clone().apply { yaw=90f }))
            server.pluginManager.callEvent(PlayerMoveEvent(player,here.clone(),here.clone().add(0.0,0.0,0.3)))
            server.scheduler.performTicks(60L)
            assertEquals(1,moved,"standing still must not cancel the warmup")
            drain(player)

            command(router,player,"spawn"); drain(player)   // the warm-up notice is not what we assert
            // A different block cancels.
            server.pluginManager.callEvent(PlayerMoveEvent(player,here.clone(),Location(world,12.0,4.0,10.0)))
            assertTrue(player.nextMessage()!!.contains("你移动了"))
            server.scheduler.performTicks(60L)
            assertEquals(1,moved,"a cancelled warmup must never teleport")
        }
    }

    @Test fun `cooldown is charged at request time and blocks an immediate retry`() {
        var moved=0
        spawnHarness(SpawnSettings(warmupSeconds=1,cooldownSeconds=30,invulnerabilitySeconds=0),{ _,_-> moved++; true }) { router,_,world ->
            world.setSpawnLocation(buildSpawn(world))
            val player=addPlayer("Impatient"); player.teleport(Location(world,5.0,4.0,5.0))
            command(router,player,"spawn")
            assertTrue(player.nextMessage()!!.contains("秒后传送"))
            command(router,player,"spawn")
            assertTrue(player.nextMessage()!!.contains("正在准备中"),"a second request while warming up must be refused")
            server.scheduler.performTicks(40L)
            assertEquals(1,moved)
            drain(player)
            command(router,player,"spawn")
            assertTrue(player.nextMessage()!!.contains("还需等待"),"the cooldown must still be active right after a teleport")
        }
    }

    @Test fun `damage cancels the warmup and close releases granted invulnerability`() {
        var moved=0
        spawnHarness(SpawnSettings(warmupSeconds=2,cooldownSeconds=0,invulnerabilitySeconds=5),{ _,_-> moved++; true }) { router,context,world ->
            world.setSpawnLocation(buildSpawn(world))
            val player=addPlayer("Bruiser"); player.teleport(Location(world,7.0,4.0,7.0))
            command(router,player,"spawn"); drain(player)
            server.pluginManager.callEvent(EntityDamageEvent(player,EntityDamageEvent.DamageCause.FALL,1.0))
            assertTrue(player.nextMessage()!!.contains("受到了伤害"))
            server.scheduler.performTicks(60L)
            assertEquals(0,moved)
            drain(player)

            command(router,player,"spawn")
            server.scheduler.performTicks(60L)
            assertEquals(1,moved)
            assertTrue(player.isInvulnerable,"a landed teleport grants temporary invulnerability")
            context.close()
            assertFalse(player.isInvulnerable,"close() must restore invulnerability instead of stranding it")
        }
    }

    @Test fun `an all-lava spawn is refused rather than dropping the player in it`() {
        var moved=0
        spawnHarness(SpawnSettings(warmupSeconds=0,cooldownSeconds=0,invulnerabilitySeconds=0,safeSearchRange=8),{ _,_-> moved++; true }) { router,_,world ->
            repeat(6) { y -> world.getBlockAt(0,y,0).type=Material.LAVA }
            world.setSpawnLocation(Location(world,0.5,3.0,0.5))
            val player=addPlayer("Diver"); player.teleport(Location(world,20.0,4.0,20.0))
            command(router,player,"spawn")
            assertTrue(player.nextMessage()!!.contains("找不到可站立的安全落点"))
            assertEquals(0,moved,"nothing may be teleported into an unsafe spawn")
        }
    }

    @Test fun `setspawn moves the world spawn and warns when the spot is not standable`() {
        spawnHarness { router,_,world ->
            buildSpawn(world); world.setSpawnLocation(buildSpawn(world))
            buildSpawn(world,3,3)
            // meowo.spawn.set defaults to op, so the admin has to actually be one.
            val admin=addPlayer("Builder").apply { isOp=true }
            // Stand on real ground so the first /setspawn records a usable spot.
            admin.teleport(Location(world,3.5,5.0,3.5))
            command(router,admin,"setspawn")
            assertTrue(admin.nextMessage()!!.contains("已把"))
            assertEquals(3,world.spawnLocation.blockX,"setspawn must move the world spawn")
            // A spot floating in mid-air is still applied, but flagged as unsafe.
            // The MockBukkit world has ground at y=3, so a spot high above it has nothing standable
            // within the 16 block search range: exactly the "would drop you from a height" case.
            admin.teleport(Location(world,6.5,30.0,6.5))
            drain(admin)
            command(router,admin,"setspawn")
            assertTrue(admin.nextMessage()!!.contains("已把"))
            assertTrue(admin.nextMessage()!!.contains("不是安全落点"))
        }
    }

    @Test fun `a pending warmup dies with the context and its routes are removed`() {
        var moved=0
        spawnHarness(SpawnSettings(warmupSeconds=2,cooldownSeconds=0,invulnerabilitySeconds=0),{ _,_-> moved++; true }) { router,context,world ->
            world.setSpawnLocation(buildSpawn(world))
            val player=addPlayer("Latecomer"); player.teleport(Location(world,3.0,4.0,3.0))
            command(router,player,"spawn")
            context.close()
            server.scheduler.performTicks(60L)
            assertEquals(0,moved,"a closed context must not teleport anyone")
            drain(player)
            command(router,player,"spawn")
            assertTrue(player.nextMessage()!!.contains("未知命令"),"closing must unregister the routes")
        }
    }

    /**
     * The old behaviour was the opposite, and it made the feature useless: a world spawn is very
     * often somewhere nobody is standing, so refusing on "the chunk is not loaded" failed
     * precisely when `/meo spawn` was needed. The chunk is now loaded asynchronously instead.
     */
    @Test fun `an unloaded spawn chunk is loaded rather than refused`() {
        var moved=0
        spawnHarness(SpawnSettings(warmupSeconds=0,cooldownSeconds=0,invulnerabilitySeconds=0),{ _,_-> moved++; true }) { router,_,world ->
            buildSpawn(world); world.setSpawnLocation(buildSpawn(world))
            val player=addPlayer("Wanderer"); player.teleport(Location(world,2.0,5.0,2.0))
            drain(player)
            assertTrue(world.getChunkAt(0,0).unload(),"the fixture must be able to unload the chunk")
            assertFalse(world.isChunkLoaded(0,0))
            command(router,player,"spawn")
            drain(player)
            assertTrue(world.isChunkLoaded(0,0),"the spawn chunk must have been loaded")
            assertEquals(1,moved,"and the player must actually arrive")
        }
    }

    @Test fun `a player who was already invulnerable keeps it`() {
        spawnHarness(SpawnSettings(warmupSeconds=0,cooldownSeconds=0,invulnerabilitySeconds=3)) { router,_,world ->
            buildSpawn(world); world.setSpawnLocation(buildSpawn(world))
            val player=addPlayer("Creative"); player.teleport(Location(world,2.0,5.0,2.0))
            player.isInvulnerable=true
            command(router,player,"spawn")
            assertTrue(player.nextMessage()!!.contains("已传送回出生点"))
            server.scheduler.performTicks(80L)   // well past the shield window
            assertTrue(player.isInvulnerable,"a pre-existing invulnerability must never be revoked")
        }
    }

    @Test fun `a blocked teleport is reported and grants no shield`() {
        spawnHarness(SpawnSettings(warmupSeconds=0,cooldownSeconds=0,invulnerabilitySeconds=4),{ _,_-> false }) { router,_,world ->
            world.setSpawnLocation(buildSpawn(world))
            val player=addPlayer("Denied"); player.teleport(Location(world,9.0,4.0,9.0))
            command(router,player,"spawn")
            assertTrue(player.nextMessage()!!.contains("被其他插件"))
            assertFalse(player.isInvulnerable,"a blocked teleport must not grant a shield")
        }
    }
    @Test fun `real movement during tpa countdown refunds applicant and stops all magic`() {
        val from=addPlayer("MoveCancel"); val target=addPlayer("MoveAnchor")
        flush(from.uniqueId); flush(target.uniqueId)
        api().setBalance(from.uniqueId,BigDecimal("2000")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        val world=from.world as WorldMock
        var moves=0
        teleportHarness({ _,_ -> moves++; true },particles=true,countdown=3) { router,_ ->
            command(router,from,"tpa MoveAnchor"); command(router,target,"tpa accept MoveCancel")
            settle()
            assertEquals(BigDecimal("1998.00"),balance(from.uniqueId))
            val here=from.location
            server.pluginManager.callEvent(PlayerMoveEvent(from,here,here.clone().apply { yaw+=50f }))
            server.scheduler.performTicks(2)
            assertEquals(BigDecimal("1998.00"),balance(from.uniqueId),"head turns must not refund")
            server.pluginManager.callEvent(PlayerMoveEvent(from,here,here.clone().add(0.1,0.0,0.0)))
            flush(from.uniqueId)
            val particles=world.spawnedParticles.size
            server.scheduler.performTicks(80)
            assertEquals(0,moves)
            assertEquals(BigDecimal("2000.00"),balance(from.uniqueId))
            assertEquals(BigDecimal("0.00"),settlements().treasury().toCompletableFuture().get(5,TimeUnit.SECONDS))
            assertEquals(particles,world.spawnedParticles.size)
            assertTrue(generateSequence { from.nextMessage() }.any { it.contains("移动") })
        }
    }
    @Test fun `moving applicant refunds both sponsored taxes to the payer`() {
        val from=addPlayer("SponsoredMove"); val payer=addPlayer("Sponsor")
        flush(from.uniqueId); flush(payer.uniqueId)
        api().setBalance(payer.uniqueId,BigDecimal("100")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        var moved=0
        teleportHarness({ _,_ -> moved++; true },countdown=3) { router,_ ->
            command(router,from,"tpa Sponsor"); command(router,payer,"tpa accept SponsoredMove"); settle()
            command(router,payer,"tpa accept SponsoredMove"); settle()
            assertEquals(BigDecimal("98.99"),balance(payer.uniqueId))
            val here=from.location
            server.pluginManager.callEvent(PlayerMoveEvent(from,here,here.clone().add(0.0,0.2,0.0)))
            settle(); server.scheduler.performTicks(80)
            assertEquals(0,moved)
            assertEquals(BigDecimal("100.00"),balance(payer.uniqueId))
            assertEquals(BigDecimal("0.00"),balance(from.uniqueId))
            assertEquals(BigDecimal("0.00"),settlements().treasury().toCompletableFuture().get(5,TimeUnit.SECONDS))
        }
    }
    @Test fun `landing hexagram uses actual biome colours and rotates at the fixed landing point`() {
        val from=addPlayer("Runes"); val to=addPlayer("DesertAnchor")
        flush(from.uniqueId); flush(to.uniqueId)
        api().setBalance(from.uniqueId,BigDecimal("100")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        val world=server.addSimpleWorld("runes-${spawnWorldCounter++}")
        val landing=Location(world,10.5,5.0,10.5)
        to.teleport(landing)
        world.setBiome(10,5,10,org.bukkit.block.Biome.DESERT)
        world.clearSpawnedParticles()
        teleportHarness({ a,b -> a.teleport(b.location) },particles=true,countdown=3) { router,_ ->
            command(router,from,"tpa DesertAnchor"); command(router,to,"tpa accept Runes"); settle()
            assertTrue(world.spawnedParticles.isEmpty(),"no destination circle before arrival")
            server.scheduler.performTicks(65); settle()
            val first=world.spawnedParticles
            assertTrue(first.isNotEmpty())
            val palette=com.meowo.teleport.BiomePalette.forBiome("desert")
            assertEquals(setOf(org.bukkit.Color.fromRGB(palette.primary),org.bukkit.Color.fromRGB(palette.highlight)),
                first.map { (it.data() as org.bukkit.Particle.DustOptions).color }.toSet())
            assertTrue(first.all { kotlin.math.abs(it.x()-10.5)<1 && kotlin.math.abs(it.z()-10.5)<1 && it.y() in 5.04..5.07 })
            val initialPoint=first.last()
            world.clearSpawnedParticles()
            from.teleport(Location(world,20.0,5.0,20.0))
            world.setBiome(10,5,10,org.bukkit.block.Biome.FROZEN_OCEAN)
            server.scheduler.performTicks(12)
            val later=world.spawnedParticles
            assertTrue(later.isNotEmpty())
            assertTrue(later.all { it.x()<12 && it.z()<12 },"circle must stay at the landing point")
            assertTrue(kotlin.math.abs(initialPoint.x()-later.last().x())>0.001 || kotlin.math.abs(initialPoint.z()-later.last().z())>0.001,"circle must rotate")
            assertEquals(first.map { (it.data() as org.bukkit.Particle.DustOptions).color }.toSet(),
                later.map { (it.data() as org.bukkit.Particle.DustOptions).color }.toSet(),"palette must stay fixed after landing")
            server.scheduler.performTicks(60)
            val end=world.spawnedParticles.size
            server.scheduler.performTicks(20)
            assertEquals(end,world.spawnedParticles.size)
        }
    }

    @Test fun `extended waits keep travel effects active and preserve stronger existing nausea`() {
        val from=addPlayer("LongTravel"); val target=addPlayer("LongAnchor")
        settle()
        api().setBalance(from.uniqueId,BigDecimal("100")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        from.addPotionEffect(PotionEffect(PotionEffectType.NAUSEA,40,2,false,true,true))
        var moved=0
        teleportHarness({ player,_ ->
            moved++
            assertTrue(player.getPotionEffect(PotionEffectType.NAUSEA)!!.duration in 19..21)
            assertTrue(player.getPotionEffect(PotionEffectType.DARKNESS)!!.duration in 19..21)
            true
        },countdown=5) { router,_ ->
            command(router,from,"tpa LongAnchor"); command(router,target,"tpa accept LongTravel"); settle()
            val nausea=from.getPotionEffect(PotionEffectType.NAUSEA)!!
            assertEquals(2,nausea.amplifier)
            assertTrue(nausea.duration>100)
            assertTrue(nausea.hasParticles()); assertTrue(nausea.hasIcon()); assertFalse(nausea.isAmbient)
            server.scheduler.performTicks(70)
            assertEquals(0,moved)
            assertTrue(from.hasPotionEffect(PotionEffectType.DARKNESS))
            server.scheduler.performTicks(35L); settle()
            assertEquals(1,moved)
            assertTrue(from.hasPotionEffect(PotionEffectType.NAUSEA))
            assertTrue(from.hasPotionEffect(PotionEffectType.DARKNESS))
            server.scheduler.performTicks(20)
            assertFalse(from.hasPotionEffect(PotionEffectType.NAUSEA))
            assertFalse(from.hasPotionEffect(PotionEffectType.DARKNESS))
        }
    }

    @Test fun `turning the camera throughout the countdown neither cancels nor refunds teleport`() {
        val from=addPlayer("Camera"); val to=addPlayer("LookTarget")
        flush(from.uniqueId); flush(to.uniqueId)
        api().setBalance(from.uniqueId,BigDecimal("100")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        var moved=0
        teleportHarness({ _,_ -> moved++; true },countdown=3) { router,_ ->
            command(router,from,"tpa LookTarget"); command(router,to,"tpa accept Camera"); settle()
            repeat(3) { i ->
                from.teleport(from.location.clone().apply { yaw=(i+1)*90f; pitch=(i-1)*60f })
                server.scheduler.performTicks(10)
                assertEquals(0,moved)
                assertEquals(BigDecimal("99.00"),balance(from.uniqueId))
            }
            server.scheduler.performTicks(35); flush(from.uniqueId)
            assertEquals(1,moved)
            assertEquals(BigDecimal("99.00"),balance(from.uniqueId))
            assertEquals(BigDecimal("1.00"),settlements().treasury().toCompletableFuture().get(5,TimeUnit.SECONDS))
        }
    }

    @Test fun `look events with small position correction or stale previous coordinates do not cancel tpa`() {
        val from=addPlayer("LookJitter"); val target=addPlayer("JitterAnchor")
        flush(from.uniqueId); flush(target.uniqueId)
        api().setBalance(from.uniqueId,BigDecimal("100")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        var moved=0
        teleportHarness({ _,_ -> moved++; true },countdown=3) { router,_ ->
            command(router,from,"tpa JitterAnchor"); command(router,target,"tpa accept LookJitter"); flush(from.uniqueId)
            val origin=from.location.clone()
            // Rotation packets can report a tiny settling correction, then return to the origin.
            val corrected=origin.clone().add(0.0,-0.044,0.0).apply { yaw=150f; pitch=70f }
            server.pluginManager.callEvent(PlayerMoveEvent(from,origin,corrected))
            from.setLocation(corrected)
            assertEquals(BigDecimal("99.00"),balance(from.uniqueId))
            // Event.from may predate the countdown. It must not replace the reservation's origin.
            server.pluginManager.callEvent(PlayerMoveEvent(from,origin.clone().add(0.2,0.0,0.0),origin.clone().apply { yaw=-170f; pitch=-60f }))
            from.setLocation(corrected)
            server.scheduler.performTicks(65); flush(from.uniqueId)
            assertEquals(1,moved,"turning with subpixel position correction must finish the countdown")
            assertEquals(BigDecimal("99.00"),balance(from.uniqueId))
            assertEquals(BigDecimal("1.00"),settlements().treasury().toCompletableFuture().get(5,TimeUnit.SECONDS))
        }
    }

    @Test fun `small actual steps accumulate from the countdown origin and still cancel and refund`() {
        val from=addPlayer("SlowStep"); val target=addPlayer("StepAnchor")
        flush(from.uniqueId); flush(target.uniqueId)
        api().setBalance(from.uniqueId,BigDecimal("100")).toCompletableFuture().get(5,TimeUnit.SECONDS)
        var moved=0
        teleportHarness({ _,_ -> moved++; true },countdown=3) { router,_ ->
            command(router,from,"tpa StepAnchor"); command(router,target,"tpa accept SlowStep"); settle()
            val origin=from.location.clone()
            for (step in 1..6) {
                val previous=from.location
                val next=origin.clone().add(step*0.01,0.0,0.0)
                server.pluginManager.callEvent(PlayerMoveEvent(from,previous,next)); from.setLocation(next)
            }
            assertEquals(BigDecimal("99.00"),balance(from.uniqueId),"small corrections within tolerance must not refund")
            val next=origin.clone().add(0.07,0.0,0.0)
            server.pluginManager.callEvent(PlayerMoveEvent(from,from.location,next)); from.setLocation(next)
            settle(); server.scheduler.performTicks(65)
            assertEquals(0,moved)
            assertEquals(BigDecimal("100.00"),balance(from.uniqueId))
            assertEquals(BigDecimal("0.00"),settlements().treasury().toCompletableFuture().get(5,TimeUnit.SECONDS))
        }
    }

    // ── sit / ride ───────────────────────────────────────────────────────────────────────────

    /** Puts a solid block under the player so /sit has ground to sit on. */
    private fun standOnGround(player: PlayerMock) {
        val world = player.world
        val base = player.location.clone()
        base.y = world.minHeight.toDouble() + 1
        world.getBlockAt(base).type = Material.STONE
        player.teleport(base)
    }

    @Test fun `sit puts the player on a seat and running it again stands them up`() {
        val player = addPlayer("Sitter")
        standOnGround(player)
        server.dispatchCommand(player, "sit")
        assertTrue(player.isInsideVehicle, "sitting must mount a seat")
        assertEquals(1, seats(player), "exactly one armour stand should exist")
        server.dispatchCommand(player, "sit")
        assertFalse(player.isInsideVehicle, "sitting again must stand up")
        assertEquals(0, seats(player), "the stand must be removed, not leaked")
    }

    @Test fun `the seat is invisible so other players never see an armour stand`() {
        val player = addPlayer("Sitter2")
        standOnGround(player)
        server.dispatchCommand(player, "sit")
        val stand = player.vehicle as org.bukkit.entity.ArmorStand
        assertTrue(stand.isInvisible, "the seat must be invisible")
        assertFalse(stand.hasGravity(), "the seat must not fall")
        assertTrue(stand.isInvulnerable, "the seat must not be damageable")
    }

    @Test fun `sitting in mid air is refused so it cannot be used to hover`() {
        val player = addPlayer("Flyer")
        val void = player.world.minHeight.toDouble() + 40
        player.teleport(player.location.clone().apply { y = void })
        server.dispatchCommand(player, "sit")
        assertFalse(player.isInsideVehicle, "there is no ground to sit on")
        assertTrue(generateSequence { player.nextMessage() }.toList().joinToString().contains("没有可坐的地面"))
    }

    @Test fun `sneaking dismounts and clears the seat`() {
        val player = addPlayer("Sneaker")
        standOnGround(player)
        server.dispatchCommand(player, "sit")
        assertTrue(player.isInsideVehicle)
        // simulateSneak fires the real PlayerToggleSneakEvent, so the listener under test runs.
        player.simulateSneak(true)
        assertFalse(player.isInsideVehicle, "sneak must dismount")
        assertEquals(0, seats(player), "sneaking must remove our stand")
    }

    @Test fun `closing the context frees every rider and removes every stand`() {
        val player = addPlayer("Leaving")
        standOnGround(player)
        server.dispatchCommand(player, "sit")
        assertTrue(player.isInsideVehicle)
        server.dispatchCommand(server.consoleSender, "meowo reload")
        configure { it.set("contexts.sit", false) }
        server.dispatchCommand(server.consoleSender, "meowo reload")
        assertFalse(player.isInsideVehicle, "a disabled context must not leave riders mounted")
        assertEquals(0, seats(player), "a disabled context must remove its stands")
    }

    @Test fun `riding a nearby player needs only the normal ride permission`() {
        val rider = addPlayer("Rider")
        val mount = addPlayer("Mount")
        standOnGround(rider)
        mount.teleport(rider.location.clone().add(1.0,0.0,0.0))
        rider.addAttachment(plugin, "meowo.ride", true)
        rider.addAttachment(plugin, "meowo.ride.any", false)
        server.dispatchCommand(rider, "ride Mount")
        assertTrue(rider.vehicle === mount, "riding a player should work without the elevated permission")
        assertTrue(generateSequence { rider.nextMessage() }.toList().joinToString().contains("Mount"))
    }

    @Test fun `ride lists only nearby visible players and rechecks range even for operators`() {
        val rider=addPlayer("RangeRider")
        val near=addPlayer("Nearby"); val far=addPlayer("FarAway"); val high=addPlayer("Upstairs")
        val hidden=addPlayer("Hidden"); val elsewhere=addPlayer("Elsewhere")
        rider.teleport(Location(rider.world,10.0,5.0,10.0)); rider.isOp=true
        near.teleport(rider.location.clone().add(1.5,0.0,1.5))
        far.teleport(rider.location.clone().add(1.51,0.0,0.0))
        high.teleport(rider.location.clone().add(0.0,2.0,0.0))
        hidden.teleport(rider.location); rider.hidePlayer(plugin,hidden)
        elsewhere.teleport(Location(server.addSimpleWorld("ride-${spawnWorldCounter++}"),10.0,5.0,10.0))
        rider.world.spawn(rider.location,org.bukkit.entity.Cow::class.java)
        val ride=requireNotNull(plugin.getCommand("ride"))
        assertEquals(listOf("Nearby"),ride.tabComplete(rider,"ride",arrayOf("")))
        assertEquals(listOf("Nearby"),ride.tabComplete(rider,"ride",arrayOf("ne")))
        assertTrue(ride.tabComplete(rider,"ride",arrayOf("fa")).isEmpty())
        for (target in listOf("FarAway","Upstairs","Hidden","Elsewhere","Cow")) {
            server.dispatchCommand(rider,"ride $target"); assertFalse(rider.isInsideVehicle)
        }
        server.dispatchCommand(rider,"ride Nearby"); assertSame(near,rider.vehicle)
        server.dispatchCommand(rider,"ride")
        near.teleport(rider.location.clone().add(1.51,0.0,0.0))
        server.dispatchCommand(rider,"ride Nearby"); assertFalse(rider.isInsideVehicle)
    }

    @Test fun `occupied players form a single legal stack and cannot create cycles`() {
        val base=addPlayer("StackBase"); val middle=addPlayer("StackMiddle")
        val top=addPlayer("StackTop"); val rider=addPlayer("StackRider")
        for (player in listOf(middle,top,rider)) player.teleport(base.location)
        assertTrue(base.addPassenger(middle)); assertTrue(middle.addPassenger(top))
        server.dispatchCommand(rider,"ride StackBase")
        assertSame(top,rider.vehicle)
        assertEquals(listOf(middle),base.passengers)
        assertEquals(listOf(top),middle.passengers)
        assertEquals(listOf(rider),top.passengers)
        server.dispatchCommand(base,"ride StackMiddle")
        assertFalse(base.isInsideVehicle,"the base must not mount one of its own descendants")
        assertSame(top,rider.vehicle)
    }

    @Test fun `ride permission failures and illegal occupied targets leave riders untouched`() {
        val base=addPlayer("LegalBase"); val top=addPlayer("IllegalTop"); val rider=addPlayer("DeniedRider")
        top.teleport(base.location); rider.teleport(base.location)
        base.addPassenger(top)
        rider.addAttachment(plugin,"meowo.ride",false)
        drain(rider)
        server.dispatchCommand(rider,"ride LegalBase")
        assertFalse(rider.isInsideVehicle)
        assertTrue(generateSequence { rider.nextMessage() }.toList().joinToString().contains("权限"))
        assertTrue(plugin.getCommand("ride")!!.tabComplete(rider,"ride",arrayOf("")).isEmpty())
        rider.addAttachment(plugin,"meowo.ride",true)
        rider.teleport(base.location.clone().add(0.0,1.5,0.0))
        // The selected base is in reach but its passenger is outside the permitted area.
        top.setLocation(base.location.clone().add(0.0,-0.1,0.0))
        server.dispatchCommand(rider,"ride LegalBase")
        assertFalse(rider.isInsideVehicle)
        assertSame(base,top.vehicle)
        assertTrue(generateSequence { rider.nextMessage() }.toList().joinToString().contains("堆叠"))
    }

    @Test fun `nonplayer and branched passenger stacks are refused without rearranging them`() {
        val base=addPlayer("BlockedBase"); val rider=addPlayer("BlockedRider")
        rider.teleport(base.location)
        val cow=base.world.spawn(base.location,org.bukkit.entity.Cow::class.java)
        base.addPassenger(cow)
        server.dispatchCommand(rider,"ride BlockedBase")
        assertFalse(rider.isInsideVehicle); assertSame(base,cow.vehicle)
        assertTrue(plugin.getCommand("ride")!!.tabComplete(rider,"ride",arrayOf("BlockedB")).isEmpty())
        base.removePassenger(cow)
        val first=addPlayer("BranchOne"); val second=addPlayer("BranchTwo")
        first.teleport(base.location); second.teleport(base.location)
        base.addPassenger(first); base.addPassenger(second)
        server.dispatchCommand(rider,"ride BlockedBase")
        assertFalse(rider.isInsideVehicle)
        assertEquals(listOf(first,second),base.passengers)
        base.removePassenger(second)
        first.gameMode=org.bukkit.GameMode.SPECTATOR
        server.dispatchCommand(rider,"ride BlockedBase")
        assertFalse(rider.isInsideVehicle)
        assertSame(base,first.vehicle)
    }

    @Test fun `reloading the ride area applies smaller ranges without allowing old wider values`() {
        val rider=addPlayer("ReloadRider"); val target=addPlayer("ReloadTarget")
        target.teleport(rider.location.clone().add(1.0,0.0,0.0))
        configure { it.set("sit.target-range",1) }
        server.dispatchCommand(server.consoleSender,"meowo reload")
        server.dispatchCommand(rider,"ride ReloadTarget"); assertFalse(rider.isInsideVehicle)
        configure { it.set("sit.target-range",6) }
        server.dispatchCommand(server.consoleSender,"meowo reload")
        server.dispatchCommand(rider,"ride ReloadTarget"); assertSame(target,rider.vehicle)
        server.dispatchCommand(rider,"ride")
        target.teleport(rider.location.clone().add(1.51,0.0,0.0))
        server.dispatchCommand(rider,"ride ReloadTarget"); assertFalse(rider.isInsideVehicle)
    }

    @Test fun `ride with no argument dismounts and an unknown target is reported`() {
        val rider = addPlayer("Rider3")
        rider.addAttachment(plugin, "meowo.ride", true)
        server.dispatchCommand(rider, "ride")
        assertTrue(generateSequence { rider.nextMessage() }.toList().joinToString().contains("没有乘坐"))
        server.dispatchCommand(rider, "ride NobodyHere")
        assertTrue(generateSequence { rider.nextMessage() }.toList().joinToString().contains("找不到"))
    }

    @Test fun `riding yourself is refused`() {
        val rider = addPlayer("Rider4")
        rider.addAttachment(plugin, "meowo.ride", true)
        server.dispatchCommand(rider, "ride Rider4")
        assertFalse(rider.isInsideVehicle)
        assertTrue(generateSequence { rider.nextMessage() }.toList().joinToString().contains("不能骑自己"))
    }

    // ── command surface ─────────────────────────────────────────────────────────────────────

    /**
     * Every command the descriptor declares has a root alias, and every alias is one Bukkit can resolve.
     *
     * The check used to be a list of five names typed out here, which meant a command added to
     * `plugin.yml` and to the alias table was invisible to it. Both sides are now read from the
     * places that hold them — `plugin.yml` from the descriptor, the aliases from the runtime's own
     * table — and compared.
     *
     * The mismatch this catches is the one `/back` used to be: a descriptor entry with no executor is
     * not an error anywhere. Bukkit resolves the command, finds nothing registered for it, and
     * answers with the `usage:` line. The route existed, the permission existed, the help page listed
     * it, and the command players reach for after dying did nothing.
     */
    @Test fun `every command the descriptor declares is reachable at the root`() {
        val declared = plugin.description.commands.keys
        val aliased = runtime.aliases.keys

        assertEquals(
            emptySet<String>(),
            aliased - declared,
            "an alias that plugin.yml does not declare cannot be resolved by Bukkit at all",
        )
        assertEquals(
            setOf("meowo"),
            declared - aliased,
            "every other declared command needs a root alias; 'meowo' is the command itself",
        )
    }

    /**
     * Every root alias the running server serves reaches a route rather than an unknown command.
     *
     * Derived from the runtime's alias table, so a command added to `plugin.yml` and to that table is
     * covered without anybody typing its name here — which is what the old five-name list could not do.
     *
     * A context that is switched off takes its routes with it, so its alias has nothing to reach. That
     * is a **configuration**, not a broken command, and it is read from `config.yml` rather than typed
     * out: a route that goes missing while its context is *on* fails the test, and one that goes
     * missing because the feature is off does not.
     */
    @Test fun `every root alias reaches a route rather than an unknown command`() {
        val player = addPlayer("Root")
        player.addAttachment(plugin, "meowo.use", true)
        // `core` is not one of the runtime's contexts — it registers `help`, `info` and `reload` itself.
        val served = runtime.router.routesOf("core") +
            runtime.activeContexts.flatMap { runtime.router.routesOf(it) }

        val config = org.bukkit.configuration.file.YamlConfiguration
            .loadConfiguration(plugin.dataFolder.resolve("config.yml"))
        val contexts = config.getConfigurationSection("contexts")
        val disabled = contexts?.getKeys(false)?.filter { !contexts.getBoolean(it) }?.toSet().orEmpty()

        val unserved = runtime.aliases.filterValues { prefix ->
            val route = prefix.firstOrNull() ?: return@filterValues false
            route !in served
        }.keys
        assertTrue(
            unserved.all { runtime.aliases[it]?.firstOrNull() in disabled },
            "root commands whose route is not registered: $unserved. Either the route was renamed or " +
                "its context is on but does not register it. Contexts switched off in config.yml: $disabled.",
        )

        for (root in runtime.aliases.keys - unserved) {
            drain(player)
            server.dispatchCommand(player, root)
            val line = generateSequence { player.nextMessage() }.toList().joinToString("\n")
            assertFalse(line.contains("未知命令"), "/$root must be a registered route, got: $line")
        }
    }

    /**
     * `/back` is the one root command `plugin.yml` declared and the alias table forgot.
     *
     * A descriptor entry with no executor is not an error anywhere: Bukkit resolves the command,
     * finds nothing registered for it, and answers with the `usage:` line. So the route existed,
     * the permission existed, the help page listed it, and `/back` — the command people reach for
     * after dying — did nothing. Checking that the *route's own* reply arrives is what tells the
     * two apart; "not unknown" would have passed on the usage line too.
     */
    @Test fun `back is reachable at the root rather than only behind meowo`() {
        val player = addPlayer("Returner")
        drain(player)
        server.dispatchCommand(player, "back")
        val said = generateSequence { player.nextMessage() }.toList().joinToString("\n")
        assertTrue(said.contains("回溯"), "/back must run the back route, got: $said")
        assertFalse(said.contains("Usage"), "and never the descriptor's usage line: $said")
    }

    @Test fun `meo is a working alias for the whole meowo command`() {
        val player = addPlayer("Meo")
        player.addAttachment(plugin, "meowo.use", true)
        server.dispatchCommand(player, "meo info")
        val line = generateSequence { player.nextMessage() }.toList().joinToString("\n")
        assertTrue(line.contains("Meowo"), "/meo info should behave like /meowo info: $line")
    }

    @Test fun `help and reload are deliberately not shadowed at the root`() {
        val player = addPlayer("Safe")
        player.addAttachment(plugin, "meowo.use", true)
        player.addAttachment(plugin, "meowo.admin", true)
        // They must still work behind the plugin prefix, and must NOT own a root command.
        server.dispatchCommand(player, "meowo help")
        assertTrue(generateSequence { player.nextMessage() }.toList().joinToString().isNotEmpty())
        assertNull(plugin.getCommand("help"), "registering /help would shadow the vanilla command")
        assertNull(plugin.getCommand("reload"), "registering /reload would collide with server plugins")
    }

    @Test fun `help hides the commands a plain player may not run`() {
        val plain = addPlayer("Reader")
        plain.addAttachment(plugin, "meowo.use", true)
        plain.addAttachment(plugin, "meowo.nametag.afk", false)
        plain.addAttachment(plugin, "meowo.admin", false)
        drain(plain)
        server.dispatchCommand(plain, "meowo help")
        val text = generateSequence { plain.nextMessage() }.toList().joinToString("\n")
        assertFalse(text.contains("手动标记离开"), "afk is op-only and must not be advertised: $text")
        assertFalse(text.contains("重载语言与模块开关"), "reload is admin-only and must not be advertised: $text")
        assertFalse(text.contains("把世界出生点设在这里"), "and so must setspawn: $text")
    }

    /**
     * Every permission a route checks must be declared in plugin.yml.
     *
     * `meowo.teleport.back` was the one node used as a route permission and never declared. Bukkit
     * resolves an undeclared node against the default group, so `/meo back` behaved as OP-only — the
     * feature was written, routed and reachable by nobody who was not an operator, and **no filter
     * anywhere said so**, because the help page, tab completion and the command handler all read the
     * same wrong answer and agreed with each other. A permission nobody can find in the descriptor is
     * a permission nobody can grant either.
     */
    @Test fun `every route permission is declared in the plugin descriptor`() {
        val declared = plugin.description.permissions.map { it.name }.toSet()
        val offenders = runtime.activeContexts
            .flatMap { runtime.router.routesOf(it) }
            .mapNotNull { name -> runtime.router.permissionOf(name)?.let { name to it } }
            .filterNot { it.second in declared }
            .map { "${it.first} needs the undeclared node ${it.second}" }
            .distinct()

        assertTrue(offenders.isEmpty(), "route permissions missing from plugin.yml: $offenders")
    }

    /**
     * The filtering itself, as a size difference rather than a set of strings — the two assertions
     * above already pin the wording, and this says the page is genuinely shorter, not merely missing
     * two specific words.
     */
    @Test fun `a player sees fewer commands than an operator`() {
        val plain = addPlayer("Plain"); plain.addAttachment(plugin, "meowo.use", true)
        val boss = addPlayer("Boss"); boss.addAttachment(plugin, "meowo.use", true)
        boss.addAttachment(plugin, "meowo.admin", true); boss.addAttachment(plugin, "meowo.spawn.set", true)
        boss.addAttachment(plugin, "meowo.nametag.afk", true)
        drain(plain); drain(boss)

        val plainLines = fullHelp(plain).lines().count { it.contains("▸") }
        val bossLines = fullHelp(boss).lines().count { it.contains("▸") }

        assertTrue(bossLines > plainLines, "an operator has more commands: $plainLines vs $bossLines")
    }

    @Test fun `help shows those commands to an operator`() {
        val admin = addPlayer("Boss")
        admin.addAttachment(plugin, "meowo.use", true)
        admin.addAttachment(plugin, "meowo.nametag.afk", true)
        val text = fullHelp(admin)
        assertTrue(text.contains("手动标记离开"), "an operator must see the afk line: $text")
    }

    /**
     * Every page this reader has, concatenated.
     *
     * The assertions below are about the whole list — "every command is on the page" — and paging
     * turned that into a question about *every* page. Stopping on the first reply with no command
     * line in it means the helper needs no hard-coded page count, so a permission change that adds
     * or removes a group cannot quietly truncate these checks.
     */
    private fun fullHelp(reader: PlayerMock): String {
        drain(reader)
        val out = StringBuilder()
        for (page in 1..8) {
            server.dispatchCommand(reader, "meowo help $page")
            val said = generateSequence { reader.nextMessage() }.toList().joinToString("\n")
            if (!said.contains("▸")) break
            out.append(said).append('\n')
        }
        return out.toString()
    }

    /**
     * The gap that prompted the rewrite: five real routes were missing while the footer went on
     * advertising one of them. A reader who had to find out that `/meo replay` exists — from the
     * footer, on a page that claims to list the commands — was being handed a half-page.
     *
     * Stated as a list so the next command added without a help line fails here. Only commands an
     * ordinary player may run: `/meo afk` is deliberately op-only and is asserted from the other
     * side in `help shows those commands to an operator`.
     */
    @Test fun `every command a player is likely to reach for is on the page`() {
        val reader = addPlayer("Finder"); reader.addAttachment(plugin, "meowo.use", true)
        val text = fullHelp(reader)

        for (command in listOf("/meo msg", "/meo mail", "/meo who", "/meo replay", "/meo em",
            "/meo known", "/meo forget", "/meo eco", "/meo pay", "/meo menu",
            "/meo home", "/meo tpa", "/meo tpa accept", "/meo back", "/meo spawn",
            "/meo sit", "/meo ride", "/meo hat", "/meo shadowbox", "/meo mine", "/meo info")) {
            assertTrue(text.contains(command), "$command is missing from the help: $text")
        }
    }

    /**
     * Every listed line has to be pasteable.
     *
     * The old page printed bare names — `info`, `eco`, `msg` — so nothing on it could be copied back
     * into chat. The one thing a help page exists for is being pasted.
     */
    @Test fun `every command line is something the reader can paste`() {
        val reader = addPlayer("Paster"); reader.addAttachment(plugin, "meowo.use", true)
        val text = fullHelp(reader)

        val lines = text.lines().filter { it.contains("▸") }
        assertTrue(lines.size >= 15, "the whole list should actually list things: ${lines.size}")
        for (line in lines) {
            // The §f is the template's own colour on the invocation; it is not part of what to paste.
            val invocation = line.substringAfter("▸ ").substringBefore(" §8·").removePrefix("§f")
            assertTrue(invocation.startsWith("/meo"), "not pasteable: '$invocation' in: $line")
        }
    }

    /**
     * `/meo tpaccept` does not exist — `tpaccept` is a root alias, not a route — so rendering the
     * alias as its own `/meo` command would advertise something that answers "unknown".
     */
    @Test fun `an alias is listed as the command that works`() {
        val reader = addPlayer("AliasUser"); reader.addAttachment(plugin, "meowo.use", true)
        val text = fullHelp(reader)

        assertTrue(text.contains("/meo tpa accept"), "the working form: $text")
        assertFalse(text.contains("/meo tpaccept"), "and never the one that does not exist: $text")
    }

    /** Group headings exist so a 20-line page is six small ones. */
    @Test fun `the page is grouped rather than one wall`() {
        val reader = addPlayer("Grouper"); reader.addAttachment(plugin, "meowo.use", true)
        val text = fullHelp(reader)

        for (group in listOf("说话", "猫记得你什么", "钱", "移动")) {
            assertTrue(text.contains(group), "group $group missing: $text")
        }
    }

    // ── paging ───────────────────────────────────────────────────────────────────────────────

    /**
     * Paging exists because thirty lines of chat is a wall nobody scrolls.
     *
     * Asserted as a **line count** rather than as copy: a page that quietly grew back into a wall
     * would still render perfectly and would still pass any check that its text reads well.
     */
    @Test fun `the list is split across pages rather than one wall`() {
        val reader = addPlayer("Paged"); reader.addAttachment(plugin, "meowo.use", true); drain(reader)

        server.dispatchCommand(reader, "meowo help 1")
        val first = generateSequence { reader.nextMessage() }.toList().joinToString("\n")

        assertTrue(first.contains("下一页"), "page one must offer a way to the rest: $first")
        assertTrue(first.lines().count { it.contains("▸") } < 20,
            "a page is a page, not the whole list: ${first.lines().count { it.contains("▸") }} command lines")
    }

    /**
     * The console has no cursor and no tab completion, so a button there is a dead end: it would be
     * told a next page exists and given no way to reach it. It gets the command written out, exactly
     * as the pre-button page did — so the click is an addition, never a replacement.
     */
    @Test fun `the console is told the command rather than offered a button`() {
        server.dispatchCommand(server.consoleSender, "meowo help 1")
        val said = generateSequence { server.consoleSender.nextMessage() }.toList().joinToString("\n")

        assertTrue(said.contains("/meo help 2"), "the console must be able to type its way to page two: $said")
        assertFalse(said.contains("§"), "and it still gets no colour codes: $said")
    }

    /** Page three is the one that loops, and it must still offer its way back. */
    @Test fun `the last page offers a way back to the first`() {
        val reader = addPlayer("Last"); reader.addAttachment(plugin, "meowo.use", true); drain(reader)
        val last = runtime.router.onTabComplete(reader, plugin.getCommand("meowo")!!, "meowo", arrayOf("help", ""))
            .maxOf { it.toInt() }

        server.dispatchCommand(reader, "meowo help $last")
        val said = generateSequence { reader.nextMessage() }.toList().joinToString("\n")

        assertTrue(said.contains("回到开头"), "a dead end is where a reader stops reading: $said")
    }

    /**
     * A group is never split across a page boundary.
     *
     * A heading stranded at the bottom of one page and its commands at the top of the next is worse
     * than a slightly longer page, so the check is that the last heading on a page has commands under
     * it before the page ends.
     */
    @Test fun `a group is never split across a page break`() {
        val reader = addPlayer("Whole"); reader.addAttachment(plugin, "meowo.use", true); drain(reader)

        server.dispatchCommand(reader, "meowo help 1")
        val lines = generateSequence { reader.nextMessage() }.toList()
            .joinToString("\n").lines().toList()
        val lastHeading = lines.indexOfLast { it.contains("§8▌§b ") }
        assertTrue(lastHeading >= 0, "page one should have headings: $lines")
        assertTrue(lines.drop(lastHeading + 1).any { it.contains("▸") },
            "the last group on the page must have its commands on the same page: $lines")
    }

    /** Every page numbers itself, so a reader who bookmarked page 2 knows where they are. */
    @Test fun `every page says which one it is`() {
        val reader = addPlayer("Counter"); reader.addAttachment(plugin, "meowo.use", true); drain(reader)

        for (page in 1..4) {
            server.dispatchCommand(reader, "meowo help $page")
            val said = generateSequence { reader.nextMessage() }.toList().joinToString("\n")
            if (!said.contains("▸")) break
            assertTrue(said.contains("§f$page§8/"), "page $page must number itself: $said")
        }
    }

    /**
     * Asking for a page that does not exist says so.
     *
     * Falling back to page one would look like the argument had been understood, and the reader
     * would be looking for the command they asked for on a page they had already seen.
     */
    @Test fun `a page that does not exist is refused by name`() {
        val reader = addPlayer("Overreacher"); reader.addAttachment(plugin, "meowo.use", true); drain(reader)

        server.dispatchCommand(reader, "meowo help 99")

        val said = generateSequence { reader.nextMessage() }.toList().joinToString("\n")
        assertTrue(said.contains("没有这一页"), "and it must say why rather than shrug: $said")
        assertFalse(said.contains("▸"), "a refusal is not a page: $said")
    }

    /** Completion offers the pages this reader has — never page four of two. */
    @Test fun `help completes only the page numbers that exist`() {
        val reader = addPlayer("Tabber"); reader.addAttachment(plugin, "meowo.use", true)
        val command = plugin.getCommand("meowo")!!

        val offered = runtime.router.onTabComplete(reader, command, "meowo", arrayOf("help", ""))

        assertTrue(offered.isNotEmpty(), "a paged list has pages to offer")
        assertTrue(offered.all { it.toIntOrNull() != null && it.toIntOrNull()!! >= 1 }, "only page numbers: $offered")
        assertEquals(offered.sorted(), offered, "in order, so the first suggestion is page one: $offered")
        assertTrue(runtime.router.onTabComplete(reader, command, "meowo", arrayOf("help", "1", "")).isEmpty(),
            "and nothing more once the page is chosen")
        drain(reader)
    }

    /** The reader with the fewest permissions gets the fewest pages: the count is per-reader. */
    @Test fun `page counts follow the reader's permissions`() {
        val reader = addPlayer("Few"); reader.addAttachment(plugin, "meowo.use", true)
        val boss = addPlayer("Many"); boss.addAttachment(plugin, "meowo.use", true)
        boss.addAttachment(plugin, "meowo.admin", true); boss.addAttachment(plugin, "meowo.spawn.set", true)
        boss.addAttachment(plugin, "meowo.nametag.afk", true); boss.addAttachment(plugin, "meowo.render", true)
        val command = plugin.getCommand("meowo")!!

        val readerPages = runtime.router.onTabComplete(reader, command, "meowo", arrayOf("help", "")).size
        val bossPages = runtime.router.onTabComplete(boss, command, "meowo", arrayOf("help", "")).size

        assertTrue(bossPages >= readerPages, "an operator has at least as much to read: $readerPages vs $bossPages")
        drain(reader); drain(boss)
    }

    /**
     * A multi-line block carries its speaker **once**.
     *
     * The help page is one message with newlines in it, so the `✉ MeowCat` prefix belongs to the
     * first line and nothing else. A prefix on every line turns a reference into a column of the same
     * word, which is the complaint that prompted this assertion.
     */
    @Test fun `a multi-line block names its speaker exactly once`() {
        val admin = addPlayer("Boss")
        admin.addAttachment(plugin, "meowo.use", true)
        drain(admin)
        server.dispatchCommand(admin, "meowo help")
        val lines = generateSequence { admin.nextMessage() }.toList()
        val text = lines.joinToString("\n")
        val named = Regex("MeowCat").findAll(text).count()
        assertTrue(named <= 1) { "the speaker must be named once, not per line (found $named): $text" }
        assertTrue(lines.size <= 2) { "the page is one message with newlines, not one per line: $lines" }
        assertTrue(text.contains("\n")) { "and it really is multi-line: $text" }
        assertTrue(named == 1) { "and it is named, not anonymous: $text" }
    }

    /** Counts the armour stands this plugin spawned in the player's world. */
    private fun seats(near: Player): Int =
        near.world.getEntities().count { it is org.bukkit.entity.ArmorStand && it.customName == null }

    // ── sit on blocks ───────────────────────────────────────────────────────────────────────

    private fun placeAt(player: PlayerMock, type: Material): org.bukkit.block.Block {
        val base = player.location.clone()
        base.y = player.world.minHeight.toDouble() + 2
        val block = player.world.getBlockAt(base)
        block.type = type
        clearSeatRoom(block)
        return block
    }

    /**
     * MockBukkit fills an untouched world with GRASS_BLOCK, so "there is air here" has to be
     * stated explicitly. Without this the new head-room check refuses every seat in every test,
     * because a default world is solid everywhere above the block too.
     */
    private fun clearSeatRoom(block: org.bukkit.block.Block) {
        val origin = block.location
        // dy starts at 1: the block just placed is the seat, not part of the room above it.
        for (dx in -1..1) for (dz in -1..1) for (dy in 1..2) {
            val at = Location(block.world, origin.x + dx, origin.y + dy, origin.z + dz)
            block.world.getBlockAt(at).type = Material.AIR
        }
    }

    /**
     * Moves the player onto the walkable step of a stair, which is the only side a chair fits on.
     * A stair's `facing` looks at its full-block half, so the step is the opposite face.
     */
    private fun standOnStepSide(player: PlayerMock, stair: org.bukkit.block.Block) {
        val facing = (stair.blockData as? org.bukkit.block.data.type.Stairs)?.facing ?: return
        val step = facing.oppositeFace.direction
        val spot = Location(
            stair.world, stair.x + 0.5 + step.x * 1.5, stair.y + 2.0, stair.z + 0.5 + step.z * 1.5,
        )
        player.teleport(spot)
    }

    /** Fires a real right-click so the listener under test runs, with an empty hand. */
    private fun rightClick(player: PlayerMock, block: org.bukkit.block.Block) {
        rightClick(player, block, EquipmentSlot.HAND)
    }

    /**
     * Fires one [PlayerInteractEvent] for [hand]. Bukkit dispatches a real right-click twice — once
     * for the main hand and once for the off hand — so tests that only send the main-hand event
     * cannot see a handler that reacts to both.
     */
    private fun rightClick(player: PlayerMock, block: org.bukkit.block.Block, hand: EquipmentSlot) {
        if (hand == EquipmentSlot.HAND) player.inventory.setItemInMainHand(ItemStack(Material.AIR))
        val event = PlayerInteractEvent(
            player, Action.RIGHT_CLICK_BLOCK, player.inventory.itemInMainHand,
            block, org.bukkit.block.BlockFace.UP, hand,
        )
        server.pluginManager.callEvent(event)
    }

    @Test fun `a right click is handled once even though Bukkit fires both hands`() {
        val player = addPlayer("DoubleHand")
        standOnGround(player)
        val block = placeAt(player, Material.OAK_STAIRS)
        // facing=NORTH puts the tall side north, so the step is south. Approaching from the north
        // means the step is on the far side and the click must be refused — that refusal is the
        // message that used to be sent twice.
        val stairs = block.blockData as org.bukkit.block.data.type.Stairs
        stairs.facing = org.bukkit.block.BlockFace.NORTH
        block.blockData = stairs
        player.teleport(block.location.clone().add(0.5, 1.0, -2.5))

        rightClick(player, block, EquipmentSlot.HAND)
        rightClick(player, block, EquipmentSlot.OFF_HAND)

        val lines = generateSequence { player.nextMessage() }.toList()
        assertEquals(1, lines.size, "one click must produce exactly one message, got $lines")
        assertTrue(lines.first().contains("朝向"), "expected the stair-facing refusal, got ${lines.first()}")
    }

    @Test fun `right clicking a slab seats the player on it`() {
        val player = addPlayer("SlabSitter")
        standOnGround(player)
        val slab = placeAt(player, Material.OAK_SLAB)
        rightClick(player, slab)
        assertTrue(player.isInsideVehicle, "right-clicking a slab must seat the player")
        assertEquals(1, seats(player))
    }

    @Test fun `stairs and carpets are sittable but a plain block is not`() {
        for (type in listOf(Material.OAK_STAIRS, Material.WHITE_CARPET)) {
            val player = addPlayer("Clicker${type.name}")
            standOnGround(player)
            val block = placeAt(player, type)
            if (type == Material.OAK_STAIRS) standOnStepSide(player, block)
            rightClick(player, block)
            assertTrue(player.isInsideVehicle, "$type must be sittable")
            server.dispatchCommand(player, "sit")   // stand back up for the next case
        }
        val other = addPlayer("StoneClicker")
        standOnGround(other)
        rightClick(other, placeAt(other, Material.STONE))
        assertFalse(other.isInsideVehicle, "a full block must not be a chair")
    }

    @Test fun `a slab seat applies the configured height and forward offset`() {
        configure { it.set("sit.slab-offset-y", "0.12"); it.set("sit.slab-forward", "0.27") }
        server.dispatchCommand(server.consoleSender, "meowo reload")
        val player = addPlayer("ForwardSitter")
        standOnGround(player)
        val block = placeAt(player, Material.OAK_SLAB)
        val centre = block.location.clone().add(0.5, 0.5, 0.5)
        // Face +Z (yaw 0) so "forward" is a known direction to assert against.
        player.teleport(centre.clone().add(0.0, 0.0, -2.0).also { it.yaw = 0f })
        rightClick(player, block)
        assertTrue(player.isInsideVehicle, "the player must be seated")
        val stand = player.world.getEntities().filterIsInstance<org.bukkit.entity.ArmorStand>()
            .firstOrNull { it.isMarker } ?: error("no seat stand was created")
        val seat = stand.location
        assertEquals(centre.y + 0.12, seat.y, 0.001, "the configured height must reach the runtime seat")
        assertEquals(centre.z + 0.27, seat.z, 0.001, "the configured forward offset must follow a +Z-facing player")
    }

    @Test fun `a held item means the player meant to use it, so the click is not hijacked`() {
        val player = addPlayer("Holder")
        standOnGround(player)
        val slab = placeAt(player, Material.OAK_SLAB)
        player.inventory.setItemInMainHand(ItemStack(Material.STONE))
        val event = PlayerInteractEvent(
            player, Action.RIGHT_CLICK_BLOCK, player.inventory.itemInMainHand, slab, org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND,
        )
        server.pluginManager.callEvent(event)
        assertFalse(player.isInsideVehicle, "a non-empty hand must not sit")
        assertFalse(event.isCancelled, "and the interaction must be left alone")
    }

    @Test fun `a stair is refused from the tall side with its own message`() {
        val player = addPlayer("WrongSide")
        standOnGround(player)
        val stair = placeAt(player, Material.OAK_STAIRS)
        val facing = (stair.blockData as org.bukkit.block.data.type.Stairs).facing
        // Stand on the half-block side, where the step is not, and the chair would be buried in it.
        val away = facing.direction
        player.teleport(Location(stair.world, stair.x + 0.5 + away.x * 1.5, stair.y + 2.0, stair.z + 0.5 + away.z * 1.5))
        rightClick(player, stair)
        assertFalse(player.isInsideVehicle, "the tall side of a stair is not a seat")
        assertTrue(messagesOf(player).contains("台阶朝向另一边"), "the refusal must explain itself")
    }

    @Test fun `a seat with no head room is refused instead of trapping the player`() {
        val player = addPlayer("Claustrophobe")
        standOnGround(player)
        val slab = placeAt(player, Material.OAK_SLAB)
        slab.world.getBlockAt(slab.location.clone().add(0.0, 1.0, 0.0)).type = Material.STONE
        rightClick(player, slab)
        assertFalse(player.isInsideVehicle, "a solid block above the seat must be refused")
        assertTrue(messagesOf(player).contains("上方被挡住"))
    }

    @Test fun `a seat beyond the configured reach is refused`() {
        configure { it.set("sit.max-distance", "1") }
        server.dispatchCommand(server.consoleSender, "meowo reload")
        val player = addPlayer("Stretcher")
        standOnGround(player)
        val slab = placeAt(player, Material.OAK_SLAB)
        val far = Location(slab.world, slab.x + 0.5, slab.y + 2.0, slab.z + 0.5)
        far.add(0.0, 0.0, if (player.location.z < slab.z) 3.5 else -3.5)
        player.teleport(far)
        rightClick(player, slab)
        assertFalse(player.isInsideVehicle, "a chair across the room must be refused")
        assertTrue(messagesOf(player).contains("离得太远"))
    }

    @Test fun `two players cannot claim the same seat`() {
        val first = addPlayer("Claimer")
        standOnGround(first)
        val slab = placeAt(first, Material.OAK_SLAB)
        rightClick(first, slab)
        assertTrue(first.isInsideVehicle)
        val second = addPlayer("Claimant")
        second.teleport(first.location.clone())
        rightClick(second, slab)
        assertFalse(second.isInsideVehicle, "one seat belongs to one player")
        assertTrue(messagesOf(second).contains("已经有人坐着"))
    }

    @Test fun `breaking the chair block releases the rider`() {
        val player = addPlayer("Miner")
        standOnGround(player)
        val slab = placeAt(player, Material.OAK_SLAB)
        rightClick(player, slab)
        assertTrue(player.isInsideVehicle)
        server.pluginManager.callEvent(BlockBreakEvent(slab, player))
        assertFalse(player.isInsideVehicle, "a chair must not outlive its block")
        assertEquals(0, seats(player), "and its stand must be removed, not leaked")
    }

    @Test fun `a seat left behind by a crash is swept instead of trapping the player`() {
        val player = addPlayer("Survivor")
        standOnGround(player)
        // A stand carrying our tag but no live seat entry is exactly what a crash leaves behind.
        val orphan = player.world.spawn(player.location, org.bukkit.entity.ArmorStand::class.java) { stand ->
            stand.isInvisible = true
            stand.isSmall = true
            stand.isMarker = true
            stand.getPersistentDataContainer()
                .set(org.bukkit.NamespacedKey(plugin, "seat"), org.bukkit.persistence.PersistentDataType.BYTE, 1)
        }
        assertFalse(orphan.isDead)
        // The sweep runs when the context starts, so cycle it rather than reloading in place.
        configure { it.set("contexts.sit", false) }
        server.dispatchCommand(server.consoleSender, "meowo reload")
        configure { it.set("contexts.sit", true) }
        server.dispatchCommand(server.consoleSender, "meowo reload")
        assertTrue(orphan.isDead, "a tagged stand from a previous process must be swept on start")
    }

    // ── hat ─────────────────────────────────────────────────────────────────────────────────

    private fun messagesOf(player: PlayerMock): String =
        generateSequence { player.nextMessage() }.toList().joinToString("\n")
    /** Armour pieces: the only things `Material#getEquipmentSlot` answers non-null for. */
    private val equippableMaterials = setOf(
        Material.LEATHER_HELMET, Material.CHAINMAIL_HELMET, Material.IRON_HELMET,
        Material.GOLDEN_HELMET, Material.DIAMOND_HELMET, Material.NETHERITE_HELMET,
        Material.TURTLE_HELMET, Material.COPPER_HELMET, Material.IRON_CHESTPLATE,
        Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.GOLDEN_BOOTS,
    )

    /**
     * MockBukkit cannot answer the two questions the command asks: `Material#getEquipmentSlot`
     * is an unimplemented stub, and `PlayerInventory#getHelmet` throws instead of returning an
     * empty stack. Both seams on [HatContext] exist for that reason, so the tests drive the real
     * default behaviours through stand-ins. The wearable rule itself is covered for real in
     * `HatPolicyTest`, which needs no Bukkit at all.
     */
    private fun hatHarness(
        player: PlayerMock,
        settings: HatSettings = HatSettings(),
        action: (CommandRouter, CommandSender) -> Unit,
    ) {
        val bus = EventBus { _, error -> throw error }
        val messages = Messages(plugin).apply { load("zh_CN") }
        val router = CommandRouter(messages)
        val host = PlatformHost(plugin, bus, router, messages)
        val context = HatContext(
            host, settings,
            equippable = { it in equippableMaterials },
            wornHelmet = { it.inventory.helmet?.takeIf { s -> !s.type.isAir } },
            setHelmet = { p, item -> p.inventory.setHelmet(item ?: ItemStack(Material.AIR)) },
        )
        context.start()
        try { action(router, player) } finally { context.close(); bus.close() }
    }

    @Test fun `hat swaps the held helmet onto the head`() {
        val player = addPlayer("Helmet")
        player.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_HELMET))
        hatHarness(player) { router, sender -> command(router, sender, "hat") }
        assertEquals(Material.DIAMOND_HELMET, player.inventory.helmet?.type, "the helmet must be worn")
        assertEquals(Material.AIR, player.inventory.itemInMainHand.type, "the hand must be emptied")
        assertTrue(messagesOf(player).contains("戴到了头上"))
    }

    @Test fun `hat swaps the previous helmet back into the hand`() {
        val player = addPlayer("Helmet2")
        player.inventory.setHelmet(ItemStack(Material.LEATHER_HELMET))
        player.inventory.setItemInMainHand(ItemStack(Material.NETHERITE_HELMET))
        hatHarness(player) { router, sender -> command(router, sender, "hat") }
        assertEquals(Material.NETHERITE_HELMET, player.inventory.helmet?.type)
        assertEquals(Material.LEATHER_HELMET, player.inventory.itemInMainHand.type,
            "the old helmet must be returned to the hand, not dropped")
    }

    @Test fun `hat with an empty hand takes the helmet off`() {
        val player = addPlayer("Helmet3")
        player.inventory.setHelmet(ItemStack(Material.IRON_HELMET))
        hatHarness(player) { router, sender -> command(router, sender, "hat") }
        assertTrue(player.inventory.helmet == null || player.inventory.helmet.type.isAir,
            "the helmet must come off, got ${player.inventory.helmet}")
        assertEquals(Material.IRON_HELMET, player.inventory.itemInMainHand.type)
    }

    @Test fun `hat refuses a tool and changes nothing`() {
        val player = addPlayer("Tool")
        player.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_PICKAXE))
        hatHarness(player) { router, sender -> command(router, sender, "hat") }
        assertTrue(player.inventory.helmet == null || player.inventory.helmet.type.isAir,
            "a tool must not be worn")
        assertEquals(Material.DIAMOND_PICKAXE, player.inventory.itemInMainHand.type)
        assertTrue(messagesOf(player).contains("不能戴在头上"))
    }

    @Test fun `hat wears ordinary blocks and items that are not tools`() {
        for (material in listOf(Material.WHITE_WOOL, Material.LANTERN, Material.SNOWBALL, Material.DIRT)) {
            val player = addPlayer("Wearer$material")
            player.inventory.setItemInMainHand(ItemStack(material))
            hatHarness(player) { router, sender -> command(router, sender, "hat") }
            assertEquals(material, player.inventory.helmet?.type, "$material must be wearable")
        }
    }

    @Test fun `hat wears non helmet armour on the head`() {
        val player = addPlayer("Armour")
        player.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_CHESTPLATE))
        hatHarness(player) { router, sender -> command(router, sender, "hat") }
        assertEquals(Material.DIAMOND_CHESTPLATE, player.inventory.helmet?.type,
            "armour has durability but is not a tool")
    }

    @Test fun `hat reports when there is nothing to do`() {
        val player = addPlayer("Empty")
        hatHarness(player) { router, sender -> command(router, sender, "hat") }
        assertTrue(messagesOf(player).contains("手上没有东西"))
    }

    @Test fun `a configured deny entry blocks an otherwise wearable item`() {
        val player = addPlayer("Denied")
        player.inventory.setItemInMainHand(ItemStack(Material.BEDROCK))
        hatHarness(player, HatSettings(deny = setOf("BEDROCK"))) { router, sender -> command(router, sender, "hat") }
        assertTrue(player.inventory.helmet == null || player.inventory.helmet.type.isAir,
            "a denied material must not be worn")
    }

    @Test fun `allow tools lets a tool be worn when configured`() {
        val player = addPlayer("ToolOk")
        player.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_PICKAXE))
        hatHarness(player, HatSettings(allowTools = true)) { router, sender -> command(router, sender, "hat") }
        assertEquals(Material.DIAMOND_PICKAXE, player.inventory.helmet?.type)
    }

    /**
     * The head-slot click path is deliberately NOT covered here: MockBukkit's InventoryClickEvent
     * constructor itself throws UnimplementedOperationException, so any such test would be recorded
     * as *skipped* rather than passed — a green build that proves nothing. See docs/minimax/13 §4.
     * The wearable rule that path depends on is covered for real in HatPolicyTest.
     */

    @Test fun `hat is reachable at the root and refuses the console`() {
        val player = addPlayer("Helmet6")
        player.addAttachment(plugin, "meowo.hat", true)
        server.dispatchCommand(player, "hat")
        assertFalse(messagesOf(player).contains("未知命令"), "/hat must be a registered route")
        drain(server.consoleSender)
        server.dispatchCommand(server.consoleSender, "meowo hat")
        assertTrue(generateSequence { server.consoleSender.nextMessage() }.toList().joinToString()
            .contains("只能由玩家执行"), "the console has no head to wear a hat on")
    }

    @Test fun `disabling the hat context removes its route`() {
        val player = addPlayer("Helmet7")
        player.addAttachment(plugin, "meowo.hat", true)
        configure { it.set("contexts.hat", false) }
        server.dispatchCommand(server.consoleSender, "meowo reload")
        server.dispatchCommand(player, "hat")
        assertTrue(messagesOf(player).contains("未知命令"), "a disabled context must drop its route")
    }

    // ── shadow box ─────────────────────────────────────────────────────────────────────────

    /**
     * Drives both key bindings through real events.
     *
     * The window is opened on the next tick on the click binding, because Paper forbids changing the
     * player's view from inside an `InventoryClickEvent`; `performTicks` is therefore part of the
     * interaction, not an optimisation.
     */
    private fun box(player: PlayerMock): ItemStack {
        server.dispatchCommand(server.consoleSender, "shadowbox give ${player.name}")
        drain(server.consoleSender)
        val stack = (0 until player.inventory.size)
            .mapNotNull { player.inventory.getItem(it) }
            .firstOrNull { it.type == Material.SHULKER_BOX }
        assertNotNull(stack, "/shadowbox give must hand out a box")
        return stack!!
    }

    /** The player's own shadow box, found by material wherever it ended up in their inventory. */
    private fun boxIn(player: PlayerMock): ItemStack =
        (0 until player.inventory.size).mapNotNull { player.inventory.getItem(it) }
            .first { it.type == Material.SHULKER_BOX }

    private fun top(player: PlayerMock): Inventory = player.openInventory.topInventory
    private fun bottom(player: PlayerMock): Inventory = player.openInventory.bottomInventory

    /**
     * A stand-in for the screen a player sees after pressing E: its own inventory.
     *
     * `player.openInventory` cannot be used for this. When nothing is open, MockBukkit hands out
     * `SimpleInventoryViewMock(this, null, playerInventory, CRAFTING)` — a view whose top is
     * **null** and whose `convertSlot` throws `UnimplementedOperationException` outright, so
     * `InventoryClickEvent`'s constructor blows up. That failure is swallowed by JUnit as a
     * *skipped* test, which is how three shadow-box cases sat here green-but-never-run.
     *
     * So the view is built by hand from the one implementation that works, `PlayerInventoryViewMock`,
     * over a nine-slot top standing in for the crafting grid. Nine rather than the five a real
     * screen has, because MockBukkit refuses a custom inventory that is not a multiple of nine —
     * and it does not matter, since the production rule reads `top.size` rather than a literal.
     *
     * One mock quirk to keep in mind: `InventoryViewMock#getItem` subtracts the top size with a
     * plain offset and does **not** apply the renumbering `convertSlot` does, so player inventory
     * index *i* is read from raw slot *i + 9*. The two disagree, which is precisely the trap the
     * renumbering test pins.
     */
    private fun ownInventoryView(player: PlayerMock): InventoryView =
        PlayerInventoryViewMock(player, Bukkit.createInventory(null, CRAFTING_GRID, "crafting")).apply {
            type = InventoryType.CRAFTING
        }

    /** Raw slot of [index] in the player's own inventory, as [ownInventoryView] reads it. */
    private fun ownSlot(index: Int): Int = index + CRAFTING_GRID

    /** Shift + right-click on the player's own inventory screen, as the game delivers it. */
    private fun shiftRightClickOwn(player: PlayerMock, index: Int): InventoryClickEvent {
        val event = InventoryClickEvent(
            ownInventoryView(player), InventoryType.SlotType.CONTAINER, ownSlot(index),
            ClickType.SHIFT_RIGHT, InventoryAction.MOVE_TO_OTHER_INVENTORY,
        )
        server.pluginManager.callEvent(event)
        server.scheduler.performTicks(1)
        return event
    }

    /** Shift + left-click is vanilla's hotbar/storage quick-move and must stay untouched. */
    private fun shiftLeftClickOwn(player: PlayerMock, index: Int): InventoryClickEvent {
        val event = InventoryClickEvent(
            ownInventoryView(player), InventoryType.SlotType.CONTAINER, ownSlot(index),
            ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY,
        )
        server.pluginManager.callEvent(event)
        server.scheduler.performTicks(1)
        return event
    }

    private fun shiftClickTop(player: PlayerMock, slot: Int) {
        server.pluginManager.callEvent(
            InventoryClickEvent(
                player.openInventory, InventoryType.SlotType.CONTAINER, slot,
                ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY,
            ),
        )
    }
    /** A click inside the window. Returns it so the caller can assert what we refused. */
    private fun clickTop(player: PlayerMock, slot: Int, type: ClickType = ClickType.LEFT): InventoryClickEvent {
        val event = InventoryClickEvent(
            player.openInventory, InventoryType.SlotType.CONTAINER, slot, type, InventoryAction.PICKUP_ALL,
        )
        server.pluginManager.callEvent(event)
        return event
    }
    /** Holds the box in the main hand and sneaks, which is the second supported binding. */
    private fun sneakRightClick(player: PlayerMock, box: ItemStack) {
        player.inventory.setItemInMainHand(box)
        player.setSneaking(true)
        server.pluginManager.callEvent(interact(player, box, EquipmentSlot.HAND))
    }
    /** Paper 26.2 has no interact constructor without a block, so the block is irrelevant here. */
    private fun interact(
        player: PlayerMock, box: ItemStack, hand: EquipmentSlot,
    ) = PlayerInteractEvent(
        player, Action.RIGHT_CLICK_AIR, box, player.world.getBlockAt(player.location), BlockFace.UP, hand,
    )

    @Test fun `the console hands out a box and both bindings open it`() {
        val player = addPlayer("Keeper")
        val box = box(player)
        assertTrue(messagesOf(player).isEmpty(), "the box was handed to the console, so the player hears nothing")

        // Binding 1: shift + right-click in the player's own inventory.
        val index = indexOfBox(player)
        val click = shiftRightClickOwn(player, index)
        assertTrue(click.isCancelled, "the vanilla quick-move must be suppressed")
        assertTrue(player.openInventory.title!!.contains("潜影盒"), "shift + right-click must open the box")

        player.closeInventory()
        server.scheduler.performTicks(1)
        drain(player)

        // Binding 2: sneak + right-click while holding it.
        sneakRightClick(player, box)
        assertTrue(player.openInventory.title!!.contains("潜影盒"), "sneak + right-click must open the box")
    }

    @Test fun `an unsneaked right click places the box and neither plain click nor shift left click opens it`() {
        val player = addPlayer("Placer")
        val box = box(player)
        player.inventory.setItemInMainHand(box)
        val event = interact(player, box, EquipmentSlot.HAND)
        server.pluginManager.callEvent(event)
        assertFalse(event.isCancelled, "without sneaking the box behaves like an ordinary block")
        assertEquals(InventoryType.CRAFTING, player.openInventory.type, "no window may open")

        val click = InventoryClickEvent(
            ownInventoryView(player), InventoryType.SlotType.CONTAINER, ownSlot(indexOfBox(player)),
            ClickType.LEFT, InventoryAction.PICKUP_ALL,
        )
        server.pluginManager.callEvent(click)
        assertFalse(click.isCancelled, "a plain left click keeps vanilla behaviour")
        // Shift + left-click is vanilla's own quick-move between the hotbar and the storage rows;
        // the binding moved to shift + right-click, so this one must be left to the game.
        val shiftLeft = shiftLeftClickOwn(player, indexOfBox(player))
        assertFalse(shiftLeft.isCancelled, "shift + left-click keeps vanilla behaviour")
        assertEquals(InventoryType.CRAFTING, player.openInventory.type, "only shift + right-click opens the box")
    }

    /**
     * The box must open from **every** part of the player's own inventory, hotbar included.
     *
     * This is the case a converted-slot range silently loses. Bukkit renumbers the own-inventory
     * view, and the hotbar is renumbered *below* storage — so any constant written for "the
     * player's storage" excludes the slot the player is most likely holding the box in, the main
     * hand. The production code therefore reasons in raw slots; this test is what keeps it honest.
     *
     * The first assertion is what stops this quietly rotting: it proves the sweep really does
     * contain a slot the view renumbers into the range a converted-slot rule would miss. Without
     * it, pointing the sweep at friendlier indices would leave the regression unguarded while the
     * test still passed.
     */
    @Test fun `every part of the player's own inventory opens the box`() {
        val player = addPlayer("Swept")
        val view = ownInventoryView(player)
        val renumberedLow = OWN_SLOTS.filter { view.convertSlot(ownSlot(it)) < 9 }
        assertTrue(renumberedLow.isNotEmpty(),
            "the sweep must still include a slot the view renumbers below 9, or it no longer guards anything")

        for (index in OWN_SLOTS) {
            player.closeInventory()
            server.scheduler.performTicks(1)
            player.inventory.setItem(index, box(player))

            val click = shiftRightClickOwn(player, index)
            assertTrue(click.isCancelled, "the vanilla quick-move must be suppressed at index $index")
            assertTrue(player.openInventory.title!!.contains("潜影盒"), "index $index must open the box")
        }
    }

    /**
     * A box sitting in the view's own top half is **not** the player's slot, so it is left alone.
     *
     * On a real server the top of the own-inventory view is the crafting grid, which cannot hold a
     * box; the case is asserted anyway because it is the boundary of the raw-slot rule — everything
     * from `top.size` down is the player's, everything above it is not.
     */
    @Test fun `a box in the view's own top half is left alone`() {
        val player = addPlayer("Grid")
        val view = ownInventoryView(player)
        view.topInventory.setItem(0, box(player))
        val click = InventoryClickEvent(
            view, InventoryType.SlotType.CONTAINER, 0,
            ClickType.SHIFT_RIGHT, InventoryAction.MOVE_TO_OTHER_INVENTORY,
        )
        server.pluginManager.callEvent(click)
        server.scheduler.performTicks(1)
        assertFalse(click.isCancelled, "only the player's own half is ours to take")
        assertEquals(InventoryType.CRAFTING, player.openInventory.type, "no window may open")
    }

    /** Player inventory index of the slot the given box ended up in. */
    private fun indexOfBox(player: PlayerMock): Int =
        (0 until player.inventory.size).first { player.inventory.getItem(it)?.type == Material.SHULKER_BOX }

    @Test fun `contents go back into the box on close and come back out on the next open`() {
        val player = addPlayer("Carrier")
        val box = box(player)
        player.inventory.setItemInMainHand(box)
        server.dispatchCommand(player, "shadowbox")
        assertEquals(27, top(player).size, "a shulker box carries 27 slots")

        // MockBukkit fires the event but does not run vanilla's quick-move transaction, so the
        // transfer is driven by hand. What is asserted is that we **do not touch** the window: an
        // earlier version cleared it on every click, which destroyed the very stack being taken out.
        val spare = (0 until bottom(player).size).first { bottom(player).getItem(it) == null }
        top(player).setItem(3, ItemStack(Material.DIAMOND, 7))
        bottom(player).setItem(spare, ItemStack(Material.EMERALD, 1))
        val take = clickTop(player, 3)
        assertFalse(take.isCancelled, "an ordinary click in the window must keep vanilla behaviour")
        assertEquals(Material.DIAMOND, top(player).getItem(3)?.type,
            "the window must not be emptied under the player's cursor")
        assertEquals(7, top(player).getItem(3)?.amount)
        assertEquals(Material.EMERALD, bottom(player).getItem(spare)?.type,
            "a click in the window must leave the player's own items alone")
        // …and the take-out itself, which vanilla performs once the event is not cancelled.
        bottom(player).setItem(spare, top(player).getItem(3))
        top(player).setItem(3, null)

        player.closeInventory()
        server.scheduler.performTicks(1)
        val stored = boxIn(player)
        assertEquals(boxState(box), stored.let(::boxState), "an empty window must leave the box empty")
        player.inventory.clear()
        player.inventory.setItemInMainHand(stored)

        server.dispatchCommand(player, "shadowbox")
        assertEquals(Material.AIR, top(player).getItem(3)?.type, "nothing was left in the box")

        // Now the other direction: take something out, close, and it must stay out.
        top(player).setItem(5, ItemStack(Material.GOLD_INGOT, 3))
        bottom(player).setItem(spare, top(player).getItem(5))
        top(player).setItem(5, null)
        player.closeInventory()
        server.scheduler.performTicks(1)
        // The box is already back in the hand, and the withdrawn stack must still be where it was put.
        assertEquals(Material.SHULKER_BOX, player.inventory.itemInMainHand.type)
        server.dispatchCommand(player, "shadowbox")
        assertEquals(Material.AIR, top(player).getItem(5)?.type, "a withdrawn stack must not come back")
        assertEquals(Material.GOLD_INGOT, bottom(player).getItem(spare)?.type,
            "…it must be sitting in the player's own inventory")
    }

    @Test fun `taking things out never destroys them`() {
        val player = addPlayer("Taker")
        val box = box(player)
        player.inventory.setItemInMainHand(box)
        server.dispatchCommand(player, "shadowbox")
        top(player).setItem(0, ItemStack(Material.REDSTONE, 32))
        top(player).setItem(1, ItemStack(Material.ARROW, 16))
        // Two ordinary clicks, the way a player takes a stack and then the next one.
        repeat(2) { assertFalse(clickTop(player, it).isCancelled) }
        assertEquals(Material.REDSTONE, top(player).getItem(0)?.type, "slot 0 must survive the click")
        assertEquals(Material.ARROW, top(player).getItem(1)?.type, "slot 1 must survive the click")
    }

    @Test fun `a box cannot be parked inside the window`() {
        val player = addPlayer("Nester")
        val box = box(player)
        val spare = box(player)
        player.inventory.setItemInMainHand(box)
        server.dispatchCommand(player, "shadowbox")
        // Only a box is refused in the window; a nested box would be a second copy of an item that
        // still exists in the world.
        top(player).setItem(4, spare)
        assertTrue(clickTop(player, 4).isCancelled, "a box in the window must be refused")
        assertTrue(clickTop(player, 4, ClickType.SHIFT_LEFT).isCancelled, "…however it is clicked")
        assertTrue(clickTop(player, 4, ClickType.SHIFT_RIGHT).isCancelled, "…and by the open binding too")
    }

    @Test fun `a box left in the window by another plugin is returned instead of stored`() {
        val player = addPlayer("Rescuer")
        val box = box(player)
        val spare = box(player)
        player.inventory.clear()
        player.inventory.setItemInMainHand(box)
        server.dispatchCommand(player, "shadowbox")
        // Simulates a foreign plugin putting a box past our click and drag guards.
        top(player).setItem(6, spare)
        val before = (0 until player.inventory.size).mapNotNull { player.inventory.getItem(it) }
            .count { it.type == Material.SHULKER_BOX }
        player.closeInventory()
        server.scheduler.performTicks(1)
        val after = (0 until player.inventory.size).mapNotNull { player.inventory.getItem(it) }
            .count { it.type == Material.SHULKER_BOX }
        assertEquals(before + 1, after, "the nested box must come back, never vanish into the outer one")
    }

    @Test fun `a box in the offhand opens on its own`() {
        val player = addPlayer("Offhanded")
        val box = box(player)
        player.inventory.setItemInMainHand(ItemStack(Material.AIR))
        player.inventory.setItemInOffHand(box)
        player.setSneaking(true)
        server.pluginManager.callEvent(interact(player, box, EquipmentSlot.OFF_HAND))
        assertTrue(player.openInventory.title!!.contains("潜影盒"),
            "an offhand box must open, the way the shift + right-click binding already did")
    }

    /**
     * Every box is openable on its own and keeps its own contents.
     *
     * Two things about this fixture are load-bearing. The box is **re-read by id** after every
     * close, because writing the contents back produces a *new* stack in the slot, so a reference
     * captured earlier is stale. And the hand is filled by *swapping*, not overwriting: the hand is
     * slot 0 of the inventory, so `setItemInMainHand` on a box that lives in another slot would
     * delete whatever was there.
     */
    @Test fun `every box in the world is openable and keeps its own contents`() {
        val player = addPlayer("Collector")
        box(player); box(player)
        val key = org.bukkit.NamespacedKey(plugin, "box-id")
        fun idOf(stack: ItemStack) = ((stack.itemMeta as? org.bukkit.inventory.meta.BlockStateMeta)
            ?.persistentDataContainer)?.get(key, org.bukkit.persistence.PersistentDataType.STRING)
        fun locate(id: String): Pair<Int, ItemStack> = (0 until player.inventory.size)
            .mapNotNull { slot -> player.inventory.getItem(slot)?.let { slot to it } }
            .first { idOf(it.second) == id }
        /** Swaps the requested box into the hand, putting whatever was in the hand back in its slot. */
        fun hold(id: String) {
            val (slot, stack) = locate(id)
            val inHand = player.inventory.getItem(0)
            if (inHand != null && !inHand.type.isAir) player.inventory.setItem(slot, inHand)
            player.inventory.setItem(0, stack)
        }

        val held = (0 until player.inventory.size).mapNotNull { player.inventory.getItem(it) }
            .filter { it.type == Material.SHULKER_BOX }
        assertEquals(2, held.size, "/shadowbox give must hand out two separate boxes")
        val firstId = requireNotNull(idOf(held[0])) { "a box must carry an id" }
        val secondId = requireNotNull(idOf(held[1])) { "a box must carry an id" }
        assertTrue(firstId != secondId, "every box needs its own id, or they would share contents")

        hold(firstId)
        server.dispatchCommand(player, "shadowbox")
        top(player).setItem(0, ItemStack(Material.DIAMOND, 3))
        player.closeInventory()
        server.scheduler.performTicks(1)

        hold(secondId)
        server.dispatchCommand(player, "shadowbox")
        assertTrue(top(player).contents.all { it == null || it.type.isAir },
            "the second box must start empty, not show the first box's contents")
        top(player).setItem(1, ItemStack(Material.EMERALD, 5))
        player.closeInventory()
        server.scheduler.performTicks(1)

        hold(firstId)
        server.dispatchCommand(player, "shadowbox")
        assertEquals(Material.DIAMOND, top(player).getItem(0)?.type, "the first box kept its own contents")
        assertEquals(Material.AIR, top(player).getItem(1)?.type, "and did not borrow the second box's")
        player.closeInventory()
        server.scheduler.performTicks(1)

        hold(secondId)
        server.dispatchCommand(player, "shadowbox")
        assertEquals(Material.EMERALD, top(player).getItem(1)?.type, "the second box kept its own contents")
        assertEquals(Material.AIR, top(player).getItem(0)?.type, "and did not borrow the first box's")
    }

    @Test fun `a box the plugin never handed out still opens and keeps its contents`() {
        val player = addPlayer("Outsider")
        // A plain shulker box straight from the world: no tag, no id, no custom name.
        val foreign = ItemStack(Material.LIME_SHULKER_BOX)
        val blank = (foreign.itemMeta as org.bukkit.inventory.meta.BlockStateMeta)
            .persistentDataContainer.keys
        assertTrue(blank.isEmpty(), "the fixture must be an untagged box")
        player.inventory.setItemInMainHand(foreign)

        server.dispatchCommand(player, "shadowbox")
        assertTrue(player.openInventory.title!!.contains("潜影盒"), "any container item must open")
        top(player).setItem(2, ItemStack(Material.QUARTZ, 9))
        player.closeInventory()
        server.scheduler.performTicks(1)

        val stored = (0 until player.inventory.size).mapNotNull { player.inventory.getItem(it) }
            .first { it.type == Material.LIME_SHULKER_BOX }
        assertEquals(Material.LIME_SHULKER_BOX, stored.type, "it must still be the item it was")
        val data = (stored.itemMeta as org.bukkit.inventory.meta.BlockStateMeta).persistentDataContainer
        assertNotNull(data.get(org.bukkit.NamespacedKey(plugin, "box-id"), org.bukkit.persistence.PersistentDataType.STRING),
            "an adopted box is tracked from then on")

        player.inventory.setItemInMainHand(stored)
        server.dispatchCommand(player, "shadowbox")
        assertEquals(Material.QUARTZ, top(player).getItem(2)?.type, "its contents must have been written back")
    }

    /**
     * Both bindings must accept a box the plugin never handed out.
     *
     * A shulker box is used rather than an ender chest because MockBukkit's `EnderChestStateMock`
     * exposes no inventory at all, so the capability check cannot be asserted for one here. The rule
     * is the same either way: anything whose block state carries a window-sized inventory.
     */
    @Test fun `an untagged box opens on both bindings`() {
        val player = addPlayer("FOREIGN")
        val foreign = ItemStack(Material.SHULKER_BOX)
        player.inventory.setItemInMainHand(foreign)
        // Shift + right-click in hand.
        player.setSneaking(true)
        server.pluginManager.callEvent(interact(player, foreign, EquipmentSlot.HAND))
        assertTrue(player.openInventory.title!!.contains("潜影盒"))
        player.closeInventory()
        server.scheduler.performTicks(1)

        // Shift + right-click in the own inventory.
        player.inventory.setItemInMainHand(ItemStack(Material.SHULKER_BOX))
        shiftRightClickOwn(player, HOTBAR_FIRST_INDEX)
        assertTrue(player.openInventory.title!!.contains("潜影盒"))
    }

    /**
     * The window must be **empty** once it has been saved, or vanilla refunds it on close.
     *
     * A window made by `Bukkit#createInventory` has no block entity behind it, so
     * `ServerPlayer#doCloseContainer` hands its contents back to the player after
     * `InventoryCloseEvent` has run. Saving into the box without emptying the window therefore
     * pays the player twice — once as the box's contents and once loose in their inventory.
     *
     * MockBukkit's `closeInventory` does not model the refund, so the invariant is asserted
     * directly on the window object, which is exactly what the real server would find non-empty.
     */
    @Test fun `the window is emptied on close so vanilla cannot refund it a second time`() {
        val player = addPlayer("Drain")
        player.inventory.setItemInMainHand(box(player))
        server.dispatchCommand(player, "shadowbox")
        val window = top(player)
        window.setItem(3, ItemStack(Material.DIAMOND, 7))
        window.setItem(9, ItemStack(Material.EMERALD, 3))

        player.closeInventory()
        server.scheduler.performTicks(1)

        val left = window.contents.filterNotNull().filter { !it.type.isAir }
        assertTrue(left.isEmpty(), "a closed window must hold nothing, or vanilla returns it again: $left")
        // And the contents really did go into the box rather than into the void.
        val stored = boxIn(player)
        assertEquals(7, boxAmount(stored, 3), "the diamonds must be in the box")
        assertEquals(3, boxAmount(stored, 9), "the emeralds must be in the box")
    }

    /** How much of anything sits in slot [slot] of [item]'s own container, 0 when empty. */
    private fun boxAmount(item: ItemStack, slot: Int): Int =
        (((item.itemMeta as? org.bukkit.inventory.meta.BlockStateMeta)?.blockState as? Container)
            ?.inventory?.getItem(slot)?.amount) ?: 0

    /**
     * A stack of boxes must not open: a stack shares one id, so every box in it would end up
     * carrying a copy of the same contents the moment the server splits the stack.
     */
    @Test fun `a stack of boxes is refused rather than opened`() {
        val player = addPlayer("Stacked")
        val pair = ItemStack(Material.SHULKER_BOX, 2)
        player.inventory.setItemInMainHand(pair)

        server.dispatchCommand(player, "shadowbox")

        assertTrue(messagesOf(player).contains("拆"), "a stack has to be split before it can be opened")
        assertEquals(InventoryType.CRAFTING, player.openInventory.type, "no window may open")
        assertEquals(2, player.inventory.itemInMainHand.amount, "and the stack must be left intact")
    }

    /**
     * The adopted copy goes back to the box that was opened, not to another one that merely looks
     * the same.
     *
     * Two **untagged** boxes are ordinary items and compare equal, so matching on the stack itself
     * tags whichever sits in the main hand. That is the wrong box whenever the player opens a
     * different one from their inventory — a case that is easy to reach, because nothing about the
     * two boxes differs on screen.
     */
    @Test fun `an untagged box is adopted in the slot it was opened from`() {
        val player = addPlayer("TwoHanded")
        // Byte for byte the same box, so an equality match would pick the main hand one.
        player.inventory.setItemInMainHand(ItemStack(Material.SHULKER_BOX))
        player.inventory.setItem(20, ItemStack(Material.SHULKER_BOX))

        val view = ownInventoryView(player)
        val raw = ownSlot(20)
        // The converted slot of a CRAFTING view is the player inventory index, which is what the
        // production code writes to. Taking it from the view keeps the test honest about *which*
        // box is adopted, which is the property that regressed.
        val opened = view.convertSlot(raw)
        val click = InventoryClickEvent(
            view, InventoryType.SlotType.CONTAINER, raw,
            ClickType.SHIFT_RIGHT, InventoryAction.MOVE_TO_OTHER_INVENTORY,
        )
        server.pluginManager.callEvent(click)
        server.scheduler.performTicks(1)
        assertTrue(click.isCancelled, "the vanilla quick-move must be suppressed")

        assertNotNull(boxIdOf(player.inventory.getItem(opened)), "the box that was opened is the one adopted")
        assertEquals(null, boxIdOf(player.inventory.itemInMainHand), "the identical box elsewhere must be left alone")
    }

    private fun boxIdOf(item: ItemStack?): String? = (item?.itemMeta as? org.bukkit.inventory.meta.BlockStateMeta)
        ?.persistentDataContainer
        ?.get(org.bukkit.NamespacedKey(plugin, "box-id"), org.bukkit.persistence.PersistentDataType.STRING)

    @Test fun `a container that is not a chest-sized window is not a box`() {
        val player = addPlayer("Hopper")
        // A hopper holds five slots, which cannot be laid out as a window.
        player.inventory.setItemInMainHand(ItemStack(Material.HOPPER))
        server.dispatchCommand(player, "shadowbox")
        assertTrue(messagesOf(player).contains("不是潜影盒"), "5 slots is not a window")
        assertEquals(InventoryType.CRAFTING, player.openInventory.type)
    }

    @Test fun `the other boxes in the inventory stay movable while a box is open`() {
        val player = addPlayer("Multi")
        val open = box(player)
        val other = box(player)
        player.inventory.setItemInMainHand(open)
        server.dispatchCommand(player, "shadowbox")
        val openId = ((open.itemMeta as? org.bukkit.inventory.meta.BlockStateMeta)?.persistentDataContainer)
            ?.get(org.bukkit.NamespacedKey(plugin, "box-id"), org.bukkit.persistence.PersistentDataType.STRING)
        val bottom = bottom(player)
        val slot = (0 until bottom.size).first {
            val item = bottom.getItem(it)
            item != null && item.type == Material.SHULKER_BOX &&
                ((item.itemMeta as? org.bukkit.inventory.meta.BlockStateMeta)?.persistentDataContainer
                    ?.get(org.bukkit.NamespacedKey(plugin, "box-id"), org.bukkit.persistence.PersistentDataType.STRING)) != openId
        }
        val raw = top(player).size + slot
        val event = InventoryClickEvent(
            player.openInventory, InventoryType.SlotType.CONTAINER, raw,
            ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY,
        )
        server.pluginManager.callEvent(event)
        assertFalse(event.isCancelled,
            "now that a plain shulker box counts as a box, the guard must match the open box by id, not by kind")
    }

    @Test fun `a box is looked up by id even after the player rearranges their inventory`() {
        val player = addPlayer("Shuffler")
        val box = box(player)
        player.inventory.setItemInMainHand(box)
        server.dispatchCommand(player, "shadowbox")
        top(player).setItem(0, ItemStack(Material.EMERALD, 12))
        // Move the box to another slot while the window is open; the write-back must still find it.
        player.inventory.setItem(20, player.inventory.itemInMainHand)
        player.inventory.setItemInMainHand(ItemStack(Material.AIR))
        player.closeInventory()
        server.scheduler.performTicks(1)
        assertEquals(Material.EMERALD, player.inventory.getItem(20)?.let { boxState(it) },
            "the contents must land on the box, wherever it moved to")
    }

    /** The material of the first item stored in [item]'s container, or AIR when it is empty. */
    private fun boxState(item: ItemStack): Material =
        (((item.itemMeta as? org.bukkit.inventory.meta.BlockStateMeta)?.blockState as? Container)?.inventory
            ?.contents?.filterNotNull()?.firstOrNull { !it.type.isAir }?.type) ?: Material.AIR

    @Test fun `the open box cannot be dropped, swapped or dragged away`() {
        val player = addPlayer("Anchor")
        val box = box(player)
        player.inventory.setItemInMainHand(box)
        server.dispatchCommand(player, "shadowbox")
        // The box is in the hotbar, so aim at the raw slot the view really puts it in rather than
        // at a guessed index: the bottom inventory starts after the window's own slots.
        val slot = (0 until bottom(player).size).first { bottom(player).getItem(it)?.type == Material.SHULKER_BOX }
        val raw = top(player).size + slot
        for (click in listOf(ClickType.DROP, ClickType.CONTROL_DROP, ClickType.SWAP_OFFHAND, ClickType.NUMBER_KEY,
            ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT)) {
            val action = if (click == ClickType.NUMBER_KEY) InventoryAction.HOTBAR_SWAP else InventoryAction.PLACE_ALL
            val event = InventoryClickEvent(
                player.openInventory, InventoryType.SlotType.CONTAINER, raw, click, action, 0,
            )
            server.pluginManager.callEvent(event)
            assertTrue(event.isCancelled, "$click must not move the open box")
        }
        val drag = InventoryDragEvent(
            player.openInventory, ItemStack(Material.AIR), box, false, mapOf(raw to box),
        )
        server.pluginManager.callEvent(drag)
        assertTrue(drag.isCancelled, "a drag must not carry the open box either")
        assertEquals(Material.SHULKER_BOX, bottom(player).getItem(slot)?.type, "the box must still be in place")
    }

    @Test fun `a second box cannot be opened while the first one is open`() {
        val player = addPlayer("TwoHanded")
        val first = box(player)
        val second = box(player)
        player.inventory.setItemInMainHand(first)
        server.dispatchCommand(player, "shadowbox")
        val title = player.openInventory.title
        sneakRightClick(player, second)
        assertEquals(title, player.openInventory.title, "a busy player must not get a second window")
        assertTrue(messagesOf(player).contains("已经打开"))
    }

    @Test fun `a box held in both hands opens once`() {
        val player = addPlayer("Ambidextrous")
        val box = box(player)
        player.inventory.setItemInMainHand(box)
        player.inventory.setItemInOffHand(box)
        player.setSneaking(true)
        for (hand in listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
            server.pluginManager.callEvent(interact(player, box, hand))
        }
        assertTrue(player.openInventory.title!!.contains("潜影盒"), "one window, whichever hand the client reports first")
    }

    @Test fun `quitting with the box open still stores the contents`() {
        val player = addPlayer("Quitter")
        val box = box(player)
        player.inventory.setItemInMainHand(box)
        server.dispatchCommand(player, "shadowbox")
        top(player).setItem(1, ItemStack(Material.GOLD_INGOT, 5))
        server.pluginManager.callEvent(org.bukkit.event.player.PlayerQuitEvent(player, "quit"))
        val stored = (0 until player.inventory.size)
            .mapNotNull { player.inventory.getItem(it) }
            .first { it.type == Material.SHULKER_BOX }
        assertEquals(Material.GOLD_INGOT, boxState(stored), "a disconnect must not lose what was put in")
    }

    @Test fun `disabling the context drops the route and the listeners`() {
        val player = addPlayer("Switcher")
        val box = box(player)
        player.inventory.setItemInMainHand(box)
        configure { it.set("contexts.shadowbox", false) }
        server.dispatchCommand(server.consoleSender, "meowo reload")
        server.dispatchCommand(player, "shadowbox")
        assertTrue(messagesOf(player).contains("未知命令"), "a disabled context must drop its route")
        sneakRightClick(player, box)
        assertEquals(InventoryType.CRAFTING, player.openInventory.type, "a disabled context must not open a window")
    }

    @Test fun `an unusable material is reported and falls back instead of failing the reload`() {
        val player = addPlayer("Config")
        configure { it.set("shadowbox.material", "NOT_A_MATERIAL") }
        server.dispatchCommand(server.consoleSender, "meowo reload")
        assertNotNull(server.consoleSender.nextMessage(), "the reload must be reported as done")
        assertTrue(messagesOf(player).isEmpty())
        server.dispatchCommand(server.consoleSender, "shadowbox give ${player.name}")
        assertNotNull((0 until player.inventory.size).mapNotNull { player.inventory.getItem(it) }
            .firstOrNull { it.type == Material.SHULKER_BOX }, "a bad material must still yield a usable box")
    }

    /**
     * Handing a box out mints an item, so it is closed to ordinary players **by default** and the
     * subcommand is withheld from them everywhere it could show up.
     *
     * The default used to be the other way round, and the config comment even claimed `give` was
     * gated when the code left it open — so any player could `/shadowbox give` themselves a box.
     * Advertising an action that then answers "no permission" is worse than not listing it, so the
     * tab completer and the usage text are both trimmed from the same [mayGive] answer.
     */
    @Test fun `giving a box is closed to players by default and is never advertised to them`() {
        val player = addPlayer("Grumpy")
        server.dispatchCommand(player, "shadowbox give ${player.name}")
        assertTrue(messagesOf(player).contains("权限"), "handing out an item is not open to everyone")
        assertTrue((0 until player.inventory.size).mapNotNull { player.inventory.getItem(it) }
            .none { it.type == Material.SHULKER_BOX }, "and no box may reach them")

        assertEquals(listOf("open"), shadowboxCompletions(player, ""), "the subcommand must not be suggested")
        assertTrue(shadowboxCompletions(player, "gi").isEmpty(), "nor by typing a prefix of it")
        assertTrue(shadowboxCompletions(player, "give", player.name).isEmpty(), "nor completed as a target list")

        server.dispatchCommand(player, "shadowbox nonsense")
        assertFalse(messagesOf(player).contains("give"), "nor mentioned in the usage text")
    }

    /** The operator may open it up on purpose; the two key bindings stay open either way. */
    @Test fun `giving can be opened up deliberately while the key bindings stay open`() {
        val player = addPlayer("Generous")
        configure { it.set("shadowbox.give-permission", false) }
        server.dispatchCommand(server.consoleSender, "meowo reload")
        assertEquals(listOf("open", "give"), shadowboxCompletions(player, ""), "now it is offered")

        val box = box(player)
        sneakRightClick(player, box)
        assertTrue(player.openInventory.title!!.contains("潜影盒"), "the key bindings are for everyone")
    }

    /**
     * Tab completion for the root-level `/shadowbox` alias, which routes to the shadowbox route.
     *
     * Paper 26.2 has no two-argument `Command#tabComplete` left, so the arg array is passed as
     * the server does — including the empty leading arg that a trailing `<TAB>` produces.
     */
    private fun shadowboxCompletions(sender: CommandSender, vararg args: String): List<String> =
        requireNotNull(org.bukkit.Bukkit.getPluginCommand("shadowbox"))
            .tabComplete(sender, "shadowbox", args)

    @Test fun `the console cannot open a box and an empty hand says so`() {
        drain(server.consoleSender)
        server.dispatchCommand(server.consoleSender, "shadowbox")
        assertTrue(generateSequence { server.consoleSender.nextMessage() }.toList().joinToString()
            .contains("只能由玩家执行"))
        val player = addPlayer("Empty")
        server.dispatchCommand(player, "shadowbox")
        assertTrue(messagesOf(player).contains("不是潜影盒"))
    }
}
