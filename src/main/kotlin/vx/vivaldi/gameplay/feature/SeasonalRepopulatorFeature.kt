package vx.vivaldi.gameplay.feature

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.Bisected
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.persistence.PersistentDataType
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.config.lib.annotations.Comment
import vx.vivaldi.season.Season
import kotlin.random.Random

object SeasonalRepopulatorFeature : Listener {

    class RepopulatorConfig {
        var enabled: Boolean = true

        @Comment("Сколько попыток создать полянку растений в новом чанке (отвечает за густоту)")
        var attemptsPerChunk: Int = 20

        @Comment("Множитель густоты травы у воды")
        var waterProximityMultiplier: Double = 2.0

        @Comment("Шансы появления разных видов растений (0.0 - 1.0)")
        var flowerChance: Double = 0.15
        var tallGrassChance: Double = 0.30
        var wheatChance: Double = 0.02

        @Comment("Минимальное и максимальное количество травинок в одном кластере")
        var minGrassCluster: Int = 3
        var maxGrassCluster: Int = 7
    }

    private val cfg get() = plugin.gameplayManager.config.environment.repopulator

    // Ключ для пометки чанков, которые уже были "заселены" нашим репопулятором
    private val repopulatedKey = NamespacedKey(plugin, "repopulated_seasonally")

    private val flowers = listOf(
        Material.DANDELION, Material.POPPY, Material.CORNFLOWER,
        Material.OXEYE_DAISY, Material.AZURE_BLUET, Material.ALLIUM
    )

    private enum class GrowthMode {
        NORMAL, WHEAT, TALL_PLANT
    }

    private data class PlantPlacement(
        val x: Int,
        val z: Int,
        val type: Material,
        val mode: GrowthMode = GrowthMode.NORMAL
    )

