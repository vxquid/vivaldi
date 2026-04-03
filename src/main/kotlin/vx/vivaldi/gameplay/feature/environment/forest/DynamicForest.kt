package vx.vivaldi.gameplay.feature.environment.forest

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.TreeType
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.FaceAttachable
import org.bukkit.block.data.MultipleFacing
import org.bukkit.block.data.type.Leaves
import org.bukkit.block.data.type.Switch
import org.bukkit.block.data.type.Wall
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.EntityType
import org.bukkit.entity.Marker
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import org.joml.Quaternionf
import org.joml.Vector3f
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.season.Season
import java.io.File
import kotlin.random.Random

object DynamicForest : Listener {
    private val gson = Gson()
    private val gsonPretty = GsonBuilder().setPrettyPrinting().create()

    private val TREE_STAGE_KEY = NamespacedKey(plugin, "df_stage")
    private val TREE_BASE_X = NamespacedKey(plugin, "df_base_x")
    private val TREE_BASE_Y = NamespacedKey(plugin, "df_base_y")
    private val TREE_BASE_Z = NamespacedKey(plugin, "df_base_z")
    private val TREE_HEIGHT_KEY = NamespacedKey(plugin, "df_height")
    private val TREE_BP_ID_KEY = NamespacedKey(plugin, "df_bp_id")

    private val BP_GROW_KEY = NamespacedKey(plugin, "df_bp_grow")
    private val BP_THICKEN_KEY = NamespacedKey(plugin, "df_bp_thick")
    private val BP_EXPAND_KEY = NamespacedKey(plugin, "df_bp_exp")
    private val BP_HARDEN_KEY = NamespacedKey(plugin, "df_bp_harden")
    private val PROG_KEY = NamespacedKey(plugin, "df_prog")

    private val HORIZONTAL_FACES = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
    private val ALL_FACES = HORIZONTAL_FACES + listOf(BlockFace.UP, BlockFace.DOWN)
    private var task: BukkitRunnable? = null

    enum class TreeStage { GROWING, THICKENING, EXPANDING, HARDENING, MATURE }

    private data class BpBlock(val dx: Int, val dy: Int, val dz: Int, val mat: Material) {
        override fun toString() = "$dx,$dy,$dz,${mat.name}"
        companion object {
            fun fromString(str: String): BpBlock? {
                val p = str.split(",")
                if (p.size != 4) return null
                val mat = Material.getMaterial(p[3]) ?: return null
                return BpBlock(p[0].toInt(), p[1].toInt(), p[2].toInt(), mat)
            }
        }
    }

    data class Range(val min: Int, val max: Int) { fun random() = Random.nextInt(min, max + 1) }
    data class TrunkRule(val bottomMaterial: String, val bottomMaxHeight: Int, val rootChance: Double, val middleMaterial: String, val middleHeightPercent: Double, val topMaterial: String, val bendChance: Double = 0.15)
    data class BranchRule(val count: Range, val length: Range, val startHeightPercent: Double, val knotChance: Double)
    data class LeafRule(val materials: List<String>, val radius: Int, val density: Double, val startHeightPercent: Double, val shapeFocusY: Double)

    data class TreeBlueprint(val baseMaterial: String, val height: Range, val trunk: TrunkRule, val branches: BranchRule, val leaves: LeafRule) {
        fun getLeafMaterials(): List<Material> = leaves.materials.mapNotNull { Material.getMaterial(it) }
        fun getRandomLeaf(): Material = getLeafMaterials().randomOrNull() ?: Material.OAK_LEAVES
    }

    private class TreeStructureData(val height: Int, val growPhase: List<BpBlock>, val thickenPhase: List<BpBlock>, val expandPhase: List<BpBlock>, val hardenPhase: List<BpBlock>)

    private val blueprints = mutableMapOf<String, TreeBlueprint>()

    init {
        loadBlueprints()
        start()
    }

