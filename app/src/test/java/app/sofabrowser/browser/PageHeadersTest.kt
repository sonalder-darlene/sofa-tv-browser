package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class PageHeadersTest {

    private val ua = "Mozilla/5.0 (Linux; Android 14) Chrome/141"
    private val cookies = { _: String -> "id=1" }

    @Test
    fun aPlayerInAnEmbedAsksAsTheEmbedNotTheTopPage() {
        // A site embeds geo.dailymotion.com, whose player fetches the master from cdndirector.
        assertEquals(mapOf("User-Agent" to ua, "Accept" to "*/*", "Referer" to "https://geo.dailymotion.com/",
            "Origin" to "https://geo.dailymotion.com", "Sec-Fetch-Site" to "same-site", "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"),
            PageHeaders.forRequest("https://cdndirector.dailymotion.com/cdn/manifest/video/x.m3u8?sec=1",
                "https://geo.dailymotion.com/player/x1l1ry.html?video=k6", ua, cookies))
    }

    @Test
    fun crossSiteRequestsGetNoCookies() {
        val h = PageHeaders.forRequest("https://vod3.cf.dmcdn.net/a/manifest.m3u8", "https://geo.dailymotion.com/", ua, cookies)
        assertEquals("cross-site", h["Sec-Fetch-Site"])
        assertEquals(null, h["Cookie"])
    }

    @Test
    fun sameOriginRequestsGetTheFullAddressAndCookies() {
        val h = PageHeaders.forRequest("https://site.test/hls/master.m3u8", "https://site.test/watch/42#t=3", ua, cookies)
        assertEquals("https://site.test/watch/42", h["Referer"])
        assertEquals(null, h["Origin"])
        assertEquals("same-origin", h["Sec-Fetch-Site"])
        assertEquals("id=1", h["Cookie"])
    }

    @Test
    fun noRefererFromHttpsToHttp() {
        val h = PageHeaders.forRequest("http://cdn.test/a.m3u8", "https://site.test/", ua)
        assertEquals(null, h["Referer"])
        assertEquals("https://site.test", h["Origin"])
    }

    @Test
    fun aVideoElementsOwnFileIsAMediaRequest() {
        val h = PageHeaders.forRequest("https://media.site.test/film.mp4", "https://www.site.test/film", ua, cookies, media = true)
        assertEquals(null, h["Origin"])
        assertEquals("no-cors", h["Sec-Fetch-Mode"])
        assertEquals("video", h["Sec-Fetch-Dest"])
        assertEquals("id=1", h["Cookie"])
    }

    @Test
    fun withoutAFrameOnlyTheBrowserHeaders() {
        assertEquals(mapOf("User-Agent" to ua, "Accept" to "*/*", "Sec-Fetch-Site" to "none", "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"), PageHeaders.forRequest("https://cdn.test/a.m3u8", null, ua, cookies))
    }

    @Test
    fun defaultPortsAreTheSameOrigin() {
        assertEquals(PageHeaders.origin("https://site.test/a"), PageHeaders.origin("https://SITE.test:443/b"))
    }

    // What a frame says about itself is kept only as far as WebView's own word on who sent it (the sender's origin).

    @Test
    fun aFrameMayNameItsOwnAddress() {
        assertEquals("https://geo.dailymotion.com/player/x1l1ry.html?video=k6",
            PageHeaders.frameFor("https://geo.dailymotion.com/player/x1l1ry.html?video=k6", "https://geo.dailymotion.com"))
    }

    @Test
    fun aFrameClaimingAnotherSiteIsTakenForItsOwnOrigin() {
        assertEquals("https://ads.test/", PageHeaders.frameFor("https://bank.test/", "https://ads.test"))
    }

    @Test
    fun schemeAndPortCount() {
        assertEquals("http://site.test/", PageHeaders.frameFor("https://site.test/watch", "http://site.test"))
        assertEquals("https://site.test:8443/", PageHeaders.frameFor("https://site.test/watch", "https://site.test:8443"))
        assertEquals("https://site.test:443/watch", PageHeaders.frameFor("https://site.test:443/watch", "https://site.test"))
    }

    @Test
    fun opaqueSendersAreNoFrame() {
        assertEquals(null, PageHeaders.frameFor("https://bank.test/", "null"))
        assertEquals(null, PageHeaders.frameFor("https://bank.test/", "about:blank"))
        assertEquals(null, PageHeaders.frameFor("https://bank.test/", ""))
        assertEquals(null, PageHeaders.frameFor(null, "null"))
    }

    @Test
    fun aFrameThatNamesNoAddressIsItsOrigin() {
        assertEquals("https://site.test/", PageHeaders.frameFor(null, "https://site.test"))
        assertEquals("https://site.test/", PageHeaders.frameFor("about:blank", "https://site.test"))
    }

    @Test
    fun sameOriginComparesSchemeHostAndPort() {
        assertEquals(true, PageHeaders.sameOrigin("https://site.test", "https://SITE.test:443/a"))
        assertEquals(false, PageHeaders.sameOrigin("https://site.test", "http://site.test"))
        assertEquals(false, PageHeaders.sameOrigin("https://site.test", "https://site.test:8443"))
        assertEquals(false, PageHeaders.sameOrigin("https://site.test", "https://cdn.site.test"))
        assertEquals(false, PageHeaders.sameOrigin("null", "null"))
        assertEquals(false, PageHeaders.sameOrigin(null, "https://site.test"))
    }
}
