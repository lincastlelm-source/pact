package com.pact.coach.data.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The on-disk backup format.
 *
 * Deliberately a flat mirror of the database rather than a nested object graph: it is easy to
 * validate field by field, easy to extend, and an old file can be read by a newer app without
 * guesswork. Every field carries a default so adding a column in a later version does not make
 * older backups unreadable.
 *
 * Nothing here is executable. Import parses data, validates it, and inserts rows; it never
 * evaluates anything from the file.
 */
@Serializable
data class BackupEnvelope(
    /** Bumped whenever the format changes incompatibly. See [BackupService.CURRENT_VERSION]. */
    val formatVersion: Int,
    val appVersionName: String = "",
    val exportedAtMillis: Long = 0L,
    val deviceZoneId: String = "UTC",

    val goals: List<BackupGoal> = emptyList(),
    val behaviors: List<BackupBehavior> = emptyList(),
    val schedules: List<BackupSchedule> = emptyList(),
    val dailyInstructions: List<BackupDailyInstruction> = emptyList(),
    val checklistItems: List<BackupChecklistItem> = emptyList(),
    val checklistTicks: List<BackupChecklistTick> = emptyList(),
    val instances: List<BackupInstance> = emptyList(),
    val events: List<BackupEvent> = emptyList(),
    val exceptions: List<BackupException> = emptyList(),
    val templates: List<BackupTemplate> = emptyList(),
    val progressionSteps: List<BackupProgressionStep> = emptyList(),
    val settings: BackupSettings? = null,
) {
    val totalRows: Int
        get() = goals.size + behaviors.size + schedules.size + dailyInstructions.size +
            checklistItems.size + instances.size + exceptions.size + templates.size +
            progressionSteps.size
}

@Serializable
data class BackupGoal(
    val id: String,
    val name: String,
    val description: String = "",
    val category: String = "",
    val whyItMatters: String = "",
    val startDateEpochDay: Long,
    val targetDateEpochDay: Long? = null,
    val priority: String = "MEDIUM",
    val status: String = "ACTIVE",
    val colorSeed: Int = 0,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
)

@Serializable
data class BackupBehavior(
    val id: String,
    val goalId: String? = null,
    val name: String,
    val description: String = "",
    val category: String = "",
    val isTimeBased: Boolean = true,
    val measurement: String = "DURATION",
    val importance: String = "NORMAL",
    val difficulty: String = "MEDIUM",
    val enforcement: String = "COACH",
    val targetDurationMinutes: Int? = null,
    val minimumDurationMinutes: Int? = null,
    val targetQuantity: Double? = null,
    val minimumQuantity: Double? = null,
    val quantityUnit: String = "",
    val defaultInstruction: String = "",
    val notes: String = "",
    val reminderEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    /**
     * Sound URIs are exported for convenience but may not resolve on another device or after a
     * reinstall. Import keeps the value; the notification layer falls back to the default sound
     * if it cannot be read.
     */
    val soundUri: String? = null,
    val vibrationPattern: String = "default",
    val preAlertMinutes: Int? = null,
    val escalationMinutes: Int? = null,
    val channelVersion: Int = 1,
    val recoveryEnabled: Boolean = true,
    val maxRecoveriesPerInstance: Int = 3,
    val defaultRecoveryMinutes: Int = 30,
    val missWindowMinutes: Int = 120,
    val reflectionFrequency: String = "SOMETIMES",
    val allowPartialChecklist: Boolean = true,
    val requirePassReason: Boolean = false,
    val isActive: Boolean = true,
    val progressionEnabled: Boolean = false,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
)

@Serializable
data class BackupSchedule(
    val id: String,
    val behaviorId: String,
    val recurrenceType: String = "DAILY",
    val daysOfWeekMask: Int = 127,
    val intervalDays: Int = 1,
    val anchorDateEpochDay: Long? = null,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long? = null,
    val timesOfDayCsv: String = "",
    val timesPerWeek: Int? = null,
    val timesPerDay: Int = 1,
    val isEnabled: Boolean = true,
)

