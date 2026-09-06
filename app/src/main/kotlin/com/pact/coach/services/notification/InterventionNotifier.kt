package com.pact.coach.services.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pact.coach.MainActivity
import com.pact.coach.R
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.BehaviorInstance
import com.pact.coach.domain.model.CoachPersonality
import com.pact.coach.domain.model.EnforcementLevel
import com.pact.coach.domain.model.ResolvedInstruction
import com.pact.coach.domain.coaching.MessageLibrary

/**
 * Builds and posts the intervention notification.
 *
 * The notification carries COMMIT / RECOVER / PASS actions so a decision costs one tap from the
 * lock screen. Notification actions are not guaranteed to be visible on every launcher or
 * wearable, so the notification body itself is always tappable and opens the full intervention
 * screen, which is the fallback path required by the spec.
 *
 * STRONG behaviors additionally request a full-screen intent. On Android 14+ that is restricted
 * to calendar and alarm apps, so it is requested and allowed to be ignored: the notification
 * degrades to a high-priority heads-up rather than failing.
 */
class InterventionNotifier(
    private val context: Context,
    private val channels: NotificationChannels = NotificationChannels(context),
) {

    private val notificationManager = NotificationManagerCompat.from(context)

    fun postIntervention(
        instance: BehaviorInstance,
        behavior: Behavior,
        instruction: ResolvedInstruction,
        personality: CoachPersonality,
        defaultSoundUri: String?,
        isEscalation: Boolean = false,
    ) {
        if (!hasPostPermission()) {
            Log.i(TAG, "Notification permission not granted; skipping post")
            return
        }

        val channelId = channels.channelFor(behavior, defaultSoundUri)
        val notificationId = instance.alarmRequestCode

        val title = if (isEscalation) {
            "Still time for ${instruction.title}"
        } else {
            instruction.title.ifBlank { behavior.name }
        }

        val lines = buildList {
            add(MessageLibrary.nudge(personality, behavior.name))
            if (instruction.instructions.isNotBlank()) add(instruction.instructions)
            instruction.durationMinutes?.let { add("Planned: $it min") }
            behavior.minimumLabel()?.let { add("Minimum: $it") }
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(openInterventionIntent(instance))
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(priorityFor(behavior.enforcement))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(false)
            // STRONG asks for a decision, so it should not be swiped away by accident. The
            // intervention screen and the notification actions both still dismiss it.
            .setOngoing(behavior.enforcement == EnforcementLevel.STRONG)
            .addAction(
                R.drawable.ic_commit,
                "Commit",
                actionIntent(instance, NotificationActionReceiver.ACTION_COMMIT),
            )
            .addAction(
                R.drawable.ic_recover,
                "Later",
                actionIntent(instance, NotificationActionReceiver.ACTION_RECOVER),
            )
            .addAction(
                R.drawable.ic_pass,
                "Pass",
                actionIntent(instance, NotificationActionReceiver.ACTION_PASS),
            )

        if (behavior.enforcement == EnforcementLevel.STRONG) {
            // Requested, not required. See the class comment.
            builder.setFullScreenIntent(openInterventionIntent(instance, fullScreen = true), true)
        }

        post(notificationId, builder)
    }

    fun postPreAlert(instance: BehaviorInstance, behavior: Behavior, minutesAhead: Int) {
        if (!hasPostPermission()) return

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_PRE_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${behavior.name} in $minutesAhead min")
            .setContentText("A heads-up so you can finish what you are doing.")
            .setContentIntent(openInterventionIntent(instance))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        post(instance.alarmRequestCode + PRE_ALERT_ID_OFFSET, builder)
    }

    /** Quiet, non-urgent coaching note. Never makes a sound. */
    fun postCoachMessage(id: Int, title: String, body: String) {
        if (!hasPostPermission()) return

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_COACH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        post(id, builder)
    }

    fun cancel(instance: BehaviorInstance) {
        runCatching {
            notificationManager.cancel(instance.alarmRequestCode)
            notificationManager.cancel(instance.alarmRequestCode + PRE_ALERT_ID_OFFSET)
        }
    }

    fun cancelById(notificationId: Int) {
        runCatching { notificationManager.cancel(notificationId) }
    }

    // --- Internals ----------------------------------------------------------------------

    private fun post(id: Int, builder: NotificationCompat.Builder) {
        try {
            notificationManager.notify(id, builder.build())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post.
            Log.w(TAG, "Notification refused", e)
        } catch (e: Exception) {
            Log.e(TAG, "Could not post notification", e)
        }
    }

    private fun hasPostPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            notificationManager.areNotificationsEnabled()
        }

    private fun priorityFor(level: EnforcementLevel): Int = when (level) {
        EnforcementLevel.GENTLE -> NotificationCompat.PRIORITY_DEFAULT
        EnforcementLevel.COACH -> NotificationCompat.PRIORITY_HIGH
        EnforcementLevel.STRONG -> NotificationCompat.PRIORITY_MAX
    }

    private fun openInterventionIntent(
        instance: BehaviorInstance,
        fullScreen: Boolean = false,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SHOW_INTERVENTION
            data = Uri.parse("pact://intervention/${instance.id}")
            putExtra(MainActivity.EXTRA_INSTANCE_ID, instance.id)
            putExtra(MainActivity.EXTRA_FULL_SCREEN, fullScreen)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val offset = if (fullScreen) FULL_SCREEN_ID_OFFSET else 0
        return PendingIntent.getActivity(
            context,
            instance.alarmRequestCode + offset,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(instance: BehaviorInstance, action: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("pact://action/$action/${instance.id}")
            putExtra(NotificationActionReceiver.EXTRA_INSTANCE_ID, instance.id)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, instance.alarmRequestCode)
        }
        return PendingIntent.getBroadcast(
            context,
            instance.alarmRequestCode + action.hashCode().and(0xFFFF),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "InterventionNotifier"
        private const val PRE_ALERT_ID_OFFSET = 500_000
        private const val FULL_SCREEN_ID_OFFSET = 700_000
    }
}
