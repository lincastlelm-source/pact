package com.pact.coach.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pact.coach.data.repository.DaySummary
import com.pact.coach.di.AppContainer
import com.pact.coach.domain.coaching.BehaviorStats
import com.pact.coach.domain.coaching.CoachInsight
import com.pact.coach.domain.coaching.OverallStats
import com.pact.coach.domain.coaching.WeeklyReview
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.recurrence.RecurrenceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Statistics, the calendar and the weekly review.
 *
 * Everything is recomputed from history on load rather than cached, so what the user sees always
 * matches what actually happened.
 */
class InsightsViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val overall: OverallStats? = null,
        val perBehavior: List<Pair<Behavior, BehaviorStats>> = emptyList(),
        val insights: List<CoachInsight> = emptyList(),
        val weeklyTrend: List<Pair<LocalDate, Double>> = emptyList(),
        val dayOfWeek: Map<DayOfWeek, Double> = emptyMap(),
        val review: WeeklyReview? = null,
        val calendarMonth: YearMonth = YearMonth.now(),
        val calendar: Map<LocalDate, DaySummary> = emptyMap(),
        val appliedSuggestion: String? = null,
        val message: String? = null,
    ) {
        val mostConsistent: Pair<Behavior, BehaviorStats>? get() = perBehavior.firstOrNull()
        val leastConsistent: Pair<Behavior, BehaviorStats>? get() =
            perBehavior.lastOrNull().takeIf { perBehavior.size > 1 }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val settings = container.currentSettings()

            val ranking = container.insightsRepository.consistencyRanking()
            val insights = container.insightsRepository.insightsForAll(settings.coachIntensity)
            container.insightsRepository.recordInsights(insights)

            val month = _state.value.calendarMonth

            _state.update {
                it.copy(
                    loading = false,
                    overall = container.insightsRepository.overall(),
                    perBehavior = ranking,
                    insights = insights,
                    weeklyTrend = container.insightsRepository.weeklyTrend(),
                    dayOfWeek = container.insightsRepository.dayOfWeekPerformance(),
                    review = container.insightsRepository.weeklyReview(
                        weekStart = RecurrenceEngine.weekStart(
                            container.clock.today(),
                            settings.firstDayOfWeek,
                        ),
                        intensity = settings.coachIntensity,
                    ),
                    calendar = container.insightsRepository.calendarSummary(
                        month.atDay(1),
                        month.atEndOfMonth(),
                    ),
                )
            }
        }
    }

    fun showMonth(month: YearMonth) {
        viewModelScope.launch {
            _state.update { it.copy(calendarMonth = month) }
            val summary = container.insightsRepository.calendarSummary(
                month.atDay(1),
                month.atEndOfMonth(),
            )
            _state.update { it.copy(calendar = summary) }
        }
    }

    /**
     * Applies a schedule change the coach suggested. Only ever called from an explicit
     * "Apply change" tap; the engine never writes schedules itself.
     */
    fun applySuggestion(insight: CoachInsight) {
        val suggestion = insight.scheduleSuggestion ?: return
        viewModelScope.launch {
            val moved = container.behaviorRepository.moveScheduleTime(
                suggestion.scheduleId,
                suggestion.fromTime,
                suggestion.toTime,
            )
            if (moved) {
                container.schedulingCoordinator.refresh()
                _state.update {
                    it.copy(
                        appliedSuggestion = suggestion.scheduleId,
                        message = "Schedule updated to ${suggestion.toTime}.",
                    )
                }
                load()
            } else {
                _state.update { it.copy(message = "That schedule has already changed.") }
            }
        }
    }

    fun keepSchedule(insight: CoachInsight) {
        viewModelScope.launch {
            _state.update { it.copy(message = "Kept as it is.") }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}

/** History for a single behavior, including its recovery chains. */
class BehaviorDetailViewModel(
    private val container: AppContainer,
    private val behaviorId: String,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val behavior: Behavior? = null,
        val schedules: List<com.pact.coach.domain.model.Schedule> = emptyList(),
        val instructions: List<com.pact.coach.domain.model.DailyInstruction> = emptyList(),
        val stats: BehaviorStats? = null,
        val insights: List<CoachInsight> = emptyList(),
        val history: List<com.pact.coach.domain.model.BehaviorInstance> = emptyList(),
        val goalName: String? = null,
        /** Upcoming skips, pauses and vacations that apply to this behavior. */
        val exceptions: List<com.pact.coach.domain.model.BehaviorException> = emptyList(),
        val message: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val behavior = container.behaviorRepository.get(behaviorId)
            if (behavior == null) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val settings = container.currentSettings()
            _state.update {
                it.copy(
                    loading = false,
                    behavior = behavior,
                    schedules = container.behaviorRepository.getSchedules(behaviorId),
                    instructions = container.behaviorRepository.getInstructions(behaviorId),
                    stats = container.insightsRepository.statsFor(behaviorId),
                    insights = container.insightsRepository.insightsFor(
                        behaviorId,
                        settings.coachIntensity,
                    ),
                    history = container.instanceRepository.getForBehavior(behaviorId).take(120),
                    goalName = behavior.goalId?.let { id -> container.goalRepository.get(id)?.name },
                    exceptions = container.behaviorRepository
                        .getExceptionsFrom(container.clock.today())
                        .filter { it.behaviorId == null || it.behaviorId == behaviorId }
                        .sortedBy { it.startDate },
                )
            }
        }
    }

    /**
     * Records a skip, pause or vacation. This removes days from generation without touching the
     * permanent schedule: "skip 10 September" leaves the daily rule exactly as it was.
     */
    fun addException(
        type: com.pact.coach.domain.model.ExceptionType,
        start: LocalDate,
        end: LocalDate,
        note: String = "",
    ) {
        viewModelScope.launch {
            container.behaviorRepository.saveException(
                com.pact.coach.domain.model.BehaviorException(
                    behaviorId = behaviorId,
                    type = type,
                    startDate = start,
                    endDate = maxOf(start, end),
                    note = note,
                ),
            )
            // Drop any alarms already armed for the skipped days, then regenerate.
            container.schedulingCoordinator.cancelForBehavior(behaviorId)
            container.schedulingCoordinator.refresh()
            _state.update { it.copy(message = "Schedule paused. Your recurring plan is unchanged.") }
            load()
        }
    }

    fun removeException(id: String) {
        viewModelScope.launch {
            container.behaviorRepository.deleteException(id)
            container.schedulingCoordinator.refresh()
            load()
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun setActive(active: Boolean) {
        viewModelScope.launch {
            container.behaviorRepository.setActive(behaviorId, active)
            if (!active) container.schedulingCoordinator.cancelForBehavior(behaviorId)
            container.schedulingCoordinator.refresh()
            load()
        }
    }

    /** Records a completion for a past occurrence the user did but never logged. */
    fun completeHistoric(instanceId: String) {
        viewModelScope.launch {
            container.instanceRepository.complete(instanceId)
            load()
        }
    }
}