    private fun loadBlueprints() {
        val blueprintsDir = File(plugin.dataFolder, "environment/forest/blueprints")
        if (!blueprintsDir.exists()) blueprintsDir.mkdirs()

        val files = blueprintsDir.listFiles { _, name -> name.endsWith(".json") }
        if (files == null || files.isEmpty()) {
            val defaultBirch = TreeBlueprint("PALE_OAK_FENCE", Range(14, 21), TrunkRule("BIRCH_LOG", 2, 0.2, "DIORITE_WALL", 0.4, "PALE_OAK_FENCE", 0.02), BranchRule(Range(2, 5), Range(1, 3), 0.45, 0.08), LeafRule(listOf("OAK_LEAVES", "ACACIA_LEAVES"), 3, 0.65, 0.4, 0.8))
            val defaultOak = TreeBlueprint("OAK_FENCE", Range(10, 16), TrunkRule("OAK_LOG", 3, 0.4, "GRANITE_WALL", 0.45, "OAK_FENCE", 0.15), BranchRule(Range(4, 7), Range(2, 4), 0.35, 0.15), LeafRule(listOf("OAK_LEAVES", "ACACIA_LEAVES"), 4, 0.75, 0.35, 0.0))
            File(blueprintsDir, "birch.json").writeText(gsonPretty.toJson(defaultBirch))
            File(blueprintsDir, "oak.json").writeText(gsonPretty.toJson(defaultOak))
        }

        blueprints.clear()
        blueprintsDir.listFiles { _, name -> name.endsWith(".json") }?.forEach { file ->
            try { blueprints[file.nameWithoutExtension] = gson.fromJson(file.readText(), TreeBlueprint::class.java) }
            catch (e: Exception) { plugin.logger.warning("[DynamicForest] Failed to load blueprint from ${file.name}: ${e.message}") }
        }
    }

    private fun start() {
        task = object : BukkitRunnable() {
            override fun run() {
                val season = plugin.seasonManager.currentSeason
                val worlds = Bukkit.getWorlds().filter { it.name in plugin.gameplayManager.allowedWorlds }
                for (world in worlds) {
                    val observers = world.players.filter { it.inventory.itemInMainHand.type == Material.SPYGLASS || it.inventory.itemInOffHand.type == Material.SPYGLASS }
                    val loadedChunks = world.loadedChunks.toList()
                    for (chunk in loadedChunks.shuffled().take(30)) {
                        for (entity in chunk.entities.toList()) {
                            if (entity !is Marker || !entity.persistentDataContainer.has(TREE_STAGE_KEY, PersistentDataType.STRING)) continue
                            val pdc = entity.persistentDataContainer
                            val stage = TreeStage.valueOf(pdc.get(TREE_STAGE_KEY, PersistentDataType.STRING) ?: TreeStage.GROWING.name)
                            val baseX = pdc.get(TREE_BASE_X, PersistentDataType.INTEGER) ?: entity.location.blockX
                            val baseY = pdc.get(TREE_BASE_Y, PersistentDataType.INTEGER) ?: entity.location.blockY
                            val baseZ = pdc.get(TREE_BASE_Z, PersistentDataType.INTEGER) ?: entity.location.blockZ

                            if (observers.isNotEmpty() && stage != TreeStage.MATURE) {
                                val allBlocks = parseList(pdc.get(BP_GROW_KEY, PersistentDataType.STRING)) + parseList(pdc.get(BP_EXPAND_KEY, PersistentDataType.STRING)) + parseList(pdc.get(BP_HARDEN_KEY, PersistentDataType.STRING))
                                allBlocks.shuffled().take(35).forEach { bp ->
                                    val color = if (bp.mat.name.contains("LEAVES")) Particle.DustOptions(org.bukkit.Color.GREEN, 0.6f) else Particle.DustOptions(org.bukkit.Color.WHITE, 0.6f)
                                    world.spawnParticle(Particle.DUST, baseX + bp.dx + 0.5, baseY + bp.dy + 0.5, baseZ + bp.dz + 0.5, 1, 0.0, 0.0, 0.0, 0.0, color)
                                }
                            }
                            processTreeGrowth(entity, stage, season, baseX, baseY, baseZ, 0)
                            if (stage == TreeStage.MATURE && season != Season.WINTER) {
                                animateLeaves(world, baseX, baseY, baseZ, pdc.get(TREE_HEIGHT_KEY, PersistentDataType.INTEGER) ?: 16, pdc.get(TREE_BP_ID_KEY, PersistentDataType.STRING) ?: "birch")
                            }
                        }
                    }
                }
            }
        }
        task?.runTaskTimer(plugin, 60L, 4L)
    }

