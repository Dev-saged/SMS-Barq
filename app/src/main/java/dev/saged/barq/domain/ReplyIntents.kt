package dev.saged.barq.domain

/** How composed replies sound: stately, warm, or just a few words. */
enum class ReplyTone { FORMAL, FRIENDLY, BRIEF }

fun replyToneOf(name: String?, default: ReplyTone = ReplyTone.FORMAL): ReplyTone =
    ReplyTone.entries.firstOrNull { it.name == name } ?: default

/** What a text says, in order of priority (the first one found is "the" intent of the text). */
enum class Intent {
    DEATHNEWS, CONDOLENCE, SICK, BLAME,
    URGENT, SALAM, HOWARE, AMEN, PRAYER, MORNING, EVENING, GREETING, BIRTHDAY, EID, RAMADAN, FRIDAY,
    CONGRATS, GOODNEWS, BLESSING, THANKS, APOLOGY, MISSYOU, PRAISE, WELLWISH, GOODNIGHT, FAREWELL,
    CALLME, INVITE, REQUEST, WHERE, WHEN, QUESTION,
}

/**
 * Understands what an incoming text says, in Arabic (Levantine, Gulf, Egyptian, Iraqi wording)
 * and English, and writes the answer itself: one fitting sentence for each thing the text says
 * (a greeting, a prayer, thanks, good news…), in the chosen [ReplyTone]. It never decides for the
 * user: a request, an invitation or a question only gets "I received it and will get back to you".
 * Rule-based: no model, no network.
 */
