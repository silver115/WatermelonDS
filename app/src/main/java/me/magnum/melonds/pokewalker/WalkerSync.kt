package me.magnum.melonds.pokewalker

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.magnum.melonds.R
import java.text.NumberFormat

/**
 * Pulls new steps from Health Connect into the walker. Used when the walker screen opens,
 * when HeartGold/SoulSilver starts, by the hourly background job and by the widget/tile.
 */
object WalkerSync {
    sealed class Result {
        data class Added(val steps: Long) : Result()
        object NothingNew : Result()
        /** Health Connect is missing or reading steps wasn't allowed (in the background). */
        object NotSetUp : Result()
        object NoStroll : Result()
        object Busy : Result()
        data class Failed(val message: String) : Result()
    }

    /** A short sentence for a toast. */
    fun describe(context: Context, result: Result): String = when (result) {
        is Result.Added -> context.getString(R.string.pokewalker_sync_added, NumberFormat.getIntegerInstance().format(result.steps))
        Result.NothingNew -> context.getString(R.string.pokewalker_sync_nothing_new)
        Result.NotSetUp -> context.getString(R.string.pokewalker_sync_not_set_up)
        Result.NoStroll -> context.getString(R.string.pokewalker_sync_no_stroll)
        Result.Busy -> context.getString(R.string.pokewalker_sync_busy)
        is Result.Failed -> context.getString(R.string.pokewalker_sync_failed, result.message)
    }

    /** [background]: no screen of the app is visible, which needs Health Connect's background-read permission. */
    suspend fun fromHealthConnect(context: Context, background: Boolean = false): Result = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@withContext Result.NotSetUp
        val app = context.applicationContext
        val result = try {
            val health = HealthConnectSteps(app)
            when {
                health.availability() != HealthConnectSteps.Availability.AVAILABLE -> Result.NotSetUp
                !health.hasPermission() -> Result.NotSetUp
                background && !health.hasBackgroundPermission() -> Result.NotSetUp
                else -> {
                    val status = Pokewalker.status()
                    when {
                        status.sessionActive -> Result.Busy
                        !status.hasPokemon || status.strollStartUnix <= 0 -> Result.NoStroll
                        else -> {
                            val now = System.currentTimeMillis()
                            val from = maxOf(status.strollStartUnix * 1000, now - 30 * LocalDays.DAY_MS)
                            when (val credit = StepLedger(app).credit(health.stepsByDay(from, now))) {
                                is CreditResult.Added -> if (credit.steps > 0) Result.Added(credit.steps) else Result.NothingNew
                                CreditResult.NoStroll -> Result.NoStroll
                                CreditResult.Busy -> Result.Busy
                                is CreditResult.Error -> Result.Failed(credit.message)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (result is Result.Added) {
            PokewalkerWidget.updateAll(app)
            PokewalkerTileService.refresh(app)
        }
        result
    }
}