@Serializable
data class BackupDailyInstruction(
    val id: String,
    val behaviorId: String,
    val dayOfWeek: Int? = null,
    val dateEpochDay: Long? = null,
    val occurrenceIndex: Int? = null,
    val title: String = "",
    val instructions: String = "",
    val durationMinutes: Int? = null,
    val quantityTarget: Double? = null,
    val notes: String = "",
    val referenceUri: String? = null,
    val imageUri: String? = null,
    val isRestDay: Boolean = false,
)

@Serializable
data class BackupChecklistItem(
    val id: String,
    val behaviorId: String,
    val dailyInstructionId: String? = null,
    val position: Int = 0,
    val text: String,
    val isRequired: Boolean = true,
)

@Serializable
data class BackupChecklistTick(
    val instanceId: String,
    val checklistItemId: String,
    val isChecked: Boolean = false,
)

@Serializable
data class BackupInstance(
    val id: String,
    val behaviorId: String,
    val scheduleId: String? = null,
    val scheduledDateEpochDay: Long,
    val scheduledTimeMinutes: Int? = null,
    val scheduledAtUtcMillis: Long? = null,
    val zoneId: String = "UTC",
    val occurrenceIndex: Int = 0,
    val state: String = "SCHEDULED",
    val originInstanceId: String? = null,
    val rootInstanceId: String? = null,
    val recoveryDepth: Int = 0,
    val firedAtMillis: Long? = null,
    val respondedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
    val completionQuality: String? = null,
    val actualDurationMinutes: Int? = null,
    val actualQuantity: Double? = null,
    val rating: Int? = null,
    val note: String = "",
    val passReason: String? = null,
    val passNote: String = "",
    val alarmRequestCode: Int = 0,
    val dedupKey: String,
    val createdAtMillis: Long = 0,
)

@Serializable
data class BackupEvent(
    val id: String,
    val instanceId: String? = null,
    val behaviorId: String? = null,
    val type: String,
    val atMillis: Long,
    val fromState: String? = null,
    val toState: String? = null,
    val detail: String = "",
)

@Serializable
data class BackupException(
    val id: String,
    val behaviorId: String? = null,
    val type: String = "SKIP",
    val startDateEpochDay: Long,
    val endDateEpochDay: Long,
    val note: String = "",
)

@Serializable
data class BackupTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    val category: String = "",
    val isTimeBased: Boolean = true,
    val measurement: String = "DURATION",
    val importance: String = "NORMAL",
    val difficulty: String = "MEDIUM",
    val enforcement: String = "COACH",
    val targetDurationMinutes: Int? = null,
    val minimumDurationMinutes: Int? = null,
    val targetQuantity: Double? = null,
    val quantityUnit: String = "",
    val defaultInstruction: String = "",
    val recurrenceType: String = "DAILY",
    val daysOfWeekMask: Int = 127,
    val intervalDays: Int = 1,
    val timesOfDayCsv: String = "",
    val defaultRecoveryMinutes: Int = 30,
    val isBuiltIn: Boolean = false,
    val createdAtMillis: Long = 0,
)

@Serializable
data class BackupProgressionStep(
    val id: String,
    val behaviorId: String,
    val weekIndex: Int,
    val targetDurationMinutes: Int? = null,
    val targetQuantity: Double? = null,
)

@Serializable
data class BackupSettings(
    @SerialName("themeMode") val themeMode: String = "SYSTEM",
    val use24HourClock: Boolean = true,
    val firstDayOfWeek: Int = 1,
    val defaultEnforcement: String = "COACH",
    val defaultSoundUri: String? = null,
    val defaultSoundEnabled: Boolean = true,
    val defaultVibrationEnabled: Boolean = true,
    val defaultPreAlertMinutes: Int = 0,
    val defaultRecoveryMinutes: Int = 30,
    val coachPersonality: String = "SUPPORTIVE",
    val coachIntensity: String = "NORMAL",
    val reflectionFrequency: String = "SOMETIMES",
)
