package com.pact.coach.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entities.
 *
 * Conventions used throughout:
 *  - Primary keys are locally generated UUID strings, so export/import can merge without
 *    renumbering anything.
 *  - Dates are stored as epoch days and times as minute-of-day integers, never as formatted
 *    strings, so ordering and range queries are correct and locale independent.
 *  - Absolute moments are epoch milliseconds (UTC).
 *  - Enums are stored by name.
 *  - Every foreign key that the UI filters on has an explicit index; SQLite will not create one
 *    for you and Room warns loudly if it is missing.
 */

@Entity(
    tableName = "goals",
    indices = [Index("status"), Index("category")],
)
data class GoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val category: String,
    val whyItMatters: String,
    val startDateEpochDay: Long,
    val targetDateEpochDay: Long?,
    val priority: String,
    val status: String,
    val colorSeed: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "behaviors",
    foreignKeys = [
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            // Deleting a goal must not delete the behavior history. The behavior is detached
            // and stays visible as an independent behavior.
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("goalId"), Index("isActive")],
)
data class BehaviorEntity(
    @PrimaryKey val id: String,
    val goalId: String?,
    val name: String,
    val description: String,
    val category: String,
    val isTimeBased: Boolean,
    val measurement: String,
    val importance: String,
    val difficulty: String,
    val enforcement: String,
    val targetDurationMinutes: Int?,
    val minimumDurationMinutes: Int?,
    val targetQuantity: Double?,
    val minimumQuantity: Double?,
    val quantityUnit: String,
    val defaultInstruction: String,
    val notes: String,
    val reminderEnabled: Boolean,
    val soundEnabled: Boolean,
    val vibrationEnabled: Boolean,
    val soundUri: String?,
    val vibrationPattern: String,
    val preAlertMinutes: Int?,
    val escalationMinutes: Int?,
    val channelVersion: Int,
    val recoveryEnabled: Boolean,
    val maxRecoveriesPerInstance: Int,
    val defaultRecoveryMinutes: Int,
    val missWindowMinutes: Int,
    val reflectionFrequency: String,
    val allowPartialChecklist: Boolean,
    val requirePassReason: Boolean,
    val isActive: Boolean,
    val progressionEnabled: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = BehaviorEntity::class,
            parentColumns = ["id"],
            childColumns = ["behaviorId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("behaviorId")],
)
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val behaviorId: String,
    val recurrenceType: String,
    val daysOfWeekMask: Int,
    val intervalDays: Int,
    val anchorDateEpochDay: Long?,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long?,
    /** Comma-separated minutes-of-day, ascending. Empty string when the behavior is untimed. */
    val timesOfDayCsv: String,
    val timesPerWeek: Int?,
    val timesPerDay: Int,
    val isEnabled: Boolean,
)

