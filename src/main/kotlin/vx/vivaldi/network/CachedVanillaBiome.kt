package vx.vivaldi.network

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
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.season.Season
import vx.vivaldi.season.biome.BiomeColorPalette
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

    // Maps: [Vanilla Biome Network ID] ->[Season -> Seasonal Normal Biome Network ID]
    private val vanillaToSeasonalNormalMap = ConcurrentHashMap<Int, Map<Season, Int>>()

    // Maps: [Seasonal Normal Biome Network ID] ->[Seasonal Alternate Biome Network ID]
    // Alternate biomes are used specifically under tree leaves to create a "shadow" or varied depth effect.
    private val normalToAlternateBiomeMap = ConcurrentHashMap<Int, Int>()

    // Maps:[Hardcoded Leaf BlockState Global ID] -> [Oak Leaf BlockState Global ID]
    private val leafReplacementMap = ConcurrentHashMap<Int, Int>()

    // Cached reflection fields for fast NBT reading
    private val nbtFloatValueField: Field by lazy {
        NBTFloat::class.java.getDeclaredField("value").apply { isAccessible = true }
    }

    private val nbtIntValueField: Field by lazy {
        NBTInt::class.java.getDeclaredField("value").apply { isAccessible = true }
    }

    /**
     * Minecraft hardcodes Birch and Spruce leaves to ignore biome colormaps.
     * To make them change colors during seasons, we generate a map that translates
     * every possible state of Birch/Spruce leaves (distance, waterlogged, persistent)
     * into the equivalent state of Oak leaves, which DO respond to biome colors.
     */
    fun buildLeafMappings() {
        if (leafReplacementMap.isNotEmpty()) return

        try {
            val targets = mapOf(
                org.bukkit.Material.BIRCH_LEAVES to org.bukkit.Material.OAK_LEAVES,
                org.bukkit.Material.SPRUCE_LEAVES to org.bukkit.Material.OAK_LEAVES
            )

            for ((sourceMat, targetMat) in targets) {
                for (distance in 1..7) {
                    for (persistent in listOf(true, false)) {
                        for (waterlogged in listOf(true, false)) {
                            // Build Source (e.g. Birch)
                            val sourceData = org.bukkit.Bukkit.createBlockData(sourceMat) as org.bukkit.block.data.type.Leaves
                            sourceData.distance = distance
                            sourceData.isPersistent = persistent
                            if (sourceData is org.bukkit.block.data.Waterlogged) {
                                (sourceData as org.bukkit.block.data.Waterlogged).isWaterlogged = waterlogged
                            }

                            // Build Target (e.g. Oak)
                            val targetData = org.bukkit.Bukkit.createBlockData(targetMat) as org.bukkit.block.data.type.Leaves
                            targetData.distance = distance
                            targetData.isPersistent = persistent
                            if (targetData is org.bukkit.block.data.Waterlogged) {
                                (targetData as org.bukkit.block.data.Waterlogged).isWaterlogged = waterlogged
                            }

                            // Get global protocol IDs and map them
                            val sourceId = SpigotConversionUtil.fromBukkitBlockData(sourceData).globalId
                            val targetId = SpigotConversionUtil.fromBukkitBlockData(targetData).globalId

                            leafReplacementMap[sourceId] = targetId
                        }
                    }
                }
            }
            plugin.logger.info("Successfully built block replacement map for hardcoded leaves.")
        } catch (e: Exception) {
            plugin.logger.warning("Failed to build leaf replacement map: ${e.message}")
        }
    }

    /**
     * Calculates the dynamically shifted temperature for a biome based on the current season.
     * This naturally triggers vanilla weather mechanics (like rendering snow instead of rain in winter).
     * Vanilla snow threshold is < 0.15f.
     */
    private fun getSeasonalTemperature(baseTemp: Float, season: Season): Float {
        val newTemp = when (season) {
            Season.SPRING -> baseTemp + 0.05f // Mild, close to default
            Season.SUMMER -> baseTemp + 0.40f // Hotter (might turn snow into rain in cold biomes)
            Season.AUTUMN -> baseTemp - 0.20f // Cooler
            Season.WINTER -> baseTemp - 0.80f // Freezing (causes snow in most temperate biomes)
        }
        // Minecraft usually bounds temperatures between -0.7 and 2.0
        return max(-0.7f, min(2.0f, newTemp))
    }

    override fun onPacketSend(event: PacketSendEvent) {
        when (event.packetType) {
            PacketType.Configuration.Server.REGISTRY_DATA -> handleRegistryData(event)
            PacketType.Play.Server.CHUNK_DATA -> handleChunkData(event)
        }
    }

    /**
     * Intercepts the BIOME REGISTRY packet sent when a player joins.
     * We don't overwrite vanilla biomes; instead, we read them, apply our custom seasonal
     * color palettes and temperatures, and append them as brand NEW virtual biomes to the registry.
     */
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

                for (i in elements.indices) {
                    val element = elements[i]
                    val biomeKey = element.id.toString()
                    val nbt = element.data as? NBTCompound

                    // Cache vanilla fallback data
                    if (nbt != null && !vanillaBiomesCache.containsKey(biomeKey)) {
                        extractAndCacheBiomeData(biomeKey, nbt)
                    }

                    val rawName = (if (biomeKey.contains(":")) biomeKey.split(":")[1] else biomeKey).lowercase()
                    val seasonMap = mutableMapOf<Season, Int>()

                    // Retrieve original baseline temperature
                    val baseTemp = vanillaBiomesCache[biomeKey]?.temperature ?: 0.5f

                    // For every vanilla biome, generate its counterpart for ALL 4 seasons.
                    for (season in Season.entries) {
                        val seasonName = season.name.lowercase()
                        val normalPalette = plugin.seasonalBiomeManager.getActivePaletteFor(biomeKey, season)
                        val altPalette = plugin.seasonalBiomeManager.getAlternatePaletteFor(biomeKey, season)

                        if (normalPalette != null && altPalette != null) {
                            val normalKey = "vivaldi:${seasonName}_$rawName"
                            val altKey = "vivaldi:${seasonName}_${rawName}_alt"

                            // Dynamically adjust temperature based on the season
                            val seasonalTemp = getSeasonalTemperature(baseTemp, season)

                            // Generate NBT for the Normal Seasonal Biome
                            val normalNbt = if (nbt != null) cloneBiomeNbt(nbt) else createDefaultBiomeNbt()
                            normalNbt.setTag("temperature", NBTFloat(seasonalTemp)) // Inject new temp
                            val normalEffects = getOrCreateEffects(normalNbt)
                            injectColors(normalEffects, normalPalette)

                            // Generate NBT for the Alternate Seasonal Biome (used under leaves)
                            val altNbt = if (nbt != null) cloneBiomeNbt(nbt) else createDefaultBiomeNbt()
                            altNbt.setTag("temperature", NBTFloat(seasonalTemp)) // Inject new temp
                            val altEffects = getOrCreateEffects(altNbt)
                            injectColors(altEffects, altPalette)

                            // Append to registry list
                            newElements.add(WrapperConfigServerRegistryData.RegistryElement(ResourceLocation(normalKey), normalNbt))
                            newElements.add(WrapperConfigServerRegistryData.RegistryElement(ResourceLocation(altKey), altNbt))

                            // Calculate new protocol IDs for tracking
                            val normalId = elements.size + newElements.size - 2
                            val altId = elements.size + newElements.size - 1

                            seasonMap[season] = normalId
                            tempNormalToAlternate[normalId] = altId
                            injectedCount += 2
                        }
                    }

                    if (seasonMap.isNotEmpty()) {
                        tempVanillaToSeasonal[i] = seasonMap
                    }
                }

                // Push custom biomes to the packet
                elements.addAll(newElements)
                wrapper.elements = elements

                // Update lookup maps safely
                vanillaToSeasonalNormalMap.clear()
                vanillaToSeasonalNormalMap.putAll(tempVanillaToSeasonal)

                normalToAlternateBiomeMap.clear()
                normalToAlternateBiomeMap.putAll(tempNormalToAlternate)

                plugin.logger.info("Appended $injectedCount virtual seasonal biomes for ALL seasons.")
            }
        } catch (e: Exception) {
            plugin.logger.severe("Exception while processing REGISTRY_DATA: ${e.message}")
        }
    }

    /**
     * Intercepts actual CHUNK packets.
     * Replaces vanilla biomes with our virtual seasonal biomes, and replaces hardcoded leaves with oak.
     */
    private fun handleChunkData(event: PacketSendEvent) {
        if (leafReplacementMap.isEmpty()) buildLeafMappings()
        if (vanillaToSeasonalNormalMap.isEmpty()) return

        try {
            val wrapper = WrapperPlayServerChunkData(event)
            val chunks = wrapper.column.chunks
            var modified = false

            // ==============================================================================
            // OPTIMIZATION: Fast scan to find the highest section containing non-air blocks.
            // This prevents us from wasting CPU cycles iterating through completely empty sky.
            // ==============================================================================
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

            // Unloaded or entirely empty chunk column check
            if (highestSection == -1) return

            // Limit processing vertical bounds.
            // Bottom: Ignore deep caves (highestSection - 4). Seasons don't exist underground.
            // Top: Buffer of 2 sections (+32 blocks) above the highest block for sky color transitioning.
            val bottomSection = max(0, highestSection - 4)
            val topSection = min(chunks.lastIndex, highestSection + 2)

            val currentSeason = plugin.seasonManager.currentSeason

            for (i in bottomSection..topSection) {
                val chunk = chunks[i] ?: continue

                if (chunk is Chunk_v1_18) {
                    val biomeData = chunk.biomeData
                    var sectionModified = false

                    // ==============================================================================
                    // THE CRASH PREVENTION SAFEGUARD (The 8-Biome Limit)
                    // Vanilla Minecraft 1.18+ strictly limits 'indirect' biome palettes to a maximum
                    // of 8 unique biomes per 16x16x16 section. If a section has >8 biomes, it MUST
                    // use a Global Palette.
                    // PacketEvents has a bug: modifying a palette dynamically beyond 8 entries causes
                    // it to write an invalid buffer size. The client tries to read past the buffer length
                    // and gets kicked with 'readerIndex exceeds writerIndex' or 'IndexOutOfBoundsException'.
                    //
                    // FIX: We gather all existing unique biomes first. We then only allow adding
                    // NEW seasonal biomes if doing so won't push the total unique count past 8.
                    // ==============================================================================
                    val currentUniqueBiomes = mutableSetOf<Int>()
                    for (bx in 0..3) {
                        for (by in 0..3) {
                            for (bz in 0..3) {
                                currentUniqueBiomes.add(biomeData.get(bx, by, bz))
                            }
                        }
                    }

                    // If vanilla generation already gave us >8 biomes, it's natively using a Global Palette.
                    // Global palettes don't suffer from this bug, so we are completely safe to add as many as we want.
                    val isGlobal = currentUniqueBiomes.size > 8

                    // Track biomes we actively inject so we can accurately check against the limit
                    val addedBiomes = mutableSetOf<Int>()

                    // PASS 1: Biomes
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
                                        // LIMIT EXCEEDED!
                                        // Instead of leaving a green vanilla biome, we take ANY already added seasonal biome:
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

                    // -------------------------------------------------------------------------
                    // PASS 2: Block Sweep & Shadow Biomes
                    // Scans the 16x16x16 block volume. Replaces non-tintable leaves with Oak.
                    // Additionally, injects an 'Alternate' darker biome directly at the leaf's
                    // coordinate to create shadow and depth.
                    // -------------------------------------------------------------------------
                    for (bx in 0..15) {
                        for (by in 0..15) {
                            for (bz in 0..15) {
                                val currentStateId = chunk.get(bx, by, bz)

                                // Native fast-skip for air blocks
                                if (currentStateId.globalId == 0) continue

                                val replacementStateId = leafReplacementMap[currentStateId.globalId]

                                if (replacementStateId != null) {
                                    // Replace Birch/Spruce with Oak
                                    chunk.set(bx, by, bz, replacementStateId)
                                    sectionModified = true

                                    // Calculate biome coordinate (biomes are 4x4x4 blocks)
                                    val biomeX = bx / 4
                                    val biomeY = by / 4
                                    val biomeZ = bz / 4

                                    val currentBiomeId = biomeData.get(biomeX, biomeY, biomeZ)
                                    val altBiomeId = normalToAlternateBiomeMap[currentBiomeId]

                                    if (altBiomeId != null && currentBiomeId != altBiomeId) {
                                        if (isGlobal || currentUniqueBiomes.contains(altBiomeId) || addedBiomes.contains(altBiomeId) || (currentUniqueBiomes.size + addedBiomes.size < 7)) {
                                            biomeData.set(biomeX, biomeY, biomeZ, altBiomeId)
                                            addedBiomes.add(altBiomeId)
                                            sectionModified = true
                                        } else {
                                            // LIMIT EXCEEDED! Color the foliage in any already loaded seasonal color.
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
                // If anything changed, signal PacketEvents to recalculate and write the new buffer safely.
                event.markForReEncode(true)
            }
        } catch (e: Exception) {
            // Silently swallow random chunk corruption to prevent player kicks.
        }
    }

    /**
     * Creates a fallback blank NBT compound for a biome if the original cannot be parsed.
     */
    private fun createDefaultBiomeNbt(): NBTCompound {
        val nbt = NBTCompound()
        nbt.setTag("has_precipitation", NBTByte(1.toByte()))
        nbt.setTag("temperature", NBTFloat(0.5f))
        nbt.setTag("downfall", NBTFloat(0.5f))
        nbt.setTag("effects", NBTCompound())
        return nbt
    }

    /**
     * Extracts or initializes the "effects" NBT compound where biome colors are stored.
     */
    private fun getOrCreateEffects(nbt: NBTCompound): NBTCompound {
        var effects = getTagSafe(nbt, "effects") as? NBTCompound
        if (effects == null) {
            effects = NBTCompound()
            nbt.setTag("effects", effects)
        }
        return effects
    }

    /**
     * Deep clones a biome NBT so we can modify colors without altering the vanilla reference.
     */
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

    /**
     * Parses the vanilla biome NBT data and caches the original values for safety.
     */
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
        } catch (e: Exception) {
            plugin.logger.warning("§c[Vivaldi-DEBUG] Failed to parse NBT for biome $fullKey: ${e.message}")
        }
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

    /**
     * Injects the calculated hex colors from our custom season palette into the biome's NBT.
     */
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