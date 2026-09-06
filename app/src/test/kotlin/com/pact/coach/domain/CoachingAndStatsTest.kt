package com.pact.coach.domain

import com.pact.coach.domain.coaching.CoachingEngine
import com.pact.coach.domain.coaching.StatsCalculator
import com.pact.coach.domain.instruction.InstructionResolver
import com.pact.coach.domain.instruction.ProgressionResolver
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.ChecklistItem
import com.pact.coach.domain.model.CoachIntensity
import com.pact.coach.domain.model.CompletionQuality
import com.pact.coach.domain.model.DailyInstruction
import com.pact.coach.domain.model.Importance
import com.pact.coach.domain.model.InsightKind
import com.pact.coach.domain.model.InsightSeverity
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.model.ProgressionStep
import com.pact.coach.domain.model.RecurrenceType
import com.pact.coach.domain.model.Schedule
import com.pact.coach.domain.scheduler.ConflictDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

private val NOW: Instant = Instant.parse("2026-09-07T00:00:00Z")
private val TODAY: LocalDate = LocalDate.of(2026, 9, 7)

private fun behavior(
    id: String = "b1",
    name: String = "Exercise",
    minimumMinutes: Int? = 5,
    importance: Importance = Importance.NORMAL,
    targetMinutes: Int? = 30,
) = Behavior(
    id = id,
    name = name,
    targetDurationMinutes = targetMinutes,
    minimumDurationMinutes = minimumMinutes,
    importance = importance,
    createdAt = NOW,
    updatedAt = NOW,
)

private fun instance(
    id: String,
    state: InstanceState,
    date: LocalDate = TODAY,
    time: LocalTime? = LocalTime.of(18, 0),
    root: String = id,
    depth: Int = 0,
    quality: CompletionQuality? = null,
    behaviorId: String = "b1",
) = BehaviorInstance(
    id = id,
    behaviorId = behaviorId,
    scheduleId = "s1",
    scheduledDate = date,
    scheduledTime = time,
    scheduledAtUtcMillis = time?.let { date.atTime(it).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() },
    zoneId = "UTC",
    occurrenceIndex = 0,
    state = state,
    rootInstanceId = root,
    originInstanceId = if (depth == 0) null else "prev",
    recoveryDepth = depth,
    completionQuality = quality,
    dedupKey = id,
    createdAtMillis = NOW.toEpochMilli(),
)

class StatsCalculatorTest {

    private val calculator = StatsCalculator()

    @Test
    fun `completion rate is measured over chains not raw rows`() {
        // One chain: planned, recovered, then completed. That is one completion, not one miss
        // plus one completion. Scoring rows instead would punish recovering.
        val rows = listOf(
            instance("a", InstanceState.RECOVERED, root = "a"),
            instance("a1", InstanceState.COMPLETED, root = "a", depth = 1),
        )
        val stats = calculator.forBehavior("b1", rows, TODAY)

        assertEquals(1, stats.resolvedChains)
        assertEquals(1, stats.completed)
        assertEquals(1.0, stats.completionRate, 0.0001)
        assertEquals(1, stats.chainsWithRecovery)
        assertEquals(1, stats.recoveredAndCompleted)
        assertEquals(1.0, stats.recoveryScore, 0.0001)
    }

    @Test
    fun `recovery score is independent of how often recovery was needed`() {
        val rows = listOf(
            // Chain 1: recovered, completed.
            instance("a", InstanceState.RECOVERED, root = "a"),
            instance("a1", InstanceState.COMPLETED, root = "a", depth = 1),
            // Chain 2: recovered, missed.
            instance("b", InstanceState.RECOVERED, root = "b", date = TODAY.minusDays(1)),
            instance("b1", InstanceState.MISSED, root = "b", depth = 1, date = TODAY.minusDays(1)),
            // Chain 3: straight completion, no recovery.
            instance("c", InstanceState.COMPLETED, root = "c", date = TODAY.minusDays(2)),
        )
        val stats = calculator.forBehavior("b1", rows, TODAY)

        assertEquals(3, stats.resolvedChains)
        assertEquals(2, stats.chainsWithRecovery)
        assertEquals(1, stats.recoveredAndCompleted)
        assertEquals(0.5, stats.recoveryScore, 0.0001)
        assertEquals(1, stats.completedFirstTime)
    }

