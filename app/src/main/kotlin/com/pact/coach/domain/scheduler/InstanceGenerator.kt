package com.pact.coach.domain.scheduler

import com.pact.coach.core.util.Ids
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorException
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.ExceptionType
import com.pact.coach.domain.model.RecurrenceType
import com.pact.coach.domain.model.Schedule
import com.pact.coach.domain.recurrence.RecurrenceEngine
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Turns recurrence rules into concrete [BehaviorInstance] rows over a *bounded* horizon.
 *
 * Two rules keep the database from exploding and from drifting:
 *
 *  1. We never materialise past the horizon ([SchedulingHorizon.GENERATION_DAYS]). A behavior
 *     that repeats daily for five years is 14 rows at a time, not 1825.
 *  2. Every generated row carries a deterministic [BehaviorInstance.dedupKey]. Regenerating the
 *     same window produces the same keys, so the repository can insert-or-ignore and the user
 *     never ends up with two alarms for one morning.
 */
object SchedulingHorizon {
    /** How far ahead instance rows are materialised. */
    const val GENERATION_DAYS = 14L

    /** How far ahead actual OS alarms are registered. Kept short so reboots stay cheap. */
    const val ALARM_WINDOW_HOURS = 48L

    /** How far back the "mark as missed" sweep looks. */
    const val MISS_SWEEP_DAYS = 7L
}

class InstanceGenerator {

    /**
     * @param exceptions all exceptions that could apply, both behavior-specific and global.
     * @return instances covering [from]..[to] inclusive, in chronological order.
     */
    fun generate(
        behavior: Behavior,
        schedules: List<Schedule>,
        exceptions: List<BehaviorException>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
    ): List<BehaviorInstance> {
        if (!behavior.isActive) return emptyList()
        if (to.isBefore(from)) return emptyList()

        val relevant = exceptions.filter { it.behaviorId == null || it.behaviorId == behavior.id }
        val out = mutableListOf<BehaviorInstance>()

        for (schedule in schedules) {
            if (!schedule.isEnabled) continue
            out += when (schedule.recurrenceType) {
                RecurrenceType.TIMES_PER_WEEK ->
                    generateFlexibleWeekly(behavior, schedule, relevant, from, to, zone)
                else ->
                    generateFixed(behavior, schedule, relevant, from, to, zone)
            }
        }

        return out.sortedWith(
            compareBy({ it.scheduledDate }, { it.scheduledTime ?: LocalTime.MAX }, { it.occurrenceIndex }),
        )
    }

    private fun generateFixed(
        behavior: Behavior,
        schedule: Schedule,
        exceptions: List<BehaviorException>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
    ): List<BehaviorInstance> {
        val days = RecurrenceEngine.occurrencesBetween(schedule, from, to)
        val out = mutableListOf<BehaviorInstance>()

        for (date in days) {
            if (isSkipped(date, exceptions)) continue

            if (behavior.isTimeBased && schedule.timesOfDay.isNotEmpty()) {
                // Sort defensively: occurrenceIndex must mean "the Nth slot of the day", and the
                // daily-instruction resolver keys off that index.
                schedule.timesOfDay.sorted().forEachIndexed { index, time ->
                    out += build(behavior, schedule, date, time, index, zone)
                }
            } else {
                repeat(schedule.timesPerDay.coerceAtLeast(1)) { index ->
                    out += build(behavior, schedule, date, null, index, zone)
                }
            }
        }
        return out
    }

    /**
     * "3 times per week" produces three untimed slots, spread across the week so the user is not
     * asked to do everything on Monday. Weeks are anchored to the schedule start date so the
     * spread is stable across regenerations.
     */
    private fun generateFlexibleWeekly(
        behavior: Behavior,
        schedule: Schedule,
        exceptions: List<BehaviorException>,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
    ): List<BehaviorInstance> {
        val perWeek = (schedule.timesPerWeek ?: 1).coerceIn(1, 7)
        val out = mutableListOf<BehaviorInstance>()

        var weekStart = RecurrenceEngine.weekStart(maxOf(from, schedule.startDate))
        var guard = 0
        while (!weekStart.isAfter(to) && guard++ < 120) {
            val days = RecurrenceEngine.spreadAcrossWeek(perWeek, weekStart)
            days.forEachIndexed { index, date ->
                val inRange = !date.isBefore(from) && !date.isAfter(to) &&
                    !date.isBefore(schedule.startDate) &&
                    (schedule.endDate == null || !date.isAfter(schedule.endDate))
                if (inRange && !isSkipped(date, exceptions)) {
                    out += build(behavior, schedule, date, null, index, zone)
                }
            }
            weekStart = weekStart.plusWeeks(1)
        }
        return out
    }

    private fun isSkipped(date: LocalDate, exceptions: List<BehaviorException>): Boolean =
        exceptions.any { ex ->
            ex.covers(date) && ex.type in SKIPPING_TYPES
        }

    private fun build(
        behavior: Behavior,
        schedule: Schedule,
        date: LocalDate,
        time: LocalTime?,
        occurrenceIndex: Int,
        zone: ZoneId,
    ): BehaviorInstance {
        val id = Ids.new()
        return BehaviorInstance(
            id = id,
            behaviorId = behavior.id,
            scheduleId = schedule.id,
            scheduledDate = date,
            scheduledTime = time,
            scheduledAtUtcMillis = time?.let { absoluteMillis(date, it, zone) },
            zoneId = zone.id,
            occurrenceIndex = occurrenceIndex,
            rootInstanceId = id,
            dedupKey = dedupKey(behavior.id, schedule.id, date, occurrenceIndex),
            createdAtMillis = 0L, // filled in by the repository, which owns the clock
        )
    }

    companion object {
        private val SKIPPING_TYPES = setOf(
            ExceptionType.SKIP,
            ExceptionType.PAUSE,
            ExceptionType.VACATION,
            ExceptionType.HOLIDAY,
        )

        /**
         * Stable identity for a generated occurrence. Regenerating the same horizon yields the
         * same key, which is what makes generation idempotent.
         */
        fun dedupKey(
            behaviorId: String,
            scheduleId: String,
            date: LocalDate,
            occurrenceIndex: Int,
        ): String = "s|$behaviorId|$scheduleId|${date.toEpochDay()}|$occurrenceIndex"

        /** Recovery instances are always unique; they are never regenerated. */
        fun recoveryDedupKey(instanceId: String): String = "r|$instanceId"

        /**
         * Local wall-clock time to an absolute instant.
         *
         * [ZonedDateTime.of] resolves DST edges for us: a time that does not exist on a
         * spring-forward day is pushed forward by the gap, and an ambiguous autumn time picks the
         * earlier offset. That is the behaviour a user expects from "remind me at 06:30".
         */
        fun absoluteMillis(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
            ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
    }
}
