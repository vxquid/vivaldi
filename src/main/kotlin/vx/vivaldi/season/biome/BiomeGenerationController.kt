package vx.vivaldi.season.biome

import vx.vivaldi.network.CachedVanillaBiome

/**
 * Constructs the AI prompt based entirely on intercepted NBT packet data
 * rather than the Bukkit API.
 */
class BiomeGenerationController(val cachedBiome: CachedVanillaBiome) {

    private val namespace = cachedBiome.namespace
    private val biomeName = cachedBiome.key.replace("_", " ")

    private val generatorContext = if (namespace == "minecraft") {
        "This is a standard Vanilla Minecraft biome."
    } else {
        "This biome belongs to a custom Minecraft world generator / datapack / mod named '$namespace'."
    }

    /**
     * Safely formats integer colors into hex.
     * In NBT, if a color is missing or explicitly 0, we assume it relies on the hardcoded Minecraft biome color maps.
     */
    private fun formatColor(color: Int?): String {
        return if (color != null && color != 0) String.format("#%06X", color and 0xFFFFFF) else "Auto"
    }

    private val temp = cachedBiome.temperature
    private val downfall = cachedBiome.downfall // Humidity (0.0 to 1.0+)

    /*
     We map the intercepted packet NBT data.
     If a value is "Auto", the AI understands that it needs to figure out
     the visual identity based on temperature and downfall.
    */
    private val waterColor = formatColor(cachedBiome.waterColor)
    private val waterFogColor = formatColor(cachedBiome.waterFogColor)
    private val skyColor = formatColor(cachedBiome.skyColor)
    private val fogColor = formatColor(cachedBiome.fogColor)
    private val grassColor = formatColor(cachedBiome.grassColor)
    private val foliageColor = formatColor(cachedBiome.foliageColor)

    // PROMPT UPDATED: Added context regarding dynamic temperatures to ensure AI is fully aware.
    private val biomePrompt = """
        You are an expert Minecraft technical artist and game designer. Your task is to generate custom seasonal color palettes for a specific biome.
        Respond ONLY with valid, parseable JSON. Do not include markdown code blocks (like ```json), explanations, or any other text.
        
        ### BASE BIOME DATA (ORIGINAL/VANILLA):
        - Origin Generator: `$namespace` ($generatorContext)
        - Biome Name: `$biomeName`
        - Base Temperature: $temp (determines if it's freezing, temperate, or hot. Note: The plugin dynamically shifts this in-game by +0.4 in Summer and -0.8 in Winter to allow realistic weather like snow, but you must base your colors strictly on the original base temperature.)
        - Humidity/Downfall: $downfall (determines if it's arid, average, or wet)
        - Original Water Color: $waterColor
        - Original Water Fog Color: $waterFogColor
        - Original Sky Color: $skyColor
        - Original Fog Color: $fogColor
        - Original Grass Color: $grassColor
        - Original Foliage Color: $foliageColor
        
        ### AESTHETIC STYLE & TONE (CRITICAL):
        - Make the seasonal colors VIBRANT, LUSH, and VISUALLY STRIKING. 
        - DO NOT use muddy, gloomy, overly dark, or "dirty" hex codes. 
        - The seasons should "pop" and look picturesque, similar to high-quality stylized shaders.
        
        ### INSTRUCTIONS:
        1. Analyze the BASE BIOME DATA. If a color is marked as "Auto", deduce its default visual appearance in Minecraft based on the Temperature and Humidity.
        2. Use the Original Colors as your baseline. For example, if the original biome is "summer-like", use its exact or slightly enhanced colors for the "summer" season.
        3. Based on the baseline, generate 4 seasons: spring, summer, autumn, and winter by visually shifting the hex colors.
        4. For EACH season, provide two variants: "normal" and "alternate".
        5. ALL colors MUST be provided as standard 6-character Hex Color codes starting with '#' (e.g., "#8EB971").
        
        ### VARIANT GUIDELINES ("Normal" vs "Alternate"):
        - The "normal" variant represents the standard seasonal look.
        - The "alternate" variant is used to break up repeating patterns and create visually distinct mixed forests.
        - CRITICAL: For the "alternate" variant's FOLIAGE COLOR, you MUST use a SIGNIFICANT HUE SHIFT (e.g., if normal autumn foliage is bright red, the alternate foliage MUST be bright golden yellow or vivid orange). Do not just change the brightness; shift the color entirely to create contrast.
        - For ALL OTHER COLORS (grass, water, sky, fog) in the "alternate" variant, use only a SLIGHT gradation/deviation in hue or brightness (about 5-10% shift) from the "normal" variant.
        
        ### SEASONAL SHIFT GUIDELINES:
        - SPRING: Shift baseline greens to be much fresher, blooming, lighter, and highly vibrant.
        - SUMMER: Deep, rich, lush emerald greens and saturated colors. 
        - AUTUMN: Shift foliage and grass into FIERY reds, GOLDEN yellows, and VIVID burnt oranges. Minimize dark "dead" browns; keep the autumn forest glowing and colorful.
        - WINTER: Shift colors to be crisp, cold, and frosty. Use icy blues, pale cyans, and soft cool tones instead of flat, dead gray. Make it look like a bright, magical winter morning.
        *Note: Respect the biome's core identity. A snowy taiga shouldn't become tropical in summer; it should just melt slightly.*
        
        ### JSON SCHEMA TO FOLLOW:
        {
          "spring": {
            "normal": { "grassColor": "#...", "foliageColor": "#...", "waterColor": "#...", "waterFogColor": "#...", "skyColor": "#...", "fogColor": "#..." },
            "alternate": { "grassColor": "#...", "foliageColor": "#...", "waterColor": "#...", "waterFogColor": "#...", "skyColor": "#...", "fogColor": "#..." }
          },
          "summer": { ... },
          "autumn": { ... },
          "winter": { ... }
        }
    """.trimIndent()

    val prompt = biomePrompt
}