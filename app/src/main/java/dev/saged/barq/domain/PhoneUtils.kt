package dev.saged.barq.domain

object PhoneUtils {

    private const val MATCH_DIGITS = 9

    /** Converts Arabic-Indic and Persian digits to ASCII so regexes and comparisons work. */
    fun normalizeDigits(s: String): String {
        if (s.none { it in '٠'..'٩' || it in '۰'..'۹' }) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /** Digits only (keeps a leading +). Alphanumeric sender IDs are returned lowercased. */
    fun normalize(address: String): String {
        val a = normalizeDigits(address.trim())
        return if (isPhoneNumber(a)) {
            val plus = a.startsWith("+")
            (if (plus) "+" else "") + a.filter { it.isDigit() }
        } else a.lowercase()
    }

    /** True for real phone numbers, false for sender IDs like "MyBank". */
    fun isPhoneNumber(address: String): Boolean {
        val a = normalizeDigits(address)
        val digits = a.count { it.isDigit() }
        return digits >= 3 && a.all { it.isDigit() || it in "+-() ." }
    }

    /** Numbers a human can reply to (excludes short codes and alphanumeric senders). */
    fun isReplyable(address: String): Boolean =
        isPhoneNumber(address) && normalizeDigits(address).count { it.isDigit() } >= 7

    /** Loose equality: last [MATCH_DIGITS] digits for numbers, case-insensitive for sender IDs. */
    fun same(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return true
        if (isPhoneNumber(na) && isPhoneNumber(nb)) {
            val da = na.filter { it.isDigit() }
            val db = nb.filter { it.isDigit() }
            if (da.length >= 7 && db.length >= 7) {
                return da.takeLast(MATCH_DIGITS) == db.takeLast(MATCH_DIGITS)
            }
        }
        return false
    }

    fun splitAddresses(addresses: String): List<String> =
        addresses.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Identity of a number regardless of how it is written:
     * 0597857250, +972597857250, +970597857250 and 059-785-7250 all give "597857250".
     * Sender IDs ("MyBank") give their lowercase name. Used for contact matching and for
     * keeping one conversation per real number.
     */
    fun matchKey(address: String): String {
        val a = normalizeDigits(address.trim())
        if (!isPhoneNumber(a)) return a.lowercase()
        val digits = a.filter { it.isDigit() }
        return if (digits.length >= MATCH_DIGITS) digits.takeLast(MATCH_DIGITS) else digits
    }

    /** Key for a whole conversation (one or more recipients). */
    fun conversationKey(addresses: String): String =
        splitAddresses(addresses).map { matchKey(it) }.distinct().sorted().joinToString(",")
}

/** Shows saved contact names in place of phone numbers written inside a message (display only). */
object NumberText {
    private val number = Regex("(?<![\\d+])\\+?\\d(?:[\\d-]{5,16})\\d(?![\\d])")

    fun replaceWithNames(text: String, nameFor: (String) -> String?): String {
        if (text.isEmpty()) return text
        val normalized = PhoneUtils.normalizeDigits(text)
        if (normalized.count { it.isDigit() } < 7) return text
        var changed = false
        val result = number.replace(normalized) { m ->
            val digits = m.value.count { it.isDigit() }
            val name = if (digits >= 9) nameFor(m.value) else null
            if (name != null) {
                changed = true
                name
            } else m.value
        }
        return if (changed) result else text
    }
}
