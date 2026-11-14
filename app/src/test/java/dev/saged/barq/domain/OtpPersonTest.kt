package dev.saged.barq.domain

import dev.saged.barq.data.db.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpPersonTest {
    private fun classify(address: String, body: String) =
        Classifier.classify(address, body, OtpDetector.detect(body), false, true, emptyList())

    @Test fun spacedPhoneNumberIsNotACode() {
        assertNull(OtpDetector.detect("تأكيد الحجز، اتصل بي على 0599 123 456"))
        assertNull(OtpDetector.detect("للتأكيد كلمني على 059 912 3456"))
        assertNull(OtpDetector.detect("confirm with Ali +972 59 123 4567"))
        assertNull(OtpDetector.detect("تأكيد: رقمي 0599-123-456"))
    }

    @Test fun realCodeNextToAPhoneNumberStillWorks() {
        assertEquals("4821", OtpDetector.detect("Your code is 4821, support: 059 912 3456"))
        assertEquals("482913", OtpDetector.detect("رمز التحقق 482913 للاستفسار 0599 123 456"))
    }

    @Test fun chatBetweenPeopleIsNotAnOtp() {
        assertEquals(Category.PERSONAL, classify("+970599111222", "كلمة السر الجديدة للواي فاي 44551"))
        assertEquals(Category.PERSONAL, classify("+970599111222", "تأكيد موعدنا الساعة 4500 تقريباً"))
    }

    @Test fun clearCodeFromANumberIsAnOtp() {
        assertEquals(Category.OTP, classify("+970599111222", "رمز التحقق الخاص بك 482913"))
        assertEquals(Category.OTP, classify("+970599111222", "Your verification code is 482913"))
    }

    @Test fun namedSendersKeepTheWideList() {
        assertEquals(Category.OTP, classify("Jawwal", "كلمة المرور المؤقتة 482913"))
    }
}
