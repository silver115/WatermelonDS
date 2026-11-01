package me.magnum.melonds.pokewalker

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What went wrong, kept so it can be read and sent: a crash (the app closed) is written down
 * before Android ends the app and shown the next time the walker screen opens; something that
 * went wrong but was caught (the route map, the route music) is written down too, and the part
 * that broke is switched off instead of taking the app with it.
 */
object CrashLog {
    private const val TAG = "PokewalkerCrash"
    private const val FILE = "pokewalker_last_problem.txt"

    @Volatile private var app: Context? = null
    @Volatile private var installed = false

    /** Starts keeping crashes (once; the app's own handler still runs after). */
    fun install(context: Context) {
        app = context.applicationContext
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write("The app closed: a crash in the \"${thread.name}\" thread", e) }
            if (previous != null) previous.uncaughtException(thread, e) else throw e
        }
    }

    /** Something that went wrong but was caught: [what] was switched off. */
    fun record(what: String, e: Throwable) {
        Log.e(TAG, what, e)
        runCatching { PokewalkerNative.note("$what: ${e.javaClass.simpleName}: ${e.message}") }
        runCatching { write(what, e) }
    }

    /** The last problem written down (and forgets it), or null. */
    fun take(context: Context): String? {
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text?.takeIf { it.isNotBlank() }
    }

    private fun write(what: String, e: Throwable) {
        val context = app ?: return
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val text = buildString {
            append(what).append('\n')
            append("When: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            append("App: ").append(version).append('\n')
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append("), Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("AYN Thor: ").append(Handheld.isAynThor).append("\n\n")
            append(trace.take(12_000))
        }
        File(context.filesDir, FILE).writeText(text)
    }
}
