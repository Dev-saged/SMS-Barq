package dev.saged.barq.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BulkPlanTest {
    private val oneOrTwo: (String) -> Int = { if (it.length > 20) 2 else 1 }

    @Test fun plainTextCountsPartsForEveryone() {
        val r = BulkPlan.review(listOf("أحمد", null, "سارة"), "مرحبا بكم جميعا", 30, oneOrTwo)
        assertEquals(3, r.recipients)
        assertEquals(3, r.parts)
        assertEquals(0, r.unnamed)
        assertEquals(1, r.minutes)
    }

    @Test fun personalTextIsCountedPerPerson() {
        val r = BulkPlan.review(listOf("عبدالرحمن بن محمد", "سعد", null), "مرحبا {الاسم} كل عام وانتم بخير", 30, oneOrTwo)
        assertEquals(1, r.unnamed)
        assertEquals(2 + 2 + 2, r.parts)
        assertEquals("مرحبا عبدالرحمن بن محمد كل عام وانتم بخير", r.preview)
    }

    @Test fun waitingFollowsAndroidsLimit() {
        val names = List(100) { "س$it" }
        assertEquals(4, BulkPlan.review(names, "اهلا", 30, oneOrTwo).minutes)
        assertEquals(0, BulkPlan.review(names, "اهلا", 0, oneOrTwo).minutes)
    }

    @Test fun emptyPartCountStillCountsOne() {
        assertEquals(2, BulkPlan.review(listOf("أ", "ب"), "اهلا", 30) { 0 }.parts)
    }
}
