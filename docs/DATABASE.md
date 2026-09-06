# Database

Room over SQLite, entirely on-device. Schema version **1**, exported to `app/schemas/`.

## Conventions

Applied consistently across every table, and worth knowing before reading the entities:

- **Primary keys are locally generated UUID strings.** No autoincrement, no server coordination.
  This is what makes backup export/import able to merge two devices' data without renumbering
  anything.
- **Dates are epoch days** (`Long`), **times are minute-of-day** (`Int`, 0–1439), **instants are
  epoch milliseconds UTC** (`Long`). Nothing is stored as a formatted string, so ordering and
  range queries are correct and locale-independent.
- **Enums are stored by name**, never by ordinal. Reordering an enum must never silently change
  the meaning of existing rows. Reading is forgiving: an unrecognised value maps to a sensible
  default rather than throwing.
- **Every foreign key that the UI filters on has an explicit index.** SQLite does not create them
  for you, and Room warns if they are missing.

## Tables

### `goals`
The reason behind the behaviors. `whyItMatters` is surfaced on the intervention screen, which is
why it is a first-class column rather than part of the description.

Indices: `status`, `category`.

### `behaviors`
The largest table by column count: identity, targets, reminder configuration, recovery policy,
reflection settings.

`goalId` is `ON DELETE SET NULL`, **not** cascade. Deleting a goal detaches its behaviors; it does
not destroy months of history. That is a product decision encoded in the schema, and there is a
test for it.

`channelVersion` exists because Android notification channels are immutable once created. Changing
a sound-affecting field bumps it so a fresh channel is minted. See
[ALARM_SYSTEM.md](ALARM_SYSTEM.md).

`isTimeBased` and `measurement` are separate columns rather than one "behavior type" enum, because
they are genuinely orthogonal: a checklist can be anchored to 06:30, and a duration behavior can
float across the day. The four archetypes in the creation flow are presets over this pair.

Indices: `goalId`, `isActive`.

### `schedules`
A recurrence rule plus its clock times. One behavior may own several.

`timesOfDayCsv` holds minute-of-day values, ascending and de-duplicated. A child table was
considered and rejected: the list is small, always read and written whole, and the position in the
sorted list *is* the occurrence index that day plans key off. A join would add cost without adding
meaning.

Index: `behaviorId`.

### `daily_instructions`
What a behavior means on a particular day. `dayOfWeek` (ISO 1–7), `dateEpochDay`, and
`occurrenceIndex` are each nullable, and the combination determines specificity. A row with all
three null is the default.

Resolution order, most specific first: date + occurrence → date → weekday + occurrence → weekday →
behavior default. A matching row only overrides the fields it actually sets, so a Tuesday row
saying "Upper body" still inherits the behavior's duration.

Indices: `behaviorId`, `(behaviorId, dayOfWeek)`, `(behaviorId, dateEpochDay)`.

### `checklist_items` and `checklist_ticks`
Items belong to a behavior, optionally scoped to one day plan via `dailyInstructionId`. Ticks are
per-occurrence, keyed `(instanceId, checklistItemId)`.

### `behavior_instances`
The busiest table, and the one carrying the interesting invariants.

**`dedupKey` is uniquely indexed.** For generated rows it is
`s|behaviorId|scheduleId|epochDay|occurrenceIndex`; for recovery rows, `r|uuid`. This single
constraint is what makes horizon regeneration idempotent and what prevents duplicate alarms.
Everything else in the scheduling design leans on it.

**Recovery links.** `originInstanceId` points at the row this one replaces; `rootInstanceId`
groups the whole chain; `recoveryDepth` counts hops. The original keeps its planned time, so
history shows both the plan and what happened.

**Dual time storage.** `scheduledDateEpochDay` + `scheduledTimeMinutes` are the wall-clock intent;
`scheduledAtUtcMillis` is the absolute firing instant; `zoneId` records which zone produced it.
A time-zone change recomputes the instant while preserving the intent.

`alarmRequestCode` is allocated as `MAX(alarmRequestCode) + 1` inside the insert transaction, so
`PendingIntent` identities are unique and stable.

Indices: unique `dedupKey`, plus `behaviorId`, `scheduledDateEpochDay`, `state`,
`scheduledAtUtcMillis`, `rootInstanceId`, and the composite `(behaviorId, scheduledDateEpochDay)`
that the Today and history screens query on.

### `action_events`
Append-only audit trail. Written in the **same transaction** as every state change, so the log can
never disagree with the instance table. Never updated, only inserted, and pruned after 400 days.

Because rows are immutable and identified by a stable id, backup import uses insert-or-ignore:
re-importing the same file is a no-op rather than a constraint violation. (That path was a real
bug, caught by a test.)

Indices: `instanceId`, `behaviorId`, `atMillis`.

### `behavior_exceptions`
Skips, pauses, vacations and holidays. A null `behaviorId` applies to every behavior. Exceptions
remove days from generation without touching the permanent schedule.

### `behavior_templates`
Reusable starting points, including a handful seeded on first run. Editable and deletable like any
other; nothing is written into a user's data until a template is applied.

### `progression_steps`
An optional manual ramp: week 0 is 10 minutes, week 3 is 30. The app never raises a target on its
own — these rows exist only because the user wrote them.

### `coach_messages`
Generated coaching observations, so the same one is not re-announced every time a screen opens.
`dedupKey` is `behaviorId|kind|isoWeek`, uniquely indexed, which lets an observation resurface next
week if it still holds. Pruned alongside the audit trail.

## Migration policy

**Destructive migration is not configured.** If a future schema change ships without a matching
`Migration`, the app fails loudly in development rather than silently deleting months of a user's
history on their phone.

Adding a version requires three things:

1. A `Migration` in `Migrations.ALL` (`data/db/PactDatabase.kt`).
2. The exported JSON schema for the new version committed under `app/schemas/`, which is what a
   migration test reads.
3. A migration test using `MigrationTestHelper`.

Enum constants must not be renamed without an accompanying data migration **and** an alias in the
backup importer, because old database rows and old backup files carry the old spelling.

## Foreign keys

`PRAGMA foreign_keys = ON` is set in the database `onOpen` callback. Room does not enable it by
default, and the cascade behaviour on behavior deletion depends on it.

Cascades: deleting a behavior removes its schedules, day plans, checklist items, progression steps
and instances. Deleting a goal detaches its behaviors and touches nothing else.

## Reactive reads

DAOs return `Flow` for anything the UI observes, so screens update automatically when a decision
is recorded — including one made from a notification while the app was in the background.
Suspend functions are used for one-shot reads inside repositories and background work.

## Size and performance

The horizon bounds the instance table: roughly *(behaviors × occurrences per day × 14)* live rows
at any time, plus completed history. A user with 20 behaviors averaging two occurrences a day
carries about 560 scheduled rows and accumulates around 15,000 history rows a year — trivial for
SQLite with the indices above.

The audit trail and coach messages are pruned after 400 days by `AppContainer.pruneOldRecords`,
called from the maintenance worker.
