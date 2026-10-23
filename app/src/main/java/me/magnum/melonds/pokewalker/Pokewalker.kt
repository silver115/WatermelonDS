package me.magnum.melonds.pokewalker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import me.magnum.melonds.R
import androidx.preference.PreferenceManager
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Settings and storage for the infrared Pokéwalker support.
 */
object Pokewalker {
    const val KEY_IR_MODE = "pokewalker_ir_mode"
    const val KEY_TCP_PORT = "pokewalker_tcp_port"
    const val KEY_LOG_PACKETS = "pokewalker_log_packets"
    const val KEY_AUTO_SYNC = "pokewalker_auto_sync"
    const val KEY_STEP_GOAL = "pokewalker_step_goal"
    const val KEY_SKIN = "pokewalker_device_skin"
    private const val KEY_LAST_GAME_URI = "pokewalker_last_game_uri"
    private const val KEY_LAST_GAME_NAME = "pokewalker_last_game_name"

    const val SKIN_MODERN = "modern"
    const val SKIN_CLASSIC = "classic"

    const val MODE_OFF = "off"
    const val MODE_VIRTUAL = "virtual"
    const val MODE_TCP = "tcp"

    private const val TAG = "Pokewalker"
    private const val MAX_LOG_BYTES = 4L * 1024 * 1024

    @Volatile
    private var logChecked = false

    /** For quick background jobs that should outlive the screen that started them. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun eepromFile(context: Context): File = File(File(context.filesDir, "pokewalker"), "eeprom.bin")

    fun logFile(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(base, "pokewalker"), "pokewalker-log.txt")
    }

    /**
     * Pushes the current preferences to the native side. Safe to call often.
     * [startingGame] also logs the emulator settings the game starts with, which
     * helps when a user sends an exported log.
     */
    fun applySettings(context: Context, startingGame: Boolean = false) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val mode = when (prefs.getString(KEY_IR_MODE, MODE_VIRTUAL)) {
                MODE_OFF -> 0
                MODE_TCP -> 2
                else -> 1
            }
            val port = prefs.getString(KEY_TCP_PORT, "8081")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8081
            val logPackets = prefs.getBoolean(KEY_LOG_PACKETS, false)

            val eeprom = eepromFile(context)
            eeprom.parentFile?.mkdirs()
            val log = logFile(context)
            log.parentFile?.mkdirs()
            if (!logChecked) {
                // Only before the native side opens the file: start a fresh log if it got big.
                logChecked = true
                if (log.length() > MAX_LOG_BYTES) log.delete()
            }
            PokewalkerNative.configure(mode, eeprom.absolutePath, log.absolutePath, port, logPackets)
            if (startingGame) {
                val jit = prefs.getBoolean("enable_jit", Build.SUPPORTED_64_BIT_ABIS.isNotEmpty())
                val system = prefs.getString("console_type", "ds")
                val renderer = prefs.getString("video_renderer", "software")
                val customBios = prefs.getBoolean("use_custom_bios", false)
                PokewalkerNative.note(
                    "Starting a game: app ${context.packageName}, JIT ${if (jit) "on" else "off"}, system $system, " +
                        "renderer $renderer, ${if (customBios) "custom BIOS" else "built-in BIOS"}, Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}"
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not configure the Pokéwalker", e)
        }
    }

