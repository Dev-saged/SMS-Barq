package dev.saged.barq.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTextTest {
    @Test fun numbersAreDroppedFromMatchingWords() =
        assertEquals(listOf("كم", "مبلغ"), ReplyText.tokens("كم المبلغ 500؟"))

    @Test fun sameQuestionWithOtherNumberHasSameKey() =
        assertEquals(ReplyText.key("كم المبلغ 500"), ReplyText.key("كم المبلغ ٣٠٠"))

    @Test fun repliesWithDigitsLinksOrAddressesAreNotSafe() {
        assertFalse(ReplyText.isSafeReply("تمام 5 دقايق"))
        assertFalse(ReplyText.isSafeReply("تمام ٥ دقايق"))
        assertFalse(ReplyText.isSafeReply("شوف https://example.org"))
        assertFalse(ReplyText.isSafeReply("ابعتلي على a@b.co"))
        assertFalse(ReplyText.isSafeReply("x".repeat(ReplyText.MAX_REPLY + 1)))
        assertFalse(ReplyText.isSafeReply("👍"))
    }

    @Test fun genericRepliesAreSafe() {
        assertTrue(ReplyText.isSafeReply("تمام"))
        assertTrue(ReplyText.isSafeReply("OK see you"))
    }

    @Test fun repliesNamingThePersonAreNotSafe() {
        val names = ReplyText.nameWords("أحمد علي")
        assertFalse(ReplyText.isSafeReply("مبروك يا أحمد", names))
        assertTrue(ReplyText.isSafeReply("مبروك", names))
    }
}

class ReplyIntentsTest {
    @Test fun dialectsOfHowAreYouAreOneIntent() {
        for (t in listOf("كيفك", "شلونك؟", "كيف الحال", "شو أخبارك", "How are you", "what's up")) {
            assertEquals(t, Intent.HOWARE, ReplyIntents.of(t))
        }
    }

    @Test fun stretchedLettersAreUnderstood() {
        assertEquals(Intent.GREETING, ReplyIntents.of("هلااااا"))
        assertEquals(Intent.GREETING, ReplyIntents.of("مرحبااااا"))
        assertEquals(ReplyText.key("هلا"), ReplyText.key("هلااااا"))
    }

    @Test fun otherIntents() {
        assertEquals(Intent.SALAM, ReplyIntents.of("السلام عليكم ورحمة الله"))
        assertEquals(Intent.URGENT, ReplyIntents.of("ضروري ردّ علي"))
        assertEquals(Intent.THANKS, ReplyIntents.of("مشكور يا غالي"))
        assertEquals(Intent.WHERE, ReplyIntents.of("وين وصلت"))
        assertEquals(Intent.QUESTION, ReplyIntents.of("بدك تيجي؟"))
        assertNull(ReplyIntents.of("تمام الاجتماع الساعة 5"))
    }

    @Test fun urgentWinsOverAGreeting() = assertEquals(Intent.URGENT, ReplyIntents.of("مرحبا ضروري"))

    @Test fun readyMadeAnswerFollowsTheLanguageOfTheText() {
        assertTrue(ReplyIntents.reply("كيفك")!!.contains("الحمد لله"))
        assertTrue(ReplyIntents.reply("hello there")!!.startsWith("Hi"))
        assertNull(ReplyIntents.reply("你好吗"))
        assertNull(ReplyIntents.reply("الاجتماع بعد الظهر"))
    }

    private val friendly = ReplyTone.FRIENDLY

    @Test fun aPrayerAndAWishAreAnsweredPartByPart() {
        val text = "اللهم آمين يسعد مساك يا ساجد"
        assertEquals("آمين وإياك يا رب، يسعدك ويسعد مساك", ReplyIntents.reply(text, tone = friendly))
        assertEquals("آمين وإياك يا رب، يسعدك ويسعد مساك يا غالي", ReplyIntents.reply(text, address = "غالي", tone = friendly))
        assertEquals("اللهم آمين، ولك بمثله وزيادة، أسعد الله مساءك بكل خير", ReplyIntents.reply(text))
        assertEquals("آمين وإياك، مساء النور", ReplyIntents.reply(text, tone = ReplyTone.BRIEF))
    }

