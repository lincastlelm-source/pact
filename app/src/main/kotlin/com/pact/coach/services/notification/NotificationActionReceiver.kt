package com.pact.coach.services.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pact.coach.PactApplication
import com.pact.coach.launchGuarded
import com.pact.coach.data.repository.InstanceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles COMMIT / RECOVER / PASS tapped directly on the notification.
 *
 * This is the low-friction path the product depends on: answering an intervention should cost
 * one tap from the lock screen, without unlocking the phone or waiting for an app to start.
 *
 * RECOVER from the notification uses the behavior's own default delay rather than opening a
 * picker, because a notification action cannot show one. The full intervention screen offers the
 * complete set of choices.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        val app = context.applicationContext as? PactApplication ?: return

        val pending = goAsync()
        app.applicationScope.launchGuarded(
            onError = { Log.e(TAG, "Notification action $action failed", it) },
            onFinally = { pending.finish() },
        ) {
            withContext(Dispatchers.IO) {
                handle(app, action, instanceId, notificationId)
            }
        }
    }

    private suspend fun handle(
        app: PactApplication,
        action: String,
        instanceId: String,
        notificationId: Int,
    ) {
        val container = app.container
        val instance = container.instanceRepository.get(instanceId) ?: return
        val behavior = container.behaviorRepository.get(instance.behaviorId) ?: return

        val result = when (action) {
            ACTION_COMMIT -> container.instanceRepository.commit(instanceId)

            ACTION_RECOVER -> container.instanceRepository.recover(
                instanceId,
                behavior.defaultRecoveryMinutes,
            )

            ACTION_PASS -> {
                // If this behavior insists on a reason, the notification cannot collect one, so
                // the intervention screen is opened instead of silently recording a bare pass.
                if (behavior.requirePassReason) {
                    openInterventionScreen(app, instanceId)
                    return
                }
                container.instanceRepository.pass(instanceId)
            }

            else -> return
        }

        when (result) {
            is InstanceRepository.DecisionResult.Ok -> {
                // Cancel the alarm and the notification for the row that was just answered.
                container.schedulingCoordinator.cancelInstance(instance)
                if (notificationId >= 0) container.notifier.cancelById(notificationId)
                container.notifier.cancel(instance)

                // A recovery creates a follow-up occurrence that needs its own alarm.
                result.newInstance?.let { container.schedulingCoordinator.armInstance(it) }
            }

            is InstanceRepository.DecisionResult.Failed -> {
                Log.i(TAG, "Notification action refused: ${result.reason}")
                if (notificationId >= 0) container.notifier.cancelById(notificationId)
            }
        }
    }

    private fun openInterventionScreen(app: PactApplication, instanceId: String) {
        val intent = Intent(app, com.pact.coach.MainActivity::class.java).apply {
            action = com.pact.coach.MainActivity.ACTION_SHOW_INTERVENTION
            putExtra(com.pact.coach.MainActivity.EXTRA_INSTANCE_ID, instanceId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        runCatching { app.startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not open the intervention screen", it) }
    }

    companion object {
        private const val TAG = "NotificationAction"

        const val ACTION_COMMIT = "com.pact.coach.action.COMMIT"
        const val ACTION_RECOVER = "com.pact.coach.action.RECOVER"
        const val ACTION_PASS = "com.pact.coach.action.PASS"

        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
