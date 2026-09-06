package com.pact.coach.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pact.coach.core.util.Ids
import com.pact.coach.di.AppContainer
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorArchetype
import com.pact.coach.domain.model.BehaviorTemplate
import com.pact.coach.domain.model.ChecklistItem
import com.pact.coach.domain.model.DailyInstruction
import com.pact.coach.domain.model.DayMask
import com.pact.coach.domain.model.Difficulty
import com.pact.coach.domain.model.EnforcementLevel
import com.pact.coach.domain.model.Goal
import com.pact.coach.domain.model.Importance
import com.pact.coach.domain.model.Measurement
import com.pact.coach.domain.model.ProgressionStep
import com.pact.coach.domain.model.RecurrenceType
import com.pact.coach.domain.model.ReflectionFrequency
import com.pact.coach.domain.model.Schedule
import com.pact.coach.domain.recurrence.RecurrenceValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * The behavior editor.
 *
 * It carries a lot of fields, so the state is one flat object with granular setters rather than
 * nested sub-states: it makes the form trivially previewable and keeps validation in one place.
 *
 * Saving always goes through [com.pact.coach.data.repository.BehaviorRepository.save], which
 * drops stale future occurrences, and is then followed by a coordinator refresh so alarms match
 * the new schedule immediately.
 */