    @Test
    fun `pass and miss are counted separately, never lumped together`() {
        val rows = listOf(
            instance("a", InstanceState.PASSED, root = "a"),
            instance("b", InstanceState.MISSED, root = "b", date = TODAY.minusDays(1)),
            instance("c", InstanceState.COMPLETED, root = "c", date = TODAY.minusDays(2)),
        )
        val stats = calculator.forBehavior("b1", rows, TODAY)

        assertEquals(1, stats.passed)
        assertEquals(1, stats.missed)
        assertEquals(1, stats.completed)
        assertEquals(3, stats.resolvedChains)
    }

    @Test
    fun `pending chains are excluded from rates`() {
        val rows = listOf(
            instance("a", InstanceState.COMPLETED, root = "a"),
            instance("b", InstanceState.SCHEDULED, root = "b", date = TODAY.plusDays(1)),
        )
        val stats = calculator.forBehavior("b1", rows, TODAY)
        assertEquals(1, stats.resolvedChains)
        assertEquals(1, stats.pending)
        assertEquals(1.0, stats.completionRate, 0.0001)
    }

    @Test
    fun `streak counts consecutive fully completed scheduled days`() {
        val rows = (0..4).map { offset ->
            instance("d$offset", InstanceState.COMPLETED, date = TODAY.minusDays(offset.toLong()), root = "d$offset")
        }
        val stats = calculator.forBehavior("b1", rows, TODAY)
        assertEquals(5, stats.currentStreak)
        assertEquals(5, stats.longestStreak)
    }

    @Test
    fun `a break resets the current streak but not the longest`() {
        val rows = listOf(
            instance("d4", InstanceState.COMPLETED, date = TODAY.minusDays(4), root = "d4"),
            instance("d3", InstanceState.COMPLETED, date = TODAY.minusDays(3), root = "d3"),
            instance("d2", InstanceState.COMPLETED, date = TODAY.minusDays(2), root = "d2"),
            instance("d1", InstanceState.MISSED, date = TODAY.minusDays(1), root = "d1"),
            instance("d0", InstanceState.COMPLETED, date = TODAY, root = "d0"),
        )
        val stats = calculator.forBehavior("b1", rows, TODAY)
        assertEquals(1, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
    }

    @Test
    fun `a day is only a streak day when every occurrence on it was completed`() {
        val rows = listOf(
            instance("m", InstanceState.COMPLETED, date = TODAY, time = LocalTime.of(6, 30), root = "m"),
            instance("e", InstanceState.MISSED, date = TODAY, time = LocalTime.of(18, 0), root = "e"),
        )
        val stats = calculator.forBehavior("b1", rows, TODAY)
        assertEquals(0, stats.currentStreak)
    }

    @Test
    fun `minimum completions are tracked separately from full target completions`() {
        val rows = listOf(
            instance("a", InstanceState.COMPLETED, root = "a", quality = CompletionQuality.MINIMUM_COMPLETED),
            instance("b", InstanceState.COMPLETED, root = "b", quality = CompletionQuality.TARGET_COMPLETED, date = TODAY.minusDays(1)),
        )
        val stats = calculator.forBehavior("b1", rows, TODAY)
        assertEquals(1, stats.minimumCompletions)
        assertEquals(1, stats.targetCompletions)
        assertEquals(0.5, stats.minimumShare, 0.0001)
    }

    @Test
    fun `time of day rates are grouped by scheduled hour`() {
        val rows = listOf(
            instance("a", InstanceState.COMPLETED, time = LocalTime.of(6, 30), root = "a"),
            instance("b", InstanceState.COMPLETED, time = LocalTime.of(6, 30), root = "b", date = TODAY.minusDays(1)),
            instance("c", InstanceState.MISSED, time = LocalTime.of(18, 0), root = "c", date = TODAY.minusDays(2)),
            instance("d", InstanceState.MISSED, time = LocalTime.of(18, 0), root = "d", date = TODAY.minusDays(3)),
        )
        val stats = calculator.forBehavior("b1", rows, TODAY)
        assertEquals(1.0, stats.completionsByHour.getValue(6).rate, 0.0001)
        assertEquals(0.0, stats.completionsByHour.getValue(18).rate, 0.0001)
        assertEquals(6, stats.bestHour)
        assertEquals(18, stats.worstHour)
    }

    @Test
    fun `an empty history produces zeros rather than a crash`() {
        val stats = calculator.forBehavior("b1", emptyList(), TODAY)
        assertEquals(0, stats.resolvedChains)
        assertEquals(0.0, stats.completionRate, 0.0001)
        assertEquals(0.0, stats.recoveryScore, 0.0001)
        assertFalse(stats.hasEnoughData)
    }
}

class CoachingEngineTest {

