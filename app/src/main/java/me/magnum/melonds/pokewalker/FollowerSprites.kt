package me.magnum.melonds.pokewalker

/**
 * The walking Pokémon as HeartGold/SoulSilver draws it when it follows the player: its overworld
 * sprite from the player's own ROM (`a/0/8/1`, data/mmodel/mmodel.narc), eight 32x32 (or 64x64)
 * frames: back, front, left and right, two steps each, with a normal and a shiny palette.
 *
 * Which sprite a Pokémon gets follows src/follow_mon.c FollowMon_GetSpriteID: a per-species index,
 * plus one for a female when the species has a female sprite, else plus its form.
 */
object FollowerSprites {
    const val MMODEL = "a/0/8/1"
    /** MMODEL_FOLLOWER_MON_BASE (include/constants/mmodel.h). */
    const val FIRST = 297
    private const val LAST = 862

    const val UP = 0
    const val DOWN = 1
    const val LEFT = 2
    const val RIGHT = 3

    /** Species -> follower index (sModelIndexLUT), three hex digits per species from #1. */
    private const val INDEX =
        "00000100200400500600700800900a00b00c00d00e00f01001101201301401501601701801901b01c01d01e01f020021022023024025026" +
        "02702802902a02b02c02d02e02f03003103203303403503603703803903a03b03c03d03e03f04004104204304404504604704804904a04b" +
        "04c04d04e04f05005105205305405505605705805905a05b05c05d05e05f06006106206306406506606706806906a06b06c06d06e06f070" +
        "07107207307407507607707807907a07b07c07d07e07f08008108208308408508608708808908a08b08c08d08e08f090091092093094095" +
        "09609709809909a09b09d09e09f0a00a10a20a30a40a50a60a70a80a90aa0ab0ac0ad0ae0b00b10b20b30b40b50b60b70b80b90ba0bb0bc" +
        "0bd0be0bf0c00c10c20c30c40c50c60c70c80c90ca0cb0cc0e80ea0eb0ec0ed0ee0ef0f10f20f30f40f50f60f80f90fa0fb0fc0fd0fe0ff" +
        "10010110210310410510610710810910a10b10c10d10e10f11011111211311411511611711811911a11b11c11d11e11f120121122123124" +
        "12512612712812912a12b12c12d12e12f13013113213313413513613713813913a13b13c13d13e13f140141142143144145146147148149" +
        "14a14b14c14d14e14f15015115215315415515615715815915a15b15c15d15e15f16016116216316416516616716816916a16b16c16d16e" +
        "16f17017117217317417517617717817917a17b17c17d17e17f18018118218318418518618718818918a18b18c18d18e18f190191192193" +
        "19419519619719819919a19b19c19d19e19f1a01a11a21a31a71a81a91aa1ab1ac1ad1ae1af1b01b11b21b31b41b51b61b71b81b91ba1bb" +
        "1bc1bd1be1bf1c01c31c61c71c91ca1cb1cc1cd1ce1cf1d11d31d41d51d61d71d81d91da1db1dc1dd1de1df1e01e11e21e31e41e51e61e8" +
        "1ea1ec1ed1ee1ef1f11f31f41f51f61f71f81f91fa1fb1fc1fd1fe1ff20020120220320420520620720820920a20b20c20d20e20f215216" +
        "21721821921a21b21c21e21f220221222224"

    /** Species with more forms than one sprite (sFormMaxLUT): highest form number. */
    private val FORMS = mapOf(172 to 1, 201 to 27, 386 to 3, 412 to 2, 413 to 2, 422 to 1, 423 to 1, 479 to 5, 487 to 1, 492 to 1, 493 to 17)

    /** Species with a female sprite (sFemaleFlagLUT). */
    private val FEMALE = setOf(3, 25, 154, 202, 208, 214, 415, 443, 444, 445, 449, 450)

    /** The overworld sprite (mmodel) number for a Pokémon. */
    fun spriteFor(species: Int, form: Int, female: Boolean): Int {
        if (species !in 1..493) return FIRST
        var i = INDEX.substring((species - 1) * 3, species * 3).toInt(16)
        if (species in FEMALE) {
            if (female) i++
        } else {
            val max = FORMS[species] ?: 0
            if (form in 1..max) i += form
        }
        return (FIRST + i).coerceAtMost(LAST)
    }

