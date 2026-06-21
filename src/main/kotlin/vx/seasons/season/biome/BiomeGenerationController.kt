package vx.seasons.season.biome

import vx.seasons.network.CachedVanillaBiome

/**
 * Constructs the AI prompt based entirely on intercepted NBT packet data
 * rather than the Bukkit API.
 */
class BiomeGenerationController(cachedBiome: CachedVanillaBiome) {

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

    // PROMPT UPDATED: Highly structured rules to prevent neon colors and enforce biome identities.
    private val biomePrompt = """
        You are an expert Minecraft technical artist and game designer. Your task is to generate custom seasonal color palettes for a specific biome.
        Respond ONLY with valid, parseable JSON. Do not include markdown code blocks (like ```json), explanations, or any other text.
        
        ### BASE BIOME DATA (ORIGINAL/VANILLA):
        - Origin Generator: `$namespace` ($generatorContext)
        - Biome Name: `$biomeName`
        - Base Temperature: $temp (Determines climate. NOTE: The plugin shifts this in-game for weather, but your colors MUST be based on this original base value.)
        - Humidity/Downfall: $downfall (0.0 is completely arid, 1.0+ is very wet)
        - Original Water Color: $waterColor
        - Original Sky Color: $skyColor
        - Original Grass Color: $grassColor
        - Original Foliage Color: $foliageColor
        
        ### AESTHETIC STYLE & TONE (STRICT CONSTRAINTS):
        - The colors must be NATURAL, ATMOSPHERIC, and PLEASING to the eye.
        - ABSOLUTELY NO ACIDIC, NEON, OR EYE-STRAINING COLORS. Avoid pure greens (#00FF00), pure reds (#FF0000), or overly saturated values.
        - Use earthy, organic, and pastel tones akin to high-quality stylized shaders (e.g., soft lime, olive, burnt orange, pale frost).
        
        ### BIOME CLIMATE RULES (CRITICAL):
        Before applying seasonal changes, classify the biome based on its Name, Temperature, and Humidity, and apply these absolute rules:
        1. TROPICAL / EVERGREEN (e.g., Jungle, Bamboo, Mangrove): 
           - MUST remain green year-round. 
           - Autumn should ONLY feature very slight yellowing or darker olive hues. NO red/orange autumns.
           - Winter should feature deeper, cooler, slightly desaturated greens. NO blue/white grass or foliage.
        2. BOREAL / COLD (e.g., Taiga, Grove, Snowy biomes, Pine forests):
           - Summer colors must remain COOL and DARKER (e.g., deep pine, spruce green). NO bright lime greens.
           - Autumn should be a mix of muted pine green with occasional dull, rusted orange (in the alternate variant).
        3. ARID / DRY (e.g., Savanna, Badlands, Desert):
           - Keep colors dry, khaki, olive, and dusty year-round. Do not introduce lush greens or snowy blues.
        4. TEMPERATE (e.g., Plains, Oak Forest, Birch Forest):
           - Allow full seasonal expression (Soft lime spring, rich green summer, vibrant autumn, frosty winter).
        
        ### SEASONAL SHIFT GUIDELINES:
        - SPRING: Soft, fresh, pale yellow-greens. Think of new leaf buds. Never neon.
        - SUMMER: Natural, healthy greens. Light lime tones for temperate biomes, but deep/cool greens for taiga/cold biomes.
        - AUTUMN: Warm, rich tones (gold, amber, rusted red, burnt orange). Remember the Climate Rules: tropical/arid biomes do not get typical autumn colors!
        - WINTER: Frost-kissed, desaturated cool tones. Soft icy cyans and pale frosty greens/browns. Make it look cold but natural, not like a smudged gray mess.
        
        ### INSTRUCTIONS:
        1. Deduce the base visual appearance using the Original Colors and Climate Rules. (If a color is "Auto", infer it).
        2. Generate 4 seasons: spring, summer, autumn, and winter.
        3. For EACH season, provide two variants: "normal" and "alternate".
        4. All colors MUST be standard 6-character Hex Codes starting with '#' (e.g., "#8EB971").
        
        ### VARIANT GUIDELINES ("Normal" vs "Alternate"):
        - "normal": The standard, unified look for the season.
        - "alternate": Used to create visually distinct mixed forests (e.g., birch trees inside an oak forest).
        - For FOLIAGE COLOR in the "alternate" variant, use a noticeable HUE SHIFT while keeping it natural (e.g., if normal Autumn is amber, alternate could be a soft rusted red; if normal Spring is pale green, alternate could be a soft cherry-blossom pink or willow-green).
        - For ALL OTHER COLORS (grass, water, sky) in the "alternate" variant, use only a VERY SLIGHT deviation (about 3-5% shift) from the "normal" variant.
        
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