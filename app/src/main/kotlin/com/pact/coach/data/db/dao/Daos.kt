package com.pact.coach.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.pact.coach.data.db.entity.ActionEventEntity
import com.pact.coach.data.db.entity.BehaviorEntity
import com.pact.coach.data.db.entity.BehaviorExceptionEntity
import com.pact.coach.data.db.entity.BehaviorInstanceEntity
import com.pact.coach.data.db.entity.BehaviorTemplateEntity
import com.pact.coach.data.db.entity.ChecklistItemEntity
import com.pact.coach.data.db.entity.ChecklistTickEntity
import com.pact.coach.data.db.entity.CoachMessageEntity
import com.pact.coach.data.db.entity.DailyInstructionEntity
import com.pact.coach.data.db.entity.GoalEntity
import com.pact.coach.data.db.entity.ProgressionStepEntity
import com.pact.coach.data.db.entity.ScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GoalDao {

    @Query("SELECT * FROM goals ORDER BY status ASC, priority DESC, name ASC")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE status = :status ORDER BY priority DESC, name ASC")
    fun observeByStatus(status: String): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE id = :id")
    fun observeById(id: String): Flow<GoalEntity?>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: String): GoalEntity?

    @Query("SELECT * FROM goals")
    suspend fun getAll(): List<GoalEntity>

    @Upsert
    suspend fun upsert(goal: GoalEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(goals: List<GoalEntity>): List<Long>

    @Upsert
    suspend fun upsertAll(goals: List<GoalEntity>)

    @Delete
    suspend fun delete(goal: GoalEntity)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM goals")
    suspend fun deleteAll()

    @Query("SELECT DISTINCT category FROM goals WHERE category != '' ORDER BY category")
    fun observeCategories(): Flow<List<String>>
}

@Dao
interface BehaviorDao {

    @Query("SELECT * FROM behaviors ORDER BY isActive DESC, name ASC")
    fun observeAll(): Flow<List<BehaviorEntity>>

    @Query("SELECT * FROM behaviors WHERE isActive = 1")
    fun observeActive(): Flow<List<BehaviorEntity>>

    @Query("SELECT * FROM behaviors WHERE isActive = 1")
    suspend fun getActive(): List<BehaviorEntity>

    @Query("SELECT * FROM behaviors WHERE goalId = :goalId ORDER BY name ASC")
    fun observeByGoal(goalId: String): Flow<List<BehaviorEntity>>

    @Query("SELECT * FROM behaviors WHERE goalId IS NULL ORDER BY name ASC")
    fun observeUnassigned(): Flow<List<BehaviorEntity>>

    @Query("SELECT * FROM behaviors WHERE id = :id")
    fun observeById(id: String): Flow<BehaviorEntity?>

    @Query("SELECT * FROM behaviors WHERE id = :id")
    suspend fun getById(id: String): BehaviorEntity?

