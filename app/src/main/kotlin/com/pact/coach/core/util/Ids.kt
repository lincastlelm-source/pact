package com.pact.coach.core.util

import java.util.UUID

/**
 * All primary keys are string UUIDs. They are generated on-device, never coordinated with a
 * server, and are stable across export/import which makes backup merging possible.
 */
object Ids {
    fun new(): String = UUID.randomUUID().toString()

    /** True when [value] looks like a UUID. Used to validate imported backups. */
    fun isValid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess
}
