package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockedHistoryTest {

    private val blocked = { url: String -> "casino" in url || "bets" in url }

    @Test fun backSkipsEveryBlockedPage() {
        val urls = listOf("https://news.example/", "https://casino.example/", "https://bets.example/")
        assertEquals(-2, BlockedHistory.back(urls, 2, blocked))
    }

    @Test fun backOneStepToAPage() {
        assertEquals(-1, BlockedHistory.back(listOf("https://news.example/", "https://casino.example/"), 1, blocked))
    }

    @Test fun nothingWhenOnlyBlockedPagesAreLeft() {
        assertNull(BlockedHistory.back(listOf("https://bets.example/", "https://casino.example/"), 1, blocked))
        assertNull(BlockedHistory.back(listOf("https://casino.example/"), 0, blocked))
        assertNull(BlockedHistory.back(emptyList(), -1, blocked))
    }

    @Test fun nonWebEntriesAreNoWayBack() {
        assertNull(BlockedHistory.back(listOf("about:blank", null, "https://casino.example/"), 2, blocked))
    }

    @Test fun forwardEntriesDontCount() {
        val urls = listOf("https://casino.example/", "https://bets.example/", "https://news.example/")
        assertNull(BlockedHistory.back(urls, 1, blocked))
    }

    @Test fun keptIsThePageOrTheOneBeforeTheBlockedOnes() {
        assertEquals("https://news.example/", BlockedHistory.kept(listOf("https://news.example/"), 0, blocked))
        val urls = listOf("https://news.example/", "https://casino.example/", "https://bets.example/")
        assertEquals("https://news.example/", BlockedHistory.kept(urls, 2, blocked))
        assertNull(BlockedHistory.kept(listOf("https://casino.example/"), 0, blocked))
        assertNull(BlockedHistory.kept(emptyList(), -1, blocked))
    }
}
