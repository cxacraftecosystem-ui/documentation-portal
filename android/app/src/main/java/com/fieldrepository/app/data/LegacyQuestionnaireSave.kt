package com.fieldrepository.app.data

/**
 * The legacy capture form's save, as state that survives a partial failure (H21).
 *
 * A save is a create (or update) followed by one upload per clip. When a clip fails after the create
 * succeeded, the form used to stay in create mode with every clip still pending, so Save again made
 * a second interview and uploaded every clip twice. Now:
 *  - once the create returns an id, every later save of this form UPDATES that id ([target]);
 *  - each clip leaves the pending map the moment it is linked ([withoutClip]), so a retry sends only
 *    the clips that failed.
 */
object LegacyQuestionnaireSave {

    sealed interface Target {
        data object Create : Target
        data class Update(val interviewId: String) : Target
    }

    /** What this save must do: update the interview being edited, or the one an earlier try created. */
    fun target(editingId: String?, createdId: String?): Target =
        (editingId ?: createdId)?.let { Target.Update(it) } ?: Target.Create

    /** [clips] without [clip] under [key]; a key left with no clips is dropped. */
    fun <T> withoutClip(clips: Map<String, List<T>>, key: String, clip: T): Map<String, List<T>> {
        val rest = (clips[key] ?: return clips).filterNot { it == clip }
        return if (rest.isEmpty()) clips - key else clips + (key to rest)
    }
}
