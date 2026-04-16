package vx.embark.gameplay.feature.environment

import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.TreeType
import org.bukkit.World
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Beehive
import org.bukkit.event.Listener
import org.bukkit.scheduler.BukkitRunnable
import vx.embark.Wilderness.Companion.plugin
import vx.embark.config.lib.annotations.Comment
import vx.embark.season.Season
import kotlin.random.Random

object PlantGrowthFeature : Listener {

    class PlantGrowthConfig {
        var enabled: Boolean = true

        @Comment("How often to run the growth cycle (in ticks). 100 = every 5 seconds")
        var intervalTicks: Long = 100L
        var chunksPerCycle: Int = 15
        var attemptsPerChunk: Int = 4

        @Comment("How many times more growth attempts to make if it's raining")
        var rainAttemptsMultiplier: Int = 3
        @Comment("Grass density (cluster size) multiplier if near water")
        var waterProximityMultiplier: Double = 2.0

        @Comment("Growth chances for standard plants (Spring/Summer)")
        var treeChance: Double = 0.005
        var instantTreeGrowth: Boolean = false

        @Comment("Chance (0.0 - 1.0) to grow wild crops (wheat, carrots, potatoes, beetroots)")
        var cropChance: Double = 0.02
        @Comment("Minimum and maximum number of wild crop sprouts in a single cluster")
        var minCropCluster: Int = 5
        var maxCropCluster: Int = 6

        var flowerChance: Double = 0.15
        var tallGrassChance: Double = 0.20
        var minGrassCluster: Int = 3
        var maxGrassCluster: Int = 6

        @Comment("Chance to grow a lily pad on water (Spring/Summer)")
        var lilyPadChance: Double = 0.08

        @Comment("Chance of a bee nest spawning under oak/birch leaves (Spring)")
        var beeNestChance: Double = 0.01

        @Comment("Chance to grow a cactus on sand (Spring/Summer/Autumn)")
        var cactusChance: Double = 0.05

        @Comment("Chance to grow sugar cane near water (Spring/Summer/Autumn)")
        var sugarCaneChance: Double = 0.10

        @Comment("Chances for autumn plants (Autumn)")
        var mushroomChance: Double = 0.30
        var pumpkinChance: Double = 0.03

        @Comment("Chance to replace a flower with leaf litter (LEAF_LITTER) in Autumn")
        var autumnFlowerReplaceChance: Double = 0.40

        @Comment("Chance of accelerated growth for existing wild crops in Autumn (harvest effect)")
        var autumnCropGrowthChance: Double = 0.60
    }

    private val cfg get() = plugin.gameplayManager.config.environment.plantGrowth
    private var task: BukkitRunnable? = null

    // Used for spawning new flowers in Spring/Summer
    private val flowers = listOf(
        Material.DANDELION, Material.POPPY, Material.CORNFLOWER,
        Material.OXEYE_DAISY, Material.AZURE_BLUET, Material.ALLIUM
    )

    // Used for identifying ANY flower in the world to replace it in Autumn
    private val allFlowers = setOf(
        Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM,
        Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP,
        Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY,
        Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY, Material.WITHER_ROSE,
        Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY,
        Material.WILDFLOWERS, Material.PITCHER_PLANT, Material.TORCHFLOWER,
        Material.OPEN_EYEBLOSSOM, Material.CLOSED_EYEBLOSSOM
    )

    // Used for wild crop placements and identifying existing crops
    private val crops = listOf(
        Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS
    )

    private enum class GrowthMode {
        NORMAL, CROP, TALL_PLANT, TREE, CACTUS, SUGAR_CANE, BEE_NEST,
        REPLACE_WITH_LEAF_LITTER, AGE_CROP
    }

    private enum class ForestTree {
        OAK, BIRCH, SPRUCE, JUNGLE, ACACIA, DARK_OAK, MANGROVE, CHERRY
    }

    private data class PlantPlacement(
        val world: World,
        val x: Int,
        val z: Int,
        val type: Material,
        val mode: GrowthMode = GrowthMode.NORMAL,
        val treeType: TreeType? = null,
        val overrideY: Int? = null
    )

    private data class ScanChunk(val world: World, val snapshot: ChunkSnapshot, val isRaining: Boolean)

    init {
        start()
    }

