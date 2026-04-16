package vx.embark.config

import vx.embark.config.lib.annotations.Comment
import vx.embark.config.lib.annotations.Configuration

@Configuration("provider.yml")
class ProviderConfiguration {

    @Comment(
        "The provider type for content generation.",
        "CEREBRAS (cloud.cerebras.ai) provides blazing fast inference for open-source models (like Llama 3) and offers a generous free tier. Recommended, used by default."
    )
    var providerType: ProviderType = ProviderType.CEREBRAS

    @Comment(
        "For CEREBRAS, default model is \"qwen-3-235b-a22b-instruct-2507\"."
    )
    var model = "qwen-3-235b-a22b-instruct-2507"

    @Comment("The API key used to authenticate with the selected provider.")
    var apiKey: String = "YOUR_API_KEY"

    @Comment(
        "The language for generated content. Specify the desired language (e.g., 'English', 'Spanish', 'Russian', 'Dalek Language', 'Moonspeak', etc.)."
    )
    var language: String = "English"

    @Comment(
        "The naming convention for generated content. For example, 'English Names' for standard English-style names."
    )
    var namingStyle: String = "Fantasy Names"

    @Comment(
        "The thematic setting for content generation. For example, 'Fantasy' for a fantasy-themed world."
    )
    var setting: String = "Minecraft Universe"

    @Comment(
        "Controls the randomness of generated content. Higher values (e.g., 2.0) increase creativity but may reduce coherence."
    )
    var temperature: Double = 1.0

    @Comment(
        "The maximum number of retry attempts after a failed content generation request."
    )
    var maxRetries: Int = 2

    @Comment(
        "Proxy configuration for connecting to providers in regions where they are restricted. Won't be used if host name is PROXY_HOST."
    )
    var proxy: Proxy = Proxy()

    @Configuration
    data class Proxy(
        @Comment("The type of proxy to use (e.g., HTTP, SOCKS).")
        var type: java.net.Proxy.Type = java.net.Proxy.Type.HTTP,

        @Comment("The proxy server hostname or IP address.")
        var host: String = "PROXY_HOST",

        @Comment("The port number for the proxy server.")
        var port: Int = 1337,

        @Comment("The username for proxy authentication, if required.")
        var user: String = "PROXY_USERNAME",

        @Comment("The password for proxy authentication, if required.")
        var pass: String = "PROXY_PASSWORD"
    )

    enum class ProviderType {
        CEREBRAS
    }

}