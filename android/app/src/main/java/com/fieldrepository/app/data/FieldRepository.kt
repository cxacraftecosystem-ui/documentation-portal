package com.fieldrepository.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.time.Instant

class FieldRepository(
    private val api: FieldRepositoryApi,
    private val tokenStore: TokenStore
) {
    private val storageClient = OkHttpClient()

    fun hasToken(): Boolean = !tokenStore.getToken().isNullOrBlank()

    /** Last known signed-in profile, used for instant, persistent login across resumes. */
    fun cachedUser(): UserDto? = tokenStore.getUser()

    suspend fun login(email: String, password: String): UserDto {
        val response = api.login(LoginRequest(email = email.trim(), password = password))
        tokenStore.setToken(response.accessToken)
        tokenStore.setUser(response.user)
        return response.user
    }

    suspend fun loginWithGoogle(idToken: String): UserDto {
        val response = api.googleLogin(GoogleLoginRequest(googleIdToken = idToken))
        tokenStore.setToken(response.accessToken)
        tokenStore.setUser(response.user)
        return response.user
    }

    fun logout() {
        tokenStore.clear()
    }

    suspend fun currentUser(): UserDto = api.me()

    /** Refresh the profile from the server and update the local cache. */
    suspend fun refreshUser(): UserDto {
        val user = api.me()
        tokenStore.setUser(user)
        return user
    }

    suspend fun stats(): DashboardStats = api.dashboardStats()

    suspend fun users(): List<UserDto> = api.users(pageSize = 100).items

    suspend fun updateUserQuestionnaireAccess(id: String, canManageQuestionnaire: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canManageQuestionnaire = canManageQuestionnaire))

    suspend fun artisans(): List<ArtisanDto> = api.artisans(pageSize = 100).items

    suspend fun crafts(): List<CraftDto> = api.crafts(pageSize = 100).items

    suspend fun createArtisan(body: ArtisanCreateRequest): ArtisanDto = api.createArtisan(body)

    suspend fun media(): List<MediaFileDto> = api.media(pageSize = 20).items

    suspend fun createCraft(body: CraftCreateRequest): CreatedRecordDto = api.createCraft(body)

    suspend fun createWorkshop(body: WorkshopCreateRequest): CreatedRecordDto = api.createWorkshop(body)

    suspend fun createProduct(body: ProductCreateRequest): CreatedRecordDto = api.createProduct(body)

    suspend fun createTool(body: ToolCreateRequest): CreatedRecordDto = api.createTool(body)

    suspend fun questionnaireQuestions(): List<QuestionnaireQuestionDto> = api.questionnaireQuestions()

    suspend fun questionnaireSections(): List<QuestionnaireSectionDto> = api.questionnaireSections()

    suspend fun createQuestionnaireSection(body: QuestionnaireSectionCreateRequest): QuestionnaireSectionDto =
        api.createQuestionnaireSection(body)

    suspend fun updateQuestionnaireSection(id: String, body: QuestionnaireSectionUpdateRequest): QuestionnaireSectionDto =
        api.updateQuestionnaireSection(id, body)

    suspend fun deleteQuestionnaireSection(id: String) {
        api.deleteQuestionnaireSection(id)
    }

    suspend fun reorderQuestionnaireSections(sectionIds: List<String>): List<QuestionnaireSectionDto> =
        api.reorderQuestionnaireSections(QuestionnaireSectionReorderRequest(sectionIds))

    suspend fun createQuestionnaireQuestion(body: QuestionnaireQuestionCreateRequest): QuestionnaireQuestionDto =
        api.createQuestionnaireQuestion(body)

    suspend fun updateQuestionnaireQuestion(id: String, body: QuestionnaireQuestionUpdateRequest): QuestionnaireQuestionDto =
        api.updateQuestionnaireQuestion(id, body)

    suspend fun deleteQuestionnaireQuestion(id: String) {
        api.deleteQuestionnaireQuestion(id)
    }

    suspend fun reorderQuestionnaireQuestions(sectionId: String, questionIds: List<String>): List<QuestionnaireSectionDto> =
        api.reorderQuestionnaireQuestions(QuestionnaireQuestionReorderRequest(sectionId, questionIds))

    suspend fun createQuestionnaireInterview(body: QuestionnaireInterviewCreateRequest): CreatedRecordDto =
        api.createQuestionnaireInterview(body)

    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        linkedRecordType: String?,
        linkedRecordId: String?,
        caption: String?,
        location: LocationRequest?,
        titleHint: String? = null,
        batchIndex: Int = 1,
        processingRequests: List<String>? = null
    ): MediaFileDto {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val originalName = displayName(context, uri) ?: "field-media-${System.currentTimeMillis()}"
        val mediaType = inferMediaType(mimeType)
        val resolvedProcessing = processingRequests
            ?: if (mediaType == "AUDIO") listOf("TRANSCRIPTION") else emptyList()
        val filename = mediaFilename(titleHint = titleHint ?: caption, originalName = originalName, mediaType = mediaType, batchIndex = batchIndex)
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to open selected media")
        }
        val presign = api.presignMedia(
            MediaPresignRequest(
                filename = filename,
                mimeType = mimeType,
                mediaType = mediaType,
                sizeBytes = bytes.size.toLong(),
                linkedRecordType = linkedRecordType.blankToNull(),
                linkedRecordId = linkedRecordId.blankToNull()
            )
        )
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
                .url(presign.uploadUrl)
                .put(bytes.toRequestBody(mimeType.toMediaType()))
            presign.headers.forEach { (name, value) -> requestBuilder.header(name, value) }
            storageClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Object storage upload failed: HTTP ${response.code}")
                }
            }
        }
        return api.completeMedia(
            MediaCompleteRequest(
                originalFilename = filename,
                mediaType = mediaType,
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong(),
                objectKey = presign.objectKey,
                bucket = presign.bucket,
                url = presign.publicUrl,
                caption = caption.blankToNull(),
                linkedRecordType = linkedRecordType.blankToNull(),
                linkedRecordId = linkedRecordId.blankToNull(),
                recordedAt = Instant.now().toString(),
                location = location,
                processingRequests = resolvedProcessing
            )
        )
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
        }
        return uri.lastPathSegment
    }

    private fun inferMediaType(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "IMAGE"
        mimeType.startsWith("video/") -> "VIDEO"
        mimeType.startsWith("audio/") -> "AUDIO"
        mimeType == "application/pdf" -> "PDF"
        else -> "DOCUMENT"
    }

    private fun mediaFilename(titleHint: String?, originalName: String, mediaType: String, batchIndex: Int): String {
        val extension = originalName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val base = titleHint.blankToNull()?.let(::safeFilePart) ?: safeFilePart(originalName.substringBeforeLast('.'))
        val type = mediaType.lowercase()
        val suffix = if (extension == null) "" else ".$extension"
        return "$base-$type-$batchIndex$suffix"
    }

    private fun safeFilePart(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(80)
            .ifBlank { "field-media" }
}

private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
