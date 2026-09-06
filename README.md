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

## Install

Download the latest `app-release.apk` from the
[Releases page](https://github.com/lincastlelm-source/pact/releases) and open it on your phone.

Android will warn that the app comes from an unknown source and offer a settings toggle. Allow
your browser or file manager to install unknown apps, then confirm. Play Protect may show a
second dialog; choose to install anyway. Both warnings are normal for any app not delivered by a
store and are not a sign that anything is wrong.

Requires **Android 8.0 (API 26) or newer**.

### Verifying the download

Each release lists the APK's SHA-256 hash. To check what you downloaded matches:

```bash
sha256sum app-release.apk
```

Every release is signed with the same key. Its certificate fingerprint is:

```
SHA-256: <fill this in — see "Publishing a release" below>
```

If a future release shows a different fingerprint, it did not come from this project. You can
check any APK yourself:

```bash
keytool -printcert -jarfile app-release.apk
```

### Staying up to date

Sideloaded apps do not update themselves. [Obtainium](https://github.com/ImranR98/Obtainium)
watches this repository's releases and installs new versions automatically — point it at
`https://github.com/lincastlelm-source/pact` and it handles the rest.

---

## First run

Two permissions decide whether reminders actually arrive, and Android grants neither by default:

1. Allow notifications when first prompted.
2. Settings → "Will my reminders actually arrive?" → **Alarm permission**, and grant
   *Alarms & reminders*. Without it, reminders drift by up to ten minutes.

Then use **Test my reminder** on that same screen. It fires a real alarm through the real
pipeline about fifteen seconds later, so you can confirm sound, vibration and the intervention
screen work on your specific phone before relying on it.

---

## Using the app

1. **Onboarding** walks you through the idea and creates your first goal.
2. **Create a behavior** — name it, choose whether it is time-based, set a schedule, and set a
   *minimum action*.
3. **Test your reminders** as described above.
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

**Goals** — name, why it matters, free-text category, priority, status, target date, many
behaviors.

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

## What this app will not do

No gambling mechanics, no variable-ratio rewards, no artificial scarcity, no shaming language, no
deceptive notifications, no dark patterns around leaving. Streaks exist but are never presented as
the measure of success, and a broken one is not framed as lost progress.

No cloud AI, either. The coaching engine is local rules and templates over your own numbers. The
architecture leaves room for an optional local model later, but nothing in the app requires one.

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

## Documentation

| Document | What it covers |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, module layout, dependency injection, key design decisions |
| [DATABASE.md](docs/DATABASE.md) | Room schema, entities, indices, migration policy |
| [SCHEDULING.md](docs/SCHEDULING.md) | Recurrence rules, instance generation, the horizon, time-zone and DST handling |
| [ALARM_SYSTEM.md](docs/ALARM_SYSTEM.md) | Alarm delivery, notifications, reboot recovery, Android platform limits |
| [TESTING.md](docs/TESTING.md) | Test layout, how to run them, what is and is not covered |
| [PRIVACY.md](docs/PRIVACY.md) | The offline guarantee and how it is enforced |

A full user guide is in [docs/guide/](docs/guide/), also available as
[PACT-User-Guide.pdf](PACT-User-Guide.pdf).

---

## Building from source

### Requirements

- **JDK 17–21.** The Android Gradle Plugin does not support JDK 22 or newer. If your system Java
  is outside that range, set `JAVA_HOME` to a supported JDK before building.
- **Android SDK** with platform 36 and build-tools 36.0.0.
- **Gradle** is not needed separately — the wrapper is committed.

`minSdk` is 26 deliberately: it is the first release with native `java.time` and notification
channels, both of which this app leans on heavily, and it still covers the overwhelming majority
of active devices.

### Setup

Clone the repository, then point the build at your SDK by creating `local.properties` in the
project root:

```properties
sdk.dir=/path/to/Android/sdk
```

Use forward slashes even on Windows — `local.properties` is a Java properties file, where a
backslash is an escape character. Android Studio writes this file for you on first sync.

### Build

```bash
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # release APK
```

Output lands in `app/build/outputs/apk/`. On Windows use `gradlew.bat`.

The release APK is around 1.9 MB, R8-minified with resource shrinking, and **universal**: it
carries `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64` native code, so it installs on effectively
any Android 8.0+ device.

The committed Gradle wrapper is patched to quote its path variables, so the build works from a
directory whose name contains `&`. The stock wrapper does not.

### Test

```bash
./gradlew :app:testDebugUnitTest          # unit + Room + backup tests, no device needed
./gradlew :app:connectedDebugAndroidTest  # instrumented tests, needs a device or emulator
```

The unit suite includes the Room and backup tests: they run under Robolectric against real
SQLite, so the schema, indices and transactions are genuinely exercised without a device. See
[TESTING.md](docs/TESTING.md).

### Installing a local build over USB

`adb` ships with the SDK's platform-tools. On the phone: Settings → About phone → tap
**Build number** seven times to unlock Developer options, then Developer options → enable
**USB debugging**. Plug in, accept the "Allow USB debugging?" prompt, then:

```bash
adb devices
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-r` reinstalls over an existing copy, keeping its data — but only while the signing key stays
the same.

A physical device is the only honest way to test alarm behaviour. The things most likely to break
reminders — OEM battery managers, Doze, exact-alarm permission — differ per manufacturer and are
not reproduced by an emulator.

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

## Publishing a release

Signing credentials are read from `keystore.properties` in the project root:

```properties
storeFile=pact-release.jks
storePassword=...
keyAlias=pact
keyPassword=...
```

Both that file and the keystore itself are gitignored and must never be committed. If
`keystore.properties` is missing, the release build falls back to debug signing and logs a
warning, so `assembleRelease` still works on a fresh clone. Do not keep a debug-signed build
installed long term: the debug key is machine-local, and regenerating it breaks in-place updates.

To create a key of your own:

```bash
keytool -genkeypair -v -keystore pact-release.jks -alias pact -keyalg RSA -keysize 4096 -validity 10950
```

**Back up the keystore somewhere safe.** Android identifies an app by its signature. If you lose
the key you can never ship an update that installs over an existing copy — the only way back is
uninstall and reinstall, and because PACT disables cloud backup, that wipes the user's history.

To publish, tag the commit and push the tag:

```bash
git tag -a v1.0.0 -m "PACT v1.0.0"
git push origin v1.0.0
```

Then attach `app-release.apk` to the GitHub release, along with its SHA-256 hash and the
certificate fingerprint from `keytool -printcert -jarfile`.

---

## Contributing

Issues and pull requests are welcome. Two constraints are not negotiable and a PR that breaks
either will be closed:

1. **No `INTERNET` permission.** Not for analytics, not for crash reporting, not for an optional
   feature behind a toggle. The guarantee is only worth something if it is structural.
2. **No dark patterns.** See "What this app will not do" above.

---

## License

Licensed under the [GNU General Public License v3.0](LICENSE).

This means you are free to use, study, modify and redistribute PACT — and any derivative you
distribute must also be released under the GPLv3 with its source available. That is deliberate:
the privacy guarantee in this README is only verifiable because the source is open, and the
license keeps it that way in anything built on top of it.
