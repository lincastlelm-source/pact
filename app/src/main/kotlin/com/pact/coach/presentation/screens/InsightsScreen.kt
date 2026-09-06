package com.pact.coach.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pact.coach.data.repository.AppSettings
import com.pact.coach.data.repository.DaySummary
import com.pact.coach.domain.coaching.MessageLibrary
import com.pact.coach.domain.model.InsightSeverity
import com.pact.coach.presentation.components.ProgressRing
import com.pact.coach.presentation.components.RichEmptyState
import com.pact.coach.presentation.components.InsightCard
import com.pact.coach.presentation.components.RateBar
import com.pact.coach.presentation.components.SectionHeader
import com.pact.coach.presentation.components.StatTile
import com.pact.coach.presentation.theme.LocalDecisionColors
import com.pact.coach.presentation.viewmodels.InsightsViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Statistics, the weekly review and the history calendar. */
@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel,
    settings: AppSettings,
    onOpenBehavior: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Review", "Calendar")

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(title) },
                )
            }
        }

        when (tab) {
            0 -> OverviewTab(state, onOpenBehavior, viewModel)
            1 -> ReviewTab(state, viewModel)
            else -> CalendarTab(state, settings, viewModel)
        }
    }
}

@Composable
private fun OverviewTab(
    state: InsightsViewModel.UiState,
    onOpenBehavior: (String) -> Unit,
    viewModel: InsightsViewModel,
) {
    val decision = LocalDecisionColors.current
    val overall = state.overall

    if (overall == null || overall.resolvedChains == 0) {
        RichEmptyState(
            icon = Icons.Outlined.Insights,
            title = "No history yet",
            body = "Once you have answered a few reminders, this is where the patterns show up.",
            accent = MaterialTheme.colorScheme.primary,
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // The two headline numbers, as rings rather than tiles. The empty part of a ring is a
            // gentler way to show a gap than a mostly-empty bar.
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ProgressRing(
                        fraction = overall.completionRate.toFloat(),
                        label = "Completed",
                        size = 92.dp,
                        stroke = 9.dp,
                        color = decision.completed,
                    )
                    ProgressRing(
                        fraction = overall.recoveryScore.toFloat(),
                        label = "Recovery score",
                        size = 92.dp,
                        stroke = 9.dp,
                        color = decision.recovered,
                        centerText = if (overall.chainsWithRecovery > 0) null else "-",
                    )
                }
            }
        }

        item {
            // The recovery score is the number the product actually wants people to care about.
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("What the recovery score means", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (overall.chainsWithRecovery > 0) {
                            "When you did not act at the planned time, you came back to it " +
                                "${MessageLibrary.percent(overall.recoveryScore)} of the time. " +
                                "Returning to a behavior matters more than never missing one."
                        } else {
                            "You have not needed to move anything yet. When you do, this shows how " +
                                "often you come back to it."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        item {
            SectionHeader("Outcomes")
            RateBar("Completed", overall.completed, overall.resolvedChains, accent = decision.completed)
            Spacer(Modifier.height(8.dp))
            RateBar("Passed", overall.passed, overall.resolvedChains, accent = decision.passed)
            Spacer(Modifier.height(8.dp))
            RateBar("Missed", overall.missed, overall.resolvedChains, accent = decision.missed)
        }

        if (state.weeklyTrend.isNotEmpty()) {
            item {
                SectionHeader("Recent weeks")
                Row(
                    Modifier.fillMaxWidth().height(90.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    state.weeklyTrend.forEach { (weekStart, rate) ->
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height((6 + rate * 60).dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(decision.completed.copy(alpha = 0.75f))
                                    .semantics {
                                        contentDescription =
                                            "Week of $weekStart: ${MessageLibrary.percent(rate)}"
                                    },
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${weekStart.dayOfMonth}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (state.dayOfWeek.isNotEmpty()) {
            item {
                SectionHeader("By day of week")
                DayOfWeek.entries.forEach { day ->
                    val rate = state.dayOfWeek[day]
                    if (rate != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                Modifier.width(48.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(rate.toFloat())
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(decision.completed),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                MessageLibrary.percent(rate),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }

        if (state.insights.isNotEmpty()) {
            item { SectionHeader("What the coach notices") }
            items(state.insights) { insight ->
                InsightCard(
                    title = insight.title,
                    body = insight.body,
                    recommendation = insight.recommendation,
                    accent = insight.severity.accent(),
                    primaryAction = if (insight.scheduleSuggestion != null) {
                        {
                            Button(onClick = { viewModel.applySuggestion(insight) }) {
                                Text("Apply change")
                            }
                        }
                    } else {
                        null
                    },
                    secondaryAction = if (insight.scheduleSuggestion != null) {
                        {
                            OutlinedButton(onClick = { viewModel.keepSchedule(insight) }) {
                                Text("Keep schedule")
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }

        if (state.perBehavior.isNotEmpty()) {
            item { SectionHeader("By behavior") }
            items(state.perBehavior) { (behavior, stats) ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenBehavior(behavior.id) }
                        .padding(vertical = 8.dp),
                ) {
                    RateBar(behavior.name, stats.completed, stats.resolvedChains)
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ReviewTab(state: InsightsViewModel.UiState, viewModel: InsightsViewModel) {
    val review = state.review
    val decision = LocalDecisionColors.current

    if (review == null || review.lines.isEmpty()) {
        RichEmptyState(
            icon = Icons.Outlined.EventNote,
            title = "No review yet",
            body = "The weekly review appears once there is a week of history to look at.",
            accent = MaterialTheme.colorScheme.primary,
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Weekly review", style = MaterialTheme.typography.headlineMedium)
            Text(
                "${review.weekStart} to ${review.weekEnd}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(review.lines) { line ->
            RateBar(line.behaviorName, line.completed, line.scheduled)
        }

        item {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(review.totalCompleted.toString(), "Completed", Modifier.weight(1f), decision.completed)
                StatTile(review.totalRecoveries.toString(), "Moved", Modifier.weight(1f), decision.recovered)
                StatTile(review.totalPassed.toString(), "Passed", Modifier.weight(1f), decision.passed)
                StatTile(review.totalMissed.toString(), "Missed", Modifier.weight(1f), decision.missed)
            }
        }

        if (review.insights.isNotEmpty()) {
            item { SectionHeader("Coach observation") }
            items(review.insights) { insight ->
                InsightCard(
                    title = insight.title,
                    body = insight.body,
                    recommendation = insight.recommendation,
                    accent = insight.severity.accent(),
                    primaryAction = if (insight.scheduleSuggestion != null) {
                        { Button(onClick = { viewModel.applySuggestion(insight) }) { Text("Apply change") } }
                    } else {
                        null
                    },
                    secondaryAction = if (insight.scheduleSuggestion != null) {
                        { OutlinedButton(onClick = { viewModel.keepSchedule(insight) }) { Text("Keep schedule") } }
                    } else {
                        null
                    },
                )
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

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun CalendarTab(
    state: InsightsViewModel.UiState,
    settings: AppSettings,
    viewModel: InsightsViewModel,
) {
    val month = state.calendarMonth
    val decision = LocalDecisionColors.current

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.showMonth(month.minusMonths(1)) }) { Text("Previous") }
            Text(
                "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { viewModel.showMonth(month.plusMonths(1)) }) { Text("Next") }
        }

        Spacer(Modifier.height(8.dp))

        val firstDay = settings.firstDayOfWeek
        val headers = (0..6).map { offset ->
            DayOfWeek.of(((firstDay.value - 1 + offset) % 7) + 1)
        }
        Row(Modifier.fillMaxWidth()) {
            headers.forEach { day ->
                Text(
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        val firstOfMonth = month.atDay(1)
        val leadingBlanks = (firstOfMonth.dayOfWeek.value - firstDay.value + 7) % 7
        val cells = buildList<LocalDate?> {
            repeat(leadingBlanks) { add(null) }
            (1..month.lengthOfMonth()).forEach { add(month.atDay(it)) }
        }

        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (date != null) {
                            DayCell(date, state.calendar[date])
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Legend")
        Column {
            LegendRow("✓", "Completed", decision.completed)
            LegendRow("↻", "Moved later", decision.recovered)
            LegendRow("→", "Passed", decision.passed)
            LegendRow("!", "Missed", decision.missed)
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, summary: DaySummary?) {
    val decision = LocalDecisionColors.current

    // Colour indicates the dominant outcome; the number keeps the cell readable without colour.
    val (background, symbol) = when {
        summary == null || summary.isEmpty -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f) to ""
        summary.completed == summary.total -> decision.completed.copy(alpha = 0.7f) to "✓"
        summary.missed > 0 -> decision.missed.copy(alpha = 0.5f) to "!"
        summary.recovered > 0 -> decision.recovered.copy(alpha = 0.5f) to "↻"
        summary.passed > 0 -> decision.passed.copy(alpha = 0.4f) to "→"
        summary.completed > 0 -> decision.completed.copy(alpha = 0.35f) to "✓"
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) to ""
    }

    val label = if (summary == null || summary.isEmpty) {
        "${date.dayOfMonth}: nothing scheduled"
    } else {
        "${date.dayOfMonth}: ${summary.completed} of ${summary.total} completed"
    }

    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${date.dayOfMonth}", style = MaterialTheme.typography.labelLarge)
            if (symbol.isNotEmpty()) {
                Text(symbol, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun LegendRow(symbol: String, label: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(symbol, color = color, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InsightSeverity.accent(): androidx.compose.ui.graphics.Color {
    val decision = LocalDecisionColors.current
    return when (this) {
        InsightSeverity.POSITIVE -> decision.completed
        InsightSeverity.ATTENTION -> decision.missed
        InsightSeverity.NEUTRAL -> decision.recovered
    }
}
