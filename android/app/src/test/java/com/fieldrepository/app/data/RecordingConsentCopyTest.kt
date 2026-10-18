package com.fieldrepository.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The consent switch's status line, word for word with the web's `recordingConsentStatus`. */
class RecordingConsentCopyTest {
    @Test
    fun notAskedYet() {
        assertEquals("Not asked yet", RecordingConsentCopy.status(RecordingConsentDto(), null))
    }

    @Test
    fun ownerAttestationIsNamed() {
        val consent = RecordingConsentDto(decision = "GRANTED", recorded = true, granted = true, source = "owner attestation")
        assertEquals("Agreed on 18 Oct 2026 · confirmed by the owner", RecordingConsentCopy.status(consent, "18 Oct 2026"))
    }

    @Test
    fun aDeclineIsSaidPlainly() {
        val consent = RecordingConsentDto(decision = "REFUSED", recorded = true, granted = false)
        assertEquals("Declined", RecordingConsentCopy.status(consent, null))
    }
}
