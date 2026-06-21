package vx.seasons.gameplay.feature.environment.forest

import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.TreeType
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.block.data.FaceAttachable
import org.bukkit.block.data.MultipleFacing
import org.bukkit.block.data.type.Leaves
import org.bukkit.block.data.type.Switch
import org.bukkit.block.data.type.Wall
import org.bukkit.entity.EntityType
import org.bukkit.entity.Marker
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkPopulateEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.profile.PlayerProfile
import org.bukkit.scheduler.BukkitRunnable
import vx.seasons.SeasonsPlugin.Companion.gson
import vx.seasons.SeasonsPlugin.Companion.plugin
import vx.seasons.config.GameplayConfiguration
import vx.seasons.season.Season
import java.io.File
import java.net.URL
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

object DynamicForestFeature : Listener {

    private val TREE_STAGE_KEY = NamespacedKey(plugin, "df_stage")
    private val TREE_BASE_X = NamespacedKey(plugin, "df_base_x")
    private val TREE_BASE_Y = NamespacedKey(plugin, "df_base_y")
    private val TREE_BASE_Z = NamespacedKey(plugin, "df_base_z")
    private val TREE_HEIGHT_KEY = NamespacedKey(plugin, "df_height")
    private val TREE_BP_ID_KEY = NamespacedKey(plugin, "df_bp_id")
    private val TREE_VAR_IDX_KEY = NamespacedKey(plugin, "df_var_idx")
    private val PROG_KEY = NamespacedKey(plugin, "df_prog")

    private val REPLACED_TAG = NamespacedKey(plugin, "df_chunk_replaced")

    private val HORIZONTAL_FACES = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
    private val ALL_FACES = HORIZONTAL_FACES + listOf(BlockFace.UP, BlockFace.DOWN)

    private val SOIL_BLOCKS = setOf(
        Material.DIRT, Material.GRASS_BLOCK, Material.PODZOL,
        Material.MOSS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT,
        Material.MYCELIUM, Material.DIRT_PATH, Material.FARMLAND
    )

    private val ROOT_REPLACEABLE = SOIL_BLOCKS + setOf(
        Material.STONE, Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.DEEPSLATE, Material.TUFF, Material.SNOW_BLOCK, Material.SNOW
    )

    data class ChunkLocation(val world: String, val x: Int, val z: Int)
    data class TreeData(val x: Int, val y: Int, val z: Int, val logMat: Material, val bpId: String)
    data class PendingChunkTask(val world: String, val trees: List<TreeData>) {
        var deleteIndex = 0
        var spawnIndex = 0
    }

    private val chunkScanQueue = ConcurrentLinkedQueue<ChunkLocation>()
    private val chunkTaskQueue = ConcurrentLinkedQueue<PendingChunkTask>()

    private var masterWorker: BukkitRunnable? = null
    private var treeGrowthWorker: BukkitRunnable? = null

    private val treePool = ConcurrentHashMap<String, List<TreeStructureData>>()
    private val skullProfileCache = ConcurrentHashMap<String, PlayerProfile>()

    private val config: GameplayConfiguration.DynamicForestConfig
        get() = plugin.gameplayManager.config.dynamicForest

    data class CanopyData(val bpId: String, val cx: Int, val minY: Int, val maxY: Int, val cz: Int, val radiusSq: Int) {
        fun contains(x: Int, y: Int, z: Int): Boolean {
            if (y !in minY..maxY) return false
            val dx = x - cx
            val dz = z - cz
            return (dx * dx + dz * dz) <= radiusSq
        }
    }

    val canopyCache = ConcurrentHashMap<Long, CopyOnWriteArrayList<CanopyData>>()

    fun getChunkKey(worldName: String, x: Int, z: Int): Long {
        return (worldName.hashCode().toLong() shl 32) or ((x.toLong() and 0xFFFF) shl 16) or (z.toLong() and 0xFFFF)
    }

    private fun addCanopy(worldName: String, bpId: String, baseX: Int, baseY: Int, baseZ: Int, height: Int, radius: Int, startHeightPercent: Double) {
        val minY = baseY + (height * startHeightPercent).toInt() - 1
        val maxY = baseY + height + 3
        val radiusSq = (radius + 1) * (radius + 1)
        val canopy = CanopyData(bpId, baseX, minY, maxY, baseZ, radiusSq)

        val minCX = (baseX - radius - 1) shr 4
        val maxCX = (baseX + radius + 1) shr 4
        val minCZ = (baseZ - radius - 1) shr 4
        val maxCZ = (baseZ + radius + 1) shr 4

        for (cx in minCX..maxCX) {
            for (cz in minCZ..maxCZ) {
                val key = getChunkKey(worldName, cx, cz)
                canopyCache.computeIfAbsent(key) { CopyOnWriteArrayList() }.add(canopy)
            }
        }
    }

    enum class TreeStage { GROWING, THICKENING, EXPANDING, HARDENING, MATURE }

    class PrecomputedBlock(val dx: Byte, val dy: Byte, val dz: Byte, val mat: Material, val ext: String? = null)

    private fun packCoord(dx: Int, dy: Int, dz: Int): Int {
        return ((dx + 128) shl 16) or ((dy + 128) shl 8) or (dz + 128)
    }

    class TreeStructureData(
        val height: Int,
        val growPhase: List<PrecomputedBlock>,
        val thickenPhase: List<PrecomputedBlock>,
        val expandPhase: List<PrecomputedBlock>,
        val hardenPhase: List<PrecomputedBlock>,
        val occupiedSpace: Set<Int>
    )

    data class Range(val min: Int, val max: Int) { fun random() = Random.nextInt(min, max + 1) }
    data class DecorationRule(val buttonChance: Double = 0.05, val trapdoorChance: Double = 0.05)
    data class TrunkRule(val thickChance: Double = 0.0, val bottomMaterial: String, val bottomMaxHeight: Int, val middleMaterial: String, val middleHeightPercent: Double, val topMaterial: String, val bendChance: Double = 0.15, val stairsMaterial: String, val decorations: DecorationRule? = DecorationRule())
    data class RootRule(val material: String, val chance: Double, val maxDepth: Int)
    data class BranchRule(val count: Range, val length: Range, val startHeightPercent: Double, val knotChance: Double, val bareChance: Double? = 0.0, val bareMaterial: String? = null, val curveUpChance: Double? = 0.8, val curveDownChance: Double? = 0.0, val thickBaseLength: Int? = 1)
    data class LeafRule(val materials: List<String>, val radius: Int, val density: Double, val startHeightPercent: Double, val shapeFocusY: Double, val shape: String = "OVAL", val vinesChance: Double = 0.0, val seasonalColors: Map<String, String>? = null)
    data class FruitRule(val base64: String, val dropMaterial: String, val spawnChance: Double, val dropChance: Double, val attachTo: String = "LEAVES")

    data class TreeBlueprint(val baseMaterial: String, val height: Range, val maxStairs: Range = Range(0, 3), val trunk: TrunkRule, val roots: RootRule? = null, val branches: BranchRule, val leaves: LeafRule, val fruits: List<FruitRule> = emptyList()) {
        fun getLeafMaterials(): List<Material> = leaves.materials.mapNotNull { Material.getMaterial(it) }
        fun getRandomLeaf(): Material = getLeafMaterials().randomOrNull() ?: Material.OAK_LEAVES
        fun getRootsRule(): RootRule = roots ?: RootRule(trunk.bottomMaterial, 0.4, 3)
        fun getDecorations(): DecorationRule = trunk.decorations ?: DecorationRule(0.0, 0.0)
    }

    val blueprints = mutableMapOf<String, TreeBlueprint>()
    private val fruitMap = mutableMapOf<String, FruitRule>()

    init {
        if (config.enabled) {
            loadBlueprints()
            start()
        }
    }

    private fun getUrlFromBase64(base64: String): String? {
        return try {
            val decoded = String(Base64.getDecoder().decode(base64))
            decoded.substringAfter("\"url\":\"").substringBefore("\"")
        } catch (e: Exception) { null }
    }