    private fun start() {
        if (!cfg.enabled) return

        task = object : BukkitRunnable() {
            override fun run() {
                val currentSeason = plugin.seasonManager.currentSeason
                if (currentSeason == Season.WINTER) return

                val snapshots = mutableListOf<ScanChunk>()

                for (world in Bukkit.getWorlds()) {
                    if (world.name !in plugin.gameplayManager.allowedWorlds) continue
                    val loadedChunks = world.loadedChunks
                    if (loadedChunks.isEmpty()) continue

                    val isRaining = world.hasStorm()
                    val limit = minOf(cfg.chunksPerCycle, loadedChunks.size)

                    for (i in 0 until limit) {
                        val randomChunk = loadedChunks.random()
                        snapshots.add(ScanChunk(world, randomChunk.getChunkSnapshot(true, false, false), isRaining))
                    }
                }

                if (snapshots.isEmpty()) return

                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    val placements = mutableListOf<PlantPlacement>()

                    for (scan in snapshots) {
                        val world = scan.world
                        val snapshot = scan.snapshot
                        val attempts = if (scan.isRaining) cfg.attemptsPerChunk * cfg.rainAttemptsMultiplier else cfg.attemptsPerChunk

                        for (i in 0 until attempts) {
                            val lx = Random.nextInt(16)
                            val lz = Random.nextInt(16)

                            val globalX = (snapshot.x * 16) + lx
                            val globalZ = (snapshot.z * 16) + lz

                            val highestY = snapshot.getHighestBlockYAt(lx, lz)
                            // BUGFIX: getHighestBlockYAt uses MOTION_BLOCKING heightmap which ignores non-solid blocks like flowers!
                            // We offset by +3 to guarantee we scan the air/plants resting on top of the solid ground.
                            val startY = minOf(world.maxHeight - 1, highestY + 3)

                            var groundY = -1
                            var groundType = Material.AIR
                            var foundAutumnOverride = false

                            // Scan downwards to find the surface
                            for (y in startY downTo world.minHeight) {
                                val type = snapshot.getBlockType(lx, y, lz)
                                if (type.isAir) continue

                                // AUTUMN OVERRIDES: Intercept existing flowers and crops before hitting solid ground
                                if (currentSeason == Season.AUTUMN) {
                                    if (allFlowers.contains(type)) {
                                        if (Random.nextDouble() < cfg.autumnFlowerReplaceChance) {
                                            placements.add(PlantPlacement(world, globalX, globalZ, Material.LEAF_LITTER, GrowthMode.REPLACE_WITH_LEAF_LITTER, overrideY = y))
                                        }
                                        foundAutumnOverride = true
                                        break
                                    }
                                    if (crops.contains(type)) {
                                        if (Random.nextDouble() < cfg.autumnCropGrowthChance) {
                                            placements.add(PlantPlacement(world, globalX, globalZ, type, GrowthMode.AGE_CROP, overrideY = y))
                                        }
                                        foundAutumnOverride = true
                                        break
                                    }
                                }

                                if (type == Material.GRASS_BLOCK || type == Material.PODZOL || type == Material.DIRT || type == Material.SAND || type == Material.WATER) {
                                    groundY = y
                                    groundType = type
                                    break
                                }
                                if (type.isSolid && !type.name.contains("LEAVES") && !type.name.contains("LOG") && !type.name.contains("WOOD")) break
                            }

                            // If we already scheduled an autumn replacement/growth, skip generating a new plant here
                            if (foundAutumnOverride) continue
                            if (groundY == -1 || groundY >= world.maxHeight - 2) continue

                            val airBlockType = snapshot.getBlockType(lx, groundY + 1, lz)
                            if (!airBlockType.isAir) continue

                            val skyLight = snapshot.getBlockSkyLight(lx, groundY + 1, lz)
                            val roll = Random.nextDouble()

                            // Proximity water scan
                            var nearWater = false
                            val minWX = maxOf(0, lx - 4); val maxWX = minOf(15, lx + 4)
                            val minWZ = maxOf(0, lz - 4); val maxWZ = minOf(15, lz + 4)
                            waterSearch@ for (wx in minWX..maxWX) {
                                for (wz in minWZ..maxWZ) {
                                    for (wy in maxOf(world.minHeight, groundY - 2)..minOf(world.maxHeight - 1, groundY + 1)) {
                                        if (snapshot.getBlockType(wx, wy, wz) == Material.WATER) { nearWater = true; break@waterSearch }
                                    }
                                }
                            }

                            // Strict adjacent water scan
                            var adjacentWater = false
                            if (lx > 0 && snapshot.getBlockType(lx - 1, groundY, lz) == Material.WATER) adjacentWater = true
                            if (lx < 15 && snapshot.getBlockType(lx + 1, groundY, lz) == Material.WATER) adjacentWater = true
                            if (lz > 0 && snapshot.getBlockType(lx, groundY, lz - 1) == Material.WATER) adjacentWater = true
                            if (lz < 15 && snapshot.getBlockType(lx, groundY, lz + 1) == Material.WATER) adjacentWater = true

                            if (groundType == Material.WATER) {
                                if (currentSeason != Season.AUTUMN && skyLight >= 9 && roll < cfg.lilyPadChance) {
                                    placements.add(PlantPlacement(world, globalX, globalZ, Material.LILY_PAD, GrowthMode.NORMAL))
                                }
                                continue
                            }

                            if (groundType == Material.SAND) {
                                if (adjacentWater && roll < cfg.sugarCaneChance) {
                                    placements.add(PlantPlacement(world, globalX, globalZ, Material.SUGAR_CANE, GrowthMode.SUGAR_CANE))
                                } else if (!nearWater && roll < cfg.cactusChance && currentSeason != Season.AUTUMN) {
                                    var safe = true
                                    if (lx > 0 && snapshot.getBlockType(lx - 1, groundY + 1, lz).isSolid) safe = false
                                    if (lx < 15 && snapshot.getBlockType(lx + 1, groundY + 1, lz).isSolid) safe = false
                                    if (lz > 0 && snapshot.getBlockType(lx, groundY + 1, lz - 1).isSolid) safe = false
                                    if (lz < 15 && snapshot.getBlockType(lx, groundY + 1, lz + 1).isSolid) safe = false

                                    if (safe) placements.add(PlantPlacement(world, globalX, globalZ, Material.CACTUS, GrowthMode.CACTUS))
                                }
                                continue
                            }

                            val clusterMult = if (nearWater) cfg.waterProximityMultiplier else 1.0

                            if (currentSeason == Season.SPRING && groundType == Material.GRASS_BLOCK && roll < cfg.beeNestChance) {
                                for (hy in groundY + 3..groundY + 12) {
                                    val hType = snapshot.getBlockType(lx, hy, lz)
                                    if (hType == Material.OAK_LEAVES || hType == Material.BIRCH_LEAVES) {
                                        if (snapshot.getBlockType(lx, hy - 1, lz).isAir) {
                                            placements.add(PlantPlacement(world, globalX, globalZ, Material.BEE_NEST, GrowthMode.BEE_NEST, overrideY = hy - 1))
                                            break
                                        }
                                    }
                                }
                            }

                            if (currentSeason == Season.AUTUMN) {
                                if (roll < cfg.pumpkinChance) {
                                    val clusterSize = Random.nextInt(1, 4)
                                    for (c in 0 until clusterSize) {
                                        val cLx = (lx + Random.nextInt(-2, 3)).coerceIn(0, 15)
                                        val cLz = (lz + Random.nextInt(-2, 3)).coerceIn(0, 15)

                                        val pType = if (cLx in 1..14 && cLz in 1..14) {
                                            if (Random.nextDouble() < 0.05) Material.JACK_O_LANTERN
                                            else if (Random.nextDouble() < 0.15) Material.CARVED_PUMPKIN
                                            else Material.PUMPKIN
                                        } else {
                                            Material.PUMPKIN
                                        }

                                        placements.add(PlantPlacement(world, (snapshot.x shl 4) + cLx, (snapshot.z shl 4) + cLz, pType, GrowthMode.NORMAL))
                                    }
                                } else if (skyLight < 13 && roll < cfg.mushroomChance) {
                                    val mushroomType = if (Random.nextBoolean()) Material.RED_MUSHROOM else Material.BROWN_MUSHROOM
                                    placements.add(PlantPlacement(world, globalX, globalZ, mushroomType, GrowthMode.NORMAL))
                                } else if (adjacentWater && roll < cfg.sugarCaneChance) {
                                    placements.add(PlantPlacement(world, globalX, globalZ, Material.SUGAR_CANE, GrowthMode.SUGAR_CANE))
                                }
                            } else {
                                val hasLight = skyLight >= 9

                                when {
                                    adjacentWater && roll < cfg.sugarCaneChance -> {
                                        placements.add(PlantPlacement(world, globalX, globalZ, Material.SUGAR_CANE, GrowthMode.SUGAR_CANE))
                                    }
                                    hasLight && roll < cfg.treeChance -> {
                                        val forestTree = findNearbyTree(snapshot, lx, groundY, lz, world.maxHeight)
                                        if (forestTree != null) {
                                            if (cfg.instantTreeGrowth) placements.add(PlantPlacement(world, globalX, globalZ, Material.AIR, GrowthMode.TREE, getTreeType(forestTree)))
                                            else placements.add(PlantPlacement(world, globalX, globalZ, getSapling(forestTree), GrowthMode.NORMAL))
                                        }
                                    }
                                    hasLight && roll < (cfg.treeChance + cfg.cropChance) -> {
                                        val cropType = crops.random()
                                        val clusterSize = (Random.nextInt(cfg.minCropCluster, cfg.maxCropCluster + 1) * clusterMult).toInt()
                                        for (c in 0 until clusterSize) {
                                            val cLx = (lx + Random.nextInt(-2, 3)).coerceIn(0, 15)
                                            val cLz = (lz + Random.nextInt(-2, 3)).coerceIn(0, 15)
                                            placements.add(PlantPlacement(world, (snapshot.x shl 4) + cLx, (snapshot.z shl 4) + cLz, cropType, GrowthMode.CROP))
                                        }
                                    }
                                    hasLight && roll < (cfg.treeChance + cfg.cropChance + cfg.flowerChance) -> {
                                        placements.add(PlantPlacement(world, globalX, globalZ, flowers.random(), GrowthMode.NORMAL))
                                    }
                                    roll < (cfg.treeChance + cfg.cropChance + cfg.flowerChance + cfg.tallGrassChance) -> {
                                        val clusterSize = (Random.nextInt(cfg.minGrassCluster, cfg.maxGrassCluster + 1) * clusterMult).toInt()
                                        for (c in 0 until clusterSize) {
                                            val cLx = (lx + Random.nextInt(-2, 3)).coerceIn(0, 15)
                                            val cLz = (lz + Random.nextInt(-2, 3)).coerceIn(0, 15)
                                            placements.add(PlantPlacement(world, (snapshot.x shl 4) + cLx, (snapshot.z shl 4) + cLz, Material.TALL_GRASS, GrowthMode.TALL_PLANT))
                                        }
                                    }
                                    else -> {
                                        val clusterSize = (Random.nextInt(cfg.minGrassCluster, cfg.maxGrassCluster + 1) * clusterMult).toInt()
                                        for (c in 0 until clusterSize) {
                                            val cLx = (lx + Random.nextInt(-2, 3)).coerceIn(0, 15)
                                            val cLz = (lz + Random.nextInt(-2, 3)).coerceIn(0, 15)
                                            placements.add(PlantPlacement(world, (snapshot.x shl 4) + cLx, (snapshot.z shl 4) + cLz, Material.SHORT_GRASS, GrowthMode.NORMAL))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // [SYNCHRONOUS PHASE]
                    if (placements.isNotEmpty()) {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            for (p in placements) {
                                if (!p.world.isChunkLoaded(p.x shr 4, p.z shr 4)) continue

                                val actualY = p.overrideY ?: findGroundY(p.world, p.x, p.z)
                                if (actualY == -1) continue

                                // 1. AUTUMN MODIFICATIONS (Targets existing blocks)
                                if (p.mode == GrowthMode.REPLACE_WITH_LEAF_LITTER) {
                                    val block = p.world.getBlockAt(p.x, actualY, p.z)
                                    // Failsafe: make sure the flower wasn't already broken by a player
                                    if (allFlowers.contains(block.type)) {
                                        val blockData = block.blockData
                                        if (blockData is Bisected) {
                                            // Handle 2-tall flowers safely so they don't trigger physics updates and drop items
                                            if (blockData.half == Bisected.Half.TOP) {
                                                val bottom = block.getRelative(BlockFace.DOWN)
                                                block.setType(Material.AIR, false)
                                                if (allFlowers.contains(bottom.type)) bottom.setType(Material.LEAF_LITTER, false)
                                            } else {
                                                val top = block.getRelative(BlockFace.UP)
                                                if (allFlowers.contains(top.type)) top.setType(Material.AIR, false)
                                                block.setType(Material.LEAF_LITTER, false)
                                            }
                                        } else {
                                            block.setType(Material.LEAF_LITTER, false)
                                        }
                                    }
                                    continue
                                }

                                if (p.mode == GrowthMode.AGE_CROP) {
                                    val block = p.world.getBlockAt(p.x, actualY, p.z)
                                    val blockData = block.blockData as? Ageable
                                    if (blockData != null && crops.contains(block.type)) {
                                        // Massive harvest boost: jump 2 to 4 growth stages instantly
                                        blockData.age = minOf(blockData.maximumAge, blockData.age + Random.nextInt(2, 5))
                                        block.setBlockData(blockData, false)
                                    }
                                    continue
                                }

                                // 2. STANDARD PLACEMENTS (Targets empty air above ground)
                                val groundBlock = p.world.getBlockAt(p.x, actualY, p.z)
                                val airBlock = p.world.getBlockAt(p.x, actualY + 1, p.z)

                                if (p.mode == GrowthMode.BEE_NEST) {
                                    val nestBlock = p.world.getBlockAt(p.x, actualY, p.z)
                                    val leafBlock = nestBlock.getRelative(BlockFace.UP)
                                    if (nestBlock.type.isAir && (leafBlock.type == Material.OAK_LEAVES || leafBlock.type == Material.BIRCH_LEAVES)) {
                                        nestBlock.setType(Material.BEE_NEST, false)
                                        val blockData = nestBlock.blockData as Beehive
                                        blockData.facing = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST).random()
                                        nestBlock.blockData = blockData
                                    }
                                    continue
                                }

                                val validGroundTypes = listOf(Material.GRASS_BLOCK, Material.PODZOL, Material.SAND, Material.DIRT, Material.WATER)
                                if (validGroundTypes.contains(groundBlock.type) && airBlock.type.isAir) {
                                    when (p.mode) {
                                        GrowthMode.CACTUS -> {
                                            if (groundBlock.type == Material.SAND) {
                                                val height = Random.nextInt(1, 4)
                                                for (dy in 0 until height) {
                                                    val current = p.world.getBlockAt(p.x, actualY + 1 + dy, p.z)
                                                    var currentSafe = true
                                                    for (face in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                                                        if (current.getRelative(face).type.isSolid) currentSafe = false
                                                    }
                                                    if (current.type.isAir && currentSafe) current.setType(Material.CACTUS, false)
                                                    else break
                                                }
                                            }
                                        }
                                        GrowthMode.SUGAR_CANE -> {
                                            if (groundBlock.type == Material.SAND || groundBlock.type == Material.DIRT || groundBlock.type == Material.GRASS_BLOCK) {
                                                val height = Random.nextInt(1, 4)
                                                for (dy in 0 until height) {
                                                    val current = p.world.getBlockAt(p.x, actualY + 1 + dy, p.z)
                                                    if (current.type.isAir) current.setType(Material.SUGAR_CANE, false)
                                                    else break
                                                }
                                            }
                                        }
                                        GrowthMode.TREE -> {
                                            p.treeType?.let { treeType ->
                                                p.world.generateTree(Location(p.world, p.x.toDouble(), actualY.toDouble() + 1, p.z.toDouble()), treeType)
                                            }
                                        }
                                        GrowthMode.CROP -> {
                                            if (groundBlock.type == Material.GRASS_BLOCK) {
                                                groundBlock.setType(Material.FARMLAND, false)
                                                airBlock.setType(p.type, false)
                                                val blockData = airBlock.blockData as? Ageable
                                                if (blockData != null) {
                                                    val maxWildAge = maxOf(1, blockData.maximumAge / 2)
                                                    blockData.age = Random.nextInt(0, maxWildAge + 1)
                                                    airBlock.blockData = blockData
                                                }
                                            }
                                        }
                                        GrowthMode.TALL_PLANT -> {
                                            val upperAir = p.world.getBlockAt(p.x, actualY + 2, p.z)
                                            if (upperAir.type.isAir) {
                                                airBlock.setType(p.type, false)
                                                val bottomData = airBlock.blockData as? Bisected
                                                if (bottomData != null) {
                                                    bottomData.half = Bisected.Half.BOTTOM
                                                    airBlock.blockData = bottomData
                                                }
                                                upperAir.setType(p.type, false)
                                                val topData = upperAir.blockData as? Bisected
                                                if (topData != null) {
                                                    topData.half = Bisected.Half.TOP
                                                    upperAir.blockData = topData
                                                }
                                            }
                                        }
                                        GrowthMode.NORMAL -> {
                                            if (p.type == Material.RED_MUSHROOM || p.type == Material.BROWN_MUSHROOM) {
                                                if (groundBlock.type == Material.PODZOL || airBlock.lightLevel < 13) airBlock.setType(p.type, false)
                                            } else {
                                                airBlock.setType(p.type, false)
                                            }
                                        }
                                        else -> {}
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

    private fun findNearbyTree(snapshot: ChunkSnapshot, startX: Int, startY: Int, startZ: Int, maxHeight: Int): ForestTree? {
        val radius = 6
        var closestTree: ForestTree? = null
        var minDistanceSq = Double.MAX_VALUE

        for (x in maxOf(0, startX - radius)..minOf(15, startX + radius)) {
            for (z in maxOf(0, startZ - radius)..minOf(15, startZ + radius)) {
                for (y in startY..minOf(maxHeight - 1, startY + 12)) {
                    val mat = snapshot.getBlockType(x, y, z)
                    if (mat.name.endsWith("_LOG") || mat.name.endsWith("_LEAVES")) {
                        val tree = mapBlockToTree(mat)
                        if (tree != null) {
                            val distSq = ((x - startX) * (x - startX) + (y - startY) * (y - startY) + (z - startZ) * (z - startZ)).toDouble()
                            if (distSq < minDistanceSq) { minDistanceSq = distSq; closestTree = tree }
                        }
                    }
                }
            }
        }
        return closestTree
    }

    private fun findGroundY(world: World, x: Int, z: Int): Int {
        val highestY = world.getHighestBlockYAt(x, z)
        for (y in highestY downTo world.minHeight) {
            val type = world.getBlockAt(x, y, z).type
            if (type == Material.GRASS_BLOCK || type == Material.PODZOL || type == Material.SAND || type == Material.DIRT || type == Material.WATER) return y
            if (type.isSolid && !type.name.contains("LEAVES") && !type.name.contains("LOG") && !type.name.contains("WOOD")) break
        }
        return -1
    }

    private fun mapBlockToTree(mat: Material): ForestTree? {
        return when {
            mat.name.startsWith("OAK_") -> ForestTree.OAK
            mat.name.startsWith("BIRCH_") -> ForestTree.BIRCH
            mat.name.startsWith("SPRUCE_") -> ForestTree.SPRUCE
            mat.name.startsWith("JUNGLE_") -> ForestTree.JUNGLE
            mat.name.startsWith("ACACIA_") -> ForestTree.ACACIA
            mat.name.startsWith("DARK_OAK_") -> ForestTree.DARK_OAK
            mat.name.startsWith("MANGROVE_") -> ForestTree.MANGROVE
            mat.name.startsWith("CHERRY_") -> ForestTree.CHERRY
            else -> null
        }
    }

    private fun getSapling(tree: ForestTree): Material {
        return when (tree) {
            ForestTree.OAK -> Material.OAK_SAPLING
            ForestTree.BIRCH -> Material.BIRCH_SAPLING
            ForestTree.SPRUCE -> Material.SPRUCE_SAPLING
            ForestTree.JUNGLE -> Material.JUNGLE_SAPLING
            ForestTree.ACACIA -> Material.ACACIA_SAPLING
            ForestTree.DARK_OAK -> Material.DARK_OAK_SAPLING
            ForestTree.MANGROVE -> Material.MANGROVE_PROPAGULE
            ForestTree.CHERRY -> Material.CHERRY_SAPLING
        }
    }

    private fun getTreeType(tree: ForestTree): TreeType {
        return when (tree) {
            ForestTree.OAK -> TreeType.TREE
            ForestTree.BIRCH -> TreeType.BIRCH
            ForestTree.SPRUCE -> TreeType.REDWOOD
            ForestTree.JUNGLE -> TreeType.JUNGLE
            ForestTree.ACACIA -> TreeType.ACACIA
            ForestTree.DARK_OAK -> TreeType.DARK_OAK
            ForestTree.MANGROVE -> TreeType.MANGROVE
            ForestTree.CHERRY -> TreeType.CHERRY
        }
    }

    fun stop() {
        task?.cancel()
        task = null
    }
}