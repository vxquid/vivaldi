package vx.vivaldi.season.biome

import vx.vivaldi.Vivaldi.Companion.gson
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.season.Season
import java.io.File

/**
 * Manages the loading of AI-generated biome JSON files and provides
 * the active seasonal color palette for the packet interceptor.
 */
class SeasonalBiomeManager {

    // Cache mapping full biome keys (e.g., "minecraft:plains") to their generated seasonal data
    private val seasonalBiomes = mutableMapOf<String, GeneratedBiomeContainer>()

    /**
     * Loads all generated JSON files from plugins/Vivaldi/biomes/
     */
    fun loadAllBiomes() {
        seasonalBiomes.clear()
        val biomesFolder = File(plugin.dataFolder, "biomes")
        if (!biomesFolder.exists()) return

        // Recursively find all .json files in the biomes directory and subdirectories
        biomesFolder.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
            try {
                val container = gson.fromJson(file.readText(), GeneratedBiomeContainer::class.java)

                // Assuming folder structure is: biomes/<namespace>/<key>.json
                val namespace = file.parentFile.name
                val key = file.nameWithoutExtension
                val fullKey = "$namespace:$key"

                seasonalBiomes[fullKey] = container
            } catch (e: Exception) {
                plugin.logger.warning("Failed to load seasonal biome data from ${file.name}: ${e.message}")
            }
        }

        plugin.logger.info("§a[Vivaldi] Successfully loaded ${seasonalBiomes.size} seasonal biomes into memory.")
    }

    /**
     * Retrieves the NORMAL color palette for a specific biome based on the provided season.
     *
     * @param fullKey The full namespaced key (e.g., "minecraft:plains")
     * @param season The season to retrieve the palette for
     * @return The normal color palette, or null if no generated data exists for this biome
     */
    fun getActivePaletteFor(fullKey: String, season: Season): BiomeColorPalette? {
        val container = seasonalBiomes[fullKey] ?: return null
        return when (season) {
            Season.SPRING -> container.spring.normal
            Season.SUMMER -> container.summer.normal
            Season.AUTUMN -> container.autumn.normal
            Season.WINTER -> container.winter.normal
        }
    }

    /**
     * Retrieves the ALTERNATE color palette for a specific biome based on the provided season.
     *
     * @param fullKey The full namespaced key (e.g., "minecraft:plains")
     * @param season The season to retrieve the palette for
     * @return The alternate color palette, or null if no generated data exists for this biome
     */
    fun getAlternatePaletteFor(fullKey: String, season: Season): BiomeColorPalette? {
        val container = seasonalBiomes[fullKey] ?: return null
        return when (season) {
            Season.SPRING -> container.spring.alternate
            Season.SUMMER -> container.summer.alternate
            Season.AUTUMN -> container.autumn.alternate
            Season.WINTER -> container.winter.alternate
        }
    }
}