    private val calculator = StatsCalculator()
    private val engine = CoachingEngine(CoachIntensity.HIGH)

    private fun analyse(rows: List<BehaviorInstance>, schedules: List<Schedule> = emptyList()) =
        engine.analyse(
            behavior(),
            calculator.forBehavior("b1", rows, TODAY),
            schedules,
            rows,
            TODAY,
        )

    @Test
    fun `a new behavior gets an honest 'not enough data' message rather than fake insight`() {
        val insights = analyse(listOf(instance("a", InstanceState.COMPLETED, root = "a")))
        assertEquals(1, insights.size)
        assertEquals(InsightKind.GETTING_STARTED, insights.single().kind)
    }

    @Test
    fun `high completion produces positive reinforcement`() {
        val rows = (0..9).map {
            instance("d$it", InstanceState.COMPLETED, date = TODAY.minusDays(it.toLong()), root = "d$it")
        }
        val insights = analyse(rows)
        assertTrue(insights.any { it.kind == InsightKind.CONSISTENCY && it.severity == InsightSeverity.POSITIVE })
    }

    @Test
    fun `low completion recommends reviewing the schedule`() {
        val rows = (0..9).map { offset ->
            val state = if (offset < 7) InstanceState.MISSED else InstanceState.COMPLETED
            instance("d$offset", state, date = TODAY.minusDays(offset.toLong()), root = "d$offset")
        }
        val insights = analyse(rows)
        val low = insights.firstOrNull { it.kind == InsightKind.LOW_COMPLETION }
        assertNotNull("expected a low-completion insight", low)
        assertEquals(InsightSeverity.ATTENTION, low!!.severity)
        assertNotNull(low.recommendation)
    }

    @Test
    fun `frequent recovery is flagged as a possible schedule problem`() {
        val rows = mutableListOf<BehaviorInstance>()
        repeat(6) { i ->
            rows += instance("r$i", InstanceState.RECOVERED, date = TODAY.minusDays(i.toLong()), root = "r$i")
            rows += instance("r${i}a", InstanceState.COMPLETED, date = TODAY.minusDays(i.toLong()), root = "r$i", depth = 1)
        }
        rows += instance("x", InstanceState.COMPLETED, date = TODAY.minusDays(7), root = "x")

        val insights = analyse(rows)
        assertTrue(insights.any { it.kind == InsightKind.RECOVERY && it.severity == InsightSeverity.ATTENTION })
    }

