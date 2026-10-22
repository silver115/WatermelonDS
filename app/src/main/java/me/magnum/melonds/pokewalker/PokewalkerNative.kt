package me.magnum.melonds.pokewalker

import me.magnum.melonds.NativeCoreLoader

/**
 * Bindings to the native IR front end and virtual Pokéwalker
 * (app/src/main/cpp/pokewalker).
 */
object PokewalkerNative {
    init {
        NativeCoreLoader.load()
    }

    /** mode: 0 = off, 1 = virtual Pokéwalker, 2 = TCP. */
    external fun configure(mode: Int, eepromPath: String, logPath: String?, tcpPort: Int, logPackets: Boolean)

    external fun getStatusJson(): String

    /** Adds steps walked [daysAgo] days ago (0 = today). Returns null on success, otherwise an error message. */
    external fun addSteps(steps: Int, daysAgo: Int): String?

    /** Returns null on success, otherwise an error message. */
    external fun resetWalker(): String?

    /** Returns null on success, otherwise an error message. */
    external fun importImage(data: ByteArray): String?

    external fun exportImage(): ByteArray?
}
