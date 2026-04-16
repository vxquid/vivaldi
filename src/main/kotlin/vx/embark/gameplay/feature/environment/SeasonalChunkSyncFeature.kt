package vx.embark.gameplay.feature.environment

import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.Bisected
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.persistence.PersistentDataType
import vx.embark.Wilderness.Companion.plugin
import vx.embark.config.lib.annotations.Comment
import vx.embark.season.Season
import kotlin.random.Random

object SeasonalChunkSyncFeature : Listener {

    class ChunkSyncConfig {
        var enabled: Boolean = true

        @Comment("Chance to cover a valid top block with snow when a chunk is synced in Winter (0.0 - 1.0)")
        var winterSnowCoverage: Double = 0.85

        @Comment("Chance for snow to fall through leaves during chunk sync instead of sitting on top")
        var winterLeavesFallthrough: Double = 0.45

        @Comment("How many attempts to create plant clusters in a chunk during Spring/Summer")
        var floraAttemptsPerChunk: Int = 20

        @Comment("Multiplier for grass density near water")
        var waterProximityMultiplier: Double = 2.0

        @Comment("Spawn chances for various plants (0.0 - 1.0)")
        var flowerChance: Double = 0.15
        var tallGrassChance: Double = 0.30
        var wheatChance: Double = 0.02

        @Comment("Min and max grass block clusters")
        var minGrassCluster: Int = 3
        var maxGrassCluster: Int = 7
    }

    // You might need to adjust the getter path if you renamed it in your config class forest
    private val cfg get() = plugin.gameplayManager.config.environment.repopulator

    // PDC key to track which season the chunk was last synchronized with
    private val syncedSeasonKey = NamespacedKey(plugin, "last_synced_season")

    private val flowers = listOf(
        Material.DANDELION, Material.POPPY, Material.CORNFLOWER,
        Material.OXEYE_DAISY, Material.AZURE_BLUET, Material.ALLIUM
    )

