package dev.saged.barq.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** A reply the user once wrote to an incoming text; [incoming] is already reduced to its words. */
data class Memory(val incoming: String, val reply: String, val uses: Int, val lastUsed: Long, val threadId: Long = 0)

/** [builtin]: not one of the user's replies but the ready-made answer for a recognised intent. */
data class Suggestion(val reply: String, val score: Double, val popular: Boolean = false, val builtin: Boolean = false)

/** Text rules shared by learning and suggesting. */
object ReplyText {
    const val MAX_REPLY = 160
    private const val MAX_TOKENS = 40
    private val stretched = Regex("""(.)\1{2,}""")
    private val link = Regex("""https?:|www\.|://|@|\.(com|net|org|me|io|ly)\b""", RegexOption.IGNORE_CASE)
    private val spaces = Regex("""\s+""")

    /**
     * Words used for matching: numbers are dropped, so "كم المبلغ 500" matches "كم المبلغ 300", and
     * stretched letters shrink ("هلااااا" = "هلا").
     */
    fun tokens(text: String): List<String> =
        TextNorm.words(text)
            .map { it.replace(stretched, "$1") }
            .filter { w -> w.length >= 2 && w.none { it.isDigit() } }
            .take(MAX_TOKENS)

    fun key(text: String): String = tokens(text).joinToString(" ")

    fun replyKey(reply: String): String = TextNorm.forIndex(reply).trim().replace(spaces, " ")

    fun grams(text: String): Set<String> {
        val padded = " $text "
        return (0..padded.length - 3).mapTo(HashSet()) { padded.substring(it, it + 3) }
    }

    fun isLearnableIncoming(text: String): Boolean = key(text).length >= 2

    /** Endearments the user may use with one person and never with another. */
    val addressWords = listOf("غالي", "حبيبي", "عمري", "أخي", "خوي", "روحي", "قلبي", "باشا", "عزيزي")

    private val addressRegex = Regex(
        "(?<!\\p{L})(?:يا\\s+)?(?:" +
            addressWords.flatMap { listOf(it, it.replace('أ', 'ا')) }.distinct().joinToString("|") { Regex.escape(it) } +
            ")(?!\\p{L})",
    )

    /** [reply] without its endearment ("تمام يا غالي" → "تمام"), or null when nothing else is left. */
    fun withoutAddress(reply: String): String? =
        addressRegex.replace(reply, " ").replace(spaces, " ")
            .trim { it.isWhitespace() || it == '،' || it == ',' || it == '-' }
            .takeIf { r -> r.any { it.isLetter() } }

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
 * Replies the user asked never to see again. A reply blocked for a text is also blocked for texts
 * that say nearly the same, and once it has been blocked for several different texts it is
 * blocked for every text. [blocked]: pairs of (incoming key, reply).
 */
class ReplyBlocklist(blocked: List<Pair<String, String>> = emptyList()) {
    private val byReply: Map<String, List<Set<String>>> = blocked
        .groupBy({ ReplyText.replyKey(it.second) }, { it.first.split(' ').filter(String::isNotEmpty).toSet() })
    private val everywhere: Set<String> = byReply.filterValues { it.toSet().size >= EVERYWHERE_AFTER }.keys

    fun blocks(incomingKey: String, reply: String): Boolean {
        if (byReply.isEmpty()) return false
        val k = ReplyText.replyKey(reply)
        if (k in everywhere) return true
        val said = byReply[k] ?: return false
        val q = incomingKey.split(' ').filter(String::isNotEmpty).toSet()
        return said.any { t ->
            val union = (q + t).size
            union > 0 && (q intersect t).size.toDouble() / union >= SAME_TEXT
        }
    }

    private companion object {
        const val EVERYWHERE_AFTER = 3
        const val SAME_TEXT = 0.6
    }
}

/**
 * Finds, among the user's own past replies, the ones that fit an incoming text.
 * Similarity = IDF-weighted word cosine (70%) + character-trigram overlap (30%); a reply's score
 * also rises with how often and how recently the user used it. Nothing leaves the phone.
 *
 * A past reply is only reused when it cannot go off topic: every word in it must be either part
 * of the user's habitual vocabulary (used in several replies, e.g. "تمام", "الحمد لله") or appear
 * in the incoming text itself. So "صحيح شو موضوع البطالة" is never sent to a blessing.
 */
class ReplyIndex(memories: List<Memory>, private val blocked: ReplyBlocklist = ReplyBlocklist()) {

    private class Row(
        val memory: Memory,
        val tokens: Set<String>,
        val intent: Intent?,
        val replyTokens: Set<String>,
        val addressed: Boolean,
        var norm: Double = 0.0,
    ) {
        val grams: Set<String> by lazy { ReplyText.grams(memory.incoming) }
    }

    private class Sim(val score: Double, val cosine: Double, val shared: Int, val exact: Boolean)