object ReplyIntents {
    private val phrases: List<Pair<Intent, List<String>>> = listOf(
        Intent.DEATHNEWS to listOf(
            "توفي", "توفت", "توفيت", "انتقل الى رحمة الله", "انتقلت الى رحمة الله", "في ذمة الله", "العزاء",
            "passed away", "funeral",
        ),
        Intent.CONDOLENCE to listOf(
            "عظم الله اجرك", "عظم الله اجركم", "البقية في حياتك", "البقاء لله", "احسن الله عزاك", "احسن الله عزاءكم",
            "sorry for your loss", "my condolences", "condolences",
        ),
        Intent.SICK to listOf("مريض", "مريضة", "عيان", "عيانة", "مستشفى", "sick", "hospital", "surgery"),
        Intent.BLAME to listOf(
            "زعلان منك", "زعلانة منك", "ليش ما ترد", "ليه ما ترد", "ليش ما بترد", "ما بترد", "مش بترد", "وينك مختفي",
            "نسيتنا", "نسيتني", "مقصر معنا", "طنشتني", "مطنش", "you never reply", "why dont you answer",
        ),
        Intent.URGENT to listOf("مستعجل", "ضروري", "عاجل", "بسرعة", "طارئ", "urgent", "asap", "emergency"),
        Intent.SALAM to listOf("السلام عليكم", "سلام عليكم", "السلام", "سلام", "salam", "assalamu alaikum", "peace be upon you"),
        Intent.HOWARE to listOf(
            "كيفك", "كيف حالك", "كيف الحال", "كيف الاحوال", "شلونك", "شلونكم", "كيفكم", "اخبارك", "شو اخبارك",
            "شو الاخبار", "شخبارك", "علومك", "ازيك", "ازيكم", "عامل ايه", "عاملة ايه", "اخبارك ايه", "وش اخبارك",
            "كيف صحتك", "كيف الصحة", "كيف امورك", "عساك بخير", "عساك طيب", "ايش لونك", "واش راك",
            "how are you", "whats up", "hows it going", "how are things",
        ),
        Intent.AMEN to listOf("امين", "amen"),
        Intent.PRAYER to listOf(
            "الله يوفقك", "الله يحفظك", "الله يسعدك", "ربنا يخليك", "بارك الله فيك", "جزاك الله خير", "جزاك الله خيرا",
            "الله يرضى عليك", "الله يكرمك", "الله يجزيك الخير", "ربي يحفظك", "الله يطول عمرك", "حفظك الله", "وفقك الله",
            "الله يبارك فيك", "god bless you", "bless you",
        ),
        Intent.MORNING to listOf("صباح الخير", "صباح النور", "صباح الورد", "يسعد صباحك", "صباحك", "صباحو", "good morning"),
        Intent.EVENING to listOf(
            "مساء الخير", "مساء النور", "مساء الورد", "يسعد مساك", "يسعد مساءك", "مساك", "good evening", "good afternoon",
        ),
        Intent.GREETING to listOf("مرحبا", "مرحبتين", "هلا", "اهلا", "اهلين", "هاي", "هلو", "hi", "hello", "hey"),
        Intent.BIRTHDAY to listOf("عيد ميلاد سعيد", "ميلاد سعيد", "happy birthday"),
        Intent.EID to listOf(
            "عيد مبارك", "عيدكم مبارك", "عيد سعيد", "كل عام وانت بخير", "كل عام وانتم بخير", "كل سنة وانت طيب",
            "كل سنة وانت سالم", "عساكم من عواده", "ينعاد عليك", "ينعاد عليكم", "eid mubarak", "happy eid", "happy new year",
        ),
        Intent.RAMADAN to listOf(
            "رمضان كريم", "رمضان مبارك", "مبارك عليكم الشهر", "مبارك عليك الشهر", "ramadan kareem", "ramadan mubarak",
        ),
        Intent.FRIDAY to listOf("جمعة مباركة", "جمعة طيبة", "جمعتكم مباركة", "jumma mubarak", "jummah mubarak"),
        Intent.CONGRATS to listOf("مبروك", "الف مبروك", "congrats", "congratulations"),
        Intent.GOODNEWS to listOf(
            "تخرجت", "نجحت", "انخطبت", "خطبت", "تزوجت", "رزقت", "رزقنا", "انقبلت", "توظفت", "اتوظفت",
            "got the job", "graduated", "engaged",
        ),
        Intent.BLESSING to listOf("يعطيك العافية", "الله يعطيك العافية", "يعطيكم العافية", "يعطيك الف عافية", "الله يقويك"),
        Intent.THANKS to listOf("شكرا", "مشكور", "تسلم", "يسلمو", "thanks", "thank you", "thx"),
        Intent.APOLOGY to listOf(
            "اسف", "اسفة", "اسفين", "سامحني", "سامحيني", "اعتذر", "بعتذر", "معلش", "معليش", "sorry", "apologies", "my bad",
        ),
        Intent.MISSYOU to listOf(
            "اشتقتلك", "اشتقت لك", "مشتاق", "مشتاقلك", "مشتاقين", "وحشتني", "وحشتيني", "واحشني", "miss you", "missed you",
        ),
        Intent.PRAISE to listOf("ما شاء الله", "ماشاء الله", "ابدعت", "مبدع", "يا سلام", "الله عليك", "كفو", "well done", "great job", "awesome"),
        Intent.WELLWISH to listOf(
            "الله يشفيك", "سلامتك", "الف سلامة", "شافاك الله", "الحمد لله على السلامة", "الحمدلله على السلامة",
            "حمدلله عالسلامة", "الحمدلله عالسلامة", "حمد الله على السلامة", "get well soon",
        ),
        Intent.GOODNIGHT to listOf(
            "تصبح على خير", "تصبحون على خير", "تصبحي على خير", "ليلة سعيدة", "نوم العوافي", "good night", "goodnight", "sweet dreams",
        ),
        Intent.FAREWELL to listOf(
            "مع السلامة", "في امان الله", "بامان الله", "الله معك", "استودعك الله", "باي", "bye", "goodbye", "see you", "take care",
        ),
        Intent.CALLME to listOf(
            "اتصل بي", "اتصل علي", "اتصل فيني", "اتصلي", "كلمني", "رن علي", "رنلي", "دق علي", "دقلي", "call me", "give me a call",
        ),
        Intent.INVITE to listOf(
            "عازمك", "عازمكم", "معزوم", "معزومين", "عزومة", "تفضل عنا", "تعال عنا", "ندعوكم", "ادعوك", "يشرفنا حضورك",
            "يشرفنا حضوركم", "come over", "join us", "invited",
        ),
        Intent.REQUEST to listOf(
            "ممكن", "لو سمحت", "لو سمحتي", "من فضلك", "بدي منك", "محتاج", "محتاجك", "ابعتلي", "ابعث لي", "ارسل لي", "ارسللي",
            "ياريت", "فلوس", "مصاري", "سلفني", "حوللي", "حول لي", "please", "can you", "could you", "would you", "need you",
        ),
        Intent.WHERE to listOf("وين", "وينك", "فين", "فينك", "اين", "where are you", "where"),
        Intent.WHEN to listOf("متى", "امتى", "ايمتى", "وقتيش", "when"),
        Intent.QUESTION to listOf("كيف", "ليش", "ليه", "شو", "ماذا", "لماذا", "هل", "كم", "مين", "ايش", "وش", "what", "why", "how", "who"),
    ).map { (intent, list) ->
        intent to list.map { " " + ReplyText.key(it) + " " }.filter { it.isNotBlank() }
    }

