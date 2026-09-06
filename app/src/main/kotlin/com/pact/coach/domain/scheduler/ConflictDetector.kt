package com.pact.coach.domain.scheduler

import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.Importance
import java.time.LocalTime

/**
 * Finds occurrences that collide on the clock. The app only ever *warns*: it will not move a
 * behavior on the user's behalf.
 *
 * Two occurrences conflict when their windows overlap. A window runs from the scheduled time for
 * the behavior's target duration, with a small floor so two zero-duration behaviors booked at
 * exactly the same minute still register as a clash.
 */
class ConflictDetector(
    private val minimumWindowMinutes: Int = 15,
) {

    data class Conflict(
        val first: BehaviorInstance,
        val second: BehaviorInstance,
        val firstBehavior: Behavior,
        val secondBehavior: Behavior,
        val overlapMinutes: Int,
    ) {
        /** Both sides matter to the user, so the warning is worth showing prominently. */
        val isHighPriority: Boolean
            get() = firstBehavior.importance in HIGH && secondBehavior.importance in HIGH

        val message: String
            get() = if (isHighPriority) {
                "You have two high-priority behaviors scheduled at the same time: " +
                    "${firstBehavior.name} and ${secondBehavior.name}."
            } else {
                "${firstBehavior.name} and ${secondBehavior.name} overlap."
            }

        private companion object {
            val HIGH = setOf(Importance.HIGH, Importance.CRITICAL)
        }
    }

    /**
     * @param instances occurrences for a single day, timed or untimed. Untimed ones are ignored
     *   because they do not compete for a specific moment.
     */
    fun detect(
        instances: List<BehaviorInstance>,
        behaviorsById: Map<String, Behavior>,
    ): List<Conflict> {
        val timed = instances
            .filter { it.scheduledTime != null }
            .filter { behaviorsById[it.behaviorId]?.isActive == true }
            .sortedBy { it.scheduledTime }

        val conflicts = mutableListOf<Conflict>()

        for (i in timed.indices) {
            for (j in i + 1 until timed.size) {
                val a = timed[i]
                val b = timed[j]
                // Same behavior scheduled twice a day is intentional, not a conflict.
                if (a.behaviorId == b.behaviorId) continue

                val behaviorA = behaviorsById[a.behaviorId] ?: continue
                val behaviorB = behaviorsById[b.behaviorId] ?: continue

                val overlap = overlapMinutes(a, behaviorA, b, behaviorB)
                if (overlap > 0) {
                    conflicts += Conflict(a, b, behaviorA, behaviorB, overlap)
                }
            }
        }
        return conflicts.sortedByDescending { it.overlapMinutes }
    }

    private fun overlapMinutes(
        a: BehaviorInstance,
        behaviorA: Behavior,
        b: BehaviorInstance,
        behaviorB: Behavior,
    ): Int {
        val startA = minutesOfDay(a.scheduledTime ?: return 0)
        val startB = minutesOfDay(b.scheduledTime ?: return 0)
        val endA = startA + windowOf(behaviorA)
        val endB = startB + windowOf(behaviorB)

        val overlapStart = maxOf(startA, startB)
        val overlapEnd = minOf(endA, endB)
        return (overlapEnd - overlapStart).coerceAtLeast(0)
    }

    private fun windowOf(behavior: Behavior): Int =
        (behavior.targetDurationMinutes ?: 0).coerceAtLeast(minimumWindowMinutes)

    private fun minutesOfDay(time: LocalTime): Int = time.hour * 60 + time.minute
}
