# Testing

## Running

```bash
./gradlew :app:testDebugUnitTest          # everything below except the device tests
./gradlew :app:connectedDebugAndroidTest  # device / emulator required
```

HTML report: `app/build/reports/tests/testDebugUnitTest/index.html`

## Layout

```
app/src/test/kotlin/         runs on the JVM — no device needed
├── domain/                  pure Kotlin: recurrence, generation, state, recovery, stats, coaching
├── data/                    Room + backup, via Robolectric against real SQLite
└── ui/                      Compose tests, via Robolectric

app/src/androidTest/kotlin/  device / emulator only
└── FlowInstrumentedTest     real Application, real database file, real AlarmManager
```

The important structural choice: **the Room and Compose tests live in `src/test`, not
`src/androidTest`.** Robolectric runs Room against real SQLite and Compose against a real
composition, so the schema, the indices, the transactions and the semantics tree are genuinely
exercised — but the suite still runs in seconds on any machine, with no emulator and no CI device
farm. Only things that cannot be meaningfully faked are left for `androidTest`.

## What is covered

### Recurrence — `RecurrenceEngineTest` (23 tests)

Every rule type, `startDate` and `endDate` bounds, disabled schedules, `EVERY_N_DAYS` anchoring,
`nextOccurrence`, inclusive ranges, inverted ranges, the times-per-week spread, week-start with a
configurable first day, and the human-readable description. Plus validation: no days selected, a
time-based behavior with no times, an inverted date range, a zero interval, an out-of-range
times-per-week.

### Instance generation — `InstanceGeneratorTest` (15 tests)

The behaviour this app depends on most:

- **Two times a day produce two independent occurrences** with distinct indices and dedup keys —
  the evening session cannot overwrite the morning one.
- Occurrence indices follow chronological order even when times are entered unsorted.
- **Regeneration is idempotent**: the same window yields identical dedup keys.
- Paused behaviors generate nothing.
- One-day skips, multi-day vacations, and exceptions belonging to another behavior.
- Untimed and times-per-week generation.
- **DST**: a spring-forward gap resolves to the first valid moment rather than throwing; an
  autumn-back overlap is deterministic across calls.
- The horizon is respected; every generated row is its own chain root.

### State machine — `InstanceStateMachineTest` (10 tests)

The documented happy paths, and the invariants that matter:

- `COMPLETED → MISSED` is impossible, so a background sweep can never erase a real completion.
- `PASSED → COMPLETED` and `MISSED → COMPLETED` are allowed, because a user who did it anyway
  should be able to say so.
- `RECOVERED` is closed; it hands off to its successor.
- Self-transitions are always legal, so a double tap is harmless.
- Only unresolved states are sweepable.

### Recovery — `RecoveryEngineTest`, `RecoveryChainTest` (13 tests)

The original keeps its planned time; the successor is linked and measured from *now* rather than
from the original slot; recovery can cross midnight; it is refused when disabled, when the
instance is closed, past the configured limit, or with a nonsensical delay. Chain outcomes:
recovered-then-completed reads as a completion, recovered-twice-then-missed as a miss, and an
in-flight chain is not resolved.

### Statistics — `StatsCalculatorTest` (10 tests)

Rates are computed over **chains, not rows** — the test that pins down the product's central
claim. Recovery score is independent of recovery rate. Pass and miss are never lumped together.
Pending chains are excluded. Streaks count only days that had something scheduled, and a day
counts only when every occurrence on it was completed. Minimum completions are tracked separately
from full-target ones. An empty history returns zeros rather than crashing.

### Coaching — `CoachingEngineTest` (9 tests)

Each rule from the specification: not-enough-data honesty, positive reinforcement above 85%,
low-completion review below 50%, frequent recovery, frequent passing, minimum-heavy completion,
and the time-of-day rule — including that it produces an **applicable** `ScheduleSuggestion`
naming the right schedule and times. Also that intensity caps output and that attention items
sort ahead of praise.

### Instructions — `InstructionResolverTest` (9 tests)

