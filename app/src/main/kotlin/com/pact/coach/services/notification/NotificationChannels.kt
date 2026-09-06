package com.pact.coach.services.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.pact.coach.domain.model.Behavior
import com.pact.coach.domain.model.EnforcementLevel

/**
 * Notification channels.
 *
 * The awkward platform fact this class exists to handle: **a channel's sound, vibration and
 * importance are immutable once created**. Changing a behavior's alarm sound therefore cannot
 * update its channel; it has to create a new one. [Behavior.channelVersion] is bumped by the
 * repository whenever a sound-affecting field changes, and the channel id includes that version,
 * so a new channel appears and the old one is deleted.
 *
 * Sound URIs are also not guaranteed to stay readable: the user can delete the file, revoke
 * access, or restore a backup from another device. Every sound is therefore validated before it
 * is attached, and an unreadable URI silently degrades to the default rather than throwing.
 */
class NotificationChannels(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /** Channels that are not behavior-specific. */
    fun ensureBaseChannels() {
        val nm = manager ?: return

        createOrUpdate(
            nm,
            NotificationChannel(
                CHANNEL_GENERAL,
                "General",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "App messages that are not tied to a scheduled behavior."
            },
        )

        createOrUpdate(
            nm,
            NotificationChannel(
                CHANNEL_PRE_ALERT,
                "Heads-up reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "The short warning before a behavior is due."
                enableVibration(false)
            },
        )

        createOrUpdate(
            nm,
            NotificationChannel(
                CHANNEL_COACH,
                "Coaching",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Weekly reviews and observations. Never urgent."
                setSound(null, null)
                enableVibration(false)
            },
        )

        createOrUpdate(
            nm,
            NotificationChannel(
                CHANNEL_FALLBACK_INTERVENTION,
                "Reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Used when a behavior has no channel of its own."
            },
        )
    }

    /**
     * The channel for one behavior, created on demand. Returns the id to post to.
     *
     * Falls back to the shared intervention channel if the platform refuses the behavior-specific
     * one for any reason, so a notification is never lost.
     */
    fun channelFor(behavior: Behavior, defaultSoundUri: String?): String {
        val nm = manager ?: return CHANNEL_FALLBACK_INTERVENTION
        val id = channelId(behavior)

        if (nm.getNotificationChannel(id) != null) return id

        // Remove stale versions of this behavior's channel so the app's channel list does not
        // grow every time the user changes a sound.
        deleteOtherVersions(nm, behavior)

        val importance = when (behavior.enforcement) {
            EnforcementLevel.GENTLE -> NotificationManager.IMPORTANCE_DEFAULT
            EnforcementLevel.COACH -> NotificationManager.IMPORTANCE_HIGH
            EnforcementLevel.STRONG -> NotificationManager.IMPORTANCE_HIGH
        }

        return try {
            val channel = NotificationChannel(id, behavior.name.take(40), importance).apply {
                description = "Reminders for ${behavior.name}"
                enableVibration(behavior.vibrationEnabled)
                if (behavior.vibrationEnabled) {
                    vibrationPattern = vibrationPatternFor(behavior.vibrationPattern)
                }
                if (behavior.soundEnabled) {
                    val uri = resolveSound(behavior.soundUri ?: defaultSoundUri)
                    setSound(
                        uri,
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build(),
                    )
                } else {
                    setSound(null, null)
                }
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
            nm.createNotificationChannel(channel)
            id
        } catch (e: Exception) {
            Log.w(TAG, "Could not create channel for ${behavior.id}", e)
            CHANNEL_FALLBACK_INTERVENTION
        }
    }

    fun deleteChannelsFor(behaviorId: String) {
        val nm = manager ?: return
        nm.notificationChannels
            .filter { it.id.startsWith("$CHANNEL_BEHAVIOR_PREFIX$behaviorId") }
            .forEach { runCatching { nm.deleteNotificationChannel(it.id) } }
    }

    fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Verifies a sound URI is actually readable right now. A URI that came from a backup, a
     * deleted file or a revoked permission must not be handed to the platform.
     */
    private fun resolveSound(uriString: String?): Uri {
        val fallback = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
        if (uriString.isNullOrBlank()) return fallback
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { }
            uri
        } catch (e: Exception) {
            Log.i(TAG, "Sound $uriString is not accessible, using the default", e)
            fallback
        }
    }

    private fun deleteOtherVersions(nm: NotificationManager, behavior: Behavior) {
        val prefix = "$CHANNEL_BEHAVIOR_PREFIX${behavior.id}_v"
        nm.notificationChannels
            .filter { it.id.startsWith(prefix) && it.id != channelId(behavior) }
            .forEach { runCatching { nm.deleteNotificationChannel(it.id) } }
    }

    private fun createOrUpdate(nm: NotificationManager, channel: NotificationChannel) {
        runCatching { nm.createNotificationChannel(channel) }
            .onFailure { Log.w(TAG, "Could not create channel ${channel.id}", it) }
    }

    companion object {
        private const val TAG = "NotificationChannels"

        const val CHANNEL_GENERAL = "pact_general"
        const val CHANNEL_PRE_ALERT = "pact_pre_alert"
        const val CHANNEL_COACH = "pact_coach"
        const val CHANNEL_FALLBACK_INTERVENTION = "pact_intervention"
        const val CHANNEL_BEHAVIOR_PREFIX = "pact_behavior_"

        fun channelId(behavior: Behavior): String =
            "$CHANNEL_BEHAVIOR_PREFIX${behavior.id}_v${behavior.channelVersion}"

        fun vibrationPatternFor(name: String): LongArray = when (name) {
            "short" -> longArrayOf(0, 200)
            "long" -> longArrayOf(0, 800)
            "double" -> longArrayOf(0, 250, 200, 250)
            "insistent" -> longArrayOf(0, 400, 200, 400, 200, 400)
            else -> longArrayOf(0, 350, 250, 350)
        }
    }
}
