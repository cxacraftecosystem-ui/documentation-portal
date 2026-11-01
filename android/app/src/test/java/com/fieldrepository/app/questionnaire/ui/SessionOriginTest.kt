package com.fieldrepository.app.questionnaire.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 0.0.6: Back from a session lands where it was opened: a list of the home, or the host's screen. */
class SessionOriginTest {
    @Test fun `back returns to the list and tab the session was opened from, then forgets it`() {
        val o = SessionOrigin("list")
        o.note("recordings", QuestionnaireTab.SESSIONS, inSession = false)
        // A session opened from inside a session does not move the origin.
        o.note("session-b", QuestionnaireTab.SESSIONS, inSession = true)
        assertEquals(SessionOrigin.Back.To("recordings", QuestionnaireTab.SESSIONS), o.back())
        assertEquals(SessionOrigin.Back.To("list", null), o.back())
    }

    @Test fun `a session another screen opened goes back to that screen, once`() {
        val o = SessionOrigin("list")
        o.note("list", QuestionnaireTab.FORMS, inSession = false)
        o.noteHost()
        assertEquals(SessionOrigin.Back.Host, o.back())
        // The next session opened from the home's own list returns to the list, not the host.
        o.note("list", QuestionnaireTab.SESSIONS, inSession = false)
        assertEquals(SessionOrigin.Back.To("list", QuestionnaireTab.SESSIONS), o.back())
    }
}
