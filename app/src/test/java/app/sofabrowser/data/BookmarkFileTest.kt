package app.sofabrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun readsEveryWebLinkInFileOrderFromAllFolders() {
        val c = BookmarkFile.read(desktopExport, now)
        assertEquals(listOf(
            BookmarkFile.Entry("News & weather", "https://news.example.com/", 1_600_000_001_000L),
            BookmarkFile.Entry("Talk – part 1", "https://video.example.org/watch?v=1&t=30", 1_600_000_002_000L),
            BookmarkFile.Entry("Lower case", "http://plain.example.net", null),
            BookmarkFile.Entry("https://untitled.example.com/", "https://untitled.example.com/", null),
        ), c.entries)
        // javascript:, place: and the second copy of news.example.com
        assertEquals(3, c.skipped)
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
