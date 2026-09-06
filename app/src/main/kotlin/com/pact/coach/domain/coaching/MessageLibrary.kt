package com.pact.coach.domain.coaching

import com.pact.coach.domain.model.CoachPersonality
import kotlin.math.roundToInt

/**
 * Every sentence the coach can say, as local templates. There is no model here and no network
 * call: the app picks a template by rule and fills in numbers the user can verify against their
 * own history.
 *
 * Tone rules that the templates are held to:
 *  - never shame, never threaten, never imply the user has ruined anything
 *  - never claim insight the data does not support
 *  - always leave the decision with the user
 */
object MessageLibrary {

    /** Shown on the intervention screen when the behavior comes due. */
    fun nudge(personality: CoachPersonality, behaviorName: String): String = when (personality) {
        CoachPersonality.SUPPORTIVE -> "It is time for $behaviorName. Whatever you can manage counts."
        CoachPersonality.PROFESSIONAL -> "Scheduled now: $behaviorName."
        CoachPersonality.DIRECT -> "$behaviorName. Now."
        CoachPersonality.MOTIVATIONAL -> "$behaviorName is up. This is the part that adds up."
        CoachPersonality.MINIMAL -> behaviorName
    }

    fun committed(personality: CoachPersonality): String = when (personality) {
        CoachPersonality.SUPPORTIVE -> "Good. Take it at your own pace."
        CoachPersonality.PROFESSIONAL -> "Committed. Mark it complete when you are done."
        CoachPersonality.DIRECT -> "Started. Finish it."
        CoachPersonality.MOTIVATIONAL -> "That is the hard part done. Keep going."
        CoachPersonality.MINIMAL -> "In progress."
    }

    fun completed(personality: CoachPersonality): String = when (personality) {
        CoachPersonality.SUPPORTIVE -> "Well done. You followed through."
        CoachPersonality.PROFESSIONAL -> "Recorded as complete."
        CoachPersonality.DIRECT -> "Done."
        CoachPersonality.MOTIVATIONAL -> "That is one more in the bank."
        CoachPersonality.MINIMAL -> "Complete."
    }

    fun completedMinimum(personality: CoachPersonality): String = when (personality) {
        CoachPersonality.SUPPORTIVE -> "You did the minimum on a hard day. That still counts."
        CoachPersonality.PROFESSIONAL -> "Minimum action recorded."
        CoachPersonality.DIRECT -> "Minimum done. Better than nothing."
        CoachPersonality.MOTIVATIONAL -> "Showing up on a bad day is the whole skill."
        CoachPersonality.MINIMAL -> "Minimum complete."
    }

    fun recovered(personality: CoachPersonality, minutes: Int): String {
        val when1 = if (minutes >= 60) "${minutes / 60} h" else "$minutes min"
        return when (personality) {
            CoachPersonality.SUPPORTIVE -> "No problem. Moved to $when1 from now."
            CoachPersonality.PROFESSIONAL -> "Rescheduled for $when1 from now."
            CoachPersonality.DIRECT -> "Moved $when1. Do it then."
            CoachPersonality.MOTIVATIONAL -> "Pushed by $when1. Coming back to it is what matters."
            CoachPersonality.MINIMAL -> "Moved $when1."
        }
    }

    fun passed(personality: CoachPersonality): String = when (personality) {
        CoachPersonality.SUPPORTIVE -> "Noted. Skipping on purpose is a decision, not a failure."
        CoachPersonality.PROFESSIONAL -> "Recorded as passed."
        CoachPersonality.DIRECT -> "Passed."
        CoachPersonality.MOTIVATIONAL -> "Skipped this one. The next one is still there."
        CoachPersonality.MINIMAL -> "Passed."
    }

    /** Offered when the user hesitates, to lower the bar rather than raise the pressure. */
    fun minimumOffer(minimumLabel: String): String =
        "You do not have to do the full session. Your minimum is $minimumLabel."

    // --- Insight bodies -----------------------------------------------------------------

    fun strongConsistency(name: String, completed: Int, total: Int): String =
        "You completed $completed of your last $total $name sessions."

    fun streak(name: String, count: Int): String =
        "You have completed $name $count times in a row."

    fun goodRecovery(score: Double): String =
        "When you do not act immediately, you come back to it ${percent(score)} of the time."

    fun frequentRecovery(name: String, rate: Double): String =
        "You move $name to a later time ${percent(rate)} of the time. The planned slot may be the wrong one."

    fun lowCompletion(name: String, rate: Double): String =
        "$name is completing ${percent(rate)} of the time. It may be worth reviewing the schedule or the target."

    fun timeOfDay(name: String, betterHour: Int, betterRate: Double, worseHour: Int, worseRate: Double): String =
        "You complete $name ${percent(betterRate)} of the time at ${hour(betterHour)}, " +
            "compared with ${percent(worseRate)} at ${hour(worseHour)}."

    fun dayOfWeek(name: String, day: String, rate: Double): String =
        "$day is your weakest day for $name, at ${percent(rate)}."

    fun frequentPass(name: String, count: Int): String =
        "You have passed $name $count times this week."

    fun minimumHeavy(name: String, share: Double): String =
        "${percent(share)} of your $name completions are the minimum rather than the full target. " +
            "Lowering the target, or ramping it up gradually, may fit better."

    fun gettingStarted(name: String): String =
        "$name is new. There is not enough history yet for useful patterns, so keep going for a week or so."

    fun conflict(a: String, b: String): String =
        "$a and $b are scheduled at the same time."

    // --- Recommendations ----------------------------------------------------------------

    fun suggestMoveTime(name: String, fromHour: Int, toHour: Int): String =
        "Consider moving $name from ${hour(fromHour)} to ${hour(toHour)}."

    fun suggestReviewSchedule(name: String): String =
        "Consider reviewing the schedule for $name."

    fun suggestLowerTarget(name: String): String =
        "Consider lowering the target for $name, or adding a gradual ramp."

    // --- Helpers ------------------------------------------------------------------------

    fun percent(value: Double): String = "${(value * 100).roundToInt()}%"

    fun hour(hour: Int): String = String.format(java.util.Locale.ROOT, "%02d:00", hour)
}
