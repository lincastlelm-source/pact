package com.pact.coach.domain.model

import com.pact.coach.core.util.Ids
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Domain models. These are what the UI, the scheduler and the coaching engine talk about.
 * Room entities live in `data.db.entity` and are mapped across in `data.mapper`; keeping them
 * separate means a storage change never ripples into the scheduling logic.
 */

data class Goal(
    val id: String = Ids.new(),
    val name: String,
    val description: String = "",
    val category: String = "Personal",
    val whyItMatters: String = "",
    val startDate: LocalDate,
    val targetDate: LocalDate? = null,
    val priority: Priority = Priority.MEDIUM,
    val status: GoalStatus = GoalStatus.ACTIVE,
    val colorSeed: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isActive: Boolean get() = status == GoalStatus.ACTIVE
}

data class Behavior(
    val id: String = Ids.new(),
    val goalId: String? = null,
    val name: String,
    val description: String = "",
    val category: String = "",

    /** When true the behavior owns clock times and fires alarms; when false it just needs doing. */
    val isTimeBased: Boolean = true,
    val measurement: Measurement = Measurement.DURATION,

    val importance: Importance = Importance.NORMAL,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val enforcement: EnforcementLevel = EnforcementLevel.COACH,

    /** The full session the user is aiming for. Null when the behavior is not duration-based. */
    val targetDurationMinutes: Int? = null,

    /**
     * The "you can always manage this much" floor. The intervention screen offers it explicitly
     * so a bad day produces a small win instead of a miss.
     */
    val minimumDurationMinutes: Int? = null,

    val targetQuantity: Double? = null,
    val minimumQuantity: Double? = null,
    val quantityUnit: String = "",

    val defaultInstruction: String = "",
    val notes: String = "",

    val reminderEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    /** Null means "use the app default sound". Stored as a content:// or android.resource:// URI. */
    val soundUri: String? = null,
    val vibrationPattern: String = "default",
    val preAlertMinutes: Int? = null,
    val escalationMinutes: Int? = null,

    /**
     * Notification channels are immutable once created, so changing sound or vibration bumps this
     * counter and the app creates a fresh channel.
     */
    val channelVersion: Int = 1,

    val recoveryEnabled: Boolean = true,
    val maxRecoveriesPerInstance: Int = 3,
    val defaultRecoveryMinutes: Int = 30,

    /** How long after the scheduled time an unanswered intervention becomes MISSED. */
    val missWindowMinutes: Int = 120,

    val reflectionFrequency: ReflectionFrequency = ReflectionFrequency.SOMETIMES,
    val allowPartialChecklist: Boolean = true,
    val requirePassReason: Boolean = false,

    val isActive: Boolean = true,
    val progressionEnabled: Boolean = false,

    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val hasMinimum: Boolean
        get() = minimumDurationMinutes != null || minimumQuantity != null

    fun minimumLabel(): String? = when (measurement) {
        Measurement.DURATION -> minimumDurationMinutes?.let { "$it min" }
        Measurement.QUANTITY -> minimumQuantity?.let { formatQuantity(it) }
        else -> null
    }

    fun targetLabel(): String? = when (measurement) {
        Measurement.DURATION -> targetDurationMinutes?.let { "$it min" }
        Measurement.QUANTITY -> targetQuantity?.let { formatQuantity(it) }
        else -> null
    }

    fun formatQuantity(value: Double): String {
        val number = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
        return if (quantityUnit.isBlank()) number else "$number $quantityUnit"
    }
}

/**
 * A recurrence rule plus the clock times it fires at. One behavior may own several schedules
 * (for example weekday mornings and a longer weekend session).
 */
data class Schedule(
    val id: String = Ids.new(),
    val behaviorId: String,
    val recurrenceType: RecurrenceType = RecurrenceType.DAILY,
    val daysOfWeekMask: Int = DayMask.ALL,
    val intervalDays: Int = 1,
    val anchorDate: LocalDate? = null,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    /** Sorted and de-duplicated. Empty for non-time-based behaviors. Its index is the occurrence. */
    val timesOfDay: List<LocalTime> = emptyList(),
    /** Only meaningful for [RecurrenceType.TIMES_PER_WEEK]. */
    val timesPerWeek: Int? = null,
    /** Daily target count for flexible behaviors that are not pinned to clock times. */
    val timesPerDay: Int = 1,
    val isEnabled: Boolean = true,
) {
    val occurrenceCount: Int
        get() = if (timesOfDay.isNotEmpty()) timesOfDay.size else timesPerDay.coerceAtLeast(1)
}

/**
 * What the behavior actually means on a given day. "Exercise" stays one behavior while Monday
 * is a 20-minute run and Thursday is strength training.
 *
 * Resolution is most-specific-first: exact date plus occurrence, exact date, weekday plus
 * occurrence, weekday, then the behavior default.
 */
