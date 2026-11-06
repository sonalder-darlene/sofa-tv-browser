package app.sofabrowser.browser

/**
 * The tabs of one side (normal or private browsing): their order in the menu's strip, the one in sight, and the order
 * they were in sight in. That order decides where Sofa goes when a tab closes (the one in sight before it) and which
 * tabs keep their page loaded (the most recent). Plain Kotlin, so the rules are unit-tested.
 */
class TabList<T : Any>(val max: Int) {

    private val order = ArrayList<T>()
    /** Most recently in sight first; a tab added and never selected is the least recent. */
    private val recent = ArrayList<T>()

    val items: List<T> get() = order
    val size get() = order.size
    val full get() = order.size >= max
    /** The tab in sight (or behind the start page); null only while the list is empty. */
    val current: T? get() = recent.firstOrNull()

    /** Adds [tab] right after [after], or at the end when [after] is null or not here. False when full. */
    fun add(tab: T, after: T? = null): Boolean {
        if (full || tab in order) return false
        val i = after?.let(order::indexOf) ?: -1
        if (i < 0) order.add(tab) else order.add(i + 1, tab)
        recent.add(tab)
        return true
    }

    /** [tab] comes into sight. */
    fun select(tab: T) {
        if (tab !in order) return
        recent.remove(tab)
        recent.add(0, tab)
    }

    /** Takes [tab] out. Returns the tab now in sight: the one that was in sight before it, or null when none is left. */
    fun remove(tab: T): T? {
        order.remove(tab)
        recent.remove(tab)
        return current
    }

    /** The tabs out of sight, the most recently seen first. */
    fun background(): List<T> = recent.drop(1)

    /**
     * Of the tabs out of sight whose page is loaded ([live]), the ones past the [keep] most recently seen: the pages
     * to unload, the least recently seen last.
     */
    fun beyond(keep: Int, live: (T) -> Boolean): List<T> = background().filter(live).drop(keep.coerceAtLeast(0))

    companion object {
        /**
         * The tab list as Settings keeps it (`tabs`): the index of the tab in sight on the first line, then one web
         * address per line. Addresses only: never a page's content, its history or a private tab.
         */
        fun encode(addresses: List<String>, current: Int): String {
            val kept = addresses.filter(Browser::isWebUrl).filter { '\n' !in it && '\r' !in it }
            if (kept.isEmpty()) return ""
            val at = addresses.getOrNull(current)?.let { kept.indexOf(it) }?.takeIf { it >= 0 } ?: 0
            return (listOf(at.toString()) + kept).joinToString("\n")
        }

        /** What [encode] wrote: the addresses (web ones, at most [max]) and the index in sight. Anything else gives none. */
        fun decode(stored: String?, max: Int): Pair<List<String>, Int> {
            val lines = stored?.split('\n').orEmpty()
            val at = lines.firstOrNull()?.trim()?.toIntOrNull() ?: return emptyList<String>() to 0
            val addresses = lines.drop(1).map { it.trim() }.filter(Browser::isWebUrl).take(max)
            return addresses to if (at in addresses.indices) at else 0
        }

        /**
         * How many tabs out of sight keep their page loaded, from the TV's memory. Every loaded page costs the shared
         * page renderer its memory; one unloaded comes back from its address and history when chosen again.
         */
        fun liveBackground(totalMemory: Long, lowRam: Boolean): Int = when {
            lowRam || totalMemory < 1_700L * MB -> 0
            totalMemory < 2_600L * MB -> 1
            totalMemory < 3_600L * MB -> 2
            else -> 3
        }

        private const val MB = 1024L * 1024L
    }
}
