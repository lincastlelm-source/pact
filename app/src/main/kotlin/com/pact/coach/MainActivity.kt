package com.pact.coach

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.pact.coach.data.repository.AppSettings
import com.pact.coach.presentation.navigation.PactApp
import com.pact.coach.presentation.theme.PactTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The single activity.
 *
 * It hosts the whole Compose navigation graph and, when launched from a notification, routes
 * straight to the intervention screen. For a STRONG behavior it also turns the screen on and
 * shows over the lock screen, so an alarm the user asked to be insistent actually is.
 */
class MainActivity : ComponentActivity() {

    private val container by lazy { (application as PactApplication).container }

    /**
     * Notification permission (Android 13+). Asked once, on first launch, and never nagged: if
     * the user says no the app keeps working and the Help screen explains what they will miss.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* either way, carry on */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        applyLockScreenFlags(intent)

        val settingsFlow = container.settingsRepository.settings.stateIn(
            scope = lifecycleScope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettings(),
        )

        var pendingInterventionId by mutableStateOf(intent.interventionId())

        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) {
                requestNotificationPermissionIfNeeded()
            }

            PactTheme(themeMode = settings.themeMode) {
                PactApp(
                    container = container,
                    settings = settings,
                    startInterventionId = pendingInterventionId,
                    onInterventionConsumed = { pendingInterventionId = null },
                )
            }
        }

        // Coming back to the foreground is a good moment to catch up on anything the process
        // missed while it was not running.
        lifecycleScope.launch {
            runCatching { container.schedulingCoordinator.refresh() }
        }
    }

    /** singleTask, so a notification tap while the app is open arrives here rather than onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLockScreenFlags(intent)
        recreate()
    }

    /**
     * Shows the intervention over the lock screen for full-screen alarms. Guarded so the flags
     * are only ever set for an actual intervention launch, never for a normal app open.
     */
    private fun applyLockScreenFlags(intent: Intent?) {
        val fullScreen = intent?.getBooleanExtra(EXTRA_FULL_SCREEN, false) == true
        if (!fullScreen) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun Intent.interventionId(): String? =
        if (action == ACTION_SHOW_INTERVENTION) {
            getStringExtra(EXTRA_INSTANCE_ID) ?: data?.lastPathSegment
        } else {
            null
        }

    companion object {
        const val ACTION_SHOW_INTERVENTION = "com.pact.coach.action.SHOW_INTERVENTION"
        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_FULL_SCREEN = "full_screen"
    }
}
