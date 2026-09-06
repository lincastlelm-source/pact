package com.pact.coach.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pact.coach.presentation.viewmodels.OnboardingViewModel

/**
 * First run.
 *
 * Kept to four short steps. The most important thing it does is set an expectation: start with
 * one or two behaviors, not a whole new life. An onboarding that encourages ten habits produces
 * a user who abandons all ten.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onCreateBehavior: (String) -> Unit,
    onFinished: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) { if (state.finished) onFinished() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(48.dp))

        when (state.step) {
            0 -> WelcomeStep(onNext = viewModel::next, onSkip = viewModel::skip)
            1 -> ConceptStep(onNext = viewModel::next, onBack = viewModel::back)
            2 -> GoalStep(state, viewModel)
            else -> BehaviorStep(
                state = state,
                onCreateBehavior = { state.createdGoal?.id?.let(onCreateBehavior) },
                onFinish = viewModel::finish,
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit, onSkip: () -> Unit) {
    Text("PACT", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Light)
    Spacer(Modifier.height(8.dp))
    Text(
        "A personal coach for the things you have decided to do.",
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.height(24.dp))
    Text(
        "PACT reminds you at the moment you planned, then asks one question: are you doing this " +
            "now, coming back to it later, or letting it go today?",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        "Everything stays on this phone. There is no account and no internet connection.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Skip setup") }
}

@Composable
private fun ConceptStep(onNext: () -> Unit, onBack: () -> Unit) {
    Text("How it works", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(20.dp))

    listOf(
        "GOAL" to "The reason. \"Improve my fitness.\"",
        "BEHAVIOR" to "What actually moves it. \"Exercise.\"",
        "SCHEDULE" to "When it happens. \"06:30 and 18:00, every day.\"",
        "DECISION" to "Commit, recover, or pass. Every reminder asks.",
    ).forEach { (title, body) ->
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(body, style = MaterialTheme.typography.bodyLarge)
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("The important part", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Missing something is not failure. Recovering is a real choice, tracked as its own " +
                    "outcome. The goal is not a perfect streak; it is coming back to the behavior.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

@Composable
private fun GoalStep(
    state: OnboardingViewModel.UiState,
    viewModel: OnboardingViewModel,
) {
    Text("Your first goal", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "One is plenty. You can add more once this one is working.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    OutlinedTextField(
        value = state.goalName,
        onValueChange = viewModel::setGoalName,
        label = { Text("What do you want to change?") },
        placeholder = { Text("Improve my physical fitness") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.goalWhy,
        onValueChange = viewModel::setGoalWhy,
        label = { Text("Why does it matter to you?") },
        supportingText = { Text("You will see this when a reminder arrives.") },
        modifier = Modifier.fillMaxWidth(),
    )

    state.error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { viewModel.createGoal {} },
        enabled = state.canContinueGoal,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Continue")
    }
    TextButton(onClick = viewModel::back, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

@Composable
private fun BehaviorStep(
    state: OnboardingViewModel.UiState,
    onCreateBehavior: () -> Unit,
    onFinish: () -> Unit,
) {
    Text("One behavior", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    state.createdGoal?.let {
        Text(
            "For: ${it.name}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "What is the one thing that would actually move this forward? Pick something small " +
            "enough that a bad day cannot stop it.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(16.dp))
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Do not try to change everything at once.",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "One or two behaviors that survive a month beat ten that last a week. " +
                    "On the next screen you can set a minimum action, which is what you fall back " +
                    "on when the day goes wrong.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    Button(onClick = onCreateBehavior, modifier = Modifier.fillMaxWidth()) {
        Text("Create my first behavior")
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
        Text("I will do this later")
    }
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "You can test your reminders any time from Settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
