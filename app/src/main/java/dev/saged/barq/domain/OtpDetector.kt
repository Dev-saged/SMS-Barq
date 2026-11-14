package dev.saged.barq.domain

import kotlin.math.abs

/**
 * Finds one-time codes in a message. Works with Arabic and English texts and Arabic-Indic digits.
 * Returns only the digits, ready to paste.
 */
object OtpDetector {

    private val keywords = Regex(
        "(?i)(otp|code|passcode|password|pin\\b|verification|verify|one[- ]?time|2fa|auth|token|confirm|" +
            "رمز|الرمز|كود|الكود|التحقق|تحقق|التفعيل|تفعيل|كلمة المرور|كلمة السر|السري|الدخول|التأكيد|تأكيد)"
    )

    /**
     * 4–8 digits, or 3+3 split by space/dash. Must not be part of a longer number, a decimal,
     * a date or a time — but a sentence-ending dot after the code is fine.
     */
    private val candidate = Regex("(?<!\\d)(?<!\\d[.,/:])(\\d{3}[ -]\\d{3}|\\d{4,8})(?!\\d)(?![.,/:]\\d)")

    private val currency = Regex(
        "(?i)(ريال|ر\\.س|دينار|د\\.ا|شيكل|شيقل|شاقل|جنيه|درهم|ليرة|دولار|\\$|€|₪|usd|sar|ils|nis|jod|egp|aed|kwd|qar|try|eur)"
    )

    /** Phone numbers written with spaces or dashes ("059 912 3456", "+972 59 123 4567") are not codes. */
    private val phone = Regex(
        "(?<!\\d)(?:(?:\\+|00)\\d{1,3}[ -]?\\(?\\d{1,3}\\)?(?:[ -]?\\d{2,4}){2,4}|0\\d{2,3}[ -]?\\d{3}[ -]?\\d{3,4})(?!\\d)"
    )

    fun detect(body: String): String? {
        val text = phone.replace(PhoneUtils.normalizeDigits(body)) { "#".repeat(it.value.length) }
        val kw = keywords.find(text) ?: return null
        val matches = candidate.findAll(text)
            .filter { !isAmount(text, it.range) }
            .toList()
        if (matches.isEmpty()) return null
        val best = matches.minByOrNull { abs(it.range.first - kw.range.first) } ?: return null
        return best.value.filter { it.isDigit() }
    }

    private fun isAmount(text: String, range: IntRange): Boolean {
        val before = text.substring((range.first - 6).coerceAtLeast(0), range.first)
        val after = text.substring(range.last + 1, (range.last + 8).coerceAtMost(text.length))
        return currency.containsMatchIn(before) || currency.containsMatchIn(after)
    }
}
