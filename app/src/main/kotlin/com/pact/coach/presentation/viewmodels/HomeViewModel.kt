package com.pact.coach.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pact.coach.di.AppContainer
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.scheduler.ConflictDetector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * State for the dashboard and the Today timeline.
 *
 * Both screens read the same day of data, so they share a view model: the dashboard is a
 * prioritised slice ("what now?") and Today is the same list in chronological order.
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val refreshTrigger = MutableStateFlow(0)

    data class DayItem(
        val instance: BehaviorInstance,
        val behavior: Behavior,
        val goalName: String?,
    ) {
        val time: LocalTime? get() = instance.scheduledTime
        val isOpen: Boolean get() = !instance.state.isTerminal
    }

    data class UiState(
        val today: LocalDate = LocalDate.now(),
        val dueNow: List<DayItem> = emptyList(),
        val overdue: List<DayItem> = emptyList(),
        val upcoming: List<DayItem> = emptyList(),
        val timeline: List<DayItem> = emptyList(),
        val flexible: List<DayItem> = emptyList(),
        val conflicts: List<ConflictDetector.Conflict> = emptyList(),
        val scheduledCount: Int = 0,
        val completedCount: Int = 0,
        val remainingCount: Int = 0,
        val recoveredCount: Int = 0,
        val hasAnyBehavior: Boolean = false,
        val isLoading: Boolean = true,
    ) {
        /** The one thing the dashboard should lead with, if there is one. */
        val focus: DayItem? get() = dueNow.firstOrNull() ?: overdue.firstOrNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState> = combine(
        container.instanceRepository.observeForDay(container.clock.today()),
        container.behaviorRepository.observeAll(),
        container.goalRepository.observeAll(),
        refreshTrigger,
    ) { instances, behaviors, goals, _ ->
        val behaviorsById = behaviors.associateBy { it.id }
        val goalsById = goals.associateBy { it.id }
        val now = container.clock.nowMillis()

        val items = instances.mapNotNull { instance ->
            val behavior = behaviorsById[instance.behaviorId] ?: return@mapNotNull null
            DayItem(
                instance = instance,
                behavior = behavior,
                goalName = behavior.goalId?.let { goalsById[it]?.name },
            )
        }

        val timed = items.filter { it.time != null }
        val flexible = items.filter { it.time == null }

        // "Due now" is anything the app has already surfaced and the user has not answered.
        val dueNow = timed.filter {
            it.instance.state == InstanceState.DUE || it.instance.state == InstanceState.COMMITTED
        }

        // Overdue: the moment has passed but no intervention was recorded, e.g. the alarm was
        // suppressed by battery optimisation. Shown so nothing silently disappears.
        val overdue = timed.filter { item ->
            item.instance.state == InstanceState.SCHEDULED &&
                (item.instance.scheduledAtUtcMillis ?: Long.MAX_VALUE) < now
        }

        val upcoming = timed
            .filter { item ->
                item.instance.state == InstanceState.SCHEDULED &&
                    (item.instance.scheduledAtUtcMillis ?: 0L) >= now
            }
            .sortedBy { it.time }

        UiState(
            today = container.clock.today(),
            dueNow = dueNow.sortedBy { it.time },
            overdue = overdue.sortedBy { it.time },
            upcoming = upcoming,
            timeline = timed.sortedBy { it.time },
            flexible = flexible,
            conflicts = container.conflictDetector.detect(
                instances.filter { !it.state.isTerminal },
                behaviorsById,
            ),
            scheduledCount = items.size,
            completedCount = items.count { it.instance.state == InstanceState.COMPLETED },
            remainingCount = items.count { !it.instance.state.isTerminal },
            recoveredCount = items.count { it.instance.state == InstanceState.RECOVERED },
            hasAnyBehavior = behaviors.isNotEmpty(),
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Completes an untimed behavior straight from the dashboard. */
    fun quickComplete(instanceId: String) {
        viewModelScope.launch {
            container.instanceRepository.complete(instanceId)
            container.schedulingCoordinator.refresh()
            refreshTrigger.value++
        }
    }

    fun refresh() {
        viewModelScope.launch {
            container.schedulingCoordinator.refresh()
            refreshTrigger.value++
        }
    }
}
