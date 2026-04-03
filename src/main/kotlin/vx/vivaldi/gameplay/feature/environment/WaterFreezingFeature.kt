package vx.vivaldi.gameplay.feature.environment

import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.Levelled
import org.bukkit.event.Listener
import org.bukkit.scheduler.BukkitRunnable
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.config.lib.annotations.Comment
import vx.vivaldi.season.Season

object WaterFreezingFeature : Listener {

    class WaterFreezingConfig {
        var enabled: Boolean = true

        @Comment("How often to run the freezing calculation cycle (in ticks). 20 = 1 second")
        var intervalTicks: Long = 20L

        @Comment("How many chunks to process per cycle")
        var chunksPerCycle: Int = 60

        @Comment("Maximum number of water blocks to freeze per chunk per cycle. Lower = slower creeping ice.")
        var maxFreezesPerChunk: Int = 5
    }

    private val cfg get() = plugin.gameplayManager.config.environment.waterFreezing
    private val excludedBiomes get() = plugin.gameplayManager.config.environment.excludedBiomes
    private var task: BukkitRunnable? = null

    private data class IceCandidate(val world: World, val x: Int, val y: Int, val z: Int, val contacts: Int)
    private data class IcePlacement(val world: World, val x: Int, val y: Int, val z: Int)

    /**
     * Chunk snapshot cache for safe cross-chunk reading in an async thread.
     */
    private class WorldSnapshotCache(val world: World) {
        val snapshots = mutableMapOf<Long, ChunkSnapshot>()

        // Unique key for 2D coordinates
        private fun getKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)

        // Take a snapshot of the chunk itself and its 4 neighbors
        fun addChunkAndNeighbors(cx: Int, cz: Int) {
            val coords = intArrayOf(
                cx, cz,
                cx - 1, cz, // West
                cx + 1, cz, // East
                cx, cz - 1, // North
                cx, cz + 1  // South
            )
            for (i in 0 until 5) {
                val nx = coords[i * 2]
                val nz = coords[i * 2 + 1]
                val key = getKey(nx, nz)
                if (!snapshots.containsKey(key)) {
                    // Take a snapshot only if the neighboring chunk is loaded (to avoid loading chunks and killing TPS)
                    if (world.isChunkLoaded(nx, nz)) {
                        snapshots[key] = world.getChunkAt(nx, nz).getChunkSnapshot(true, false, true)
                    }
                }
            }
        }

        fun getSnapshot(cx: Int, cz: Int): ChunkSnapshot? = snapshots[getKey(cx, cz)]

