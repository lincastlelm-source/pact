# PACT — Personal Action & Commitment Tracker

An offline-first Android behavioural coach. You define goals, turn them into recurring
behaviors, and the app intervenes at the moment you planned. Every intervention asks one
question:

```
COMMIT     I am doing this now
RECOVER    Not now, but I will come back to it
PASS       I am choosing to skip this one
```

It is not a habit tracker with a streak counter. It is a
**Goal → Behavior → Schedule → Intervention → Decision → Action → Recovery → Analysis** loop, and
its central claim is deliberately not "never miss":

> Success is not never missing. Success is returning to the behavior.

Everything runs on the device. There is no account, no server, and **no `INTERNET` permission in
the manifest** — the app is structurally incapable of sending your data anywhere.

---

## Contents

| Document | What it covers |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, module layout, dependency injection, key design decisions |
| [DATABASE.md](docs/DATABASE.md) | Room schema, entities, indices, migration policy |
| [SCHEDULING.md](docs/SCHEDULING.md) | Recurrence rules, instance generation, the horizon, time-zone and DST handling |
| [ALARM_SYSTEM.md](docs/ALARM_SYSTEM.md) | Alarm delivery, notifications, reboot recovery, Android platform limits |
| [TESTING.md](docs/TESTING.md) | Test layout, how to run them, what is and is not covered |
| [PRIVACY.md](docs/PRIVACY.md) | The offline guarantee and how it is enforced |

---

## Requirements

- **JDK 17–21** (the Android Gradle Plugin does not support JDK 22+)
- **Android SDK** with platform 36 and build-tools 36.0.0
- **Gradle 8.14.3** (a wrapper is committed, so you do not need it installed)
- A device or emulator running **Android 8.0 (API 26) or newer**

`minSdk` is 26 deliberately: it is the first release with native `java.time` and notification
channels, both of which this app leans on heavily, and it still covers the overwhelming majority
of active devices.

## Setup

1. Open the project in Android Studio and let Gradle sync, **or** build from the command line.
2. Point the build at your SDK by creating `local.properties` in the project root:

```properties
sdk.dir=/path/to/Android/sdk
```

Use forward slashes even on Windows — `local.properties` is a Java properties file, where a
backslash is an escape character.

## Build

