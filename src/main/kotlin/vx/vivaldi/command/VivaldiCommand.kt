package vx.vivaldi.command

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import org.bukkit.Bukkit
import org.bukkit.Registry
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import vx.vivaldi.Vivaldi.Companion.gson
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.Vivaldi.Companion.sendFormattedMessage
import vx.vivaldi.config.ProviderConfiguration.ProviderType
import vx.vivaldi.config.lib.ConfigurationManager
import vx.vivaldi.network.BiomeRegistryInterceptor
import vx.vivaldi.network.CachedVanillaBiome
import vx.vivaldi.season.biome.BiomeGenerationController
import vx.vivaldi.season.biome.GeneratedBiomeContainer
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

@CommandAlias("vivaldi|vvd")
class VivaldiCommand : BaseCommand(), Listener {

    private val isRunning = AtomicBoolean(false)
    private val queueFile = File(plugin.dataFolder, "cache/biome_queue.yml")
    private val biomesFolder = File(plugin.dataFolder, "biomes")
    private var bossBar: BossBar? = null

    private enum class SetupStep { KEY, LANGUAGE, SETTING, NAMING }
    private data class Session(val type: ProviderType, var step: SetupStep)

    private val setupSessions = mutableMapOf<UUID, Session>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.commandManager.commandCompletions.registerCompletion("providers") {
            ProviderType.entries.map { it.name }
        }
        if (!biomesFolder.exists()) biomesFolder.mkdirs()
    }

    private companion object {
        const val SETUP_PREFIX = "§6[Vivaldi AI] "
        const val STEP_PREFIX = "§e[Step {n}/4] "

        const val MSG_START = "$SETUP_PREFIX§7Select your provider: §e/vvd provider <type>"
        const val MSG_LIST = "§7Available: §fCEREBRAS (recommended), GROQ, GEMINI, OPENROUTER, DEEPSEEK, CHATGPT, ANYTHINGLLM"
        const val MSG_INVALID = "§cInvalid provider type!"
        const val MSG_CANCELLED = "§c[Vivaldi AI] Setup cancelled."

        const val STEP_1_PROMPT = "${STEP_PREFIX}§fPaste your §6API Key §fin chat. §7(It will be hidden)."
        const val STEP_2_PROMPT = "${STEP_PREFIX}§fType the §6Language §ffor prompts (e.g., English):"
        const val STEP_3_PROMPT = "${STEP_PREFIX}§fType the §6Thematic Setting §f(e.g., Realistic, Fantasy):"
        const val STEP_4_PROMPT = "${STEP_PREFIX}§fType the §6Naming Style §f(e.g., Standard):"

        const val MSG_SUCCESS = "§a[Vivaldi AI] Configuration complete! Provider: §6{provider} §a| Model: §e{model}"
        const val MSG_SUGGESTION = "$SETUP_PREFIX§7You can now generate seasonal biomes using §e/vvd dev generate§7."

        const val MSG_DISABLED_HEADER = "$SETUP_PREFIX§cAI features have been disabled."
        const val MSG_DISABLED_MODE = "§7Vivaldi is now running in §eDeterministic Mode§7."
        const val MSG_DISABLED_INFO_1 = "§7- Generative biomes: §cOFF"
        const val MSG_DISABLED_INFO_2 = "§7- Content will be loaded strictly from local JSON configuration files."

        const val MSG_DONATE_DIVIDER = "§8§m--------------------------------------------------"
        const val MSG_DONATE_1 = "§7Support the development of §6Vivaldi §7and my other projects"
        const val MSG_DONATE_2 = "§7via Ko-fi if you enjoy the plugin:"
        const val MSG_DONATE_LINK = "§b➤ https://ko-fi.com/vxquid"
    }

    @Subcommand("setup")
    @CommandPermission("vivaldi.admin.setup")
    fun onSetup(player: Player) {
        player.sendFormattedMessage(MSG_START)
        player.sendFormattedMessage(MSG_LIST)
    }

    @Subcommand("disable ai")
    @CommandPermission("vivaldi.admin.setup")
    fun onDisableAI(player: Player) {
        if (setupSessions.containsKey(player.uniqueId)) {
            setupSessions.remove(player.uniqueId)
        }
        val config = plugin.providerManager.config
        config.apiKey = "DISABLED"
        ConfigurationManager.save(config)

        player.sendFormattedMessage(MSG_DISABLED_HEADER)
        player.sendFormattedMessage(MSG_DISABLED_MODE)
        player.sendFormattedMessage(MSG_DISABLED_INFO_1)
        player.sendFormattedMessage(MSG_DISABLED_INFO_2)
        sendSupportMessage(player)
    }

    @Subcommand("provider")
    @CommandPermission("vivaldi.admin.setup")
    @CommandCompletion("@providers")
    fun onSelectProvider(player: Player, @Values("@providers") type: String) {
        val providerType = try { ProviderType.valueOf(type.uppercase()) } catch (_: Exception) {
            player.sendFormattedMessage(MSG_INVALID)
            return
        }

        val (url, defaultModel) = when (providerType) {
            ProviderType.CEREBRAS -> "https://cloud.cerebras.ai" to "llama3.1-8b"
            else -> "Provider Dashboard" to "default-model"
        }

        plugin.providerManager.config.model = defaultModel
        plugin.providerManager.config.providerType = providerType
        setupSessions[player.uniqueId] = Session(providerType, SetupStep.KEY)

        player.sendFormattedMessage("$SETUP_PREFIX§7Selected §e${providerType.name}§7. URL: §b$url")
        player.sendFormattedMessage("$SETUP_PREFIX§7Recommended model §a$defaultModel §7has been automatically selected.")
        player.sendFormattedMessage(STEP_1_PROMPT.replace("{n}", "1"))
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChatInterceptor(event: AsyncPlayerChatEvent) {
        val player = event.player
        val session = setupSessions[player.uniqueId] ?: return

        event.isCancelled = true
        val input = event.message.trim()

        if (input.equals("cancel", true)) {
            setupSessions.remove(player.uniqueId)
            player.sendFormattedMessage(MSG_CANCELLED)
            return
        }

        val config = plugin.providerManager.config

        when (session.step) {
            SetupStep.KEY -> {
                config.apiKey = input
                session.step = SetupStep.LANGUAGE
                player.sendFormattedMessage(STEP_2_PROMPT.replace("{n}", "2"))
            }
            SetupStep.LANGUAGE -> {
                config.language = input
                session.step = SetupStep.SETTING
                player.sendFormattedMessage(STEP_3_PROMPT.replace("{n}", "4"))
            }
            SetupStep.SETTING -> {
                config.setting = input
                session.step = SetupStep.NAMING
                player.sendFormattedMessage(STEP_4_PROMPT.replace("{n}", "4"))
            }
            SetupStep.NAMING -> {
                config.namingStyle = input
                ConfigurationManager.save(config)
                setupSessions.remove(player.uniqueId)

                plugin.server.scheduler.runTask(plugin, Runnable {
                    plugin.providerManager.load()
                    player.sendFormattedMessage(MSG_SUCCESS
                        .replace("{provider}", config.providerType.name)
                        .replace("{model}", config.model))
                    player.sendFormattedMessage(MSG_SUGGESTION)
                    sendSupportMessage(player)
                })
            }
        }
    }

    private fun sendSupportMessage(player: Player) {
        player.sendFormattedMessage(" ")
        player.sendFormattedMessage(MSG_DONATE_DIVIDER)
        player.sendFormattedMessage(MSG_DONATE_1)
        player.sendFormattedMessage(MSG_DONATE_2)
        player.sendFormattedMessage(MSG_DONATE_LINK)
        player.sendFormattedMessage(MSG_DONATE_DIVIDER)
    }

    // --- GENERATION LOGIC ---

    @Subcommand("dev generate")
    @CommandPermission("vivaldi.admin.generate")
    fun onGenerateInfo(player: Player) {
        if (isRunning.get()) {
            player.sendFormattedMessage("§cBiome generation is already running!")
            return
        }

        if (queueFile.exists()) {
            player.sendFormattedMessage("§eFound an interrupted generation session. Use §6/vvd dev generate resume §eto continue.")
            return
        }

        // БЕРЁМ БИОМЫ НАПРЯМУЮ ИЗ BUKKIT API, ИСКЛЮЧАЯ ИСПОЛЬЗОВАНИЕ NMS И КЭША ПАКЕТОВ
        val biomes = Registry.BIOME.map { it.key }.filter { key ->
            val name = key.key.lowercase()
            !name.contains("nether") && !name.contains("end") &&
                    !name.contains("crimson") && !name.contains("warped") &&
                    !name.contains("basalt") && !name.contains("soul_sand")
        }

        if (biomes.isEmpty()) {
            player.sendFormattedMessage("§c[Vivaldi] Biome list is empty!")
            return
        }

        val byNamespace = biomes.groupBy { it.namespace }

        player.sendFormattedMessage("§eReady to generate seasonal variants for §6${biomes.size} §ebiomes.")
        player.sendFormattedMessage("§7Detected generators: §f${byNamespace.keys.joinToString(", ")}")
        player.sendFormattedMessage("§aType §6/vvd dev generate accept §ato start.")
    }

    @Subcommand("dev generate accept|resume")
    @CommandPermission("vivaldi.admin.generate")
    fun onGenerateAccept(player: Player) {
        if (isRunning.get()) return

        if (!queueFile.exists()) {
            val config = YamlConfiguration()

            val biomes = Registry.BIOME.map { it.key }.filter { key ->
                val name = key.key.lowercase()
                !name.contains("nether") && !name.contains("end") &&
                        !name.contains("crimson") && !name.contains("warped") &&
                        !name.contains("basalt") && !name.contains("soul_sand")
            }.map { "${it.namespace}:${it.key}" } // Получаем строку namespace:key

            if (biomes.isEmpty()) {
                player.sendFormattedMessage("§c[Vivaldi] Biome list is empty!")
                return
            }

            config.set("pending", biomes)
            config.set("total", biomes.size)
            config.save(queueFile)
        }

        isRunning.set(true)
        startGenerationWorker(player)
    }

    private fun startGenerationWorker(admin: Player) {
        val config = YamlConfiguration.loadConfiguration(queueFile)
        val total = config.getInt("total", 1)

        bossBar = Bukkit.createBossBar(
            "§dAI: Initializing Multi-Generator Biomes...",
            BarColor.GREEN, BarStyle.SOLID
        ).apply { addPlayer(admin) }

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                while (true) {
                    val currentQueue = YamlConfiguration.loadConfiguration(queueFile)
                    val pending = currentQueue.getStringList("pending").toMutableList()

                    if (pending.isEmpty()) break

                    val fullKeyStr = pending[0]
                    val split = fullKeyStr.split(":")
                    val namespace = split.getOrNull(0) ?: "minecraft"
                    val key = split.getOrNull(1) ?: fullKeyStr

                    // Если пакеты успели закэшировать биом - берём его. Иначе создаём дефолтную болванку
                    val cachedBiome = BiomeRegistryInterceptor.vanillaBiomesCache[fullKeyStr] ?: CachedVanillaBiome(
                        namespace = namespace,
                        key = key,
                        temperature = 0.5f,
                        downfall = 0.5f,
                        waterColor = 4159204, // Ванильные дефолтные цвета воды/тумана
                        waterFogColor = 329011,
                        skyColor = 7907327,
                        fogColor = 12638463,
                        grassColor = null,
                        foliageColor = null
                    )

                    val progress = ((total - pending.size).toDouble() / total * 100).toInt()
                    updateBar("§aGenerating: §e${cachedBiome.namespace} §7| §f${cachedBiome.key} §7($progress%)", progress.toDouble() / 100)

                    val generatorFolder = File(biomesFolder, cachedBiome.namespace)
                    if (!generatorFolder.exists()) generatorFolder.mkdirs()

                    val targetFile = File(generatorFolder, "${cachedBiome.key}.json")

                    if (targetFile.exists()) {
                        pending.removeAt(0)
                        saveQueue(pending)
                        continue
                    }

                    val controller = BiomeGenerationController(cachedBiome)
                    val result = plugin.providerManager.client.sendPromptWithSchema(
                        controller.prompt,
                        GeneratedBiomeContainer::class
                    )

                    if (result != null) {
                        targetFile.writeText(gson.toJson(result))

                        pending.removeAt(0)
                        saveQueue(pending)

                        plugin.seasonalBiomeManager.loadAllBiomes()
                        handleCooldown(2, progress, fullKeyStr)
                    } else {
                        plugin.logger.warning("Failed to generate biome: $fullKeyStr. Retrying in 10 seconds...")
                        handleCooldown(10, progress, fullKeyStr, isError = true)
                    }
                }

                plugin.server.scheduler.runTask(plugin, Runnable {
                    bossBar?.removeAll()
                    queueFile.delete()
                    isRunning.set(false)
                    admin.sendFormattedMessage("§a[Vivaldi] Biome generation successfully completed!")
                })

            } catch (e: Exception) {
                isRunning.set(false)
                plugin.logger.severe("Worker failure: ${e.message}")
                e.printStackTrace()
            }
        })
    }

    private fun handleCooldown(seconds: Int, progress: Int, biome: String, isError: Boolean = false) {
        val color = if (isError) "§c" else "§7"
        val status = if (isError) "Rate Limit/Error" else "Cooldown"

        for (i in seconds downTo 1) {
            updateBar("$color$status for $biome... §e${i}s", progress.toDouble() / 100)
            Thread.sleep(1000)
        }
    }

    private fun saveQueue(list: List<String>) {
        val config = YamlConfiguration.loadConfiguration(queueFile)
        config.set("pending", list)
        config.save(queueFile)
    }

    private fun updateBar(title: String, progress: Double) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            bossBar?.setTitle(title)
            bossBar?.progress = progress.coerceIn(0.0, 1.0)
        })
    }
}