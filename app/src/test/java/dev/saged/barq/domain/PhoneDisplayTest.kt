package dev.saged.barq.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneDisplayTest {

    @Test
    fun searchKeyIgnoresPrefixes() {
        listOf("0599 123 456", "+970599123456", "00972599123456", "059-912-3456", "٠٥٩٩١٢٣٤٥٦")
            .forEach { assertEquals(it, "599123456", PhoneUtils.searchKey(it)) }
    }

    @Test
    fun searchKeyKeepsPartialNumbers() {
        assertEquals("5991", PhoneUtils.searchKey("05991"))
        assertEquals("599", PhoneUtils.searchKey("0599"))
    }

    @Test
    fun searchKeyIsEmptyForTextAndShortInput() {
        assertEquals("", PhoneUtils.searchKey("أحمد"))
        assertEquals("", PhoneUtils.searchKey("059"))
        assertEquals("", PhoneUtils.searchKey("bank123456"))
    }

    @Test
    fun localJoinedShowsRawLocalNumbers() {
        assertEquals("0599123456", PhoneUtils.localJoined("+970599123456"))
        assertEquals("0599123456, 0569123456", PhoneUtils.localJoined("+972599123456,00970569123456"))
        assertEquals("MyBank", PhoneUtils.localJoined("MyBank"))
    }
}
