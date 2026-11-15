package dev.saged.barq.domain

import dev.saged.barq.data.db.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SearchFiltersTest {

    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 15, 12, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    @Test
    fun defaultFiltersAreInactiveAndMatchEverything() {
        val f = SearchFilters()
        assertFalse(f.active)
        assertTrue(f.matches("أي نص", 0L, Box.SENT, now))
    }

    @Test
    fun todayStartsAtLocalMidnight() {
        val f = SearchFilters(period = SearchPeriod.TODAY)
        assertTrue(f.matches("x", now - 11 * hour, Box.INBOX, now))
        assertFalse(f.matches("x", now - 13 * hour, Box.INBOX, now))
    }

    @Test
    fun weekAndMonthWindows() {
        assertTrue(SearchFilters(period = SearchPeriod.WEEK).matches("x", now - 6 * day, Box.INBOX, now))
        assertFalse(SearchFilters(period = SearchPeriod.WEEK).matches("x", now - 8 * day, Box.INBOX, now))
        assertTrue(SearchFilters(period = SearchPeriod.MONTH).matches("x", now - 29 * day, Box.INBOX, now))
        assertFalse(SearchFilters(period = SearchPeriod.MONTH).matches("x", now - 31 * day, Box.INBOX, now))
    }

    @Test
    fun directionSeparatesReceivedFromSent() {
        val received = SearchFilters(direction = SearchDirection.RECEIVED)
        val sent = SearchFilters(direction = SearchDirection.SENT)
        assertTrue(received.matches("x", now, Box.INBOX, now))
        assertFalse(received.matches("x", now, Box.SENT, now))
        assertTrue(sent.matches("x", now, Box.SENT, now))
        assertFalse(sent.matches("x", now, Box.INBOX, now))
    }

    @Test
    fun linkAmountAndCodeDetection() {
        val link = SearchFilters(has = setOf(SearchHas.LINK))
        assertTrue(link.matches("افتح https://example.com/x", now, Box.INBOX, now))
        assertFalse(link.matches("مرحبا كيف حالك", now, Box.INBOX, now))

        val amount = SearchFilters(has = setOf(SearchHas.AMOUNT))
        assertTrue(amount.matches("تم خصم 150.50 شيكل من حسابك", now, Box.INBOX, now))
        assertTrue(amount.matches("تم خصم ١٥٠ ريال", now, Box.INBOX, now))
        assertFalse(amount.matches("موعدنا الساعة 5", now, Box.INBOX, now))

        val code = SearchFilters(has = setOf(SearchHas.CODE))
        assertTrue(code.matches("رمز التحقق 482913", now, Box.INBOX, now))
        assertFalse(code.matches("أراك غداً", now, Box.INBOX, now))
    }

    @Test
    fun filtersCombine() {
        val f = SearchFilters(SearchPeriod.WEEK, SearchDirection.RECEIVED, setOf(SearchHas.LINK, SearchHas.AMOUNT))
        assertTrue(f.active)
        assertTrue(f.matches("ادفع 20 دولار www.pay.com", now - day, Box.INBOX, now))
        assertFalse(f.matches("ادفع 20 دولار", now - day, Box.INBOX, now))
        assertEquals(false, f.matches("ادفع 20 دولار www.pay.com", now - day, Box.SENT, now))
    }
}
