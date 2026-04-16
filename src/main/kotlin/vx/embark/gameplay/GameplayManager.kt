package vx.embark.gameplay

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import vx.embark.Wilderness
import vx.embark.config.GameplayConfiguration
import vx.embark.config.lib.ConfigurationManager
import vx.embark.gameplay.feature.environment.PlantGrowthFeature
import vx.embark.gameplay.feature.environment.SeasonalChunkSyncFeature
import vx.embark.gameplay.feature.environment.SeasonalDaylightFeature
import vx.embark.gameplay.feature.environment.SeasonalMeltingFeature
import vx.embark.gameplay.feature.environment.SeasonalWeatherFeature
import vx.embark.gameplay.feature.environment.SnowAccumulationFeature
import vx.embark.gameplay.feature.environment.WaterFreezingFeature
import vx.embark.gameplay.feature.environment.forest.DynamicForestFeature

class GameplayManager(val plugin: Wilderness) : Listener {

    val config: GameplayConfiguration = ConfigurationManager.load(GameplayConfiguration::class.java)
    val allowedWorlds: Set<String> = config.worlds.allowedWorlds.toSet()

    fun registerFeatures() {
        this.registerFeature(PlantGrowthFeature,
            SeasonalChunkSyncFeature, SeasonalMeltingFeature, SeasonalDaylightFeature, SeasonalWeatherFeature,
            SnowAccumulationFeature, WaterFreezingFeature, DynamicForestFeature
        )
        featureListeners.forEach { plugin.server.pluginManager.registerEvents(it, plugin) }
    }

    fun registerFeature(vararg listeners: Listener) {
        listeners.forEach { featureListeners.add(it) }
    }

    @EventHandler
    fun onFirstWorldLoad(event: WorldLoadEvent) {
        val worldName = event.world.name
        if (worldName == allowedWorlds.first()) {
            this.registerFeatures()
        }
    }

    // Если какая-то фича захочет перезагрузить конфиг — просто вызывай GameplayManager.reload()
    fun reload() {
        // TODO: перезагрузка конфига + перерегистрация если нужно (пока не требуется)
    }

    private val featureListeners = mutableListOf<Listener>()

    init {
        plugin.gameplayManager = this
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

}