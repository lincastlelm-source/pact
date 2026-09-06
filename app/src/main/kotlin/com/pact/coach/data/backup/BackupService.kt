package com.pact.coach.data.backup

import androidx.room.withTransaction
import com.pact.coach.core.time.ClockProvider
import com.pact.coach.core.util.Ids
import com.pact.coach.data.db.PactDatabase
import com.pact.coach.data.db.entity.ActionEventEntity
import com.pact.coach.data.db.entity.BehaviorEntity
import com.pact.coach.data.db.entity.BehaviorExceptionEntity
import com.pact.coach.data.db.entity.BehaviorInstanceEntity
import com.pact.coach.data.db.entity.BehaviorTemplateEntity
import com.pact.coach.data.db.entity.ChecklistItemEntity
import com.pact.coach.data.db.entity.ChecklistTickEntity
import com.pact.coach.data.db.entity.DailyInstructionEntity
import com.pact.coach.data.db.entity.GoalEntity
import com.pact.coach.data.db.entity.ProgressionStepEntity
import com.pact.coach.data.db.entity.ScheduleEntity
import com.pact.coach.data.repository.AppSettings
import kotlinx.serialization.json.Json

/**
 * Local export and import. There is no cloud, so this is the user's only way to move their
 * history to a new phone or keep a copy of it.
 *
 * Import is defensive by design. A backup file is untrusted input: it may be truncated, hand
 * edited, from a newer build, or simply not a PACT file at all. Every stage validates before it
 * writes, and the whole apply runs in one transaction so a rejected file leaves the database
 * exactly as it was.
 */