Most-specific-first resolution across default / weekday / exact date / occurrence index, that a
day plan **only overrides the fields it sets**, rest days, checklist items scoped to a day plan,
and the manual progression ramp (including that it holds at the last defined step and does nothing
when disabled).

### Conflicts — `ConflictDetectorTest` (5 tests)

Same-minute clashes, overlapping durations with different start times, well-separated behaviors,
the same behavior twice a day (intentional, not a conflict), and untimed behaviors.

### Database and repositories — `DatabaseTest` (28 tests)

Round trips, CSV time encoding, unique alarm request codes, and:

- **Regenerating a window inserts zero duplicates** — the unique dedup index doing its job.
- Two daily occurrences keep separate history.
- Commit → complete records timestamps and quality; minimum-only is recorded distinctly.
- Recovery preserves the original and links the successor; the chain reads as one completion.
- Pass with and without a required reason; pass-then-complete.
- An impossible transition is refused rather than corrupting the row.
- Every decision writes an audit event in the same transaction.
- The missed sweep respects grace windows and **never overwrites a recorded decision**.
- A time-zone change preserves wall-clock time and moves the absolute instant.
- Deleting a goal **detaches** behaviors; deleting a behavior cascades.
- Editing a schedule drops stale future rows but keeps history.
- Pausing clears future occurrences; changing a sound bumps the channel version.
- Applying a coaching suggestion moves exactly one time, and fails cleanly if it has since changed.
- An unknown enum value falls back instead of throwing.

### Backup — `BackupServiceTest` (23 tests)

Round trip through export → parse → import, the pre-import summary, merge that never overwrites,
replace that clears first, and a recovery chain surviving with its links intact.

The rejection paths get more attention than the happy one, because a backup file is untrusted
input: not JSON, empty, valid JSON that is not a backup, a newer format version, a missing
version, a behavior pointing at a missing goal, duplicate ids, a nameless goal, an out-of-range
time, history referencing an unknown behavior, and an oversized file. Plus: **a rejected file
leaves the database untouched**, and imported content containing script tags and SQL fragments is
stored as literal text.

### UI — `InterventionScreenTest` (4 tests)

That all three decisions are present and independently labelled (so they are distinguishable
without colour), that recovery is worded as a normal choice rather than a failure, that every pass
reason is reachable, and that even the strongest enforcement level tells the user they can leave.

### Device tests — `FlowInstrumentedTest` (7 tests)

What needs real Android: two occurrences from one behavior, commit → complete through the state
machine, recovery arming a real alarm via `AlarmManager`, passing with a reason, **repeated
`SchedulingCoordinator.refresh()` not duplicating anything**, the app launching with its
navigation intact, and per-behavior notification channel creation.

## Current status

**149 unit tests, all passing.** The instrumented suite compiles; it needs a connected device to
run and has not been executed in this environment.

## Notable finding

The backup tests caught a genuine defect during development: re-importing a backup crashed with a
`UNIQUE constraint failed: action_events.id`, because audit rows were inserted with a plain
`@Insert`. Fixed by scoping imported events to content the import actually accepted and switching
to insert-or-ignore, which is correct anyway — audit rows are immutable and identified by a stable
id, so a row that already exists is the same row.

## What is deliberately not tested

- **Actual alarm delivery timing.** No test can prove an OEM's power manager will not drop an
  alarm. That is what the in-app self-test in Settings is for: it fires a real alarm through the
  real pipeline so the user can verify their own device.
- **Full-screen intent presentation over the lock screen.** Platform-gated and device-specific.
- **Notification appearance.** The posting path is exercised; how a given launcher renders it is
  not something a test can assert usefully.

## Writing new tests

Use `FixedClockProvider` rather than real time — it is mutable, so `advanceMinutes` /
`advanceDays` drive sweeps, streaks and recovery windows deterministically. No test should ever
depend on the wall clock.

For database tests, build an in-memory Room database and construct repositories directly; there is
no DI framework to configure.

Date fixtures in this suite are anchored to **2026-09-07, a Monday**, so weekday assertions read
clearly.
