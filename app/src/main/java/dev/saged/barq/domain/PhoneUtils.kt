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
}
