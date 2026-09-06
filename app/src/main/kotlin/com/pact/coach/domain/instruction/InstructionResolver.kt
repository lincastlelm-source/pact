package com.pact.coach.domain.instruction

import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.ChecklistItem
import com.pact.coach.domain.model.DailyInstruction
import com.pact.coach.domain.model.ProgressionStep
import com.pact.coach.domain.model.ResolvedInstruction
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Works out what a behavior actually means on a particular day and slot.
 *
 * "Exercise" stays a single behavior with a single history, while Monday is a 20-minute run,
 * Thursday is strength training and Sunday is a rest day. Rows are matched most-specific-first:
 *
 *   exact date + occurrence  >  exact date  >  weekday + occurrence  >  weekday  >  default
 *
 * A matching row only overrides the fields it actually sets, so a Tuesday row that just says
 * "Upper body" still inherits the behavior's default duration.
 */
class InstructionResolver {

    fun resolve(
        behavior: Behavior,
        instructions: List<DailyInstruction>,
        checklist: List<ChecklistItem>,
        date: LocalDate,
        occurrenceIndex: Int,
        progression: List<ProgressionStep> = emptyList(),
        behaviorStartDate: LocalDate? = null,
    ): ResolvedInstruction {
        val candidates = instructions
            .filter { it.behaviorId == behavior.id }
            .filter { matches(it, date, occurrenceIndex) }
            .sortedByDescending { it.specificity }

        // Fold from least to most specific so the most specific row wins field by field.
        var title = behavior.name
        var text = behavior.defaultInstruction
        var duration = behavior.targetDurationMinutes
        var quantity = behavior.targetQuantity
        var notes = behavior.notes
        var reference: String? = null
        var image: String? = null
        var restDay = false
        var winningInstructionId: String? = null

        for (row in candidates.reversed()) {
            if (row.title.isNotBlank()) title = row.title
            if (row.instructions.isNotBlank()) text = row.instructions
            row.durationMinutes?.let { duration = it }
            row.quantityTarget?.let { quantity = it }
            if (row.notes.isNotBlank()) notes = row.notes
            row.referenceUri?.let { reference = it }
            row.imageUri?.let { image = it }
            restDay = row.isRestDay
            winningInstructionId = row.id
        }

        // A manual progression ramp overrides the behavior default but not an explicit day plan.
        if (behavior.progressionEnabled && behaviorStartDate != null && candidates.none { it.durationMinutes != null }) {
            ProgressionResolver.stepFor(progression, behaviorStartDate, date)?.let { step ->
                step.targetDurationMinutes?.let { duration = it }
                step.targetQuantity?.let { quantity = it }
            }
        }

        val items = checklist
            .filter { it.behaviorId == behavior.id }
            .filter { it.dailyInstructionId == null || it.dailyInstructionId == winningInstructionId }
            .sortedBy { it.position }

        return ResolvedInstruction(
            title = title,
            instructions = text,
            durationMinutes = duration,
            quantityTarget = quantity,
            notes = notes,
            referenceUri = reference,
            imageUri = image,
            isRestDay = restDay,
            checklist = items,
        )
    }

    private fun matches(row: DailyInstruction, date: LocalDate, occurrenceIndex: Int): Boolean {
        if (row.occurrenceIndex != null && row.occurrenceIndex != occurrenceIndex) return false
        return when {
            row.date != null -> row.date == date
            row.dayOfWeek != null -> row.dayOfWeek == date.dayOfWeek.value
            else -> true // the default row
        }
    }
}

/**
 * Optional, entirely manual ramp: week 1 is 10 minutes, week 4 is 30. The app never raises a
 * target by itself; the user writes these steps and can delete them at any time.
 */
object ProgressionResolver {

    /** The step covering [date], or the last defined step once the ramp is finished. */
    fun stepFor(
        steps: List<ProgressionStep>,
        behaviorStartDate: LocalDate,
        date: LocalDate,
    ): ProgressionStep? {
        if (steps.isEmpty()) return null
        if (date.isBefore(behaviorStartDate)) return steps.minByOrNull { it.weekIndex }

        val weeksIn = ChronoUnit.WEEKS.between(behaviorStartDate, date).toInt()
        val sorted = steps.sortedBy { it.weekIndex }
        // The applicable step is the last one whose week has already started.
        return sorted.lastOrNull { it.weekIndex <= weeksIn } ?: sorted.first()
    }

    fun weekIndexOf(behaviorStartDate: LocalDate, date: LocalDate): Int =
        ChronoUnit.WEEKS.between(behaviorStartDate, date).toInt().coerceAtLeast(0)
}
