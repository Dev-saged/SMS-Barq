package dev.saged.barq.data.backup

import dev.saged.barq.data.db.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedInputStream

@RunWith(RobolectricTestRunner::class)
class SmsXmlTest {

    private val tricky = "مرحبا \"أحمد\" & <أنت> 'تمام'\nسطر ثانٍ\tتاب 😀\u0001"

    @Test
    fun escapeKeepsTextSafeAndDropsIllegalCharacters() {
        val e = SmsXml.escape(tricky)
        assertFalse(e.contains('<') || e.contains('"') || e.contains('\n') || e.contains('\u0001'))
        assertTrue(e.contains("&amp;") && e.contains("&#10;") && e.contains("😀"))
    }

    @Test
    fun exportedFileReadsBackUnchanged() {
        val xml = SmsXml.header(3, 1_700_000_000_000, "set") +
            SmsXml.sms("+972599111222", tricky, 1_700_000_000_000, 1_699_999_999_000, Box.INBOX, false, 1) +
            SmsXml.sms("Jawwal", "رصيدك 10 شيكل", 1_700_000_100_000, 0, Box.SENT, true, -1) +
            SmsXml.sms("+972599333444", "فشلت", 1_700_000_200_000, 0, Box.FAILED, true, -1) +
            SmsXml.FOOTER
        val messages = SmsXml.parse(xml.byteInputStream())
        assertEquals(3, messages.size)
        with(messages[0]) {
            assertEquals("+972599111222", address)
            assertEquals(tricky.replace("\u0001", ""), body)
            assertEquals(1_700_000_000_000, date)
            assertEquals(1_699_999_999_000, dateSent)
            assertEquals(Box.INBOX, box)
            assertFalse(read)
            assertEquals(1, subId)
        }
        assertEquals(Box.SENT, messages[1].box)
        assertEquals(Box.FAILED, messages[2].box)
    }

    @Test
    fun readsAFileFromTheOriginalApp() {
        val xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <?xml-stylesheet type="text/xsl" href="sms.xsl"?>
            <smses count="4" backup_set="x" backup_date="1" type="full">
              <sms protocol="0" address="0599123456" date="1700000000000" type="1" subject="null" body="مرحبا" toa="null" sc_toa="null" service_center="null" read="1" status="-1" locked="0" date_sent="1700000000000" readable_date="x" contact_name="Ali" />
              <sms protocol="0" address="0599123456" date="1700000001000" type="3" body="مسودة" read="1" />
              <mms date="1700000002000" address="0599123456" msg_box="1"><parts/></mms>
              <sms protocol="0" address="" date="1700000003000" type="1" body="بلا عنوان" />
            </smses>"""
        val messages = SmsXml.parse(xml.byteInputStream())
        assertEquals(1, messages.size)
        assertEquals("مرحبا", messages[0].body)
        assertEquals(0, messages[0].subId.coerceAtLeast(0))
    }

    @Test
    fun recognisesXmlButNotJsonOrGzip() {
        fun looks(bytes: ByteArray) = SmsXml.looksLikeXml(BufferedInputStream(bytes.inputStream()))
        assertTrue(looks("<?xml version='1.0'?><smses/>".toByteArray()))
        assertTrue(looks(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "\n <smses/>".toByteArray()))
        assertFalse(looks("{\"format\":\"barq-backup\"}".toByteArray()))
        assertFalse(looks(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0)))
    }
}