class BehaviorEditViewModel(
    private val container: AppContainer,
    private val behaviorId: String?,
    private val presetGoalId: String?,
) : ViewModel() {

    data class UiState(
        val id: String? = null,
        val name: String = "",
        val description: String = "",
        val goalId: String? = null,
        val category: String = "",

        val archetype: BehaviorArchetype = BehaviorArchetype.TIME_BASED,
        val isTimeBased: Boolean = true,
        val measurement: Measurement = Measurement.DURATION,

        val importance: Importance = Importance.NORMAL,
        val difficulty: Difficulty = Difficulty.MEDIUM,
        val enforcement: EnforcementLevel = EnforcementLevel.COACH,

        val targetDuration: String = "30",
        val minimumDuration: String = "5",
        val targetQuantity: String = "",
        val minimumQuantity: String = "",
        val quantityUnit: String = "",

        val defaultInstruction: String = "",
        val notes: String = "",

        val recurrenceType: RecurrenceType = RecurrenceType.DAILY,
        val daysOfWeekMask: Int = DayMask.ALL,
        val intervalDays: String = "2",
        val timesPerWeek: String = "3",
        val timesPerDay: String = "1",
        val timesOfDay: List<LocalTime> = listOf(LocalTime.of(7, 0)),

        val reminderEnabled: Boolean = true,
        val soundEnabled: Boolean = true,
        val vibrationEnabled: Boolean = true,
        val soundUri: String? = null,
        val soundLabel: String = "Default",
        val vibrationPattern: String = "default",
        val preAlertMinutes: String = "",
        val escalationMinutes: String = "",

        val recoveryEnabled: Boolean = true,
        val defaultRecoveryMinutes: String = "30",
        val maxRecoveries: String = "3",
        val missWindowMinutes: String = "120",

        val reflectionFrequency: ReflectionFrequency = ReflectionFrequency.SOMETIMES,
        val requirePassReason: Boolean = false,
        val allowPartialChecklist: Boolean = true,
        val isActive: Boolean = true,

        val checklist: List<ChecklistItem> = emptyList(),
        val dailyInstructions: List<DailyInstruction> = emptyList(),
        val progressionEnabled: Boolean = false,
        val progression: List<ProgressionStep> = emptyList(),

        val goals: List<Goal> = emptyList(),
        val templates: List<BehaviorTemplate> = emptyList(),

        val validationErrors: List<String> = emptyList(),
        val duplicateNameWarning: Boolean = false,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val savedBehaviorId: String? = null,
        val error: String? = null,
    ) {
        val isEditing: Boolean get() = id != null
        val canSave: Boolean get() = name.isNotBlank() && validationErrors.isEmpty() && !saving
        val showsDuration: Boolean get() = measurement == Measurement.DURATION
        val showsQuantity: Boolean get() = measurement == Measurement.QUANTITY
        val showsChecklist: Boolean get() = measurement == Measurement.CHECKLIST
    }

    private val _state = MutableStateFlow(UiState(goalId = presetGoalId))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = container.currentSettings()
            _state.update {
                it.copy(
                    enforcement = settings.defaultEnforcement,
                    soundEnabled = settings.defaultSoundEnabled,
                    vibrationEnabled = settings.defaultVibrationEnabled,
                    defaultRecoveryMinutes = settings.defaultRecoveryMinutes.toString(),
                    reflectionFrequency = settings.reflectionFrequency,
                )
            }
        }
        viewModelScope.launch {
            container.goalRepository.observeActive().collect { goals ->
                _state.update { it.copy(goals = goals) }
            }
        }
        viewModelScope.launch {
            container.behaviorRepository.observeTemplates().collect { templates ->
                _state.update { it.copy(templates = templates) }
            }
        }
        if (behaviorId != null) loadExisting(behaviorId)
    }

    private fun loadExisting(id: String) {
        viewModelScope.launch {
            val behavior = container.behaviorRepository.get(id) ?: return@launch
            val schedules = container.behaviorRepository.getSchedules(id)
            val schedule = schedules.firstOrNull()
            val checklist = container.behaviorRepository.getChecklist(id)
            val instructions = container.behaviorRepository.getInstructions(id)
            val progression = container.behaviorRepository.getProgression(id)

            _state.update {
                it.copy(
                    id = behavior.id,
                    name = behavior.name,
                    description = behavior.description,
                    goalId = behavior.goalId,
                    category = behavior.category,
                    isTimeBased = behavior.isTimeBased,
                    measurement = behavior.measurement,
                    archetype = archetypeFor(behavior.isTimeBased, behavior.measurement),
                    importance = behavior.importance,
                    difficulty = behavior.difficulty,
                    enforcement = behavior.enforcement,
                    targetDuration = behavior.targetDurationMinutes?.toString() ?: "",
                    minimumDuration = behavior.minimumDurationMinutes?.toString() ?: "",
                    targetQuantity = behavior.targetQuantity?.toString() ?: "",
                    minimumQuantity = behavior.minimumQuantity?.toString() ?: "",
                    quantityUnit = behavior.quantityUnit,
                    defaultInstruction = behavior.defaultInstruction,
                    notes = behavior.notes,
                    recurrenceType = schedule?.recurrenceType ?: RecurrenceType.DAILY,
                    daysOfWeekMask = schedule?.daysOfWeekMask ?: DayMask.ALL,
                    intervalDays = (schedule?.intervalDays ?: 2).toString(),
                    timesPerWeek = (schedule?.timesPerWeek ?: 3).toString(),
                    timesPerDay = (schedule?.timesPerDay ?: 1).toString(),
                    timesOfDay = schedule?.timesOfDay ?: emptyList(),
                    reminderEnabled = behavior.reminderEnabled,
                    soundEnabled = behavior.soundEnabled,
                    vibrationEnabled = behavior.vibrationEnabled,
                    soundUri = behavior.soundUri,
                    // The real title needs a Context to resolve, so the screen refreshes it when
                    // the user opens the picker. This is a safe placeholder until then.
                    soundLabel = if (behavior.soundUri == null) "Default" else "Custom sound",
                    vibrationPattern = behavior.vibrationPattern,
                    preAlertMinutes = behavior.preAlertMinutes?.toString() ?: "",
                    escalationMinutes = behavior.escalationMinutes?.toString() ?: "",
                    recoveryEnabled = behavior.recoveryEnabled,
                    defaultRecoveryMinutes = behavior.defaultRecoveryMinutes.toString(),
                    maxRecoveries = behavior.maxRecoveriesPerInstance.toString(),
                    missWindowMinutes = behavior.missWindowMinutes.toString(),
                    reflectionFrequency = behavior.reflectionFrequency,
                    requirePassReason = behavior.requirePassReason,
                    allowPartialChecklist = behavior.allowPartialChecklist,
                    isActive = behavior.isActive,
                    progressionEnabled = behavior.progressionEnabled,
                    checklist = checklist,
                    dailyInstructions = instructions,
                    progression = progression,
                )
            }
            validate()
        }
    }

    // --- Field setters ------------------------------------------------------------------

    fun setName(v: String) {
        _state.update { it.copy(name = v) }
        checkDuplicateName(v)
    }

    fun setDescription(v: String) = _state.update { it.copy(description = v) }
    fun setGoal(v: String?) = _state.update { it.copy(goalId = v) }
    fun setCategory(v: String) = _state.update { it.copy(category = v) }
    fun setImportance(v: Importance) = _state.update { it.copy(importance = v) }
    fun setDifficulty(v: Difficulty) = _state.update { it.copy(difficulty = v) }
    fun setEnforcement(v: EnforcementLevel) = _state.update { it.copy(enforcement = v) }
    fun setDefaultInstruction(v: String) = _state.update { it.copy(defaultInstruction = v) }
    fun setNotes(v: String) = _state.update { it.copy(notes = v) }
    fun setQuantityUnit(v: String) = _state.update { it.copy(quantityUnit = v) }
    fun setReminderEnabled(v: Boolean) = _state.update { it.copy(reminderEnabled = v) }
    fun setSoundEnabled(v: Boolean) = _state.update { it.copy(soundEnabled = v) }
    fun setVibrationEnabled(v: Boolean) = _state.update { it.copy(vibrationEnabled = v) }
    fun setVibrationPattern(v: String) = _state.update { it.copy(vibrationPattern = v) }
    fun setRecoveryEnabled(v: Boolean) = _state.update { it.copy(recoveryEnabled = v) }
    fun setReflectionFrequency(v: ReflectionFrequency) = _state.update { it.copy(reflectionFrequency = v) }
    fun setRequirePassReason(v: Boolean) = _state.update { it.copy(requirePassReason = v) }
    fun setAllowPartialChecklist(v: Boolean) = _state.update { it.copy(allowPartialChecklist = v) }
    fun setActive(v: Boolean) = _state.update { it.copy(isActive = v) }
    fun setProgressionEnabled(v: Boolean) = _state.update { it.copy(progressionEnabled = v) }

    fun setSound(uri: String?, label: String) =
        _state.update { it.copy(soundUri = uri, soundLabel = label) }

    fun setTargetDuration(v: String) { _state.update { it.copy(targetDuration = v.filterDigits()) }; validate() }
    fun setMinimumDuration(v: String) { _state.update { it.copy(minimumDuration = v.filterDigits()) }; validate() }
    fun setTargetQuantity(v: String) { _state.update { it.copy(targetQuantity = v.filterDecimal()) }; validate() }
    fun setMinimumQuantity(v: String) { _state.update { it.copy(minimumQuantity = v.filterDecimal()) }; validate() }
    fun setPreAlert(v: String) = _state.update { it.copy(preAlertMinutes = v.filterDigits()) }
    fun setEscalation(v: String) = _state.update { it.copy(escalationMinutes = v.filterDigits()) }
    fun setDefaultRecovery(v: String) = _state.update { it.copy(defaultRecoveryMinutes = v.filterDigits()) }
    fun setMaxRecoveries(v: String) = _state.update { it.copy(maxRecoveries = v.filterDigits()) }
    fun setMissWindow(v: String) = _state.update { it.copy(missWindowMinutes = v.filterDigits()) }

    fun setIntervalDays(v: String) { _state.update { it.copy(intervalDays = v.filterDigits()) }; validate() }
    fun setTimesPerWeek(v: String) { _state.update { it.copy(timesPerWeek = v.filterDigits()) }; validate() }
    fun setTimesPerDay(v: String) { _state.update { it.copy(timesPerDay = v.filterDigits()) }; validate() }

    /** Switching archetype is a preset over (isTimeBased, measurement); both stay editable. */
    fun setArchetype(archetype: BehaviorArchetype) {
        _state.update {
            it.copy(
                archetype = archetype,
                isTimeBased = archetype.isTimeBased,
                measurement = archetype.measurement,
                timesOfDay = if (archetype.isTimeBased && it.timesOfDay.isEmpty()) {
                    listOf(LocalTime.of(7, 0))
                } else {
                    it.timesOfDay
                },
            )
        }
        validate()
    }

    fun setTimeBased(v: Boolean) {
        _state.update {
            it.copy(
                isTimeBased = v,
                timesOfDay = if (v && it.timesOfDay.isEmpty()) listOf(LocalTime.of(7, 0)) else it.timesOfDay,
            )
        }
        validate()
    }

    fun setMeasurement(v: Measurement) { _state.update { it.copy(measurement = v) }; validate() }

    fun setRecurrenceType(v: RecurrenceType) {
        _state.update {
            it.copy(
                recurrenceType = v,
                daysOfWeekMask = when (v) {
                    RecurrenceType.WEEKDAYS -> DayMask.WEEKDAYS
                    RecurrenceType.WEEKENDS -> DayMask.WEEKENDS
                    RecurrenceType.DAILY -> DayMask.ALL
                    else -> it.daysOfWeekMask
                },
            )
        }
        validate()
    }

    fun toggleDay(isoDay: Int) {
        _state.update { it.copy(daysOfWeekMask = DayMask.toggle(it.daysOfWeekMask, isoDay)) }
        validate()
    }

    /** Multiple times a day are separate occurrences with separate history, never overwrites. */
    fun addTime(time: LocalTime) {
        _state.update {
            it.copy(timesOfDay = (it.timesOfDay + time.withSecond(0).withNano(0)).distinct().sorted())
        }
        validate()
    }

    fun removeTime(time: LocalTime) {
        _state.update { it.copy(timesOfDay = it.timesOfDay - time) }
        validate()
    }

    fun replaceTime(old: LocalTime, new: LocalTime) {
        _state.update {
            it.copy(timesOfDay = (it.timesOfDay - old + new.withSecond(0).withNano(0)).distinct().sorted())
        }
        validate()
    }

    // --- Checklist, daily plans, progression --------------------------------------------

    fun addChecklistItem(text: String) {
        if (text.isBlank()) return
        _state.update {
            it.copy(
                checklist = it.checklist + ChecklistItem(
                    behaviorId = it.id ?: "",
                    position = it.checklist.size,
                    text = text.trim(),
                ),
            )
        }
    }

    fun removeChecklistItem(id: String) =
        _state.update { it.copy(checklist = it.checklist.filterNot { item -> item.id == id }) }

    fun upsertDailyInstruction(instruction: DailyInstruction) {
        _state.update { current ->
            val existing = current.dailyInstructions.filterNot { it.id == instruction.id }
            current.copy(dailyInstructions = existing + instruction)
        }
    }

    fun removeDailyInstruction(id: String) =
        _state.update { it.copy(dailyInstructions = it.dailyInstructions.filterNot { r -> r.id == id }) }

    fun setProgressionStep(weekIndex: Int, minutes: Int?) {
        _state.update { current ->
            val without = current.progression.filterNot { it.weekIndex == weekIndex }
            val updated = if (minutes == null) {
                without
            } else {
                without + ProgressionStep(
                    behaviorId = current.id ?: "",
                    weekIndex = weekIndex,
                    targetDurationMinutes = minutes,
                )
            }
            current.copy(progression = updated.sortedBy { it.weekIndex })
        }
    }

    /** Prefills the whole form from a saved template. */
    fun applyTemplate(templateId: String) {
        viewModelScope.launch {
            val template = container.behaviorRepository.getTemplate(templateId) ?: return@launch
            _state.update {
                it.copy(
                    name = it.name.ifBlank { template.name },
                    description = template.description,
                    category = template.category,
                    isTimeBased = template.isTimeBased,
                    measurement = template.measurement,
                    archetype = archetypeFor(template.isTimeBased, template.measurement),
                    importance = template.importance,
                    difficulty = template.difficulty,
                    enforcement = template.enforcement,
                    targetDuration = template.targetDurationMinutes?.toString() ?: "",
                    minimumDuration = template.minimumDurationMinutes?.toString() ?: "",
                    targetQuantity = template.targetQuantity?.toString() ?: "",
                    quantityUnit = template.quantityUnit,
                    defaultInstruction = template.defaultInstruction,
                    recurrenceType = template.recurrenceType,
                    daysOfWeekMask = template.daysOfWeekMask,
                    intervalDays = template.intervalDays.toString(),
                    timesOfDay = template.timesOfDay,
                    defaultRecoveryMinutes = template.defaultRecoveryMinutes.toString(),
                )
            }
            validate()
        }
    }

    /** Saves the current form as a reusable template. */
    fun saveAsTemplate(name: String) {
        val s = _state.value
        viewModelScope.launch {
            container.behaviorRepository.saveTemplate(
                BehaviorTemplate(
                    name = name.ifBlank { s.name },
                    description = s.description,
                    category = s.category,
                    isTimeBased = s.isTimeBased,
                    measurement = s.measurement,
                    importance = s.importance,
                    difficulty = s.difficulty,
                    enforcement = s.enforcement,
                    targetDurationMinutes = s.targetDuration.toIntOrNull(),
                    minimumDurationMinutes = s.minimumDuration.toIntOrNull(),
                    targetQuantity = s.targetQuantity.toDoubleOrNull(),
                    quantityUnit = s.quantityUnit,
                    defaultInstruction = s.defaultInstruction,
                    recurrenceType = s.recurrenceType,
                    daysOfWeekMask = s.daysOfWeekMask,
                    intervalDays = s.intervalDays.toIntOrNull() ?: 1,
                    timesOfDay = s.timesOfDay,
                    defaultRecoveryMinutes = s.defaultRecoveryMinutes.toIntOrNull() ?: 30,
                    createdAt = container.clock.now(),
                ),
            )
        }
    }

    // --- Validation and save ------------------------------------------------------------

    private fun checkDuplicateName(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val taken = container.behaviorRepository.isNameTaken(name, _state.value.id ?: "")
            _state.update { it.copy(duplicateNameWarning = taken) }
        }
    }

    fun validate() {
        val s = _state.value
        val errors = mutableListOf<String>()

        if (s.name.isBlank()) errors += "Give the behavior a name."

        val target = s.targetDuration.toIntOrNull()
        val minimum = s.minimumDuration.toIntOrNull()
        if (s.showsDuration && target != null && minimum != null && minimum > target) {
            errors += "The minimum cannot be longer than the target."
        }

        val targetQty = s.targetQuantity.toDoubleOrNull()
        val minQty = s.minimumQuantity.toDoubleOrNull()
        if (s.showsQuantity) {
            if (targetQty == null || targetQty <= 0) errors += "Set a quantity target."
            if (minQty != null && targetQty != null && minQty > targetQty) {
                errors += "The minimum cannot be larger than the target."
            }
        }

        errors += RecurrenceValidator.validate(buildSchedule(s, "preview"), s.isTimeBased).errors

        _state.update { it.copy(validationErrors = errors.distinct()) }
    }

    private fun buildSchedule(s: UiState, id: String): Schedule = Schedule(
        id = id,
        behaviorId = s.id ?: "",
        recurrenceType = s.recurrenceType,
        daysOfWeekMask = s.daysOfWeekMask,
        intervalDays = s.intervalDays.toIntOrNull() ?: 1,
        anchorDate = container.clock.today(),
        startDate = container.clock.today(),
        timesOfDay = if (s.isTimeBased) s.timesOfDay else emptyList(),
        timesPerWeek = s.timesPerWeek.toIntOrNull(),
        timesPerDay = s.timesPerDay.toIntOrNull() ?: 1,
    )

    fun save() {
        validate()
        val s = _state.value
        if (!s.canSave) return

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                val now = container.clock.now()
                val existing = s.id?.let { container.behaviorRepository.get(it) }
                val behaviorId = s.id ?: Ids.new()

                val behavior = Behavior(
                    id = behaviorId,
                    goalId = s.goalId,
                    name = s.name.trim(),
                    description = s.description.trim(),
                    category = s.category.trim(),
                    isTimeBased = s.isTimeBased,
                    measurement = s.measurement,
                    importance = s.importance,
                    difficulty = s.difficulty,
                    enforcement = s.enforcement,
                    targetDurationMinutes = s.targetDuration.toIntOrNull(),
                    minimumDurationMinutes = s.minimumDuration.toIntOrNull(),
                    targetQuantity = s.targetQuantity.toDoubleOrNull(),
                    minimumQuantity = s.minimumQuantity.toDoubleOrNull(),
                    quantityUnit = s.quantityUnit.trim(),
                    defaultInstruction = s.defaultInstruction.trim(),
                    notes = s.notes.trim(),
                    reminderEnabled = s.reminderEnabled,
                    soundEnabled = s.soundEnabled,
                    vibrationEnabled = s.vibrationEnabled,
                    soundUri = s.soundUri,
                    vibrationPattern = s.vibrationPattern,
                    preAlertMinutes = s.preAlertMinutes.toIntOrNull(),
                    escalationMinutes = s.escalationMinutes.toIntOrNull(),
                    channelVersion = existing?.channelVersion ?: 1,
                    recoveryEnabled = s.recoveryEnabled,
                    maxRecoveriesPerInstance = s.maxRecoveries.toIntOrNull() ?: 3,
                    defaultRecoveryMinutes = s.defaultRecoveryMinutes.toIntOrNull() ?: 30,
                    missWindowMinutes = s.missWindowMinutes.toIntOrNull() ?: 120,
                    reflectionFrequency = s.reflectionFrequency,
                    allowPartialChecklist = s.allowPartialChecklist,
                    requirePassReason = s.requirePassReason,
                    isActive = s.isActive,
                    progressionEnabled = s.progressionEnabled,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )

                val scheduleId = container.behaviorRepository.getSchedules(behaviorId)
                    .firstOrNull()?.id ?: Ids.new()

                container.behaviorRepository.save(
                    behavior = behavior,
                    schedules = listOf(buildSchedule(s, scheduleId).copy(behaviorId = behaviorId)),
                    instructions = s.dailyInstructions.map { it.copy(behaviorId = behaviorId) },
                    checklist = s.checklist.map { it.copy(behaviorId = behaviorId) },
                    progression = if (s.progressionEnabled) {
                        s.progression.map { it.copy(behaviorId = behaviorId) }
                    } else {
                        emptyList()
                    },
                    replaceInstructions = true,
                    replaceChecklist = true,
                )

                // Regenerate occurrences and re-arm alarms so the new schedule takes effect now
                // rather than at the next periodic sweep.
                container.schedulingCoordinator.refresh()

                _state.update {
                    it.copy(saving = false, saved = true, savedBehaviorId = behaviorId)
                }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = "Could not save: ${e.message}") }
            }
        }
    }

    fun delete() {
        val id = _state.value.id ?: return
        viewModelScope.launch {
            container.schedulingCoordinator.cancelForBehavior(id)
            container.notificationChannels.deleteChannelsFor(id)
            container.behaviorRepository.delete(id)
            _state.update { it.copy(saved = true, savedBehaviorId = null) }
        }
    }

    private fun archetypeFor(timeBased: Boolean, measurement: Measurement): BehaviorArchetype =
        BehaviorArchetype.entries.firstOrNull {
            it.isTimeBased == timeBased && it.measurement == measurement
        } ?: if (timeBased) BehaviorArchetype.TIME_BASED else BehaviorArchetype.FLEXIBLE
}

private fun String.filterDigits(): String = filter { it.isDigit() }.take(5)

private fun String.filterDecimal(): String =
    filterIndexed { index, c -> c.isDigit() || (c == '.' && indexOf('.') == index) }.take(8)
