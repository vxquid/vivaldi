package vx.vivaldi.gameplay.feature.environment

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Snow
import org.bukkit.event.Listener
import org.bukkit.scheduler.BukkitRunnable
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.config.lib.annotations.Comment
import vx.vivaldi.season.Season
import kotlin.random.Random

object SeasonalMeltingFeature : Listener {

    class MeltingConfig {
        var enabled: Boolean = true

        @Comment("How often to run the melting cycle (in ticks). 20 = 1 second")
        var intervalTicks: Long = 20L

        @Comment("How many chunks to randomly process per cycle")
        var chunksPerCycle: Int = 64

        @Comment("How many random blocks to check in each selected chunk. Higher = faster melting.")
        var attemptsPerChunk: Int = 100
    }

    private val cfg get() = plugin.gameplayManager.config.environment.melting
    private var task: BukkitRunnable? = null

    // Оптимизированный массив для проверки 4-х соседей по горизонтали
    private val neighborFaces = arrayOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

    init {
        start()
    }

    fun start() {
        if (!cfg.enabled) return
        if (task != null) return

        task = object : BukkitRunnable() {
            override fun run() {
                val currentSeason = plugin.seasonManager.currentSeason
                val targets = mutableListOf<Block>()

                // Шаг 1: Собираем случайные блоки
                for (world in Bukkit.getWorlds()) {
                    if (world.name !in plugin.gameplayManager.allowedWorlds) continue

                    val loadedChunks = world.loadedChunks
                    if (loadedChunks.isEmpty()) continue

                    val limit = minOf(cfg.chunksPerCycle, loadedChunks.size)
                    for (i in 0 until limit) {
                        val randomChunk = loadedChunks.random()

                        for (attempt in 0 until cfg.attemptsPerChunk) {
                            val lx = Random.nextInt(16)
                            val lz = Random.nextInt(16)

                            val globalX = (randomChunk.x shl 4) + lx
                            val globalZ = (randomChunk.z shl 4) + lz

                            val highestY = world.getHighestBlockYAt(globalX, globalZ)

                            var targetBlock: Block? = null
                            for (y in highestY downTo world.minHeight) {
                                val block = world.getBlockAt(globalX, y, globalZ)
                                val type = block.type

                                if (type.isAir) continue
                                // Пропускаем листву, чтобы снег/лёд под деревьями тоже таял
                                if (type.name.endsWith("_LEAVES")) continue

                                targetBlock = block
                                break
                            }

                            if (targetBlock != null) {
                                targets.add(targetBlock)
                            }
                        }
                    }
                }

                if (targets.isEmpty()) return

                // Шаг 2: Обработка таяния
                for (block in targets) {
                    val type = block.type

                    // Быстрый выход, если это не снег и не лёд
                    if (type != Material.SNOW && !isIce(type)) continue

                    // --- ТЕМПЕРАТУРА И СЕЗОН ---
                    var seasonalTemp = block.temperature
                    when (currentSeason) {
                        Season.SUMMER -> seasonalTemp += 0.4
                        Season.WINTER -> seasonalTemp -= 0.8
                        else -> {}
                    }

                    // Если все еще мороз (< 0.15), ничего не тает
                    if (seasonalTemp < 0.15) continue

                    // --- РАСЧЕТ ВЕРОЯТНОСТИ ТАЯНИЯ (СВЕТ + ТЕМПЕРАТУРА) ---

                    // Чем выше температура над нулем (0.15), тем больше базовый шанс
                    val tempBonus = (seasonalTemp - 0.15).coerceIn(0.1, 1.0)

                    // Уровень света (0-15). Учитывает и солнце (с поправкой на время суток), и факелы.
                    val lightLevel = block.lightLevel.toDouble()

                    // Модификатор света: ночью/в темноте скорость падает до 10%, днем при солнце - 100%
                    val lightModifier = 0.1 + (0.9 * (lightLevel / 15.0))

                    var meltChance = tempBonus * lightModifier

                    // Логика для льда: он тает везде, но у берегов и воды - быстрее
                    if (isIce(type)) {
                        // Лёд сам по себе тает чуть медленнее снега (выглядит реалистичнее)
                        meltChance *= 0.5

                        if (isEdgeOfIce(block)) {
                            // Увеличиваем шанс в 2.5 раза, если лёд касается берега или воды (тает от краев)
                            meltChance *= 2.5
                        }
                    }

                    // Бросаем кубик. Если случайное число больше нашего шанса - пропускаем таяние в этот тик.
                    if (Random.nextDouble() > meltChance) continue

                    // Применяем таяние
                    if (type == Material.SNOW) {
                        val snowData = block.blockData as? Snow
                        if (snowData != null) {
                            if (snowData.layers > 1) {
                                snowData.layers -= 1
                                block.setBlockData(snowData, false)
                            } else {
                                block.type = Material.AIR
                            }
                        }
                    } else if (isIce(type)) {
                        block.type = Material.WATER
                    }
                }
            }
        }

        task?.runTaskTimer(plugin, 60L, cfg.intervalTicks)
    }

    /**
     * Проверяет, является ли блок краем льда (касается ли он воды или земли).
     * Это нужно, чтобы водоемы красиво таяли от краев к центру.
     */
    private fun isEdgeOfIce(block: Block): Boolean {
        val world = block.world
        for (face in neighborFaces) {
            val neighbor = block.getRelative(face)

            // Защита от прогрузки чанков
            if (!world.isChunkLoaded(neighbor.x shr 4, neighbor.z shr 4)) continue

            val type = neighbor.type

            // Если сосед - вода ИЛИ твердый блок (земля, песок), но НЕ лёд и НЕ снег
            if (type == Material.WATER || (type.isSolid && !isIce(type) && type != Material.SNOW && type != Material.SNOW_BLOCK)) {
                return true
            }
        }
        return false
    }

    private fun isIce(material: Material): Boolean {
        // Обычный лед. Если хотите, чтобы Packed Ice и Blue Ice не таяли вообще,
        // просто уберите их из этого списка (в ваниле они не тают).
        return material == Material.ICE ||
                material == Material.PACKED_ICE ||
                material == Material.BLUE_ICE ||
                material == Material.FROSTED_ICE
    }

    fun stop() {
        task?.cancel()
        task = null
    }
}