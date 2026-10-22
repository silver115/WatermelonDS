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
            )
        }
    }
}
