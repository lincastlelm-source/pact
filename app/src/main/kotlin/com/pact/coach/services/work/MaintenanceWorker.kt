package com.pact.coach.services.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pact.coach.PactApplication
import java.util.concurrent.TimeUnit

/**
 * The safety net.
 *
 * Exact alarms do the real work of firing interventions on time. This worker exists for the
 * cases where they cannot be trusted:
 *
 *  - the user has not granted (or has revoked) the exact-alarm permission
 *  - the OEM has aggressive battery management that drops alarms
 *  - the app was force-stopped and later restarted by the system
 *  - the horizon needs topping up because the user has not opened the app in days
 *
 * It never *replaces* alarms: precise, user-chosen times are not something WorkManager can
 * deliver. It tops up the generation horizon, re-arms alarms, sweeps stale interventions into
 * MISSED, and prunes old audit rows.
 */
class MaintenanceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? PactApplication ?: return Result.success()

        return try {
            val report = app.container.schedulingCoordinator.refresh()
            Log.i(
                TAG,
                "Maintenance: generated=${report.generated} armed=${report.alarmsScheduled} " +
                    "inexact=${report.alarmsInexact} missed=${report.missedSwept}",
            )
            app.container.pruneOldRecords()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Maintenance failed", e)
            // Retrying is safe: every step is idempotent.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "MaintenanceWorker"
        private const val UNIQUE_NAME = "pact_maintenance"
        private const val MAX_ATTEMPTS = 3

        /**
         * Every two hours. Short enough to keep a 14-day horizon healthy and to catch missed
         * interventions promptly; long enough that it costs nothing noticeable in battery.
         */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(2, TimeUnit.HOURS)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    // KEEP, so repeated calls (boot, app start) do not reset the schedule.
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onFailure { Log.e(TAG, "Could not enqueue maintenance work", it) }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME) }
        }
    }
}
