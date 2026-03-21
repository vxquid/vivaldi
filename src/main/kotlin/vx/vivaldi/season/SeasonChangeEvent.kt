package vx.vivaldi.season

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import vx.vivaldi.season.Season

/**
 * Called when the world's season changes.
 */
class SeasonChangeEvent(
    val oldSeason: Season,
    val newSeason: Season
) : Event() {

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }

    override fun getHandlers(): HandlerList = handlerList
}