    fun status(): WalkerStatus = WalkerStatus.parse(PokewalkerNative.getStatusJson())

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    fun autoSyncEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_SYNC, true)

    fun stepGoal(context: Context): Int =
        prefs(context).getString(KEY_STEP_GOAL, "10000")?.trim()?.toIntOrNull()?.takeIf { it in 100..99999 } ?: 10000

    fun skin(context: Context): String = prefs(context).getString(KEY_SKIN, SKIN_MODERN) ?: SKIN_MODERN

    fun setSkin(context: Context, skin: String) {
        prefs(context).edit().putString(KEY_SKIN, skin).apply()
    }

    /** App start: background step sync, launcher shortcuts. */
    fun onAppStarted(context: Context) {
        val app = context.applicationContext
        try {
            PokewalkerSyncWorker.schedule(app)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not schedule the step sync", e)
        }
        PokewalkerShortcuts.publish(app)
    }

    /** Name of the HeartGold/SoulSilver ROM last started, if any. */
    fun lastGameName(context: Context): String? = prefs(context).getString(KEY_LAST_GAME_NAME, null)

    /** Starts the HeartGold/SoulSilver ROM last played in this app, or null if there is none yet. */
    fun openGameIntent(context: Context): Intent? {
        val uri = prefs(context).getString(KEY_LAST_GAME_URI, null) ?: return null
        return Intent("${context.packageName}.LAUNCH_ROM")
            .setPackage(context.packageName)
            .putExtra("uri", uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Called once a game is running. For HeartGold/SoulSilver: remembers the ROM for the
     * walker's "open game" button, and pulls new steps from Health Connect so the walker
     * is up to date before the game connects to it.
     */
    fun onGameStarted(context: Context, romUri: String, romName: String) {
        val app = context.applicationContext
        try {
            if (!status().gameIsPokewalkerCompatible) return
        } catch (e: Throwable) {
            return
        }
        prefs(app).edit().putString(KEY_LAST_GAME_URI, romUri).putString(KEY_LAST_GAME_NAME, romName).apply()
        if (!autoSyncEnabled(app)) return
        scope.launch {
            val result = WalkerSync.fromHealthConnect(app)
            if (result is WalkerSync.Result.Added) {
                PokewalkerNative.note("Synced ${result.steps} steps from Health Connect as the game started")
            }
        }
    }

    /** One sentence describing [check], or null when there is nothing to compare against. */
    fun copyCheckText(context: Context, check: CopyCheck?): String? {
        if (check == null || !check.known) return null
        return when {
            check.pokewalkerProgramModified -> context.getString(R.string.pokewalker_copy_program_modified, check.release)
            check.otherPartsDifferent > 0 -> context.getString(
                R.string.pokewalker_copy_parts_modified,
                check.release,
                check.otherPartsDifferent,
                check.otherPartsChecked + 1,
            )
            check.rom == "different" -> context.getString(R.string.pokewalker_copy_rom_modified, check.release)
            check.rom == "original" && check.trimmed -> context.getString(R.string.pokewalker_copy_original_trimmed, check.release)
            check.rom == "original" -> context.getString(R.string.pokewalker_copy_original, check.release)
            else -> null
        }
    }

    /**
     * Called once a game has started: when HeartGold/SoulSilver turns out to be a modified
     * copy, says so right away, because the game's own Pokéwalker screen may then fail.
     */
    fun announceCopyCheck(context: Context) {
        try {
            val check = status().copyCheck ?: return
            if (!check.modified) return
            val text = copyCheckText(context, check) ?: return
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not report the game check", e)
        }
    }
}

/**
 * Local calendar days as "epoch day" numbers, without java.time (minSdk 24).
 * Matches the day numbering the native walker uses for its 7-day history.
 */
object LocalDays {
    const val DAY_MS = 86_400_000L

    fun dayOf(millis: Long, tz: TimeZone = TimeZone.getDefault()): Long = Math.floorDiv(millis + tz.getOffset(millis), DAY_MS)

    fun today(): Long = dayOf(System.currentTimeMillis())

    /** First millisecond of the local day. */
    fun startOfDay(day: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val utcMidnight = day * DAY_MS
        var start = utcMidnight - tz.getOffset(utcMidnight)
        start = utcMidnight - tz.getOffset(start) // settle daylight-saving changes
        return start
    }

    /** Proleptic Gregorian date to epoch day. */
    fun fromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    fun format(day: Long): String {
        val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(day * DAY_MS))
    }
}