@Entity(
    tableName = "daily_instructions",
    foreignKeys = [
        ForeignKey(
            entity = BehaviorEntity::class,
            parentColumns = ["id"],
            childColumns = ["behaviorId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("behaviorId"), Index("behaviorId", "dayOfWeek"), Index("behaviorId", "dateEpochDay")],
)
data class DailyInstructionEntity(
    @PrimaryKey val id: String,
    val behaviorId: String,
    val dayOfWeek: Int?,
    val dateEpochDay: Long?,
    val occurrenceIndex: Int?,
    val title: String,
    val instructions: String,
    val durationMinutes: Int?,
    val quantityTarget: Double?,
    val notes: String,
    val referenceUri: String?,
    val imageUri: String?,
    val isRestDay: Boolean,
)

@Entity(
    tableName = "checklist_items",
    foreignKeys = [
        ForeignKey(
            entity = BehaviorEntity::class,
            parentColumns = ["id"],
            childColumns = ["behaviorId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("behaviorId"), Index("dailyInstructionId")],
)
data class ChecklistItemEntity(
    @PrimaryKey val id: String,
    val behaviorId: String,
    val dailyInstructionId: String?,
    val position: Int,
    val text: String,
    val isRequired: Boolean,
)

/**
 * One scheduled occurrence.
 *
 * [dedupKey] is uniquely indexed. That single constraint is what makes horizon regeneration safe
 * to run as often as we like: re-generating a window that already exists is a no-op rather than a
 * source of duplicate alarms.
 */
@Entity(
    tableName = "behavior_instances",
    foreignKeys = [
        ForeignKey(
            entity = BehaviorEntity::class,
            parentColumns = ["id"],
            childColumns = ["behaviorId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["dedupKey"], unique = true),
        Index("behaviorId"),
        Index("scheduledDateEpochDay"),
        Index("state"),
        Index("scheduledAtUtcMillis"),
        Index("rootInstanceId"),
        Index("behaviorId", "scheduledDateEpochDay"),
    ],
)
data class BehaviorInstanceEntity(
    @PrimaryKey val id: String,
    val behaviorId: String,
    val scheduleId: String?,
    val scheduledDateEpochDay: Long,
    /** Minute of day, or null for untimed occurrences. */
    val scheduledTimeMinutes: Int?,
    val scheduledAtUtcMillis: Long?,
    val zoneId: String,
    val occurrenceIndex: Int,
    val state: String,
    val originInstanceId: String?,
    val rootInstanceId: String?,
    val recoveryDepth: Int,
    val firedAtMillis: Long?,
    val respondedAtMillis: Long?,
    val completedAtMillis: Long?,
    val completionQuality: String?,
    val actualDurationMinutes: Int?,
    val actualQuantity: Double?,
    val rating: Int?,
    val note: String,
    val passReason: String?,
    val passNote: String,
    val alarmRequestCode: Int,
    val dedupKey: String,
    val createdAtMillis: Long,
)

/** Append-only audit trail. Never updated, only inserted, so history cannot be rewritten. */
@Entity(
    tableName = "action_events",
    indices = [Index("instanceId"), Index("behaviorId"), Index("atMillis")],
)
data class ActionEventEntity(
    @PrimaryKey val id: String,
    val instanceId: String?,
    val behaviorId: String?,
    val type: String,
    val atMillis: Long,
    val fromState: String?,
    val toState: String?,
    val detail: String,
)

@Entity(
    tableName = "behavior_exceptions",
    indices = [Index("behaviorId"), Index("startDateEpochDay"), Index("endDateEpochDay")],
)
data class BehaviorExceptionEntity(
    @PrimaryKey val id: String,
    /** Null means the exception applies to every behavior. */
    val behaviorId: String?,
    val type: String,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long,
    val note: String,
)

@Entity(tableName = "behavior_templates")
data class BehaviorTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val category: String,
    val isTimeBased: Boolean,
    val measurement: String,
    val importance: String,
    val difficulty: String,
    val enforcement: String,
    val targetDurationMinutes: Int?,
    val minimumDurationMinutes: Int?,
    val targetQuantity: Double?,
    val quantityUnit: String,
    val defaultInstruction: String,
    val recurrenceType: String,
    val daysOfWeekMask: Int,
    val intervalDays: Int,
    val timesOfDayCsv: String,
    val defaultRecoveryMinutes: Int,
    val isBuiltIn: Boolean,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "progression_steps",
    foreignKeys = [
        ForeignKey(
            entity = BehaviorEntity::class,
            parentColumns = ["id"],
            childColumns = ["behaviorId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("behaviorId")],
)
data class ProgressionStepEntity(
    @PrimaryKey val id: String,
    val behaviorId: String,
    val weekIndex: Int,
    val targetDurationMinutes: Int?,
    val targetQuantity: Double?,
)

/** Which checklist items were ticked for a particular occurrence. */
@Entity(
    tableName = "checklist_ticks",
    primaryKeys = ["instanceId", "checklistItemId"],
    indices = [Index("instanceId")],
)
data class ChecklistTickEntity(
    val instanceId: String,
    val checklistItemId: String,
    val isChecked: Boolean,
)

/** Coaching messages that have been generated and shown, so they are not repeated endlessly. */
@Entity(
    tableName = "coach_messages",
    indices = [Index("behaviorId"), Index("createdAtMillis"), Index("dedupKey", unique = true)],
)
data class CoachMessageEntity(
    @PrimaryKey val id: String,
    val behaviorId: String?,
    val kind: String,
    val severity: String,
    val title: String,
    val body: String,
    val recommendation: String?,
    val createdAtMillis: Long,
    val acknowledgedAtMillis: Long?,
    /** Kind plus behavior plus week, so the same observation is not surfaced twice in a week. */
    val dedupKey: String,
)
