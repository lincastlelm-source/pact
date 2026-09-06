# Architecture

## Shape

Single-activity Compose app, MVVM, with a clean-ish separation into three layers. The rule that
matters: **the domain layer has no Android dependency and no clock of its own.** Everything that
decides *what should happen* — recurrence, instance generation, the state machine, recovery,
statistics, coaching — is plain Kotlin that a JVM unit test can drive directly.

```
com.pact.coach
├── core/
│   ├── time/        ClockProvider — the only source of "now"
│   └── util/        Ids, TimeFormat
├── domain/          No Android imports. Fully unit tested.
│   ├── model/       Goal, Behavior, Schedule, BehaviorInstance, enums
│   ├── recurrence/  RecurrenceEngine, RecurrenceValidator
│   ├── scheduler/   InstanceGenerator, SchedulingHorizon, ConflictDetector
│   ├── state/       InstanceStateMachine
│   ├── recovery/    RecoveryEngine, RecoveryChain
│   ├── instruction/ InstructionResolver, ProgressionResolver
│   └── coaching/    StatsCalculator, CoachingEngine, MessageLibrary
├── data/
│   ├── db/          PactDatabase, entities, DAOs, Migrations
│   ├── mapper/      entity ↔ domain
│   ├── repository/  Goal, Behavior, Instance, Insights, Settings
│   └── backup/      BackupService, backup DTOs
├── services/        Android-specific side effects
│   ├── alarm/       AlarmScheduler, AlarmReceiver, SystemEventReceiver, TimeChangeReceiver
│   ├── notification/NotificationChannels, InterventionNotifier, NotificationActionReceiver
│   ├── work/        MaintenanceWorker
│   └── SchedulingCoordinator
├── presentation/
│   ├── theme/       Material 3 palette, DecisionColors
│   ├── navigation/  Routes, PactApp (NavHost), the view-model factory
│   ├── components/  Shared composables
│   ├── screens/     One file per screen
│   └── viewmodels/
├── di/              AppContainer
├── MainActivity.kt
└── PactApplication.kt
```

## Decisions worth explaining

### Manual dependency injection

There is no Hilt, no Koin, no annotation processor for DI. `AppContainer` builds the whole graph
by hand with lazy singletons.

The graph is one database and about a dozen objects. A DI framework would add a second annotation
processor to the build, a generated component to reason about, and a compile-time cost, in
exchange for wiring that fits on one screen. `AppContainer` is also trivially substitutable in
tests — you construct one with a `FixedClockProvider` and an in-memory database.

The one place this costs something is the view-model factory in `PactApp.kt`, which is an
explicit `when` over model classes rather than generated. That is a fair trade: it keeps the view
models plain classes with constructor arguments, so a unit test can instantiate one directly
without `SavedStateHandle` ceremony.

### ClockProvider

Nothing in the domain or data layer calls `Instant.now()` or `System.currentTimeMillis()`
directly. They take a `ClockProvider`. `SystemClockProvider` reads the device; `FixedClockProvider`
is mutable so a test can advance time and assert on sweeps, streaks and recovery windows.

`SystemClockProvider.zone()` deliberately re-reads `ZoneId.systemDefault()` on every call rather
than caching it, because the user can change time zone while the process is alive.

### Domain models separate from Room entities

Entities store epoch days, minute-of-day integers and enum names — shapes chosen for correct
SQLite ordering and range queries. Domain models use `LocalDate`, `LocalTime` and real enums.
`data/mapper` sits between them.

The duplication is real but it buys two things: the scheduling logic never sees a storage
concern, and enum parsing can be **forgiving**. A row written by a newer build with an unknown
`state` value falls back to a sensible default instead of throwing. A user should never lose
access to months of history because one column holds a string this build does not recognise.
There is a test for exactly that.

### Bounded generation instead of infinite rows

Occurrences are materialised over a rolling 14-day horizon
(`SchedulingHorizon.GENERATION_DAYS`), never for the whole future. A daily behavior that runs for
five years is 14 rows at a time, not 1,825.

Generation is made safe to repeat by a deterministic `dedupKey` on every generated row, with a
unique index behind it. Regenerating an overlapping window is an insert-or-ignore, so the
coordinator can run as often as it likes. This is the single mechanism that prevents duplicate
alarms, and it is tested directly.

### The state machine is the only writer of `state`

`InstanceStateMachine` owns the transition table, and `InstanceRepository` routes every state
change through `require()`. Nothing else may set `state`.

