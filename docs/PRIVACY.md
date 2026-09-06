# Privacy

> Your behavioural data is stored locally on this device.

That sentence appears in the app's Settings screen. This document explains why it is a structural
property of the build rather than a promise.

## The offline guarantee

**PACT does not declare the `INTERNET` permission.**

This is the whole guarantee, and it is enforced by the operating system rather than by the app's
own good behaviour. Without that permission Android refuses every socket the process tries to
open. Even if a dependency attempted a network call, even if a future contributor added one by
accident, and even if the app were compromised, there is no route off the device.

`ACCESS_NETWORK_STATE` is also removed. WorkManager merges it into the manifest for its network
constraints; PACT never sets one, so the permission is stripped with `tools:node="remove"`. The
merged manifest can be verified in any build:

```bash
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```

## What the app can therefore not do

- Send behavioural data anywhere
- Sync between devices
- Call an AI service — OpenAI, Claude, Gemini or any other
- Report crashes or analytics
- Load ads
- Check for updates
- Resolve a remote image or URL

The coaching engine is local rules and templates over the user's own numbers. There is no model
and no inference service. The architecture leaves room for an optional **local** model later, but
nothing in the app requires one, and adding a cloud one would mean adding the `INTERNET`
permission — a change no reviewer could miss.

## Permissions, and why each one exists

| Permission | Purpose |
|---|---|
| `POST_NOTIFICATIONS` | Show interventions (Android 13+) |
| `SCHEDULE_EXACT_ALARM` | Fire reminders at the exact chosen minute |
| `RECEIVE_BOOT_COMPLETED` | Rebuild alarms after a restart |
| `VIBRATE` | Per-behavior vibration |
| `USE_FULL_SCREEN_INTENT` | Lock-screen intervention for strong enforcement |
| `WAKE_LOCK` | Merged by WorkManager; finishing background work |
| `FOREGROUND_SERVICE` | Merged by WorkManager. Unused — PACT never requests expedited work — but left rather than stripped, since removing a permission a library declares can break it in ways that are hard to test. |

Neither of the last two can move data anywhere; without `INTERNET` there is nowhere for it to go.

Not requested: internet, network state, location, contacts, camera, microphone, SMS, phone,
calendar, storage, or any identifier.

The app also does not declare `USE_EXACT_ALARM`. It is auto-granted, but reserved for apps whose
primary purpose is alarms and clocks; PACT asks for `SCHEDULE_EXACT_ALARM` and lets the user
decide.

## No third-party SDKs

The dependency list is AndroidX, Jetpack Compose, Room, kotlinx.serialization and
kotlinx.coroutines. There is no Firebase, no Crashlytics, no analytics library, no advertising
SDK, and no attribution SDK. `app/build.gradle.kts` is short enough to audit in a minute.

## Cloud backup is disabled

`android:allowBackup="false"`, with `backup_rules.xml` and `data_extraction_rules.xml` both
excluding the database, preferences and files.

This is deliberate. Android's automatic backup would copy the database to the user's Google
account, which would quietly break the guarantee above. Data moves only when the user exports it.

## Export and import

Backup is local JSON through the Storage Access Framework. The user picks the destination, so the
app needs no storage permission and never touches a path it was not handed.

Import treats the file as **untrusted input**:

- It is parsed and validated before anything is written.
- Structural checks reject duplicate ids, orphaned references, nameless records, and out-of-range
  times.
- A file from a newer format version is refused with an explanation rather than partially read.
- An oversized file is rejected before parsing.
- The user sees what the file contains and chooses merge or replace explicitly. Replace states
  plainly that it deletes existing data.
- The whole apply runs in one transaction, so a rejected or failed import leaves the database
  exactly as it was.

Imported content is **data, never instructions**. Values are bound as SQL parameters, never
concatenated, and nothing in a backup is executed or evaluated. There is a test that imports a
file containing script tags and SQL fragments and asserts they are stored as literal text.

## What is stored on the device

Goals, behaviors, schedules, day plans, checklists, every occurrence and its outcome, the audit
trail of decisions, coaching messages, and preferences. In plain terms: a detailed record of what
someone intended to do and whether they did it.

That is sensitive, which is the reason for the design above. It lives in the app's private data
directory, which other apps cannot read on a non-rooted device.

## Full-disk encryption, and what is not done

The database is not separately encrypted with SQLCipher. Android already encrypts the device by
default from Android 10, and app-private storage is protected by the OS sandbox. Adding SQLCipher
would mean either shipping a key in the APK — which protects nothing — or requiring a passphrase
on every launch, which is a poor trade for a behavioural log and would break background alarm
handling before first unlock.

If a future version stores something genuinely secret, the Android Keystore is the right mechanism
and the place to add it is `AppContainer`.

## Verifying the claims

```bash
# No INTERNET or network permission in the built APK
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk

# No networking libraries anywhere in the dependency graph
./gradlew :app:dependencies --configuration debugRuntimeClasspath

# No network calls in the source
grep -rE "HttpURLConnection|okhttp|retrofit|Socket|URLConnection" app/src/main
```