    @Test
    fun `a clear time of day difference produces an applicable schedule suggestion`() {
        val rows = mutableListOf<BehaviorInstance>()
        // 20:00 works, 18:00 does not.
        repeat(4) { i ->
            rows += instance("g$i", InstanceState.COMPLETED, time = LocalTime.of(20, 0), date = TODAY.minusDays(i.toLong()), root = "g$i")
        }
        repeat(4) { i ->
            rows += instance("b$i", InstanceState.MISSED, time = LocalTime.of(18, 0), date = TODAY.minusDays((i + 5).toLong()), root = "b$i")
        }

        val schedule = Schedule(
            id = "s1",
            behaviorId = "b1",
            recurrenceType = RecurrenceType.DAILY,
            startDate = TODAY.minusDays(30),
            timesOfDay = listOf(LocalTime.of(18, 0), LocalTime.of(20, 0)),
        )

        val insights = analyse(rows, listOf(schedule))
        val timing = insights.firstOrNull { it.kind == InsightKind.TIME_OF_DAY }
        assertNotNull("expected a time-of-day insight", timing)

        val suggestion = timing!!.scheduleSuggestion
        assertNotNull("the insight should carry an applicable suggestion", suggestion)
        assertEquals(LocalTime.of(18, 0), suggestion!!.fromTime)
        assertEquals(20, suggestion.toTime.hour)
        assertEquals("s1", suggestion.scheduleId)
    }

    @Test
    fun `repeatedly passing this week is surfaced`() {
        val rows = (0..5).map { offset ->
            val state = if (offset < 4) InstanceState.PASSED else InstanceState.COMPLETED
            instance("d$offset", state, date = TODAY.minusDays(offset.toLong()), root = "d$offset")
        }
        val insights = analyse(rows)
        assertTrue(insights.any { it.kind == InsightKind.FREQUENT_PASS })
    }

    @Test
    fun `mostly hitting only the minimum suggests lowering the target`() {
        val rows = (0..7).map { offset ->
            instance(
                "d$offset",
                InstanceState.COMPLETED,
                date = TODAY.minusDays(offset.toLong()),
                root = "d$offset",
                quality = if (offset < 6) CompletionQuality.MINIMUM_COMPLETED else CompletionQuality.TARGET_COMPLETED,
            )
        }
        val insights = analyse(rows)
        assertTrue(insights.any { it.kind == InsightKind.MINIMUM_VS_TARGET })
    }

    @Test
    fun `intensity caps how much the coach says`() {
        val rows = (0..13).map { offset ->
            instance("d$offset", InstanceState.COMPLETED, date = TODAY.minusDays(offset.toLong()), root = "d$offset")
        }
        val quiet = CoachingEngine(CoachIntensity.LOW).analyse(
            behavior(), calculator.forBehavior("b1", rows, TODAY), emptyList(), rows, TODAY,
        )
        assertTrue(quiet.size <= 1)
    }

    @Test
    fun `attention insights sort ahead of praise`() {
        val rows = mutableListOf<BehaviorInstance>()
        repeat(6) { i ->
            rows += instance("r$i", InstanceState.RECOVERED, date = TODAY.minusDays(i.toLong()), root = "r$i")
            rows += instance("r${i}a", InstanceState.COMPLETED, date = TODAY.minusDays(i.toLong()), root = "r$i", depth = 1)
        }
        val insights = analyse(rows)
        if (insights.size > 1) {
            assertEquals(InsightSeverity.ATTENTION, insights.first().severity)
        }
    }
}

class InstructionResolverTest {

    private val resolver = InstructionResolver()

    @Test
    fun `falls back to the behavior default when there is no day plan`() {
        val result = resolver.resolve(
            behavior().copy(defaultInstruction = "Move for 30 minutes"),
            emptyList(), emptyList(), TODAY, 0,
        )
        assertEquals("Exercise", result.title)
        assertEquals("Move for 30 minutes", result.instructions)
        assertEquals(30, result.durationMinutes)
    }

