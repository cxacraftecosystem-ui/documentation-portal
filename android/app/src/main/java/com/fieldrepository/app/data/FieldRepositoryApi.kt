package com.fieldrepository.app.data

import com.fieldrepository.app.ui.ConsolidatedQuestionnaireDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Streaming
import retrofit2.http.Url
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface FieldRepositoryApi {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): SignInResponse

    @POST("auth/login")
    suspend fun googleLogin(@Body body: GoogleLoginRequest): SignInResponse

    @POST("auth/login")
    suspend fun oidcLogin(@Body body: OidcLoginRequest): SignInResponse

    @POST("auth/two-factor/verify")
    suspend fun verifyTwoFactor(@Body body: TwoFactorVerifyRequest): TokenResponse

    @GET("auth/two-factor")
    suspend fun twoFactorStatus(): TwoFactorStatusDto

    @POST("auth/two-factor/setup")
    suspend fun beginTwoFactorSetup(): TwoFactorSetupDto

    @POST("auth/two-factor/setup/cancel")
    suspend fun cancelTwoFactorSetup(): Response<Unit>

    @POST("auth/two-factor/enable")
    suspend fun enableTwoFactor(@Body body: TwoFactorCodeRequest): RecoveryCodesDto

    @POST("auth/two-factor/disable")
    suspend fun disableTwoFactor(@Body body: TwoFactorCodeRequest): Response<Unit>

    @POST("auth/two-factor/recovery-codes")
    suspend fun makeNewRecoveryCodes(@Body body: TwoFactorCodeRequest): RecoveryCodesDto

    @DELETE("users/{id}/two-factor")
    suspend fun resetTwoFactorForUser(@Path("id") id: String): UserDto

    @POST("auth/change-password")
    suspend fun changeOwnPassword(@Body body: ChangePasswordRequest): Response<ChangePasswordResponse>

    @POST("auth/password-links")
    suspend fun issuePasswordLink(@Body body: IssuePasswordLinkRequest): IssuedPasswordLinkDto

    @POST("auth/password-links/{id}/revoke")
    suspend fun revokePasswordLink(@Path("id") id: String): Map<String, Boolean>

    @POST("auth/set-password/check")
    suspend fun checkPasswordLinkInBody(@Body body: PasswordLinkCheckRequest): PasswordLinkCheckDto

    @POST("auth/set-password")
    suspend fun setPasswordWithLink(@Body body: SetPasswordRequest): Map<String, Boolean>

    @GET("me")
    suspend fun me(): UserDto

    @GET("reference/address")
    suspend fun addressReference(): AddressReferenceDto

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

    @GET("users/directory")
    suspend fun userDirectory(): List<UserDto>

    @GET("access-roster")
    suspend fun accessRoster(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
        @Query("search") search: String? = null,
        @Query("status") status: String? = null
    ): PageResponse<AccessRosterDto>

    @GET("access-roster/pending-count")
    suspend fun accessRosterPendingCount(): AccessRosterPendingCountDto

    @GET("notifications")
    suspend fun notifications(@Query("limit") limit: Int): NotificationPageDto

    @POST("notifications/read")
    suspend fun markNotificationsRead(@Body body: NotificationReadBody): NotificationReadResultDto

    @GET("notifications/digest")
    suspend fun weeklyDigestPreference(): WeeklyDigestPreferenceDto

    @PUT("notifications/digest")
    suspend fun updateWeeklyDigestPreference(@Body body: WeeklyDigestPreferenceBody): WeeklyDigestPreferenceDto

    @POST("access-roster")
    suspend fun addToAccessRoster(@Body body: AccessRosterCreateBody): AccessRosterDto

    @PATCH("access-roster/{id}")
    suspend fun updateAccessRosterEntry(
        @Path("id") id: String,
        @Body body: AccessRosterUpdateBody
    ): AccessRosterDto

    @DELETE("access-roster/{id}")
    suspend fun suspendAccessRosterEntry(@Path("id") id: String): AccessRosterDto

    @GET("review/pending")
    suspend fun pendingReviews(
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null,
        @Query("workshopId") workshopId: String? = null
    ): PendingReviewListDto

    @GET("review/reasons")
    suspend fun reviewReasons(): List<ReviewReasonDto>

    @POST("app/release")
    suspend fun publishAppRelease(@Body body: AppReleasePublishRequest): AppReleaseDto

    @GET("app/release/latest")
    suspend fun latestAppRelease(): AppReleaseDto

    @GET("settings")
    suspend fun appSettings(): AppSettingDto

    @PUT("settings")
    suspend fun updateAppSettings(@Body body: AppSettingUpdateRequest): AppSettingDto

    @GET("feedback/me")
    suspend fun myFeedback(): FeedbackDto

    @PUT("feedback/me")
    suspend fun upsertMyFeedback(@Body body: FeedbackUpsertRequest): FeedbackDto

    @GET("feedback")
    suspend fun allFeedback(): List<FeedbackDto>

    @GET("review/{type}/{id}/latest")
    suspend fun latestReview(@Path("type") type: String, @Path("id") id: String): LatestReviewAnswerDto

    @GET("review/mine")
    suspend fun myReturnedRecords(): ReturnedListDto

    @POST("review/bulk-approve")
    suspend fun bulkApprove(@Body body: BulkApproveRequest): BulkApproveResponse

    @POST("review/{type}/{id}/approve")
    suspend fun approveRecord(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: ReviewActionRequest,
        @Header("Idempotency-Key") idempotencyKey: String? = null
    ): JsonElement

    @POST("review/{type}/{id}/reject")
    suspend fun rejectRecord(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: ReviewActionRequest,
        @Header("Idempotency-Key") idempotencyKey: String? = null
    ): JsonElement

    @GET("review/{type}/{id}/media")
    suspend fun recordMediaReview(@Path("type") type: String, @Path("id") id: String): RecordMediaReviewDto

    @POST("review/{type}/{id}/revise")
    suspend fun reviseRecord(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: ReviewActionRequest
    ): JsonElement

    @POST("review/{type}/{id}/edit")
    suspend fun editReviewedRecord(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: ReviewEditRequest
    ): JsonElement

    @GET("artisan-cards/status")
    suspend fun artisanCardStatus(): ArtisanCardStatusDto

    @POST("artisan-cards/resolve")
    suspend fun resolveArtisanCard(@Body body: ResolveArtisanCardRequest): ResolveArtisanCardDto

    @GET("artisans")
    suspend fun artisansFromCache(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Header("Cache-Control") cacheControl: String = "only-if-cached, max-stale=2147483647"
    ): PageResponse<ArtisanDto>

    @GET("artisans")
    suspend fun artisans(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        @Query("workshopIds") workshopIds: String? = null,
        @Query("craftId") craftId: String? = null,
        @Query("craftIds") craftIds: String? = null,
        @Query("workshopId") workshopId: String? = null,
        @Query("search") search: String? = null,
        @Query("createdBy") createdBy: String? = null,
        @Query("completeness") completeness: String? = null
    ): PageResponse<ArtisanDto>

    @GET("records/completeness-rules")
    suspend fun completenessRules(): ResponseBody

    @GET("crafts/catalogue")
    suspend fun craftCatalogue(
        @Header("If-None-Match") ifNoneMatch: String? = null,
        @Query("includeInactive") includeInactive: Boolean? = null,
    ): Response<ResponseBody>

    @GET("crafts")
    suspend fun crafts(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("workshopId") workshopId: String? = null,
        @Query("search") search: String? = null,
        @Query("createdBy") createdBy: String? = null
    ): PageResponse<CraftDto>

    @POST("artisans")
    suspend fun createArtisan(@Header("Idempotency-Key") idempotencyKey: String?, @Body body: ArtisanCreateRequest): ArtisanDto

    @POST("artisan-duplicates/check")
    suspend fun checkDuplicates(@Body body: DuplicateCheckRequest): DuplicateCheckResponse

    @POST("artisans/lookup/aadhaar")
    suspend fun lookupArtisanByAadhaar(@Body body: AadhaarLookupRequest): AadhaarLookupDto

    @GET("artisans/{id}")
    suspend fun artisan(@Path("id") id: String): ArtisanDetailDto

    @PATCH("artisans/{id}")
    suspend fun updateArtisan(@Path("id") id: String, @Body body: ArtisanCreateRequest): ArtisanDetailDto

    @GET("artisans/{id}/questionnaire")
    suspend fun artisanQuestionnaire(@Path("id") id: String): ArtisanQuestionnaireDto

    @POST("crafts")
    suspend fun createCraft(@Header("Idempotency-Key") idempotencyKey: String?, @Body body: CraftCreateRequest): CreatedRecordDto

    @GET("crafts/{id}")
    suspend fun craft(@Path("id") id: String): CraftDto

    @PATCH("crafts/{id}")
    suspend fun updateCraft(@Path("id") id: String, @Body body: CraftCreateRequest): CraftDto

    @GET("products")
    suspend fun products(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("artisanId") artisanId: String? = null,
        @Query("artisanName") artisanName: String? = null,
        @Query("workshopId") workshopId: String? = null,
        @Query("search") search: String? = null,
        @Query("createdBy") createdBy: String? = null,
        @Query("completeness") completeness: String? = null
    ): PageResponse<ProductDetailDto>

    @GET("products/{id}")
    suspend fun product(@Path("id") id: String): ProductDetailDto

    @PATCH("products/{id}")
    suspend fun updateProduct(@Path("id") id: String, @Body body: ProductCreateRequest): ProductDetailDto

    @GET("tools")
    suspend fun tools(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("workshopId") workshopId: String? = null,
        @Query("search") search: String? = null,
        @Query("createdBy") createdBy: String? = null,
        @Query("completeness") completeness: String? = null
    ): PageResponse<ToolDetailDto>

    @GET("tools/{id}")
    suspend fun tool(@Path("id") id: String): ToolDetailDto

    @PATCH("tools/{id}")
    suspend fun updateTool(@Path("id") id: String, @Body body: ToolCreateRequest): ToolDetailDto

    @GET("tools/{id}/artisans")
    suspend fun toolArtisans(@Path("id") id: String): List<ArtisanDto>

    @POST("tools/{id}/artisans")
    suspend fun assignToolArtisans(@Path("id") id: String, @Body body: ToolArtisanAssignRequest): List<ArtisanDto>

    @DELETE("tools/{id}/artisans/{artisanId}")
    suspend fun unassignToolArtisan(@Path("id") id: String, @Path("artisanId") artisanId: String)

    @GET("workshops")
    suspend fun workshops(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("createdBy") createdBy: String? = null,
        @Query("search") search: String? = null,
        @Query("completeness") completeness: String? = null
    ): PageResponse<WorkshopDetailDto>

    @GET("workshops/{id}")
    suspend fun workshop(@Path("id") id: String): WorkshopDetailDto

    @PATCH("workshops/{id}")
    suspend fun updateWorkshop(@Path("id") id: String, @Body body: WorkshopCreateRequest): WorkshopDetailDto

    @GET("workshops/{id}/submission-check")
    suspend fun workshopSubmissionCheck(@Path("id") id: String): WorkshopSubmissionCheckDto

    @GET("workshops/unmapped")
    suspend fun unmappedRecords(): WorkshopMappingPlanDto

    @POST("workshops/unmapped/map")
    suspend fun mapUnmappedRecords(): WorkshopMappingPlanDto

    @PUT("workshops/unmapped/auto")
    suspend fun setUnmappedAuto(@Body body: WorkshopAutoMapRequest): WorkshopAutoMapDto

    @Multipart
    @POST("media/analyze-measurement")
    suspend fun analyzeMeasurement(
        @Part file: okhttp3.MultipartBody.Part,
        @Query("dimension") dimension: String? = null
    ): AnalyzeMeasurementResponse

    @POST("media/presign")
    suspend fun presignMedia(@Body body: MediaPresignRequest): MediaPresignResponse

    @POST("media/multipart/create")
    suspend fun createMultipart(@Body body: MultipartCreateRequest): MultipartCreateResponse

    @POST("media/multipart/presign-parts")
    suspend fun presignMultipartParts(@Body body: MultipartPresignPartsRequest): MultipartPresignPartsResponse

    @POST("media/multipart/complete")
    suspend fun completeMultipart(@Body body: MultipartCompleteRequest): MultipartCompleteResponse

    @POST("media/multipart/abort")
    suspend fun abortMultipart(@Body body: MultipartAbortRequest): JsonElement

    @POST("media/complete")
    suspend fun completeMedia(@Body body: MediaCompleteRequest): MediaFileDto

    @POST("media/complete")
    suspend fun completeMediaChecksummed(@Body body: JsonObject): MediaFileDto

    @DELETE("media/object")
    suspend fun deleteMediaObject(@Query("objectKey") objectKey: String)

    @DELETE("media/{id}")
    suspend fun deleteMedia(@Path("id") id: String)

    @GET("media/{id}")
    suspend fun getMedia(@Path("id") id: String): MediaFileDto

    @GET("media")
    suspend fun media(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        @Query("linkedRecordType") linkedRecordType: String? = null,
        @Query("linkedRecordId") linkedRecordId: String? = null,
        @Query("search") search: String? = null,
        @QueryMap(encoded = true) filters: Map<String, String> = emptyMap()
    ): PageResponse<MediaFileDto>

    @GET("media/orphans")
    suspend fun orphanMedia(): List<MediaFileDto>

    @POST("media/{id}/relink")
    suspend fun relinkMedia(@Path("id") id: String, @Body body: MediaRelinkRequest): MediaFileDto

    @POST("media/{id}/refine-transcript")
    suspend fun refineTranscript(@Path("id") id: String, @Body body: TranscriptRefineRequest): TranscriptRefineResponse

    @POST("media/{id}/transcribe-now")
    suspend fun transcribeNow(@Path("id") id: String): MediaFileDto

    @GET("media/{id}/insights")
    suspend fun transcriptInsight(@Path("id") id: String): TranscriptInsightViewDto

    @GET("media/insights")
    suspend fun transcriptInsights(@Query("ids") ids: String): TranscriptInsightBatchDto

    @PATCH("media/{id}/insights")
    suspend fun patchTranscriptInsight(@Path("id") id: String, @Body body: TranscriptInsightPatchRequest): TranscriptInsightViewDto

    @POST("media/{id}/insights/regenerate")
    suspend fun regenerateTranscriptInsight(@Path("id") id: String, @Body body: TranscriptInsightRegenerateRequest): TranscriptInsightViewDto

    @POST("media/{id}/transcript")
    suspend fun setTranscript(@Path("id") id: String, @Body body: TranscriptUpdateRequest): MediaFileDto

    @Streaming
    @GET("export/dataset")
    suspend fun datasetManifestStream(@Query("stream") stream: Int = 1): Response<ResponseBody>

    @GET("export/dataset")
    suspend fun datasetManifest(): DatasetManifestDto

    @Streaming
    @GET("data/report")
    suspend fun dataReport(
        @Query("format") format: String = "xlsx",
        @Query("path") path: String = "",
        @Query("status") status: String? = null
    ): Response<ResponseBody>

    @DELETE("artisans/{id}")
    suspend fun deleteArtisan(@Path("id") id: String)

    @DELETE("crafts/{id}")
    suspend fun deleteCraft(@Path("id") id: String)

    @DELETE("products/{id}")
    suspend fun deleteProduct(@Path("id") id: String)

    @DELETE("tools/{id}")
    suspend fun deleteTool(@Path("id") id: String)

    @DELETE("workshops/{id}")
    suspend fun deleteWorkshop(@Path("id") id: String)

    @DELETE("processes/{id}")
    suspend fun deleteProcess(@Path("id") id: String)

    @DELETE("questionnaire/interviews/{id}")
    suspend fun deleteInterview(@Path("id") id: String)

    @GET("processes")
    suspend fun processes(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("productId") productId: String? = null,
        @Query("workshopId") workshopId: String? = null,
        @Query("search") search: String? = null,
        @Query("createdBy") createdBy: String? = null,
        @Query("craftId") craftId: String? = null
    ): PageResponse<ProcessDetailDto>

    @GET("processes/{id}")
    suspend fun process(@Path("id") id: String): ProcessDetailDto

    @POST("processes")
    suspend fun createProcess(@Header("Idempotency-Key") idempotencyKey: String?, @Body body: ProcessCreateRequest): ProcessDetailDto

    @PATCH("processes/{id}")
    suspend fun updateProcess(@Path("id") id: String, @Body body: ProcessCreateRequest): ProcessDetailDto

    @POST("workshops")
    suspend fun createWorkshop(@Header("Idempotency-Key") idempotencyKey: String?, @Body body: WorkshopCreateRequest): CreatedRecordDto

    @POST("products")
    suspend fun createProduct(@Header("Idempotency-Key") idempotencyKey: String?, @Body body: ProductCreateRequest): CreatedRecordDto

    @POST("tools")
    suspend fun createTool(@Header("Idempotency-Key") idempotencyKey: String?, @Body body: ToolCreateRequest): CreatedRecordDto

    @GET("questionnaires")
    suspend fun questionnaires(@Query("activeOnly") activeOnly: Boolean = true): List<QuestionnaireDto>

    @GET("questionnaire/questions")
    suspend fun questionnaireQuestions(
        @Query("questionnaireId") questionnaireId: String? = null
    ): List<QuestionnaireQuestionDto>

    @GET("questionnaire/sections")
    suspend fun questionnaireSections(
        @Query("questionnaireId") questionnaireId: String? = null
    ): List<QuestionnaireSectionDto>

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
    suspend fun createQuestionnaireInterview(@Header("Idempotency-Key") idempotencyKey: String?, @Body body: QuestionnaireInterviewCreateRequest): CreatedRecordDto

    @GET("questionnaire/interviews")
    suspend fun interviews(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("workshopIds") workshopIds: String? = null,
        @Query("workshopId") workshopId: String? = null,
        @Query("artisanId") artisanId: String? = null,
        @Query("createdBy") createdBy: String? = null,
        @Query("search") search: String? = null,
        @Query("completeness") completeness: String? = null
    ): PageResponse<QuestionnaireInterviewDetailDto>

    @GET("questionnaire/interviews/{id}")
    suspend fun interview(@Path("id") id: String): QuestionnaireInterviewDetailDto

    @PATCH("questionnaire/interviews/{id}")
    suspend fun updateInterview(
        @Path("id") id: String,
        @Body body: QuestionnaireInterviewUpdateRequest
    ): QuestionnaireInterviewDetailDto

    @POST("questionnaire/interviews/{id}/merge-into/{targetId}")
    suspend fun mergeInterviewInto(
        @Path("id") id: String,
        @Path("targetId") targetId: String
    ): QuestionnaireInterviewDetailDto

    @GET("questionnaire/completion")
    suspend fun completionMatrix(
        @Query("artisanId") artisanId: String? = null,
        @Query("workshopIds") workshopIds: String? = null,
        @Query("questionnaireId") questionnaireId: String? = null
    ): CompletionMatrixDto

    @PUT("questionnaire/completion")
    suspend fun setCompletionCell(@Body body: CompletionCellRequest): JsonElement

    @GET("questionnaire/artisans/{id}/consolidated")
    suspend fun consolidatedQuestionnaire(
        @Path("id") artisanId: String,
        @Query("workshopIds") workshopIds: String? = null,
        @Query("questionnaireId") questionnaireId: String? = null
    ): ConsolidatedQuestionnaireDto

    @GET("data-access/tiers")
    suspend fun dataAccessTiers(): List<DataAccessTierInfo>

    @GET("data-access/grants")
    suspend fun dataAccessGrants(): MyGrantsDto

    @POST("data-access/requests")
    suspend fun requestDataAccess(@Body body: DataAccessRequestBody): DataAccessGrantDto

    @POST("data-access/grants")
    suspend fun grantDataAccess(@Body body: DataAccessGrantBody): DataAccessGrantDto

    @POST("data-access/grants/{id}/decide")
    suspend fun decideDataAccess(@Path("id") id: String, @Body body: DataAccessDecisionBody): DataAccessGrantDto

    @POST("data-access/grants/{id}/revoke")
    suspend fun revokeDataAccess(@Path("id") id: String): DataAccessGrantDto

    @DELETE("data-access/grants/{id}")
    suspend fun deleteDataAccess(@Path("id") id: String)

    @GET("data-access/comments")
    suspend fun entryComments(
        @Query("recordType") recordType: String,
        @Query("recordId") recordId: String
    ): List<EntryCommentDto>

    @POST("data-access/comments")
    suspend fun addEntryComment(@Header("Idempotency-Key") idempotencyKey: String?, @Body body: EntryCommentBody): EntryCommentDto

    @DELETE("data-access/comments/{id}")
    suspend fun deleteEntryComment(@Path("id") id: String)

    @PATCH("data-access/comments/{id}")
    suspend fun updateEntryComment(@Path("id") id: String, @Body body: EntryCommentUpdateBody): EntryCommentDto

    @GET("data-access/revisions")
    suspend fun recordRevisions(
        @Query("recordType") recordType: String,
        @Query("recordId") recordId: String
    ): List<RecordRevisionDto>

    @GET("workshops/{id}/assignments")
    suspend fun workshopAssignments(@Path("id") id: String): List<WorkshopAssignmentDto>

    @PUT("workshops/{id}/assignments")
    suspend fun setWorkshopAssignments(
        @Path("id") id: String,
        @Body body: WorkshopAssignmentBody
    ): List<WorkshopAssignmentDto>

    @POST("workshops/{id}/assignments")
    suspend fun grantWorkshopAssignment(
        @Path("id") id: String,
        @Body body: WorkshopGrantBody
    ): WorkshopAssignmentDto

    @PATCH("workshops/{id}/assignments/{userId}")
    suspend fun updateWorkshopAssignment(
        @Path("id") id: String,
        @Path("userId") userId: String,
        @Body body: WorkshopAssignmentUpdateBody
    ): WorkshopAssignmentDto

    @DELETE("workshops/{id}/assignments/{userId}")
    suspend fun revokeWorkshopAssignment(
        @Path("id") id: String,
        @Path("userId") userId: String
    ): WorkshopAssignmentDto

    @GET("workshops/access-levels")
    suspend fun workshopAccessLevels(): List<WorkshopAccessLevelDto>

    @POST("workshops/access-requests")
    suspend fun requestWorkshopAccess(@Body body: WorkshopAccessRequestBody): WorkshopAccessRequestResultDto

    @GET("workshops/access-requests/mine")
    suspend fun myWorkshopAccess(): List<WorkshopAssignmentDto>

    @GET("workshops/access-requests")
    suspend fun workshopAccessRequests(
        @Query("statusFilter") statusFilter: String = "PENDING"
    ): List<WorkshopAssignmentDto>

    @POST("workshops/access-requests/{id}/decide")
    suspend fun decideWorkshopAccess(
        @Path("id") id: String,
        @Body body: WorkshopAccessDecisionBody
    ): WorkshopAssignmentDto

    @GET("tasks")
    suspend fun tasks(
        @Query("view") view: String = "assigned",
        @Query("status") status: String? = null,
        @Query("workshopId") workshopId: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("assigneeId") assigneeId: String? = null,
        @Query("batchId") batchId: String? = null,
        @Query("withDerived") withDerived: Boolean? = null,
        @Query("outstanding") outstanding: Boolean? = null
    ): PageResponse<TaskDto>

    @GET("tasks/summary")
    suspend fun taskSummary(@Query("workshopId") workshopId: String? = null): TaskSummaryDto

    @GET("tasks/{id}")
    suspend fun task(@Path("id") id: String): TaskDto

    @PATCH("tasks/{id}")
    suspend fun updateTask(@Path("id") id: String, @Body body: TaskUpdateBody): TaskDto

    @DELETE("tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String)

    @GET("tasks/options")
    suspend fun taskOptions(@Query("workshopId") workshopId: String? = null): TaskOptionsDto

    @POST("tasks/batch")
    suspend fun createTaskBatch(@Body body: TaskBatchCreateBody): TaskBatchResultDto

    @GET("tasks/batches")
    suspend fun taskBatches(
        @Query("view") view: String = "all",
        @Query("workshopId") workshopId: String? = null,
        @Query("batchId") batchId: String? = null,
        @Query("assigneeId") assigneeId: String? = null,
        @Query("status") status: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20
    ): PageResponse<TaskBatchDto>

    @GET("tasks/progress")
    suspend fun taskProgress(
        @Query("workshopId") workshopId: String? = null,
        @Query("assigneeId") assigneeId: String? = null,
        @Query("includeFinished") includeFinished: Boolean? = null
    ): TaskProgressReportDto

    @DELETE("tasks/batch/{batchId}")
    suspend fun deleteTaskBatch(@Path("batchId") batchId: String)

    @GET("ai/providers")
    suspend fun aiProviders(): AiCatalogueDto

    @GET("me/ai-keys")
    suspend fun myAiKeys(): List<UserAiKeyDto>

    @PUT("me/ai-keys/{provider}")
    suspend fun setMyAiKey(
        @Path("provider") provider: String,
        @Body body: UserAiKeySetBody
    ): UserAiKeyDto

    @DELETE("me/ai-keys/{provider}")
    suspend fun deleteMyAiKey(@Path("provider") provider: String): UserAiKeyDto

    @POST("me/ai-keys/{provider}/test")
    suspend fun testMyAiKey(@Path("provider") provider: String): UserAiKeyDto

    @GET("secrets")
    suspend fun managedSecrets(): List<ManagedSecretDto>

    @GET("secrets/{key}/reveal")
    suspend fun revealSecret(@Path("key") key: String): ManagedSecretRevealDto

    @PUT("secrets/{key}")
    suspend fun setSecret(@Path("key") key: String, @Body body: ManagedSecretSetBody): ManagedSecretDto

    @DELETE("secrets/{key}")
    suspend fun clearSecret(@Path("key") key: String): ManagedSecretDto

    @POST("secrets/{key}/test")
    suspend fun testSecret(@Path("key") key: String): ManagedSecretDto

    @GET("preferences/me")
    suspend fun myPreferences(): PreferencesDto

    @PUT("preferences/me")
    suspend fun updateMyPreferences(@Body body: PreferencesUpdateBody): PreferencesDto

    @GET("search/unified")
    suspend fun unifiedSearch(
        @Query("q") q: String,
        @Query("limit") limit: Int = 5,
        @Query("types") types: String? = null,
        @Query("scope") scope: String? = null,
        @Query("type") type: String? = null,
        @Query("page") page: Int? = null,
        @Query("artisanIds") artisanIds: String? = null,
        @Query("sort") sort: String? = null,
    ): UnifiedSearchResponse

    @GET("search/remote/{type}/{id}")
    suspend fun remoteRecord(@Path("type") type: String, @Path("id") id: String): RemoteRecord

    @GET("search")
    suspend fun search(
        @Query("q") q: String? = null,
        @Query("craftId") craftId: String? = null,
        @Query("place") place: String? = null,
        @Query("artisanId") artisanId: String? = null,
        @Query("mediaType") mediaType: String? = null,
        @Query("types") types: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("workshopIds") workshopIds: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 10,
        @QueryMap filters: Map<String, String> = emptyMap()
    ): SearchResultsDto

    @GET("map/points")
    suspend fun mapPoints(
        @Query("q") q: String? = null,
        @Query("craftId") craftId: String? = null,
        @Query("place") place: String? = null,
        @Query("artisanId") artisanId: String? = null,
        @Query("mediaType") mediaType: String? = null,
        @Query("types") types: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("workshopIds") workshopIds: String? = null,
        @Query("level") level: String? = null,
        @Query("focusType") focusType: String? = null,
        @Query("focusId") focusId: String? = null
    ): MapPointsDto

    @GET("map/points/{key}/records")
    suspend fun mapPointRecords(
        @Path(value = "key", encoded = false) key: String,
        @Query("q") q: String? = null,
        @Query("craftId") craftId: String? = null,
        @Query("place") place: String? = null,
        @Query("artisanId") artisanId: String? = null,
        @Query("mediaType") mediaType: String? = null,
        @Query("types") types: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("workshopIds") workshopIds: String? = null,
        @Query("level") level: String? = null
    ): MapPointRecordsDto

    @GET("map/coverage")
    suspend fun mapCoverage(@QueryMap params: Map<String, String> = emptyMap()): CoverageResponse

    @GET("map/coverage/units/{stateLgd}/{districtLgd}/records")
    suspend fun coverageUnitRecords(
        @Path("stateLgd") stateLgd: Int,
        @Path("districtLgd") districtLgd: Int,
        @QueryMap params: Map<String, String> = emptyMap(),
        @Query("bucket") bucket: String? = null,
        @Query("cursor") cursor: String? = null
    ): CoverageUnitRecords

    @GET("data/tree")
    suspend fun dataTree(@Query("path") path: String = "", @Query("status") status: String? = null): DataTreeDto

    @GET("data/locate")
    suspend fun dataLocate(@Query("type") type: String, @Query("id") id: String): JsonElement

    @Streaming
    @GET("data/manifest")
    suspend fun dataManifestStream(
        @Query("path") path: String = "",
        @Query("include") include: String? = null,
        @Query("stream") stream: Int = 1,
        @Query("status") status: String? = null
    ): Response<ResponseBody>

    @GET("data/manifest")
    suspend fun dataManifest(
        @Query("path") path: String = "",
        @Query("include") include: String? = null,
        @Query("status") status: String? = null
    ): DataManifestDto

    @Streaming
    @GET
    suspend fun apiPath(@Url path: String): Response<ResponseBody>

    @Streaming
    @GET("data/media/{id}/download")
    suspend fun downloadDataMedia(
        @Path("id") id: String,
        @Query("format") format: String? = null
    ): Response<ResponseBody>
}
