package com.pact.coach.data.mapper

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
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorException
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.BehaviorTemplate
import com.pact.coach.domain.model.ChecklistItem
import com.pact.coach.domain.model.ChecklistTick
import com.pact.coach.domain.model.CompletionQuality
import com.pact.coach.domain.model.DailyInstruction
import com.pact.coach.domain.model.Difficulty
import com.pact.coach.domain.model.EnforcementLevel
import com.pact.coach.domain.model.ExceptionType
import com.pact.coach.domain.model.Goal
import com.pact.coach.domain.model.GoalStatus
import com.pact.coach.domain.model.Importance
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.model.Measurement
import com.pact.coach.domain.model.PassReason
import com.pact.coach.domain.model.Priority
import com.pact.coach.domain.model.ProgressionStep
import com.pact.coach.domain.model.RecurrenceType
import com.pact.coach.domain.model.ReflectionFrequency
import com.pact.coach.domain.model.Schedule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Entity to domain mapping.
 *
 * Enum parsing is deliberately forgiving: an unknown string coming out of an old database or an
 * imported backup falls back to a sensible default instead of throwing. A user should never lose
 * access to their history because one column holds a value this build does not recognise.
 */

internal inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
    if (this == null) default else enumValues<T>().firstOrNull { it.name == this } ?: default

internal inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? =
    if (this == null) null else enumValues<T>().firstOrNull { it.name == this }

internal fun Long?.toLocalDateOrNull(): LocalDate? = this?.let { LocalDate.ofEpochDay(it) }

internal fun Int?.toLocalTimeOrNull(): LocalTime? =
    this?.let { LocalTime.of((it / 60).coerceIn(0, 23), (it % 60).coerceIn(0, 59)) }

internal fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute

/** Times of day are stored as an ascending, comma-separated list of minute-of-day values. */
internal fun String.toTimesOfDay(): List<LocalTime> =
    if (isBlank()) {
        emptyList()
    } else {
        split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..1439 }
            .distinct()
            .sorted()
            .map { LocalTime.of(it / 60, it % 60) }
    }

internal fun List<LocalTime>.toCsv(): String =
    map { it.toMinuteOfDay() }.distinct().sorted().joinToString(",")

// --- Goal -------------------------------------------------------------------------------

fun GoalEntity.toDomain(): Goal = Goal(
    id = id,
    name = name,
    description = description,
    category = category,
    whyItMatters = whyItMatters,
    startDate = LocalDate.ofEpochDay(startDateEpochDay),
    targetDate = targetDateEpochDay.toLocalDateOrNull(),
    priority = priority.toEnum(Priority.MEDIUM),
    status = status.toEnum(GoalStatus.ACTIVE),
    colorSeed = colorSeed,
    createdAt = Instant.ofEpochMilli(createdAtMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtMillis),
)

fun Goal.toEntity(): GoalEntity = GoalEntity(
    id = id,
    name = name,
    description = description,
    category = category,
    whyItMatters = whyItMatters,
    startDateEpochDay = startDate.toEpochDay(),
    targetDateEpochDay = targetDate?.toEpochDay(),
    priority = priority.name,
    status = status.name,
    colorSeed = colorSeed,
    createdAtMillis = createdAt.toEpochMilli(),
    updatedAtMillis = updatedAt.toEpochMilli(),
)

// --- Behavior ---------------------------------------------------------------------------

