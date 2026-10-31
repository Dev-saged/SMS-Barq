package dev.saged.barq.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun comparesVersionsByNumberNotText() {
        assertTrue(UpdateChecker.isNewer("1.5.10", "1.5.9"))
        assertTrue(UpdateChecker.isNewer("v1.6.0", "1.5.3"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewer("1.5.3", "1.5.3"))
        assertFalse(UpdateChecker.isNewer("1.6", "1.6.0"))
        assertFalse(UpdateChecker.isNewer("1.5.2", "1.5.3"))
        assertFalse(UpdateChecker.isNewer("latest-build", "1.5.3"))
    }

    @Test
    fun notesBecomePlainLines() {
        val md = "## برق 1.5.3 — عنوان\n\n- **ميزة** أولى\n- ثانية `كود`\n\n> للرجوع لنسخة أقدم: احذف برق"
        assertEquals("• ميزة أولى\n• ثانية كود", UpdateChecker.cleanNotes(md))
    }
}
