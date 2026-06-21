package vx.seasons.gameplay

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import vx.seasons.SeasonsPlugin
import vx.seasons.config.GameplayConfiguration
import vx.seasons.config.lib.ConfigurationManager
import vx.seasons.gameplay.feature.environment.PlantGrowthFeature
import vx.seasons.gameplay.feature.environment.SeasonalChunkSyncFeature
import vx.seasons.gameplay.feature.environment.SeasonalDaylightFeature
import vx.seasons.gameplay.feature.environment.SeasonalMeltingFeature
import vx.seasons.gameplay.feature.environment.SeasonalWeatherFeature
import vx.seasons.gameplay.feature.environment.SnowAccumulationFeature
import vx.seasons.gameplay.feature.environment.WaterFreezingFeature
import vx.seasons.gameplay.feature.environment.forest.DynamicForestFeature

class GameplayManager(val plugin: SeasonsPlugin) : Listener {

    val config: GameplayConfiguration = ConfigurationManager.load(GameplayConfiguration::class.java)
    val allowedWorlds: Set<String> = config.worlds.allowedWorlds.toSet()

    fun registerFeatures() {

        // Enable features only if user enabled the main functional of the plugin.
        if (config.general.enableWorldModifications) {
            this.registerFeature(PlantGrowthFeature,
                SeasonalChunkSyncFeature, SeasonalMeltingFeature, SeasonalDaylightFeature, SeasonalWeatherFeature,
                SnowAccumulationFeature, WaterFreezingFeature, DynamicForestFeature
            )
            featureListeners.forEach { plugin.server.pluginManager.registerEvents(it, plugin) }
        }

        else {
            plugin.logger.warning("WARNING! This plugin currently only makes visual changes. To enable core gameplay mechanics (grass growth, complete tree overhaul, river freezing and snow in winter, etc.), go to /plugins/wilderness/gameplay.yml and enable \"enableWorldModifications.\"")
        }

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