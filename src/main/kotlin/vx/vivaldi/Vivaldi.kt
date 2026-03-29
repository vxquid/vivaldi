package vx.vivaldi

import co.aikar.commands.PaperCommandManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import vx.vivaldi.ai.ProviderManager
import vx.vivaldi.command.VivaldiCommand
import vx.vivaldi.gameplay.GameplayManager
import vx.vivaldi.network.BiomeRegistryInterceptor
import vx.vivaldi.season.SeasonManager
import vx.vivaldi.season.biome.SeasonalBiomeManager

import java.io.File
import java.util.jar.JarFile

class Vivaldi : JavaPlugin() {

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

        com.github.retrooper.packetevents.PacketEvents.getAPI().eventManager.registerListener(
            BiomeRegistryInterceptor,
            com.github.retrooper.packetevents.event.PacketListenerPriority.NORMAL
        )
    }

    override fun onDisable() {
        // Гарантированное сохранение сезона перед выгрузкой миров сервером
        if (this::seasonManager.isInitialized) {
            seasonManager.saveToPDC()
            logger.info("§a[Vivaldi] Season state saved successfully.")
        }
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

        try {
            // Open the plugin's own .jar file
            JarFile(super.getFile()).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    // Look for everything inside the "biomes/" directory in the jar
                    if (name.startsWith("biomes/") && !entry.isDirectory) {
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
        lateinit var plugin: Vivaldi
        lateinit var gson: Gson

        fun lang(path: String, default: String): String {
            return plugin.language.getString(path, default) ?: default
        }

        fun Player.sendFormattedMessage(message: String) {
            this.sendMessage(plugin.gameplayManager.config.general.messagePrefix + " " + message)
        }
    }
}