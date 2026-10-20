package app.sofabrowser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

data class Site(val id: Long, val title: String, val url: String)

data class HistoryEntry(val id: Long, val title: String, val url: String, val visitedAt: Long)

/**
 * All persistent user data: quick-access tiles, bookmarks and history. Everything stays on the device;
 * there is no sync and no upload.
 */
class Db private constructor(context: Context) : SQLiteOpenHelper(context, "sofa.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tiles (id INTEGER PRIMARY KEY, title TEXT NOT NULL, url TEXT NOT NULL, position INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY, title TEXT NOT NULL, url TEXT NOT NULL UNIQUE, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY, title TEXT NOT NULL, url TEXT NOT NULL, visited INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_visited ON history (visited)")
        db.execSQL("CREATE INDEX history_url ON history (url)")
        createDownloads(db)
        DEFAULT_TILES.forEachIndexed { i, (title, url) ->
            db.insert("tiles", null, ContentValues().apply {
                put("title", title); put("url", url); put("position", i)
            })
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDownloads(db)
    }

    /** Downloads, with enough state to resume them (byte offset, validator) after a restart. */
    private fun createDownloads(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE downloads (
              id INTEGER PRIMARY KEY, url TEXT NOT NULL, name TEXT NOT NULL, mime TEXT,
              total INTEGER NOT NULL DEFAULT -1, done INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL,
              error TEXT, created INTEGER NOT NULL, finished INTEGER NOT NULL DEFAULT 0,
              target TEXT, referer TEXT, user_agent TEXT, validator TEXT, private INTEGER NOT NULL DEFAULT 0
            )""".trimIndent())
    }

    // Tiles

    fun tiles(): List<Site> = query("SELECT id, title, url FROM tiles ORDER BY position") {
        Site(it.getLong(0), it.getString(1), it.getString(2))
    }

    fun addTile(title: String, url: String) {
        val db = writableDatabase
        val next = db.compileStatement("SELECT COALESCE(MAX(position) + 1, 0) FROM tiles").simpleQueryForLong()
        db.insert("tiles", null, ContentValues().apply {
            put("title", title); put("url", url); put("position", next)
        })
    }

    fun updateTile(id: Long, title: String, url: String) {
        writableDatabase.update("tiles", ContentValues().apply { put("title", title); put("url", url) },
            "id = ?", arrayOf(id.toString()))
    }

    fun removeTile(id: Long) {
        writableDatabase.delete("tiles", "id = ?", arrayOf(id.toString()))
    }

    /** Persists [ids] as the new tile order. */
    fun reorderTiles(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEachIndexed { i, id ->
                db.update("tiles", ContentValues().apply { put("position", i) }, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // Bookmarks

    fun bookmarks(): List<Site> = query("SELECT id, title, url FROM bookmarks ORDER BY created DESC") {
        Site(it.getLong(0), it.getString(1), it.getString(2))
    }

    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ?", arrayOf(url)).use { it.moveToFirst() }

    fun addBookmark(title: String, url: String) {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("title", title.ifBlank { url }); put("url", url); put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeBookmark(url: String) {
        writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
    }

    // History

    fun history(limit: Int = 500): List<HistoryEntry> =
        query("SELECT id, title, url, visited FROM history ORDER BY visited DESC LIMIT $limit") {
            HistoryEntry(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3))
        }

    /** Records a visit, collapsing a reload or same-page revisit within the last minute into one row. */
    fun recordVisit(title: String, url: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val updated = db.update("history", ContentValues().apply { put("title", title); put("visited", now) },
            "url = ? AND visited > ?", arrayOf(url, (now - 60_000).toString()))
        if (updated == 0) {
            db.insert("history", null, ContentValues().apply {
                put("title", title); put("url", url); put("visited", now)
            })
        }
    }

    fun updateHistoryTitle(url: String, title: String) {
        writableDatabase.execSQL(
            "UPDATE history SET title = ? WHERE id = (SELECT id FROM history WHERE url = ? ORDER BY visited DESC LIMIT 1)",
            arrayOf(title, url))
    }

    fun removeHistory(id: Long) {
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    /** Removes the pages "Keep history for" no longer keeps ([days] as in HistoryRetention). */
    fun pruneHistory(days: Int) {
        val cutoff = HistoryRetention.cutoff(days, System.currentTimeMillis()) ?: return
        writableDatabase.delete("history", "visited < ?", arrayOf(cutoff.toString()))
    }

    /**
     * Sites visited at least [minVisits] times in the last 30 days, grouped by host so that many pages of one
     * site count as that site. Hosts already pinned as tiles are left out.
     */
    fun mostVisited(limit: Int, minVisits: Int = 3): List<Site> {
        val since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val pinned = tiles().mapNotNull { hostOf(it.url) }.toSet()
        class Agg(val scheme: String, val latest: Long) {
            var count = 0
            var rootTitle: String? = null
        }
        val byHost = LinkedHashMap<String, Agg>()
        readableDatabase.rawQuery("SELECT title, url, visited FROM history WHERE visited > ? ORDER BY visited DESC",
            arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) {
                val uri = Uri.parse(c.getString(1))
                val scheme = uri.scheme
                val host = uri.host?.lowercase() ?: continue
                if (scheme != "https" && scheme != "http") continue
                val agg = byHost.getOrPut(host) { Agg(scheme, c.getLong(2)) }
                agg.count++
                if (agg.rootTitle == null && (uri.path.isNullOrEmpty() || uri.path == "/")) agg.rootTitle = c.getString(0)
            }
        }
        return byHost.entries
            .filter { it.value.count >= minVisits && it.key.removePrefix("www.") !in pinned }
            .sortedWith(compareByDescending<Map.Entry<String, Agg>> { it.value.count }.thenByDescending { it.value.latest })
            .take(limit)
            .mapIndexed { i, (host, agg) ->
                Site(-1L - i, agg.rootTitle?.takeIf { it.isNotBlank() } ?: prettyHost(host), "${agg.scheme}://$host/")
            }
    }

    /** Prefix/substring matches from bookmarks and history for the address bar, all computed locally. */
    fun suggest(text: String, limit: Int = 6): List<Site> {
        if (text.isBlank()) return emptyList()
        val like = "%" + text.replace("%", "").replace("_", "") + "%"
        return query("""
            SELECT id, title, url FROM (
              SELECT id, title, url, 2 AS rank, created AS t FROM bookmarks WHERE url LIKE ?1 OR title LIKE ?1
              UNION ALL
              SELECT MAX(id), title, url, 1 AS rank, MAX(visited) AS t FROM history
                WHERE url LIKE ?1 OR title LIKE ?1 GROUP BY url
            ) GROUP BY url ORDER BY MAX(rank) DESC, MAX(t) DESC LIMIT $limit""".trimIndent(), arrayOf(like)) {
            Site(it.getLong(0), it.getString(1), it.getString(2))
        }
    }

    private fun <T> query(sql: String, args: Array<String>? = null, row: (android.database.Cursor) -> T): List<T> =
        readableDatabase.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }

    companion object {
        private val DEFAULT_TILES = listOf(
            "YouTube" to "https://www.youtube.com/",
            "Crunchyroll" to "https://www.crunchyroll.com/videos/anime",
            "IGN" to "https://www.ign.com/videos",
            "Plex" to "https://app.plex.tv/desktop/#!/",
            "Wikipedia" to "https://www.wikipedia.org/",
        )

        fun hostOf(url: String): String? =
            runCatching { Uri.parse(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")

        /** "news.example.co.uk" -> "News Example"; used when a site never gave its home page a title. */
        fun prettyHost(host: String): String {
            // IP addresses (and "localhost") are already as readable as they get.
            if (host.all { it.isDigit() || it == '.' } || ':' in host || '.' !in host) return host
            val parts = host.removePrefix("www.").split('.')
            val core = if (parts.size > 2 && parts[parts.size - 2].length <= 3) parts.dropLast(2) else parts.dropLast(1)
            return core.ifEmpty { parts }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        }

        @Volatile private var instance: Db? = null

        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(context.applicationContext).also { instance = it }
        }
    }
}