class BackupService(
    private val db: PactDatabase,
    private val clock: ClockProvider,
    private val appVersionName: String,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true      // tolerate fields added by a newer build
        encodeDefaults = true
        isLenient = false             // but do not accept malformed JSON
    }

    // --- Export -------------------------------------------------------------------------

    suspend fun export(settings: AppSettings?): String {
        val envelope = BackupEnvelope(
            formatVersion = CURRENT_VERSION,
            appVersionName = appVersionName,
            exportedAtMillis = clock.nowMillis(),
            deviceZoneId = clock.zone().id,
            goals = db.goalDao().getAll().map { it.toBackup() },
            behaviors = db.behaviorDao().getAll().map { it.toBackup() },
            schedules = db.scheduleDao().getAll().map { it.toBackup() },
            dailyInstructions = db.instructionDao().getAll().map { it.toBackup() },
            checklistItems = db.checklistDao().getAll().map { it.toBackup() },
            checklistTicks = db.checklistDao().getAllTicks().map { it.toBackup() },
            instances = db.instanceDao().getAll().map { it.toBackup() },
            events = db.eventDao().getAll().map { it.toBackup() },
            exceptions = db.exceptionDao().getAll().map { it.toBackup() },
            templates = db.templateDao().getAll().filter { !it.isBuiltIn }.map { it.toBackup() },
            progressionSteps = db.progressionDao().getAll().map { it.toBackup() },
            settings = settings?.toBackup(),
        )
        return json.encodeToString(BackupEnvelope.serializer(), envelope)
    }

    fun suggestedFileName(): String {
        val today = clock.today()
        return "pact-backup-$today.json"
    }

    // --- Inspect ------------------------------------------------------------------------

    sealed interface ParseResult {
        data class Ok(val envelope: BackupEnvelope, val summary: BackupSummary) : ParseResult
        data class Invalid(val reason: String) : ParseResult
    }

    /**
     * Parses and validates without writing anything, so the UI can show the user what they are
     * about to import and ask how to apply it.
     */
    fun parse(raw: String): ParseResult {
        if (raw.isBlank()) return ParseResult.Invalid("The file is empty.")
        if (raw.length > MAX_FILE_CHARS) {
            return ParseResult.Invalid("That file is too large to be a PACT backup.")
        }

        val envelope = try {
            json.decodeFromString(BackupEnvelope.serializer(), raw)
        } catch (e: Exception) {
            return ParseResult.Invalid("This does not look like a PACT backup file.")
        }

        if (envelope.formatVersion <= 0) {
            return ParseResult.Invalid("The file does not declare a backup version.")
        }
        if (envelope.formatVersion > CURRENT_VERSION) {
            return ParseResult.Invalid(
                "This backup was made by a newer version of PACT " +
                    "(format ${envelope.formatVersion}, this build reads up to $CURRENT_VERSION). " +
                    "Update the app and try again.",
            )
        }

        validate(envelope)?.let { return ParseResult.Invalid(it) }

        return ParseResult.Ok(envelope, summarise(envelope))
    }

    /** Structural checks that would otherwise produce orphaned or impossible rows. */
    private fun validate(envelope: BackupEnvelope): String? {
        val goalIds = envelope.goals.map { it.id }.toSet()
        val behaviorIds = envelope.behaviors.map { it.id }.toSet()

        if (envelope.goals.size != goalIds.size) return "The file contains duplicate goal ids."
        if (envelope.behaviors.size != behaviorIds.size) return "The file contains duplicate behavior ids."

        envelope.goals.firstOrNull { it.name.isBlank() }
            ?.let { return "A goal in the file has no name." }
        envelope.behaviors.firstOrNull { it.name.isBlank() }
            ?.let { return "A behavior in the file has no name." }

        envelope.behaviors.firstOrNull { it.goalId != null && it.goalId !in goalIds }
            ?.let { return "Behavior '${it.name}' refers to a goal that is not in the file." }

        envelope.schedules.firstOrNull { it.behaviorId !in behaviorIds }
            ?.let { return "A schedule refers to a behavior that is not in the file." }

        envelope.instances.firstOrNull { it.behaviorId !in behaviorIds }
            ?.let { return "History refers to a behavior that is not in the file." }

        if (envelope.instances.map { it.dedupKey }.toSet().size != envelope.instances.size) {
            return "The file contains duplicate history entries."
        }

        val badTime = envelope.instances.firstOrNull {
            it.scheduledTimeMinutes != null && it.scheduledTimeMinutes !in 0..1439
        }
        if (badTime != null) return "The file contains an invalid scheduled time."

        return null
    }

    private fun summarise(envelope: BackupEnvelope) = BackupSummary(
        formatVersion = envelope.formatVersion,
        appVersionName = envelope.appVersionName,
        exportedAtMillis = envelope.exportedAtMillis,
        goals = envelope.goals.size,
        behaviors = envelope.behaviors.size,
        historyEntries = envelope.instances.size,
        hasSettings = envelope.settings != null,
    )

    // --- Import -------------------------------------------------------------------------

    enum class Mode {
        /** Add what is missing, leave existing rows untouched. Nothing is ever overwritten. */
        MERGE,

        /** Delete everything currently stored, then insert the file's contents. */
        REPLACE,
    }

    data class ImportResult(
        val inserted: Int,
        val skipped: Int,
        val mode: Mode,
    )

    /**
     * Applies a previously parsed envelope. One transaction: either the whole backup lands or
     * nothing does. REPLACE is destructive and the UI must confirm it explicitly first.
     */
    suspend fun import(envelope: BackupEnvelope, mode: Mode): ImportResult = db.withTransaction {
        if (mode == Mode.REPLACE) {
            wipe()
        }

        val existingGoalIds = db.goalDao().getAll().map { it.id }.toSet()
        val existingBehaviorIds = db.behaviorDao().getAll().map { it.id }.toSet()
        val existingInstanceKeys = db.instanceDao().getAll().map { it.dedupKey }.toSet()

        var inserted = 0
        var skipped = 0

        val goals = envelope.goals.filter { it.id !in existingGoalIds }
        skipped += envelope.goals.size - goals.size
        if (goals.isNotEmpty()) {
            db.goalDao().upsertAll(goals.map { it.toEntity() })
            inserted += goals.size
        }

        val behaviors = envelope.behaviors.filter { it.id !in existingBehaviorIds }
        skipped += envelope.behaviors.size - behaviors.size
        if (behaviors.isNotEmpty()) {
            db.behaviorDao().upsertAll(behaviors.map { it.toEntity() })
            inserted += behaviors.size
        }

        // Children are only imported for behaviors that actually got inserted, so a MERGE never
        // grafts someone else's schedule onto a behavior the user already has.
        val acceptedBehaviorIds = behaviors.map { it.id }.toSet()

        val schedules = envelope.schedules.filter { it.behaviorId in acceptedBehaviorIds }
        if (schedules.isNotEmpty()) {
            db.scheduleDao().upsertAll(schedules.map { it.toEntity() })
            inserted += schedules.size
        }

        val instructions = envelope.dailyInstructions.filter { it.behaviorId in acceptedBehaviorIds }
        if (instructions.isNotEmpty()) {
            db.instructionDao().upsertAll(instructions.map { it.toEntity() })
            inserted += instructions.size
        }

        val checklist = envelope.checklistItems.filter { it.behaviorId in acceptedBehaviorIds }
        if (checklist.isNotEmpty()) {
            db.checklistDao().upsertAll(checklist.map { it.toEntity() })
            inserted += checklist.size
        }

        val progression = envelope.progressionSteps.filter { it.behaviorId in acceptedBehaviorIds }
        if (progression.isNotEmpty()) {
            db.progressionDao().upsertAll(progression.map { it.toEntity() })
            inserted += progression.size
        }

        val instances = envelope.instances
            .filter { it.behaviorId in acceptedBehaviorIds }
            .filter { it.dedupKey !in existingInstanceKeys }
        skipped += envelope.instances.size - instances.size
        if (instances.isNotEmpty()) {
            db.instanceDao().upsertAll(instances.map { it.toEntity() })
            inserted += instances.size
        }

        val acceptedInstanceIds = instances.map { it.id }.toSet()

        val ticks = envelope.checklistTicks.filter { it.instanceId in acceptedInstanceIds }
        if (ticks.isNotEmpty()) db.checklistDao().upsertTicks(ticks.map { it.toEntity() })

        // Only carry over audit rows belonging to content this import actually accepted, so a
        // behavior the user already had keeps its own history rather than gaining someone else's.
        // insertIgnoring then makes re-importing the same file a clean no-op: audit events are
        // append-only and identified by a stable id, so a row that already exists is the same row.
        val events = envelope.events.filter { event ->
            when {
                event.instanceId != null -> event.instanceId in acceptedInstanceIds
                event.behaviorId != null -> event.behaviorId in acceptedBehaviorIds
                else -> false
            }
        }
        if (events.isNotEmpty()) {
            db.eventDao().insertIgnoring(events.map { it.toEntity() })
        }

        val exceptions = envelope.exceptions.filter {
            it.behaviorId == null || it.behaviorId in acceptedBehaviorIds
        }
        if (exceptions.isNotEmpty()) {
            db.exceptionDao().upsertAll(exceptions.map { it.toEntity() })
            inserted += exceptions.size
        }

        if (envelope.templates.isNotEmpty()) {
            db.templateDao().upsertAll(envelope.templates.map { it.toEntity() })
        }

        db.eventDao().insert(
            ActionEventEntity(
                id = Ids.new(),
                instanceId = null,
                behaviorId = null,
                type = "EDIT",
                atMillis = clock.nowMillis(),
                fromState = null,
                toState = null,
                detail = "imported backup (${mode.name.lowercase()}): $inserted rows",
            ),
        )

        ImportResult(inserted, skipped, mode)
    }

    private suspend fun wipe() {
        db.coachMessageDao().deleteAll()
        db.eventDao().deleteAll()
        db.checklistDao().deleteAllTicks()
        db.instanceDao().deleteAll()
        db.checklistDao().deleteAll()
        db.instructionDao().deleteAll()
        db.progressionDao().deleteAll()
        db.scheduleDao().deleteAll()
        db.exceptionDao().deleteAll()
        db.behaviorDao().deleteAll()
        db.goalDao().deleteAll()
        db.templateDao().deleteAll()
    }

    companion object {
        /** Increment when the schema of [BackupEnvelope] changes incompatibly. */
        const val CURRENT_VERSION = 1

        /** Sanity bound so a mis-picked 500 MB file is rejected before it is parsed. */
        const val MAX_FILE_CHARS = 40_000_000
    }
}

