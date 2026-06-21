package vx.seasons.season.biome

import vx.seasons.SeasonsPlugin.Companion.gson
import vx.seasons.SeasonsPlugin.Companion.plugin
import vx.seasons.config.GameplayConfiguration
import vx.seasons.config.lib.ConfigurationManager
import vx.seasons.season.Season
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

        // Load config to check for excluded biomes
        val gameplayConfig = ConfigurationManager.load(GameplayConfiguration::class.java)
        val excluded = gameplayConfig.environment.excludedBiomes.map { it.lowercase() }

        // Recursively find all .json files in the biomes directory and subdirectories
        biomesFolder.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
            try {
                // Assuming folder structure is: biomes/<namespace>/<key>.json
                val namespace = file.parentFile.name
                val key = file.nameWithoutExtension
                val fullKey = "$namespace:$key"

                val fullKeyLower = fullKey.lowercase()
                val keyLower = key.lowercase()

                // Check if the current biome matches any of the exclusion rules (including wildcards)
                val isExcluded = excluded.any { ex ->
                    when {
                        ex == fullKeyLower || ex == keyLower -> true
                        ex.contains("*") -> {
                            val regex = ex.replace("*", ".*").toRegex()
                            fullKeyLower.matches(regex) || keyLower.matches(regex)
                        }
                        else -> false
                    }
                }

                if (isExcluded) return@forEach // Skip loading this biome

                val container = gson.fromJson(file.readText(), GeneratedBiomeContainer::class.java)
                seasonalBiomes[fullKey] = container

            } catch (e: Exception) {
                plugin.logger.warning("Failed to load seasonal biome data from ${file.name}: ${e.message}")
            }
        }

        plugin.logger.info("Successfully loaded ${seasonalBiomes.size} seasonal biomes into memory.")
    }

    fun getActivePaletteFor(fullKey: String, season: Season): BiomeColorPalette? {
        val container = seasonalBiomes[fullKey] ?: return null
        return when (season) {
            Season.SPRING -> container.spring.normal
            Season.SUMMER -> container.summer.normal
            Season.AUTUMN -> container.autumn.normal
            Season.WINTER -> container.winter.normal
        }
    }

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