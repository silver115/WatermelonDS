package me.magnum.melonds.pokewalker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * About once an hour: new Health Connect steps into the walker (when Health Connect lets
 * the app read in the background), then fresh widgets and Quick Settings tile.
 */
class PokewalkerSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (Pokewalker.autoSyncEnabled(app)) {
            val result = WalkerSync.fromHealthConnect(app, background = true)
            if (result is WalkerSync.Result.Added) PokewalkerNative.note("Background sync added ${result.steps} steps from Health Connect")
        }
        PokewalkerWidget.updateAll(app)
        PokewalkerTileService.refresh(app)
        return Result.success()
    }

    companion object {
        private const val NAME = "pokewalker_hourly_sync"

        /** Keeps the hourly job scheduled (it also refreshes widgets, so it always runs). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PokewalkerSyncWorker>(1, TimeUnit.HOURS, 20, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
