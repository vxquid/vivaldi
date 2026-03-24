package vx.vivaldi.config

import vx.vivaldi.config.lib.annotations.Comment
import vx.vivaldi.config.lib.annotations.Configuration
import vx.vivaldi.gameplay.feature.PlantGrowthFeature
import vx.vivaldi.gameplay.feature.SeasonalRepopulatorFeature

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
        val plantGrowth = PlantGrowthFeature.PlantGrowthConfig()
        val repopulator = SeasonalRepopulatorFeature.RepopulatorConfig()
    }

}