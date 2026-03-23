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

class Vivaldi : JavaPlugin() {

    lateinit var providerManager: ProviderManager
    lateinit var gameplayManager: GameplayManager
    lateinit var commandManager:  PaperCommandManager
    lateinit var seasonalBiomeManager: SeasonalBiomeManager

    lateinit var seasonManager: SeasonManager

    var language: YamlConfiguration = run {
        val file = File(super.getDataFolder(), "language.yml")
        if (!file.exists()) super.saveResource("language.yml", false)
        YamlConfiguration.loadConfiguration(file)
    }

    override fun onEnable() {
        this.providerManager = ProviderManager()
        this.gameplayManager = GameplayManager(this)
        this.commandManager  = PaperCommandManager(this)
        this.commandManager.registerCommand(VivaldiCommand())
        this.seasonalBiomeManager = SeasonalBiomeManager()
        this.seasonalBiomeManager.loadAllBiomes()
        this.seasonManager = SeasonManager(this)

        com.github.retrooper.packetevents.PacketEvents.getAPI().eventManager.registerListener(
            BiomeRegistryInterceptor,
            com.github.retrooper.packetevents.event.PacketListenerPriority.NORMAL
        )

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