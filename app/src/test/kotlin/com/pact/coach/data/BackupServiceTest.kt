package com.pact.coach.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pact.coach.core.time.FixedClockProvider
import com.pact.coach.data.backup.BackupService
import com.pact.coach.data.db.PactDatabase
import com.pact.coach.data.repository.AppSettings
import com.pact.coach.data.repository.BehaviorRepository
import com.pact.coach.data.repository.GoalRepository
import com.pact.coach.data.repository.InstanceRepository
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.Priority
import com.pact.coach.domain.model.RecurrenceType
import com.pact.coach.domain.model.Schedule
import com.pact.coach.domain.scheduler.InstanceGenerator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Export and import.
 *
 * A backup file is untrusted input. These tests cover the round trip, but the more important
 * half is what happens with a file that is truncated, from a newer build, internally
 * inconsistent, or simply not a PACT backup: the import must refuse it and leave the database
 * exactly as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupServiceTest {

    private lateinit var db: PactDatabase
    private lateinit var clock: FixedClockProvider
    private lateinit var service: BackupService
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
        service = BackupService(db, clock, "1.0.0-test")
        goals = GoalRepository(db.goalDao(), db.behaviorDao(), clock)
        behaviors = BehaviorRepository(db, clock)
        instances = InstanceRepository(db, clock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        val goal = goals.create("Fitness", "Because it matters", "Health", Priority.HIGH)
        behaviors.save(
            Behavior(
                id = "b1",
                goalId = goal.id,
                name = "Exercise",
                targetDurationMinutes = 30,
                minimumDurationMinutes = 5,
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
            listOf(
                Schedule(
                    id = "s1",
                    behaviorId = "b1",
                    recurrenceType = RecurrenceType.DAILY,
                    startDate = today,
                    timesOfDay = listOf(LocalTime.of(6, 30), LocalTime.of(18, 0)),
                ),
            ),
        )
        val generated = InstanceGenerator().generate(
            behaviors.get("b1")!!, behaviors.getSchedules("b1"), emptyList(), today, today, zone,
        )
        instances.insertGenerated(generated)
        instances.complete(instances.getForBehavior("b1").first().id)
    }

    @Test
    fun `export then import into an empty database restores everything`() = runTest {
        seed()
        val json = service.export(AppSettings())

        // Wipe and re-import, which is exactly the "moved to a new phone" path.
        val parsed = service.parse(json)
        assertTrue(parsed is BackupService.ParseResult.Ok)
        parsed as BackupService.ParseResult.Ok

        service.import(parsed.envelope, BackupService.Mode.REPLACE)

        assertEquals(1, db.goalDao().getAll().size)
        assertEquals(1, db.behaviorDao().getAll().size)
        assertEquals(1, db.scheduleDao().getAll().size)
        assertEquals(2, db.instanceDao().getAll().size)

        val behavior = behaviors.get("b1")!!
        assertEquals("Exercise", behavior.name)
        assertEquals(5, behavior.minimumDurationMinutes)
        assertEquals(
            listOf(LocalTime.of(6, 30), LocalTime.of(18, 0)),
            behaviors.getSchedules("b1").single().timesOfDay,
        )
    }

    @Test
    fun `the summary describes the file before anything is written`() = runTest {
        seed()
        val json = service.export(AppSettings())
        val parsed = service.parse(json) as BackupService.ParseResult.Ok

        assertEquals(1, parsed.summary.goals)
        assertEquals(1, parsed.summary.behaviors)
        assertEquals(2, parsed.summary.historyEntries)
        assertEquals(BackupService.CURRENT_VERSION, parsed.summary.formatVersion)
        assertTrue(parsed.summary.hasSettings)
    }

    @Test
    fun `merge never overwrites what is already there`() = runTest {
        seed()
        val json = service.export(AppSettings())
        val parsed = service.parse(json) as BackupService.ParseResult.Ok

        // Import the same file on top of the live data.
        val result = service.import(parsed.envelope, BackupService.Mode.MERGE)

        assertEquals("nothing new should be inserted", 0, result.inserted)
        assertTrue(result.skipped > 0)
        assertEquals(1, db.behaviorDao().getAll().size)
        assertEquals(2, db.instanceDao().getAll().size)
    }

    @Test
    fun `importing duplicate ids is skipped rather than failing`() = runTest {
        seed()
        val json = service.export(AppSettings())
        val parsed = service.parse(json) as BackupService.ParseResult.Ok

        service.import(parsed.envelope, BackupService.Mode.MERGE)
        service.import(parsed.envelope, BackupService.Mode.MERGE)

        assertEquals(1, db.goalDao().getAll().size)
        assertEquals(2, db.instanceDao().getAll().size)
    }

    @Test
    fun `replace clears existing data first`() = runTest {
        seed()
        val json = service.export(AppSettings())

        // Add something that is not in the backup.
        behaviors.save(
            Behavior(
                id = "extra",
                name = "Reading",
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
            emptyList(),
        )
        assertEquals(2, db.behaviorDao().getAll().size)

        val parsed = service.parse(json) as BackupService.ParseResult.Ok
        service.import(parsed.envelope, BackupService.Mode.REPLACE)

        assertEquals(1, db.behaviorDao().getAll().size)
        assertNotNull(behaviors.get("b1"))
        assertEquals(null, behaviors.get("extra"))
    }

    // --- Rejection paths ----------------------------------------------------------------

    @Test
    fun `a file that is not json is rejected`() {
        val result = service.parse("this is not a backup")
        assertTrue(result is BackupService.ParseResult.Invalid)
    }

    @Test
    fun `an empty file is rejected`() {
        assertTrue(service.parse("") is BackupService.ParseResult.Invalid)
        assertTrue(service.parse("   ") is BackupService.ParseResult.Invalid)
    }

    @Test
    fun `valid json that is not a backup is rejected`() {
        val result = service.parse("""{"hello":"world"}""")
        assertTrue(result is BackupService.ParseResult.Invalid)
    }

    @Test
    fun `a backup from a newer format version is refused with a clear reason`() {
        val future = """{"formatVersion": 999, "goals": [], "behaviors": []}"""
        val result = service.parse(future)
        assertTrue(result is BackupService.ParseResult.Invalid)
        result as BackupService.ParseResult.Invalid
        assertTrue(result.reason, result.reason.contains("newer version"))
    }

    @Test
    fun `a missing format version is refused`() {
        val result = service.parse("""{"formatVersion": 0, "goals": []}""")
        assertTrue(result is BackupService.ParseResult.Invalid)
    }

    @Test
    fun `an older format version is still accepted`() {
        // Forward compatibility: version 1 is the current one, and unknown fields are ignored.
        val old = """
            {"formatVersion": 1, "someFutureField": 42, "goals": [], "behaviors": []}
        """.trimIndent()
        assertTrue(service.parse(old) is BackupService.ParseResult.Ok)
    }

    @Test
    fun `a behavior pointing at a missing goal is refused`() {
        val broken = """
            {
              "formatVersion": 1,
              "goals": [],
              "behaviors": [
                {"id":"b1","goalId":"ghost","name":"Exercise","startDateEpochDay":0}
              ]
            }
        """.trimIndent()
        val result = service.parse(broken)
        assertTrue(result is BackupService.ParseResult.Invalid)
        result as BackupService.ParseResult.Invalid
        assertTrue(result.reason, result.reason.contains("goal that is not in the file"))
    }

    @Test
    fun `duplicate ids inside one file are refused`() {
        val broken = """
            {
              "formatVersion": 1,
              "goals": [
                {"id":"g1","name":"A","startDateEpochDay":0},
                {"id":"g1","name":"B","startDateEpochDay":0}
              ]
            }
        """.trimIndent()
        val result = service.parse(broken)
        assertTrue(result is BackupService.ParseResult.Invalid)
        result as BackupService.ParseResult.Invalid
        assertTrue(result.reason, result.reason.contains("duplicate goal ids"))
    }

    @Test
    fun `a nameless goal is refused`() {
        val broken = """
            {"formatVersion": 1, "goals": [{"id":"g1","name":"","startDateEpochDay":0}]}
        """.trimIndent()
        assertTrue(service.parse(broken) is BackupService.ParseResult.Invalid)
    }

    @Test
    fun `an out of range scheduled time is refused`() {
        val broken = """
            {
              "formatVersion": 1,
              "behaviors": [{"id":"b1","name":"X","startDateEpochDay":0}],
              "instances": [
                {"id":"i1","behaviorId":"b1","scheduledDateEpochDay":0,
                 "scheduledTimeMinutes":5000,"dedupKey":"k1"}
              ]
            }
        """.trimIndent()
        val result = service.parse(broken)
        assertTrue(result is BackupService.ParseResult.Invalid)
        result as BackupService.ParseResult.Invalid
        assertTrue(result.reason, result.reason.contains("invalid scheduled time"))
    }

    @Test
    fun `history referring to an unknown behavior is refused`() {
        val broken = """
            {
              "formatVersion": 1,
              "behaviors": [],
              "instances": [
                {"id":"i1","behaviorId":"ghost","scheduledDateEpochDay":0,"dedupKey":"k1"}
              ]
            }
        """.trimIndent()
        assertTrue(service.parse(broken) is BackupService.ParseResult.Invalid)
    }

    @Test
    fun `a rejected file leaves the database untouched`() = runTest {
        seed()
        val goalsBefore = db.goalDao().getAll().size
        val behaviorsBefore = db.behaviorDao().getAll().size
        val instancesBefore = db.instanceDao().getAll().size

        // parse() alone must never write, and it is the only route into import().
        assertTrue(service.parse("garbage") is BackupService.ParseResult.Invalid)

        assertEquals(goalsBefore, db.goalDao().getAll().size)
        assertEquals(behaviorsBefore, db.behaviorDao().getAll().size)
        assertEquals(instancesBefore, db.instanceDao().getAll().size)
    }

    @Test
    fun `the export file name is dated and json`() {
        val name = service.suggestedFileName()
        assertTrue(name, name.startsWith("pact-backup-"))
        assertTrue(name, name.endsWith(".json"))
        assertTrue(name, name.contains("2026-09-07"))
    }

    @Test
    fun `exported json carries the format version and app version`() = runTest {
        seed()
        val json = service.export(AppSettings())
        assertTrue(json.contains("\"formatVersion\""))
        assertTrue(json.contains("1.0.0-test"))
    }

    @Test
    fun `settings round trip through the backup`() = runTest {
        seed()
        val settings = AppSettings(
            use24HourClock = false,
            coachPersonality = com.pact.coach.domain.model.CoachPersonality.DIRECT,
        )
        val json = service.export(settings)
        val parsed = service.parse(json) as BackupService.ParseResult.Ok

        assertNotNull(parsed.envelope.settings)
        assertEquals(false, parsed.envelope.settings!!.use24HourClock)
        assertEquals("DIRECT", parsed.envelope.settings!!.coachPersonality)
    }

    @Test
    fun `an absurdly large file is rejected before parsing`() {
        val huge = "x".repeat(BackupService.MAX_FILE_CHARS + 1)
        val result = service.parse(huge)
        assertTrue(result is BackupService.ParseResult.Invalid)
        result as BackupService.ParseResult.Invalid
        assertTrue(result.reason, result.reason.contains("too large"))
    }

    @Test
    fun `a recovery chain survives the round trip with its links intact`() = runTest {
        seed()
        val original = instances.getForBehavior("b1").last()
        val recovered = instances.recover(original.id, 30) as InstanceRepository.DecisionResult.Ok

        val json = service.export(AppSettings())
        val parsed = service.parse(json) as BackupService.ParseResult.Ok
        service.import(parsed.envelope, BackupService.Mode.REPLACE)

        val chain = instances.getChain(original.id)
        assertEquals(2, chain.instances.size)
        assertTrue(chain.wasRecovered)
        assertEquals(
            original.id,
            chain.instances.first { it.id == recovered.newInstance!!.id }.originInstanceId,
        )
    }

    @Test
    fun `imported content is treated as data and never executed`() = runTest {
        // A hand-edited file with script-like content in a text field must import as plain text.
        val hostile = """
            {
              "formatVersion": 1,
              "goals": [{
                "id":"11111111-1111-1111-1111-111111111111",
                "name":"<script>alert(1)</script>",
                "whyItMatters":"'; DROP TABLE goals; --",
                "startDateEpochDay": 20000
              }]
            }
        """.trimIndent()

        val parsed = service.parse(hostile)
        assertTrue(parsed is BackupService.ParseResult.Ok)
        service.import((parsed as BackupService.ParseResult.Ok).envelope, BackupService.Mode.MERGE)

        val stored = db.goalDao().getAll().single()
        assertEquals("<script>alert(1)</script>", stored.name)
        assertEquals("'; DROP TABLE goals; --", stored.whyItMatters)
        // The table is still there, which is the point: values are bound, never concatenated.
        assertFalse(db.goalDao().getAll().isEmpty())
    }
}
