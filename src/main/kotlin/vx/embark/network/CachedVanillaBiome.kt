package vx.embark.network

import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.nbt.NBTByte
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound
import com.github.retrooper.packetevents.protocol.nbt.NBTFloat
import com.github.retrooper.packetevents.protocol.nbt.NBTInt
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18
import com.github.retrooper.packetevents.resources.ResourceLocation
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerRegistryData
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.entity.Player
import vx.embark.Wilderness.Companion.plugin
import vx.embark.gameplay.feature.environment.forest.DynamicForestFeature
import vx.embark.season.Season
import vx.embark.season.biome.BiomeColorPalette
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * A data class used to store original vanilla biome properties.
 * This is used as a fallback or baseline when we create our virtual seasonal biomes.
 */
data class CachedVanillaBiome(
    val namespace: String,
    val key: String,
    val temperature: Float,
    val downfall: Float,
    val waterColor: Int,
    val waterFogColor: Int,
    val skyColor: Int,
    val fogColor: Int,
    val grassColor: Int?,
    val foliageColor: Int?
)

/**
 * The core network interceptor of Vivaldi.
 * This object dynamically rewrites server-to-client packets to inject custom seasonal biomes
 * and manipulate chunk data on the fly, creating seasons without modifying the actual world save.
 */
object BiomeRegistryInterceptor : PacketListener {

    val vanillaBiomesCache = ConcurrentHashMap<String, CachedVanillaBiome>()

    // Maps: [Vanilla Biome Network ID] -> [Season] -> [Seasonal Normal Biome Network ID]
    private val vanillaToSeasonalNormalMap = ConcurrentHashMap<Int, Map<Season, Int>>()

    // Maps: [Seasonal Normal Biome Network ID] -> [Seasonal Alternate Biome Network ID]
    private val normalToAlternateBiomeMap = ConcurrentHashMap<Int, Int>()

    // Maps: [Any Base Biome ID (Normal or Alt)] -> [Blueprint ID] -> [Custom Tree Biome ID]
    private val treeOverrideBiomeMap = ConcurrentHashMap<Int, Map<String, Int>>()

    // Maps: [Hardcoded Leaf BlockState Global ID] -> [Oak Leaf BlockState Global ID]
    private val leafReplacementMap = ConcurrentHashMap<Int, Int>()

    // Fast lookup to know if a block is a leaf block (used for fast AABB tree checks)
    private val allLeafGlobalIds = ConcurrentHashMap.newKeySet<Int>()

    // Cached reflection fields for fast NBT reading
    private val nbtFloatValueField: Field by lazy {
        NBTFloat::class.java.getDeclaredField("value").apply { isAccessible = true }
    }

    private val nbtIntValueField: Field by lazy {
        NBTInt::class.java.getDeclaredField("value").apply { isAccessible = true }
    }

    /**
     * Minecraft hardcodes Birch, Spruce, and Cherry leaves to ignore biome colormaps.
     * To make them change colors during seasons, we generate a map that translates
     * every possible state of these leaves into the equivalent state of Oak leaves,
     * which DO respond to biome colors.
     */
    fun buildLeafMappings() {
        if (leafReplacementMap.isNotEmpty()) return

        try {
            val targets = mapOf(
                org.bukkit.Material.BIRCH_LEAVES to org.bukkit.Material.OAK_LEAVES,
                org.bukkit.Material.SPRUCE_LEAVES to org.bukkit.Material.OAK_LEAVES,
                org.bukkit.Material.CHERRY_LEAVES to org.bukkit.Material.OAK_LEAVES
            )

            // Build the all-leaf lookup set first for ALL native leaf types
            for (mat in org.bukkit.Material.values()) {
                if (mat.name.endsWith("_LEAVES")) {
                    for (distance in 1..7) {
                        for (persistent in listOf(true, false)) {
                            for (waterlogged in listOf(true, false)) {
                                val data = org.bukkit.Bukkit.createBlockData(mat) as org.bukkit.block.data.type.Leaves
                                data.distance = distance
                                data.isPersistent = persistent
                                if (data is org.bukkit.block.data.Waterlogged) {
                                    data.isWaterlogged = waterlogged
                                }
                                val id = SpigotConversionUtil.fromBukkitBlockData(data).globalId
                                allLeafGlobalIds.add(id)

                                // Build replacement target if it's one of the hardcoded ones
                                if (targets.containsKey(mat)) {
                                    val targetData = org.bukkit.Bukkit.createBlockData(targets[mat]!!) as org.bukkit.block.data.type.Leaves
                                    targetData.distance = distance
                                    targetData.isPersistent = persistent
                                    if (targetData is org.bukkit.block.data.Waterlogged) {
                                        targetData.isWaterlogged = waterlogged
                                    }
                                    val targetId = SpigotConversionUtil.fromBukkitBlockData(targetData).globalId
                                    leafReplacementMap[id] = targetId
                                }
                            }
                        }
                    }
                }
            }
            plugin.logger.info("Successfully built block replacement map for hardcoded leaves.")
        } catch (e: Exception) {
            plugin.logger.warning("Failed to build leaf replacement map: ${e.message}")
        }
    }

