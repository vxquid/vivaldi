package vx.vivaldi.config.lib

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import vx.vivaldi.Vivaldi.Companion.plugin
import vx.vivaldi.config.lib.annotations.Comment
import vx.vivaldi.config.lib.annotations.Configuration
import vx.vivaldi.config.lib.annotations.Header
import vx.vivaldi.config.lib.annotations.Ignore
import java.io.File
import java.io.FileWriter
import java.io.StringWriter
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.LinkedHashMap

@Suppress("UNCHECKED_CAST")
object ConfigurationManager {

    private val yaml = Yaml(DumperOptions().apply {
        isPrettyFlow = true
        indent = 2
        width = 80
    })

    fun <T : Any> load(configClass: Class<T>): T {
        val configInstance = configClass.getDeclaredConstructor().newInstance()
        val configPath = configClass.getAnnotation(Configuration::class.java)?.name ?: "config.yml"
        val configFile = plugin.dataFolder.resolve(configPath)

        if (!configFile.exists()) {
            configFile.parentFile?.mkdirs()
            configFile.createNewFile()
            save(configInstance)
            return configInstance
        }

        val loadedData = yaml.load<MutableMap<String, Any?>>(configFile.readText()) ?: mutableMapOf()

        val missing = applyToInstance(configInstance, loadedData, "")

        if (missing.isNotEmpty()) {
            val defaultData = toMap(configInstance)
            missing.forEach { key ->
                setNested(loadedData, key, getNested(defaultData, key))
            }
            saveMap(loadedData, configFile, configInstance)
        }

        return configInstance
    }

    fun save(config: Any) {
        val configPath = config::class.java.getAnnotation(Configuration::class.java)?.name ?: "config.yml"
        val configFile = plugin.dataFolder.resolve(configPath)
        val data = toMap(config)
        saveMap(data, configFile, config)
    }

    private fun applyToInstance(instance: Any, data: Map<String, Any?>, prefix: String): MutableList<String> {
        val missing = mutableListOf<String>()
        val fields = getFields(instance::class.java)
        fields.forEach { field ->
            val kebabField = toKebabCase(field.name)
            val fullKey = if (prefix.isEmpty()) kebabField else "$prefix.$kebabField"
            if (field.isAnnotationPresent(Ignore::class.java)) return@forEach

            field.isAccessible = true
            val value = data[kebabField]
            if (value == null) {
                missing.add(fullKey)
                return@forEach
            }

            if (isSimpleType(field.type)) {
                field.set(instance, convertValue(value, field.type))
            } else if (field.type == List::class.java) {
                field.set(instance, (value as? List<*>)?.map { convertValue(it, Any::class.java) } ?: emptyList<Any>())
            } else if (field.type == Map::class.java) {
                field.set(instance, value as? Map<*, *>)
            } else {
                val nestedInstance = field.get(instance) ?: field.type.getDeclaredConstructor().newInstance()
                field.set(instance, nestedInstance)
                val nestedPrefix = fullKey
                missing.addAll(applyToInstance(nestedInstance, value as? Map<String, Any?> ?: emptyMap(), nestedPrefix))
            }
        }
        return missing
    }

    private fun <T> convertValue(value: Any?, type: Class<T>): T? {
        if (value == null) return null
        return when (type) {
            Int::class.java -> (value as Number).toInt() as T
            Double::class.java -> (value as Number).toDouble() as T
            Float::class.java -> (value as Number).toFloat() as T
            Boolean::class.java -> value as Boolean as T
            String::class.java -> value.toString() as T
            else -> if (type.isEnum) {
                // Ищем Enum безопасно, игнорируя регистр. Если кто-то опечатается в конфиге, плагин не умрет.
                val enumConstants = type.enumConstants
                enumConstants.firstOrNull { it.toString().equals(value.toString(), ignoreCase = true) } as T
                    ?: enumConstants.first() as T
            } else value as T
        }
    }