    private fun processTreeGrowth(marker: Marker, stage: TreeStage, season: Season, bx: Int, by: Int, bz: Int, forcedSteps: Int) {
        if (season == Season.WINTER && stage != TreeStage.MATURE) return
        val pdc = marker.persistentDataContainer
        val world = marker.world
        var prog = pdc.get(PROG_KEY, PersistentDataType.INTEGER) ?: 0

        val (currentBpKey, defaultBlocksPerTick, nextStage) = when (stage) {
            TreeStage.GROWING -> Triple(BP_GROW_KEY, 2, TreeStage.THICKENING)
            TreeStage.THICKENING -> Triple(BP_THICKEN_KEY, 1, TreeStage.EXPANDING)
            TreeStage.EXPANDING -> Triple(BP_EXPAND_KEY, 4, TreeStage.HARDENING)
            TreeStage.HARDENING -> Triple(BP_HARDEN_KEY, 2, TreeStage.MATURE)
            TreeStage.MATURE -> return
        }

        val blueprint = parseList(pdc.get(currentBpKey, PersistentDataType.STRING))
        val blocksToPlace = if (forcedSteps > 0) forcedSteps else defaultBlocksPerTick

        for (i in 0 until blocksToPlace) {
            if (prog < blueprint.size) {
                val bp = blueprint[prog]
                val block = world.getBlockAt(bx + bp.dx, by + bp.dy, bz + bp.dz)
                val isWood = bp.mat.name.contains("LOG") || bp.mat.name.contains("WALL") || bp.mat.name.contains("FENCE") || bp.mat.name.contains("BUTTON")

                if (block.type == Material.AIR || block.type.name.contains("LEAVES") || isWood) {
                    block.setType(bp.mat, false)
                    setupBlockData(block)
                }
                prog++
            } else break
        }

        if (prog >= blueprint.size) {
            pdc.set(TREE_STAGE_KEY, PersistentDataType.STRING, nextStage.name)
            pdc.set(PROG_KEY, PersistentDataType.INTEGER, 0)
        } else pdc.set(PROG_KEY, PersistentDataType.INTEGER, prog)
    }

    fun spawnMatureTree(baseBlock: Block, blueprintId: String) {
        val blueprint = blueprints[blueprintId] ?: return
        val structure = generateTreeStructure(blueprint)

        baseBlock.setType(Material.getMaterial(blueprint.baseMaterial) ?: Material.OAK_FENCE, false)
        setupBlockData(baseBlock)

        val world = baseBlock.world
        val bx = baseBlock.x
        val by = baseBlock.y
        val bz = baseBlock.z

        val finalMap = mutableMapOf<Triple<Int, Int, Int>, Material>()
        (structure.growPhase + structure.thickenPhase + structure.expandPhase + structure.hardenPhase).forEach {
            finalMap[Triple(it.dx, it.dy, it.dz)] = it.mat
        }

        finalMap.forEach { (coords, mat) ->
            val block = world.getBlockAt(bx + coords.first, by + coords.second, bz + coords.third)
            if (block.type == Material.AIR || block.type.name.contains("LEAVES") || block.type.name.contains("LOG") || block.type.name.contains("FENCE")) {
                block.setType(mat, false)
                setupBlockData(block)
            }
        }
    }

    private fun spawnDynamicTreeBase(baseBlock: Block, blueprintId: String) {
        val blueprint = blueprints[blueprintId] ?: return
        baseBlock.setType(Material.getMaterial(blueprint.baseMaterial) ?: Material.OAK_FENCE, false)
        setupBlockData(baseBlock)

        val loc = baseBlock.location.add(0.5, 0.0, 0.5)
        val marker = baseBlock.world.spawnEntity(loc, EntityType.MARKER) as Marker
        val pdc = marker.persistentDataContainer
        val structure = generateTreeStructure(blueprint)

        pdc.set(TREE_STAGE_KEY, PersistentDataType.STRING, TreeStage.GROWING.name)
        pdc.set(TREE_BP_ID_KEY, PersistentDataType.STRING, blueprintId)
        pdc.set(TREE_BASE_X, PersistentDataType.INTEGER, baseBlock.x)
        pdc.set(TREE_BASE_Y, PersistentDataType.INTEGER, baseBlock.y)
        pdc.set(TREE_BASE_Z, PersistentDataType.INTEGER, baseBlock.z)
        pdc.set(TREE_HEIGHT_KEY, PersistentDataType.INTEGER, structure.height)

        pdc.set(BP_GROW_KEY, PersistentDataType.STRING, structure.growPhase.joinToString(";"))
        pdc.set(BP_THICKEN_KEY, PersistentDataType.STRING, structure.thickenPhase.joinToString(";"))
        pdc.set(BP_EXPAND_KEY, PersistentDataType.STRING, structure.expandPhase.joinToString(";"))
        pdc.set(BP_HARDEN_KEY, PersistentDataType.STRING, structure.hardenPhase.joinToString(";"))
        pdc.set(PROG_KEY, PersistentDataType.INTEGER, 0)
    }

