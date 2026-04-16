package vx.embark.gameplay.feature.environment

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.weather.WeatherChangeEvent
import vx.embark.Wilderness.Companion.plugin
import vx.embark.config.lib.annotations.Comment
import vx.embark.season.Season
import kotlin.random.Random

object SeasonalWeatherFeature : Listener {

    class WeatherConfig {
        var enabled: Boolean = true

        @Comment("Min and max multiplier for precipitation (rain/snow) duration in Autumn/Winter. 1.5 - 2.0 means 50% to 100% longer.")
        var minPrecipitationMultiplier: Double = 1.5
        var maxPrecipitationMultiplier: Double = 2.0

        @Comment("Multiplier for clear weather duration in Autumn/Winter. 0.6 means clear weather is 40% shorter, making rain happen more frequently.")
        var clearWeatherMultiplier: Double = 0.6
    }

    // Adjust this path if you place it differently in your config structure
    private val cfg get() = plugin.gameplayManager.config.environment.weather

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler(ignoreCancelled = true)
    fun onWeatherChange(event: WeatherChangeEvent) {
        if (!cfg.enabled) return
        val world = event.world
        
        if (world.name !in plugin.gameplayManager.allowedWorlds) return

        val currentSeason = plugin.seasonManager.currentSeason
        
        // This feature only affects Autumn and Winter
        if (currentSeason != Season.AUTUMN && currentSeason != Season.WINTER) return

        val isRaining = event.toWeatherState()

        // We schedule a 1-tick delay because Vanilla Minecraft generates the random weather duration
        // exactly at the moment the event completes. We must wait 1 tick to modify the freshly generated duration.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (isRaining) {
                // Precipitation is starting: increase its duration
                val multiplier = Random.nextDouble(cfg.minPrecipitationMultiplier, cfg.maxPrecipitationMultiplier)
                val currentDuration = world.weatherDuration
                
                // Set the extended duration
                world.weatherDuration = (currentDuration * multiplier).toInt()
            } else {
                // Clear weather is starting: reduce its duration to make precipitation trigger sooner
                val currentDuration = world.clearWeatherDuration
                
                // Set the shortened clear duration
                world.clearWeatherDuration = (currentDuration * cfg.clearWeatherMultiplier).toInt()
            }
        }, 1L)
    }
}