    /**
     * Frames of one sprite: [direction * perDirection + step] (see [UP] ...), ARGB, [size] x [size].
     * Pokémon have two a way (two steps); people four (stand, step, stand, other step).
     */
    class Sprite(val frames: Array<IntArray>, val size: Int, val perDirection: Int = 2) {
        fun frame(direction: Int, step: Int): IntArray =
            frames[(direction * perDirection + Math.floorMod(step, perDirection)).coerceIn(0, frames.size - 1)]
    }

    /** Reads the sprite; null when the ROM doesn't have it in the expected shape. */
    fun load(src: CryArchive.Source, species: Int, form: Int, female: Boolean, shiny: Boolean): Sprite? {
        val narc = Nitro.narc(src, MMODEL) ?: return null
        val index = spriteFor(species, form, female)
        if (index >= narc.count) return null
        val set = Nitro.parse(narc.read(index)).texSet ?: return null
        return fromTexSet(set, shiny)
    }

    /**
     * Someone's overworld sprite by its mmodel number ([FieldPeople.modelFor]): a person's
     * sixteen frames, or a Pokémon's eight; null when it's neither (signs, trees, rocks).
     */
    fun loadModel(src: CryArchive.Source, model: Int): Sprite? {
        val narc = Nitro.narc(src, MMODEL) ?: return null
        if (model < 0 || model >= narc.count) return null
        val set = Nitro.parse(narc.read(model)).texSet ?: return null
        return fromPersonTexSet(set) ?: if (model >= FIRST) fromTexSet(set, false) else null
    }

    /**
     * A thing that stands still on the map by its mmodel number (a cracked rock, a boulder): its
     * one picture, set in a square with its bottom at the bottom; null when there's none.
     */
    fun loadStill(src: CryArchive.Source, model: Int): Sprite? {
        val narc = Nitro.narc(src, MMODEL) ?: return null
        if (model < 0 || model >= narc.count) return null
        val set = Nitro.parse(narc.read(model)).texSet ?: return null
        val t = set.textures.values.firstOrNull() ?: return null
        val pal = set.palettes.values.firstOrNull() ?: return null
        val px = Nitro.decode(t, pal) ?: return null
        val size = maxOf(t.width, t.height)
        if (size > 64) return null
        val out = IntArray(size * size)
        val left = (size - t.width) / 2
        val top = size - t.height
        for (y in 0 until t.height) for (x in 0 until t.width) out[(top + y) * size + left + x] = px[y * t.width + x]
        return Sprite(arrayOf(out), size, 1)
    }

    /** A person's sixteen frames, by the number after the dot (gswoman1.1 ... .16), with palette 0. */
    fun fromPersonTexSet(set: Nitro.TexSet): Sprite? {
        if (set.textures.size < 16) return null
        val ordered = set.textures.entries
            .map { (it.key.substringAfterLast('.').toIntOrNull() ?: Int.MAX_VALUE) to it.value }
            .sortedBy { it.first }
            .take(16)
        if (ordered.any { it.first == Int.MAX_VALUE }) return null
        val pal = set.palettes.values.firstOrNull() ?: return null
        val size = ordered[0].second.width
        if (size != 32 && size != 64) return null
        val frames = Array(16) { i ->
            val t = ordered[i].second
            if (t.width != size || t.height != size) return null
            Nitro.decode(t, pal) ?: return null
        }
        return Sprite(frames, size, 4)
    }

    /** The eight frames in the texture set's order (tsure_poke.1, .10 ... .16), with palette 0 or the shiny one. */
    fun fromTexSet(set: Nitro.TexSet, shiny: Boolean): Sprite? {
        val textures = set.textures.values.toList()
        if (textures.size < 8) return null
        val palettes = set.palettes.values.toList()
        if (palettes.isEmpty()) return null
        val pal = if (shiny && palettes.size > 1) palettes[1] else palettes[0]
        val size = textures[0].width
        val frames = Array(8) { i ->
            val t = textures[i]
            if (t.width != size || t.height != size) return null
            Nitro.decode(t, pal) ?: return null
        }
        return Sprite(frames, size)
    }
}

