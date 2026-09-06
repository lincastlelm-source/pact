package com.pact.coach.domain.recovery

import com.pact.coach.core.util.Ids
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.scheduler.InstanceGenerator
import java.time.Instant
import java.time.ZoneId

/**
 * Recovery is the product's central idea: "I cannot do this now, but I will come back to it."
 *
 * It is never a failure and it never edits the original occurrence's plan. The original row is
 * closed as RECOVERED, keeping its scheduled time intact for the history, and a *new* instance is
 * created at the chosen later time, linked back through
 * [BehaviorInstance.originInstanceId] and [BehaviorInstance.rootInstanceId].
 *
 * That gives the analytics layer a chain it can reason about: "this behavior was planned for
 * 18:00, recovered twice, and finished at 19:30."
 */
class RecoveryEngine {

    sealed interface Result {
        data class Created(val original: BehaviorInstance, val recovery: BehaviorInstance) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * @param delayMinutes how far out the user pushed it, from the quick options or a custom pick.
     * @param now used both for the audit trail and as the base the delay is measured from.
     */
    fun recover(
        instance: BehaviorInstance,
        behavior: Behavior,
        delayMinutes: Int,
        now: Instant,
        zone: ZoneId,
    ): Result {
        if (!behavior.recoveryEnabled) {
            return Result.Rejected("Recovery is turned off for this behavior.")
        }
        if (instance.state.isTerminal) {
            return Result.Rejected("This one is already closed.")
        }
        if (delayMinutes !in MIN_DELAY_MINUTES..MAX_DELAY_MINUTES) {
            return Result.Rejected("Pick a time between 5 minutes and 24 hours from now.")
        }
        if (instance.recoveryDepth >= behavior.maxRecoveriesPerInstance) {
            return Result.Rejected(
                "You have already moved this ${instance.recoveryDepth} times. " +
                    "It may be worth changing the schedule instead.",
            )
        }

        // Measure the delay from *now*, not from the original slot. If the user opens a 06:30
        // alarm at 08:00 and asks for 30 minutes, they mean 08:30.
        val recoveryAt = now.plusSeconds(delayMinutes * 60L)
        val local = recoveryAt.atZone(zone)

        val closedOriginal = instance.copy(
            state = InstanceState.RECOVERED,
            respondedAtMillis = instance.respondedAtMillis ?: now.toEpochMilli(),
        )

        val newId = Ids.new()
        val recovery = BehaviorInstance(
            id = newId,
            behaviorId = instance.behaviorId,
            scheduleId = instance.scheduleId,
            // The recovery can land on the next calendar day; that is expected and supported.
            scheduledDate = local.toLocalDate(),
            scheduledTime = local.toLocalTime().withSecond(0).withNano(0),
            scheduledAtUtcMillis = recoveryAt.toEpochMilli(),
            zoneId = zone.id,
            occurrenceIndex = instance.occurrenceIndex,
            state = InstanceState.SCHEDULED,
            originInstanceId = instance.id,
            rootInstanceId = instance.chainId,
            recoveryDepth = instance.recoveryDepth + 1,
            dedupKey = InstanceGenerator.recoveryDedupKey(newId),
            createdAtMillis = now.toEpochMilli(),
        )

        return Result.Created(closedOriginal, recovery)
    }

    companion object {
        const val MIN_DELAY_MINUTES = 5
        const val MAX_DELAY_MINUTES = 24 * 60

        /** The quick options offered on the intervention screen. */
        val QUICK_OPTIONS = listOf(15, 30, 45, 60, 120)
    }
}

/**
 * The outcome of a whole recovery chain, which is what the statistics layer actually scores.
 * A chain that was recovered twice and then completed counts once, as a completion.
 */
data class RecoveryChain(
    val rootId: String,
    val instances: List<BehaviorInstance>,
) {
    val recoveryCount: Int get() = instances.count { it.state == InstanceState.RECOVERED }

    val wasRecovered: Boolean get() = recoveryCount > 0

    /** The state that decides how this chain is scored: the last non-RECOVERED row. */
    val outcome: InstanceState
        get() = instances
            .sortedBy { it.recoveryDepth }
            .lastOrNull { it.state != InstanceState.RECOVERED }
            ?.state
            ?: InstanceState.RECOVERED

    val finalInstance: BehaviorInstance?
        get() = instances.maxByOrNull { it.recoveryDepth }

    val originalInstance: BehaviorInstance?
        get() = instances.minByOrNull { it.recoveryDepth }

    val isResolved: Boolean get() = outcome.isTerminal && outcome != InstanceState.RECOVERED

    companion object {
        /** Group a flat list of instances into chains by their root id. */
        fun group(instances: List<BehaviorInstance>): List<RecoveryChain> =
            instances.groupBy { it.chainId }
                .map { (root, rows) -> RecoveryChain(root, rows) }
    }
}
