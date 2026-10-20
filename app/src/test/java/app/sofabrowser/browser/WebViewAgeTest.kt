package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewAgeTest {

    @Test
    fun readsTheMajorVersionFromThePackageVersion() {
        assertEquals(113, WebViewAge.major("113.0.5672.136", null))
    }

    @Test
    fun oddPackageVersionsFallBackToTheUserAgent() {
        val ua = "Mozilla/5.0 (Linux; Android 9; AFTMM Build/PS7233; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/130.0.6723.102 Mobile Safari/537.36"
        assertEquals(130, WebViewAge.major("fireos-7.2", ua))
        assertEquals(130, WebViewAge.major("", ua))
        assertEquals(130, WebViewAge.major(null, ua))
        assertEquals(120, WebViewAge.major(" 120.0.1 (vendor build)", null))
        assertNull(WebViewAge.major("unknown", "Mozilla/5.0"))
        assertNull(WebViewAge.major(null, null))
    }

    private val day = 24 * 3600 * 1000L

    @Test
    fun warnsOnceForAnOldVersion() {
        assertTrue(WebViewAge.shouldWarn(major = 113, version = "113.0.5672.136", warnedVersion = "", warnedAt = 0, now = day))
        assertFalse(WebViewAge.shouldWarn(major = 113, version = "113.0.5672.136", warnedVersion = "113.0.5672.136", warnedAt = day, now = 2 * day))
    }

    @Test
    fun warnsAgainWhenTheVersionChangesOrAfterAboutThreeMonths() {
        assertTrue(WebViewAge.shouldWarn(major = 114, version = "114.0.1", warnedVersion = "113.0.5672.136", warnedAt = day, now = 2 * day))
        assertFalse(WebViewAge.shouldWarn(major = 113, version = "113.0", warnedVersion = "113.0", warnedAt = 0, now = 89 * day))
        assertTrue(WebViewAge.shouldWarn(major = 113, version = "113.0", warnedVersion = "113.0", warnedAt = 0, now = 91 * day))
    }

    @Test
    fun neverWarnsForACurrentOrUnknownVersion() {
        assertFalse(WebViewAge.shouldWarn(major = WebViewAge.MINIMUM, version = "x", warnedVersion = "", warnedAt = 0, now = day))
        assertFalse(WebViewAge.shouldWarn(major = null, version = "x", warnedVersion = "", warnedAt = 0, now = day))
    }
}
