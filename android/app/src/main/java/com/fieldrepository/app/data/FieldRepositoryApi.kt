package com.fieldrepository.app.data

// THE ONE TYPE THIS LAYER BORROWS FROM THE UI PACKAGE, and the direction is backwards on purpose:
// the consolidated-questionnaire DTOs were declared beside their screen in
// ui/ConsolidatedQuestionnaireScreen.kt while this file and ApiModels.kt were being edited
// concurrently, so re-declaring them here would give the app two incompatible spellings of one wire
// format. If they are ever moved into ApiModels.kt, this import is the only line to delete.
import com.fieldrepository.app.ui.ConsolidatedQuestionnaireDto
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
import kotlinx.serialization.json.JsonObject

interface FieldRepositoryApi {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokenResponse

    @POST("auth/login")
    suspend fun googleLogin(@Body body: GoogleLoginRequest): TokenResponse

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

    // --- The access roster: who may sign in at all (require_admin on every route) ---------------
    //
    // Declared next to /users because the two answer one question from opposite ends: /users is who
    // HAS an account, /access-roster is who is allowed to have one.

    /**
     * The roster, newest request first. `status` absent means EVERY status — refused and suspended
     * rows included, which is the point: an admin arrives here because somebody says they cannot
     * sign in, and the row refusing them is the one they need to see.
     *
     * `pageSize` is capped at 100 SERVER-SIDE whatever is sent (`normalize_pagination`), so the
     * response's own `pageSize`/`total` are the truth about what came back — never the request's.
     */
    @GET("access-roster")
    suspend fun accessRoster(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50,
        @Query("search") search: String? = null,
        @Query("status") status: String? = null
    ): PageResponse<AccessRosterDto>

    /**
     * How many people are waiting on an administrator. THE NOTIFICATION, in its entirety — there is
     * no email and no push in this codebase, so the requirement's "the admins and master admins
     * should get a notification" is this number, on surfaces admins already open.
     *
     * A bare count and not a list on purpose: the server answers it out of an index without reading
     * a row, which is what keeps it cheap enough to poll from a nav bar.
     */
    @GET("access-roster/pending-count")
    suspend fun accessRosterPendingCount(): AccessRosterPendingCountDto

    @POST("access-roster")
    suspend fun addToAccessRoster(@Body body: AccessRosterCreateBody): AccessRosterDto

    @PATCH("access-roster/{id}")
    suspend fun updateAccessRosterEntry(
        @Path("id") id: String,
        @Body body: AccessRosterUpdateBody
    ): AccessRosterDto

