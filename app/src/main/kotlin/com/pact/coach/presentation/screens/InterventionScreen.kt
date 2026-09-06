package com.pact.coach.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pact.coach.core.util.TimeFormat
import com.pact.coach.domain.model.EnforcementLevel
import com.pact.coach.domain.model.Measurement
import com.pact.coach.domain.model.PassReason
import com.pact.coach.domain.recovery.RecoveryEngine
import com.pact.coach.presentation.components.DecisionButton
import com.pact.coach.presentation.theme.LocalDecisionColors
import com.pact.coach.presentation.theme.accentForCategory
import com.pact.coach.presentation.viewmodels.InterventionViewModel

/**
 * The intervention.
 *
 * Design constraints this screen is built around:
 *  - The user may be half asleep. The three decisions are large, labelled, and colour-coded.
 *  - "Recover" is never framed as failure; it sits between commit and pass as a normal choice.
 *  - The minimum action is offered explicitly, so a hard day produces a small win.
 *  - Even a STRONG behavior has a visible way out. The app never traps the user.
 */
@Composable
fun InterventionScreen(
    viewModel: InterventionViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onClose()
    }

    when {
        state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

        state.missing -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("That reminder is no longer available.", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose) { Text("Close") }
            }
        }

        else -> InterventionContent(state, viewModel, onClose)
    }
}

