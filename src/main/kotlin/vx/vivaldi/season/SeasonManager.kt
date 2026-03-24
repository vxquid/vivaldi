package vx.vivaldi.season

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.persistence.PersistentDataAdapterContext
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitRunnable
import vx.vivaldi.Vivaldi
import vx.vivaldi.Vivaldi.Companion.gson
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

// Data class для упаковки состояния, который GSON будет конвертировать в JSON
data class SeasonData(
    var season: Season = Season.AUTUMN,
    var passedTicks: Long = 0L
)

// Кастомный тип данных для PDC, связывающий GSON и Bukkit API
class SeasonDataType : PersistentDataType<String, SeasonData> {
    override fun getPrimitiveType(): Class<String> = String::class.java
    override fun getComplexType(): Class<SeasonData> = SeasonData::class.java

    override fun toPrimitive(complex: SeasonData, context: PersistentDataAdapterContext): String {
        return gson.toJson(complex)
    }

    override fun fromPrimitive(primitive: String, context: PersistentDataAdapterContext): SeasonData {
        return try {
            gson.fromJson(primitive, SeasonData::class.java) ?: SeasonData()
        } catch (e: Exception) {
            SeasonData() // Возвращаем дефолт при ошибке чтения
        }
    }
}

class SeasonManager(private val plugin: Vivaldi) {

    val updateManager = SeasonUpdateManager()

    // Configuration for time cycle
    private val seasonDurationTicks: Long = 72_000L

    // Инструменты работы с PDC
    private val pdcKey = NamespacedKey(plugin, "season_info")
    private val pdcType = SeasonDataType()

    // Внутренний кэш для мгновенного доступа без десериализации каждый раз
    private var seasonData = SeasonData()

    // The currently active season (читает напрямую из кэша)
    val currentSeason: Season
        get() = seasonData.season

    init {
        loadFromPDC()
    }

    /**
     * Загружает сохраненные данные из PDC главного мира.
     */
    private fun loadFromPDC() {
        // Сохраняем глобальную инфу в главном мире (index 0)
        val world = Bukkit.getWorlds().firstOrNull() ?: return
        val pdc = world.persistentDataContainer

        if (pdc.has(pdcKey, pdcType)) {
            seasonData = pdc.get(pdcKey, pdcType) ?: SeasonData()
            plugin.logger.info("§a[Vivaldi] Loaded season data from PDC: ${seasonData.season} (${seasonData.passedTicks} ticks)")
        } else {
            saveToPDC() // Устанавливаем и сохраняем начальные значения
        }
    }

    /**
     * Синхронизирует текущий кэш с PDC мира.
     */
    fun saveToPDC() {
        val world = Bukkit.getWorlds().firstOrNull() ?: return
        world.persistentDataContainer.set(pdcKey, pdcType, seasonData)
    }

    /**
     * Changes the current season, triggers the Bukkit event, and updates online players.
     */
    fun setSeason(newSeason: Season) {
        if (currentSeason == newSeason) return

        val oldSeason = currentSeason
        seasonData.season = newSeason
        seasonData.passedTicks = 0L // Reset the cycle timer

        // Сохраняем сброс таймера и новый сезон в мир
        saveToPDC()

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
                seasonData.passedTicks += 20

                if (seasonData.passedTicks >= seasonDurationTicks) {
                    // Transition to the next season in the cycle
                    setSeason(currentSeason.next())
                } else {
                    // Обновляем PDC. В Bukkit PDC висит в оперативной памяти и сбрасывается
                    // на диск сервером пакетом при автосохранении мира (каждые ~5 мин).
                    // Поэтому обновлять его каждую секунду здесь абсолютно безопасно для TPS.
                    saveToPDC()
                }
            }
        }.runTaskTimer(plugin, 20L, 20L) // Run every 1 second
    }
}