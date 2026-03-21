package vx.vivaldi.season.biome

import vx.vivaldi.Vivaldi.Companion.gson
import vx.vivaldi.Vivaldi.Companion.plugin
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
     * Retrieves the NORMAL color palette for a specific biome based on the current active season.
     *
     * @param fullKey The full namespaced key (e.g., "minecraft:plains")
     * @return The normal color palette, or null if no generated data exists for this biome
     */
    fun getActivePaletteFor(fullKey: String): BiomeColorPalette? {
        val container = seasonalBiomes[fullKey] ?: return null

        // TODO: Replace this with your actual Season/Gameplay manager logic!
        val currentSeasonName = "AUTUMN" // Hardcoded for testing

        return when (currentSeasonName) {
            "SPRING" -> container.spring.normal
            "SUMMER" -> container.summer.normal
            "AUTUMN" -> container.autumn.normal
            "WINTER" -> container.winter.normal
            else -> null
        }
    }

    /**
     * Retrieves the ALTERNATE color palette for a specific biome based on the current active season.
     *
     * @param fullKey The full namespaced key (e.g., "minecraft:plains")
     * @return The alternate color palette, or null if no generated data exists for this biome
     */
    fun getAlternatePaletteFor(fullKey: String): BiomeColorPalette? {
        val container = seasonalBiomes[fullKey] ?: return null

        // TODO: Replace this with your actual Season/Gameplay manager logic!
        val currentSeasonName = "AUTUMN" // Hardcoded for testing

        return when (currentSeasonName) {
            "SPRING" -> container.spring.alternate
            "SUMMER" -> container.summer.alternate
            "AUTUMN" -> container.autumn.alternate
            "WINTER" -> container.winter.alternate
            else -> null
        }
    }
}