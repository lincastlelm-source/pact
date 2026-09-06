package com.pact.coach.domain.model

/**
 * Every enum here is persisted by [Enum.name]. Never reorder-by-ordinal and never rename a
 * constant without a Room migration plus a backup-import alias, because old backups and old
 * database rows carry the old spelling.
 */

enum class GoalStatus { ACTIVE, PAUSED, COMPLETED, ARCHIVED }

enum class Priority { LOW, MEDIUM, HIGH, CRITICAL }

enum class Importance { LOW, NORMAL, HIGH, CRITICAL }

enum class Difficulty { EASY, MEDIUM, HARD }

/**
 * How hard the app pushes when an intervention fires.
 *
 * - [GENTLE]  a quiet notification, dismissible like any other
 * - [COACH]   heads-up notification plus one escalation reminder
 * - [STRONG]  full-screen intervention that asks for an explicit decision before it goes away
 *
 * STRONG never locks the user out: the back gesture and a visible "Decide later" affordance
 * always work. See docs/ALARM_SYSTEM.md.
 */
enum class EnforcementLevel { GENTLE, COACH, STRONG }

/**
 * What "done" means for a behavior. Orthogonal to [Behavior.isTimeBased]: a checklist can be
 * anchored to 06:30, and a duration behavior can float freely across the day.
 */
enum class Measurement { COMPLETION, DURATION, QUANTITY, CHECKLIST }

/**
 * The four archetypes offered in the behavior creation flow. Each is a preset over
 * ([Behavior.isTimeBased], [Behavior.measurement]); the user can refine both afterwards.
 */
enum class BehaviorArchetype(
    val label: String,
    val isTimeBased: Boolean,
    val measurement: Measurement,
) {
    TIME_BASED("Time-based", true, Measurement.DURATION),
    FLEXIBLE("Flexible", false, Measurement.COMPLETION),
    QUANTITY("Quantity-based", false, Measurement.QUANTITY),
    CHECKLIST("Checklist", true, Measurement.CHECKLIST),
}

enum class RecurrenceType {
    ONCE,
    DAILY,
    WEEKDAYS,
    WEEKENDS,
    DAYS_OF_WEEK,
    EVERY_N_DAYS,
    TIMES_PER_WEEK,
}

/**
 * Lifecycle of a single scheduled occurrence. Transitions are enforced centrally by
 * [com.pact.coach.domain.state.InstanceStateMachine]; nothing else may write this field.
 */
enum class InstanceState {
    SCHEDULED,
    DUE,
    COMMITTED,
    RECOVERED,
    COMPLETED,
    PASSED,
    MISSED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == PASSED || this == MISSED ||
            this == CANCELLED || this == RECOVERED

    /** Terminal states that represent the chain actually being resolved by doing the thing. */
    val isSuccess: Boolean get() = this == COMPLETED
}

/** How much of the behavior was actually done. */
enum class CompletionQuality { TARGET_COMPLETED, MINIMUM_COMPLETED, PARTIAL }

enum class PassReason(val label: String) {
    BUSY("Busy"),
    TIRED("Tired"),
    FORGOT("Forgot"),
    UNWELL("Not feeling well"),
    UNEXPECTED_EVENT("Unexpected event"),
    NOT_IMPORTANT_TODAY("Not important today"),
    SCHEDULE_CONFLICT("Schedule conflict"),
    OTHER("Other"),
}

/** Append-only audit log of everything the user did. Never mutated, only inserted. */
enum class ActionType {
    SCHEDULED,
    FIRED,
    COMMIT,
    RECOVER,
    PASS,
    COMPLETE,
    MISS,
    CANCEL,
    REOPEN,
    EDIT,
}

enum class ExceptionType { SKIP, PAUSE, VACATION, HOLIDAY }

enum class CoachPersonality { SUPPORTIVE, PROFESSIONAL, DIRECT, MOTIVATIONAL, MINIMAL }

enum class CoachIntensity { LOW, NORMAL, HIGH }

enum class ReflectionFrequency { NEVER, SOMETIMES, ALWAYS }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class InsightSeverity { POSITIVE, NEUTRAL, ATTENTION }

enum class InsightKind {
    CONSISTENCY,
    STREAK,
    RECOVERY,
    LOW_COMPLETION,
    TIME_OF_DAY,
    DAY_OF_WEEK,
    FREQUENT_PASS,
    MINIMUM_VS_TARGET,
    CONFLICT,
    GETTING_STARTED,
}

/** Bit positions are ISO-8601: Monday = 1 .. Sunday = 7, stored as (1 shl (value - 1)). */
object DayMask {
    const val NONE = 0
    const val WEEKDAYS = 0b0011111   // Mon..Fri
    const val WEEKENDS = 0b1100000   // Sat, Sun
    const val ALL = 0b1111111

    fun of(vararg isoDays: Int): Int = isoDays.fold(0) { acc, d -> acc or bit(d) }
    fun bit(isoDay: Int): Int = 1 shl (isoDay - 1)
    fun contains(mask: Int, isoDay: Int): Boolean = mask and bit(isoDay) != 0
    fun toIsoDays(mask: Int): List<Int> = (1..7).filter { contains(mask, it) }
    fun toggle(mask: Int, isoDay: Int): Int = mask xor bit(isoDay)
}

/** Suggested categories. The user is never restricted to these; the field is free text. */
object Categories {
    val SUGGESTED = listOf(
        "Health", "Fitness", "Learning", "Career", "Productivity",
        "Personal", "Finance", "Relationships", "Spiritual",
    )
}