    @Test fun greetingsAreAnsweredInKind() {
        assertEquals("صباح النور", ReplyIntents.reply("صباح الخير", tone = friendly))
        assertEquals("يسعدك ويسعد صباحك", ReplyIntents.reply("يسعد صباحك", tone = friendly))
        assertEquals("الله يبارك فيك، تسلم", ReplyIntents.reply("مبروك", tone = friendly))
        assertEquals("يا هلا والله يا غالي", ReplyIntents.reply("هلا والله", address = "غالي", tone = friendly))
        assertEquals("وعليكم السلام ورحمة الله وبركاته", ReplyIntents.reply("السلام عليكم"))
        assertEquals("Good morning.", ReplyIntents.reply("good morning"))
    }

    @Test fun severalThingsInOneTextAreAllAnswered() {
        assertEquals(
            "وعليكم السلام ورحمة الله، الحمد لله تمام، وأنت كيف حالك؟",
            ReplyIntents.reply("السلام عليكم كيف حالك", tone = friendly),
        )
    }

    @Test fun theSendersDialectIsMirrored() {
        assertEquals("الحمد لله بخير، وإنت شلونك؟", ReplyIntents.reply("شلونك", tone = friendly))
        assertEquals("الحمد لله كويس، وإنت عامل إيه؟", ReplyIntents.reply("ازيك عامل ايه", tone = friendly))
        assertEquals("الحمد لله منيح، وإنت كيفك؟", ReplyIntents.reply("كيفك", tone = friendly))
    }

    @Test fun busyLineOnlyForWhatNeedsTheUser() {
        assertEquals("يا هلا والله. مشغول الآن، إذا الأمر ضروري اتصل علي.", ReplyIntents.reply("مرحبا ضروري", tone = friendly))
        assertEquals("يا هلا والله", ReplyIntents.reply("مرحبا ضروري", withBusy = false, tone = friendly))
        assertEquals("وصلني سؤالك، برد عليك بأقرب وقت.", ReplyIntents.reply("بدك تيجي؟", tone = friendly))
        assertNull(ReplyIntents.reply("بدك تيجي؟", withBusy = false))
        assertEquals("العفو، ولو", ReplyIntents.reply("شكرا", tone = friendly))
        assertEquals("وصلني طلبك، وسأرد عليك في أقرب وقت بإذن الله.", ReplyIntents.reply("ممكن تبعتلي الملف"))
    }

    @Test fun heavyNewsIsAnsweredAloneAndSoberly() {
        assertEquals(
            "إنا لله وإنا إليه راجعون، عظّم الله أجركم وأحسن عزاءكم وغفر لفقيدكم",
            ReplyIntents.reply("مرحبا، توفي والدي اليوم", address = "غالي"),
        )
        assertEquals("سلامتك، أسأل الله لك الشفاء العاجل والعافية التامة", ReplyIntents.reply("مرحبا انا مريض"))
        assertTrue(ReplyIntents.isGrave("ليش ما ترد علي"))
    }

    @Test fun negationAndLookAlikesAreUnderstood() {
        assertNull(ReplyIntents.of("مش مستعجل"))
        assertEquals(Intent.URGENT, ReplyIntents.of("انا مستعجل"))
        assertNull(ReplyIntents.of("للأسف ما بقدر"))
        assertEquals(Intent.APOLOGY, ReplyIntents.of("آسف تأخرت عليك"))
    }

    @Test fun acknowledgementsNeedNoReply() {
        for (t in listOf("تمام", "ok 👍", "ههههه", "ان شاء الله", "الحمد لله بخير")) assertTrue(t, ReplyIntents.needsNoReply(t))
        assertFalse(ReplyIntents.needsNoReply("تمام بكرا بنحكي"))
        assertFalse(ReplyIntents.needsNoReply("كيفك"))
    }

