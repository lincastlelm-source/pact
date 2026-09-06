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
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.ChecklistTick
import com.pact.coach.domain.model.CompletionQuality
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.model.PassReason
import com.pact.coach.domain.recovery.RecoveryChain
import com.pact.coach.domain.recovery.RecoveryEngine
import com.pact.coach.domain.scheduler.InstanceGenerator
import com.pact.coach.domain.scheduler.SchedulingHorizon
import com.pact.coach.domain.state.IllegalStateTransitionException
import com.pact.coach.domain.state.InstanceStateMachine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

/**
 * The write path for everything the user actually does: commit, recover, pass, complete.
 *
 * Two invariants are enforced here and nowhere else:
 *
 *  1. Every state change goes through [InstanceStateMachine], so an impossible row cannot exist.
 *  2. Every state change also appends an [com.pact.coach.data.db.entity.ActionEventEntity], in
 *     the same transaction. The audit trail can never disagree with the instance table.
 */
class InstanceRepository(
    private val db: PactDatabase,
    private val clock: ClockProvider,
    private val recoveryEngine: RecoveryEngine = RecoveryEngine(),
) {
    private val instanceDao = db.instanceDao()
    private val eventDao = db.eventDao()
    private val behaviorDao = db.behaviorDao()
    private val checklistDao = db.checklistDao()

    /** What a decision produced, so the caller knows whether to (re)arm an alarm. */
    sealed interface DecisionResult {
        data class Ok(
            val instance: BehaviorInstance,
            val newInstance: BehaviorInstance? = null,
            val message: String? = null,
        ) : DecisionResult

        data class Failed(val reason: String) : DecisionResult
    }

    // --- Reads --------------------------------------------------------------------------

    fun observeForDay(date: LocalDate): Flow<List<BehaviorInstance>> =
        instanceDao.observeForDay(date.toEpochDay()).map { rows -> rows.map { it.toDomain() } }

    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<BehaviorInstance>> =
        instanceDao.observeBetween(from.toEpochDay(), to.toEpochDay())
            .map { rows -> rows.map { it.toDomain() } }

    fun observeUpcoming(limit: Int = 10): Flow<List<BehaviorInstance>> =
        instanceDao.observeUpcoming(clock.nowMillis(), limit).map { rows -> rows.map { it.toDomain() } }

    fun observeOpen(): Flow<List<BehaviorInstance>> =
        instanceDao.observeOpen().map { rows -> rows.map { it.toDomain() } }

    fun observeById(id: String): Flow<BehaviorInstance?> =
        instanceDao.observeById(id).map { it?.toDomain() }

    fun observeChain(rootId: String): Flow<List<BehaviorInstance>> =
        instanceDao.observeChain(rootId).map { rows -> rows.map { it.toDomain() } }

    fun observeChecklistTicks(instanceId: String): Flow<List<ChecklistTick>> =
        checklistDao.observeTicks(instanceId).map { rows -> rows.map { it.toDomain() } }

    suspend fun get(id: String): BehaviorInstance? = instanceDao.getById(id)?.toDomain()

    suspend fun getForBehavior(behaviorId: String): List<BehaviorInstance> =
        instanceDao.getByBehavior(behaviorId).map { it.toDomain() }

    suspend fun getBetween(from: LocalDate, to: LocalDate): List<BehaviorInstance> =
        instanceDao.getBetween(from.toEpochDay(), to.toEpochDay()).map { it.toDomain() }

    suspend fun getChain(rootId: String): RecoveryChain =
        RecoveryChain(rootId, instanceDao.getChain(rootId).map { it.toDomain() })

    /** Instances that need an OS alarm in the next window. */
    suspend fun getSchedulable(windowHours: Long = SchedulingHorizon.ALARM_WINDOW_HOURS): List<BehaviorInstance> {
        val from = clock.nowMillis()
        val to = from + windowHours * 3_600_000L
        return instanceDao.getSchedulable(from, to).map { it.toDomain() }
    }

    // --- Generation ---------------------------------------------------------------------

    /**
     * Persists freshly generated occurrences. Relies on the unique dedupKey index, so calling
     * this repeatedly over an overlapping window is safe and cheap: existing rows are ignored
     * rather than duplicated.
     *
     * @return the number of genuinely new rows.
     */
    suspend fun insertGenerated(instances: List<BehaviorInstance>): Int {
        if (instances.isEmpty()) return 0
        return db.withTransaction {
            var nextCode = instanceDao.maxAlarmRequestCode()
            val stamped = instances.map { instance ->
                instance.copy(
                    alarmRequestCode = ++nextCode,
                    createdAtMillis = clock.nowMillis(),
                )
            }
            val rowIds = instanceDao.insertIgnoring(stamped.map { it.toEntity() })
            rowIds.count { it != -1L }
        }
    }

    // --- Decisions ----------------------------------------------------------------------

    /** The alarm fired. Moves SCHEDULED to DUE and records when it was shown. */
    suspend fun markDue(instanceId: String): BehaviorInstance? {
        val current = instanceDao.getById(instanceId)?.toDomain() ?: return null
        if (current.state != InstanceState.SCHEDULED) return current
        val updated = current.copy(
            state = InstanceState.DUE,
            firedAtMillis = clock.nowMillis(),
        )
        applyTransition(current, updated, ActionType.FIRED)
        return updated
    }

    /** "I am doing this now." */
    suspend fun commit(instanceId: String): DecisionResult {
        val current = instanceDao.getById(instanceId)?.toDomain()
            ?: return DecisionResult.Failed("That reminder is no longer available.")
        if (current.state.isTerminal) {
            return DecisionResult.Failed("This one is already closed.")
        }
        val updated = current.copy(
            state = InstanceState.COMMITTED,
            respondedAtMillis = current.respondedAtMillis ?: clock.nowMillis(),
        )
        return runTransition(current, updated, ActionType.COMMIT)
    }

    /**
     * "I cannot do this now, but I will." Closes the original as RECOVERED and creates a linked
     * follow-up. The original's scheduled time is never rewritten.
     */
    suspend fun recover(instanceId: String, delayMinutes: Int): DecisionResult {
        val current = instanceDao.getById(instanceId)?.toDomain()
            ?: return DecisionResult.Failed("That reminder is no longer available.")
        val behavior = behaviorDao.getById(current.behaviorId)?.toDomain()
            ?: return DecisionResult.Failed("That behavior no longer exists.")

        val zone = ZoneId.of(current.zoneId).takeIf { runCatching { it }.isSuccess } ?: clock.zone()

        return when (
            val result = recoveryEngine.recover(current, behavior, delayMinutes, clock.now(), zone)
        ) {
            is RecoveryEngine.Result.Rejected -> DecisionResult.Failed(result.reason)

            is RecoveryEngine.Result.Created -> {
                try {
                    InstanceStateMachine.require(current.state, InstanceState.RECOVERED)
                } catch (e: IllegalStateTransitionException) {
                    return DecisionResult.Failed("This one is already closed.")
                }
                val saved = db.withTransaction {
                    var nextCode = instanceDao.maxAlarmRequestCode()
                    val recovery = result.recovery.copy(
                        alarmRequestCode = ++nextCode,
                        createdAtMillis = clock.nowMillis(),
                    )
                    instanceDao.update(result.original.toEntity())
                    instanceDao.insertIgnoring(recovery.toEntity())
                    eventDao.insertAll(
                        listOf(
                            actionEvent(
                                id = Ids.new(),
                                instanceId = current.id,
                                behaviorId = current.behaviorId,
                                type = ActionType.RECOVER,
                                atMillis = clock.nowMillis(),
                                fromState = current.state,
                                toState = InstanceState.RECOVERED,
                                detail = "moved $delayMinutes min to ${recovery.id}",
                            ),
                            actionEvent(
                                id = Ids.new(),
                                instanceId = recovery.id,
                                behaviorId = recovery.behaviorId,
                                type = ActionType.SCHEDULED,
                                atMillis = clock.nowMillis(),
                                toState = InstanceState.SCHEDULED,
                                detail = "recovery of ${current.id}",
                            ),
                        ),
                    )
                    recovery
                }
                DecisionResult.Ok(result.original, saved)
            }
        }
    }

    /** "I am intentionally skipping this." Not a failure, and recorded as its own outcome. */
    suspend fun pass(
        instanceId: String,
        reason: PassReason? = null,
        note: String = "",
    ): DecisionResult {
        val current = instanceDao.getById(instanceId)?.toDomain()
            ?: return DecisionResult.Failed("That reminder is no longer available.")
        val behavior = behaviorDao.getById(current.behaviorId)?.toDomain()

        if (behavior?.requirePassReason == true && reason == null) {
            return DecisionResult.Failed("Choose a reason to continue.")
        }

        val updated = current.copy(
            state = InstanceState.PASSED,
            respondedAtMillis = current.respondedAtMillis ?: clock.nowMillis(),
            passReason = reason,
            passNote = note.trim(),
        )
        return runTransition(current, updated, ActionType.PASS)
    }

    /**
     * Marks the occurrence done. [minimumOnly] records that the user hit their floor rather than
     * their full target, which the coaching engine reads to spot a target that is set too high.
     */
    suspend fun complete(
        instanceId: String,
        durationMinutes: Int? = null,
        quantity: Double? = null,
        minimumOnly: Boolean = false,
        partial: Boolean = false,
        rating: Int? = null,
        note: String = "",
    ): DecisionResult {
        val current = instanceDao.getById(instanceId)?.toDomain()
            ?: return DecisionResult.Failed("That reminder is no longer available.")

        val quality = when {
            partial -> CompletionQuality.PARTIAL
            minimumOnly -> CompletionQuality.MINIMUM_COMPLETED
            else -> CompletionQuality.TARGET_COMPLETED
        }

        val now = clock.nowMillis()
        val updated = current.copy(
            state = InstanceState.COMPLETED,
            respondedAtMillis = current.respondedAtMillis ?: now,
            completedAtMillis = now,
            completionQuality = quality,
            actualDurationMinutes = durationMinutes ?: current.actualDurationMinutes,
            actualQuantity = quantity ?: current.actualQuantity,
            rating = rating ?: current.rating,
            note = note.trim().ifBlank { current.note },
        )
        return runTransition(current, updated, ActionType.COMPLETE)
    }

    /** Undo a mis-tap. Only allowed on the same day, and only back to DUE. */
    suspend fun reopen(instanceId: String): DecisionResult {
        val current = instanceDao.getById(instanceId)?.toDomain()
            ?: return DecisionResult.Failed("That reminder is no longer available.")
        if (current.scheduledDate != clock.today()) {
            return DecisionResult.Failed("Only today's entries can be reopened.")
        }
        val updated = current.copy(
            state = InstanceState.DUE,
            completedAtMillis = null,
            completionQuality = null,
        )
        return runTransition(current, updated, ActionType.REOPEN)
    }

    suspend fun cancel(instanceId: String): DecisionResult {
        val current = instanceDao.getById(instanceId)?.toDomain()
            ?: return DecisionResult.Failed("That reminder is no longer available.")
        val updated = current.copy(state = InstanceState.CANCELLED)
        return runTransition(current, updated, ActionType.CANCEL)
    }

    suspend fun setChecklistTick(instanceId: String, itemId: String, checked: Boolean) {
        checklistDao.upsertTick(ChecklistTick(instanceId, itemId, checked).toEntity())
    }

    // --- Sweeps -------------------------------------------------------------------------

    /**
     * Marks unanswered interventions as MISSED once their grace window has passed.
     *
     * Only [InstanceStateMachine.sweepable] states are touched, so this can never overwrite a
     * decision the user made. Runs on app start, on boot and from the periodic worker.
     *
     * @return ids that were marked missed.
     */
    suspend fun sweepMissed(): List<String> {
        val now = clock.nowMillis()
        val today = clock.today()
        val notBeforeDay = today.minusDays(SchedulingHorizon.MISS_SWEEP_DAYS).toEpochDay()

        val behaviors = behaviorDao.getAll().associate { it.id to it.toDomain() }
        val missed = mutableListOf<String>()

        // Timed occurrences: each behavior defines its own grace window.
        val candidates = instanceDao.getOverdue(now, notBeforeDay).map { it.toDomain() }
        for (instance in candidates) {
            val behavior = behaviors[instance.behaviorId] ?: continue
            val scheduledAt = instance.scheduledAtUtcMillis ?: continue
            val graceMinutes = if (instance.state == InstanceState.COMMITTED) {
                // A committed session gets until the end of the day to be marked complete.
                COMMITTED_GRACE_MINUTES
            } else {
                behavior.missWindowMinutes
            }
            if (now - scheduledAt < graceMinutes * 60_000L) continue
            if (instance.state !in InstanceStateMachine.sweepable) continue

            val updated = instance.copy(state = InstanceState.MISSED)
            applyTransition(instance, updated, ActionType.MISS)
            missed += instance.id
        }

        // Untimed occurrences simply expire at the end of their day.
        val stale = instanceDao.getStaleUntimed(today.toEpochDay(), notBeforeDay).map { it.toDomain() }
        for (instance in stale) {
            if (instance.state !in InstanceStateMachine.sweepable) continue
            val updated = instance.copy(state = InstanceState.MISSED)
            applyTransition(instance, updated, ActionType.MISS)
            missed += instance.id
        }

        return missed
    }

    /**
     * Recomputes absolute firing times for future occurrences after a time zone change, keeping
     * the wall-clock time the user chose. "Exercise at 06:30" stays 06:30 in the new zone.
     *
     * @return true when anything changed, so the caller can re-arm alarms.
     */
    suspend fun rebaseFutureInstancesToZone(zone: ZoneId): Boolean {
        val today = clock.today()
        val future = instanceDao.getFutureScheduled(today.toEpochDay()).map { it.toDomain() }
        val changed = future.mapNotNull { instance ->
            val time = instance.scheduledTime ?: return@mapNotNull null
            val recomputed = InstanceGenerator.absoluteMillis(instance.scheduledDate, time, zone)
            if (recomputed == instance.scheduledAtUtcMillis && instance.zoneId == zone.id) {
                null
            } else {
                instance.copy(scheduledAtUtcMillis = recomputed, zoneId = zone.id)
            }
        }
        if (changed.isEmpty()) return false
        instanceDao.updateAll(changed.map { it.toEntity() })
        return true
    }

    // --- Internals ----------------------------------------------------------------------

    private suspend fun runTransition(
        current: BehaviorInstance,
        updated: BehaviorInstance,
        action: ActionType,
    ): DecisionResult = try {
        InstanceStateMachine.require(current.state, updated.state)
        applyTransition(current, updated, action)
        DecisionResult.Ok(updated)
    } catch (e: IllegalStateTransitionException) {
        DecisionResult.Failed("That is not possible from here (${current.state.name.lowercase()}).")
    }

    /** Instance row and audit event are written together or not at all. */
    private suspend fun applyTransition(
        current: BehaviorInstance,
        updated: BehaviorInstance,
        action: ActionType,
    ) {
        db.withTransaction {
            instanceDao.update(updated.toEntity())
            eventDao.insert(
                actionEvent(
                    id = Ids.new(),
                    instanceId = updated.id,
                    behaviorId = updated.behaviorId,
                    type = action,
                    atMillis = clock.nowMillis(),
                    fromState = current.state,
                    toState = updated.state,
                ),
            )
        }
    }

    companion object {
        /** A committed-but-unfinished session is given this long before it counts as missed. */
        const val COMMITTED_GRACE_MINUTES = 12 * 60
    }
}
