package dev.saged.barq.sms

/**
 * "Auto" bulk-send speed: starts balanced, goes faster after a clean run of confirmed messages and
 * slows down at the first failure or stall. Never faster than "fast": the maximum speed can make
 * the carrier block the SIM, which no confirmation from the radio shows in advance.
 */
class AutoSpeed {

    data class Pace(val window: Int, val gapMs: Long)

    private var level = START
    private var streak = 0

    @Synchronized
    fun pace(): Pace = LEVELS[level]

    /** A message was confirmed by the radio after [latencyMs]; [ok] = it was really sent. */
    @Synchronized
    fun onResult(latencyMs: Long, ok: Boolean) {
        when {
            !ok -> {
                level = (level - 2).coerceAtLeast(0)
                streak = 0
            }
            latencyMs > SLOW_MS -> {
                level = (level - 1).coerceAtLeast(0)
                streak = 0
            }
            else -> if (++streak >= CLEAN_RUN) {
                level = (level + 1).coerceAtMost(LEVELS.lastIndex)
                streak = 0
            }
        }
    }

    /** No answer for a long time: Android's question may be on screen, or the radio is stuck. */
    @Synchronized
    fun onStall() {
        level = 0
        streak = 0
    }

    companion object {
        /** Safest first. The last one equals the "fast" choice. */
        val LEVELS = listOf(Pace(1, 800), Pace(1, 250), Pace(2, 250), Pace(2, 100), Pace(3, 100))
        private const val START = 2
        private const val CLEAN_RUN = 15
        private const val SLOW_MS = 6_000L
    }
}
