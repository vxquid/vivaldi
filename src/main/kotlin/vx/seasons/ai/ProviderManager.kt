package vx.seasons.ai

import vx.seasons.ai.base.AIClient
import vx.seasons.config.ProviderConfiguration
import vx.seasons.config.lib.ConfigurationManager

class ProviderManager {
    lateinit var config: ProviderConfiguration
    lateinit var client: AIClient

    fun load() {
        config = ConfigurationManager.load(ProviderConfiguration::class.java)
        client = when (config.providerType) {
            ProviderConfiguration.ProviderType.GEMINI -> GeminiClient(config)
        }
    }

    init {
        load()
    }
}