package vx.vivaldi.config

import vx.vivaldi.config.lib.annotations.Comment
import vx.vivaldi.config.lib.annotations.Configuration
import vx.vivaldi.gameplay.feature.PlantGrowthFeature
import vx.vivaldi.gameplay.feature.SeasonalChunkSyncFeature
import vx.vivaldi.gameplay.feature.SeasonalDaylightFeature
import vx.vivaldi.gameplay.feature.SeasonalMeltingFeature
import vx.vivaldi.gameplay.feature.SeasonalWeatherFeature
import vx.vivaldi.gameplay.feature.SnowAccumulationFeature
import vx.vivaldi.gameplay.feature.WaterFreezingFeature

@Configuration("gameplay.yml")
class GameplayConfiguration {

    val general     = GeneralConfig()
    val worlds      = WorldsConfig()
    val environment = EnvironmentSection()

    class GeneralConfig {
        @Comment("Message prefix.")
        val messagePrefix: String = "§6VIVALDI §8| §7"
    }

    class WorldsConfig {
        @Comment("Worlds where vivaldi features are active. Everything else is vanilla.")
        var allowedWorlds: List<String> = listOf("world")
    }

    class EnvironmentSection {
        @Comment(
            "List of biomes that should remain completely vanilla and ignore seasons.",
            "You can use the full name (e.g., 'minecraft:ocean'), just the key (e.g., 'ocean'),",
            "or wildcards (e.g., '*ocean*' or 'minecraft:end_*')."
        )
        var excludedBiomes: List<String> = listOf(
            "*ocean*", // Excludes all ocean variants automatically so we don't freeze the whole sea
            "minecraft:mushroom_fields",
            "minecraft:deep_dark",
            "minecraft:lush_caves",

            // Default dimension exclusions
            "minecraft:nether_wastes",
            "minecraft:crimson_forest",
            "minecraft:warped_forest",
            "minecraft:soul_sand_valley",
            "minecraft:basalt_deltas",
            "minecraft:the_end",
            "minecraft:end_highlands",
            "minecraft:end_midlands",
            "minecraft:small_end_islands",
            "minecraft:end_barrens"
        )

        @Comment("todo; comment me")
        val plantGrowth = PlantGrowthFeature.PlantGrowthConfig()

        @Comment("todo; comment me")
        val repopulator = SeasonalChunkSyncFeature.ChunkSyncConfig()

        @Comment("Custom dynamic snow mechanics for Winter season")
        val snowAccumulation = SnowAccumulationFeature.SnowConfig()

        @Comment("Gradual water freezing mechanics for Winter season")
        val waterFreezing = WaterFreezingFeature.WaterFreezingConfig()

        @Comment("Melting configuration. Melting works for all seasons except winter.")
        val melting = SeasonalMeltingFeature.MeltingConfig()

        @Comment("todo; comment me")
        val daylight = SeasonalDaylightFeature.DaylightConfig()

        @Comment("todo; comment me")
        val weather = SeasonalWeatherFeature.WeatherConfig()
    }
}