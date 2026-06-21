package vx.seasons.ai

import org.bukkit.event.Listener
import vx.seasons.SeasonsPlugin.Companion.plugin
import vx.seasons.ai.base.AIClient
import vx.seasons.ai.base.DummyClient
import vx.seasons.config.ProviderConfiguration
import vx.seasons.config.ProviderConfiguration.ProviderType
import vx.seasons.config.lib.ConfigurationManager

class ProviderManager : Listener {

    lateinit var config: ProviderConfiguration
    lateinit var client: AIClient

    init {
        load()
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /**
     * Loads the configuration from disk and initializes the AI Client.
     * Can be called multiple times to hot-reload settings.
     */
    fun load() {
        this.config = ConfigurationManager.load(ProviderConfiguration::class.java)
        this.client = createClient()
    }

    private fun createClient(): AIClient {
        val apiKey = listOf(config.apiKey)

        // Return a DummyClient if the key is default or empty
        if (config.apiKey == "YOUR_API_KEY" || config.apiKey == "DISABLED" || config.apiKey.isBlank()) {
            return DummyClient()
        }

        plugin.logger.info("Initializing AI provider: ${config.providerType}")

        return when (config.providerType) {
            ProviderType.CEREBRAS -> CerebrasClient(CerebrasClient.KeyManager(apiKey), config)
        }
    }

}