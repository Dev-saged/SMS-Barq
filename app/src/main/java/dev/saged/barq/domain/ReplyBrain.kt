package dev.saged.barq.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** A reply the user once wrote to an incoming text; [incoming] is already reduced to its words. */
data class Memory(val incoming: String, val reply: String, val uses: Int, val lastUsed: Long)

data class Suggestion(val reply: String, val score: Double, val popular: Boolean = false)

/** Text rules shared by learning and suggesting. */
object ReplyText {
    const val MAX_REPLY = 160
    private const val MAX_TOKENS = 40
    private val link = Regex("""https?:|www\.|://|@|\.(com|net|org|me|io|ly)\b""", RegexOption.IGNORE_CASE)
    private val spaces = Regex("""\s+""")

    /** Words used for matching: numbers are dropped, so "كم المبلغ 500" matches "كم المبلغ 300". */
    fun tokens(text: String): List<String> =
        TextNorm.words(text).filter { w -> w.length >= 2 && w.none { it.isDigit() } }.take(MAX_TOKENS)

    fun key(text: String): String = tokens(text).joinToString(" ")

    fun replyKey(reply: String): String = TextNorm.forIndex(reply).trim().replace(spaces, " ")

    fun grams(text: String): Set<String> {
        val padded = " $text "
        return (0..padded.length - 3).mapTo(HashSet()) { padded.substring(it, it + 3) }
    }

    fun isLearnableIncoming(text: String): Boolean = key(text).length >= 2

    /**
     * A reply may be reused for someone else only if it is generic: short, no digits (times,
     * amounts, phone numbers, codes), no link or address, and no word from the person's name.
     */
    fun isSafeReply(reply: String, names: Set<String> = emptySet()): Boolean {
        val r = reply.trim()
        if (r.length < 2 || r.length > MAX_REPLY) return false
        if (r.none { it.isLetter() } || r.any { it.isDigit() }) return false
        if (link.containsMatchIn(r)) return false
        return names.isEmpty() || TextNorm.words(r).none { it in names }
    }

    /** Words of a contact's name that must never appear in a learned reply. */
    fun nameWords(displayName: String): Set<String> =
        TextNorm.words(displayName).filter { it.length >= 3 && it.none { c -> c.isDigit() } }.toSet()
}

/** One message of a conversation, in time order, as the learner sees it. */
data class Turn(
    val incoming: Boolean,
    val body: String,
    val date: Long,
    val auto: Boolean = false,
    /** Incoming only: a personal, replyable sender (not a service, bank or short code). */
    val eligible: Boolean = true,
)

/** Pairs each incoming text with the first reply the user wrote to it. */
object ReplyPairing {
    const val WINDOW_MS = 12 * 60 * 60 * 1000L

    data class Pair(val incoming: String, val reply: String, val at: Long)

    /** Feed ONE conversation's turns in time order; [names] are words that must not be learned. */
    class Walker(private val names: Set<String> = emptySet()) {
        private var pending: Turn? = null

        fun feed(t: Turn): Pair? {
            if (t.auto) return null
            if (t.incoming) {
                pending = t.takeIf { it.eligible && ReplyText.isLearnableIncoming(it.body) }
                return null
            }
            val p = pending ?: return null
            pending = null
            if (t.date - p.date !in 0..WINDOW_MS) return null
            if (!ReplyText.isSafeReply(t.body, names)) return null
            return Pair(p.body, t.body.trim(), t.date)
        }
    }
}

/**
 * Finds, among the user's own past replies, the ones that fit an incoming text.
 * Similarity = IDF-weighted word cosine (70%) + character-trigram overlap (30%); a reply's score
 * also rises with how often and how recently the user used it. Nothing leaves the phone.
 */
class ReplyIndex(memories: List<Memory>) {

    private class Row(val memory: Memory, val tokens: Set<String>, val grams: Set<String>, var norm: Double = 0.0)

    private val rows: List<Row> = memories.map { m ->
        Row(m, m.incoming.split(' ').filter { it.isNotEmpty() }.toSet(), ReplyText.grams(m.incoming))
    }
    private val idf: Map<String, Double>
    private val unseenIdf: Double

