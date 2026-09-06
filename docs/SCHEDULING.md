# Scheduling

The scheduling engine answers one question: **what should happen next, and when exactly?**

It is split so that the hard part is testable. All date arithmetic lives in pure Kotlin with no
Android dependency and no ambient clock; only the last step, arming an OS alarm, touches the
platform.

```
Schedule (recurrence rule)
    │  RecurrenceEngine          which calendar days?
    ▼
LocalDate list
    │  InstanceGenerator         which occurrences on those days, at what absolute instant?
    ▼
BehaviorInstance rows (bounded horizon, deduplicated)
    │  SchedulingCoordinator     which of those need an OS alarm right now?
    ▼
AlarmManager
```

## Recurrence

`RecurrenceEngine` answers "does this schedule land on this date". Supported rules:

| Type | Meaning | Fields used |
|---|---|---|
| `ONCE` | A single day | `startDate` |
| `DAILY` | Every day | — |
| `WEEKDAYS` | Monday–Friday | — |
| `WEEKENDS` | Saturday, Sunday | — |
| `DAYS_OF_WEEK` | Chosen days | `daysOfWeekMask` |
| `EVERY_N_DAYS` | Every N days from an anchor | `intervalDays`, `anchorDate` |
| `TIMES_PER_WEEK` | N flexible slots a week | `timesPerWeek` |

Days of the week are a 7-bit mask, ISO numbering (Monday = bit 0 … Sunday = bit 6), via `DayMask`.
`startDate` and the optional `endDate` bound every rule, and a disabled schedule never occurs.

`EVERY_N_DAYS` counts from `anchorDate` rather than from "today", so the cadence is stable no
matter when the app is opened: `(date − anchor) mod interval == 0`.

`TIMES_PER_WEEK` is not tied to particular weekdays. `spreadAcrossWeek` distributes N slots evenly
across the seven days (3 becomes Monday / Wednesday / Friday) so the user is not asked to do
everything on Monday. Weeks are anchored to the schedule's start date, so the spread is stable
across regenerations.

Every scan is bounded by `MAX_SCAN_DAYS` (five years), so a malformed rule can never spin.

### Validation

`RecurrenceValidator` runs as the user edits and rejects: an end date before the start, chosen-days
with nothing selected, an interval below 1 or above 365, a times-per-week outside 1–7, a
time-based behavior with no times, and duplicate times. A behavior cannot have more than 12 times
a day.

## Occurrences

`InstanceGenerator` turns days into `BehaviorInstance` rows.

### Several times a day are separate occurrences

A behavior scheduled at 06:30 and 18:00 produces **two rows a day**, with `occurrenceIndex` 0 and
1. They have their own state, their own history and their own alarms. The evening session can
never overwrite the morning one, and the statistics layer reports them separately.

Times are sorted before indexing, so `occurrenceIndex` always means "the Nth slot of the day" even
if the user entered them out of order. That matters because day-specific instructions can be keyed
to an occurrence index.

### The horizon

Rows are materialised over a rolling **14 days** (`SchedulingHorizon.GENERATION_DAYS`), never for
the whole future. A daily behavior running for five years is 14 rows at a time, not 1,825.

`SchedulingCoordinator.refresh()` tops the window up on app start, boot, time change, behavior
edits, every answered intervention, and every two hours from `MaintenanceWorker`.

### Deduplication

Every generated row carries a deterministic key:

```
s|<behaviorId>|<scheduleId>|<epochDay>|<occurrenceIndex>
```

with a **unique index** on it. Regeneration is `INSERT OR IGNORE`, so re-running an overlapping
window is a no-op rather than a source of duplicate alarms. This single constraint is what makes
`refresh()` safe to call from four different places concurrently.

Recovery instances use `r|<uuid>` instead — they are unique by construction and never regenerated.

### Exceptions

`BehaviorException` rows remove days without touching the permanent schedule: one-off skips,
temporary pauses, vacations and holidays. A `behaviorId` of `null` applies the exception to every
behavior. "Skip 10 September" leaves the daily rule intact; it simply generates nothing that day.

## Time, time zones and DST

Timestamps are stored two ways on purpose:

- `scheduledDate` (epoch day) + `scheduledTimeMinutes` — the **wall-clock intent**: "06:30".
- `scheduledAtUtcMillis` — the **absolute instant** the alarm fires at.
- `zoneId` — the zone that instant was computed in.

Nothing is compared as a string, and nothing is derived from a formatted date.

### Conversion

```kotlin
ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
```

`ZonedDateTime.of` resolves DST edges the way a user expects, which is why it is used rather than
manual offset arithmetic:

- **Spring forward.** On a day where 01:00 jumps to 02:00, a 01:30 reminder does not exist. The
  time is pushed forward by the gap and fires at 02:30. The reminder still happens.
- **Autumn back.** Where 01:30 occurs twice, the earlier offset is chosen — deterministically, so
  regeneration produces the same instant.

Both are covered by tests.

### Time-zone changes

`TimeChangeReceiver` handles `TIMEZONE_CHANGED` and `TIME_SET`, and calls
`refresh(zoneChanged = true)`. That runs `rebaseFutureInstancesToZone`, which recomputes
`scheduledAtUtcMillis` for every future scheduled row from its **preserved wall-clock time** in the
new zone.

The semantic is: *"exercise at 06:30" stays 06:30 after you fly somewhere else.* The absolute
instant moves; the user's intent does not. Past rows are never touched, so history keeps the
instant it actually happened at.

### Manual clock changes

`TIME_SET` fires when the user or the network moves the clock. The same rebase-and-re-arm path
runs. Because generation is deduplicated and alarm identity is stable, moving the clock backwards
and forwards repeatedly cannot accumulate duplicate alarms.

## The missed sweep

An intervention that is never answered has to become `MISSED` — but a sweep must never overwrite a
decision the user made.

Two guards:

1. Only states in `InstanceStateMachine.sweepable` (`SCHEDULED`, `DUE`, `COMMITTED`) are eligible.
   Anything terminal is untouchable.
2. Each behavior sets its own grace window (`missWindowMinutes`, default 120). A `COMMITTED`
   occurrence gets a longer one (12 hours) because the user did start it.

Untimed occurrences simply expire at the end of their day. The sweep looks back seven days at
most, so reinstalling after a long absence does not rewrite ancient history.

## Conflict detection

`ConflictDetector` finds occurrences whose windows overlap — a window being the scheduled time
plus the behavior's target duration, with a 15-minute floor so two zero-duration behaviors booked
at the same minute still register.

Two occurrences of the *same* behavior never conflict: twice a day is intentional.

The app only ever **warns**. It never moves anything. When both sides are high importance the
warning is phrased more prominently, but the resolution is always the user's.

## What is not scheduled

`MaintenanceWorker` (WorkManager, every two hours) is a **safety net**, not the delivery
mechanism. WorkManager cannot honour a precise user-chosen minute, so it is never used to fire an
intervention. It exists to top up the horizon, re-arm alarms, run the missed sweep and prune old
audit rows when exact alarms are unavailable or the app has been closed for days.

See [ALARM_SYSTEM.md](ALARM_SYSTEM.md) for delivery.
