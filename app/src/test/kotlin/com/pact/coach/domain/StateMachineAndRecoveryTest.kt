package com.pact.coach.domain

import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.recovery.RecoveryChain
import com.pact.coach.domain.recovery.RecoveryEngine
import com.pact.coach.domain.state.IllegalStateTransitionException
import com.pact.coach.domain.state.InstanceStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class InstanceStateMachineTest {

    @Test
    fun `the documented happy paths are allowed`() {
        assertTrue(InstanceStateMachine.canTransition(InstanceState.SCHEDULED, InstanceState.DUE))
        assertTrue(InstanceStateMachine.canTransition(InstanceState.DUE, InstanceState.COMMITTED))
        assertTrue(InstanceStateMachine.canTransition(InstanceState.DUE, InstanceState.RECOVERED))
        assertTrue(InstanceStateMachine.canTransition(InstanceState.DUE, InstanceState.PASSED))
        assertTrue(InstanceStateMachine.canTransition(InstanceState.DUE, InstanceState.MISSED))
        assertTrue(InstanceStateMachine.canTransition(InstanceState.COMMITTED, InstanceState.COMPLETED))
    }

    @Test
    fun `a completed instance cannot be quietly turned into a miss`() {
        // This is the invariant that stops a background sweep from erasing a real completion.
        assertFalse(InstanceStateMachine.canTransition(InstanceState.COMPLETED, InstanceState.MISSED))
        assertFalse(InstanceStateMachine.canTransition(InstanceState.COMPLETED, InstanceState.PASSED))
    }

    @Test
    fun `passing then doing it anyway is allowed`() {
        assertTrue(InstanceStateMachine.canTransition(InstanceState.PASSED, InstanceState.COMPLETED))
    }

    @Test
    fun `a missed occurrence can still be completed late`() {
        assertTrue(InstanceStateMachine.canTransition(InstanceState.MISSED, InstanceState.COMPLETED))
    }

    @Test
    fun `a recovered instance is closed and hands off to its successor`() {
        assertFalse(InstanceStateMachine.canTransition(InstanceState.RECOVERED, InstanceState.COMPLETED))
        assertFalse(InstanceStateMachine.canTransition(InstanceState.RECOVERED, InstanceState.DUE))
        assertTrue(InstanceStateMachine.canTransition(InstanceState.RECOVERED, InstanceState.CANCELLED))
    }

    @Test
    fun `cancelled is a dead end`() {
        assertTrue(InstanceStateMachine.allowedFrom(InstanceState.CANCELLED).isEmpty())
    }

    @Test
    fun `a state can always transition to itself so repeated taps are harmless`() {
        InstanceState.entries.forEach {
            assertTrue(it.name, InstanceStateMachine.canTransition(it, it))
        }
    }

    @Test
    fun `require throws on an impossible transition`() {
        try {
            InstanceStateMachine.require(InstanceState.COMPLETED, InstanceState.MISSED)
            fail("expected an IllegalStateTransitionException")
        } catch (e: IllegalStateTransitionException) {
            assertEquals(InstanceState.COMPLETED, e.from)
            assertEquals(InstanceState.MISSED, e.to)
        }
    }

    @Test
    fun `the sweep may only touch unresolved states`() {
        assertEquals(
            setOf(InstanceState.SCHEDULED, InstanceState.DUE, InstanceState.COMMITTED),
            InstanceStateMachine.sweepable,
        )
        // Nothing the user decided is sweepable.
        assertFalse(InstanceState.COMPLETED in InstanceStateMachine.sweepable)
        assertFalse(InstanceState.PASSED in InstanceStateMachine.sweepable)
        assertFalse(InstanceState.RECOVERED in InstanceStateMachine.sweepable)
    }

    @Test
    fun `terminal states are classified correctly`() {
        assertTrue(InstanceState.COMPLETED.isTerminal)
        assertTrue(InstanceState.PASSED.isTerminal)
        assertTrue(InstanceState.MISSED.isTerminal)
        assertTrue(InstanceState.RECOVERED.isTerminal)
        assertFalse(InstanceState.DUE.isTerminal)
        assertFalse(InstanceState.COMMITTED.isTerminal)
    }
}

