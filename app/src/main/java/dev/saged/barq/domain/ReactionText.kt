package dev.saged.barq.domain

/**
 * Reactions over SMS. SMS has no real reactions, so apps send a short text instead:
 *  - Google Messages:  `👍 “Original text”`  or  `Reacted 😂 to “Original text”`
 *  - iPhone:           `Liked “Original text”`, `Loved …`, `Laughed at …`, `Emphasized …`,
 *                      `Disliked …`, `Questioned …`, `Removed a like from “…”`
 *  - Barq:             `❤️ على «Original text»`  (Arabic)  /  `Reacted ❤️ to “Original text”`
 *
 * [parse] recognises these so the reaction is shown on the original message instead of as a new
 * bubble. It only ever returns something for a short frame around a quoted text; the caller still
 * has to find that quoted text in the conversation before treating it as a reaction.
 */
object ReactionText {

    /** The reactions offered in the message menu (the first one is the default). */
    val choices = listOf("❤️", "👍", "😂", "😮", "😢", "🙏", "👎")

    data class Parsed(
        /** The emoji, or null when the reaction was removed. */
        val emoji: String?,
        /** The quoted text the reaction points at (as quoted, may be shortened with "…"). */
        val quoted: String,
    )

    private val openQuotes = charArrayOf('“', '"', '«', '„', '‟', '”')
    private val closeQuotes = charArrayOf('”', '"', '»', '“', '‟')

    private val iosVerbs = mapOf(
        "liked" to "👍",
        "loved" to "❤️",
        "laughed at" to "😂",
        "emphasized" to "‼️",
        "emphasised" to "‼️",
        "disliked" to "👎",
        "questioned" to "❓",
    )

    /** Words allowed around the emoji in a reaction frame (English and Arabic). */
    private val frameWords = setOf(
        "reacted", "to", "with", "on", "removed", "from", "a", "an", "the", "reaction",
        "على", "تفاعل", "تفاعلت", "ب", "مع", "إلى", "الى", "أزال", "ازال", "حذف", "من",
    )

    fun parse(body: String): Parsed? {
        val text = body.trim()
        if (text.length < 4 || text.length > 700) return null
        val open = text.indexOfAny(openQuotes)
        val close = text.lastIndexOfAny(closeQuotes)
        if (open < 0 || close <= open + 1) return null
        val quoted = text.substring(open + 1, close).trim()
        if (quoted.isEmpty()) return null
        val frame = (text.substring(0, open) + " " + text.substring(close + 1)).trim()
        if (frame.length > 40) return null
        val lower = frame.lowercase()
        val removed = lower.startsWith("removed") || frame.startsWith("أزال") || frame.startsWith("ازال") ||
            frame.startsWith("حذف")

        val emoji = emojiIn(frame)
        if (emoji != null) {
            // Everything else around the emoji must be one of the frame words.
            val rest = frame.replace(emoji, " ").split(' ', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (rest.all { it in frameWords }) return Parsed(if (removed) null else emoji, quoted)
            return null
        }
        // "Removed a like from “…”" (iPhone).
        if (removed) return Parsed(null, quoted)
        val verb = iosVerbs.entries.firstOrNull { lower == it.key }
        return verb?.let { Parsed(it.value, quoted) }
    }

    /** The first emoji in [s] (with its variation selector / joiners), or null. */
    fun emojiIn(s: String): String? {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = Character.charCount(cp)
            if (isEmojiStart(cp)) {
                var end = i + len
                while (end < s.length) {
                    val next = s.codePointAt(end)
                    val nl = Character.charCount(next)
                    when {
                        next == 0xFE0F || next == 0x20E3 || next in 0x1F3FB..0x1F3FF -> end += nl
                        next == 0x200D && end + nl < s.length -> {
                            end += nl
                            end += Character.charCount(s.codePointAt(end))
                        }
                        else -> break
                    }
                }
                return s.substring(i, end)
            }
            i += len
        }
        return null
    }

    private fun isEmojiStart(cp: Int): Boolean =
        cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp == 0x203C || cp == 0x2049 || cp == 0x2753 ||
            cp == 0x2757 || cp == 0x2764 || cp in 0x1F000..0x1F2FF

    /** Does the quoted text (maybe shortened) point at [body]? */
    fun matches(quoted: String, body: String): Boolean {
        val q = squash(quoted)
        val b = squash(body)
        if (q.isEmpty() || b.isEmpty()) return false
        if (q == b) return true
        val shortened = q.endsWith("…") || q.endsWith("...")
        val core = q.removeSuffix("…").removeSuffix("...").trimEnd()
        if (shortened && core.length >= 8 && b.startsWith(core)) return true
        // Some apps cut long texts without marking it.
        return core.length >= 24 && b.startsWith(core)
    }

    private fun squash(s: String): String = s.trim().replace(Regex("\\s+"), " ")

    /** The text Barq sends for a reaction, short enough to stay a single SMS part when possible. */
    fun outgoing(emoji: String, original: String, arabic: Boolean): String {
        val clean = squash(original)
        val quoted = if (clean.length > 40) clean.take(40).trimEnd() + "…" else clean
        return if (arabic) "$emoji على «$quoted»" else "Reacted $emoji to “$quoted”"
    }
}
