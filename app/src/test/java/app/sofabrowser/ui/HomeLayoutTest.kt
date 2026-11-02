package app.sofabrowser.ui

import app.sofabrowser.ui.HomeLayout.Button
import app.sofabrowser.ui.HomeLayout.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {

    @Test
    fun theIdsNeverChange() {
        // Stored in Settings and written by a settings export: renaming one would lose its place.
        assertEquals(listOf("quick_access", "most_visited", "bookmarks"), Row.entries.map { it.id })
        assertEquals(listOf("bookmarks", "history", "downloads", "private", "adblock", "settings", "exit"), Button.entries.map { it.id })
    }

    @Test
    fun nothingStoredIsTodaysStartPage() {
        for (stored in listOf(null, "", ";;", " ; ; ")) assertEquals(HomeLayout.DEFAULT, HomeLayout.decode(stored))
        val l = HomeLayout.decode("")
        assertEquals(listOf(Row.QUICK_ACCESS, Row.MOST_VISITED, Row.BOOKMARKS), l.rows.shown)
        assertEquals(listOf(Button.BOOKMARKS, Button.HISTORY, Button.DOWNLOADS, Button.PRIVATE, Button.ADBLOCK, Button.SETTINGS, Button.EXIT),
            l.buttons.shown)
        assertEquals(1, l.bookmarkRows)
        assertEquals(12, l.bookmarkSlots)
        assertEquals("", HomeLayout.DEFAULT.encode())
    }

    @Test
    fun anOrderAndHiddenOnesComeBackAsStored() {
        val l = HomeLayout.DEFAULT.copy(
            rows = HomeLayout.DEFAULT.rows.move(Row.BOOKMARKS.ordinal, 0).show(Row.QUICK_ACCESS, false),
            buttons = HomeLayout.DEFAULT.buttons.move(Button.SETTINGS.ordinal, 0).show(Button.EXIT, false).show(Button.PRIVATE, false))
        assertEquals("bookmarks,-quick_access,most_visited;settings,bookmarks,history,downloads,-private,adblock,-exit;1", l.encode())
        val back = HomeLayout.decode(l.encode())
        assertEquals(l, back)
        assertEquals(listOf(Row.BOOKMARKS, Row.MOST_VISITED), back.rows.shown)
        assertEquals(Button.SETTINGS, back.buttons.shown.first())
        assertEquals(3, back.hiddenCount)
    }

    @Test
    fun goingBackToTheDefaultStoresNothing() {
        val l = HomeLayout.DEFAULT.copy(rows = HomeLayout.DEFAULT.rows.move(0, 2).move(2, 0)).withBookmarkRows(3).withBookmarkRows(1)
        assertTrue(l.isDefault)
        assertEquals("", l.encode())
    }

    @Test
    fun settingsCanNeverBeHiddenButExitAndPrivateCan() {
        val stored = HomeLayout.decode(";-settings,-exit,-private")
        assertTrue(Button.SETTINGS in stored.buttons.shown)
        assertFalse(Button.EXIT in stored.buttons.shown)
        assertFalse(Button.PRIVATE in stored.buttons.shown)
        val b = HomeLayout.DEFAULT.buttons
        assertEquals(b, b.show(Button.SETTINGS, false))
        // Every row can be hidden: the search pill, which isn't one of them, stays.
        val none = Row.entries.fold(HomeLayout.DEFAULT.rows) { r, row -> r.show(row, false) }
        assertTrue(none.shown.isEmpty())
    }

    @Test
    fun mostVisitedIsShownOrHiddenByItsOwnSwitchOnly() {
        // Switched off in Settings, nothing else changed: the page is still the default one, with one row hidden.
        val off = HomeLayout.decode("", mostVisited = false)
        assertFalse(off.mostVisited)
        assertEquals(1, off.hiddenCount)
        assertTrue(off.isDefault)
        assertEquals("", off.encode())
        // Hidden in the editor and moved: the string keeps its place, not its state, which goes to the switch.
        val moved = HomeLayout.DEFAULT.copy(rows = HomeLayout.DEFAULT.rows.move(Row.MOST_VISITED.ordinal, 0).show(Row.MOST_VISITED, false))
        assertEquals("most_visited,quick_access,bookmarks;;1", moved.encode())
        assertFalse(moved.mostVisited)
        assertTrue(HomeLayout.decode(moved.encode(), mostVisited = true).mostVisited)
        assertFalse(HomeLayout.decode("-most_visited", mostVisited = true).rows.shown.isEmpty())
        assertTrue(HomeLayout.decode("-most_visited", mostVisited = true).mostVisited)
    }

    @Test
    fun oneToFourRowsOfTwelveBookmarks() {
        assertEquals(";;2", HomeLayout.DEFAULT.withBookmarkRows(2).encode())
        assertEquals(2, HomeLayout.decode(";;2").bookmarkRows)
        assertEquals(listOf(1, 1, 4, 4, 1), listOf("", ";;0", ";;4", ";;9", ";;two").map { HomeLayout.decode(it).bookmarkRows })
        assertEquals(listOf(12, 24, 36, 48), (1..4).map { HomeLayout.DEFAULT.withBookmarkRows(it).bookmarkSlots })
        assertEquals(4, HomeLayout.DEFAULT.withBookmarkRows(7).bookmarkRows)
        assertEquals(1, HomeLayout.DEFAULT.withBookmarkRows(0).bookmarkRows)

        val thirty = (1..30).toList()
        assertEquals(listOf(12), HomeLayout.DEFAULT.bookmarkLines(thirty).map { it.size })
        assertEquals(listOf(12, 12), HomeLayout.DEFAULT.withBookmarkRows(2).bookmarkLines(thirty).map { it.size })
        // Fewer bookmarks than places: fewer lines, the last one short, and never an empty one.
        assertEquals(listOf(12, 12, 6), HomeLayout.DEFAULT.withBookmarkRows(4).bookmarkLines(thirty).map { it.size })
        assertEquals((1..30).toList(), HomeLayout.DEFAULT.withBookmarkRows(4).bookmarkLines(thirty).flatten())
        assertEquals(listOf(listOf(1, 2, 3)), HomeLayout.DEFAULT.withBookmarkRows(3).bookmarkLines(listOf(1, 2, 3)))
        assertTrue(HomeLayout.DEFAULT.withBookmarkRows(4).bookmarkLines(emptyList<Int>()).isEmpty())
        assertEquals(48, HomeLayout.DEFAULT.withBookmarkRows(4).bookmarkLines((1..60).toList()).flatten().size)
    }

    @Test
    fun aButtonNewInALaterVersionTakesItsDefaultPlace() {
        // Stored by a version without Downloads: it comes back after History, the button before it by default.
        val l = HomeLayout.decode(";exit,history,bookmarks,private,adblock,settings")
        assertEquals(listOf(Button.EXIT, Button.HISTORY, Button.DOWNLOADS, Button.BOOKMARKS), l.buttons.shown.take(4))
        assertEquals(Button.entries.size, l.buttons.entries.size)
    }

    @Test
    fun unknownAndRepeatedIdsAndPartsAreSkipped() {
        val l = HomeLayout.decode(" bookmarks , weather,-bookmarks,,quick_access ; teleport,exit,exit,bookmarks ; 2 ; later")
        assertEquals(Row.BOOKMARKS, l.rows.shown.first())
        assertEquals(Row.entries.size, l.rows.entries.size)
        assertEquals(0, l.rows.hiddenCount)
        assertEquals(Button.EXIT, l.buttons.shown.first())
        assertEquals(1, l.buttons.shown.count { it == Button.EXIT })
        assertEquals(2, l.bookmarkRows)
    }
}
