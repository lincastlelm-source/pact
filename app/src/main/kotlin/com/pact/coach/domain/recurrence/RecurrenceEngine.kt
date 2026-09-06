package com.pact.coach.domain.recurrence

import com.pact.coach.domain.model.DayMask
import com.pact.coach.domain.model.RecurrenceType
import com.pact.coach.domain.model.Schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure date maths: given a [Schedule], which calendar days does it land on?
 *
 * This has no Android dependency and no clock of its own, so it is fully unit testable. It
 * answers questions about *days only*; turning a day into an absolute firing instant (which is
 * where time zones and DST come in) is the job of the instance generator.
 */
object RecurrenceEngine {

    /** Hard ceiling so a malformed rule can never spin forever. */
    private const val MAX_SCAN_DAYS = 366 * 5

    fun occursOn(schedule: Schedule, date: LocalDate): Boolean {
        if (!schedule.isEnabled) return false
        if (date.isBefore(schedule.startDate)) return false
        schedule.endDate?.let { if (date.isAfter(it)) return false }

        return when (schedule.recurrenceType) {
            RecurrenceType.ONCE -> date == schedule.startDate

            RecurrenceType.DAILY -> true

            RecurrenceType.WEEKDAYS ->
                DayMask.contains(DayMask.WEEKDAYS, date.dayOfWeek.value)

            RecurrenceType.WEEKENDS ->
                DayMask.contains(DayMask.WEEKENDS, date.dayOfWeek.value)

            RecurrenceType.DAYS_OF_WEEK ->
                DayMask.contains(schedule.daysOfWeekMask, date.dayOfWeek.value)

            RecurrenceType.EVERY_N_DAYS -> {
                val interval = schedule.intervalDays.coerceAtLeast(1)
                val anchor = schedule.anchorDate ?: schedule.startDate
                if (date.isBefore(anchor)) {
                    false
                } else {
                    ChronoUnit.DAYS.between(anchor, date) % interval == 0L
                }
            }

            // "3 times per week" is not tied to particular weekdays. The generator spreads the
            // required count across the week; every in-range day is a candidate.
            RecurrenceType.TIMES_PER_WEEK -> true
        }
    }

    /** Every day in [from]..[to] inclusive that the schedule lands on. */
    fun occurrencesBetween(schedule: Schedule, from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        val result = mutableListOf<LocalDate>()
        var cursor = maxOf(from, schedule.startDate)
        val last = schedule.endDate?.let { minOf(to, it) } ?: to
        var guard = 0
        while (!cursor.isAfter(last) && guard++ < MAX_SCAN_DAYS) {
            if (occursOn(schedule, cursor)) result += cursor
            cursor = cursor.plusDays(1)
        }
        return result
    }

    /** The first day strictly after [after] that the schedule lands on, or null. */
    fun nextOccurrence(schedule: Schedule, after: LocalDate): LocalDate? {
        var cursor = after.plusDays(1)
        val limit = schedule.endDate
        var guard = 0
        while (guard++ < MAX_SCAN_DAYS) {
            if (limit != null && cursor.isAfter(limit)) return null
            if (occursOn(schedule, cursor)) return cursor
            cursor = cursor.plusDays(1)
        }
        return null
    }

    /**
     * For [RecurrenceType.TIMES_PER_WEEK]: which days of the week [weekStart] should carry a
     * flexible slot. Spreads N slots as evenly as possible across the 7 days so the user is not
     * asked to do everything on Monday.
     */
    fun spreadAcrossWeek(count: Int, weekStart: LocalDate): List<LocalDate> {
        val n = count.coerceIn(0, 7)
        if (n == 0) return emptyList()
        if (n == 7) return (0L..6L).map { weekStart.plusDays(it) }
        // Evenly spaced offsets, e.g. n=3 -> days 0, 2, 4.
        return (0 until n).map { i -> weekStart.plusDays((i * 7L) / n) }
    }

    /** Monday-based week start for [date], honouring the user's preferred first day. */
    fun weekStart(date: LocalDate, firstDay: DayOfWeek = DayOfWeek.MONDAY): LocalDate {
        val diff = (date.dayOfWeek.value - firstDay.value + 7) % 7
        return date.minusDays(diff.toLong())
    }

    /** Human-readable summary used on behavior cards, e.g. "Mon, Wed, Fri at 06:30". */
    fun describe(schedule: Schedule, use24Hour: Boolean = true): String {
        val days = when (schedule.recurrenceType) {
            RecurrenceType.ONCE -> "Once on ${schedule.startDate}"
            RecurrenceType.DAILY -> "Every day"
            RecurrenceType.WEEKDAYS -> "Weekdays"
            RecurrenceType.WEEKENDS -> "Weekends"
            RecurrenceType.DAYS_OF_WEEK -> {
                val names = DayMask.toIsoDays(schedule.daysOfWeekMask)
                    .map { DayOfWeek.of(it).name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } }
                if (names.isEmpty()) "No days selected" else names.joinToString(", ")
            }
            RecurrenceType.EVERY_N_DAYS ->
                if (schedule.intervalDays <= 1) "Every day" else "Every ${schedule.intervalDays} days"
            RecurrenceType.TIMES_PER_WEEK ->
                "${schedule.timesPerWeek ?: 1} times per week"
        }
        if (schedule.timesOfDay.isEmpty()) {
            val perDay = schedule.timesPerDay
            return if (perDay > 1) "$days, $perDay times a day" else days
        }
        val times = schedule.timesOfDay.joinToString(", ") { t ->
            com.pact.coach.core.util.TimeFormat.time(t, use24Hour)
        }
        return "$days at $times"
    }
}

/** Result of validating a schedule the user is editing. */
data class RecurrenceValidation(val errors: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
}

object RecurrenceValidator {

    fun validate(schedule: Schedule, isTimeBased: Boolean): RecurrenceValidation {
        val errors = mutableListOf<String>()

        schedule.endDate?.let {
            if (it.isBefore(schedule.startDate)) {
                errors += "The end date is before the start date."
            }
        }

        when (schedule.recurrenceType) {
            RecurrenceType.DAYS_OF_WEEK ->
                if (schedule.daysOfWeekMask == DayMask.NONE) {
                    errors += "Pick at least one day of the week."
                }

            RecurrenceType.EVERY_N_DAYS ->
                if (schedule.intervalDays < 1) {
                    errors += "The repeat interval must be at least 1 day."
                } else if (schedule.intervalDays > 365) {
                    errors += "The repeat interval cannot be longer than a year."
                }

            RecurrenceType.TIMES_PER_WEEK -> {
                val n = schedule.timesPerWeek ?: 0
                if (n !in 1..7) errors += "Choose between 1 and 7 times per week."
            }

            else -> Unit
        }

        if (isTimeBased) {
            if (schedule.timesOfDay.isEmpty()) {
                errors += "Add at least one time of day."
            }
            if (schedule.timesOfDay.distinct().size != schedule.timesOfDay.size) {
                errors += "The same time is listed twice."
            }
            if (schedule.timesOfDay.size > MAX_TIMES_PER_DAY) {
                errors += "A behavior can have at most $MAX_TIMES_PER_DAY times a day."
            }
        } else {
            if (schedule.timesPerDay !in 1..MAX_TIMES_PER_DAY) {
                errors += "Choose between 1 and $MAX_TIMES_PER_DAY times a day."
            }
        }

        return RecurrenceValidation(errors)
    }

    const val MAX_TIMES_PER_DAY = 12
}
