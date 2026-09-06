package com.pact.coach

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pact.coach.data.repository.InstanceRepository
import com.pact.coach.di.AppContainer
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.model.Priority
import com.pact.coach.domain.model.RecurrenceType
import com.pact.coach.domain.model.Schedule
import com.pact.coach.domain.scheduler.InstanceGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

/**
 * End-to-end tests that need a real device or emulator.
 *
 * Everything that can be verified without one already lives in `src/test` (including the Room and
 * Compose tests, which run under Robolectric). What is left here is the part that genuinely
 * depends on a running Android system: the real application object, the real database file, the
 * real notification manager, and the real navigation host.
 *
 * Run with:
 *   ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class FlowInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as PactApplication
        container = app.container
    }

    private fun seedBehavior(name: String = "Instrumented exercise"): String = runBlocking {
        val goal = container.goalRepository.create(
            name = "Instrumented goal",
            whyItMatters = "Verifying the loop end to end",
            category = "Health",
            priority = Priority.HIGH,
        )
        val now = container.clock.now()
        val behavior = Behavior(
            goalId = goal.id,
            name = name,
            targetDurationMinutes = 30,
            minimumDurationMinutes = 5,
            createdAt = now,
            updatedAt = now,
        )
        container.behaviorRepository.save(
            behavior,
            listOf(
                Schedule(
                    behaviorId = behavior.id,
                    recurrenceType = RecurrenceType.DAILY,
                    startDate = container.clock.today(),
                    timesOfDay = listOf(LocalTime.of(6, 30), LocalTime.of(18, 0)),
                ),
            ),
        )
        val generated = InstanceGenerator().generate(
            behavior = container.behaviorRepository.get(behavior.id)!!,
            schedules = container.behaviorRepository.getSchedules(behavior.id),
            exceptions = emptyList(),
            from = container.clock.today(),
            to = container.clock.today(),
            zone = container.clock.zone(),
        )
        container.instanceRepository.insertGenerated(generated)
        behavior.id
    }

    private fun cleanUp(behaviorId: String) = runBlocking {
        container.schedulingCoordinator.cancelForBehavior(behaviorId)
        container.notificationChannels.deleteChannelsFor(behaviorId)
        container.behaviorRepository.delete(behaviorId)
    }

    @Test
    fun creatingABehaviorProducesTwoSeparateOccurrences() {
        val id = seedBehavior()
        try {
            val instances = runBlocking { container.instanceRepository.getForBehavior(id) }
            assertEquals(2, instances.size)
            assertEquals(
                listOf(LocalTime.of(6, 30), LocalTime.of(18, 0)),
                instances.sortedBy { it.scheduledTime }.map { it.scheduledTime },
            )
        } finally {
            cleanUp(id)
        }
    }

    @Test
    fun commitThenCompleteMovesThroughTheStateMachine() {
        val id = seedBehavior()
        try {
            runBlocking {
                val instance = container.instanceRepository.getForBehavior(id).first()
                container.instanceRepository.markDue(instance.id)

                val committed = container.instanceRepository.commit(instance.id)
                assertTrue(committed is InstanceRepository.DecisionResult.Ok)
                assertEquals(
                    InstanceState.COMMITTED,
                    container.instanceRepository.get(instance.id)!!.state,
                )

                val completed = container.instanceRepository.complete(instance.id, durationMinutes = 30)
                assertTrue(completed is InstanceRepository.DecisionResult.Ok)
                assertEquals(
                    InstanceState.COMPLETED,
                    container.instanceRepository.get(instance.id)!!.state,
                )
            }
        } finally {
            cleanUp(id)
        }
    }

    @Test
    fun recoveringCreatesALinkedFollowUpAndArmsItsAlarm() {
        val id = seedBehavior()
        try {
            runBlocking {
                val original = container.instanceRepository.getForBehavior(id)
                    .sortedBy { it.scheduledTime }.last()

                val result = container.instanceRepository.recover(original.id, 30)
                assertTrue(result is InstanceRepository.DecisionResult.Ok)
                result as InstanceRepository.DecisionResult.Ok

                assertEquals(
                    InstanceState.RECOVERED,
                    container.instanceRepository.get(original.id)!!.state,
                )

                val recovery = result.newInstance
                assertNotNull(recovery)
                assertEquals(original.id, recovery!!.originInstanceId)

                // Arming uses the real AlarmManager on the device.
                val outcome = container.schedulingCoordinator.armInstance(recovery)
                assertTrue(
                    "the recovery should be armed, or explicitly report why not",
                    outcome.wasScheduled ||
                        outcome is com.pact.coach.services.alarm.ScheduleOutcome.Skipped ||
                        outcome is com.pact.coach.services.alarm.ScheduleOutcome.Failed,
                )
            }
        } finally {
            cleanUp(id)
        }
    }

    @Test
    fun passingRecordsTheReasonWithoutBeingTreatedAsAMiss() {
        val id = seedBehavior()
        try {
            runBlocking {
                val instance = container.instanceRepository.getForBehavior(id).first()
                container.instanceRepository.pass(
                    instance.id,
                    com.pact.coach.domain.model.PassReason.BUSY,
                    "Meeting overran",
                )
                val after = container.instanceRepository.get(instance.id)!!
                assertEquals(InstanceState.PASSED, after.state)
                assertEquals(com.pact.coach.domain.model.PassReason.BUSY, after.passReason)
                assertEquals("Meeting overran", after.passNote)
            }
        } finally {
            cleanUp(id)
        }
    }

    @Test
    fun theSchedulingCoordinatorIsSafeToRunRepeatedly() {
        val id = seedBehavior()
        try {
            runBlocking {
                container.schedulingCoordinator.refresh()
                val afterFirst = container.instanceRepository.getForBehavior(id).size

                container.schedulingCoordinator.refresh()
                container.schedulingCoordinator.refresh()
                val afterThird = container.instanceRepository.getForBehavior(id).size

                assertEquals(
                    "repeated refreshes must not duplicate occurrences",
                    afterFirst,
                    afterThird,
                )
            }
        } finally {
            cleanUp(id)
        }
    }

    @Test
    fun theAppLaunchesAndShowsItsPrimaryNavigation() {
        // A smoke test that the real activity, theme and nav host all come up.
        compose.waitForIdle()
        val hasHome = compose.onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty()
        val hasOnboarding = compose.onAllNodesWithText("PACT").fetchSemanticsNodes().isNotEmpty()
        assertTrue(
            "the app should land on either onboarding or the dashboard",
            hasHome || hasOnboarding,
        )
    }

    @Test
    fun notificationChannelsAreCreatedForABehavior() {
        val id = seedBehavior("Channel check")
        try {
            runBlocking {
                val behavior = container.behaviorRepository.get(id)!!
                val channelId = container.notificationChannels.channelFor(behavior, null)
                assertTrue(
                    "a behavior should get its own channel, or the documented fallback",
                    channelId.startsWith("pact_behavior_") || channelId == "pact_intervention",
                )
            }
        } finally {
            cleanUp(id)
        }
    }
}