class RecoveryEngineTest {

    private val engine = RecoveryEngine()
    private val zone = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-09-07T17:05:00Z")
    private val today = LocalDate.of(2026, 9, 7)

    private fun behavior(
        recoveryEnabled: Boolean = true,
        maxRecoveries: Int = 3,
    ) = Behavior(
        id = "b1",
        name = "Exercise",
        recoveryEnabled = recoveryEnabled,
        maxRecoveriesPerInstance = maxRecoveries,
        createdAt = now,
        updatedAt = now,
    )

    private fun instance(
        state: InstanceState = InstanceState.DUE,
        depth: Int = 0,
        time: LocalTime = LocalTime.of(18, 0),
    ) = BehaviorInstance(
        id = "i1",
        behaviorId = "b1",
        scheduleId = "s1",
        scheduledDate = today,
        scheduledTime = time,
        scheduledAtUtcMillis = java.time.ZonedDateTime.of(today, time, zone).toInstant().toEpochMilli(),
        zoneId = zone.id,
        occurrenceIndex = 0,
        state = state,
        rootInstanceId = "i1",
        recoveryDepth = depth,
        dedupKey = "s|b1|s1|1|0",
        createdAtMillis = now.toEpochMilli(),
    )

    @Test
    fun `recovery closes the original without rewriting its scheduled time`() {
        val original = instance()
        val result = engine.recover(original, behavior(), 30, now, zone)
        assertTrue(result is RecoveryEngine.Result.Created)
        result as RecoveryEngine.Result.Created

        assertEquals(InstanceState.RECOVERED, result.original.state)
        // The plan is preserved. This is what lets history show "planned 18:00, done 18:35".
        assertEquals(LocalTime.of(18, 0), result.original.scheduledTime)
        assertEquals(today, result.original.scheduledDate)
    }

    @Test
    fun `recovery creates a linked successor measured from now`() {
        val original = instance()
        val result = engine.recover(original, behavior(), 30, now, zone) as RecoveryEngine.Result.Created

        assertEquals("i1", result.recovery.originInstanceId)
        assertEquals("i1", result.recovery.rootInstanceId)
        assertEquals(1, result.recovery.recoveryDepth)
        assertEquals(InstanceState.SCHEDULED, result.recovery.state)
        // 17:05 UTC plus 30 minutes.
        assertEquals(
            now.plusSeconds(1800).toEpochMilli(),
            result.recovery.scheduledAtUtcMillis,
        )
    }

    @Test
    fun `a recovery may cross midnight onto the following day`() {
        val lateNight = Instant.parse("2026-09-07T22:40:00Z")
        val result = engine.recover(
            instance(time = LocalTime.of(23, 40)),
            behavior(),
            120,
            lateNight,
            zone,
        ) as RecoveryEngine.Result.Created

        val landed = Instant.ofEpochMilli(result.recovery.scheduledAtUtcMillis!!).atZone(zone)
        assertEquals(today.plusDays(1), landed.toLocalDate())
        assertEquals(today.plusDays(1), result.recovery.scheduledDate)
    }

    @Test
    fun `recovery is refused when the behavior has it turned off`() {
        val result = engine.recover(instance(), behavior(recoveryEnabled = false), 30, now, zone)
        assertTrue(result is RecoveryEngine.Result.Rejected)
    }

    @Test
    fun `recovery is refused once the instance is closed`() {
        val result = engine.recover(instance(state = InstanceState.COMPLETED), behavior(), 30, now, zone)
        assertTrue(result is RecoveryEngine.Result.Rejected)
    }

