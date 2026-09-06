package com.pact.coach.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pact.coach.core.time.FixedClockProvider
import com.pact.coach.data.db.PactDatabase
import com.pact.coach.data.mapper.toDomain
import com.pact.coach.data.mapper.toEntity
import com.pact.coach.data.repository.BehaviorRepository
import com.pact.coach.data.repository.GoalRepository
import com.pact.coach.data.repository.InstanceRepository
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.Goal
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.model.PassReason
import com.pact.coach.domain.model.Priority
import com.pact.coach.domain.model.RecurrenceType
import com.pact.coach.domain.model.Schedule
import com.pact.coach.domain.scheduler.InstanceGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Room and repository tests.
 *
 * These run on Robolectric rather than a device so they execute in a normal `test` run: Room
 * works against real SQLite under Robolectric, which means the schema, the indices and the
 * transactions are genuinely exercised rather than mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DatabaseTest {

    private lateinit var db: PactDatabase
    private lateinit var clock: FixedClockProvider
    private lateinit var goals: GoalRepository
    private lateinit var behaviors: BehaviorRepository
    private lateinit var instances: InstanceRepository

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 7)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PactDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        clock = FixedClockProvider(Instant.parse("2026-09-07T08:00:00Z"), zone)
        goals = GoalRepository(db.goalDao(), db.behaviorDao(), clock)
        behaviors = BehaviorRepository(db, clock)
        instances = InstanceRepository(db, clock)
    }

    @After
    fun tearDown() = db.close()

    // --- Helpers ------------------------------------------------------------------------

    private fun newBehavior(id: String = "b1", name: String = "Exercise") = Behavior(
        id = id,
        name = name,
        targetDurationMinutes = 30,
        minimumDurationMinutes = 5,
        createdAt = clock.now(),
        updatedAt = clock.now(),
    )

    private fun newSchedule(
        behaviorId: String = "b1",
        times: List<LocalTime> = listOf(LocalTime.of(6, 30), LocalTime.of(18, 0)),
    ) = Schedule(
        id = "s-$behaviorId",
        behaviorId = behaviorId,
        recurrenceType = RecurrenceType.DAILY,
        startDate = today,
        timesOfDay = times,
    )

    private suspend fun seedInstances(behaviorId: String = "b1"): List<BehaviorInstance> {
        val generated = InstanceGenerator().generate(
            behavior = behaviors.get(behaviorId)!!,
            schedules = behaviors.getSchedules(behaviorId),
            exceptions = emptyList(),
            from = today,
            to = today,
            zone = zone,
        )
        instances.insertGenerated(generated)
        return instances.getForBehavior(behaviorId)
    }

    // --- Round trip ---------------------------------------------------------------------

    @Test
    fun `goal round trips through the database intact`() = runTest {
        val goal = Goal(
            name = "Improve my fitness",
            whyItMatters = "I want to keep up with my kids",
            category = "Health",
            priority = Priority.HIGH,
            startDate = today,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )
        goals.save(goal)

        val loaded = goals.get(goal.id)
        assertNotNull(loaded)
        assertEquals("Improve my fitness", loaded!!.name)
        assertEquals("I want to keep up with my kids", loaded.whyItMatters)
        assertEquals(Priority.HIGH, loaded.priority)
        assertEquals(today, loaded.startDate)
    }

    @Test
    fun `behavior and schedule save together and read back`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule()))

        val loaded = behaviors.get("b1")
        assertNotNull(loaded)
        assertEquals(30, loaded!!.targetDurationMinutes)
        assertEquals(5, loaded.minimumDurationMinutes)

        val schedules = behaviors.getSchedules("b1")
        assertEquals(1, schedules.size)
        assertEquals(listOf(LocalTime.of(6, 30), LocalTime.of(18, 0)), schedules.single().timesOfDay)
    }

    @Test
    fun `times of day survive the csv encoding in sorted order`() = runTest {
        behaviors.save(
            newBehavior(),
            listOf(newSchedule(times = listOf(LocalTime.of(18, 0), LocalTime.of(6, 30), LocalTime.of(12, 15)))),
        )
        assertEquals(
            listOf(LocalTime.of(6, 30), LocalTime.of(12, 15), LocalTime.of(18, 0)),
            behaviors.getSchedules("b1").single().timesOfDay,
        )
    }

    // --- Idempotent generation ----------------------------------------------------------

    @Test
    fun `regenerating the same window does not create duplicate occurrences`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule()))
        val generator = InstanceGenerator()

        val batch = generator.generate(
            behaviors.get("b1")!!, behaviors.getSchedules("b1"), emptyList(), today, today, zone,
        )

        val firstInsert = instances.insertGenerated(batch)
        assertEquals(2, firstInsert)

        // Generate the same window again, exactly as the coordinator would on every refresh.
        val batchAgain = generator.generate(
            behaviors.get("b1")!!, behaviors.getSchedules("b1"), emptyList(), today, today, zone,
        )
        val secondInsert = instances.insertGenerated(batchAgain)

        assertEquals("the unique dedupKey index must reject the repeats", 0, secondInsert)
        assertEquals(2, instances.getForBehavior("b1").size)
    }

    @Test
    fun `each occurrence gets a unique alarm request code`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule()))
        val rows = seedInstances()
        val codes = rows.map { it.alarmRequestCode }
        assertEquals(codes.size, codes.distinct().size)
        assertTrue(codes.all { it > 0 })
    }

    @Test
    fun `two daily occurrences keep separate history`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule()))
        val rows = seedInstances().sortedBy { it.scheduledTime }

        instances.complete(rows[0].id)
        instances.pass(rows[1].id, PassReason.TIRED)

        val after = instances.getForBehavior("b1").sortedBy { it.scheduledTime }
        assertEquals(InstanceState.COMPLETED, after[0].state)
        assertEquals(InstanceState.PASSED, after[1].state)
        assertEquals(PassReason.TIRED, after[1].passReason)
    }

    // --- Decisions ----------------------------------------------------------------------

    @Test
    fun `commit then complete records timestamps and quality`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule(times = listOf(LocalTime.of(6, 30)))))
        val instance = seedInstances().single()

        instances.markDue(instance.id)
        clock.advanceMinutes(2)
        val committed = instances.commit(instance.id)
        assertTrue(committed is InstanceRepository.DecisionResult.Ok)

        clock.advanceMinutes(30)
        val completed = instances.complete(instance.id, durationMinutes = 30)
        assertTrue(completed is InstanceRepository.DecisionResult.Ok)

        val final = instances.get(instance.id)!!
        assertEquals(InstanceState.COMPLETED, final.state)
        assertEquals(30, final.actualDurationMinutes)
        assertNotNull(final.completedAtMillis)
        assertNotNull(final.respondedAtMillis)
        assertEquals(
            com.pact.coach.domain.model.CompletionQuality.TARGET_COMPLETED,
            final.completionQuality,
        )
    }

    @Test
    fun `completing only the minimum is recorded distinctly`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule(times = listOf(LocalTime.of(6, 30)))))
        val instance = seedInstances().single()

        instances.complete(instance.id, minimumOnly = true)

        val final = instances.get(instance.id)!!
        assertEquals(InstanceState.COMPLETED, final.state)
        assertEquals(
            com.pact.coach.domain.model.CompletionQuality.MINIMUM_COMPLETED,
            final.completionQuality,
        )
    }

    @Test
    fun `recovery preserves the original and links the successor`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule(times = listOf(LocalTime.of(18, 0)))))
        val original = seedInstances().single()

        val result = instances.recover(original.id, 30)
        assertTrue(result is InstanceRepository.DecisionResult.Ok)
        result as InstanceRepository.DecisionResult.Ok

        val closed = instances.get(original.id)!!
        assertEquals(InstanceState.RECOVERED, closed.state)
        assertEquals("the original plan must not be rewritten", LocalTime.of(18, 0), closed.scheduledTime)

        val recovery = result.newInstance!!
        assertEquals(original.id, recovery.originInstanceId)
        assertEquals(original.id, recovery.rootInstanceId)
        assertEquals(1, recovery.recoveryDepth)

        val chain = instances.getChain(original.id)
        assertEquals(2, chain.instances.size)
        assertTrue(chain.wasRecovered)
    }

    @Test
    fun `a recovery chain that completes reads as one completion`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule(times = listOf(LocalTime.of(18, 0)))))
        val original = seedInstances().single()

        val recovered = instances.recover(original.id, 30) as InstanceRepository.DecisionResult.Ok
        clock.advanceMinutes(35)
        instances.complete(recovered.newInstance!!.id)

        val chain = instances.getChain(original.id)
        assertEquals(InstanceState.COMPLETED, chain.outcome)
        assertTrue(chain.isResolved)
    }

    @Test
    fun `recovery is refused past the configured limit`() = runTest {
        behaviors.save(
            newBehavior().copy(maxRecoveriesPerInstance = 1),
            listOf(newSchedule(times = listOf(LocalTime.of(18, 0)))),
        )
        val original = seedInstances().single()

        val first = instances.recover(original.id, 30) as InstanceRepository.DecisionResult.Ok
        val second = instances.recover(first.newInstance!!.id, 30)
        assertTrue(second is InstanceRepository.DecisionResult.Failed)
    }

    @Test
    fun `passing without a reason is allowed unless the behavior requires one`() = runTest {
        behaviors.save(
            newBehavior().copy(requirePassReason = true),
            listOf(newSchedule(times = listOf(LocalTime.of(6, 30)))),
        )
        val instance = seedInstances().single()

        val refused = instances.pass(instance.id, reason = null)
        assertTrue(refused is InstanceRepository.DecisionResult.Failed)

        val accepted = instances.pass(instance.id, reason = PassReason.BUSY)
        assertTrue(accepted is InstanceRepository.DecisionResult.Ok)
    }

    @Test
    fun `passing and then doing it anyway is allowed`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule(times = listOf(LocalTime.of(6, 30)))))
        val instance = seedInstances().single()

        instances.pass(instance.id, PassReason.FORGOT)
        val result = instances.complete(instance.id)

        assertTrue(result is InstanceRepository.DecisionResult.Ok)
        assertEquals(InstanceState.COMPLETED, instances.get(instance.id)!!.state)
    }

    @Test
    fun `an impossible transition is refused rather than corrupting the row`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule(times = listOf(LocalTime.of(6, 30)))))
        val instance = seedInstances().single()

        instances.complete(instance.id)
        instances.cancel(instance.id)
        // CANCELLED is a dead end.
        val result = instances.commit(instance.id)
        assertTrue(result is InstanceRepository.DecisionResult.Failed)
        assertEquals(InstanceState.CANCELLED, instances.get(instance.id)!!.state)
    }

    @Test
    fun `every decision writes an audit event in the same transaction`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule(times = listOf(LocalTime.of(6, 30)))))
        val instance = seedInstances().single()

        instances.markDue(instance.id)
        instances.commit(instance.id)
        instances.complete(instance.id)

        val events = db.eventDao().getForInstance(instance.id)
        val types = events.map { it.type }
        assertTrue(types.contains("FIRED"))
        assertTrue(types.contains("COMMIT"))
        assertTrue(types.contains("COMPLETE"))
    }

    // --- Sweeps -------------------------------------------------------------------------

    @Test
    fun `the missed sweep only closes unanswered occurrences`() = runTest {
        behaviors.save(
            newBehavior().copy(missWindowMinutes = 60),
            listOf(newSchedule(times = listOf(LocalTime.of(6, 30), LocalTime.of(7, 0)))),
        )
        val rows = seedInstances().sortedBy { it.scheduledTime }
        instances.complete(rows[0].id)

        // Move well past both slots plus the grace window.
        clock.instant = Instant.parse("2026-09-07T12:00:00Z")
        val missed = instances.sweepMissed()

        assertEquals(listOf(rows[1].id), missed)
        assertEquals(InstanceState.COMPLETED, instances.get(rows[0].id)!!.state)
        assertEquals(InstanceState.MISSED, instances.get(rows[1].id)!!.state)
    }

    @Test
    fun `the sweep respects the grace window`() = runTest {
        behaviors.save(
            newBehavior().copy(missWindowMinutes = 120),
            listOf(newSchedule(times = listOf(LocalTime.of(6, 30)))),
        )
        val instance = seedInstances().single()

        // 07:30 is only an hour past a 06:30 slot with a two-hour window.
        clock.instant = Instant.parse("2026-09-07T07:30:00Z")
        assertTrue(instances.sweepMissed().isEmpty())
        assertEquals(InstanceState.SCHEDULED, instances.get(instance.id)!!.state)
    }

    @Test
    fun `the sweep never overwrites a recorded decision`() = runTest {
        behaviors.save(
            newBehavior().copy(missWindowMinutes = 30),
            listOf(newSchedule(times = listOf(LocalTime.of(6, 30)))),
        )
        val instance = seedInstances().single()
        instances.pass(instance.id, PassReason.TIRED)

        clock.instant = Instant.parse("2026-09-08T12:00:00Z")
        instances.sweepMissed()

        assertEquals(InstanceState.PASSED, instances.get(instance.id)!!.state)
    }

    // --- Time zone ----------------------------------------------------------------------

    @Test
    fun `a time zone change keeps the wall clock time and moves the absolute instant`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule(times = listOf(LocalTime.of(6, 30)))))
        // Generate for tomorrow so the row is genuinely in the future.
        val generated = InstanceGenerator().generate(
            behaviors.get("b1")!!, behaviors.getSchedules("b1"), emptyList(),
            today.plusDays(1), today.plusDays(1), zone,
        )
        instances.insertGenerated(generated)

        val before = instances.getForBehavior("b1").single()
        val newZone = ZoneId.of("America/New_York")

        clock.zoneId = newZone
        val changed = instances.rebaseFutureInstancesToZone(newZone)
        assertTrue(changed)

        val after = instances.get(before.id)!!
        assertEquals("the user's chosen time must not move", LocalTime.of(6, 30), after.scheduledTime)
        assertEquals(newZone.id, after.zoneId)
        assertTrue(
            "the absolute instant must shift with the offset",
            after.scheduledAtUtcMillis != before.scheduledAtUtcMillis,
        )
    }

    // --- Deletion semantics -------------------------------------------------------------

    @Test
    fun `deleting a goal detaches its behaviors instead of destroying their history`() = runTest {
        val goal = goals.create("Fitness", "Because it matters", "Health", Priority.HIGH)
        behaviors.save(newBehavior().copy(goalId = goal.id), listOf(newSchedule()))
        seedInstances()

        goals.delete(goal.id)

        val behavior = behaviors.get("b1")
        assertNotNull("the behavior must survive", behavior)
        assertNull("but it should no longer point at a goal", behavior!!.goalId)
        assertEquals(2, instances.getForBehavior("b1").size)
    }

    @Test
    fun `deleting a behavior cascades to its schedules and history`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule()))
        seedInstances()

        behaviors.delete("b1")

        assertNull(behaviors.get("b1"))
        assertTrue(behaviors.getSchedules("b1").isEmpty())
        assertTrue(instances.getForBehavior("b1").isEmpty())
    }

    @Test
    fun `editing a schedule drops stale future occurrences but keeps history`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule()))
        val rows = seedInstances().sortedBy { it.scheduledTime }
        instances.complete(rows[0].id)

        // Re-save with a different time; the untouched future row should be discarded.
        behaviors.save(newBehavior(), listOf(newSchedule(times = listOf(LocalTime.of(20, 0)))))

        val remaining = instances.getForBehavior("b1")
        assertTrue("the completed occurrence must survive", remaining.any { it.id == rows[0].id })
        assertFalse("the stale future occurrence must be gone", remaining.any { it.id == rows[1].id })
    }

    @Test
    fun `pausing a behavior clears its future occurrences`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule()))
        seedInstances()

        behaviors.setActive("b1", false)

        assertFalse(behaviors.get("b1")!!.isActive)
        assertTrue(instances.getForBehavior("b1").none { it.state == InstanceState.SCHEDULED })
    }

    @Test
    fun `changing a sound bumps the channel version so a fresh channel is created`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule()))
        assertEquals(1, behaviors.get("b1")!!.channelVersion)

        behaviors.save(
            behaviors.get("b1")!!.copy(soundUri = "content://media/1"),
            listOf(newSchedule()),
        )
        assertEquals(2, behaviors.get("b1")!!.channelVersion)
    }

    @Test
    fun `applying a coaching suggestion moves exactly one time`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule()))

        val moved = behaviors.moveScheduleTime("s-b1", LocalTime.of(18, 0), LocalTime.of(20, 0))
        assertTrue(moved)

        assertEquals(
            listOf(LocalTime.of(6, 30), LocalTime.of(20, 0)),
            behaviors.getSchedules("b1").single().timesOfDay,
        )
    }

    @Test
    fun `applying a suggestion for a time that no longer exists fails cleanly`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule()))
        assertFalse(behaviors.moveScheduleTime("s-b1", LocalTime.of(11, 11), LocalTime.of(20, 0)))
    }

    @Test
    fun `flows emit updates when the underlying rows change`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule()))
        seedInstances()

        val forToday = instances.observeForDay(today).first()
        assertEquals(2, forToday.size)

        val all = behaviors.observeAll().first()
        assertEquals(1, all.size)
    }

    @Test
    fun `mapping an unknown enum value falls back rather than throwing`() = runTest {
        behaviors.save(newBehavior(), listOf(newSchedule()))
        // Simulate a row written by a newer build with a state this version does not know.
        val entity = db.instanceDao().getAll().firstOrNull()
            ?: run {
                seedInstances()
                db.instanceDao().getAll().first()
            }
        val corrupted = entity.copy(state = "SOMETHING_NEW", completionQuality = "ALIEN")
        db.instanceDao().update(corrupted)

        val domain = db.instanceDao().getById(entity.id)!!.toDomain()
        assertEquals(InstanceState.SCHEDULED, domain.state)
        assertNull(domain.completionQuality)
    }
}