    private fun toMap(instance: Any): MutableMap<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        val fields = getFields(instance::class.java)
        fields.forEach { field ->
            if (field.isAnnotationPresent(Ignore::class.java)) return@forEach
            field.isAccessible = true
            val value = field.get(instance)
            val kebabKey = toKebabCase(field.name)
            if (isSimpleType(field.type)) {
                map[kebabKey] = if (field.type.isEnum) value?.toString() else value
            } else if (field.type == List::class.java || field.type == Map::class.java) {
                map[kebabKey] = value
            } else {
                map[kebabKey] = toMap(value!!)
            }
        }
        return map
    }

    private fun saveMap(data: Map<String, Any?>, file: File, config: Any) {
        val writer = StringWriter()
        addHeader(writer, config)
        dumpWithComments(data, writer, config, 0)
        FileWriter(file).use { it.write(writer.toString()) }
    }

    private fun addHeader(writer: StringWriter, config: Any) {
        val headerAnnotation = config::class.java.getAnnotation(Header::class.java)
        if (headerAnnotation != null && headerAnnotation.comments.isNotEmpty()) {
            headerAnnotation.comments.forEach { line ->
                val prefixed = if (!line.trim().startsWith('#')) "# $line" else line
                writer.append(prefixed + "\n")
            }
        } else {
            val className = config::class.java.simpleName
            writer.append("# $className Configuration File\n")
        }
        writer.append("\n")
    }

    private fun isNodeComplex(value: Any?): Boolean {
        // Мы считаем узел "сложным", если это вложенный класс (сохраняется как Map) или список
        return value is Map<*, *> || value is List<*>
    }

    private fun dumpWithComments(data: Map<String, Any?>, writer: StringWriter, config: Any, indentLevel: Int) {
        val indent = "  ".repeat(indentLevel)

        if (config is Map<*, *>) {
            val keys = config.keys.map { it.toString() }
            keys.forEachIndexed { index, key ->
                writer.append("$indent$key: ")
                val value = config[key]

                if (value is Map<*, *>) {
                    writer.append("\n")
                    dumpWithComments(data[key] as? Map<String, Any?> ?: emptyMap(), writer, value, indentLevel + 1)
                } else if (value is List<*>) {
                    writer.append("\n")
                    value.forEach { item ->
                        writer.append("$indent  - ${yaml.dump(item).trim()}\n")
                    }
                } else {
                    writer.append(yaml.dump(value).trim() + "\n")
                }

                // Умные отступы для динамических мап
                if (index < keys.size - 1) {
                    val isCurrentComplex = isNodeComplex(value)
                    val nextValue = config[keys[index + 1]]
                    val isNextComplex = isNodeComplex(nextValue)

                    if (indentLevel == 0 || isCurrentComplex || isNextComplex) {
                        writer.append("\n")
                    }
                }
            }
            return
        }

        // Добавляем class-level comments перед всей секцией
        config::class.java.getAnnotation(Comment::class.java)?.value?.forEach { comment ->
            val prefixed = if (!comment.trim().startsWith('#')) "# $comment" else comment
            writer.append("$indent$prefixed\n")
        }

        val validFields = getFields(config::class.java).filter { !it.isAnnotationPresent(Ignore::class.java) }
        validFields.forEachIndexed { index, field ->

            field.getAnnotation(Comment::class.java)?.value?.forEach { comment ->
                val prefixed = if (!comment.trim().startsWith('#')) "# $comment" else comment
                writer.append("$indent$prefixed\n")
            }

            val kebabKey = toKebabCase(field.name)
            val value = data[kebabKey]

            writer.append("$indent$kebabKey: ")

            if (value is Map<*, *>) {
                writer.append("\n")
                field.isAccessible = true
                val nested = field.get(config)!!
                dumpWithComments(value as Map<String, Any?>, writer, nested, indentLevel + 1)
            } else if (value is List<*>) {
                writer.append("\n")
                value.forEach { item ->
                    writer.append("$indent  - ${yaml.dump(item).trim()}\n")
                }
            } else {
                writer.append(yaml.dump(value).trim() + "\n")
            }

            // Умные отступы для объектов
            if (index < validFields.size - 1) {
                val isCurrentComplex = isNodeComplex(value)
                val nextField = validFields[index + 1]
                val nextValue = data[toKebabCase(nextField.name)]
                val isNextComplex = isNodeComplex(nextValue)

                // Делаем разрыв строки, если мы на нулевом уровне, либо если текущий или следующий элемент — сложный блок
                if (indentLevel == 0 || isCurrentComplex || isNextComplex) {
                    writer.append("\n")
                }
            }
        }
    }

    private fun toKebabCase(camelCase: String): String {
        // Надежный паттерн: разделяет слова безопасно, не создавая лишних тире в начале
        return camelCase
            .replace(Regex("([a-z])([A-Z]+)"), "$1-$2")
            .replace(Regex("([A-Z])([A-Z][a-z])"), "$1-$2")
            .lowercase()
    }

    private fun getFields(clazz: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current = clazz
        while (current != Any::class.java) {
            fields.addAll(current.declaredFields.filter { !Modifier.isStatic(it.modifiers) })
            current = current.superclass ?: break
        }
        return fields
    }

    private fun isSimpleType(type: Class<*>): Boolean {
        return type.isPrimitive || type == String::class.java || Number::class.java.isAssignableFrom(type) ||
                Boolean::class.java == type || type.isEnum
    }

    private fun setNested(map: MutableMap<String, Any?>, key: String, value: Any?) {
        val parts = key.split(".")
        var current = map
        parts.dropLast(1).forEach { part ->
            current = current.computeIfAbsent(part) { LinkedHashMap<String, Any?>() } as MutableMap<String, Any?>
        }
        current[parts.last()] = value
    }

    private fun getNested(map: Map<String, Any?>, key: String): Any? {
        val parts = key.split(".")
        var current: Any? = map
        parts.forEach { part ->
            current = (current as? Map<String, Any?>)?.get(part)
        }
        return current
    }

}