package dev.saged.barq.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** A reply the user once wrote to an incoming text; [incoming] is already reduced to its words. */
data class Memory(val incoming: String, val reply: String, val uses: Int, val lastUsed: Long)

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
 *
 * A past reply is only reused when it cannot go off topic: every word in it must be either part
 * of the user's habitual vocabulary (used in several replies, e.g. "تمام", "الحمد لله") or appear
 * in the incoming text itself. So "صحيح شو موضوع البطالة" is never sent to a blessing.
 */
class ReplyIndex(memories: List<Memory>) {

    private class Row(
        val memory: Memory,
        val tokens: Set<String>,
        val grams: Set<String>,
        val intent: Intent?,
        val replyTokens: Set<String>,
        var norm: Double = 0.0,
    )

    private class Sim(val score: Double, val cosine: Double, val shared: Int, val exact: Boolean)

    private val rows: List<Row> = memories.map { m ->
        val words = m.incoming.split(' ').filter { it.isNotEmpty() }
        Row(m, words.toSet(), ReplyText.grams(m.incoming), ReplyIntents.specific(words), ReplyText.tokens(m.reply).toSet())
    }
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
    }

    private val popular: List<Suggestion> by lazy {
        rows.groupBy { ReplyText.replyKey(it.memory.reply) }.values
            .map { g -> Triple(g.maxByOrNull { it.memory.lastUsed }!!.memory.reply, g.sumOf { it.memory.uses }, g.maxOf { it.memory.lastUsed }) }
            .filter { (reply, uses, _) -> uses >= POPULAR_MIN_USES && reply.length <= POPULAR_MAX_LEN }
            .sortedWith(compareByDescending<Triple<String, Int, Long>> { it.second }.thenByDescending { it.third })
            .take(10)
            .map { Suggestion(it.first, 0.0, popular = true) }
    }

    /** How the user usually addresses people ("غالي", "حبيبي"…), when it is a clear habit. */
    val addressTerm: String? by lazy {
        val counts = HashMap<String, Int>()
        rows.forEach { r -> ADDRESS_TERMS.filter { it.key in r.replyTokens }.forEach { counts[it.value] = (counts[it.value] ?: 0) + 1 } }
        counts.filter { it.value >= ADDRESS_MIN_USES && it.value * 5 >= rows.size }.maxByOrNull { it.value }?.key
    }

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
            for (r in rows) {
                val s = similarity(set, key, grams, norm, r)
                // Same meaning in other words ("كيفك" / "شلونك" / "كيف الحال"): the user's habit still applies.
                val sameIntent = intent != null && r.intent == intent
                val sim = if (sameIntent) maxOf(s.score, INTENT_FLOOR) else s.score
                if (sim < minScore) continue
                if (!s.exact) {
                    if (!r.replyTokens.all { it in generic || it in set }) continue
                    if (strict && !sameIntent && !(s.cosine >= STRICT_COSINE && s.shared >= minOf(2, set.size))) continue
                }
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
        suggest(incoming, 1, now, AUTO_MIN, withPopular = false, strict = true).firstOrNull()

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
        private const val ADDRESS_MIN_USES = 3
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
            listOf("غالي", "حبيبي", "عمري", "أخي", "خوي", "روحي", "قلبي", "باشا", "عزيزي")
                .mapNotNull { raw -> ReplyText.tokens(raw).firstOrNull()?.let { it to raw } }.toMap()
    }
}

enum class Intent {
    URGENT, SALAM, HOWARE, AMEN, MORNING, EVENING, GREETING, CONGRATS, BLESSING, THANKS, WELLWISH, GOODNIGHT,
    WHERE, WHEN, QUESTION,
}

/**
 * Knows what an incoming text is about, in Arabic (several dialects) and English, so the engine
 * understands "شلونك" as "كيف حالك", and can write a fitting answer itself: it answers what was
 * said ("يسعد مساك" → "يسعدك ويسعد مساك"), part by part, instead of reusing an unrelated sentence.
 */
object ReplyIntents {
    /** In order of priority: the first matching intent is "the" intent of a text. */
    private val phrases: List<Pair<Intent, List<String>>> = listOf(
        Intent.URGENT to listOf("مستعجل", "ضروري", "عاجل", "بسرعة", "طارئ", "urgent", "asap", "emergency"),
        Intent.SALAM to listOf("السلام عليكم", "سلام عليكم", "السلام", "سلام", "salam", "assalamu alaikum", "peace be upon you"),
        Intent.HOWARE to listOf(
            "كيفك", "كيف حالك", "كيف الحال", "كيف الاحوال", "شلونك", "شلونكم", "كيفكم", "اخبارك", "شو اخبارك",
            "شو الاخبار", "شخبارك", "علومك", "how are you", "how r u", "whats up", "hows it going",
        ),
        Intent.AMEN to listOf("امين", "آمين", "amen"),
        Intent.MORNING to listOf("صباح الخير", "صباح النور", "صباح الورد", "يسعد صباحك", "صباحك", "صباحو", "good morning"),
        Intent.EVENING to listOf(
            "مساء الخير", "مساء النور", "مساء الورد", "يسعد مساك", "يسعد مساءك", "مساك", "good evening", "good afternoon",
        ),
        Intent.GREETING to listOf("مرحبا", "مرحبتين", "هلا", "اهلا", "اهلين", "هاي", "هلو", "hi", "hello", "hey"),
        Intent.CONGRATS to listOf("مبروك", "الف مبروك", "congrats", "congratulations"),
        Intent.BLESSING to listOf("يعطيك العافية", "الله يعطيك العافية", "يعطيكم العافية"),
        Intent.THANKS to listOf("شكرا", "مشكور", "تسلم", "يسلمو", "thanks", "thank you", "thx"),
        Intent.WELLWISH to listOf("الله يشفيك", "سلامتك", "الف سلامة", "شافاك الله"),
        Intent.GOODNIGHT to listOf("تصبح على خير", "تصبحون على خير", "good night", "goodnight"),
        Intent.WHERE to listOf("وين", "فين", "اين", "where are you", "where r u", "where"),
        Intent.WHEN to listOf("متى", "امتى", "when"),
        Intent.QUESTION to listOf("كيف", "ليش", "شو", "ماذا", "لماذا", "هل", "كم", "مين", "ايش", "what", "why", "how", "who", "can you", "could you"),
    ).map { (intent, list) ->
        intent to list.map { " " + ReplyText.key(it) + " " }.filter { it.isNotBlank() }
    }