    private fun generateTreeStructure(blueprint: TreeBlueprint): TreeStructureData {
        val growPhase = mutableListOf<BpBlock>()
        val thickenPhase = mutableListOf<BpBlock>()
        val expandPhase = mutableListOf<BpBlock>()
        val hardenPhase = mutableListOf<BpBlock>()

        val trunkNodes = mutableListOf<Triple<Int, Int, Int>>()
        val branchNodes = mutableListOf<Triple<Int, Int, Int>>()
        val branchEnds = mutableListOf<Triple<Int, Int, Int>>()
        val allWoodSet = mutableSetOf<Triple<Int, Int, Int>>()

        val height = blueprint.height.random()
        var cx = 0; var cy = 0; var cz = 0
        val topMat = Material.getMaterial(blueprint.trunk.topMaterial) ?: Material.OAK_FENCE
        val middleMat = Material.getMaterial(blueprint.trunk.middleMaterial) ?: Material.DIORITE_WALL
        val bottomMat = Material.getMaterial(blueprint.trunk.bottomMaterial) ?: Material.OAK_LOG
        val btnMatStr = blueprint.trunk.topMaterial.replace("_FENCE", "_BUTTON").replace("_LOG", "_BUTTON")
        val btnMat = Material.getMaterial(btnMatStr) ?: Material.OAK_BUTTON

        trunkNodes.add(Triple(cx, cy, cz))
        while (cy < height) {
            if (cy > 3 && cy < height - 3 && Random.nextDouble() < blueprint.trunk.bendChance) {
                val dx = listOf(-1, 0, 1).random()
                val dz = if (dx == 0) listOf(-1, 1).random() else 0
                if (dx != 0 || dz != 0) {
                    cx += dx; cz += dz
                    trunkNodes.add(Triple(cx, cy, cz))
                }
            }
            cy++
            trunkNodes.add(Triple(cx, cy, cz))
        }

        val bStartY = (height * blueprint.branches.startHeightPercent).toInt()
        val maxBranches = blueprint.branches.count.random()
        var bCount = 0
        for (node in trunkNodes) {
            if (node.second >= bStartY && node.second < height - 2 && bCount < maxBranches && Random.nextDouble() <= 0.4) {
                var bx = node.first; var by = node.second; var bz = node.third
                val dx = listOf(-1, 1).random(); val dz = listOf(-1, 1).random()
                val len = blueprint.branches.length.random()
                for (l in 0 until len) {
                    if (Random.nextBoolean()) {
                        bx += dx; branchNodes.add(Triple(bx, by, bz))
                        if (Random.nextBoolean()) { bz += dz; branchNodes.add(Triple(bx, by, bz)) }
                    } else {
                        bz += dz; branchNodes.add(Triple(bx, by, bz))
                        if (Random.nextBoolean()) { bx += dx; branchNodes.add(Triple(bx, by, bz)) }
                    }
                    by++; branchNodes.add(Triple(bx, by, bz))
                }
                branchEnds.add(Triple(bx, by, bz))
                bCount++
            }
        }

        val logMaxY = Random.nextInt(0, blueprint.trunk.bottomMaxHeight + 1)
        val wallMaxY = (height * blueprint.trunk.middleHeightPercent).toInt()

        trunkNodes.forEach { n ->
            // ФИКС ВИСЯЩЕГО В ВОЗДУХЕ ДЕРЕВА:
            // Фаза 1 (Grow) ОБЯЗАНА выстраивать скелет снизу доверху.
            val baseTypePhase1 = if (n.second <= wallMaxY) middleMat else topMat
            growPhase.add(BpBlock(n.first, n.second, n.third, baseTypePhase1))
            allWoodSet.add(n)

            // Остальные фазы накатываются сверху
            if (n.second <= wallMaxY) {
                thickenPhase.add(BpBlock(n.first, n.second, n.third, if (n.second <= logMaxY) bottomMat else middleMat))
            }
            hardenPhase.add(BpBlock(n.first, n.second, n.third, bottomMat))

            // ФИКС КНОПОК: Спавним их только в 4-й фазе, когда ствол становится прочным бревном.
            if (n.second in 2..(height-2) && Random.nextDouble() < 0.08) {
                val face = HORIZONTAL_FACES.random()
                hardenPhase.add(BpBlock(n.first + face.modX, n.second, n.third + face.modZ, btnMat))
            }

            if (n.second <= logMaxY) {
                HORIZONTAL_FACES.forEach { face ->
                    val rootCh = if (n.second == 0) blueprint.trunk.rootChance * 1.5 else blueprint.trunk.rootChance
                    if (Random.nextDouble() < rootCh) {
                        thickenPhase.add(BpBlock(n.first + face.modX, n.second, n.third + face.modZ, topMat))
                        allWoodSet.add(Triple(n.first + face.modX, n.second, n.third + face.modZ))
                    }
                }
            }
        }

        branchNodes.forEach { n ->
            growPhase.add(BpBlock(n.first, n.second, n.third, topMat))
            allWoodSet.add(n)
        }

        val leafSet = mutableSetOf<Triple<Int, Int, Int>>()
        val r = blueprint.leaves.radius
        val ovalFactor = if (blueprint.leaves.shapeFocusY > 0) 0.6 else 1.0
        val lStartY = (height * blueprint.leaves.startHeightPercent).toInt()

        val canopyCenters = branchEnds.toMutableList()
        if (trunkNodes.isNotEmpty()) canopyCenters.add(trunkNodes.last())

        canopyCenters.forEach { center ->
            val isTop = (center == trunkNodes.last())
            val cr = if (isTop) r else maxOf(1, r - 1)
            for (dx in -cr..cr) {
                for (dy in -cr..cr) {
                    for (dz in -cr..cr) {
                        if ((dx*dx + dy*dy*ovalFactor + dz*dz) <= cr*cr && Random.nextDouble() < blueprint.leaves.density) {
                            val p = Triple(center.first + dx, center.second + dy, center.third + dz)
                            if (p !in allWoodSet) leafSet.add(p)
                        }
                    }
                }
            }
        }

        (trunkNodes + branchNodes).forEach { w ->
            if (w.second >= lStartY) {
                ALL_FACES.forEach { face ->
                    if (Random.nextDouble() < blueprint.leaves.density * 0.8) {
                        val p = Triple(w.first + face.modX, w.second + face.modY, w.third + face.modZ)
                        if (p !in allWoodSet) leafSet.add(p)
                    }
                }
            }
        }

        leafSet.forEach { l ->
            if (Random.nextBoolean()) growPhase.add(BpBlock(l.first, l.second, l.third, blueprint.getRandomLeaf()))
            else expandPhase.add(BpBlock(l.first, l.second, l.third, blueprint.getRandomLeaf()))
        }

        growPhase.sortWith(Comparator { a, b ->
            val aLeaf = a.mat.name.contains("LEAVES")
            val bLeaf = b.mat.name.contains("LEAVES")
            if (aLeaf && !bLeaf) return@Comparator 1
            if (!aLeaf && bLeaf) return@Comparator -1
            if (!aLeaf && !bLeaf) return@Comparator a.dy.compareTo(b.dy) // Ствол растет вверх
            return@Comparator b.dy.compareTo(a.dy) // Крона нарастает вниз
        })

        return TreeStructureData(height, growPhase, thickenPhase, expandPhase, hardenPhase)
    }

