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

    @Test fun emptyMemorySuggestsNothing() {
        val i = ReplyIndex(emptyList())
        assertTrue(i.suggest("كيف حالك", 3, now).isEmpty())
        assertNull(i.best("كيف حالك", now))
    }
}