    @Test fun aSecondGreetingIsNotRepeated() {
        assertNull(ReplyIntents.reply("السلام عليكم", greeted = true))
        assertEquals("العفو، ولو", ReplyIntents.reply("السلام عليكم شكرا", tone = friendly, greeted = true))
        assertTrue(ReplyIntents.onlyGreets("صباح الخير"))
        assertFalse(ReplyIntents.onlyGreets("صباح الخير كيفك"))
    }

    @Test fun timeOfDayAndWordingVary() {
        assertEquals("يا هلا والله، صباح الخير", ReplyIntents.reply("مرحبا", tone = friendly, hour = 9))
        assertEquals("يا هلا والله، مساء الخير", ReplyIntents.reply("مرحبا", tone = friendly, hour = 20))
        assertEquals("أهلاً", ReplyIntents.reply("مرحبا", tone = ReplyTone.BRIEF, hour = 9))
        assertEquals("العفو، في أي وقت", ReplyIntents.reply("شكرا", tone = friendly, seed = 1))
    }

    @Test fun broadIntentsCarryNoHabit() {
        assertNull(ReplyIntents.specific(ReplyText.tokens("ممكن خدمة")))
        assertEquals(Intent.HOWARE, ReplyIntents.specific(ReplyText.tokens("ازيك")))
    }

    @Test fun everyIntentHasALineInBothLanguages() {
        for (i in Intent.entries) for (t in ReplyTone.entries) assertNotNull("$i $t", ReplyIntents.sample(i, t))
    }
}

class ReplyPairingTest {
    private fun incoming(body: String, at: Long, eligible: Boolean = true) = Turn(true, body, at, eligible = eligible)
    private fun mine(body: String, at: Long, auto: Boolean = false) = Turn(false, body, at, auto = auto)

    @Test fun firstReplyPairsWithTheTextItAnswered() {
        val w = ReplyPairing.Walker()
        assertNull(w.feed(incoming("كيف حالك", 0)))
        val pair = w.feed(mine("الحمد لله تمام", 60_000))
        assertEquals(ReplyPairing.Pair("كيف حالك", "الحمد لله تمام", 60_000), pair)
    }

    @Test fun followUpMessagesDoNotPairAgain() {
        val w = ReplyPairing.Walker()
        w.feed(incoming("كيف حالك", 0))
        assertNotNull(w.feed(mine("تمام", 1_000)))
        assertNull(w.feed(mine("وانت كيفك", 2_000)))
    }

    @Test fun serviceSendersAreNotLearned() {
        val w = ReplyPairing.Walker()
        w.feed(incoming("رصيدك منخفض", 0, eligible = false))
        assertNull(w.feed(mine("شكرا", 1_000)))
    }

    @Test fun lateRepliesAreNotPaired() {
        val w = ReplyPairing.Walker()
        w.feed(incoming("كيف حالك", 0))
        assertNull(w.feed(mine("تمام", ReplyPairing.WINDOW_MS + 1)))
    }

    @Test fun autoRepliesNeverTrainAndDoNotHideTheRealOne() {
        val w = ReplyPairing.Walker()
        w.feed(incoming("وين انت", 0))
        assertNull(w.feed(mine("مشغول حاليا", 1_000, auto = true)))
        assertEquals("في الطريق", w.feed(mine("في الطريق", 2_000))?.reply)
    }

    @Test fun unsafeRepliesAreSkipped() {
        val w = ReplyPairing.Walker()
        w.feed(incoming("متى توصل", 0))
        assertNull(w.feed(mine("بعد 10 دقايق", 1_000)))
    }
}

class ReplyIndexTest {
    private val now = 1_800_000_000_000L

    private fun memory(incoming: String, reply: String, uses: Int = 1) =
        Memory(ReplyText.key(incoming), reply, uses, now)

