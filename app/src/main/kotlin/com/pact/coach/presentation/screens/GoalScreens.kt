package com.pact.coach.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pact.coach.data.repository.GoalWithBehaviors
import com.pact.coach.domain.coaching.MessageLibrary
import com.pact.coach.domain.model.GoalStatus
import com.pact.coach.domain.model.Priority
import com.pact.coach.presentation.components.AccentCard
import com.pact.coach.presentation.components.IconBadge
import com.pact.coach.presentation.components.ProgressRing
import com.pact.coach.presentation.components.RichEmptyState
import com.pact.coach.presentation.components.SectionHeader
import com.pact.coach.presentation.components.Tag
import com.pact.coach.presentation.theme.accentForCategory
import com.pact.coach.presentation.viewmodels.GoalEditViewModel
import com.pact.coach.presentation.viewmodels.GoalsViewModel

/**
 * Goals, each with the behaviors that actually move it forward.
 *
 * Every goal card carries its category accent, an icon, and a completion ring, so the list reads at
 * a glance without becoming a scoreboard. The overflow menu is where a goal's life-cycle lives:
 * mark it achieved, pause it, archive it, or delete it outright.
 */
@Composable
fun GoalsScreen(
    viewModel: GoalsViewModel,
    onAddGoal: () -> Unit,
    onEditGoal: (String) -> Unit,
    onAddBehavior: (String?) -> Unit,
    onOpenBehavior: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<GoalWithBehaviors?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshStats() }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddGoal,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Goal") },
            )
        },
    ) { padding ->
        if (state.goals.isEmpty() && !state.isLoading) {
            RichEmptyState(
                icon = Icons.Outlined.Flag,
                title = "No goals yet",
                body = "A goal is the reason behind the behaviors. Start with one that matters to you.",
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(padding),
                action = { Button(onClick = onAddGoal) { Text("Create a goal") } },
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(state.goals, key = { it.goal.id }) { entry ->
                GoalCard(
                    entry = entry,
                    completed = entry.behaviors.sumOf { state.statsByBehavior[it.id]?.completed ?: 0 },
                    total = entry.behaviors.sumOf { state.statsByBehavior[it.id]?.resolvedChains ?: 0 },
                    rateFor = { id -> state.statsByBehavior[id]?.completionRate },
                    onEdit = { onEditGoal(entry.goal.id) },
                    onAddBehavior = { onAddBehavior(entry.goal.id) },
                    onOpenBehavior = onOpenBehavior,
                    onStatus = { viewModel.setStatus(entry.goal.id, it) },
                    onDelete = { pendingDelete = entry },
                )
            }

            if (state.unassignedCount > 0) {
                item {
                    Text(
                        "${state.unassignedCount} behavior${if (state.unassignedCount > 1) "s are" else " is"} " +
                            "not linked to a goal.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    // Deleting a goal is not the same as deleting the work under it, and the dialog says so
    // explicitly rather than relying on the user to guess.
    pendingDelete?.let { entry ->
        val count = entry.behaviors.size
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text("Delete \"${entry.goal.name}\"?") },
            text = {
                Text(
                    if (count == 0) {
                        "The goal will be removed. This cannot be undone."
                    } else {
                        "The goal will be removed. Its $count behavior${if (count > 1) "s" else ""} " +
                            "and all of their history are kept, and will simply no longer be linked " +
                            "to a goal.\n\nIf you have finished this goal, \"Mark as achieved\" keeps " +
                            "it in your records instead."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(entry.goal.id)
                    pendingDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun GoalCard(
    entry: GoalWithBehaviors,
    completed: Int,
    total: Int,
    rateFor: (String) -> Double?,
    onEdit: () -> Unit,
    onAddBehavior: () -> Unit,
    onOpenBehavior: (String) -> Unit,
    onStatus: (GoalStatus) -> Unit,
    onDelete: () -> Unit,
) {
    val accent = accentForCategory(entry.goal.category)
    val achieved = entry.goal.status == GoalStatus.COMPLETED
    var menuOpen by remember { mutableStateOf(false) }

    AccentCard(accentColor = accent.base, onClick = onEdit) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    icon = if (achieved) Icons.Default.CheckCircle else accent.icon,
                    tint = accent.base,
                    background = accent.soft,
                    contentDescription = entry.goal.category,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.goal.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (achieved) TextDecoration.LineThrough else null,
                    )
                    Text(
                        entry.goal.category,
                        style = MaterialTheme.typography.labelLarge,
                        color = accent.base,
                    )
                }

                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options for ${entry.goal.name}")
                    }
                    GoalMenu(
                        expanded = menuOpen,
                        status = entry.goal.status,
                        onDismiss = { menuOpen = false },
                        onStatus = { menuOpen = false; onStatus(it) },
                        onDelete = { menuOpen = false; onDelete() },
                    )
                }
            }

            if (entry.goal.whyItMatters.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    entry.goal.whyItMatters,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (total > 0) {
                    ProgressRing(
                        fraction = completed.toFloat() / total,
                        label = "$completed of $total",
                        size = 64.dp,
                        stroke = 6.dp,
                        color = accent.base,
                    )
                    Spacer(Modifier.width(16.dp))
                }

                Column(Modifier.weight(1f)) {
                    if (entry.behaviors.isEmpty()) {
                        Text(
                            "No behaviors yet. What will actually move this forward?",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        entry.behaviors.forEach { behavior ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenBehavior(behavior.id) }
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (behavior.isActive) accent.base
                                            else MaterialTheme.colorScheme.outline,
                                        ),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    behavior.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                rateFor(behavior.id)?.let { rate ->
                                    Text(
                                        MessageLibrary.percent(rate),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!behavior.isActive) {
                                    Spacer(Modifier.width(8.dp))
                                    Tag("Paused")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAddBehavior) { Text("Add a behavior") }
                Spacer(Modifier.weight(1f))
                if (entry.goal.status != GoalStatus.ACTIVE) {
                    Tag(
                        entry.goal.status.name.lowercase().replaceFirstChar { it.uppercase() },
                        color = accent.base,
                    )
                }
            }
        }
    }
}

/** The life-cycle menu. This is where a finished goal finally gets to leave the list. */
@Composable
private fun GoalMenu(
    expanded: Boolean,
    status: GoalStatus,
    onDismiss: () -> Unit,
    onStatus: (GoalStatus) -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (status != GoalStatus.COMPLETED) {
            DropdownMenuItem(
                text = { Text("Mark as achieved") },
                leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                onClick = { onStatus(GoalStatus.COMPLETED) },
            )
        }
        if (status == GoalStatus.ACTIVE) {
            DropdownMenuItem(
                text = { Text("Pause") },
                leadingIcon = { Icon(Icons.Outlined.PauseCircleOutline, contentDescription = null) },
                onClick = { onStatus(GoalStatus.PAUSED) },
            )
        } else {
            DropdownMenuItem(
                text = { Text("Make active again") },
                leadingIcon = { Icon(Icons.Outlined.PlayCircleOutline, contentDescription = null) },
                onClick = { onStatus(GoalStatus.ACTIVE) },
            )
        }
        if (status != GoalStatus.ARCHIVED) {
            DropdownMenuItem(
                text = { Text("Archive") },
                leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                onClick = { onStatus(GoalStatus.ARCHIVED) },
            )
        }
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onDelete,
        )
    }
}

/** Create or edit a goal. Deliberately short: name, why, category, priority. */
@Composable
fun GoalEditScreen(
    viewModel: GoalEditViewModel,
    onDone: () -> Unit,
    onAddBehavior: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val accent = accentForCategory(state.category)

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = accent.icon, tint = accent.base, background = accent.soft, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                if (state.isEditing) "Edit goal" else "New goal",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text("What is the goal?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.whyItMatters,
            onValueChange = viewModel::setWhy,
            label = { Text("Why does it matter?") },
            supportingText = { Text("This is shown when a reminder fires, so make it honest.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.description,
            onValueChange = viewModel::setDescription,
            label = { Text("Notes (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        SectionHeader("Category")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.suggestedCategories.forEach { category ->
                FilterChip(
                    selected = state.category == category,
                    onClick = { viewModel.setCategory(category) },
                    label = { Text(category) },
                    leadingIcon = if (state.category == category) {
                        { Icon(accentForCategory(category).icon, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.category,
            onValueChange = viewModel::setCategory,
            label = { Text("Or write your own") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        SectionHeader("Priority")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Priority.entries.forEach { priority ->
                FilterChip(
                    selected = state.priority == priority,
                    onClick = { viewModel.setPriority(priority) },
                    label = { Text(priority.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        if (state.isEditing) {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Status")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalStatus.entries.forEach { status ->
                    FilterChip(
                        selected = state.status == status,
                        onClick = { viewModel.setStatus(status) },
                        label = { Text(status.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Text(
                when (state.status) {
                    GoalStatus.ACTIVE -> "Working on it now."
                    GoalStatus.PAUSED -> "Set aside for the moment. Nothing is deleted."
                    GoalStatus.COMPLETED -> "Achieved. It stays in your records."
                    GoalStatus.ARCHIVED -> "Filed away and out of the main list."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = viewModel::save,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isEditing) "Save" else "Create goal")
        }

        if (state.isEditing && state.id != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onAddBehavior(state.id!!) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add a behavior to this goal")
            }

            if (state.status != GoalStatus.COMPLETED) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.markAchieved() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Mark as achieved")
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete goal", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        Spacer(Modifier.height(32.dp))
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text("Delete this goal?") },
            text = {
                Text(
                    "Any behaviors under it are kept, along with all of their history, and will " +
                        "simply no longer be linked to a goal.\n\nIf you have finished this goal, " +
                        "\"Mark as achieved\" keeps it in your records instead.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Keep it") }
            },
        )
    }
}
