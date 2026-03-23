package vx.vivaldi.season

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.scheduler.BukkitRunnable
import vx.vivaldi.Vivaldi
import vx.vivaldi.season.biome.SeasonUpdateManager

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

class SeasonManager(private val plugin: Vivaldi) {

    val updateManager = SeasonUpdateManager()

    // The currently active season
    var currentSeason: Season = Season.AUTUMN
        private set

    // Configuration for time cycle
    private val seasonDurationTicks: Long = 72_000L
    private var passedTicks: Long = 0L

    init {
        // TODO: Load saved season and passedTicks from a database or flatfile
    }

    /**
     * Changes the current season, triggers the Bukkit event, and updates online players.
     */
    fun setSeason(newSeason: Season) {
        if (currentSeason == newSeason) return

        val oldSeason = currentSeason
        currentSeason = newSeason
        passedTicks = 0L // Reset the cycle timer

        // 1. Call custom Bukkit event
        val event = SeasonChangeEvent(oldSeason, newSeason)
        Bukkit.getPluginManager().callEvent(event)

        plugin.logger.info("§a[Vivaldi] Season has changed from $oldSeason to $newSeason!")

        // 2. Broadcast message to players
        Bukkit.broadcastMessage("§6[Vivaldi] §eThe season has transitioned to §6${newSeason.name}§e!")

        // 3. Update chunks and registry for all online players seamlessly
        updateManager.applySeasonToAllOnline()
    }

    /**
     * Starts the background task that tracks time and automatically changes seasons.
     */
    fun startTimeCycle() {
        object : BukkitRunnable() {
            override fun run() {
                // We add 20 ticks (1 second) every execution
                passedTicks += 20

                if (passedTicks >= seasonDurationTicks) {
                    // Transition to the next season in the cycle
                    setSeason(currentSeason.next())
                }
            }
        }.runTaskTimer(plugin, 20L, 20L) // Run every 1 second
    }
}