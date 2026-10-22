package me.magnum.melonds.pokewalker

/**
 * Bindings to the native IR front end and virtual Pokéwalker
 * (app/src/main/cpp/pokewalker).
 */
object PokewalkerNative {
    init {
        // Same library MelonDSApplication loads; loading twice is a no-op.
        System.loadLibrary("melonDS-android-frontend")
    }

    /** mode: 0 = off, 1 = virtual Pokéwalker, 2 = TCP. */
    external fun configure(mode: Int, eepromPath: String, logPath: String?, tcpPort: Int, logPackets: Boolean)

    external fun getStatusJson(): String

    /** Writes a line to the Pokéwalker log. */
    external fun note(text: String)

    /** Adds steps walked [daysAgo] days ago (0 = today). Returns null on success, otherwise an error message. */
    external fun addSteps(steps: Int, daysAgo: Int): String?

    /** Returns null on success, otherwise an error message. */
    external fun resetWalker(): String?

    /** Returns null on success, otherwise an error message. */
    external fun importImage(data: ByteArray): String?

    external fun exportImage(): ByteArray?

    /** Poké Radar (10 W): JSON {slot, species, speciesName, level, watts}, or {error}. */
    external fun radarEncounter(): String

    /** Keeps route Pokémon [slot] as caught. Returns null on success, otherwise an error message. */
    external fun storeCaught(slot: Int): String?

    /** Dowsing (3 W): JSON {item, itemName, watts}, or {error}. */
    external fun dowsingStart(): String

    /** Keeps a dowsed item. Returns null on success, otherwise an error message. */
    external fun storeDowsed(item: Int): String?
}
