package com.pact.coach.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Indirection over "what time is it" so the scheduling, recurrence and coaching engines can be
 * unit tested deterministically. Nothing in the domain layer is allowed to call
 * [System.currentTimeMillis] or [Instant.now] directly.
 */
interface ClockProvider {
    fun now(): Instant
    fun zone(): ZoneId

    fun nowLocal(): LocalDateTime = LocalDateTime.ofInstant(now(), zone())
    fun today(): LocalDate = nowLocal().toLocalDate()
    fun nowMillis(): Long = now().toEpochMilli()
}

/** Production implementation, reading the device clock and the device's current time zone. */
class SystemClockProvider : ClockProvider {
    override fun now(): Instant = Instant.now()

    /** Read every call: the user can change time zone while the process is alive. */
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/** Test implementation. Mutable so a test can advance time. */
class FixedClockProvider(
    var instant: Instant,
    var zoneId: ZoneId = ZoneId.of("UTC"),
) : ClockProvider {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId

    fun advanceMinutes(minutes: Long) {
        instant = instant.plusSeconds(minutes * 60)
    }

    fun advanceDays(days: Long) {
        instant = instant.plusSeconds(days * 86_400)
    }
}
