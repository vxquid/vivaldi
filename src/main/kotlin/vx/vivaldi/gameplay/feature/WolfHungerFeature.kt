package vx.vivaldi.gameplay.feature

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.EntityType
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Wolf
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.config.lib.annotations.Comment
import kotlin.random.Random

object WolfHungerFeature : Listener {

    class WolfHungerConfig {
        var enabled: Boolean = true

        @Comment("Maximum hunger capacity")
        var maxHunger: Double = 100.0

        @Comment("Threshold: The wolf starts hunting passive animals")
        var hungryThreshold: Double = 80.0

        @Comment("Threshold: The wolf goes crazy, ignores 'sit' command, and attacks players/bears")
        var starvingThreshold: Double = 40.0

        @Comment("Threshold: Tamed vivaldi go feral, break their bond, and attack their owner")
        var feralThreshold: Double = 15.0

        @Comment("Threshold: Cannibalism. Wolves start hunting other vivaldi")
        var cannibalThreshold: Double = 10.0

        @Comment("Damage taken per tick cycle when hunger is <= 0")
        var starvationDamage: Double = 1.0

        @Comment("Amount of hunger lost per tick cycle")
        var hungerDecay: Double = 0.15

        @Comment("Check interval (in ticks). 10 = twice a second (optimal for pathfinding)")
        var checkIntervalTicks: Long = 10L

        @Comment("Amount of hunger restored per piece of meat eaten")
        var meatNutrition: Double = 25.0

        @Comment("Materials that vivaldi consider 'bait' (can be picked up from the ground)")
        var edibleMeats: List<String> = listOf(
            "BEEF", "COOKED_BEEF", "PORKCHOP", "COOKED_PORKCHOP",
            "MUTTON", "COOKED_MUTTON", "CHICKEN", "COOKED_CHICKEN",
            "RABBIT", "COOKED_RABBIT", "ROTTEN_FLESH"
        )

        @Comment("Animals that hungry vivaldi will hunt")
        var hungryTargets: List<String> = listOf("SHEEP", "COW", "PIG", "CHICKEN", "RABBIT", "TURTLE")

        @Comment("Creatures that starving vivaldi will attack (including players)")
        var starvingTargets: List<String> = listOf("PLAYER", "POLAR_BEAR", "FOX", "HORSE", "DONKEY", "LLAMA")

        @Comment("Radius to search for food/prey (in blocks)")
        var searchRadius: Double = 24.0
    }

    private val cfg get() = plugin.gameplayManager.config.wolves.hunger

    private val hungerKey = NamespacedKey(plugin, "wolf_hunger")
    // Key for "torn" meat dropped from hunting, which players cannot pick up
    private val tornMeatKey = NamespacedKey(plugin, "wolf_torn_meat")

    private val edibleMeatTypes: Set<Material> by lazy {
        cfg.edibleMeats.mapNotNull { Material.getMaterial(it.uppercase()) }.toSet()
    }

    private val hungryTargetTypes: Set<EntityType> by lazy {
        cfg.hungryTargets.mapNotNull { runCatching { EntityType.valueOf(it.uppercase()) }.getOrNull() }.toSet()
    }

    private val starvingTargetTypes: Set<EntityType> by lazy {
        cfg.starvingTargets.mapNotNull { runCatching { EntityType.valueOf(it.uppercase()) }.getOrNull() }.toSet()
    }

    init {
        // Start the hunger loop
        plugin.server.scheduler.runTaskTimer(plugin, this::tickHunger, 20L, cfg.checkIntervalTicks)
    }

