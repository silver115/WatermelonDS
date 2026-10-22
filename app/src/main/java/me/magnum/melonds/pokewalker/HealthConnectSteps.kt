package me.magnum.melonds.pokewalker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.RequiresApi
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/**
 * Reads step counts from Health Connect. Samsung Health, Google Fit, Fitbit and
 * most fitness apps can share their steps there.
 */
@RequiresApi(Build.VERSION_CODES.P)
class HealthConnectSteps(private val context: Context) {

    enum class Availability { AVAILABLE, NEEDS_INSTALL, UNSUPPORTED }

    fun availability(): Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NEEDS_INSTALL
        else -> Availability.UNSUPPORTED
    }

    suspend fun hasPermission(): Boolean {
        val granted = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    /** Steps per local epoch day between [fromMillis] and [toMillis]. */
    suspend fun stepsByDay(fromMillis: Long, toMillis: Long = System.currentTimeMillis()): Map<Long, Long> {
        val client = HealthConnectClient.getOrCreate(context)
        val out = LinkedHashMap<Long, Long>()
        var start = fromMillis
        while (start < toMillis) {
            val day = LocalDays.dayOf(start)
            val end = minOf(LocalDays.startOfDay(day + 1), toMillis)
            if (end <= start) break
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end)),
                )
            )
            out[day] = result[StepsRecord.COUNT_TOTAL] ?: 0L
            start = end
        }
        return out
    }

    companion object {
        const val READ_STEPS = "android.permission.health.READ_STEPS"
        val PERMISSIONS = setOf(READ_STEPS)
        private const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
            PermissionController.createRequestPermissionResultContract()

        fun installIntent(): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding"))
                .setPackage("com.android.vending")
    }
}