        // Smart neighbor check that "jumps" to the neighboring chunk's snapshot if we are on the border
        fun isSolidCrossChunk(cx: Int, cz: Int, lx: Int, y: Int, lz: Int): Boolean {
            var tx = cx
            var tz = cz
            var tlx = lx
            var tlz = lz

            if (tlx < 0) { tx--; tlx = 15 }
            else if (tlx > 15) { tx++; tlx = 0 }

            if (tlz < 0) { tz--; tlz = 15 }
            else if (tlz > 15) { tz++; tlz = 0 }

            val snapshot = snapshots[getKey(tx, tz)] ?: return false
            return snapshot.getBlockType(tlx, y, tlz).isSolid
        }
    }

    init {
        start()
    }

    fun start() {
        if (!cfg.enabled) return
        if (task != null) return

        task = object : BukkitRunnable() {
            override fun run() {
                val currentSeason = plugin.seasonManager.currentSeason
                if (currentSeason != Season.WINTER) return

                val worldCaches = mutableMapOf<World, WorldSnapshotCache>()
                val selectedChunks = mutableListOf<Pair<World, Pair<Int, Int>>>()

                for (world in Bukkit.getWorlds()) {
                    if (world.name !in plugin.gameplayManager.allowedWorlds) continue
                    val loadedChunks = world.loadedChunks
                    if (loadedChunks.isEmpty()) continue

                    val limit = minOf(cfg.chunksPerCycle, loadedChunks.size)
                    val cache = WorldSnapshotCache(world)
                    worldCaches[world] = cache

                    // Synchronously prepare chunk snapshots
                    for (i in 0 until limit) {
                        val randomChunk = loadedChunks.random()
                        val cx = randomChunk.x
                        val cz = randomChunk.z

                        cache.addChunkAndNeighbors(cx, cz)
                        selectedChunks.add(world to (cx to cz))
                    }
                }

                if (selectedChunks.isEmpty()) return

                // Asynchronously calculate ice placements
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    val finalPlacements = mutableListOf<IcePlacement>()

                    for ((world, coords) in selectedChunks) {
                        val cx = coords.first
                        val cz = coords.second
                        val cache = worldCaches[world] ?: continue
                        val snapshot = cache.getSnapshot(cx, cz) ?: continue

                        val freezableCandidates = mutableListOf<IceCandidate>()

                        for (lx in 0..15) {
                            for (lz in 0..15) {
                                val highestY = snapshot.getHighestBlockYAt(lx, lz)
                                var targetY = -1

                                var solidThickness = 0 // Counter for the thickness of the obstacle above

                                for (y in highestY downTo world.minHeight) {
                                    val type = snapshot.getBlockType(lx, y, lz)

                                    // Identify water surface OR water plants that reach the surface
                                    if (type == Material.WATER || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS || type == Material.KELP || type == Material.KELP_PLANT) {
                                        targetY = y
                                        break
                                    }

                                    // If we hit already frozen ice, stop searching.
                                    // Otherwise, the plugin will freeze water UNDER the ice, freezing lakes to the bottom.
                                    if (type == Material.ICE) {
                                        break
                                    }

                                    if (type.isSolid && type != Material.SNOW) {
                                        solidThickness++

                                        // If the "roof" is thicker than 10 blocks in a row, we are underground/inside a mountain.
                                        // Break the search so we don't freeze underground lakes in deep caves.
                                        if (solidThickness > 10) {
                                            break
                                        }
                                    } else {
                                        // Air, grass, flowers, vines, etc., reset the thickness counter,
                                        // because under a bridge (or forest) there is usually an air gap!
                                        solidThickness = 0
                                    }
                                }

                                if (targetY == -1) continue

                                val targetType = snapshot.getBlockType(lx, targetY, lz)
                                var isFreezableWater = false

                                // Strict check: Make sure it's a full block of water (level 0) or a waterlogged plant
                                if (targetType == Material.WATER) {
                                    val blockData = snapshot.getBlockData(lx, targetY, lz)
                                    if (blockData is Levelled && blockData.level == 0) isFreezableWater = true
                                } else if (targetType == Material.SEAGRASS || targetType == Material.TALL_SEAGRASS || targetType == Material.KELP || targetType == Material.KELP_PLANT) {
                                    isFreezableWater = true
                                }

                                if (isFreezableWater) {

                                    val biomeName = "minecraft:" + snapshot.getBiome(lx, targetY, lz).key.key.lowercase()
                                    if (isBiomeExcluded(biomeName)) continue

                                    // Cross-chunk neighbor check!
                                    // Now water on the chunk border easily sees ice in the neighboring chunk.
                                    var contacts = 0
                                    if (cache.isSolidCrossChunk(cx, cz, lx - 1, targetY, lz)) contacts++
                                    if (cache.isSolidCrossChunk(cx, cz, lx + 1, targetY, lz)) contacts++
                                    if (cache.isSolidCrossChunk(cx, cz, lx, targetY, lz - 1)) contacts++
                                    if (cache.isSolidCrossChunk(cx, cz, lx, targetY, lz + 1)) contacts++

                                    if (contacts > 0) {
                                        val globalX = (cx shl 4) + lx
                                        val globalZ = (cz shl 4) + lz
                                        freezableCandidates.add(IceCandidate(world, globalX, targetY, globalZ, contacts))
                                    }
                                }
                            }
                        }

                        if (freezableCandidates.isNotEmpty()) {
                            freezableCandidates.shuffle()
                            freezableCandidates.sortByDescending { it.contacts }

                            val limit = minOf(cfg.maxFreezesPerChunk, freezableCandidates.size)
                            for (j in 0 until limit) {
                                val c = freezableCandidates[j]
                                finalPlacements.add(IcePlacement(c.world, c.x, c.y, c.z))
                            }
                        }
                    }

                    if (finalPlacements.isNotEmpty()) {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            for (p in finalPlacements) {
                                val block = p.world.getBlockAt(p.x, p.y, p.z)
                                val type = block.type

                                var shouldFreeze = false
                                if (type == Material.WATER) {
                                    val data = block.blockData
                                    if (data is Levelled && data.level == 0) {
                                        shouldFreeze = true
                                    }
                                } else if (type == Material.SEAGRASS || type == Material.TALL_SEAGRASS || type == Material.KELP || type == Material.KELP_PLANT) {
                                    shouldFreeze = true
                                }

                                if (shouldFreeze) {
                                    // Handle 2-tall plants safely to prevent item drops and physics glitches
                                    if (type == Material.TALL_SEAGRASS) {
                                        val data = block.blockData as? Bisected
                                        if (data != null && data.half == Bisected.Half.TOP) {
                                            val bottom = block.getRelative(BlockFace.DOWN)
                                            if (bottom.type == Material.TALL_SEAGRASS) {
                                                bottom.setType(Material.SEAGRASS, false) // Trim down to 1-tall seagrass
                                            }
                                        }
                                    }

                                    block.setType(Material.ICE, false)
                                }
                            }
                        })
                    }
                })
            }
        }

        task?.runTaskTimer(plugin, 40L, cfg.intervalTicks)
    }

    private fun isBiomeExcluded(biomeKey: String): Boolean {
        val shortKey = biomeKey.replace("minecraft:", "")
        return excludedBiomes.any { ex ->
            val lowerEx = ex.lowercase()
            when {
                lowerEx == biomeKey || lowerEx == shortKey -> true
                lowerEx.contains("*") -> {
                    val regex = lowerEx.replace("*", ".*").toRegex()
                    biomeKey.matches(regex) || shortKey.matches(regex)
                }
                else -> false
            }
        }
    }

    fun stop() {
        task?.cancel()
        task = null
    }
}