    @Test
    fun `a weekday plan overrides the default for that day only`() {
        // 2026-09-07 is a Monday.
        val monday = DailyInstruction(
            behaviorId = "b1",
            dayOfWeek = 1,
            title = "20-minute run",
            durationMinutes = 20,
        )
        val onMonday = resolver.resolve(behavior(), listOf(monday), emptyList(), TODAY, 0)
        assertEquals("20-minute run", onMonday.title)
        assertEquals(20, onMonday.durationMinutes)

        val onTuesday = resolver.resolve(behavior(), listOf(monday), emptyList(), TODAY.plusDays(1), 0)
        assertEquals("Exercise", onTuesday.title)
        assertEquals(30, onTuesday.durationMinutes)
    }

    @Test
    fun `an exact date beats a weekday rule`() {
        val weekday = DailyInstruction(behaviorId = "b1", dayOfWeek = 1, title = "Weekly plan")
        val exact = DailyInstruction(behaviorId = "b1", date = TODAY, title = "Special session")

        val result = resolver.resolve(behavior(), listOf(weekday, exact), emptyList(), TODAY, 0)
        assertEquals("Special session", result.title)
    }

    @Test
    fun `an occurrence specific plan beats a whole day plan`() {
        val allDay = DailyInstruction(behaviorId = "b1", dayOfWeek = 1, title = "General")
        val secondSlot = DailyInstruction(behaviorId = "b1", dayOfWeek = 1, occurrenceIndex = 1, title = "Evening session")

        assertEquals("General", resolver.resolve(behavior(), listOf(allDay, secondSlot), emptyList(), TODAY, 0).title)
        assertEquals("Evening session", resolver.resolve(behavior(), listOf(allDay, secondSlot), emptyList(), TODAY, 1).title)
    }

    @Test
    fun `a day plan only overrides the fields it sets`() {
        // Tuesday says "Upper body" but sets no duration, so the behavior default is inherited.
        val plan = DailyInstruction(behaviorId = "b1", dayOfWeek = 1, title = "Upper body")
        val result = resolver.resolve(behavior(targetMinutes = 45), listOf(plan), emptyList(), TODAY, 0)
        assertEquals("Upper body", result.title)
        assertEquals(45, result.durationMinutes)
    }

    @Test
    fun `a rest day is surfaced as such`() {
        val rest = DailyInstruction(behaviorId = "b1", dayOfWeek = 1, title = "Rest", isRestDay = true)
        assertTrue(resolver.resolve(behavior(), listOf(rest), emptyList(), TODAY, 0).isRestDay)
    }

    @Test
    fun `checklist items scoped to a day plan only appear on that day`() {
        val plan = DailyInstruction(id = "p1", behaviorId = "b1", dayOfWeek = 1, title = "Monday")
        val general = ChecklistItem(behaviorId = "b1", position = 0, text = "Warm up")
        val mondayOnly = ChecklistItem(behaviorId = "b1", dailyInstructionId = "p1", position = 1, text = "Hill sprints")

        val monday = resolver.resolve(behavior(), listOf(plan), listOf(general, mondayOnly), TODAY, 0)
        assertEquals(2, monday.checklist.size)

        val tuesday = resolver.resolve(behavior(), listOf(plan), listOf(general, mondayOnly), TODAY.plusDays(1), 0)
        assertEquals(1, tuesday.checklist.size)
        assertEquals("Warm up", tuesday.checklist.single().text)
    }

    @Test
    fun `a progression ramp raises the target only when enabled`() {
        val steps = listOf(
            ProgressionStep(behaviorId = "b1", weekIndex = 0, targetDurationMinutes = 10),
            ProgressionStep(behaviorId = "b1", weekIndex = 1, targetDurationMinutes = 15),
            ProgressionStep(behaviorId = "b1", weekIndex = 3, targetDurationMinutes = 30),
        )
        val ramped = behavior().copy(progressionEnabled = true)

        val week1 = resolver.resolve(ramped, emptyList(), emptyList(), TODAY, 0, steps, TODAY)
        assertEquals(10, week1.durationMinutes)

        val week2 = resolver.resolve(ramped, emptyList(), emptyList(), TODAY.plusWeeks(1), 0, steps, TODAY)
        assertEquals(15, week2.durationMinutes)

        // Week 2 has no step of its own, so it holds at the previous one.
        val week3 = resolver.resolve(ramped, emptyList(), emptyList(), TODAY.plusWeeks(2), 0, steps, TODAY)
        assertEquals(15, week3.durationMinutes)

        val week4 = resolver.resolve(ramped, emptyList(), emptyList(), TODAY.plusWeeks(3), 0, steps, TODAY)
        assertEquals(30, week4.durationMinutes)

        // Disabled: the behavior target wins, no silent escalation.
        val off = resolver.resolve(behavior(), emptyList(), emptyList(), TODAY.plusWeeks(3), 0, steps, TODAY)
        assertEquals(30, off.durationMinutes)
    }

