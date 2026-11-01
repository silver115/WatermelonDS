package me.magnum.melonds.pokewalker

import android.content.Context
import java.io.File

/**
 * Copies of the virtual Pokéwalker kept so a mistake can be undone: one is taken just before
 * anything that wipes or replaces it (a reset, an import, unpairing, a restore) and one a day
 * while it's used. Each is the walker's whole 64 KiB memory (the same as an export), restored
 * through the normal import.
 */
object WalkerBackups {
    const val BEFORE_RESET = "reset"
    const val BEFORE_IMPORT = "import"
    const val BEFORE_UNPAIR = "unpair"
    const val BEFORE_RESTORE = "restore"
    const val DAILY = "daily"

    private const val SIZE = 65_536
    private const val KEEP_DAILY = 3
    private const val KEEP_OTHERS = 8

    /** One copy: when, why, and who was on the walker then (0: nobody). */
    class Snapshot(val file: File, val time: Long, val reason: String, val species: Int)

    private fun dir(context: Context) = File(Pokewalker.eepromFile(context).parentFile, "snapshots")

    /** Takes a copy of the walker as it is now, for [reason]; false when there's no walker to copy. */
    @Synchronized
    fun take(context: Context, reason: String): Boolean {
        val image = runCatching { PokewalkerNative.exportImage() }.getOrNull() ?: return false
        if (image.size != SIZE || String(image, 0, 8, Charsets.ISO_8859_1) != "nintendo") return false
        val d = dir(context)
        if (!d.isDirectory && !d.mkdirs()) return false
        // the same walker as the newest copy: nothing new to keep
        list(context).firstOrNull()?.let { newest -> if (runCatching { newest.file.readBytes() }.getOrNull()?.contentEquals(image) == true && reason == DAILY) return true }
        val tmp = File(d, "snapshot.tmp")
        tmp.writeBytes(image)
        if (!tmp.renameTo(File(d, "${System.currentTimeMillis()}_$reason.bin"))) { tmp.delete(); return false }
        prune(context)
        return true
    }

    /** Today's copy, once a day. */
    fun daily(context: Context) {
        val today = LocalDays.today()
        if (list(context).any { it.reason == DAILY && LocalDays.dayOf(it.time) == today }) return
        take(context, DAILY)
    }

    /** The copies kept, newest first. */
    fun list(context: Context): List<Snapshot> {
        val files = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".bin") && f.length() == SIZE.toLong() } ?: return emptyList()
        return files.mapNotNull { f ->
            val time = f.name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
            val reason = f.name.substringAfter('_').removeSuffix(".bin")
            val species = runCatching { WalkParty.fromImage(f.readBytes()).walking?.species ?: 0 }.getOrDefault(0)
            Snapshot(f, time, reason, species)
        }.sortedByDescending { it.time }
    }

    /**
     * Puts [s] back on the walker (the walker as it is now is kept first, so this can be undone
     * too); null when it worked, else what went wrong.
     */
    fun restore(context: Context, s: Snapshot): String? {
        val image = runCatching { s.file.readBytes() }.getOrNull() ?: return "the copy can't be read"
        take(context, BEFORE_RESTORE)
        return PokewalkerNative.importImage(image)
    }

    private fun prune(context: Context) {
        val all = list(context)
        all.filter { it.reason == DAILY }.drop(KEEP_DAILY).forEach { it.file.delete() }
        all.filter { it.reason != DAILY }.drop(KEEP_OTHERS).forEach { it.file.delete() }
    }
}