    // Fragile blocks that will be crushed by settling snow
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
        Material.FERN, Material.LARGE_FERN, Material.DEAD_BUSH, Material.WILDFLOWERS
    )

    private enum class GrowthMode {
        NORMAL, WHEAT, TALL_PLANT
    }

    private enum class SyncActionType {
        PLACE_SNOW, CRUSH_AND_PLACE_SNOW, GROW_PLANT
    }

    private data class SyncAction(
        val x: Int,
        val y: Int,
        val z: Int,
        val actionType: SyncActionType,
        val material: Material = Material.AIR,
        val mode: GrowthMode = GrowthMode.NORMAL
    )

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (!cfg.enabled) return

        val chunk = event.chunk
        val world = chunk.world

        if (world.name !in plugin.gameplayManager.allowedWorlds) return

        val currentSeason = plugin.seasonManager.currentSeason
        val pdc = chunk.persistentDataContainer
        val lastSynced = pdc.get(syncedSeasonKey, PersistentDataType.STRING)

        // If the chunk is already synchronized with the current season, ignore it
        if (lastSynced == currentSeason.name) return

        // Mark the chunk as synced for THIS season immediately to prevent race conditions
        pdc.set(syncedSeasonKey, PersistentDataType.STRING, currentSeason.name)

        // Capture snapshot for thread-safe asynchronous reading
        val snapshot = chunk.getChunkSnapshot(true, false, false)
        val chunkX = chunk.x
        val chunkZ = chunk.z

        // Offload heavy scanning to an async thread
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val actions = mutableListOf<SyncAction>()

            when (currentSeason) {
                Season.WINTER -> {
                    actions.addAll(generateWinterActions(snapshot, chunkX, chunkZ, world))
                }
                Season.SPRING, Season.SUMMER -> {
                    actions.addAll(generateFloraActions(snapshot, chunkX, chunkZ, world))
                }
                else -> {
                    // Autumn could be added here (e.g. replacing standard leaves with autumn variants or fallen leaves)
                }
            }

            // Return to the main thread to apply block changes safely
            if (actions.isNotEmpty()) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    // Failsafe: check if the chunk was unloaded while we were calculating
                    if (!world.isChunkLoaded(chunkX, chunkZ)) return@Runnable

                    applyActions(world, actions)
                })
            }
        })
    }

    /**
     * Scans the chunk and generates snow placements for Winter.
     */
    private fun generateWinterActions(
        snapshot: ChunkSnapshot,
        chunkX: Int,
        chunkZ: Int,
        world: World
    ): List<SyncAction> {
        val actions = mutableListOf<SyncAction>()

        for (lx in 0 until 16) {
            for (lz in 0 until 16) {
                // Determine whether to place snow based on the configured coverage density
                if (Random.nextDouble() > cfg.winterSnowCoverage) continue

                val highestY = snapshot.getHighestBlockYAt(lx, lz)
                var targetY = -1
                var crush = false

                for (y in highestY downTo world.minHeight) {
                    val type = snapshot.getBlockType(lx, y, lz)
                    if (type.isAir) continue

                    // Handle leaves
                    if (type.name.endsWith("_LEAVES")) {
                        if (Random.nextDouble() < cfg.winterLeavesFallthrough) {
                            continue
                        } else {
                            targetY = y + 1
                            break
                        }
                    }

                    // Check if block is weak and should be crushed by snow
                    if (fragileBlocks.contains(type)) {
                        targetY = y
                        crush = true
                        break
                    }

                    // Skip if there's already snow or liquids
                    if (type == Material.SNOW || type == Material.WATER || type == Material.LAVA) {
                        break
                    }

                    if (type.isSolid) {
                        targetY = y + 1
                        break
                    }
                }

                if (targetY in world.minHeight until world.maxHeight) {
                    // Prevent placing snow directly on top of ice blocks
                    val belowY = targetY - 1
                    var skipIce = false
                    if (belowY >= world.minHeight) {
                        val belowType = snapshot.getBlockType(lx, belowY, lz)
                        if (isIce(belowType)) skipIce = true
                    }

                    if (!skipIce) {
                        val globalX = (chunkX shl 4) + lx
                        val globalZ = (chunkZ shl 4) + lz
                        val actionType = if (crush) SyncActionType.CRUSH_AND_PLACE_SNOW else SyncActionType.PLACE_SNOW
                        actions.add(SyncAction(globalX, targetY, globalZ, actionType))
                    }
                }
            }
        }
        return actions
    }

    /**
     * Scans the chunk and generates grass/flowers for Spring and Summer.
     */
    private fun generateFloraActions(
        snapshot: ChunkSnapshot,
        chunkX: Int,
        chunkZ: Int,
        world: World
    ): List<SyncAction> {
        val actions = mutableListOf<SyncAction>()

        for (i in 0 until cfg.floraAttemptsPerChunk) {
            val lx = Random.nextInt(16)
            val lz = Random.nextInt(16)

            val groundY = findGroundYAsync(snapshot, lx, lz, world)
            if (groundY == -1 || groundY >= world.maxHeight - 2) continue

            val airBlock = snapshot.getBlockType(lx, groundY + 1, lz)
            if (airBlock.isAir) {
                val skyLight = snapshot.getBlockSkyLight(lx, groundY + 1, lz)
                if (skyLight < 9) continue // Plants require sufficient lighting

                // Proximity water search
                var nearWater = false
                val minWX = maxOf(0, lx - 4)
                val maxWX = minOf(15, lx + 4)
                val minWZ = maxOf(0, lz - 4)
                val maxWZ = minOf(15, lz + 4)
                val minWY = maxOf(world.minHeight, groundY - 2)
                val maxWY = minOf(world.maxHeight - 1, groundY + 1)

                waterSearch@ for (wx in minWX..maxWX) {
                    for (wz in minWZ..maxWZ) {
                        for (wy in minWY..maxWY) {
                            if (snapshot.getBlockType(wx, wy, wz) == Material.WATER) {
                                nearWater = true
                                break@waterSearch
                            }
                        }
                    }
                }

                val clusterMult = if (nearWater) cfg.waterProximityMultiplier else 1.0
                val roll = Random.nextDouble()

                // Helper to cleanly create cluster formations entirely within the chunk boundaries
                fun addCluster(baseMin: Int, baseMax: Int, material: Material, mode: GrowthMode) {
                    val clusterSize = (Random.nextInt(baseMin, baseMax + 1) * clusterMult).toInt()
                    for (c in 0 until clusterSize) {
                        // Coerce coordinates so we never spill over to unloaded chunks (fixes lag spikes)
                        val cLx = (lx + Random.nextInt(-2, 3)).coerceIn(0, 15)
                        val cLz = (lz + Random.nextInt(-2, 3)).coerceIn(0, 15)

                        val cY = findGroundYAsync(snapshot, cLx, cLz, world)
                        if (cY != -1) {
                            val globalX = (chunkX shl 4) + cLx
                            val globalZ = (chunkZ shl 4) + cLz
                            actions.add(SyncAction(globalX, cY, globalZ, SyncActionType.GROW_PLANT, material, mode))
                        }
                    }
                }

                when {
                    roll < cfg.wheatChance -> {
                        addCluster(3, 4, Material.WHEAT, GrowthMode.WHEAT)
                    }
                    roll < (cfg.wheatChance + cfg.flowerChance) -> {
                        val globalX = (chunkX shl 4) + lx
                        val globalZ = (chunkZ shl 4) + lz
                        actions.add(SyncAction(globalX, groundY, globalZ, SyncActionType.GROW_PLANT, flowers.random(), GrowthMode.NORMAL))
                    }
                    roll < (cfg.wheatChance + cfg.flowerChance + cfg.tallGrassChance) -> {
                        addCluster(cfg.minGrassCluster, cfg.maxGrassCluster, Material.TALL_GRASS, GrowthMode.TALL_PLANT)
                    }
                    else -> {
                        addCluster(cfg.minGrassCluster, cfg.maxGrassCluster, Material.SHORT_GRASS, GrowthMode.NORMAL)
                    }
                }
            }
        }
        return actions
    }

    /**
     * Applies the previously calculated block placements synchronously.
     */
    private fun applyActions(world: World, actions: List<SyncAction>) {
        for (action in actions) {
            val block = world.getBlockAt(action.x, action.y, action.z)

            when (action.actionType) {
                SyncActionType.CRUSH_AND_PLACE_SNOW -> {
                    if (fragileBlocks.contains(block.type)) {
                        block.breakNaturally()
                        block.setType(Material.SNOW, false)
                    }
                }
                SyncActionType.PLACE_SNOW -> {
                    if (block.type.isAir && block.getRelative(BlockFace.DOWN).type.isSolid) {
                        block.setType(Material.SNOW, false)
                    }
                }
                SyncActionType.GROW_PLANT -> {
                    val airBlock = world.getBlockAt(action.x, action.y + 1, action.z)
                    val isValidGround = block.type == Material.GRASS_BLOCK || block.type == Material.PODZOL

                    if (isValidGround && airBlock.type.isAir) {
                        when (action.mode) {
                            GrowthMode.WHEAT -> {
                                if (block.type == Material.GRASS_BLOCK) {
                                    block.type = Material.FARMLAND
                                    airBlock.type = Material.WHEAT
                                    val blockData = airBlock.blockData
                                    if (blockData is Ageable) {
                                        blockData.age = Random.nextInt(0, 4)
                                        airBlock.blockData = blockData
                                    }
                                }
                            }
                            GrowthMode.TALL_PLANT -> {
                                val upperAir = world.getBlockAt(action.x, action.y + 2, action.z)
                                if (upperAir.type.isAir) {
                                    airBlock.type = action.material
                                    val bottomData = airBlock.blockData as? Bisected
                                    if (bottomData != null) {
                                        bottomData.half = Bisected.Half.BOTTOM
                                        airBlock.blockData = bottomData
                                    }

                                    upperAir.type = action.material
                                    val topData = upperAir.blockData as? Bisected
                                    if (topData != null) {
                                        topData.half = Bisected.Half.TOP
                                        upperAir.blockData = topData
                                    }
                                }
                            }
                            GrowthMode.NORMAL -> {
                                airBlock.type = action.material
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Reusable async-safe search for valid grass or podzol.
     */
    private fun findGroundYAsync(snapshot: ChunkSnapshot, lx: Int, lz: Int, world: World): Int {
        val highestY = snapshot.getHighestBlockYAt(lx, lz)
        for (y in highestY downTo world.minHeight) {
            val type = snapshot.getBlockType(lx, y, lz)
            if (type == Material.GRASS_BLOCK || type == Material.PODZOL) return y

            // Abort if we hit solid structures/trunks before finding grass
            if (type.isSolid && !type.name.contains("LEAVES") && !type.name.contains("LOG") && !type.name.contains("WOOD")) {
                break
            }
        }
        return -1
    }

    private fun isIce(material: Material): Boolean {
        return material == Material.ICE ||
                material == Material.PACKED_ICE ||
                material == Material.BLUE_ICE ||
                material == Material.FROSTED_ICE
    }
}