    /** Heavy news or a reproach: answered alone, soberly, never with a cheerful line next to it. */
    private val grave = setOf(Intent.DEATHNEWS, Intent.CONDOLENCE, Intent.SICK, Intent.BLAME)

    /** Things only the user can settle: the reply just says the text arrived. */
    private val pending = setOf(
        Intent.URGENT, Intent.CALLME, Intent.INVITE, Intent.REQUEST, Intent.WHERE, Intent.WHEN, Intent.QUESTION,
    )

    private val openers = setOf(Intent.SALAM, Intent.GREETING, Intent.MORNING, Intent.EVENING)

    /** Too broad or too delicate for "same intent, so the same habitual reply applies". */
    private val noHabit = setOf(
        Intent.QUESTION, Intent.REQUEST, Intent.INVITE, Intent.GOODNEWS,
        Intent.DEATHNEWS, Intent.CONDOLENCE, Intent.SICK, Intent.BLAME,
    )

    /** "مش مستعجل" is not urgent: these intents are cancelled by a negation right before them. */
    private val negatable = setOf(Intent.URGENT, Intent.SICK)
    private val negators = words("مش مو ما لا مب ليس غير موب not no isnt")

    /** A text made only of these needs no answer at all ("تمام", "ok", "ماشي"). */
    private val acks = words(
        "تمام اوكي اوك ماشي طيب تم حاضر ان شاء الله زين خلاص اه ايوه ايوا نعم لا صح مزبوط اكيد يب اها الحمد لله بخير منيح كويس " +
            "ok okay okey yes no yep yeah sure fine good great nice cool lol haha alright",
    )

    private enum class Dialect { NEUTRAL, LEVANT, EGYPT, GULF }

    private val dialects = listOf(
        Dialect.LEVANT to words("كيفك كيفكم شو هلق بدي بدك منيح هيك ليش"),
        Dialect.EGYPT to words("ازيك ازيكم ايه عامل عاملة دلوقتي كده عايز ازاي فين امتى كويس"),
        Dialect.GULF to words("شلونك شلونكم وش ابي ابغى الحين زين وايد شخبارك علومك عساك"),
    )

    private fun words(list: String): Set<String> = list.split(' ').flatMap { ReplyText.tokens(it) }.toSet()

    /** The first intent of already-split words, or null. */
    fun detect(words: List<String>): Intent? = hits(words).firstOrNull()

    /** Like [detect], limited to intents narrow enough to carry a habit from one wording to another. */
    fun specific(words: List<String>): Intent? = detect(words)?.takeIf { it !in noHabit }

    fun of(text: String): Intent? = all(text).firstOrNull()

    private fun hits(words: List<String>): List<Intent> {
        if (words.isEmpty()) return emptyList()
        val joined = " " + words.joinToString(" ") + " "
        return phrases.filter { (intent, list) -> list.any { present(joined, it, intent in negatable) } }.map { it.first }
    }