    @Test
    fun `progression resolver returns null with no steps`() {
        assertNull(ProgressionResolver.stepFor(emptyList(), TODAY, TODAY))
    }
}

class ConflictDetectorTest {

    private val detector = ConflictDetector()

    @Test
    fun `two behaviors booked at the same minute conflict`() {
        val a = behavior(id = "a", name = "Teaching materials", importance = Importance.HIGH, targetMinutes = 60)
        val b = behavior(id = "b", name = "Exercise", importance = Importance.HIGH, targetMinutes = 45)

        val conflicts = detector.detect(
            listOf(
                instance("i1", InstanceState.SCHEDULED, time = LocalTime.of(17, 0), behaviorId = "a"),
                instance("i2", InstanceState.SCHEDULED, time = LocalTime.of(17, 0), behaviorId = "b"),
            ),
            mapOf("a" to a, "b" to b),
        )

        assertEquals(1, conflicts.size)
        assertTrue(conflicts.single().isHighPriority)
        assertTrue(conflicts.single().message.contains("high-priority"))
    }

    @Test
    fun `overlapping durations conflict even when the start times differ`() {
        val a = behavior(id = "a", name = "Teaching", targetMinutes = 60)
        val b = behavior(id = "b", name = "Exercise", targetMinutes = 30)

        val conflicts = detector.detect(
            listOf(
                instance("i1", InstanceState.SCHEDULED, time = LocalTime.of(17, 0), behaviorId = "a"),
                instance("i2", InstanceState.SCHEDULED, time = LocalTime.of(17, 30), behaviorId = "b"),
            ),
            mapOf("a" to a, "b" to b),
        )
        assertEquals(1, conflicts.size)
        assertEquals(30, conflicts.single().overlapMinutes)
    }

    @Test
    fun `well separated behaviors do not conflict`() {
        val a = behavior(id = "a", targetMinutes = 30)
        val b = behavior(id = "b", targetMinutes = 30)

        val conflicts = detector.detect(
            listOf(
                instance("i1", InstanceState.SCHEDULED, time = LocalTime.of(6, 30), behaviorId = "a"),
                instance("i2", InstanceState.SCHEDULED, time = LocalTime.of(18, 0), behaviorId = "b"),
            ),
            mapOf("a" to a, "b" to b),
        )
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `the same behavior twice a day is intentional, not a conflict`() {
        val a = behavior(id = "a", targetMinutes = 30)
        val conflicts = detector.detect(
            listOf(
                instance("i1", InstanceState.SCHEDULED, time = LocalTime.of(6, 30), behaviorId = "a"),
                instance("i2", InstanceState.SCHEDULED, time = LocalTime.of(6, 45), behaviorId = "a"),
            ),
            mapOf("a" to a),
        )
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `untimed behaviors never conflict`() {
        val a = behavior(id = "a")
        val b = behavior(id = "b")
        val conflicts = detector.detect(
            listOf(
                instance("i1", InstanceState.SCHEDULED, time = null, behaviorId = "a"),
                instance("i2", InstanceState.SCHEDULED, time = null, behaviorId = "b"),
            ),
            mapOf("a" to a, "b" to b),
        )
        assertTrue(conflicts.isEmpty())
    }
}
