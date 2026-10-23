package me.magnum.melonds.pokewalker

import org.json.JSONObject

/** Snapshot of the virtual Pokéwalker, parsed from the native status JSON. */
data class WalkerStatus(
    val open: Boolean,
    val mode: Int,
    val registered: Boolean,
    val hasPokemon: Boolean,
    val sessionActive: Boolean,
    val trainer: String,
    val todaySteps: Long,
    val totalSteps: Long,
    val watts: Int,
    val totalDays: Int,
    val lastSyncUnix: Long,
    val strollStartUnix: Long,
    val history: List<Long>,
    val pokemonName: String,
    val speciesName: String,
    val level: Int,
    val route: String,
    val caught: Int,
    val dowsed: Int,
    val gifted: Int,
    /** Result of the HeartGold/SoulSilver copy check for the loaded game, if one ran. */
    val copyCheck: CopyCheck?,
    /** The game loaded in the emulator is HeartGold or SoulSilver. */
    val gameIsPokewalkerCompatible: Boolean = false,
    /** National dex number of the walking Pokémon (0 when none). */
    val species: Int = 0,
    /** The route's picture kind (0 field, 1 forest, 2 suburbs, 3 town, 4 mountain, 5 cave, 6 lake, 7 beach), -1 if unknown. */
    val routeImage: Int = -1,
) {
    companion object {
        fun parse(json: String): WalkerStatus {
            val o = JSONObject(json)
            val pokemon = o.optJSONObject("pokemon")
            val history = o.optJSONArray("history")
            val nickname = pokemon?.optString("nickname").orEmpty()
            val species = pokemon?.optString("speciesName").orEmpty()
            return WalkerStatus(
                open = o.optBoolean("open"),
                mode = o.optInt("mode"),
                registered = o.optBoolean("registered"),
                hasPokemon = o.optBoolean("hasPokemon"),
                sessionActive = o.optBoolean("sessionActive"),
                trainer = o.optString("trainer"),
                todaySteps = o.optLong("todaySteps"),
                totalSteps = o.optLong("totalSteps"),
                watts = o.optInt("watts"),
                totalDays = o.optInt("totalDays"),
                lastSyncUnix = o.optLong("lastSyncUnix"),
                strollStartUnix = o.optLong("strollStartUnix"),
                history = if (history == null) emptyList() else List(history.length()) { history.optLong(it) },
                pokemonName = nickname.ifBlank { species },
                speciesName = species,
                level = pokemon?.optInt("level") ?: 0,
                route = pokemon?.optString("route").orEmpty(),
                caught = o.optInt("caught"),
                dowsed = o.optInt("dowsed"),
                gifted = o.optInt("gifted"),
                gameIsPokewalkerCompatible = o.optBoolean("gameIsPokewalkerCompatible"),
                species = pokemon?.optInt("species") ?: 0,
                routeImage = pokemon?.optInt("routeImage", -1) ?: -1,
                copyCheck = o.optJSONObject("romCheck")?.let { c ->
                    CopyCheck(
                        release = c.optString("release"),
                        rom = c.optString("rom"),
                        trimmed = c.optBoolean("trimmed"),
                        pokewalkerProgram = c.optString("overlay"),
                        otherPartsChecked = c.optInt("otherOverlaysChecked"),
                        otherPartsDifferent = c.optInt("otherOverlaysDifferent"),
                        detail = c.optString("detail"),
                    )
                },
            )
        }
    }
}

/**
 * Whether the loaded HeartGold/SoulSilver ROM matches the original release (checked by
 * SHA-1 against the pret/pokeheartgold decompilation). States are "original",
 * "different", "missing" or "unknown".
 */
data class CopyCheck(
    val release: String,
    val rom: String,
    val trimmed: Boolean,
    val pokewalkerProgram: String,
    val otherPartsChecked: Int,
    val otherPartsDifferent: Int,
    val detail: String,
) {
    val known: Boolean get() = release.isNotBlank()
    val pokewalkerProgramModified: Boolean get() = pokewalkerProgram == "different" || pokewalkerProgram == "missing"
    val modified: Boolean get() = pokewalkerProgramModified || rom == "different" || otherPartsDifferent > 0
}
