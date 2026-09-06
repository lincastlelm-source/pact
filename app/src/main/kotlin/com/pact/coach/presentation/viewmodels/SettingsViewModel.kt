package com.pact.coach.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pact.coach.core.util.Ids
import com.pact.coach.data.backup.BackupService
import com.pact.coach.data.backup.BackupSummary
import com.pact.coach.data.repository.AppSettings
import com.pact.coach.di.AppContainer
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.CoachIntensity
import com.pact.coach.domain.model.CoachPersonality
import com.pact.coach.domain.model.EnforcementLevel
import com.pact.coach.domain.model.Goal
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.model.ReflectionFrequency
import com.pact.coach.domain.model.Schedule
import com.pact.coach.domain.model.ThemeMode
import com.pact.coach.domain.scheduler.InstanceGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.time.DayOfWeek

/**
 * Settings, backup and the alarm self-test.
 *
 * Backup deliberately uses the Storage Access Framework (the caller passes in already-opened
 * streams), so the app needs no storage permission and never touches a path the user did not
 * pick themselves.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    data class BackupState(
        val exporting: Boolean = false,
        val importing: Boolean = false,
        /** Set once a file has been parsed and validated, awaiting the user's choice of mode. */
        val pendingImport: BackupSummary? = null,
        val message: String? = null,
        val error: String? = null,
    )

    private val pendingEnvelope = MutableStateFlow<com.pact.coach.data.backup.BackupEnvelope?>(null)

    private val _backup = MutableStateFlow(BackupState())
    val backup: StateFlow<BackupState> = _backup.asStateFlow()

    private val _alarmTest = MutableStateFlow<String?>(null)
    val alarmTest: StateFlow<String?> = _alarmTest.asStateFlow()

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val exactAlarmsAvailable: Boolean get() = container.exactAlarmCapability.canScheduleExact()
    val notificationsEnabled: Boolean get() = container.notificationChannels.areNotificationsEnabled()

    // --- Preferences --------------------------------------------------------------------

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { container.settingsRepository.setTheme(mode) }
    fun setUse24Hour(v: Boolean) = viewModelScope.launch { container.settingsRepository.setUse24Hour(v) }
    fun setFirstDay(day: DayOfWeek) = viewModelScope.launch { container.settingsRepository.setFirstDayOfWeek(day) }
    fun setPersonality(v: CoachPersonality) = viewModelScope.launch { container.settingsRepository.setPersonality(v) }
    fun setIntensity(v: CoachIntensity) = viewModelScope.launch { container.settingsRepository.setIntensity(v) }
    fun setReflection(v: ReflectionFrequency) = viewModelScope.launch { container.settingsRepository.setReflectionFrequency(v) }
    fun setDefaultEnforcement(v: EnforcementLevel) = viewModelScope.launch { container.settingsRepository.setDefaultEnforcement(v) }
    fun setDefaultSoundEnabled(v: Boolean) = viewModelScope.launch { container.settingsRepository.setDefaultSoundEnabled(v) }
    fun setDefaultVibrationEnabled(v: Boolean) = viewModelScope.launch { container.settingsRepository.setDefaultVibrationEnabled(v) }
    fun setDefaultSound(uri: String?) = viewModelScope.launch { container.settingsRepository.setDefaultSound(uri) }
    fun setDefaultRecovery(minutes: Int) = viewModelScope.launch { container.settingsRepository.setDefaultRecoveryMinutes(minutes) }
    fun markAlarmHelpSeen() = viewModelScope.launch { container.settingsRepository.setAlarmHelpSeen(true) }

    // --- Backup -------------------------------------------------------------------------

    /** @param open supplies a stream for a document the user already chose via SAF. */
    fun export(open: () -> OutputStream?) {
        viewModelScope.launch {
            _backup.update { it.copy(exporting = true, error = null, message = null) }
            try {
                val json = container.backupService.export(settings.value)
                val stream = open()
                if (stream == null) {
                    _backup.update {
                        it.copy(exporting = false, error = "Could not open the file for writing.")
                    }
                    return@launch
                }
                stream.use { out -> out.write(json.toByteArray(Charsets.UTF_8)) }
                _backup.update {
                    it.copy(exporting = false, message = "Backup saved.")
                }
            } catch (e: Exception) {
                _backup.update {
                    it.copy(exporting = false, error = "Export failed: ${e.message}")
                }
            }
        }
    }

    fun suggestedFileName(): String = container.backupService.suggestedFileName()

    /**
     * Reads and validates a chosen file without changing anything. The user then picks merge or
     * replace, and only [confirmImport] writes.
     */
    fun stageImport(open: () -> InputStream?) {
        viewModelScope.launch {
            _backup.update { it.copy(importing = true, error = null, message = null) }
            try {
                val stream = open()
                if (stream == null) {
                    _backup.update {
                        it.copy(importing = false, error = "Could not open that file.")
                    }
                    return@launch
                }
                val raw = stream.use { it.readBytes().toString(Charsets.UTF_8) }

                when (val parsed = container.backupService.parse(raw)) {
                    is BackupService.ParseResult.Invalid ->
                        _backup.update { it.copy(importing = false, error = parsed.reason) }

                    is BackupService.ParseResult.Ok -> {
                        pendingEnvelope.value = parsed.envelope
                        _backup.update {
                            it.copy(importing = false, pendingImport = parsed.summary)
                        }
                    }
                }
            } catch (e: Exception) {
                _backup.update {
                    it.copy(importing = false, error = "Could not read that file: ${e.message}")
                }
            }
        }
    }

    fun cancelImport() {
        pendingEnvelope.value = null
        _backup.update { it.copy(pendingImport = null) }
    }

    fun confirmImport(mode: BackupService.Mode) {
        val envelope = pendingEnvelope.value ?: return
        viewModelScope.launch {
            _backup.update { it.copy(importing = true) }
            try {
                val result = container.backupService.import(envelope, mode)
                pendingEnvelope.value = null
                // Imported history changes what should be scheduled.
                container.schedulingCoordinator.refresh()
                _backup.update {
                    it.copy(
                        importing = false,
                        pendingImport = null,
                        message = "Imported ${result.inserted} entries" +
                            if (result.skipped > 0) ", skipped ${result.skipped} already present." else ".",
                    )
                }
            } catch (e: Exception) {
                _backup.update {
                    it.copy(importing = false, error = "Import failed, nothing was changed: ${e.message}")
                }
            }
        }
    }

    fun dismissBackupMessage() = _backup.update { it.copy(message = null, error = null) }

    // --- Alarm self-test ----------------------------------------------------------------

    /**
     * Schedules a real alarm a short time from now, using the real pipeline, so the user can see
     * whether sound, vibration and the intervention screen actually work on their device. This
     * is the single most useful diagnostic on Android, where OEM battery management silently
     * breaks reminders.
     */
    fun testAlarm(secondsFromNow: Int = 15) {
        viewModelScope.launch {
            try {
                val now = container.clock.now()
                val at = now.plusSeconds(secondsFromNow.toLong())
                val local = at.atZone(container.clock.zone())

                val settingsNow = settings.value
                val testId = Ids.new()

                // A throwaway behavior and occurrence, saved so the intervention screen can load
                // them exactly as it would for a real reminder, then cleaned up by the user.
                val behavior = Behavior(
                    id = testId,
                    name = "Test reminder",
                    description = "Checks that alarms, sound and notifications work on this device.",
                    defaultInstruction = "If you can see this, reminders are working.",
                    enforcement = settingsNow.defaultEnforcement,
                    soundEnabled = settingsNow.defaultSoundEnabled,
                    vibrationEnabled = settingsNow.defaultVibrationEnabled,
                    soundUri = settingsNow.defaultSoundUri,
                    targetDurationMinutes = 1,
                    minimumDurationMinutes = 1,
                    isActive = true,
                    createdAt = now,
                    updatedAt = now,
                )

                container.behaviorRepository.save(
                    behavior = behavior,
                    schedules = listOf(
                        Schedule(
                            behaviorId = testId,
                            recurrenceType = com.pact.coach.domain.model.RecurrenceType.ONCE,
                            startDate = local.toLocalDate(),
                            timesOfDay = listOf(local.toLocalTime().withSecond(0).withNano(0)),
                        ),
                    ),
                )

                val instance = BehaviorInstance(
                    behaviorId = testId,
                    scheduleId = null,
                    scheduledDate = local.toLocalDate(),
                    scheduledTime = local.toLocalTime(),
                    scheduledAtUtcMillis = at.toEpochMilli(),
                    zoneId = container.clock.zone().id,
                    occurrenceIndex = 0,
                    state = InstanceState.SCHEDULED,
                    dedupKey = InstanceGenerator.recoveryDedupKey("test-$testId"),
                    createdAtMillis = now.toEpochMilli(),
                )
                container.instanceRepository.insertGenerated(listOf(instance))

                val stored = container.instanceRepository.getForBehavior(testId).firstOrNull()
                if (stored == null) {
                    _alarmTest.value = "Could not create the test reminder."
                    return@launch
                }

                when (val outcome = container.schedulingCoordinator.armInstance(stored)) {
                    is com.pact.coach.services.alarm.ScheduleOutcome.Exact ->
                        _alarmTest.value =
                            "A test reminder will arrive in about $secondsFromNow seconds."

                    is com.pact.coach.services.alarm.ScheduleOutcome.Inexact ->
                        _alarmTest.value =
                            "Scheduled, but without exact-alarm permission it may be several " +
                                "minutes late. See the alarm help below."

                    is com.pact.coach.services.alarm.ScheduleOutcome.Failed ->
                        _alarmTest.value = "Could not schedule the test: ${outcome.reason}"

                    is com.pact.coach.services.alarm.ScheduleOutcome.Skipped ->
                        _alarmTest.value = "Test skipped: ${outcome.reason}"
                }
            } catch (e: Exception) {
                _alarmTest.value = "Test failed: ${e.message}"
            }
        }
    }

    /** Removes any leftover test behaviors so they never pollute real statistics. */
    fun clearTestBehaviors() {
        viewModelScope.launch {
            container.behaviorRepository.getAll()
                .filter { it.name == "Test reminder" }
                .forEach { behavior ->
                    container.schedulingCoordinator.cancelForBehavior(behavior.id)
                    container.notificationChannels.deleteChannelsFor(behavior.id)
                    container.behaviorRepository.delete(behavior.id)
                }
            _alarmTest.value = "Test reminders removed."
        }
    }

    fun dismissAlarmTest() { _alarmTest.value = null }

    fun exactAlarmSettingsIntent() = container.exactAlarmCapability.settingsIntent()
    fun batterySettingsIntent() = container.exactAlarmCapability.batteryOptimisationIntent()

    /** Human-readable name for a chosen sound URI, for display in Settings. */
    fun soundLabelFor(uri: String?): String =
        if (uri.isNullOrBlank()) "Default notification sound" else "Custom sound"
}

