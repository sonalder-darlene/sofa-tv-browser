package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeTimeTest {
    private fun words(n: Int) = List(n) { "word" }.joinToString(" ")

    @Test fun shortNoticeStaysFiveSeconds() = assertEquals(5000L, NoticeTime.ms("Sofa Player plays H.264"))

    @Test fun oneSecondMorePerTenWords() {
        assertEquals(5000L, NoticeTime.ms(words(9)))
        assertEquals(6000L, NoticeTime.ms(words(10)))
        assertEquals(8000L, NoticeTime.ms(words(31)))
    }

    @Test fun cappedForVeryLongText() = assertEquals(NoticeTime.MAX_MS, NoticeTime.ms(words(400)))

    @Test fun lineBreaksAndSpacesCountAsOneGap() = assertEquals(5000L, NoticeTime.ms("  one\n\ntwo   three "))
}
