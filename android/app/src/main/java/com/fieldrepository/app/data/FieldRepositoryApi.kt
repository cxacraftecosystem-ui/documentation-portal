package com.fieldrepository.app.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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

    @POST("workshops")
    suspend fun createWorkshop(@Body body: WorkshopCreateRequest): CreatedRecordDto

    @POST("products")
    suspend fun createProduct(@Body body: ProductCreateRequest): CreatedRecordDto

    @POST("tools")
    suspend fun createTool(@Body body: ToolCreateRequest): CreatedRecordDto

    @GET("questionnaire/questions")
    suspend fun questionnaireQuestions(): List<QuestionnaireQuestionDto>

    @POST("questionnaire/interviews")
    suspend fun createQuestionnaireInterview(@Body body: QuestionnaireInterviewCreateRequest): CreatedRecordDto
}
