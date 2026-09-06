package com.pact.coach.data.repository

import com.pact.coach.core.time.ClockProvider
import com.pact.coach.data.db.dao.BehaviorDao
import com.pact.coach.data.db.dao.GoalDao
import com.pact.coach.data.mapper.toDomain
import com.pact.coach.data.mapper.toEntity
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.Goal
import com.pact.coach.domain.model.GoalStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Goals and their behaviors.
 *
 * Deleting a goal does not delete its behaviors: the foreign key is ON DELETE SET NULL, so the
 * behaviors survive as unassigned and keep every bit of their history. Losing months of records
 * because a goal was tidied away would be the wrong default.
 */
class GoalRepository(
    private val goalDao: GoalDao,
    private val behaviorDao: BehaviorDao,
    private val clock: ClockProvider,
) {

    fun observeAll(): Flow<List<Goal>> =
        goalDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeActive(): Flow<List<Goal>> =
        goalDao.observeByStatus(GoalStatus.ACTIVE.name).map { rows -> rows.map { it.toDomain() } }

    fun observeById(id: String): Flow<Goal?> =
        goalDao.observeById(id).map { it?.toDomain() }

    fun observeWithBehaviors(id: String): Flow<GoalWithBehaviors?> =
        combine(
            goalDao.observeById(id),
            behaviorDao.observeByGoal(id),
        ) { goal, behaviors ->
            goal?.let { GoalWithBehaviors(it.toDomain(), behaviors.map { b -> b.toDomain() }) }
        }

    fun observeAllWithBehaviors(): Flow<List<GoalWithBehaviors>> =
        combine(
            goalDao.observeAll(),
            behaviorDao.observeAll(),
        ) { goals, behaviors ->
            val byGoal = behaviors.groupBy { it.goalId }
            goals.map { goal ->
                GoalWithBehaviors(
                    goal.toDomain(),
                    byGoal[goal.id].orEmpty().map { it.toDomain() },
                )
            }
        }

    fun observeCategories(): Flow<List<String>> = goalDao.observeCategories()

    suspend fun get(id: String): Goal? = goalDao.getById(id)?.toDomain()

    suspend fun save(goal: Goal): Goal {
        val now = clock.now()
        val stamped = goal.copy(updatedAt = now)
        goalDao.upsert(stamped.toEntity())
        return stamped
    }

    suspend fun create(
        name: String,
        whyItMatters: String,
        category: String,
        priority: com.pact.coach.domain.model.Priority,
        description: String = "",
        targetDate: java.time.LocalDate? = null,
    ): Goal {
        val now = clock.now()
        val goal = Goal(
            name = name.trim(),
            description = description.trim(),
            category = category.trim().ifBlank { "Personal" },
            whyItMatters = whyItMatters.trim(),
            startDate = clock.today(),
            targetDate = targetDate,
            priority = priority,
            status = GoalStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
        goalDao.upsert(goal.toEntity())
        return goal
    }

    suspend fun setStatus(id: String, status: GoalStatus) {
        val existing = goalDao.getById(id) ?: return
        goalDao.upsert(existing.copy(status = status.name, updatedAtMillis = clock.nowMillis()))
    }

    /** Behaviors are detached, not deleted. See the class comment. */
    suspend fun delete(id: String) {
        goalDao.deleteById(id)
    }
}

data class GoalWithBehaviors(
    val goal: Goal,
    val behaviors: List<Behavior>,
) {
    val activeBehaviors: List<Behavior> get() = behaviors.filter { it.isActive }
}
