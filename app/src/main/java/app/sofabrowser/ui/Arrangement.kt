package app.sofabrowser.ui

/**
 * An order of things and which of them are shown, as chosen in an [ArrangeEditor]: the menu's buttons ([MenuLayout]),
 * the start page's rows and buttons ([HomeLayout]).
 *
 * Stored as one string ([encode]): ids in order, separated by commas, a hidden one prefixed with "-", for example
 * `watch,home,-back`. "" is the default, so a user who never changed it gets a later version's default. The ids
 * never change, so a stored or exported value stays readable: unknown ids are skipped, and a thing added in a later
 * version shows up at its default place.
 */
class Arrangement<T : Arrangement.Item> private constructor(private val all: List<T>, val entries: List<Entry<T>>) {

    /** Something to arrange, with how an editor shows it. */
    interface Item {
        val id: String
        val icon: Int
        val label: Int
        /** Can't be hidden. */
        val locked: Boolean
    }

    data class Entry<T : Item>(val item: T, val shown: Boolean)

    val shown: List<T> get() = entries.filter { it.shown }.map { it.item }
    val isDefault get() = entries.all { it.shown } && entries.map { it.item } == all
    val hiddenCount get() = entries.count { !it.shown }

    /** [from] moved to [to] (indexes in [entries]); out-of-range moves change nothing. */
    fun move(from: Int, to: Int): Arrangement<T> {
        if (from !in entries.indices || to !in entries.indices || from == to) return this
        return Arrangement(all, entries.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** The one at [index] shown or hidden; a [Item.locked] one stays shown. */
    fun show(index: Int, on: Boolean): Arrangement<T> {
        val e = entries.getOrNull(index) ?: return this
        if (e.item.locked || e.shown == on) return this
        return Arrangement(all, entries.toMutableList().apply { set(index, e.copy(shown = on)) })
    }

    /** [item] shown or hidden, wherever it is. */
    fun show(item: T, on: Boolean) = show(entries.indexOfFirst { it.item == item }, on)

    fun isShown(item: T) = entries.any { it.item == item && it.shown }

    fun encode(): String = if (isDefault) "" else entries.joinToString(",") { (if (it.shown) "" else "-") + it.item.id }

    override fun equals(other: Any?) = other is Arrangement<*> && other.entries == entries
    override fun hashCode() = entries.hashCode()

    companion object {
        /** Everything of [all], in that order, shown. */
        fun <T : Item> default(all: List<T>) = Arrangement(all, all.map { Entry(it, true) })

        fun <T : Item> decode(all: List<T>, stored: String?): Arrangement<T> {
            if (stored.isNullOrBlank()) return default(all)
            val list = ArrayList<Entry<T>>()
            for (raw in stored.split(',')) {
                val token = raw.trim()
                val hidden = token.startsWith("-")
                val item = all.firstOrNull { it.id == token.removePrefix("-") } ?: continue
                if (list.none { it.item == item }) list.add(Entry(item, !hidden || item.locked))
            }
            // One the stored value doesn't name (new in this version) goes after the one before it in the default order.
            for ((i, item) in all.withIndex()) {
                if (list.any { it.item == item }) continue
                val before = all.subList(0, i).asReversed().firstOrNull { b -> list.any { it.item == b } }
                list.add(if (before == null) 0 else list.indexOfFirst { it.item == before } + 1, Entry(item, true))
            }
            return Arrangement(all, list)
        }
    }
}
