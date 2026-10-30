package dev.saged.barq.domain

/**
 * "{الاسم}" in a message becomes each person's own name (from an imported Excel file, a group or
 * the phone's contacts). Someone without a name gets the message without it, tidied up.
 */
object Personalize {
    const val TOKEN = "{الاسم}"
    private val token = Regex("\\{\\s*(الاسم|الإسم|اسم|name|Name|NAME)\\s*\\}")
    private val spaces = Regex("[ \\t]{2,}")
    private val beforePunct = Regex("[ \\t]+([،,.!؟?:؛])")
    private val leadingPunct = Regex("^[ \\t]*[،,][ \\t]*")

    fun has(text: String): Boolean = token.containsMatchIn(text)

    fun apply(text: String, name: String?): String {
        val n = name?.trim().orEmpty()
        val out = token.replace(text) { n }
        if (n.isNotEmpty()) return out.trim()
        return out.lines().joinToString("\n") { line ->
            line.replace(spaces, " ").replace(beforePunct, "$1").replace(leadingPunct, "").trimEnd()
        }.trim()
    }
}