/** First-run flow: explain the idea, then create one goal and one behavior. */
class OnboardingViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val step: Int = 0,
        val goalName: String = "",
        val goalWhy: String = "",
        val createdGoal: Goal? = null,
        val finished: Boolean = false,
        val error: String? = null,
    ) {
        val canContinueGoal: Boolean get() = goalName.isNotBlank()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun next() = _state.update { it.copy(step = it.step + 1) }
    fun back() = _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }
    fun setGoalName(v: String) = _state.update { it.copy(goalName = v) }
    fun setGoalWhy(v: String) = _state.update { it.copy(goalWhy = v) }

    fun createGoal(onCreated: (String) -> Unit) {
        val s = _state.value
        if (!s.canContinueGoal) return
        viewModelScope.launch {
            try {
                val goal = container.goalRepository.create(
                    name = s.goalName,
                    whyItMatters = s.goalWhy,
                    category = "Personal",
                    priority = com.pact.coach.domain.model.Priority.HIGH,
                )
                _state.update { it.copy(createdGoal = goal, step = it.step + 1) }
                onCreated(goal.id)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Could not create the goal: ${e.message}") }
            }
        }
    }

    fun finish() {
        viewModelScope.launch {
            container.settingsRepository.setOnboardingComplete(true)
            container.schedulingCoordinator.refresh()
            _state.update { it.copy(finished = true) }
        }
    }

    fun skip() = finish()
}