    private val rows: List<Row> = memories.filterNot { blocked.blocks(it.incoming, it.reply) }.map { m ->
        val words = m.incoming.split(' ').filter { it.isNotEmpty() }
        val replyTokens = ReplyText.tokens(m.reply).toSet()
        Row(m, words.toSet(), ReplyIntents.specific(words), replyTokens, ADDRESS_TERMS.keys.any { it in replyTokens })
    }
    private val byToken: Map<String, IntArray>
    private val byIntent: Map<Intent, IntArray>
    private val idf: Map<String, Double>
    private val unseenIdf: Double

    /** The user's habitual words: used in replies to several different texts, plus everyday words. */
    private val generic: Set<String>

    init {
        val df = HashMap<String, Int>()
        rows.forEach { r -> r.tokens.forEach { t -> df[t] = (df[t] ?: 0) + 1 } }
        val n = rows.size
        idf = df.mapValues { (_, d) -> ln(1.0 + n / (1.0 + d)) }
        unseenIdf = ln(1.0 + n)
        rows.forEach { r -> r.norm = sqrt(r.tokens.sumOf { t -> (idf[t] ?: 0.0).let { it * it } }) }
        val used = HashMap<String, Int>()
        rows.forEach { r -> r.replyTokens.forEach { t -> used[t] = (used[t] ?: 0) + 1 } }
        generic = used.filterValues { it >= GENERIC_MIN_ROWS }.keys + COMMON
        val tokenRows = HashMap<String, MutableList<Int>>()
        val intentRows = HashMap<Intent, MutableList<Int>>()
        rows.forEachIndexed { i, r ->
            r.tokens.forEach { tokenRows.getOrPut(it) { ArrayList() } += i }
            r.intent?.let { intentRows.getOrPut(it) { ArrayList() } += i }
        }
        byToken = tokenRows.mapValues { it.value.toIntArray() }
        byIntent = intentRows.mapValues { it.value.toIntArray() }
    }

    private val popular: List<Suggestion> by lazy {
        rows.filterNot { it.addressed }.groupBy { ReplyText.replyKey(it.memory.reply) }.values
            .map { g -> Triple(g.maxByOrNull { it.memory.lastUsed }!!.memory.reply, g.sumOf { it.memory.uses }, g.maxOf { it.memory.lastUsed }) }
            .filter { (reply, uses, _) -> uses >= POPULAR_MIN_USES && reply.length <= POPULAR_MAX_LEN }
            .sortedWith(compareByDescending<Triple<String, Int, Long>> { it.second }.thenByDescending { it.third })
            .take(10)
            .map { Suggestion(it.first, 0.0, popular = true) }
    }

    private val rowsByThread: Map<Long, List<Row>> by lazy { rows.groupBy { it.memory.threadId } }
    private val addressTerms = HashMap<Long, String?>()

    /**
     * How the user addresses this one person ("غالي", "حبيبي"…), only when their own conversation
     * shows a clear habit; null (a plain reply, no endearment) for a stranger or an unknown thread.
     */
    fun addressTerm(threadId: Long): String? {
        if (threadId <= 0L) return null
        return synchronized(addressTerms) {
            addressTerms.getOrPut(threadId) {
                val mine = rowsByThread[threadId].orEmpty()
                val total = mine.sumOf { it.memory.uses }
                val counts = HashMap<String, Int>()
                mine.forEach { r ->
                    ADDRESS_TERMS.filter { it.key in r.replyTokens }.forEach { counts[it.value] = (counts[it.value] ?: 0) + r.memory.uses }
                }
                counts.filter { it.value >= ADDRESS_MIN_USES && it.value * 5 >= total }.maxByOrNull { it.value }?.key
            }
        }
    }

    /** True when [reply] was blocked for texts like [incoming]. */
    fun isBlocked(incoming: String, reply: String): Boolean = blocked.blocks(ReplyText.key(incoming), reply)

    val size: Int get() = rows.size

