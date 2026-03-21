package vx.vivaldi.season

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitRunnable
import vx.vivaldi.Vivaldi

class SeasonManager(private val plugin: Vivaldi) {

    // The currently active season
    var currentSeason: Season = Season.AUTUMN
        private set

    // Configuration for time cycle (e.g., how many ticks a season lasts)
    // 20 ticks * 60 seconds * 60 minutes * 24 hours = 1 real life day (1,728,000 ticks)
    // Let's default to a smaller value for testing: 1 hour (72,000 ticks)
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

        // 3. Update chunks and registry for all online players
        updatePlayersForNewSeason()
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

    /**
     * Updates visual colors for players already online.
     */
    private fun updatePlayersForNewSeason() {
        /*
         * CRITICAL NOTE ON 1.20.5+ ARCHITECTURE:
         * Registry Data (Biome Colors) is ONLY sent during the Configuration Phase.
         * To apply the new seasonal colors to players currently playing, we must transition
         * them back to the Configuration Phase, resend the registry, and return them to Play Phase.
         */

        for (player in Bukkit.getOnlinePlayers()) {

            // FIXME: Transition the player to Configuration Phase and back.
            // Using PacketEvents or Paper API to send ClientboundStartConfigurationPacket.
            // This forces the client to re-download the Registry Packet (intercepted by our BiomeRegistryInterceptor)
            // and then forces chunks to redraw with new seasonal colors.

            // Temporarily (until we write the Configuration phase transition logic),
            // you can just notify them to relog if they want to see the new season:
            player.sendMessage("§7Please re-login to synchronize seasonal visual changes.")
        }
    }
}