data class DailyInstruction(
    val id: String = Ids.new(),
    val behaviorId: String,
    /** ISO day-of-week 1..7, or null when this row is keyed by [date] or is the default. */
    val dayOfWeek: Int? = null,
    val date: LocalDate? = null,
    /** Null means "every occurrence that day"; 0-based otherwise. */
    val occurrenceIndex: Int? = null,
    val title: String = "",
    val instructions: String = "",
    val durationMinutes: Int? = null,
    val quantityTarget: Double? = null,
    val notes: String = "",
    /** Locally held reference only. Never fetched over the network. */
    val referenceUri: String? = null,
    val imageUri: String? = null,
    val isRestDay: Boolean = false,
) {
    val isDefault: Boolean get() = dayOfWeek == null && date == null

    /** Higher wins when several rows match the same day. */
    val specificity: Int
        get() = (if (date != null) 4 else 0) +
            (if (dayOfWeek != null) 2 else 0) +
            (if (occurrenceIndex != null) 1 else 0)
}

data class ChecklistItem(
    val id: String = Ids.new(),
    val behaviorId: String,
    /** When set, this item only applies to that day's instruction. */
    val dailyInstructionId: String? = null,
    val position: Int,
    val text: String,
    val isRequired: Boolean = true,
)

/**
 * A single occurrence. Generated ahead of time over a bounded horizon, never infinitely.
 * Recovery creates a new instance pointing back at the original via [originInstanceId] so the
 * history keeps both the plan and what actually happened.
 */
data class BehaviorInstance(
    val id: String = Ids.new(),
    val behaviorId: String,
    val scheduleId: String?,
    val scheduledDate: LocalDate,
    /** Null for behaviors that are not pinned to a clock time. */
    val scheduledTime: LocalTime?,
    /** Absolute firing moment, recomputed if the device time zone changes. */
    val scheduledAtUtcMillis: Long?,
    val zoneId: String,
    val occurrenceIndex: Int,
    val state: InstanceState = InstanceState.SCHEDULED,

    val originInstanceId: String? = null,
    val rootInstanceId: String? = null,
    val recoveryDepth: Int = 0,

    val firedAtMillis: Long? = null,
    val respondedAtMillis: Long? = null,
    val completedAtMillis: Long? = null,

    val completionQuality: CompletionQuality? = null,
    val actualDurationMinutes: Int? = null,
    val actualQuantity: Double? = null,
    val rating: Int? = null,
    val note: String = "",
    val passReason: PassReason? = null,
    val passNote: String = "",

    val alarmRequestCode: Int = 0,
    /** Unique across the table; makes horizon regeneration idempotent. */
    val dedupKey: String,
    val createdAtMillis: Long,
) {
    val isRecovery: Boolean get() = originInstanceId != null

    /** The id that groups an original occurrence with all of its recovery attempts. */
    val chainId: String get() = rootInstanceId ?: id

    /** Seconds between the alarm firing and the user answering it. */
    val responseDelaySeconds: Long?
        get() {
            val fired = firedAtMillis ?: scheduledAtUtcMillis ?: return null
            val responded = respondedAtMillis ?: return null
            return ((responded - fired) / 1000).coerceAtLeast(0)
        }
}

data class BehaviorException(
    val id: String = Ids.new(),
    /** Null applies the exception to every behavior (a holiday or a vacation block). */
    val behaviorId: String?,
    val type: ExceptionType,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val note: String = "",
) {
    fun covers(date: LocalDate): Boolean = !date.isBefore(startDate) && !date.isAfter(endDate)
}

/** A reusable starting point for new behaviors. Stores the same fields a behavior does. */
data class BehaviorTemplate(
    val id: String = Ids.new(),
    val name: String,
    val description: String = "",
    val category: String = "",
    val isTimeBased: Boolean = true,
    val measurement: Measurement = Measurement.DURATION,
    val importance: Importance = Importance.NORMAL,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val enforcement: EnforcementLevel = EnforcementLevel.COACH,
    val targetDurationMinutes: Int? = null,
    val minimumDurationMinutes: Int? = null,
    val targetQuantity: Double? = null,
    val quantityUnit: String = "",
    val defaultInstruction: String = "",
    val recurrenceType: RecurrenceType = RecurrenceType.DAILY,
    val daysOfWeekMask: Int = DayMask.ALL,
    val intervalDays: Int = 1,
    val timesOfDay: List<LocalTime> = emptyList(),
    val defaultRecoveryMinutes: Int = 30,
    val isBuiltIn: Boolean = false,
    val createdAt: Instant,
)

/**
 * Optional manual ramp. Week 1 is 10 minutes, week 4 is 30. The app never escalates a target on
 * its own; the user writes these steps.
 */
data class ProgressionStep(
    val id: String = Ids.new(),
    val behaviorId: String,
    /** 0-based week offset from the behavior's start date. */
    val weekIndex: Int,
    val targetDurationMinutes: Int? = null,
    val targetQuantity: Double? = null,
)

/** A resolved, ready-to-display plan for one occurrence. */
data class ResolvedInstruction(
    val title: String,
    val instructions: String,
    val durationMinutes: Int?,
    val quantityTarget: Double?,
    val notes: String,
    val referenceUri: String?,
    val imageUri: String?,
    val isRestDay: Boolean,
    val checklist: List<ChecklistItem>,
)

/** Per-instance checklist tick state. */
data class ChecklistTick(
    val instanceId: String,
    val checklistItemId: String,
    val isChecked: Boolean,
)
