package app.sofabrowser.browser

import app.sofabrowser.adblock.SiteBlockedException
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Site filters in [StreamResolver] on a local server: what "Play in…" checks beyond the stream's own addresses,
 * [StreamResolver.land] for Sofa Player, and the manifest reads, whose redirects to a second, listed server must
 * never reach it.
 */
class StreamResolverSiteFiltersTest {

    private val listedRequested = ArrayList<String>()
    private val listed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            synchronized(listedRequested) { listedRequested += ex.requestURI.toString() }
            val bytes = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val listedBase = "http://127.0.0.1:${listed.address.port}"

    private val requested = ArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.toString()
            synchronized(requested) { requested += path }
            val (code, body) = when (path) {
                "/redirect" -> { ex.responseHeaders.add("Location", "http://blocked.test/video.mp4"); 302 to "" }
                "/master-redirect.m3u8" -> { ex.responseHeaders.add("Location", "$listedBase/master.m3u8"); 302 to "" }
                "/hop" -> { ex.responseHeaders.add("Location", "/video.mp4"); 302 to "" }
                "/video.mp4" -> 200 to "x"
                "/variant.m3u8" -> 200 to "#EXTM3U\n#EXTINF:4,\nseg0.ts\n#EXTINF:4,\nhttp://blocked.test/seg1.ts\n#EXT-X-ENDLIST"
                "/clean.m3u8" -> 200 to "#EXTM3U\n#EXTINF:4,\nseg0.ts\n#EXT-X-ENDLIST"
                else -> 404 to ""
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val resolver = StreamResolver("ua", { null }) { "blocked.test" in it || it.startsWith(listedBase) }

    @After
    fun stop() {
        server.stop(0)
        listed.stop(0)
    }

    private fun master(vararg variants: String) = Resolved(variants.joinToString("") { "#EXT-X-STREAM-INF:BANDWIDTH=1\n$it\n" })

    @Suppress("TestFunctionName")
    private fun Resolved(body: String) =
        StreamResolver.Resolved("$base/master.m3u8", StreamResolver.Kind.HLS, null, "#EXTM3U\n$body")

    @Test
    fun aFileThatRedirectsToABlockedSiteIsCaughtWithoutAskingIt() {
        assertEquals("http://blocked.test/video.mp4", resolver.blockedAlong("$base/redirect", null, null))
    }

    @Test
    fun aFileWhoseRedirectsStayAllowedPasses() {
        assertNull(resolver.blockedAlong("$base/hop", null, null))
        assertEquals(listOf("/hop", "/video.mp4"), requested)
    }

    @Test
    fun aSegmentOnABlockedSiteInAVariantPlaylistIsCaught() {
        assertEquals("http://blocked.test/seg1.ts", resolver.blockedAlong(null, null, master("$base/clean.m3u8", "$base/variant.m3u8")))
    }

    @Test
    fun cleanVariantsPassAndNoSegmentIsFetched() {
        assertNull(resolver.blockedAlong(null, null, master("$base/clean.m3u8")))
        assertEquals(listOf("/clean.m3u8"), requested)
    }

    @Test
    fun aMediaPlaylistIsNotReadFurther() {
        // Its addresses are segments, which the caller checks from the text; none is fetched.
        assertNull(resolver.blockedAlong(null, null, Resolved("#EXTINF:4,\n$base/seg0.ts\n#EXT-X-ENDLIST")))
        assertEquals(emptyList<String>(), requested)
    }

    @Test
    fun landFollowsAllowedRedirectsToTheirEnd() {
        assertEquals("$base/video.mp4", resolver.land("$base/hop", null, media = true))
        assertEquals("$base/video.mp4", resolver.land("$base/video.mp4", null, media = true))
    }

    @Test
    fun landRefusesARedirectToABlockedSiteBeforeFollowingIt() {
        val e = assertThrows(SiteBlockedException::class.java) { resolver.land("$base/redirect", null, media = true) }
        assertEquals("http://blocked.test/video.mp4", e.url)
        assertEquals(listOf("/redirect"), requested)
    }

    @Test
    fun aManifestThatRedirectsToABlockedSiteIsNotRead() {
        val stream = StreamSniffer.Stream("$base/master-redirect.m3u8", manifest = true, referer = null, origin = null)
        assertNull(resolver.resolve(listOf(stream), emptyMap(), checkOutside = false))
        assertEquals(listOf("/master-redirect.m3u8"), requested)
        assertEquals(emptyList<String>(), listedRequested)
    }
}
