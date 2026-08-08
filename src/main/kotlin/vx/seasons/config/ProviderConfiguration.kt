package vx.seasons.config

import vx.seasons.config.lib.annotations.Comment
import vx.seasons.config.lib.annotations.Configuration

@Configuration("provider.yml")
class ProviderConfiguration {

    @Comment(
        "The provider type for content generation.",
        "GEMINI (aistudio.google.com) provides fast inference and high quality generations. Recommended, used by default."
    )
    var providerType: ProviderType = ProviderType.GEMINI

    @Comment(
        "For GEMINI, default model is \"gemini-3.1-flash-lite\"."
    )
    var model = "gemini-3.1-flash-lite"

    @Comment("The API key(s) used to authenticate with Gemini. Can be a single key or multiple keys separated by commas.")
    var apiKey: String = "YOUR_API_KEY"

    @Comment(
        "The language for generated content. Specify the desired language (e.g., 'English', 'Spanish', 'Russian', etc.)."
    )
    var language: String = "English"

    @Comment(
        "The naming convention for generated content. For example, 'Fantasy Names' for standard Fantasy-style names."
    )
    var namingStyle: String = "Fantasy Names"

    @Comment(
        "The thematic setting for content generation. For example, 'Minecraft Universe' for default atmosphere."
    )
    var setting: String = "Minecraft Universe"

    @Comment(
        "Controls the randomness of generated content. Higher values (e.g., 1.0) increase creativity."
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
        GEMINI
    }

}