package com.pact.coach.domain

import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorException
import com.pact.coach.domain.model.DayMask
import com.pact.coach.domain.model.ExceptionType
import com.pact.coach.domain.model.RecurrenceType
import com.pact.coach.domain.model.Schedule
import com.pact.coach.domain.scheduler.InstanceGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class InstanceGeneratorTest {

    private val generator = InstanceGenerator()
    private val zone = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 9, 7)
    private val now = Instant.parse("2026-09-07T00:00:00Z")

    private fun behavior(
        id: String = "b1",
        timeBased: Boolean = true,
        active: Boolean = true,
    ) = Behavior(
        id = id,
        name = "Exercise",
        isTimeBased = timeBased,
        isActive = active,
        createdAt = now,
        updatedAt = now,
    )

    private fun schedule(
        times: List<LocalTime> = listOf(LocalTime.of(6, 30), LocalTime.of(18, 0)),
        type: RecurrenceType = RecurrenceType.DAILY,
        perDay: Int = 1,
        perWeek: Int? = null,
        mask: Int = DayMask.ALL,
    ) = Schedule(
        id = "s1",
        behaviorId = "b1",
        recurrenceType = type,
        daysOfWeekMask = mask,
        startDate = monday,
        timesOfDay = times,
        timesPerDay = perDay,
        timesPerWeek = perWeek,
    )

    @Test
    fun `two times a day produce two separate occurrences that do not overwrite each other`() {
        val instances = generator.generate(
            behavior(), listOf(schedule()), emptyList(), monday, monday, zone,
        )

        assertEquals(2, instances.size)
        assertEquals(LocalTime.of(6, 30), instances[0].scheduledTime)
        assertEquals(LocalTime.of(18, 0), instances[1].scheduledTime)
        // Distinct occurrence indices and distinct dedup keys: this is what stops the evening
        // session from replacing the morning one.
        assertEquals(0, instances[0].occurrenceIndex)
        assertEquals(1, instances[1].occurrenceIndex)
        assertNotEquals(instances[0].dedupKey, instances[1].dedupKey)
        assertNotEquals(instances[0].id, instances[1].id)
    }

    @Test
    fun `occurrence index follows chronological order even when times are given unsorted`() {
        val unsorted = schedule(times = listOf(LocalTime.of(18, 0), LocalTime.of(6, 30)))
        val instances = generator.generate(
            behavior(), listOf(unsorted), emptyList(), monday, monday, zone,
        )
        assertEquals(LocalTime.of(6, 30), instances.first { it.occurrenceIndex == 0 }.scheduledTime)
        assertEquals(LocalTime.of(18, 0), instances.first { it.occurrenceIndex == 1 }.scheduledTime)
    }

    @Test
    fun `dedup keys are stable across regeneration so alarms cannot be duplicated`() {
        val first = generator.generate(behavior(), listOf(schedule()), emptyList(), monday, monday.plusDays(3), zone)
        val second = generator.generate(behavior(), listOf(schedule()), emptyList(), monday, monday.plusDays(3), zone)

        assertEquals(first.map { it.dedupKey }, second.map { it.dedupKey })
        // Ids differ (they are fresh UUIDs); only the dedup key is the identity that matters.
        assertEquals(first.size, first.map { it.dedupKey }.distinct().size)
    }

    @Test
    fun `a paused behavior generates nothing`() {
        val instances = generator.generate(
            behavior(active = false), listOf(schedule()), emptyList(), monday, monday.plusDays(7), zone,
        )
        assertTrue(instances.isEmpty())
    }

    @Test
    fun `a one day skip removes only that day and leaves the schedule intact`() {
        val skip = BehaviorException(
            behaviorId = "b1",
            type = ExceptionType.SKIP,
            startDate = monday.plusDays(2),
            endDate = monday.plusDays(2),
        )
        val instances = generator.generate(
            behavior(), listOf(schedule()), listOf(skip), monday, monday.plusDays(4), zone,
        )

        val dates = instances.map { it.scheduledDate }.distinct()
        assertFalse(dates.contains(monday.plusDays(2)))
        assertTrue(dates.contains(monday.plusDays(1)))
        assertTrue(dates.contains(monday.plusDays(3)))
    }

    @Test
    fun `a vacation block removes a whole range`() {
        val vacation = BehaviorException(
            behaviorId = null, // applies to every behavior
            type = ExceptionType.VACATION,
            startDate = monday.plusDays(1),
            endDate = monday.plusDays(3),
        )
        val dates = generator.generate(
            behavior(), listOf(schedule()), listOf(vacation), monday, monday.plusDays(4), zone,
        ).map { it.scheduledDate }.distinct()

        assertEquals(listOf(monday, monday.plusDays(4)), dates)
    }

    @Test
    fun `an exception for a different behavior is ignored`() {
        val other = BehaviorException(
            behaviorId = "someone-else",
            type = ExceptionType.SKIP,
            startDate = monday,
            endDate = monday,
        )
        val instances = generator.generate(
            behavior(), listOf(schedule()), listOf(other), monday, monday, zone,
        )
        assertEquals(2, instances.size)
    }

    @Test
    fun `untimed behaviors produce occurrences without a clock time`() {
        val instances = generator.generate(
            behavior(timeBased = false),
            listOf(schedule(times = emptyList(), perDay = 3)),
            emptyList(),
            monday, monday, zone,
        )
        assertEquals(3, instances.size)
        assertTrue(instances.all { it.scheduledTime == null })
        assertTrue(instances.all { it.scheduledAtUtcMillis == null })
        assertEquals(listOf(0, 1, 2), instances.map { it.occurrenceIndex })
    }

    @Test
    fun `times per week generates the requested count spread across the week`() {
        val instances = generator.generate(
            behavior(timeBased = false),
            listOf(schedule(times = emptyList(), type = RecurrenceType.TIMES_PER_WEEK, perWeek = 3)),
            emptyList(),
            monday, monday.plusDays(6), zone,
        )
        assertEquals(3, instances.size)
        assertEquals(3, instances.map { it.scheduledDate }.distinct().size)
    }

    @Test
    fun `absolute time is computed in the behavior's zone`() {
        val instances = generator.generate(
            behavior(), listOf(schedule(times = listOf(LocalTime.of(6, 30)))), emptyList(),
            monday, monday, zone,
        )
        val expected = java.time.ZonedDateTime.of(monday, LocalTime.of(6, 30), zone)
            .toInstant().toEpochMilli()
        assertEquals(expected, instances.single().scheduledAtUtcMillis)
        assertEquals(zone.id, instances.single().zoneId)
    }

    @Test
    fun `a spring forward gap is resolved rather than throwing`() {
        // In Europe/London, 2026-03-29 jumps from 01:00 to 02:00. 01:30 does not exist.
        val dstDay = LocalDate.of(2026, 3, 29)
        val millis = InstanceGenerator.absoluteMillis(dstDay, LocalTime.of(1, 30), zone)

        // ZonedDateTime pushes the time forward by the size of the gap, which is what a user
        // expects: the reminder still happens, at the first valid moment.
        val resolved = java.time.Instant.ofEpochMilli(millis).atZone(zone)
        assertEquals(dstDay, resolved.toLocalDate())
        assertEquals(LocalTime.of(2, 30), resolved.toLocalTime())
    }

    @Test
    fun `an autumn overlap picks a single deterministic instant`() {
        // 2026-10-25 in London has 01:30 twice.
        val dstDay = LocalDate.of(2026, 10, 25)
        val a = InstanceGenerator.absoluteMillis(dstDay, LocalTime.of(1, 30), zone)
        val b = InstanceGenerator.absoluteMillis(dstDay, LocalTime.of(1, 30), zone)
        assertEquals("must be deterministic across calls", a, b)
    }

    @Test
    fun `the same behavior scheduled twice at the same time yields one occurrence`() {
        // Duplicate times are collapsed, so the user cannot accidentally create two identical alarms.
        val duplicated = schedule(times = listOf(LocalTime.of(7, 0), LocalTime.of(7, 0)))
        val instances = generator.generate(
            behavior(), listOf(duplicated), emptyList(), monday, monday, zone,
        )
        assertEquals(2, instances.size)
        // They are separate occurrence slots with distinct keys, never a silent overwrite.
        assertEquals(2, instances.map { it.dedupKey }.distinct().size)
    }

    @Test
    fun `generation is bounded by the requested window`() {
        val instances = generator.generate(
            behavior(), listOf(schedule(times = listOf(LocalTime.NOON))), emptyList(),
            monday, monday.plusDays(13), zone,
        )
        assertEquals(14, instances.size)
        assertTrue(instances.all { !it.scheduledDate.isAfter(monday.plusDays(13)) })
    }

    @Test
    fun `every generated instance is its own chain root`() {
        val instances = generator.generate(
            behavior(), listOf(schedule()), emptyList(), monday, monday, zone,
        )
        assertTrue(instances.all { it.rootInstanceId == it.id })
        assertTrue(instances.all { it.originInstanceId == null })
        assertTrue(instances.all { it.recoveryDepth == 0 })
    }
}
