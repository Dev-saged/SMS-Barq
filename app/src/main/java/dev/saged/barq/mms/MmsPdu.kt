package dev.saged.barq.mms

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Minimal MMS (OMA-MMS-ENC / WAP-230-WSP) decoder written for Barq. It understands exactly what
 * a receiving client needs: m-notification.ind, m-retrieve.conf (multipart) and it can encode
 * m-notifyresp.ind. Unknown headers are skipped safely using the generic WSP value rules.
 */
object MmsPdu {

    const val TYPE_NOTIFICATION_IND = 0x82
    const val TYPE_NOTIFYRESP_IND = 0x83
    const val TYPE_RETRIEVE_CONF = 0x84

    private const val H_CONTENT_LOCATION = 0x03
    private const val H_CONTENT_TYPE = 0x04
    private const val H_DATE = 0x05
    private const val H_FROM = 0x09
    private const val H_MESSAGE_TYPE = 0x0C
    private const val H_MMS_VERSION = 0x0D
    private const val H_MESSAGE_SIZE = 0x0E
    private const val H_SUBJECT = 0x16
    private const val H_TRANSACTION_ID = 0x18

    data class Notification(
        val transactionId: String,
        val contentLocation: String,
        val from: String?,
        val size: Long,
        val version: Int,
    )

    class Part(val contentType: String, val name: String?, val charset: Int, val data: ByteArray) {
        val isText: Boolean get() = contentType.startsWith("text/plain")
        fun text(): String = String(data, charsetFor(charset)).trimEnd('\u0000')
    }

    data class Retrieved(
        val from: String?,
        val date: Long,
        val subject: String?,
        val parts: List<Part>,
    )

    private data class ContentType(val mime: String, val charset: Int, val name: String?)

    private class Headers {
        var type = -1
        var transactionId: String? = null
        var contentLocation: String? = null
        var from: String? = null
        var date = 0L
        var size = 0L
        var version = 0x12 // MMS 1.2, stored without the short-integer high bit
        var subject: String? = null
        var contentType: ContentType? = null
    }

    fun parseNotification(pdu: ByteArray?): Notification? {
        if (pdu == null || pdu.isEmpty()) return null
        return try {
            val h = readHeaders(Reader(pdu))
            val location = h.contentLocation
            if (h.type != TYPE_NOTIFICATION_IND || location == null) null
            else Notification(h.transactionId.orEmpty(), location, h.from, h.size, h.version)
        } catch (_: Exception) {
            null
        }
    }

    fun parseRetrieveConf(pdu: ByteArray): Retrieved? {
        return try {
            val r = Reader(pdu)
            val h = readHeaders(r)
            if (h.type != TYPE_RETRIEVE_CONF) return null
            val ct = h.contentType
            val parts = when {
                ct == null -> emptyList()
                ct.mime.contains("multipart") -> readMultipart(r)
                else -> listOf(Part(ct.mime, ct.name, ct.charset, r.rest()))
            }
            Retrieved(h.from, if (h.date > 0) h.date * 1000 else System.currentTimeMillis(), h.subject, parts)
        } catch (_: Exception) {
            null
        }
    }

    /** m-notifyresp.ind with X-Mms-Status = Retrieved. Tells the carrier the download succeeded. */
    fun encodeNotifyResp(transactionId: String, version: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80 or H_MESSAGE_TYPE); out.write(TYPE_NOTIFYRESP_IND)
        out.write(0x80 or H_TRANSACTION_ID); out.write(transactionId.toByteArray(Charsets.US_ASCII)); out.write(0)
        out.write(0x80 or H_MMS_VERSION); out.write(version or 0x80)
        out.write(0x80 or 0x15); out.write(0x81) // X-Mms-Status: Retrieved
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ decoding

