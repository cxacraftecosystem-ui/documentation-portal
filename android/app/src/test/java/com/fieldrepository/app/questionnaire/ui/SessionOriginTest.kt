package com.fieldrepository.app.questionnaire.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionOriginTest {
    @Test fun `back returns to the list and tab the session was opened from, then forgets it`() {
        val o = SessionOrigin("list")
        o.note("recordings", QuestionnaireTab.SESSIONS, inSession = false)
        o.note("session-b", QuestionnaireTab.SESSIONS, inSession = true)
        assertEquals(SessionOrigin.Back.To("recordings", QuestionnaireTab.SESSIONS), o.back())
        assertEquals(SessionOrigin.Back.To("list", null), o.back())
    }

    @Test fun `a session another screen opened goes back to that screen, once`() {
        val o = SessionOrigin("list")
        o.note("list", QuestionnaireTab.FORMS, inSession = false)
        o.noteHost()
        assertEquals(SessionOrigin.Back.Host, o.back())
        o.note("list", QuestionnaireTab.SESSIONS, inSession = false)
        assertEquals(SessionOrigin.Back.To("list", QuestionnaireTab.SESSIONS), o.back())
    }
}
