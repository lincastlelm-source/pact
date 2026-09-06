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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pact.coach.data.backup.BackupService
import com.pact.coach.domain.model.CoachIntensity
import com.pact.coach.domain.model.CoachPersonality
import com.pact.coach.domain.model.EnforcementLevel
import com.pact.coach.domain.model.ReflectionFrequency
import com.pact.coach.domain.model.ThemeMode
import com.pact.coach.presentation.components.SectionHeader
import com.pact.coach.presentation.viewmodels.SettingsViewModel
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Settings, including backup, the alarm self-test and the honest explanation of what Android
 * does and does not let a reminder app guarantee.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    val alarmTest by viewModel.alarmTest.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Storage Access Framework: the user picks the file, so the app needs no storage permission.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            viewModel.export { context.contentResolver.openOutputStream(uri) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.stageImport { context.contentResolver.openInputStream(uri) }
        }
    }

    // The fallback sound for behaviors that do not choose one of their own.
    val defaultSoundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val picked: Uri? = result.data?.let { data ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
            }
            viewModel.setDefaultSound(picked?.toString())
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        // --- General --------------------------------------------------------------------

        Spacer(Modifier.height(16.dp))
        SectionHeader("Appearance")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.themeMode == mode,
                    onClick = { viewModel.setTheme(mode) },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        SwitchRow("24-hour clock", null, settings.use24HourClock, viewModel::setUse24Hour)

        Spacer(Modifier.height(8.dp))
        SectionHeader("Week starts on")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY).forEach { day ->
                FilterChip(
                    selected = settings.firstDayOfWeek == day,
                    onClick = { viewModel.setFirstDay(day) },
                    label = { Text(day.getDisplayName(TextStyle.FULL, Locale.getDefault())) },
                )
            }
        }

        // --- Notifications --------------------------------------------------------------

        Spacer(Modifier.height(20.dp))
        SectionHeader("Reminders")
        SwitchRow(
            "Sound by default",
            null,
            settings.defaultSoundEnabled,
            viewModel::setDefaultSoundEnabled,
        )
        SwitchRow(
            "Vibration by default",
            null,
            settings.defaultVibrationEnabled,
            viewModel::setDefaultVibrationEnabled,
        )
        Text(
            "Sound: ${viewModel.soundLabelFor(settings.defaultSoundUri)}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    defaultSoundPicker.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_TYPE,
                                RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM,
                            )
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Default reminder sound")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                settings.defaultSoundUri?.let(Uri::parse),
                            )
                        },
                    )
                },
            ) {
                Text("Choose default sound")
            }
            if (settings.defaultSoundUri != null) {
                TextButton(onClick = { viewModel.setDefaultSound(null) }) { Text("Reset") }
            }
        }

        Spacer(Modifier.height(8.dp))
        SectionHeader("Default insistence")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnforcementLevel.entries.forEach { level ->
                FilterChip(
                    selected = settings.defaultEnforcement == level,
                    onClick = { viewModel.setDefaultEnforcement(level) },
                    label = { Text(level.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        // --- Alarm health ---------------------------------------------------------------

        Spacer(Modifier.height(20.dp))
        SectionHeader("Will my reminders actually arrive?")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                StatusLine(
                    "Notifications",
                    if (viewModel.notificationsEnabled) "Allowed" else "Blocked in system settings",
                    viewModel.notificationsEnabled,
                )
                StatusLine(
                    "Exact alarms",
                    if (viewModel.exactAlarmsAvailable) {
                        "Allowed, reminders fire at the exact minute"
                    } else {
                        "Not allowed, reminders may be several minutes late"
                    },
                    viewModel.exactAlarmsAvailable,
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    "Android limits when apps can wake the device. Battery optimisation, Doze and " +
                        "some manufacturer power managers can delay or drop reminders. PACT never " +
                        "tries to work around these restrictions; it degrades gracefully and tells " +
                        "you when precision is lost.",
                    style = MaterialTheme.typography.bodyLarge,
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.testAlarm() }) { Text("Test my reminder") }
                    viewModel.exactAlarmSettingsIntent()?.let { intent ->
                        OutlinedButton(onClick = { context.safeStart(intent) }) {
                            Text("Alarm permission")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { context.safeStart(viewModel.batterySettingsIntent()) }) {
                    Text("Battery settings")
                }
            }
        }

        alarmTest?.let { message ->
            Spacer(Modifier.height(8.dp))
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                    Row {
                        TextButton(onClick = viewModel::dismissAlarmTest) { Text("OK") }
                        TextButton(onClick = viewModel::clearTestBehaviors) {
                            Text("Remove test reminders")
                        }
                    }
                }
            }
        }

        // --- Coach ----------------------------------------------------------------------

        Spacer(Modifier.height(20.dp))
        SectionHeader("Coach personality")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoachPersonality.entries.forEach { personality ->
                FilterChip(
                    selected = settings.coachPersonality == personality,
                    onClick = { viewModel.setPersonality(personality) },
                    label = {
                        Text(personality.name.lowercase().replaceFirstChar { it.uppercase() })
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        SectionHeader("How much coaching?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoachIntensity.entries.forEach { intensity ->
                FilterChip(
                    selected = settings.coachIntensity == intensity,
                    onClick = { viewModel.setIntensity(intensity) },
                    label = { Text(intensity.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        SectionHeader("Ask how it went")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReflectionFrequency.entries.forEach { frequency ->
                FilterChip(
                    selected = settings.reflectionFrequency == frequency,
                    onClick = { viewModel.setReflection(frequency) },
                    label = { Text(frequency.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        // --- Privacy --------------------------------------------------------------------

        Spacer(Modifier.height(20.dp))
        SectionHeader("Privacy")
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Your behavioural data is stored locally on this device.",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "PACT does not request internet access at all: the permission is not in the app, " +
                        "so it cannot send your data anywhere even if it wanted to. There are no " +
                        "accounts, no analytics, no advertising, and no cloud backup. Nothing you " +
                        "record here leaves the phone unless you export it yourself.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        // --- Backup ---------------------------------------------------------------------

        Spacer(Modifier.height(20.dp))
        SectionHeader("Backup")
        Text(
            "Because there is no cloud, exporting is how you move your history to a new phone " +
                "or keep a copy of it.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { exportLauncher.launch(viewModel.suggestedFileName()) },
                enabled = !backup.exporting,
            ) {
                Text(if (backup.exporting) "Exporting..." else "Export data")
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                enabled = !backup.importing,
            ) {
                Text("Import data")
            }
        }

        backup.message?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = viewModel::dismissBackupMessage) { Text("OK") }
            }
        }

        backup.error?.let {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        it,
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = viewModel::dismissBackupMessage) { Text("OK") }
                }
            }
        }

        // --- About ----------------------------------------------------------------------

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        SectionHeader("About")
        Text("PACT, Personal Action and Commitment Tracker", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Success is not never missing. Success is returning to the behavior.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Built with AndroidX, Jetpack Compose, Room and kotlinx.serialization, all under the " +
                "Apache 2.0 licence.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(48.dp))
    }

    // Import must never silently overwrite. The user chooses merge or replace, explicitly.
    backup.pendingImport?.let { summary ->
        var mode by remember { mutableStateOf(BackupService.Mode.MERGE) }
        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text("Import this backup?") },
            text = {
                Column {
                    Text(
                        "${summary.goals} goals, ${summary.behaviors} behaviors and " +
                            "${summary.historyEntries} history entries.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(12.dp))
                    FilterChip(
                        selected = mode == BackupService.Mode.MERGE,
                        onClick = { mode = BackupService.Mode.MERGE },
                        label = { Text("Merge, keep what I have") },
                    )
                    Spacer(Modifier.height(4.dp))
                    FilterChip(
                        selected = mode == BackupService.Mode.REPLACE,
                        onClick = { mode = BackupService.Mode.REPLACE },
                        label = { Text("Replace everything") },
                    )
                    if (mode == BackupService.Mode.REPLACE) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This deletes all goals, behaviors and history currently on this device.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmImport(mode) }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelImport) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(if (ok) "✓" else "!", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(4.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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

/** Some OEMs do not implement every settings screen; a missing one must not crash the app. */
private fun android.content.Context.safeStart(intent: Intent) {
    runCatching {
        startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}
