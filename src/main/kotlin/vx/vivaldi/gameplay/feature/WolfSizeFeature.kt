package vx.vivaldi.gameplay.feature

import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Wolf
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.persistence.PersistentDataType
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.config.lib.annotations.Comment
import kotlin.random.Random

object WolfSizeFeature : Listener {

    class WolfSizeConfig {
        var enabled: Boolean = true

        @Comment("Минимальный множитель размера обычного волка (1.0 - стандартный)")
        var minScale: Double = 0.85

        @Comment("Максимальный множитель размера обычного волка")
        var maxScale: Double = 1.15

        @Comment("Шанс появления огромного волка-аномалии (от 0.0 до 1.0)")
        var anomalyChance: Double = 0.05

        @Comment("Размер волка-аномалии")
        var anomalyScale: Double = 1.65

        @Comment("Должно ли базовое здоровье волка зависеть от его размера?")
        var scaleHealth: Boolean = true
    }

    // Предполагается, что в GameplayConfiguration появится категория vivaldi (или останется в monsters)
    private val cfg get() = plugin.gameplayManager.config.wolves.size

    // PDC ключ, чтобы мы не меняли размер волкам повторно при перезагрузках или других ивентах
    private val processedKey = NamespacedKey(plugin, "wolf_size_processed")

    @EventHandler
    fun onWolfSpawn(event: CreatureSpawnEvent) {
        if (!cfg.enabled) return
        
        val entity = event.entity
        if (entity !is Wolf) return
        if (entity.world.name !in plugin.gameplayManager.allowedWorlds) return

        // Если этот волк уже был обработан нами ранее - пропускаем
        if (entity.persistentDataContainer.has(processedKey, PersistentDataType.BYTE)) return

        // Помечаем волка, чтобы больше не менять его статы
        entity.persistentDataContainer.set(processedKey, PersistentDataType.BYTE, 1.toByte())

        // Определяем, будет ли это волк-аномалия
        val isAnomaly = Random.nextDouble() <= cfg.anomalyChance
        
        val scale = if (isAnomaly) {
            cfg.anomalyScale
        } else {
            Random.nextDouble(cfg.minScale, cfg.maxScale)
        }

        // 1. Применяем размер (доступно в 1.20.5+)
        val scaleAttr = entity.getAttribute(Attribute.SCALE)
        if (scaleAttr != null) {
            scaleAttr.baseValue = scale
        }

        // 2. Скейлим здоровье пропорционально размеру
        if (cfg.scaleHealth) {
            val hpAttr = entity.getAttribute(Attribute.MAX_HEALTH)
            if (hpAttr != null) {
                // Умножаем стандартное базовое ХП на размер
                val newMaxHealth = (hpAttr.baseValue * scale).coerceAtLeast(1.0)
                hpAttr.baseValue = newMaxHealth
                // Восстанавливаем здоровье до нового максимума (ведь волк только что заспавнился)
                entity.health = newMaxHealth
            }
            
            // Если это аномалия, можно также увеличить урон
            if (isAnomaly) {
                val dmgAttr = entity.getAttribute(Attribute.ATTACK_DAMAGE)
                if (dmgAttr != null) {
                    dmgAttr.baseValue *= 1.5 // Урон аномалии на 50% больше
                }
            }
        }
    }

}