fun BehaviorEntity.toDomain(): Behavior = Behavior(
    id = id,
    goalId = goalId,
    name = name,
    description = description,
    category = category,
    isTimeBased = isTimeBased,
    measurement = measurement.toEnum(Measurement.DURATION),
    importance = importance.toEnum(Importance.NORMAL),
    difficulty = difficulty.toEnum(Difficulty.MEDIUM),
    enforcement = enforcement.toEnum(EnforcementLevel.COACH),
    targetDurationMinutes = targetDurationMinutes,
    minimumDurationMinutes = minimumDurationMinutes,
    targetQuantity = targetQuantity,
    minimumQuantity = minimumQuantity,
    quantityUnit = quantityUnit,
    defaultInstruction = defaultInstruction,
    notes = notes,
    reminderEnabled = reminderEnabled,
    soundEnabled = soundEnabled,
    vibrationEnabled = vibrationEnabled,
    soundUri = soundUri,
    vibrationPattern = vibrationPattern,
    preAlertMinutes = preAlertMinutes,
    escalationMinutes = escalationMinutes,
    channelVersion = channelVersion,
    recoveryEnabled = recoveryEnabled,
    maxRecoveriesPerInstance = maxRecoveriesPerInstance,
    defaultRecoveryMinutes = defaultRecoveryMinutes,
    missWindowMinutes = missWindowMinutes,
    reflectionFrequency = reflectionFrequency.toEnum(ReflectionFrequency.SOMETIMES),
    allowPartialChecklist = allowPartialChecklist,
    requirePassReason = requirePassReason,
    isActive = isActive,
    progressionEnabled = progressionEnabled,
    createdAt = Instant.ofEpochMilli(createdAtMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtMillis),
)

fun Behavior.toEntity(): BehaviorEntity = BehaviorEntity(
    id = id,
    goalId = goalId,
    name = name,
    description = description,
    category = category,
    isTimeBased = isTimeBased,
    measurement = measurement.name,
    importance = importance.name,
    difficulty = difficulty.name,
    enforcement = enforcement.name,
    targetDurationMinutes = targetDurationMinutes,
    minimumDurationMinutes = minimumDurationMinutes,
    targetQuantity = targetQuantity,
    minimumQuantity = minimumQuantity,
    quantityUnit = quantityUnit,
    defaultInstruction = defaultInstruction,
    notes = notes,
    reminderEnabled = reminderEnabled,
    soundEnabled = soundEnabled,
    vibrationEnabled = vibrationEnabled,
    soundUri = soundUri,
    vibrationPattern = vibrationPattern,
    preAlertMinutes = preAlertMinutes,
    escalationMinutes = escalationMinutes,
    channelVersion = channelVersion,
    recoveryEnabled = recoveryEnabled,
    maxRecoveriesPerInstance = maxRecoveriesPerInstance,
    defaultRecoveryMinutes = defaultRecoveryMinutes,
    missWindowMinutes = missWindowMinutes,
    reflectionFrequency = reflectionFrequency.name,
    allowPartialChecklist = allowPartialChecklist,
    requirePassReason = requirePassReason,
    isActive = isActive,
    progressionEnabled = progressionEnabled,
    createdAtMillis = createdAt.toEpochMilli(),
    updatedAtMillis = updatedAt.toEpochMilli(),
)

// --- Schedule ---------------------------------------------------------------------------

fun ScheduleEntity.toDomain(): Schedule = Schedule(
    id = id,
    behaviorId = behaviorId,
    recurrenceType = recurrenceType.toEnum(RecurrenceType.DAILY),
    daysOfWeekMask = daysOfWeekMask,
    intervalDays = intervalDays,
    anchorDate = anchorDateEpochDay.toLocalDateOrNull(),
    startDate = LocalDate.ofEpochDay(startDateEpochDay),
    endDate = endDateEpochDay.toLocalDateOrNull(),
    timesOfDay = timesOfDayCsv.toTimesOfDay(),
    timesPerWeek = timesPerWeek,
    timesPerDay = timesPerDay,
    isEnabled = isEnabled,
)

fun Schedule.toEntity(): ScheduleEntity = ScheduleEntity(
    id = id,
    behaviorId = behaviorId,
    recurrenceType = recurrenceType.name,
    daysOfWeekMask = daysOfWeekMask,
    intervalDays = intervalDays,
    anchorDateEpochDay = anchorDate?.toEpochDay(),
    startDateEpochDay = startDate.toEpochDay(),
    endDateEpochDay = endDate?.toEpochDay(),
    timesOfDayCsv = timesOfDay.toCsv(),
    timesPerWeek = timesPerWeek,
    timesPerDay = timesPerDay,
    isEnabled = isEnabled,
)

// --- Instance ---------------------------------------------------------------------------