    /**
     * Up to [limit] replies, best first; [withPopular] tops up with the user's most used short replies.
     * [strict] (auto-reply, nobody checks it first): a text that is only vaguely similar is not enough.
     */
    fun suggest(
        incoming: String,
        limit: Int,
        now: Long,
        minScore: Double = SUGGEST_MIN,
        withPopular: Boolean = true,
        strict: Boolean = false,
        threadId: Long = 0,
    ): List<Suggestion> {
        if (limit <= 0) return emptyList()
        val tokens = ReplyText.tokens(incoming)
        val best = HashMap<String, Suggestion>()
        if (tokens.isNotEmpty() && rows.isNotEmpty()) {
            val set = tokens.toSet()
            val key = tokens.joinToString(" ")
            val grams = ReplyText.grams(key)
            val norm = sqrt(set.sumOf { t -> (idf[t] ?: unseenIdf).let { it * it } })
            val intent = ReplyIntents.specific(tokens)
            val near = java.util.BitSet(rows.size)
            for (t in set) byToken[t]?.forEach { near.set(it) }
            if (intent != null) byIntent[intent]?.forEach { near.set(it) }
            var at = near.nextSetBit(0)
            while (at >= 0) {
                val r = rows[at]
                at = near.nextSetBit(at + 1)
                val s = similarity(set, key, grams, norm, r)
                // Same meaning in other words ("كيفك" / "شلونك" / "كيف الحال"): the user's habit still applies.
                val sameIntent = intent != null && r.intent == intent
                val sim = if (sameIntent) maxOf(s.score, INTENT_FLOOR) else s.score
                if (sim < minScore) continue
                if (!s.exact) {
                    if (!r.replyTokens.all { it in generic || it in set }) continue
                    if (strict && !sameIntent && !(s.cosine >= STRICT_COSINE && s.shared >= minOf(2, set.size))) continue
                }
                // Another person's endearment is never carried over: it is dropped from the reply.
                val reply = if (r.addressed && (threadId <= 0L || r.memory.threadId != threadId)) {
                    ReplyText.withoutAddress(r.memory.reply) ?: continue
                } else r.memory.reply
                if (blocked.blocks(key, reply)) continue
                val own = if (threadId > 0L && r.memory.threadId == threadId) SAME_PERSON else 1.0
                val score = minOf(1.0, sim * (0.8 + 0.2 * frequency(r.memory.uses)) * (0.85 + 0.15 * recency(now - r.memory.lastUsed)) * own)
                if (score < minScore) continue
                val k = ReplyText.replyKey(reply)
                if ((best[k]?.score ?: -1.0) < score) best[k] = Suggestion(reply, score)
            }
        }
        val out = best.values.sortedByDescending { it.score }.take(limit).toMutableList()
        if (withPopular && out.size < limit) {
            val seen = out.map { ReplyText.replyKey(it.reply) }.toMutableSet()
            for (p in popular) {
                if (out.size >= limit) break
                if (!blocked.blocks(ReplyText.key(incoming), p.reply) && seen.add(ReplyText.replyKey(p.reply))) out += p
            }
        }
        return out
    }

    /** The one reply confident enough to be sent without asking, or null. */
    fun best(incoming: String, now: Long, threadId: Long = 0): Suggestion? =
        suggest(incoming, 1, now, AUTO_MIN, withPopular = false, strict = true, threadId = threadId).firstOrNull()

    private fun similarity(q: Set<String>, key: String, grams: Set<String>, qNorm: Double, r: Row): Sim {
        if (r.memory.incoming == key) return Sim(1.0, 1.0, q.size, true)
        var dot = 0.0
        var shared = 0
        for (t in q) if (t in r.tokens) { shared++; (idf[t] ?: 0.0).let { dot += it * it } }
        val cosine = if (dot == 0.0 || qNorm == 0.0 || r.norm == 0.0) 0.0 else dot / (qNorm * r.norm)
        val overlap = grams.count { it in r.grams }
        val dice = 2.0 * overlap / (grams.size + r.grams.size)
        return Sim(0.7 * cosine + 0.3 * dice, cosine, shared, false)
    }

    private fun frequency(uses: Int): Double = minOf(1.0, ln(1.0 + uses) / ln(6.0))

    private fun recency(ageMs: Long): Double = exp(-maxOf(0L, ageMs) / DAY_MS / 365.0)

    companion object {
        /** Sent without asking only at or above this score. */
        const val AUTO_MIN = 0.55
        /** Shown as a chip at or above this score. */
        const val SUGGEST_MIN = 0.30
        /** Two texts with the same recognised intent are at least this similar. */
        private const val INTENT_FLOOR = 0.60
        /** Auto-reply without a shared intent needs this much word overlap (and two shared words). */
        private const val STRICT_COSINE = 0.60
        private const val GENERIC_MIN_ROWS = 3
        private const val POPULAR_MIN_USES = 3
        private const val POPULAR_MAX_LEN = 40
        private const val ADDRESS_MIN_USES = 2
        /** A reply the user already said to this same person is a little more likely to fit again. */
        private const val SAME_PERSON = 1.12
        private const val DAY_MS = 86_400_000.0

        /** Everyday words that never make a reply off topic. */
        private val COMMON: Set<String> = (
            "يا الله ان شاء انا انت انتي هلا اهلا تمام اوكي ok okay نعم لا ايوه طيب شكرا عفو حبيبي غالي اخي خوي صح ماشي تم ابشر " +
                "يسلم يسلمو الحمد لله خير بخير وياك وياكم وانت وانتي كمان كذلك امين رب thanks thank you sure yes no the and too same welcome " +
                "مبارك يبارك فيك فيكم العافيه عافيه يعافيك يسلمك سلام عليكم ورحمه وبركاته وعليكم النور صباح مساء الخير يسعدك يسعد ويسعد " +
                "صباحك مساك مشغول حاليا الان قريبا لاحقا بعدين شوي اكلمك احكيك كلمك بكلمك"
            ).split(' ').flatMap { ReplyText.tokens(it) }.toSet()

        /** Normalised word → the way it is written back. */
        private val ADDRESS_TERMS: Map<String, String> =
            ReplyText.addressWords
                .mapNotNull { raw -> ReplyText.tokens(raw).firstOrNull()?.let { it to raw } }.toMap()
    }
}
