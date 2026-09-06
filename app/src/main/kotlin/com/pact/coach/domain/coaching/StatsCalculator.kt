package com.pact.coach.domain.coaching

import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.CompletionQuality
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.model.PassReason
import com.pact.coach.domain.recovery.RecoveryChain
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Every number the Insights screen and the coaching rules use.
 *
 * The important modelling choice: rates are computed over **chains**, not raw rows. A behavior
 * planned for 18:00, recovered to 18:30 and finished counts once, as a completion. Scoring the
 * raw rows instead would punish the user for recovering, which is precisely the behaviour the
 * product wants to encourage.
 */
data class BehaviorStats(
    val behaviorId: String,
    val resolvedChains: Int,
    val completed: Int,
    val passed: Int,
    val missed: Int,
    val cancelled: Int,
    val pending: Int,

    /** Chains that involved at least one recovery. */
    val chainsWithRecovery: Int,
    /** Recovered chains that ended in a completion. */
    val recoveredAndCompleted: Int,
    /** Total recovery hops across all chains. */
    val recoveryEvents: Int,

    /** Chains completed with no recovery at all. */
    val completedFirstTime: Int,

    val targetCompletions: Int,
    val minimumCompletions: Int,
    val partialCompletions: Int,

    val averageResponseSeconds: Long?,
    val currentStreak: Int,
    val longestStreak: Int,

    val completionsByHour: Map<Int, Rate>,
    val completionsByDayOfWeek: Map<DayOfWeek, Rate>,
    val passReasons: Map<PassReason, Int>,

    val averageRating: Double?,
    val totalDurationMinutes: Int,
    val totalQuantity: Double,
) {
    /** Share of resolved chains that ended in a completion. This is the headline number. */
    val completionRate: Double = ratio(completed, resolvedChains)

    val passRate: Double = ratio(passed, resolvedChains)
    val missRate: Double = ratio(missed, resolvedChains)

    /** How often the user needed to push something later. */
    val recoveryRate: Double = ratio(chainsWithRecovery, resolvedChains)

    /**
     * The metric the product cares most about: when you did not act at the planned moment, how
     * often did you come back to it? High is good, and it is deliberately independent of how
     * often recovery was needed in the first place.
     */
    val recoveryScore: Double = ratio(recoveredAndCompleted, chainsWithRecovery)

    /** Completions that needed no rescheduling at all. */
    val firstTimeRate: Double = ratio(completedFirstTime, resolvedChains)

    /** Of the completions, how many were only the minimum. */
    val minimumShare: Double = ratio(minimumCompletions, completed)

    val hasEnoughData: Boolean get() = resolvedChains >= 3

    val bestHour: Int? get() = completionsByHour.filterValues { it.total >= 2 }
        .maxByOrNull { it.value.rate }?.key

    val worstHour: Int? get() = completionsByHour.filterValues { it.total >= 2 }
        .minByOrNull { it.value.rate }?.key

    val bestDay: DayOfWeek? get() = completionsByDayOfWeek.filterValues { it.total >= 2 }
        .maxByOrNull { it.value.rate }?.key

    val worstDay: DayOfWeek? get() = completionsByDayOfWeek.filterValues { it.total >= 2 }
        .minByOrNull { it.value.rate }?.key

    companion object {
        fun ratio(part: Int, whole: Int): Double = if (whole <= 0) 0.0 else part.toDouble() / whole

        fun empty(behaviorId: String) = BehaviorStats(
            behaviorId = behaviorId,
            resolvedChains = 0, completed = 0, passed = 0, missed = 0, cancelled = 0, pending = 0,
            chainsWithRecovery = 0, recoveredAndCompleted = 0, recoveryEvents = 0,
            completedFirstTime = 0,
            targetCompletions = 0, minimumCompletions = 0, partialCompletions = 0,
            averageResponseSeconds = null, currentStreak = 0, longestStreak = 0,
            completionsByHour = emptyMap(), completionsByDayOfWeek = emptyMap(),
            passReasons = emptyMap(), averageRating = null,
            totalDurationMinutes = 0, totalQuantity = 0.0,
        )
    }
}

/** A completed/total pair, so the UI can show "6 of 7" alongside the percentage. */
data class Rate(val completed: Int, val total: Int) {
    val rate: Double get() = if (total <= 0) 0.0 else completed.toDouble() / total
    operator fun plus(other: Rate) = Rate(completed + other.completed, total + other.total)
}

/** Roll-up across several behaviors, used on the dashboard and in the weekly review. */
data class OverallStats(
    val behaviors: Int,
    val resolvedChains: Int,
    val completed: Int,
    val passed: Int,
    val missed: Int,
    val recoveryEvents: Int,
    val recoveredAndCompleted: Int,
    val chainsWithRecovery: Int,
) {
    val completionRate: Double = BehaviorStats.ratio(completed, resolvedChains)
    val recoveryScore: Double = BehaviorStats.ratio(recoveredAndCompleted, chainsWithRecovery)
    val passRate: Double = BehaviorStats.ratio(passed, resolvedChains)
    val missRate: Double = BehaviorStats.ratio(missed, resolvedChains)
}

class StatsCalculator {

