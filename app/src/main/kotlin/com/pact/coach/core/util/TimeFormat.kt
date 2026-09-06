package com.pact.coach.core.util

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Formatting helpers. Everything takes an explicit `use24Hour` flag rather than reading a
 * global, so the same call site works in previews, tests and the running app.
 */
object TimeFormat {

    fun time(time: LocalTime, use24Hour: Boolean): String =
        if (use24Hour) {
            String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
        } else {
            val hour12 = when (val h = time.hour % 12) {
                0 -> 12
                else -> h
            }
            val suffix = if (time.hour < 12) "AM" else "PM"
            String.format(Locale.ROOT, "%d:%02d %s", hour12, time.minute, suffix)
        }

    fun minutesOfDay(minutes: Int, use24Hour: Boolean): String =
        time(LocalTime.of(minutes / 60, minutes % 60), use24Hour)

    /** "45 min", "1 h 30 min", "2 h". */
    fun duration(minutes: Int): String = when {
        minutes <= 0 -> "-"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    /** Compact relative label used on the dashboard: "in 12 min", "2 h ago", "now". */
    fun relative(deltaSeconds: Long): String {
        val abs = kotlin.math.abs(deltaSeconds)
        val label = when {
            abs < 60 -> "now"
            abs < 3600 -> "${abs / 60} min"
            abs < 86_400 -> "${abs / 3600} h"
            else -> "${abs / 86_400} d"
        }
        return when {
            label == "now" -> "now"
            deltaSeconds > 0 -> "in $label"
            else -> "$label ago"
        }
    }


    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${date.dayOfMonth}"
    }
}