fun BehaviorInstanceEntity.toDomain(): BehaviorInstance = BehaviorInstance(
    id = id,
    behaviorId = behaviorId,
    scheduleId = scheduleId,
    scheduledDate = LocalDate.ofEpochDay(scheduledDateEpochDay),
    scheduledTime = scheduledTimeMinutes.toLocalTimeOrNull(),
    scheduledAtUtcMillis = scheduledAtUtcMillis,
    zoneId = zoneId,
    occurrenceIndex = occurrenceIndex,
    state = state.toEnum(InstanceState.SCHEDULED),
    originInstanceId = originInstanceId,
    rootInstanceId = rootInstanceId,
    recoveryDepth = recoveryDepth,
    firedAtMillis = firedAtMillis,
    respondedAtMillis = respondedAtMillis,
    completedAtMillis = completedAtMillis,
    completionQuality = completionQuality.toEnumOrNull<CompletionQuality>(),
    actualDurationMinutes = actualDurationMinutes,
    actualQuantity = actualQuantity,
    rating = rating,
    note = note,
    passReason = passReason.toEnumOrNull<PassReason>(),
    passNote = passNote,
    alarmRequestCode = alarmRequestCode,
    dedupKey = dedupKey,
    createdAtMillis = createdAtMillis,
)

fun BehaviorInstance.toEntity(): BehaviorInstanceEntity = BehaviorInstanceEntity(
    id = id,
    behaviorId = behaviorId,
    scheduleId = scheduleId,
    scheduledDateEpochDay = scheduledDate.toEpochDay(),
    scheduledTimeMinutes = scheduledTime?.toMinuteOfDay(),
    scheduledAtUtcMillis = scheduledAtUtcMillis,
    zoneId = zoneId,
    occurrenceIndex = occurrenceIndex,
    state = state.name,
    originInstanceId = originInstanceId,
    rootInstanceId = rootInstanceId,
    recoveryDepth = recoveryDepth,
    firedAtMillis = firedAtMillis,
    respondedAtMillis = respondedAtMillis,
    completedAtMillis = completedAtMillis,
    completionQuality = completionQuality?.name,
    actualDurationMinutes = actualDurationMinutes,
    actualQuantity = actualQuantity,
    rating = rating,
    note = note,
    passReason = passReason?.name,
    passNote = passNote,
    alarmRequestCode = alarmRequestCode,
    dedupKey = dedupKey,
    createdAtMillis = createdAtMillis,
)

// --- Daily instruction ------------------------------------------------------------------

fun DailyInstructionEntity.toDomain(): DailyInstruction = DailyInstruction(
    id = id,
    behaviorId = behaviorId,
    dayOfWeek = dayOfWeek,
    date = dateEpochDay.toLocalDateOrNull(),
    occurrenceIndex = occurrenceIndex,
    title = title,
    instructions = instructions,
    durationMinutes = durationMinutes,
    quantityTarget = quantityTarget,
    notes = notes,
    referenceUri = referenceUri,
    imageUri = imageUri,
    isRestDay = isRestDay,
)

fun DailyInstruction.toEntity(): DailyInstructionEntity = DailyInstructionEntity(
    id = id,
    behaviorId = behaviorId,
    dayOfWeek = dayOfWeek,
    dateEpochDay = date?.toEpochDay(),
    occurrenceIndex = occurrenceIndex,
    title = title,
    instructions = instructions,
    durationMinutes = durationMinutes,
    quantityTarget = quantityTarget,
    notes = notes,
    referenceUri = referenceUri,
    imageUri = imageUri,
    isRestDay = isRestDay,
)

// --- Checklist --------------------------------------------------------------------------

fun ChecklistItemEntity.toDomain(): ChecklistItem = ChecklistItem(
    id = id,
    behaviorId = behaviorId,
    dailyInstructionId = dailyInstructionId,
    position = position,
    text = text,
    isRequired = isRequired,
)

fun ChecklistItem.toEntity(): ChecklistItemEntity = ChecklistItemEntity(
    id = id,
    behaviorId = behaviorId,
    dailyInstructionId = dailyInstructionId,
    position = position,
    text = text,
    isRequired = isRequired,
)

fun ChecklistTickEntity.toDomain(): ChecklistTick = ChecklistTick(instanceId, checklistItemId, isChecked)

fun ChecklistTick.toEntity(): ChecklistTickEntity = ChecklistTickEntity(instanceId, checklistItemId, isChecked)