    private fun getSeasonalTemperature(baseTemp: Float, season: Season): Float {
        val newTemp = when (season) {
            Season.SPRING -> baseTemp + 0.05f
            Season.SUMMER -> baseTemp + 0.40f
            Season.AUTUMN -> baseTemp - 0.20f
            Season.WINTER -> baseTemp - 0.80f
        }
        return max(-0.7f, min(2.0f, newTemp))
    }

    override fun onPacketSend(event: PacketSendEvent) {
        when (event.packetType) {
            PacketType.Configuration.Server.REGISTRY_DATA -> handleRegistryData(event)
            PacketType.Play.Server.CHUNK_DATA -> handleChunkData(event)
        }
    }

    private fun handleRegistryData(event: PacketSendEvent) {
        try {
            val wrapper = WrapperConfigServerRegistryData(event)
            val registryId = wrapper.registryKey?.toString() ?: "null"

            if (registryId.contains("worldgen/biome")) {
                val elements = wrapper.elements?.toMutableList() ?: return
                val newElements = mutableListOf<WrapperConfigServerRegistryData.RegistryElement>()

                var injectedCount = 0
                val tempVanillaToSeasonal = mutableMapOf<Int, Map<Season, Int>>()
                val tempNormalToAlternate = mutableMapOf<Int, Int>()
                val tempTreeOverrides = mutableMapOf<Int, Map<String, Int>>()

                for (i in elements.indices) {
                    val element = elements[i]
                    val biomeKey = element.id.toString()
                    val nbt = element.data as? NBTCompound

                    if (nbt != null && !vanillaBiomesCache.containsKey(biomeKey)) {
                        extractAndCacheBiomeData(biomeKey, nbt)
                    }

                    val rawName = (if (biomeKey.contains(":")) biomeKey.split(":")[1] else biomeKey).lowercase()
                    val seasonMap = mutableMapOf<Season, Int>()

                    val baseTemp = vanillaBiomesCache[biomeKey]?.temperature ?: 0.5f

                    for (season in Season.entries) {
                        val seasonName = season.name.lowercase()
                        val normalPalette = plugin.seasonalBiomeManager.getActivePaletteFor(biomeKey, season)
                        val altPalette = plugin.seasonalBiomeManager.getAlternatePaletteFor(biomeKey, season)

                        if (normalPalette != null && altPalette != null) {
                            val seasonalTemp = getSeasonalTemperature(baseTemp, season)

                            val normalKey = "embark:${seasonName}_$rawName"
                            val normalNbt = if (nbt != null) cloneBiomeNbt(nbt) else createDefaultBiomeNbt()
                            normalNbt.setTag("temperature", NBTFloat(seasonalTemp))
                            normalNbt.setTag("has_precipitation", NBTByte(1.toByte()))
                            injectColors(getOrCreateEffects(normalNbt), normalPalette)

                            val altKey = "embark:${seasonName}_${rawName}_alt"
                            val altNbt = if (nbt != null) cloneBiomeNbt(nbt) else createDefaultBiomeNbt()
                            altNbt.setTag("temperature", NBTFloat(seasonalTemp))
                            altNbt.setTag("has_precipitation", NBTByte(1.toByte()))
                            injectColors(getOrCreateEffects(altNbt), altPalette)

                            newElements.add(WrapperConfigServerRegistryData.RegistryElement(ResourceLocation(normalKey), normalNbt))
                            newElements.add(WrapperConfigServerRegistryData.RegistryElement(ResourceLocation(altKey), altNbt))

                            val normalId = elements.size + newElements.size - 2
                            val altId = elements.size + newElements.size - 1

                            seasonMap[season] = normalId
                            tempNormalToAlternate[normalId] = altId

                            // ----------------------------------------------------
                            // Generate Virtual Biomes for Custom Tree Color Overrides
                            // ----------------------------------------------------
                            val bpOverrides = mutableMapOf<String, Int>()
                            for ((bpId, bp) in DynamicForestFeature.blueprints) {
                                val overrideColor = bp.leaves.seasonalColors?.get(season.name.lowercase())
                                if (!overrideColor.isNullOrEmpty()) {
                                    val treeNbt = cloneBiomeNbt(normalNbt)
                                    val treeEffects = getOrCreateEffects(treeNbt)
                                    treeEffects.setTag("foliage_color", NBTInt(parseHexColor(overrideColor)))

                                    val treeKey = "embark:${seasonName}_${rawName}_tree_$bpId"
                                    newElements.add(WrapperConfigServerRegistryData.RegistryElement(ResourceLocation(treeKey), treeNbt))
                                    bpOverrides[bpId] = elements.size + newElements.size - 1
                                    injectedCount++
                                }
                            }

                            // Map overrides safely so we can look them up whether the base block is normal, alt, or already an override
                            if (bpOverrides.isNotEmpty()) {
                                tempTreeOverrides[normalId] = bpOverrides
                                tempTreeOverrides[altId] = bpOverrides
                                for (treeId in bpOverrides.values) {
                                    tempTreeOverrides[treeId] = bpOverrides
                                }
                            }

                            injectedCount += 2
                        }
                    }

                    if (seasonMap.isNotEmpty()) {
                        tempVanillaToSeasonal[i] = seasonMap
                    }
                }

                elements.addAll(newElements)
                wrapper.elements = elements

                vanillaToSeasonalNormalMap.clear()
                vanillaToSeasonalNormalMap.putAll(tempVanillaToSeasonal)

                normalToAlternateBiomeMap.clear()
                normalToAlternateBiomeMap.putAll(tempNormalToAlternate)

                treeOverrideBiomeMap.clear()
                treeOverrideBiomeMap.putAll(tempTreeOverrides)

                plugin.logger.info("Appended $injectedCount virtual seasonal biomes for ALL seasons and tree overrides.")
            }
        } catch (e: Exception) {
            plugin.logger.severe("Exception while processing REGISTRY_DATA: ${e.message}")
        }
    }

