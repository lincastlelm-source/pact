# Alarm system

The reliability of this app is the reliability of its alarms. Everything else is bookkeeping.

Android has spent several releases making it harder for apps to wake a device at a precise moment,
for good reasons. This document describes what PACT does, what the platform will and will not
guarantee, and where the app deliberately gives up precision rather than fighting the system.

## Delivery path

```
SchedulingCoordinator.refresh()
    └─ AlarmScheduler.schedule(instance, behavior)
           └─ AlarmManager  (setAlarmClock | setExactAndAllowWhileIdle | setWindow)
                  ▼  at the scheduled instant
           AlarmReceiver (BroadcastReceiver, not exported)
                  ├─ InstanceRepository.markDue      SCHEDULED → DUE
                  ├─ InterventionNotifier.postIntervention
                  ├─ AlarmScheduler.scheduleEscalation   (COACH / STRONG only, once)
                  └─ SchedulingCoordinator.refresh()     top the horizon back up
```

`AlarmReceiver` does its database work inside `goAsync()`. A receiver's process can be killed the
moment `onReceive` returns, and a reminder that loses its own state transition is worse than no
reminder.

## Which AlarmManager call, and why

| Enforcement | Call | Rationale |
|---|---|---|
| `STRONG` | `setAlarmClock` | Strongest guarantee the platform offers. Exempt from Doze, and surfaced to the user as a real alarm — which is honest about what a strong behavior is. |
| `COACH`, `GENTLE` | `setExactAndAllowWhileIdle` | Fires on time, survives Doze, without claiming the alarm-clock slot. |
| Permission unavailable | `setWindow` (10 min) | Degraded but functional. |

Each instance can own three alarms: a pre-alert, the main intervention, and one escalation.

## Duplicate prevention

A `PendingIntent`'s identity is `(requestCode, Intent.filterEquals)`. Both halves are derived
deterministically from the instance:

- **Request code**: `instance.alarmRequestCode` plus a fixed per-kind offset (pre-alert
  +1,000,000, escalation +2,000,000). Request codes are allocated from
  `MAX(alarmRequestCode) + 1` inside the same transaction that inserts the row, so they are unique.
- **Data URI**: `pact://alarm/<KIND>/<instanceId>`, which makes `filterEquals` differ per kind even
  if two request codes ever collided.

Re-arming an already-armed instance therefore *replaces* its alarm rather than adding a second
one. Combined with the unique `dedupKey` on generated rows, `refresh()` is safe to call as often
as needed — and it is called from four different places.

`SchedulingCoordinator` additionally holds a `Mutex` for the whole refresh, because boot, the
periodic worker and the UI can all fire at once.

## Reboot and update recovery

**Alarms do not survive a reboot.** They must be rebuilt from the database every time.

`SystemEventReceiver` handles `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` and
`MY_PACKAGE_REPLACED`. `TimeChangeReceiver` handles `TIME_SET` and `TIMEZONE_CHANGED`.

They are separate classes on purpose: the boot receiver is declared with
`android:permission="android.permission.RECEIVE_BOOT_COMPLETED"` so only the system can trigger
it, but the clock broadcasts are *not* protected by that permission, and giving that receiver the
same filter would stop it ever firing.

On any of these events the app:

1. Rebases future instants if the zone changed, preserving wall-clock times.
2. Sweeps unanswered interventions into `MISSED`.
3. Tops the 14-day generation horizon back up.
4. Re-arms every alarm inside the 48-hour alarm window.
5. Re-enqueues the periodic maintenance worker, which a reboot also clears.

Only a 48-hour window is armed rather than the whole horizon, which keeps boot cheap and stays
well clear of any per-app alarm limit.

## Notifications

### Channels

The awkward platform fact: **a channel's sound, vibration and importance are immutable once
created.** Changing a behavior's alarm sound cannot update its channel.

PACT gives each behavior its own channel, `pact_behavior_<id>_v<n>`. When the user changes a
sound-affecting field, `BehaviorRepository.save` bumps `channelVersion`, so a new channel is
created and stale versions for that behavior are deleted. Without this, changing a sound would
silently do nothing — a common and confusing bug in reminder apps.

Base channels: `pact_general`, `pact_pre_alert`, `pact_coach` (silent), and
`pact_intervention` as a fallback if a behavior-specific channel cannot be created.

