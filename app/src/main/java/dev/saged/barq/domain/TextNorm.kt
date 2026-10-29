package dev.saged.barq.domain

/**
 * Arabic-aware text folding used by search (messages and contacts).
 *  - أ إ آ ٱ → ا,  ة → ه,  ى → ي,  ؤ → و,  ئ → ي
 *  - Arabic-Indic and Persian digits → 0-9
 *  - diacritics (tashkeel) and tatweel are dropped
 *  - Arabic punctuation becomes a space so words split correctly
 *  - Latin letters are lowercased
 *
 * The database migration uses the same code to index existing messages, so old and new
 * messages always match the same way.
 */
object TextNorm {

    private val letterMap: List<Pair<Char, Char>> = listOf(
        'أ' to 'ا', 'إ' to 'ا', 'آ' to 'ا', 'ٱ' to 'ا',
        'ة' to 'ه', 'ى' to 'ي', 'ؤ' to 'و', 'ئ' to 'ي',
    ) + ('٠'..'٩').mapIndexed { i, c -> c to ('0' + i) } + ('۰'..'۹').mapIndexed { i, c -> c to ('0' + i) }

    /** Tashkeel (U+064B..U+0652), superscript alef (U+0670) and tatweel (U+0640). */
    private val dropped: Set<Char> = (('\u064B'..'\u0652') + '\u0670' + '\u0640').toSet()

    private val punctuation: Set<Char> = setOf('،', '؛', '؟', '«', '»', '“', '”', '…', '٪', '\n', '\r', '\t')

    private val map: Map<Char, Char> = letterMap.toMap()

    /** One-to-one folding (keeps string length) — used for highlighting matches. */
    fun foldChar(c: Char): Char = map[c] ?: c.lowercaseChar()

    fun fold1to1(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(foldChar(c))
        return sb.toString()
    }

    /** Full normalisation for the search index and for queries. */
    fun forIndex(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c in dropped -> Unit
                c in punctuation -> sb.append(' ')
                else -> sb.append(foldChar(c))
            }
        }
        return sb.toString()
    }

    /** Same normalisation for names (contacts list), trimmed. */
    fun forName(text: String): String = forIndex(text).trim()

    /** Arabic attached prefixes: "بالمدرسة" is also indexed as "مدرسه" so searches find it. */
    private val prefixes = listOf("وال", "بال", "فال", "كال", "ال", "لل")

    private fun stem(word: String): String? {
        for (p in prefixes) {
            if (word.startsWith(p) && word.length - p.length >= 3) return word.substring(p.length)
        }
        return null
    }

    /** Text stored in the message search index: normalised words plus their stems. */
    fun indexText(body: String): String {
        val norm = forIndex(body)
        val stems = norm.split(' ').mapNotNull { stem(it) }
        return if (stems.isEmpty()) norm else norm + " " + stems.joinToString(" ")
    }

    /**
     * Turns what the user typed into an FTS4 MATCH expression: every word must appear, and each
     * word matches as a prefix ("مرح" finds "مرحبا", "المدرسة" finds "بالمدرسة").
     * Returns null when nothing searchable is left.
     */
    fun ftsQuery(input: String): String? {
        val words = forIndex(input)
            .split(' ')
            .map { w -> w.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            .take(8)
        if (words.isEmpty()) return null
        return words.joinToString(" ") { (stem(it) ?: it) + "*" }
    }

    /** Words of the query in 1:1 folded form, for highlighting inside the original text. */
    fun highlightTerms(input: String): List<String> =
        fold1to1(input).split(' ', '،').map { it.trim() }.filter { it.isNotEmpty() }.map { stem(it) ?: it }
}
