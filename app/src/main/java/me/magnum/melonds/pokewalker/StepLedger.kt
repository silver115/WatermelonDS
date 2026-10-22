package me.magnum.melonds.pokewalker

import android.content.Context
import org.json.JSONObject

sealed class CreditResult {
    data class Added(val steps: Long, val wattsNow: Int) : CreditResult()
    object NoStroll : CreditResult()
    object Busy : CreditResult()
    data class Error(val message: String) : CreditResult()
}

/**
 * Remembers how many phone steps were already credited for each day of the
 * current stroll. Syncing again, syncing late-arriving data, or mixing
 * Health Connect with file imports therefore never counts a step twice:
 * for every day the walker gets max(phone steps) once.
 */
class StepLedger(context: Context) {
    private val prefs = context.getSharedPreferences("pokewalker_step_ledger", Context.MODE_PRIVATE)

    /**
     * @param dailySteps phone steps per local epoch day (see [LocalDays]).
     */
    fun credit(dailySteps: Map<Long, Long>): CreditResult {
        val status = Pokewalker.status()
        if (status.sessionActive) return CreditResult.Busy
        if (!status.hasPokemon || status.strollStartUnix <= 0) return CreditResult.NoStroll

        val stroll = status.strollStartUnix
        val strollDay = LocalDays.dayOf(stroll * 1000)
        val today = LocalDays.today()
        val credited = load(stroll)
        var added = 0L

        for ((day, steps) in dailySteps.toSortedMap()) {
            if (day < strollDay || day > today || steps <= 0) continue
            val delta = steps - (credited[day] ?: 0L)
            if (delta <= 0) continue
            val error = PokewalkerNative.addSteps(delta.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), (today - day).toInt())
            if (error != null) {
                save(stroll, credited)
                return CreditResult.Error(error)
            }
            credited[day] = steps
            added += delta
        }
        save(stroll, credited)
        return CreditResult.Added(added, Pokewalker.status().watts)
    }

    private fun load(stroll: Long): MutableMap<Long, Long> {
        val raw = prefs.getString(KEY, null) ?: return mutableMapOf()
        return try {
            val o = JSONObject(raw)
            if (o.optLong("stroll") != stroll) return mutableMapOf()
            val days = o.optJSONObject("days") ?: return mutableMapOf()
            val out = mutableMapOf<Long, Long>()
            days.keys().forEach { key -> key.toLongOrNull()?.let { out[it] = days.optLong(key) } }
            out
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun save(stroll: Long, credited: Map<Long, Long>) {
        val oldest = LocalDays.today() - 45
        val days = JSONObject()
        credited.filterKeys { it >= oldest }.forEach { (day, steps) -> days.put(day.toString(), steps) }
        prefs.edit().putString(KEY, JSONObject().put("stroll", stroll).put("days", days).toString()).apply()
    }

    private companion object {
        const val KEY = "ledger"
    }
}