    private val social = setOf(
        Intent.SALAM, Intent.HOWARE, Intent.AMEN, Intent.MORNING, Intent.EVENING, Intent.GREETING,
        Intent.CONGRATS, Intent.BLESSING, Intent.THANKS, Intent.WELLWISH, Intent.GOODNIGHT,
    )

    /** The first intent of already-split words, or null. */
    fun detect(words: List<String>): Intent? = hits(words).firstOrNull()

    /** Like [detect] but without the broad "it is a question" intent: used to match habits. */
    fun specific(words: List<String>): Intent? = detect(words)?.takeIf { it != Intent.QUESTION }

    fun of(text: String): Intent? = all(text).firstOrNull()

    private fun hits(words: List<String>): List<Intent> {
        if (words.isEmpty()) return emptyList()
        val joined = " " + words.joinToString(" ") + " "
        return phrases.filter { (_, list) -> list.any { it in joined } }.map { it.first }
    }

    /** Everything a text says: "اللهم آمين يسعد مساك" is a prayer and an evening wish. */
    fun all(text: String): List<Intent> {
        val hit = hits(ReplyText.tokens(text))
        val others = hit.filter { it != Intent.QUESTION }
        return when {
            others.isNotEmpty() -> others
            hit.isNotEmpty() -> hit
            text.contains('?') || text.contains('؟') -> listOf(Intent.QUESTION)
            else -> emptyList()
        }
    }

    /**
     * An answer written for [text], in its language (Arabic or English; other scripts get none):
     * a courteous reply to each greeting, wish or prayer it contains (mirroring the wish), then,
     * if [withBusy], a short "I am busy" line for an urgent text or a question. [address] is the
     * way the user usually addresses people ("غالي"…).
     */
    fun reply(text: String, address: String? = null, withBusy: Boolean = true): String? {
        val acts = all(text)
        if (acts.isEmpty()) return null
        val arabic = text.count { it in '؀'..'ۿ' }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (arabic == 0 && latin == 0) return null
        val ar = arabic >= latin
        val wish = "يسعد" in ReplyText.tokens(text)

        val pieces = acts.filter { it in social }.take(2).map { piece(it, wish, ar) }
        val busy = if (withBusy) acts.firstOrNull { it !in social }?.let { busyLine(it, ar) } else null
        val head = when {
            pieces.isEmpty() -> null
            ar -> pieces.joinToString("، ") + (address?.let { " يا $it" } ?: "")
            else -> pieces.joinToString(". ") + "."
        }
        return listOfNotNull(head, busy).joinToString(if (ar) ". " else " ").ifEmpty { null }
    }

    private fun piece(intent: Intent, wish: Boolean, ar: Boolean): String = if (ar) {
        when (intent) {
            Intent.SALAM -> "وعليكم السلام ورحمة الله"
            Intent.HOWARE -> "الحمد لله بخير، وأنت كيفك"
            Intent.AMEN -> "آمين وإياك يا رب"
            Intent.MORNING -> if (wish) "يسعدك ويسعد صباحك" else "صباح النور"
            Intent.EVENING -> if (wish) "يسعدك ويسعد مساك" else "مساء النور"
            Intent.GREETING -> "أهلاً"
            Intent.CONGRATS -> "الله يبارك فيك"
            Intent.BLESSING -> "الله يعافيك"
            Intent.THANKS -> "العفو، في أي وقت"
            Intent.WELLWISH -> "الله يسلمك"
            else -> "تصبح على خير"
        }
    } else {
        when (intent) {
            Intent.SALAM -> "Wa alaykum assalam"
            Intent.HOWARE -> "I'm good, thanks, how about you"
            Intent.AMEN -> "Amen, and the same to you"
            Intent.MORNING -> "Good morning"
            Intent.EVENING -> "Good evening"
            Intent.GREETING -> "Hi"
            Intent.CONGRATS -> "Thank you so much"
            Intent.BLESSING -> "God bless you too"
            Intent.THANKS -> "You're welcome, anytime"
            Intent.WELLWISH -> "Thank you"
            else -> "Good night"
        }
    }

    private fun busyLine(intent: Intent, ar: Boolean): String = when {
        intent == Intent.URGENT && ar -> "مشغول الآن، إذا الأمر عاجل اتصل بي من فضلك."
        intent == Intent.URGENT -> "I'm busy right now. If it's urgent, please call me."
        ar -> "مشغول حالياً، سأرد على سؤالك بأقرب وقت."
        else -> "I'm busy right now and will answer your question as soon as I can."
    }
}