### Sound URIs are not permanent

A `content://` URI can stop resolving: the file is deleted, the source app is uninstalled, a
permission is revoked, or a backup was restored from another device. `NotificationChannels`
therefore **opens every URI before attaching it** and falls back to the system default if it
cannot be read. The app never crashes because a sound went missing.

### Actions

Every intervention notification carries **COMMIT / RECOVER / PASS**, handled by
`NotificationActionReceiver`, which writes straight through the repository. Answering from the
lock screen never needs the app to start.

Notification actions are not guaranteed to be visible on every launcher or companion device, so
the notification body always opens the full intervention screen as a fallback. If a behavior
requires a pass reason — which a notification action cannot collect — tapping PASS opens the
screen instead of silently recording a bare pass.

Recovery from a notification uses the behavior's default delay, because an action cannot show a
picker. The full screen offers 15 / 30 / 45 / 60 / 120 minutes and a custom value.

### Full-screen interventions

`STRONG` requests `setFullScreenIntent`. On Android 14+ this is restricted to calendar and alarm
apps; if it is not granted, the notification degrades to a high-priority heads-up. The app
requests it and accepts refusal.

When a full-screen intervention does launch, `MainActivity` calls `setShowWhenLocked(true)` and
`setTurnScreenOn(true)` — but only for that launch, never for a normal app open.

## Permissions

| Permission | Why | Consequence if denied |
|---|---|---|
| `POST_NOTIFICATIONS` | Any notification at all (Android 13+) | No interventions are shown. Asked once, never nagged. |
| `SCHEDULE_EXACT_ALARM` | Alarms at the exact chosen minute (Android 12+) | Falls back to a 10-minute window. |
| `RECEIVE_BOOT_COMPLETED` | Rebuild alarms after reboot | Reminders stop after a restart until the app is opened. |
| `VIBRATE` | Per-behavior vibration | No vibration. |
| `USE_FULL_SCREEN_INTENT` | Lock-screen intervention for STRONG | Degrades to heads-up. |
| `WAKE_LOCK` | Merged by WorkManager; keeps the device awake long enough to finish background work | — |
| `FOREGROUND_SERVICE` | Merged by WorkManager. PACT uses a plain periodic worker and never requests expedited work, so nothing in the app starts a foreground service. Left in place rather than stripped, because removing a permission a library declares risks breaking it on a path that is hard to test. | — |

Explicitly **not** requested: `INTERNET`, `ACCESS_NETWORK_STATE` (removed from the merged manifest
with `tools:node="remove"`), location, contacts, camera, microphone, SMS, phone, storage.

`USE_EXACT_ALARM` is also deliberately not declared. It is auto-granted, but it is reserved for
apps whose primary purpose is alarms and clocks; using it here would be a Play policy violation.
PACT asks for `SCHEDULE_EXACT_ALARM` and lets the user decide.

## Platform limitations, stated plainly

These are properties of Android. The app detects them, degrades, and tells the user.

1. **Exact alarms are not granted by default** for apps targeting API 33+. Settings shows the
   current state and links to the system screen.
2. **Doze** batches alarms when the device is idle. `setExactAndAllowWhileIdle` and
   `setAlarmClock` are exempt; the inexact fallback is not.
3. **OEM power management** — several manufacturers are considerably more aggressive than stock
   Android and can drop alarms or kill the app entirely. No app can work around this reliably.
   Settings links to battery settings.
4. **Force-stop** clears all alarms and stops receivers until the app is opened again.
5. **Per-app alarm limits** exist on recent releases. Arming only a 48-hour window keeps PACT well
   under them.
6. `SecurityException` can be thrown even after `canScheduleExactAlarms()` returns true, if the
   permission is revoked in between. `AlarmScheduler` catches it and falls back rather than
   crashing.

## The self-test

Because none of the above is visible until a reminder fails, Settings has **"Test my reminder"**.

It creates a real behavior and occurrence, arms a real alarm 15 seconds out through the real
pipeline, and reports what it actually got: exact, inexact, or refused with a reason. The user can
verify sound, vibration, the notification and the intervention screen on their own device before
they rely on it. A "Remove test reminders" action cleans up so the test never pollutes real
statistics.

This is the single most useful diagnostic on Android, where reminders fail silently and per-device.
