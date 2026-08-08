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

    private var eventsRegistered = false
    private val featureListeners = mutableListOf<Listener>()

    fun registerFeatures() {
        if (config.general.enableWorldModifications) {
            if (!eventsRegistered) {
                this.registerFeature(
                    PlantGrowthFeature,
                    SeasonalChunkSyncFeature,
                    SeasonalMeltingFeature,
                    SeasonalDaylightFeature,
                    SeasonalWeatherFeature,
                    SnowAccumulationFeature,
                    WaterFreezingFeature,
                    DynamicForestFeature
                )
                featureListeners.forEach { plugin.server.pluginManager.registerEvents(it, plugin) }
                eventsRegistered = true
            }

            // Мгновенный запуск всех задач генерации/роста/таяния в рантайме
            startAllTasks()
        } else {
            plugin.logger.warning("WARNING! World modifications are disabled. Run /vxs activate modifications to enable them.")
        }
    }

    fun startAllTasks() {
        if (!config.general.enableWorldModifications) return

        PlantGrowthFeature.start()
        SeasonalMeltingFeature.start()
        SeasonalDaylightFeature.start()
        SnowAccumulationFeature.start()
        WaterFreezingFeature.start()

        if (config.dynamicForest.enabled) {
            DynamicForestFeature.start()
        }
    }

    fun stopAllTasks() {
        PlantGrowthFeature.stop()
        SeasonalMeltingFeature.stop()
        SeasonalDaylightFeature.stop()
        SnowAccumulationFeature.stop()
        WaterFreezingFeature.stop()
        DynamicForestFeature.stop()
    }

    fun registerFeature(vararg listeners: Listener) {
        listeners.forEach { featureListeners.add(it) }
    }

    @EventHandler
    fun onFirstWorldLoad(event: WorldLoadEvent) {
        val worldName = event.world.name
        if (worldName == allowedWorlds.firstOrNull()) {
            this.registerFeatures()
        }
    }

    fun reload() {
        stopAllTasks()
        registerFeatures()
    }

    init {
        plugin.gameplayManager = this
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

}