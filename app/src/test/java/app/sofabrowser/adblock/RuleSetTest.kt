package app.sofabrowser.adblock

import jp.hazuki.yuzubrowser.adblock.core.ContentRequest
import jp.hazuki.yuzubrowser.adblock.getContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class RuleSetTest {

    private fun compile(text: String) = CompiledList.parse(text.trimIndent().reader().buffered())

    private fun rules(vararg lists: String) = RuleSet.Builder().apply { lists.forEach { add(compile(it)) } }.build()

    /** Whether [url] is blocked as a request with [accept] on a page at [page]. */
    private fun RuleSet.blocks(url: String, page: String = "https://news.example/article", accept: String = "*/*"): Boolean {
        val u = RequestUrl(url)
        val pageUrl = RequestUrl(page)
        return pageAllowed(pageUrl) && blocks(u, getContentType(u, false, mapOf("Accept" to accept)), pageUrl.host)
    }

    @Test
    fun hostsFilesAndDomainRulesBlockTheDomainAndItsSubdomains() {
        val r = rules("""
            # a hosts file
            0.0.0.0 ads.example
            127.0.0.1 one.test two.test # several on one line
            ||tracker.test^
            plain.test
        """)
        assertTrue(r.blocks("https://ads.example/x.js"))
        assertTrue(r.blocks("https://cdn.ads.example/x.js"))
        assertTrue(r.blocks("https://two.test/"))
        assertTrue(r.blocks("https://tracker.test/pixel"))
        assertTrue(r.blocks("https://plain.test/"))
        assertFalse(r.blocks("https://badads.example/"))
        assertFalse(r.blocks("https://example/"))
        assertEquals(5, r.domains.size)
    }

    @Test
    fun pathRulesBlockOnlyMatchingAddresses() {
        val r = rules("""
            /banner/*/ad_
            ||cdn.example^*/ads/
            &ad_type=
            /\/pagead[0-9]\.js/
        """)
        assertTrue(r.blocks("https://site.test/banner/top/ad_1.png"))
        assertTrue(r.blocks("https://cdn.example/static/ads/a.js"))
        assertFalse(r.blocks("https://cdn.example/static/app.js"))
        assertTrue(r.blocks("https://x.test/load?id=1&ad_type=video"))
        assertTrue(r.blocks("https://x.test/pagead2.js"))
        assertFalse(r.blocks("https://x.test/pagead.js"))
    }

    @Test
    fun exceptionsWinOverBlocksAndImportantWinsOverExceptions() {
        val r = rules("""
            ||ads.example^
            @@||ads.example/allowed/
            ||tracker.test^${'$'}important
            @@||tracker.test^
        """)
        assertTrue(r.blocks("https://ads.example/x.js"))
        assertFalse(r.blocks("https://ads.example/allowed/x.js"))
        assertTrue(r.blocks("https://tracker.test/x.js"))
    }

    @Test
    fun typeThirdPartyAndDomainOptionsNarrowARule() {
        val r = rules("""
            ||media.example^${'$'}image
            ||widgets.example^${'$'}third-party
            /promo.js${'$'}script,domain=news.example|~sport.news.example
            ||api.example^${'$'}~xhr
        """)
        assertTrue(r.blocks("https://media.example/a.png"))
        assertFalse(r.blocks("https://media.example/a.js"))
        assertTrue(r.blocks("https://widgets.example/w.js"))
        assertFalse(r.blocks("https://widgets.example/w.js", page = "https://www.widgets.example/"))
        assertTrue(r.blocks("https://cdn.test/promo.js"))
        assertFalse(r.blocks("https://cdn.test/promo.js", page = "https://sport.news.example/"))
        assertFalse(r.blocks("https://cdn.test/promo.js", page = "https://other.example/"))
        assertTrue(r.blocks("https://api.example/a.png"))
    }

    @Test
    fun aDocumentExceptionLetsTheWholePageThrough() {
        val r = rules("""
            ||ads.example^
            @@||trusted.example^${'$'}document
            @@||cdn.example^
        """)
        assertTrue(r.blocks("https://ads.example/x.js"))
        assertFalse(r.blocks("https://ads.example/x.js", page = "https://trusted.example/"))
        // A plain exception only allows requests to that host; it never allows a whole page.
        assertTrue(r.blocks("https://ads.example/x.js", page = "https://cdn.example/"))
    }

    @Test
    fun badfilterCancelsTheSameRuleInAnyList() {
        val r = rules("||ads.example^\n||track.example^\$third-party\n/ad.js\$script", """
            ||ads.example^${'$'}badfilter
            ||track.example^${'$'}third-party,badfilter
            /ad.js${'$'}script,badfilter
        """)
        assertFalse(r.blocks("https://ads.example/x.js"))
        assertFalse(r.blocks("https://track.example/x.js"))
        assertFalse(r.blocks("https://x.test/ad.js"))
    }

    @Test
    fun unsupportedRulesAreSkippedRatherThanAppliedTooWidely() {
        val list = compile("""
            ${'$'}popup,third-party
            ||ads.example^${'$'}popup
            @@${'$'}sitekey=abc,document
            ||site.example^${'$'}csp=script-src 'none'
            ||site.example^${'$'}removeparam=utm_source
            ||site.example/v.js${'$'}redirect-rule=noop.js
            @@||site.example^${'$'}redirect
            *${'$'}script
            example.com##+js(nobab)
            example.com#?#.ad:-abp-has(.x)
            example.com##.ad {height: 0}
            example.com${'$'}${'$'}script[tag-content="ad"]
        """)
        assertEquals(0, list.rules)
        assertEquals(12, list.skipped)
        val r = RuleSet.Builder().apply { add(list) }.build()
        assertFalse(r.blocks("https://site.example/v.js"))
        assertFalse(r.blocks("https://ads.example/"))
    }

    @Test
    fun aRedirectRuleStillBlocks() {
        val r = rules("||ads.example/v.js\$script,redirect=noop.js\n||cdn.example^\$empty,image")
        assertTrue(r.blocks("https://ads.example/v.js"))
        assertTrue(r.blocks("https://cdn.example/a.png"))
    }

    @Test
    fun fromIsTheSameAsDomain() {
        val r = rules("/ad.js\$from=news.example")
        assertTrue(r.blocks("https://cdn.test/ad.js"))
        assertFalse(r.blocks("https://cdn.test/ad.js", page = "https://other.example/"))
    }

    @Test
    fun conditionsLeaveOutLinesForOtherPlatforms() {
        val r = rules("""
            !#if env_firefox
            ||firefox-only.test^
            !#else
            ||not-firefox.test^
            !#endif
            !#if env_mobile && !env_safari
            ||mobile.test^
            !#endif
            !#if (env_mv3 || cap_html_filtering)
            ||never.test^
            !#endif
        """)
        assertFalse(r.blocks("https://firefox-only.test/"))
        assertTrue(r.blocks("https://not-firefox.test/"))
        assertTrue(r.blocks("https://mobile.test/"))
        assertFalse(r.blocks("https://never.test/"))
    }

    @Test
    fun aCompiledListReadsBackTheSame() {
        val list = compile("""
            ! Title: Test list
            ! Expires: 4 days
            0.0.0.0 ads.example
            ||widgets.example^${'$'}third-party
            /banner/*/ad_${'$'}image,domain=a.test|~b.a.test
            @@||ok.example^${'$'}document
            ##.ad-box
            example.com,~m.example.com##.promo
        """)
        val bytes = ByteArrayOutputStream().also { list.writeTo(DataOutputStream(it), 42) }.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertEquals(42L, CompiledList.readSource(input))
        val back = CompiledList.readBody(input)
        assertEquals("Test list", back.title)
        assertEquals(96, back.expiresHours)
        assertEquals(list.rules, back.rules)
        val r = RuleSet.Builder().apply { add(back) }.build()
        assertTrue(r.blocks("https://ads.example/"))
        assertTrue(r.blocks("https://x.test/banner/1/ad_2.png", page = "https://a.test/", accept = "image/webp,*/*"))
        assertFalse(r.blocks("https://x.test/banner/1/ad_2.png", page = "https://b.a.test/", accept = "image/webp,*/*"))
        assertFalse(r.pageAllowed(RequestUrl("https://ok.example/")))
        val page = r.cosmeticsFor(RequestUrl("https://www.example.com/"))!!
        assertEquals(listOf(".promo"), page.selectors)
        assertEquals(listOf(".ad-box"), r.cosmetics.forKeys(page, listOf(".ad-box")))
    }

    @Test
    fun anOldOrDamagedCacheIsNotRead() {
        assertNull(CompiledList.readSource(DataInputStream(ByteArrayInputStream(ByteArray(16)))))
    }

    @Test
    fun requestTypesComeFromTheAddressAndHeaders() {
        fun type(url: String, accept: String? = null) =
            getContentType(RequestUrl(url), false, if (accept == null) emptyMap() else mapOf("accept" to accept))
        assertEquals(ContentRequest.TYPE_SCRIPT, type("https://a.test/x/app.js?v=2"))
        assertEquals(ContentRequest.TYPE_IMAGE, type("https://a.test/p.webp"))
        assertEquals(ContentRequest.TYPE_STYLE_SHEET, type("https://a.test/s.css"))
        assertEquals(ContentRequest.TYPE_MEDIA, type("https://a.test/v/seg.m3u8"))
        assertEquals(ContentRequest.TYPE_IMAGE, type("https://a.test/img", "image/avif,image/webp,*/*"))
        assertEquals(ContentRequest.TYPE_SUB_DOCUMENT, type("https://a.test/frame", "text/html,application/xhtml+xml"))
        // A host that looks like a file name is not an extension.
        assertTrue(type("https://cdn.js/") and ContentRequest.TYPE_XHR != 0)
    }

    @Test
    fun aWebPageServedInsteadOfAListHasNoRules() {
        val page = compile("""
            <!doctype html>
            <html><head><title>Sign in to the Wi-Fi</title></head>
            <body>ads.example.com</body></html>
        """)
        assertEquals(0, page.rules)
    }

    @Test
    fun domainLinesAreReadWithoutTheRuleParser() {
        assertEquals("ads.example.com", CompiledList.plainDomain("Ads.Example.com."))
        assertNull(CompiledList.plainDomain("-ad-banner."))
        assertNull(CompiledList.plainDomain("a..b"))
        assertNull(CompiledList.plainDomain("localhost.localdomain"))
        assertNull(CompiledList.plainDomain("ads"))
        assertEquals(listOf("a.test", "b.test"), CompiledList.hostsLine("0.0.0.0\ta.test  b.test # two"))
        assertNull(CompiledList.hostsLine("192.168.1.1 router.lan"))
    }

    @Test
    fun partiesFollowTheSite() {
        assertEquals(jp.hazuki.yuzubrowser.adblock.filter.unified.STRICT_FIRST_PARTY, RuleSet.party("a.example", "a.example"))
        assertEquals(jp.hazuki.yuzubrowser.adblock.filter.unified.FIRST_PARTY, RuleSet.party("cdn.bbc.co.uk", "www.bbc.co.uk"))
        assertEquals(jp.hazuki.yuzubrowser.adblock.filter.unified.THIRD_PARTY, RuleSet.party("ads.co.uk", "www.bbc.co.uk"))
        assertEquals(jp.hazuki.yuzubrowser.adblock.filter.unified.THIRD_PARTY, RuleSet.party("a.github.io", "b.github.io"))
    }
}
