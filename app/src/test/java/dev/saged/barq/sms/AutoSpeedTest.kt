package dev.saged.barq.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSpeedTest {

    private fun AutoSpeed.clean(times: Int) = repeat(times) { onResult(500, true) }

    @Test
    fun startsBalanced() {
        assertEquals(AutoSpeed.Pace(2, 250), AutoSpeed().pace())
    }

    @Test
    fun speedsUpAfterACleanRunButNeverPastFast() {
        val auto = AutoSpeed()
        auto.clean(15)
        assertEquals(AutoSpeed.Pace(2, 100), auto.pace())
        auto.clean(15 * 10)
        assertEquals(AutoSpeed.LEVELS.last(), auto.pace())
        assertEquals(AutoSpeed.Pace(3, 100), auto.pace())
    }

    @Test
    fun aFailureSlowsDownTwoStepsAndResetsTheRun() {
        val auto = AutoSpeed()
        auto.clean(14)
        auto.onResult(500, false)
        assertEquals(AutoSpeed.Pace(1, 800), auto.pace())
        auto.clean(14)
        assertEquals(AutoSpeed.Pace(1, 800), auto.pace())
    }

    @Test
    fun slowConfirmationAndStallSlowDown() {
        val auto = AutoSpeed()
        auto.onResult(9_000, true)
        assertEquals(AutoSpeed.Pace(1, 250), auto.pace())
        auto.onStall()
        assertEquals(AutoSpeed.Pace(1, 800), auto.pace())
    }

    @Test
    fun anyLimitFromTenPerSecondCountsAsRaised() {
        assertTrue(SendLimit.Limit(maxCount = 1000, intervalMs = 60_000, isDefault = false).raised)
        assertTrue(SendLimit.Limit(maxCount = 600, intervalMs = 60_000, isDefault = false).raised)
        assertFalse(SendLimit.Limit(maxCount = 300, intervalMs = 60_000, isDefault = false).raised)
        assertFalse(SendLimit.Limit(maxCount = 30, intervalMs = 60_000, isDefault = true).raised)
    }
}
