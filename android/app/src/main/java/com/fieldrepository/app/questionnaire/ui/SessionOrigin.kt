package com.fieldrepository.app.questionnaire.ui

/**
 * WHERE BACK FROM A SESSION GOES (owner, 1 Nov 2026): the list it was opened from, on its tab, or,
 * when the session was opened from another screen of the app (a form's own sessions on the form
 * detail), back to that screen through the host.
 *
 * [R] is the home's own route type; [home] is its list route, where Back lands with nothing noted.
 */
class SessionOrigin<R : Any>(private val home: R) {
    sealed interface Back<out R> {
        /** The host's screen the session was opened from: the host navigates there. */
        data object Host : Back<Nothing>
        data class To<R>(val route: R, val tab: QuestionnaireTab?) : Back<R>
    }

    private var from: R = home
    private var tab: QuestionnaireTab? = null
    private var toHost = false

    /** A session is about to open from [current] on [currentTab]; a session opening a session changes nothing. */
    fun note(current: R, currentTab: QuestionnaireTab, inSession: Boolean) {
        if (inSession) return
        from = current
        tab = currentTab
        toHost = false
    }

    /** The session about to open came from a screen outside the home that wants it back. */
    fun noteHost() { toHost = true }

    /** Where Back goes now; the origin is then forgotten. */
    fun back(): Back<R> {
        val target: Back<R> = if (toHost) Back.Host else Back.To(from, tab)
        from = home
        tab = null
        toHost = false
        return target
    }
}