    @Test
    fun `recovery is refused past the configured limit`() {
        val result = engine.recover(instance(depth = 3), behavior(maxRecoveries = 3), 30, now, zone)
        assertTrue(result is RecoveryEngine.Result.Rejected)
        result as RecoveryEngine.Result.Rejected
        assertTrue(result.reason, result.reason.contains("schedule"))
    }

    @Test
    fun `recovery rejects a nonsensical delay`() {
        assertTrue(engine.recover(instance(), behavior(), 0, now, zone) is RecoveryEngine.Result.Rejected)
        assertTrue(engine.recover(instance(), behavior(), 5000, now, zone) is RecoveryEngine.Result.Rejected)
    }

    @Test
    fun `each recovery gets a unique dedup key so it is never regenerated`() {
        val a = engine.recover(instance(), behavior(), 30, now, zone) as RecoveryEngine.Result.Created
        val b = engine.recover(instance(), behavior(), 45, now, zone) as RecoveryEngine.Result.Created
        assertTrue(a.recovery.dedupKey.startsWith("r|"))
        assertTrue(a.recovery.dedupKey != b.recovery.dedupKey)
    }
}

class RecoveryChainTest {

    private val now = Instant.parse("2026-09-07T00:00:00Z")

    private fun row(
        id: String,
        state: InstanceState,
        depth: Int,
        root: String = "root",
    ) = BehaviorInstance(
        id = id,
        behaviorId = "b1",
        scheduleId = "s1",
        scheduledDate = LocalDate.of(2026, 9, 7),
        scheduledTime = LocalTime.of(18, 0),
        scheduledAtUtcMillis = 0,
        zoneId = "UTC",
        occurrenceIndex = 0,
        state = state,
        rootInstanceId = root,
        originInstanceId = if (depth == 0) null else "prev",
        recoveryDepth = depth,
        dedupKey = id,
        createdAtMillis = now.toEpochMilli(),
    )

    @Test
    fun `a chain that was recovered then completed counts as a completion`() {
        val chain = RecoveryChain(
            "root",
            listOf(
                row("root", InstanceState.RECOVERED, 0),
                row("r1", InstanceState.COMPLETED, 1),
            ),
        )
        assertEquals(InstanceState.COMPLETED, chain.outcome)
        assertTrue(chain.wasRecovered)
        assertTrue(chain.isResolved)
        assertEquals(1, chain.recoveryCount)
    }

    @Test
    fun `a chain still in flight is not resolved`() {
        val chain = RecoveryChain(
            "root",
            listOf(
                row("root", InstanceState.RECOVERED, 0),
                row("r1", InstanceState.SCHEDULED, 1),
            ),
        )
        assertFalse(chain.isResolved)
    }

    @Test
    fun `a chain recovered twice and then missed counts as a miss`() {
        val chain = RecoveryChain(
            "root",
            listOf(
                row("root", InstanceState.RECOVERED, 0),
                row("r1", InstanceState.RECOVERED, 1),
                row("r2", InstanceState.MISSED, 2),
            ),
        )
        assertEquals(InstanceState.MISSED, chain.outcome)
        assertEquals(2, chain.recoveryCount)
    }

    @Test
    fun `a single completed instance is a resolved chain with no recovery`() {
        val chain = RecoveryChain("root", listOf(row("root", InstanceState.COMPLETED, 0)))
        assertEquals(InstanceState.COMPLETED, chain.outcome)
        assertFalse(chain.wasRecovered)
    }

    @Test
    fun `grouping splits a flat list into chains by root`() {
        val rows = listOf(
            row("a", InstanceState.COMPLETED, 0, root = "a"),
            row("b", InstanceState.RECOVERED, 0, root = "b"),
            row("b1", InstanceState.COMPLETED, 1, root = "b"),
        )
        val chains = RecoveryChain.group(rows)
        assertEquals(2, chains.size)
        assertEquals(1, chains.first { it.rootId == "a" }.instances.size)
        assertEquals(2, chains.first { it.rootId == "b" }.instances.size)
    }
}
