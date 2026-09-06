package com.pact.coach.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pact.coach.di.AppContainer
import com.pact.coach.data.repository.InstanceRepository
import com.pact.coach.domain.coaching.MessageLibrary
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.ChecklistTick
import com.pact.coach.domain.model.CoachPersonality
import com.pact.coach.domain.model.Goal
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.model.PassReason
import com.pact.coach.domain.model.ReflectionFrequency
import com.pact.coach.domain.model.ResolvedInstruction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the intervention screen: the moment the whole app exists for.
 *
 * The screen has to work when the user is half awake and holding a phone at arm's length, so the
 * view model keeps the state machine simple: one [Stage] at a time, three obvious ways out, and
 * every action is idempotent because a double tap is likely.
 */
class InterventionViewModel(
    private val container: AppContainer,
    private val instanceId: String,
) : ViewModel() {

    enum class Stage {
        /** The three-way decision. */
        DECIDE,

        /** Committed: doing it now, waiting to be marked complete. */
        IN_PROGRESS,

        /** Choosing how far to push it. */
        CHOOSING_RECOVERY,

        /** Optionally saying why it is being skipped. */
        CHOOSING_PASS_REASON,

        /** Optional rating and note after completion. */
        REFLECTING,

        /** Nothing more to do; the screen can close. */
        DONE,
    }

    data class UiState(
        val loading: Boolean = true,
        val missing: Boolean = false,
        val instance: BehaviorInstance? = null,
        val behavior: Behavior? = null,
        val goal: Goal? = null,
        val instruction: ResolvedInstruction? = null,
        val ticks: Map<String, Boolean> = emptyMap(),
        val stage: Stage = Stage.DECIDE,
        val coachMessage: String = "",
        val error: String? = null,
        val personality: CoachPersonality = CoachPersonality.SUPPORTIVE,
        val recoveryCount: Int = 0,
        /** Set once the user has finished, so the screen knows it may close. */
        val finished: Boolean = false,
    ) {
        val checklistComplete: Boolean
            get() {
                val required = instruction?.checklist?.filter { it.isRequired }.orEmpty()
                return required.isEmpty() || required.all { ticks[it.id] == true }
            }

        val minimumLabel: String? get() = behavior?.minimumLabel()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val instance = container.instanceRepository.get(instanceId)
            if (instance == null) {
                _state.update { it.copy(loading = false, missing = true) }
                return@launch
            }
            val behavior = container.behaviorRepository.get(instance.behaviorId)
            if (behavior == null) {
                _state.update { it.copy(loading = false, missing = true) }
                return@launch
            }

            val settings = container.currentSettings()
            val instruction = container.resolveInstruction(behavior, instance)
            val goal = behavior.goalId?.let { container.goalRepository.get(it) }
            val ticks = container.database.checklistDao().getTicks(instance.id)
                .associate { it.checklistItemId to it.isChecked }

            // Coming back to a screen that was already answered should reflect that, not offer
            // the decision again.
            val stage = when (instance.state) {
                InstanceState.COMMITTED -> Stage.IN_PROGRESS
                InstanceState.COMPLETED, InstanceState.PASSED,
                InstanceState.RECOVERED, InstanceState.CANCELLED,
                -> Stage.DONE
                else -> Stage.DECIDE
            }

            _state.update {
                it.copy(
                    loading = false,
                    instance = instance,
                    behavior = behavior,
                    goal = goal,
                    instruction = instruction,
                    ticks = ticks,
                    stage = stage,
                    personality = settings.coachPersonality,
                    coachMessage = MessageLibrary.nudge(settings.coachPersonality, behavior.name),
                    recoveryCount = instance.recoveryDepth,
                )
            }
        }
    }

    fun commit() {
        val current = _state.value.instance ?: return
        viewModelScope.launch {
            when (val result = container.instanceRepository.commit(current.id)) {
                is InstanceRepository.DecisionResult.Ok -> {
                    container.schedulingCoordinator.cancelInstance(current)
                    container.notifier.cancel(current)
                    _state.update {
                        it.copy(
                            instance = result.instance,
                            stage = Stage.IN_PROGRESS,
                            coachMessage = MessageLibrary.committed(it.personality),
                            error = null,
                        )
                    }
                }
                is InstanceRepository.DecisionResult.Failed ->
                    _state.update { it.copy(error = result.reason) }
            }
        }
    }

    fun showRecoveryOptions() = _state.update { it.copy(stage = Stage.CHOOSING_RECOVERY, error = null) }

    fun showPassReasons() = _state.update { it.copy(stage = Stage.CHOOSING_PASS_REASON, error = null) }

    fun backToDecision() = _state.update { it.copy(stage = Stage.DECIDE, error = null) }

    fun recover(delayMinutes: Int) {
        val current = _state.value.instance ?: return
        viewModelScope.launch {
            when (val result = container.instanceRepository.recover(current.id, delayMinutes)) {
                is InstanceRepository.DecisionResult.Ok -> {
                    container.schedulingCoordinator.cancelInstance(current)
                    container.notifier.cancel(current)
                    // The follow-up occurrence needs its own alarm right away.
                    result.newInstance?.let { container.schedulingCoordinator.armInstance(it) }
                    _state.update {
                        it.copy(
                            instance = result.instance,
                            stage = Stage.DONE,
                            finished = true,
                            coachMessage = MessageLibrary.recovered(it.personality, delayMinutes),
                            error = null,
                        )
                    }
                }
                is InstanceRepository.DecisionResult.Failed ->
                    _state.update { it.copy(error = result.reason, stage = Stage.DECIDE) }
            }
        }
    }

    fun pass(reason: PassReason?, note: String = "") {
        val current = _state.value.instance ?: return
        viewModelScope.launch {
            when (val result = container.instanceRepository.pass(current.id, reason, note)) {
                is InstanceRepository.DecisionResult.Ok -> {
                    container.schedulingCoordinator.cancelInstance(current)
                    container.notifier.cancel(current)
                    _state.update {
                        it.copy(
                            instance = result.instance,
                            stage = Stage.DONE,
                            finished = true,
                            coachMessage = MessageLibrary.passed(it.personality),
                            error = null,
                        )
                    }
                }
                is InstanceRepository.DecisionResult.Failed ->
                    _state.update { it.copy(error = result.reason) }
            }
        }
    }

    /**
     * @param minimumOnly the user took the "minimum action" route. Recorded distinctly so the
     *   coaching engine can spot a target that is set too high.
     */
    fun complete(
        minimumOnly: Boolean = false,
        durationMinutes: Int? = null,
        quantity: Double? = null,
    ) {
        val current = _state.value.instance ?: return
        val behavior = _state.value.behavior ?: return
        viewModelScope.launch {
            val partial = !_state.value.checklistComplete && behavior.allowPartialChecklist

            val result = container.instanceRepository.complete(
                instanceId = current.id,
                durationMinutes = durationMinutes
                    ?: if (minimumOnly) behavior.minimumDurationMinutes else _state.value.instruction?.durationMinutes,
                quantity = quantity,
                minimumOnly = minimumOnly,
                partial = partial,
            )

            when (result) {
                is InstanceRepository.DecisionResult.Ok -> {
                    container.schedulingCoordinator.cancelInstance(current)
                    container.notifier.cancel(current)

                    val settings = container.currentSettings()
                    val askForReflection = when (behavior.reflectionFrequency) {
                        ReflectionFrequency.ALWAYS -> true
                        ReflectionFrequency.NEVER -> false
                        // "Sometimes" means occasionally, not randomly: ask when the user is
                        // most likely to have something to say, which is a minimum or partial day.
                        ReflectionFrequency.SOMETIMES -> minimumOnly || partial
                    } && settings.reflectionFrequency != ReflectionFrequency.NEVER

                    _state.update {
                        it.copy(
                            instance = result.instance,
                            stage = if (askForReflection) Stage.REFLECTING else Stage.DONE,
                            finished = !askForReflection,
                            coachMessage = if (minimumOnly) {
                                MessageLibrary.completedMinimum(it.personality)
                            } else {
                                MessageLibrary.completed(it.personality)
                            },
                            error = null,
                        )
                    }
                }
                is InstanceRepository.DecisionResult.Failed ->
                    _state.update { it.copy(error = result.reason) }
            }
        }
    }

    fun saveReflection(rating: Int?, note: String) {
        val current = _state.value.instance ?: return
        viewModelScope.launch {
            container.instanceRepository.complete(
                instanceId = current.id,
                minimumOnly = current.completionQuality ==
                    com.pact.coach.domain.model.CompletionQuality.MINIMUM_COMPLETED,
                rating = rating,
                note = note,
            )
            _state.update { it.copy(stage = Stage.DONE, finished = true) }
        }
    }

    fun skipReflection() = _state.update { it.copy(stage = Stage.DONE, finished = true) }

    fun toggleChecklistItem(itemId: String) {
        val current = _state.value.instance ?: return
        val newValue = !(_state.value.ticks[itemId] ?: false)
        _state.update { it.copy(ticks = it.ticks + (itemId to newValue)) }
        viewModelScope.launch {
            container.instanceRepository.setChecklistTick(current.id, itemId, newValue)
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Used by STRONG behaviors: leaving without deciding is allowed, and recorded as nothing. */
    fun leaveWithoutDeciding() = _state.update { it.copy(finished = true) }
}
