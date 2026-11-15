package dev.saged.barq.domain

/** قيمة داخل الرسالة يمكن نسخها بنقرة: اسم مستخدم، كلمة سر، رقم، بريد... */
data class CopySpan(val range: IntRange, val value: String)

object CopyableValues {

    private val labeled = Regex("""(?<=[\p{L}\s])\s?[:：][ \t]*(?!//)([^\s]+)[ \t]*(?=\r?\n|$)""", RegexOption.MULTILINE)

    private val email = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

    private val mixed = Regex("""(?<![\w@])(?=[A-Za-z0-9_\-]*\d)(?=[A-Za-z0-9_\-]*[A-Za-z])[A-Za-z0-9][A-Za-z0-9_\-]{3,}(?![\w@])""")

    private val digits = Regex("""(?<![\d.,/:\-])[+]?\d[\d \-]{2,}\d(?![\d,/:]|\.\d)""")

    private val url = Regex("(?i)\\b((https?://|www\\.)[^\\s]+)")

    private const val MAX_LEN = 64

    fun find(text: String): List<CopySpan> {
        if (text.isBlank()) return emptyList()
        val taken = ArrayList<IntRange>()
        url.findAll(text).forEach { taken += it.range }
        val out = ArrayList<CopySpan>()

        fun add(range: IntRange, raw: String) {
            if (range.isEmpty() || taken.any { it.first <= range.last && range.first <= it.last }) return
            val value = clean(raw)
            if (value.length !in 2..MAX_LEN) return
            taken += range
            out += CopySpan(range, value)
        }

        labeled.findAll(text).forEach { m ->
            val g = m.groups[1] ?: return@forEach
            val token = g.value.trimEnd('.', '،', ',', ';', '؛')
            if (token.isEmpty() || !looksLikeValue(token)) return@forEach
            add(g.range.first until g.range.first + token.length, token)
        }
        email.findAll(text).forEach { add(it.range, it.value) }
        mixed.findAll(text).forEach { add(it.range, it.value) }
        digits.findAll(text).forEach { m ->
            val digitsOnly = PhoneUtils.normalizeDigits(m.value).count { it.isDigit() }
            if (digitsOnly >= 4 && !isDateOrTime(text, m.range)) add(m.range, m.value.trim())
        }
        return out.sortedBy { it.range.first }
    }

    /** الأرقام العربية تُحوَّل للاتينية لتصلح للصق في أي حقل. */
    fun clean(raw: String): String {
        val v = raw.trim()
        return if (v.all { it.isDigit() || it == ' ' || it == '-' || it == '+' }) PhoneUtils.normalizeDigits(v) else v
    }

    /** نص عربي صرف بلا رقم أو رمز ليس قيمة (مثل «الاسم: أحمد»). */
    private fun looksLikeValue(token: String): Boolean {
        if (token.length < 2 || token.length > MAX_LEN) return false
        if (token.startsWith("//")) return false
        return token.any { it in 'A'..'Z' || it in 'a'..'z' || it.isDigit() || it in "@#$%&*_-+=!" } ||
            PhoneUtils.normalizeDigits(token).any { it.isDigit() }
    }

    private fun isDateOrTime(text: String, range: IntRange): Boolean {
        val before = if (range.first > 0) text[range.first - 1] else ' '
        val after = if (range.last + 1 < text.length) text[range.last + 1] else ' '
        return before == '/' || before == ':' || after == '/' || after == ':'
    }
}