    private fun handleChunkData(event: PacketSendEvent) {
        if (leafReplacementMap.isEmpty()) buildLeafMappings()
        if (vanillaToSeasonalNormalMap.isEmpty()) return

        try {
            val wrapper = WrapperPlayServerChunkData(event)
            val chunks = wrapper.column.chunks
            var modified = false

            val chunkX = wrapper.column.x
            val chunkZ = wrapper.column.z

            // Grab context details for canopy intersections
            val player = event.getPlayer<Player>()
            val worldName = player?.world?.name ?: "world"
            val minHeight = player?.world?.minHeight ?: -64
            val chunkKey = DynamicForestFeature.getChunkKey(worldName, chunkX, chunkZ)
            val canopies = DynamicForestFeature.canopyCache[chunkKey]

            var highestSection = -1
            for (i in chunks.indices.reversed()) {
                val chunk = chunks[i]
                if (chunk is Chunk_v1_18) {
                    var hasBlocks = false
                    scan@ for (by in 15 downTo 0) {
                        for (bx in 0..15) {
                            for (bz in 0..15) {
                                if (chunk.get(bx, by, bz).globalId != 0) {
                                    hasBlocks = true
                                    break@scan
                                }
                            }
                        }
                    }
                    if (hasBlocks) {
                        highestSection = i
                        break
                    }
                }
            }

            if (highestSection == -1) return

            val bottomSection = max(0, highestSection - 4)
            val topSection = min(chunks.lastIndex, highestSection + 2)
            val currentSeason = plugin.seasonManager.currentSeason

            for (i in bottomSection..topSection) {
                val chunk = chunks[i] ?: continue

                if (chunk is Chunk_v1_18) {
                    val biomeData = chunk.biomeData
                    var sectionModified = false

                    // Gather unique biomes safely for 8-biome limit check
                    val currentUniqueBiomes = mutableSetOf<Int>()
                    for (bx in 0..3) {
                        for (by in 0..3) {
                            for (bz in 0..3) {
                                currentUniqueBiomes.add(biomeData.get(bx, by, bz))
                            }
                        }
                    }

                    val isGlobal = currentUniqueBiomes.size > 8
                    val addedBiomes = mutableSetOf<Int>()

                    // PASS 1: Base Seasonal Biomes
                    for (bx in 0..3) {
                        for (by in 0..3) {
                            for (bz in 0..3) {
                                val currentBiomeId = biomeData.get(bx, by, bz)
                                val normalId = vanillaToSeasonalNormalMap[currentBiomeId]?.get(currentSeason)

                                if (normalId != null && currentBiomeId != normalId) {
                                    if (isGlobal || currentUniqueBiomes.contains(normalId) || addedBiomes.contains(normalId) || (currentUniqueBiomes.size + addedBiomes.size < 7)) {
                                        biomeData.set(bx, by, bz, normalId)
                                        addedBiomes.add(normalId)
                                        sectionModified = true
                                    } else {
                                        val safeFallback = addedBiomes.firstOrNull()
                                        if (safeFallback != null) {
                                            biomeData.set(bx, by, bz, safeFallback)
                                            sectionModified = true
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // PASS 2: Block Sweep, Leaf Replacements & Shadow/Tree Overrides
                    for (bx in 0..15) {
                        for (by in 0..15) {
                            for (bz in 0..15) {
                                val currentStateId = chunk.get(bx, by, bz)
                                if (currentStateId.globalId == 0) continue

                                val replacementStateId = leafReplacementMap[currentStateId.globalId]
                                val isLeaf = replacementStateId != null || allLeafGlobalIds.contains(currentStateId.globalId)

                                if (isLeaf) {
                                    if (replacementStateId != null) {
                                        chunk.set(bx, by, bz, replacementStateId)
                                        sectionModified = true
                                    }

                                    val biomeX = bx / 4
                                    val biomeY = by / 4
                                    val biomeZ = bz / 4

                                    val currentBiomeId = biomeData.get(biomeX, biomeY, biomeZ)
                                    var targetBiomeId = normalToAlternateBiomeMap[currentBiomeId]

                                    // Custom Tree Override Check
                                    if (canopies != null && canopies.isNotEmpty()) {
                                        val absX = (chunkX shl 4) + bx
                                        val absY = by + (i * 16) + minHeight
                                        val absZ = (chunkZ shl 4) + bz

                                        val matchingCanopy = canopies.firstOrNull { it.contains(absX, absY, absZ) }
                                        if (matchingCanopy != null) {
                                            val overrideId = treeOverrideBiomeMap[currentBiomeId]?.get(matchingCanopy.bpId)
                                            if (overrideId != null) {
                                                targetBiomeId = overrideId
                                            }
                                        }
                                    }

                                    if (targetBiomeId != null && currentBiomeId != targetBiomeId) {
                                        if (isGlobal || currentUniqueBiomes.contains(targetBiomeId) || addedBiomes.contains(targetBiomeId) || (currentUniqueBiomes.size + addedBiomes.size < 7)) {
                                            biomeData.set(biomeX, biomeY, biomeZ, targetBiomeId)
                                            addedBiomes.add(targetBiomeId)
                                            sectionModified = true
                                        } else {
                                            val safeFallback = addedBiomes.firstOrNull()
                                            if (safeFallback != null) {
                                                biomeData.set(biomeX, biomeY, biomeZ, safeFallback)
                                                sectionModified = true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (sectionModified) {
                        modified = true
                    }
                }
            }

            if (modified) {
                event.markForReEncode(true)
            }
        } catch (e: Exception) {}
    }

    private fun createDefaultBiomeNbt(): NBTCompound {
        val nbt = NBTCompound()
        nbt.setTag("has_precipitation", NBTByte(1.toByte()))
        nbt.setTag("temperature", NBTFloat(0.5f))
        nbt.setTag("downfall", NBTFloat(0.5f))
        nbt.setTag("effects", NBTCompound())
        return nbt
    }

    private fun getOrCreateEffects(nbt: NBTCompound): NBTCompound {
        var effects = getTagSafe(nbt, "effects") as? NBTCompound
        if (effects == null) {
            effects = NBTCompound()
            nbt.setTag("effects", effects)
        }
        return effects
    }

    private fun cloneBiomeNbt(original: NBTCompound): NBTCompound {
        val clone = NBTCompound()
        for ((key, tag) in original.tags) {
            if (key == "effects" && tag is NBTCompound) {
                val effectsClone = NBTCompound()
                for ((effKey, effTag) in tag.tags) {
                    effectsClone.setTag(effKey, effTag)
                }
                clone.setTag("effects", effectsClone)
            } else {
                clone.setTag(key, tag)
            }
        }
        return clone
    }

    private fun extractAndCacheBiomeData(fullKey: String, nbt: NBTCompound) {
        try {
            val split = fullKey.split(":")
            val namespace = split.getOrNull(0) ?: "minecraft"
            val key = split.getOrNull(1) ?: fullKey

            val temperature = getFloat(nbt, "temperature", 0.5f)
            val downfall = getFloat(nbt, "downfall", 0.5f)
            val effects = getTagSafe(nbt, "effects") as? NBTCompound ?: return

            val cachedBiome = CachedVanillaBiome(
                namespace = namespace,
                key = key,
                temperature = temperature,
                downfall = downfall,
                waterColor = getInt(effects, "water_color", 0) ?: 0,
                waterFogColor = getInt(effects, "water_fog_color", 0) ?: 0,
                skyColor = getInt(effects, "sky_color", 0) ?: 0,
                fogColor = getInt(effects, "fog_color", 0) ?: 0,
                grassColor = getInt(effects, "grass_color"),
                foliageColor = getInt(effects, "foliage_color")
            )

            vanillaBiomesCache[fullKey] = cachedBiome
        } catch (e: Exception) {}
    }

    private fun getTagSafe(nbt: NBTCompound, key: String): Any? {
        return try { nbt.getTagOrThrow(key) } catch (_: Exception) { null }
    }

    private fun getFloat(nbt: NBTCompound, key: String, default: Float = 0.5f): Float {
        return try {
            val tag = getTagSafe(nbt, key) as? NBTFloat ?: return default
            nbtFloatValueField.getFloat(tag)
        } catch (e: Exception) { default }
    }

    private fun getInt(nbt: NBTCompound, key: String, default: Int? = null): Int? {
        return try {
            val tag = getTagSafe(nbt, key) as? NBTInt ?: return default
            nbtIntValueField.getInt(tag)
        } catch (e: Exception) { default }
    }

    private fun injectColors(effects: NBTCompound, palette: BiomeColorPalette) {
        effects.setTag("grass_color", NBTInt(parseHexColor(palette.grassColor)))
        effects.setTag("foliage_color", NBTInt(parseHexColor(palette.foliageColor)))
        effects.setTag("water_color", NBTInt(parseHexColor(palette.waterColor)))
        effects.setTag("water_fog_color", NBTInt(parseHexColor(palette.waterFogColor)))
        effects.setTag("sky_color", NBTInt(parseHexColor(palette.skyColor)))
        effects.setTag("fog_color", NBTInt(parseHexColor(palette.fogColor)))
    }

    private fun parseHexColor(hex: String): Int {
        return try { hex.replace("#", "").toInt(16) } catch (e: Exception) { 0xFFFFFF }
    }
}