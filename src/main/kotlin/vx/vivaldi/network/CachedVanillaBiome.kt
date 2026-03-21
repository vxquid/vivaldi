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
import vx.vivaldi.season.biome.BiomeColorPalette
import java.lang.reflect.Field

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

    val vanillaBiomesCache = mutableMapOf<String, CachedVanillaBiome>()

    // Maps [Original Biome Network ID] -> [Alternate Fake Biome Network ID]
    private val alternateBiomeIdMap = mutableMapOf<Int, Int>()

    // Хранит ID всех альтернативных биомов для быстрой проверки O(1)
    private val alternateBiomeIds = mutableSetOf<Int>()

    // Maps hardcoded leaf block state IDs (Birch/Spruce) to Oak leaf block state IDs
    private val leafReplacementMap = mutableMapOf<Int, Int>()

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
                val keyToOriginalId = mutableMapOf<String, Int>()

                // First Pass
                for (i in elements.indices) {
                    val element = elements[i]
                    val biomeKey = element.id.toString()
                    var nbt = element.data as? NBTCompound

                    if (nbt != null && !vanillaBiomesCache.containsKey(biomeKey)) {
                        extractAndCacheBiomeData(biomeKey, nbt)
                    }

                    keyToOriginalId[biomeKey] = i

                    val activePalette = plugin.seasonalBiomeManager.getActivePaletteFor(biomeKey)
                    if (activePalette != null) {
                        var effects: NBTCompound? = null

                        if (nbt == null) {
                            nbt = NBTCompound()
                            nbt.setTag("has_precipitation", NBTByte(1.toByte()))
                            nbt.setTag("temperature", NBTFloat(0.5f))
                            nbt.setTag("downfall", NBTFloat(0.5f))

                            effects = NBTCompound()
                            nbt.setTag("effects", effects)

                            elements[i] = WrapperConfigServerRegistryData.RegistryElement(element.id, nbt)
                        } else {
                            effects = getTagSafe(nbt, "effects") as? NBTCompound
                            if (effects == null) {
                                effects = NBTCompound()
                                nbt.setTag("effects", effects)
                            }
                        }

                        injectColors(effects, activePalette)
                        injectedCount++
                    }
                }

                // Second Pass
                for (i in elements.indices) {
                    val element = elements[i]
                    val biomeKey = element.id.toString()
                    val nbt = element.data as? NBTCompound ?: continue

                    val altPalette = plugin.seasonalBiomeManager.getAlternatePaletteFor(biomeKey) ?: continue

                    val altKey = if (biomeKey.contains(":")) {
                        val split = biomeKey.split(":")
                        "${split[0]}:${split[1]}_alt"
                    } else {
                        "${biomeKey}_alt"
                    }

                    val altNbt = cloneBiomeNbt(nbt)
                    val effects = getTagSafe(altNbt, "effects") as? NBTCompound
                    if (effects != null) {
                        injectColors(effects, altPalette)
                    }

                    val altElement = WrapperConfigServerRegistryData.RegistryElement(ResourceLocation(altKey), altNbt)
                    newElements.add(altElement)

                    val originalId = keyToOriginalId[biomeKey]!!
                    val altId = elements.size + newElements.size - 1

                    alternateBiomeIdMap[originalId] = altId
                    alternateBiomeIds.add(altId) // Сохраняем ID для быстрой проверки!
                }

                elements.addAll(newElements)
                wrapper.elements = elements

                plugin.logger.info("§a[Vivaldi] Injected $injectedCount colors. Generated ${newElements.size} virtual alternate biomes.")
            }
        } catch (e: Exception) {
            plugin.logger.severe("§c[Vivaldi] Exception while processing REGISTRY_DATA: ${e.message}")
        }
    }

    private fun handleChunkData(event: PacketSendEvent) {
        if (leafReplacementMap.isEmpty()) buildLeafMappings()
        if (alternateBiomeIdMap.isEmpty() || leafReplacementMap.isEmpty()) return

        try {
            val wrapper = WrapperPlayServerChunkData(event)
            val chunks = wrapper.column.chunks
            var modified = false

            for (i in chunks.indices) {
                val chunk = chunks[i] ?: continue

                if (chunk is Chunk_v1_18) {
                    val biomeData = chunk.biomeData

                    for (bx in 0..15) {
                        for (by in 0..15) {
                            for (bz in 0..15) {
                                val currentStateId = chunk.get(bx, by, bz)
                                val replacementStateId = leafReplacementMap[currentStateId.globalId]

                                if (replacementStateId != null) {
                                    val biomeX = bx / 4
                                    val biomeY = by / 4
                                    val biomeZ = bz / 4

                                    val currentBiomeId = biomeData.get(biomeX, biomeY, biomeZ)
                                    val altBiomeId = alternateBiomeIdMap[currentBiomeId]

                                    if (altBiomeId != null) {
                                        // Биом оригинальный. Заменяем блок и меняем биом на альтернативный
                                        chunk.set(bx, by, bz, replacementStateId)
                                        biomeData.set(biomeX, biomeY, biomeZ, altBiomeId)
                                        modified = true
                                    } else if (alternateBiomeIds.contains(currentBiomeId)) {
                                        // Биом УЖЕ был изменен на альтернативный другим блоком листвы в этой зоне 4x4x4.
                                        // Просто меняем сам блок листвы на дубовый, чтобы дерево стало цельным.
                                        chunk.set(bx, by, bz, replacementStateId)
                                        modified = true
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (modified) {
                event.markForReEncode(true)
            }
        } catch (e: Exception) {
            // Silently catch
        }
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

            val waterColor = getInt(effects, "water_color", 0) ?: 0
            val waterFogColor = getInt(effects, "water_fog_color", 0) ?: 0
            val skyColor = getInt(effects, "sky_color", 0) ?: 0
            val fogColor = getInt(effects, "fog_color", 0) ?: 0

            val grassColor = getInt(effects, "grass_color")
            val foliageColor = getInt(effects, "foliage_color")

            val cachedBiome = CachedVanillaBiome(
                namespace = namespace,
                key = key,
                temperature = temperature,
                downfall = downfall,
                waterColor = waterColor,
                waterFogColor = waterFogColor,
                skyColor = skyColor,
                fogColor = fogColor,
                grassColor = grassColor,
                foliageColor = foliageColor
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