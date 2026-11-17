package dev.saged.barq.domain

import kotlin.math.ceil

/** What a bulk send will do, worked out before anything is sent. */
object BulkPlan {

    /**
     * [parts]: SMS parts in total (a long text is several); [unnamed]: people who get a "{الاسم}" message
     * without a name; [minutes]: the least time Android's own limit allows (0 = no waiting).
     */
    data class Review(val recipients: Int, val parts: Int, val unnamed: Int, val minutes: Int, val preview: String)

    /** [names]: one entry per recipient (null or blank when the contact has no name). */
    fun review(names: List<String?>, body: String, perMinute: Int, partsOf: (String) -> Int): Review {
        val personal = Personalize.has(body)
        val parts = if (personal) {
            names.sumOf { partsOf(Personalize.apply(body, it)).coerceAtLeast(1) }
        } else {
            partsOf(body).coerceAtLeast(1) * names.size
        }
        val minutes = if (perMinute <= 0) 0 else ceil(parts.toDouble() / perMinute).toInt()
        val unnamed = if (personal) names.count { it.isNullOrBlank() } else 0
        val preview = Personalize.apply(body, names.firstOrNull { !it.isNullOrBlank() })
        return Review(names.size, parts, unnamed, minutes, preview)
    }
}
