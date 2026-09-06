package com.pact.coach.di

import android.content.Context
import com.pact.coach.core.time.ClockProvider
import com.pact.coach.core.time.SystemClockProvider
import com.pact.coach.data.backup.BackupService
import com.pact.coach.data.db.PactDatabase
import com.pact.coach.data.repository.AppSettings
import com.pact.coach.data.repository.BehaviorRepository
import com.pact.coach.data.repository.GoalRepository
import com.pact.coach.data.repository.InsightsRepository
import com.pact.coach.data.repository.InstanceRepository
import com.pact.coach.data.repository.SettingsRepository
import com.pact.coach.domain.instruction.InstructionResolver
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.BehaviorTemplate
import com.pact.coach.domain.model.DayMask
import com.pact.coach.domain.model.EnforcementLevel
import com.pact.coach.domain.model.Measurement
import com.pact.coach.domain.model.RecurrenceType
import com.pact.coach.domain.model.ResolvedInstruction
import com.pact.coach.domain.scheduler.ConflictDetector
import com.pact.coach.services.SchedulingCoordinator
import com.pact.coach.services.alarm.AlarmScheduler
import com.pact.coach.services.alarm.ExactAlarmCapability
import com.pact.coach.services.notification.InterventionNotifier
import com.pact.coach.services.notification.NotificationChannels
import kotlinx.coroutines.flow.first
import java.time.LocalTime

/**
 * The whole object graph, wired by hand.
 *
 * Everything is lazy so that a broadcast receiver waking a cold process only pays for what it
 * actually touches, and everything is a singleton for the process lifetime.
 */
class AppContainer(
    private val context: Context,
    val clock: ClockProvider = SystemClockProvider(),
) {

    val database: PactDatabase by lazy { PactDatabase.get(context) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(context) }

    val goalRepository: GoalRepository by lazy {
        GoalRepository(database.goalDao(), database.behaviorDao(), clock)
    }

    val behaviorRepository: BehaviorRepository by lazy { BehaviorRepository(database, clock) }

    val instanceRepository: InstanceRepository by lazy { InstanceRepository(database, clock) }

    val insightsRepository: InsightsRepository by lazy { InsightsRepository(database, clock) }

    val notificationChannels: NotificationChannels by lazy { NotificationChannels(context) }

    val notifier: InterventionNotifier by lazy {
        InterventionNotifier(context, notificationChannels)
    }

    val exactAlarmCapability: ExactAlarmCapability by lazy { ExactAlarmCapability(context) }

    val alarmScheduler: AlarmScheduler by lazy {
        AlarmScheduler(context, exactAlarmCapability)
    }

    val schedulingCoordinator: SchedulingCoordinator by lazy {
        SchedulingCoordinator(behaviorRepository, instanceRepository, alarmScheduler, clock)
    }

    val instructionResolver: InstructionResolver by lazy { InstructionResolver() }

    val conflictDetector: ConflictDetector by lazy { ConflictDetector() }

    val backupService: BackupService by lazy {
        BackupService(database, clock, appVersionName())
    }

    suspend fun currentSettings(): AppSettings = settingsRepository.settings.first()

    /** Resolves what a behavior means for one specific occurrence. */
    suspend fun resolveInstruction(
        behavior: Behavior,
        instance: BehaviorInstance,
    ): ResolvedInstruction = instructionResolver.resolve(
        behavior = behavior,
        instructions = behaviorRepository.getInstructions(behavior.id),
        checklist = behaviorRepository.getChecklist(behavior.id),
        date = instance.scheduledDate,
        occurrenceIndex = instance.occurrenceIndex,
        progression = behaviorRepository.getProgression(behavior.id),
        behaviorStartDate = behaviorRepository.getSchedules(behavior.id)
            .minByOrNull { it.startDate }?.startDate,
    )

    /** Housekeeping so the audit trail and coaching log stay bounded. */
    suspend fun pruneOldRecords() {
        val cutoff = clock.nowMillis() - AUDIT_RETENTION_DAYS * 86_400_000L
        runCatching { database.eventDao().pruneBefore(cutoff) }
        runCatching { database.coachMessageDao().pruneBefore(cutoff) }
    }

    suspend fun seedBuiltInTemplates() {
        behaviorRepository.ensureBuiltInTemplates(builtInTemplates())
    }

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    /**
     * A handful of starting points so a new user is not staring at an empty form. These are
     * ordinary templates: editable, deletable, and not written into anyone's data until used.
     */
    private fun builtInTemplates(): List<BehaviorTemplate> {
        val now = clock.now()
        return listOf(
            BehaviorTemplate(
                name = "Morning exercise",
                description = "A short session to start the day.",
                category = "Fitness",
                measurement = Measurement.DURATION,
                enforcement = EnforcementLevel.COACH,
                targetDurationMinutes = 30,
                minimumDurationMinutes = 5,
                defaultInstruction = "Move for as long as you can manage.",
                recurrenceType = RecurrenceType.WEEKDAYS,
                daysOfWeekMask = DayMask.WEEKDAYS,
                timesOfDay = listOf(LocalTime.of(6, 30)),
                defaultRecoveryMinutes = 15,
                isBuiltIn = true,
                createdAt = now,
            ),
            BehaviorTemplate(
                name = "Evening reading",
                description = "Wind down with a book.",
                category = "Learning",
                measurement = Measurement.DURATION,
                enforcement = EnforcementLevel.GENTLE,
                targetDurationMinutes = 30,
                minimumDurationMinutes = 10,
                recurrenceType = RecurrenceType.DAILY,
                timesOfDay = listOf(LocalTime.of(20, 0)),
                defaultRecoveryMinutes = 30,
                isBuiltIn = true,
                createdAt = now,
            ),
            BehaviorTemplate(
                name = "Drink water",
                description = "A daily hydration target, with no fixed time.",
                category = "Health",
                isTimeBased = false,
                measurement = Measurement.QUANTITY,
                enforcement = EnforcementLevel.GENTLE,
                targetQuantity = 2.0,
                quantityUnit = "litres",
                recurrenceType = RecurrenceType.DAILY,
                isBuiltIn = true,
                createdAt = now,
            ),
            BehaviorTemplate(
                name = "Plan tomorrow",
                description = "Five minutes to decide what matters next.",
                category = "Productivity",
                measurement = Measurement.COMPLETION,
                enforcement = EnforcementLevel.COACH,
                targetDurationMinutes = 10,
                minimumDurationMinutes = 2,
                recurrenceType = RecurrenceType.WEEKDAYS,
                daysOfWeekMask = DayMask.WEEKDAYS,
                timesOfDay = listOf(LocalTime.of(21, 0)),
                isBuiltIn = true,
                createdAt = now,
            ),
            BehaviorTemplate(
                name = "Weekly review",
                description = "Look back at the week and adjust.",
                category = "Personal",
                measurement = Measurement.CHECKLIST,
                enforcement = EnforcementLevel.GENTLE,
                targetDurationMinutes = 20,
                recurrenceType = RecurrenceType.DAYS_OF_WEEK,
                daysOfWeekMask = DayMask.of(7),
                timesOfDay = listOf(LocalTime.of(18, 0)),
                isBuiltIn = true,
                createdAt = now,
            ),
        )
    }

    companion object {
        private const val AUDIT_RETENTION_DAYS = 400L
    }
}