// --- Exception --------------------------------------------------------------------------

fun BehaviorExceptionEntity.toDomain(): BehaviorException = BehaviorException(
    id = id,
    behaviorId = behaviorId,
    type = type.toEnum(ExceptionType.SKIP),
    startDate = LocalDate.ofEpochDay(startDateEpochDay),
    endDate = LocalDate.ofEpochDay(endDateEpochDay),
    note = note,
)

fun BehaviorException.toEntity(): BehaviorExceptionEntity = BehaviorExceptionEntity(
    id = id,
    behaviorId = behaviorId,
    type = type.name,
    startDateEpochDay = startDate.toEpochDay(),
    endDateEpochDay = endDate.toEpochDay(),
    note = note,
)

// --- Template ---------------------------------------------------------------------------

fun BehaviorTemplateEntity.toDomain(): BehaviorTemplate = BehaviorTemplate(
    id = id,
    name = name,
    description = description,
    category = category,
    isTimeBased = isTimeBased,
    measurement = measurement.toEnum(Measurement.DURATION),
    importance = importance.toEnum(Importance.NORMAL),
    difficulty = difficulty.toEnum(Difficulty.MEDIUM),
    enforcement = enforcement.toEnum(EnforcementLevel.COACH),
    targetDurationMinutes = targetDurationMinutes,
    minimumDurationMinutes = minimumDurationMinutes,
    targetQuantity = targetQuantity,
    quantityUnit = quantityUnit,
    defaultInstruction = defaultInstruction,
    recurrenceType = recurrenceType.toEnum(RecurrenceType.DAILY),
    daysOfWeekMask = daysOfWeekMask,
    intervalDays = intervalDays,
    timesOfDay = timesOfDayCsv.toTimesOfDay(),
    defaultRecoveryMinutes = defaultRecoveryMinutes,
    isBuiltIn = isBuiltIn,
    createdAt = Instant.ofEpochMilli(createdAtMillis),
)

fun BehaviorTemplate.toEntity(): BehaviorTemplateEntity = BehaviorTemplateEntity(
    id = id,
    name = name,
    description = description,
    category = category,
    isTimeBased = isTimeBased,
    measurement = measurement.name,
    importance = importance.name,
    difficulty = difficulty.name,
    enforcement = enforcement.name,
    targetDurationMinutes = targetDurationMinutes,
    minimumDurationMinutes = minimumDurationMinutes,
    targetQuantity = targetQuantity,
    quantityUnit = quantityUnit,
    defaultInstruction = defaultInstruction,
    recurrenceType = recurrenceType.name,
    daysOfWeekMask = daysOfWeekMask,
    intervalDays = intervalDays,
    timesOfDayCsv = timesOfDay.toCsv(),
    defaultRecoveryMinutes = defaultRecoveryMinutes,
    isBuiltIn = isBuiltIn,
    createdAtMillis = createdAt.toEpochMilli(),
)

// --- Progression ------------------------------------------------------------------------

fun ProgressionStepEntity.toDomain(): ProgressionStep = ProgressionStep(
    id = id,
    behaviorId = behaviorId,
    weekIndex = weekIndex,
    targetDurationMinutes = targetDurationMinutes,
    targetQuantity = targetQuantity,
)

fun ProgressionStep.toEntity(): ProgressionStepEntity = ProgressionStepEntity(
    id = id,
    behaviorId = behaviorId,
    weekIndex = weekIndex,
    targetDurationMinutes = targetDurationMinutes,
    targetQuantity = targetQuantity,
)

// --- Events -----------------------------------------------------------------------------

/** Convenience factory; events are append-only so there is no reverse mapping. */
fun actionEvent(
    id: String,
    instanceId: String?,
    behaviorId: String?,
    type: com.pact.coach.domain.model.ActionType,
    atMillis: Long,
    fromState: InstanceState? = null,
    toState: InstanceState? = null,
    detail: String = "",
): ActionEventEntity = ActionEventEntity(
    id = id,
    instanceId = instanceId,
    behaviorId = behaviorId,
    type = type.name,
    atMillis = atMillis,
    fromState = fromState?.name,
    toState = toState?.name,
    detail = detail,
)
