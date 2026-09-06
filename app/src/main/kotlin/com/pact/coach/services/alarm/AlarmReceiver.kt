package com.pact.coach.services.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pact.coach.PactApplication
import com.pact.coach.launchGuarded
import com.pact.coach.domain.model.EnforcementLevel
import com.pact.coach.domain.model.InstanceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Receives the alarm and turns it into an intervention.
 *
 * A BroadcastReceiver has roughly ten seconds of foreground time, and its process can be killed
 * the moment [onReceive] returns. All the database work therefore happens inside
 * [goAsync], which keeps the process alive until the work finishes.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
        if (instanceId.isNullOrBlank()) {
            Log.w(TAG, "Alarm fired without an instance id")
            return
        }
        val kind = runCatching {
            AlarmScheduler.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "MAIN")
        }.getOrDefault(AlarmScheduler.Kind.MAIN)

        val app = context.applicationContext as? PactApplication ?: run {
            Log.e(TAG, "Application context unavailable")
            return
        }

        val pending = goAsync()
        app.applicationScope.launchGuarded(
            onError = { Log.e(TAG, "Alarm handling failed for $instanceId", it) },
            onFinally = { pending.finish() },
        ) {
            handle(app, instanceId, kind)
        }
    }

    private suspend fun handle(
        app: PactApplication,
        instanceId: String,
        kind: AlarmScheduler.Kind,
    ) = withContext(Dispatchers.IO) {
        val container = app.container
        val instance = container.instanceRepository.get(instanceId) ?: run {
            // The behavior was deleted, or history was replaced by an import, while the alarm
            // was still armed. Nothing to do; the alarm simply expires.
            Log.i(TAG, "Alarm fired for an instance that no longer exists: $instanceId")
            return@withContext
        }

        val behavior = container.behaviorRepository.get(instance.behaviorId) ?: run {
            Log.i(TAG, "Alarm fired for a deleted behavior: ${instance.behaviorId}")
            return@withContext
        }

        // The user may have paused the behavior, or already answered from the notification,
        // after the alarm was armed.
        if (!behavior.isActive || !behavior.reminderEnabled) return@withContext
        if (instance.state.isTerminal) return@withContext

        val settings = container.currentSettings()

        when (kind) {
            AlarmScheduler.Kind.PRE_ALERT -> {
                val minutes = behavior.preAlertMinutes ?: return@withContext
                container.notifier.postPreAlert(instance, behavior, minutes)
            }

            AlarmScheduler.Kind.MAIN, AlarmScheduler.Kind.ESCALATION -> {
                val due = container.instanceRepository.markDue(instanceId) ?: instance
                val instruction = container.resolveInstruction(behavior, due)

                container.notifier.postIntervention(
                    instance = due,
                    behavior = behavior,
                    instruction = instruction,
                    personality = settings.coachPersonality,
                    defaultSoundUri = settings.defaultSoundUri,
                    isEscalation = kind == AlarmScheduler.Kind.ESCALATION,
                )

                // One escalation only, and only if the user has not answered yet.
                if (kind == AlarmScheduler.Kind.MAIN &&
                    behavior.enforcement != EnforcementLevel.GENTLE &&
                    due.state == InstanceState.DUE
                ) {
                    container.schedulingCoordinator.scheduleEscalationFor(due, behavior)
                }
            }
        }

        // Keep the rolling horizon topped up: every fired alarm is one fewer armed alarm.
        container.schedulingCoordinator.refresh()
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_FIRE = "com.pact.coach.action.ALARM_FIRE"
        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_KIND = "alarm_kind"
    }
}