data class BackupSummary(
    val formatVersion: Int,
    val appVersionName: String,
    val exportedAtMillis: Long,
    val goals: Int,
    val behaviors: Int,
    val historyEntries: Int,
    val hasSettings: Boolean,
)

// --- Entity <-> backup DTO -----------------------------------------------------------------

internal fun GoalEntity.toBackup() = BackupGoal(
    id, name, description, category, whyItMatters, startDateEpochDay, targetDateEpochDay,
    priority, status, colorSeed, createdAtMillis, updatedAtMillis,
)

internal fun BackupGoal.toEntity() = GoalEntity(
    id, name, description, category, whyItMatters, startDateEpochDay, targetDateEpochDay,
    priority, status, colorSeed, createdAtMillis, updatedAtMillis,
)

internal fun BehaviorEntity.toBackup() = BackupBehavior(
    id, goalId, name, description, category, isTimeBased, measurement, importance, difficulty,
    enforcement, targetDurationMinutes, minimumDurationMinutes, targetQuantity, minimumQuantity,
    quantityUnit, defaultInstruction, notes, reminderEnabled, soundEnabled, vibrationEnabled,
    soundUri, vibrationPattern, preAlertMinutes, escalationMinutes, channelVersion,
    recoveryEnabled, maxRecoveriesPerInstance, defaultRecoveryMinutes, missWindowMinutes,
    reflectionFrequency, allowPartialChecklist, requirePassReason, isActive, progressionEnabled,
    createdAtMillis, updatedAtMillis,
)

