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
        var messagePrefix: String = "§2Embark §aWilderness §8| §7"

        @Comment(
            "If needed, you can disable the seasonal cycle so that players do not receive modified packets.",
            "Please note that the plugin will still work and some gameplay mechanics will still affect the game (grass growth, custom trees)."
        )
        var enableSeasons: Boolean = true

        @Comment(
            "By default, all world modifications are disabled. All gameplay features below won't work until you turn this option to true.",
            "By switching this setting to true, you assume responsibility for all potential damage to your worlds.",
            "I highly recommend quickly reading through this file and customizing (or disabling it beforehand) everything."
        )
        var enableWorldModifications = false
    }

    class DynamicForestConfig {
        @Comment("Enable or disable the dynamic forest feature entirely. Every new and existing tree will be replaced by procedurally generated.")
        var enabled: Boolean = true

        @Comment("If true, saplings will instantly grow into fully mature dynamic trees, bypassing the slow growth phases.")
        var instantTrees: Boolean = false

        @Comment("How many unique trees of each type to pre-generate on startup.", "Higher value = more variety but slightly more RAM usage (100-300 is optimal).")
        var treePoolSize: Int = 200

        @Comment("Maximum tree replacement operations per tick when the server is perfectly smooth (TPS ~ 20.0).", "The plugin uses smart AI-like TPS monitoring and will dynamically lower this if the server starts to lag.")
        var maxOperationsPerTick: Int = 10

        @Comment("How many chunks to process per growth tick. Lower = better performance, Higher = faster global tree growth.")
        var maxChunksProcessedPerGrowthTick: Int = 15

        @Comment("Delay in ticks before the tree growth worker starts.")
        var growthTaskDelay: Long = 100L

        @Comment("Period in ticks how often the tree growth worker runs. (10 = twice per second)")
        var growthTaskPeriod: Long = 10L

        @Comment("Chance (0.0 to 1.0) for mature trees to drop fruit randomly during Autumn per growth tick.")
        var autumnFruitDropChance: Double = 0.05

        @Comment("Delay in ticks before scanning a newly loaded/populated chunk for vanilla trees.")
        var chunkScanDelayTicks: Long = 60L

        @Comment("Delay in ticks before spawning grass around a newly planted sapling.")
        var grassSpawnDelayTicks: Long = 40L

        @Comment("Chance (0.0 to 1.0) to spawn grass at a valid block around a new tree base.")
        var grassSpawnChance: Double = 0.6

        @Comment("Chance (0.0 to 1.0) for the spawned grass to be tall grass instead of short grass.")
        var tallGrassChance: Double = 0.1

        @Comment("How many growth steps to fast-forward when a player uses bone meal on a growing tree.")
        var boneMealGrowthSteps: Int = 15
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
            "*ocean*",
            "*cave*",
            "*underground*",
            "*volcanic*",
            "*hot_spring*",
            "*deep_dark*",
            "minecraft:the_void",
            "minecraft:mushroom_fields",
            "minecraft:ice_spikes",
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

        val plantGrowth = PlantGrowthFeature.PlantGrowthConfig()
        val repopulator = SeasonalChunkSyncFeature.ChunkSyncConfig()
        val snowAccumulation = SnowAccumulationFeature.SnowConfig()
        val waterFreezing = WaterFreezingFeature.WaterFreezingConfig()
        val melting = SeasonalMeltingFeature.MeltingConfig()
        val daylight = SeasonalDaylightFeature.DaylightConfig()
        val weather = SeasonalWeatherFeature.WeatherConfig()
    }

}