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

    /** The walker's own screen: back to its home screen (when the screen opens). */
    external fun deviceReset()

    /** The walker's own screen: 0 = left, 1 = middle, 2 = right button. */
    external fun devicePress(button: Int)

    /**
     * The walker's own screen: advances it and copies its 96x64 shades (0-3, row by row) into
     * [frame] when the picture changed. Returns flags: bit 0 changed, bits 4-7 sound,
     * bits 8-9 volume (0-2), bits 12-15 shade (0-9).
     */
    external fun deviceFrame(frame: ByteArray): Int

    /** The walker's own screen: jumps into one of its apps (0 Poké Radar, 1 Dowsing, 3 Trainer Card...). */
    external fun deviceOpenApp(index: Int)

    /** The walker's own screen: a tap at walker pixel ([x] 0-95, [y] 0-63). False if it did nothing. */
    external fun deviceTouch(x: Int, y: Int): Boolean

    /** The walker's home screen for widgets (96x64 shades into [frame]). False if the walker isn't open. */
    external fun widgetFrame(frame: ByteArray): Boolean
}
