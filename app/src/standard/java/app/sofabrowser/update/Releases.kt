package app.sofabrowser.update

/** Android-free helpers for reading GitHub releases; kept pure so they can be unit-tested on the JVM. */
object Releases {

    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

    /** The numeric parts of a version or tag: "v1.2.10" is [1, 2, 10]. A suffix such as "-beta" is ignored. */
    fun parts(version: String): List<Int> =
        Regex("""\d+(?:\.\d+)*""").find(version)?.value?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: emptyList()

    /** Whether [candidate] is a later version than [current]; missing parts count as 0, so 1.1 equals 1.1.0. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** The release's APK: "sofa-<version>.apk" when attached, otherwise the only APK there is. */
    fun pickApk(assets: List<Asset>, version: String): Asset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        return apks.firstOrNull { it.name.equals("sofa-$version.apk", ignoreCase = true) } ?: apks.singleOrNull()
    }

    /** GitHub's "sha256:<hex>" asset digest as lowercase hex; null when missing or of another kind. */
    fun sha256(digest: String?): String? =
        digest?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substring(7)?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    /** Release notes as plain text for a TV dialog: Markdown marks dropped, list items as bullets, at most [max] chars. */
    fun plainNotes(markdown: String?, max: Int = 700): String {
        val text = (markdown ?: "").replace("\r\n", "\n").lines().joinToString("\n") { line ->
            line.trimEnd()
                .replace(Regex("""^#{1,6}\s+"""), "")
                .replace(Regex("""^\s*[-*+]\s+"""), "• ")
                .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
                .replace(Regex("""__(.+?)__"""), "$1")
                .replace(Regex("""`([^`]*)`"""), "$1")
                .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        }.replace(Regex("\n{3,}"), "\n\n").trim()
        return if (text.length <= max) text else text.take(max).trimEnd() + "…"
    }
}