    init {
        // Регистрируем ивенты автоматически
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (!cfg.enabled) return

        // Работаем ТОЛЬКО с только что сгенерированными чанками
        if (!event.isNewChunk) return

        val chunk = event.chunk
        val world = chunk.world

        if (world.name !in plugin.gameplayManager.allowedWorlds) return

        val currentSeason = plugin.seasonManager.currentSeason
        // Репопулятор работает только в период буйного роста
        if (currentSeason != Season.SPRING && currentSeason != Season.SUMMER) return

        // Защита PDC: проверяем, не был ли этот чанк уже обработан (на случай сбоев генератора)
        val pdc = chunk.persistentDataContainer
        if (pdc.has(repopulatedKey, PersistentDataType.BYTE)) return
        pdc.set(repopulatedKey, PersistentDataType.BYTE, 1.toByte())

        // Делаем снимок чанка для асинхронного сканирования
        val snapshot = chunk.getChunkSnapshot(true, false, false)
        val chunkX = chunk.x
        val chunkZ = chunk.z

        // Уходим в асинхронный поток, чтобы не стопить сервер при исследовании мира игроками
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val placements = mutableListOf<PlantPlacement>()

            for (i in 0 until cfg.attemptsPerChunk) {
                val lx = Random.nextInt(16)
                val lz = Random.nextInt(16)

                val highestY = snapshot.getHighestBlockYAt(lx, lz)
                var groundY = -1

                // Ищем поверхность земли
                for (y in highestY downTo world.minHeight) {
                    val type = snapshot.getBlockType(lx, y, lz)
                    if (type == Material.GRASS_BLOCK || type == Material.PODZOL) {
                        groundY = y
                        break
                    }
                    if (type.isSolid && !type.name.contains("LEAVES") && !type.name.contains("LOG") && !type.name.contains("WOOD")) {
                        break
                    }
                }

                if (groundY == -1 || groundY >= world.maxHeight - 2) continue

                val airBlock = snapshot.getBlockType(lx, groundY + 1, lz)
                if (airBlock.isAir) {
                    val skyLight = snapshot.getBlockSkyLight(lx, groundY + 1, lz)
                    if (skyLight < 9) continue // Растениям нужен свет

                    val globalX = (chunkX * 16) + lx
                    val globalZ = (chunkZ * 16) + lz

                    // Сканируем воду поблизости (радиус 4)
                    var nearWater = false
                    val minWX = maxOf(0, lx - 4)
                    val maxWX = minOf(15, lx + 4)
                    val minWZ = maxOf(0, lz - 4)
                    val maxWZ = minOf(15, lz + 4)
                    val minWY = maxOf(world.minHeight, groundY - 2)
                    val maxWY = minOf(world.maxHeight - 1, groundY + 1)

                    waterSearch@ for (wx in minWX..maxWX) {
                        for (wz in minWZ..maxWZ) {
                            for (wy in minWY..maxWY) {
                                if (snapshot.getBlockType(wx, wy, wz) == Material.WATER) {
                                    nearWater = true
                                    break@waterSearch
                                }
                            }
                        }
                    }

                    val clusterMult = if (nearWater) cfg.waterProximityMultiplier else 1.0
                    val roll = Random.nextDouble()

                    when {
                        roll < cfg.wheatChance -> {
                            val baseCluster = Random.nextInt(3, 5)
                            val clusterSize = (baseCluster * clusterMult).toInt()
                            for (c in 0 until clusterSize) {
                                val offsetX = globalX + Random.nextInt(-2, 3)
                                val offsetZ = globalZ + Random.nextInt(-2, 3)
                                placements.add(PlantPlacement(offsetX, offsetZ, Material.WHEAT, GrowthMode.WHEAT))
                            }
                        }
                        roll < (cfg.wheatChance + cfg.flowerChance) -> {
                            // Цветы спавнятся без кластеров (чтобы не превращать мир в клумбу)
                            placements.add(PlantPlacement(globalX, globalZ, flowers.random(), GrowthMode.NORMAL))
                        }
                        roll < (cfg.wheatChance + cfg.flowerChance + cfg.tallGrassChance) -> {
                            val baseCluster = Random.nextInt(cfg.minGrassCluster, cfg.maxGrassCluster + 1)
                            val clusterSize = (baseCluster * clusterMult).toInt()
                            for (c in 0 until clusterSize) {
                                val offsetX = globalX + Random.nextInt(-2, 3)
                                val offsetZ = globalZ + Random.nextInt(-2, 3)
                                placements.add(PlantPlacement(offsetX, offsetZ, Material.TALL_GRASS, GrowthMode.TALL_PLANT))
                            }
                        }
                        else -> {
                            val baseCluster = Random.nextInt(cfg.minGrassCluster, cfg.maxGrassCluster + 1)
                            val clusterSize = (baseCluster * clusterMult).toInt()
                            for (c in 0 until clusterSize) {
                                val offsetX = globalX + Random.nextInt(-2, 3)
                                val offsetZ = globalZ + Random.nextInt(-2, 3)
                                placements.add(PlantPlacement(offsetX, offsetZ, Material.SHORT_GRASS, GrowthMode.NORMAL))
                            }
                        }
                    }
                }
            }

            // Возвращаемся в синхронный поток для расстановки
            if (placements.isNotEmpty()) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    // Критически важно: проверяем загружен ли ещё чанк (игрок мог пролететь на элитрах и чанк выгрузился)
                    if (!world.isChunkLoaded(chunkX, chunkZ)) return@Runnable

                    for (p in placements) {
                        val actualY = findGroundY(world, p.x, p.z)
                        if (actualY == -1) continue

                        val groundBlock = world.getBlockAt(p.x, actualY, p.z)
                        val airBlock = world.getBlockAt(p.x, actualY + 1, p.z)
                        val isValidGround = groundBlock.type == Material.GRASS_BLOCK || groundBlock.type == Material.PODZOL

                        if (isValidGround && airBlock.type.isAir) {
                            when (p.mode) {
                                GrowthMode.WHEAT -> {
                                    if (groundBlock.type == Material.GRASS_BLOCK) {
                                        groundBlock.type = Material.FARMLAND
                                        airBlock.type = Material.WHEAT
                                        val blockData = airBlock.blockData
                                        if (blockData is Ageable) {
                                            blockData.age = Random.nextInt(0, 4)
                                            airBlock.blockData = blockData
                                        }
                                    }
                                }
                                GrowthMode.TALL_PLANT -> {
                                    val upperAir = world.getBlockAt(p.x, actualY + 2, p.z)
                                    if (upperAir.type.isAir) {
                                        airBlock.type = p.type
                                        val bottomData = airBlock.blockData as? Bisected
                                        if (bottomData != null) {
                                            bottomData.half = Bisected.Half.BOTTOM
                                            airBlock.blockData = bottomData
                                        }

                                        upperAir.type = p.type
                                        val topData = upperAir.blockData as? Bisected
                                        if (topData != null) {
                                            topData.half = Bisected.Half.TOP
                                            upperAir.blockData = topData
                                        }
                                    }
                                }
                                GrowthMode.NORMAL -> {
                                    airBlock.type = p.type
                                }
                            }
                        }
                    }
                })
            }
        })
    }

    private fun findGroundY(world: World, x: Int, z: Int): Int {
        val highestY = world.getHighestBlockYAt(x, z)
        for (y in highestY downTo world.minHeight) {
            val block = world.getBlockAt(x, y, z)
            if (block.type == Material.GRASS_BLOCK || block.type == Material.PODZOL) return y
            if (block.type.isSolid && !block.type.name.contains("LEAVES") && !block.type.name.contains("LOG") && !block.type.name.contains("WOOD")) break
        }
        return -1
    }
}