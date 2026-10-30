package com.meowo.webapi.infrastructure

import com.meowo.webapi.domain.AdvancementEntry
import com.meowo.webapi.domain.PlayerSnapshot
import com.meowo.webapi.domain.ServerSnapshot
import com.meowo.webapi.domain.SkinReference
import com.meowo.webapi.domain.StatisticEntry
import com.meowo.webapi.domain.WorldSnapshot
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.advancement.Advancement
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player

/**
 * Builds the snapshot from the live server.
 *
 * **Main thread only.** Everything here reads the Bukkit API, which is not thread safe. The
 * context runs this on the main thread and publishes the result; the HTTP threads only ever read
 * the published value.
 *
 * The shape is the one the old `yawebapi` module produced, because the web front end still reads
 * it: statistics are keyed by the short name (`BOAT_ONE_CM`, `COAL_ORE`, `ZOMBIE`) and
 * advancements by `story/root`.
 *
 * ### What this costs
 *
 * Everything here happens on the main thread, so the price is paid by the server and not by the
 * API. Two things were made cheaper:
 *
 * - the materials and entity types are worked out **once**, not once per statistic and once per
 *   player. `Material.isItem()` goes through the server's registry, and the old code asked it
 *   again for every item statistic of every player — hundreds of thousands of registry lookups per
 *   snapshot, to learn the same thing over and over;
 * - the server's advancements are read **once per snapshot**, not once per player.
 *
 * What is left is the part the Bukkit API makes unavoidable: one `getStatistic` call per
 * (statistic, material) pair, and one `getAdvancementProgress` call per (player, advancement)
 * pair. There is no bulk API for either. `statistics-mode: basic` skips the first set entirely.
 */