    private fun loadBlueprints() {
        val blueprintsDir = File(plugin.dataFolder, "environment/forest/blueprints")
        if (!blueprintsDir.exists()) blueprintsDir.mkdirs()

        val files = blueprintsDir.listFiles { _, name -> name.endsWith(".json") }
        if (files == null || files.isEmpty()) {

            val defaultBirch = TreeBlueprint("PALE_OAK_FENCE", Range(16, 21), Range(0, 2),
                TrunkRule(0.0, "BIRCH_WOOD", 2, "DIORITE_WALL", 0.4, "PALE_OAK_FENCE", 0.02, "ANDESITE_STAIRS", DecorationRule(0.02, 0.01)),
                RootRule("BIRCH_WOOD", 0.3, 3),
                BranchRule(Range(2, 5), Range(1, 3), 0.45, 0.08, 0.0, "BIRCH", 0.9, 0.0, 1),
                LeafRule(listOf("OAK_LEAVES"), 3, 0.55, 0.4, 0.8, "OVAL", 0.0, mapOf("autumn" to "#eab308")))

            val appleBase64 = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMTdlYTI3OGQ2MjI1YzQ0N2M1OTQzZDY1Mjc5OGQwYmJiZDE0MTg0MzRjZThjNTRjNTRmZGFjNzk5OTRkZGQ2YyJ9fX0="
            val goldenAppleBase64 = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTkyZWFhY2QyOTBlYWQzN2ViMWEyMDJhYzczNjdmMzJiZTc0Y2Y0YWM3NzIzZTA2N2M0NjU4YmY2MmMzZGJkNiJ9fX0="
            val cocoaBase64 = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjExNmI5ZDhkZjM0NmEyNWVkZDA1Zjg0MmU3YTkzNDViZWFmMTZkY2E0MTE4YWJmNWE2OGM3NWJjYWFlMTAifX19"

            val defaultOak = TreeBlueprint("SPRUCE_FENCE", Range(18, 26), Range(0, 3),
                TrunkRule(0.05, "OAK_WOOD", 3, "MUD_BRICK_WALL", 0.45, "SPRUCE_FENCE", 0.15, "SPRUCE_STAIRS", DecorationRule(0.05, 0.04)),
                RootRule("OAK_WOOD", 0.5, 4),
                BranchRule(Range(4, 7), Range(2, 4), 0.35, 0.15, 0.15, "SPRUCE", 0.6, 0.1, 1),
                LeafRule(listOf("OAK_LEAVES"), 4, 0.60, 0.35, 0.0, "OVAL", 0.0), listOf(
                    FruitRule(appleBase64, "APPLE", 0.015, 0.6, "LEAVES"), FruitRule(goldenAppleBase64, "GOLDEN_APPLE", 0.001, 1.0, "LEAVES")
                ))

            val defaultSpruce = TreeBlueprint("DARK_OAK_FENCE", Range(20, 27), Range(0, 1),
                TrunkRule(0.15, "SPRUCE_WOOD", 3, "NETHER_BRICK_WALL", 0.5, "DARK_OAK_FENCE", 0.05, "SPRUCE_STAIRS", DecorationRule(0.03, 0.08)),
                RootRule("SPRUCE_WOOD", 0.4, 3),
                BranchRule(Range(3, 6), Range(1, 2), 0.35, 0.1, 0.0, "DARK_OAK", 0.2, 0.4, 1),
                LeafRule(listOf("OAK_LEAVES"), 4, 0.75, 0.3, 0.0, "CONE", 0.0, mapOf("spring" to "#3b593b", "summer" to "#3b593b", "autumn" to "#3b593b", "winter" to "#537053")))

            val defaultAcacia = TreeBlueprint("ACACIA_FENCE", Range(17, 24), Range(0, 2),
                TrunkRule(0.0, "ACACIA_WOOD", 2, "TUFF_WALL", 0.55, "ACACIA_FENCE", 0.25, "TUFF_STAIRS", DecorationRule(0.01, 0.02)),
                RootRule("ACACIA_WOOD", 0.2, 2),
                BranchRule(Range(3, 6), Range(3, 5), 0.4, 0.15, 0.0, "ACACIA", 0.3, 0.0, 2),
                LeafRule(listOf("OAK_LEAVES"), 4, 0.65, 0.6, 0.0, "FLAT", 0.0))

            val defaultJungle = TreeBlueprint("JUNGLE_FENCE", Range(20, 32), Range(0, 0),
                TrunkRule(0.4, "JUNGLE_WOOD", 6, "JUNGLE_LOG", 0.6, "JUNGLE_FENCE", 0.05, "JUNGLE_STAIRS", DecorationRule(0.06, 0.03)),
                RootRule("JUNGLE_WOOD", 0.7, 4),
                BranchRule(Range(4, 7), Range(3, 6), 0.5, 0.1, 0.2, "JUNGLE", 0.7, 0.2, 2),
                LeafRule(listOf("JUNGLE_LEAVES"), 4, 0.45, 0.55, 0.2, "OVAL", 0.15), listOf(
                    FruitRule(cocoaBase64, "COCOA_BEANS", 0.08, 0.8, "TRUNK")
                ))

            val defaultDarkOak = TreeBlueprint("DARK_OAK_FENCE", Range(18, 22), Range(0, 0),
                TrunkRule(0.3, "DARK_OAK_WOOD", 4, "DARK_OAK_LOG", 0.5, "DARK_OAK_FENCE", 0.1, "DARK_OAK_STAIRS", DecorationRule(0.05, 0.05)),
                RootRule("DARK_OAK_WOOD", 0.6, 4),
                BranchRule(Range(4, 8), Range(3, 5), 0.4, 0.1, 0.15, "DARK_OAK", 0.5, 0.1, 1),
                LeafRule(listOf("OAK_LEAVES"), 3, 0.55, 0.4, 0.4, "OVAL", 0.0))

            val defaultCherry = TreeBlueprint("CHERRY_FENCE", Range(15, 20), Range(0, 2),
                TrunkRule(0.0, "CHERRY_WOOD", 2, "NETHER_BRICK_WALL", 0.4, "CHERRY_FENCE", 0.1, "CHERRY_STAIRS", DecorationRule(0.0, 0.0)),
                RootRule("CHERRY_WOOD", 0.2, 2),
                BranchRule(Range(3, 5), Range(2, 4), 0.4, 0.1, 0.0, "CHERRY", 0.5, 0.1, 1),
                LeafRule(listOf("OAK_LEAVES"), 4, 0.65, 0.4, 0.0, "FLAT", 0.0, mapOf("spring" to "#ffb7c5", "summer" to "#76b057", "autumn" to "#d46a43")))

            File(blueprintsDir, "birch.json").writeText(gson.toJson(defaultBirch))
            File(blueprintsDir, "oak.json").writeText(gson.toJson(defaultOak))
            File(blueprintsDir, "spruce.json").writeText(gson.toJson(defaultSpruce))
            File(blueprintsDir, "acacia.json").writeText(gson.toJson(defaultAcacia))
            File(blueprintsDir, "jungle.json").writeText(gson.toJson(defaultJungle))
            File(blueprintsDir, "dark_oak.json").writeText(gson.toJson(defaultDarkOak))
            File(blueprintsDir, "cherry.json").writeText(gson.toJson(defaultCherry))
        }

        blueprints.clear()
        fruitMap.clear()
        skullProfileCache.clear()

        blueprintsDir.listFiles { _, name -> name.endsWith(".json") }?.forEach { file ->
            try {
                val bp = gson.fromJson(file.readText(), TreeBlueprint::class.java)
                blueprints[file.nameWithoutExtension] = bp
                bp.fruits.forEach { fruit ->
                    val urlStr = getUrlFromBase64(fruit.base64)
                    if (urlStr != null) {
                        fruitMap[urlStr] = fruit
                        try {
                            val profile = Bukkit.createPlayerProfile(UUID.randomUUID())
                            val textures = profile.textures
                            textures.skin = URL(urlStr)
                            profile.setTextures(textures)
                            skullProfileCache[fruit.base64] = profile
                        } catch (e: Exception) {}
                    }
                }
            }
            catch (e: Exception) { plugin.logger.warning("Failed to load blueprint from ${file.name}: ${e.message}") }
        }
    }