internal fun BackupBehavior.toEntity() = BehaviorEntity(
    id, goalId, name, description, category, isTimeBased, measurement, importance, difficulty,
    enforcement, targetDurationMinutes, minimumDurationMinutes, targetQuantity, minimumQuantity,
    quantityUnit, defaultInstruction, notes, reminderEnabled, soundEnabled, vibrationEnabled,
    soundUri, vibrationPattern, preAlertMinutes, escalationMinutes, channelVersion,
    recoveryEnabled, maxRecoveriesPerInstance, defaultRecoveryMinutes, missWindowMinutes,
    reflectionFrequency, allowPartialChecklist, requirePassReason, isActive, progressionEnabled,
    createdAtMillis, updatedAtMillis,
)

internal fun ScheduleEntity.toBackup() = BackupSchedule(
    id, behaviorId, recurrenceType, daysOfWeekMask, intervalDays, anchorDateEpochDay,
    startDateEpochDay, endDateEpochDay, timesOfDayCsv, timesPerWeek, timesPerDay, isEnabled,
)

internal fun BackupSchedule.toEntity() = ScheduleEntity(
    id, behaviorId, recurrenceType, daysOfWeekMask, intervalDays, anchorDateEpochDay,
    startDateEpochDay, endDateEpochDay, timesOfDayCsv, timesPerWeek, timesPerDay, isEnabled,
)

internal fun DailyInstructionEntity.toBackup() = BackupDailyInstruction(
    id, behaviorId, dayOfWeek, dateEpochDay, occurrenceIndex, title, instructions,
    durationMinutes, quantityTarget, notes, referenceUri, imageUri, isRestDay,
)

internal fun BackupDailyInstruction.toEntity() = DailyInstructionEntity(
    id, behaviorId, dayOfWeek, dateEpochDay, occurrenceIndex, title, instructions,
    durationMinutes, quantityTarget, notes, referenceUri, imageUri, isRestDay,
)

internal fun ChecklistItemEntity.toBackup() =
    BackupChecklistItem(id, behaviorId, dailyInstructionId, position, text, isRequired)

