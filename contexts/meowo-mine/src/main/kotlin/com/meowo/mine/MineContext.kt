package com.meowo.mine

import com.meowo.mine.domain.Discovery
import com.meowo.mine.domain.DiscoveryLedger
import com.meowo.mine.domain.RareKind
import com.meowo.mine.infrastructure.MineLedger
import com.meowo.platform.*
import com.meowo.shared.lifecycle.Context
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/**
 * Reports every rare-material find, and remembers which materials this server has produced.
 *
 * `/meowo mine` lists the register. Broadcasts show the amount from **this** event, never a
 * running total: vanilla's own statistics screen already owns per-player totals, and a second
 * number in chat would only compete with it.
 *
 * The register is keyed by material, so it holds at most five rows per world and cannot grow.
 * Deepslate ore is a separate kind from surface ore — see [RareKind].
 *
 * The database is a **cache, not a query path**: it is read once at start and written only when
 * something is recorded. See [MineLedger]. Because the read is off the tick thread, [hydrated]
 * gates reporting until it finishes.
 */
class MineContext(
    private val host: PlatformHost,
    private val settings: MineSettings = MineSettings(),
    /** Injected so tests can use a temporary file; production passes the plugin data folder. */
    private val store: MineLedger? = null,
) : Context, org.bukkit.event.Listener {
    override val id = "mine"
    private val active = AtomicBoolean(false)
    private val resources = mutableListOf<AutoCloseable>()

    /** One register per world, so a fresh world starts with a clean slate. */
    private val ledgers = ConcurrentHashMap<String, DiscoveryLedger>()

    /**
     * Ore positions this player has already been credited for, oldest first.
     *
     * A vein is counted when the player breaks into it, but the blocks stay in the world. Without
     * this the same vein is reported again for every block of it, so the eight diamonds in one vein
     * would be announced eight times. Recording the positions stops that without touching drops or
     * breaking anything.
     */
    private val counted = mutableMapOf<UUID, LinkedHashSet<org.bukkit.util.BlockVector>>()

    /** Reporting is suppressed until the stored rows have been read. */
    private val hydrated = AtomicBoolean(false)
    @Volatile private var ledger: MineLedger? = null

    override fun start() {
        active.set(true)
        Bukkit.getPluginManager().registerEvents(this, host.plugin)
        resources += AutoCloseable { HandlerList.unregisterAll(this) }
        resources += host.commands.register("mine", "mine", object : Route {
            override val permission = "meowo.mine"
            override fun execute(sender: CommandSender, args: List<String>) = report(sender, args)
            override fun complete(sender: CommandSender, args: List<String>): List<String> = emptyList()
        })
        openStore()
    }

    /** Opens the database and reads every world's register back into memory, off the tick thread. */
    private fun openStore() {
        val opened = try {
            store ?: MineLedger(host.plugin.dataFolder.toPath().resolve("mine").resolve("registry.db"))
        } catch (e: Exception) {
            // The register is a convenience; a broken file must not stop the plugin from loading.
            host.plugin.logger.log(Level.SEVERE, "Mine register unavailable, running in memory", e)
            null
        }
        ledger = opened ?: return
        host.plugin.server.scheduler.runTaskAsynchronously(host.plugin, Runnable {
            Bukkit.getWorlds().forEach { world -> ledgers[world.name] = opened.load(world.name) }
            hydrated.set(true)
        })
    }

    /** Lists what this server has produced. */
    private fun report(sender: CommandSender, args: List<String>) {
        val world = (sender as? Player)?.world?.name
            ?: Bukkit.getWorlds().firstOrNull()?.name
            ?: run { host.messages.send(sender, "mine-none-world"); return }
        val ledger = ledgers[world] ?: DiscoveryLedger()
        val found = RareKind.entries.filter { ledger.isDiscovered(it) }
        if (found.isEmpty()) { host.messages.send(sender, "mine-none", world); return }
        host.messages.send(sender, "mine-header", world)
        for (kind in found) {
            host.messages.send(
                sender, "mine-entry",
                label(sender, kind), ledger.firstFinder(kind).orEmpty(), ledger.totalOf(kind).toString(),
            )
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun breakBlock(event: BlockBreakEvent) {
        if (!active.get()) return
        // Still reading the stored rows: reporting now would understate the totals.
        if (!hydrated.get()) return
        val kind = kindOf(event.block.type) ?: return
        // The broken block has already left the world, so the vein is the ring of same-ore blocks
        // still around the hole. One report per vein beats eight reports of "x 1".
        val owner = event.player.uniqueId
        val mined = org.bukkit.util.BlockVector(event.block.x, event.block.y, event.block.z)
        // The block itself was credited when its vein was first reported, so breaking it later is
        // not news. Without this the rest of a vein would still be announced one block at a time.
        if (counted[owner]?.contains(mined) == true) return
        val fresh = newVein(event.block, owner)
        // The mined block counts too, and maxVeinSize bounds the reported total.
        val amount = minOf(fresh.size + 1, maxOf(settings.maxVeinSize, 1))
        markCounted(owner, fresh)
        val discovery = register(kind, amount, event.block.world.name, event.player) ?: return
        if (settings.announce) broadcast(event.block, discovery, event.player)
    }

    /**
     * Counts the ore still connected to [broken], so a vein reports as one find.
     *
     * Breadth-first from the six direct neighbours, bounded twice: by [MineSettings.maxVeinSize]
     * and by a loaded-chunk guard, because touching an unloaded chunk would generate it and stall
     * the tick. The block the player mined is added by the caller, so the count includes it.
     */
    /**
     * Remembers the positions just credited, evicting the oldest once [COUNTED_LIMIT] is reached.
     * The bound matters: without it a player who mines every diamond vein on the server would grow
     * this set without limit, and the oldest entries are the least likely to be reached again.
     */
    private fun markCounted(owner: UUID, fresh: List<org.bukkit.util.BlockVector>) {
        if (fresh.isEmpty()) return
        val set = counted.getOrPut(owner) { LinkedHashSet() }
        set.addAll(fresh)
        while (set.size > COUNTED_LIMIT) set.remove(set.first())
    }

    /**
     * The ore positions connected to [broken] that this player has **not** already been credited
     * for, capped and never leaving a loaded chunk.
     *
     * The mined block itself is excluded: the ring is connected to it, so without that skip the
     * search walks straight back into the block it started from and counts it twice. The caller
     * adds it once.
     *
     * @return newly counted positions, in visit order.
     */
    private fun newVein(broken: org.bukkit.block.Block, owner: UUID): List<org.bukkit.util.BlockVector> =
        scanVein(
            world = broken.world,
            origin = org.bukkit.util.BlockVector(broken.x, broken.y, broken.z),
            type = broken.type,
            limit = settings.maxVeinSize,
            already = counted.getOrPut(owner) { LinkedHashSet() },
        )

    /**
     * A placed ore counts as discovered too — a diamond block that existed before anyone mined one
     * means the material is not a first find any more. It is **not** broadcast: a player who
     * places an ore did not discover it, and crediting them for it would be a lie.
     */
    @EventHandler(ignoreCancelled = true)
    fun placeBlock(event: BlockPlaceEvent) {
        if (!active.get() || !hydrated.get()) return
        val kind = kindOf(event.block.type) ?: return
        register(kind, 1, event.block.world.name, event.player)
    }

    /** Updates the in-memory register and returns the find, or null when the amount is unusable. */
    private fun register(kind: RareKind, amount: Int, world: String, player: Player): Discovery? {
        val key = player.uniqueId.toString()
        val current = ledgers[world] ?: DiscoveryLedger()
        val discovery = current.record(kind, amount, key) ?: return null
        ledgers[world] = discovery.next
        persist(world, key, discovery, player.name)
        return discovery
    }

    private fun broadcast(block: org.bukkit.block.Block, discovery: Discovery, player: Player) {
        val amount = discovery.amount.toString()
        val label = label(player, discovery.kind)
        val key = if (discovery.isFirst && settings.highlightFirst) "mine-first" else "mine-announce"
        Bukkit.getOnlinePlayers().forEach { viewer ->
            host.messages.send(viewer, key, player.name, label, amount)
        }
        if (settings.sound) {
            val at = Location(block.world, block.x + 0.5, block.y + 0.5, block.z + 0.5)
            block.world.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f)
        }
    }

    /**
     * Writes one find. Placed blocks are recorded too, so this is once per rare block touched —
     * still far below the rate of an ordinary block break, and it never runs on the tick thread.
     */
    private fun persist(world: String, playerId: String, discovery: Discovery, name: String) {
        val store = ledger ?: return
        val kind = discovery.kind
        val amount = discovery.amount
        val isFirst = discovery.isFirst
        host.plugin.server.scheduler.runTaskAsynchronously(host.plugin, Runnable {
            runCatching { store.record(world, kind, amount, name, System.currentTimeMillis()) }
                .onFailure { host.plugin.logger.log(Level.WARNING, "Mine register write failed", it) }
        })
    }

    private fun label(viewer: CommandSender, kind: RareKind): String = host.messages.text(viewer, kind.labelKey)

    /** A world created after start has no entry yet; give it an empty register on first use. */
    @EventHandler fun join(event: PlayerJoinEvent) {
        ledgers.putIfAbsent(event.player.world.name, DiscoveryLedger())
    }

    @EventHandler fun quit(event: PlayerQuitEvent) {
        // A departing player takes no credited positions with them; the world keeps its ore.
        counted.remove(event.player.uniqueId)
    }

    override fun close() {
        active.set(false)
        ledgers.clear()
        counted.clear()
        runCatching { ledger?.close() }
        ledger = null
        resources.asReversed().forEach { it.close() }; resources.clear()
    }

    internal companion object {
        /** Most credited positions kept per player; older ones are evicted first. */
        const val COUNTED_LIMIT = 8192

        /** The six face neighbours, which is how ore veins actually connect. */
        private val NEIGHBOURS = arrayOf(
            intArrayOf(1,0,0), intArrayOf(-1,0,0), intArrayOf(0,1,0),
            intArrayOf(0,-1,0), intArrayOf(0,0,1), intArrayOf(0,0,-1),
        )

        /**
         * The ore positions connected to [origin] that have **not** already been credited, capped
         * at [limit] and never leaving a loaded chunk.
         *
         * Breadth-first from the six direct neighbours. The origin is excluded: the ring is
         * connected to it, so without that skip the search walks straight back into the block it
         * started from and counts it twice. The caller adds it once.
         *
         * It lives here, and is `internal`, because a test has to be able to drive **this** search.
         * The test used to carry its own copy of it — a second implementation with its own
         * hard-coded cap — so it could not fail when this one changed: the cap could have been 64
         * in the plugin and 32 in the test and the suite would still have been green.
         *
         * @return newly counted positions, in visit order.
         */
        internal fun scanVein(
            world: org.bukkit.World,
            origin: org.bukkit.util.BlockVector,
            type: Material,
            limit: Int,
            already: Set<org.bukkit.util.BlockVector>,
        ): List<org.bukkit.util.BlockVector> {
            if (limit <= 0) return emptyList()
            val visited = HashSet<org.bukkit.util.BlockVector>()
            val fresh = ArrayList<org.bukkit.util.BlockVector>()
            val queue = ArrayDeque<org.bukkit.util.BlockVector>()
            for (v in NEIGHBOURS) queue.addLast(org.bukkit.util.BlockVector(origin.x + v[0], origin.y + v[1], origin.z + v[2]))
            while (queue.isNotEmpty() && fresh.size < limit) {
                val at = queue.removeFirst()
                if (at == origin) continue
                if (!visited.add(at)) continue
                if (at in already) continue
                if (!world.isChunkLoaded(at.x.toInt() shr 4, at.z.toInt() shr 4)) continue
                if (world.getBlockAt(at.x.toInt(), at.y.toInt(), at.z.toInt()).type != type) continue
                fresh += at
                for (v in NEIGHBOURS) queue.addLast(org.bukkit.util.BlockVector(at.x + v[0], at.y + v[1], at.z + v[2]))
            }
            return fresh
        }

        /**
         * Maps a block material onto the rare kind it represents, or null when it is not rare.
         *
         * Written against explicit enum constants rather than a name pattern, because the name
         * approach mis-fires on `LEGACY_DIAMOND_ORE` and would break if Paper renamed a constant.
         * There is deliberately **no** `NETHERITE_ORE`: that block does not exist in 26.2.
         *
         * Deepslate maps to its own kind, so a deepslate grind is never folded into the surface
         * numbers.
         */
        internal fun kindOf(material: Material): RareKind? = when (material) {
            Material.DIAMOND_ORE -> RareKind.DIAMOND
            Material.DEEPSLATE_DIAMOND_ORE -> RareKind.DEEPSLATE_DIAMOND
            Material.EMERALD_ORE -> RareKind.EMERALD
            Material.DEEPSLATE_EMERALD_ORE -> RareKind.DEEPSLATE_EMERALD
            Material.ANCIENT_DEBRIS -> RareKind.NETHERITE
            else -> null
        }
    }
}