    /**
     * @param instances every instance for one behavior, including recovery rows.
     * @param upTo streaks are counted backwards from this date.
     */
    fun forBehavior(
        behaviorId: String,
        instances: List<BehaviorInstance>,
        upTo: LocalDate,
    ): BehaviorStats {
        if (instances.isEmpty()) return BehaviorStats.empty(behaviorId)

        val chains = RecoveryChain.group(instances)
        val resolved = chains.filter { it.isResolved }

        var completed = 0
        var passed = 0
        var missed = 0
        var cancelled = 0
        var chainsWithRecovery = 0
        var recoveredAndCompleted = 0
        var completedFirstTime = 0

        for (chain in resolved) {
            when (chain.outcome) {
                InstanceState.COMPLETED -> completed++
                InstanceState.PASSED -> passed++
                InstanceState.MISSED -> missed++
                InstanceState.CANCELLED -> cancelled++
                else -> Unit
            }
            if (chain.wasRecovered) {
                chainsWithRecovery++
                if (chain.outcome == InstanceState.COMPLETED) recoveredAndCompleted++
            } else if (chain.outcome == InstanceState.COMPLETED) {
                completedFirstTime++
            }
        }

        val pending = chains.count { !it.isResolved }
        val recoveryEvents = instances.count { it.state == InstanceState.RECOVERED }

        val completedRows = instances.filter { it.state == InstanceState.COMPLETED }
        val targetCompletions = completedRows.count { it.completionQuality == CompletionQuality.TARGET_COMPLETED }
        val minimumCompletions = completedRows.count { it.completionQuality == CompletionQuality.MINIMUM_COMPLETED }
        val partialCompletions = completedRows.count { it.completionQuality == CompletionQuality.PARTIAL }

        val responseDelays = instances.mapNotNull { it.responseDelaySeconds }
        val averageResponse = if (responseDelays.isEmpty()) null else responseDelays.average().toLong()

        val ratings = completedRows.mapNotNull { it.rating }
        val averageRating = if (ratings.isEmpty()) null else ratings.average()

        val (current, longest) = streaks(resolved, upTo)

        return BehaviorStats(
            behaviorId = behaviorId,
            resolvedChains = resolved.size,
            completed = completed,
            passed = passed,
            missed = missed,
            cancelled = cancelled,
            pending = pending,
            chainsWithRecovery = chainsWithRecovery,
            recoveredAndCompleted = recoveredAndCompleted,
            recoveryEvents = recoveryEvents,
            completedFirstTime = completedFirstTime,
            targetCompletions = targetCompletions,
            minimumCompletions = minimumCompletions,
            partialCompletions = partialCompletions,
            averageResponseSeconds = averageResponse,
            currentStreak = current,
            longestStreak = longest,
            completionsByHour = ratesByHour(resolved),
            completionsByDayOfWeek = ratesByDayOfWeek(resolved),
            passReasons = instances.mapNotNull { it.passReason }.groupingBy { it }.eachCount(),
            averageRating = averageRating,
            totalDurationMinutes = completedRows.sumOf { it.actualDurationMinutes ?: 0 },
            totalQuantity = completedRows.sumOf { it.actualQuantity ?: 0.0 },
        )
    }

    fun overall(perBehavior: List<BehaviorStats>): OverallStats = OverallStats(
        behaviors = perBehavior.size,
        resolvedChains = perBehavior.sumOf { it.resolvedChains },
        completed = perBehavior.sumOf { it.completed },
        passed = perBehavior.sumOf { it.passed },
        missed = perBehavior.sumOf { it.missed },
        recoveryEvents = perBehavior.sumOf { it.recoveryEvents },
        recoveredAndCompleted = perBehavior.sumOf { it.recoveredAndCompleted },
        chainsWithRecovery = perBehavior.sumOf { it.chainsWithRecovery },
    )

    /**
     * Streaks are counted over *days that had something scheduled*, so a Mon/Wed/Fri behavior is
     * not penalised for Tuesday. A day counts when every chain planned for it was completed.
     */
    private fun streaks(chains: List<RecoveryChain>, upTo: LocalDate): Pair<Int, Int> {
        val byDay = chains
            .mapNotNull { chain -> chain.originalInstance?.scheduledDate?.let { it to chain } }
            .groupBy({ it.first }, { it.second })

        if (byDay.isEmpty()) return 0 to 0

        val days = byDay.keys.sorted()
        var longest = 0
        var run = 0
        for (day in days) {
            val allDone = byDay.getValue(day).all { it.outcome == InstanceState.COMPLETED }
            if (allDone) {
                run++
                longest = maxOf(longest, run)
            } else {
                run = 0
            }
        }

        // The current streak walks backwards from the most recent scheduled day at or before upTo.
        var current = 0
        for (day in days.filter { !it.isAfter(upTo) }.reversed()) {
            val allDone = byDay.getValue(day).all { it.outcome == InstanceState.COMPLETED }
            if (allDone) current++ else break
        }

        return current to longest
    }

    private fun ratesByHour(chains: List<RecoveryChain>): Map<Int, Rate> {
        val acc = mutableMapOf<Int, Rate>()
        for (chain in chains) {
            val original = chain.originalInstance ?: continue
            val hour = original.scheduledTime?.hour ?: continue
            val done = if (chain.outcome == InstanceState.COMPLETED) 1 else 0
            acc[hour] = (acc[hour] ?: Rate(0, 0)) + Rate(done, 1)
        }
        return acc
    }

    private fun ratesByDayOfWeek(chains: List<RecoveryChain>): Map<DayOfWeek, Rate> {
        val acc = mutableMapOf<DayOfWeek, Rate>()
        for (chain in chains) {
            val original = chain.originalInstance ?: continue
            val day = original.scheduledDate.dayOfWeek
            val done = if (chain.outcome == InstanceState.COMPLETED) 1 else 0
            acc[day] = (acc[day] ?: Rate(0, 0)) + Rate(done, 1)
        }
        return acc
    }
}
