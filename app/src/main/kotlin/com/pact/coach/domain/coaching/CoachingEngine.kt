package com.pact.coach.domain.coaching

import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.CoachIntensity
import com.pact.coach.domain.model.InsightKind
import com.pact.coach.domain.model.InsightSeverity
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.model.Schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * A local, deterministic coach.
 *
 * Given the statistics for a behavior it fires a fixed set of rules and produces observations
 * and, where the data supports one, a concrete suggestion. Nothing is inferred by a model,
 * nothing leaves the device, and the same input always produces the same output, which is what
 * makes the whole thing testable.
 *
 * Every suggestion is a proposal. [ScheduleSuggestion] is only ever applied when the user taps
 * "Apply change"; the engine has no write access to schedules.
 */
class CoachingEngine(
    private val intensity: CoachIntensity = CoachIntensity.NORMAL,
) {

    fun analyse(
        behavior: Behavior,
        stats: BehaviorStats,
        schedules: List<Schedule>,
        recentInstances: List<BehaviorInstance>,
        today: LocalDate,
    ): List<CoachInsight> {
        val insights = mutableListOf<CoachInsight>()

        if (!stats.hasEnoughData) {
            insights += CoachInsight(
                behaviorId = behavior.id,
                kind = InsightKind.GETTING_STARTED,
                severity = InsightSeverity.NEUTRAL,
                title = "Still early",
                body = MessageLibrary.gettingStarted(behavior.name),
            )
            return insights
        }

        insights += positiveReinforcement(behavior, stats)
        insights += streakInsight(behavior, stats)
        insights += recoveryInsights(behavior, stats)
        insights += lowCompletionInsight(behavior, stats)
        insights += timeOfDayInsight(behavior, stats, schedules)
        insights += dayOfWeekInsight(behavior, stats)
        insights += frequentPassInsight(behavior, recentInstances, today)
        insights += minimumVsTargetInsight(behavior, stats)

        val limit = when (intensity) {
            CoachIntensity.LOW -> 1
            CoachIntensity.NORMAL -> 3
            CoachIntensity.HIGH -> 6
        }

        // Attention items first, then positives, so a problem is never buried under praise.
        return insights
            .sortedWith(compareBy({ it.severity.sortOrder }, { it.kind.ordinal }))
            .take(limit)
    }

    // Rule: completion_rate > 85% -> positive reinforcement.
    private fun positiveReinforcement(behavior: Behavior, stats: BehaviorStats): List<CoachInsight> {
        if (stats.completionRate < 0.85) return emptyList()
        return listOf(
            CoachInsight(
                behaviorId = behavior.id,
                kind = InsightKind.CONSISTENCY,
                severity = InsightSeverity.POSITIVE,
                title = "Consistent",
                body = MessageLibrary.strongConsistency(
                    behavior.name,
                    stats.completed,
                    stats.resolvedChains,
                ),
            ),
        )
    }

    private fun streakInsight(behavior: Behavior, stats: BehaviorStats): List<CoachInsight> {
        if (stats.currentStreak < 3) return emptyList()
        return listOf(
            CoachInsight(
                behaviorId = behavior.id,
                kind = InsightKind.STREAK,
                severity = InsightSeverity.POSITIVE,
                title = "On a run",
                body = MessageLibrary.streak(behavior.name, stats.currentStreak),
            ),
        )
    }

    // Rule: recovery_rate > 40% -> suggest reviewing the schedule.
    // Rule: recovery_score high -> reinforce that coming back is working.
    private fun recoveryInsights(behavior: Behavior, stats: BehaviorStats): List<CoachInsight> {
        val out = mutableListOf<CoachInsight>()

        if (stats.recoveryRate > 0.40) {
            out += CoachInsight(
                behaviorId = behavior.id,
                kind = InsightKind.RECOVERY,
                severity = InsightSeverity.ATTENTION,
                title = "Often moved later",
                body = MessageLibrary.frequentRecovery(behavior.name, stats.recoveryRate),
                recommendation = MessageLibrary.suggestReviewSchedule(behavior.name),
            )
        }

        if (stats.chainsWithRecovery >= 3 && stats.recoveryScore >= 0.70) {
            out += CoachInsight(
                behaviorId = behavior.id,
                kind = InsightKind.RECOVERY,
                severity = InsightSeverity.POSITIVE,
                title = "You come back to it",
                body = MessageLibrary.goodRecovery(stats.recoveryScore),
            )
        }

        return out
    }

    // Rule: completion_rate < 50% and enough history -> recommend a schedule review.
    private fun lowCompletionInsight(behavior: Behavior, stats: BehaviorStats): List<CoachInsight> {
        if (stats.completionRate >= 0.50 || stats.resolvedChains < 7) return emptyList()
        return listOf(
            CoachInsight(
                behaviorId = behavior.id,
                kind = InsightKind.LOW_COMPLETION,
                severity = InsightSeverity.ATTENTION,
                title = "Not landing",
                body = MessageLibrary.lowCompletion(behavior.name, stats.completionRate),
                recommendation = MessageLibrary.suggestReviewSchedule(behavior.name),
            ),
        )
    }

    /**
     * Rule: if one scheduled hour clearly outperforms another, surface it and offer to move the
     * weaker slot. Requires a real gap (25 points) and enough samples at both hours, so a single
     * good morning cannot trigger a schedule change suggestion.
     */
    private fun timeOfDayInsight(
        behavior: Behavior,
        stats: BehaviorStats,
        schedules: List<Schedule>,
    ): List<CoachInsight> {
        val eligible = stats.completionsByHour.filterValues { it.total >= 3 }
        if (eligible.size < 2) return emptyList()

        val best = eligible.maxByOrNull { it.value.rate } ?: return emptyList()
        val worst = eligible.minByOrNull { it.value.rate } ?: return emptyList()
        if (best.key == worst.key) return emptyList()
        if (best.value.rate - worst.value.rate < 0.25) return emptyList()

        val suggestion = schedules
            .firstOrNull { schedule -> schedule.timesOfDay.any { it.hour == worst.key } }
            ?.let { schedule ->
                val from = schedule.timesOfDay.first { it.hour == worst.key }
                ScheduleSuggestion(
                    behaviorId = behavior.id,
                    scheduleId = schedule.id,
                    fromTime = from,
                    toTime = LocalTime.of(best.key, from.minute),
                )
            }

        return listOf(
            CoachInsight(
                behaviorId = behavior.id,
                kind = InsightKind.TIME_OF_DAY,
                severity = InsightSeverity.NEUTRAL,
                title = "Better at a different hour",
                body = MessageLibrary.timeOfDay(
                    behavior.name,
                    best.key, best.value.rate,
                    worst.key, worst.value.rate,
                ),
                recommendation = suggestion?.let {
                    MessageLibrary.suggestMoveTime(behavior.name, worst.key, best.key)
                },
                scheduleSuggestion = suggestion,
            ),
        )
    }

    private fun dayOfWeekInsight(behavior: Behavior, stats: BehaviorStats): List<CoachInsight> {
        val eligible = stats.completionsByDayOfWeek.filterValues { it.total >= 3 }
        if (eligible.size < 3) return emptyList()
        val worst = eligible.minByOrNull { it.value.rate } ?: return emptyList()
        if (worst.value.rate > 0.40) return emptyList()

        return listOf(
            CoachInsight(
                behaviorId = behavior.id,
                kind = InsightKind.DAY_OF_WEEK,
                severity = InsightSeverity.NEUTRAL,
                title = "One weak day",
                body = MessageLibrary.dayOfWeek(
                    behavior.name,
                    worst.key.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                    worst.value.rate,
                ),
            ),
        )
    }

    // Rule: repeatedly passed in the last 7 days -> possible scheduling problem.
    private fun frequentPassInsight(
        behavior: Behavior,
        recentInstances: List<BehaviorInstance>,
        today: LocalDate,
    ): List<CoachInsight> {
        val weekAgo = today.minusDays(6)
        val passes = recentInstances.count {
            it.state == InstanceState.PASSED && !it.scheduledDate.isBefore(weekAgo)
        }
        if (passes < 3) return emptyList()

        return listOf(
            CoachInsight(
                behaviorId = behavior.id,
                kind = InsightKind.FREQUENT_PASS,
                severity = InsightSeverity.ATTENTION,
                title = "Passed several times",
                body = MessageLibrary.frequentPass(behavior.name, passes),
                recommendation = MessageLibrary.suggestReviewSchedule(behavior.name),
            ),
        )
    }

    // Rule: minimum completions high but full-target completions low -> suggest a smaller target.
    private fun minimumVsTargetInsight(behavior: Behavior, stats: BehaviorStats): List<CoachInsight> {
        if (stats.completed < 4) return emptyList()
        if (stats.minimumShare < 0.60) return emptyList()
        return listOf(
            CoachInsight(
                behaviorId = behavior.id,
                kind = InsightKind.MINIMUM_VS_TARGET,
                severity = InsightSeverity.NEUTRAL,
                title = "Mostly the minimum",
                body = MessageLibrary.minimumHeavy(behavior.name, stats.minimumShare),
                recommendation = MessageLibrary.suggestLowerTarget(behavior.name),
            ),
        )
    }
}

