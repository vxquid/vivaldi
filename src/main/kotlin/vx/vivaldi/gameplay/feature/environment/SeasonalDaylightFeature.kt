package vx.vivaldi.gameplay.feature.environment

import org.bukkit.Bukkit
import org.bukkit.GameRules
import org.bukkit.event.Listener
import org.bukkit.scheduler.BukkitRunnable
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.config.lib.annotations.Comment
import vx.vivaldi.season.Season
import java.util.UUID

object SeasonalDaylightFeature : Listener {

    class DaylightConfig {
        var enabled: Boolean = true

        @Comment("Time speed multipliers. < 1.0 = slower (longer phase), > 1.0 = faster (shorter phase)")
        var summerDaySpeed: Double = 0.8
        var summerNightSpeed: Double = 1.3

        @Comment("For Winter: days are shorter (faster), nights are longer (slower)")
        var winterDaySpeed: Double = 1.3
        var winterNightSpeed: Double = 0.8

        @Comment("Standard speed for Spring and Autumn")
        var standardSpeed: Double = 1.0
    }

    private val cfg get() = plugin.gameplayManager.config.environment.daylight

    private var task: BukkitRunnable? = null

    // Accumulates fractional ticks for smooth time additions/freezes
    private val timeAccumulators = mutableMapOf<UUID, Double>()

    // Keeps track of worlds whose daylight cycle was paused by THIS script.
    // If a world is paused but NOT in this set, it means an admin paused it manually.
    private val pausedByUs = mutableSetOf<UUID>()

    init {
        start()
    }

    fun start() {
        if (!cfg.enabled) return
        if (task != null) return

        task = object : BukkitRunnable() {
            override fun run() {
                val currentSeason = plugin.seasonManager.currentSeason

                for (world in Bukkit.getWorlds()) {
                    if (world.name !in plugin.gameplayManager.allowedWorlds) continue

                    // Note: Use standard Bukkit GameRule.DO_DAYLIGHT_CYCLE
                    val isPausedByRule = world.getGameRuleValue(GameRules.ADVANCE_TIME) == false

                    // Respect the vanilla gamerule. If an admin manually paused time, we do nothing.
                    if (isPausedByRule && !pausedByUs.contains(world.uid)) {
                        continue
                    }

                    val time = world.time
                    // Minecraft Day is strictly 0 to 12000, Night is 12000 to 24000
                    val isDay = time in 0..12000

                    val speedMultiplier = when (currentSeason) {
                        Season.SUMMER -> if (isDay) cfg.summerDaySpeed else cfg.summerNightSpeed
                        Season.WINTER -> if (isDay) cfg.winterDaySpeed else cfg.winterNightSpeed
                        else -> cfg.standardSpeed
                    }

                    if (speedMultiplier == 1.0) {
                        // Standard speed: restore gamerule to true if we previously paused it
                        if (pausedByUs.contains(world.uid)) {
                            world.setGameRule(GameRules.ADVANCE_TIME, true)
                            pausedByUs.remove(world.uid)
                        }
                        continue
                    }

                    if (speedMultiplier < 1.0) {
                        // SLOWER TIME
                        // Instead of moving time backwards (which breaks shaders), we pause the vanilla daylight
                        // cycle rule periodically.
                        val deficit = 1.0 - speedMultiplier
                        val acc = timeAccumulators.getOrDefault(world.uid, 0.0) + deficit

                        if (acc >= 1.0) {
                            // Freeze time for this server tick (50ms)
                            if (!isPausedByRule) {
                                world.setGameRule(GameRules.ADVANCE_TIME, false)
                                pausedByUs.add(world.uid)
                            }
                            timeAccumulators[world.uid] = acc - 1.0
                        } else {
                            // Let time flow naturally for this server tick
                            if (isPausedByRule) {
                                world.setGameRule(GameRules.ADVANCE_TIME, true)
                                pausedByUs.remove(world.uid)
                            }
                            timeAccumulators[world.uid] = acc
                        }
                    } else {
                        // FASTER TIME
                        // Ensure the cycle is running normally
                        if (isPausedByRule) {
                            world.setGameRule(GameRules.ADVANCE_TIME, true)
                            pausedByUs.remove(world.uid)
                        }

                        // Vanilla already adds 1 tick organically. We just add the surplus.
                        val surplus = speedMultiplier - 1.0
                        val acc = timeAccumulators.getOrDefault(world.uid, 0.0) + surplus

                        if (acc >= 1.0) {
                            val add = acc.toLong()
                            // Moving time FORWARD is completely safe and won't break shaders
                            world.time = (time + add) % 24000
                            timeAccumulators[world.uid] = acc - add
                        } else {
                            timeAccumulators[world.uid] = acc
                        }
                    }
                }
            }
        }

        // Run every tick to guarantee smooth sun/moon movement
        task?.runTaskTimer(plugin, 1L, 1L)
    }

    fun stop() {
        task?.cancel()
        task = null

        // Cleanup: Restore daylight cycle seamlessly if the plugin reloads or stops
        for (uid in pausedByUs) {
            val world = Bukkit.getWorld(uid)
            world?.setGameRule(GameRules.ADVANCE_TIME, true)
        }

        pausedByUs.clear()
        timeAccumulators.clear()
    }
}