    private fun readHeaders(r: Reader): Headers {
        val h = Headers()
        while (r.has()) {
            val first = r.peek()
            if (first and 0x80 == 0) {
                // Application header: token text name followed by a text value.
                r.readText(); if (r.has()) r.skipValue()
                continue
            }
            r.read()
            when (first and 0x7F) {
                H_MESSAGE_TYPE -> h.type = r.read()
                H_TRANSACTION_ID -> h.transactionId = r.readText()
                H_CONTENT_LOCATION -> h.contentLocation = r.readText()
                H_FROM -> h.from = r.readFrom()
                H_DATE -> h.date = r.readInteger()
                H_MESSAGE_SIZE -> h.size = r.readInteger()
                H_MMS_VERSION -> h.version = r.read() and 0x7F
                H_SUBJECT -> h.subject = r.readEncodedString()
                H_CONTENT_TYPE -> {
                    h.contentType = r.readContentType()
                    return h // the body follows Content-Type
                }
                else -> r.skipValue()
            }
        }
        return h
    }

    private fun readMultipart(r: Reader): List<Part> {
        val count = r.readUintvar().toInt()
        val parts = ArrayList<Part>(count)
        repeat(count) {
            if (!r.has()) return parts
            val headersLen = r.readUintvar().toInt()
            val dataLen = r.readUintvar().toInt()
            val headersEnd = r.pos + headersLen
            val ct = r.readContentType()
            var name = ct.name
            while (r.pos < headersEnd) {
                val b = r.peek()
                if (b and 0x80 != 0) {
                    r.read()
                    when (b and 0x7F) {
                        0x0E -> { val loc = r.readText(); if (name == null) name = loc } // Content-Location
                        0x40 -> { val id = r.readText(); if (name == null) name = id.trim('<', '>') } // Content-ID
                        else -> r.skipValue()
                    }
                } else {
                    r.readText(); if (r.pos < headersEnd) r.skipValue()
                }
            }
            r.pos = headersEnd
            val data = r.bytes(dataLen)
            parts += Part(ct.mime, name, ct.charset, data)
        }
        return parts
    }

    private class Reader(private val b: ByteArray) {
        var pos = 0
        fun has() = pos < b.size
        fun peek() = b[pos].toInt() and 0xFF
        fun read() = b[pos++].toInt() and 0xFF
        fun rest(): ByteArray = b.copyOfRange(pos, b.size).also { pos = b.size }
        fun bytes(n: Int): ByteArray {
            val end = (pos + n).coerceAtMost(b.size)
            return b.copyOfRange(pos, end).also { pos = end }
        }

        fun readUintvar(): Long {
            var value = 0L
            var count = 0
            while (has() && count < 5) {
                val x = read()
                value = (value shl 7) or (x and 0x7F).toLong()
                count++
                if (x and 0x80 == 0) break
            }
            return value
        }

        fun readValueLength(): Int {
            val v = read()
            return when {
                v <= 30 -> v
                v == 31 -> readUintvar().toInt()
                else -> { pos--; 0 }
            }
        }

        /** Null-terminated text, optionally starting with a quote (0x7F or '"'). */
        fun readText(): String {
            if (has() && (peek() == 0x7F || peek() == 0x22)) pos++
            val start = pos
            while (has() && b[pos].toInt() != 0) pos++
            val s = String(b, start, pos - start, Charsets.UTF_8)
            if (has()) pos++ // skip terminator
            return s
        }

        /** Short-integer or Long-integer. */
        fun readInteger(): Long {
            val v = peek()
            if (v and 0x80 != 0) return (read() and 0x7F).toLong()
            val len = read()
            var value = 0L
            repeat(len) { value = (value shl 8) or read().toLong() }
            return value
        }

        /** Generic WSP skip rule — works for every header value type. */
        fun skipValue() {
            if (!has()) return
            val v = peek()
            when {
                v <= 30 -> { pos++; pos += v }
                v == 31 -> { pos++; val len = readUintvar(); pos += len.toInt() }
                v <= 127 -> readText()
                else -> pos++
            }
            if (pos > b.size) pos = b.size
        }

        fun readEncodedString(): String {
            val v = peek()
            if (v > 31) return readText()
            val len = readValueLength()
            val end = pos + len
            val charset = readInteger().toInt()
            if (has() && peek() == 0x7F) pos++
            val raw = b.copyOfRange(pos, end.coerceAtMost(b.size))
            pos = end
            return String(raw, charsetFor(charset)).trimEnd('\u0000')
        }

