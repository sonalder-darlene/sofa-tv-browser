package app.sofabrowser.adblock

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** [SiteFilteredHttp.open]: each redirect checked before it is followed, on two local servers (the second listed). */
class SiteFilteredHttpTest {

    private val requested = ArrayList<String>()
    private val listedRequested = ArrayList<String>()

    private val listed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            synchronized(listedRequested) { listedRequested += ex.requestURI.toString() }
            ex.sendResponseHeaders(200, 1)
            ex.responseBody.use { it.write('x'.code) }
        }
        start()
    }
    private val listedBase = "http://127.0.0.1:${listed.address.port}"

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.toString()
            synchronized(requested) { requested += "$path cookie=${ex.requestHeaders.getFirst("Cookie")}" }
            val (code, location) = when {
                path == "/to-listed" -> 302 to "$listedBase/video.mp4"
                path == "/hop" -> 301 to "/hop2"
                path == "/hop2" -> 307 to "/file"
                path == "/file" -> 200 to null
                path == "/loop" -> 302 to "/loop"
                path == "/ftp" -> 302 to "ftp://example.com/file"
                path == "/no-location" -> 302 to null
                path == "/not-modified" -> 304 to null
                else -> 404 to null
            }
            location?.let { ex.responseHeaders.add("Location", it) }
            if (code == 200) { ex.sendResponseHeaders(200, 1); ex.responseBody.use { it.write('x'.code) } }
            else { ex.sendResponseHeaders(code, -1); ex.close() }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val blocked = { url: String -> url.startsWith(listedBase) }

    @After
    fun stop() {
        server.stop(0)
        listed.stop(0)
    }

    private fun open(path: String, setup: (java.net.HttpURLConnection) -> Unit = {}) = SiteFilteredHttp.open("$base$path", blocked, setup = setup)

    @Test
    fun aRedirectToAListedSiteIsRefusedWithoutContactingIt() {
        val e = assertThrows(SiteBlockedException::class.java) { open("/to-listed") }
        assertEquals("$listedBase/video.mp4", e.url)
        assertEquals(emptyList<String>(), listedRequested)
    }

    @Test
    fun aListedAddressIsNeverRequestedAtAll() {
        assertThrows(SiteBlockedException::class.java) { SiteFilteredHttp.open("$listedBase/a", blocked) {} }
        assertEquals(emptyList<String>(), listedRequested)
    }

    @Test
    fun allowedRedirectsAreFollowedEachWithItsOwnHeaders() {
        val c = open("/hop") { if (it.url.path == "/hop") it.setRequestProperty("Cookie", "a=1") }
        try {
            assertEquals(200, c.responseCode)
            assertEquals("$base/file", c.url.toString())
        } finally {
            c.disconnect()
        }
        // The first address's cookie isn't carried to the next hops.
        assertEquals(listOf("/hop cookie=a=1", "/hop2 cookie=null", "/file cookie=null"), requested)
    }

    @Test
    fun tooManyRedirectsAreRefused() {
        assertThrows(RedirectRefusedException::class.java) { open("/loop") }
        assertEquals(SiteFilteredHttp.MAX_REDIRECTS + 1, requested.size)
    }

    @Test
    fun aRedirectToAnotherKindOfAddressIsRefused() {
        assertThrows(RedirectRefusedException::class.java) { open("/ftp") }
    }

    @Test
    fun answersThatArentRedirectsComeBackAsTheyAre() {
        listOf("/no-location" to 302, "/not-modified" to 304, "/missing" to 404).forEach { (path, code) ->
            val c = open(path)
            try { assertEquals(code, c.responseCode) } finally { c.disconnect() }
        }
        assertTrue(requested.all { it.endsWith("cookie=null") })
    }
}