    /**
     * SUSPEND. Never a delete, whatever the verb says: the server sets `status = SUSPENDED` and
     * answers 200 WITH THE ROW, so the entry stays on screen — dated, and one tap from restored.
     * A real delete would drop the person back into the pending queue at their next sign-in, which
     * is exactly the loop REJECTED and SUSPENDED exist to break.
     */
    @DELETE("access-roster/{id}")
    suspend fun suspendAccessRosterEntry(@Path("id") id: String): AccessRosterDto

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
        @Query("pageSize") pageSize: Int = 20,
        // The shared workshop scope, plural. BROADER than the singular `workshopId` filter the form
        // pickers use: it also counts an artisan who merely SAT IN an interview taken at the
        // workshop, so this list and the completion matrix cannot disagree about who was there.
        @Query("workshopIds") workshopIds: String? = null,
        /**
         * One craft's roster, filtered by the SERVER (`routes/artisans.py:234-235` — the parameter
         * has always been there; nothing on this client had ever sent it).
         *
         * This is the whole point of the parameter existing here. Both record forms used to load one
         * 100-row page of the entire artisan table and filter it by craft in memory, which makes the
         * dropdown the intersection of one craft with the newest hundred rows overall — see
         * `ui/RecordPickers.kt` for what that cost. Filtering where the WHERE clause is turns that
         * into the craft's actual roster.
         */
        @Query("craftId") craftId: String? = null,
        /**
         * SEVERAL crafts' rosters at once, comma-joined — the plural of [craftId] directly above,
         * and the shape `workshopIds` two parameters up already has on this same route.
         *
         * It exists because a MULTI-craft picker cannot be served by the singular one. The tool
         * form's craft box became a many-of-many on 2026-09-15; issuing one request per ticked craft
         * is not a substitute ("Select all 178" would fire 178 of them), and filtering one 100-row
         * page in memory gives the intersection of N crafts with the newest hundred artisans
         * overall, which is the ceiling defect `ui/RecordPickers.kt` is written about.
         *
         * DISTINCT FROM [craftId], WHICH STAYS, because every single-select picker link still uses
         * it. WHEN BOTH ARE SENT BOTH NARROW — see `FieldRepository.artisansForCraftsPage`, which
         * sends both for a single craft on purpose so that this client still narrows correctly
         * against an API deployed before the plural landed.
         */
        @Query("craftIds") craftIds: String? = null,
        /**
         * ONE workshop's records, filtered by the SERVER — the singular filter, not the plural scope.
         *
         * `GET /artisans` has always accepted it (`backend/app/api/routes/artisans.py`, `where["workshopId"]
         * = workshopId`); nothing on this client had ever sent it. It is what `ui/RecordSwitcher.kt`'s
         * second dropdown is a list OF, and filtering where the WHERE clause is rather than in memory
         * is the same argument [artisans]' `craftId` carries: a page is 100 rows of the whole table,
         * and one workshop's share of a long table is mostly not in it.
         *
         * DISTINCT FROM `workshopIds` wherever both exist. The plural is the shared SCOPE vocabulary
         * — broader, reserved-word aware, and the one the matrix and the map speak. This is the narrow
         * "records filed at this workshop" the form pickers have always meant, and the two must not be
         * collapsed: an artisan who merely sat in an interview at a workshop is in the plural's answer
         * and not in this one.
         */
        @Query("workshopId") workshopId: String? = null,
        /**
         * The server-side free-text search, for reaching rows past the 100-row page.
         *
         * Only sent when the list is genuinely cut — see `shouldSearchServer` in `ui/RecordSwitcher.kt`
         * for why an unconditional search-per-keystroke would be strictly worse than the local filter
         * it would replace, and would stop working in exactly the place this product is used.
         */
        @Query("search") search: String? = null
    ): PageResponse<ArtisanDto>

    @GET("crafts")
    suspend fun crafts(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        /**
         * ONE workshop's records, filtered by the SERVER — the singular filter, not the plural scope.
         *
         * `GET /crafts` has always accepted it (`backend/app/api/routes/crafts.py`, `where["workshopId"]
         * = workshopId`); nothing on this client had ever sent it. It is what `ui/RecordSwitcher.kt`'s
         * second dropdown is a list OF, and filtering where the WHERE clause is rather than in memory
         * is the same argument [artisans]' `craftId` carries: a page is 100 rows of the whole table,
         * and one workshop's share of a long table is mostly not in it.
         *
         * DISTINCT FROM `workshopIds` wherever both exist. The plural is the shared SCOPE vocabulary
         * — broader, reserved-word aware, and the one the matrix and the map speak. This is the narrow
         * "records filed at this workshop" the form pickers have always meant, and the two must not be
         * collapsed: an artisan who merely sat in an interview at a workshop is in the plural's answer
         * and not in this one.
         */
        @Query("workshopId") workshopId: String? = null,
        /**
         * The server-side free-text search, for reaching rows past the 100-row page.
         *
         * Only sent when the list is genuinely cut — see `shouldSearchServer` in `ui/RecordSwitcher.kt`
         * for why an unconditional search-per-keystroke would be strictly worse than the local filter
         * it would replace, and would stop working in exactly the place this product is used.
         */
        @Query("search") search: String? = null
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
        @Query("artisanName") artisanName: String? = null,
        /**
         * ONE workshop's records, filtered by the SERVER — the singular filter, not the plural scope.
         *
         * `GET /products` has always accepted it (`backend/app/api/routes/products.py`, `where["workshopId"]
         * = workshopId`); nothing on this client had ever sent it. It is what `ui/RecordSwitcher.kt`'s
         * second dropdown is a list OF, and filtering where the WHERE clause is rather than in memory
         * is the same argument [artisans]' `craftId` carries: a page is 100 rows of the whole table,
         * and one workshop's share of a long table is mostly not in it.
         *
         * DISTINCT FROM `workshopIds` wherever both exist. The plural is the shared SCOPE vocabulary
         * — broader, reserved-word aware, and the one the matrix and the map speak. This is the narrow
         * "records filed at this workshop" the form pickers have always meant, and the two must not be
         * collapsed: an artisan who merely sat in an interview at a workshop is in the plural's answer
         * and not in this one.
         */
        @Query("workshopId") workshopId: String? = null,
        /**
         * The server-side free-text search, for reaching rows past the 100-row page.
         *
         * Only sent when the list is genuinely cut — see `shouldSearchServer` in `ui/RecordSwitcher.kt`
         * for why an unconditional search-per-keystroke would be strictly worse than the local filter
         * it would replace, and would stop working in exactly the place this product is used.
         */
        @Query("search") search: String? = null
    ): PageResponse<ProductDetailDto>

    @GET("products/{id}")
    suspend fun product(@Path("id") id: String): ProductDetailDto

    @PATCH("products/{id}")
    suspend fun updateProduct(@Path("id") id: String, @Body body: ProductCreateRequest): ProductDetailDto

    @GET("tools")
    suspend fun tools(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 100,
        /**
         * ONE workshop's records, filtered by the SERVER — the singular filter, not the plural scope.
         *
         * `GET /tools` has always accepted it (`backend/app/api/routes/tools.py`, `where["workshopId"]
         * = workshopId`); nothing on this client had ever sent it. It is what `ui/RecordSwitcher.kt`'s
         * second dropdown is a list OF, and filtering where the WHERE clause is rather than in memory
         * is the same argument [artisans]' `craftId` carries: a page is 100 rows of the whole table,
         * and one workshop's share of a long table is mostly not in it.
         *
         * DISTINCT FROM `workshopIds` wherever both exist. The plural is the shared SCOPE vocabulary
         * — broader, reserved-word aware, and the one the matrix and the map speak. This is the narrow
         * "records filed at this workshop" the form pickers have always meant, and the two must not be
         * collapsed: an artisan who merely sat in an interview at a workshop is in the plural's answer
         * and not in this one.
         */
        @Query("workshopId") workshopId: String? = null,
        /**
         * The server-side free-text search, for reaching rows past the 100-row page.
         *
         * Only sent when the list is genuinely cut — see `shouldSearchServer` in `ui/RecordSwitcher.kt`
         * for why an unconditional search-per-keystroke would be strictly worse than the local filter
         * it would replace, and would stop working in exactly the place this product is used.
         */
        @Query("search") search: String? = null
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

    // Which records name NO workshop, and where each one's own evidence points. Admin-only, a pure read,
    // and the preview the button below acts on. See [WorkshopMappingPlanDto].
    //
    // Declared beside `workshops/{id}` and it does not collide: FastAPI registers the literal
    // `/workshops/unmapped` before the parameterised route, so it is never read as a workshop whose id is
    // the word "unmapped".
    @GET("workshops/unmapped")
    suspend fun unmappedRecords(): WorkshopMappingPlanDto

    // File every unassigned record whose evidence names exactly one workshop. NO BODY — the server
    // re-derives the plan rather than trusting one sent back, so this client cannot ask for an arbitrary
    // row to be moved to an arbitrary workshop. Idempotent: it only ever fills an empty column.
    @POST("workshops/unmapped/map")
    suspend fun mapUnmappedRecords(): WorkshopMappingPlanDto

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

    /**
     * [completeMedia] with the endpoint's optional `checksum` key added to the body. Retrofit binds a
     * body type per method, so carrying that one extra key needs its own declaration; the caller
     * encodes [MediaCompleteRequest] and adds the key, so the two bodies cannot drift apart.
     */
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

    // The whole-repository download manifest.
    //
    // TWO DECLARATIONS OF ONE ROUTE, AND THE STREAMED ONE IS THE ONE TO USE. The typed call below
    // returns a fully-materialised DTO, which sends the response through Retrofit's
    // kotlinx-serialization converter — `Serializer.FromString`, i.e. `decodeFromString(body
    // .string())`, i.e. the entire body as one contiguous ByteArray and then one contiguous String.
    // The manifest is unbounded in bytes (the server caps the entry COUNT at 20,000 media rows plus
    // 6x5,000 record rows and inlines every details.txt and every transcript), so on a large
    // repository that single allocation is what throws
    // `OutOfMemoryError: Failed to allocate a N byte allocation` on the handset. See
    // data/ManifestStream.kt for the full account.
    //
    // The typed one is KEPT, not deleted, because it is the fallback for a server that predates
    // `?stream=1`: such a server ignores the unknown parameter and answers `application/json`, and
    // the client has to be able to finish the download against it. It must not be used for anything
    // else — a new caller wanting "the list of files" should take the streamed route and consume it
    // a line at a time.
    @Streaming
    @GET("export/dataset")
    suspend fun datasetManifestStream(@Query("stream") stream: Int = 1): Response<ResponseBody>

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
        @Query("productId") productId: String? = null,
        /**
         * ONE workshop's records, filtered by the SERVER — the singular filter, not the plural scope.
         *
         * `GET /processes` has always accepted it (`backend/app/api/routes/processes.py`, `where["workshopId"]
         * = workshopId`); nothing on this client had ever sent it. It is what `ui/RecordSwitcher.kt`'s
         * second dropdown is a list OF, and filtering where the WHERE clause is rather than in memory
         * is the same argument [artisans]' `craftId` carries: a page is 100 rows of the whole table,
         * and one workshop's share of a long table is mostly not in it.
         *
         * DISTINCT FROM `workshopIds` wherever both exist. The plural is the shared SCOPE vocabulary
         * — broader, reserved-word aware, and the one the matrix and the map speak. This is the narrow
         * "records filed at this workshop" the form pickers have always meant, and the two must not be
         * collapsed: an artisan who merely sat in an interview at a workshop is in the plural's answer
         * and not in this one.
         */
        @Query("workshopId") workshopId: String? = null,
        /**
         * The server-side free-text search, for reaching rows past the 100-row page.
         *
         * Only sent when the list is genuinely cut — see `shouldSearchServer` in `ui/RecordSwitcher.kt`
         * for why an unconditional search-per-keystroke would be strictly worse than the local filter
         * it would replace, and would stop working in exactly the place this product is used.
         */
        @Query("search") search: String? = null
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

    /**
     * Every instrument, in picker order (sortOrder asc). `isDefault` says which one a request that
     * names none would land on.
     */
    @GET("questionnaires")
    suspend fun questionnaires(@Query("activeOnly") activeOnly: Boolean = true): List<QuestionnaireDto>

    // questionnaireId is OPTIONAL on both of these and absent means the DEFAULT instrument - which
    // is exactly what every build of this app that predates 2026-09-13 sends, and what it must keep
    // getting.
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
        @Query("artisanId") artisanId: String? = null,
        // The shared workshop scope: comma-joined ids plus the reserved "none". Absent = every
        // workshop, which is why it is nullable rather than defaulted to a string.
        @Query("workshopIds") workshopIds: String? = null,
        // WHICH INSTRUMENT the matrix is about. Absent = the default one. The matrix's columns are
        // ONE instrument's sections, so a screen showing the other instrument's form beside an
        // unscoped matrix is showing two different questionnaires under one set of letters.
        @Query("questionnaireId") questionnaireId: String? = null
    ): CompletionMatrixDto

    @PUT("questionnaire/completion")
    suspend fun setCompletionCell(@Body body: CompletionCellRequest): JsonElement

    // One artisan's answers gathered from EVERY interview they sat in. Scoped by the shared
    // workshopIds so the same document can be read "as it stands for these workshops" — the whole
    // document is always returned, the scope only decides which sittings feed it.
    @GET("questionnaire/artisans/{id}/consolidated")
    suspend fun consolidatedQuestionnaire(
        @Path("id") artisanId: String,
        @Query("workshopIds") workshopIds: String? = null,
        // ABSENT MEANS EVERY INSTRUMENT here, grouped and labelled - the opposite default from every
        // other questionnaire call, and deliberately so: this document's meaning is "everything this
        // artisan has ever told us", and narrowing it by default would silently drop half an account.
        @Query("questionnaireId") questionnaireId: String? = null
    ): ConsolidatedQuestionnaireDto

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
        @Query("withDerived") withDerived: Boolean? = null,
        // "STILL ON MY SCREEN" — OPEN, IN_PROGRESS *and* SUBMITTED. Not a convenience: `status` takes
        // ONE value and this question is three, so the only alternative is fetching everything and
        // dropping rows client-side — which is wrong in a way that hides itself, because the page is
        // twenty rows deep and a researcher with twenty-one tasks would silently lose outstanding
        // work off the end of it. Sending this WITH `status` is a 422 rather than a guess at which
        // one was meant.
        @Query("outstanding") outstanding: Boolean? = null
    ): PageResponse<TaskDto>

    // MY workload in one object — the full-width card at the top of the assignee's screen. Any
    // authenticated caller, always about themselves; `tasks/progress` is the admin's board and is
    // not a substitute. Declared server-side BEFORE `/{task_id}` so "summary" is not swallowed as
    // the id of a task somebody happened to name that.
    @GET("tasks/summary")
    suspend fun taskSummary(@Query("workshopId") workshopId: String? = null): TaskSummaryDto

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
    // ── A designer's OWN provider keys ────────────────────────────────────────────────────
    //
    // NO REVEAL ROUTE, and that is a deliberate absence rather than an omission: the secrets API
    // above has one because the deployment's keys belong to the organisation, and nobody has the
    // equivalent need for somebody else's personal credential. The server takes the owner from the
    // token, so none of these carries a user id — there is no shape of request that reads or
    // writes another person's key.

    /** The catalogue every settings screen is built from: providers, models, capabilities, how-to. */
    @GET("ai/providers")
    suspend fun aiProviders(): AiCatalogueDto

    /** This person's own keys, one row per provider, with no plaintext in any of them. */
    @GET("me/ai-keys")
    suspend fun myAiKeys(): List<UserAiKeyDto>

    /** Save or rotate a key, or change only the model by sending a body with no key. */
    @PUT("me/ai-keys/{provider}")
    suspend fun setMyAiKey(
        @Path("provider") provider: String,
        @Body body: UserAiKeySetBody
    ): UserAiKeyDto

    /** Remove it; this work goes back to whatever key the server itself is set up with. */
    @DELETE("me/ai-keys/{provider}")
    suspend fun deleteMyAiKey(@Path("provider") provider: String): UserAiKeyDto

    /** Ask the provider whether the stored key works, now, and remember the answer. */
    @POST("me/ai-keys/{provider}/test")
    suspend fun testMyAiKey(@Path("provider") provider: String): UserAiKeyDto

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
        // Which buckets to search, comma-joined ("artisans,media"); omitted searches all five. The
        // route reads a repeated parameter too, but one value keeps the query string canonical —
        // the same three buckets cannot arrive spelled two ways depending on tick order.
        @Query("types") types: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        // The workshop SCOPE, comma-joined ids plus the reserved literal "none" for records linked to
        // no workshop. ABSENT means every workshop; never send "" to mean "all", which the server
        // reads as one blank id and matches nothing. See `record_filters.resolve_workshop_ids`.
        @Query("workshopIds") workshopIds: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 10
    ): SearchResultsDto

    // --- Map: where the records ARE (aggregate pins, then one pin's records on demand) ---
    // The filter vocabulary is `search`'s, spelled identically and sent from the same place, because
    // a map that answered "Bagru, last 30 days" differently from the search box would leave no way
    // to tell which of the two was lying.
    @GET("map/points")
    suspend fun mapPoints(
        @Query("q") q: String? = null,
        @Query("craftId") craftId: String? = null,
        @Query("place") place: String? = null,
        @Query("artisanId") artisanId: String? = null,
        @Query("mediaType") mediaType: String? = null,
        // Which buckets to count, comma-joined; omitted counts all five.
        @Query("types") types: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
        @Query("workshopIds") workshopIds: String? = null,
        // NATION | STATE | DISTRICT — the administrative unit BOTH layers are grouped at. Null lets
        // the server pick its default (DISTRICT) rather than this client hard-coding a second copy
        // of it that could drift.
        @Query("level") level: String? = null,
        // The single-record scope. Both must be sent or neither: one alone is ignored, and the map
        // still draws the whole filtered corpus either way.
        @Query("focusType") focusType: String? = null,
        @Query("focusId") focusId: String? = null
    ): MapPointsDto

    // The point key holds ':' and '|' — "district:Rajasthan|Jaipur", "capture:0.25:107_302".
    // `encoded = false` is DELIBERATE and is the correct setting: Retrofit then percent-encodes the
    // characters that are illegal in a path segment ('|' becomes %7C) and leaves ':' alone, which is
    // legal there, and the route's `{point_key:path}` receives the key decoded and whole. Declaring
    // it `encoded = true` would ship a raw '|' — not a legal URL character — and the request would
    // either be rejected or silently mangled by the first proxy that normalised it.
    @GET("map/points/{key}/records")
    suspend fun mapPointRecords(
        @Path(value = "key", encoded = false) key: String,
        // These MUST be the filters the map was drawn with, level included. The key names an
        // administrative unit; which records sit in it is what the filters decide.
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
    //
    // Same pair, same reason, as `datasetManifest`/`datasetManifestStream` above: a folder manifest
    // with `include=transcripts` (or no filter) inlines every transcript body in the subtree, so it
    // is unbounded in bytes too. The DOWNLOAD path takes the streamed one. The typed one stays
    // because the data browser's transcript panel genuinely needs the whole list resident — it
    // indexes into it on every toggle — and because it is the fallback for an older server.
    @Streaming
    @GET("data/manifest")
    suspend fun dataManifestStream(
        @Query("path") path: String = "",
        @Query("include") include: String? = null,
        @Query("stream") stream: Int = 1
    ): Response<ResponseBody>

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
