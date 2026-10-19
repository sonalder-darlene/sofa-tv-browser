package app.sofabrowser.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PrettyHostTest {
    @Test
    fun namesSitesFromTheirHost() {
        assertEquals("Youtube", Db.prettyHost("www.youtube.com"))
        assertEquals("News Example", Db.prettyHost("news.example.co.uk"))
        assertEquals("Wikipedia", Db.prettyHost("wikipedia.org"))
    }

    @Test
    fun leavesAddressesAlone() {
        assertEquals("10.0.2.2", Db.prettyHost("10.0.2.2"))
        assertEquals("localhost", Db.prettyHost("localhost"))
        assertEquals("[::1]", Db.prettyHost("[::1]"))
    }
}
