package vx.vivaldi.gameplay.feature

import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Snow
import org.bukkit.event.Listener
import org.bukkit.scheduler.BukkitRunnable
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.config.lib.annotations.Comment
import vx.vivaldi.season.Season

object SeasonalMeltingFeature : Listener {

    class MeltingConfig {
        var enabled: Boolean = true

        @Comment("How often to run the melting cycle (in ticks). 20 = 1 second")
        var intervalTicks: Long = 20L

        @Comment("How many chunks to randomly process per cycle")
        var chunksPerCycle: Int = 30

        @Comment("Maximum number of snow/ice blocks to melt in a single chunk per cycle. Keeps melting gradual.")
        var maxMeltedBlocksPerChunk: Int = 50
    }

    private val cfg get() = plugin.gameplayManager.config.environment.melting
    private var task: BukkitRunnable? = null

    private enum class MeltActionType {
        MELT_SNOW_LAYER, MELT_ICE
    }

    private data class MeltAction(
        val world: World,
        val x: Int,
        val y: Int,
        val z: Int,
        val type: MeltActionType
    )

    init {
        start()
    }

    fun start() {
        if (!cfg.enabled) return
        if (task != null) return

        task = object : BukkitRunnable() {
            override fun run() {
                val currentSeason = plugin.seasonManager.currentSeason

                // Melting happens in all seasons EXCEPT Winter
                if (currentSeason == Season.WINTER) return

                val snapshots = mutableListOf<ChunkSnapshot>()
                val activeWorlds = mutableListOf<World>()

                // Collect random loaded chunks
                for (world in Bukkit.getWorlds()) {
                    if (world.name !in plugin.gameplayManager.allowedWorlds) continue

                    val loadedChunks = world.loadedChunks
                    if (loadedChunks.isEmpty()) continue

                    val limit = minOf(cfg.chunksPerCycle, loadedChunks.size)
                    for (i in 0 until limit) {
                        val randomChunk = loadedChunks.random()
                        snapshots.add(randomChunk.getChunkSnapshot(true, false, false))
                        activeWorlds.add(world)
                    }
                }

                if (snapshots.isEmpty()) return

                // Process asynchronously to find actual snow/ice blocks
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    val actions = mutableListOf<MeltAction>()

                    for (i in snapshots.indices) {
                        val snapshot = snapshots[i]
                        val world = activeWorlds[i]
                        val chunkX = snapshot.x
                        val chunkZ = snapshot.z

                        val chunkCandidates = mutableListOf<MeltAction>()

                        // Scan the entire 16x16 surface of the chunk
                        for (lx in 0 until 16) {
                            for (lz in 0 until 16) {
                                val highestY = snapshot.getHighestBlockYAt(lx, lz)
                                var targetY = highestY + 1

                                // Scan downwards to find the actual exposed surface block
                                while (targetY >= world.minHeight) {
                                    val type = snapshot.getBlockType(lx, targetY, lz)
                                    if (!type.isAir) {
                                        // Once we hit a solid block, check if it's meltable
                                        if (type == Material.SNOW) {
                                            chunkCandidates.add(MeltAction(world, (chunkX shl 4) + lx, targetY, (chunkZ shl 4) + lz, MeltActionType.MELT_SNOW_LAYER))
                                        } else if (isIce(type)) {
                                            chunkCandidates.add(MeltAction(world, (chunkX shl 4) + lx, targetY, (chunkZ shl 4) + lz, MeltActionType.MELT_ICE))
                                        }
                                        break // Stop digging down for this column
                                    }
                                    targetY--
                                }
                            }
                        }

                        // If we found snow/ice, we randomly pick a limited amount to melt.
                        // This prevents the whole chunk from instantly melting, keeping it organic.
                        if (chunkCandidates.isNotEmpty()) {
                            chunkCandidates.shuffle()
                            actions.addAll(chunkCandidates.take(cfg.maxMeltedBlocksPerChunk))
                        }
                    }

                    if (actions.isNotEmpty()) {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            applyMelting(actions)
                        })
                    }
                })
            }
        }

        task?.runTaskTimer(plugin, 60L, cfg.intervalTicks)
    }

    private fun applyMelting(actions: List<MeltAction>) {
        for (action in actions) {
            // Ensure chunk is still loaded before applying changes to prevent cascading lag
            if (!action.world.isChunkLoaded(action.x shr 4, action.z shr 4)) continue

            val block = action.world.getBlockAt(action.x, action.y, action.z)

            when (action.type) {
                MeltActionType.MELT_SNOW_LAYER -> {
                    if (block.type == Material.SNOW) {
                        val snowData = block.blockData as? Snow
                        if (snowData != null) {
                            // Gradually reduce snow by 1 layer at a time
                            if (snowData.layers > 1) {
                                snowData.layers -= 1
                                block.setBlockData(snowData, false)
                            } else {
                                // If it's the last layer, melt it completely into air
                                block.type = Material.AIR
                            }
                        }
                    }
                }
                MeltActionType.MELT_ICE -> {
                    if (isIce(block.type)) {
                        // Ice only melts if it touches the shore.
                        // Since we randomly pick from candidates, it will slowly melt from the outside in!
                        if (isShoreline(block.world, action.x, action.y, action.z)) {
                            block.type = Material.WATER
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if the block is touching a solid non-ice block horizontally (simulating a shore/coast).
     */
    private fun isShoreline(world: World, x: Int, y: Int, z: Int): Boolean {
        val faces = arrayOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
        for (face in faces) {
            val nx = x + face.modX
            val nz = z + face.modZ

            // Do not load adjacent chunks just to check shoreline (prevents performance hits)
            if (!world.isChunkLoaded(nx shr 4, nz shr 4)) continue

            val neighbor = world.getBlockAt(nx, y, nz)
            val type = neighbor.type

            // If it's a solid block and NOT ice/snow/water, it's considered shore
            if (type.isSolid && !isIce(type) && type != Material.SNOW && type != Material.SNOW_BLOCK) {
                return true
            }
        }
        return false
    }

    private fun isIce(material: Material): Boolean {
        return material == Material.ICE ||
                material == Material.PACKED_ICE ||
                material == Material.BLUE_ICE ||
                material == Material.FROSTED_ICE
    }

    fun stop() {
        task?.cancel()
        task = null
    }
}