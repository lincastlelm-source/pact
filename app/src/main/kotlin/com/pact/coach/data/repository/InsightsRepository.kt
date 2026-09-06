package com.pact.coach.data.repository

import com.pact.coach.core.time.ClockProvider
import com.pact.coach.core.util.Ids
import com.pact.coach.data.db.PactDatabase
import com.pact.coach.data.db.entity.CoachMessageEntity
import com.pact.coach.data.mapper.toDomain
import com.pact.coach.domain.coaching.BehaviorStats
import com.pact.coach.domain.coaching.CoachInsight
import com.pact.coach.domain.coaching.CoachingEngine
import com.pact.coach.domain.coaching.OverallStats
import com.pact.coach.domain.coaching.StatsCalculator
import com.pact.coach.domain.coaching.WeeklyReview
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.CoachIntensity
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.recovery.RecoveryChain
import com.pact.coach.domain.recurrence.RecurrenceEngine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Reads history and turns it into numbers and coaching observations.
 *
 * Everything here is computed on demand from the local database. There is no background
 * aggregation job and no derived-metrics table, which keeps the data honest: what the Insights
 * screen shows is always a direct function of what actually happened.
 */
class InsightsRepository(
    private val db: PactDatabase,
    private val clock: ClockProvider,
    private val statsCalculator: StatsCalculator = StatsCalculator(),
) {
    private val instanceDao = db.instanceDao()
    private val behaviorDao = db.behaviorDao()
    private val scheduleDao = db.scheduleDao()
    private val coachDao = db.coachMessageDao()

    suspend fun statsFor(behaviorId: String, sinceDays: Long = 90): BehaviorStats {
        val since = clock.today().minusDays(sinceDays)
        val instances = instanceDao
            .getByBehaviorSince(behaviorId, since.toEpochDay())
            .map { it.toDomain() }
        return statsCalculator.forBehavior(behaviorId, instances, clock.today())
    }

    suspend fun statsForAll(sinceDays: Long = 90): Map<String, BehaviorStats> {
        val behaviors = behaviorDao.getAll()
        return behaviors.associate { it.id to statsFor(it.id, sinceDays) }
    }

    suspend fun overall(sinceDays: Long = 90): OverallStats =
        statsCalculator.overall(statsForAll(sinceDays).values.toList())

    /** Fresh insights for one behavior, recomputed from history. */
    suspend fun insightsFor(behaviorId: String, intensity: CoachIntensity): List<CoachInsight> {
        val behavior = behaviorDao.getById(behaviorId)?.toDomain() ?: return emptyList()
        val stats = statsFor(behaviorId)
        val schedules = scheduleDao.getByBehavior(behaviorId).map { it.toDomain() }
        val recent = instanceDao
            .getByBehaviorSince(behaviorId, clock.today().minusDays(30).toEpochDay())
            .map { it.toDomain() }
        return CoachingEngine(intensity).analyse(behavior, stats, schedules, recent, clock.today())
    }

    suspend fun insightsForAll(intensity: CoachIntensity, limit: Int = 8): List<CoachInsight> {
        val behaviors = behaviorDao.getActive().map { it.toDomain() }
        return behaviors
            .flatMap { insightsFor(it.id, intensity) }
            .sortedBy { it.severity.ordinal }
            .take(limit)
    }

    /**
     * Persists insights so the same observation is not re-announced every time the screen opens.
     * The dedup key includes the ISO week, so an observation can resurface next week if it still
     * holds.
     */
    suspend fun recordInsights(insights: List<CoachInsight>) {
        if (insights.isEmpty()) return
        val now = clock.nowMillis()
        val today = clock.today()
        val week = "${today.year}-${today.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())}"
        val rows = insights.map { insight ->
            CoachMessageEntity(
                id = Ids.new(),
                behaviorId = insight.behaviorId,
                kind = insight.kind.name,
                severity = insight.severity.name,
                title = insight.title,
                body = insight.body,
                recommendation = insight.recommendation,
                createdAtMillis = now,
                acknowledgedAtMillis = null,
                dedupKey = "${insight.behaviorId}|${insight.kind.name}|$week",
            )
        }
        coachDao.insertIgnoring(rows)
    }

    suspend fun acknowledgeMessage(id: String) = coachDao.acknowledge(id, clock.nowMillis())

    /**
     * The seven-day report. Counts scheduled vs completed per behavior over chains, so a session
     * that was recovered and then done reads as completed rather than as one hit and one miss.
     */
    suspend fun weeklyReview(
        weekStart: LocalDate = RecurrenceEngine.weekStart(clock.today()),
        intensity: CoachIntensity = CoachIntensity.NORMAL,
    ): WeeklyReview {
        val weekEnd = weekStart.plusDays(6)
        val behaviors = behaviorDao.getAll().associate { it.id to it.toDomain() }

        val instances = instanceDao
            .getBetween(weekStart.toEpochDay(), weekEnd.toEpochDay())
            .map { it.toDomain() }

        val chains = RecoveryChain.group(instances)

        val lines = chains
            .groupBy { it.originalInstance?.behaviorId ?: it.instances.first().behaviorId }
            .mapNotNull { (behaviorId, behaviorChains) ->
                val behavior = behaviors[behaviorId] ?: return@mapNotNull null
                WeeklyReview.Line(
                    behaviorId = behaviorId,
                    behaviorName = behavior.name,
                    completed = behaviorChains.count { it.outcome == InstanceState.COMPLETED },
                    scheduled = behaviorChains.size,
                )
            }
            .sortedByDescending { it.scheduled }

        val resolvedChains = chains.filter { it.isResolved }
        val completed = resolvedChains.count { it.outcome == InstanceState.COMPLETED }
        val passed = resolvedChains.count { it.outcome == InstanceState.PASSED }
        val missed = resolvedChains.count { it.outcome == InstanceState.MISSED }
        val recoveries = instances.count { it.state == InstanceState.RECOVERED }
        val withRecovery = resolvedChains.count { it.wasRecovered }
        val recoveredCompleted = resolvedChains.count {
            it.wasRecovered && it.outcome == InstanceState.COMPLETED
        }

        val insights = behaviors.values
            .filter { it.isActive }
            .flatMap { insightsFor(it.id, intensity) }
            .sortedBy { it.severity.ordinal }
            .take(5)

        return WeeklyReview(
            weekStart = weekStart,
            weekEnd = weekEnd,
            lines = lines,
            totalCompleted = completed,
            totalPassed = passed,
            totalMissed = missed,
            totalRecoveries = recoveries,
            overallRate = BehaviorStats.ratio(completed, resolvedChains.size),
            recoveryScore = BehaviorStats.ratio(recoveredCompleted, withRecovery),
            insights = insights,
        )
    }

    /** Per-day completion counts for the calendar view. */
    suspend fun calendarSummary(from: LocalDate, to: LocalDate): Map<LocalDate, DaySummary> {
        val instances = instanceDao.getBetween(from.toEpochDay(), to.toEpochDay()).map { it.toDomain() }
        return instances.groupBy { it.scheduledDate }.mapValues { (_, rows) ->
            DaySummary(
                completed = rows.count { it.state == InstanceState.COMPLETED },
                recovered = rows.count { it.state == InstanceState.RECOVERED },
                passed = rows.count { it.state == InstanceState.PASSED },
                missed = rows.count { it.state == InstanceState.MISSED },
                pending = rows.count { !it.state.isTerminal },
                total = rows.size,
            )
        }
    }

    /** Completion rate for each of the last [weeks] ISO weeks, oldest first. */
    suspend fun weeklyTrend(weeks: Int = 8): List<Pair<LocalDate, Double>> {
        val thisWeek = RecurrenceEngine.weekStart(clock.today())
        return (weeks - 1 downTo 0).map { offset ->
            val start = thisWeek.minusWeeks(offset.toLong())
            val end = start.plusDays(6)
            val chains = RecoveryChain.group(
                instanceDao.getBetween(start.toEpochDay(), end.toEpochDay()).map { it.toDomain() },
            ).filter { it.isResolved }
            val completed = chains.count { it.outcome == InstanceState.COMPLETED }
            start to BehaviorStats.ratio(completed, chains.size)
        }
    }

    /** Which behaviors are the most and least consistent, for the Insights headline. */
    suspend fun consistencyRanking(): List<Pair<Behavior, BehaviorStats>> {
        val behaviors = behaviorDao.getActive().map { it.toDomain() }
        return behaviors
            .map { it to statsFor(it.id) }
            .filter { it.second.hasEnoughData }
            .sortedByDescending { it.second.completionRate }
    }

    /** Aggregate day-of-week performance across every behavior. */
    suspend fun dayOfWeekPerformance(sinceDays: Long = 90): Map<DayOfWeek, Double> {
        val since = clock.today().minusDays(sinceDays)
        val instances = instanceDao
            .getBetween(since.toEpochDay(), clock.today().toEpochDay())
            .map { it.toDomain() }
        val chains = RecoveryChain.group(instances).filter { it.isResolved }
        return chains
            .mapNotNull { chain -> chain.originalInstance?.let { it.scheduledDate.dayOfWeek to chain } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, rows) ->
                BehaviorStats.ratio(rows.count { it.outcome == InstanceState.COMPLETED }, rows.size)
            }
    }

    suspend fun daysSince(epochDay: Long): Long =
        if (epochDay <= 0) Long.MAX_VALUE
        else ChronoUnit.DAYS.between(LocalDate.ofEpochDay(epochDay), clock.today())
}

data class DaySummary(
    val completed: Int,
    val recovered: Int,
    val passed: Int,
    val missed: Int,
    val pending: Int,
    val total: Int,
) {
    val rate: Double get() = BehaviorStats.ratio(completed, total)
    val isEmpty: Boolean get() = total == 0
}
