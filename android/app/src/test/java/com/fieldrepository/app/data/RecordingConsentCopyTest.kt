package com.fieldrepository.app.data

import com.fieldrepository.app.questionnaire.data.QConsentGet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The consent switch's status line, word for word with the web's `recordingConsentStatus`. */
class RecordingConsentCopyTest {
    @Test
    fun onByDefault() {
        assertEquals("On by default", RecordingConsentCopy.status(QConsentGet(), null))
        assertTrue("no answer on record: the switch is on", RecordingConsentCopy.granted(QConsentGet()))
        assertFalse("a refusal turns it off", RecordingConsentCopy.granted(QConsentGet(state = "REFUSED")))
    }

    @Test
    fun ownerAttestationIsNamed() {
        val consent = QConsentGet(state = "GRANTED", source = "owner attestation")
        assertEquals("Agreed on 18 Oct 2026 · confirmed by the owner", RecordingConsentCopy.status(consent, "18 Oct 2026"))
    }

    @Test
    fun aDeclineIsSaidPlainly() {
        val consent = QConsentGet(state = "REFUSED")
        assertEquals("Declined", RecordingConsentCopy.status(consent, null))
    }
}
