package com.meowo.home

import com.meowo.api.*
import com.meowo.api.UIAPI
import com.meowo.api.UIBindings
import com.meowo.platform.*
import com.meowo.shared.lifecycle.Context
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.*
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.player.*
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.Locale
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/** Private homes, paid location changes, UI and navigation. Only immutable values reach storage. */
class HomeContext(private val host: PlatformHost, private val settings: HomeSettings,
    /** The UI service, resolved per open so the composition root's ordering cannot leave a stale null. */
    private val ui: () -> UIAPI? = { Bukkit.getServicesManager().load(UIAPI::class.java) },
    private val navigator: HomeNavigator? = null,
    /**
     * The actual arrival, when a caller supplies one.
     *
     * `null` means production, and production uses `teleportAsync` — see [arrive]. A caller-supplied
     * one is how the tests observe the move synchronously; MockBukkit's `teleportAsync` throws, so
     * the default would leave only the exploding branch reachable.
     */
    private val move: ((Player,Location)->Boolean)? = null,
    /**
     * Loads a home's chunk, generating it when it has never been visited.
     *
     * `generate = true` is the point of the change: a home is somewhere the owner stood at some
     * point, but the server may not have kept that column — and refusing to travel to an
     * ungenerated chunk would make `/meo home` useless for exactly the homes that are furthest
     * away. The load is asynchronous, so generation never runs on a tick.
     */
    private val loadChunk: (Location)->CompletableFuture<Boolean> = { l ->
        l.world.getChunkAtAsync(l.blockX shr 4,l.blockZ shr 4,true).thenApply { it!=null }
    },
    /**
     * Holds the destination column for the whole countdown, and gives it back afterwards.
     *
     * Loading a chunk is not the same as keeping it: `getChunkAtAsync` releases its ticket as soon
     * as the load finishes, and a column that is outside every player's view distance has nothing
     * else holding it. So the chunk was gone again by the time the countdown ended — which is why
     * `/meo home` only ever worked for homes near where the player already was. See [arrive] for the
     * other half of the fix; this one keeps the *validated* chunk from being unloaded before the move.
     */
    private val holdChunk: (Location)->Unit = { l -> l.world.addPluginChunkTicket(l.blockX shr 4,l.blockZ shr 4,host.plugin) },
    private val dropChunk: (Location)->Unit = { l -> l.world.removePluginChunkTicket(l.blockX shr 4,l.blockZ shr 4,host.plugin) },
    private val passable: (org.bukkit.block.Block)->Boolean = { it.isPassable },
) : Context, Listener, HomeMenuAPI {
    override val id = "home"
    private val active=AtomicBoolean(false)
    private var ready=false
    private lateinit var store: HomeStore
    private lateinit var economy: EconomyAPI
    private lateinit var settlement: SettlementAPI
    private lateinit var markers: HomeNavigator
    private val presentation=TravelPresentation(host,active::get)
    private val cache=mutableMapOf<UUID,MutableMap<Int,Home>>()
    private val busy=mutableMapOf<UUID,UUID>()
    private val jobs=mutableSetOf<CompletableFuture<*>>()
    private val flights=mutableMapOf<UUID,Flight>()
    /** Destination column held by a plugin ticket, per travel ticket. Dropped in [release]. */
    private val held=mutableMapOf<UUID,Location>()
    private val shown=mutableMapOf<UUID,List<Home>>()
    private val selected=mutableMapOf<UUID,Int>()
    private val prompts=ConcurrentHashMap<UUID,Home>()
    private val promptTasks=mutableMapOf<UUID,BukkitTask>()
    private val resources=mutableListOf<AutoCloseable>()
    private var transportFailed=false
    private data class Flight(val ticket: UUID,val player: Player,val home: Home,val origin: Location,
        var destination: Location,val force: Boolean,var task: BukkitTask?=null,var moving: Boolean=false)

    fun homes(owner: UUID): List<Home> = cache[owner]?.values?.sortedBy { it.slot }.orEmpty()
    override fun capacity(owner: UUID): Int {
        val player=Bukkit.getPlayer(owner) ?: return settings.limit
        return player.effectivePermissions.filter { it.value && it.permission.startsWith("meowo.home.limit.") }
            .mapNotNull { it.permission.substringAfterLast('.').toIntOrNull()?.takeIf { n -> n in 1..1000 } }
            .maxOrNull() ?: settings.limit
    }
    override fun entries(owner: UUID) = homes(owner).map { HomeMenuEntry(it.slot,it.name,it.color,it.item,it.worldName,it.x,it.y,it.z,it.visible,it.style) }
    private val bars=mutableMapOf<UUID,net.kyori.adventure.bossbar.BossBar>()
    /** Typed commands ask twice; this is what the first ask leaves behind. */
    private val pending=mutableMapOf<UUID,PendingRequest>()

    /**
     * What the confirmation currently on screen will do if answered yes.
     *
     * Held here rather than in the page because the page is `ui.yml` now: its "yes" cell runs
     * `meowo home gui confirm`, and this is what that command finds.
     */
    private val confirmed=mutableMapOf<UUID,()->Unit>()
    private fun stopNavigation(player: Player) {
        selected.remove(player.uniqueId)
        bars.remove(player.uniqueId)?.let { player.hideBossBar(it) }
    }
    override fun start() {
        economy=requireNotNull(Bukkit.getServicesManager().load(EconomyAPI::class.java)) { "Home requires economy" }
        settlement=requireNotNull(Bukkit.getServicesManager().load(SettlementAPI::class.java))
        store=HomeStore(host.plugin.dataFolder.resolve("home/homes.db").toPath())
        markers=navigator ?: if(settings.locator && Bukkit.getPluginManager().isPluginEnabled("packetevents")) PacketHomeNavigator() else NoHomeNavigator()
        if(settings.locator && navigator==null && markers is NoHomeNavigator) host.plugin.logger.warning("Home locator requires PacketEvents 2.14.0; BossBar navigation remains available")
        Bukkit.getServicesManager().register(HomeMenuAPI::class.java,this,host.plugin,org.bukkit.plugin.ServicePriority.Normal)
        resources+=AutoCloseable { Bukkit.getServicesManager().unregister(HomeMenuAPI::class.java,this) }
        active.set(true)
        Bukkit.getPluginManager().registerEvents(this,host.plugin)
        resources+=AutoCloseable { HandlerList.unregisterAll(this) }
        val loading=store.pending().thenCompose { tickets ->
            CompletableFuture.allOf(*tickets.map { ticket -> settlement.chargeStatus(ticket).thenCompose { charge ->
                store.finish(ticket,charge?.state==ChargeState.COMMITTED)
            }.toCompletableFuture() }.toTypedArray())
        }.thenCompose { store.all() }
        jobs+=loading
        loading.whenComplete { values,error -> host.mainThread(active::get) {
            jobs.remove(loading)
            if(error!=null) { host.plugin.logger.log(Level.SEVERE,"Home recovery failed",error);return@mainThread }
            values.forEach { cache.getOrPut(it.owner) { mutableMapOf() }[it.slot]=it }
            ready=true;Bukkit.getOnlinePlayers().forEach(::syncMarkers)
        } }
        resources+=host.commands.register("home", "home",object:Route {
            override val permission="meowo.home"
            override fun execute(sender: CommandSender,args: List<String>) {
                if(sender !is Player) { host.messages.send(sender,"player-only");return }
                if(!available(sender)) return
                if(args.isEmpty()) { listMenu(sender);return }
                // A `gui` marker records that the click came from a menu, so the reply can stay in the
                // inventory the player is already looking at. Typed commands answer in chat instead.
                val fromGui=args[0].equals("gui",true)
                val rest=if(fromGui) args.drop(1) else args
                if(rest.isEmpty()) { listMenu(sender);return }
                val origin=if(fromGui) Origin.MENU else Origin.CHAT
                val verb=rest[0].lowercase(Locale.ROOT)
                when(verb) {
                    "cancel" -> cancel(sender)
                    "set" -> setFor(sender,rest,origin)
                    "view" -> if(!admin(sender)) host.messages.send(sender,"no-permission")
                        else if(rest.size==2) viewHomes(sender,rest[1]) else usage(sender)
                    "edit" -> find(sender,rest.getOrNull(1))?.let { editMenu(sender,it) }
                    "delete" -> find(sender,rest.getOrNull(1))?.let { confirmDelete(sender,it,origin) }
                    "rename" -> if(rest.size>=3) find(sender,rest[1])?.let { rename(sender,it,rest.drop(2).joinToString(" ")) }
                        else find(sender,rest.getOrNull(1))?.let { promptRename(sender,it) } ?: usage(sender)
                    "confirm" -> confirmPending(sender)
                    "color" -> if(rest.size==3) find(sender,rest[1])?.let { home ->
                        val rgb=Home.parseColor(rest[2]);if(rgb==null) host.messages.send(sender,"home-invalid-color") else update(sender,home,home.copy(color=rgb))
                    } else find(sender,rest.getOrNull(1))?.let { colorMenu(sender,it) } ?: usage(sender)
                    // Opening the icon page; setting a style from it is the three-argument form below.
                    "icons" -> find(sender,rest.getOrNull(1))?.let { home ->
                        if(rest.getOrNull(2)=="menu") iconMenu(sender,home,false) else iconMenu(sender,home,true)
                    } ?: usage(sender)
                    "show" -> if(rest.size==3 && rest[2] in listOf("on","off")) find(sender,rest[1])?.let { update(sender,it,it.copy(visible=rest[2]=="on")) } else usage(sender)
                    // Two different things share the word "icon": the bar's two shapes change the
                    // **style**, the menu's sixteen change the **icon** the home is drawn with. Both
                    // arrive as the third argument, so the argument decides which one it is — and a
                    // material the server does not know is a usage line, not a silent no-op.
                    "icon" -> when {
                        rest.size!=3 -> usage(sender)
                        rest[2] in listOf("default","bowtie") ->
                            find(sender,rest[1])?.let { update(sender,it,it.copy(style=rest[2])) }
                        else -> {
                            val material=Home.ITEMS.firstOrNull { it.equals(rest[2],true) }
                            if(material==null) usage(sender)
                            else find(sender,rest[1])?.let { update(sender,it,it.copy(item=material)) }
                        }
                    }
                    "nav" -> if(rest.getOrNull(1)=="off") { stopNavigation(sender) }
                        else find(sender,rest.getOrNull(1))?.let { selected[sender.uniqueId]=it.slot;host.messages.send(sender,"home-nav-selected",it.name) }
                    else -> {
                        // `/home <编号> force` — the only way to reach a home whose saved spot has
                        // stopped being standable. Parsed here rather than as its own verb because
                        // the target comes first, which is what lets tab-completion offer it.
                        val force=rest.size==2 && rest[1].equals("force",true)
                        if(rest.size==1||force) find(sender,rest[0])?.let { confirmTravel(sender,it,origin,force) } else usage(sender)
                    }
                }
            }
            override fun complete(sender: CommandSender,args: List<String>): List<String> {
                if(sender !is Player) return emptyList()
                val head=args.firstOrNull()
                val shifted=if(head.equals("gui",true)) args.drop(1) else args
                val verb=shifted.firstOrNull()
                // Admin verbs are offered to nobody else: the help page hides a command the sender
                // may not run, and a verb that silently does nothing is worse than an absent one.
                val verbs=buildList {
                    addAll(listOf("gui","set","edit","delete","rename","color","show","icon","nav","cancel"))
                    if(admin(sender)) add("view")
                }
                val choices=if(verb==null) verbs+homes(sender.uniqueId).flatMap { listOf(it.slot.toString(),it.name) }
                else if(shifted.size==1) homes(sender.uniqueId).flatMap { listOf(it.slot.toString(),it.name) }
                else if(shifted.size==2) when(verb) { "color" -> Home.COLORS.keys.toList(); "show" -> listOf("on","off")
                    // The two bar styles and the sixteen menu icons, because the page offers exactly these.
                    "icon" -> listOf("default","bowtie")+Home.ITEMS.map { it.lowercase(Locale.ROOT) }
                    // Only after a real target, so `force` never looks like a verb of its own.
                    else -> if(forceAvailable(sender) && homes(sender.uniqueId).any { it.slot.toString()==verb || it.name.equals(verb,true) })
                        listOf("force") else emptyList() }
                else emptyList()
                return choices.filter { it.startsWith(shifted.lastOrNull().orEmpty(),true) }
            }
        })
        var navigationTick=0
        val nav=Bukkit.getScheduler().runTaskTimer(host.plugin,Runnable {
            if(ready) Bukkit.getOnlinePlayers().forEach { if(navigationTick%10==0) syncMarkers(it);textNavigation(it) };navigationTick++
        },2L,2L)
        resources+=AutoCloseable { nav.cancel() }
    }
    private fun available(player: Player): Boolean {
        if(!active.get() || !ready) { host.messages.send(player,"home-loading");return false }
        if(!player.hasPermission("meowo.home")) { host.messages.send(player,"no-permission");return false }
        return true
    }
    /** An admin who fumbles the syntax is told about the verbs a plain player cannot see. */
    private fun usage(player: Player) {
        host.messages.send(player,"home-usage")
        if(forceAvailable(player)) host.messages.send(player,"home-force-usage")
        if(admin(player)) host.messages.send(player,"home-admin-usage")
    }
    /**
     * Both gates on the force verb: the config switch and the permission.
     *
     * Permission rather than config alone, because "let my regulars force-teleport but not the
     * griefers" is a per-player decision the server owner wants to make; config alone would force
     * the choice to be all-or-nothing for the whole player base.
     */
    private fun forceAvailable(player: Player) = settings.force && player.hasPermission("meowo.home.force")

    // ── administrative: somebody else's homes ─────────────────────────────────────────────────

    /** Deliberately its own node rather than `meowo.admin`, so a server can hand out home duty alone. */
    private fun admin(player: Player): Boolean = player.hasPermission("meowo.home.admin")

    /**
     * Online first, then only what the server has already seen.
     *
     * Never `getOfflinePlayer(String)`: it may block on a profile lookup, and it will happily invent
     * a profile for a name that has never joined. The case that needs offline support is the common
     * one — a player who has been here before and whose home is broken — so the cached lookup covers
     * it, and everything else fails with a sentence rather than a timeout.
     */
    private fun resolveTarget(name: String): UUID? =
        Bukkit.getPlayerExact(name)?.uniqueId ?: Bukkit.getServer().getOfflinePlayerIfCached(name)?.uniqueId

    /** A name an admin can recognise, with the id behind it so two similar names are distinguishable. */
    private fun labelOf(id: UUID, fallback: String): String =
        Bukkit.getPlayer(id)?.name ?: "$fallback §8(${id.toString().take(8)})"

    /**
     * `/meo home view <player>` — the other list, in chat.
     *
     * Chat rather than a menu on purpose: the menu is built for one owner, and an admin inspecting
     * somebody's homes is a read, not a place to click around. Private homes are shown too — that is
     * the whole reason to ask, and the permission is what makes it acceptable.
     */
    private fun viewHomes(admin: Player, name: String) {
        val target=resolveTarget(name)
        if(target==null) { host.messages.send(admin,"home-admin-unknown",name);return }
        val rows=homes(target)
        val who=labelOf(target,name)
        host.messages.send(admin,"home-admin-view-header",who,rows.size)
        if(rows.isEmpty()) { host.messages.send(admin,"home-admin-view-empty");return }
        for(home in rows) host.messages.send(admin,"home-admin-view-row",home.slot,home.name,
            home.worldName,home.x.toInt(),home.y.toInt(),home.z.toInt(),
            host.messages.text(admin,if(home.visible) "home-visible" else "home-hidden"))
        host.messages.send(admin,"home-admin-view-footer")
    }

    /**
     * `/meo home set <player> [slot|name]` — the admin form, free, and safe to run on someone offline.
     *
     * The destination is **the admin's own feet**, exactly as a player's own set is theirs: an admin
     * repairing somebody's base stands in it. That is the only shape that does not require the target
     * to be online and asked, which is what most repairs need.
     *
     * The name is the confirmation. No second prompt, because the target's name was typed out and the
     * edit costs nobody anything — the same rule as `/meo forget`, for the same reason.
     */
    private fun adminSet(admin: Player, name: String, value: String?) {
        if(!unlocked(admin)) return
        val target=resolveTarget(name)
        if(target==null) { host.messages.send(admin,"home-admin-unknown",name);return }
        val existing=value?.let { v -> homes(target).firstOrNull { it.slot.toString()==v || it.name.equals(v,true) } }
        val slot=existing?.slot ?: value?.toIntOrNull() ?: (1..capacity(target)).firstOrNull { n -> cache[target]?.containsKey(n)!=true }
        if(slot==null || (existing==null && slot !in 1..capacity(target))) { host.messages.send(admin,"home-limit",capacity(target));return }
        val label=existing?.name
            ?: if(value!=null && value.toIntOrNull()==null) value
            else host.messages.text(admin,"home-default-name",slot)
        if(!Home.validName(label)) { host.messages.send(admin,"home-invalid-name");return }
        val l=admin.location.clone()
        if(!safeState(admin) || !safe(l)) { host.messages.send(admin,"home-unsafe");return }
        val next=existing?.copy(world=l.world.uid,worldName=l.world.name,x=l.x,y=l.y,z=l.z,yaw=l.yaw,pitch=l.pitch)
            ?: Home(target,slot,l.world.uid,l.world.name,l.x,l.y,l.z,l.yaw,l.pitch,label,Home.COLORS.values.elementAt((slot-1)%16))
        host.messages.send(admin,"home-admin-set-doing",labelOf(target,name),next.slot,next.name)
        saveLocation(admin,next,existing,target)
    }

    /**
     * Splits the two shapes of `set`.
     *
     * Three arguments is unambiguously the admin form — a player and a slot — so it goes there even
     * when the name does not resolve, which is what lets the command say "the server does not know
     * them" instead of printing a usage line. Two arguments are genuinely ambiguous between "my slot
     * 5" and "that player's first free slot", so the name is resolved to decide, and an admin can
     * still set their own home with the plain form.
     *
     * The routing by **shape first** is the fix for a bug this started as: resolving first meant an
     * unknown name never reached the admin path, so the one message that explains why is unreachable.
     */
    private fun setFor(sender: Player, rest: List<String>, origin: Origin) {
        val first=rest.getOrNull(1)
        val administrative=admin(sender) && first!=null
        when {
            administrative && rest.size==3 -> adminSet(sender,first,rest[2])
            administrative && rest.size==2 && resolveTarget(first)!=null -> adminSet(sender,first,null)
            rest.size<=2 -> prepareSet(sender,first,origin)
            else -> usage(sender)
        }
    }
    private fun find(player: Player,value: String?): Home? {
        val home=homes(player.uniqueId).firstOrNull { it.slot.toString()==value || it.name.equals(value,true) }
        if(home==null) host.messages.send(player,"home-missing")
        return home
    }
    private fun unlocked(player: Player): Boolean {
        if(player.uniqueId in busy) { host.messages.send(player,"home-busy");return false }
        return available(player)
    }
    private fun current(player: Player,home: Home): Boolean {
        if(cache[player.uniqueId]?.get(home.slot)!=home) { host.messages.send(player,"home-stale");return false }
        return unlocked(player)
    }
    private fun prepareSet(player: Player,value: String?,origin: Origin) {
        if(!unlocked(player)) return
        val existing=value?.let { v -> homes(player.uniqueId).firstOrNull { it.slot.toString()==v || it.name.equals(v,true) } }
        val slot=existing?.slot ?: value?.toIntOrNull() ?: (1..capacity(player.uniqueId)).firstOrNull { n -> cache[player.uniqueId]?.containsKey(n)!=true }
        if(slot==null || (existing==null && slot !in 1..capacity(player.uniqueId))) { host.messages.send(player,"home-limit",capacity(player.uniqueId));return }
        val name=existing?.name ?: if(value!=null && value.toIntOrNull()==null) value else host.messages.text(player,"home-default-name",slot)
        if(!Home.validName(name)) { host.messages.send(player,"home-invalid-name");return }
        val l=player.location.clone()
        val next=existing?.copy(world=l.world.uid,worldName=l.world.name,x=l.x,y=l.y,z=l.z,yaw=l.yaw,pitch=l.pitch)
            ?: Home(player.uniqueId,slot,l.world.uid,l.world.name,l.x,l.y,l.z,l.yaw,l.pitch,name,Home.COLORS.values.elementAt((slot-1)%16))
        if(!safeState(player) || !safe(l)) { host.messages.send(player,"home-unsafe");return }
        confirmSet(player,next,existing,origin)
    }
    private fun confirmSet(player: Player,home: Home,old: Home?,origin: Origin) {
        host.reply(player,economy.balance(player.uniqueId),active::get) { balance ->
            val cost=settings.setAssessment().amount(balance)
            if(origin==Origin.MENU) { confirm(player,"home-confirm-set",home,cost.toPlainString()) { saveLocation(player,home,old) };return@reply }
            if(!awaiting(player,PendingAction.SET,home.slot)) {
                host.messages.send(player,"home-confirm-chat-set",home.name,host.money(cost),CONFIRM_WINDOW.toString())
                return@reply
            }
            saveLocation(player,home,old)
        }
    }
    /**
     * Commits a location change.
     *
     * [owner] is whose home this is; the caller is whoever triggered it. They are the same person for
     * a normal `set`, and an admin for an administrative one — and the split is the whole point:
     * the **caller** pays, holds the teleport lock and gets the error, while the **owner** owns the
     * row, carries the slot limit and gets the cache write. Getting that backwards would either
     * charge the wrong person or let the admin's own limit stand in for the target's.
     */
    private fun saveLocation(player: Player, home: Home, old: Home?, owner: UUID = player.uniqueId) {
        if(!unlocked(player)) return
        val administrative=owner!=player.uniqueId
        val assessment=if(administrative) settings.adminSetAssessment() else settings.setAssessment()
        if(cache[owner]?.get(home.slot)!=old) { host.messages.send(player,"home-stale");return }
        val destination=location(home)
        if(destination==null || !safeState(player) || !safe(destination)) { host.messages.send(player,"home-unsafe");return }
        val ticket=UUID.randomUUID()
        if(!host.claimTravel(player.uniqueId,ticket)) { host.messages.send(player,"home-busy");return }
        busy[player.uniqueId]=ticket
        val work=settlement.reserveTax(ticket,player.uniqueId,assessment).thenCompose { charge ->
            if(!active.get()) throw CancellationException("Home disabled")
            store.stage(home,old,ticket,capacity(owner)).thenCompose {
                settlement.settleTeleport(ticket,active.get()).thenCompose { paid ->
                    store.finish(ticket,paid.state==ChargeState.COMMITTED).thenApply {
                        check(paid.state==ChargeState.COMMITTED) { "Home edit cancelled" };charge
                    }
                }
            }
        }.toCompletableFuture()
        val operation=work.handle { charge,error ->
            if(error==null) CompletableFuture.completedFuture(charge) else {
                // A committed location survives cleanup failure; an uncommitted one rolls back.
                settlement.cancelTeleport(ticket).thenCompose { settlement.chargeStatus(ticket) }.thenCompose { status ->
                    store.finish(ticket,status?.state==ChargeState.COMMITTED)
                }.thenApply<TeleportCharge> { throw CompletionException(error) }.toCompletableFuture()
            }
        }.thenCompose { it }
        jobs+=operation
        operation.whenComplete { charge,error ->
            host.mainThread(active::get) {
                jobs.remove(operation);release(player.uniqueId,ticket)
                if(error==null) {
                    cache.getOrPut(owner) { mutableMapOf() }[home.slot]=home
                    syncMarkers(player)
                    if(!administrative) {
                        host.messages.send(player,"home-saved",home.slot,home.name,host.money(charge.fee))
                        if(player.isOnline) editMenu(player,home)
                    } else {
                        // The owner never asked for this, so they are told rather than left to
                        // discover that their base moved. Offline is the common case and there is
                        // nobody to tell, which is why the admin's own line says who it was for.
                        host.messages.send(player,"home-admin-set-done",labelOf(owner,""),home.slot,home.name)
                        Bukkit.getPlayer(owner)?.let { online ->
                            syncMarkers(online)
                            host.messages.send(online,"home-admin-set-told",player.name,home.slot,home.name)
                        }
                    }
                } else report(player,error)
            }
        }
    }
    private fun update(player: Player,old: Home,home: Home) {
        if(!current(player,old)) return
        val ticket=UUID.randomUUID();busy[player.uniqueId]=ticket
        val job=store.update(home,old);jobs+=job
        job.whenComplete { _,error -> host.mainThread(active::get) {
            jobs.remove(job);release(player.uniqueId,ticket)
            if(error!=null) report(player,error) else {
                cache[player.uniqueId]!![home.slot]=home;syncMarkers(player);if(!home.visible && selected[player.uniqueId]==home.slot) stopNavigation(player);textNavigation(player);host.messages.send(player,"home-updated")
                editMenu(player,home)
            }
        } }
    }
    private fun rename(player: Player,home: Home,name: String) {
        if(!Home.validName(name) || homes(player.uniqueId).any { it.slot!=home.slot && it.name.equals(name,true) }) {
            host.messages.send(player,"home-invalid-name");return
        }
        update(player,home,home.copy(name=name))
    }
    private fun confirmDelete(player: Player,home: Home,origin: Origin) {
        if(origin==Origin.MENU) { confirmDeleteGui(player,home);return }
        if(!awaiting(player,PendingAction.DELETE,home.slot)) {
            host.messages.send(player,"home-confirm-chat-delete",home.name,CONFIRM_WINDOW.toString())
            return
        }
        deleteHome(player,home)
    }
    private fun confirmDeleteGui(player: Player,home: Home) = confirm(player,"home-confirm-delete",home,"0") {
        deleteHome(player,home)
    }
    private fun deleteHome(player: Player,home: Home) {
        if(!current(player,home)) return
        val ticket=UUID.randomUUID();busy[player.uniqueId]=ticket
        val job=store.delete(home);jobs+=job
        job.whenComplete { _,error -> host.mainThread(active::get) {
            jobs.remove(job);release(player.uniqueId,ticket)
            if(error!=null) report(player,error) else {
                cache[player.uniqueId]?.remove(home.slot);if(selected[player.uniqueId]==home.slot) { stopNavigation(player) }
                syncMarkers(player);host.messages.send(player,"home-deleted",home.name);listMenu(player)
            }
        } }
    }
    private fun location(home: Home): Location? = Bukkit.getWorld(home.world)?.let { Location(it,home.x,home.y,home.z,home.yaw,home.pitch) }
    private fun safeState(player: Player) = player.isOnline && !player.isDead && player.gameMode!=GameMode.SPECTATOR && !player.isInsideVehicle && player.passengers.isEmpty()
    private fun safe(l: Location): Boolean {
        if(!l.world.isChunkLoaded(l.blockX shr 4,l.blockZ shr 4)) return false
        if(!l.world.worldBorder.isInside(l) || l.y<l.world.minHeight || l.y+1>=l.world.maxHeight) return false
        val feet=l.block; val head=feet.getRelative(0,1,0); val floor=feet.getRelative(0,-1,0)
        val danger=setOf(Material.LAVA,Material.FIRE,Material.SOUL_FIRE,Material.CAMPFIRE,Material.SOUL_CAMPFIRE,Material.MAGMA_BLOCK,Material.CACTUS,Material.SWEET_BERRY_BUSH,Material.POWDER_SNOW)
        return passable(feet) && passable(head) && floor.type.isSolid && listOf(feet.type,head.type,floor.type).none { it in danger }
    }
    private fun confirmTravel(player: Player,home: Home,origin: Origin,force: Boolean=false) {
        if(!current(player,home)) return
        if(force&&!forceAvailable(player)) { host.messages.send(player,if(settings.force) "no-permission" else "home-force-disabled");return }
        if(player.world.uid!=home.world && !settings.crossWorld) { host.messages.send(player,"home-cross-world-disabled");return }
        val fee=feeOf(player,home)
        if(origin==Origin.MENU) {
            confirm(player,if(force) "home-confirm-force" else "home-confirm-travel",home,fee.toPlainString()) { travel(player,home,force) }
            return
        }
        // Typed command: quote the price in chat and wait for the same command again. A menu click
        // keeps the inventory confirm it always had, because the player is already looking at it.
        if(!awaiting(player,PendingAction.TRAVEL,home.slot,force)) {
            host.messages.send(player,if(force) "home-force-chat" else "home-confirm-chat",home.name,host.money(fee),CONFIRM_WINDOW.toString())
            return
        }
        travel(player,home,force)
    }
    /** Distance-scaled travel fee, quoted identically whether it is shown in chat or in a GUI. */
    private fun feeOf(player: Player,home: Home): java.math.BigDecimal {
        val distance=if(player.world.uid==home.world) home.distance(player.location.x,player.location.z) else null
        return settings.travelAssessment(distance).amount(java.math.BigDecimal.ZERO)
    }
    /**
     * True when this is the second identical command inside the window, consuming the pending
     * request. A different target, a different action or an expired request all return false and
     * simply re-quote, so a stale window can never fire a different action than the one asked for.
     *
     * [force] is part of the identity of the request, not a detail of it: `/home 1` followed by
     * `/home 1 force` must re-quote rather than confirm, or the price and the safety promise the
     * player agreed to would both be the ones they never asked about.
     */
    private fun awaiting(player: Player,action: PendingAction,slot: Int,force: Boolean=false): Boolean {
        val id=player.uniqueId
        val now=System.currentTimeMillis()
        val held=pending[id]
        val matches=held!=null&&held.action==action&&held.slot==slot&&held.force==force&&now-held.at<=CONFIRM_WINDOW
        pending.remove(id)
        if(!matches) pending[id]=PendingRequest(action,slot,now,force)
        return matches
    }
    /**
     * Starts a trip home, with or without the landing-spot check.
     *
     * **Why the check exists at all, and why it strands homes.** [safe] is deliberately strict: the
     * exact saved block must have two passable spaces, a solid non-lethal floor, a live world and
     * room inside the border. That is right *the moment a home is set*, and `prepareSet` enforces
     * it there. It is wrong forever after, because the stored coordinates never move while the world
     * does: mine the block under your base, wall yourself in, move the world border, let a plugin
     * reshape the terrain, and the home silently stops being reachable. `/home` then answers
     * `home-unsafe` — which also covers "chunk unavailable", so the player cannot tell which it was
     * — on every single attempt, and the only repair is paying the setup tax again to relocate it.
     *
     * **What force skips, and what it deliberately does not:**
     *  - skips the block scan and the world border. That is the whole point: get the player back to
     *    the coordinates they paid for, standing in whatever they have since built around them.
     *  - **still loads the chunk** (`generate = true`, off-thread). A teleport into a column the
     *    server has never generated is a no-op rather than a trip, so leaving it to chance would
     *    make force — the path that has to work — quietly fail on the furthest homes.
     *  - **still refuses a coordinate outside the build limits.** That is the server refusing to
     *    place an entity at all, so force cannot talk it out of it and pretending otherwise would
     *    charge the player for a trip that never happens.
     *  - still charges the normal distance fee: force is a rescue, not a free teleport, and a free
     *    one would be a cheaper alternative to `/home` for every trip.
     */
    private fun travel(player: Player,home: Home,force: Boolean=false) {
        if(!current(player,home)) return
        val destination=location(home)
        if(destination==null) { host.messages.send(player,"home-world-missing",home.worldName);return }
        if(player.world.uid!=home.world && !settings.crossWorld) { host.messages.send(player,"home-cross-world-disabled");return }
        if(force && (destination.y<destination.world.minHeight || destination.y>=destination.world.maxHeight)) {
            host.messages.send(player,"home-force-height",home.worldName);return
        }
        if(!safeState(player)) { host.messages.send(player,"home-state");return }
        val ticket=UUID.randomUUID()
        if(!host.claimTravel(player.uniqueId,ticket)) { host.messages.send(player,"home-busy");return }
        busy[player.uniqueId]=ticket
        val flight=Flight(ticket,player,home,player.location.clone(),destination,force)
        flights[ticket]=flight
        // Take the column before loading it. A ticket taken after the load would already be too late
        // for a home outside view distance: the load releases its own ticket, and the chunk system is
        // free to unload the column again before the countdown is even over.
        try { holdChunk(destination);held[ticket]=destination } catch(error:Exception) {
            // A ticket this server refuses is not a reason to refuse the trip — the asynchronous move
            // in [arrive] can still load the column. Losing the ticket only costs the pre-move recheck.
            host.plugin.logger.log(Level.WARNING,"Could not hold the home chunk; travelling anyway",error)
        }
        // Load the already-generated home chunk off-thread, then revalidate it on the tick thread.
        // Recorded before the countdown, so `/back` returns the player to where they stood before
        // they started travelling rather than to wherever they happen to be when it fires.
        host.travelJournal.departed(player.uniqueId, player.location)
        val loading=try { loadChunk(destination) } catch(error:Exception) { CompletableFuture.failedFuture(error) }
        loading.whenComplete { loaded,failure -> host.mainThread(active::get) {
            if(flights[ticket]!==flight) return@mainThread
            if(failure!=null || loaded!=true || (!force && !safe(destination)) || !safeState(player) || changed(flight.origin,player.location)) { abort(flight,"home-unsafe");return@mainThread }
            val distance=if(player.world.uid==home.world) home.distance(player.location.x,player.location.z) else null
            val reservation=settlement.reserveTax(ticket,player.uniqueId,settings.travelAssessment(distance))
            reservation.whenComplete { charge,error -> host.mainThread(active::get) {
                if(flights[ticket]!==flight) return@mainThread
                if(error!=null) { flights.remove(ticket);release(player.uniqueId,ticket);report(player,error);return@mainThread }
                if(!safeState(player) || changed(flight.origin,player.location)) { abort(flight,"home-moved");return@mainThread }
                host.messages.send(player,"home-warmup",home.name,settings.countdownSeconds,host.money(charge.fee))
                presentation.applyTravelEffects(player,settings.countdownSeconds*20)
                if(settings.particles) presentation.animate(player,settings.countdownSeconds*20)
                flight.task=Bukkit.getScheduler().runTaskLater(host.plugin,Runnable { arrive(flight) },settings.countdownSeconds*20L)
            } }
        } }
    }
    /**
     * Ends the countdown and gets the player onto the destination chunk.
     *
     * **This is the distance bug.** The move used to be a synchronous
     * `isChunkLoaded(...) && player.teleport(...)`, which refuses to move anybody into a column that
     * is not loaded right now. `getChunkAtAsync` releases its ticket the moment the load finishes, so
     * a home far outside every player's view distance was unloaded again during the countdown and the
     * move simply did not happen — the fee was refunded and the player was told the operation failed.
     * Near a home it always worked, because the column is inside the player's view distance and stays
     * loaded by itself, which is exactly the reported symptom: force or no force, only the nearby
     * homes came back.
     *
     * `teleportAsync` is the same fix `/meo spawn` and `/tpa` already use: Paper loads the
     * destination as part of the move, off the tick, so whether the column happens to be resident at
     * that instant stops being the plugin's problem.
     *
     * The settlement stays on the main thread behind the flight's identity check, so a flight that
     * was aborted or a context that was closed cannot settle twice, and the outcome is captured
     * before the hop — a player who disconnects mid-flight still settles as *moved*, not as refunded.
     */
    private fun arrive(flight: Flight) {
        if(flights[flight.ticket]!==flight) return
        val player=flight.player
        if(!safeState(player) || changed(flight.origin,player.location)) { abort(flight,"home-moved");return }
        // The destination is held by a plugin ticket for the whole flight, so this re-check reads a
        // real column. A forced trip has no spot to re-check by definition: it goes where it was told
        // to go, and says so on arrival instead of pretending the landing was vetted.
        if(!flight.force && flight.destination.world.isChunkLoaded(flight.destination.blockX shr 4,flight.destination.blockZ shr 4) && !safe(flight.destination)) { abort(flight,"home-unsafe");return }
        presentation.stopWaiting(player.uniqueId)
        // Drop the guard before the move: PlayerTeleportEvent extends PlayerMoveEvent, so our own
        // teleport would otherwise look like the player walking away and cancel the flight.
        flight.moving=true
        val sync=move
        if(sync!=null) { settle(flight,safeMove(sync,player,flight.destination));return }
        try {
            player.teleportAsync(flight.destination,PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete { moved,error ->
                if(error!=null) host.plugin.logger.log(Level.WARNING,"Home teleport failed",error)
                settle(flight,moved==true && error==null)
            }
        } catch(error:Exception) { host.plugin.logger.log(Level.WARNING,"Home teleport failed",error);settle(flight,false) }
    }
    /** A refused or exploding move is a failed trip, never a crash on the tick thread. */
    private fun safeMove(move: (Player,Location)->Boolean,player: Player,destination: Location) =
        try { move(player,destination) } catch(error:Exception) {
            host.plugin.logger.log(Level.WARNING,"Home teleport failed",error);false
        }
    /**
     * Settles the reservation and reports. Main thread, once: the completion of an asynchronous
     * teleport arrives on a chunk worker, and [flights], [busy] and the marker sync are all
     * main-thread state.
     */
    private fun settle(flight: Flight,moved: Boolean) {
        val player=flight.player
        host.mainThread(active::get) {
            if(flights.remove(flight.ticket)!==flight) return@mainThread
            release(player.uniqueId,flight.ticket)
            val settled=settlement.settleTeleport(flight.ticket,moved)
            if(moved) {
                if(settings.particles) try { presentation.arrive(player) } catch(e:Exception) {host.plugin.logger.log(Level.WARNING,"Home arrival particles failed",e)}
                syncMarkers(player)
                host.reply(player,settled,active::get) { host.messages.send(player,if(flight.force) "home-force-arrived" else "home-arrived",flight.home.name,host.money(it.fee)) }
            } else {
                // The reservation is already being refunded; only the storage failure is worth a log,
                // because the journal replays it on the next start.
                settled.whenComplete { _,failure -> if(failure!=null) host.plugin.logger.log(Level.SEVERE,"Home refund deferred to recovery",failure) }
                host.messages.send(player,"home-failed")
            }
        }
    }

    private fun changed(from: Location,to: Location) = from.world!=to.world || from.distanceSquared(to)>1.0/256.0
    private fun abort(flight: Flight,key: String) {
        if(flights.remove(flight.ticket)==null) return
        flight.task?.cancel();presentation.stopWaiting(flight.player.uniqueId);release(flight.player.uniqueId,flight.ticket)
        // The departure was recorded before the flight, so a `/back` would carry the player back to
        // the place they never left — for a fee. A flight that was aborted did not move anybody, so
        // the record it left behind is wrong and is taken back.
        //
        // Only *our* record is taken back: another context may have recorded a departure of its own
        // in the meantime, and that one is none of this flight's business.
        try { host.travelJournal.forgetDeparture(flight.player.uniqueId,flight.origin) }
        catch(error:Exception) { host.plugin.logger.log(Level.WARNING,"Could not clear the travel journal entry",error) }
        settlement.cancelTeleport(flight.ticket).whenComplete { _,error -> if(error!=null) host.plugin.logger.log(Level.SEVERE,"Home cancellation deferred to restart",error) }
        if(flight.player.isOnline) host.messages.send(flight.player,key)
    }
    /**
     * One exit for a flight: the travel lock, the economy reservation and **the chunk ticket**.
     *
     * The ticket is keyed by travel ticket and removed with `remove`, so a flight that is aborted
     * from three places at once — the countdown task, a move event, `close()` — still gives the
     * column back exactly once. A leaked plugin ticket keeps a chunk resident forever, which is the
     * kind of leak that only shows up as the server getting slower weeks later.
     */
    private fun release(player: UUID,ticket: UUID) {
        if(busy[player]==ticket) busy.remove(player)
        held.remove(ticket)?.let { location ->
            try { dropChunk(location) } catch(error:Exception) { host.plugin.logger.log(Level.WARNING,"Could not release a home chunk ticket",error) }
        }
        host.releaseTravel(player,ticket)
    }
    private fun cancel(player: Player) {
        val flight=flights.values.firstOrNull { it.player.uniqueId==player.uniqueId }
        if(flight==null) host.messages.send(player,"home-no-travel") else if(!flight.moving) abort(flight,"home-cancelled")
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun movement(event: PlayerMoveEvent) {
        if(!event.hasChangedPosition()) return
        flights.values.filter { it.player.uniqueId==event.player.uniqueId && !it.moving }.toList().forEach { flight ->
            if(changed(flight.origin,event.to)) abort(flight,"home-moved")
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun teleport(event: PlayerTeleportEvent)=movement(event)
    @EventHandler fun quit(event: PlayerQuitEvent) {
        flights.values.filter { it.player.uniqueId==event.player.uniqueId }.toList().forEach { abort(it,"home-cancelled") }
        shown.remove(event.player.uniqueId);stopNavigation(event.player);prompts.remove(event.player.uniqueId)
        promptTasks.remove(event.player.uniqueId)?.cancel();presentation.stop(event.player.uniqueId)
        pending.remove(event.player.uniqueId)
    }
    @EventHandler fun join(event: PlayerJoinEvent) { defer { syncMarkers(event.player) } }
    @EventHandler fun world(event: PlayerChangedWorldEvent) { shown.remove(event.player.uniqueId);defer { syncMarkers(event.player) } }
    @EventHandler fun respawn(event: PlayerRespawnEvent) { shown.remove(event.player.uniqueId);defer { syncMarkers(event.player) } }
    private fun syncMarkers(player: Player) {
        if(!ready || !player.isOnline || !settings.locator || transportFailed) return
        val wanted=if(player.hasPermission("meowo.home")) homes(player.uniqueId).filter { it.visible && it.world==player.world.uid } else emptyList()
        val old=shown[player.uniqueId].orEmpty()
        try {
            old.filter { it !in wanted }.forEach { markers.untrack(player,it) }
            wanted.filter { it !in old }.forEach { markers.track(player,it) }
            shown[player.uniqueId]=wanted
        } catch(error:Exception) { transportFailed=true;host.plugin.logger.log(Level.WARNING,"Home locator transport unavailable; BossBar navigation remains",error) }
    }
    private fun textNavigation(player: Player) {
        val home=cache[player.uniqueId]?.get(selected[player.uniqueId])
        if(!settings.bossbar || !player.hasPermission("meowo.home") || home==null || !home.visible) {
            bars.remove(player.uniqueId)?.let { player.hideBossBar(it) };return
        }
        val l=player.location
        val sameWorld=home.world==player.world.uid
        val details=if(!sameWorld) text(player,"home-nav-other-world",home.worldName)
            else "${home.direction(l.x,l.z,l.yaw)}  ${home.distance(l.x,l.z).toInt()}m · ΔY ${(home.y-l.y).toInt()}"
        val angle=if(sameWorld) {
            val target=Math.toDegrees(kotlin.math.atan2(-(home.x-l.x),home.z-l.z))
            ((target-l.yaw+540)%360)-180
        } else 0.0
        val index=((angle+180)/360*24).toInt().coerceIn(0,24)
        val compass=(0..24).joinToString("") { if(it==index) "◆" else if(it==12) "┃" else "─" }
        val title=Component.text("⋈ ${home.name} · $details  [$compass]",TextColor.color(home.color))
        val bar=bars.getOrPut(player.uniqueId) {
            net.kyori.adventure.bossbar.BossBar.bossBar(title,1f,net.kyori.adventure.bossbar.BossBar.Color.WHITE,net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS).also { player.showBossBar(it) }
        }
        if(bar.name()!=title) bar.name(title)
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true)
    fun chat(event: AsyncChatEvent) {
        val home=prompts.remove(event.player.uniqueId) ?: return
        event.isCancelled=true
        val name=PlainTextComponentSerializer.plainText().serialize(event.message()).trim()
        host.mainThread(active::get) {
            promptTasks.remove(event.player.uniqueId)?.cancel()
            if(name.equals("cancel",true)) editMenu(event.player,home) else rename(event.player,home,name)
        }
    }
    private fun defer(action: ()->Unit) { Bukkit.getScheduler().runTask(host.plugin,Runnable { if(active.get()) action() }) }
    private fun report(player: Player,error: Throwable) {
        var cause=error;while(cause is CompletionException || cause is ExecutionException) cause=cause.cause ?: break
        if(cause is EconomyException) host.messages.send(player,"error-${cause.code.name.lowercase(Locale.ROOT)}")
        else { host.plugin.logger.log(Level.SEVERE,"Home operation failed",cause);host.messages.send(player,"home-failed") }
    }

    private fun item(material: String,title: String,lore: List<String> = emptyList(),color: Int? = null): ItemStack {
        return ItemStack(Material.matchMaterial(material) ?: Material.WHITE_BED).apply {
            itemMeta=itemMeta.apply { displayName(Component.text(title,if(color==null) TextColor.color(0xDDDDDD) else TextColor.color(color)))
                lore(lore.map { Component.text(it,TextColor.color(0xAAAAAA)) }) }
        }
    }
    private fun text(player: Player,key: String,vararg args: Any)=host.messages.text(player,key,*args)
    /**
     * Opens a home screen.
     *
     * The layout, icons, wording and commands are `ui.yml`'s. This supplies only what the module knows: which
     * home is being shown (`{slot}`), which way its visibility cell should flip it (`{show}`), and the cells
     * whose text is data — its name, coordinates, distance, price. The rail and the bedrock floor belong to the
     * renderer now, so no page can be written without an exit; the height is the config's too.
     */
    private fun open(
        player: Player,
        screen: String,
        overrides: Map<Int, UIAPI.UIElement> = emptyMap(),
        values: Map<String, String> = emptyMap(),
        title: String? = null,
        onBack: (() -> Unit)? = null,
    ) {
        if(!available(player) || !player.isOnline) return
        ui()?.open(
            player.uniqueId, screen,
            overrides = overrides,
            bindings = UIBindings(
                // Guarded: a click that arrives after the context stopped, or after a reload, must not run.
                onBack = { defer { onBack?.invoke() ?: listMenu(player) } },
                onMainMenu = { player.performCommand("menu") },
                onClose = { player.closeInventory() },
            ),
            title = title,
            values = values,
        )
    }

    private fun listMenu(player: Player) {
        val menu=Bukkit.getServicesManager().load(MenuAPI::class.java)
        if(menu==null) host.messages.send(player,"unavailable") else menu.openHomes(player.uniqueId)
    }

    /** The home itself, as the cell that shows it: the one piece of a detail page the config cannot write. */
    private fun homeElement(player: Player,home: Home): UIAPI.UIElement {
        val l=player.location
        val distance=if(player.world.uid==home.world) "${home.distance(l.x,l.z).toInt()}m · ΔY ${(home.y-l.y).toInt()}" else text(player,"home-nav-other-world",home.worldName)
        return UIAPI.UIElement(
            icon=home.item,
            name="#${home.slot} ${home.name}",
            lore=listOf("${home.worldName} · ${home.x.toInt()}, ${home.y.toInt()}, ${home.z.toInt()}",distance,
                text(player,if(home.visible) "home-visible" else "home-hidden"),text(player,"home-icon-detail",home.style),text(player,"home-list-help")),
            color=home.color,
        )
    }

    private fun editMenu(player: Player,home: Home) {
        if(cache[player.uniqueId]?.get(home.slot)!=home) { listMenu(player);return }
        val slot=home.slot
        open(
            player, SCREEN_EDIT,
            overrides=mapOf(
                4 to homeElement(player,home),
                // Offered only when it would work. Hidden, not shown-and-refused: a button that always ends
                // in a refusal teaches the player that the menu lies about what the server allows.
                // A hidden cell needs no content; the config's own name and icon are simply not drawn.
                9 to UIAPI.UIElement("NETHER_STAR","",hidden=!forceAvailable(player)),
                13 to UIAPI.UIElement("COMPASS",text(player,if(home.visible) "home-visible" else "home-hidden"),listOf(text(player,"home-free"))),
                14 to UIAPI.UIElement("PAPER",text(player,"home-bar-icon"),listOf(text(player,"home-icon-detail",home.style),text(player,"home-icon-limit"))),
                20 to UIAPI.UIElement("LODESTONE",text(player,"home-relocate"),
                    listOf(text(player,"home-set-tax-hint",settings.setRate.multiply(java.math.BigDecimal(100)).stripTrailingZeros().toPlainString(),host.money(settings.minimum),host.money(settings.maximum)))),
            ),
            values=mapOf("slot" to slot.toString(),"show" to if(home.visible) "off" else "on"),
            title=text(player,"home-edit-title",slot,home.name),
            onBack={ listMenu(player) },
        )
    }

    private fun colorMenu(player: Player,home: Home) {
        // Sixteen colours are data, so this is a list: the frame is the config's, one element per dye.
        val elements=Home.COLORS.entries.map { (name,rgb) ->
            UIAPI.UIElement(
                icon="${name.uppercase(Locale.ROOT)}_DYE",
                name=text(player,"home-color-$name"),
                lore=listOf("#%06X".format(rgb)),
                action="home gui color ${home.slot} $name",
                color=rgb,
            )
        }
        ui()?.openList(
            player.uniqueId, SCREEN_COLOR, elements,
            bindings=UIBindings(onBack={ defer { editMenu(player,home) } },onMainMenu={ player.performCommand("menu") },onClose={ player.closeInventory() }),
            values=mapOf("slot" to home.slot.toString()),
        )
    }

    private fun iconMenu(player: Player,home: Home,bar: Boolean) {
        val values=mapOf("slot" to home.slot.toString())
        val back: () -> Unit={ defer { editMenu(player,home) } }
        if(bar) {
            open(
                player, SCREEN_ICON_BAR,
                values=values,
                title=text(player,"home-bar-icon"),
                onBack=back,
            )
            return
        }
        // The menu icons are data too, so they page through the same list path as the colours.
        val elements=Home.ITEMS.map { material ->
            UIAPI.UIElement(
                icon=material,
                name=text(player,"home-item-${material.lowercase(Locale.ROOT)}"),
                action="home gui icon ${home.slot} ${material.lowercase(Locale.ROOT)}",
            )
        }
        ui()?.openList(
            player.uniqueId, SCREEN_ICON_MENU, elements,
            bindings=UIBindings(onBack=back,onMainMenu={ player.performCommand("menu") },onClose={ player.closeInventory() }),
            values=values,
        )
    }

    private fun confirm(player: Player,key: String,home: Home,amount: String,action: ()->Unit) {
        confirmed[player.uniqueId]=action
        open(
            player, SCREEN_CONFIRM,
            overrides=mapOf(
                4 to homeElement(player,home),
                11 to UIAPI.UIElement("LIME_CONCRETE",text(player,"home-confirm"),
                    listOf(text(player,key,home.name,host.money(java.math.BigDecimal(amount))),text(player,"home-confirm-tax"))),
                15 to UIAPI.UIElement("RED_CONCRETE",text(player,"home-back")),
            ),
            title=text(player,"home-confirm-title"),
            onBack={ listMenu(player) },
        )
    }

    /**
     * Runs what the confirmation page asked about, when its "yes" cell is clicked.
     *
     * The page is config like every other screen, so its cell runs a command rather than holding a closure —
     * the same shape the pomodoro confirmation uses. What the answer *means* stays here, because only this
     * context knows which home and which fee were on the page.
     */
    private fun confirmPending(player: Player) {
        val action=confirmed.remove(player.uniqueId)
            ?: run { host.messages.send(player,"home-confirm-expired");return }
        action()
    }

    /**
     * Asks for a new name in chat.
     *
     * A name cannot be a command argument when the player has not typed one yet, so this is the one cell
     * whose action is a prompt — `/home gui rename <slot>` with no name reaches it too, which is what keeps
     * the menu and the typed command on the same path.
     */
    private fun promptRename(player: Player,home: Home) {
        if(!current(player,home)) return
        player.closeInventory();prompts[player.uniqueId]=home;host.messages.send(player,"home-name-prompt")
        promptTasks.remove(player.uniqueId)?.cancel()
        promptTasks[player.uniqueId]=Bukkit.getScheduler().runTaskLater(host.plugin,Runnable {
            prompts.remove(player.uniqueId,home);promptTasks.remove(player.uniqueId)
        },1200L)
    }
    /**
     * Shuts the context down, and **every step of it is isolated**.
     *
     * The resources were closed in a plain `forEach { it.close() }`. One resource that threw ended
     * the loop: everything after it was never closed, and the `close()` that followed — the flight
     * aborts, the settlement cancellations, the chunk-ticket release — never ran at all. A home
     * context's resources are not a stack of dependencies where the ones below can be skipped: a
     * chunk ticket left pinned keeps a chunk **resident for the rest of the server's life**, and a
     * settlement that was never cancelled leaves money reserved.
     *
     * So each step is closed on its own, each failure is logged, and the shutdown continues. A
     * cleanup that cannot finish is a cleanup that must not become the reason the rest is skipped.
     */
    override fun close() {
        active.set(false);ready=false
        resources.asReversed().forEach { resource ->
            try { resource.close() }
            catch(e:Throwable) { host.plugin.logger.log(Level.WARNING,"Home resource cleanup failed",e) }
        }
        resources.clear()
        flights.values.toList().forEach { flight ->
            try { abort(flight,"home-cancelled") }
            catch(e:Throwable) { host.plugin.logger.log(Level.WARNING,"Home flight abort failed",e) }
        }
        // The refunds are part of the wait below: a settlement that is still in flight when the
        // server stops would leave a player charged for a journey nobody made.
        val refunds=mutableListOf<CompletableFuture<*>>()
        busy.forEach { (player,ticket) ->
            try { refunds+=settlement.cancelTeleport(ticket).toCompletableFuture() }
            catch(e:Throwable) { host.plugin.logger.log(Level.WARNING,"Home settlement cancel failed",e) }
            try { host.releaseTravel(player,ticket) }
            catch(e:Throwable) { host.plugin.logger.log(Level.WARNING,"Home travel release failed",e) }
        }
        try { presentation.close() } catch(e:Throwable) { host.plugin.logger.log(Level.WARNING,"Home presentation cleanup failed",e) }
        prompts.clear()
        promptTasks.values.forEach { task -> try { task.cancel() } catch(e:Throwable) { host.plugin.logger.log(Level.WARNING,"Home prompt task cleanup failed",e) } }
        promptTasks.clear()
        shown.forEach { (owner,homes) -> Bukkit.getPlayer(owner)?.let { p -> homes.forEach { home -> try { markers.untrack(p,home) } catch(e:Exception) {host.plugin.logger.log(Level.WARNING,"Home marker cleanup failed",e)} } } }
        bars.keys.toList().forEach { Bukkit.getPlayer(it)?.let { player -> try { stopNavigation(player) } catch(e:Throwable) { host.plugin.logger.log(Level.WARNING,"Home navigation cleanup failed",e) } } }
        bars.clear()
        // Pipelines never need the tick thread to finish their durable storage/settlement work — but
        // they do need to be given a **short** grace period, and ten seconds of it was a reload that
        // looked like a hang: the server was frozen, the console said nothing, and an operator had no
        // way to tell a slow shutdown from a stuck one.
        //
        // A second is enough for the local work (a SQLite write, a settlement refund), and cutting it
        // short is safe: the store's writes are transactional, so a half-finished one is either
        // committed or not, and the tickets that were still pending are recovered by the next start.
        val unfinished=(jobs+refunds).toList()
        if(unfinished.isNotEmpty()) try { CompletableFuture.allOf(*unfinished.toTypedArray()).get(CLOSE_GRACE_SECONDS,TimeUnit.SECONDS) }
        catch(e:Exception) {
            host.plugin.logger.log(Level.WARNING,"${unfinished.count { !it.isDone }} home operation(s) did not finish; they will recover on restart",e)
        }
        if(::store.isInitialized) try { store.close() } catch(e:Throwable) { host.plugin.logger.log(Level.WARNING,"Home store close failed",e) }
        cache.clear();busy.clear();jobs.clear();shown.clear();selected.clear();pending.clear()
        // Any ticket a flight did not give back (an abort that raced a reload) is released here rather
        // than left pinning a chunk resident for the rest of the server's life.
        held.keys.toList().forEach { ticket ->
            held.remove(ticket)?.let { location ->
                try { dropChunk(location) } catch(e:Exception) { host.plugin.logger.log(Level.WARNING,"Could not release a home chunk ticket",e) }
            }
        }
    }
    /** Where an action came from, which decides whether the answer is chat or an inventory. */
    private enum class Origin { CHAT, MENU }
    private enum class PendingAction { TRAVEL, SET, DELETE }
    private data class PendingRequest(val action: PendingAction,val slot: Int,val at: Long,val force: Boolean=false)

    private companion object {
        /** The screen ids in `ui.yml`. */
        const val SCREEN_EDIT = "home-edit"
        const val SCREEN_COLOR = "home-color"
        const val SCREEN_ICON_BAR = "home-icon-bar"
        const val SCREEN_ICON_MENU = "home-icon-menu"
        const val SCREEN_CONFIRM = "home-confirm"

        /** How long a quoted price stays confirmable. Long enough to retype, short enough to forget. */
        const val CONFIRM_WINDOW=10_000L

        /**
         * How long the tick thread waits for the home pipeline at close.
         *
         * One second: enough for a SQLite write and a settlement refund, short enough that a reload
         * does not look like a hang. See `close`.
         */
        const val CLOSE_GRACE_SECONDS=1L
    }
}
