package com.meowo.core

import com.meowo.api.*
import com.meowo.home.*
import com.meowo.economy.EconomyContext
import com.meowo.teleport.*
import com.meowo.platform.*
import com.meowo.shared.event.EventBus
import org.bukkit.*
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.potion.PotionEffectType
import org.mockbukkit.mockbukkit.*
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.mockbukkit.mockbukkit.command.MessageTarget
import java.math.BigDecimal
import java.util.Locale
import java.util.UUID
import java.util.concurrent.*

class HomeIntegrationTest {
    private lateinit var server:ServerMock
    private lateinit var plugin:TestPlugin
    private lateinit var host:PlatformHost
    private lateinit var economyContext:EconomyContext
    private lateinit var context:HomeContext
    private lateinit var player:PlayerMock
    private lateinit var ui:FakeUI
    private val packets=mutableListOf<Pair<String,Home>>()
    private val tracked=mutableListOf<UUID>()
    private var moved=0
    private var success=true
    private var loaded=true
    private var held=0
    private var released=0
    private lateinit var load:CompletableFuture<Boolean>
    @BeforeEach fun setup() {
        server=MockBukkit.mock();plugin=MockBukkit.loadWith(TestPlugin::class.java,java.io.File("build/resources/main/plugin.yml"))
        plugin.description.permissions.forEach { server.pluginManager.addPermission(it) };plugin.saveDefaultConfig()
        val messages=Messages(plugin);messages.load("zh_CN");host=PlatformHost(plugin,EventBus { _,e -> throw e },CommandRouter(messages),messages)
        economyContext=EconomyContext(host);economyContext.start()
        player=server.addPlayer("HomeOwner");player.setLocale(Locale.SIMPLIFIED_CHINESE)
        stand(0.5,65.0,0.5);balanceApi().setBalance(player.uniqueId,BigDecimal("10000")).await()
        // Wait for the store rather than for a page: the pages come from `ui.yml` now, so "a page appeared"
        // is not the same moment as "the homes finished loading", and the two raced.
        start();pump { context.capacity(player.uniqueId)>0 }
    }
    private fun start(settings:HomeSettings=HomeSettings()) {
        // The same double the menu and pomodoro suites use: it draws the screens from `ui.yml`, which is where
        // the home pages live now, so a cell that moves in the config moves here too.
        ui=FakeUI(host.messages)
        // A cell names its command the way a player types it (`home gui set 1`); the harness's router path
        // already carries the `home` root. It runs as the player who is looking at the page, which is
        // who the renderer runs it as — a second player's click must not be answered on the first's behalf.
        ui.dispatch={ who, line ->
            val reader=Bukkit.getPlayer(who)
            if(reader!=null) commandAs(reader, *line.removePrefix("home ").split(" ").filter { it.isNotEmpty() }.toTypedArray())
        }
        val nav=object:HomeNavigator {
            // Records *who* was tracked rather than asserting one player: the administrative path
            // legitimately syncs a second player's markers, and a fixture that only knows one of
            // them would fail for the right reason.
            override fun track(p:Player,h:Home) { tracked+=p.uniqueId;packets+="track" to h }
            override fun untrack(p:Player,h:Home) { tracked+=p.uniqueId;packets+="untrack" to h }
        }
        context=HomeContext(host,settings,ui={ ui },navigator=nav,move={ p,l -> moved++;if(success) p.teleport(l) else false },
            loadChunk={ load=CompletableFuture.completedFuture(loaded);load },
            // Counted rather than merely tolerated: the ticket is the fix for "only works when the
            // home is nearby", and MockBukkit's WorldMock has no chunk-ticket implementation to
            // observe, so the counts are the only evidence the production path takes and returns one.
            holdChunk={ held++ },dropChunk={ released++ },
            // MockBukkit has no isPassable implementation; fixtures use only air and full blocks.
            passable={ it.type.isAir })
        context.start();pump { command("gui","set","1");ui.page!=null }
    }
    @AfterEach fun close() { try { if(::context.isInitialized) context.close();if(::economyContext.isInitialized) economyContext.close() } finally {MockBukkit.unmock()} }
    private fun <T> CompletionStage<T>.await()=toCompletableFuture().get(5,TimeUnit.SECONDS)
    private fun balanceApi()=Bukkit.getServicesManager().load(EconomyAPI::class.java)!!
    private fun settlement()=Bukkit.getServicesManager().load(SettlementAPI::class.java)!!
    private fun balance()=balanceApi().balance(player.uniqueId).await()
    private fun command(vararg args:String) { host.commands.onCommand(player,plugin.getCommand("meowo")!!,"meowo",arrayOf("home",*args)) }
    private fun pump(condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!condition()) { assertTrue(System.nanoTime()<deadline,"Home operation timed out");Thread.sleep(5);server.scheduler.performTicks(1) }
    }
    private fun click(slot:Int,right:Boolean=false) { ui.click(slot,right);server.scheduler.performTicks(1) }
    private fun stand(x:Double,y:Double,z:Double) {
        val world=player.world;world.getBlockAt(kotlin.math.floor(x).toInt(),kotlin.math.floor(y).toInt()-1,kotlin.math.floor(z).toInt()).type=Material.STONE
        player.teleport(Location(world,x,y,z));world.getChunkAt(kotlin.math.floor(x).toInt() shr 4,kotlin.math.floor(z).toInt() shr 4)
    }
    private fun create(slot:Int=1) {
        ui.page=null;command("gui","set",slot.toString());pump { ui.page?.title=="确认家园操作" };click(11)
        pump { context.homes(player.uniqueId).any { it.slot==slot } && ui.page?.title?.startsWith("编辑")==true }
    }
    private fun begin() { command("gui","1");click(11);pump { player.getPotionEffect(PotionEffectType.DARKNESS)!=null } }
    @Test fun `new homes default to bowtie and capacity follows explicit permissions`() {
        assertEquals(10,context.capacity(player.uniqueId))
        val attachment=player.addAttachment(plugin,"meowo.home.limit.40",true)
        assertEquals(40,context.capacity(player.uniqueId))
        create(35)
        val home=context.homes(player.uniqueId).single()
        assertTrue(home.visible);assertEquals("bowtie",home.style)
        command("nav","35");server.scheduler.performTicks(20)
        command("nav","off");server.scheduler.performTicks(20)
        attachment.remove();assertEquals(10,context.capacity(player.uniqueId))
        assertEquals(35,context.homes(player.uniqueId).single().slot)
    }
    @Test fun `GUI confirms percent tax and free appearance changes persist across restart`() {
        // Six rows is the house minimum: the confirmation asked for three, and a three-row chest has no
        // bottom row to put the rail on — which is why the exit was silently missing rather than wrong.
        assertEquals(6,ui.page!!.rows)
        create();assertEquals(BigDecimal("9500.00"),balance());assertEquals(BigDecimal("500.00"),settlement().treasury().await())
        assertTrue(packets.any { it.first=="track" && it.second.slot==1 })
        command("rename","1","樱花之家");pump { context.homes(player.uniqueId)[0].name=="樱花之家" }
        click(12);click(16);pump { context.homes(player.uniqueId)[0].color==Home.COLORS.getValue("pink") }
        click(14);click(15);pump { context.homes(player.uniqueId)[0].style=="bowtie" && ui.page?.title?.startsWith("编辑")==true }
        click(15);click(15);pump { context.homes(player.uniqueId)[0].item=="BELL" }
        click(13);pump { !context.homes(player.uniqueId)[0].visible };assertTrue(packets.any { it.first=="untrack" })
        assertEquals(BigDecimal("9500.00"),balance());context.close();start()
        val saved=context.homes(player.uniqueId).single();assertEquals("樱花之家",saved.name);assertEquals("bowtie",saved.style);assertFalse(saved.visible)
        stand(40.5,65.0,40.5);ui.page=null;command("gui","set","1");pump { ui.page?.title=="确认家园操作" };click(11)
        pump { context.homes(player.uniqueId).single().x==40.5 }
        assertEquals(BigDecimal("9025.00"),balance());assertEquals(BigDecimal("975.00"),settlement().treasury().await())
    }
    @Test fun `home travel permits camera motion and charges horizontal distance after full countdown`() {
        create();stand(3000.5,90.0,4000.5);begin();assertEquals(BigDecimal("9000.00"),balance());assertEquals(0,moved)
        val from=player.location.clone();val to=from.clone().apply { yaw=135f;pitch=-40f }
        server.pluginManager.callEvent(PlayerMoveEvent(player,from,to));server.scheduler.performTicks(58)
        assertEquals(0,moved);server.scheduler.performTicks(2);pump { moved==1 && settlement().treasury().await()==BigDecimal("1000.00") }
        assertEquals(0.5,player.location.x);assertEquals(65.0,player.location.y)
        assertTrue(player.getPotionEffect(PotionEffectType.NAUSEA)!!.duration>=19)
        assertTrue(player.getPotionEffect(PotionEffectType.DARKNESS)!!.duration>=19)
    }
    @Test fun `position movement cancels and refunds while tiny corrections do not`() {
        create();stand(30.5,65.0,30.5);begin()
        val from=player.location.clone();val tiny=from.clone().apply { x+=0.04;pitch=50f }
        server.pluginManager.callEvent(PlayerMoveEvent(player,from,tiny));assertEquals(BigDecimal("9400.00"),balance())
        val shifted=from.clone().apply { x+=0.1 }
        server.pluginManager.callEvent(PlayerMoveEvent(player,from,shifted));pump { balance()==BigDecimal("9500.00") }
        server.scheduler.performTicks(80);assertEquals(0,moved);assertEquals(BigDecimal("500.00"),settlement().treasury().await())
    }
    @Test fun `blocked move unsafe home and explicit cancellation do not consume fee`() {
        create();stand(20.5,65.0,20.5);success=false;begin();server.scheduler.performTicks(60);pump { balance()==BigDecimal("9500.00") };assertEquals(1,moved)
        player.removePotionEffect(PotionEffectType.DARKNESS);success=true;begin();command("cancel");pump { balance()==BigDecimal("9500.00") };server.scheduler.performTicks(60);assertEquals(1,moved)
        player.world.getBlockAt(0,65,0).type=Material.LAVA;command("gui","1");click(11);server.scheduler.performTicks(5)
        assertEquals(BigDecimal("9500.00"),balance());assertEquals(BigDecimal("500.00"),settlement().treasury().await())
    }
    @Test fun `a typed home command quotes the price in chat and needs a second identical command`() {
        create()
        val before=balance()
        ui.page=null
        command("1")
        // The first command only quotes: no GUI, and no money moved yet.
        assertTrue(ui.page==null,"a typed command must answer in chat, not open an inventory")
        assertEquals(before,balance(),"quoting a price must not charge")
        assertTrue(
            generateSequence { player.nextMessage() }.toList().any { it.contains("再输入一次") },
            "the quote must tell the player how to confirm",
        )
        // Repeating the same command confirms and actually travels.
        command("1")
        pump { player.getPotionEffect(PotionEffectType.DARKNESS)!=null }
        assertTrue(balance()<before,"the confirmed trip must charge")
    }

    @Test fun `the confirm window expires instead of firing a stale request`() {
        create()
        val before=balance()
        command("1");drain(player)
        Thread.sleep(CONFIRM_WINDOW_OVERRIDE)
        command("1")
        assertTrue(
            generateSequence { player.nextMessage() }.toList().any { it.contains("再输入一次") },
            "an expired quote must re-quote, not travel",
        )
        assertEquals(before,balance(),"an expired quote must not charge")
    }

    @Test fun `home capacity privacy and reload refund are enforced`() {
        for(n in 1..10) create(n)
        assertEquals(10,context.homes(player.uniqueId).size);val before=balance();command("gui","set","11");server.scheduler.performTicks(5);assertEquals(before,balance())
        val other=server.addPlayer("Other");other.setLocale(Locale.SIMPLIFIED_CHINESE)
        host.commands.onCommand(other,plugin.getCommand("meowo")!!,"meowo",arrayOf("home","1"));assertTrue(context.homes(other.uniqueId).isEmpty())
        stand(50.5,65.0,50.5);begin();context.close();assertEquals(before,balance());server.scheduler.performTicks(80);assertEquals(0,moved);start()
        assertEquals(10,context.homes(player.uniqueId).size)
    }
    @Test fun `insufficient balance and permission revocation prevent creation`() {
        balanceApi().setBalance(player.uniqueId,BigDecimal("99")).await();command("gui","set","1");pump { ui.page?.title=="确认家园操作" };click(11);server.scheduler.performTicks(20)
        assertEquals(BigDecimal("99.00"),balance());assertTrue(context.homes(player.uniqueId).isEmpty());assertEquals(BigDecimal("0.00"),settlement().treasury().await())
        val attachment=player.addAttachment(plugin,"meowo.home",false);command("gui");assertTrue(context.homes(player.uniqueId).isEmpty());attachment.remove()
    }
    @Test fun `restart reconciles a pending paid relocation with refunded economy escrow`() {
        create();val old=context.homes(player.uniqueId).single();context.close()
        val ticket=UUID.randomUUID();settlement().reserveTax(ticket,player.uniqueId,HomeSettings().setAssessment()).await()
        HomeStore(plugin.dataFolder.resolve("home/homes.db").toPath()).use { it.stage(old.copy(x=900.0),old,ticket,10).await() }
        economyContext.close();economyContext=EconomyContext(host);economyContext.start();start()
        assertEquals(old,context.homes(player.uniqueId).single());assertEquals(BigDecimal("9501.00"),balance())
        // Restart replays the login reward: one Meo comes from the previously committed home tax.
        assertEquals(BigDecimal("499.00"),settlement().treasury().await())
        assertEquals(ChargeState.REFUNDED,settlement().chargeStatus(ticket).await()!!.state)
    }
    @Test fun `restart finishes committed paid relocation journal without refunding tax`() {
        create();val old=context.homes(player.uniqueId).single();context.close()
        val ticket=UUID.randomUUID();settlement().reserveTax(ticket,player.uniqueId,HomeSettings().setAssessment()).await()
        HomeStore(plugin.dataFolder.resolve("home/homes.db").toPath()).use { it.stage(old.copy(x=900.0),old,ticket,10).await() }
        settlement().settleTeleport(ticket,true).await();economyContext.close();economyContext=EconomyContext(host);economyContext.start();start()
        assertEquals(900.0,context.homes(player.uniqueId).single().x);assertEquals(BigDecimal("9026.00"),balance())
        assertEquals(BigDecimal("974.00"),settlement().treasury().await())
    }
    @Test fun `TPA and home cannot reserve simultaneous travel for the same player`() {
        create();val accepter=server.addPlayer("Accepter")
        val tpa=TeleportContext(host,TeleportSettings(countdownSeconds=3),move={ _,_ -> false })
        tpa.start()
        try {
            host.commands.onCommand(player,plugin.getCommand("meowo")!!,"meowo",arrayOf("tpa",accepter.name))
            host.commands.onCommand(accepter,plugin.getCommand("meowo")!!,"meowo",arrayOf("tpa","accept",player.name))
            pump { balance()==BigDecimal("9490.50") }
            command("gui","1");click(11);server.scheduler.performTicks(5)
            assertEquals(BigDecimal("9490.50"),balance());assertEquals(0,moved)
        } finally { tpa.close() }
        assertEquals(BigDecimal("9500.00"),balance())
    }

    /**
     * The failure forced travel exists for.
     *
     * The home was set on solid ground and then the block underneath it was mined out: the world
     * moved, the stored coordinates did not. A normal trip refuses — correctly, and permanently,
     * because the exact saved block has no floor and never will again. The forced trip goes to the
     * same coordinates anyway, so the owner is not locked out of a base they already paid for.
     */
    @Test fun `a home that stopped being standable is refused normally and reachable with force`() {
        create()
        player.world.getBlockAt(0,64,0).type=Material.AIR
        drain(player)

        command("gui","1");click(11);server.scheduler.performTicks(10)

        assertEquals(BigDecimal("9500.00"),balance(),"a refused trip must not charge")
        assertEquals(0,moved,"and must not move the player either")
        assertTrue(spoken(player).contains("落点不安全"),"and it has to say why: ${spoken(player)}")

        command("gui","1","force");click(11);pump { moved==1 }

        assertEquals(0.5,player.location.x);assertEquals(65.0,player.location.y)
        assertEquals(BigDecimal("9400.00"),balance(),"a forced trip costs the same as a normal one")
    }

    /**
     * Force is a way out of a broken home, so both gates have to be real: a player without the
     * permission must not get it from the config default alone, and a server that turned it off must
     * say the verb is off rather than silently treating `force` as a typo and printing a usage line.
     */
    @Test fun `force travel is gated by its own permission and by the config switch`() {
        create()
        player.world.getBlockAt(0,64,0).type=Material.AIR

        val attachment=player.addAttachment(plugin,"meowo.home.force",false)
        command("1","force");command("1","force");server.scheduler.performTicks(10)
        assertEquals(BigDecimal("9500.00"),balance());assertEquals(0,moved)
        assertTrue(spoken(player).contains("没有权限"),"the refusal is a permission sentence: ${spoken(player)}")
        attachment.remove()

        context.close()
        // Put the floor back before restarting: the fixture sets its first home, and a set is
        // refused from a spot with no ground under it — which is the very rule under test here.
        player.world.getBlockAt(0,64,0).type=Material.STONE
        start(HomeSettings(force=false));drain(player)
        command("1","force");command("1","force");server.scheduler.performTicks(10)
        assertEquals(BigDecimal("9500.00"),balance());assertEquals(0,moved)
        assertTrue(spoken(player).contains("未开放强制传送"),"or a sentence about the switch: ${spoken(player)}")
    }

    /**
 * The chunk has to stay resident for the whole countdown, and has to be given back afterwards.
 *
 * This is the distance bug in its smallest form: `getChunkAtAsync` drops its ticket when the load
 * finishes, so a home outside view distance was unloaded again before the move — the trip failed and
 * refunded every time, no matter how far the player was willing to force it. A leaked ticket is the
 * opposite failure, and it is invisible until the server is mysteriously slow weeks later.
 */
