package app.sofabrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkFileTest {

    private val now = 1_800_000_000_000L

    /** The shape desktop browsers export: nested folders, upper-case tags, icons, entities. */
    private val desktopExport = """
        <!DOCTYPE NETSCAPE-Bookmark-file-1>
        <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
        <TITLE>Bookmarks</TITLE>
        <H1>Bookmarks</H1>
        <DL><p>
            <DT><H3 ADD_DATE="1600000000" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks bar</H3>
            <DL><p>
                <DT><A HREF="https://news.example.com/" ADD_DATE="1600000001" ICON="data:image/png;base64,iVBORw0KGgo=">News &amp; weather</A>
                <DT><H3>Videos</H3>
                <DL><p>
                    <DT><A HREF="https://video.example.org/watch?v=1&amp;t=30" ADD_DATE="1600000002">Talk &#8211; part 1</A>
                </DL><p>
            </DL><p>
            <DT><A HREF="javascript:void(0)">A bookmarklet</A>
            <DT><A HREF="place:sort=8&maxResults=10">Recent tags</A>
            <DT><a href='http://plain.example.net'>  Lower
                case   </a>
            <DT><A HREF="https://news.example.com/">News again</A>
            <DT><A HREF="https://untitled.example.com/"></A>
        </DL><p>
    """.trimIndent()

    @Test fun readsEveryWebLinkInFileOrderWithItsFolder() {
        val c = BookmarkFile.read(desktopExport, now)
        assertEquals(listOf(
            // The bookmarks bar is opened up: its links are at the top, its folders top folders.
            BookmarkFile.Entry("News & weather", "https://news.example.com/", 1_600_000_001_000L),
            BookmarkFile.Entry("Talk – part 1", "https://video.example.org/watch?v=1&t=30", 1_600_000_002_000L, listOf("Videos")),
            BookmarkFile.Entry("Lower case", "http://plain.example.net", null),
            BookmarkFile.Entry("https://untitled.example.com/", "https://untitled.example.com/", null),
        ), c.entries)
        // javascript:, place: and the second copy of news.example.com
        assertEquals(3, c.skipped)
    }

    /** Chrome, Edge, Brave, Opera, Vivaldi: everything in root folders, the bar marked as the toolbar. */
    private val chromiumExport = """
        <!DOCTYPE NETSCAPE-Bookmark-file-1>
        <!-- This is an automatically generated file.
             It will be read and overwritten.
             DO NOT EDIT! -->
        <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
        <TITLE>Bookmarks</TITLE>
        <H1>Bookmarks</H1>
        <DL><p>
            <DT><H3 ADD_DATE="1600000000" LAST_MODIFIED="1600000100" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks bar</H3>
            <DL><p>
                <DT><A HREF="https://mail.example.com/" ADD_DATE="1600000001" ICON="data:image/png;base64,iVBORw0KGgo=">Mail</A>
                <DT><H3 ADD_DATE="1600000002" LAST_MODIFIED="1600000003">News</H3>
                <DL><p>
                    <DT><A HREF="https://news.example.com/" ADD_DATE="1600000004">Daily news</A>
                    <DT><H3 ADD_DATE="1600000005" LAST_MODIFIED="0">Tech</H3>
                    <DL><p>
                        <DT><A HREF="https://tech.example.org/" ADD_DATE="1600000006">Tech site</A>
                    </DL><p>
                </DL><p>
                <DT><H3 ADD_DATE="1600000007" LAST_MODIFIED="0">Nothing yet</H3>
                <DL><p>
                </DL><p>
            </DL><p>
            <DT><H3 ADD_DATE="1600000008" LAST_MODIFIED="0">Other bookmarks</H3>
            <DL><p>
                <DT><A HREF="https://recipes.example.net/" ADD_DATE="1600000009">Recipes</A>
            </DL><p>
            <DT><H3 ADD_DATE="1600000010" LAST_MODIFIED="0">Mobile bookmarks</H3>
            <DL><p>
                <DT><A HREF="https://phone.example.com/" ADD_DATE="1600000011">On the phone</A>
            </DL><p>
        </DL><p>
    """.trimIndent()

    @Test fun chromiumBarOpensUpAndOtherRootFoldersStay() {
        val c = BookmarkFile.read(chromiumExport, now)
        assertEquals(listOf(
            "Mail" to emptyList(),
            "Daily news" to listOf("News"),
            "Tech site" to listOf("News", "Tech"),
            "Recipes" to listOf("Other bookmarks"),
            "On the phone" to listOf("Mobile bookmarks"),
        ), c.entries.map { it.title to it.folder })
        assertEquals(0, c.skipped)
    }

    /** Firefox: the menu's contents at the root, the toolbar and "Other Bookmarks" as marked folders, smart folders. */
    private val firefoxExport = """
        <!DOCTYPE NETSCAPE-Bookmark-file-1>
        <!-- This is an automatically generated file.
             It will be read and overwritten.
             DO NOT EDIT! -->
        <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
        <meta http-equiv="Content-Security-Policy"
              content="default-src 'self'; script-src 'none'; img-src data: *; object-src 'none'"></meta>
        <TITLE>Bookmarks</TITLE>
        <H1>Bookmarks Menu</H1>

        <DL><p>
            <DT><H3 ADD_DATE="1600000000" LAST_MODIFIED="1600000001">Browser help</H3>
            <DL><p>
                <DT><A HREF="https://help.example.org/" ADD_DATE="1600000002" LAST_MODIFIED="1600000002" ICON_URI="https://help.example.org/favicon.ico">Get help</A>
            </DL><p>
            <HR>
            <DT><A HREF="https://menu.example.com/" ADD_DATE="1600000003" LAST_MODIFIED="1600000003">In the menu</A>
            <DD>A description the browser keeps
            <DT><H3 ADD_DATE="1600000004" LAST_MODIFIED="1600000005" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks Toolbar</H3>
            <DL><p>
                <DT><A HREF="https://toolbar.example.com/" ADD_DATE="1600000006">On the toolbar</A>
                <DT><H3 ADD_DATE="1600000007" LAST_MODIFIED="1600000008">Smart</H3>
                <DL><p>
                    <DT><A HREF="place:sort=8&amp;maxResults=10" ADD_DATE="1600000009">Most visited</A>
                </DL><p>
            </DL><p>
            <DT><H3 ADD_DATE="1600000010" LAST_MODIFIED="1600000011" UNFILED_BOOKMARKS_FOLDER="true">Other Bookmarks</H3>
            <DL><p>
                <DT><A HREF="https://other.example.net/" ADD_DATE="1600000012">Filed later</A>
            </DL><p>
        </DL>
    """.trimIndent()

    @Test fun firefoxToolbarOpensUpAndSmartFoldersGo() {
        val c = BookmarkFile.read(firefoxExport, now)
        assertEquals(listOf(
            "Get help" to listOf("Browser help"),
            "In the menu" to emptyList(),
            "On the toolbar" to emptyList(),
            "Filed later" to listOf("Other Bookmarks"),
        ), c.entries.map { it.title to it.folder })
        // The smart folder's place: link; with nothing else in it, "Smart" has no bookmark and isn't made.
        assertEquals(1, c.skipped)
        assertTrue(c.entries.none { "Smart" in it.folder })
    }

    /** Safari: no outer list, folders marked FOLDED, the favourites bar without the toolbar mark, the Reading List. */
    private val safariExport = """
        <!DOCTYPE NETSCAPE-Bookmark-file-1>
        	<HTML>
        	<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
        	<Title>Bookmarks</Title>
        	<H1>Bookmarks</H1>
        	<DT><H3 FOLDED>Favourites</H3>
        	<DL><p>
        		<DT><A HREF="https://fav.example.com/">Favourite site</A>
        	</DL><p>
        	<DT><H3 FOLDED>Bookmarks Menu</H3>
        	<DL><p>
        	</DL><p>
        	<DT><H3 FOLDED>Holidays</H3>
        	<DL><p>
        		<DT><A HREF="https://trip.example.org/">Trip planner</A>
        	</DL><p>
        	<DT><H3 FOLDED id="com.apple.ReadingList">Reading List</H3>
        	<DL><p>
        		<DT><A HREF="https://long.example.net/read">Long read</A>
        	</DL><p>
        	</HTML>
    """.trimIndent()

    @Test fun safariFoldersStayFoldersAndEmptyOnesGo() {
        val c = BookmarkFile.read(safariExport, now)
        assertEquals(listOf(
            "Favourite site" to listOf("Favourites"),
            "Trip planner" to listOf("Holidays"),
            "Long read" to listOf("Reading List"),
        ), c.entries.map { it.title to it.folder })
    }

    @Test fun separatorsUnclosedTagsAndNamelessFolders() {
        val c = BookmarkFile.read("""
            <DL><p>
            <DT><H3>News
            <DL><p>
                <DT><A HREF="https://a.example/">Story
                <HR>
                <DT><H3></H3>
                <DL><p>
                    <DT><A HREF="https://b.example/">In a nameless folder</A>
                </DL><p>
                <DT><H3>  Deep
                   down  </H3>
                <DL><p>
                    <DT><A HREF="https://c.example/">Deep link</A>
            </DL>
            <DT><A HREF="https://d.example/">After the folders</A>
        """.trimIndent(), now)
        assertEquals(listOf(
            "Story" to listOf("News"),
            "In a nameless folder" to listOf("News"),
            "Deep link" to listOf("News", "Deep down"),
            // The file forgot one </DL>: the link stays in the folder still open, nothing is lost.
            "After the folders" to listOf("News"),
        ), c.entries.map { it.title to it.folder })
    }

    @Test fun onlyATopBarIsOpenedUp() {
        val c = BookmarkFile.read("""
            <DL><p><DT><H3>Saved</H3><DL><p>
                <DT><H3 PERSONAL_TOOLBAR_FOLDER="true">Bar</H3><DL><p>
                    <DT><A HREF="https://a.example/">A</A>
                </DL><p>
            </DL><p></DL><p>
        """.trimIndent(), now)
        assertEquals(listOf("Saved", "Bar"), c.entries.single().folder)
    }

    @Test fun foldersDeeperThanTheLimitGoIntoTheDeepest() {
        val depth = FolderTree.MAX_DEPTH + 5
        val html = (1..depth).joinToString("\n") { "<DT><H3>L$it</H3><DL><p>" } +
            "\n<DT><A HREF=\"https://deep.example/\">Deep</A>\n" + "</DL><p>\n".repeat(depth) +
            "<DT><A HREF=\"https://top.example/\">Top</A>"
        val c = BookmarkFile.read(html, now)
        assertEquals((1..FolderTree.MAX_DEPTH).map { "L$it" }, c.entries[0].folder)
        assertEquals(emptyList<String>(), c.entries[1].folder)
    }

    @Test fun foldersPastTheLimitOpenIntoTheirParent() {
        val n = BookmarkFile.MAX_FOLDERS + 3
        val html = (0 until n).joinToString("\n") { "<DT><H3>F$it</H3><DL><p><DT><A HREF=\"https://e.example/$it\">$it</A></DL><p>" } +
            // A folder already taken still opens after the limit.
            "\n<DT><H3>F0</H3><DL><p><DT><A HREF=\"https://e.example/again\">again</A></DL><p>"
        val c = BookmarkFile.read(html, now)
        assertEquals(listOf("F0"), c.entries[0].folder)
        assertEquals(listOf("F${BookmarkFile.MAX_FOLDERS - 1}"), c.entries[BookmarkFile.MAX_FOLDERS - 1].folder)
        assertEquals(emptyList<String>(), c.entries[BookmarkFile.MAX_FOLDERS].folder)
        assertEquals(listOf("F0"), c.entries.last().folder)
    }

    @Test fun writtenFileReadsBackTheSame() {
        val entries = listOf(
            BookmarkFile.Entry("Quotes \"and\" <tags> & more", "https://example.com/a?b=1&c=\"2\"", 1_700_000_000_000L),
            BookmarkFile.Entry("Plain", "http://example.org/", null),
        )
        val file = BookmarkFile.write(entries)
        assertEquals(entries, BookmarkFile.read(file, now).entries)
        assert(file.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>"))
    }

    @Test fun writtenTreeReadsBackTheSame() {
        val entries = listOf(
            BookmarkFile.Entry("Top", "https://top.example/", 1_700_000_000_000L),
            BookmarkFile.Entry("Story", "https://news.example/", 1_700_000_001_000L, listOf("News")),
            BookmarkFile.Entry("Phone <review>", "https://tech.example/", 1_700_000_002_000L, listOf("News", "Tech & toys")),
            BookmarkFile.Entry("Soup", "https://soup.example/", null, listOf("Recipes")),
        )
        val folders = listOf(BookmarkFile.Folder(listOf("News"), 1_600_000_000_000L), BookmarkFile.Folder(listOf("Empty"), 1_650_000_000_000L))
        val file = BookmarkFile.write(entries, folders)
        assertEquals(entries.toSet(), BookmarkFile.read(file, now).entries.toSet())
        // Folders A to Z before the bookmarks, with their dates; an empty folder is still written.
        assert("<DT><H3 ADD_DATE=\"1600000000\">News</H3>" in file)
        assert("<DT><H3 ADD_DATE=\"1650000000\">Empty</H3>" in file)
        assert("Tech &amp; toys" in file)
        assert(file.indexOf(">Empty</H3>") < file.indexOf(">News</H3>"))
        assert(file.indexOf(">Recipes</H3>") < file.indexOf(">Top</A>"))
    }
    @Test fun datesInSecondsMillisecondsOrMicroseconds() {
        assertEquals(1_600_000_000_000L, BookmarkFile.date("1600000000", now))
        assertEquals(1_600_000_000_123L, BookmarkFile.date("1600000000123", now))
        assertEquals(1_600_000_000_123L, BookmarkFile.date("1600000000123456", now))
        assertEquals(now, BookmarkFile.date("9999999999", now)) // in the future: capped
        assertNull(BookmarkFile.date("0", now))
        assertNull(BookmarkFile.date("soon", now))
    }

    @Test fun attributesWithAnyQuotingAndOnlyWholeNames() {
        assertEquals("https://a.example/", BookmarkFile.attribute("""HREF="https://a.example/"""", "href"))
        assertEquals("https://b.example/", BookmarkFile.attribute("href='https://b.example/'", "href"))
        assertEquals("https://c.example/", BookmarkFile.attribute("href=https://c.example/ add_date=1", "href"))
        assertNull(BookmarkFile.attribute("""data-href="https://d.example/"""", "href"))
        assertEquals("", BookmarkFile.attribute("""href="""""", "href"))
    }

    @Test fun entitiesDecodeAndUnknownOnesStay() {
        assertEquals("a & b < c > \"d\" 'e' é", BookmarkFile.decode("a &amp; b &lt; c &gt; &quot;d&quot; &#39;e&#x27; &#233;"))
        assertEquals("© – … &#0; &bogus", BookmarkFile.decode("&copy; &ndash; &hellip; &#0; &bogus"))
    }

    @Test fun aLinkLeftOpenEndsAtTheNextLine() {
        val c = BookmarkFile.read("<DL><DT><A HREF=\"https://a.example/\">First\n<DT><A HREF=\"https://b.example/\">Second</A></DL>", now)
        assertEquals(listOf("First", "Second"), c.entries.map { it.title })
    }

    @Test fun emptyOrForeignFileGivesNothing() {
        assertEquals(BookmarkFile.Contents(emptyList(), 0), BookmarkFile.read("", now))
        assertEquals(BookmarkFile.Contents(emptyList(), 0), BookmarkFile.read("just some text, no links", now))
    }

    @Test fun stopsAtTheEntryLimit() {
        val many = (0..BookmarkFile.MAX_ENTRIES + 4).joinToString("\n") { """<DT><A HREF="https://e.example/$it">$it</A>""" }
        val c = BookmarkFile.read(many, now)
        assertEquals(BookmarkFile.MAX_ENTRIES, c.entries.size)
        assertEquals(5, c.skipped)
    }
}
