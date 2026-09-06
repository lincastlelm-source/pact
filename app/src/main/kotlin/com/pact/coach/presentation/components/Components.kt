package com.pact.coach.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pact.coach.domain.model.InstanceState
import com.pact.coach.presentation.theme.LocalDecisionColors
import kotlin.math.roundToInt

/**
 * Shared UI pieces.
 *
 * Accessibility rule applied throughout: state is never communicated by colour alone. Every
 * status carries a symbol and a text label, and anything that is only an icon gets a
 * contentDescription.
 */

/** Symbol and label for an outcome. The symbol set matches the calendar legend in the docs. */
data class StateVisual(val symbol: String, val label: String, val color: Color)

@Composable
fun visualFor(state: InstanceState): StateVisual {
    val colors = LocalDecisionColors.current
    return when (state) {
        InstanceState.COMPLETED -> StateVisual("✓", "Completed", colors.completed)
        InstanceState.RECOVERED -> StateVisual("↻", "Moved later", colors.recovered)
        InstanceState.PASSED -> StateVisual("→", "Passed", colors.passed)
        InstanceState.MISSED -> StateVisual("!", "Missed", colors.missed)
        InstanceState.COMMITTED -> StateVisual("•", "In progress", colors.commit)
        InstanceState.DUE -> StateVisual("•", "Due now", colors.commit)
        InstanceState.SCHEDULED -> StateVisual("·", "Upcoming", colors.pending)
        InstanceState.CANCELLED -> StateVisual("–", "Cancelled", colors.pending)
    }
}

@Composable
fun StateChip(state: InstanceState, modifier: Modifier = Modifier) {
    val visual = visualFor(state)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(visual.color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = visual.label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = visual.symbol,
            color = visual.color,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = visual.label,
            color = visual.color,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The primary decision buttons. Deliberately large, clearly separated and distinct in both
 * colour and label so a half-awake user can answer in a second without misreading them.
 */
@Composable
fun DecisionButton(
    label: String,
    supporting: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        if (enabled) container else container.copy(alpha = 0.4f),
        label = "decision-bg",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 18.dp, horizontal = 20.dp)
            .semantics { contentDescription = "$label. $supporting" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = content,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodyLarge,
            color = content.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Card(
        modifier = modifier.semantics { contentDescription = "$label: $value" },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = accent)
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A rate shown as both a bar and "6 of 7", because a bare percentage hides how much evidence is
 * behind it. Two completions out of two is not the same as 40 out of 40.
 */
@Composable
fun RateBar(
    label: String,
    completed: Int,
    total: Int,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val fraction = if (total <= 0) 0f else completed.toFloat() / total
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "$label: $completed of $total, ${(fraction * 100).roundToInt()} percent"
            },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (total == 0) "-" else "$completed / $total",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** A coaching observation. Severity changes the accent but never the readability. */
@Composable
fun InsightCard(
    title: String,
    body: String,
    accent: Color,
    modifier: Modifier = Modifier,
    recommendation: String? = null,
    primaryAction: (@Composable () -> Unit)? = null,
    secondaryAction: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge)

            if (recommendation != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    recommendation,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (primaryAction != null || secondaryAction != null) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    primaryAction?.invoke()
                    secondaryAction?.invoke()
                }
            }
        }
    }
}

@Composable
fun LabeledRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Small pill used for categories, importance and similar metadata. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = tint,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
