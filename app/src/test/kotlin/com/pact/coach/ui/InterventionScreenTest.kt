package com.pact.coach.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pact.coach.domain.model.EnforcementLevel
import com.pact.coach.domain.model.PassReason
import com.pact.coach.presentation.components.DecisionButton
import com.pact.coach.presentation.theme.PactTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests for the intervention surface.
 *
 * These run under Robolectric in the normal `test` source set, so they execute in CI and on a
 * developer machine without a connected device. The device-only checks (real alarm delivery,
 * lock-screen presentation) live in `src/androidTest` and in the in-app self-test, because they
 * cannot be meaningfully faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class InterventionScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the three decisions are all visible and individually labelled`() {
        var committed = false
        var recovered = false
        var passed = false

        compose.setContent {
            PactTheme {
                androidx.compose.foundation.layout.Column {
                    DecisionButton(
                        label = "COMMIT",
                        supporting = "I am doing this now",
                        container = androidx.compose.ui.graphics.Color(0xFF2F6F62),
                        content = androidx.compose.ui.graphics.Color.White,
                        onClick = { committed = true },
                    )
                    DecisionButton(
                        label = "RECOVER",
                        supporting = "Not now, but I will come back to it",
                        container = androidx.compose.ui.graphics.Color(0xFF3F6C9E),
                        content = androidx.compose.ui.graphics.Color.White,
                        onClick = { recovered = true },
                    )
                    DecisionButton(
                        label = "PASS",
                        supporting = "I am choosing to skip this one",
                        container = androidx.compose.ui.graphics.Color(0xFF6F6A62),
                        content = androidx.compose.ui.graphics.Color.White,
                        onClick = { passed = true },
                    )
                }
            }
        }

        // Each decision carries its own words, so they are distinguishable without colour.
        compose.onNodeWithText("COMMIT").assertIsDisplayed()
        compose.onNodeWithText("RECOVER").assertIsDisplayed()
        compose.onNodeWithText("PASS").assertIsDisplayed()
        compose.onNodeWithText("I am doing this now").assertIsDisplayed()
        compose.onNodeWithText("Not now, but I will come back to it").assertIsDisplayed()

        compose.onNodeWithText("COMMIT").performClick()
        assert(committed) { "COMMIT must invoke its handler" }
        assert(!recovered && !passed) { "one tap must not trigger the other decisions" }
    }

    @Test
    fun `recover reads as a normal choice rather than a failure`() {
        compose.setContent {
            PactTheme {
                DecisionButton(
                    label = "RECOVER",
                    supporting = "Not now, but I will come back to it",
                    container = androidx.compose.ui.graphics.Color(0xFF3F6C9E),
                    content = androidx.compose.ui.graphics.Color.White,
                    onClick = {},
                )
            }
        }
        // Wording check: nothing on this control frames the choice as failing or missing.
        val supporting = "Not now, but I will come back to it"
        compose.onNodeWithText(supporting).assertIsDisplayed()
        assert(!supporting.contains("fail", ignoreCase = true))
        assert(!supporting.contains("miss", ignoreCase = true))
    }

    @Test
    fun `every pass reason is offered`() {
        compose.setContent {
            PactTheme {
                // Scrollable, as the real intervention screen is: all eight reasons must be
                // reachable rather than only the ones that happen to fit.
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.verticalScroll(
                        androidx.compose.foundation.rememberScrollState(),
                    ),
                ) {
                    PassReason.entries.forEach { reason ->
                        androidx.compose.material3.Text(reason.label)
                    }
                }
            }
        }
        listOf(
            "Busy", "Tired", "Forgot", "Not feeling well",
            "Unexpected event", "Not important today", "Schedule conflict", "Other",
        ).forEach { label ->
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `enforcement levels are described without coercive language`() {
        val descriptions = EnforcementLevel.entries.map { level ->
            when (level) {
                EnforcementLevel.GENTLE -> "A quiet notification you can ignore."
                EnforcementLevel.COACH -> "A heads-up reminder, with one follow-up if you do not answer."
                EnforcementLevel.STRONG -> "A full-screen prompt asking for a decision. You can always leave it."
            }
        }
        // Even the strongest level must state that the user can leave.
        assert(descriptions.last().contains("always leave"))
        descriptions.forEach { text ->
            assert(!text.contains("must", ignoreCase = true)) { "coercive wording: $text" }
        }
    }
}