    private fun present(joined: String, phrase: String, negatable: Boolean): Boolean {
        var i = joined.indexOf(phrase)
        while (i >= 0) {
            if (!negatable) return true
            val before = joined.substring(0, i).trim().substringAfterLast(' ')
            if (before !in negators) return true
            i = joined.indexOf(phrase, i + 1)
        }
        return false
    }

    /** Everything a text says: "اللهم آمين يسعد مساك" is a prayer and an evening wish. */
    fun all(text: String): List<Intent> {
        var hit = hits(ReplyText.tokens(text))
        // "للأسف" (unfortunately) is not an apology.
        if (Intent.APOLOGY in hit && "للاسف" in TextNorm.forIndex(text)) hit = hit - Intent.APOLOGY
        val others = hit.filter { it != Intent.QUESTION }
        return when {
            others.isNotEmpty() -> others
            hit.isNotEmpty() -> hit
            text.contains('?') || text.contains('؟') -> listOf(Intent.QUESTION)
            else -> emptyList()
        }
    }

    /** Nothing to answer: an acknowledgement ("تمام", "ok"), laughter or only emoji and digits. */
    fun needsNoReply(text: String): Boolean {
        val tokens = ReplyText.tokens(text)
        return tokens.isEmpty() || (tokens.size <= 4 && tokens.all { it in acks })
    }

    /** The text opens a conversation (a greeting): used to avoid greeting the same person twice. */
    fun greets(text: String): Boolean = all(text).any { it in openers }

    /** The text only opens a conversation and says nothing else. */
    fun onlyGreets(text: String): Boolean = all(text).let { acts -> acts.isNotEmpty() && acts.all { it in openers } }

    /** Bad news or a reproach: a habitual reply must not be reused for it. */
    fun isGrave(text: String): Boolean = all(text).any { it in grave }

    /** The text asks something of the user (a question, a request, an invitation…). */
    fun asks(text: String): Boolean = all(text).any { it in pending }

    /**
     * An answer written for [text], in its language (Arabic or English; other scripts get none).
     *  - [address]: how the user usually addresses people ("غالي"…), added to Arabic replies;
     *  - [withBusy]: false leaves out the "I will get back to you" line (reply chips: the user is there);
     *  - [greeted]: the user already greeted this person lately, so greetings are not repeated;
     *  - [hour]: local hour, lets a bare "مرحبا" be answered with the right time of day;
     *  - [seed]: picks among equivalent wordings so replies do not repeat.
     */
    fun reply(
        text: String,
        address: String? = null,
        withBusy: Boolean = true,
        tone: ReplyTone = ReplyTone.FORMAL,
        greeted: Boolean = false,
        hour: Int? = null,
        seed: Int = 0,
    ): String? {
        val acts = all(text)
        if (acts.isEmpty()) return null
        val arabic = text.count { it in '؀'..'ۿ' }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (arabic == 0 && latin == 0) return null
        val ar = arabic >= latin
        val tokens = ReplyText.tokens(text)

        acts.firstOrNull { it in grave }?.let { return line(it, tone, ar, seed, tokens) }

        var said = acts.filter { it !in pending }
        if (Intent.PRAYER in said) said = said - Intent.AMEN
        if (said.any { it != Intent.GREETING && it in openers }) said = said - Intent.GREETING
        if (greeted) said = said.filter { it !in openers }
        val pieces = said.take(MAX_PIECES).map { line(it, tone, ar, seed, tokens) }.toMutableList()
        val tail = if (withBusy) acts.firstOrNull { it in pending }?.let { line(it, tone, ar, seed, tokens) } else null

        if (ar && pieces.isNotEmpty()) {
            if (said.size == 1 && said[0] == Intent.GREETING && tail == null && tone != ReplyTone.BRIEF) {
                when (hour ?: -1) {
                    in 5..11 -> pieces[0] = pieces[0] + "، صباح الخير"
                    in 17..23 -> pieces[0] = pieces[0] + "، مساء الخير"
                }
            }
            if (address != null) {
                val at = pieces.indexOfLast { !it.endsWith("؟") }
                if (at >= 0) pieces[at] = pieces[at] + " يا " + address
            }
        }
        val head = when {
            pieces.isEmpty() -> null
            ar -> join(pieces, "، ")
            else -> join(pieces, ". ").let { if (it.last() in ENDS) it else "$it." }
        }
        return when {
            head == null -> tail
            tail == null -> head
            head.last() in ENDS -> "$head $tail"
            ar -> "$head. $tail"
            else -> "$head $tail"
        }
    }