@Test fun `a trip holds its destination chunk for the countdown and gives the ticket back`() {
    create()
    begin()
    assertEquals(1,held,"the destination is pinned before the load, not after it")

    server.scheduler.performTicks(60);pump { moved==1 }
    assertEquals(1,released,"a finished flight gives its column back")

    command("gui","1");click(11);pump { player.getPotionEffect(PotionEffectType.DARKNESS)!=null }
    command("cancel");server.scheduler.performTicks(5)
    assertEquals(2,held);assertEquals(2,released,"and so does a cancelled one")
}

/** The window is 10s in production; the test needs it observable without a real wait. */
    private val CONFIRM_WINDOW_OVERRIDE=11_000L
    private fun drain(p:MessageTarget) { while(p.nextMessage()!=null) { } }

    // ── administrative: another player's homes ───────────────────────────────────────────────

    /** A second player holding `meowo.home.admin`. Op by default; the attachment says so explicitly. */
    private fun admin(name:String="HomeAdmin"):PlayerMock =
        server.addPlayer(name).also {
            it.setLocale(Locale.SIMPLIFIED_CHINESE)
            it.addAttachment(plugin,"meowo.home",true)
            it.addAttachment(plugin,"meowo.home.admin",true)
        }

    /** The same route, run by somebody other than [player]. */
    private fun commandAs(who:CommandSender,vararg args:String) {
        host.commands.onCommand(who,plugin.getCommand("meowo")!!,"meowo",arrayOf("home",*args))
    }

    private fun spoken(p:MessageTarget):String =
        generateSequence { p.nextMessage() }.toList().joinToString("\n")

    /** Puts [who] on solid ground at a spot of their own, since [stand] only moves [player]. */
    private fun stand(who:Player,x:Double,y:Double,z:Double) {
        val world=who.world
        world.getBlockAt(kotlin.math.floor(x).toInt(),kotlin.math.floor(y).toInt()-1,kotlin.math.floor(z).toInt()).type=Material.STONE
        who.teleport(Location(world,x,y,z))
        world.getChunkAt(kotlin.math.floor(x).toInt() shr 4,kotlin.math.floor(z).toInt() shr 4)
    }

    /**
     * The permission boundary, in the direction that matters: a player who may use homes still may
     * not read anybody else's. Refusing quietly would leave them thinking the feature is broken.
     */
    @Test fun `a player who is not an admin cannot list somebody else's homes`() {
        create()
        val other=server.addPlayer("Nosy")

        commandAs(other,"view",player.name)

        assertTrue(spoken(other).contains("§c✖"), "the refusal has to be visible: ${spoken(other)}")
        assertEquals(1,context.homes(player.uniqueId).size,"and nothing about the target changed")
    }

    /**
     * Private homes are included, because an admin reading somebody's homes is the reason the
     * command exists — hiding the hidden ones would make it answer a question nobody asked.
     */
    @Test fun `an admin sees another player's homes, private ones included`() {
        create(1);create(2);ui.page=null;command("show","2","off");pump { !context.homes(player.uniqueId)[1].visible }
        val boss=admin()

        commandAs(boss,"view",player.name)

        val said=spoken(boss)
        assertTrue(said.contains("家 1"), "slot 1: $said")
        assertTrue(said.contains("家 2"), "the hidden one too: $said")
        assertTrue(said.contains("隐藏"), "and marked as hidden, rather than silently included: $said")
    }

    /**
     * The whole point of the tax decision, asserted in money.
     *
     * Both wallets and the treasury must be untouched: an admin correcting a base is not something
     * the server bills, and billing the owner would make the common case — an offline player whose
     * base is broken — fail for want of funds.
     */
    @Test fun `an admin's set costs neither party anything`() {
        val boss=admin();stand(boss,80.5,65.0,80.5)
        val ownerBefore=balance();val bossBefore=balanceApi().balance(boss.uniqueId).await()
        val treasuryBefore=settlement().treasury().await()

        commandAs(boss,"set",player.name,"7")

        pump { context.homes(player.uniqueId).any { it.slot==7 } }
        val home=context.homes(player.uniqueId).single { it.slot==7 }
        assertEquals(80.5,home.x,1e-9,"it lands where the admin is standing, not where the owner was")
        assertEquals(player.uniqueId,home.owner)
        assertEquals(ownerBefore,balance(),"the owner's wallet is not the server's to spend")
        assertEquals(bossBefore,balanceApi().balance(boss.uniqueId).await(),"nor is the admin's")
        assertEquals(treasuryBefore,settlement().treasury().await(),"and the treasury must not grow either")
    }

    /**
     * The case that decides the tax: a player who is not here cannot pay, cannot be asked, and is
     * exactly whose home most needs repairing. Every "must be online" step has to have an offline
     * path or this command is decoration.
     */
    @Test fun `an admin can set a home for somebody who is offline`() {
        val boss=admin();stand(boss,120.5,65.0,120.5)
        val away=context.homes(player.uniqueId).isEmpty()
        assertTrue(away,"the owner has no homes yet, so the slot is genuinely new")
        player.disconnect();server.scheduler.performTicks(2)

        commandAs(boss,"set",player.name,"3")

        pump { context.homes(player.uniqueId).any { it.slot==3 } }
        val home=context.homes(player.uniqueId).single { it.slot==3 }
        assertEquals(120.5,home.x,1e-9)
        assertTrue(spoken(boss).contains("HomeOwner"),"and the admin is told whose it was for")
    }

    /** An offline owner cannot be told, so the admin's line has to name them — it is the only record. */
    @Test fun `the offline owner is not spoken to, and the admin still hears about it`() {
        val boss=admin();stand(boss,140.5,65.0,140.5)
        player.disconnect();server.scheduler.performTicks(2)

        commandAs(boss,"set",player.name,"4")

        pump { context.homes(player.uniqueId).any { it.slot==4 } }
        val said=spoken(boss)
        assertTrue(said.contains("#4"), "slot and id in the admin's reply: $said")
        assertTrue(said.contains("不收设置税"), "and that it was free, so nobody later wonders who paid: $said")
    }

    /** Permission on the route must not turn every admin command into an admin command. */
    @Test fun `an admin can still set their own home with the plain form`() {
        val boss=admin();stand(boss,160.5,65.0,160.5)
        balanceApi().setBalance(boss.uniqueId,BigDecimal("10000")).await()

        ui.page=null;commandAs(boss,"gui","set","5");pump { ui.page?.title=="确认家园操作" };click(11)

        pump { context.homes(boss.uniqueId).any { it.slot==5 } }
        val home=context.homes(boss.uniqueId).single { it.slot==5 }
        assertEquals(boss.uniqueId,home.owner,"their own home, not an empty owner's")
        assertEquals(160.5,home.x,1e-9)
    }

    /**
     * A name the server has never seen must fail with a sentence.
     *
     * `getOfflinePlayer(String)` would have invented a profile for it — and the alternative
     * (creating an empty owner) is what turns a typo into a row that outlives the typo.
     */
    @Test fun `a name the server has never seen is refused`() {
        val boss=admin()

        commandAs(boss,"set","NeverJoinedHere","1")

        val said=spoken(boss)
        assertTrue(said.contains("不认得"), "and it has to say so rather than inventing a player: [$said]")
    }

    /** An empty admin listing is a sentence, not silence — and it is a different sentence from "unknown". */
    @Test fun `an admin listing somebody with no homes gets an answer`() {
        val boss=admin()
        server.addPlayer("Fresh")  // has joined, so the server knows them; has set nothing

        commandAs(boss,"view","Fresh")

        val said=spoken(boss)
        assertTrue(said.contains("一个都没有"), "silence reads as a broken command: [$said]")
        assertFalse(said.contains("不认得"), "and they are known, so 'unknown' would be a lie: [$said]")
    }

    /** The owner's limit is the owner's; the admin's own capacity must not stand in for it. */
    @Test fun `an admin cannot exceed the owner's own home limit`() {
        val boss=admin();stand(boss,180.5,65.0,180.5)
        player.addAttachment(plugin,"meowo.home.limit.1",true)
        server.scheduler.performTicks(2)

        commandAs(boss,"set",player.name,"1");pump { context.homes(player.uniqueId).any { it.slot==1 } }
        commandAs(boss,"set",player.name,"2");server.scheduler.performTicks(5)

        assertEquals(1,context.homes(player.uniqueId).size,"the owner's limit, not the admin's")
        assertTrue(spoken(boss).contains("最多"), "and the reason is stated")
    }
}