```bash
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

### On this machine specifically

Two local quirks are already handled, but worth knowing:

**The `&` in the folder name.** `cmd.exe` treats `&` as a command separator, and the stock
`gradlew.bat` assigns its path variables unquoted — so it split the path and failed with
`'Commitment' is not recognized`. The wrapper here has been patched to use quoted `set "VAR=..."`
syntax, which is the standard fix. It now runs correctly from the real path.

**No suitable JDK on PATH.** The system Java is 26; AGP supports 17–21 only. `pact.ps1` sets
`JAVA_HOME` and `ANDROID_HOME` to the local toolchain and forwards to the wrapper:

```powershell
.\pact.ps1 :app:assembleDebug
```

Once you have Android Studio (or any JDK 17–21 on `JAVA_HOME`), use `.\gradlew.bat` directly and
delete `pact.ps1`.

## Install it on your phone

Use the **release** APK: `app/build/outputs/apk/release/app-release.apk`. It is smaller, faster,
and installs as `com.pact.coach` rather than the `.debug` variant.

### Without a cable (simplest)

Copy the APK to the phone — email it to yourself, put it in Drive or OneDrive, or use a USB
transfer — then open it in the phone's file manager and tap it.

Android will warn that the app is from an unknown source and offer a settings toggle; allow your
file manager or browser to install unknown apps, then confirm. This warning is normal for any app
not delivered by a store, and it is not a sign anything is wrong.

### Over USB with adb

`adb` is not on PATH; it ships with the SDK platform-tools:

```powershell
$env:Path += ";C:\Users\lincastle\android-toolchain\sdk\platform-tools"
```

On the phone: Settings → About phone → tap **Build number** seven times to unlock Developer
options, then Developer options → enable **USB debugging**. Plug in, accept the "Allow USB
debugging?" prompt, then:

```powershell
adb devices
```

```powershell
adb install -r "app\build\outputs\apk\release\app-release.apk"
```

`-r` reinstalls over an existing copy, keeping its data. That only works while the signing key
stays the same.

### After installing

Two permissions decide whether reminders actually arrive, and Android grants neither by default:

1. Allow notifications when first prompted.
2. Settings → "Will my reminders actually arrive?" → **Alarm permission**, and grant
   *Alarms & reminders*. Without it reminders drift by up to ten minutes.

Then use **Test my reminder** on that same screen. It fires a real alarm through the real pipeline
about fifteen seconds later, so you can confirm sound, vibration and the intervention screen work
on your specific phone before relying on it.

## Release APK

```bash
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` — around 1.9 MB, R8-minified with
resource shrinking, and **universal**: it carries `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`
native code, so it installs on effectively any Android 8.0+ phone. No Play Store, no developer
account, no per-device build.

### Signing

Signing credentials are read from `keystore.properties` in the project root:

```properties
storeFile=pact-release.jks
storePassword=...
keyAlias=pact
keyPassword=...
```

Both that file and `pact-release.jks` are gitignored and must never be committed.

**Back both up somewhere safe.** Android identifies an app by its signature. If you lose the
keystore you can never ship an update that installs over an existing copy — the only way back is
uninstall and reinstall, and because PACT disables cloud backup that wipes your history. Export
your data from Settings before doing anything drastic.

To create a fresh key of your own:

```bash
keytool -genkeypair -v -keystore pact-release.jks -alias pact -keyalg RSA -keysize 4096 -validity 10950
```

If `keystore.properties` is missing, the release build falls back to debug signing and logs a
warning, so `assembleRelease` still works on a fresh clone. Do not keep a debug-signed build
installed long term: the debug key is machine-local and regenerating it breaks in-place updates.

## Running it on a device

Building produces an APK; running it needs an actual Android target. There are two options.

### A physical phone (recommended, and free)

Android 8.0 or newer. On the phone: Settings → About phone → tap *Build number* seven times to
unlock Developer options, then Developer options → enable **USB debugging**. Plug it in, accept the
"Allow USB debugging?" prompt, then:

```powershell
& "C:\Users\lincastle\android-toolchain\sdk\platform-tools\adb.exe" devices
```

Your device should be listed as `device` (not `unauthorized`). Then install as above.

This is also the *only* honest way to test the alarm behaviour, because the things most likely to
break reminders — OEM battery managers, Doze, exact-alarm permission — differ per manufacturer and
are not reproduced by an emulator.

### An emulator

Needs about 2.5 GB of downloads (emulator package plus a system image) and hardware acceleration.
This machine reports a hypervisor already active, so the emulator should be able to use WHPX. With
4 cores and 16 GB RAM it will run, but not quickly.

```powershell
$sdk = "C:\Users\lincastle\android-toolchain\sdk"
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root=$sdk "emulator" "system-images;android-34;google_apis;x86_64"
& "$sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n pact -k "system-images;android-34;google_apis;x86_64" -d pixel_6
& "$sdk\emulator\emulator.exe" -avd pact
```

## Test

```bash
./gradlew :app:testDebugUnitTest          # unit + Room + backup tests, no device needed
./gradlew :app:connectedDebugAndroidTest  # instrumented tests, needs a device or emulator
```

On this machine, use `.\pact.ps1 :app:testDebugUnitTest` instead (see above).

The unit test suite includes the Room and backup tests: they run under Robolectric against real
SQLite, so the schema, indices and transactions are genuinely exercised without a device. See
[TESTING.md](docs/TESTING.md).

### Troubleshooting

**Spurious `Unresolved reference` errors after editing a few files.** If a build suddenly cannot
resolve whole packages that obviously exist — and the errors appear *only* in the files you just
touched, with nothing reported against the file that defines the missing symbol — it is Kotlin
incremental-compilation cache staleness, not a real error. Run a clean build:

```bash
./gradlew :app:clean :app:assembleDebug
```

**`Unable to establish loopback connection`.** Gradle needs local socket access. Sandboxed or
heavily locked-down shells can block it; run Gradle from a normal terminal.

---

## Using the app

1. **Onboarding** walks you through the idea and creates your first goal.
2. **Create a behavior** — name it, choose whether it is time-based, set a schedule, and set a
   *minimum action*.
3. **Test your reminders**: Settings → "Test my reminder" schedules a real alarm 15 seconds out
   through the real pipeline, so you can confirm sound, vibration and the intervention screen work
   on your particular device before you rely on it.
4. When a behavior comes due, answer it: commit, recover or pass — from the notification, or on
   the full intervention screen.

### The minimum action

Every behavior can define a normal target and a floor:

```
Exercise
  Normal target:    30 minutes
  Minimum action:    5 minutes
