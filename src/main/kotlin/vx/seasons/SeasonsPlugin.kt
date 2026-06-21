package vx.seasons

import co.aikar.commands.PaperCommandManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import vx.seasons.ai.ProviderManager
import vx.seasons.command.VivaldiCommand
import vx.seasons.gameplay.GameplayManager
import vx.seasons.network.BiomeRegistryInterceptor
import vx.seasons.season.SeasonManager
import vx.seasons.season.biome.SeasonalBiomeManager

import java.io.File
import java.util.jar.JarFile

class SeasonsPlugin : JavaPlugin() {

    lateinit var providerManager: ProviderManager
    lateinit var gameplayManager: GameplayManager
    lateinit var commandManager: PaperCommandManager
    lateinit var seasonalBiomeManager: SeasonalBiomeManager
    lateinit var seasonManager: SeasonManager

    var language: YamlConfiguration = run {
        val file = File(super.getDataFolder(), "language.yml")
        if (!file.exists()) super.saveResource("language.yml", false)
        YamlConfiguration.loadConfiguration(file)
    }

    override fun onEnable() {
        // Extract default biomes from the plugin jar before loading them
        saveDefaultBiomes()

        this.providerManager = ProviderManager()
        this.gameplayManager = GameplayManager(this)
        this.commandManager  = PaperCommandManager(this)
        this.commandManager.registerCommand(VivaldiCommand())

        this.seasonalBiomeManager = SeasonalBiomeManager()
        this.seasonalBiomeManager.loadAllBiomes()

        this.seasonManager = SeasonManager(this)
        this.seasonManager.initialize() // Запуск загрузки и старт таймера

        if (plugin.gameplayManager.config.general.enableSeasons) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().eventManager.registerListener(
                BiomeRegistryInterceptor,
                com.github.retrooper.packetevents.event.PacketListenerPriority.NORMAL
            )
        }

    }

    override fun onDisable() {
        // Гарантированное сохранение сезона перед выгрузкой миров сервером
        if (this::seasonManager.isInitialized) {
            seasonManager.saveToPDC()
            logger.info("Season state saved successfully.")
        }
    }

    // NEW: Метод проверки наличия датапака Terralith
    private fun isTerralithInstalled(): Boolean {
        // 1. Проверяем через официальный DatapackManager (API 1.20+)
        try {
            val packs = Bukkit.getDatapackManager().enabledPacks
            if (packs.any { it.name.contains("terralith", ignoreCase = true) }) return true
        } catch (e: Exception) {
            // Игнорируем, если API почему-то недоступно
        }

        // 2. Проверяем физическую папку datapacks в главном мире
        val worldFolder = Bukkit.getWorlds().firstOrNull()?.worldFolder
        if (worldFolder != null) {
            val datapacksFolder = File(worldFolder, "datapacks")
            if (datapacksFolder.exists()) {
                val hasTerralithFile = datapacksFolder.listFiles()?.any {
                    it.name.contains("terralith", ignoreCase = true)
                } == true
                if (hasTerralithFile) return true
            }
        }

        // 3. Проверяем, если Terralith установлен как плагин-обертка
        if (Bukkit.getPluginManager().getPlugin("Terralith") != null) return true

        return false
    }

    /**
     * Extracts all files from the "biomes" folder inside the plugin's .jar
     * into the plugin's data folder. It will not overwrite existing files.
     */
    private fun saveDefaultBiomes() {
        val biomesDir = File(dataFolder, "biomes")
        if (!biomesDir.exists()) {
            biomesDir.mkdirs()
        }

        // NEW: Проверяем наличие Terralith перед распаковкой ресурсов
        val hasTerralith = isTerralithInstalled()
        if (hasTerralith) {
            logger.info("Terralith datapack detected! Extracting Terralith seasonal biomes...")
        }

        try {
            // Open the plugin's own .jar file
            JarFile(super.getFile()).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    // Look for everything inside the "biomes/" directory in the jar
                    if (name.startsWith("biomes/") && !entry.isDirectory) {

                        // NEW: Если файл/папка относится к Terralith, а датапака нет — пропускаем
                        if (name.contains("terralith", ignoreCase = true) && !hasTerralith) {
                            continue
                        }

                        val outFile = File(dataFolder, name)

                        // Only copy if the file doesn't exist yet (to prevent overwriting user edits)
                        if (!outFile.exists()) {
                            outFile.parentFile.mkdirs()
                            jar.getInputStream(entry).use { input ->
                                outFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warning("Failed to extract default biomes from jar: ${e.message}")
        }
    }

    init {
        plugin = this
        gson = GsonBuilder().setPrettyPrinting().create()
    }

    companion object {
        lateinit var plugin: SeasonsPlugin
        lateinit var gson: Gson

        fun lang(path: String, default: String): String {
            return plugin.language.getString(path, default) ?: default
        }

        fun Player.sendFormattedMessage(message: String) {
            this.sendMessage(plugin.gameplayManager.config.general.messagePrefix + " " + message)
        }
    }

}