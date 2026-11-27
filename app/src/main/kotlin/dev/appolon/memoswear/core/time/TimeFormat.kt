package dev.appolon.memoswear.core.time

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Short date and time labels that fit a watch. */
object TimeFormat {
    fun relative(
        instant: Instant,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        use24h: Boolean = true,
    ): String {
        val minutes = Duration.between(instant, now).toMinutes()
        val time = instant.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        return when {
            minutes < 1 -> "Now"
            minutes < 60 -> "${minutes}m ago"
            time.toLocalDate() == today -> time.format(timePattern(use24h, locale))
            time.toLocalDate() == today.minusDays(1) -> "Yesterday"
            time.year == today.year -> time.format(DateTimeFormatter.ofPattern("d MMM", locale))
            else -> time.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
    }

    fun full(
        instant: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
        use24h: Boolean = true,
    ): String {
        val date = instant.atZone(zone).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        return "$date, ${instant.atZone(zone).format(timePattern(use24h, locale))}"
    }

    fun clock(
        instant: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        use24h: Boolean = true,
    ): String = instant.atZone(zone).format(timePattern(use24h, Locale.getDefault()))

    private fun timePattern(use24h: Boolean, locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", locale)

    fun localDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate = instant.atZone(zone).toLocalDate()
}
