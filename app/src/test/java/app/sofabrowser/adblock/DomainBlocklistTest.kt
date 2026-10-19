package app.sofabrowser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DomainBlocklistTest {

    private fun listOf(text: String) = DomainBlocklist.parse(text.trimIndent().reader().buffered())

    @Test
    fun parsesHostsAbpAndPlainLines() {
        assertEquals("ads.example.com", DomainBlocklist.parseLine("0.0.0.0 ads.example.com"))
        assertEquals("ads.example.com", DomainBlocklist.parseLine("127.0.0.1\tADS.example.com.  # comment"))
        assertEquals("tracker.net", DomainBlocklist.parseLine("||tracker.net^"))
        assertEquals("plain.org", DomainBlocklist.parseLine("plain.org"))
    }

    @Test
    fun skipsRulesADomainMatcherCannotHonour() {
        assertNull(DomainBlocklist.parseLine("! comment"))
        assertNull(DomainBlocklist.parseLine("[Adblock Plus 2.0]"))
        assertNull(DomainBlocklist.parseLine("||ads.example.com^\$third-party"))
        assertNull(DomainBlocklist.parseLine("||example.com/ads/*"))
        assertNull(DomainBlocklist.parseLine("@@||example.com^"))
        assertNull(DomainBlocklist.parseLine("example.com##.ad-banner"))
        assertNull(DomainBlocklist.parseLine("0.0.0.0 0.0.0.0"))
        assertNull(DomainBlocklist.parseLine("127.0.0.1 localhost"))
        assertNull(DomainBlocklist.parseLine("192.168.1.1 router.lan"))
        assertNull(DomainBlocklist.parseLine("com"))
    }

    @Test
    fun blocksDomainAndSubdomainsButNotParentsOrLookalikes() {
        val list = listOf("""
            # header
            0.0.0.0 doubleclick.net
            ||ads.example.com^
        """)
        assertEquals(2, list.size)
        assertTrue(list.isBlocked("doubleclick.net"))
        assertTrue(list.isBlocked("stats.g.doubleclick.net"))
        assertTrue(list.isBlocked("ADS.Example.com."))
        assertTrue(list.isBlocked("x.ads.example.com"))
        assertFalse(list.isBlocked("example.com"))
        assertFalse(list.isBlocked("www.example.com"))
        assertFalse(list.isBlocked("notdoubleclick.net"))
        assertFalse(list.isBlocked("net"))
    }

    @Test
    fun compiledFormRoundTrips() {
        val list = listOf("0.0.0.0 a.test\n0.0.0.0 b.test\n")
        val bytes = ByteArrayOutputStream().also(list::writeTo).toByteArray()
        val read = DomainBlocklist.read(ByteArrayInputStream(bytes))
        assertEquals(2, read.size)
        assertTrue(read.isBlocked("sub.a.test"))
        assertFalse(read.isBlocked("c.test"))
    }

    @Test
    fun emptyListBlocksNothing() {
        assertFalse(DomainBlocklist.EMPTY.isBlocked("doubleclick.net"))
    }
}
