package com.pact.coach.services

import android.util.Log
import com.pact.coach.core.time.ClockProvider
import com.pact.coach.data.repository.BehaviorRepository
import com.pact.coach.data.repository.InstanceRepository
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.scheduler.InstanceGenerator
import com.pact.coach.domain.scheduler.SchedulingHorizon
import com.pact.coach.services.alarm.AlarmScheduler
import com.pact.coach.services.alarm.ScheduleOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * The single entry point for "make the schedule correct again".
 *
 * Everything that can invalidate the schedule calls [refresh]: app start, device boot, a clock or
 * time-zone change, a behavior edit, a decision on an intervention, and the periodic worker. It
 * is idempotent and safe to call as often as needed.
 *
 * A [Mutex] guards the whole pass. Boot, the worker and the UI can all fire at once, and running
 * two generations concurrently is the classic way to end up with duplicate alarms.
 */
class SchedulingCoordinator(
    private val behaviorRepository: BehaviorRepository,
    private val instanceRepository: InstanceRepository,
    private val alarmScheduler: AlarmScheduler,
    private val clock: ClockProvider,
    private val generator: InstanceGenerator = InstanceGenerator(),
) {

    private val mutex = Mutex()

    data class RefreshReport(
        val generated: Int,
        val alarmsScheduled: Int,
        val alarmsInexact: Int,
        val alarmsFailed: Int,
        val missedSwept: Int,
        val rebasedForTimeZone: Boolean,
    ) {
        val degraded: Boolean get() = alarmsInexact > 0 || alarmsFailed > 0
    }

    /**
     * @param zoneChanged when true, future instances are re-pinned to the current time zone,
     *   keeping their wall-clock times. Set by the time-zone broadcast receiver.
     */
    suspend fun refresh(zoneChanged: Boolean = false): RefreshReport = mutex.withLock {
        val zone = clock.zone()

        val rebased = if (zoneChanged) {
            runCatching { instanceRepository.rebaseFutureInstancesToZone(zone) }
                .onFailure { Log.e(TAG, "Time-zone rebase failed", it) }
                .getOrDefault(false)
        } else {
            false
        }

        val missed = runCatching { instanceRepository.sweepMissed() }
            .onFailure { Log.e(TAG, "Missed sweep failed", it) }
            .getOrDefault(emptyList())

        val generated = runCatching { generateHorizon(zone) }
            .onFailure { Log.e(TAG, "Instance generation failed", it) }
            .getOrDefault(0)

        val alarms = runCatching { rearmAlarms() }
            .onFailure { Log.e(TAG, "Alarm rearm failed", it) }
            .getOrDefault(AlarmReport(0, 0, 0))

        RefreshReport(
            generated = generated,
            alarmsScheduled = alarms.exact,
            alarmsInexact = alarms.inexact,
            alarmsFailed = alarms.failed,
            missedSwept = missed.size,
            rebasedForTimeZone = rebased,
        )
    }

    /**
     * Materialises occurrences for the next [SchedulingHorizon.GENERATION_DAYS] days.
     *
     * Bounded on purpose: a daily behavior running for years is a handful of rows at a time, not
     * thousands. Duplicate suppression is handled by the unique dedupKey index, so overlapping
     * calls are harmless.
     */
    private suspend fun generateHorizon(zone: ZoneId): Int {
        val today = clock.today()
        val to = today.plusDays(SchedulingHorizon.GENERATION_DAYS)

        val behaviors = behaviorRepository.getActive()
        if (behaviors.isEmpty()) return 0

        val exceptions = behaviorRepository.getExceptionsFrom(today)
        var inserted = 0

        for (behavior in behaviors) {
            val schedules = behaviorRepository.getSchedules(behavior.id)
            if (schedules.isEmpty()) continue

            val generated = generator.generate(
                behavior = behavior,
                schedules = schedules,
                exceptions = exceptions,
                from = today,
                to = to,
                zone = zone,
            )
            if (generated.isNotEmpty()) {
                inserted += instanceRepository.insertGenerated(generated)
            }
        }
        return inserted
    }

    private data class AlarmReport(val exact: Int, val inexact: Int, val failed: Int)

    /**
     * Registers OS alarms for everything inside the alarm window.
     *
     * Re-arming an already-armed instance is a no-op at the platform level because the
     * PendingIntent identity is stable, which is what keeps this safe to run repeatedly.
     */
    private suspend fun rearmAlarms(): AlarmReport {
        val due = instanceRepository.getSchedulable()
        if (due.isEmpty()) return AlarmReport(0, 0, 0)

        val behaviors = behaviorRepository.getActive().associateBy { it.id }

        var exact = 0
        var inexact = 0
        var failed = 0

        for (instance in due) {
            val behavior = behaviors[instance.behaviorId] ?: continue
            when (alarmScheduler.schedule(instance, behavior)) {
                is ScheduleOutcome.Exact -> exact++
                is ScheduleOutcome.Inexact -> inexact++
                is ScheduleOutcome.Failed -> failed++
                is ScheduleOutcome.Skipped -> Unit
            }
        }
        return AlarmReport(exact, inexact, failed)
    }

    /** Arms a single instance immediately, e.g. right after a recovery is created. */
    suspend fun armInstance(instance: BehaviorInstance): ScheduleOutcome {
        val behavior = behaviorRepository.get(instance.behaviorId)
            ?: return ScheduleOutcome.Skipped("Behavior no longer exists")
        return alarmScheduler.schedule(instance, behavior)
    }

    fun cancelInstance(instance: BehaviorInstance) = alarmScheduler.cancel(instance)

    /** Cancels every armed alarm for a behavior. Used when it is paused or deleted. */
    suspend fun cancelForBehavior(behaviorId: String) {
        val instances = instanceRepository.getForBehavior(behaviorId)
        alarmScheduler.cancelAll(instances)
    }

    suspend fun scheduleEscalationFor(instance: BehaviorInstance, behavior: Behavior) {
        alarmScheduler.scheduleEscalation(instance, behavior)
    }

    companion object {
        private const val TAG = "SchedulingCoordinator"
    }
}
