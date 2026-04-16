package vx.embark.config

import vx.embark.config.lib.annotations.Comment
import vx.embark.config.lib.annotations.Configuration
import vx.embark.gameplay.feature.environment.PlantGrowthFeature
import vx.embark.gameplay.feature.environment.SeasonalChunkSyncFeature
import vx.embark.gameplay.feature.environment.SeasonalDaylightFeature
import vx.embark.gameplay.feature.environment.SeasonalMeltingFeature
import vx.embark.gameplay.feature.environment.SeasonalWeatherFeature
import vx.embark.gameplay.feature.environment.SnowAccumulationFeature
import vx.embark.gameplay.feature.environment.WaterFreezingFeature

@Configuration("gameplay.yml")
class GameplayConfiguration {

    val general       = GeneralConfig()
    val worlds        = WorldsConfig()
    val environment   = EnvironmentSection()
    val dynamicForest = DynamicForestConfig()

    class GeneralConfig {
        @Comment("Message prefix.")
        val messagePrefix: String = "§6Vivaldi §8| §7"

        @Comment(
            "If needed, you can disable the seasonal cycle so that players do not receive modified packets.",
            "Please note that the plugin will still work and some gameplay mechanics will still affect the game (grass growth, custom trees)."
        )
        val enableSeasons = true
    }

    class DynamicForestConfig {
        @Comment("How many unique trees of each type to pre-generate on startup.", "Higher value = more variety but slightly more RAM usage (100-300 is optimal).")
        val treePoolSize: Int = 200

        @Comment("Maximum tree replacement operations per tick when the server is perfectly smooth (TPS ~ 20.0).", "The plugin uses smart AI-like TPS monitoring and will dynamically lower this if the server starts to lag.")
        val maxOperationsPerTick: Int = 20
    }

    class WorldsConfig {
        @Comment("Worlds where embark features are active. Everything else is vanilla.")
        var allowedWorlds: List<String> = listOf("world")
    }

    class EnvironmentSection {
        @Comment(
            "List of biomes that should remain completely vanilla and ignore seasons.",
            "You can use the full name (e.g., 'minecraft:ocean'), just the key (e.g., 'ocean'),",
            "or wildcards (e.g., '*ocean*' or 'minecraft:end_*')."
        )
        var excludedBiomes: List<String> = listOf(
            // --- WILDCARDS (Covers Vanilla & Datapacks like Terralith) ---
            "*ocean*",        // Excludes all oceans so we don't freeze the entire sea
            "*cave*",         // Excludes lush_caves, dripstone_caves, and all Terralith 'cave/*' biomes
            "*underground*",  // Excludes Terralith subterranean biomes (e.g., underground_dirt)
            "*volcanic*",     // Excludes Terralith volcanic biomes (volcanic_crater, volcanic_peaks)
            "*hot_spring*",   // Excludes Terralith hot springs (so thermal water doesn't freeze)
            "*deep_dark*",    // Excludes the Deep Dark (safer than '*deep*' which might accidentally catch 'deep_forest')

            // --- VANILLA SPECIFIC EXCLUSIONS ---
            "minecraft:the_void",
            "minecraft:mushroom_fields",
            "minecraft:ice_spikes",

            // --- VANILLA NETHER DIMENSION ---
            "minecraft:nether_wastes",
            "minecraft:crimson_forest",
            "minecraft:warped_forest",
            "minecraft:soul_sand_valley",
            "minecraft:basalt_deltas",

            // --- VANILLA END DIMENSION ---
            "minecraft:the_end",
            "minecraft:end_highlands",
            "minecraft:end_midlands",
            "minecraft:small_end_islands",
            "minecraft:end_barrens"
        )

        val plantGrowth = PlantGrowthFeature.PlantGrowthConfig()
        val repopulator = SeasonalChunkSyncFeature.ChunkSyncConfig()
        val snowAccumulation = SnowAccumulationFeature.SnowConfig()
        val waterFreezing = WaterFreezingFeature.WaterFreezingConfig()
        val melting = SeasonalMeltingFeature.MeltingConfig()
        val daylight = SeasonalDaylightFeature.DaylightConfig()
        val weather = SeasonalWeatherFeature.WeatherConfig()
    }
}