package com.pact.coach.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pact.coach.domain.model.ThemeMode

/**
 * A calm, low-contrast palette built around a muted green. The product is meant to read as a
 * personal coach rather than a corporate task manager, so there is no alarm red anywhere in the
 * primary surface, and the decision colours are distinguishable by shape and label as well as by
 * hue for anyone who cannot rely on colour alone.
 */

private val Sage = Color(0xFF2F6F62)
private val SageLight = Color(0xFF7FBCAC)
private val Clay = Color(0xFF9C6644)
private val Sand = Color(0xFFE8DFD3)

private val LightColors = lightColorScheme(
    primary = Sage,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8E5D8),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Clay,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7DDCD),
    onSecondaryContainer = Color(0xFF33150A),
    tertiary = Color(0xFF4A6572),
    onTertiary = Color.White,
    background = Color(0xFFFCFAF7),
    onBackground = Color(0xFF1A1C1B),
    surface = Color(0xFFFCFAF7),
    onSurface = Color(0xFF1A1C1B),
    surfaceVariant = Sand,
    onSurfaceVariant = Color(0xFF4A4640),
    outline = Color(0xFF7C7973),
    error = Color(0xFFA33A2C),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF410001),
)

private val DarkColors = darkColorScheme(
    primary = SageLight,
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF1F5145),
    onPrimaryContainer = Color(0xFFB8E5D8),
    secondary = Color(0xFFE7B894),
    onSecondary = Color(0xFF4A2711),
    secondaryContainer = Color(0xFF663D22),
    onSecondaryContainer = Color(0xFFF7DDCD),
    tertiary = Color(0xFFB2CBDA),
    onTertiary = Color(0xFF1C3541),
    background = Color(0xFF12100E),
    onBackground = Color(0xFFE6E2DC),
    surface = Color(0xFF12100E),
    onSurface = Color(0xFFE6E2DC),
    surfaceVariant = Color(0xFF3A3733),
    onSurfaceVariant = Color(0xFFCDC7BE),
    outline = Color(0xFF97918A),
    error = Color(0xFFFFB4A6),
    onError = Color(0xFF690002),
    errorContainer = Color(0xFF930006),
    onErrorContainer = Color(0xFFFFDAD4),
)

/**
 * Semantic colours for the three decisions and the outcome states. Held outside the Material
 * scheme because they carry meaning that must survive a dynamic-colour palette.
 */
data class DecisionColors(
    val commit: Color,
    val onCommit: Color,
    val recover: Color,
    val onRecover: Color,
    val pass: Color,
    val onPass: Color,
    val completed: Color,
    val recovered: Color,
    val passed: Color,
    val missed: Color,
    val pending: Color,
)

private val LightDecisions = DecisionColors(
    commit = Color(0xFF2F6F62), onCommit = Color.White,
    recover = Color(0xFF3F6C9E), onRecover = Color.White,
    pass = Color(0xFF6F6A62), onPass = Color.White,
    completed = Color(0xFF2F6F62),
    recovered = Color(0xFF3F6C9E),
    passed = Color(0xFF8A8579),
    missed = Color(0xFFA36A4A),
    pending = Color(0xFF9A968E),
)

private val DarkDecisions = DecisionColors(
    commit = Color(0xFF7FBCAC), onCommit = Color(0xFF00291F),
    recover = Color(0xFF9BC0E8), onRecover = Color(0xFF10293F),
    pass = Color(0xFFB6B0A6), onPass = Color(0xFF25231F),
    completed = Color(0xFF7FBCAC),
    recovered = Color(0xFF9BC0E8),
    passed = Color(0xFFA8A296),
    missed = Color(0xFFD9A183),
    pending = Color(0xFF7C7871),
)

val LocalDecisionColors = staticCompositionLocalOf { LightDecisions }

private val PactTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Light,
        fontSize = 34.sp,
        lineHeight = 42.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 21.sp,
        lineHeight = 28.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun PactTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You. Off by default so the calm palette is what most users see. */
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(
        LocalDecisionColors provides if (darkTheme) DarkDecisions else LightDecisions,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PactTypography,
            content = content,
        )
    }
}