    /** One Arabic and one English line for [intent]: a self-check that no intent lacks wording. */
    internal fun sample(intent: Intent, tone: ReplyTone): String =
        line(intent, tone, true, 0, emptyList()) + line(intent, tone, false, 0, emptyList())

    private fun join(parts: List<String>, separator: String): String {
        val sb = StringBuilder()
        for (p in parts) {
            if (sb.isNotEmpty()) sb.append(if (sb.last() in ENDS) " " else separator)
            sb.append(p)
        }
        return sb.toString()
    }

    private fun dialect(tokens: List<String>): Dialect =
        dialects.map { (d, marks) -> d to tokens.count { it in marks } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first ?: Dialect.NEUTRAL

    private fun line(intent: Intent, tone: ReplyTone, ar: Boolean, seed: Int, tokens: List<String>): String {
        if (!ar) return english.getValue(intent)
        val wish = "يسعد" in tokens
        return when {
            intent == Intent.HOWARE && tone == ReplyTone.FRIENDLY -> when (dialect(tokens)) {
                Dialect.LEVANT -> "الحمد لله منيح، وإنت كيفك؟"
                Dialect.EGYPT -> "الحمد لله كويس، وإنت عامل إيه؟"
                Dialect.GULF -> "الحمد لله بخير، وإنت شلونك؟"
                Dialect.NEUTRAL -> "الحمد لله تمام، وأنت كيف حالك؟"
            }
            intent == Intent.MORNING && wish && tone != ReplyTone.BRIEF ->
                if (tone == ReplyTone.FORMAL) "أسعد الله صباحك بكل خير" else "يسعدك ويسعد صباحك"
            intent == Intent.EVENING && wish && tone != ReplyTone.BRIEF ->
                if (tone == ReplyTone.FORMAL) "أسعد الله مساءك بكل خير" else "يسعدك ويسعد مساك"
            else -> arabic.getValue(intent).pick(tone, seed)
        }
    }

    /** Equivalent wordings per tone, separated by '|'. */
    private class Lines(formal: String, friendly: String, brief: String) {
        private val byTone = listOf(formal, friendly, brief).map { it.split('|') }
        fun pick(tone: ReplyTone, seed: Int): String = byTone[tone.ordinal].let { it[Math.floorMod(seed, it.size)] }
    }

    private val arabic: Map<Intent, Lines> = mapOf(
        Intent.DEATHNEWS to Lines(
            "إنا لله وإنا إليه راجعون، عظّم الله أجركم وأحسن عزاءكم وغفر لفقيدكم",
            "إنا لله وإنا إليه راجعون، عظّم الله أجركم وأحسن عزاءكم",
            "إنا لله وإنا إليه راجعون، عظّم الله أجركم",
        ),
        Intent.CONDOLENCE to Lines(
            "شكر الله سعيكم وجزاكم خيراً، ولا أراكم الله مكروهاً في عزيز",
            "شكر الله سعيكم، ولا أراكم الله مكروهاً",
            "شكر الله سعيكم",
        ),
        Intent.SICK to Lines(
            "سلامتك، أسأل الله لك الشفاء العاجل والعافية التامة",
            "سلامتك، ما تشوف شر إن شاء الله",
            "سلامتك، ما تشوف شر",
        ),
        Intent.BLAME to Lines(
            "حقك عليّ، أعتذر عن تأخري في الرد، وسأتواصل معك في أقرب وقت بإذن الله.",
            "حقك عليّ، مشغول شوي وبرجعلك بأقرب وقت.",
            "حقك عليّ، برجعلك قريب.",
        ),
        Intent.SALAM to Lines(
            "وعليكم السلام ورحمة الله وبركاته|وعليكم السلام ورحمة الله وبركاته، حيّاك الله",
            "وعليكم السلام ورحمة الله|وعليكم السلام، يا هلا",
            "وعليكم السلام",
        ),
        Intent.HOWARE to Lines(
            "الحمد لله بخير وعافية، أسأل الله أن تكون بأتم الصحة والسرور|بخير والحمد لله، أرجو أن تكون في أحسن حال",
            "الحمد لله تمام، وأنت كيف حالك؟",
            "الحمد لله بخير",
        ),
        Intent.AMEN to Lines("اللهم آمين، ولك بمثله وزيادة|آمين يا رب العالمين، ولك مثله", "آمين وإياك يا رب|آمين، ولك بالمثل", "آمين وإياك"),
        Intent.PRAYER to Lines(
            "اللهم آمين، جزاك الله خيراً وبارك فيك|آمين، ولك بمثل ما دعوت وزيادة",
            "آمين، الله يسعدك ويحفظك|آمين وإياك يا رب",
            "آمين وإياك",
        ),
        Intent.MORNING to Lines("صباح النور والسرور|صباح الخير والبركة", "صباح النور|صباح الورد", "صباح النور"),
        Intent.EVENING to Lines("مساء النور والسرور|مساء الخير والبركة", "مساء النور|مساء الورد", "مساء النور"),
        Intent.GREETING to Lines("أهلاً وسهلاً بك|حيّاك الله، أهلاً وسهلاً", "يا هلا والله|أهلين", "أهلاً"),
        Intent.BIRTHDAY to Lines("شكراً جزيلاً على تهنئتك اللطيفة، أسعدك الله", "تسلم، الله يسعدك", "شكراً، تسلم"),
        Intent.EID to Lines(
            "وأنت بألف خير، أعاده الله عليك باليمن والبركات",
            "وإنت بألف خير، ينعاد عليك بالصحة والسلامة",
            "وأنت بخير",
        ),
        Intent.RAMADAN to Lines(
            "الله أكرم، مبارك عليك الشهر، وتقبّل الله منا ومنك صالح الأعمال",
            "الله أكرم، مبارك عليك الشهر",
            "الله أكرم",
        ),
        Intent.FRIDAY to Lines("جمعة مباركة، تقبّل الله منا ومنكم صالح الأعمال", "علينا وعليك يا رب", "علينا وعليك"),
        Intent.CONGRATS to Lines("بارك الله فيك وأسعدك، شكراً لتهنئتك الكريمة", "الله يبارك فيك، تسلم", "الله يبارك فيك"),
        Intent.GOODNEWS to Lines("ألف مبروك، أسعدني هذا الخبر، بارك الله لك", "ألف ألف مبروك، فرحتلك من قلبي", "ألف مبروك"),
        Intent.BLESSING to Lines("عافاك الله وبارك فيك", "الله يعافيك", "الله يعافيك"),
        Intent.THANKS to Lines("العفو، هذا واجبي، وأنا في خدمتك دائماً|لا شكر على واجب، يسعدني ذلك", "العفو، ولو|العفو، في أي وقت", "العفو"),
        Intent.APOLOGY to Lines("لا عليك، لم يحدث إلا الخير", "ولا يهمك، ما صار شي", "ولا يهمك"),
        Intent.MISSYOU to Lines("والشوق متبادل، أسأل الله أن يجمعنا على خير قريباً", "وأنا أكثر والله", "وأنا أكثر"),
        Intent.PRAISE to Lines("شكراً لك، هذا من لطفك وذوقك", "تسلم، هذا من ذوقك", "تسلم"),
        Intent.WELLWISH to Lines("سلّمك الله من كل سوء، شكراً لسؤالك", "الله يسلمك", "الله يسلمك"),
        Intent.GOODNIGHT to Lines("تصبح على خير، ليلة هانئة", "وأنت من أهل الخير", "تصبح على خير"),
        Intent.FAREWELL to Lines("في أمان الله وحفظه", "مع السلامة، الله معك", "مع السلامة"),
        Intent.URGENT to Lines(
            "أعتذر، أنا مشغول الآن. إن كان الأمر عاجلاً فأرجو الاتصال بي مباشرة.",
            "مشغول الآن، إذا الأمر ضروري اتصل علي.",
            "مشغول الآن، اتصل إن كان عاجلاً.",
        ),
        Intent.CALLME to Lines(
            "وصلتني رسالتك، وسأتصل بك حالما أتفرّغ بإذن الله.",
            "تمام، برن عليك أول ما أفضى.",
            "سأتصل بك لاحقاً.",
        ),
        Intent.INVITE to Lines(
            "شكراً على دعوتك الكريمة، وسأؤكد لك في أقرب وقت بإذن الله.",
            "تسلم على الدعوة، بأكدلك قريب.",
            "تسلم، بأكدلك قريب.",
        ),
        Intent.REQUEST to Lines(
            "وصلني طلبك، وسأرد عليك في أقرب وقت بإذن الله.",
            "وصلني، برد عليك بأقرب وقت.",
            "وصلني، برد عليك.",
        ),
        Intent.WHERE to Lines(
            "أنا منشغل حالياً، وسأخبرك حالما أتفرّغ.",
            "مشغول شوي، بخبرك بعد قليل.",
            "مشغول، بخبرك لاحقاً.",
        ),
        Intent.WHEN to Lines(
            "سأوافيك بالموعد في أقرب وقت بإذن الله.",
            "بأكدلك الموعد بعد شوي.",
            "بأكدلك لاحقاً.",
        ),
        Intent.QUESTION to Lines(
            "وصلني سؤالك، وسأجيبك في أقرب وقت بإذن الله.",
            "وصلني سؤالك، برد عليك بأقرب وقت.",
            "برد عليك لاحقاً.",
        ),
    )

    private val english: Map<Intent, String> = mapOf(
        Intent.DEATHNEWS to "I'm so sorry for your loss",
        Intent.CONDOLENCE to "Thank you for your kind words",
        Intent.SICK to "Get well soon",
        Intent.BLAME to "Sorry for the late reply, I'll get back to you soon.",
        Intent.SALAM to "Wa alaykum assalam",
        Intent.HOWARE to "I'm good, thanks, how about you?",
        Intent.AMEN to "Amen, and the same to you",
        Intent.PRAYER to "Amen, and the same to you",
        Intent.MORNING to "Good morning",
        Intent.EVENING to "Good evening",
        Intent.GREETING to "Hi",
        Intent.BIRTHDAY to "Thank you so much",
        Intent.EID to "Thank you, and the same to you",
        Intent.RAMADAN to "Ramadan Kareem to you too",
        Intent.FRIDAY to "Jumu'ah Mubarak to you too",
        Intent.CONGRATS to "Thank you so much",
        Intent.GOODNEWS to "Congratulations, that's wonderful news",
        Intent.BLESSING to "Thank you, God bless you",
        Intent.THANKS to "You're welcome, anytime",
        Intent.APOLOGY to "No worries at all",
        Intent.MISSYOU to "Miss you too",
        Intent.PRAISE to "Thank you, that's kind of you",
        Intent.WELLWISH to "Thank you",
        Intent.GOODNIGHT to "Good night",
        Intent.FAREWELL to "Take care",
        Intent.URGENT to "I'm busy right now. If it's urgent, please call me.",
        Intent.CALLME to "I'll call you back as soon as I can.",
        Intent.INVITE to "Thanks for the invitation, I'll confirm soon.",
        Intent.REQUEST to "Got your message, I'll get back to you soon.",
        Intent.WHERE to "I'm busy right now, I'll let you know shortly.",
        Intent.WHEN to "I'll confirm the time shortly.",
        Intent.QUESTION to "I'm busy right now and will answer your question as soon as I can.",
    )

    private const val MAX_PIECES = 3
    private const val ENDS = "؟?.!"
}
