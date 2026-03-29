package vx.vivaldi.gameplay.feature

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.scheduler.BukkitRunnable
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.config.lib.annotations.Comment
import vx.vivaldi.season.Season
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

    private enum class SnowAction {
        PLACE_SNOW, ADD_LAYER, CRUSH_AND_PLACE
    }

    private data class SnowPlacement(
        val world: World,
        val x: Int,
        val y: Int,
        val z: Int,
        val action: SnowAction
    )

    // Optimized offset array for neighbor search (North, South, East, West)
    private val neighborOffsets = intArrayOf(-1, 0, 1, 0, 0, -1, 0, 1)

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

                val snapshots = mutableListOf<ChunkSnapshot>()
                val activeWorlds = mutableListOf<World>()

                for (world in Bukkit.getWorlds()) {
                    if (world.name !in plugin.gameplayManager.allowedWorlds) continue
                    if (!world.hasStorm()) continue

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

                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    val placements = mutableListOf<SnowPlacement>()

                    for (i in snapshots.indices) {
                        val snapshot = snapshots[i]
                        val world = activeWorlds[i]

                        for (attempt in 0 until cfg.attemptsPerChunk) {
                            val lx = Random.nextInt(16)
                            val lz = Random.nextInt(16)
                            val highestY = snapshot.getHighestBlockYAt(lx, lz)

                            var targetY = -1

                            for (y in highestY downTo world.minHeight) {
                                val type = snapshot.getBlockType(lx, y, lz)
                                if (type.isAir) continue

                                if (type.name.endsWith("_LEAVES")) {
                                    if (Random.nextDouble() < cfg.fallThroughLeavesChance) {
                                        continue
                                    } else {
                                        targetY = y + 1
                                        break
                                    }
                                }

                                if (fragileBlocks.contains(type)) {
                                    targetY = y
                                    break
                                }

                                if (type == Material.SNOW) {
                                    targetY = y
                                    break
                                }

                                if (type == Material.WATER || type == Material.LAVA) {
                                    break
                                }

                                if (type.isSolid) {
                                    targetY = y + 1
                                    break
                                }
                            }

                            if (targetY in world.minHeight until world.maxHeight) {

                                val belowY = targetY - 1
                                if (belowY >= world.minHeight) {
                                    val belowType = snapshot.getBlockType(lx, belowY, lz)

                                    if (!cfg.allowSnowOnIce && isIce(belowType)) {
                                        continue
                                    }

                                    // PREVENT SNOW ON NON-FULL BLOCKS:
                                    // Slabs, stairs, and fences report 'isSolid' = true, so we must manually reject them.
                                    if (isInvalidSnowSurface(belowType)) {
                                        continue
                                    }
                                }

                                val targetType = snapshot.getBlockType(lx, targetY, lz)
                                val globalX = (snapshot.x shl 4) + lx
                                val globalZ = (snapshot.z shl 4) + lz

                                if (fragileBlocks.contains(targetType)) {
                                    placements.add(SnowPlacement(world, globalX, targetY, globalZ, SnowAction.CRUSH_AND_PLACE))
                                }
                                else if (targetType == Material.SNOW) {
                                    val snowData = snapshot.getBlockData(lx, targetY, lz) as? org.bukkit.block.data.type.Snow
                                    if (snowData != null && snowData.layers < cfg.maxSnowLayers) {

                                        // SLOPE CALCULATION: Check neighbors to calculate smooth gradient stairs
                                        val expectedLayers = getExpectedSnowLayers(snapshot, lx, targetY, lz, cfg.maxSnowLayers)

                                        // Only add a layer if the block is part of a slope near a wall/hill
                                        if (snowData.layers < expectedLayers) {
                                            if (Random.nextDouble() < cfg.slopeFormationChance) {
                                                placements.add(SnowPlacement(world, globalX, targetY, globalZ, SnowAction.ADD_LAYER))
                                            }
                                        }
                                    }
                                }
                                else if (targetType.isAir) {
                                    val below = snapshot.getBlockType(lx, targetY - 1, lz)
                                    if (below.isSolid && below != Material.SNOW) {
                                        placements.add(SnowPlacement(world, globalX, targetY, globalZ, SnowAction.PLACE_SNOW))
                                    }
                                }
                            }
                        }
                    }

                    if (placements.isNotEmpty()) {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            for (p in placements) {
                                val block = p.world.getBlockAt(p.x, p.y, p.z)

                                when (p.action) {
                                    SnowAction.CRUSH_AND_PLACE -> {
                                        if (fragileBlocks.contains(block.type)) {
                                            block.breakNaturally()
                                            block.setType(Material.SNOW, false)
                                        }
                                    }
                                    SnowAction.PLACE_SNOW -> {
                                        if (block.type.isAir && block.getRelative(BlockFace.DOWN).type.isSolid) {
                                            block.setType(Material.SNOW, false)
                                        }
                                    }
                                    SnowAction.ADD_LAYER -> {
                                        if (block.type == Material.SNOW) {
                                            val snowData = block.blockData as? org.bukkit.block.data.type.Snow
                                            if (snowData != null && snowData.layers < cfg.maxSnowLayers) {
                                                snowData.layers += 1
                                                block.setBlockData(snowData, false)
                                            }
                                        }
                                    }
                                }
                            }
                        })
                    }
                })
            }
        }

        task?.runTaskTimer(plugin, 40L, cfg.intervalTicks)
    }

    private fun getExpectedSnowLayers(snapshot: ChunkSnapshot, lx: Int, y: Int, lz: Int, maxAllowed: Int): Int {
        var expected = 1
        for (i in 0 until 4) {
            val nx = lx + neighborOffsets[i * 2]
            val nz = lz + neighborOffsets[i * 2 + 1]

            if (nx !in 0..15 || nz !in 0..15) continue

            val aboveNeighbor = snapshot.getBlockType(nx, y + 1, nz)
            if (aboveNeighbor.isSolid && !aboveNeighbor.name.endsWith("_LEAVES")) {
                return maxAllowed
            }

            val neighborType = snapshot.getBlockType(nx, y, nz)
            if (neighborType == Material.SNOW) {
                val data = snapshot.getBlockData(nx, y, nz) as? org.bukkit.block.data.type.Snow
                if (data != null) {
                    val slopeDecay = data.layers - 1
                    if (slopeDecay > expected) {
                        expected = slopeDecay
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
        return name.endsWith("_STAIRS") ||
                name.endsWith("_SLAB") ||
                name.endsWith("_FENCE") ||
                name.endsWith("_GATE") ||
                name.endsWith("_WALL") ||
                name.endsWith("_SIGN") ||
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
                name == "ANVIL" ||
                name == "CHIPPED_ANVIL" ||
                name == "DAMAGED_ANVIL" ||
                name == "BELL" ||
                name == "LANTERN" ||
                name == "SOUL_LANTERN" ||
                name == "CAMPFIRE" ||
                name == "SOUL_CAMPFIRE" ||
                name == "DAYLIGHT_DETECTOR" ||
                name == "TURTLE_EGG" ||
                name == "SNIFFER_EGG" ||
                name == "POINTED_DRIPSTONE" ||
                name == "LIGHTNING_ROD" ||
                name == "COBWEB" ||
                name == "SCAFFOLDING" ||
                name == "LADDER" ||
                name == "END_ROD" ||
                name == "CHEST" ||
                name == "TRAPPED_CHEST" ||
                name == "ENDER_CHEST" ||
                name == "BREWING_STAND" ||
                name == "LECTERN" ||
                name == "CONDUIT"
    }

    private fun isIce(material: Material): Boolean {
        return material == Material.ICE ||
                material == Material.PACKED_ICE ||
                material == Material.BLUE_ICE ||
                material == Material.FROSTED_ICE
    }

    /**
     * PaperMC EVENT: intercepts and completely cancels the vanilla internal destruction logic.
     * When the server detects snow on an invalid block (like ice) and tries to break it,
     * this event stops it dead in its tracks. No cascading explosions.
     */
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

    /**
     * Fallback for older legacy physics checks just in case.
     */
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