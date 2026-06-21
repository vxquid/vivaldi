package vx.seasons.season.biome

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import vx.seasons.SeasonsPlugin.Companion.plugin
import kotlin.math.abs
import kotlin.math.max

class SeasonUpdateManager {

    /**
     * Вызывайте этот метод, когда на сервере глобально меняется сезон
     */
    fun applySeasonToAllOnline() {
        // Используем Set (множество), чтобы ни один чанк не попал в список дважды,
        // даже если рядом стоят 100 игроков. Это спасает от сетевого DDoS-а.
        val chunksToUpdate = mutableSetOf<Pair<World, Pair<Int, Int>>>()
        val playerCenters = mutableListOf<Pair<Player, Pair<Int, Int>>>()

        for (player in Bukkit.getOnlinePlayers()) {
            player.sendMessage("§b❄ §7The environment shifts around you as the new season begins...")
            // Слепота чуть подольше, так как волна глобальная и может идти 5-10 секунд
            player.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 20, 1, false, false, false))

            val world = player.world
            if (world.name !in plugin.gameplayManager.allowedWorlds) continue

            val cx = player.location.chunk.x
            val cz = player.location.chunk.z
            playerCenters.add(player to (cx to cz))

            // Берем чистую дальность клиента БЕЗ coerceAtMost().
            // Если игрок видит на 16 чанков, мы пытаемся обновить 16.
            // Проверка isChunkLoaded сама отсеет то, чего нет на сервере.
            val viewDist = player.clientViewDistance

            for (x in cx - viewDist..cx + viewDist) {
                for (z in cz - viewDist..cz + viewDist) {
                    if (world.isChunkLoaded(x, z)) {
                        chunksToUpdate.add(world to (x to z))
                    }
                }
            }
        }

        if (chunksToUpdate.isEmpty()) return

        // Сортировка чанков от центра к краям для красивого эффекта разрастания сезона.
        // Ищем минимальную дистанцию от конкретного чанка до ЛЮБОГО игрока в этом мире.
        val sortedChunks = chunksToUpdate.sortedBy { chunkEntry ->
            val world = chunkEntry.first
            val cx = chunkEntry.second.first
            val cz = chunkEntry.second.second

            var minDist = Int.MAX_VALUE
            for ((player, center) in playerCenters) {
                if (player.world != world) continue
                val dist = max(abs(cx - center.first), abs(cz - center.second))
                if (dist < minDist) {
                    minDist = dist
                }
            }
            minDist
        }

        // Запускаем ОДИН глобальный асинхронный отправитель вместо десятка локальных
        object : BukkitRunnable() {
            var index = 0

            // 15 чанков в тик глобально. Это ~300 чанков в секунду.
            // Это абсолютно безопасно для любого железа и сети, клиент не захлебнётся.
            val chunksPerTick = 15

            override fun run() {
                if (index >= sortedChunks.size) {
                    cancel()
                    return
                }

                for (i in 0 until chunksPerTick) {
                    if (index >= sortedChunks.size) break

                    val (world, coords) = sortedChunks[index]
                    val (qx, qz) = coords

                    // Исключаем ситуацию, когда игрок убежал, а чанк уже выгрузился
                    if (world.isChunkLoaded(qx, qz)) {
                        @Suppress("DEPRECATION")
                        world.refreshChunk(qx, qz)
                    }

                    index++
                }
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

}