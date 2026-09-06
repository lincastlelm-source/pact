package com.pact.coach.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pact.coach.core.util.TimeFormat
import com.pact.coach.data.repository.AppSettings
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.domain.model.Measurement
import com.pact.coach.presentation.components.HeroCard
import com.pact.coach.presentation.components.IconBadge
import com.pact.coach.presentation.components.ProgressRing
import com.pact.coach.presentation.components.RichEmptyState
import com.pact.coach.presentation.components.SectionHeader
import com.pact.coach.presentation.components.StateChip
import com.pact.coach.presentation.components.Tag
import com.pact.coach.presentation.theme.LocalDecisionColors
import com.pact.coach.presentation.theme.accentForCategory
import com.pact.coach.presentation.viewmodels.HomeViewModel
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The dashboard.
 *
 * It answers one question before anything else: what do I need to do now? Whatever is due sits
 * at the top with a single obvious action, and the rest of the day follows underneath.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    settings: AppSettings,
    onOpenIntervention: (String) -> Unit,
    onOpenBehavior: (String) -> Unit,
    onAddBehavior: () -> Unit,
    onSeeToday: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        floatingActionButton = {
            if (state.hasAnyBehavior) {
                ExtendedFloatingActionButton(
                    onClick = onAddBehavior,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Behavior") },
                )
            }
        },
    ) { padding ->
        if (!state.hasAnyBehavior && !state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                RichEmptyState(
                    icon = Icons.Outlined.WbSunny,
                    title = "Nothing scheduled yet",
                    body = "Start with one behavior. One is enough; you can always add more later.",
                    accent = MaterialTheme.colorScheme.primary,
                    action = { Button(onClick = onAddBehavior) { Text("Create a behavior") } },
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    state.today.format(
                        DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // NOW
            val focus = state.focus
            if (focus != null) {
                item {
                    SectionHeader("Now")
                    NowCard(
                        item = focus,
                        settings = settings,
                        onOpen = { onOpenIntervention(focus.instance.id) },
                    )
                }
            }

            if (state.overdue.isNotEmpty() && focus?.instance?.state != InstanceState.SCHEDULED) {
                item {
                    SectionHeader("Overdue")
                }
                items(state.overdue, key = { it.instance.id }) { item ->
                    TimelineRow(item, settings) { onOpenIntervention(item.instance.id) }
                }
            }

            // Untimed behaviors: no alarm, just a target for the day.
            if (state.flexible.isNotEmpty()) {
                item { SectionHeader("Anytime today") }
                items(state.flexible, key = { it.instance.id }) { item ->
                    FlexibleRow(
                        item = item,
                        onComplete = { viewModel.quickComplete(item.instance.id) },
                        onOpen = { onOpenIntervention(item.instance.id) },
                    )
                }
            }

            if (state.upcoming.isNotEmpty()) {
                item { SectionHeader("Upcoming") }
                items(state.upcoming.take(5), key = { it.instance.id }) { item ->
                    TimelineRow(item, settings) { onOpenBehavior(item.behavior.id) }
                }
                if (state.upcoming.size > 5) {
                    item {
                        TextButton(onClick = onSeeToday) {
                            Text("See the whole day")
                        }
                    }
                }
            }

            // A scheduling clash is worth flagging, but never fixed automatically.
            if (state.conflicts.isNotEmpty()) {
                item {
                    SectionHeader("Heads up")
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            state.conflicts.take(2).forEach { conflict ->
                                Text(
                                    conflict.message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Nothing has been changed. Adjust a schedule if you want to.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader("Today")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProgressRing(
                            fraction = if (state.scheduledCount == 0) 0f
                            else state.completedCount.toFloat() / state.scheduledCount,
                            label = "of today done",
                            size = 78.dp,
                            color = LocalDecisionColors.current.completed,
                            centerText = "${state.completedCount}/${state.scheduledCount}",
                        )
                        Spacer(Modifier.width(20.dp))
                        Column(Modifier.weight(1f)) {
                            TodayStat("Scheduled", state.scheduledCount, MaterialTheme.colorScheme.onSurface)
                            TodayStat("Completed", state.completedCount, LocalDecisionColors.current.completed)
                            if (state.recoveredCount > 0) {
                                TodayStat("Moved later", state.recoveredCount, LocalDecisionColors.current.recovered)
                            }
                            TodayStat("Remaining", state.remainingCount, LocalDecisionColors.current.pending)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

/** The Today timeline: the same day in strict chronological order. */
@Composable
fun TodayScreen(
    viewModel: HomeViewModel,
    settings: AppSettings,
    onOpenIntervention: (String) -> Unit,
    onOpenBehavior: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                state.today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${state.completedCount} of ${state.scheduledCount} done" +
                    if (state.recoveredCount > 0) ", ${state.recoveredCount} moved" else "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }

        if (state.timeline.isEmpty() && state.flexible.isEmpty()) {
            item {
                RichEmptyState(
                    icon = Icons.Outlined.Coffee,
                    title = "Nothing scheduled today",
                    body = "A quiet day is a valid day.",
                    accent = MaterialTheme.colorScheme.primary,
                )
            }
        }

        items(state.timeline, key = { it.instance.id }) { item ->
            TimelineRow(item, settings) {
                if (item.isOpen) onOpenIntervention(item.instance.id)
                else onOpenBehavior(item.behavior.id)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }

        if (state.flexible.isNotEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeader("Anytime")
            }
            items(state.flexible, key = { it.instance.id }) { item ->
                FlexibleRow(
                    item = item,
                    onComplete = { viewModel.quickComplete(item.instance.id) },
                    onOpen = { onOpenIntervention(item.instance.id) },
                )
            }
        }
    }
}

/**
 * The single most important element on the dashboard, and styled to look like it: a full-width
 * gradient panel in the behavior's category colour, with everything else on the screen
 * deliberately quieter.
 */
@Composable
private fun NowCard(
    item: HomeViewModel.DayItem,
    settings: AppSettings,
    onOpen: () -> Unit,
) {
    val accent = accentForCategory(item.behavior.category.ifBlank { item.goalName })
    val committed = item.instance.state == InstanceState.COMMITTED

    HeroCard(brush = accent.gradient(), contentColor = Color.White) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = accent.icon,
                tint = Color.White,
                background = Color.White.copy(alpha = 0.18f),
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (committed) "IN PROGRESS" else "DUE NOW",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.85f),
                )
                item.time?.let {
                    Text(
                        TimeFormat.time(it, settings.use24HourClock),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            item.behavior.name,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
        item.goalName?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
            )
        }

        // The minimum is worth surfacing before the user has even opened the intervention.
        item.behavior.minimumLabel()?.let { minimum ->
            Spacer(Modifier.height(10.dp))
            Text(
                "Minimum today: $minimum",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.16f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = accent.base,
            ),
        ) {
            Text(
                if (committed) "COMPLETE" else "RESPOND",
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** One "12 completed" line in the Today summary. Number and label, never a bare colour. */
@Composable
private fun TodayStat(label: String, value: Int, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            value.toString(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun TimelineRow(
    item: HomeViewModel.DayItem,
    settings: AppSettings,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.time?.let { TimeFormat.time(it, settings.use24HourClock) } ?: "--:--",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(if (settings.use24HourClock) 56.dp else 80.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.behavior.name, style = MaterialTheme.typography.bodyLarge)
            item.goalName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StateChip(item.instance.state)
    }
}

@Composable
private fun FlexibleRow(
    item: HomeViewModel.DayItem,
    onComplete: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(item.behavior.name, style = MaterialTheme.typography.bodyLarge)
            val target = when (item.behavior.measurement) {
                Measurement.QUANTITY -> item.behavior.targetLabel()
                Measurement.DURATION -> item.behavior.targetDurationMinutes?.let { TimeFormat.duration(it) }
                else -> null
            }
            if (target != null) {
                Tag(target, Modifier.padding(top = 4.dp))
            }
        }
        if (item.isOpen) {
            OutlinedButton(onClick = onComplete) { Text("Done") }
        } else {
            StateChip(item.instance.state)
        }
    }
}
