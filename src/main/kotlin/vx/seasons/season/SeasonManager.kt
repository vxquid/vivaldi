package vx.seasons.season

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import vx.seasons.SeasonsPlugin
import vx.seasons.SeasonsPlugin.Companion.gson
import vx.seasons.season.biome.SeasonUpdateManager

// Helper enum representation of Seasons
enum class Season {
    SPRING, SUMMER, AUTUMN, WINTER;

    fun next(): Season {
        val nextOrdinal = (this.ordinal + 1) % entries.size
        return entries[nextOrdinal]
    }
}

// Bukkit Event for developers listening to Season Changes
class SeasonChangeEvent(val oldSeason: Season, val newSeason: Season) : Event() {
    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
    override fun getHandlers(): HandlerList = handlerList
}

// Data class to wrap the state, which GSON will convert into JSON
data class SeasonData(
    var season: Season = Season.SPRING,
    var passedTicks: Long = 0L
)

class SeasonManager(private val plugin: SeasonsPlugin) {

    val updateManager = SeasonUpdateManager()

    // Configuration for time cycle (72,000 ticks = 1 hour real-time per season)
    private val seasonDurationTicks: Long = 72_000L

    // PDC utilities
    private val pdcKey = NamespacedKey(plugin, "season_info")

    // Internal cache for instant access
    private var seasonData = SeasonData()

    // Safety flag to prevent overwriting existing world data with defaults before loading completes
    private var isDataLoaded = false

    val currentSeason: Season
        get() = seasonData.season

    /**
     * Called exactly once when the plugin enables.
     */
    fun initialize() {
        loadFromPDC()
        if (plugin.gameplayManager.config.general.enableSeasons) {
            startTimeCycle()
        }
    }

    /**
     * Loads the saved data from the PDC of the main world.
     */
    private fun loadFromPDC() {
        val world = Bukkit.getWorlds().firstOrNull()
        if (world == null) {
            // Worlds are not loaded yet (e.g., startup phase). Wait 1 tick and retry.
            Bukkit.getScheduler().runTask(plugin, Runnable { loadFromPDC() })
            return
        }

        val pdc = world.persistentDataContainer

        // Reading directly as a String bypassing tricky custom Bukkit DataType abstractions
        if (pdc.has(pdcKey, PersistentDataType.STRING)) {
            val json = pdc.get(pdcKey, PersistentDataType.STRING)
            if (json != null) {
                try {
                    seasonData = gson.fromJson(json, SeasonData::class.java) ?: SeasonData()
                    plugin.logger.info("Loaded season data: ${seasonData.season} (${seasonData.passedTicks} ticks)")
                } catch (e: Exception) {
                    plugin.logger.warning("Corrupted season data! Starting fresh.")
                    seasonData = SeasonData()
                }
            }
        } else {
            plugin.logger.info("No previous season data found. Starting fresh with ${seasonData.season}.")
        }

        isDataLoaded = true
        saveToPDC() // Commits the initial state if it was freshly generated
    }

    /**
     * Synchronizes the current cache with the world's PDC.
     */
    fun saveToPDC() {
        if (!isDataLoaded) return // CRITICAL: Don't overwrite data if we haven't read it yet!

        val world = Bukkit.getWorlds().firstOrNull() ?: return
        val json = gson.toJson(seasonData)
        world.persistentDataContainer.set(pdcKey, PersistentDataType.STRING, json)
    }

    /**
     * Changes the current season, triggers the Bukkit event, and updates online players.
     */
    fun setSeason(newSeason: Season) {
        if (currentSeason == newSeason) return

        val oldSeason = currentSeason
        seasonData.season = newSeason
        seasonData.passedTicks = 0L // Reset the cycle timer

        saveToPDC()

        // 1. Call custom Bukkit event
        val event = SeasonChangeEvent(oldSeason, newSeason)
        Bukkit.getPluginManager().callEvent(event)

        plugin.logger.info("Season has changed from $oldSeason to $newSeason!")

        // 2. Broadcast message to players
        Bukkit.broadcastMessage("The season has transitioned to §6${newSeason.name}§e!")

        // 3. Update chunks and registry for all online players seamlessly
        updateManager.applySeasonToAllOnline()
    }

    /**
     * Starts the background task that tracks time and automatically changes seasons.
     */
    private fun startTimeCycle() {
        object : BukkitRunnable() {
            override fun run() {
                if (!isDataLoaded) return // Wait until loading is complete

                // We add 20 ticks (1 second) every execution
                seasonData.passedTicks += 20

                if (seasonData.passedTicks >= seasonDurationTicks) {
                    setSeason(currentSeason.next())
                } else {
                    saveToPDC()
                }
            }
        }.runTaskTimer(plugin, 20L, 20L) // Run every 1 second
    }

}