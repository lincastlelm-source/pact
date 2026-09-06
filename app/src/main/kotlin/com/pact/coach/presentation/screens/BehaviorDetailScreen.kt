package com.pact.coach.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pact.coach.core.util.TimeFormat
import com.pact.coach.data.repository.AppSettings
import com.pact.coach.domain.coaching.MessageLibrary
import com.pact.coach.domain.model.ExceptionType
import com.pact.coach.domain.model.InsightSeverity
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.recurrence.RecurrenceEngine
import com.pact.coach.presentation.components.InsightCard
import com.pact.coach.presentation.components.RateBar
import com.pact.coach.presentation.components.SectionHeader
import com.pact.coach.presentation.components.StatTile
import com.pact.coach.presentation.components.StateChip
import com.pact.coach.presentation.components.Tag
import com.pact.coach.presentation.theme.LocalDecisionColors
import com.pact.coach.presentation.viewmodels.BehaviorDetailViewModel
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One behavior: its schedule, how it is going, and its full history including recoveries. */
@Composable
fun BehaviorDetailScreen(
    viewModel: BehaviorDetailViewModel,
    settings: AppSettings,
    onEdit: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val decision = LocalDecisionColors.current
    val behavior = state.behavior

    if (behavior == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("This behavior no longer exists.", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onBack) { Text("Back") }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(behavior.name, style = MaterialTheme.typography.headlineMedium)
            state.goalName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(behavior.measurement.name.lowercase().replaceFirstChar { it.uppercase() })
                Tag(behavior.enforcement.name.lowercase().replaceFirstChar { it.uppercase() })
                Tag(behavior.importance.name.lowercase().replaceFirstChar { it.uppercase() })
            }
        }

        item {
            state.schedules.forEach { schedule ->
                Text(
                    RecurrenceEngine.describe(schedule, settings.use24HourClock),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            behavior.minimumLabel()?.let {
                Text(
                    "Minimum: $it",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Active", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = behavior.isActive,
                    onCheckedChange = { viewModel.setActive(it) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onEdit(behavior.id) }) { Text("Edit") }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }

        state.stats?.let { stats ->
            item {
                SectionHeader("How it is going")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        MessageLibrary.percent(stats.completionRate),
                        "Completed",
                        Modifier.weight(1f),
                        decision.completed,
                    )
                    StatTile(
                        stats.currentStreak.toString(),
                        "Streak",
                        Modifier.weight(1f),
                    )
                    StatTile(
                        if (stats.chainsWithRecovery > 0) {
                            MessageLibrary.percent(stats.recoveryScore)
                        } else {
                            "-"
                        },
                        "Recovery",
                        Modifier.weight(1f),
                        decision.recovered,
                    )
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                RateBar("Completed", stats.completed, stats.resolvedChains, accent = decision.completed)
                Spacer(Modifier.height(8.dp))
                RateBar("Passed", stats.passed, stats.resolvedChains, accent = decision.passed)
                Spacer(Modifier.height(8.dp))
                RateBar("Missed", stats.missed, stats.resolvedChains, accent = decision.missed)

                if (stats.minimumCompletions > 0) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${stats.minimumCompletions} of ${stats.completed} completions were the minimum. " +
                            "That still counts.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                stats.averageResponseSeconds?.let { seconds ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You usually answer within ${TimeFormat.relative(-seconds).removeSuffix(" ago")}.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.insights.isNotEmpty()) {
            item { SectionHeader("Coach") }
            items(state.insights) { insight ->
                InsightCard(
                    title = insight.title,
                    body = insight.body,
                    recommendation = insight.recommendation,
                    accent = when (insight.severity) {
                        InsightSeverity.POSITIVE -> decision.completed
                        InsightSeverity.ATTENTION -> decision.missed
                        InsightSeverity.NEUTRAL -> decision.recovered
                    },
                )
            }
        }

        if (state.instructions.isNotEmpty()) {
            item { SectionHeader("Day plans") }
            items(state.instructions) { plan ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        plan.dayOfWeek?.let { iso ->
                            java.time.DayOfWeek.of(iso)
                                .getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
                        } ?: plan.date?.toString() ?: "Default",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        if (plan.isRestDay) "Rest day" else plan.title.ifBlank { plan.instructions },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        // Skips and pauses remove days from generation without editing the recurring plan.
        item {
            SectionHeader("Skip or pause")
            Text(
                "Take days off without changing your schedule. The recurring plan stays exactly " +
                    "as it is and picks up again afterwards.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val today = java.time.LocalDate.now()
                    viewModel.addException(ExceptionType.SKIP, today, today, "Skipped today")
                }) { Text("Skip today") }

                OutlinedButton(onClick = {
                    val tomorrow = java.time.LocalDate.now().plusDays(1)
                    viewModel.addException(ExceptionType.SKIP, tomorrow, tomorrow, "Skipped tomorrow")
                }) { Text("Skip tomorrow") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val today = java.time.LocalDate.now()
                    viewModel.addException(ExceptionType.PAUSE, today, today.plusDays(6), "Paused for a week")
                }) { Text("Pause a week") }

                OutlinedButton(onClick = {
                    val today = java.time.LocalDate.now()
                    viewModel.addException(ExceptionType.VACATION, today, today.plusDays(13), "Away")
                }) { Text("Away 2 weeks") }
            }
        }

        if (state.exceptions.isNotEmpty()) {
            items(state.exceptions, key = { it.id }) { exception ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            exception.type.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (exception.startDate == exception.endDate) {
                                exception.startDate.toString()
                            } else {
                                "${exception.startDate} to ${exception.endDate}"
                            } + if (exception.behaviorId == null) " (all behaviors)" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { viewModel.removeException(exception.id) }) {
                        Text("Remove")
                    }
                }
            }
        }

        state.message?.let { message ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                }
            }
        }

        item { SectionHeader("History") }

        if (state.history.isEmpty()) {
            item {
                Text(
                    "Nothing recorded yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.history, key = { it.id }) { instance ->
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            instance.scheduledDate.format(
                                DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row {
                            instance.scheduledTime?.let {
                                Text(
                                    TimeFormat.time(it, settings.use24HourClock),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (instance.isRecovery) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "moved from earlier",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = decision.recovered,
                                )
                            }
                        }
                        if (instance.note.isNotBlank()) {
                            Text(
                                instance.note,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    StateChip(instance.state)
                }

                // A late completion is still a completion. See the state machine.
                if (instance.state == InstanceState.MISSED || instance.state == InstanceState.PASSED) {
                    TextButton(onClick = { viewModel.completeHistoric(instance.id) }) {
                        Text("I actually did this")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }

        item { Spacer(Modifier.height(48.dp)) }
    }
}
