package vx.vivaldi.gameplay.feature

import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Wolf
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.persistence.PersistentDataType
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.config.lib.annotations.Comment
import kotlin.random.Random

object WolfAttributesFeature : Listener {

    class WolfAttributesConfig {
        var enabled: Boolean = true

        @Comment("Минимальный множитель статов (очень слабые волки, генетический мусор)")
        var minMultiplier: Double = 0.75

        @Comment("Максимальный множитель статов (сильнейшие волки)")
        var maxMultiplier: Double = 1.45

        @Comment("Порог потенциала (0.0 - 1.0), при котором волк становится Альфой. 0.85 = лучшие 15%")
        var alphaThreshold: Double = 0.85

        @Comment("Дополнительная плоская броня, которую получают только Альфа-волки")
        var alphaBonusArmor: Double = 6.0
    }

    private val cfg get() = plugin.gameplayManager.config.wolves.attributes

    private val geneticsKey = NamespacedKey(plugin, "wolf_genetics_applied")
    private val isAlphaKey = NamespacedKey(plugin, "wolf_is_alpha")

    // Для 1.21+ используем NamespacedKey вместо устаревших UUID для идентификации модификаторов
    private val HEALTH_MOD_KEY = NamespacedKey(plugin, "wolf_genetic_hp")
    private val SPEED_MOD_KEY = NamespacedKey(plugin, "wolf_genetic_speed")
    private val DAMAGE_MOD_KEY = NamespacedKey(plugin, "wolf_genetic_damage")
    private val ARMOR_MOD_KEY = NamespacedKey(plugin, "wolf_alpha_armor")

    // Метод генерации Гауссова распределения (имитация ванильной механики генерации лошадей)
    // Дает красивую колоколообразную кривую с пиком в 0.5
    private fun generateBellCurveValue(): Double {
        return (Random.nextDouble() + Random.nextDouble() + Random.nextDouble()) / 3.0
    }

    @EventHandler
    fun onWolfSpawn(event: CreatureSpawnEvent) {
        if (!cfg.enabled) return

        val entity = event.entity
        if (entity !is Wolf) return
        if (entity.world.name !in plugin.gameplayManager.allowedWorlds) return

        val pdc = entity.persistentDataContainer
        if (pdc.has(geneticsKey, PersistentDataType.BYTE)) return
        pdc.set(geneticsKey, PersistentDataType.BYTE, 1.toByte())

        // 1. Высчитываем "Генетический потенциал" от 0.0 до 1.0
        val geneticPotential = generateBellCurveValue()

        // 2. Интерполируем потенциал между минимальным и максимальным множителем
        val multiplier = cfg.minMultiplier + ((cfg.maxMultiplier - cfg.minMultiplier) * geneticPotential)

        // 3. Проверка на Альфу
        val isAlpha = geneticPotential >= cfg.alphaThreshold
        if (isAlpha) {
            pdc.set(isAlphaKey, PersistentDataType.BYTE, 1.toByte())
        }

        // Operation.MULTIPLY_SCALAR_1 означает: Финальное_Значение = База + (База * modValue)
        // Следовательно, если multiplier = 1.2 (120%), то modValue должен быть 0.2 (+20%).
        val modValue = multiplier - 1.0

        applyModifier(entity, Attribute.MAX_HEALTH, HEALTH_MOD_KEY, modValue, AttributeModifier.Operation.MULTIPLY_SCALAR_1)
        applyModifier(entity, Attribute.MOVEMENT_SPEED, SPEED_MOD_KEY, modValue, AttributeModifier.Operation.MULTIPLY_SCALAR_1)
        applyModifier(entity, Attribute.ATTACK_DAMAGE, DAMAGE_MOD_KEY, modValue, AttributeModifier.Operation.MULTIPLY_SCALAR_1)

        if (isAlpha) {
            // Для брони используем ADD_NUMBER, так как базовая броня волка = 0
            applyModifier(entity, Attribute.ARMOR, ARMOR_MOD_KEY, cfg.alphaBonusArmor, AttributeModifier.Operation.ADD_NUMBER)
        }

        // 4. Лечим волка до полного ХП, так как мы только что изменили его максимальное здоровье
        entity.health = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: entity.health
    }

    private fun applyModifier(
        entity: Wolf,
        attribute: Attribute,
        key: NamespacedKey,
        value: Double,
        operation: AttributeModifier.Operation
    ) {
        val attrInstance = entity.getAttribute(attribute) ?: return

        // В 1.21+ мы можем легко найти существующий модификатор по его NamespacedKey (.key)
        attrInstance.modifiers.firstOrNull { it.key == key }?.let {
            attrInstance.removeModifier(it)
        }

        // Используем новый конструктор 1.21+
        val modifier = AttributeModifier(key, value, operation)
        attrInstance.addModifier(modifier)
    }

}