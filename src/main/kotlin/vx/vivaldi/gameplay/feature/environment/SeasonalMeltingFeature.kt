package vx.vivaldi.gameplay.feature.environment

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Snow
import org.bukkit.event.Listener
import org.bukkit.scheduler.BukkitRunnable
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.config.lib.annotations.Comment
import vx.vivaldi.season.Season
import kotlin.random.Random

object SeasonalMeltingFeature : Listener {

    class MeltingConfig {
        var enabled: Boolean = true

        @Comment("How often to run the melting cycle (in ticks). 20 = 1 second")
        var intervalTicks: Long = 20L

        @Comment("How many chunks to randomly process per cycle")
        var chunksPerCycle: Int = 30

        @Comment("How many random blocks to check in each selected chunk. Higher = faster melting. Replaces the old full-chunk scan.")
        var attemptsPerChunk: Int = 100
    }

    private val cfg get() = plugin.gameplayManager.config.environment.melting
    private var task: BukkitRunnable? = null

    // Optimized array for 4-way horizontal neighbor checks
    private val neighborFaces = arrayOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

    init {
        start()
    }

    fun start() {
        if (!cfg.enabled) return
        if (task != null) return

        task = object : BukkitRunnable() {
            override fun run() {
                val currentSeason = plugin.seasonManager.currentSeason

                // Step 1: Gather random target locations synchronously
                val targets = mutableListOf<Block>()

                for (world in Bukkit.getWorlds()) {
                    if (world.name !in plugin.gameplayManager.allowedWorlds) continue

                    val loadedChunks = world.loadedChunks
                    if (loadedChunks.isEmpty()) continue

                    val limit = minOf(cfg.chunksPerCycle, loadedChunks.size)
                    for (i in 0 until limit) {
                        val randomChunk = loadedChunks.random()

                        for (attempt in 0 until cfg.attemptsPerChunk) {
                            val lx = Random.nextInt(16)
                            val lz = Random.nextInt(16)

                            val globalX = (randomChunk.x shl 4) + lx
                            val globalZ = (randomChunk.z shl 4) + lz

                            val highestY = world.getHighestBlockYAt(globalX, globalZ)

                            // Scan downwards to find the actual exposed surface block
                            var targetBlock: Block? = null
                            for (y in highestY downTo world.minHeight) {
                                val block = world.getBlockAt(globalX, y, globalZ)
                                val type = block.type

                                if (type.isAir) continue

                                // FIX: Ignore leaves so snow UNDER trees can properly melt!
                                if (type.name.endsWith("_LEAVES")) continue

                                targetBlock = block
                                break
                            }

                            if (targetBlock != null) {
                                targets.add(targetBlock)
                            }
                        }
                    }
                }

                if (targets.isEmpty()) return

                // Step 2: Process melting on valid targets
                for (block in targets) {
                    val type = block.type

                    // Quick exit if it's not a meltable block
                    if (type != Material.SNOW && !isIce(type)) continue

                    // --- CRITICAL FIX: DYNAMIC SEASONAL TEMPERATURE CHECK ---
                    var seasonalTemp = block.temperature
                    when (currentSeason) {
                        Season.SUMMER -> seasonalTemp += 0.4
                        Season.WINTER -> seasonalTemp -= 0.8
                        else -> {}
                    }

                    // If the temperature is still freezing (< 0.15), DO NOT MELT!
                    // This allows inherently snowy biomes (like Snowy Taiga) and high mountains
                    // to retain their snow globally, even during Summer.
                    if (seasonalTemp < 0.15) continue
                    // --------------------------------------------------------

                    // Apply melting logic
                    if (type == Material.SNOW) {
                        val snowData = block.blockData as? Snow
                        if (snowData != null) {
                            if (snowData.layers > 1) {
                                snowData.layers -= 1
                                block.setBlockData(snowData, false)
                            } else {
                                block.type = Material.AIR
                            }
                        }
                    } else if (isIce(type)) {
                        // Ice only melts if it touches the shore (melts from the outside in)
                        if (isShoreline(block)) {
                            block.type = Material.WATER
                        }
                    }
                }
            }
        }

        task?.runTaskTimer(plugin, 60L, cfg.intervalTicks)
    }

    /**
     * Checks if the block is touching a solid non-ice block horizontally (simulating a shore/coast).
     */
    private fun isShoreline(block: Block): Boolean {
        val world = block.world
        for (face in neighborFaces) {
            val neighbor = block.getRelative(face)

            // Do not load adjacent chunks just to check shoreline (prevents lag spikes at chunk borders)
            if (!world.isChunkLoaded(neighbor.x shr 4, neighbor.z shr 4)) continue

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