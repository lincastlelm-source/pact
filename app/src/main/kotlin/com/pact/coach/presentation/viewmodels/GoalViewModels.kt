package com.pact.coach.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pact.coach.di.AppContainer
import com.pact.coach.data.repository.GoalWithBehaviors
import com.pact.coach.domain.coaching.BehaviorStats
import com.pact.coach.domain.model.Categories
import com.pact.coach.domain.model.Goal
import com.pact.coach.domain.model.GoalStatus
import com.pact.coach.domain.model.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The goals list, with each goal's behaviors and a live completion rate. */
class GoalsViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val goals: List<GoalWithBehaviors> = emptyList(),
        val statsByBehavior: Map<String, BehaviorStats> = emptyMap(),
        val unassignedCount: Int = 0,
        val isLoading: Boolean = true,
    )

    private val stats = MutableStateFlow<Map<String, BehaviorStats>>(emptyMap())

    val state: StateFlow<UiState> = kotlinx.coroutines.flow.combine(
        container.goalRepository.observeAllWithBehaviors(),
        container.behaviorRepository.observeAll(),
        stats,
    ) { goals, behaviors, statsMap ->
        UiState(
            goals = goals,
            statsByBehavior = statsMap,
            unassignedCount = behaviors.count { it.goalId == null },
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refreshStats()
    }

    fun refreshStats() {
        viewModelScope.launch {
            stats.value = container.insightsRepository.statsForAll()
        }
    }

    fun setStatus(goalId: String, status: GoalStatus) {
        viewModelScope.launch { container.goalRepository.setStatus(goalId, status) }
    }

    fun delete(goalId: String) {
        // Behaviors survive as unassigned; see GoalRepository.
        viewModelScope.launch { container.goalRepository.delete(goalId) }
    }
}

/** Create or edit one goal. */
class GoalEditViewModel(
    private val container: AppContainer,
    private val goalId: String?,
) : ViewModel() {

    data class UiState(
        val id: String? = null,
        val name: String = "",
        val whyItMatters: String = "",
        val description: String = "",
        val category: String = "Personal",
        val priority: Priority = Priority.MEDIUM,
        val status: GoalStatus = GoalStatus.ACTIVE,
        val targetDate: LocalDate? = null,
        val suggestedCategories: List<String> = Categories.SUGGESTED,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val savedGoalId: String? = null,
        val error: String? = null,
    ) {
        val isEditing: Boolean get() = id != null
        val canSave: Boolean get() = name.isNotBlank() && !saving
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (goalId != null) {
            viewModelScope.launch {
                container.goalRepository.get(goalId)?.let { goal ->
                    _state.update {
                        it.copy(
                            id = goal.id,
                            name = goal.name,
                            whyItMatters = goal.whyItMatters,
                            description = goal.description,
                            category = goal.category,
                            priority = goal.priority,
                            status = goal.status,
                            targetDate = goal.targetDate,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            container.goalRepository.observeCategories().map { existing ->
                (Categories.SUGGESTED + existing).distinct()
            }.collect { merged ->
                _state.update { it.copy(suggestedCategories = merged) }
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value, error = null) }
    fun setWhy(value: String) = _state.update { it.copy(whyItMatters = value) }
    fun setDescription(value: String) = _state.update { it.copy(description = value) }
    fun setCategory(value: String) = _state.update { it.copy(category = value) }
    fun setPriority(value: Priority) = _state.update { it.copy(priority = value) }
    fun setStatus(value: GoalStatus) = _state.update { it.copy(status = value) }
    fun setTargetDate(value: LocalDate?) = _state.update { it.copy(targetDate = value) }

    fun save() {
        val current = _state.value
        if (!current.canSave) return

        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                val existing = current.id?.let { container.goalRepository.get(it) }
                val now = container.clock.now()
                val goal = existing?.copy(
                    name = current.name.trim(),
                    whyItMatters = current.whyItMatters.trim(),
                    description = current.description.trim(),
                    category = current.category.trim().ifBlank { "Personal" },
                    priority = current.priority,
                    status = current.status,
                    targetDate = current.targetDate,
                ) ?: Goal(
                    name = current.name.trim(),
                    whyItMatters = current.whyItMatters.trim(),
                    description = current.description.trim(),
                    category = current.category.trim().ifBlank { "Personal" },
                    priority = current.priority,
                    status = current.status,
                    startDate = container.clock.today(),
                    targetDate = current.targetDate,
                    createdAt = now,
                    updatedAt = now,
                )
                val saved = container.goalRepository.save(goal)
                _state.update { it.copy(saving = false, saved = true, savedGoalId = saved.id) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = "Could not save: ${e.message}") }
            }
        }
    }

    /**
     * Closes a goal off as achieved. Deliberately distinct from deleting: the goal, its behaviors
     * and their whole history stay exactly where they are, which is the point of having finished
     * something.
     */
    fun markAchieved() {
        val id = _state.value.id ?: return
        viewModelScope.launch {
            try {
                container.goalRepository.setStatus(id, GoalStatus.COMPLETED)
                _state.update { it.copy(status = GoalStatus.COMPLETED, saved = true, savedGoalId = id) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not update: ${e.message}") }
            }
        }
    }

    /**
     * Removes the goal. Behaviors underneath it are detached rather than deleted, so no history is
     * lost; the caller is responsible for having confirmed this with the user first.
     */
    fun delete() {
        val id = _state.value.id ?: return
        viewModelScope.launch {
            try {
                container.goalRepository.delete(id)
                _state.update { it.copy(saved = true, savedGoalId = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not delete: ${e.message}") }
            }
        }
    }
}
