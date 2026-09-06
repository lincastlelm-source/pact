package com.pact.coach.domain

import com.pact.coach.domain.model.DayMask
import com.pact.coach.domain.model.RecurrenceType
import com.pact.coach.domain.model.Schedule
import com.pact.coach.domain.recurrence.RecurrenceEngine
import com.pact.coach.domain.recurrence.RecurrenceValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class RecurrenceEngineTest {

    // 2026-09-07 is a Monday; every date in this file is anchored to that week.
    private val monday = LocalDate.of(2026, 9, 7)
    private val tuesday = monday.plusDays(1)
    private val friday = monday.plusDays(4)
    private val saturday = monday.plusDays(5)
    private val sunday = monday.plusDays(6)

    private fun schedule(
        type: RecurrenceType,
        mask: Int = DayMask.ALL,
        start: LocalDate = monday,
        end: LocalDate? = null,
        interval: Int = 1,
        anchor: LocalDate? = null,
        times: List<LocalTime> = listOf(LocalTime.of(7, 0)),
        perWeek: Int? = null,
    ) = Schedule(
        behaviorId = "b1",
        recurrenceType = type,
        daysOfWeekMask = mask,
        intervalDays = interval,
        anchorDate = anchor,
        startDate = start,
        endDate = end,
        timesOfDay = times,
        timesPerWeek = perWeek,
    )

    @Test
    fun `daily occurs on every day from the start date`() {
        val s = schedule(RecurrenceType.DAILY)
        assertTrue(RecurrenceEngine.occursOn(s, monday))
        assertTrue(RecurrenceEngine.occursOn(s, sunday))
        assertFalse("must not fire before the start date", RecurrenceEngine.occursOn(s, monday.minusDays(1)))
    }

    @Test
    fun `weekdays exclude the weekend`() {
        val s = schedule(RecurrenceType.WEEKDAYS)
        assertTrue(RecurrenceEngine.occursOn(s, monday))
        assertTrue(RecurrenceEngine.occursOn(s, friday))
        assertFalse(RecurrenceEngine.occursOn(s, saturday))
        assertFalse(RecurrenceEngine.occursOn(s, sunday))
    }

    @Test
    fun `weekends are only saturday and sunday`() {
        val s = schedule(RecurrenceType.WEEKENDS)
        assertFalse(RecurrenceEngine.occursOn(s, monday))
        assertTrue(RecurrenceEngine.occursOn(s, saturday))
        assertTrue(RecurrenceEngine.occursOn(s, sunday))
    }

    @Test
    fun `specific days of week honour the mask`() {
        // Monday, Wednesday, Friday, the classic gym schedule.
        val s = schedule(RecurrenceType.DAYS_OF_WEEK, mask = DayMask.of(1, 3, 5))
        assertTrue(RecurrenceEngine.occursOn(s, monday))
        assertFalse(RecurrenceEngine.occursOn(s, tuesday))
        assertTrue(RecurrenceEngine.occursOn(s, monday.plusDays(2)))
        assertTrue(RecurrenceEngine.occursOn(s, friday))
        assertFalse(RecurrenceEngine.occursOn(s, saturday))
    }

    @Test
    fun `once only occurs on its start date`() {
        val s = schedule(RecurrenceType.ONCE)
        assertTrue(RecurrenceEngine.occursOn(s, monday))
        assertFalse(RecurrenceEngine.occursOn(s, tuesday))
        assertEquals(listOf(monday), RecurrenceEngine.occurrencesBetween(s, monday, sunday))
    }

    @Test
    fun `every n days counts from the anchor`() {
        val s = schedule(RecurrenceType.EVERY_N_DAYS, interval = 3, anchor = monday)
        assertTrue(RecurrenceEngine.occursOn(s, monday))
        assertFalse(RecurrenceEngine.occursOn(s, monday.plusDays(1)))
        assertFalse(RecurrenceEngine.occursOn(s, monday.plusDays(2)))
        assertTrue(RecurrenceEngine.occursOn(s, monday.plusDays(3)))
        assertTrue(RecurrenceEngine.occursOn(s, monday.plusDays(6)))
    }

    @Test
    fun `every n days does not fire before its anchor`() {
        val s = schedule(RecurrenceType.EVERY_N_DAYS, interval = 2, start = monday, anchor = friday)
        assertFalse(RecurrenceEngine.occursOn(s, monday))
        assertTrue(RecurrenceEngine.occursOn(s, friday))
    }

    @Test
    fun `end date closes the schedule`() {
        val s = schedule(RecurrenceType.DAILY, end = tuesday)
        assertTrue(RecurrenceEngine.occursOn(s, monday))
        assertTrue(RecurrenceEngine.occursOn(s, tuesday))
        assertFalse(RecurrenceEngine.occursOn(s, tuesday.plusDays(1)))
    }

    @Test
    fun `a disabled schedule never occurs`() {
        val s = schedule(RecurrenceType.DAILY).copy(isEnabled = false)
        assertFalse(RecurrenceEngine.occursOn(s, monday))
        assertTrue(RecurrenceEngine.occurrencesBetween(s, monday, sunday).isEmpty())
    }

    @Test
    fun `occurrences between returns an inclusive range`() {
        val s = schedule(RecurrenceType.DAILY)
        val days = RecurrenceEngine.occurrencesBetween(s, monday, sunday)
        assertEquals(7, days.size)
        assertEquals(monday, days.first())
        assertEquals(sunday, days.last())
    }

    @Test
    fun `occurrences between returns nothing for an inverted range`() {
        val s = schedule(RecurrenceType.DAILY)
        assertTrue(RecurrenceEngine.occurrencesBetween(s, sunday, monday).isEmpty())
    }

    @Test
    fun `next occurrence skips to the following matching day`() {
        val s = schedule(RecurrenceType.DAYS_OF_WEEK, mask = DayMask.of(1, 5))
        assertEquals(friday, RecurrenceEngine.nextOccurrence(s, monday))
        assertEquals(monday.plusWeeks(1), RecurrenceEngine.nextOccurrence(s, friday))
    }

    @Test
    fun `next occurrence returns null past the end date`() {
        val s = schedule(RecurrenceType.DAILY, end = tuesday)
        assertNull(RecurrenceEngine.nextOccurrence(s, tuesday))
    }

    @Test
    fun `times per week spreads slots across the week rather than bunching them`() {
        val days = RecurrenceEngine.spreadAcrossWeek(3, monday)
        assertEquals(3, days.size)
        assertEquals(3, days.distinct().size)
        assertTrue("slots must stay inside the week", days.all { it < monday.plusDays(7) })
        // Evenly spaced: Monday, Wednesday, Friday.
        assertEquals(listOf(monday, monday.plusDays(2), monday.plusDays(4)), days)
    }

    @Test
    fun `spread across week is bounded to seven`() {
        assertEquals(7, RecurrenceEngine.spreadAcrossWeek(12, monday).size)
        assertTrue(RecurrenceEngine.spreadAcrossWeek(0, monday).isEmpty())
    }

    @Test
    fun `week start respects the configured first day`() {
        assertEquals(monday, RecurrenceEngine.weekStart(friday))
        assertEquals(monday, RecurrenceEngine.weekStart(sunday))
        assertEquals(sunday.minusDays(7), RecurrenceEngine.weekStart(friday, java.time.DayOfWeek.SUNDAY))
    }

    @Test
    fun `describe produces a readable summary`() {
        val s = schedule(
            RecurrenceType.DAYS_OF_WEEK,
            mask = DayMask.of(1, 3, 5),
            times = listOf(LocalTime.of(6, 30), LocalTime.of(18, 0)),
        )
        val text = RecurrenceEngine.describe(s, use24Hour = true)
        assertTrue(text, text.contains("Mon"))
        assertTrue(text, text.contains("06:30"))
        assertTrue(text, text.contains("18:00"))
    }

    // --- Validation ---------------------------------------------------------------------

    @Test
    fun `validation rejects days of week with no days selected`() {
        val s = schedule(RecurrenceType.DAYS_OF_WEEK, mask = DayMask.NONE)
        val result = RecurrenceValidator.validate(s, isTimeBased = true)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("at least one day") })
    }

    @Test
    fun `validation rejects a time based behavior with no times`() {
        val s = schedule(RecurrenceType.DAILY, times = emptyList())
        val result = RecurrenceValidator.validate(s, isTimeBased = true)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("at least one time") })
    }

    @Test
    fun `validation rejects an end date before the start date`() {
        val s = schedule(RecurrenceType.DAILY, start = friday, end = monday)
        val result = RecurrenceValidator.validate(s, isTimeBased = true)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("end date") })
    }

    @Test
    fun `validation rejects a zero interval`() {
        val s = schedule(RecurrenceType.EVERY_N_DAYS, interval = 0)
        val result = RecurrenceValidator.validate(s, isTimeBased = true)
        assertFalse(result.isValid)
    }

    @Test
    fun `validation rejects an out of range times per week`() {
        val s = schedule(RecurrenceType.TIMES_PER_WEEK, perWeek = 9)
        assertFalse(RecurrenceValidator.validate(s, isTimeBased = false).isValid)
        val ok = schedule(RecurrenceType.TIMES_PER_WEEK, perWeek = 3, times = emptyList())
        assertTrue(RecurrenceValidator.validate(ok, isTimeBased = false).isValid)
    }

    @Test
    fun `validation accepts a well formed daily schedule`() {
        val s = schedule(RecurrenceType.DAILY, times = listOf(LocalTime.of(6, 30), LocalTime.of(18, 0)))
        assertTrue(RecurrenceValidator.validate(s, isTimeBased = true).isValid)
    }
}
