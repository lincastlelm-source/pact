package com.pact.coach.domain.state

import com.pact.coach.domain.model.InstanceState

/**
 * The single authority on how a [com.pact.coach.domain.model.BehaviorInstance] may move between
 * states. Repositories route every state write through [require] so an impossible row (a
 * COMPLETED instance that later becomes MISSED by a background sweep, say) cannot be created.
 *
 * The transition table is deliberately generous in one direction: several terminal states can
 * still move to COMPLETED. That is a product decision, not an accident. A user who passed a
 * behavior and then did it anyway should be able to record that, and a missed morning that gets
 * done at lunchtime is a success, not a permanent black mark.
 */
object InstanceStateMachine {

    private val transitions: Map<InstanceState, Set<InstanceState>> = mapOf(
        InstanceState.SCHEDULED to setOf(
            InstanceState.DUE,
            InstanceState.COMMITTED,
            InstanceState.COMPLETED,
            InstanceState.PASSED,
            InstanceState.RECOVERED,
            InstanceState.MISSED,
            InstanceState.CANCELLED,
        ),
        InstanceState.DUE to setOf(
            InstanceState.COMMITTED,
            InstanceState.RECOVERED,
            InstanceState.PASSED,
            InstanceState.COMPLETED,
            InstanceState.MISSED,
            InstanceState.CANCELLED,
        ),
        InstanceState.COMMITTED to setOf(
            InstanceState.COMPLETED,
            InstanceState.RECOVERED,
            InstanceState.PASSED,
            InstanceState.MISSED,
            InstanceState.CANCELLED,
        ),
        // Recovery hands off to a brand new instance; this row is closed.
        InstanceState.RECOVERED to setOf(
            InstanceState.CANCELLED,
        ),
        // Deliberately reachable: "I passed it, then did it anyway."
        InstanceState.PASSED to setOf(
            InstanceState.COMPLETED,
            InstanceState.CANCELLED,
        ),
        // Deliberately reachable: a late completion still counts.
        InstanceState.MISSED to setOf(
            InstanceState.COMPLETED,
            InstanceState.CANCELLED,
        ),
        InstanceState.COMPLETED to setOf(
            // Undo, for a mis-tap. Bounded to the same day by the repository.
            InstanceState.DUE,
            InstanceState.CANCELLED,
        ),
        InstanceState.CANCELLED to emptySet(),
    )

    fun canTransition(from: InstanceState, to: InstanceState): Boolean =
        from == to || transitions[from].orEmpty().contains(to)

    fun allowedFrom(from: InstanceState): Set<InstanceState> = transitions[from].orEmpty()

    /** @throws IllegalStateTransitionException when the move is not permitted. */
    fun require(from: InstanceState, to: InstanceState) {
        if (!canTransition(from, to)) throw IllegalStateTransitionException(from, to)
    }

    /**
     * States a background sweep is allowed to convert into MISSED. Anything terminal is left
     * alone so a sweep can never overwrite a decision the user actually made.
     */
    val sweepable: Set<InstanceState> = setOf(
        InstanceState.SCHEDULED,
        InstanceState.DUE,
        InstanceState.COMMITTED,
    )
}

class IllegalStateTransitionException(
    val from: InstanceState,
    val to: InstanceState,
) : IllegalStateException("Cannot move a behavior from $from to $to")