    private fun start() {
        stop()
        if (!config.enabled) return

        val pregenAmount = config.treePoolSize

        plugin.logger.info("Pre-generating tree pool ($pregenAmount per blueprint)...")
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val start = System.currentTimeMillis()
            blueprints.forEach { (id, bp) ->
                val list = mutableListOf<TreeStructureData>()
                for (i in 0 until pregenAmount) {
                    list.add(generateTreeStructure(bp))
                }
                treePool[id] = list
            }
            plugin.logger.info("Successfully pre-calculated ${blueprints.size * pregenAmount} tree schematics in RAM (${System.currentTimeMillis() - start}ms).")
        })

        masterWorker = object : BukkitRunnable() {
            var lastTick = System.currentTimeMillis()
            var tps = 20.0
            var currentTask: PendingChunkTask? = null
            var currentOpLimit = 1.0

            override fun run() {
                if (!config.enabled) return

                val now = System.currentTimeMillis()
                val delta = now - lastTick
                val currentTps = if (delta > 0) 1000.0 / delta else 20.0
                tps = (tps * 0.9) + (currentTps * 0.1)
                lastTick = now

                val maxOps = config.maxOperationsPerTick.toDouble()

                if (tps >= 19.5) {
                    currentOpLimit = minOf(maxOps, currentOpLimit + 0.5)
                } else if (tps < 18.5) {
                    currentOpLimit = maxOf(1.0, currentOpLimit * 0.5)
                }

                val opLimit = currentOpLimit.toInt()
                val snapshotLimit = if (opLimit > 0) 1 else 0

                for (i in 0 until snapshotLimit) {
                    val loc = chunkScanQueue.poll() ?: break
                    val w = Bukkit.getWorld(loc.world) ?: continue
                    if (w.isChunkLoaded(loc.x, loc.z)) {
                        val chunk = w.getChunkAt(loc.x, loc.z)
                        val snapshot = chunk.chunkSnapshot
                        scanChunkAsync(loc.world, snapshot)
                    }
                }

                var operationsDone = 0
                while (operationsDone < opLimit) {
                    val task = currentTask ?: chunkTaskQueue.poll() ?: break
                    currentTask = task

                    val world = Bukkit.getWorld(task.world)
                    if (world == null) {
                        currentTask = null
                        continue
                    }

                    if (task.deleteIndex < task.trees.size) {
                        val tree = task.trees[task.deleteIndex++]
                        val block = world.getBlockAt(tree.x, tree.y, tree.z)

                        if (block.chunk.isLoaded && block.type == tree.logMat) {
                            val leavesMats = getLeavesMatsFor(tree.logMat)
                            removeVanillaTree(block, tree.logMat, leavesMats)
                        }
                        operationsDone++
                    }
                    else if (task.spawnIndex < task.trees.size) {
                        val tree = task.trees[task.spawnIndex++]
                        val block = world.getBlockAt(tree.x, tree.y, tree.z)

                        if (block.chunk.isLoaded) {
                            val marker = spawnDynamicTreeBase(block, tree.bpId)
                            if (marker != null) {
                                val percent = Random.nextDouble(0.92, 1.00)
                                fastForwardTree(marker, percent)
                            }
                        }
                        operationsDone++
                    }
                    else {
                        currentTask = null
                    }
                }
            }
        }
        masterWorker?.runTaskTimer(plugin, 1L, 1L)

        treeGrowthWorker = object : BukkitRunnable() {
            override fun run() {
                if (!config.enabled) return

                val worlds = Bukkit.getWorlds().filter { it.name in plugin.gameplayManager.allowedWorlds }
                for (world in worlds) {
                    val loadedChunks = world.loadedChunks.toList()
                    if (loadedChunks.isEmpty()) continue

                    val season = plugin.seasonManager.currentSeason
                    for (chunk in loadedChunks.shuffled().take(config.maxChunksProcessedPerGrowthTick)) {
                        for (entity in chunk.entities) {
                            if (entity !is Marker || !entity.persistentDataContainer.has(TREE_STAGE_KEY, PersistentDataType.STRING)) continue

                            val pdc = entity.persistentDataContainer
                            val stage = TreeStage.valueOf(pdc.get(TREE_STAGE_KEY, PersistentDataType.STRING) ?: TreeStage.GROWING.name)
                            val bx = pdc.get(TREE_BASE_X, PersistentDataType.INTEGER) ?: entity.location.blockX
                            val by = pdc.get(TREE_BASE_Y, PersistentDataType.INTEGER) ?: entity.location.blockY
                            val bz = pdc.get(TREE_BASE_Z, PersistentDataType.INTEGER) ?: entity.location.blockZ

                            processTreeGrowth(entity, stage, season, bx, by, bz, 0)

                            if (stage == TreeStage.MATURE) {
                                if (season != Season.WINTER) {
                                    animateLeaves(world, bx, by, bz, pdc.get(TREE_HEIGHT_KEY, PersistentDataType.INTEGER) ?: 16, pdc.get(TREE_BP_ID_KEY, PersistentDataType.STRING) ?: "birch")
                                }
                                if (season == Season.AUTUMN && Random.nextDouble() < config.autumnFruitDropChance) {
                                    dropRandomFruit(world, entity, bx, by, bz)
                                }
                            }
                        }
                    }
                }
            }
        }
        treeGrowthWorker?.runTaskTimer(plugin, config.growthTaskDelay, config.growthTaskPeriod)
    }

    private fun getStructData(marker: Marker): TreeStructureData? {
        val pdc = marker.persistentDataContainer
        val bpId = pdc.get(TREE_BP_ID_KEY, PersistentDataType.STRING) ?: return null
        val varIdx = pdc.get(TREE_VAR_IDX_KEY, PersistentDataType.INTEGER) ?: 0
        return treePool[bpId]?.getOrNull(varIdx)
    }

    private fun dropRandomFruit(world: org.bukkit.World, marker: Marker, bx: Int, by: Int, bz: Int) {
        val struct = getStructData(marker) ?: return
        val fruits = struct.expandPhase.filter { it.ext != null && it.ext.startsWith("fruit:") }
        if (fruits.isEmpty()) return

        val target = fruits.random()
        val block = world.getBlockAt(bx + target.dx, by + target.dy, bz + target.dz)
        if (block.type == Material.PLAYER_HEAD || block.type == Material.PLAYER_WALL_HEAD) {
            val state = block.state as? org.bukkit.block.Skull ?: return
            val profile = state.ownerProfile ?: return
            val skinUrl = profile.textures.skin?.toString() ?: return
            val fruitRule = fruitMap[skinUrl] ?: return

            block.setType(Material.AIR, false)
            Material.getMaterial(fruitRule.dropMaterial)?.let {
                world.dropItemNaturally(block.location.add(0.5, 0.2, 0.5), ItemStack(it))
            }
        }
    }

    private fun getTreeMarkerForBlock(block: Block): Marker? {
        val markers = block.world.getNearbyEntities(block.location, 24.0, 40.0, 24.0)
            .filterIsInstance<Marker>()
            .filter { it.persistentDataContainer.has(TREE_STAGE_KEY, PersistentDataType.STRING) }

        return markers.firstOrNull { marker ->
            val pdc = marker.persistentDataContainer
            val bx = pdc.get(TREE_BASE_X, PersistentDataType.INTEGER) ?: return@firstOrNull false
            val by = pdc.get(TREE_BASE_Y, PersistentDataType.INTEGER) ?: return@firstOrNull false
            val bz = pdc.get(TREE_BASE_Z, PersistentDataType.INTEGER) ?: return@firstOrNull false

            val dx = block.x - bx; val dy = block.y - by; val dz = block.z - bz

            val packed = packCoord(dx, dy, dz)
            val struct = getStructData(marker)
            struct?.occupiedSpace?.contains(packed) == true
        }
    }

    private fun processTreeGrowth(marker: Marker, stage: TreeStage, season: Season, bx: Int, by: Int, bz: Int, forcedSteps: Int) {
        if (season == Season.WINTER && stage != TreeStage.MATURE && forcedSteps == 0) return

        val struct = getStructData(marker) ?: return
        val pdc = marker.persistentDataContainer
        val world = marker.world
        var prog = pdc.get(PROG_KEY, PersistentDataType.INTEGER) ?: 0

        val (phaseList, defaultBlocksPerTick, nextStage) = when (stage) {
            TreeStage.GROWING -> Triple(struct.growPhase, 3, TreeStage.THICKENING)
            TreeStage.THICKENING -> Triple(struct.thickenPhase, 2, TreeStage.EXPANDING)
            TreeStage.EXPANDING -> Triple(struct.expandPhase, 4, TreeStage.HARDENING)
            TreeStage.HARDENING -> Triple(struct.hardenPhase, 2, TreeStage.MATURE)
            TreeStage.MATURE -> return
        }

        val blocksToPlace = if (forcedSteps > 0) forcedSteps else defaultBlocksPerTick

        for (i in 0 until blocksToPlace) {
            if (prog < phaseList.size) {
                val bp = phaseList[prog]
                val block = world.getBlockAt(bx + bp.dx, by + bp.dy, bz + bp.dz)

                if (bp.mat == Material.AIR) {
                    if (block.type.name.contains("LEAVES") || block.type == Material.SHORT_GRASS) {
                        block.setType(Material.AIR, false)
                    }
                } else {
                    val isWood = bp.mat.name.contains("LOG") || bp.mat.name.contains("WOOD") || bp.mat.name.contains("WALL") || bp.mat.name.contains("FENCE") || bp.mat.name.contains("BUTTON") || bp.mat.name.contains("STAIRS") || bp.mat.name.contains("TRAPDOOR")
                    val isRoot = bp.dy < 0

                    if (block.type == Material.AIR || block.type.name.contains("LEAVES") || block.type == Material.VINE || isWood || (isRoot && block.type in ROOT_REPLACEABLE) || block.type == Material.SHORT_GRASS) {
                        block.setType(bp.mat, false)
                        setupBlockData(block, bp.ext ?: "")
                    }
                }
                prog++
            } else break
        }

        if (prog >= phaseList.size) {
            pdc.set(TREE_STAGE_KEY, PersistentDataType.STRING, nextStage.name)
            pdc.set(PROG_KEY, PersistentDataType.INTEGER, 0)
        } else pdc.set(PROG_KEY, PersistentDataType.INTEGER, prog)
    }

    private fun fastForwardTree(marker: Marker, percent: Double) {
        val struct = getStructData(marker) ?: return
        val pdc = marker.persistentDataContainer

        val total = struct.growPhase.size + struct.thickenPhase.size + struct.expandPhase.size + struct.hardenPhase.size
        var stepsLeft = (total * percent).toInt()

        val bx = pdc.get(TREE_BASE_X, PersistentDataType.INTEGER) ?: marker.location.blockX
        val by = pdc.get(TREE_BASE_Y, PersistentDataType.INTEGER) ?: marker.location.blockY
        val bz = pdc.get(TREE_BASE_Z, PersistentDataType.INTEGER) ?: marker.location.blockZ

        while (stepsLeft > 0) {
            val stageStr = pdc.get(TREE_STAGE_KEY, PersistentDataType.STRING) ?: break
            if (stageStr == TreeStage.MATURE.name) break

            val stage = TreeStage.valueOf(stageStr)
            val list = when (stage) {
                TreeStage.GROWING -> struct.growPhase
                TreeStage.THICKENING -> struct.thickenPhase
                TreeStage.EXPANDING -> struct.expandPhase
                TreeStage.HARDENING -> struct.hardenPhase
                else -> emptyList()
            }

            val prog = pdc.get(PROG_KEY, PersistentDataType.INTEGER) ?: 0
            val remainingInPhase = list.size - prog

            if (remainingInPhase <= 0) {
                val nextStage = when (stage) {
                    TreeStage.GROWING -> TreeStage.THICKENING
                    TreeStage.THICKENING -> TreeStage.EXPANDING
                    TreeStage.EXPANDING -> TreeStage.HARDENING
                    TreeStage.HARDENING -> TreeStage.MATURE
                    TreeStage.MATURE -> TreeStage.MATURE
                }
                pdc.set(TREE_STAGE_KEY, PersistentDataType.STRING, nextStage.name)
                pdc.set(PROG_KEY, PersistentDataType.INTEGER, 0)
                continue
            }

            val step = minOf(stepsLeft, remainingInPhase)
            processTreeGrowth(marker, stage, Season.SPRING, bx, by, bz, step)
            stepsLeft -= step
        }
    }

    private fun spawnGrassAroundTree(baseBlock: Block) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!config.enabled) return@Runnable
            if (!baseBlock.chunk.isLoaded) return@Runnable

            val world = baseBlock.world
            for (dx in -3..3) {
                for (dz in -3..3) {
                    if (dx * dx + dz * dz <= 9 && Random.nextDouble() < config.grassSpawnChance) {
                        val targetBlock = world.getHighestBlockAt(baseBlock.x + dx, baseBlock.z + dz)
                        if (Math.abs(targetBlock.y - baseBlock.y) > 3) continue

                        val below = targetBlock.getRelative(BlockFace.DOWN)
                        if (below.type in SOIL_BLOCKS) {
                            if (targetBlock.type.isAir) {
                                targetBlock.setType(if (Random.nextDouble() < config.tallGrassChance) Material.TALL_GRASS else Material.SHORT_GRASS, false)
                            }
                        }
                    }
                }
            }
        }, config.grassSpawnDelayTicks)
    }

    private fun spawnDynamicTreeBase(baseBlock: Block, blueprintId: String): Marker? {
        val blueprint = blueprints[blueprintId] ?: return null
        baseBlock.setType(Material.getMaterial(blueprint.baseMaterial) ?: Material.OAK_FENCE, false)
        setupBlockData(baseBlock)

        val loc = baseBlock.location.add(0.5, 0.0, 0.5)
        val marker = baseBlock.world.spawnEntity(loc, EntityType.MARKER) as Marker
        val pdc = marker.persistentDataContainer

        val pool = treePool[blueprintId]
        val varIdx = if (pool != null && pool.isNotEmpty()) Random.nextInt(pool.size) else 0
        val structure = pool?.getOrNull(varIdx) ?: generateTreeStructure(blueprint)

        pdc.set(TREE_STAGE_KEY, PersistentDataType.STRING, TreeStage.GROWING.name)
        pdc.set(TREE_BP_ID_KEY, PersistentDataType.STRING, blueprintId)
        pdc.set(TREE_VAR_IDX_KEY, PersistentDataType.INTEGER, varIdx)
        pdc.set(TREE_BASE_X, PersistentDataType.INTEGER, baseBlock.x)
        pdc.set(TREE_BASE_Y, PersistentDataType.INTEGER, baseBlock.y)
        pdc.set(TREE_BASE_Z, PersistentDataType.INTEGER, baseBlock.z)
        pdc.set(TREE_HEIGHT_KEY, PersistentDataType.INTEGER, structure.height)
        pdc.set(PROG_KEY, PersistentDataType.INTEGER, 0)

        addCanopy(marker.world.name, blueprintId, baseBlock.x, baseBlock.y, baseBlock.z, structure.height, blueprint.leaves.radius, blueprint.leaves.startHeightPercent)

        spawnGrassAroundTree(baseBlock)

        return marker
    }

    private fun generateTreeStructure(blueprint: TreeBlueprint): TreeStructureData {
        val growPhase = mutableListOf<PrecomputedBlock>()
        val sortedGrowPhase = mutableListOf<Pair<Int, PrecomputedBlock>>()
        val thickenPhase = mutableListOf<PrecomputedBlock>()
        val expandPhase = mutableListOf<PrecomputedBlock>()
        val hardenPhase = mutableListOf<PrecomputedBlock>()
        val occupiedSpace = mutableSetOf<Int>()

        val trunkNodes = mutableListOf<Triple<Int, Int, Int>>()
        val branchNodes = mutableListOf<Triple<Int, Int, Int>>()
        val branchEnds = mutableListOf<Triple<Int, Int, Int>>()
        val branchBlocksWithMat = mutableListOf<Pair<Triple<Int, Int, Int>, Material>>()
        val allWoodSet = mutableSetOf<Triple<Int, Int, Int>>()
        val bareBranchSet = mutableSetOf<Triple<Int, Int, Int>>()

        val height = blueprint.height.random()
        var cx = 0; var cy = 0; var cz = 0
        val topMat = Material.getMaterial(blueprint.trunk.topMaterial) ?: Material.OAK_FENCE
        val middleMat = Material.getMaterial(blueprint.trunk.middleMaterial) ?: Material.DIORITE_WALL
        val bottomMat = Material.getMaterial(blueprint.trunk.bottomMaterial) ?: Material.OAK_WOOD

        val btnMatStr = blueprint.trunk.topMaterial.replace("_FENCE", "_BUTTON").replace("_LOG", "_BUTTON").replace("_WOOD", "_BUTTON")
        val btnMat = Material.getMaterial(btnMatStr) ?: Material.OAK_BUTTON
        val stairMat = Material.getMaterial(blueprint.trunk.stairsMaterial) ?: Material.OAK_STAIRS

        val bareMatPrefix = blueprint.branches.bareMaterial ?: blueprint.trunk.topMaterial.substringBefore("_")
        val gateMat = Material.getMaterial("${bareMatPrefix}_FENCE_GATE") ?: Material.OAK_FENCE_GATE
        val trapdoorMat = Material.getMaterial("${bareMatPrefix}_TRAPDOOR") ?: Material.OAK_TRAPDOOR

        val maxStairsAllowed = blueprint.maxStairs.random()
        var stairsUsed = 0

        val thickness = if (Random.nextDouble() < blueprint.trunk.thickChance) 2 else 1

        val addTrunkLevel = { x: Int, y: Int, z: Int ->
            for (ox in 0 until thickness) {
                for (oz in 0 until thickness) {
                    trunkNodes.add(Triple(x + ox, y, z + oz))
                }
            }
        }

        addTrunkLevel(cx, cy, cz)
        while (cy < height) {
            if (cy > 3 && cy < height - 3 && Random.nextDouble() < blueprint.trunk.bendChance) {
                val dx = listOf(-1, 0, 1).random()
                val dz = if (dx == 0) listOf(-1, 1).random() else 0
                if (dx != 0 || dz != 0) {
                    val oldX = cx; val oldZ = cz
                    cx += dx; cz += dz
                    addTrunkLevel(cx, cy, cz)

                    if (thickness == 1) {
                        val facingDir = if (dx > 0) "west" else if (dx < 0) "east" else if (dz > 0) "north" else "south"
                        val oppositeDir = if (dx > 0) "east" else if (dx < 0) "west" else if (dz > 0) "south" else "north"

                        if (stairsUsed < maxStairsAllowed) {
                            hardenPhase.add(PrecomputedBlock(cx.toByte(), (cy - 1).toByte(), cz.toByte(), stairMat, "facing=$facingDir,half=top"))
                            hardenPhase.add(PrecomputedBlock(oldX.toByte(), (cy + 1).toByte(), oldZ.toByte(), stairMat, "facing=$oppositeDir,half=bottom"))
                            stairsUsed += 2
                        } else {
                            hardenPhase.add(PrecomputedBlock(cx.toByte(), (cy - 1).toByte(), cz.toByte(), topMat))
                            hardenPhase.add(PrecomputedBlock(oldX.toByte(), (cy + 1).toByte(), oldZ.toByte(), topMat))
                        }
                        allWoodSet.add(Triple(cx, cy - 1, cz))
                        allWoodSet.add(Triple(oldX, cy + 1, oldZ))
                    }
                }
            }
            cy++
            addTrunkLevel(cx, cy, cz)
        }

        val bStartY = (height * blueprint.branches.startHeightPercent).toInt()
        val maxBranches = blueprint.branches.count.random()
        var bCount = 0

        val branchSourceNodes = if (thickness == 1) trunkNodes else trunkNodes.filter {
            it.first == cx || it.first == cx + thickness - 1 || it.third == cz || it.third == cz + thickness - 1
        }

        for (node in branchSourceNodes) {
            if (node.second >= bStartY && node.second < height - 2 && bCount < maxBranches && Random.nextDouble() <= 0.4) {
                var bx = node.first; var by = node.second; var bz = node.third
                val len = blueprint.branches.length.random()

                val bareChance = blueprint.branches.bareChance ?: 0.0
                if (Random.nextDouble() < bareChance) {
                    val isX = Random.nextBoolean()
                    val dx = if(isX) listOf(-1,1).random() else 0
                    val dz = if(!isX) listOf(-1,1).random() else 0

                    val gateFace = if (dx != 0) "south" else "east"
                    val hinge = if (dx > 0) "west" else if (dx < 0) "east" else if (dz > 0) "north" else "south"

                    for (l in 0 until len) {
                        bx += dx; bz += dz
                        sortedGrowPhase.add(by to PrecomputedBlock(bx.toByte(), by.toByte(), bz.toByte(), gateMat, "facing=$gateFace,in_wall=false,open=false"))
                        if (l == 0) {
                            sortedGrowPhase.add((by + 1) to PrecomputedBlock(bx.toByte(), (by + 1).toByte(), bz.toByte(), trapdoorMat, "facing=$hinge,half=bottom,open=true"))
                        } else {
                            sortedGrowPhase.add((by + 1) to PrecomputedBlock(bx.toByte(), (by + 1).toByte(), bz.toByte(), trapdoorMat, "facing=$hinge,half=bottom,open=false"))
                        }
                        allWoodSet.add(Triple(bx, by, bz)); allWoodSet.add(Triple(bx, by + 1, bz))
                        bareBranchSet.add(Triple(bx, by, bz)); bareBranchSet.add(Triple(bx, by + 1, bz))
                    }
                    bx += dx; bz += dz
                    sortedGrowPhase.add((by + 1) to PrecomputedBlock(bx.toByte(), (by + 1).toByte(), bz.toByte(), trapdoorMat, "facing=$hinge,half=bottom,open=false"))
                    allWoodSet.add(Triple(bx, by + 1, bz))
                    bareBranchSet.add(Triple(bx, by + 1, bz))
                    bCount++
                    continue
                }

                val dx = listOf(-1, 1).random(); val dz = listOf(-1, 1).random()
                val curveUp = blueprint.branches.curveUpChance ?: 0.8
                val curveDown = blueprint.branches.curveDownChance ?: 0.0
                val thickBaseLen = blueprint.branches.thickBaseLength ?: 1

                for (l in 0 until len) {
                    val currentMat = if (l < thickBaseLen) middleMat else topMat

                    if (Random.nextBoolean()) {
                        bx += dx
                        branchBlocksWithMat.add(Triple(bx, by, bz) to currentMat)
                        branchNodes.add(Triple(bx, by, bz))
                        val fDir = if (dx > 0) "west" else "east"
                        if (stairsUsed < maxStairsAllowed) {
                            sortedGrowPhase.add(by - 1 to PrecomputedBlock(bx.toByte(), (by - 1).toByte(), bz.toByte(), stairMat, "facing=$fDir,half=top"))
                            stairsUsed++
                        } else sortedGrowPhase.add(by - 1 to PrecomputedBlock(bx.toByte(), (by - 1).toByte(), bz.toByte(), currentMat))
                        allWoodSet.add(Triple(bx, by - 1, bz))

                        if (Random.nextBoolean()) {
                            bz += dz
                            branchBlocksWithMat.add(Triple(bx, by, bz) to currentMat)
                            branchNodes.add(Triple(bx, by, bz))
                            val fDirZ = if (dz > 0) "north" else "south"
                            if (stairsUsed < maxStairsAllowed) {
                                sortedGrowPhase.add(by - 1 to PrecomputedBlock(bx.toByte(), (by - 1).toByte(), bz.toByte(), stairMat, "facing=$fDirZ,half=top"))
                                stairsUsed++
                            } else sortedGrowPhase.add(by - 1 to PrecomputedBlock(bx.toByte(), (by - 1).toByte(), bz.toByte(), currentMat))
                            allWoodSet.add(Triple(bx, by - 1, bz))
                        }
                    } else {
                        bz += dz
                        branchBlocksWithMat.add(Triple(bx, by, bz) to currentMat)
                        branchNodes.add(Triple(bx, by, bz))
                        val fDir = if (dz > 0) "north" else "south"
                        if (stairsUsed < maxStairsAllowed) {
                            sortedGrowPhase.add(by - 1 to PrecomputedBlock(bx.toByte(), (by - 1).toByte(), bz.toByte(), stairMat, "facing=$fDir,half=top"))
                            stairsUsed++
                        } else sortedGrowPhase.add(by - 1 to PrecomputedBlock(bx.toByte(), (by - 1).toByte(), bz.toByte(), currentMat))
                        allWoodSet.add(Triple(bx, by - 1, bz))

                        if (Random.nextBoolean()) {
                            bx += dx
                            branchBlocksWithMat.add(Triple(bx, by, bz) to currentMat)
                            branchNodes.add(Triple(bx, by, bz))
                            val fDirX = if (dx > 0) "west" else "east"
                            if (stairsUsed < maxStairsAllowed) {
                                sortedGrowPhase.add(by - 1 to PrecomputedBlock(bx.toByte(), (by - 1).toByte(), bz.toByte(), stairMat, "facing=$fDirX,half=top"))
                                stairsUsed++
                            } else sortedGrowPhase.add(by - 1 to PrecomputedBlock(bx.toByte(), (by - 1).toByte(), bz.toByte(), currentMat))
                            allWoodSet.add(Triple(bx, by - 1, bz))
                        }
                    }

                    val randY = Random.nextDouble()
                    if (randY < curveUp) {
                        by++
                    } else if (randY < curveUp + curveDown) {
                        by--
                    }

                    branchBlocksWithMat.add(Triple(bx, by, bz) to currentMat)
                    branchNodes.add(Triple(bx, by, bz))
                }
                branchEnds.add(Triple(bx, by, bz))
                bCount++
            }
        }

        val finalLeafSet = mutableSetOf<Triple<Int, Int, Int>>()
        val r = blueprint.leaves.radius
        val ovalFactor = if (blueprint.leaves.shapeFocusY > 0) 0.6 else 1.0
        val isCone = blueprint.leaves.shape.equals("CONE", true)
        val isFlat = blueprint.leaves.shape.equals("FLAT", true)

        val lStartY = (height * blueprint.leaves.startHeightPercent).toInt()
        val canopyCenters = branchEnds.toMutableList()
        if (trunkNodes.isNotEmpty()) {
            if (thickness == 1) canopyCenters.add(trunkNodes.last())
            else canopyCenters.addAll(trunkNodes.takeLast(4))
        }

        (trunkNodes + branchNodes).forEach { allWoodSet.add(it) }

        if (isCone) {
            val coneTop = height + Random.nextInt(1, 3)
            var currentRadius = 0.5

            for (cy in coneTop downTo lStartY) {
                val distFromTop = coneTop - cy

                if (distFromTop <= 2) {
                    for (ox in 0 until thickness) {
                        for (oz in 0 until thickness) {
                            finalLeafSet.add(Triple(cx + ox, cy, cz + oz))
                        }
                    }
                    if (distFromTop == 2) {
                        for (ox in 0 until thickness) {
                            for (oz in 0 until thickness) {
                                finalLeafSet.add(Triple(cx + ox + 1, cy, cz + oz))
                                finalLeafSet.add(Triple(cx + ox - 1, cy, cz + oz))
                                finalLeafSet.add(Triple(cx + ox, cy, cz + oz + 1))
                                finalLeafSet.add(Triple(cx + ox, cy, cz + oz - 1))
                            }
                        }
                    }
                } else if (distFromTop % 2 == 1) {
                    currentRadius += Random.nextDouble(0.4, 0.9)
                    val effRadius = minOf(currentRadius, r.toDouble())
                    val limit = Math.ceil(effRadius).toInt()

                    for (dx in -limit..limit + (thickness - 1)) {
                        for (dz in -limit..limit + (thickness - 1)) {
                            val distX = if (dx < 0) -dx else if (dx >= thickness) dx - (thickness - 1) else 0
                            val distZ = if (dz < 0) -dz else if (dz >= thickness) dz - (thickness - 1) else 0
                            val dist = distX + distZ

                            val fuzzyRadius = effRadius + if (Random.nextDouble() < 0.4) 1.0 else 0.0

                            if (dist <= fuzzyRadius) {
                                val isCore = dist <= effRadius - 1.0
                                if (isCore || Random.nextDouble() < blueprint.leaves.density) {
                                    finalLeafSet.add(Triple(cx + dx, cy, cz + dz))
                                    if (dist >= effRadius - 1.0 && Random.nextDouble() < 0.3) {
                                        finalLeafSet.add(Triple(cx + dx, cy - 1, cz + dz))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            canopyCenters.forEach { center ->
                val isTop = trunkNodes.takeLast(thickness * thickness).contains(center)
                val cr = if (isTop) r else maxOf(1, r - 1)
                for (dx in -cr..cr) {
                    for (dy in -cr..cr) {
                        for (dz in -cr..cr) {
                            if (isFlat) {
                                if (dy in -1..1) {
                                    val maxR = if (dy == 0) cr.toDouble() else cr - 1.5
                                    if (maxR > 0 && dx*dx + dz*dz <= maxR*maxR && Random.nextDouble() < blueprint.leaves.density) {
                                        val p = Triple(center.first + dx, center.second + dy, center.third + dz)
                                        if (p !in allWoodSet) finalLeafSet.add(p)
                                    }
                                }
                            } else {
                                if ((dx*dx + dy*dy*ovalFactor + dz*dz) <= cr*cr && Random.nextDouble() < blueprint.leaves.density) {
                                    val p = Triple(center.first + dx, center.second + dy, center.third + dz)
                                    if (p !in allWoodSet) finalLeafSet.add(p)
                                }
                            }
                        }
                    }
                }
            }

            (trunkNodes + branchNodes).forEach { w ->
                if (w.second >= lStartY && !isFlat && w !in bareBranchSet) {
                    ALL_FACES.forEach { face ->
                        if (Random.nextDouble() < blueprint.leaves.density * 0.8) {
                            val p = Triple(w.first + face.modX, w.second + face.modY, w.third + face.modZ)
                            if (p !in allWoodSet) finalLeafSet.add(p)
                        }
                    }
                }
            }
        }

        val logMaxY = Random.nextInt(0, blueprint.trunk.bottomMaxHeight + 1)
        val wallMaxY = (height * blueprint.trunk.middleHeightPercent).toInt()
        val topY = trunkNodes.maxOfOrNull { it.second } ?: height

        val decorRule = blueprint.getDecorations()

        trunkNodes.forEach { n ->
            val isAbsoluteTop = (n.second == topY)
            val baseTypePhase1 = if (isAbsoluteTop) middleMat else if (n.second <= wallMaxY) middleMat else topMat
            var extStr = ""
            if (n.second <= height * 0.2 && baseTypePhase1.name.contains("WALL")) {
                extStr = "relief"
            }
            sortedGrowPhase.add(n.second to PrecomputedBlock(n.first.toByte(), n.second.toByte(), n.third.toByte(), baseTypePhase1, extStr))

            if (isAbsoluteTop) {
                sortedGrowPhase.add((n.second + 1) to PrecomputedBlock(n.first.toByte(), (n.second + 1).toByte(), n.third.toByte(), topMat))
                allWoodSet.add(Triple(n.first, n.second + 1, n.third))
            }

            if (!isFlat && !isCone && thickness == 1) {
                val tempLeafMat = blueprint.getRandomLeaf()
                for (dx in -1..1) {
                    for (dy in 0..1) {
                        for (dz in -1..1) {
                            val isCore = (dx == 0 && dz == 0)
                            val isCross = (Math.abs(dx) + Math.abs(dz) <= 1)
                            var shouldSpawn = false

                            if (n.second < 3) {
                                if (isCore && dy == 1 && Random.nextDouble() < 0.8) shouldSpawn = true
                            } else {
                                if (isCross && Random.nextDouble() < 0.5) shouldSpawn = true
                            }

                            if (shouldSpawn) {
                                val p = Triple(n.first + dx, n.second + dy, n.third + dz)
                                if (p !in allWoodSet && p !in finalLeafSet) {
                                    sortedGrowPhase.add(n.second to PrecomputedBlock(p.first.toByte(), p.second.toByte(), p.third.toByte(), tempLeafMat))
                                    sortedGrowPhase.add((n.second + 4) to PrecomputedBlock(p.first.toByte(), p.second.toByte(), p.third.toByte(), Material.AIR))
                                }
                            }
                        }
                    }
                }
            }

            if (n.second <= wallMaxY) {
                thickenPhase.add(PrecomputedBlock(n.first.toByte(), n.second.toByte(), n.third.toByte(), if (n.second <= logMaxY) bottomMat else middleMat))
            }
            hardenPhase.add(PrecomputedBlock(n.first.toByte(), n.second.toByte(), n.third.toByte(), bottomMat))

            if (n.second in 2..(height-2) && thickness == 1) {
                val face = HORIZONTAL_FACES.random()
                val fDir = if (face == BlockFace.EAST) "east" else if (face == BlockFace.WEST) "west" else if (face == BlockFace.NORTH) "north" else "south"

                if (Random.nextDouble() < decorRule.trapdoorChance) {
                    val tdMat = Material.getMaterial(blueprint.trunk.topMaterial.replace("_FENCE", "_TRAPDOOR").replace("_LOG", "_TRAPDOOR")) ?: trapdoorMat
                    hardenPhase.add(PrecomputedBlock((n.first + face.modX).toByte(), n.second.toByte(), (n.third + face.modZ).toByte(), tdMat, "facing=$fDir,open=true,half=bottom"))
                } else if (Random.nextDouble() < decorRule.buttonChance) {
                    hardenPhase.add(PrecomputedBlock((n.first + face.modX).toByte(), n.second.toByte(), (n.third + face.modZ).toByte(), btnMat, "facing=$fDir"))
                }
            }
        }

        val rootsRule = blueprint.getRootsRule()
        val rootMat = Material.getMaterial(rootsRule.material) ?: bottomMat
        val rootNodes = trunkNodes.filter { it.second == 0 }

        rootNodes.forEach { n ->
            HORIZONTAL_FACES.forEach { face ->
                if (Random.nextDouble() < rootsRule.chance) {
                    val rx = n.first + face.modX
                    val rz = n.third + face.modZ
                    val isFat = Random.nextBoolean()

                    val placeRoot = { x: Int, z: Int ->
                        if (Triple(x, n.second, z) !in allWoodSet) {
                            allWoodSet.add(Triple(x, n.second, z))
                            thickenPhase.add(PrecomputedBlock(x.toByte(), n.second.toByte(), z.toByte(), rootMat, "relief"))
                            val depth = Random.nextInt(2, rootsRule.maxDepth + 2)
                            for (ry in -1 downTo -depth) {
                                allWoodSet.add(Triple(x, ry, z))
                                hardenPhase.add(PrecomputedBlock(x.toByte(), ry.toByte(), z.toByte(), rootMat))
                            }
                        }
                    }
                    placeRoot(rx, rz)
                    if (isFat) {
                        val sideFace = if (face.modX != 0) listOf(BlockFace.NORTH, BlockFace.SOUTH).random() else listOf(BlockFace.EAST, BlockFace.WEST).random()
                        placeRoot(rx + sideFace.modX, rz + sideFace.modZ)
                    }
                }
            }
            val mainDepth = rootsRule.maxDepth
            for (ry in -1 downTo -mainDepth) {
                if (Random.nextDouble() < 0.9) {
                    allWoodSet.add(Triple(n.first, ry, n.third))
                    hardenPhase.add(PrecomputedBlock(n.first.toByte(), ry.toByte(), n.third.toByte(), rootMat))
                }
            }
        }

        branchBlocksWithMat.forEach { n ->
            sortedGrowPhase.add(n.first.second to PrecomputedBlock(n.first.first.toByte(), n.first.second.toByte(), n.first.third.toByte(), n.second))
        }

        val fruitLocations = mutableSetOf<Triple<Int, Int, Int>>()
        val leafMats = blueprint.getLeafMaterials()

        finalLeafSet.forEach { l ->
            if (l in bareBranchSet) return@forEach
            val leafMaterial = leafMats.randomOrNull() ?: Material.OAK_LEAVES
            if (Random.nextBoolean()) sortedGrowPhase.add(l.second to PrecomputedBlock(l.first.toByte(), l.second.toByte(), l.third.toByte(), leafMaterial))
            else expandPhase.add(PrecomputedBlock(l.first.toByte(), l.second.toByte(), l.third.toByte(), leafMaterial))

            if (Random.nextDouble() < 0.08) {
                val py = l.second + 1
                val pg = Triple(l.first, py, l.third)
                if (pg !in finalLeafSet && pg !in allWoodSet) {
                    expandPhase.add(PrecomputedBlock(pg.first.toByte(), pg.second.toByte(), pg.third.toByte(), Material.SHORT_GRASS))
                }
            }

            blueprint.fruits.filter { it.attachTo == "LEAVES" }.forEach { fruit ->
                if (Random.nextDouble() < fruit.spawnChance) {
                    val p = Triple(l.first, l.second - 1, l.third)
                    if (p !in allWoodSet && p !in finalLeafSet && !fruitLocations.contains(p)) {
                        fruitLocations.add(p)
                        expandPhase.add(PrecomputedBlock(p.first.toByte(), p.second.toByte(), p.third.toByte(), Material.PLAYER_HEAD, "fruit:${fruit.base64}"))
                    }
                }
            }

            if (blueprint.leaves.vinesChance > 0.0 && Random.nextDouble() < blueprint.leaves.vinesChance) {
                if (Triple(l.first, l.second - 1, l.third) !in allWoodSet && Triple(l.first, l.second - 1, l.third) !in finalLeafSet) {
                    val len = Random.nextInt(2, 6)
                    for (v in 1..len) {
                        val p = Triple(l.first, l.second - v, l.third)
                        if (p !in allWoodSet && p !in finalLeafSet && !fruitLocations.contains(p)) {
                            expandPhase.add(PrecomputedBlock(p.first.toByte(), p.second.toByte(), p.third.toByte(), Material.VINE))
                        } else break
                    }
                }
            }
        }

        blueprint.fruits.filter { it.attachTo == "TRUNK" }.forEach { fruit ->
            trunkNodes.forEach { node ->
                if (Random.nextDouble() < fruit.spawnChance && node.second > 1) {
                    val face = HORIZONTAL_FACES.random()
                    val p = Triple(node.first + face.modX, node.second, node.third + face.modZ)
                    if (p !in allWoodSet && p !in finalLeafSet && !fruitLocations.contains(p)) {
                        fruitLocations.add(p)
                        val fDir = if (face == BlockFace.EAST) "east" else if (face == BlockFace.WEST) "west" else if (face == BlockFace.NORTH) "north" else "south"
                        expandPhase.add(PrecomputedBlock(p.first.toByte(), p.second.toByte(), p.third.toByte(), Material.PLAYER_WALL_HEAD, "fruit:${fruit.base64},facing=$fDir"))
                    }
                }
            }
        }

        sortedGrowPhase.sortBy { it.first }
        growPhase.addAll(sortedGrowPhase.map { it.second })

        val totalBlocks = growPhase + thickenPhase + expandPhase + hardenPhase
        totalBlocks.forEach { occupiedSpace.add(packCoord(it.dx.toInt(), it.dy.toInt(), it.dz.toInt())) }

        return TreeStructureData(height, growPhase, thickenPhase, expandPhase, hardenPhase, occupiedSpace)
    }

    private fun setupBlockData(block: Block, ext: String = "") {
        var isRelief = false
        if (ext == "relief") {
            isRelief = true
        } else if (ext.isNotEmpty()) {
            if ((block.type == Material.PLAYER_HEAD || block.type == Material.PLAYER_WALL_HEAD) && ext.startsWith("fruit:")) {
                val parts = ext.split(",")
                val base64 = parts[0].substringAfter("fruit:")
                try {
                    if (block.type == Material.PLAYER_WALL_HEAD && parts.size > 1) {
                        val facingStr = parts[1].substringAfter("facing=")
                        val face = if (facingStr == "east") BlockFace.EAST else if (facingStr == "west") BlockFace.WEST else if (facingStr == "south") BlockFace.SOUTH else BlockFace.NORTH
                        val dData = block.blockData as Directional
                        dData.facing = face
                        block.setBlockData(dData, false)
                    }

                    val state = block.state as org.bukkit.block.Skull
                    val profile = skullProfileCache[base64]
                    if (profile != null) {
                        state.ownerProfile = profile
                        state.update(true, false)
                    }
                    return
                } catch (e: Exception) { plugin.logger.warning("Failed to apply fruit texture: ${e.message}") }
            } else {
                try {
                    block.setBlockData(Bukkit.createBlockData("${block.type.name.lowercase()}[$ext]"), false)
                    return
                } catch (e: Exception) {}
            }
        }

        val type = block.type
        if (type.name.contains("LEAVES")) {
            val bData = block.blockData as Leaves
            bData.isPersistent = true; bData.distance = 1; block.setBlockData(bData, false)
        } else if (type == Material.VINE) {
            val bData = block.blockData as MultipleFacing
            HORIZONTAL_FACES.forEach { face ->
                if (block.getRelative(face).type.isSolid || block.getRelative(face).type.name.contains("LEAVES")) {
                    bData.setFace(face, true)
                }
            }
            if (block.getRelative(BlockFace.UP).type.isSolid || block.getRelative(BlockFace.UP).type.name.contains("LEAVES")) {
                bData.setFace(BlockFace.UP, true)
            }
            if (bData.allowedFaces.none { bData.hasFace(it) }) bData.setFace(BlockFace.NORTH, true)
            block.setBlockData(bData, false)
        } else if (type.name.contains("BUTTON")) {
            val bData = block.blockData as Switch
            bData.attachedFace = FaceAttachable.AttachedFace.WALL
            HORIZONTAL_FACES.firstOrNull { block.getRelative(it).type.isSolid }?.let { bData.facing = it.oppositeFace }
            block.setBlockData(bData, false)
        } else if (type.name.contains("LOG") || type.name.contains("WOOD") || type.name.contains("WALL") || type.name.contains("FENCE")) {
            updateBlockConnections(block, isRelief)
        }
    }

    private fun updateBlockConnections(block: Block, addRelief: Boolean = false) {
        val data = block.blockData
        val isConnectable = { mat: Material ->
            (mat.isSolid && !mat.isTransparent) ||
                    mat.name.contains("LOG") || mat.name.contains("WOOD") ||
                    mat.name.contains("FENCE") || mat.name.contains("WALL") ||
                    mat.name.contains("LEAVES") || mat.name.contains("TRAPDOOR")
        }

        val reliefFaces = if (addRelief) HORIZONTAL_FACES.shuffled().take(Random.nextInt(1, 3)) else emptyList()

        if (data is MultipleFacing && block.type.name.contains("FENCE")) {
            HORIZONTAL_FACES.forEach { face ->
                data.setFace(face, isConnectable(block.getRelative(face).type) || reliefFaces.contains(face))
            }
            block.setBlockData(data, false)
        } else if (data is Wall && block.type.name.contains("WALL")) {
            data.isUp = true
            HORIZONTAL_FACES.forEach { face ->
                val connect = isConnectable(block.getRelative(face).type) || reliefFaces.contains(face)
                data.setHeight(face, if (connect) Wall.Height.LOW else Wall.Height.NONE)
            }
            block.setBlockData(data, false)
        }

        HORIZONTAL_FACES.forEach { face ->
            val rel = block.getRelative(face)
            val relData = rel.blockData
            if (relData is MultipleFacing && rel.type.name.contains("FENCE")) {
                relData.setFace(face.oppositeFace, isConnectable(block.type))
                rel.setBlockData(relData, false)
            } else if (relData is Wall && rel.type.name.contains("WALL")) {
                relData.setHeight(face.oppositeFace, if (isConnectable(block.type)) Wall.Height.LOW else Wall.Height.NONE)
                rel.setBlockData(relData, false)
            }
        }
    }

    private fun animateLeaves(world: org.bukkit.World, bx: Int, by: Int, bz: Int, height: Int, bpId: String) {
        val blueprint = blueprints[bpId] ?: return
        val leafMats = blueprint.getLeafMaterials()
        if (leafMats.size < 2) return

        for (i in 0..3) {
            val rx = bx + Random.nextInt(-4, 5)
            val ry = by + Random.nextInt((height * blueprint.leaves.startHeightPercent).toInt(), height + 2)
            val rz = bz + Random.nextInt(-4, 5)
            val block = world.getBlockAt(rx, ry, rz)
            if (leafMats.contains(block.type)) {
                val nextMat = leafMats.filter { it != block.type }.randomOrNull() ?: continue
                block.setType(nextMat, false)
                setupBlockData(block)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFruitBreak(event: BlockBreakEvent) {
        if (!config.enabled) return
        val block = event.block
        if (block.type == Material.PLAYER_HEAD || block.type == Material.PLAYER_WALL_HEAD) {
            val state = block.state as? org.bukkit.block.Skull ?: return
            val profile = state.ownerProfile ?: return
            val skinUrl = profile.textures.skin?.toString() ?: return
            val fruit = fruitMap[skinUrl]
            if (fruit != null) {
                event.isCancelled = true
                block.setType(Material.AIR)
                block.world.playSound(block.location, Sound.BLOCK_CROP_BREAK, 1f, 1f)
                Material.getMaterial(fruit.dropMaterial)?.let {
                    block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), ItemStack(it))
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onFruitInteract(event: PlayerInteractEvent) {
        if (!config.enabled) return
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (block.type == Material.PLAYER_HEAD || block.type == Material.PLAYER_WALL_HEAD) {
            val state = block.state as? org.bukkit.block.Skull ?: return
            val profile = state.ownerProfile ?: return
            val skinUrl = profile.textures.skin?.toString() ?: return
            val fruit = fruitMap[skinUrl]
            if (fruit != null) {
                event.isCancelled = true
                block.setType(Material.AIR)
                block.world.playSound(block.location, Sound.BLOCK_CAVE_VINES_PICK_BERRIES, 1f, 1f)
                Material.getMaterial(fruit.dropMaterial)?.let {
                    block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), ItemStack(it))
                }
            }
        }
    }

    private fun queueChunkForProcessing(chunk: org.bukkit.Chunk) {
        if (!config.enabled) return
        if (chunk.world.name !in plugin.gameplayManager.allowedWorlds) return

        val pdc = chunk.persistentDataContainer
        if (pdc.has(REPLACED_TAG, PersistentDataType.BYTE)) return
        pdc.set(REPLACED_TAG, PersistentDataType.BYTE, 1.toByte())

        val worldName = chunk.world.name
        val cx = chunk.x
        val cz = chunk.z

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (config.enabled) chunkScanQueue.add(ChunkLocation(worldName, cx, cz))
        }, config.chunkScanDelayTicks)
    }

    @EventHandler
    fun onChunkPopulate(event: ChunkPopulateEvent) {
        queueChunkForProcessing(event.chunk)
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        queueChunkForProcessing(event.chunk)

        if (config.enabled) {
            for (entity in event.chunk.entities) {
                if (entity is Marker && entity.persistentDataContainer.has(TREE_STAGE_KEY, PersistentDataType.STRING)) {
                    val pdc = entity.persistentDataContainer
                    val bpId = pdc.get(TREE_BP_ID_KEY, PersistentDataType.STRING) ?: continue
                    val bx = pdc.get(TREE_BASE_X, PersistentDataType.INTEGER) ?: continue
                    val by = pdc.get(TREE_BASE_Y, PersistentDataType.INTEGER) ?: continue
                    val bz = pdc.get(TREE_BASE_Z, PersistentDataType.INTEGER) ?: continue
                    val h = pdc.get(TREE_HEIGHT_KEY, PersistentDataType.INTEGER) ?: continue

                    val bp = blueprints[bpId] ?: continue
                    if (!bp.leaves.seasonalColors.isNullOrEmpty()) {
                        addCanopy(entity.world.name, bpId, bx, by, bz, h, bp.leaves.radius, bp.leaves.startHeightPercent)
                    }
                }
            }
        }
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val key = getChunkKey(event.chunk.world.name, event.chunk.x, event.chunk.z)
        canopyCache.remove(key)
    }

    private fun getBlueprintIdFor(type: Material): String? {
        return when (type) {
            Material.BIRCH_LOG, Material.BIRCH_WOOD -> "birch"
            Material.SPRUCE_LOG, Material.SPRUCE_WOOD -> "spruce"
            Material.OAK_LOG, Material.OAK_WOOD -> "oak"
            Material.ACACIA_LOG, Material.ACACIA_WOOD -> "acacia"
            Material.JUNGLE_LOG, Material.JUNGLE_WOOD -> "jungle"
            Material.DARK_OAK_LOG, Material.DARK_OAK_WOOD -> "dark_oak"
            Material.CHERRY_LOG, Material.CHERRY_WOOD -> "cherry"
            else -> null
        }
    }

    private fun getLeavesMatsFor(type: Material): List<Material> {
        return when (type) {
            Material.OAK_LOG, Material.OAK_WOOD -> listOf(Material.OAK_LEAVES)
            Material.BIRCH_LOG, Material.BIRCH_WOOD -> listOf(Material.BIRCH_LEAVES)
            Material.SPRUCE_LOG, Material.SPRUCE_WOOD -> listOf(Material.SPRUCE_LEAVES)
            Material.ACACIA_LOG, Material.ACACIA_WOOD -> listOf(Material.ACACIA_LEAVES)
            Material.JUNGLE_LOG, Material.JUNGLE_WOOD -> listOf(Material.JUNGLE_LEAVES)
            Material.DARK_OAK_LOG, Material.DARK_OAK_WOOD -> listOf(Material.DARK_OAK_LEAVES)
            Material.CHERRY_LOG, Material.CHERRY_WOOD -> listOf(Material.CHERRY_LEAVES)
            else -> emptyList()
        }
    }

    private fun scanChunkAsync(worldName: String, snapshot: ChunkSnapshot) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            if (!config.enabled) return@Runnable
            val chunkX = snapshot.x
            val chunkZ = snapshot.z
            val trees = mutableListOf<TreeData>()

            for (x in 0..15) {
                for (z in 0..15) {
                    var topY = snapshot.getHighestBlockYAt(x, z)

                    while (topY > 60) {
                        val t = snapshot.getBlockType(x, topY, z)
                        if (!t.isAir && t != Material.SNOW && t != Material.VINE) break
                        topY--
                    }
                    if (topY <= 60) continue
                    val topType = snapshot.getBlockType(x, topY, z)

                    if (!topType.name.contains("LEAVES")) continue

                    for (y in 60..topY) {
                        val type = snapshot.getBlockType(x, y, z)
                        if (type.name.endsWith("_LOG") || type.name.endsWith("_WOOD")) {
                            val belowType = snapshot.getBlockType(x, y - 1, z)

                            if (belowType in SOIL_BLOCKS) {
                                val absX = (chunkX shl 4) + x
                                val absZ = (chunkZ shl 4) + z
                                val bpId = getBlueprintIdFor(type)
                                if (bpId != null) {
                                    trees.add(TreeData(absX, y, absZ, type, bpId))
                                }
                                break
                            }
                        }
                    }
                }
            }

            if (trees.isNotEmpty()) {
                chunkTaskQueue.add(PendingChunkTask(worldName, trees))
            }
        })
    }

    private fun removeVanillaTree(start: Block, logMat: Material, leavesMats: List<Material>) {
        val queue = ArrayDeque<Block>()
        val visited = mutableSetOf<Block>()
        queue.add(start)
        visited.add(start)

        var count = 0
        var mainLeafData: org.bukkit.block.data.BlockData? = null
        val particleLocs = mutableListOf<org.bukkit.Location>()
        val world = start.world

        while(queue.isNotEmpty() && count < 1000) {
            val b = queue.removeFirst()
            val type = b.type

            if (type in leavesMats) {
                val bData = b.blockData
                if (bData is Leaves && bData.isPersistent) continue

                mainLeafData = mainLeafData ?: bData
                if (Random.nextDouble() < 0.25) particleLocs.add(b.location.add(0.5, 0.5, 0.5))
            }

            b.setType(Material.AIR, false)

            val up = b.getRelative(BlockFace.UP)
            if (up.chunk.isLoaded && up.type == Material.SNOW) up.setType(Material.AIR, false)

            count++

            for (face in ALL_FACES) {
                val relX = b.x + face.modX
                val relZ = b.z + face.modZ
                if (relX shr 4 != start.x shl 4 || relZ shr 4 != start.z shl 4) {
                    if (!world.isChunkLoaded(relX shr 4, relZ shr 4)) continue
                }

                val rel = b.getRelative(face)
                if (rel !in visited) {
                    val rType = rel.type
                    if (rType == logMat || rType in leavesMats || rType == Material.VINE || rType == Material.COCOA) {
                        if (rType in leavesMats) {
                            val rData = rel.blockData
                            if (rData is Leaves && rData.isPersistent) continue
                        }
                        visited.add(rel)
                        queue.add(rel)
                    }
                }
            }
        }

        if (mainLeafData != null && particleLocs.isNotEmpty()) {
            world.playSound(start.location, Sound.BLOCK_AZALEA_LEAVES_BREAK, 1.5f, 0.8f)
            particleLocs.forEach { loc ->
                try {
                    world.spawnParticle(Particle.valueOf("FALLING_LEAVES"), loc, 4, 0.3, 0.3, 0.3, 0.0)
                } catch (e: Exception) {
                    world.spawnParticle(Particle.FALLING_DUST, loc, 4, 0.3, 0.3, 0.3, 0.0, mainLeafData)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSaplingGrow(event: StructureGrowEvent) {
        if (!config.enabled) return
        event.isCancelled = true
        val blueprintId = when (event.species) {
            TreeType.BIRCH, TreeType.TALL_BIRCH -> "birch"
            TreeType.REDWOOD, TreeType.TALL_REDWOOD -> "spruce"
            TreeType.ACACIA -> "acacia"
            TreeType.JUNGLE, TreeType.SMALL_JUNGLE -> "jungle"
            TreeType.DARK_OAK -> "dark_oak"
            TreeType.CHERRY -> "cherry"
            else -> "oak"
        }

        val marker = spawnDynamicTreeBase(event.location.block, blueprintId)

        if (config.instantTrees && marker != null) {
            fastForwardTree(marker, 1.0)
        }
    }

    @EventHandler
    fun onBoneMealInteract(event: PlayerInteractEvent) {
        if (!config.enabled) return
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.item?.type != Material.BONE_MEAL) return
        val block = event.clickedBlock ?: return
        if (!(block.type.name.contains("LOG") || block.type.name.contains("WOOD") || block.type.name.contains("FENCE") || block.type.name.contains("WALL") || block.type.name.contains("LEAVES") || block.type.name.contains("TRAPDOOR"))) return

        val marker = getTreeMarkerForBlock(block) ?: return

        val pdc = marker.persistentDataContainer
        val stageStr = pdc.get(TREE_STAGE_KEY, PersistentDataType.STRING) ?: return
        if (stageStr == TreeStage.MATURE.name) return

        event.isCancelled = true
        block.world.playSound(block.location, Sound.ITEM_BONE_MEAL_USE, 1f, 1f)
        block.world.spawnParticle(Particle.HAPPY_VILLAGER, block.location.add(0.5, 0.5, 0.5), 35, 0.7, 0.7, 0.7)

        val bx = pdc.get(TREE_BASE_X, PersistentDataType.INTEGER) ?: marker.location.blockX
        val by = pdc.get(TREE_BASE_Y, PersistentDataType.INTEGER) ?: marker.location.blockY
        val bz = pdc.get(TREE_BASE_Z, PersistentDataType.INTEGER) ?: marker.location.blockZ

        processTreeGrowth(marker, TreeStage.valueOf(stageStr), plugin.seasonManager.currentSeason, bx, by, bz, config.boneMealGrowthSteps)
    }

    fun stop() {
        masterWorker?.cancel()
        masterWorker = null
        treeGrowthWorker?.cancel()
        treeGrowthWorker = null

        chunkScanQueue.clear()
        chunkTaskQueue.clear()
        canopyCache.clear()
    }
}