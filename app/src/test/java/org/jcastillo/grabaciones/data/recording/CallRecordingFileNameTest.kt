package org.jcastillo.grabaciones.data.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRecordingFileNameTest {
    @Test
    fun `filename contains sortable local timestamp and sanitized phone`() {
        val value = CallRecordingFileName.create("+1 (555) 123-4567", 1_700_000_000_123L)
        assertTrue(value.matches(Regex("\\d{8}_\\d{6}_\\d{3}_\\+15551234567")))
        assertFalse(value.contains('/'))
        assertFalse(value.contains('\\'))
    }

    @Test
    fun `anonymous number uses a safe placeholder`() {
        val value = CallRecordingFileName.create("", 1_700_000_000_000L)
        assertTrue(value.endsWith("_desconocido"))
    }

    @Test
    fun `filename does not include contact-controlled path characters`() {
        val value = CallRecordingFileName.create("../555/abc+123", 1_700_000_000_000L)
        assertTrue(value.endsWith("_555123"))
        assertFalse(value.contains(".."))
        assertFalse(value.contains('/'))
    }
}
