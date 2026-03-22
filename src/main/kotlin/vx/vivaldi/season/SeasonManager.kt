package vx.vivaldi.season

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import vx.vivaldi.Vivaldi

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
     * Updates visual colors for players already online by forcing a chunk refresh.
     */
    private fun updatePlayersForNewSeason() {
        for (player in Bukkit.getOnlinePlayers()) {
            player.sendMessage("§7The environment shifts around you as the new season begins...")

            // Накладываем эффект слепоты на 3 секунды.
            // Это скроет мерцание чанков при их перезагрузке и создаст крутой эффект смены сезона.
            player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 60, 1, false, false, false))

            // Получаем радиус прорисовки (берем наименьшее между серверным и клиентским)
            val viewDistance = player.clientViewDistance.coerceAtMost(Bukkit.getViewDistance())
            val centerChunk = player.location.chunk
            val cx = centerChunk.x
            val cz = centerChunk.z
            val world = player.world

            val chunksToRefresh = mutableListOf<Pair<Int, Int>>()

            // Собираем все чанки вокруг игрока
            for (x in cx - viewDistance..cx + viewDistance) {
                for (z in cz - viewDistance..cz + viewDistance) {
                    chunksToRefresh.add(Pair(x, z))
                }
            }

            // Сортируем так, чтобы чанки ближе к игроку обновлялись в первую очередь
            chunksToRefresh.sortBy { (x, z) ->
                val dx = x - cx
                val dz = z - cz
                dx * dx + dz * dz
            }

            // Растягиваем обновление чанков во времени (staggering),
            // чтобы сервер не завис от массовой генерации пакетов.
            object : BukkitRunnable() {
                var index = 0
                val chunksPerTick = 15 // Обновляем по 15 чанков за тик для каждого игрока

                override fun run() {
                    // Если игрок вышел — прекращаем обновление для него
                    if (!player.isOnline) {
                        cancel()
                        return
                    }

                    for (i in 0 until chunksPerTick) {
                        if (index >= chunksToRefresh.size) {
                            cancel()
                            return
                        }

                        val (x, z) = chunksToRefresh[index]

                        // Метод помечен как @Deprecated, но это единственный "чистый" метод в Bukkit
                        // без использования NMS, который принудительно заставляет сервер отправить
                        // пакет CHUNK_DATA игроку заново. Пакет пройдет через наш PacketEvents-перехватчик!
                        world.refreshChunk(x, z)

                        index++
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L)
        }
    }
}