internal fun BackupChecklistItem.toEntity() =
    ChecklistItemEntity(id, behaviorId, dailyInstructionId, position, text, isRequired)

internal fun ChecklistTickEntity.toBackup() =
    BackupChecklistTick(instanceId, checklistItemId, isChecked)

internal fun BackupChecklistTick.toEntity() =
    ChecklistTickEntity(instanceId, checklistItemId, isChecked)

internal fun BehaviorInstanceEntity.toBackup() = BackupInstance(
    id, behaviorId, scheduleId, scheduledDateEpochDay, scheduledTimeMinutes, scheduledAtUtcMillis,
    zoneId, occurrenceIndex, state, originInstanceId, rootInstanceId, recoveryDepth, firedAtMillis,
    respondedAtMillis, completedAtMillis, completionQuality, actualDurationMinutes, actualQuantity,
    rating, note, passReason, passNote, alarmRequestCode, dedupKey, createdAtMillis,
)

internal fun BackupInstance.toEntity() = BehaviorInstanceEntity(
    id, behaviorId, scheduleId, scheduledDateEpochDay, scheduledTimeMinutes, scheduledAtUtcMillis,
    zoneId, occurrenceIndex, state, originInstanceId, rootInstanceId, recoveryDepth, firedAtMillis,
    respondedAtMillis, completedAtMillis, completionQuality, actualDurationMinutes, actualQuantity,
    rating, note, passReason, passNote, alarmRequestCode, dedupKey, createdAtMillis,
)

internal fun ActionEventEntity.toBackup() =
    BackupEvent(id, instanceId, behaviorId, type, atMillis, fromState, toState, detail)

internal fun BackupEvent.toEntity() =
    ActionEventEntity(id, instanceId, behaviorId, type, atMillis, fromState, toState, detail)

internal fun BehaviorExceptionEntity.toBackup() =
    BackupException(id, behaviorId, type, startDateEpochDay, endDateEpochDay, note)

internal fun BackupException.toEntity() =
    BehaviorExceptionEntity(id, behaviorId, type, startDateEpochDay, endDateEpochDay, note)

internal fun BehaviorTemplateEntity.toBackup() = BackupTemplate(
    id, name, description, category, isTimeBased, measurement, importance, difficulty,
    enforcement, targetDurationMinutes, minimumDurationMinutes, targetQuantity, quantityUnit,
    defaultInstruction, recurrenceType, daysOfWeekMask, intervalDays, timesOfDayCsv,
    defaultRecoveryMinutes, isBuiltIn, createdAtMillis,
)

internal fun BackupTemplate.toEntity() = BehaviorTemplateEntity(
    id, name, description, category, isTimeBased, measurement, importance, difficulty,
    enforcement, targetDurationMinutes, minimumDurationMinutes, targetQuantity, quantityUnit,
    defaultInstruction, recurrenceType, daysOfWeekMask, intervalDays, timesOfDayCsv,
    defaultRecoveryMinutes, isBuiltIn, createdAtMillis,
)

internal fun ProgressionStepEntity.toBackup() =
    BackupProgressionStep(id, behaviorId, weekIndex, targetDurationMinutes, targetQuantity)

internal fun BackupProgressionStep.toEntity() =
    ProgressionStepEntity(id, behaviorId, weekIndex, targetDurationMinutes, targetQuantity)

internal fun AppSettings.toBackup() = BackupSettings(
    themeMode = themeMode.name,
    use24HourClock = use24HourClock,
    firstDayOfWeek = firstDayOfWeek.value,
    defaultEnforcement = defaultEnforcement.name,
    defaultSoundUri = defaultSoundUri,
    defaultSoundEnabled = defaultSoundEnabled,
    defaultVibrationEnabled = defaultVibrationEnabled,
    defaultPreAlertMinutes = defaultPreAlertMinutes,
    defaultRecoveryMinutes = defaultRecoveryMinutes,
    coachPersonality = coachPersonality.name,
    coachIntensity = coachIntensity.name,
    reflectionFrequency = reflectionFrequency.name,
)
