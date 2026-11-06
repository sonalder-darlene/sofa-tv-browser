package app.sofabrowser.ui

import android.content.Context
import app.sofabrowser.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Dates in Sofa's own words. Android's formatters write them in the TV's language, so an English sentence such as
 * "Updated …" would end in another language; Sofa is English only, so its dates are too.
 */
object Dates {
    /** How long ago [t] was, as counted for "Updated …": by minutes and hours within a day, then by calendar days. */
    sealed class Ago {
        data class Minutes(val n: Int) : Ago()
        data class Hours(val n: Int) : Ago()
        object Yesterday : Ago()
        data class Days(val n: Int) : Ago()
        object Long : Ago()
    }

    fun ago(t: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Ago {
        val elapsed = (now - t).coerceAtLeast(0)
        val days = daysBetween(t, now, zone)
        return when {
            elapsed < HOUR -> Ago.Minutes((elapsed / MINUTE).toInt().coerceAtLeast(1))
            elapsed < DAY && days == 0 -> Ago.Hours((elapsed / HOUR).toInt())
            days <= 1 -> Ago.Yesterday
            days <= MAX_DAYS -> Ago.Days(days)
            else -> Ago.Long
        }
    }

    /** "5 minutes ago", "yesterday", "on 6 Nov 2026": to follow "Updated". */
    fun ago(context: Context, t: Long, now: Long = System.currentTimeMillis()): String {
        val r = context.resources
        return when (val a = ago(t, now)) {
            is Ago.Minutes -> r.getQuantityString(R.plurals.minutes_ago, a.n, a.n)
            is Ago.Hours -> r.getQuantityString(R.plurals.hours_ago, a.n, a.n)
            Ago.Yesterday -> context.getString(R.string.yesterday_lower)
            is Ago.Days -> r.getQuantityString(R.plurals.days_ago, a.n, a.n)
            Ago.Long -> context.getString(R.string.on_date, date(t))
        }
    }

    /** Calendar days from [t] to [now] in [zone]: 0 the same day, 1 the day before, across year ends too. */
    fun daysBetween(t: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        fun dayNumber(ms: Long): Long {
            val local = Calendar.getInstance(zone).apply { timeInMillis = ms }
            // That date's midnight in UTC, counted in days, so a day with a clock change still counts as one.
            val utc = Calendar.getInstance(UTC).apply {
                clear()
                set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
            }
            return utc.timeInMillis / DAY
        }
        return (dayNumber(now) - dayNumber(t)).toInt()
    }

    /** "6 Nov 2026". */
    fun date(t: Long, zone: TimeZone = TimeZone.getDefault()): String = format("d MMM yyyy", zone).format(Date(t))

    /** "Thursday 5 November", with the year when it isn't this one: a History day heading. */
    fun day(t: Long, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
        val year = { ms: Long -> Calendar.getInstance(zone).apply { timeInMillis = ms }.get(Calendar.YEAR) }
        return format(if (year(t) == year(now)) "EEEE d MMMM" else "EEEE d MMMM yyyy", zone).format(Date(t))
    }

    private fun format(pattern: String, zone: TimeZone) = SimpleDateFormat(pattern, Locale.ENGLISH).apply { timeZone = zone }

    private val UTC = TimeZone.getTimeZone("UTC")
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    /** Beyond this, "on 6 Nov 2026" says more than "45 days ago". */
    private const val MAX_DAYS = 30
}
