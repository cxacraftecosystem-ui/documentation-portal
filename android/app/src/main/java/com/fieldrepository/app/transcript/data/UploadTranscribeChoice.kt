package com.fieldrepository.app.transcript.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable

@Serializable
data class UploadPolicy(
    val autoTranscribeVideo: Boolean = false,
    val autoTranscribeAudio: Boolean = false,
)

object UploadTranscribe {
    const val TRANSCRIPTION = "TRANSCRIPTION"
    const val AUDIO = "AUDIO"
    const val VIDEO = "VIDEO"

    fun kindOf(typeOrMime: String?): String? {
        val value = typeOrMime?.trim()?.lowercase() ?: return null
        return when {
            value == "audio" || value.startsWith("audio/") -> AUDIO
            value == "video" || value.startsWith("video/") -> VIDEO
            else -> null
        }
    }

    fun eligible(typeOrMime: String?): Boolean = kindOf(typeOrMime) != null

    fun locked(typeOrMime: String?, policy: UploadPolicy?): Boolean = when (kindOf(typeOrMime)) {
        VIDEO -> policy?.autoTranscribeVideo == true
        AUDIO -> policy?.autoTranscribeAudio == true
        else -> false
    }

    fun wanted(typeOrMime: String?, chosen: Boolean, policy: UploadPolicy? = null): Boolean =
        eligible(typeOrMime) && (chosen || locked(typeOrMime, policy))

    fun processing(typeOrMime: String?, chosen: Boolean, policy: UploadPolicy? = null): List<String> =
        if (wanted(typeOrMime, chosen, policy)) listOf(TRANSCRIPTION) else emptyList()

    fun legacy(typeOrMime: String?): List<String> =
        if (kindOf(typeOrMime) == AUDIO) listOf(TRANSCRIPTION) else emptyList()

    fun processingFor(choice: Boolean?, typeOrMime: String?): List<String> =
        if (choice == null) legacy(typeOrMime) else processing(typeOrMime, choice)

    fun note(kinds: Collection<String?>, policy: UploadPolicy?): String? {
        val video = policy?.autoTranscribeVideo == true && kinds.any { kindOf(it) == VIDEO }
        val audio = policy?.autoTranscribeAudio == true && kinds.any { kindOf(it) == AUDIO }
        return when {
            video && audio -> TranscriptStrings.uploadAutoBoth
            video -> TranscriptStrings.uploadAutoVideo
            audio -> TranscriptStrings.uploadAutoAudio
            else -> null
        }
    }
}

object TranscribeChoices {
    private val chosen = mutableStateMapOf<String, Boolean>()

    fun isOn(key: String): Boolean = chosen[key] == true

    fun set(key: String, on: Boolean) {
        chosen[key] = on
    }

    fun setAll(keys: Collection<String>, on: Boolean) {
        keys.forEach { chosen[it] = on }
    }

    fun forget(key: String) {
        chosen.remove(key)
    }

    fun clear() = chosen.clear()

    fun processingFor(key: String, typeOrMime: String?): List<String> =
        UploadTranscribe.processing(typeOrMime, isOn(key))
}

object UploadPolicyCache {
    var current by mutableStateOf<UploadPolicy?>(null)

    suspend fun refresh(backend: TranscriptBackend): UploadPolicy? {
        val fresh = runCatching { backend.uploadPolicy() }.getOrNull()
        if (fresh != null) current = fresh
        return current
    }
}
