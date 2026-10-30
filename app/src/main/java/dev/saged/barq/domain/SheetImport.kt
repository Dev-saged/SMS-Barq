package dev.saged.barq.domain

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream

/**
 * Reads names and phone numbers from an Excel (.xlsx) or CSV file. The first row's titles say
 * which column is which ("الاسم" / "Name", "رقم الجوال" / "Phone"…); without titles the column that
 * looks like phone numbers is found by itself. Numbers are written in the local form (Palestinian
 * numbers become 05x) and the same number twice is kept once.
 */
object SheetImport {

    data class Person(val name: String?, val phone: String)

    data class Result(
        val people: List<Person>,
        /** Rows whose number was missing or not a phone number. */
        val invalid: Int,
        /** Rows dropped because the number was already in the file. */
        val repeated: Int,
        val nameColumn: String?,
        val phoneColumn: String?,
    )

    class UnsupportedFormat : Exception()

    private val nameWords = listOf("الاسم", "اسم", "الإسم", "إسم", "name", "full name", "fullname", "contact", "الأسماء")
    private val phoneWords = listOf(
        "رقم", "الرقم", "جوال", "الجوال", "هاتف", "الهاتف", "موبايل", "تلفون", "واتس", "phone", "mobile", "number",
        "tel", "cell", "whatsapp", "msisdn",
    )

    fun read(input: InputStream): Result {
        val bytes = input.readBytes()
        val rows = when {
            bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> readXlsx(bytes)
            // Old binary Excel (.xls) starts with an OLE header: not supported, ask for .xlsx or CSV.
            bytes.size >= 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() -> throw UnsupportedFormat()
            else -> readCsv(decode(bytes))
        }
        return pick(rows.map { r -> r.map { it.trim() } }.filter { r -> r.any { it.isNotEmpty() } })
    }

    // ------------------------------------------------------------------ columns

    internal fun pick(rows: List<List<String>>): Result {
        if (rows.isEmpty()) return Result(emptyList(), 0, 0, null, null)
        val header = rows.first()
        fun find(words: List<String>): Int = header.indexOfFirst { cell ->
            val c = cell.lowercase().trim()
            c.isNotEmpty() && !looksLikePhone(c) && words.any { w -> c == w || c.contains(w) }
        }
        var phoneCol = find(phoneWords)
        var nameCol = find(nameWords).takeIf { it != phoneCol } ?: -1
        val hasHeader = phoneCol >= 0 || nameCol >= 0
        val body = if (hasHeader) rows.drop(1) else rows
        val width = rows.maxOf { it.size }
        if (phoneCol < 0) {
            // The column where most cells are phone numbers.
            phoneCol = (0 until width).maxByOrNull { c -> body.count { looksLikePhone(it.getOrNull(c).orEmpty()) } } ?: 0
        }
        if (nameCol < 0) {
            nameCol = (0 until width).filter { it != phoneCol }.maxByOrNull { c ->
                body.count { r -> r.getOrNull(c).orEmpty().let { it.isNotBlank() && !looksLikePhone(it) && it.any(Char::isLetter) } }
            } ?: -1
        }
        val seen = HashSet<String>()
        val people = ArrayList<Person>()
        var invalid = 0
        var repeated = 0
        for (r in body) {
            val raw = r.getOrNull(phoneCol).orEmpty()
            val phone = PhoneUtils.localPs(plainNumber(raw))
            if (!looksLikePhone(phone)) {
                invalid++
                continue
            }
            if (!seen.add(PhoneUtils.matchKey(phone))) {
                repeated++
                continue
            }
            val name = if (nameCol >= 0) r.getOrNull(nameCol)?.trim()?.takeIf { it.isNotEmpty() && !looksLikePhone(it) } else null
            people += Person(name, phone)
        }
        return Result(
            people, invalid, repeated,
            nameColumn = if (hasHeader && nameCol >= 0) header.getOrNull(nameCol)?.takeIf { it.isNotBlank() } else null,
            phoneColumn = if (hasHeader) header.getOrNull(phoneCol)?.takeIf { it.isNotBlank() } else null,
        )
    }

    private fun looksLikePhone(s: String): Boolean {
        val t = PhoneUtils.normalizeDigits(s.trim())
        val digits = t.count { it.isDigit() }
        return digits in 7..15 && t.all { it.isDigit() || it in "+-() ." }
    }

    /** Excel keeps big numbers as 9.70599123456E11: written back in full. */
    private fun plainNumber(s: String): String {
        val t = s.trim()
        if (!t.contains('E', ignoreCase = true) || t.any { it.isLetter() && it != 'E' && it != 'e' }) return t
        return runCatching { BigDecimal(t).toPlainString().substringBefore('.') }.getOrDefault(t)
    }

    // ------------------------------------------------------------------ CSV

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        // Excel on Windows saves Arabic CSV as windows-1256 when not asked for UTF-8.
        return if ('\uFFFD' in utf8) runCatching { String(bytes, charset("windows-1256")) }.getOrDefault(utf8) else utf8
    }

    internal fun readCsv(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val sep = listOf(',', ';', '\t', '،').maxByOrNull { c -> firstLine.count { it == c } } ?: ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == sep -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += cell.toString(); cell.clear(); rows += row; row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }

    // ------------------------------------------------------------------ XLSX

    private val siRe = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
    private val tRe = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
    private val rowRe = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
    private val cellRe = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
    private val refRe = Regex("\\br=\"([A-Z]+)\\d+\"")
    private val typeRe = Regex("\\bt=\"([a-zA-Z]+)\"")
    private val vRe = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)

    internal fun readXlsx(bytes: ByteArray): List<List<String>> {
        val files = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val n = e.name
                if (n == "xl/sharedStrings.xml" || (n.startsWith("xl/worksheets/") && n.endsWith(".xml"))) {
                    files[n] = String(zip.readBytes(), Charsets.UTF_8)
                }
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { x ->
            siRe.findAll(x).map { si -> tRe.findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) } }.toList()
        }.orEmpty()
        val sheet = files["xl/worksheets/sheet1.xml"]
            ?: files.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()?.let { files[it] }
            ?: return emptyList()
        return rowRe.findAll(sheet).map { row ->
            val cells = ArrayList<String>()
            for (c in cellRe.findAll(row.groupValues[1])) {
                val attrs = c.groupValues[1]
                val inner = c.groupValues[2]
                val col = refRe.find(attrs)?.groupValues?.get(1)?.let { colIndex(it) } ?: cells.size
                val type = typeRe.find(attrs)?.groupValues?.get(1)
                val value = when (type) {
                    "s" -> vRe.find(inner)?.groupValues?.get(1)?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> tRe.findAll(inner).joinToString("") { unescape(it.groupValues[1]) }
                    else -> vRe.find(inner)?.groupValues?.get(1)?.let { unescape(it) }.orEmpty()
                }
                while (cells.size < col) cells += ""
                if (cells.size == col) cells += value else cells[col] = value
            }
            cells as List<String>
        }.toList()
    }

    private fun colIndex(letters: String): Int = letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(x?)([0-9a-fA-F]+);")) { m ->
            val code = if (m.groupValues[1].isNotEmpty()) m.groupValues[2].toInt(16) else m.groupValues[2].toInt()
            String(Character.toChars(code))
        }
        .replace("&amp;", "&")
}