```

When an intervention fires, the minimum is offered explicitly. A bad day is meant to produce a
small win rather than a miss, and the app records the difference so it can tell you later if your
target is consistently too high.

### Recovery

Recovery is the point of the product. Choosing "not now, 30 minutes" does **not** rewrite the
original occurrence: it is closed as `RECOVERED`, keeping its planned time in your history, and a
new linked occurrence is created. The analytics layer then scores the **chain**, not the rows, so
a session that was moved once and then done reads as a completion.

That produces the **recovery score**: when you did not act at the planned time, how often did you
come back to it? It is deliberately independent of how often you needed to.

---

## Feature summary

**Goals** — name, why it matters, free-text category, priority, status, target date, many behaviors.

**Behaviors** — time-based or flexible; measured by completion, duration, quantity or checklist;
importance, difficulty, enforcement level, per-behavior sound and vibration, pre-alert and
escalation, recovery policy, reflection frequency, optional manual progression ramp.

**Schedules** — once, daily, weekdays, weekends, chosen days, every N days, N times per week,
and several times per day. Multiple times a day are tracked as **separate occurrences with
separate history**; the evening session never overwrites the morning one.

**Daily customisation** — "Exercise" stays one behavior while Monday is a 20-minute run and
Sunday is a rest day. Plans resolve most-specific-first: exact date + occurrence → exact date →
weekday + occurrence → weekday → behavior default, and a day plan only overrides the fields it
actually sets.

**Interventions** — notification with COMMIT / RECOVER / PASS actions, plus a full intervention
screen. Strong enforcement requests a full-screen prompt; there is always a visible way out.

**Coaching** — a deterministic local rules engine over your own statistics. It never invents
insight it cannot support, and every schedule suggestion needs your explicit "Apply change".

**Insights** — completion, pass, miss and recovery rates; streaks; time-of-day and day-of-week
performance; weekly trend; monthly calendar; weekly review.

**Backup** — local JSON export and import through the Storage Access Framework, with validation,
a preview of what a file contains, and an explicit merge-or-replace choice.

---

## Android platform limitations you should know about

These are properties of Android, not bugs in the app. The app degrades gracefully and tells you
when it has, rather than pretending.

- **Exact alarms** need the `SCHEDULE_EXACT_ALARM` permission, which is not granted automatically
  for apps targeting API 33+. Without it, reminders fall back to an inexact ten-minute window.
  Settings shows the current state and links to the system screen.
- **Battery optimisation and OEM power managers** can delay or drop alarms. Some manufacturers are
  considerably more aggressive than stock Android. Settings links to the battery settings screen.
- **Full-screen intents** are restricted on Android 14+ to calendar and alarm apps. If the
  permission is not granted, a strong intervention degrades to a high-priority heads-up
  notification.
- **Notification sounds** are baked into a channel when it is created and cannot be edited
  afterwards, so changing a sound creates a fresh channel and deletes the old one.
- **Sound URIs can stop resolving** after a restore, an uninstall of the source app, or a deleted
  file. Every URI is checked before use and silently falls back to the default.
- The app deliberately does **not** declare `USE_EXACT_ALARM`, which is reserved for apps whose
  primary purpose is alarms and clocks.

See [ALARM_SYSTEM.md](docs/ALARM_SYSTEM.md) for the detail.

---

## What this app will not do

No gambling mechanics, no variable-ratio rewards, no artificial scarcity, no shaming language, no
deceptive notifications, no dark patterns around leaving. Streaks exist but are never presented as
the measure of success, and a broken one is not framed as lost progress.

No cloud AI, either. The coaching engine is local rules and templates over your own numbers. The
architecture leaves room for an optional local model later, but nothing in the app requires one.
