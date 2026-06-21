package vx.seasons.gameplay.feature.environment

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Snow
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.scheduler.BukkitRunnable
import vx.seasons.SeasonsPlugin.Companion.plugin
import vx.seasons.config.lib.annotations.Comment
import vx.seasons.season.Season
import kotlin.random.Random

object SnowAccumulationFeature : Listener {

    class SnowConfig {
        var enabled: Boolean = true

        @Comment("How often to run the snow calculation cycle (in ticks). 20 = 1 second")
        var intervalTicks: Long = 20L

        @Comment("How many chunks to randomly process per cycle")
        var chunksPerCycle: Int = 30

        @Comment("How many random blocks to check in each selected chunk")
        var attemptsPerChunk: Int = 25

        @Comment("Maximum layers of snow a block can accumulate against a wall. Dictates the height of the smooth slope. Vanilla max is 8.")
        var maxSnowLayers: Int = 6

        @Comment("Chance for snow to fall through leaves to the ground below (0.0 - 1.0)")
        var fallThroughLeavesChance: Double = 0.45

        @Comment("Chance to add a layer of snow to form smooth slopes/stairs near elevation changes")
        var slopeFormationChance: Double = 0.60

        @Comment("Allow snow to accumulate and stay on ice blocks. Prevents vanilla chain-reaction breaking.")
        var allowSnowOnIce: Boolean = true
    }

    private val cfg get() = plugin.gameplayManager.config.environment.snowAccumulation
    private var task: BukkitRunnable? = null

    // List of fragile blocks that snow will crush when falling
    private val fragileBlocks = setOf(
        Material.TORCH, Material.SOUL_TORCH, Material.REDSTONE_TORCH,
        Material.WALL_TORCH, Material.SOUL_WALL_TORCH, Material.REDSTONE_WALL_TORCH,
        Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
        Material.SWEET_BERRY_BUSH, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
        Material.SUGAR_CANE, Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID,
        Material.ALLIUM, Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP,
        Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY, Material.CORNFLOWER,
        Material.LILY_OF_THE_VALLEY, Material.WITHER_ROSE, Material.SUNFLOWER, Material.LILAC,
        Material.ROSE_BUSH, Material.PEONY, Material.TALL_GRASS, Material.SHORT_GRASS,
        Material.FERN, Material.LARGE_FERN, Material.DEAD_BUSH, Material.WILDFLOWERS,
        Material.LEAF_LITTER, Material.FIREFLY_BUSH
    )

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

                // Step 1: Gather target locations
                // We do this fast and synchronously. 750 blocks per cycle takes < 1ms on the main thread.
                // This eliminates cross-chunk snapshot boundary issues entirely.
                val targets = mutableListOf<Block>()

