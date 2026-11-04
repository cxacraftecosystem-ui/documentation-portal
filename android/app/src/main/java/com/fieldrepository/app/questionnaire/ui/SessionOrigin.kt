package com.fieldrepository.app.questionnaire.ui

class SessionOrigin<R : Any>(private val home: R) {
    sealed interface Back<out R> {
        data object Host : Back<Nothing>
        data class To<R>(val route: R, val tab: QuestionnaireTab?) : Back<R>
    }

    private var from: R = home
    private var tab: QuestionnaireTab? = null
    private var toHost = false

    fun note(current: R, currentTab: QuestionnaireTab, inSession: Boolean) {
        if (inSession) return
        from = current
        tab = currentTab
        toHost = false
    }

    fun noteHost() { toHost = true }

    fun back(): Back<R> {
        val target: Back<R> = if (toHost) Back.Host else Back.To(from, tab)
        from = home
        tab = null
        toHost = false
        return target
    }
}