The table is deliberately asymmetric. `COMPLETED → MISSED` is forbidden, so a background sweep can
never erase a real completion. But `PASSED → COMPLETED` and `MISSED → COMPLETED` are both allowed,
because a user who skipped something and then did it anyway, or did a missed morning session at
lunchtime, should be able to record that. Those are product decisions encoded as invariants.

Every state change also appends an immutable `ActionEventEntity` **in the same transaction**, so
the audit trail can never disagree with the instance table.

### Chains, not rows

The most important modelling decision in the app. Recovery creates a *new* instance linked to the
original by `originInstanceId` / `rootInstanceId`; the original is closed as `RECOVERED` with its
planned time intact.

`StatsCalculator` then scores **chains**, not rows. A session planned for 18:00, moved once, and
finished at 18:35 counts as one completion — not one miss plus one completion. Scoring rows would
punish the user for recovering, which is precisely the behaviour the product exists to encourage.

This is also where the recovery score comes from: of the chains that needed a recovery, how many
ended in a completion. It is deliberately independent of how often recovery was needed.

### SchedulingCoordinator as the single funnel

Everything that can invalidate the schedule — app start, boot, package replaced, clock change,
time-zone change, a behavior edit, a decision, the periodic worker — calls
`SchedulingCoordinator.refresh()`. It is idempotent and guarded by a `Mutex`, because boot, the
worker and the UI can all fire at once and two concurrent generations is the classic route to
duplicate alarms.

`refresh()` does four things in order: rebase future instants if the zone changed, sweep unanswered
interventions into `MISSED`, top up the generation horizon, re-arm OS alarms inside the alarm
window. Each step is wrapped in `runCatching` so one failure does not abort the rest.

### Enforcement is not coercion

`STRONG` requests a full-screen intervention and uses `setAlarmClock`, which is the strongest
delivery guarantee the platform offers. It does **not** trap the user: the intervention screen
always shows a "Decide later" affordance and the back gesture works. Autonomy is a product
principle, not a setting.

### Coaching is deterministic

`CoachingEngine` is a fixed set of rules over `BehaviorStats`. Same input, same output, which is
what makes it testable. It produces observations and, where the data supports one, a concrete
`ScheduleSuggestion` — but it has no write access to schedules. Applying a suggestion is an
explicit user action that calls `BehaviorRepository.moveScheduleTime`.

Rules require a minimum sample before they fire (a time-of-day suggestion needs at least three
observations at each hour and a 25-point gap), so a single good morning cannot trigger a
recommendation to rearrange the user's week.

There is no model and no network call. `MessageLibrary` is templates keyed by coach personality.

## Data flow

```
UI (Compose)
   ↕ StateFlow
ViewModel
   ↕ suspend / Flow
Repository ──── ClockProvider
   ↕
Room DAO ──── PactDatabase
```

Side effects go the other way:

```
AlarmManager → AlarmReceiver → InstanceRepository.markDue
                            → InterventionNotifier.postIntervention
                            → SchedulingCoordinator.refresh
```

Notification actions bypass the UI entirely — `NotificationActionReceiver` writes straight through
the repository, so answering an intervention from the lock screen never needs the app to start.

## Threading

- Repositories are `suspend`; Room dispatches its own IO.
- Broadcast receivers use `goAsync()` plus the application scope, because a receiver's process can
  be killed the moment `onReceive` returns.
- `PactApplication.applicationScope` is a `SupervisorJob` with a `CoroutineExceptionHandler`, so
  one failed background task cannot take down the others or the process.
- View models use `viewModelScope`; `stateIn(WhileSubscribed(5_000))` keeps flows warm across
  configuration changes without leaking.

## Error handling

The rule is that a reminder app must not crash, because a crash on a background path silently
stops reminders and the user does not find out until they have missed a week.

- `AlarmScheduler` catches `SecurityException` (permission revoked between check and call) and
  `IllegalStateException` (too many alarms), degrades to an inexact window, and reports the outcome
  as a value rather than throwing.
- `InterventionNotifier` catches refused posts.
- `NotificationChannels` validates every sound URI by actually opening it, and falls back to the
  default if it is no longer readable.
- Backup import validates before it writes and applies in one transaction, so a rejected file
  leaves the database untouched.
- DataStore reads catch `IOException` and fall back to defaults, so a corrupted preferences file
  cannot lock the user out of their own data.