        fun readFrom(): String? {
            val len = readValueLength()
            val end = pos + len
            val token = read()
            val address = if (token == 0x80) readEncodedString() else null
            pos = end
            // "+970599000000/TYPE=PLMN" -> "+970599000000"
            return address?.substringBefore("/TYPE=")
        }

        fun readContentType(): ContentType {
            val v = peek()
            if (v and 0x80 != 0) return ContentType(wellKnown(read() and 0x7F), 0, null)
            if (v > 31) return ContentType(readText(), 0, null)
            val len = readValueLength()
            val end = pos + len
            val m = peek()
            val mime = when {
                m and 0x80 != 0 -> wellKnown(read() and 0x7F)
                m > 31 -> readText()
                else -> { readInteger(); "application/octet-stream" }
            }
            var charset = 0
            var name: String? = null
            while (pos < end) {
                val p = peek()
                if (p and 0x80 != 0) {
                    read()
                    when (p and 0x7F) {
                        0x01 -> charset = readInteger().toInt() // Charset
                        0x05, 0x17 -> name = readText() // Name
                        0x06, 0x18 -> name = readText() // Filename
                        else -> skipValue()
                    }
                } else {
                    val key = readText().lowercase()
                    if (pos >= end) break
                    if (key == "name" || key == "filename") name = readText() else skipValue()
                }
            }
            pos = end
            return ContentType(mime, charset, name)
        }
    }

    private fun charsetFor(mib: Int): Charset = runCatching {
        when (mib) {
            3 -> Charsets.US_ASCII
            4 -> Charsets.ISO_8859_1
            1000, 1013 -> Charsets.UTF_16BE
            1014 -> Charsets.UTF_16LE
            1015 -> Charsets.UTF_16
            2026 -> Charset.forName("Big5")
            2252, 2256 -> Charset.forName("windows-125" + (mib - 2250))
            else -> Charsets.UTF_8
        }
    }.getOrDefault(Charsets.UTF_8)

    private val WELL_KNOWN = arrayOf(
        "*/*", "text/*", "text/html", "text/plain", "text/x-hdml", "text/x-ttml", "text/x-vCalendar",
        "text/x-vCard", "text/vnd.wap.wml", "text/vnd.wap.wmlscript", "text/vnd.wap.wta-event",
        "multipart/*", "multipart/mixed", "multipart/form-data", "multipart/byterantes", "multipart/alternative",
        "application/*", "application/java-vm", "application/x-www-form-urlencoded", "application/x-hdmlc",
        "application/vnd.wap.wmlc", "application/vnd.wap.wmlscriptc", "application/vnd.wap.wta-eventc",
        "application/vnd.wap.uaprof", "application/vnd.wap.wtls-ca-certificate",
        "application/vnd.wap.wtls-user-certificate", "application/x-x509-ca-cert",
        "application/x-x509-user-cert", "image/*", "image/gif", "image/jpeg", "image/tiff", "image/png",
        "image/vnd.wap.wbmp", "application/vnd.wap.multipart.*", "application/vnd.wap.multipart.mixed",
        "application/vnd.wap.multipart.form-data", "application/vnd.wap.multipart.byteranges",
        "application/vnd.wap.multipart.alternative", "application/xml", "text/xml",
        "application/vnd.wap.wbxml", "application/x-x968-cross-cert", "application/x-x968-ca-cert",
        "application/x-x968-user-cert", "text/vnd.wap.si", "application/vnd.wap.sic", "text/vnd.wap.sl",
        "application/vnd.wap.slc", "text/vnd.wap.co", "application/vnd.wap.coc",
        "application/vnd.wap.multipart.related", "application/vnd.wap.sia", "text/vnd.wap.connectivity-xml",
        "application/vnd.wap.connectivity-wbxml", "application/pkcs7-mime",
        "application/vnd.wap.hashed-certificate", "application/vnd.wap.signed-certificate",
        "application/vnd.wap.cert-response", "application/xhtml+xml", "application/wml+xml", "text/css",
        "application/vnd.wap.mms-message",
    )

    private fun wellKnown(code: Int): String = WELL_KNOWN.getOrElse(code) { "application/octet-stream" }
}
