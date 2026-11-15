package dev.saged.barq.domain

import dev.saged.barq.data.db.Box
import java.util.Calendar

enum class SearchPeriod { ANY, TODAY, WEEK, MONTH }

enum class SearchDirection { ANY, RECEIVED, SENT }

enum class SearchHas { LINK, AMOUNT, CODE }

/** فلاتر فوق البحث النصي؛ تعمل أيضاً بلا نص (آخر الرسائل المطابقة). */
data class SearchFilters(
    val period: SearchPeriod = SearchPeriod.ANY,
    val direction: SearchDirection = SearchDirection.ANY,
    val has: Set<SearchHas> = emptySet(),
) {
    val active: Boolean get() = period != SearchPeriod.ANY || direction != SearchDirection.ANY || has.isNotEmpty()

    fun matches(body: String, date: Long, box: Int, now: Long = System.currentTimeMillis()): Boolean {
        when (direction) {
            SearchDirection.RECEIVED -> if (box != Box.INBOX) return false
            SearchDirection.SENT -> if (box == Box.INBOX) return false
            SearchDirection.ANY -> Unit
        }
        if (date < since(now)) return false
        return has.all { has(it, body) }
    }

    private fun since(now: Long): Long = when (period) {
        SearchPeriod.ANY -> Long.MIN_VALUE
        SearchPeriod.TODAY -> Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        SearchPeriod.WEEK -> now - 7 * DAY
        SearchPeriod.MONTH -> now - 30 * DAY
    }

    private fun has(kind: SearchHas, body: String): Boolean = when (kind) {
        SearchHas.LINK -> link.containsMatchIn(body)
        SearchHas.AMOUNT -> amount.containsMatchIn(PhoneUtils.normalizeDigits(body))
        SearchHas.CODE -> OtpDetector.detect(body) != null
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        val link = Regex("(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|net|org|ps|info|io|me|ly)\\b)")
        val amount = Regex(
            "(?i)(\\d[\\d,.]*\\s?(ريال|ر\\.س|دينار|د\\.ا|شيكل|شيقل|جنيه|درهم|دولار|\\$|€|₪|usd|sar|ils|nis|jod|egp|aed)" +
                "|(ريال|دينار|شيكل|جنيه|درهم|دولار|\\$|€|₪|usd|sar|ils|nis|jod|egp|aed)\\s?\\d)"
        )
    }
}
