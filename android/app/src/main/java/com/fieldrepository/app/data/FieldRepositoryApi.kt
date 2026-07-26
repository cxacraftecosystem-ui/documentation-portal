package com.fieldrepository.app.data

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import kotlinx.serialization.json.JsonElement

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

    @GET("users/directory")
    suspend fun userDirectory(): List<UserDto>

    @GET("review/pending")
    suspend fun pendingReviews(): PendingReviewListDto

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

    @POST("review/{type}/{id}/approve")
    suspend fun approveRecord(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: ReviewActionRequest
    ): JsonElement

    @POST("review/{type}/{id}/reject")
    suspend fun rejectRecord(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: ReviewActionRequest
    ): JsonElement

    // Send back to the creator with mandatory comments (status NEEDS_REVISION). A blank `notes` is a
    // 422 — the whole point is that the creator is told what to fix.
    @POST("review/{type}/{id}/revise")
    suspend fun reviseRecord(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: ReviewActionRequest
    ): JsonElement

    // Correct a record's field values from the review queue instead of bouncing it back. Leaves the
    // status untouched unless `approve` is set. See [ReviewEditRequest] for the refused keys.
    @POST("review/{type}/{id}/edit")
    suspend fun editReviewedRecord(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: ReviewEditRequest
    ): JsonElement

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

    // Pre-flight duplicate check for the artisan form's Aadhaar field. Declared BEFORE `artisan(id)`
    // only for readability — Retrofit matches on the literal path, so "lookup/aadhaar" can never be
    // swallowed by the "{id}" route the way a server-side router would.
    @GET("artisans/lookup/aadhaar")
    suspend fun lookupArtisanByAadhaar(@Query("number") number: String): AadhaarLookupDto

    @GET("artisans/{id}")
    suspend fun artisan(@Path("id") id: String): ArtisanDetailDto

    @PATCH("artisans/{id}")
    suspend fun updateArtisan(@Path("id") id: String, @Body body: ArtisanCreateRequest): ArtisanDetailDto

    @GET("artisans/{id}/questionnaire")
    suspend fun artisanQuestionnaire(@Path("id") id: String): ArtisanQuestionnaireDto

    @POST("crafts")
    suspend fun createCraft(@Body body: CraftCreateRequest): CreatedRecordDto

    @GET("crafts/{id}")
    suspend fun craft(@Path("id") id: String): CraftDto

    @PATCH("crafts/{id}")
    suspend fun updateCraft(@Path("id") id: String, @Body body: CraftCreateRequest): CraftDto

    @GET("products")
    suspend fun products(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        @Query("artisanId") artisanId: String? = null,
        @Query("artisanName") artisanName: String? = null
    ): PageResponse<ProductDetailDto>

    @GET("products/{id}")
    suspend fun product(@Path("id") id: String): ProductDetailDto

    @PATCH("products/{id}")
    suspend fun updateProduct(@Path("id") id: String, @Body body: ProductCreateRequest): ProductDetailDto

    @GET("tools")
    suspend fun tools(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100
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
        @Query("pageSize") pageSize: Int = 100
    ): PageResponse<WorkshopDetailDto>

    @GET("workshops/{id}")
    suspend fun workshop(@Path("id") id: String): WorkshopDetailDto

    @PATCH("workshops/{id}")
    suspend fun updateWorkshop(@Path("id") id: String, @Body body: WorkshopCreateRequest): WorkshopDetailDto

    // Pre-flight for a record form: what would submitting into this workshop mean for me? Reports
    // only — it never 403s, so a caller must treat a failure as "no answer", never as "refused".
    @GET("workshops/{id}/submission-check")
    suspend fun workshopSubmissionCheck(@Path("id") id: String): WorkshopSubmissionCheckDto

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
        @Query("linkedRecordId") linkedRecordId: String? = null
    ): PageResponse<MediaFileDto>

    // Admin-only: media whose parent record was deleted (tag columns survive, typed FK nulled) — and
    // the action to re-attach such a file to an existing record so it reappears under it.
    @GET("media/orphans")
    suspend fun orphanMedia(): List<MediaFileDto>

    @POST("media/{id}/relink")
    suspend fun relinkMedia(@Path("id") id: String, @Body body: MediaRelinkRequest): MediaFileDto

    // AI transcript refinement (gpt-4o-mini): turn a raw transcript into a clean interviewer/
    // interviewee conversation, optionally translated to English. Billable — gated behind a cost
    // confirmation in the UI.
    @POST("media/{id}/refine-transcript")
    suspend fun refineTranscript(@Path("id") id: String, @Body body: TranscriptRefineRequest): TranscriptRefineResponse

    // Admin/master-admin: transcribe this audio file now, applying the settings-page transcription
    // mode, bypassing the queue + off-peak window. Returns the updated media row.
    @POST("media/{id}/transcribe-now")
    suspend fun transcribeNow(@Path("id") id: String): MediaFileDto

    // Save an (approved, AI-refined) transcript in place of the stored one. Uploader or admin only.
    @POST("media/{id}/transcript")
    suspend fun setTranscript(@Path("id") id: String, @Body body: TranscriptUpdateRequest): MediaFileDto

    @GET("export/dataset")
    suspend fun datasetManifest(): DatasetManifestDto

    // Styled relational report of the whole dataset (or a subtree) as a .xlsx workbook. Streamed so
    // large workbooks aren't buffered entirely in memory before being written to Downloads.
    @Streaming
    @GET("data/report")
    suspend fun dataReport(
        @Query("format") format: String = "xlsx",
        @Query("path") path: String = ""
    ): Response<ResponseBody>

    // Admin-only record deletion (backend enforces is_admin).
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
        @Query("productId") productId: String? = null
    ): PageResponse<ProcessDetailDto>

    @GET("processes/{id}")
    suspend fun process(@Path("id") id: String): ProcessDetailDto

    @POST("processes")
    suspend fun createProcess(@Body body: ProcessCreateRequest): ProcessDetailDto

    @PATCH("processes/{id}")
    suspend fun updateProcess(@Path("id") id: String, @Body body: ProcessCreateRequest): ProcessDetailDto

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

    @GET("questionnaire/interviews")
    suspend fun interviews(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100
    ): PageResponse<QuestionnaireInterviewDetailDto>

    @GET("questionnaire/interviews/{id}")
    suspend fun interview(@Path("id") id: String): QuestionnaireInterviewDetailDto

    @PATCH("questionnaire/interviews/{id}")
    suspend fun updateInterview(
        @Path("id") id: String,
        @Body body: QuestionnaireInterviewUpdateRequest
    ): QuestionnaireInterviewDetailDto

    @GET("questionnaire/completion")
    suspend fun completionMatrix(
        @Query("artisanId") artisanId: String? = null
    ): CompletionMatrixDto

    @PUT("questionnaire/completion")
    suspend fun setCompletionCell(@Body body: CompletionCellRequest): JsonElement

    // --- Cross-researcher data access (Sharing) ---
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
    suspend fun addEntryComment(@Body body: EntryCommentBody): EntryCommentDto

    @GET("data-access/revisions")
    suspend fun recordRevisions(
        @Query("recordType") recordType: String,
        @Query("recordId") recordId: String
    ): List<RecordRevisionDto>

    // --- Workshop assignment (admin) ---
    @GET("workshops/{id}/assignments")
    suspend fun workshopAssignments(@Path("id") id: String): List<WorkshopAssignmentDto>

    @PUT("workshops/{id}/assignments")
    suspend fun setWorkshopAssignments(
        @Path("id") id: String,
        @Body body: WorkshopAssignmentBody
    ): List<WorkshopAssignmentDto>

    // Grant ONE user access at a level (upsert: re-grants a REVOKED/DENIED row) without touching the
    // rest of the roster — unlike the whole-set PUT above.
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

    // Sets the row to REVOKED and RETURNS it — the row is the audit trail, so it is never deleted.
    @DELETE("workshops/{id}/assignments/{userId}")
    suspend fun revokeWorkshopAssignment(
        @Path("id") id: String,
        @Path("userId") userId: String
    ): WorkshopAssignmentDto

    // --- Workshop access requests (user side + the admin's cross-workshop queue) ---
    @GET("workshops/access-levels")
    suspend fun workshopAccessLevels(): List<WorkshopAccessLevelDto>

    @POST("workshops/access-requests")
    suspend fun requestWorkshopAccess(@Body body: WorkshopAccessRequestBody): WorkshopAccessRequestResultDto

    @GET("workshops/access-requests/mine")
    suspend fun myWorkshopAccess(): List<WorkshopAssignmentDto>

    // Admin: the approval queue across ALL workshops. `statusFilter=ALL` widens it to full history.
    @GET("workshops/access-requests")
    suspend fun workshopAccessRequests(
        @Query("statusFilter") statusFilter: String = "PENDING"
    ): List<WorkshopAssignmentDto>

    @POST("workshops/access-requests/{id}/decide")
    suspend fun decideWorkshopAccess(
        @Path("id") id: String,
        @Body body: WorkshopAccessDecisionBody
    ): WorkshopAssignmentDto

    // --- Assigned tasks ---
    // view=assigned (default) is "my tasks"; view=created / view=all are the admin planning views.
    @GET("tasks")
    suspend fun tasks(
        @Query("view") view: String = "assigned",
        @Query("status") status: String? = null,
        @Query("workshopId") workshopId: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        // Admin-only narrowing (ignored on view=assigned, which is hard-pinned to the caller).
        @Query("assigneeId") assigneeId: String? = null,
        @Query("batchId") batchId: String? = null,
        // false skips the data-backed counts (derivedCount comes back null) when only the list is needed.
        @Query("withDerived") withDerived: Boolean? = null
    ): PageResponse<TaskDto>

    @GET("tasks/{id}")
    suspend fun task(@Path("id") id: String): TaskDto

    @PATCH("tasks/{id}")
    suspend fun updateTask(@Path("id") id: String, @Body body: TaskUpdateBody): TaskDto

    // Withdraw ONE assignment. The creator or an admin only. Used for the pre-batch/single-assignee
    // rows, which have no batchId to delete by.
    @DELETE("tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String)

    // --- Task administration (admin; the master admin may assign to anyone but themselves) ---
    // Every picker the assignment builder needs in one call. `workshopId` narrows the artisan list.
    @GET("tasks/options")
    suspend fun taskOptions(@Query("workshopId") workshopId: String? = null): TaskOptionsDto

    // THE assignment endpoint: one scope handed to N people writes N rows sharing a batchId.
    // Validated in full before the first row is written, so a bad id can never leave half a batch.
    @POST("tasks/batch")
    suspend fun createTaskBatch(@Body body: TaskBatchCreateBody): TaskBatchResultDto

    // Assignments grouped back into the action that created them. The filters select which batches
    // are SHOWN; the progress reported is always for the whole batch.
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

    // Per-assignee accountability rollup: reported progress next to derived progress on every line.
    @GET("tasks/progress")
    suspend fun taskProgress(
        @Query("workshopId") workshopId: String? = null,
        @Query("assigneeId") assigneeId: String? = null,
        @Query("includeFinished") includeFinished: Boolean? = null
    ): TaskProgressReportDto

    // Withdraw a whole assignment. Only the admin who sent it (or the master admin) may unsend it.
    @DELETE("tasks/batch/{batchId}")
    suspend fun deleteTaskBatch(@Path("batchId") batchId: String)

    // --- Managed provider keys (MASTER ADMIN ONLY; every route is require_master_admin) ---
    // Cheap by design: no provider is contacted here, so the list costs one query however many
    // keys are configured. The list NEVER carries a value — only /reveal does.
    @GET("secrets")
    suspend fun managedSecrets(): List<ManagedSecretDto>

    // Plaintext of ONE key. The read is audit-logged server-side (who + which key, never the value).
    @GET("secrets/{key}/reveal")
    suspend fun revealSecret(@Path("key") key: String): ManagedSecretRevealDto

    // Set or rotate a key. Live on the next provider call — no restart, no redeploy.
    @PUT("secrets/{key}")
    suspend fun setSecret(@Path("key") key: String, @Body body: ManagedSecretSetBody): ManagedSecretDto

    // Drop the stored override so the deployed environment value applies again. Idempotent, and it
    // RETURNS the key's new state rather than 204ing.
    @DELETE("secrets/{key}")
    suspend fun clearSecret(@Path("key") key: String): ManagedSecretDto

    // Call the provider once with the key in force and persist the verdict onto the row.
    @POST("secrets/{key}/test")
    suspend fun testSecret(@Path("key") key: String): ManagedSecretDto

    // --- Appearance + accessibility preferences (every signed-in user owns their own row) ---
    // Returns an EMPTY OBJECT when the account has never saved any: see [PreferencesDto.exists].
    @GET("preferences/me")
    suspend fun myPreferences(): PreferencesDto

    @PUT("preferences/me")
    suspend fun updateMyPreferences(@Body body: PreferencesUpdateBody): PreferencesDto

    // --- Global search: five buckets sharing one page/pageSize ---
    // Dates are ISO-8601. pageSize is capped at 50 server-side.
    @GET("search")
    suspend fun search(
        @Query("q") q: String? = null,
        @Query("craftId") craftId: String? = null,
        @Query("place") place: String? = null,
        @Query("artisanId") artisanId: String? = null,
        @Query("mediaType") mediaType: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 10
    ): SearchResultsDto

    // --- Data browser (needs the dataset-download permission; rows are visibility-filtered) ---
    // ONE level of the virtual tree. path="" is the taxonomy chooser, not a folder listing.
    @GET("data/tree")
    suspend fun dataTree(@Query("path") path: String = ""): DataTreeDto

    // Where a record sits in the tree, so a search hit can be turned into a folder to open. Answers
    // `{"path": null}` when nothing files the record yet — that is a fact, not an error.
    @GET("data/locate")
    suspend fun dataLocate(@Query("type") type: String, @Query("id") id: String): JsonElement

    // The flattened subtree below `path`, for client-side zipping. `include` is a CSV of
    // text,images,videos,audios,transcripts,documents,other; omitted means everything.
    @GET("data/manifest")
    suspend fun dataManifest(
        @Query("path") path: String = "",
        @Query("include") include: String? = null
    ): DataManifestDto

    // One media file. Audio defaults to a server-side .mp4 (AAC) conversion; anything else is
    // redirected to the stored object (OkHttp follows it, dropping the auth header cross-host).
    @Streaming
    @GET("data/media/{id}/download")
    suspend fun downloadDataMedia(
        @Path("id") id: String,
        @Query("format") format: String? = null
    ): Response<ResponseBody>
}