/**
 * A concrete, user-approvable schedule change. The engine only ever produces these; applying one
 * is an explicit action on the weekly review or insights screen.
 */
data class ScheduleSuggestion(
    val behaviorId: String,
    val scheduleId: String,
    val fromTime: LocalTime,
    val toTime: LocalTime,
)

data class CoachInsight(
    val behaviorId: String?,
    val kind: InsightKind,
    val severity: InsightSeverity,
    val title: String,
    val body: String,
    val recommendation: String? = null,
    val scheduleSuggestion: ScheduleSuggestion? = null,
)

private val InsightSeverity.sortOrder: Int
    get() = when (this) {
        InsightSeverity.ATTENTION -> 0
        InsightSeverity.NEUTRAL -> 1
        InsightSeverity.POSITIVE -> 2
    }

/** The seven-day roll-up shown on the weekly review screen. */
data class WeeklyReview(
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val lines: List<Line>,
    val totalCompleted: Int,
    val totalPassed: Int,
    val totalMissed: Int,
    val totalRecoveries: Int,
    val overallRate: Double,
    val recoveryScore: Double,
    val insights: List<CoachInsight>,
) {
    data class Line(
        val behaviorId: String,
        val behaviorName: String,
        val completed: Int,
        val scheduled: Int,
    ) {
        val rate: Double get() = BehaviorStats.ratio(completed, scheduled)
    }

    val bestDay: DayOfWeek? = null
}
