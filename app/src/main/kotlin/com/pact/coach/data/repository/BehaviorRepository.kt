package com.pact.coach.data.repository

import androidx.room.withTransaction
import com.pact.coach.core.time.ClockProvider
import com.pact.coach.core.util.Ids
import com.pact.coach.data.db.PactDatabase
import com.pact.coach.data.mapper.actionEvent
import com.pact.coach.data.mapper.toDomain
import com.pact.coach.data.mapper.toEntity
import com.pact.coach.domain.model.ActionType
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorException
import com.pact.coach.domain.model.BehaviorTemplate
import com.pact.coach.domain.model.ChecklistItem
import com.pact.coach.domain.model.DailyInstruction
import com.pact.coach.domain.model.ProgressionStep
import com.pact.coach.domain.model.Schedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime

/**
 * Everything that defines *what* a behavior is: the behavior itself, its schedules, its
 * day-specific instructions, its checklist and its progression ramp.
 *
 * Writing a behavior and its schedules happens inside one transaction so a crash halfway through
 * an edit cannot leave a behavior with a schedule that references times the user never saved.
 */
class BehaviorRepository(
    private val db: PactDatabase,
    private val clock: ClockProvider,
) {
    private val behaviorDao = db.behaviorDao()
    private val scheduleDao = db.scheduleDao()
    private val instructionDao = db.instructionDao()
    private val checklistDao = db.checklistDao()
    private val exceptionDao = db.exceptionDao()
    private val templateDao = db.templateDao()
    private val progressionDao = db.progressionDao()
    private val instanceDao = db.instanceDao()
    private val eventDao = db.eventDao()

    // --- Reads --------------------------------------------------------------------------

    fun observeAll(): Flow<List<Behavior>> =
        behaviorDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeActive(): Flow<List<Behavior>> =
        behaviorDao.observeActive().map { rows -> rows.map { it.toDomain() } }

    fun observeById(id: String): Flow<Behavior?> =
        behaviorDao.observeById(id).map { it?.toDomain() }

    fun observeSchedules(behaviorId: String): Flow<List<Schedule>> =
        scheduleDao.observeByBehavior(behaviorId).map { rows -> rows.map { it.toDomain() } }

    fun observeAllSchedules(): Flow<List<Schedule>> =
        scheduleDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeInstructions(behaviorId: String): Flow<List<DailyInstruction>> =
        instructionDao.observeByBehavior(behaviorId).map { rows -> rows.map { it.toDomain() } }

    fun observeChecklist(behaviorId: String): Flow<List<ChecklistItem>> =
        checklistDao.observeByBehavior(behaviorId).map { rows -> rows.map { it.toDomain() } }

    fun observeExceptions(): Flow<List<BehaviorException>> =
        exceptionDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeTemplates(): Flow<List<BehaviorTemplate>> =
        templateDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeProgression(behaviorId: String): Flow<List<ProgressionStep>> =
        progressionDao.observeByBehavior(behaviorId).map { rows -> rows.map { it.toDomain() } }

    /** Behavior plus everything needed to render or schedule it. */
    fun observeDetail(behaviorId: String): Flow<BehaviorDetail?> =
        combine(
            behaviorDao.observeById(behaviorId),
            scheduleDao.observeByBehavior(behaviorId),
            instructionDao.observeByBehavior(behaviorId),
            checklistDao.observeByBehavior(behaviorId),
            progressionDao.observeByBehavior(behaviorId),
        ) { behavior, schedules, instructions, checklist, progression ->
            behavior?.let {
                BehaviorDetail(
                    behavior = it.toDomain(),
                    schedules = schedules.map { s -> s.toDomain() },
                    instructions = instructions.map { i -> i.toDomain() },
                    checklist = checklist.map { c -> c.toDomain() },
                    progression = progression.map { p -> p.toDomain() },
                )
            }
        }

    suspend fun get(id: String): Behavior? = behaviorDao.getById(id)?.toDomain()

    suspend fun getAll(): List<Behavior> = behaviorDao.getAll().map { it.toDomain() }

    suspend fun getActive(): List<Behavior> = behaviorDao.getActive().map { it.toDomain() }

    suspend fun getSchedules(behaviorId: String): List<Schedule> =
        scheduleDao.getByBehavior(behaviorId).map { it.toDomain() }

    suspend fun getInstructions(behaviorId: String): List<DailyInstruction> =
        instructionDao.getByBehavior(behaviorId).map { it.toDomain() }

    suspend fun getChecklist(behaviorId: String): List<ChecklistItem> =
        checklistDao.getByBehavior(behaviorId).map { it.toDomain() }

    suspend fun getProgression(behaviorId: String): List<ProgressionStep> =
        progressionDao.getByBehavior(behaviorId).map { it.toDomain() }

    suspend fun getExceptionsFrom(date: LocalDate): List<BehaviorException> =
        exceptionDao.getActiveFrom(date.toEpochDay()).map { it.toDomain() }

    suspend fun isNameTaken(name: String, excludingId: String): Boolean =
        behaviorDao.countByName(name.trim(), excludingId) > 0

    // --- Writes -------------------------------------------------------------------------

    /**
     * Saves a behavior together with its schedules. Returns true when the change affects future
     * occurrences, which tells the caller it needs to regenerate instances and refresh alarms.
     */
    suspend fun save(
        behavior: Behavior,
        schedules: List<Schedule>,
        instructions: List<DailyInstruction> = emptyList(),
        checklist: List<ChecklistItem> = emptyList(),
        progression: List<ProgressionStep> = emptyList(),
        replaceInstructions: Boolean = false,
        replaceChecklist: Boolean = false,
    ): Behavior {
        val now = clock.now()
        val previous = behaviorDao.getById(behavior.id)?.toDomain()

        // A notification channel cannot be edited once created, so a sound or vibration change
        // has to mint a new one. See NotificationChannels.
        val channelChanged = previous != null && (
            previous.soundUri != behavior.soundUri ||
                previous.soundEnabled != behavior.soundEnabled ||
                previous.vibrationEnabled != behavior.vibrationEnabled ||
                previous.enforcement != behavior.enforcement
            )

        val stamped = behavior.copy(
            updatedAt = now,
            createdAt = previous?.createdAt ?: now,
            channelVersion = if (channelChanged) behavior.channelVersion + 1 else behavior.channelVersion,
        )

        db.withTransaction {
            behaviorDao.upsert(stamped.toEntity())

            // Schedules are replaced wholesale: the editor always sends the complete set.
            val existingSchedules = scheduleDao.getByBehavior(behavior.id).map { it.id }.toSet()
            val incoming = schedules.map { it.copy(behaviorId = behavior.id) }
            val incomingIds = incoming.map { it.id }.toSet()
            (existingSchedules - incomingIds).forEach { scheduleDao.deleteById(it) }
            if (incoming.isNotEmpty()) scheduleDao.upsertAll(incoming.map { it.toEntity() })

            if (replaceInstructions) {
                val existing = instructionDao.getByBehavior(behavior.id).map { it.id }.toSet()
                val keep = instructions.map { it.id }.toSet()
                (existing - keep).forEach { instructionDao.deleteById(it) }
            }
            if (instructions.isNotEmpty()) {
                instructionDao.upsertAll(instructions.map { it.copy(behaviorId = behavior.id).toEntity() })
            }

            if (replaceChecklist) {
                val existing = checklistDao.getByBehavior(behavior.id).map { it.id }.toSet()
                val keep = checklist.map { it.id }.toSet()
                (existing - keep).forEach { checklistDao.deleteById(it) }
            }
            if (checklist.isNotEmpty()) {
                checklistDao.upsertAll(
                    checklist.mapIndexed { index, item ->
                        item.copy(behaviorId = behavior.id, position = index).toEntity()
                    },
                )
            }

            progressionDao.deleteByBehavior(behavior.id)
            if (progression.isNotEmpty()) {
                progressionDao.upsertAll(progression.map { it.copy(behaviorId = behavior.id).toEntity() })
            }

            eventDao.insert(
                actionEvent(
                    id = Ids.new(),
                    instanceId = null,
                    behaviorId = behavior.id,
                    type = if (previous == null) ActionType.SCHEDULED else ActionType.EDIT,
                    atMillis = now.toEpochMilli(),
                    detail = if (previous == null) "created" else "edited",
                ),
            )

            // Future, untouched occurrences are stale once the schedule changes. History and
            // anything the user already acted on is left alone.
            instanceDao.deleteFutureScheduled(behavior.id, clock.today().toEpochDay())
        }

        return stamped
    }

    suspend fun setActive(id: String, active: Boolean) {
        behaviorDao.setActive(id, active, clock.nowMillis())
        if (!active) {
            // Pausing must not leave alarms armed for a behavior the user switched off.
            instanceDao.deleteFutureScheduled(id, clock.today().toEpochDay())
        }
    }

    suspend fun delete(id: String) {
        // Cascades remove schedules, instructions, checklist, progression and instances.
        behaviorDao.deleteById(id)
    }

    suspend fun saveInstruction(instruction: DailyInstruction) {
        instructionDao.upsert(instruction.toEntity())
    }

    suspend fun deleteInstruction(id: String) = instructionDao.deleteById(id)

    suspend fun saveException(exception: BehaviorException) {
        exceptionDao.upsert(exception.toEntity())

        // A new skip or vacation invalidates already-generated future rows, which are then
        // regenerated with the exception applied. A null behaviorId is a global exception (a
        // holiday, a week away), so it has to clear every behavior, not none.
        val fromDay = maxOf(exception.startDate, clock.today()).toEpochDay()
        val affected = exception.behaviorId?.let { listOf(it) }
            ?: behaviorDao.getActive().map { it.id }
        affected.forEach { instanceDao.deleteFutureScheduled(it, fromDay) }
    }

    suspend fun deleteException(id: String) = exceptionDao.deleteById(id)

    suspend fun saveTemplate(template: BehaviorTemplate) = templateDao.upsert(template.toEntity())

    suspend fun deleteTemplate(id: String) = templateDao.deleteById(id)

    suspend fun getTemplate(id: String): BehaviorTemplate? = templateDao.getById(id)?.toDomain()

    /**
     * Applies a coaching suggestion the user accepted: moves one time in one schedule. Never
     * called automatically.
     */
    suspend fun moveScheduleTime(scheduleId: String, from: LocalTime, to: LocalTime): Boolean {
        val schedule = scheduleDao.getById(scheduleId)?.toDomain() ?: return false
        if (from !in schedule.timesOfDay) return false
        val updated = schedule.copy(
            timesOfDay = (schedule.timesOfDay - from + to).distinct().sorted(),
        )
        db.withTransaction {
            scheduleDao.upsert(updated.toEntity())
            instanceDao.deleteFutureScheduled(schedule.behaviorId, clock.today().toEpochDay())
            eventDao.insert(
                actionEvent(
                    id = Ids.new(),
                    instanceId = null,
                    behaviorId = schedule.behaviorId,
                    type = ActionType.EDIT,
                    atMillis = clock.nowMillis(),
                    detail = "moved $from to $to",
                ),
            )
        }
        return true
    }

    suspend fun ensureBuiltInTemplates(templates: List<BehaviorTemplate>) {
        if (templateDao.count() > 0) return
        templateDao.upsertAll(templates.map { it.toEntity() })
    }
}

data class BehaviorDetail(
    val behavior: Behavior,
    val schedules: List<Schedule>,
    val instructions: List<DailyInstruction>,
    val checklist: List<ChecklistItem>,
    val progression: List<ProgressionStep>,
)
