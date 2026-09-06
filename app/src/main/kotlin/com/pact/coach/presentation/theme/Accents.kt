package com.pact.coach.presentation.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Visual identity for a category.
 *
 * Colour here is decoration, never information: every place an accent appears, the category name
 * or an icon appears with it. Nothing in the app requires the user to distinguish two hues to
 * understand what they are looking at.
 *
 * The palette is deliberately desaturated and sits alongside the sage primary rather than
 * competing with it. A behavioural coach should feel calm, not like a children's game.
 */
data class Accent(
    val base: Color,
    val soft: Color,
    val onSoft: Color,
    val icon: ImageVector,
) {
    /** Gentle top-left to bottom-right wash used on hero and goal cards. */
    fun gradient(): Brush = Brush.linearGradient(
        listOf(base, base.blend(Color.Black, 0.18f)),
    )

    fun softGradient(): Brush = Brush.linearGradient(
        listOf(soft, soft.blend(base, 0.16f)),
    )
}

/** Simple linear blend; avoids pulling in a colour-space dependency for what is decoration. */
fun Color.blend(other: Color, amount: Float): Color = Color(
    red = red + (other.red - red) * amount,
    green = green + (other.green - green) * amount,
    blue = blue + (other.blue - blue) * amount,
    alpha = alpha,
)

private val Health = Accent(Color(0xFF2F6F62), Color(0xFFD7ECE5), Color(0xFF10382F), Icons.Outlined.Favorite)
private val Fitness = Accent(Color(0xFF3E7CA6), Color(0xFFD8E8F2), Color(0xFF12313F), Icons.Outlined.DirectionsRun)
private val Learning = Accent(Color(0xFF7A5EA7), Color(0xFFE7DFF4), Color(0xFF2B1F3F), Icons.Outlined.MenuBook)
private val Career = Accent(Color(0xFF3F6C9E), Color(0xFFDCE7F4), Color(0xFF14293D), Icons.Outlined.WorkOutline)
private val Productivity = Accent(Color(0xFF9C6644), Color(0xFFF3E2D6), Color(0xFF3B2113), Icons.Outlined.TrendingUp)
private val Personal = Accent(Color(0xFF5F7A5A), Color(0xFFE0EADC), Color(0xFF1F2C1C), Icons.Outlined.Person)
private val Finance = Accent(Color(0xFF4E7268), Color(0xFFDCEAE5), Color(0xFF17302A), Icons.Outlined.AccountBalance)
private val Relationships = Accent(Color(0xFFA85F6B), Color(0xFFF5DFE3), Color(0xFF3D1A20), Icons.Outlined.Group)
private val Spiritual = Accent(Color(0xFF6B6390), Color(0xFFE3E0F0), Color(0xFF241F38), Icons.Outlined.SelfImprovement)
private val Fallback = Accent(Color(0xFF6E7A76), Color(0xFFE3E7E5), Color(0xFF232A28), Icons.Filled.AutoAwesome)

/**
 * Resolves a free-text category to an accent. Categories are user-editable, so anything
 * unrecognised gets a stable neutral accent chosen from the name rather than an error.
 */
@Composable
fun accentFor(category: String?): Accent = accentForCategory(category)

fun accentForCategory(category: String?): Accent {
    val key = category?.trim()?.lowercase().orEmpty()
    return when {
        key.isEmpty() -> Fallback
        key.startsWith("health") -> Health
        key.startsWith("fit") || key.contains("exercise") || key.contains("sport") -> Fitness
        key.startsWith("learn") || key.contains("study") || key.contains("read") -> Learning
        key.startsWith("career") || key.contains("work") || key.contains("job") -> Career
        key.startsWith("produc") -> Productivity
        key.startsWith("person") -> Personal
        key.startsWith("financ") || key.contains("money") -> Finance
        key.startsWith("relation") || key.contains("family") || key.contains("friend") -> Relationships
        key.startsWith("spirit") || key.contains("faith") || key.contains("medit") -> Spiritual
        else -> {
            // Deterministic pick so a custom category keeps the same colour every time.
            val palette = listOf(Health, Fitness, Learning, Career, Productivity, Personal, Finance, Relationships, Spiritual)
            palette[(key.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % palette.size]
        }
    }
}
