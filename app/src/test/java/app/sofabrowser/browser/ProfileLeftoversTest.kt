package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileLeftoversTest {

    @Test
    fun onlyNumberedProfilesWithoutAFolderGo() {
        val prefs = listOf(
            "WebViewProfilePrefsDefault.xml",
            "WebViewProfilePrefsWebViewProfilePrefsProfile 6.xml",
            "WebViewProfilePrefsProfile 9.xml",
            "WebViewProfilePrefsProfile 10.xml",
            "prefs.xml",
            "Profile 3.xml",
        )
        val dirs = setOf("Default", "Profile 10", "Local State")
        assertEquals(
            listOf("WebViewProfilePrefsWebViewProfilePrefsProfile 6.xml", "WebViewProfilePrefsProfile 9.xml"),
            ProfileLeftovers.orphans(prefs, dirs),
        )
    }
}