    @Query("SELECT * FROM behaviors WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<BehaviorEntity>

    @Query("SELECT * FROM behaviors")
    suspend fun getAll(): List<BehaviorEntity>

    @Query("SELECT COUNT(*) FROM behaviors WHERE LOWER(name) = LOWER(:name) AND id != :excludingId")
    suspend fun countByName(name: String, excludingId: String): Int

    @Upsert
    suspend fun upsert(behavior: BehaviorEntity)

    @Upsert
    suspend fun upsertAll(behaviors: List<BehaviorEntity>)

    @Query("UPDATE behaviors SET isActive = :active, updatedAtMillis = :now WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean, now: Long)

    @Query("UPDATE behaviors SET channelVersion = channelVersion + 1 WHERE id = :id")
    suspend fun bumpChannelVersion(id: String)

    @Query("DELETE FROM behaviors WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM behaviors")
    suspend fun deleteAll()
}

@Dao
interface ScheduleDao {

    @Query("SELECT * FROM schedules WHERE behaviorId = :behaviorId")
    fun observeByBehavior(behaviorId: String): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE behaviorId = :behaviorId")
    suspend fun getByBehavior(behaviorId: String): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE isEnabled = 1")
    suspend fun getEnabled(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules")
    suspend fun getAll(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getById(id: String): ScheduleEntity?

    @Upsert
    suspend fun upsert(schedule: ScheduleEntity)

    @Upsert
    suspend fun upsertAll(schedules: List<ScheduleEntity>)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM schedules WHERE behaviorId = :behaviorId")
    suspend fun deleteByBehavior(behaviorId: String)

    @Query("DELETE FROM schedules")
    suspend fun deleteAll()
}

@Dao
interface InstanceDao {

    @Query("SELECT * FROM behavior_instances WHERE id = :id")
    suspend fun getById(id: String): BehaviorInstanceEntity?

    @Query("SELECT * FROM behavior_instances WHERE id = :id")
    fun observeById(id: String): Flow<BehaviorInstanceEntity?>

    @Query(
        """
        SELECT * FROM behavior_instances
        WHERE scheduledDateEpochDay = :epochDay
        ORDER BY scheduledTimeMinutes IS NULL, scheduledTimeMinutes ASC, occurrenceIndex ASC
        """,
    )
    fun observeForDay(epochDay: Long): Flow<List<BehaviorInstanceEntity>>

    @Query(
        """
        SELECT * FROM behavior_instances
        WHERE scheduledDateEpochDay BETWEEN :fromDay AND :toDay
        ORDER BY scheduledDateEpochDay ASC, scheduledTimeMinutes IS NULL, scheduledTimeMinutes ASC
        """,
    )
    fun observeBetween(fromDay: Long, toDay: Long): Flow<List<BehaviorInstanceEntity>>

    @Query(
        """
        SELECT * FROM behavior_instances
        WHERE scheduledDateEpochDay BETWEEN :fromDay AND :toDay
        ORDER BY scheduledDateEpochDay ASC, scheduledTimeMinutes IS NULL, scheduledTimeMinutes ASC
        """,
    )
    suspend fun getBetween(fromDay: Long, toDay: Long): List<BehaviorInstanceEntity>

    @Query("SELECT * FROM behavior_instances WHERE behaviorId = :behaviorId ORDER BY scheduledDateEpochDay DESC")
    suspend fun getByBehavior(behaviorId: String): List<BehaviorInstanceEntity>

    @Query("SELECT * FROM behavior_instances WHERE behaviorId = :behaviorId ORDER BY scheduledDateEpochDay DESC")
    fun observeByBehavior(behaviorId: String): Flow<List<BehaviorInstanceEntity>>

    @Query(
        """
        SELECT * FROM behavior_instances
        WHERE behaviorId = :behaviorId AND scheduledDateEpochDay >= :fromDay
        ORDER BY scheduledDateEpochDay ASC
        """,
    )
    suspend fun getByBehaviorSince(behaviorId: String, fromDay: Long): List<BehaviorInstanceEntity>

    /** Every row in a recovery chain, so history can show the original plan and what followed. */
    @Query("SELECT * FROM behavior_instances WHERE rootInstanceId = :rootId ORDER BY recoveryDepth ASC")
    suspend fun getChain(rootId: String): List<BehaviorInstanceEntity>

    @Query("SELECT * FROM behavior_instances WHERE rootInstanceId = :rootId ORDER BY recoveryDepth ASC")
    fun observeChain(rootId: String): Flow<List<BehaviorInstanceEntity>>

    /** Instances that still need an OS alarm registered. */
    @Query(
        """
        SELECT * FROM behavior_instances
        WHERE state IN ('SCHEDULED', 'DUE')
          AND scheduledAtUtcMillis IS NOT NULL
          AND scheduledAtUtcMillis BETWEEN :fromMillis AND :toMillis
        ORDER BY scheduledAtUtcMillis ASC
        """,
    )
    suspend fun getSchedulable(fromMillis: Long, toMillis: Long): List<BehaviorInstanceEntity>

    /** Anything still open whose moment has passed; candidates for the missed sweep. */
    @Query(
        """
        SELECT * FROM behavior_instances
        WHERE state IN ('SCHEDULED', 'DUE', 'COMMITTED')
          AND scheduledAtUtcMillis IS NOT NULL
          AND scheduledAtUtcMillis < :beforeMillis
          AND scheduledDateEpochDay >= :notBeforeDay
        """,
    )
    suspend fun getOverdue(beforeMillis: Long, notBeforeDay: Long): List<BehaviorInstanceEntity>

    /** Untimed occurrences that were never resolved on their day. */
    @Query(
        """
        SELECT * FROM behavior_instances
        WHERE state IN ('SCHEDULED', 'DUE', 'COMMITTED')
          AND scheduledAtUtcMillis IS NULL
          AND scheduledDateEpochDay < :beforeDay
          AND scheduledDateEpochDay >= :notBeforeDay
        """,
    )
    suspend fun getStaleUntimed(beforeDay: Long, notBeforeDay: Long): List<BehaviorInstanceEntity>

    /** The next thing due, used by the dashboard. */
    @Query(
        """
        SELECT * FROM behavior_instances
        WHERE state IN ('SCHEDULED', 'DUE')
          AND scheduledAtUtcMillis IS NOT NULL
          AND scheduledAtUtcMillis >= :fromMillis
        ORDER BY scheduledAtUtcMillis ASC
        LIMIT :limit
        """,
    )
    fun observeUpcoming(fromMillis: Long, limit: Int): Flow<List<BehaviorInstanceEntity>>

    /** Anything currently demanding attention: due, or committed and not yet finished. */
    @Query(
        """
        SELECT * FROM behavior_instances
        WHERE state IN ('DUE', 'COMMITTED')
        ORDER BY scheduledAtUtcMillis ASC
        """,
    )
    fun observeOpen(): Flow<List<BehaviorInstanceEntity>>

    /** Future rows for a behavior; used when a schedule is edited or the time zone changes. */
    @Query(
        """
        SELECT * FROM behavior_instances
        WHERE state = 'SCHEDULED' AND scheduledDateEpochDay >= :fromDay
        """,
    )
    suspend fun getFutureScheduled(fromDay: Long): List<BehaviorInstanceEntity>

    @Query(
        """
        SELECT * FROM behavior_instances
        WHERE behaviorId = :behaviorId AND state = 'SCHEDULED' AND scheduledDateEpochDay >= :fromDay
        """,
    )
    suspend fun getFutureScheduledForBehavior(behaviorId: String, fromDay: Long): List<BehaviorInstanceEntity>

    @Query("SELECT * FROM behavior_instances")
    suspend fun getAll(): List<BehaviorInstanceEntity>

    @Query("SELECT COALESCE(MAX(alarmRequestCode), 1000) FROM behavior_instances")
    suspend fun maxAlarmRequestCode(): Int

    /**
     * Insert-or-ignore against the unique dedupKey index. This is what makes horizon
     * regeneration idempotent, and it is why duplicate alarms cannot appear.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(instances: List<BehaviorInstanceEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(instance: BehaviorInstanceEntity): Long

    @Update
    suspend fun update(instance: BehaviorInstanceEntity)

    @Update
    suspend fun updateAll(instances: List<BehaviorInstanceEntity>)

    @Upsert
    suspend fun upsertAll(instances: List<BehaviorInstanceEntity>)

    @Query("DELETE FROM behavior_instances WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Remove not-yet-started future rows, e.g. after a schedule edit. History is untouched. */
    @Query(
        """
        DELETE FROM behavior_instances
        WHERE behaviorId = :behaviorId
          AND state = 'SCHEDULED'
          AND originInstanceId IS NULL
          AND scheduledDateEpochDay >= :fromDay
        """,
    )
    suspend fun deleteFutureScheduled(behaviorId: String, fromDay: Long)

    @Query("DELETE FROM behavior_instances")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM behavior_instances WHERE dedupKey = :key")
    suspend fun countByDedupKey(key: String): Int
}

@Dao
interface ActionEventDao {

    @Insert
    suspend fun insert(event: ActionEventEntity)

    @Insert
    suspend fun insertAll(events: List<ActionEventEntity>)

    /**
     * Used by backup import. Audit events are append-only and identified by a stable id, so a
     * row that is already present is the same event; re-importing the same file must be a no-op
     * rather than a constraint violation.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(events: List<ActionEventEntity>): List<Long>

    @Query("SELECT * FROM action_events WHERE instanceId = :instanceId ORDER BY atMillis ASC")
    suspend fun getForInstance(instanceId: String): List<ActionEventEntity>

    @Query("SELECT * FROM action_events WHERE behaviorId = :behaviorId ORDER BY atMillis DESC LIMIT :limit")
    suspend fun getForBehavior(behaviorId: String, limit: Int): List<ActionEventEntity>

    @Query("SELECT * FROM action_events ORDER BY atMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ActionEventEntity>

    @Query("SELECT * FROM action_events")
    suspend fun getAll(): List<ActionEventEntity>

    @Query("DELETE FROM action_events")
    suspend fun deleteAll()

    /** Housekeeping: the audit trail is useful but not unbounded. */
    @Query("DELETE FROM action_events WHERE atMillis < :beforeMillis")
    suspend fun pruneBefore(beforeMillis: Long)
}

@Dao
interface DailyInstructionDao {

    @Query("SELECT * FROM daily_instructions WHERE behaviorId = :behaviorId")
    fun observeByBehavior(behaviorId: String): Flow<List<DailyInstructionEntity>>

    @Query("SELECT * FROM daily_instructions WHERE behaviorId = :behaviorId")
    suspend fun getByBehavior(behaviorId: String): List<DailyInstructionEntity>

    @Query("SELECT * FROM daily_instructions WHERE behaviorId IN (:behaviorIds)")
    suspend fun getByBehaviors(behaviorIds: List<String>): List<DailyInstructionEntity>

    @Query("SELECT * FROM daily_instructions")
    suspend fun getAll(): List<DailyInstructionEntity>

    @Upsert
    suspend fun upsert(instruction: DailyInstructionEntity)

    @Upsert
    suspend fun upsertAll(instructions: List<DailyInstructionEntity>)

    @Query("DELETE FROM daily_instructions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM daily_instructions")
    suspend fun deleteAll()
}

@Dao
interface ChecklistDao {

    @Query("SELECT * FROM checklist_items WHERE behaviorId = :behaviorId ORDER BY position ASC")
    fun observeByBehavior(behaviorId: String): Flow<List<ChecklistItemEntity>>

    @Query("SELECT * FROM checklist_items WHERE behaviorId = :behaviorId ORDER BY position ASC")
    suspend fun getByBehavior(behaviorId: String): List<ChecklistItemEntity>

    @Query("SELECT * FROM checklist_items")
    suspend fun getAll(): List<ChecklistItemEntity>

    @Upsert
    suspend fun upsertAll(items: List<ChecklistItemEntity>)

    @Query("DELETE FROM checklist_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM checklist_items WHERE behaviorId = :behaviorId")
    suspend fun deleteByBehavior(behaviorId: String)

    @Query("DELETE FROM checklist_items")
    suspend fun deleteAll()

    @Query("SELECT * FROM checklist_ticks WHERE instanceId = :instanceId")
    fun observeTicks(instanceId: String): Flow<List<ChecklistTickEntity>>

    @Query("SELECT * FROM checklist_ticks WHERE instanceId = :instanceId")
    suspend fun getTicks(instanceId: String): List<ChecklistTickEntity>

    @Upsert
    suspend fun upsertTick(tick: ChecklistTickEntity)

    @Upsert
    suspend fun upsertTicks(ticks: List<ChecklistTickEntity>)

    @Query("SELECT * FROM checklist_ticks")
    suspend fun getAllTicks(): List<ChecklistTickEntity>

    @Query("DELETE FROM checklist_ticks")
    suspend fun deleteAllTicks()
}

@Dao
interface ExceptionDao {

    @Query("SELECT * FROM behavior_exceptions ORDER BY startDateEpochDay DESC")
    fun observeAll(): Flow<List<BehaviorExceptionEntity>>

    @Query("SELECT * FROM behavior_exceptions")
    suspend fun getAll(): List<BehaviorExceptionEntity>

    @Query(
        """
        SELECT * FROM behavior_exceptions
        WHERE (behaviorId IS NULL OR behaviorId = :behaviorId)
          AND endDateEpochDay >= :fromDay
        """,
    )
    suspend fun getRelevant(behaviorId: String, fromDay: Long): List<BehaviorExceptionEntity>

    @Query("SELECT * FROM behavior_exceptions WHERE endDateEpochDay >= :fromDay")
    suspend fun getActiveFrom(fromDay: Long): List<BehaviorExceptionEntity>

    @Upsert
    suspend fun upsert(exception: BehaviorExceptionEntity)

    @Upsert
    suspend fun upsertAll(exceptions: List<BehaviorExceptionEntity>)

    @Query("DELETE FROM behavior_exceptions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM behavior_exceptions")
    suspend fun deleteAll()
}

@Dao
interface TemplateDao {

    @Query("SELECT * FROM behavior_templates ORDER BY isBuiltIn DESC, name ASC")
    fun observeAll(): Flow<List<BehaviorTemplateEntity>>

    @Query("SELECT * FROM behavior_templates WHERE id = :id")
    suspend fun getById(id: String): BehaviorTemplateEntity?

    @Query("SELECT * FROM behavior_templates")
    suspend fun getAll(): List<BehaviorTemplateEntity>

    @Query("SELECT COUNT(*) FROM behavior_templates")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(template: BehaviorTemplateEntity)

    @Upsert
    suspend fun upsertAll(templates: List<BehaviorTemplateEntity>)

    @Query("DELETE FROM behavior_templates WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM behavior_templates")
    suspend fun deleteAll()
}

@Dao
interface ProgressionDao {

    @Query("SELECT * FROM progression_steps WHERE behaviorId = :behaviorId ORDER BY weekIndex ASC")
    suspend fun getByBehavior(behaviorId: String): List<ProgressionStepEntity>

    @Query("SELECT * FROM progression_steps WHERE behaviorId = :behaviorId ORDER BY weekIndex ASC")
    fun observeByBehavior(behaviorId: String): Flow<List<ProgressionStepEntity>>

    @Query("SELECT * FROM progression_steps")
    suspend fun getAll(): List<ProgressionStepEntity>

    @Upsert
    suspend fun upsertAll(steps: List<ProgressionStepEntity>)

    @Query("DELETE FROM progression_steps WHERE behaviorId = :behaviorId")
    suspend fun deleteByBehavior(behaviorId: String)

    @Query("DELETE FROM progression_steps")
    suspend fun deleteAll()
}

@Dao
interface CoachMessageDao {

    @Query("SELECT * FROM coach_messages ORDER BY createdAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CoachMessageEntity>>

    @Query("SELECT * FROM coach_messages WHERE acknowledgedAtMillis IS NULL ORDER BY createdAtMillis DESC")
    fun observeUnacknowledged(): Flow<List<CoachMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(messages: List<CoachMessageEntity>): List<Long>

    @Query("UPDATE coach_messages SET acknowledgedAtMillis = :now WHERE id = :id")
    suspend fun acknowledge(id: String, now: Long)

    @Query("SELECT * FROM coach_messages")
    suspend fun getAll(): List<CoachMessageEntity>

    @Query("DELETE FROM coach_messages WHERE createdAtMillis < :beforeMillis")
    suspend fun pruneBefore(beforeMillis: Long)

    @Query("DELETE FROM coach_messages")
    suspend fun deleteAll()
}