    private fun tickHunger() {
        if (!cfg.enabled) return

        plugin.gameplayManager.allowedWorlds.forEach { worldName ->
            val world = plugin.server.getWorld(worldName) ?: return@forEach

            world.livingEntities.forEach { entity ->
                if (entity !is Wolf) return@forEach

                val pdc = entity.persistentDataContainer
                var hunger = pdc.get(hungerKey, PersistentDataType.DOUBLE) ?: cfg.maxHunger

                // 1. Decrease hunger
                hunger = (hunger - cfg.hungerDecay).coerceAtLeast(0.0)
                pdc.set(hungerKey, PersistentDataType.DOUBLE, hunger)

                // 2. Starvation damage
                if (hunger <= 0.0) {
                    entity.damage(cfg.starvationDamage)
                    if (Random.nextDouble() < 0.2) {
                        entity.world.playSound(entity.location, Sound.ENTITY_WOLF_WHINE, 1.0f, 1.0f)
                    }
                }

                // 3. Survival instincts override training (stands up if sitting and starving)
                val isStarving = hunger < cfg.starvingThreshold
                if (isStarving && entity.isSitting) {
                    entity.isSitting = false
                }

                // 4. Tamed vivaldi going feral
                if (entity.isTamed && hunger <= cfg.feralThreshold) {
                    val prevOwner = entity.owner as? Player

                    entity.isTamed = false
                    entity.owner = null
                    entity.isAngry = true

                    entity.world.spawnParticle(Particle.ANGRY_VILLAGER, entity.location.add(0.0, 1.0, 0.0), 3, 0.2, 0.2, 0.2)
                    entity.world.playSound(entity.location, Sound.ENTITY_WOLF_GROWL, 1.0f, 0.5f)

                    if (prevOwner != null && prevOwner.location.distanceSquared(entity.location) < 400.0) {
                        entity.target = prevOwner
                    }
                }

                val nearbyEntities = entity.getNearbyEntities(cfg.searchRadius, cfg.searchRadius / 2.0, cfg.searchRadius)

                // === STEP A: SEARCH FOR BAIT (Highest Priority) ===
                // A wolf eats meat from the ground ONLY if it's no longer considered 'Full'
                if (hunger < cfg.hungryThreshold) {
                    val nearbyMeats = nearbyEntities.filterIsInstance<Item>()
                        .filter { !it.isDead } // IMPORTANT: Prevents multiple vivaldi from eating the same item at the same tick
                        .filter { it.itemStack.type in edibleMeatTypes }
                        .filter { entity.hasLineOfSight(it) }

                    if (nearbyMeats.isNotEmpty()) {
                        val nearestMeat = nearbyMeats.minByOrNull { it.location.distanceSquared(entity.location) }!!
                        val distSq = entity.location.distanceSquared(nearestMeat.location)

                        // Distraction mechanic: If the wolf was chasing someone, it drops the target for the meat
                        if (entity.target != null) {
                            entity.target = null
                            entity.isAngry = false
                        }

                        if (distSq < 3.0) { // Eating distance (~1.7 blocks)
                            val itemStack = nearestMeat.itemStack

                            // Decrease stack by 1
                            itemStack.amount -= 1
                            if (itemStack.amount <= 0) {
                                nearestMeat.remove()
                            } else {
                                nearestMeat.itemStack = itemStack
                            }

                            // Restore hunger
                            val newHunger = (hunger + cfg.meatNutrition).coerceAtMost(cfg.maxHunger)
                            pdc.set(hungerKey, PersistentDataType.DOUBLE, newHunger)

                            // Eating effects
                            entity.world.playSound(entity.location, Sound.ENTITY_GENERIC_EAT, 1.0f, Random.nextDouble(0.8, 1.2).toFloat())

                            // Stop pathfinding (successfully ate)
                            (entity as Mob).pathfinder.stopPathfinding()
                        } else {
                            // Run to the meat (using Paper API)
                            (entity as Mob).pathfinder.moveTo(nearestMeat.location)
                        }

                        // Return early so the wolf doesn't switch to finding prey in this tick
                        return@forEach
                    }
                }

                // === STEP B: RESTING STATE ===
                // If there's no meat on the ground and the wolf is full, it just rests and ignores targets
                if (hunger >= cfg.hungryThreshold) {
                    val currentTarget = entity.target
                    if (currentTarget != null && currentTarget.type in hungryTargetTypes) {
                        entity.target = null
                        entity.isAngry = false
                    }
                    return@forEach
                }

                // === STEP C: SEARCH FOR PREY (HUNTING) ===
                val validTypes = mutableSetOf<EntityType>()
                validTypes.addAll(hungryTargetTypes)
                if (isStarving) validTypes.addAll(starvingTargetTypes)

                // Cannibalism check
                val isCannibal = hunger < cfg.cannibalThreshold
                if (isCannibal) validTypes.add(EntityType.WOLF)

                // If already fighting a valid target, keep it
                val currentTarget = entity.target
                if (currentTarget != null && !currentTarget.isDead && currentTarget.type in validTypes) {
                    if ((currentTarget is Player || currentTarget.type in starvingTargetTypes || currentTarget.type == EntityType.WOLF) && !entity.isAngry) {
                        entity.isAngry = true
                    }
                    return@forEach
                }

                // Find a new victim
                val prey = nearbyEntities.filterIsInstance<LivingEntity>()
                    .filter { it.type in validTypes }
                    .filter { !it.isDead }
                    .filter { it != entity } // Do not eat yourself
                    .filter {
                        if (it is Player) it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE else true
                    }
                    .filter { entity.hasLineOfSight(it) }
                    .minByOrNull { it.location.distanceSquared(entity.location) }

                if (prey != null) {
                    val wasAngry = entity.isAngry
                    entity.target = prey

                    // Trigger angry state if attacking players/bears/other vivaldi
                    if (isStarving && (prey is Player || prey.type in starvingTargetTypes || prey.type == EntityType.WOLF)) {
                        entity.isAngry = true
                        if (!wasAngry) {
                            entity.world.playSound(entity.location, Sound.ENTITY_WOLF_GROWL, 1.0f, 0.4f)
                        }
                    }
                } else {
                    if (currentTarget == null && entity.isAngry) {
                        entity.isAngry = false
                    }
                }
            }
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        if (!cfg.enabled) return
        val victim = event.entity
        val killer = victim.killer

        if (killer is Wolf) {
            val maxHp = victim.getAttribute(Attribute.MAX_HEALTH)?.value ?: 10.0
            val meatAmount = (maxHp / 6.0).toInt().coerceAtLeast(1)

            val loc = victim.location.add(0.0, 0.5, 0.0)

            for (i in 0 until meatAmount) {
                val meatItem = victim.world.dropItem(loc, ItemStack(Material.BEEF))

                meatItem.itemStack.editMeta { meta ->
                    meta.setDisplayName("§cTorn Meat")
                }

                meatItem.velocity = Vector(
                    Random.nextDouble(-0.2, 0.2),
                    Random.nextDouble(0.2, 0.4),
                    Random.nextDouble(-0.2, 0.2)
                )

                // Tag the item so players cannot pick it up
                meatItem.persistentDataContainer.set(tornMeatKey, PersistentDataType.BYTE, 1.toByte())
            }
        }
    }

    @EventHandler
    fun onPlayerPickup(event: EntityPickupItemEvent) {
        if (!cfg.enabled) return

        // Block players (and other entities) from picking up ONLY torn meat.
        // Players can still pick up normal beef/pork if they manage to grab it before the wolf!
        if (event.item.persistentDataContainer.has(tornMeatKey, PersistentDataType.BYTE)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onEntityTarget(event: EntityTargetEvent) {
        if (!cfg.enabled) return
        val wolf = event.entity as? Wolf ?: return

        val reason = event.reason
        if (reason == EntityTargetEvent.TargetReason.CUSTOM ||
            reason == EntityTargetEvent.TargetReason.TARGET_ATTACKED_ENTITY ||
            reason == EntityTargetEvent.TargetReason.TARGET_ATTACKED_OWNER ||
            reason == EntityTargetEvent.TargetReason.OWNER_ATTACKED_TARGET
        ) return

        val target = event.target ?: return
        val hunger = wolf.persistentDataContainer.get(hungerKey, PersistentDataType.DOUBLE) ?: cfg.maxHunger

        // Cancel vanilla targeting if the wolf is not hungry enough for that specific entity type
        if (hunger >= cfg.hungryThreshold && target.type in hungryTargetTypes) {
            event.isCancelled = true
        } else if (hunger >= cfg.starvingThreshold && target.type in starvingTargetTypes) {
            event.isCancelled = true
        } else if (hunger >= cfg.cannibalThreshold && target.type == EntityType.WOLF) {
            event.isCancelled = true
        }
    }
}