@Composable
private fun InterventionContent(
    state: InterventionViewModel.UiState,
    viewModel: InterventionViewModel,
    onClose: () -> Unit,
) {
    val decision = LocalDecisionColors.current
    val behavior = state.behavior ?: return
    val instruction = state.instruction

    val accent = accentForCategory(behavior.category.ifBlank { state.goal?.category })

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // A coloured header band gives the screen a moment of presence before it asks for a
        // decision. It carries the behavior's category accent, so a 06:30 alarm looks different
        // from a 20:00 one and you know which is which before reading a word.
        Box(
            Modifier
                .fillMaxWidth()
                .background(accent.gradient())
                .padding(start = 24.dp, end = 24.dp, top = 46.dp, bottom = 22.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            accent.icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    state.instance?.scheduledTime?.let { time ->
                        Text(
                            TimeFormat.time(time, use24Hour = true),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = (instruction?.title ?: behavior.name).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Light,
                    color = Color.White,
                )

                if (state.recoveryCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Moved ${state.recoveryCount} time${if (state.recoveryCount > 1) "s" else ""} already.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.16f))
                            .padding(horizontal = 12.dp, vertical = 3.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Column(Modifier.padding(horizontal = 24.dp)) {

        state.goal?.let { goal ->
            DetailBlock("Goal", goal.name)
            if (goal.whyItMatters.isNotBlank()) {
                DetailBlock("Why it matters", goal.whyItMatters)
            }
        }

        if (!instruction?.instructions.isNullOrBlank()) {
            DetailBlock("Today's plan", instruction.instructions)
        }

        instruction?.durationMinutes?.let { DetailBlock("Planned", TimeFormat.duration(it)) }

        instruction?.quantityTarget?.let {
            DetailBlock("Target", behavior.formatQuantity(it))
        }

        state.minimumLabel?.let { DetailBlock("Minimum", it) }

        // Checklist behaviors get their items inline, so the user can tick as they go.
        if (behavior.measurement == Measurement.CHECKLIST && !instruction?.checklist.isNullOrEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Checklist", style = MaterialTheme.typography.labelLarge)
            instruction.checklist.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.ticks[item.id] == true,
                        onCheckedChange = { viewModel.toggleChecklistItem(item.id) },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        item.text + if (!item.isRequired) " (optional)" else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    error,
                    Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        when (state.stage) {
            InterventionViewModel.Stage.DECIDE -> DecideStage(state, viewModel)
            InterventionViewModel.Stage.IN_PROGRESS -> InProgressStage(state, viewModel)
            InterventionViewModel.Stage.CHOOSING_RECOVERY -> RecoveryStage(state, viewModel)
            InterventionViewModel.Stage.CHOOSING_PASS_REASON -> PassReasonStage(state, viewModel)
            InterventionViewModel.Stage.REFLECTING -> ReflectionStage(viewModel)
            InterventionViewModel.Stage.DONE -> {
                Text(state.coachMessage, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }

            Spacer(Modifier.height(24.dp))

            // Always present, including for STRONG. Autonomy is a product principle, not a setting.
            if (state.stage != InterventionViewModel.Stage.DONE) {
                TextButton(
                    onClick = { viewModel.leaveWithoutDeciding() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        if (behavior.enforcement == EnforcementLevel.STRONG) {
                            "Decide later"
                        } else {
                            "Close"
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DecideStage(
    state: InterventionViewModel.UiState,
    viewModel: InterventionViewModel,
) {
    val decision = LocalDecisionColors.current
    val behavior = state.behavior ?: return

    Text(
        state.coachMessage,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    DecisionButton(
        label = "COMMIT",
        supporting = "I am doing this now",
        container = decision.commit,
        content = decision.onCommit,
        onClick = { viewModel.commit() },
    )
    Spacer(Modifier.height(12.dp))

    if (behavior.recoveryEnabled) {
        DecisionButton(
            label = "RECOVER",
            supporting = "Not now, but I will come back to it",
            container = decision.recover,
            content = decision.onRecover,
            onClick = { viewModel.showRecoveryOptions() },
        )
        Spacer(Modifier.height(12.dp))
    }

    DecisionButton(
        label = "PASS",
        supporting = "I am choosing to skip this one",
        container = decision.pass,
        content = decision.onPass,
        onClick = {
            if (behavior.requirePassReason) viewModel.showPassReasons() else viewModel.pass(null)
        },
    )

    // The lower bar, offered up front rather than hidden behind a failure.
    state.minimumLabel?.let { minimum ->
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "You do not have to do the full session. Your minimum is $minimum.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = { viewModel.complete(minimumOnly = true) }) {
                    Text("Just do the minimum")
                }
            }
        }
    }
}

@Composable
private fun InProgressStage(
    state: InterventionViewModel.UiState,
    viewModel: InterventionViewModel,
) {
    val decision = LocalDecisionColors.current
    val behavior = state.behavior ?: return
    var quantity by remember { mutableStateOf("") }

    Text(state.coachMessage, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))

    if (behavior.measurement == Measurement.QUANTITY) {
        OutlinedTextField(
            value = quantity,
            onValueChange = { input ->
                quantity = input.filterIndexed { i, c -> c.isDigit() || (c == '.' && input.indexOf('.') == i) }
            },
            label = { Text("How much did you do?${if (behavior.quantityUnit.isNotBlank()) " (${behavior.quantityUnit})" else ""}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }

    DecisionButton(
        label = "COMPLETE",
        supporting = "I finished it",
        container = decision.commit,
        content = decision.onCommit,
        onClick = { viewModel.complete(quantity = quantity.toDoubleOrNull()) },
    )

    state.minimumLabel?.let { minimum ->
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { viewModel.complete(minimumOnly = true, quantity = quantity.toDoubleOrNull()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("I did the minimum ($minimum)")
        }
    }

    if (behavior.recoveryEnabled) {
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { viewModel.showRecoveryOptions() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Actually, move it later")
        }
    }
}

@Composable
private fun RecoveryStage(
    state: InterventionViewModel.UiState,
    viewModel: InterventionViewModel,
) {
    var custom by remember { mutableStateOf("") }

    Text("When will you come back to it?", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(4.dp))
    Text(
        "This is not a miss. The original time stays in your history and a new reminder is set.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    RecoveryEngine.QUICK_OPTIONS.chunked(3).forEach { row ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { minutes ->
                FilledTonalButton(
                    onClick = { viewModel.recover(minutes) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (minutes >= 60) "${minutes / 60} h" else "$minutes min")
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it.filter(Char::isDigit).take(4) },
            label = { Text("Custom (minutes)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { custom.toIntOrNull()?.let { viewModel.recover(it) } },
            enabled = custom.toIntOrNull() != null,
        ) {
            Text("Move")
        }
    }

    Spacer(Modifier.height(12.dp))
    TextButton(onClick = { viewModel.backToDecision() }) { Text("Back") }
}

@Composable
private fun PassReasonStage(
    state: InterventionViewModel.UiState,
    viewModel: InterventionViewModel,
) {
    var note by remember { mutableStateOf("") }

    Text("Why are you passing?", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(4.dp))
    Text(
        "Optional, and never judged. It just helps spot patterns later.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    PassReason.entries.chunked(2).forEach { row ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEach { reason ->
                OutlinedButton(
                    onClick = { viewModel.pass(reason, note) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(reason.label, textAlign = TextAlign.Center)
                }
            }
            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = note,
        onValueChange = { note = it.take(200) },
        label = { Text("Add a note (optional)") },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(12.dp))
    Row {
        TextButton(onClick = { viewModel.backToDecision() }) { Text("Back") }
        Spacer(Modifier.width(8.dp))
        if (state.behavior?.requirePassReason != true) {
            TextButton(onClick = { viewModel.pass(null, note) }) { Text("Skip without a reason") }
        }
    }
}

@Composable
private fun ReflectionStage(viewModel: InterventionViewModel) {
    var rating by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf("") }

    Text("How did it go?", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..5).forEach { value ->
            AssistChip(
                onClick = { rating = value },
                label = { Text("$value") },
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (rating == value) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        },
                    ),
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = note,
        onValueChange = { note = it.take(300) },
        label = { Text("Anything worth remembering?") },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { viewModel.saveReflection(rating, note) },
            modifier = Modifier.weight(1f),
        ) {
            Text("Save")
        }
        OutlinedButton(
            onClick = { viewModel.skipReflection() },
            modifier = Modifier.weight(1f),
        ) {
            Text("Skip")
        }
    }
}

@Composable
private fun DetailBlock(label: String, value: String) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
