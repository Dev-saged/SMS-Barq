package dev.saged.barq.domain

import dev.saged.barq.data.db.Category
import dev.saged.barq.data.db.CategoryRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpDetectorTest {
    @Test fun arabicCodeWithTrailingDot() =
        assertEquals("482913", OtpDetector.detect("رمز التحقق الخاص بك هو 482913. لا تشاركه مع أحد."))

    @Test fun googleStyle() = assertEquals("123456", OtpDetector.detect("G-123456 is your Google verification code."))

    @Test fun splitCode() = assertEquals("482913", OtpDetector.detect("Your code: 482 913"))

    @Test fun arabicIndicDigits() = assertEquals("4829", OtpDetector.detect("كود التفعيل: ٤٨٢٩"))

    @Test fun ignoresAmounts() = assertNull(OtpDetector.detect("تم خصم 45.00 من رصيدك"))

    @Test fun ignoresAmountWithCurrencyEvenWithKeyword() =
        assertNull(OtpDetector.detect("تأكيد: تم تحويل 2500 شيكل إلى حسابك"))

    @Test fun ignoresDatesAndTimes() =
        assertEquals("7781", OtpDetector.detect("Your OTP is 7781 valid till 12:30 on 29/10/2026"))

    @Test fun noKeywordNoCode() = assertNull(OtpDetector.detect("مرحبا كيف حالك 2026"))
}

class ClassifierTest {
    private fun classify(address: String, body: String, contact: Boolean = false, rules: List<CategoryRuleEntity> = emptyList()) =
        Classifier.classify(address, body, OtpDetector.detect(body), contact, true, rules)

    @Test fun otp() = assertEquals(Category.OTP, classify("Google", "G-123456 is your verification code"))
    @Test fun bank() = assertEquals(Category.BANK, classify("MyBank", "تم خصم 45.00 من حسابك لدى متجر"))
    @Test fun offer() = assertEquals(Category.OFFERS, classify("Shop", "خصم 30% على كل المنتجات حتى الجمعة"))
    @Test fun spam() = assertEquals(Category.SPAM, classify("+970599111222", "مبروك لقد فزت بجائزة! اضغط على الرابط bit.ly/x"))
    @Test fun contactIsPersonal() = assertEquals(Category.PERSONAL, classify("+970599111222", "وينك؟", contact = true))

    @Test fun ruleWins() {
        val rules = listOf(CategoryRuleEntity(id = 1, sender = "Shop", category = Category.BANK))
        assertEquals(Category.BANK, classify("Shop", "خصم 30% على كل المنتجات", rules = rules))
    }
}

class PhoneUtilsTest {
    @Test fun sameNumberDifferentFormats() = assertTrue(PhoneUtils.same("+970 59-911-1222", "0599111222"))
    @Test fun differentNumbers() = assertFalse(PhoneUtils.same("0599111222", "0599111223"))
    @Test fun senderIdsCaseInsensitive() = assertTrue(PhoneUtils.same("MyBank", "mybank"))
    @Test fun replyable() {
        assertTrue(PhoneUtils.isReplyable("+970599111222"))
        assertFalse(PhoneUtils.isReplyable("MyBank"))
        assertFalse(PhoneUtils.isReplyable("1234"))
    }

    @Test fun samePersonAcrossPrefixes() {
        val k = PhoneUtils.matchKey("0597857250")
        assertEquals(k, PhoneUtils.matchKey("+972597857250"))
        assertEquals(k, PhoneUtils.matchKey("+970597857250"))
        assertEquals(k, PhoneUtils.matchKey("059-785-7250"))
        assertEquals(k, PhoneUtils.matchKey("+970 59 785 7250"))
        assertFalse(k == PhoneUtils.matchKey("0598570062"))
        assertEquals("mybank", PhoneUtils.matchKey("MyBank"))
    }
}

class NumberTextTest {
    private val book = mapOf(PhoneUtils.matchKey("0592042464") to "أمي الحبيبة")
    private fun names(s: String) = NumberText.replaceWithNames(s) { book[PhoneUtils.matchKey(it)] }

    @Test fun replacesKnownNumber() =
        assertEquals("الرقم أمي الحبيبة حاول الاتصال بك", names("الرقم 0592042464 حاول الاتصال بك"))

    @Test fun replacesInternationalForm() =
        assertEquals("اتصل على أمي الحبيبة.", names("اتصل على +972592042464."))

    @Test fun keepsUnknownNumbersAndAmounts() {
        assertEquals("الرقم 0599999999 والمبلغ 45.00", names("الرقم 0599999999 والمبلغ 45.00"))
        assertEquals("رمزك 482913", names("رمزك 482913"))
    }
}
