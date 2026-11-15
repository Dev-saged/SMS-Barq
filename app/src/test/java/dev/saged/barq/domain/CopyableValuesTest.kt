package dev.saged.barq.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyableValuesTest {

    private fun values(text: String) = CopyableValues.find(text).map { it.value }

    @Test
    fun labeledCredentialsAreSeparateValues() {
        val text = "اسم المستخدم هو : sajed123\nكلمه السر : 181212"
        assertEquals(listOf("sajed123", "181212"), values(text))
    }

    @Test
    fun spanPointsAtTheValueOnly() {
        val text = "كلمة السر: Ab#9xQ"
        val span = CopyableValues.find(text).single()
        assertEquals("Ab#9xQ", text.substring(span.range.first, span.range.last + 1))
    }

    @Test
    fun arabicDigitsAreCopiedAsLatin() {
        assertEquals(listOf("123456"), values("رمزك: ١٢٣٤٥٦"))
    }

    @Test
    fun plainArabicAfterColonIsNotAValue() {
        assertTrue(values("الاسم: أحمد").isEmpty())
    }

    @Test
    fun emailsAndMixedCodesAreFound() {
        assertEquals(listOf("sajed@mail.com", "TRK98231"), values("راسلني sajed@mail.com رقم الشحنة TRK98231"))
    }

    @Test
    fun datesTimesAndLinksAreLeftAlone() {
        assertTrue(values("موعدنا 25/12/2024 الساعة 10:30").isEmpty())
        assertTrue(values("افتح https://example.com/a1b2c3 الآن").isEmpty())
    }

    @Test
    fun phoneNumbersAreCopyable() {
        assertEquals(listOf("059 912 3456"), values("اتصل على 059 912 3456 غداً"))
    }

    @Test
    fun spansDoNotOverlapAndAreSorted() {
        val spans = CopyableValues.find("user: sajed123\ncode 4455 mail a@b.co")
        spans.zipWithNext().forEach { (a, b) -> assertTrue(a.range.last < b.range.first) }
    }
}