    init {
        val df = HashMap<String, Int>()
        rows.forEach { r -> r.tokens.forEach { t -> df[t] = (df[t] ?: 0) + 1 } }
        val n = rows.size
        idf = df.mapValues { (_, d) -> ln(1.0 + n / (1.0 + d)) }
        unseenIdf = ln(1.0 + n)
        rows.forEach { r -> r.norm = sqrt(r.tokens.sumOf { t -> (idf[t] ?: 0.0).let { it * it } }) }
    }

    private val popular: List<Suggestion> by lazy {
        rows.groupBy { ReplyText.replyKey(it.memory.reply) }.values
            .map { g -> Triple(g.maxByOrNull { it.memory.lastUsed }!!.memory.reply, g.sumOf { it.memory.uses }, g.maxOf { it.memory.lastUsed }) }
            .filter { (reply, uses, _) -> uses >= POPULAR_MIN_USES && reply.length <= POPULAR_MAX_LEN }
            .sortedWith(compareByDescending<Triple<String, Int, Long>> { it.second }.thenByDescending { it.third })
            .take(10)
            .map { Suggestion(it.first, 0.0, popular = true) }
    }

    val size: Int get() = rows.size

    /** Up to [limit] replies, best first; [withPopular] tops up with the user's most used short replies. */
    fun suggest(
        incoming: String,
        limit: Int,
        now: Long,
        minScore: Double = SUGGEST_MIN,
        withPopular: Boolean = true,
    ): List<Suggestion> {
        if (limit <= 0) return emptyList()
        val tokens = ReplyText.tokens(incoming)
        val best = HashMap<String, Suggestion>()
        if (tokens.isNotEmpty() && rows.isNotEmpty()) {
            val set = tokens.toSet()
            val key = tokens.joinToString(" ")
            val grams = ReplyText.grams(key)
            val norm = sqrt(set.sumOf { t -> (idf[t] ?: unseenIdf).let { it * it } })
            for (r in rows) {
                val sim = similarity(set, key, grams, norm, r)
                if (sim < minScore) continue
                val score = sim * (0.8 + 0.2 * frequency(r.memory.uses)) * (0.85 + 0.15 * recency(now - r.memory.lastUsed))
                if (score < minScore) continue
                val k = ReplyText.replyKey(r.memory.reply)
                if ((best[k]?.score ?: -1.0) < score) best[k] = Suggestion(r.memory.reply, score)
            }
        }
        val out = best.values.sortedByDescending { it.score }.take(limit).toMutableList()
        if (withPopular && out.size < limit) {
            val seen = out.map { ReplyText.replyKey(it.reply) }.toMutableSet()
            for (p in popular) {
                if (out.size >= limit) break
                if (seen.add(ReplyText.replyKey(p.reply))) out += p
            }
        }
        return out
    }

    /** The one reply confident enough to be sent without asking, or null. */
    fun best(incoming: String, now: Long): Suggestion? =
        suggest(incoming, 1, now, AUTO_MIN, withPopular = false).firstOrNull()

    private fun similarity(q: Set<String>, key: String, grams: Set<String>, qNorm: Double, r: Row): Double {
        if (r.memory.incoming == key) return 1.0
        var dot = 0.0
        for (t in q) if (t in r.tokens) (idf[t] ?: 0.0).let { dot += it * it }
        val cosine = if (dot == 0.0 || qNorm == 0.0 || r.norm == 0.0) 0.0 else dot / (qNorm * r.norm)
        val overlap = grams.count { it in r.grams }
        val dice = 2.0 * overlap / (grams.size + r.grams.size)
        return 0.7 * cosine + 0.3 * dice
    }

    private fun frequency(uses: Int): Double = minOf(1.0, ln(1.0 + uses) / ln(6.0))

    private fun recency(ageMs: Long): Double = exp(-maxOf(0L, ageMs) / DAY_MS / 365.0)

    companion object {
        /** Sent without asking only at or above this score. */
        const val AUTO_MIN = 0.55
        /** Shown as a chip at or above this score. */
        const val SUGGEST_MIN = 0.30
        private const val POPULAR_MIN_USES = 3
        private const val POPULAR_MAX_LEN = 40
        private const val DAY_MS = 86_400_000.0
    }
}
