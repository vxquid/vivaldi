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

object BiomeRegistryInterceptor : PacketListener {

    val vanillaBiomesCache = ConcurrentHashMap<String, CachedVanillaBiome>()

    private val vanillaToSeasonalNormalMap = ConcurrentHashMap<Int, Map<Season, Int>>()
    private val normalToAlternateBiomeMap = ConcurrentHashMap<Int, Int>()
    private val leafReplacementMap = ConcurrentHashMap<Int, Int>()

    private val nbtFloatValueField: Field by lazy {
        NBTFloat::class.java.getDeclaredField("value").apply { isAccessible = true }
    }

    private val nbtIntValueField: Field by lazy {
        NBTInt::class.java.getDeclaredField("value").apply { isAccessible = true }
    }

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
                            val sourceData = org.bukkit.Bukkit.createBlockData(sourceMat) as org.bukkit.block.data.type.Leaves
                            sourceData.distance = distance
                            sourceData.isPersistent = persistent
                            if (sourceData is org.bukkit.block.data.Waterlogged) {
                                (sourceData as org.bukkit.block.data.Waterlogged).isWaterlogged = waterlogged
                            }

                            val targetData = org.bukkit.Bukkit.createBlockData(targetMat) as org.bukkit.block.data.type.Leaves
                            targetData.distance = distance
                            targetData.isPersistent = persistent
                            if (targetData is org.bukkit.block.data.Waterlogged) {
                                (targetData as org.bukkit.block.data.Waterlogged).isWaterlogged = waterlogged
                            }

                            val sourceId = SpigotConversionUtil.fromBukkitBlockData(sourceData).globalId
                            val targetId = SpigotConversionUtil.fromBukkitBlockData(targetData).globalId

