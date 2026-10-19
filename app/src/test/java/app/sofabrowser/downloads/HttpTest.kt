package app.sofabrowser.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTest {

    @Test
    fun parsesContentRange() {
        assertEquals(Http.ContentRange(100, 199, 1000), Http.parseContentRange("bytes 100-199/1000"))
        assertEquals(Http.ContentRange(0, 9, -1), Http.parseContentRange("bytes 0-9/*"))
        assertNull(Http.parseContentRange("bytes */1000"))
        assertNull(Http.parseContentRange(null))
    }

    @Test
    fun readsContentDispositionNames() {
        assertEquals("report.pdf", Http.filenameFromContentDisposition("attachment; filename=\"report.pdf\""))
        assertEquals("plain.txt", Http.filenameFromContentDisposition("attachment; filename=plain.txt; size=3"))
        assertEquals("a \"b\".txt", Http.filenameFromContentDisposition("attachment; filename=\"a \\\"b\\\".txt\""))
        // filename* (RFC 5987) wins over the ASCII fallback.
        assertEquals("€ rates.pdf",
            Http.filenameFromContentDisposition("attachment; filename=\"rates.pdf\"; filename*=UTF-8''%E2%82%AC%20rates.pdf"))
        assertEquals("1+1.txt", Http.filenameFromContentDisposition("attachment; filename*=UTF-8''1+1.txt"))
        assertNull(Http.filenameFromContentDisposition("inline"))
        assertNull(Http.filenameFromContentDisposition(null))
    }

    @Test
    fun sanitizesFileNames() {
        assertEquals("passwd", Http.safeFileName("../../etc/passwd"))
        assertEquals("evil.exe", Http.safeFileName("C:\\temp\\evil.exe"))
        assertEquals("hidden", Http.safeFileName(".hidden"))
        assertEquals("ab.txt", Http.safeFileName("a\u0000b.txt"))
        assertEquals("whatisit.txt", Http.safeFileName("what?is*it.txt"))
        assertEquals("download", Http.safeFileName("  "))
        val long = Http.safeFileName("x".repeat(300) + ".mkv")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".mkv"))
    }

    @Test
    fun formatsSizesAndDurations() {
        assertEquals("512 B", Http.formatBytes(512))
        assertEquals("1.5 KB", Http.formatBytes(1536))
        assertEquals("200 MB", Http.formatBytes(200L * 1024 * 1024))
        assertEquals("1.0 GB", Http.formatBytes(1024L * 1024 * 1024))
        assertEquals("1:05", Http.formatDuration(65))
        assertEquals("2:03:45", Http.formatDuration(2 * 3600 + 3 * 60 + 45))
    }

    @Test
    fun retriesOnlyTransientStatuses() {
        assertTrue(Http.isRetryable(503))
        assertTrue(Http.isRetryable(429))
        assertFalse(Http.isRetryable(404))
        assertFalse(Http.isRetryable(403))
    }
}
