package app.sofabrowser.adblock

import app.sofabrowser.adblock.BlockedResponse.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class BlockedResponseTest {

    // Accept headers as Chromium sends them for each kind of subresource.
    private val imageAccept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
    private val cssAccept = "text/css,*/*;q=0.1"

    @Test
    fun imagesGetATransparentPixel() {
        assertEquals(Kind.IMAGE, BlockedResponse.kind("https://ads.test/banner", imageAccept))
    }

    @Test
    fun stylesheetsGetEmptyCss() {
        assertEquals(Kind.CSS, BlockedResponse.kind("https://ads.test/style", cssAccept))
    }

    @Test
    fun scriptsAndFetchesKeepTheError() {
        // A script tag and fetch() both send */*: they can't be told apart, and an empty "loaded" ad SDK breaks
        // video players that would otherwise skip their ads.
        assertEquals(Kind.ERROR, BlockedResponse.kind("https://imasdk.googleapis.com/js/sdkloader/ima3.js", "*/*"))
        assertEquals(Kind.ERROR, BlockedResponse.kind("https://ads.test/pixel.gif", "*/*"))
        assertEquals(Kind.ERROR, BlockedResponse.kind("https://ads.test/data.json", "application/json"))
    }

    @Test
    fun withoutAnAcceptHeaderTheExtensionDecides() {
        assertEquals(Kind.IMAGE, BlockedResponse.kind("https://ads.test/a/B.PNG?x=1", null))
        assertEquals(Kind.IMAGE, BlockedResponse.kind("https://ads.test/b.webp#f", ""))
        assertEquals(Kind.CSS, BlockedResponse.kind("https://ads.test/c.css?v=2", null))
        assertEquals(Kind.ERROR, BlockedResponse.kind("https://ads.test/d.js", null))
        assertEquals(Kind.ERROR, BlockedResponse.kind("https://ads.test/png", null))
    }
}
