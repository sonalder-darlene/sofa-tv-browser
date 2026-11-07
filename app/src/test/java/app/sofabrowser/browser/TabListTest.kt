package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabListTest {

    private fun list(vararg tabs: String, max: Int = 10) = TabList<String>(max).apply { tabs.forEach { add(it); select(it) } }

    @Test
    fun theFirstTabIsInSightAndANewOneWaitsUntilSelected() {
        val l = TabList<String>(10)
        assertNull(l.current)
        l.add("a")
        assertEquals("a", l.current)
        l.add("b")
        assertEquals("a", l.current)
        l.select("b")
        assertEquals("b", l.current)
    }

    @Test
    fun aTabOpenedFromAPageGoesRightAfterIt() {
        val l = list("a", "b", "c")
        l.add("x", after = "a")
        assertEquals(listOf("a", "x", "b", "c"), l.items)
        l.add("y")
        assertEquals(listOf("a", "x", "b", "c", "y"), l.items)
    }

    @Test
    fun closingTheTabInSightGoesBackToTheOneSeenBefore() {
        val l = list("a", "b", "c")
        l.select("a")
        // Seen: a, c, b.
        assertEquals("c", l.remove("a"))
        assertEquals("b", l.remove("c"))
        assertNull(l.remove("b"))
    }

    @Test
    fun closingATabOutOfSightKeepsTheOneInSight() {
        val l = list("a", "b", "c")
        assertEquals("c", l.remove("a"))
        assertEquals(listOf("b", "c"), l.items)
    }

    @Test
    fun aFullListTakesNoMore() {
        val l = list("a", "b", max = 2)
        assertTrue(l.full)
        assertFalse(l.add("c"))
        assertEquals(listOf("a", "b"), l.items)
        assertFalse(TabList<String>(5).apply { add("a") }.add("a"))
    }

    @Test
    fun onlyTheMostRecentlySeenPagesStayLoaded() {
        val l = list("a", "b", "c", "d")
        // In sight: d; then c, b, a.
        assertEquals(listOf("b", "a"), l.beyond(1) { true })
        assertEquals(listOf("a"), l.beyond(1) { it != "b" })
        assertEquals(listOf("c", "b", "a"), l.beyond(0) { true })
        assertEquals(emptyList<String>(), l.beyond(3) { true })
        assertEquals(listOf("c", "b", "a"), l.background())
    }

    @Test
    fun theSavedListKeepsWebAddressesAndTheTabInSight() {
        val saved = TabList.encode(listOf("https://a.example/", "http://b.example/x?y=1"), 1)
        assertEquals("1\nhttps://a.example/\nhttp://b.example/x?y=1", saved)
        assertEquals(listOf("https://a.example/", "http://b.example/x?y=1") to 1, TabList.decode(saved, 10))
    }

    @Test
    fun pagesThatArentOnTheWebAreNotSaved() {
        val saved = TabList.encode(listOf("about:blank", "https://a.example/", "file:///sdcard/x.html", "https://b.example/"), 3)
        assertEquals("1\nhttps://a.example/\nhttps://b.example/", saved)
        // The tab in sight was not saved: the first one is in sight next time.
        assertEquals("0\nhttps://a.example/", TabList.encode(listOf("https://a.example/", "data:text/html,x"), 1))
        assertEquals("", TabList.encode(listOf("about:blank"), 0))
        assertEquals("", TabList.encode(emptyList(), 0))
    }

    @Test
    fun aDamagedOrForeignValueGivesNoTabs() {
        assertEquals(emptyList<String>() to 0, TabList.decode(null, 10))
        assertEquals(emptyList<String>() to 0, TabList.decode("", 10))
        assertEquals(emptyList<String>() to 0, TabList.decode("https://a.example/", 10))
        assertEquals(listOf("https://a.example/") to 0, TabList.decode("7\nhttps://a.example/\njavascript:alert(1)", 10))
        assertEquals(listOf("https://a.example/", "https://b.example/") to 0,
            TabList.decode("0\nhttps://a.example/\nhttps://b.example/\nhttps://c.example/", 2))
    }

    @Test
    fun smallerTvsKeepFewerPagesLoaded() {
        val mb = 1024L * 1024L
        assertEquals(0, TabList.liveBackground(4_000 * mb, lowRam = true))
        assertEquals(0, TabList.liveBackground(1_450 * mb, lowRam = false))
        assertEquals(1, TabList.liveBackground(1_900 * mb, lowRam = false))
        assertEquals(2, TabList.liveBackground(2_900 * mb, lowRam = false))
        assertEquals(3, TabList.liveBackground(3_800 * mb, lowRam = false))
    }

    private fun names(vararg tabs: Pair<String?, String?>) =
        TabList.names(tabs.map { it.first }, tabs.map { it.second }, "New tab", { it.removePrefix("https://").removePrefix("www.").removeSuffix("/") })

    @Test
    fun tabsAreNamedByTitleThenAddress() {
        assertEquals(listOf("News", "example.com/a", "New tab"),
            names("News" to "https://news.example/", null to "https://example.com/a", null to null))
    }

    @Test
    fun tabsWithTheSameNameShowWhereTheyDiffer() {
        // Two pages of one site with one title: each shows its address (the strip cuts it in the middle).
        assertEquals(listOf("video.example/watch/1", "video.example/watch/2", "Other"),
            names("Video" to "https://video.example/watch/1", "Video" to "https://video.example/watch/2", "Other" to "https://o.example/"))
        assertEquals(listOf("youtube.com/watch?v=dQw4w9WgXcQ", "youtube.com/watch?v=9bZkp7q19f0"),
            names("YouTube" to "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "YouTube" to "https://www.youtube.com/watch?v=9bZkp7q19f0"))
        // The same page twice: nothing to tell apart, so both keep the title.
        assertEquals(listOf("Video", "Video"), names("Video" to "https://video.example/1", "Video" to "https://video.example/1"))
        // Untitled tabs on the same address are the same page too.
        assertEquals(listOf("a.example", "a.example"), names(null to "https://a.example/", null to "https://a.example/"))
    }
}
