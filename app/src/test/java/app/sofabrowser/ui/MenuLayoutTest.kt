package app.sofabrowser.ui

import app.sofabrowser.ui.MenuLayout.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuLayoutTest {

    @Test
    fun theIdsNeverChange() {
        // Stored in Settings and written by a settings export: renaming one would lose that button's place.
        assertEquals(listOf("home", "watch", "back", "forward", "reload", "find", "bookmark", "pin", "history", "bookmarks",
            "downloads", "adblock", "auto_watch", "user_agent", "scroll", "pointer", "zoom", "settings"), Item.entries.map { it.id })
    }

    @Test
    fun nothingStoredIsTheDefaultMenu() {
        assertEquals(MenuLayout.DEFAULT, MenuLayout.decode(null))
        assertEquals(MenuLayout.DEFAULT, MenuLayout.decode(""))
        assertEquals("", MenuLayout.DEFAULT.encode())
        assertEquals(Item.entries, MenuLayout.DEFAULT.shown)
    }

    @Test
    fun anOrderAndHiddenButtonsComeBackAsStored() {
        val l = MenuLayout.DEFAULT.move(Item.WATCH.ordinal, 0).show(Item.BACK.ordinal, false)
        assertEquals("watch,home,-back", l.encode().split(',').take(3).joinToString(","))
        val back = MenuLayout.decode(l.encode())
        assertEquals(l, back)
        assertEquals(Item.WATCH, back.shown.first())
        assertFalse(Item.BACK in back.shown)
        assertEquals(1, back.hiddenCount)
    }

    @Test
    fun goingBackToTheDefaultStoresNothing() {
        val l = MenuLayout.DEFAULT.move(3, 7).move(7, 3)
        assertTrue(l.isDefault)
        assertEquals("", l.encode())
        assertEquals("", MenuLayout.DEFAULT.show(2, false).show(2, true).encode())
    }

    @Test
    fun aButtonNewInALaterVersionTakesItsDefaultPlace() {
        // Stored by a version without Find: it comes back after Reload, the button before it by default.
        val stored = MenuLayout.DEFAULT.move(Item.SETTINGS.ordinal, 0).entries.filter { it.item != Item.FIND }
            .joinToString(",") { it.item.id }
        val l = MenuLayout.decode(stored)
        assertEquals(Item.SETTINGS, l.shown[0])
        assertEquals(l.shown.indexOf(Item.RELOAD) + 1, l.shown.indexOf(Item.FIND))
        assertEquals(Item.entries.size, l.entries.size)
        // With none of the buttons before it named, a new one goes first.
        assertEquals(Item.HOME, MenuLayout.decode("watch,settings").shown[0])
    }

    @Test
    fun unknownAndRepeatedIdsAreSkipped() {
        val l = MenuLayout.decode(" reload , teleport,-reload,home,, -nothing")
        assertEquals(Item.RELOAD, l.shown[0])
        assertEquals(1, l.shown.count { it == Item.RELOAD })
        assertEquals(Item.entries.size, l.entries.size)
        assertEquals(0, l.hiddenCount)
    }

    @Test
    fun settingsAndHomeCanNeverBeHidden() {
        val stored = MenuLayout.decode("-settings,-home,-back")
        assertTrue(Item.SETTINGS in stored.shown && Item.HOME in stored.shown)
        assertFalse(Item.BACK in stored.shown)
        val l = MenuLayout.DEFAULT
        assertSame(l, l.show(Item.SETTINGS.ordinal, false))
        assertSame(l, l.show(Item.HOME.ordinal, false))
        assertFalse(Item.WATCH in l.show(Item.WATCH.ordinal, false).shown)
    }

    @Test
    fun movesOutOfRangeChangeNothing() {
        val l = MenuLayout.DEFAULT
        assertSame(l, l.move(0, -1))
        assertSame(l, l.move(0, Item.entries.size))
        assertSame(l, l.move(-1, 2))
        assertSame(l, l.show(99, false))
    }

    @Test
    fun aMoveShiftsTheButtonsBetween() {
        val l = MenuLayout.DEFAULT.move(0, 2)
        assertEquals(listOf(Item.WATCH, Item.BACK, Item.HOME, Item.FORWARD), l.shown.take(4))
    }
}
