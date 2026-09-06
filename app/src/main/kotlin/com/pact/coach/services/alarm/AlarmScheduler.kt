package com.pact.coach.services.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.EnforcementLevel

/**
 * Registers and cancels the OS alarms that drive interventions.
 *
 * Android has tightened exact alarms considerably, so this class does three things carefully:
 *
 *  1. It asks [ExactAlarmCapability] whether exact alarms are actually permitted right now, and
 *     silently degrades to an inexact window when they are not. The app keeps working; the
 *     reminder is just less precise, and the Help screen explains why.
 *  2. It never throws at the call site. [AlarmManager] can raise SecurityException when a
 *     permission is revoked between the check and the call, and can refuse when an app has
 *     scheduled too many alarms. A failed alarm must not take the app down.
 *  3. Every alarm has a stable identity, so re-running the scheduler cannot produce duplicates.
 *     Identity is the pair (requestCode, intent data URI); both are derived from the instance,
 *     so re-scheduling the same instance replaces its alarm rather than adding a second one.
 */
class AlarmScheduler(
    private val context: Context,
    private val capability: ExactAlarmCapability = ExactAlarmCapability(context),
) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** The three alarm kinds an instance can own. Each gets its own PendingIntent identity. */
    enum class Kind(val offset: Int) {
        PRE_ALERT(1_000_000),
        MAIN(0),
        ESCALATION(2_000_000),
    }

    /**
     * Arms the main alarm (and the optional pre-alert) for [instance].
     *
     * @return what actually got scheduled, so the caller can tell the user if precision was lost.
     */
    fun schedule(instance: BehaviorInstance, behavior: Behavior): ScheduleOutcome {
        val triggerAt = instance.scheduledAtUtcMillis
            ?: return ScheduleOutcome.Skipped("Behavior has no scheduled time")

        if (!behavior.isActive) return ScheduleOutcome.Skipped("Behavior is paused")
        if (!behavior.reminderEnabled) return ScheduleOutcome.Skipped("Reminders are off")
        if (alarmManager == null) return ScheduleOutcome.Failed("Alarm service unavailable")

        // An alarm in the past would fire immediately on the next reboot sweep.
        if (triggerAt <= System.currentTimeMillis()) {
            return ScheduleOutcome.Skipped("Scheduled time has already passed")
        }

        behavior.preAlertMinutes?.takeIf { it > 0 }?.let { minutes ->
            val preAt = triggerAt - minutes * 60_000L
            if (preAt > System.currentTimeMillis()) {
                setAlarm(instance, behavior, Kind.PRE_ALERT, preAt)
            }
        }

        return setAlarm(instance, behavior, Kind.MAIN, triggerAt)
    }

    /**
     * A follow-up nudge for COACH and STRONG behaviors that were never answered. Deliberately
     * capped to a single escalation: a reminder app that will not stop is one the user uninstalls.
     */
    fun scheduleEscalation(instance: BehaviorInstance, behavior: Behavior): ScheduleOutcome {
        if (behavior.enforcement == EnforcementLevel.GENTLE) {
            return ScheduleOutcome.Skipped("Gentle behaviors do not escalate")
        }
        val minutes = behavior.escalationMinutes ?: DEFAULT_ESCALATION_MINUTES
        if (minutes <= 0) return ScheduleOutcome.Skipped("Escalation is off")

        val at = System.currentTimeMillis() + minutes * 60_000L
        return setAlarm(instance, behavior, Kind.ESCALATION, at)
    }

    fun cancel(instance: BehaviorInstance) {
        Kind.entries.forEach { kind ->
            pendingIntent(instance, kind, mutable = false, createIfAbsent = false)?.let { pi ->
                alarmManager?.cancel(pi)
                pi.cancel()
            }
        }
    }

    fun cancelAll(instances: List<BehaviorInstance>) = instances.forEach { cancel(it) }

    // --- Internals ----------------------------------------------------------------------

    private fun setAlarm(
        instance: BehaviorInstance,
        behavior: Behavior,
        kind: Kind,
        triggerAtMillis: Long,
    ): ScheduleOutcome {
        val manager = alarmManager ?: return ScheduleOutcome.Failed("Alarm service unavailable")
        val pending = pendingIntent(instance, kind, mutable = false, createIfAbsent = true)
            ?: return ScheduleOutcome.Failed("Could not create alarm intent")

        return try {
            if (capability.canScheduleExact()) {
                if (behavior.enforcement == EnforcementLevel.STRONG) {
                    // setAlarmClock is the strongest guarantee the platform offers: it survives
                    // Doze and is surfaced to the user as a real alarm, which is honest about
                    // what a STRONG behavior actually is.
                    val show = PendingIntent.getActivity(
                        context,
                        instance.alarmRequestCode + SHOW_INTENT_OFFSET,
                        Intent(context, com.pact.coach.MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    manager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerAtMillis, show),
                        pending,
                    )
                } else {
                    manager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pending,
                    )
                }
                ScheduleOutcome.Exact(triggerAtMillis)
            } else {
                // Without the exact-alarm permission the platform will not honour a precise time.
                // A window is better than nothing, and the Help screen tells the user how to
                // restore precision.
                manager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    INEXACT_WINDOW_MILLIS,
                    pending,
                )
                ScheduleOutcome.Inexact(triggerAtMillis)
            }
        } catch (e: SecurityException) {
            // The permission can be revoked between the check above and this call.
            Log.w(TAG, "Exact alarm refused, falling back to inexact", e)
            try {
                manager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    INEXACT_WINDOW_MILLIS,
                    pending,
                )
                ScheduleOutcome.Inexact(triggerAtMillis)
            } catch (inner: Exception) {
                Log.e(TAG, "Could not schedule alarm at all", inner)
                ScheduleOutcome.Failed(inner.message ?: "Alarm scheduling failed")
            }
        } catch (e: IllegalStateException) {
            // Raised when an app has registered too many alarms.
            Log.e(TAG, "Alarm limit reached", e)
            ScheduleOutcome.Failed("Too many alarms are scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected alarm failure", e)
            ScheduleOutcome.Failed(e.message ?: "Alarm scheduling failed")
        }
    }

    /**
     * PendingIntent identity is (requestCode, Intent.filterEquals). The data URI carries both the
     * kind and the instance id, so the three kinds never collide even if two instances were
     * somehow given the same request code.
     */
    private fun pendingIntent(
        instance: BehaviorInstance,
        kind: Kind,
        mutable: Boolean,
        createIfAbsent: Boolean,
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            data = Uri.parse("pact://alarm/${kind.name}/${instance.id}")
            putExtra(AlarmReceiver.EXTRA_INSTANCE_ID, instance.id)
            putExtra(AlarmReceiver.EXTRA_KIND, kind.name)
        }

        var flags = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        flags = flags or if (createIfAbsent) {
            PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_NO_CREATE
        }

        return PendingIntent.getBroadcast(
            context,
            instance.alarmRequestCode + kind.offset,
            intent,
            flags,
        )
    }

    companion object {
        private const val TAG = "AlarmScheduler"
        const val DEFAULT_ESCALATION_MINUTES = 10
        private const val INEXACT_WINDOW_MILLIS = 10 * 60 * 1000L
        private const val SHOW_INTENT_OFFSET = 3_000_000
    }
}

sealed interface ScheduleOutcome {
    data class Exact(val triggerAtMillis: Long) : ScheduleOutcome
    data class Inexact(val triggerAtMillis: Long) : ScheduleOutcome
    data class Skipped(val reason: String) : ScheduleOutcome
    data class Failed(val reason: String) : ScheduleOutcome

    val wasScheduled: Boolean get() = this is Exact || this is Inexact
}

/**
 * Whether the platform will currently honour an exact alarm.
 *
 * PACT declares SCHEDULE_EXACT_ALARM, which the user can grant or revoke in system settings. It
 * deliberately does **not** declare USE_EXACT_ALARM: that permission is reserved for apps whose
 * primary purpose is alarms and timers, and using it here would be a policy violation.
 */
class ExactAlarmCapability(private val context: Context) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun canScheduleExact(): Boolean = when {
        alarmManager == null -> false
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> alarmManager.canScheduleExactAlarms()
        else -> true
    }

    /** True when the user could grant the permission but has not. */
    fun needsUserAction(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExact()

    /** The system screen where exact alarms are granted, or null when not applicable. */
    fun settingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        } else {
            null
        }

    fun batteryOptimisationIntent(): Intent =
        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