    private val index = ReplyIndex(
        listOf(
            memory("كيف حالك", "الحمد لله تمام", uses = 5),
            memory("وين انت", "في الطريق", uses = 2),
            memory("شو الاخبار", "كله تمام", uses = 3),
            memory("مرحبا", "اهلا"),
        ),
    )

    @Test fun similarTextGetsTheUsersOwnReply() =
        assertEquals("الحمد لله تمام", index.best("كيف حالك اليوم", now)?.reply)

    @Test fun unrelatedTextGetsNothing() {
        assertNull(index.best("ما هو سعر الدولار", now))
        assertTrue(index.suggest("ما هو سعر الدولار", 3, now, withPopular = false).isEmpty())
    }

    @Test fun sameQuestionWithAnotherNumberStillMatches() {
        val i = ReplyIndex(listOf(memory("كم المبلغ 500", "ابعتلك التفاصيل")))
        assertEquals("ابعتلك التفاصيل", i.best("كم المبلغ 700", now)?.reply)
    }

    @Test fun moreOftenUsedReplyWins() {
        val i = ReplyIndex(
            listOf(memory("مساء الخير", "مساء الورد"), memory("مساء الخير", "مساء النور", uses = 10)),
        )
        assertEquals("مساء النور", i.suggest("مساء الخير", 1, now).first().reply)
    }

    @Test fun theSameReplyIsOfferedOnce() {
        val i = ReplyIndex(listOf(memory("كيف حالك", "تمام"), memory("كيف الحال", "تمام")))
        assertEquals(1, i.suggest("كيف حالك", 3, now, withPopular = false).size)
    }

    @Test fun frequentShortRepliesFillTheChips() {
        val i = ReplyIndex(listOf(memory("اول", "تمام"), memory("ثاني", "تمام"), memory("ثالث", "تمام")))
        val out = i.suggest("zzz qqq", 3, now)
        assertEquals(listOf("تمام"), out.map { it.reply })
        assertTrue(out.single().popular)
    }

    @Test fun sameMeaningInOtherWordsUsesTheHabit() {
        // "شلونك" was never seen, but it asks the same as "كيف حالك" which the user answered often.
        assertEquals("الحمد لله تمام", index.best("شلونك", now)?.reply)
    }

    @Test fun aSingleOldAnswerIsOnlySuggestedForOtherWords() {
        val i = ReplyIndex(listOf(memory("كيف حالك", "تمام الحمد لله")))
        assertNull(i.best("شلونك", now))
        assertEquals("تمام الحمد لله", i.suggest("شلونك", 1, now).first().reply)
    }

    @Test fun anOffTopicHabitIsNeverSentToAnotherText() {
        val i = ReplyIndex(listOf(memory("ساجد شو اخبار الشغل", "صحيح شو موضوع البطالة الي عندك بكرا")))
        val blessing = "اللهم آمين يسعد مساك يا ساجد"
        assertNull(i.best(blessing, now))
        assertTrue(i.suggest(blessing, 3, now, withPopular = false).isEmpty())
    }

    @Test fun autoReplyNeedsRealOverlapNotOneSharedWord() {
        val i = ReplyIndex(listOf(memory("الاجتماع بكرا الساعة", "تمام")))
        assertNull(i.best("الاجتماع تاجل", now))
    }

    @Test fun theUsersAddressHabitIsLearned() {
        val i = ReplyIndex(
            listOf(
                memory("كيف حالك", "تمام يا غالي"),
                memory("وين انت", "في الطريق يا غالي"),
                memory("شو الاخبار", "كله تمام يا غالي"),
                memory("مرحبا", "اهلا"),
            ),
        )
        assertEquals("غالي", i.addressTerm)
        assertNull(index.addressTerm)
    }

    @Test fun emptyMemorySuggestsNothing() {
        val i = ReplyIndex(emptyList())
        assertTrue(i.suggest("كيف حالك", 3, now).isEmpty())
        assertNull(i.best("كيف حالك", now))
    }
}
