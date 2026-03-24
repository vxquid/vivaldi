package vx.vivaldi.gameplay

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import vx.vivaldi.Vivaldi
import vx.vivaldi.config.GameplayConfiguration
import vx.vivaldi.config.lib.ConfigurationManager
import vx.vivaldi.gameplay.feature.PlantGrowthFeature
import vx.vivaldi.gameplay.feature.SeasonalRepopulatorFeature

class GameplayManager(val plugin: Vivaldi) : Listener {

    val config: GameplayConfiguration = ConfigurationManager.load(GameplayConfiguration::class.java)
    val allowedWorlds: Set<String> = config.worlds.allowedWorlds.toSet()

    fun registerFeatures() {
        this.registerFeature(PlantGrowthFeature, SeasonalRepopulatorFeature)
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