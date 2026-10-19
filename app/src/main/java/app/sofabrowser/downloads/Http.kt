package app.sofabrowser.downloads

import java.net.URLDecoder
import java.util.Locale

/** Small, Android-free helpers for downloads; kept pure so they can be unit-tested on the JVM. */
object Http {

    data class ContentRange(val start: Long, val end: Long, val total: Long)

    /** Parses "bytes 100-199/1000" (total may be "*", reported as -1). */
    fun parseContentRange(header: String?): ContentRange? {
        val m = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE).find(header ?: return null) ?: return null
        val (start, end, total) = m.destructured
        return ContentRange(start.toLong(), end.toLong(), if (total == "*") -1 else total.toLong())
    }

    /** The file name from a Content-Disposition header, preferring the RFC 5987 `filename*` form. */
    fun filenameFromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*([\w!#$&+.^`|~-]+)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            val charset = m.groupValues[1]
            return runCatching { URLDecoder.decode(m.groupValues[2].trim().replace("+", "%2B"), charset) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
        Regex("""filename\s*=\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE).find(header)?.let {
            return it.groupValues[1].replace(Regex("""\\(.)"""), "$1").takeIf { s -> s.isNotBlank() }
        }
        Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let {
            return it.groupValues[1].trim().takeIf { s -> s.isNotBlank() }
        }
        return null
    }

    /**
     * Makes [name] safe to use as a single file name: no directories, no control or reserved characters, no
     * leading dots, at most 120 characters with the extension kept.
     */
    fun safeFileName(name: String?, fallback: String = "download"): String {
        var n = (name ?: "").substringAfterLast('/').substringAfterLast('\\')
        n = n.filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*" }.trim().trimStart('.').trim()
        if (n.isEmpty()) n = fallback
        if (n.length > 120) {
            val ext = n.substringAfterLast('.', "").take(12)
            n = if (ext.isNotEmpty() && ext.length < n.length) n.take(119 - ext.length) + "." + ext else n.take(120)
        }
        return n
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.ROOT, if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
    }

    /** "1:05" or "2:03:45" for a duration in seconds. */
    fun formatDuration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** Whether an HTTP status is worth retrying (server trouble or rate limiting) rather than final. */
    fun isRetryable(code: Int) = code == 408 || code == 429 || code >= 500
}
