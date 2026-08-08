package vx.seasons.command

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
import org.bukkit.event.player.PlayerJoinEvent
import vx.seasons.SeasonsPlugin.Companion.gson
import vx.seasons.SeasonsPlugin.Companion.lang
import vx.seasons.SeasonsPlugin.Companion.plugin
import vx.seasons.SeasonsPlugin.Companion.sendFormattedMessage
import vx.seasons.config.GameplayConfiguration
import vx.seasons.config.ProviderConfiguration.ProviderType
import vx.seasons.config.lib.ConfigurationManager
import vx.seasons.network.BiomeRegistryInterceptor
import vx.seasons.network.CachedVanillaBiome
import vx.seasons.season.Season
import vx.seasons.season.biome.BiomeGenerationController
import vx.seasons.season.biome.GeneratedBiomeContainer
import vx.seasons.gameplay.feature.environment.forest.DynamicForestFeature
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

@CommandAlias("seasons|vxs")
class VivaldiCommand : BaseCommand(), Listener {

    private val isRunning = AtomicBoolean(false)
    private val queueFile = File(plugin.dataFolder, "cache/biome_queue.yml")
    private val biomesFolder = File(plugin.dataFolder, "biomes")
    private var bossBar: BossBar? = null

    private val setupSessions = mutableSetOf<UUID>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)

        // Autocomplete for seasons
        plugin.commandManager.commandCompletions.registerCompletion("seasons") {
            Season.entries.map { it.name }
        }

        if (!biomesFolder.exists()) biomesFolder.mkdirs()
    }

    // --- OP / ADMIN JOIN NOTIFICATION ---

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (player.isOp || player.hasPermission("seasons.admin.setup")) {
            val providerConfig = plugin.providerManager.config
            val gameplayConfig = plugin.gameplayManager.config

            if (providerConfig.apiKey == "YOUR_API_KEY" || providerConfig.apiKey.isBlank()) {
                player.sendFormattedMessage(
                    lang("setup.notify_op", "&#F43F5EPlugin is not configured! Run &#F59E0B/vxs setup &#F43F5Eto start setup or &#F59E0B/vxs disable ai &#F43F5Eto disable AI features.")
                )
            }

            if (!gameplayConfig.general.enableWorldModifications) {
                player.sendFormattedMessage(
                    lang("activation.notify_modifications", "&#CBD5E1Landscape modifications are currently &#F59E0Bdisabled&#CBD5E1. Run &#F59E0B/vxs activate modifications &#CBD5E1to enable snow, ice freezing/melting, plant growth & trees.")
                )
            }

            if (!gameplayConfig.dynamicForest.enabled) {
                player.sendFormattedMessage(
                    lang("activation.notify_trees", "&#CBD5E1Procedural trees are currently &#F59E0Bdisabled&#CBD5E1. Run &#F59E0B/vxs activate trees &#CBD5E1to replace &#F59E0BALL TREES &#CBD5E1in the world with procedurally generated ones.")
                )
            }
        }
    }

    // --- ACTIVATION COMMANDS ---

    @Subcommand("activate modifications")
    @CommandPermission("seasons.admin.setup")
    fun onActivateModifications(player: Player) {
        val config = plugin.gameplayManager.config
        if (config.general.enableWorldModifications) {
            player.sendFormattedMessage(lang("activation.modifications_already_active", "&#F43F5EWorld modifications are already enabled!"))
            return
        }

        config.general.enableWorldModifications = true
        ConfigurationManager.save(config)

        plugin.gameplayManager.registerFeatures()
        plugin.gameplayManager.startAllTasks()

        player.sendFormattedMessage(lang("activation.modifications_success", "&#34D399World modifications have been successfully ENABLED!"))
        player.sendFormattedMessage(lang("activation.modifications_info", "&#CBD5E1Active features: Snow accumulation, plant growth, river/ice freezing & melting, procedural trees."))
    }

    @Subcommand("activate trees")
    @CommandPermission("seasons.admin.setup")
    fun onActivateTrees(player: Player) {
        val config = plugin.gameplayManager.config
        if (config.dynamicForest.enabled) {
            player.sendFormattedMessage(lang("activation.trees_already_active", "&#F43F5EProcedural trees are already enabled!"))
            return
        }

        config.dynamicForest.enabled = true

        if (!config.general.enableWorldModifications) {
            config.general.enableWorldModifications = true
            plugin.gameplayManager.registerFeatures()
        }

        ConfigurationManager.save(config)

        try {
            DynamicForestFeature.start()
        } catch (_: Exception) {}

        player.sendFormattedMessage(lang("activation.trees_success", "&#34D399Procedural trees feature has been ENABLED!"))
        player.sendFormattedMessage(lang("activation.trees_warning", "&#F43F5EWARNING: &#F59E0BProcedurally generated trees will now replace ABSOLUTELY ALL TREES IN THE WORLD!"))
    }

    // --- SEASON MANAGEMENT ---

    @Subcommand("season info")
    @CommandPermission("seasons.admin.season")
    fun onSeasonInfo(player: Player) {
        val current = plugin.seasonManager.currentSeason
        player.sendFormattedMessage(
            lang("season.info", "&#CBD5E1Current active season is: &#F59E0B{season}").replace("{season}", current.name)
        )
    }

    @Subcommand("season set")
    @CommandPermission("seasons.admin.season")
    @CommandCompletion("@seasons")
    fun onSeasonSet(player: Player, @Values("@seasons") seasonName: String) {
        val season = try {
            Season.valueOf(seasonName.uppercase())
        } catch (_: Exception) {
            player.sendFormattedMessage(
                lang("season.invalid", "&#F43F5EInvalid season! Available: &#F59E0B{seasons}")
                    .replace("{seasons}", Season.entries.joinToString { it.name })
            )
            return
        }

        if (plugin.seasonManager.currentSeason == season) {
            player.sendFormattedMessage(
                lang("season.already_set", "&#F43F5EThe season is already set to {season}!")
                    .replace("{season}", season.name)
            )
            return
        }

        player.sendFormattedMessage(
            lang("season.changing", "&#CBD5E1Forcefully changing season to &#2DD4BF{season}&#34D399...")
                .replace("{season}", season.name)
        )
        plugin.seasonManager.setSeason(season)
    }

    @Subcommand("season next")
    @CommandPermission("seasons.admin.season")
    fun onSeasonNext(player: Player) {
        val nextSeason = plugin.seasonManager.currentSeason.next()
        player.sendFormattedMessage(
            lang("season.next", "&#CBD5E1Advancing to the next season: &#2DD4BF{season}&#34D399...")
                .replace("{season}", nextSeason.name)
        )
        plugin.seasonManager.setSeason(nextSeason)
    }

    // --- SETUP LOGIC ---

    @Subcommand("setup|provider")
    @CommandPermission("seasons.admin.setup")
    fun onSetup(player: Player) {
        val defaultModel = "gemini-3.1-flash-lite"
        val url = "https://aistudio.google.com/app/apikey"

        plugin.providerManager.config.model = defaultModel
        plugin.providerManager.config.providerType = ProviderType.GEMINI
        setupSessions.add(player.uniqueId)

        player.sendFormattedMessage(
            lang("setup.selected_provider", "&#CBD5E1Using &#2DD4BF{provider}&#CBD5E1. Get API key here: &#38BDF8{url}")
                .replace("{provider}", ProviderType.GEMINI.name)
                .replace("{url}", url)
        )
        player.sendFormattedMessage(
            lang("setup.selected_model", "&#CBD5E1Model &#34D399{model} &#CBD5E1selected.")
                .replace("{model}", defaultModel)
        )
        player.sendFormattedMessage(lang("setup.step_1_prompt", "&#F59E0B[Step 1/1] &#CBD5E1Paste your &#2DD4BFAPI Key &#CBD5E1in chat. &#64748B(It will be hidden)."))
    }

    @Subcommand("disable ai")
    @CommandPermission("seasons.admin.setup")
    fun onDisableAI(player: Player) {
        setupSessions.remove(player.uniqueId)
        val config = plugin.providerManager.config
        config.apiKey = "DISABLED"
        ConfigurationManager.save(config)

        player.sendFormattedMessage(lang("setup.disabled_header", "&#F43F5EAI features have been disabled."))
        player.sendFormattedMessage(lang("setup.disabled_mode", "&#CBD5E1Plugin is now running in &#F59E0BDeterministic Mode&#CBD5E1."))
        player.sendFormattedMessage(lang("setup.disabled_info_1", "&#CBD5E1- Generative biomes: &#F43F5EOFF"))
        player.sendFormattedMessage(lang("setup.disabled_info_2", "&#CBD5E1- Content will be loaded strictly from local JSON configuration files."))
        sendOpenSourceMessage(player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChatInterceptor(event: AsyncPlayerChatEvent) {
        val player = event.player
        if (!setupSessions.contains(player.uniqueId)) return

        event.isCancelled = true
        val input = event.message.trim()

        if (input.equals("cancel", true)) {
            setupSessions.remove(player.uniqueId)
            player.sendFormattedMessage(lang("setup.cancelled", "&#F43F5ESetup cancelled."))
            return
        }

        val config = plugin.providerManager.config
        config.apiKey = input
        config.language = "English"
        config.setting = "Minecraft Universe"
        config.namingStyle = "Fantasy Names"

        ConfigurationManager.save(config)
        setupSessions.remove(player.uniqueId)

        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.providerManager.load()
            player.sendFormattedMessage(
                lang("setup.success", "&#34D399Configuration complete! Provider: &#2DD4BF{provider} &#34D399| Model: &#F59E0B{model}")
                    .replace("{provider}", config.providerType.name)
                    .replace("{model}", config.model)
            )
            player.sendFormattedMessage(lang("setup.suggestion", "&#CBD5E1You can now generate seasonal biomes using &#F59E0B/vxs dev generate&#CBD5E1."))
            player.sendFormattedMessage(lang("setup.activation_prompt", "&#CBD5E1Run &#F59E0B/vxs activate modifications &#CBD5E1for landscape mechanics and &#F59E0B/vxs activate trees &#CBD5E1to enable procedural trees."))
            sendOpenSourceMessage(player)
        })
    }

    private fun sendOpenSourceMessage(player: Player) {
        player.sendFormattedMessage(" ")
        player.sendFormattedMessage(lang("open_source.divider", "&#334155--------------------------------------------------"))
        player.sendFormattedMessage(lang("open_source.title", "&#CBD5E1vxseasons is an &#2DD4BFOpen Source &#CBD5E1project!"))
        player.sendFormattedMessage(lang("open_source.sub", "&#CBD5E1Explore the source code or report issues on GitHub:"))
        player.sendFormattedMessage(lang("open_source.link", "&#38BDF8➤ https://github.com/vxquid/vxseasons"))
        player.sendFormattedMessage(lang("open_source.tree_editor_sub", "&#CBD5E1Create and customize procedural trees using Web Tree Editor:"))
        player.sendFormattedMessage(lang("open_source.tree_editor_link", "&#2DD4BF➤ https://vxquid.github.io/vxseasons-tree-editor/"))
        player.sendFormattedMessage(lang("open_source.discord_sub", "&#CBD5E1Join our Discord community for support and updates:"))
        player.sendFormattedMessage(lang("open_source.discord_link", "&#818CF8➤ https://discord.com/invite/DKZkwGvEj3"))
        player.sendFormattedMessage(lang("open_source.divider", "&#334155--------------------------------------------------"))
    }

    // --- GENERATION LOGIC ---

    @Subcommand("dev generate")
    @CommandPermission("seasons.admin.generate")
    fun onGenerateInfo(player: Player) {
        if (isRunning.get()) {
            player.sendFormattedMessage(lang("generation.already_running", "&#F43F5EBiome generation is already running!"))
            return
        }

        if (queueFile.exists()) {
            player.sendFormattedMessage(lang("generation.interrupted", "&#F59E0BFound an interrupted generation session. Use &#2DD4BF/vxs dev generate resume &#F59E0Bto continue."))
            return
        }

        val gameplayConfig = ConfigurationManager.load(GameplayConfiguration::class.java)
        val excluded = gameplayConfig.environment.excludedBiomes.map { it.lowercase() }

        val biomes = Registry.BIOME.map { it.key }.filter { key ->
            val fullKey = "${key.namespace}:${key.key}".lowercase()
            val shortKey = key.key.lowercase()

            val isExcluded = excluded.any { ex ->
                when {
                    ex == fullKey || ex == shortKey -> true
                    ex.contains("*") -> {
                        val regex = ex.replace("*", ".*").toRegex()
                        fullKey.matches(regex) || shortKey.matches(regex)
                    }
                    else -> false
                }
            }
            !isExcluded
        }

        if (biomes.isEmpty()) {
            player.sendFormattedMessage(lang("generation.empty", "&#F43F5EBiome list is empty or all biomes are excluded in config!"))
            return
        }

        val byNamespace = biomes.groupBy { it.namespace }

        player.sendFormattedMessage(
            lang("generation.ready", "&#F59E0BReady to generate seasonal variants for &#2DD4BF{count} &#F59E0Bbiomes.")
                .replace("{count}", biomes.size.toString())
        )
        player.sendFormattedMessage(
            lang("generation.generators", "&#CBD5E1Detected generators: &#E2E8F0{generators}")
                .replace("{generators}", byNamespace.keys.joinToString(", "))
        )
        player.sendFormattedMessage(lang("generation.start_prompt", "&#34D399Type &#F59E0B/vxs dev generate accept &#34D399to start."))
    }

    @Subcommand("dev generate accept|resume")
    @CommandPermission("seasons.admin.generate")
    fun onGenerateAccept(player: Player) {
        if (isRunning.get()) return

        if (!queueFile.exists()) {
            val config = YamlConfiguration()
            val gameplayConfig = ConfigurationManager.load(GameplayConfiguration::class.java)
            val excluded = gameplayConfig.environment.excludedBiomes.map { it.lowercase() }

            val biomes = Registry.BIOME.map { it.key }.filter { key ->
                val fullKey = "${key.namespace}:${key.key}".lowercase()
                val shortKey = key.key.lowercase()

                val isExcluded = excluded.any { ex ->
                    when {
                        ex == fullKey || ex == shortKey -> true
                        ex.contains("*") -> {
                            val regex = ex.replace("*", ".*").toRegex()
                            fullKey.matches(regex) || shortKey.matches(regex)
                        }
                        else -> false
                    }
                }
                !isExcluded
            }.map { "${it.namespace}:${it.key}" }

            if (biomes.isEmpty()) {
                player.sendFormattedMessage(lang("generation.empty_list", "&#F43F5EBiome list is empty!"))
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
            lang("generation.bossbar_init", "&#2DD4BFAI: Initializing Multi-Generator Biomes..."),
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

                    val cachedBiome = BiomeRegistryInterceptor.vanillaBiomesCache[fullKeyStr] ?: CachedVanillaBiome(
                        namespace = namespace,
                        key = key,
                        temperature = 0.5f,
                        downfall = 0.5f,
                        waterColor = 4159204,
                        waterFogColor = 329011,
                        skyColor = 7907327,
                        fogColor = 12638463,
                        grassColor = null,
                        foliageColor = null
                    )

                    val progress = ((total - pending.size).toDouble() / total * 100).toInt()
                    val progressTitle = lang("generation.bossbar_progress", "&#34D399Generating: &#F59E0B{namespace} &#64748B| &#CBD5E1{key} &#64748B({progress}%)")
                        .replace("{namespace}", cachedBiome.namespace)
                        .replace("{key}", cachedBiome.key)
                        .replace("{progress}", progress.toString())
                    updateBar(progressTitle, progress.toDouble() / 100)

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
                        plugin.logger.warning(
                            lang("generation.failed", "Failed to generate biome: {biome}. Retrying in 10 seconds...")
                                .replace("{biome}", fullKeyStr)
                        )
                        handleCooldown(10, progress, fullKeyStr, isError = true)
                    }
                }

                plugin.server.scheduler.runTask(plugin, Runnable {
                    bossBar?.removeAll()
                    queueFile.delete()
                    isRunning.set(false)
                    admin.sendFormattedMessage(lang("generation.completed", "&#34D399Biome generation successfully completed!"))
                })

            } catch (e: Exception) {
                isRunning.set(false)
                plugin.logger.severe("Worker failure: ${e.message}")
                e.printStackTrace()
            }
        })
    }

    private fun handleCooldown(seconds: Int, progress: Int, biome: String, isError: Boolean = false) {
        val color = if (isError) "&#F43F5E" else "&#64748B"
        val status = if (isError) "Rate Limit/Error" else "Cooldown"

        for (i in seconds downTo 1) {
            val title = lang("generation.cooldown_status", "{color}{status} for {biome}... &#F59E0B{seconds}s")
                .replace("{color}", color)
                .replace("{status}", status)
                .replace("{biome}", biome)
                .replace("{seconds}", i.toString())
            updateBar(title, progress.toDouble() / 100)
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