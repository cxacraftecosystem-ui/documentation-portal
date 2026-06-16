package com.fieldrepository.app.data

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface FieldRepositoryApi {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokenResponse

    @POST("auth/login")
    suspend fun googleLogin(@Body body: GoogleLoginRequest): TokenResponse

    @GET("me")
    suspend fun me(): UserDto

    @GET("dashboard/stats")
    suspend fun dashboardStats(): DashboardStats

    @GET("users")
    suspend fun users(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100
    ): PageResponse<UserDto>

    @PATCH("users/{id}")
    suspend fun updateUser(
        @Path("id") id: String,
        @Body body: UserUpdateRequest
    ): UserDto

    @GET("artisans")
    suspend fun artisans(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20
    ): PageResponse<ArtisanDto>

    @GET("crafts")
    suspend fun crafts(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100
    ): PageResponse<CraftDto>

    @POST("artisans")
    suspend fun createArtisan(@Body body: ArtisanCreateRequest): ArtisanDto

    @POST("crafts")
    suspend fun createCraft(@Body body: CraftCreateRequest): CreatedRecordDto

    @POST("media/presign")
    suspend fun presignMedia(@Body body: MediaPresignRequest): MediaPresignResponse

    @POST("media/complete")
    suspend fun completeMedia(@Body body: MediaCompleteRequest): MediaFileDto

    @GET("media")
    suspend fun media(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20
    ): PageResponse<MediaFileDto>

    @POST("workshops")
    suspend fun createWorkshop(@Body body: WorkshopCreateRequest): CreatedRecordDto

    @POST("products")
    suspend fun createProduct(@Body body: ProductCreateRequest): CreatedRecordDto

    @POST("tools")
    suspend fun createTool(@Body body: ToolCreateRequest): CreatedRecordDto

    @GET("questionnaire/questions")
    suspend fun questionnaireQuestions(): List<QuestionnaireQuestionDto>

    @GET("questionnaire/sections")
    suspend fun questionnaireSections(): List<QuestionnaireSectionDto>

    @POST("questionnaire/sections")
    suspend fun createQuestionnaireSection(@Body body: QuestionnaireSectionCreateRequest): QuestionnaireSectionDto

    @PATCH("questionnaire/sections/{id}")
    suspend fun updateQuestionnaireSection(
        @Path("id") id: String,
        @Body body: QuestionnaireSectionUpdateRequest
    ): QuestionnaireSectionDto

    @DELETE("questionnaire/sections/{id}")
    suspend fun deleteQuestionnaireSection(@Path("id") id: String)

    @POST("questionnaire/sections/reorder")
    suspend fun reorderQuestionnaireSections(@Body body: QuestionnaireSectionReorderRequest): List<QuestionnaireSectionDto>

    @POST("questionnaire/questions")
    suspend fun createQuestionnaireQuestion(@Body body: QuestionnaireQuestionCreateRequest): QuestionnaireQuestionDto

    @PATCH("questionnaire/questions/{id}")
    suspend fun updateQuestionnaireQuestion(
        @Path("id") id: String,
        @Body body: QuestionnaireQuestionUpdateRequest
    ): QuestionnaireQuestionDto

    @DELETE("questionnaire/questions/{id}")
    suspend fun deleteQuestionnaireQuestion(@Path("id") id: String)

    @POST("questionnaire/questions/reorder")
    suspend fun reorderQuestionnaireQuestions(@Body body: QuestionnaireQuestionReorderRequest): List<QuestionnaireSectionDto>

    @POST("questionnaire/interviews")
    suspend fun createQuestionnaireInterview(@Body body: QuestionnaireInterviewCreateRequest): CreatedRecordDto
}
