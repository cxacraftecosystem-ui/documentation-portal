package com.fieldrepository.app.data

object LegacyQuestionnaireSave {

    sealed interface Target {
        data object Create : Target
        data class Update(val interviewId: String) : Target
    }

    fun target(editingId: String?, createdId: String?): Target =
        (editingId ?: createdId)?.let { Target.Update(it) } ?: Target.Create

    fun <T> withoutClip(clips: Map<String, List<T>>, key: String, clip: T): Map<String, List<T>> {
        val rest = (clips[key] ?: return clips).filterNot { it == clip }
        return if (rest.isEmpty()) clips - key else clips + (key to rest)
    }
}