                for (world in Bukkit.getWorlds()) {
                    if (world.name !in plugin.gameplayManager.allowedWorlds) continue
                    if (!world.hasStorm()) continue

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
                            targets.add(world.getBlockAt(globalX, highestY, globalZ))
                        }
                    }
                }

                if (targets.isEmpty()) return

                // Step 2: Process snow placements
                for (topBlock in targets) {
                    val world = topBlock.world
                    val x = topBlock.x
                    val z = topBlock.z
                    val highestY = topBlock.y

                    var targetBlock: Block? = null

                    // Scan downwards to find the exact resting place for the snow
                    for (y in highestY downTo world.minHeight) {
                        val block = world.getBlockAt(x, y, z)
                        val type = block.type
                        if (type.isAir) continue

                        if (type.name.endsWith("_LEAVES")) {
                            if (Random.nextDouble() < cfg.fallThroughLeavesChance) {
                                continue
                            } else {
                                targetBlock = block.getRelative(BlockFace.UP)
                                break
                            }
                        }

                        if (fragileBlocks.contains(type) || type == Material.SNOW) {
                            targetBlock = block
                            break
                        }

                        if (type == Material.WATER || type == Material.LAVA) {
                            break
                        }

                        if (block.isSolid) {
                            targetBlock = block.getRelative(BlockFace.UP)
                            break
                        }
                    }

                    if (targetBlock == null) continue

                    // --- CRITICAL FIX: DYNAMIC SEASONAL TEMPERATURE CHECK ---
                    // Snow can fall in ANY season, as long as the shifted temperature is freezing (< 0.15).
                    var seasonalTemp = targetBlock.temperature
                    when (currentSeason) {
                        Season.SUMMER -> seasonalTemp += 0.4
                        Season.WINTER -> seasonalTemp -= 0.8
                        else -> {} // Spring & Autumn use base temperature
                    }

                    // If it's too warm (>= 0.15), vanilla weather is raining here, not snowing. Skip.
                    if (seasonalTemp >= 0.15) continue
                    // --------------------------------------------------------

                    val belowBlock = targetBlock.getRelative(BlockFace.DOWN)
                    val belowType = belowBlock.type

                    if (!cfg.allowSnowOnIce && isIce(belowType)) continue

                    // PREVENT SNOW ON NON-FULL BLOCKS
                    if (isInvalidSnowSurface(belowType)) continue

                    val targetType = targetBlock.type

                    if (fragileBlocks.contains(targetType)) {
                        targetBlock.breakNaturally()
                        targetBlock.setType(Material.SNOW, false)
                    }
                    else if (targetType == Material.SNOW) {
                        val snowData = targetBlock.blockData as? Snow
                        if (snowData != null && snowData.layers < cfg.maxSnowLayers) {

                            // SLOPE CALCULATION: Now runs sync, natively fixing cross-chunk border bugs!
                            val expectedLayers = getExpectedSnowLayers(targetBlock, cfg.maxSnowLayers)

                            if (snowData.layers < expectedLayers) {
                                if (Random.nextDouble() < cfg.slopeFormationChance) {
                                    snowData.layers += 1
                                    targetBlock.setBlockData(snowData, false)
                                }
                            }
                        }
                    }
                    else if (targetType.isAir) {
                        if (belowBlock.isSolid && belowType != Material.SNOW) {
                            targetBlock.setType(Material.SNOW, false)
                        }
                    }
                }
            }
        }

        task?.runTaskTimer(plugin, 40L, cfg.intervalTicks)
    }

    /**
     * Calculates the required snow depth based on neighboring blocks to form smooth, cohesive slopes.
     */
    private fun getExpectedSnowLayers(block: Block, maxAllowed: Int): Int {
        var expected = 1
        for (face in neighborFaces) {
            val neighbor = block.getRelative(face)
            val neighborType = neighbor.type

            // Stack snow tall against solid walls
            if (neighborType.isSolid && !neighborType.name.endsWith("_LEAVES") && neighborType != Material.SNOW) {
                return maxAllowed
            }

            // Slope downwards smoothly from adjacent taller snow
            if (neighborType == Material.SNOW) {
                val data = neighbor.blockData as? Snow
                if (data != null) {
                    val slopeDecay = data.layers - 1
                    if (slopeDecay > expected) {
                        expected = slopeDecay
                    }
                }
            }

            // Look diagonally upwards to build beautiful connecting stairs
            val upNeighbor = neighbor.getRelative(BlockFace.UP)
            val upNeighborType = upNeighbor.type

            if (upNeighborType.isSolid && !upNeighborType.name.endsWith("_LEAVES") && upNeighborType != Material.SNOW) {
                return maxAllowed
            }

            if (upNeighborType == Material.SNOW) {
                val data = upNeighbor.blockData as? Snow
                if (data != null) {
                    val slopeDecay = data.layers + 1
                    if (slopeDecay > expected) {
                        expected = minOf(slopeDecay, maxAllowed)
                    }
                }
            }
        }
        return minOf(expected, maxAllowed)
    }

    /**
     * Rejects blocks that physically report as solid but visually do not fill a 1x1x1 volume.
     * Prevents snow from floating in the air above stairs, slabs, fences, etc.
     */
    private fun isInvalidSnowSurface(material: Material): Boolean {
        val name = material.name

        // FIX: Replaced `endsWith` with `contains` to definitively ban all prefix/suffix variations.
        if (name.contains("SLAB") ||
            name.contains("STAIRS") ||
            name.contains("WALL") ||
            name.contains("FENCE") ||
            name.contains("GATE")) {
            return true
        }

        return name.endsWith("_SIGN") ||
                name.endsWith("_BANNER") ||
                name.endsWith("_TRAPDOOR") ||
                name.endsWith("_BED") ||
                name.endsWith("_PANE") ||
                name.endsWith("_BUTTON") ||
                name.endsWith("_CARPET") ||
                name.endsWith("_PRESSURE_PLATE") ||
                name.endsWith("_SHULKER_BOX") ||
                name == "IRON_BARS" ||
                name == "CHAIN" ||
                name == "LEVER" ||
                name == "HOPPER" ||
                name == "CAULDRON" ||
                name.contains("ANVIL") || // catches damaged/chipped variants universally
                name == "BELL" ||
                name.endsWith("LANTERN") || // lantern, soul_lantern
                name.endsWith("CAMPFIRE") || // campfire, soul_campfire
                name == "DAYLIGHT_DETECTOR" ||
                name.endsWith("EGG") || // turtle, sniffer
                name == "POINTED_DRIPSTONE" ||
                name == "LIGHTNING_ROD" ||
                name == "COBWEB" ||
                name == "SCAFFOLDING" ||
                name == "LADDER" ||
                name == "END_ROD" ||
                name.endsWith("CHEST") ||
                name == "BREWING_STAND" ||
                name == "LECTERN" ||
                name == "CONDUIT" ||
                name == "COMPOSTER"
    }

    private fun isIce(material: Material): Boolean {
        return material == Material.ICE ||
                material == Material.PACKED_ICE ||
                material == Material.BLUE_ICE ||
                material == Material.FROSTED_ICE
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockDestroy(event: BlockDestroyEvent) {
        if (!cfg.enabled || !cfg.allowSnowOnIce) return

        val block = event.block
        if (block.type == Material.SNOW) {
            val belowType = block.getRelative(BlockFace.DOWN).type
            if (isIce(belowType)) {
                // Cancel the block from turning into air and starting a chain reaction
                event.isCancelled = true
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPhysics(event: BlockPhysicsEvent) {
        if (!cfg.enabled || !cfg.allowSnowOnIce) return

        val block = event.block
        if (block.type == Material.SNOW) {
            val belowType = block.getRelative(BlockFace.DOWN).type
            if (isIce(belowType)) {
                event.isCancelled = true
            }
        }
    }

    fun stop() {
        task?.cancel()
        task = null
    }
}