    private fun setupBlockData(block: Block) {
        val type = block.type
        if (type.name.contains("LEAVES")) {
            val bData = block.blockData as Leaves
            bData.isPersistent = true; bData.distance = 1; block.setBlockData(bData, false)
        } else if (type.name.contains("BUTTON")) {
            val bData = block.blockData as Switch
            bData.attachedFace = FaceAttachable.AttachedFace.WALL
            HORIZONTAL_FACES.firstOrNull { block.getRelative(it).type.isSolid }?.let { bData.facing = it.oppositeFace }
            block.setBlockData(bData, false)
        } else if (type.name.contains("LOG") || type.name.contains("WALL") || type.name.contains("FENCE")) {
            updateBlockConnections(block)
        }
    }

    private fun updateBlockConnections(block: Block) {
        val data = block.blockData
        val isConnectable = { mat: Material -> mat.isSolid || mat.name.contains("LOG") || mat.name.contains("FENCE") || mat.name.contains("WALL") || mat.name.contains("LEAVES") }

        if (data is MultipleFacing && block.type.name.contains("FENCE")) {
            HORIZONTAL_FACES.forEach { face -> data.setFace(face, isConnectable(block.getRelative(face).type)) }
            block.setBlockData(data, false)
        } else if (data is Wall && block.type.name.contains("WALL")) {
            data.isUp = true
            HORIZONTAL_FACES.forEach { face -> data.setHeight(face, if (isConnectable(block.getRelative(face).type)) Wall.Height.LOW else Wall.Height.NONE) }
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

    private fun parseList(str: String?): List<BpBlock> {
        if (str.isNullOrEmpty()) return emptyList()
        return str.split(";").mapNotNull { BpBlock.fromString(it) }
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

    private data class FallingBlockData(val display: BlockDisplay, val originalOffset: Vector)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTreeChop(event: BlockBreakEvent) {
        val block = event.block
        if (!(block.type.name.contains("LOG") || block.type.name.contains("FENCE") || block.type.name.contains("WALL"))) return

        val marker = block.world.getNearbyEntities(block.location, 8.0, 48.0, 8.0)
            .filterIsInstance<Marker>()
            .firstOrNull {
                it.persistentDataContainer.has(TREE_STAGE_KEY, PersistentDataType.STRING) &&
                        block.y >= (it.persistentDataContainer.get(TREE_BASE_Y, PersistentDataType.INTEGER) ?: -100)
            } ?: return

        val pdc = marker.persistentDataContainer
        val bx = pdc.get(TREE_BASE_X, PersistentDataType.INTEGER) ?: marker.location.blockX
        val by = pdc.get(TREE_BASE_Y, PersistentDataType.INTEGER) ?: marker.location.blockY
        val bz = pdc.get(TREE_BASE_Z, PersistentDataType.INTEGER) ?: marker.location.blockZ

        val treeBlocks = (parseList(pdc.get(BP_GROW_KEY, PersistentDataType.STRING)) +
                parseList(pdc.get(BP_THICKEN_KEY, PersistentDataType.STRING)) +
                parseList(pdc.get(BP_EXPAND_KEY, PersistentDataType.STRING)) +
                parseList(pdc.get(BP_HARDEN_KEY, PersistentDataType.STRING)))
            .map { Triple(bx + it.dx, by + it.dy, bz + it.dz) }.distinct()

        if (!treeBlocks.any { it.first == block.x && it.second == block.y && it.third == block.z }) return

        event.isCancelled = true
        marker.remove()

        val world = block.world
        world.playSound(block.location, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.5f)

        val pivotVector = block.location.toVector().add(Vector(0.5, 0.0, 0.5))

        // ФИКС ФИЗИКИ: Вектор падения направлен туда, КУДА СМОТРЕЛ игрок (игнорируя Y).
        val fallDir = event.player.location.direction.apply { setY(0.0) }
        if (fallDir.lengthSquared() < 0.001) fallDir.setX(1.0)
        fallDir.normalize()

        // ФИКС ОСИ ВРАЩЕНИЯ: Используем правильный вектор (0,1,0 x Взгляд), чтобы дерево падало ВПЕРЕД, а не назад
        val axis = Vector(0.0, 1.0, 0.0).crossProduct(fallDir).normalize()
        val axis3f = Vector3f(axis.x.toFloat(), axis.y.toFloat(), axis.z.toFloat())

        val fallingBlocks = mutableListOf<FallingBlockData>()

        treeBlocks.filter { it.second >= block.y }.forEach { coords ->
            val b = world.getBlockAt(coords.first, coords.second, coords.third)
            if (b.type != Material.AIR && !b.type.name.endsWith("AIR")) {
                val bData = b.blockData
                b.setType(Material.AIR, false)

                val offset = b.location.toVector().add(Vector(0.5, 0.0, 0.5)).subtract(pivotVector)

                val display = world.spawn(pivotVector.toLocation(world), BlockDisplay::class.java) { d ->
                    d.block = bData
                    d.teleportDuration = 2
                    d.interpolationDuration = 2
                    d.transformation = Transformation(offset.toVector3f(), AxisAngle4f(), Vector3f(1f), AxisAngle4f())
                }
                fallingBlocks.add(FallingBlockData(display, offset))
            }
        }

        // АНИМАЦИЯ: Потиковая плавная физика гравитации (экспоненциальное ускорение)
        val totalTicks = 80 // 4 секунды падения
        object : BukkitRunnable() {
            var tick = 0
            override fun run() {
                tick++
                if (tick > totalTicks) {
                    this.cancel()
                    world.playSound(pivotVector.toLocation(world), Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.5f)
                    fallingBlocks.forEach { fb ->
                        val trans = fb.display.transformation.translation
                        val finalWorldLoc = pivotVector.clone().add(Vector(trans.x.toDouble(), trans.y.toDouble(), trans.z.toDouble())).toLocation(world)
                        world.spawnParticle(Particle.BLOCK_CRUMBLE, finalWorldLoc, 5, 0.3, 0.3, 0.3, fb.display.block)

                        val mat = fb.display.block.material
                        if (mat.name.contains("LOG")) {
                            world.dropItemNaturally(finalWorldLoc, ItemStack(mat))
                            if (Random.nextDouble() < 0.1 && finalWorldLoc.block.type == Material.AIR) finalWorldLoc.block.setType(mat, false)
                        } else if (mat.name.contains("LEAVES")) {
                            if (Random.nextDouble() < 0.05) world.dropItemNaturally(finalWorldLoc, ItemStack(Material.STICK))
                        }
                        fb.display.remove()
                    }
                    return
                }

                // Квадратичное ускорение (сначала медленно, потом быстрее)
                val progress = tick.toDouble() / totalTicks
                val easeProgress = progress * progress
                val angle = Math.toRadians(90.0 * easeProgress)

                val quat = Quaternionf().fromAxisAngleRad(axis3f, angle.toFloat())

                fallingBlocks.forEach { fb ->
                    // Вычисляем новую дугу смещения и применяем
                    val rotatedOffset = fb.originalOffset.clone().rotateAroundAxis(axis, angle)

                    fb.display.interpolationDelay = 0
                    fb.display.interpolationDuration = 2 // Интерполяция между 1 тиком сглаживает рывки
                    fb.display.transformation = Transformation(
                        rotatedOffset.toVector3f(),
                        quat,
                        Vector3f(1f),
                        Quaternionf()
                    )
                }
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSaplingGrow(event: StructureGrowEvent) {
        event.isCancelled = true
        val blueprintId = when (event.species) {
            TreeType.BIRCH, TreeType.TALL_BIRCH -> "birch"
            else -> "oak"
        }
        spawnDynamicTreeBase(event.location.block, blueprintId)
    }

    @EventHandler
    fun onBoneMealInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.item?.type != Material.BONE_MEAL) return
        val block = event.clickedBlock ?: return
        if (!(block.type.name.contains("LOG") || block.type.name.contains("FENCE") || block.type.name.contains("WALL") || block.type.name.contains("LEAVES"))) return

        val marker = block.world.getNearbyEntities(block.location, 12.0, 32.0, 12.0).filterIsInstance<Marker>()
            .firstOrNull { it.persistentDataContainer.has(TREE_STAGE_KEY, PersistentDataType.STRING) } ?: return

        val pdc = marker.persistentDataContainer
        val stageStr = pdc.get(TREE_STAGE_KEY, PersistentDataType.STRING) ?: return
        if (stageStr == TreeStage.MATURE.name) return

        event.isCancelled = true
        block.world.playSound(block.location, Sound.ITEM_BONE_MEAL_USE, 1f, 1f)
        block.world.spawnParticle(Particle.HAPPY_VILLAGER, block.location.add(0.5, 0.5, 0.5), 35, 0.7, 0.7, 0.7)

        val bx = pdc.get(TREE_BASE_X, PersistentDataType.INTEGER) ?: marker.location.blockX
        val by = pdc.get(TREE_BASE_Y, PersistentDataType.INTEGER) ?: marker.location.blockY
        val bz = pdc.get(TREE_BASE_Z, PersistentDataType.INTEGER) ?: marker.location.blockZ

        processTreeGrowth(marker, TreeStage.valueOf(stageStr), plugin.seasonManager.currentSeason, bx, by, bz, 15)
    }

    fun stop() { task?.cancel(); task = null }
}