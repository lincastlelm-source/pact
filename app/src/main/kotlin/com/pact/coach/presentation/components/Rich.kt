package com.pact.coach.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The expressive half of the design system.
 *
 * [Components.kt] holds the plain, functional pieces; this file holds the ones that give the app
 * some warmth — rings, gradients, accent cards. Two rules apply to everything here:
 *
 *  1. Decoration never carries meaning on its own. Every ring has its number beside it, every
 *     accent colour sits next to a label or icon.
 *  2. Nothing celebrates in a way that would sting on a bad day. There are no confetti bursts and
 *     no trophies, because the same screen has to be bearable when the number is low.
 */

/**
 * A circular progress indicator with the value written in the middle.
 *
 * Used instead of a bar for headline figures, where the shape reads faster than a percentage and
 * the empty portion of the ring is a gentler way to show a gap than a mostly-empty bar.
 */
@Composable
fun ProgressRing(
    fraction: Float,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 74.dp,
    stroke: Dp = 7.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    centerText: String? = null,
) {
    val target = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 650),
        label = "ring",
    )
    val text = centerText ?: "${(target * 100).roundToInt()}%"

    Column(
        modifier = modifier.semantics { contentDescription = "$label: $text" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = stroke.toPx() / 2
                val arcSize = Size(this.size.width - stroke.toPx(), this.size.height - stroke.toPx())
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round),
                )
                if (animated > 0f) {
                    drawArc(
                        color = color,
                        // Start at twelve o'clock rather than three, which reads as "progress".
                        startAngle = -90f,
                        sweepAngle = 360f * animated,
                        useCenter = false,
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The dashboard's "now" card: a full-bleed gradient panel that is unmistakably the thing to look
 * at first. Everything else on the Home screen is deliberately quieter than this.
 */
@Composable
fun HeroCard(
    brush: Brush,
    contentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        shadowElevation = 6.dp,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(brush),
        ) {
            // A soft highlight bleeding off the top-right corner stops the flat gradient from
            // reading as a plain coloured block.
            Box(
                Modifier
                    .size(170.dp)
                    .align(Alignment.TopEnd)
                    .background(
                        Brush.radialGradient(
                            listOf(contentColor.copy(alpha = 0.14f), Color.Transparent),
                        ),
                        CircleShape,
                    ),
            )
            Column(Modifier.padding(20.dp), content = content)
        }
    }
}

/**
 * A card with a coloured spine down its left edge. Used for goals and insights, where a glanceable
 * category is genuinely useful.
 *
 * The Row is measured at its minimum intrinsic height so the spine stretches to match whatever the
 * content ends up being, without either side having to know the other's height.
 */
@Composable
fun AccentCard(
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(accentColor),
            )
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/** Circular icon badge on a tinted background. */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    contentDescription: String? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/**
 * A small horizontal strip of the last N days, so a behavior card can show its recent shape
 * without a chart. Each segment carries a tooltip-style content description.
 */
@Composable
fun MiniTrend(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 26.dp,
) {
    if (values.isEmpty()) return
    Row(
        modifier = modifier.height(height),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEach { value ->
            val v = value.coerceIn(0f, 1f)
            Box(
                Modifier
                    .weight(1f)
                    .height(height * (0.22f + 0.78f * v))
                    .clip(RoundedCornerShape(3.dp))
                    .background(color.copy(alpha = 0.35f + 0.55f * v)),
            )
        }
    }
}

/**
 * Empty states with a little presence. A blank screen with one grey sentence is the fastest way to
 * make an app feel unfinished, and the first-run screen is exactly where that matters most.
 */
@Composable
fun RichEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    accent: Color,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.18f), accent.copy(alpha = 0.06f)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(20.dp))
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
            Spacer(Modifier.height(22.dp))
            action()
        }
    }
}
