package app.sofabrowser.data

/** "Keep history for": how long visited pages stay in History. Plain Kotlin, so the rules are unit-tested. */
object HistoryRetention {

    /** Not a number of days: keep everything, or keep nothing. */
    const val ALWAYS = -1
    const val NEVER = 0
    /** The choices, in days, in the order of the strings in R.array.history_periods. */
    val CHOICES = listOf(7, 30, 90, ALWAYS, NEVER)
    const val DEFAULT = 90

    /** Pages visited before this time are removed; null keeps everything. [NEVER] keeps nothing. */
    fun cutoff(days: Int, now: Long): Long? = when {
        days == ALWAYS -> null
        days <= NEVER -> now
        else -> now - days * 24L * 3600 * 1000
    }

    /** Whether new visits are recorded at all. */
    fun records(days: Int) = days != NEVER

    /** 1.0.0 had an on/off "Remember history" (90 days when on). */
    fun fromOldSwitch(rememberHistory: Boolean) = if (rememberHistory) DEFAULT else NEVER
}
