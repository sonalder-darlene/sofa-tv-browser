package app.sofabrowser.data

import app.sofabrowser.adblock.FilterList
import app.sofabrowser.data.SettingsFile.ListChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFileTest {

    @Test fun takesValuesSettingsCouldHaveSet() {
        val (taken, leftOut) = SettingsFile.clean(mapOf(
            "theme" to "light",
            "text_zoom" to 130,
            "cursor_speed" to 3L,
            "history_days" to 30.0,
            "adblock" to false,
            "search_engine" to "custom",
            "custom_search" to "https://searx.example/search?q=%s",
            "dns_main" to "https://dns.example/dns-query",
            "dns_fallback" to "",
            "home_background" to "color:ocean",
            "keys_to_page" to listOf("tv.example", "app.example"),
            "auto_watch" to "SITES",
            "drm_mode" to "block",
        ))
        assertEquals(0, leftOut)
        assertEquals("light", taken["theme"])
        assertEquals(130, taken["text_zoom"])
        assertEquals(3, taken["cursor_speed"])
        assertEquals(30, taken["history_days"])
        assertEquals(false, taken["adblock"])
        assertEquals(listOf("tv.example", "app.example"), taken["keys_to_page"])
        assertEquals("color:ocean", taken["home_background"])
    }

    @Test fun nullMeansTheDefault() {
        val (taken, leftOut) = SettingsFile.clean(mapOf("theme" to null, "dns_main" to null))
        assertEquals(0, leftOut)
        assertTrue("theme" in taken && taken["theme"] == null)
    }

    @Test fun leavesOutWhatSettingsCouldNotHaveSet() {
        val bad = mapOf(
            "theme" to "purple",
            "text_zoom" to 1000,
            "cursor_speed" to 0,
            "history_days" to 12,
            "adblock" to "yes",
            "custom_search" to "http://plain.example/?q=%s",
            "search_engine" to "nowhere",
            "dns_main" to "8.8.8.8",
            "start_page" to "javascript:alert(1)",
            "home_background" to "image",
            "user_agent" to "custom\nX-Header: 1",
            "custom_user_agent" to "a\nb",
            "keys_to_page" to listOf("ok.example", 3),
            "drm_allowed" to listOf("has space.example"),
            "auto_watch" to "sites",
            "player_quality" to "garbage",
            "cursor_speed_typo" to 2,
            // What a file must never set, even with a fitting value.
            "last_page" to "https://example.com/",
            "tabs" to "0\nhttps://example.com/",
            "setup_done" to true,
            "private_mode" to true,
        )
        val (taken, leftOut) = SettingsFile.clean(bad)
        assertTrue(taken.toString(), taken.isEmpty())
        assertEquals(bad.size, leftOut)
    }

    @Test fun neverCarriesBrowsingDataOrThePin() {
        for (key in listOf("last_page", "tabs", "private_mode", "setup_done", "pin", "hash", "salt", "webview_warned_version",
            "last_update_check", "skipped_update", "updates_asked", "list_updates_asked", "stronger_asked", "pad_hints",
            "asked_notifications", "remember_history")) {
            assertFalse(key, key in SettingsFile.RULES)
        }
    }

    @Test fun askIsGivenTheLastWord() {
        val (taken, leftOut) = SettingsFile.clean(mapOf("video_player" to "gone.app/.Player", "theme" to "dark")) { key, _ -> key != "video_player" }
        assertEquals(mapOf("theme" to "dark"), taken)
        assertEquals(1, leftOut)
    }

    @Test fun listsByIdOrByHttpsAddress() {
        val lists = SettingsFile.cleanLists(listOf(
            ListChoice(FilterList.EASYLIST, "https://elsewhere.example/list.txt", on = true, site = true),
            ListChoice(FilterList.GAMBLING, FilterList.GAMBLING_FULL_URL, on = true, site = false),
            ListChoice(FilterList.ADULT, "https://elsewhere.example/nsfw.txt", on = true, site = true),
            ListChoice(FilterList.EASYLIST, FilterList.EASYLIST_URL, on = false, site = false),
            ListChoice("from-a-later-version", "https://later.example/list.txt", on = true, site = false),
            ListChoice(null, "https://lists.example/mine.txt", on = true, site = false),
            ListChoice(null, "https://lists.example/mine.txt", on = true, site = true),
            ListChoice(null, "https://lists.example/mine.txt", on = false, site = false),
            ListChoice(null, "http://lists.example/plain.txt", on = true, site = false),
            ListChoice(null, "file:///sdcard/list.txt", on = true, site = false),
        ))
        assertEquals(listOf(
            // A known list keeps its own address and kind, whatever the file says.
            ListChoice(FilterList.EASYLIST, FilterList.EASYLIST_URL, on = true, site = false),
            // The gambling list keeps the size the file chose.
            ListChoice(FilterList.GAMBLING, FilterList.GAMBLING_FULL_URL, on = true, site = true),
            ListChoice(FilterList.ADULT, FilterList.ADULT_URL, on = true, site = true),
            ListChoice(null, "https://lists.example/mine.txt", on = true, site = false),
            ListChoice(null, "https://lists.example/mine.txt", on = true, site = true),
        ), lists)
        assertEquals(1, SettingsFile.cleanLists(listOf(ListChoice(null, "http://lists.example/plain.txt", on = true, site = false)), http = true).size)
    }

    @Test fun entriesAreWebAddressesOnce() {
        val entries = SettingsFile.cleanEntries(listOf(
            BookmarkFile.Entry("A\nsite", "https://a.example/", 5),
            BookmarkFile.Entry("Again", "https://a.example/"),
            BookmarkFile.Entry("Script", "javascript:void(0)"),
            BookmarkFile.Entry("x".repeat(900), "http://b.example/"),
            BookmarkFile.Entry("Spaced", "https://c.example/a b"),
        ), max = 10)
        assertEquals(listOf("https://a.example/", "http://b.example/"), entries.map { it.url })
        assertEquals("A site", entries[0].title)
        assertEquals(5L, entries[0].added)
        assertEquals(500, entries[1].title.length)
        assertEquals(1, SettingsFile.cleanEntries(entries, max = 1).size)
    }

    @Test fun namesAreChecked() {
        assertEquals(listOf("a.example", "b.example"), SettingsFile.cleanNames(listOf("a.example", "", "b.example", "a.example", "c d")))
    }
}
