package app.sofabrowser.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Releases.isNewer("v1.0.10", "1.0.9"))
        assertTrue(Releases.isNewer("1.1", "1.0.9"))
        assertTrue(Releases.isNewer("2.0.0", "1.99.99"))
        assertFalse(Releases.isNewer("v1.0.0", "1.0.0"))
        assertFalse(Releases.isNewer("1.1", "1.1.0"))
        assertFalse(Releases.isNewer("0.9.9", "1.0.0"))
        assertFalse(Releases.isNewer("nightly", "1.0.0"))
        assertEquals(listOf(1, 2, 0), Releases.parts("v1.2.0-beta1"))
    }

    @Test
    fun picksTheReleaseApk() {
        val named = Releases.Asset("sofa-1.2.0.apk", "u1", 1, null)
        val other = Releases.Asset("sofa-1.2.0-debug.apk", "u2", 1, null)
        val notes = Releases.Asset("checksums.txt", "u3", 1, null)
        assertEquals(named, Releases.pickApk(listOf(other, named, notes), "1.2.0"))
        assertEquals(other, Releases.pickApk(listOf(other, notes), "1.2.0"))
        assertNull(Releases.pickApk(listOf(other, Releases.Asset("x.apk", "u4", 1, null)), "1.2.0"))
        assertNull(Releases.pickApk(listOf(notes), "1.2.0"))
    }

    @Test
    fun readsGithubDigests() {
        val hex = "74e23f09bb4b011600c38e7d555fb38926acc3835029b7fc02e48466c34c8aa9"
        assertEquals(hex, Releases.sha256("sha256:" + hex.uppercase()))
        assertNull(Releases.sha256("null"))
        assertNull(Releases.sha256("sha512:abc"))
        assertNull(Releases.sha256("sha256:1234"))
        assertNull(Releases.sha256(null))
    }

    @Test
    fun flattensReleaseNotes() {
        val md = "## What's new\r\n\r\n- **Faster** start\n* Fixed [a bug](https://x/1) in `downloads`\n\n\n\nThanks!"
        assertEquals("What's new\n\n• Faster start\n• Fixed a bug in downloads\n\nThanks!", Releases.plainNotes(md))
        assertEquals("", Releases.plainNotes(null))
        assertEquals("abcde…", Releases.plainNotes("abcdefgh", max = 5))
    }
}
