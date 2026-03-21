package vx.vivaldi.gameplay.feature

import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Wolf
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.config.lib.annotations.Comment
import kotlin.random.Random

object WolfLeapFeature : Listener {

    class WolfLeapConfig {
        var enabled: Boolean = true

        @Comment("Шанс прыжка (0.0 - 1.0) при каждой проверке")
        var leapChance: Double = 0.17

        @Comment("Интервал проверки (в тиках). 10 = 2 раза в секунду")
        var checkIntervalTicks: Long = 10L

        @Comment("Кулдаун между прыжками для одного волка (в миллисекундах)")
        var cooldownMillis: Long = 4000L

        @Comment("Минимальная дистанция для прыжка (в блоках)")
        var minDistance: Double = 3.0

        @Comment("Максимальная дистанция для прыжка (в блоках)")
        var maxDistance: Double = 8.0

        @Comment("Сила рывка (множитель вектора скорости)")
        var leapVelocity: Double = 1.3

        @Comment("Базовая высота прыжка (Y-вектор)")
        var leapHeight: Double = 0.4

        @Comment("Длительность замедления при успешном прыжке (в тиках, 20 = 1 сек)")
        var slownessDurationTicks: Int = 20

        @Comment("Уровень замедления (0 = Уровень 1, 1 = Уровень 2)")
        var slownessAmplifier: Int = 1
        
        @Comment("Сколько времени волк считается 'в прыжке' для нанесения дебаффа (миллисекунды)")
        var leapStateDurationMillis: Long = 1500L
    }

    // Предполагается, что в GameplayConfiguration добавлена категория: config.vivaldi.leap
    private val cfg get() = plugin.gameplayManager.config.wolves.leap

    // Ключи для хранения в PDC
    private val cooldownKey = NamespacedKey(plugin, "wolf_leap_cooldown")
    private val leapStateKey = NamespacedKey(plugin, "wolf_leap_timestamp")

    init {
        // Запускаем периодическую проверку целей у волков
        plugin.server.scheduler.runTaskTimer(plugin, this::tickLeap, 20L, cfg.checkIntervalTicks)
    }

    private fun tickLeap() {
        if (!cfg.enabled) return
        
        val currentTime = System.currentTimeMillis()
        val minDistSq = cfg.minDistance * cfg.minDistance
        val maxDistSq = cfg.maxDistance * cfg.maxDistance

        plugin.gameplayManager.allowedWorlds.forEach { worldName ->
            val world = plugin.server.getWorld(worldName) ?: return@forEach

            world.livingEntities.forEach { entity ->
                if (entity !is Wolf) return@forEach
                
                // Волк должен иметь цель
                val target = entity.target ?: return@forEach
                if (target.isDead || target.world != entity.world) return@forEach

                // Базовые проверки состояния волка (он не должен сидеть или плавать)
                if (entity.isSitting) return@forEach
                if (entity.isInWater) return@forEach
                
                // Волк должен видеть цель, чтобы не прыгать сквозь стены
                if (!entity.hasLineOfSight(target)) return@forEach

                // Проверка дистанции
                val distSq = entity.location.distanceSquared(target.location)
                if (distSq !in minDistSq..maxDistSq) return@forEach

                val pdc = entity.persistentDataContainer
                
                // Проверка кулдауна на прыжок
                val cooldownUntil = pdc.get(cooldownKey, PersistentDataType.LONG) ?: 0L
                if (currentTime < cooldownUntil) return@forEach

                // Ролл вероятности прыжка
                if (Random.nextDouble() > cfg.leapChance) return@forEach

                // --- Логика прыжка ---
                
                // 1. Вычисляем вектор направления к цели
                val direction = target.location.toVector().subtract(entity.location.toVector()).normalize()
                
                // Динамическая высота: если цель стоит выше волка, прыгаем чуть выше
                val yDiff = target.location.y - entity.location.y
                val yVelocity = if (yDiff > 0) cfg.leapHeight + (yDiff * 0.1) else cfg.leapHeight

                // Применяем векторы
                direction.multiply(cfg.leapVelocity)
                direction.y = yVelocity
                
                // Толкаем волка
                entity.velocity = direction

                // 2. Визуал и звук (грозный рык при прыжке)
                world.playSound(entity.location, Sound.ENTITY_WOLF_GROWL, 1.0f, 0.8f)

                // 3. Записываем состояние прыжка и вешаем кулдаун
                pdc.set(leapStateKey, PersistentDataType.LONG, currentTime)
                pdc.set(cooldownKey, PersistentDataType.LONG, currentTime + cfg.cooldownMillis)
            }
        }
    }

    @EventHandler
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (!cfg.enabled) return
        
        val damager = event.damager
        val victim = event.entity

        // Проверяем, что бьет волк по живому существу
        if (damager !is Wolf) return
        if (victim !is LivingEntity) return

        val pdc = damager.persistentDataContainer
        
        // Достаем метку времени последнего прыжка
        val leapTimestamp = pdc.get(leapStateKey, PersistentDataType.LONG) ?: return
        val currentTime = System.currentTimeMillis()
        
        // Если волк нанес урон в течение leapStateDurationMillis (1.5 сек) после начала прыжка
        if (currentTime - leapTimestamp <= cfg.leapStateDurationMillis) {
            
            // Накладываем замедление на жертву
            // Примечание: В версиях < 1.20.6 используй PotionEffectType.SLOW
            // В 1.20.6+ тип был переименован в PotionEffectType.SLOWNESS
            victim.addPotionEffect(PotionEffect(
                PotionEffectType.SLOWNESS, 
                cfg.slownessDurationTicks, 
                cfg.slownessAmplifier, 
                false, // не фоновый эффект
                true   // показывать частицы
            ))

            // Очищаем метку прыжка, чтобы волк не наложил замедление дважды за один полет
            pdc.remove(leapStateKey)
            
            // Дополнительный звук глухого удара для сочности (как shield block, только тише)
            victim.world.playSound(victim.location, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.6f)
        }
    }
}