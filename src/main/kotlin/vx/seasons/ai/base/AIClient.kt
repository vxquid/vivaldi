package vx.seasons.ai.base

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.reflect.KClass

interface AIClient {
    fun <T : Any> sendPromptWithSchema(
        prompt: String,
        targetClass: KClass<T>
    ): T?

    fun translate(yamlConfig: YamlConfiguration): YamlConfiguration?
}