                            leafReplacementMap[sourceId] = targetId
                        }
                    }
                }
            }
            plugin.logger.info("§a[Vivaldi] Successfully built block replacement map for hardcoded leaves.")
        } catch (e: Exception) {
            plugin.logger.warning("§c[Vivaldi] Failed to build leaf replacement map: ${e.message}")
        }
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

                for (i in elements.indices) {
                    val element = elements[i]
                    val biomeKey = element.id.toString()
                    val nbt = element.data as? NBTCompound

                    if (nbt != null && !vanillaBiomesCache.containsKey(biomeKey)) {
                        extractAndCacheBiomeData(biomeKey, nbt)
                    }

                    val rawName = (if (biomeKey.contains(":")) biomeKey.split(":")[1] else biomeKey).lowercase()
                    val seasonMap = mutableMapOf<Season, Int>()

                    for (season in Season.entries) {
                        val seasonName = season.name.lowercase()
                        val normalPalette = plugin.seasonalBiomeManager.getActivePaletteFor(biomeKey, season)
                        val altPalette = plugin.seasonalBiomeManager.getAlternatePaletteFor(biomeKey, season)

                        if (normalPalette != null && altPalette != null) {
                            val normalKey = "vivaldi:${seasonName}_$rawName"
                            val altKey = "vivaldi:${seasonName}_${rawName}_alt"

                            val normalNbt = if (nbt != null) cloneBiomeNbt(nbt) else createDefaultBiomeNbt()
                            val normalEffects = getOrCreateEffects(normalNbt)
                            injectColors(normalEffects, normalPalette)

                            val altNbt = if (nbt != null) cloneBiomeNbt(nbt) else createDefaultBiomeNbt()
                            val altEffects = getOrCreateEffects(altNbt)
                            injectColors(altEffects, altPalette)

                            newElements.add(WrapperConfigServerRegistryData.RegistryElement(ResourceLocation(normalKey), normalNbt))
                            newElements.add(WrapperConfigServerRegistryData.RegistryElement(ResourceLocation(altKey), altNbt))

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

                elements.addAll(newElements)
                wrapper.elements = elements

                vanillaToSeasonalNormalMap.clear()
                vanillaToSeasonalNormalMap.putAll(tempVanillaToSeasonal)

                normalToAlternateBiomeMap.clear()
                normalToAlternateBiomeMap.putAll(tempNormalToAlternate)

                plugin.logger.info("§a[Vivaldi] Appended $injectedCount virtual seasonal biomes for ALL seasons.")
            }
        } catch (e: Exception) {
            plugin.logger.severe("§c[Vivaldi] Exception while processing REGISTRY_DATA: ${e.message}")
        }
    }

    private fun handleChunkData(event: PacketSendEvent) {
        if (leafReplacementMap.isEmpty()) buildLeafMappings()
        if (vanillaToSeasonalNormalMap.isEmpty()) return

        try {
            val wrapper = WrapperPlayServerChunkData(event)
            val chunks = wrapper.column.chunks
            var modified = false

            // Быстрый скан верхней границы
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

                    // МАКСИМАЛЬНО ТУПОЕ, НО РАБОЧЕЕ РЕШЕНИЕ:
                    // Собираем все уникальные биомы, которые УЖЕ есть в секции.
                    val currentUniqueBiomes = mutableSetOf<Int>()
                    for (bx in 0..3) {
                        for (by in 0..3) {
                            for (bz in 0..3) {
                                currentUniqueBiomes.add(biomeData.get(bx, by, bz))
                            }
                        }
                    }

                    // Если ванильный сервер УЖЕ прислал больше 8 биомов (Global Palette) - мы в безопасности, багов нет.
                    val isGlobal = currentUniqueBiomes.size > 8

                    // Хранилище того, что мы добавили, чтобы не пробить лимит в 8
                    val addedBiomes = mutableSetOf<Int>()

                    // PASS 1: Биомы
                    for (bx in 0..3) {
                        for (by in 0..3) {
                            for (bz in 0..3) {
                                val currentBiomeId = biomeData.get(bx, by, bz)
                                val normalId = vanillaToSeasonalNormalMap[currentBiomeId]?.get(currentSeason)

                                if (normalId != null && currentBiomeId != normalId) {
                                    // Проверка безопасности: добавляем новый биом только если лимит не будет превышен
                                    if (isGlobal || currentUniqueBiomes.contains(normalId) || addedBiomes.contains(normalId) || (currentUniqueBiomes.size + addedBiomes.size < 8)) {
                                        biomeData.set(bx, by, bz, normalId)
                                        addedBiomes.add(normalId)
                                        sectionModified = true
                                    }
                                }
                            }
                        }
                    }

                    // PASS 2: Блоки и Теневые биомы
                    for (bx in 0..15) {
                        for (by in 0..15) {
                            for (bz in 0..15) {
                                val currentStateId = chunk.get(bx, by, bz)
                                if (currentStateId.globalId == 0) continue

                                val replacementStateId = leafReplacementMap[currentStateId.globalId]

                                if (replacementStateId != null) {
                                    chunk.set(bx, by, bz, replacementStateId)
                                    sectionModified = true

                                    val biomeX = bx / 4
                                    val biomeY = by / 4
                                    val biomeZ = bz / 4

                                    val currentBiomeId = biomeData.get(biomeX, biomeY, biomeZ)
                                    val altBiomeId = normalToAlternateBiomeMap[currentBiomeId]

                                    if (altBiomeId != null && currentBiomeId != altBiomeId) {
                                        // Такая же проверка: ставим теневой биом только если есть свободный слот
                                        if (isGlobal || currentUniqueBiomes.contains(altBiomeId) || addedBiomes.contains(altBiomeId) || (currentUniqueBiomes.size + addedBiomes.size < 8)) {
                                            biomeData.set(biomeX, biomeY, biomeZ, altBiomeId)
                                            addedBiomes.add(altBiomeId)
                                            sectionModified = true
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
        } catch (e: Exception) {
            // Игнорируем
        }
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