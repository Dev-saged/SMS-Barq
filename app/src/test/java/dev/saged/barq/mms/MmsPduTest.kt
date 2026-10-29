package dev.saged.barq.mms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class MmsPduTest {

    private fun ByteArrayOutputStream.text(s: String) { write(s.toByteArray()); write(0) }
    private fun ByteArrayOutputStream.uintvar(v: Int) {
        // up to 2 bytes is enough for the test payloads
        if (v > 0x7F) write(0x80 or (v shr 7))
        write(v and 0x7F)
    }

    @Test
    fun parsesNotificationInd() {
        val pdu = ByteArrayOutputStream().apply {
            write(0x8C); write(0x82)                       // message type: notification-ind
            write(0x98); text("T123")                      // transaction id
            write(0x8D); write(0x92)                       // version 1.2
            val from = "+970599111222/TYPE=PLMN"; write(0x89); write(from.length + 2); write(0x80); text(from) // from
            write(0x8A); write(0x80)                       // message class personal
            write(0x8E); write(0x02); write(0x10); write(0x00) // size 4096
            write(0x88); write(0x05); write(0x81); write(0x03); write(0x02); write(0xA3); write(0x00) // expiry
            write(0x83); text("http://mmsc.example/abc")   // content location
        }.toByteArray()

        val n = MmsPdu.parseNotification(pdu)
        assertNotNull(n)
        assertEquals("T123", n!!.transactionId)
        assertEquals("http://mmsc.example/abc", n.contentLocation)
        assertEquals("+970599111222", n.from)
        assertEquals(4096L, n.size)
        assertEquals(0x12, n.version)
    }

    @Test
    fun rejectsOtherTypes() {
        assertNull(MmsPdu.parseNotification(byteArrayOf(0x8C.toByte(), 0x84.toByte())))
        assertNull(MmsPdu.parseNotification(null))
    }

    @Test
    fun parsesRetrieveConfMultipart() {
        val textBytes = "مرحبا".toByteArray(Charsets.UTF_8)
        val image = byteArrayOf(1, 2, 3, 4, 5)
        val pdu = ByteArrayOutputStream().apply {
            write(0x8C); write(0x84)                       // retrieve-conf
            write(0x98); text("T9")
            write(0x8D); write(0x92)
            write(0x85); write(0x04); write(0x5F); write(0x5E); write(0x10); write(0x00) // date
            val from = "+97059911"; write(0x89); write(from.length + 2); write(0x80); text(from)
            write(0x84); write(0xA3)                       // content-type multipart.mixed
            uintvar(2)                                     // 2 parts
            // part 1: text/plain; charset=utf-8
            val h1 = byteArrayOf(0x03, 0x83.toByte(), 0x81.toByte(), 0xEA.toByte())
            uintvar(h1.size); uintvar(textBytes.size); write(h1); write(textBytes)
            // part 2: image/jpeg + Content-Location
            val h2 = ByteArrayOutputStream().apply { write(0x9E); write(0x8E); text("pic.jpg") }.toByteArray()
            uintvar(h2.size); uintvar(image.size); write(h2); write(image)
        }.toByteArray()

        val r = MmsPdu.parseRetrieveConf(pdu)
        assertNotNull(r)
        assertEquals("+97059911", r!!.from)
        assertEquals(2, r.parts.size)
        assertEquals("text/plain", r.parts[0].contentType)
        assertEquals("مرحبا", r.parts[0].text())
        assertEquals("image/jpeg", r.parts[1].contentType)
        assertEquals("pic.jpg", r.parts[1].name)
        assertArrayEquals(image, r.parts[1].data)
    }

    @Test
    fun encodesNotifyResp() {
        val bytes = MmsPdu.encodeNotifyResp("T1", 0x12)
        val expected = byteArrayOf(0x8C.toByte(), 0x83.toByte(), 0x98.toByte(), 'T'.code.toByte(), '1'.code.toByte(), 0, 0x8D.toByte(), 0x92.toByte(), 0x95.toByte(), 0x81.toByte())
        assertArrayEquals(expected, bytes)
    }
}
