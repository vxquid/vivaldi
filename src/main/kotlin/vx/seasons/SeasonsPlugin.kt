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
import java.util.regex.Pattern

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
        saveDefaultBiomes()

        this.providerManager = ProviderManager()
        this.gameplayManager = GameplayManager(this)
        this.commandManager  = PaperCommandManager(this)
        this.commandManager.registerCommand(VivaldiCommand())

        this.seasonalBiomeManager = SeasonalBiomeManager()
        this.seasonalBiomeManager.loadAllBiomes()

        this.seasonManager = SeasonManager(this)
        this.seasonManager.initialize()

        if (plugin.gameplayManager.config.general.enableSeasons) {
            com.github.retrooper.packetevents.PacketEvents.getAPI().eventManager.registerListener(
                BiomeRegistryInterceptor,
                com.github.retrooper.packetevents.event.PacketListenerPriority.NORMAL
            )
        }
    }

    override fun onDisable() {
        if (this::seasonManager.isInitialized) {
            seasonManager.saveToPDC()
            logger.info("Season state saved successfully.")
        }
    }

    private fun isTerralithInstalled(): Boolean {
        try {
            val packs = Bukkit.getDatapackManager().enabledPacks
            if (packs.any { it.name.contains("terralith", ignoreCase = true) }) return true
        } catch (_: Exception) {}

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

        if (Bukkit.getPluginManager().getPlugin("Terralith") != null) return true

        return false
    }

    private fun saveDefaultBiomes() {
        val biomesDir = File(dataFolder, "biomes")
        if (!biomesDir.exists()) {
            biomesDir.mkdirs()
        }

        val hasTerralith = isTerralithInstalled()
        if (hasTerralith) {
            logger.info("Terralith datapack detected! Extracting Terralith seasonal biomes...")
        }

        try {
            JarFile(super.getFile()).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    if (name.startsWith("biomes/") && !entry.isDirectory) {
                        if (name.contains("terralith", ignoreCase = true) && !hasTerralith) {
                            continue
                        }

                        val outFile = File(dataFolder, name)

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

        fun String.colorize(): String {
            val hexPattern = Pattern.compile("&#([A-Fa-f0-9]{6})")
            val matcher = hexPattern.matcher(this)
            val buffer = StringBuffer()
            while (matcher.find()) {
                val hex = matcher.group(1)
                val replacement = buildString {
                    append("§x")
                    for (c in hex) {
                        append("§").append(c)
                    }
                }
                matcher.appendReplacement(buffer, replacement)
            }
            matcher.appendTail(buffer)
            return org.bukkit.ChatColor.translateAlternateColorCodes('&', buffer.toString())
        }

        fun lang(path: String, default: String): String {
            val raw = plugin.language.getString(path, default) ?: default
            return raw.colorize()
        }

        fun Player.sendFormattedMessage(message: String) {
            val prefix = plugin.gameplayManager.config.general.messagePrefix.colorize()
            this.sendMessage(prefix + " " + message.colorize())
        }
    }
}