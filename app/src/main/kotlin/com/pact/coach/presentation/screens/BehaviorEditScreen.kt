package com.pact.coach.presentation.screens

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pact.coach.core.util.TimeFormat
import com.pact.coach.data.repository.AppSettings
import com.pact.coach.domain.model.BehaviorArchetype
import com.pact.coach.domain.model.DailyInstruction
import com.pact.coach.domain.model.DayMask
import com.pact.coach.domain.model.Difficulty
import com.pact.coach.domain.model.EnforcementLevel
import com.pact.coach.domain.model.Importance
import com.pact.coach.domain.model.Measurement
import com.pact.coach.domain.model.RecurrenceType
import com.pact.coach.domain.model.ReflectionFrequency
import com.pact.coach.presentation.components.SectionHeader
import com.pact.coach.presentation.viewmodels.BehaviorEditViewModel
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The behavior editor: the one genuinely long form in the app.
 *
 * It is organised so the first screenful is enough for a simple behavior (name, type, schedule)
 * and everything else is grouped underneath for people who want it. Validation is shown inline
 * rather than only on save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BehaviorEditScreen(
    viewModel: BehaviorEditViewModel,
    settings: AppSettings,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // System ringtone picker. Covers notification and alarm sounds, which is what most people
    // want, and hands back a URI the notification channel can use directly.
    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.pickedRingtoneUri()
            viewModel.setSound(uri?.toString(), ringtoneTitle(context, uri))
        }
    }

    // A file the user picked themselves. Read access has to be persisted, or the URI stops
    // resolving after a reboot and the reminder silently falls back to the default sound.
    val audioFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.isSuccess
            viewModel.setSound(
                uri.toString(),
                if (persisted) "Custom audio file" else "Custom audio file (may not persist)",
            )
        }
    }

    var showTimePicker by remember { mutableStateOf(false) }
    var editingTime by remember { mutableStateOf<LocalTime?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTemplateSave by remember { mutableStateOf(false) }
    var dayPlanFor by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    LaunchedEffect(Unit) { viewModel.validate() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            if (state.isEditing) "Edit behavior" else "New behavior",
            style = MaterialTheme.typography.headlineMedium,
        )

        if (state.templates.isNotEmpty() && !state.isEditing) {
            Spacer(Modifier.height(12.dp))
            SectionHeader("Start from a template")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.templates.forEach { template ->
                    AssistChip(
                        onClick = { viewModel.applyTemplate(template.id) },
                        label = { Text(template.name) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text("Name") },
            singleLine = true,
            isError = state.duplicateNameWarning,
            supportingText = if (state.duplicateNameWarning) {
                { Text("You already have a behavior with this name.") }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        SectionHeader("Goal")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.goalId == null,
                onClick = { viewModel.setGoal(null) },
                label = { Text("No goal") },
            )
            state.goals.forEach { goal ->
                FilterChip(
                    selected = state.goalId == goal.id,
                    onClick = { viewModel.setGoal(goal.id) },
                    label = { Text(goal.name) },
                )
            }
        }

        // --- Type -----------------------------------------------------------------------

        Spacer(Modifier.height(16.dp))
        SectionHeader("What kind of behavior is this?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BehaviorArchetype.entries.forEach { archetype ->
                FilterChip(
                    selected = state.archetype == archetype,
                    onClick = { viewModel.setArchetype(archetype) },
                    label = { Text(archetype.label) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        SwitchRow(
            label = "Happens at a specific time",
            supporting = if (state.isTimeBased) {
                "Reminders fire at the times you set below."
            } else {
                "Tracked for the day, with no alarm."
            },
            checked = state.isTimeBased,
            onChange = viewModel::setTimeBased,
        )

        Spacer(Modifier.height(8.dp))
        SectionHeader("How is it measured?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Measurement.entries.forEach { measurement ->
                FilterChip(
                    selected = state.measurement == measurement,
                    onClick = { viewModel.setMeasurement(measurement) },
                    label = {
                        Text(measurement.name.lowercase().replaceFirstChar { it.uppercase() })
                    },
                )
            }
        }

        // --- Targets --------------------------------------------------------------------

        if (state.showsDuration) {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Duration")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.targetDuration,
                    onValueChange = viewModel::setTargetDuration,
                    label = { Text("Target (min)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.minimumDuration,
                    onValueChange = viewModel::setMinimumDuration,
                    label = { Text("Minimum (min)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "The minimum is what you offer yourself on a bad day. It still counts as done.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (state.showsQuantity) {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Quantity")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.targetQuantity,
                    onValueChange = viewModel::setTargetQuantity,
                    label = { Text("Target") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.minimumQuantity,
                    onValueChange = viewModel::setMinimumQuantity,
                    label = { Text("Minimum") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.quantityUnit,
                onValueChange = viewModel::setQuantityUnit,
                label = { Text("Unit (litres, pages, steps...)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.showsChecklist) {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Checklist")
            state.checklist.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { viewModel.removeChecklistItem(item.id) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove ${item.text}")
                    }
                }
            }
            var newItem by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newItem,
                    onValueChange = { newItem = it },
                    label = { Text("Add an item") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { viewModel.addChecklistItem(newItem); newItem = "" },
                    enabled = newItem.isNotBlank(),
                ) { Text("Add") }
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                label = "Allow partial completion",
                supporting = "Ticking some of the list still counts, recorded as partial.",
                checked = state.allowPartialChecklist,
                onChange = viewModel::setAllowPartialChecklist,
            )
        }

        // --- Recurrence -----------------------------------------------------------------

        Spacer(Modifier.height(16.dp))
        SectionHeader("How often?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecurrenceType.entries.forEach { type ->
                FilterChip(
                    selected = state.recurrenceType == type,
                    onClick = { viewModel.setRecurrenceType(type) },
                    label = { Text(type.label()) },
                )
            }
        }

        if (state.recurrenceType == RecurrenceType.DAYS_OF_WEEK) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..7).forEach { iso ->
                    FilterChip(
                        selected = DayMask.contains(state.daysOfWeekMask, iso),
                        onClick = { viewModel.toggleDay(iso) },
                        label = {
                            Text(
                                DayOfWeek.of(iso)
                                    .getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                    .take(2),
                            )
                        },
                    )
                }
            }
        }

        if (state.recurrenceType == RecurrenceType.EVERY_N_DAYS) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.intervalDays,
                onValueChange = viewModel::setIntervalDays,
                label = { Text("Every how many days?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.recurrenceType == RecurrenceType.TIMES_PER_WEEK) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.timesPerWeek,
                onValueChange = viewModel::setTimesPerWeek,
                label = { Text("Times per week") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // --- Times ----------------------------------------------------------------------

        if (state.isTimeBased) {
            Spacer(Modifier.height(16.dp))
            SectionHeader("At what times?")
            Text(
                "Each time is tracked separately, so a morning and an evening session keep their own history.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.timesOfDay.forEach { time ->
                    AssistChip(
                        onClick = { editingTime = time; showTimePicker = true },
                        label = { Text(TimeFormat.time(time, settings.use24HourClock)) },
                        trailingIcon = {
                            IconButton(onClick = { viewModel.removeTime(time) }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove ${TimeFormat.time(time, settings.use24HourClock)}",
                                )
                            }
                        },
                    )
                }
                AssistChip(
                    onClick = { editingTime = null; showTimePicker = true },
                    label = { Text("+ Add time") },
                )
            }
        } else {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.timesPerDay,
                onValueChange = viewModel::setTimesPerDay,
                label = { Text("How many times a day?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // --- Daily plans ----------------------------------------------------------------

        Spacer(Modifier.height(16.dp))
        SectionHeader("What does it mean each day?")
        OutlinedTextField(
            value = state.defaultInstruction,
            onValueChange = viewModel::setDefaultInstruction,
            label = { Text("Default plan") },
            supportingText = { Text("Used on any day without its own plan.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Give a specific day its own plan, for example a Monday run and a Thursday strength session.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..7).forEach { iso ->
                val hasPlan = state.dailyInstructions.any { it.dayOfWeek == iso }
                FilterChip(
                    selected = hasPlan,
                    onClick = { dayPlanFor = iso },
                    label = {
                        Text(
                            DayOfWeek.of(iso)
                                .getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                .take(2),
                        )
                    },
                )
            }
        }

        // --- Reminder -------------------------------------------------------------------

        Spacer(Modifier.height(16.dp))
        SectionHeader("Reminder")
        SwitchRow("Remind me", null, state.reminderEnabled, viewModel::setReminderEnabled)
        SwitchRow("Sound", null, state.soundEnabled, viewModel::setSoundEnabled)

        if (state.soundEnabled) {
            Text(
                "Sound: ${state.soundLabel}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ringtonePicker.launch(ringtonePickerIntent(state.soundUri)) }) {
                    Text("Choose a sound")
                }
                OutlinedButton(onClick = { audioFilePicker.launch(arrayOf("audio/*")) }) {
                    Text("Use a file")
                }
            }
            if (state.soundUri != null) {
                TextButton(onClick = { viewModel.setSound(null, "Default") }) {
                    Text("Reset to default")
                }
            }
        }

        SwitchRow("Vibration", null, state.vibrationEnabled, viewModel::setVibrationEnabled)

        if (state.vibrationEnabled) {
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("default", "short", "long", "double", "insistent").forEach { pattern ->
                    FilterChip(
                        selected = state.vibrationPattern == pattern,
                        onClick = { viewModel.setVibrationPattern(pattern) },
                        label = { Text(pattern.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        SectionHeader("How insistent?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnforcementLevel.entries.forEach { level ->
                FilterChip(
                    selected = state.enforcement == level,
                    onClick = { viewModel.setEnforcement(level) },
                    label = { Text(level.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        Text(
            when (state.enforcement) {
                EnforcementLevel.GENTLE -> "A quiet notification you can ignore."
                EnforcementLevel.COACH -> "A heads-up reminder, with one follow-up if you do not answer."
                EnforcementLevel.STRONG -> "A full-screen prompt asking for a decision. You can always leave it."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.preAlertMinutes,
                onValueChange = viewModel::setPreAlert,
                label = { Text("Warn me before (min)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.escalationMinutes,
                onValueChange = viewModel::setEscalation,
                label = { Text("Follow up after (min)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        // --- Recovery -------------------------------------------------------------------

        Spacer(Modifier.height(16.dp))
        SectionHeader("Recovery")
        SwitchRow(
            label = "Allow moving it later",
            supporting = "Recovering is not a miss. It keeps the original time in your history.",
            checked = state.recoveryEnabled,
            onChange = viewModel::setRecoveryEnabled,
        )
        if (state.recoveryEnabled) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.defaultRecoveryMinutes,
                    onValueChange = viewModel::setDefaultRecovery,
                    label = { Text("Default delay (min)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.maxRecoveries,
                    onValueChange = viewModel::setMaxRecoveries,
                    label = { Text("Max moves") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.missWindowMinutes,
            onValueChange = viewModel::setMissWindow,
            label = { Text("Counts as missed after (min)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // --- Character ------------------------------------------------------------------

        Spacer(Modifier.height(16.dp))
        SectionHeader("Importance")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Importance.entries.forEach {
                FilterChip(
                    selected = state.importance == it,
                    onClick = { viewModel.setImportance(it) },
                    label = { Text(it.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SectionHeader("Difficulty")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Difficulty.entries.forEach {
                FilterChip(
                    selected = state.difficulty == it,
                    onClick = { viewModel.setDifficulty(it) },
                    label = { Text(it.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Reflection")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReflectionFrequency.entries.forEach {
                FilterChip(
                    selected = state.reflectionFrequency == it,
                    onClick = { viewModel.setReflectionFrequency(it) },
                    label = { Text(it.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                )
            }
        }
        SwitchRow(
            label = "Ask why when passing",
            supporting = null,
            checked = state.requirePassReason,
            onChange = viewModel::setRequirePassReason,
        )

        // --- Progression ----------------------------------------------------------------

        Spacer(Modifier.height(16.dp))
        SwitchRow(
            label = "Ramp up gradually",
            supporting = "You set each week yourself. The app never raises a target on its own.",
            checked = state.progressionEnabled,
            onChange = viewModel::setProgressionEnabled,
        )
        if (state.progressionEnabled) {
            (0..3).forEach { week ->
                val step = state.progression.firstOrNull { it.weekIndex == week }
                OutlinedTextField(
                    value = step?.targetDurationMinutes?.toString() ?: "",
                    onValueChange = { v ->
                        viewModel.setProgressionStep(week, v.filter(Char::isDigit).toIntOrNull())
                    },
                    label = { Text("Week ${week + 1} target (min)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SwitchRow("Active", "Paused behaviors do not fire reminders.", state.isActive, viewModel::setActive)

        // --- Errors and save ------------------------------------------------------------

        if (state.validationErrors.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    state.validationErrors.forEach {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = viewModel::save,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isEditing) "Save" else "Create behavior")
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showTemplateSave = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save as template") }

        if (state.isEditing) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete behavior", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        Spacer(Modifier.height(48.dp))
    }

    // --- Dialogs ------------------------------------------------------------------------

    if (showTimePicker) {
        val initial = editingTime ?: LocalTime.of(7, 0)
        val pickerState = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = settings.use24HourClock,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = LocalTime.of(pickerState.hour, pickerState.minute)
                    val previous = editingTime
                    if (previous != null) viewModel.replaceTime(previous, picked)
                    else viewModel.addTime(picked)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            text = { TimePicker(state = pickerState) },
        )
    }

    dayPlanFor?.let { iso ->
        DayPlanDialog(
            dayOfWeek = iso,
            existing = state.dailyInstructions.firstOrNull { it.dayOfWeek == iso },
            behaviorId = state.id.orEmpty(),
            onDismiss = { dayPlanFor = null },
            onSave = { viewModel.upsertDailyInstruction(it); dayPlanFor = null },
            onDelete = { id -> viewModel.removeDailyInstruction(id); dayPlanFor = null },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this behavior?") },
            text = {
                Text(
                    "Its history and any scheduled reminders will be removed. " +
                        "If you only want to stop the reminders, turn Active off instead.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; viewModel.delete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Keep it") }
            },
        )
    }

    if (showTemplateSave) {
        var templateName by remember { mutableStateOf(state.name) }
        AlertDialog(
            onDismissRequest = { showTemplateSave = false },
            title = { Text("Save as template") },
            text = {
                OutlinedTextField(
                    value = templateName,
                    onValueChange = { templateName = it },
                    label = { Text("Template name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveAsTemplate(templateName)
                    showTemplateSave = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showTemplateSave = false }) { Text("Cancel") }
            },
        )
    }
}

/** Per-weekday plan: what "Exercise" actually means on a Tuesday. */
@Composable
private fun DayPlanDialog(
    dayOfWeek: Int,
    existing: DailyInstruction?,
    behaviorId: String,
    onDismiss: () -> Unit,
    onSave: (DailyInstruction) -> Unit,
    onDelete: (String) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var instructions by remember { mutableStateOf(existing?.instructions.orEmpty()) }
    var duration by remember { mutableStateOf(existing?.durationMinutes?.toString().orEmpty()) }
    var restDay by remember { mutableStateOf(existing?.isRestDay ?: false) }

    val dayName = DayOfWeek.of(dayOfWeek).getDisplayName(TextStyle.FULL, Locale.getDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dayName) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title, e.g. 20-minute run") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Details") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it.filter(Char::isDigit).take(4) },
                    label = { Text("Duration for this day (min)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow("Rest day", "Shown as a planned rest rather than a task.", restDay) {
                    restDay = it
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    (existing ?: DailyInstruction(behaviorId = behaviorId, dayOfWeek = dayOfWeek)).copy(
                        dayOfWeek = dayOfWeek,
                        title = title.trim(),
                        instructions = instructions.trim(),
                        durationMinutes = duration.toIntOrNull(),
                        isRestDay = restDay,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = { onDelete(existing.id) }) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun SwitchRow(
    label: String,
    supporting: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Intent for the system ringtone picker, pre-selecting whatever the behavior already uses. */
private fun ringtonePickerIntent(currentUri: String?): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(
            RingtoneManager.EXTRA_RINGTONE_TYPE,
            RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM,
        )
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose a reminder sound")
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        // No "Silent" entry: silence is expressed by turning the Sound switch off, which keeps
        // the two settings from disagreeing with each other.
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri?.let(Uri::parse))
    }

private fun Intent.pickedRingtoneUri(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
    }

/**
 * Human-readable name for a chosen sound. Reading the title can fail if the URI is not accessible,
 * so this never throws; the notification layer performs the same check before using it.
 */
private fun ringtoneTitle(context: android.content.Context, uri: Uri?): String {
    if (uri == null) return "Default"
    return runCatching {
        RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "Custom sound"
    }.getOrDefault("Custom sound")
}

private fun RecurrenceType.label(): String = when (this) {
    RecurrenceType.ONCE -> "Once"
    RecurrenceType.DAILY -> "Every day"
    RecurrenceType.WEEKDAYS -> "Weekdays"
    RecurrenceType.WEEKENDS -> "Weekends"
    RecurrenceType.DAYS_OF_WEEK -> "Chosen days"
    RecurrenceType.EVERY_N_DAYS -> "Every N days"
    RecurrenceType.TIMES_PER_WEEK -> "N times a week"
}
