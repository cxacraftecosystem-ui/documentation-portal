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

    suspend fun login(email: String, password: String): UserDto {
        val response = api.login(LoginRequest(email = email.trim(), password = password))
        tokenStore.setToken(response.accessToken)
        return response.user
    }

    suspend fun loginWithGoogle(idToken: String): UserDto {
        val response = api.googleLogin(GoogleLoginRequest(googleIdToken = idToken))
        tokenStore.setToken(response.accessToken)
        return response.user
    }

    fun logout() {
        tokenStore.setToken(null)
    }

    suspend fun currentUser(): UserDto = api.me()

    suspend fun stats(): DashboardStats = api.dashboardStats()

    suspend fun artisans(): List<ArtisanDto> = api.artisans(pageSize = 50).items

    suspend fun crafts(): List<CraftDto> = api.crafts(pageSize = 100).items

    suspend fun createArtisan(body: ArtisanCreateRequest): ArtisanDto = api.createArtisan(body)

    suspend fun createCraft(body: CraftCreateRequest) {
        api.createCraft(body)
    }

    suspend fun createWorkshop(body: WorkshopCreateRequest) {
        api.createWorkshop(body)
    }

    suspend fun createProduct(body: ProductCreateRequest) {
        api.createProduct(body)
    }

    suspend fun createTool(body: ToolCreateRequest) {
        api.createTool(body)
    }

    suspend fun questionnaireQuestions(): List<QuestionnaireQuestionDto> = api.questionnaireQuestions()

    suspend fun createQuestionnaireInterview(body: QuestionnaireInterviewCreateRequest) {
        api.createQuestionnaireInterview(body)
    }

    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        linkedRecordType: String?,
        linkedRecordId: String?,
        caption: String?,
        location: LocationRequest?
    ): MediaFileDto {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val filename = displayName(context, uri) ?: "field-media-${System.currentTimeMillis()}"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to open selected media")
        }
        val mediaType = inferMediaType(mimeType)
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
                processingRequests = if (mediaType == "AUDIO") listOf("TRANSCRIPTION") else emptyList()
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
}

private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
