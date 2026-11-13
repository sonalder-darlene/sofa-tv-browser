package app.sofabrowser.data

import java.text.Collator

/** A bookmarks folder; [parent] null is the top level. */
data class Folder(val id: Long, val parent: Long?, val title: String, val created: Long)

/**
 * The bookmark folders as a tree, built from the database's rows: what is in a folder, its path, what may move where.
 * It holds folders only (bookmarks stay in the database), so even thousands of folders are a small list. Names are
 * matched without regard to case: "News" and "news" in one folder would look the same on a TV.
 */
class FolderTree(folders: List<Folder>) {

    private val byId = HashMap<Long, Folder>()
    private val children = HashMap<Long?, MutableList<Folder>>()

    init { folders.forEach(::add) }

    val size get() = byId.size

    operator fun get(id: Long?): Folder? = id?.let(byId::get)

    /** The folders in [parent], A to Z. */
    fun children(parent: Long?): List<Folder> = children[parent].orEmpty().sortedWith(compareBy(byName) { it.title })

    /** The folders from the top down to [id], [id] last; empty for the top level or a folder that isn't there. */
    fun path(id: Long?): List<Folder> {
        val out = ArrayList<Folder>()
        var f = get(id)
        // A loop can only come from a damaged database; it stops at the size of the tree.
        while (f != null && out.size <= byId.size) { out += f; f = get(f.parent) }
        return out.asReversed()
    }

    /** How deep [id] sits: 0 for the top level, 1 for a folder at the top. */
    fun depth(id: Long?): Int = path(id).size

    /** [id] and every folder inside it, at any depth. */
    fun subtree(id: Long): Set<Long> {
        val out = LinkedHashSet<Long>()
        val todo = ArrayDeque(listOf(id))
        while (todo.isNotEmpty()) {
            val f = todo.removeFirst()
            if (out.add(f)) children[f]?.forEach { todo += it.id }
        }
        return out
    }

    /** How many levels [id] spans: 1 for a folder with no folders in it. */
    private fun height(id: Long): Int {
        val visited = HashSet<Long>()
        fun h(f: Long): Int = if (!visited.add(f)) 0 else 1 + (children[f]?.maxOfOrNull { h(it.id) } ?: 0)
        return h(id)
    }

    /** The folder called [title] in [parent], whatever its case. */
    fun find(parent: Long?, title: String): Folder? =
        children[parent]?.firstOrNull { it.title.equals(title.trim(), ignoreCase = true) }

    /** Whether a new folder can go in [parent]: it isn't already at the deepest level. */
    fun canHold(parent: Long?): Boolean = (parent == null || get(parent) != null) && depth(parent) < MAX_DEPTH

    /** Whether folder [id] can move into [to]: never into itself or a folder inside it, never past the deepest level. */
    fun canMove(id: Long, to: Long?): Boolean =
        get(id) != null && (to == null || get(to) != null && to !in subtree(id)) && depth(to) + height(id) <= MAX_DEPTH

    /** Every folder depth first, each folder's own folders A to Z under it, with its depth (1 at the top). */
    fun outline(): List<Pair<Folder, Int>> {
        val out = ArrayList<Pair<Folder, Int>>()
        fun walk(parent: Long?, depth: Int) {
            for (f in children(parent)) { out += f to depth; if (depth < MAX_DEPTH) walk(f.id, depth + 1) }
        }
        walk(null, 1)
        return out
    }

    /**
     * The folder at [path] (names from the top), made where missing with [make], which stores a folder and gives its
     * id. A folder already there with the same name is used, so the same path twice gives the same folder. Names are
     * cleaned as a folder name typed by hand is; an empty one is skipped, and a path deeper than [MAX_DEPTH] ends at
     * that depth.
     */
    fun resolve(path: List<String>, make: (parent: Long?, title: String) -> Long): Long? {
        var at: Long? = null
        for (raw in path) {
            val name = cleanName(raw)
            if (name.isEmpty()) continue
            if (depth(at) >= MAX_DEPTH) break
            at = (find(at, name) ?: Folder(make(at, name), at, name, 0L).also(::add)).id
        }
        return at
    }

    /** A folder made or changed after the tree was read. */
    fun add(f: Folder) {
        remove(f.id)
        byId[f.id] = f
        children.getOrPut(f.parent) { ArrayList() } += f
    }

    fun remove(id: Long) {
        val old = byId.remove(id) ?: return
        children[old.parent]?.removeAll { it.id == id }
    }

    /** The names along [id]'s path, from the top: the folder as a bookmarks file or a backup has it. */
    fun pathNames(id: Long?): List<String> = path(id).map { it.title }

    companion object {
        /** Folders nest this deep at most; a file's deeper folders go into the one at this depth. */
        const val MAX_DEPTH = 20

        const val MAX_NAME = 200

        /** A to Z as people read it: accents and case don't split the list. */
        val byName: Comparator<String> = Collator.getInstance().apply { strength = Collator.SECONDARY }.let { c ->
            Comparator { a, b -> synchronized(c) { c.compare(a, b) }.takeIf { it != 0 } ?: a.compareTo(b) }
        }

        /** A name on one line, without spaces around it, at most [MAX_NAME] characters. */
        fun cleanName(name: String): String =
            name.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).trim()
    }
}
