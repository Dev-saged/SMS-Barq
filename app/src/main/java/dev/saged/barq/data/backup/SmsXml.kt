package dev.saged.barq.data.backup

import android.util.Xml
import dev.saged.barq.data.db.Box
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The XML format of "SMS Backup & Restore", the file most messaging apps can import. Text
 * messages only (type 1 received, 2 sent, 5 failed); MMS elements are skipped on import.
 */
object SmsXml {

    const val MIME = "text/xml"
    const val FOOTER = "</smses>\n"

    private val readable = ThreadLocal.withInitial { SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.ENGLISH) }

    fun header(count: Int, now: Long, backupSet: String): String =
        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n" +
            "<smses count=\"$count\" backup_set=\"$backupSet\" backup_date=\"$now\" type=\"full\">\n"

    fun sms(address: String, body: String, date: Long, dateSent: Long, box: Int, read: Boolean, subId: Int): String =
        "  <sms protocol=\"0\" address=\"${escape(address)}\" date=\"$date\" type=\"$box\" subject=\"null\" " +
            "body=\"${escape(body)}\" toa=\"null\" sc_toa=\"null\" service_center=\"null\" read=\"${if (read) 1 else 0}\" " +
            "status=\"-1\" locked=\"0\" date_sent=\"$dateSent\" sub_id=\"$subId\" " +
            "readable_date=\"${readable.get()!!.format(Date(date))}\" contact_name=\"(Unknown)\" />\n"

    /** Attribute-safe text; characters XML cannot hold are dropped, line breaks are kept as references. */
    fun escape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '&'.code -> sb.append("&amp;")
                cp == '<'.code -> sb.append("&lt;")
                cp == '>'.code -> sb.append("&gt;")
                cp == '"'.code -> sb.append("&quot;")
                cp == '\''.code -> sb.append("&apos;")
                cp == 9 || cp == 10 || cp == 13 -> sb.append("&#").append(cp).append(';')
                cp < 0x20 || cp in 0xD800..0xDFFF || cp == 0xFFFE || cp == 0xFFFF -> Unit
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    /** True when the stream starts with "<" (after a BOM or blanks). The stream is left unread. */
    fun looksLikeXml(input: BufferedInputStream): Boolean {
        input.mark(16)
        try {
            val head = ByteArray(16)
            val n = input.read(head).coerceAtLeast(0)
            var i = 0
            if (n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
            while (i < n && head[i].toInt().toChar().isWhitespace()) i++
            return i < n && head[i] == '<'.code.toByte()
        } finally {
            input.reset()
        }
    }

    fun parse(input: InputStream): List<BackupMessage> {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        val out = ArrayList<BackupMessage>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sms") readSms(parser)?.let(out::add)
            event = parser.next()
        }
        return out
    }

    private fun readSms(p: XmlPullParser): BackupMessage? {
        fun attr(name: String): String? = p.getAttributeValue(null, name)?.takeUnless { it == "null" }
        val address = attr("address")?.trim().orEmpty()
        val date = attr("date")?.toLongOrNull() ?: return null
        val box = when (attr("type")?.toIntOrNull()) {
            1 -> Box.INBOX
            2 -> Box.SENT
            5 -> Box.FAILED
            else -> return null
        }
        if (address.isEmpty()) return null
        return BackupMessage(
            address = address,
            body = attr("body").orEmpty(),
            date = date,
            dateSent = attr("date_sent")?.toLongOrNull() ?: 0,
            box = box,
            read = attr("read") != "0",
            subId = attr("sub_id")?.toIntOrNull() ?: -1,
        )
    }
}
