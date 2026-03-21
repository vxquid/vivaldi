package vx.vivaldi.config

import vx.vivaldi.config.lib.annotations.Comment
import vx.vivaldi.config.lib.annotations.Configuration
import vx.vivaldi.gameplay.feature.WolfAttributesFeature
import vx.vivaldi.gameplay.feature.WolfHungerFeature
import vx.vivaldi.gameplay.feature.WolfLeapFeature
import vx.vivaldi.gameplay.feature.WolfSizeFeature

@Configuration("gameplay.yml")
class GameplayConfiguration {

    val general = GeneralConfig()
    val worlds  = WorldsConfig()
    val wolves  = WolfSection()

    class GeneralConfig {
        @Comment("Message prefix.")
        val messagePrefix: String = "§6VIVALDI §8| §7"
    }

    class WorldsConfig {
        @Comment("Worlds where vivaldi features are active. Everything else is vanilla.")
        var allowedWorlds: List<String> = listOf("world", "world_nether", "world_the_end")
    }

    class WolfSection {
        @Comment("todo; comment me!")
        val size = WolfSizeFeature.WolfSizeConfig()
        val leap = WolfLeapFeature.WolfLeapConfig()
        val attributes = WolfAttributesFeature.WolfAttributesConfig()
        val hunger = WolfHungerFeature.WolfHungerConfig()
    }

}