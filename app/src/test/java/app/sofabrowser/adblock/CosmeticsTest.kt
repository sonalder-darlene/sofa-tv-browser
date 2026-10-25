package app.sofabrowser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CosmeticsTest {

    private fun rules(text: String) =
        RuleSet.Builder().apply { add(CompiledList.parse(text.trimIndent().reader().buffered())) }.build()

    private fun RuleSet.page(url: String) = cosmeticsFor(RequestUrl(url))

    @Test
    fun siteRulesApplyToTheSiteAndItsSubdomains() {
        val r = rules("""
            news.example##.sponsored
            news.example,~m.news.example##.sidebar-ad
            shop.*###top-banner
        """)
        assertEquals(listOf(".sponsored", ".sidebar-ad"), r.page("https://www.news.example/a")?.selectors)
        assertEquals(listOf(".sponsored"), r.page("https://m.news.example/")?.selectors)
        assertEquals(listOf("#top-banner"), r.page("https://shop.co.uk/")?.selectors)
        assertEquals(listOf("#top-banner"), r.page("https://www.shop.com/")?.selectors)
        assertEquals(emptyList<String>(), r.page("https://shop.evil.com/")?.selectors)
        assertEquals(emptyList<String>(), r.page("https://notnews.example/")?.selectors)
    }

    @Test
    fun genericRulesWithAClassOrIdAreLookedUpOnlyForNamesThePageUses() {
        val r = rules("""
            ##.ad-banner
            ###ad_top
            ##.adbox > .inner
            ##div[id^="google_ads_"]
            ##ins.adsbygoogle
            example.com#@#.ad-banner
        """)
        val page = r.page("https://site.test/")!!
        // Selectors that don't start with a class or id go to every page.
        assertEquals(listOf("div[id^=\"google_ads_\"]", "ins.adsbygoogle"), page.selectors)
        assertTrue(page.generic)
        assertEquals(listOf(".ad-banner", "#ad_top", ".adbox > .inner"),
            r.cosmetics.forKeys(page, listOf(".ad-banner", "#ad_top", ".adbox", ".header")))
        assertEquals(emptyList<String>(), r.cosmetics.forKeys(page, listOf(".ad", "#main")))
        // An exception for one site removes the selector there only.
        val excepted = r.page("https://www.example.com/")!!
        assertEquals(emptyList<String>(), r.cosmetics.forKeys(excepted, listOf(".ad-banner")))
    }

    @Test
    fun aGenericExceptionRemovesASelectorEverywhere() {
        val r = rules("##.ad\nnews.example##.ad\n#@#.ad")
        assertEquals(emptyList<String>(), r.page("https://news.example/")?.selectors)
        assertEquals(emptyList<String>(), r.cosmetics.forKeys(r.page("https://a.test/")!!, listOf(".ad")))
    }

    @Test
    fun pageExceptionsSwitchElementHidingOff() {
        val r = rules("""
            ##.ad
            ##[data-ad]
            video.example##.promo
            @@||video.example^${'$'}generichide
            @@||mail.example^${'$'}elemhide
            @@||bank.example^${'$'}document
        """)
        val video = r.page("https://video.example/watch")!!
        assertEquals(listOf(".promo"), video.selectors)
        assertFalse(video.generic)
        assertEquals(emptyList<String>(), r.cosmetics.forKeys(video, listOf(".ad")))
        assertNull(r.page("https://mail.example/"))
        assertNull(r.page("https://bank.example/"))
    }

    @Test
    fun selectorsThatCouldLeaveTheirRuleOrRunScriptAreRefused() {
        val out = mutableListOf<CosmeticRule>()
        assertFalse(CosmeticRule.parse("##.a{} body{background:url(https://tracker.test/)}", out))
        assertFalse(CosmeticRule.parse("##.a /* comment", out))
        assertFalse(CosmeticRule.parse("##@import url(x)", out))
        assertFalse(CosmeticRule.parse("example.com##+js(set-constant, x, 1)", out))
        assertFalse(CosmeticRule.parse("example.com##^script:has-text(ad)", out))
        assertFalse(CosmeticRule.parse("example.com##.x:has-text(Sponsored)", out))
        assertFalse(CosmeticRule.parse("example.com#\$#.x { color: red }", out))
        assertFalse(CosmeticRule.parse("/example\\.com/##.ad", out))
        assertTrue(out.isEmpty())
        assertTrue(CosmeticRule.parse("example.com##.ad:has(> .sponsor)", out))
        assertTrue(CosmeticRule.parse("##a[href^=\"https://ads.example/\"]", out))
    }

    @Test
    fun keysAreTheLeadingClassOrId() {
        assertEquals(".ad", CosmeticRule.key(".ad"))
        assertEquals(".ad-box", CosmeticRule.key(".ad-box > img"))
        assertEquals("#ad_top", CosmeticRule.key("#ad_top.wide"))
        assertNull(CosmeticRule.key("div.ad"))
        assertNull(CosmeticRule.key("[class^=\"ad\"]"))
        assertNull(CosmeticRule.key(".ad\\:x"))
        assertNull(CosmeticRule.key(".1x"))
    }

    @Test
    fun hostsCommentsAreNotElementRules() {
        val list = CompiledList.parse("# Title: hosts\n#0.0.0.0 old.test\n###ad\n".reader().buffered())
        assertEquals(1, list.cosmetics.size)
        assertEquals(0, list.skipped)
    }
}
