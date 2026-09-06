package com.pact.coach.services.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pact.coach.PactApplication
import com.pact.coach.launchGuarded
import com.pact.coach.services.work.MaintenanceWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared rebuild logic for every system event that invalidates the schedule.
 *
 * Alarms registered with AlarmManager do not survive a reboot, so they have to be recreated from
 * the database whenever the device starts or the app is updated. Absolute firing instants also
 * have to be recomputed whenever the clock or the time zone moves.
 *
 * [com.pact.coach.services.SchedulingCoordinator.refresh] is idempotent and mutex-guarded, so
 * receiving several of these in quick succession cannot produce duplicate alarms.
 */
internal fun BroadcastReceiver.rebuildSchedule(
    context: Context,
    tag: String,
    action: String,
    zoneChanged: Boolean,
) {
    val app = context.applicationContext as? PactApplication ?: return
    Log.i(tag, "Rebuilding schedule after $action")

    val pending = goAsync()
    app.applicationScope.launchGuarded(
        onError = { Log.e(tag, "Reschedule after $action failed", it) },
        onFinally = { pending.finish() },
    ) {
        withContext(Dispatchers.IO) {
            val report = app.container.schedulingCoordinator.refresh(zoneChanged = zoneChanged)
            Log.i(
                tag,
                "Rescheduled: generated=${report.generated} armed=${report.alarmsScheduled} " +
                    "inexact=${report.alarmsInexact} missed=${report.missedSwept} " +
                    "rebased=${report.rebasedForTimeZone}",
            )
            // The periodic safety net is also cleared by a reboot.
            MaintenanceWorker.enqueuePeriodic(context)
        }
    }
}

/**
 * Boot and app-update recovery.
 *
 * Declared with the RECEIVE_BOOT_COMPLETED permission filter in the manifest so only the system
 * can trigger it.
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return
        rebuildSchedule(context, TAG, action, zoneChanged = false)
    }

    companion object {
        private const val TAG = "SystemEventReceiver"
        private val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}

/**
 * Clock and time-zone changes.
 *
 * Kept separate from [SystemEventReceiver] because these broadcasts are not protected by
 * RECEIVE_BOOT_COMPLETED; giving this receiver that permission filter would stop it ever firing.
 *
 * Both cases set `zoneChanged`, which re-pins future occurrences so the user's chosen wall-clock
 * time is preserved: "exercise at 06:30" stays 06:30 after flying to another country.
 */
class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_TIME_CHANGED && action != Intent.ACTION_TIMEZONE_CHANGED) return
        rebuildSchedule(context, TAG, action, zoneChanged = true)
    }

    companion object {
        private const val TAG = "TimeChangeReceiver"
    }
}