/** The walking Pokémon as the game hands it to the walker: species, form, female, shiny. */
data class WalkingPokemon(val species: Int, val form: Int, val female: Boolean, val shiny: Boolean) {
    companion object {
        /**
         * From the walker's memory: the route info at 0x8F00 starts with the Pokémon's species,
         * then at +0x0D its form (bits 0-4) and gender (bits 5-6, 1 = female), at +0x0E bit 1
         * shiny (how HG/SS fills it in overlay 112).
         */
        fun fromImage(image: ByteArray?): WalkingPokemon? = fromSummary(image, 0x8F00)

        /** A 16-byte Pokémon summary (picowalker-core's pokemon_summary_t) at [o]; null when there's none. */
        fun fromSummary(image: ByteArray?, o: Int): WalkingPokemon? {
            if (image == null || o < 0 || image.size < o + 0x10) return null
            val species = (image[o].toInt() and 0xFF) or ((image[o + 1].toInt() and 0xFF) shl 8)
            if (species !in 1..493) return null
            val v = image[o + 0x0D].toInt() and 0xFF
            val f = image[o + 0x0E].toInt() and 0xFF
            return WalkingPokemon(species, v and 0x1F, (v shr 5) and 3 == 1, f and 0x02 != 0)
        }

        /** The course's three Pokémon you can find on the walk (route info +0x52, three summaries). */
        fun routePokemon(image: ByteArray?): List<WalkingPokemon> = (0 until 3).mapNotNull { fromSummary(image, 0x8F52 + it * 0x10) }

        /** The Pokémon caught on this walk so far (0xCE8C, three summaries; empty slots have no species). */
        fun caught(image: ByteArray?): List<WalkingPokemon> = (0 until 3).mapNotNull { fromSummary(image, 0xCE8C + it * 0x10) }
    }
}

/** Who's on the walk: the walking Pokémon, the course's Pokémon and the ones caught so far (from the walker's memory). */
data class WalkParty(
    val walking: WalkingPokemon?,
    val route: List<WalkingPokemon> = emptyList(),
    val caught: List<WalkingPokemon> = emptyList(),
    /** The walk's items (the game's item numbers). */
    val items: List<Int> = emptyList(),
) {
    companion object {
        fun fromImage(image: ByteArray?) =
            WalkParty(WalkingPokemon.fromImage(image), WalkingPokemon.routePokemon(image), WalkingPokemon.caught(image), routeItems(image))

        /** The walk's ten items (route info +0x8C, picowalker-core's le_route_items), leaving out empty ones. */
        fun routeItems(image: ByteArray?): List<Int> {
            val o = 0x8F8C
            if (image == null || image.size < o + 20) return emptyList()
            return (0 until 10).map { (image[o + it * 2].toInt() and 0xFF) or ((image[o + it * 2 + 1].toInt() and 0xFF) shl 8) }.filter { it in 1..536 }
        }
    }
}

/**
 * Pokémon types from the player's own ROM: the personal data (`a/0/0/2`,
 * poketool/personal/personal.narc), a member a species, type 1 and 2 at bytes 6 and 7.
 */
object PokemonTypes {
    const val PERSONAL = "a/0/0/2"
    const val FLYING = 2
    const val BUG = 6
    const val GHOST = 7
    const val FIRE = 10
    const val WATER = 11

    /** The species' types (one or two); empty when the ROM doesn't say. */
    fun of(src: CryArchive.Source, species: Int): Set<Int> {
        val narc = Nitro.narc(src, PERSONAL) ?: return emptySet()
        if (species !in 1..493 || species >= narc.count) return emptySet()
        val b = runCatching { narc.read(species) }.getOrNull() ?: return emptySet()
        if (b.size < 8) return emptySet()
        val a = b[6].toInt() and 0xFF
        val c = b[7].toInt() and 0xFF
        if (a > 17 || c > 17) return emptySet()
        return setOf(a, c)
    }
}
