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
        // The tab in sight was not saved: the saved one before it is in sight next time, else the one after.
        assertEquals("0\nhttps://a.example/", TabList.encode(listOf("https://a.example/", "data:text/html,x"), 1))
        assertEquals("1\nhttps://a.example/\nhttps://b.example/\nhttps://c.example/",
            TabList.encode(listOf("https://a.example/", "https://b.example/", "", "https://c.example/"), 2))
        assertEquals("0\nhttps://a.example/\nhttps://b.example/",
            TabList.encode(listOf("", "about:blank", "https://a.example/", "https://b.example/"), 1))
        assertEquals("", TabList.encode(listOf("about:blank"), 0))
        assertEquals("", TabList.encode(emptyList(), 0))
    }

    @Test
    fun theTabInSightIsKeptByItsPlaceNotItsAddress() {
        val a = "https://a.example/"
        val b = "https://b.example/"
        assertEquals("2\n$a\n$b\n$a", TabList.encode(listOf(a, b, a), 2))
        assertEquals(listOf(a, b, a) to 2, TabList.decode(TabList.encode(listOf(a, b, a), 2), 10))
        // Unsaved tabs before it don't shift it.
        assertEquals("1\n$a\n$a", TabList.encode(listOf(a, "about:blank", a), 2))
        // An index out of range (no tab in sight) clamps rather than failing.
        assertEquals("0\n$a", TabList.encode(listOf(a), -1))
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

    @Test
    fun aPageOutOfSightRestsTenMinutesThenGoes() {
        var now = 0L
        val l = TabList<String>(10) { now }
        listOf("a", "b", "c").forEach { l.add(it); l.select(it) }
        val live = { _: String -> true }
        // c in sight; b left it at 0, a too (added at 0, then left).
        assertEquals(emptyList<String>(), l.rested(600_000, live))
        assertEquals(600_000L, l.nextRest(600_000, live))
        now = 300_000
        l.select("a")
        // a in sight again, c left at 300 s; b has been out of sight since 0.
        now = 600_000
        assertEquals(listOf("b"), l.rested(600_000, live))
        // Once b is unloaded, only c is loaded out of sight: due 300 s later.
        assertEquals(300_000L, l.nextRest(600_000) { it == "c" })
        now = 900_000
        assertEquals(listOf("c"), l.rested(600_000) { it == "c" })
        // The tab in sight never rests, however long it has been there.
        assertEquals(emptyList<String>(), l.rested(600_000) { it == "a" })
        assertNull(l.nextRest(600_000) { it == "a" })
    }

    @Test
    fun aTabComingBackIntoSightStartsItsRestAgainWhenItLeaves() {
        var now = 0L
        val l = TabList<String>(10) { now }
        listOf("a", "b").forEach { l.add(it); l.select(it) }
        now = 500_000
        l.select("a")
        now = 550_000
        l.select("b")
        // a went out of sight at 550 s, not at 0.
        now = 1_000_000
        assertEquals(emptyList<String>(), l.rested(600_000) { true })
        assertEquals(150_000L, l.nextRest(600_000) { true })
        // Closing the tab in sight brings a back into sight: nothing out of sight is loaded.
        l.remove("b")
        assertNull(l.nextRest(600_000) { true })
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
