package vx.vivaldi.season.biome

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import vx.vivaldi.Vivaldi.Companion.plugin

class SeasonUpdateManager {

    /**
     * Плавно переотправляет чанки вокруг игрока.
     * Создает визуальный эффект "волны" смены сезона от центра к краям.
     */
    fun playSeasonalTransition(player: Player) {
        val world = player.world
        val cx = player.location.chunk.x
        val cz = player.location.chunk.z

        // Получаем реальную дальность прорисовки клиента
        val viewDist = player.clientViewDistance.coerceAtMost(Bukkit.getViewDistance())

        val chunksToUpdate = mutableListOf<Pair<Int, Int>>()

        // Собираем все загруженные чанки в радиусе видимости
        for (x in cx - viewDist..cx + viewDist) {
            for (z in cz - viewDist..cz + viewDist) {
                if (world.isChunkLoaded(x, z)) {
                    chunksToUpdate.add(x to z)
                }
            }
        }

        // Сортируем чанки по удаленности от игрока (ближайшие обновятся первыми)
        chunksToUpdate.sortBy { (it.first - cx) * (it.first - cx) + (it.second - cz) * (it.second - cz) }

        player.sendMessage("§7The environment shifts around you as the new season begins...")

        // Накладываем эффект слепоты, чтобы скрыть мерцание переотправки чанков
        player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 60, 1, false, false, false))

        // Запускаем асинхронную отправку
        object : BukkitRunnable() {
            var index = 0
            val chunksPerTick = 15 // Обновляем по 15 чанков в тик для безопасности TPS

            override fun run() {
                // Если игрок вышел или мы обновили все чанки — тормозим таск
                if (!player.isOnline || index >= chunksToUpdate.size) {
                    cancel()
                    return
                }

                for (i in 0 until chunksPerTick) {
                    if (index >= chunksToUpdate.size) break
                    val (qx, qz) = chunksToUpdate[index]

                    // Самый легальный и безопасный метод заставить сервер сформировать
                    // новый пакет CHUNK_DATA. Он пройдет через наш BiomeRegistryInterceptor
                    // и получит новые сезонные биомы.
                    @Suppress("DEPRECATION")
                    world.refreshChunk(qx, qz)

                    index++
                }
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    /**
     * Вызывайте этот метод, когда на сервере глобально меняется сезон
     */
    fun applySeasonToAllOnline() {
        for (player in Bukkit.getOnlinePlayers()) {
            playSeasonalTransition(player)
        }
    }
}