class BukkitSnapshotBuilder(
    private val statisticsFull: Boolean,
    private val maxPlayers: Int,
    /** Where a reading that could not be taken is reported. The same shape as [WebApiServer]. */
    private val log: (String) -> Unit = {},
) {

    /**
     * The materials and entity types the item, block and entity statistics apply to.
     *
     * They never change while the server runs, and they are the same for every player, so they are
     * worked out once for the whole life of the builder. `lazy` because a builder in `basic` mode
     * never needs them: asking the registry which materials are items is not free, and a server
     * that only wants the untyped statistics should not pay for it.
     *
     * A registry that cannot answer costs the item and block statistics and nothing else. That is
     * not hypothetical: `Material.isItem()` converts the material into the server's item type, and
     * a server that does not implement that conversion (MockBukkit is one) answers nothing at all.
     * The failure is reported, the group is empty, and the snapshot is built without it.
     */
    private val itemMaterials: List<Material> by lazy { materials("item") { it.isItem } }
    private val blockMaterials: List<Material> by lazy { materials("block") { it.isBlock } }
    private val entityTypes: List<EntityType> by lazy {
        try {
            EntityType.entries.filter { it.name != "UNKNOWN" }
        } catch (e: Throwable) {
            log("could not read the entity types: ${e.message}")
            emptyList()
        }
    }

    private fun materials(kind: String, isIt: (Material) -> Boolean): List<Material> = try {
        Material.entries.filter(isIt)
    } catch (e: Throwable) {
        log("could not read the $kind materials: ${e.message}")
        emptyList()
    }

    fun build(): WorldSnapshot {
        val online = Bukkit.getOnlinePlayers()
        val server = ServerSnapshot(
            onlinePlayers = online.size,
            maxPlayers = Bukkit.getMaxPlayers(),
            tps = readTps(),
            heartbeatStatus = true,
            updateTime = System.currentTimeMillis(),
        )
        // The server's advancements, read once. The old code walked the whole list again for every
        // player, so a hundred players meant a hundred walks of six hundred advancements.
        val advancements = serverAdvancements()
        val players = online.take(maxPlayers).map { buildPlayer(it, advancements) }
        return WorldSnapshot(
            server = server,
            players = players,
            generatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * The server's advancement tree.
     *
     * A server that cannot answer this is a server with no advancement tree, not a server with no
     * snapshot. The reading is separate from the players, the statistics and the TPS, so a failure
     * here is reported and the rest of the snapshot is built as usual — otherwise one missing API
     * would take the whole API down and keep it down.
     */
    private fun serverAdvancements(): List<Advancement> = try {
        Bukkit.advancementIterator().asSequence().toList()
    } catch (e: Throwable) {
        log("could not read the advancements: ${e.message}")
        emptyList()
    }

    private fun buildPlayer(player: Player, advancements: List<Advancement>): PlayerSnapshot = PlayerSnapshot(
        uuid = player.uniqueId.toString(),
        name = player.name,
        firstLogin = player.firstPlayed,
        lastLogin = player.lastPlayed,
        totalPlaytimeSeconds = totalPlaytimeSeconds(player),
        statistics = readStatistics(player),
        advancements = readAdvancements(player, advancements),
        skin = readSkin(player),
    )

    /** `PLAY_ONE_MINUTE` counts ticks, and twenty of them are a second. */
    private fun totalPlaytimeSeconds(player: Player): Long =
        player.getStatistic(Statistic.PLAY_ONE_MINUTE).toLong() / 20L

    private fun readStatistics(player: Player): Map<String, StatisticEntry> {
        val out = LinkedHashMap<String, StatisticEntry>()
        // The order of `Statistic.entries` decides which statistic owns a key that two of them could
        // own, so the walk stays in that order. Only the *material* lists moved: they are worked
        // out once instead of once per statistic and per player.
        for (statistic in Statistic.entries) {
            when (statistic.type) {
                Statistic.Type.UNTYPED -> {
                    val value = player.getStatistic(statistic)
                    if (value != 0) {
                        out[statistic.name] = StatisticEntry(statistic.type.name, statistic.name, value.toLong())
                    }
                }
                Statistic.Type.ITEM -> if (statisticsFull) {
                    for (material in itemMaterials) {
                        val value = player.getStatistic(statistic, material)
                        if (value != 0) put(out, statistic, material.name, value)
                    }
                }
                Statistic.Type.BLOCK -> if (statisticsFull) {
                    for (material in blockMaterials) {
                        val value = player.getStatistic(statistic, material)
                        if (value != 0) put(out, statistic, material.name, value)
                    }
                }
                Statistic.Type.ENTITY -> if (statisticsFull) {
                    for (type in entityTypes) {
                        val value = player.getStatistic(statistic, type)
                        if (value != 0) put(out, statistic, type.name, value)
                    }
                }
            }
        }
        return out
    }

    /**
     * Several statistics share one material or entity, and the front end keys them by that short
     * name, so only one of them can own the key. The first one that reports a value wins, and the
     * order of [Statistic.entries] decides which that is.
     */
    private fun put(out: MutableMap<String, StatisticEntry>, statistic: Statistic, name: String, value: Int) {
        if (out.containsKey(name)) return
        out[name] = StatisticEntry(statistic.type.name, name, value.toLong())
    }

    /**
     * The advancements, all of them.
     *
     * The shape is the one the old `yawebapi` module produced: every advancement of the game, with
     * what the player has and has not done on it. An advancement the player never touched is in
     * there too — it says the same thing about every player, but that is what the front end reads.
     */
    private fun readAdvancements(player: Player, advancements: List<Advancement>): List<AdvancementEntry> = try {
        advancements.map { advancement ->
            val progress = player.getAdvancementProgress(advancement)
            AdvancementEntry(
                key = advancement.key.key,
                done = progress.isDone,
                completed = progress.awardedCriteria.toList(),
                remaining = progress.remainingCriteria.toList(),
            )
        }
    } catch (e: Throwable) {
        // One player losing their advancements is a smaller loss than the whole snapshot failing.
        log("could not read the advancements of ${player.name}: ${e.message}")
        emptyList()
    }

    /**
     * The skin is a **URL**, not an image: downloading it would be network I/O on the main
     * thread, and the consumer fetches it.
     */
    private fun readSkin(player: Player): SkinReference {
        val textures = try {
            player.playerProfile?.textures
        } catch (e: Throwable) {
            null
        }
        val model = try {
            textures?.skinModel?.name?.lowercase() ?: "default"
        } catch (e: Throwable) {
            "default"
        }
        return SkinReference(
            type = model,
            url = try { textures?.skin?.toString() ?: "" } catch (e: Throwable) { "" },
            capeUrl = try { textures?.cape?.toString() ?: "" } catch (e: Throwable) { "" },
        )
    }

    /**
     * Paper keeps the TPS on the server implementation, and there is no API for it.
     *
     * When the reading fails — an older server, a different implementation, a security manager —
     * there is **no** TPS to report, so nothing is reported. The old code answered 20.0, which is
     * the ideal value: a server that could not be measured reported a perfect score, and a perfect
     * score is the one reading nobody ever checks.
     */
    private fun readTps(): Double? = try {
        val server = Bukkit.getServer()
        val method = server.javaClass.getDeclaredMethod("getTPS")
        method.isAccessible = true
        (method.invoke(server) as? DoubleArray)?.firstOrNull()
    } catch (e: Throwable) {
        null
    }
}
