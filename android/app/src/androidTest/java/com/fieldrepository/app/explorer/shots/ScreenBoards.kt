package com.fieldrepository.app.explorer.shots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.data.ExplorerCrumb
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerFileTotals
import com.fieldrepository.app.explorer.data.ExplorerFilled
import com.fieldrepository.app.explorer.data.ExplorerInfo
import com.fieldrepository.app.explorer.data.ExplorerInfoField
import com.fieldrepository.app.explorer.data.ExplorerItem
import com.fieldrepository.app.explorer.data.ExplorerJob
import com.fieldrepository.app.explorer.data.ExplorerJobKind
import com.fieldrepository.app.explorer.data.ExplorerJobState
import com.fieldrepository.app.explorer.data.ExplorerLanguages
import com.fieldrepository.app.explorer.data.ExplorerMore
import com.fieldrepository.app.explorer.data.ExplorerOverview
import com.fieldrepository.app.explorer.data.ExplorerOverviewArtisan
import com.fieldrepository.app.explorer.data.ExplorerOverviewTotals
import com.fieldrepository.app.explorer.data.ExplorerRecent
import com.fieldrepository.app.explorer.data.ExplorerRecentStatus
import com.fieldrepository.app.explorer.data.ExplorerReport
import com.fieldrepository.app.explorer.data.ExplorerRequest
import com.fieldrepository.app.explorer.data.ExplorerResult
import com.fieldrepository.app.explorer.data.ExplorerReviewTotals
import com.fieldrepository.app.explorer.data.ExplorerSectionHead
import com.fieldrepository.app.explorer.data.ExplorerSectionState
import com.fieldrepository.app.explorer.data.ExplorerSelectionBody
import com.fieldrepository.app.explorer.data.ExplorerSheet
import com.fieldrepository.app.explorer.data.ExplorerSource
import com.fieldrepository.app.explorer.data.ExplorerStats
import com.fieldrepository.app.explorer.data.ExplorerStatsBody
import com.fieldrepository.app.explorer.data.ExplorerTaxonomy
import com.fieldrepository.app.explorer.data.ExplorerTranscript
import com.fieldrepository.app.explorer.data.ExplorerTranscriptTotals
import com.fieldrepository.app.explorer.data.ExplorerTree
import com.fieldrepository.app.explorer.data.ExplorerTypeStats
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.util.Collections

object ScreenBoards {
    const val ROOT = "by-workshop"
    const val WORKSHOP = "by-workshop/w3"
    const val GIRIRAJ = "by-workshop/w3/artisans/giriraj"
    const val EMPTY = "by-workshop/w3/artisans/meera"
    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB
    val FOLDER_BYTES = (1.24 * GB).toLong()
    val WORKSHOP_BYTES = (7.15 * GB).toLong()

    val taxonomies = listOf(
        ExplorerTaxonomy("workshop", "Workshop", ROOT, "Workshop › artisan › record. A tool shows under every artisan it’s linked to.", isDefault = true),
        ExplorerTaxonomy("craft", "Craft", "by-craft", "Craft › artisan › record."),
        ExplorerTaxonomy("type", "Type", "by-type", "Type › file."),
    )

    private val crumbsRoot = listOf(ExplorerCrumb("All workshops", ROOT))
    private val crumbsWorkshop = crumbsRoot + ExplorerCrumb("3rd Craft Toolkit", WORKSHOP)

    private fun folder(name: String, path: String, count: Int, bytes: Long, type: String) =
        ExplorerEntry(name = name, path = path, kind = "folder", recordType = type, count = count, bytes = bytes)

    val workshops = listOf(
        folder("Bagru workshop", "$ROOT/w1", 212, (3.8 * GB).toLong(), "workshop"),
        folder("3rd Craft Toolkit", WORKSHOP, 926, WORKSHOP_BYTES, "workshop"),
        folder("Sanganer cluster", "$ROOT/w5", 1408, 12 * GB, "workshop"),
    )

    val artisans = listOf(
        folder("Ramesh Chhipa", "$WORKSHOP/artisans/ramesh", 97, (1.9 * GB).toLong(), "artisan"),
        folder("Giriraj Prasad Chhipa", GIRIRAJ, 48, FOLDER_BYTES, "artisan"),
        folder("Sunita Devi", "$WORKSHOP/artisans/sunita", 61, 880 * MB, "artisan"),
        folder("Mohan Lal Chhipa", "$WORKSHOP/artisans/mohan", 22, 410 * MB, "artisan"),
    )

    private val moreFields = listOf(
        "Phone" to "98290 11234", "Gender" to "Male", "Age" to "52", "Years in craft" to "34", "Workshop" to "3rd Craft Toolkit",
        "District" to "Jaipur", "Block" to "Sanganer", "Village" to "Bagru", "Language" to "Marwari, Hindi", "Family in craft" to "3 generations",
        "Main product" to "Dabu printed yardage", "Tools" to "Teak blocks, indigo vat", "Materials" to "Black clay, lime, gum, wheat husk",
        "Dyes" to "Indigo, pomegranate", "Fabric" to "Cotton mulmul", "Market" to "Jaipur, export", "Monthly output" to "400 m",
        "Helpers" to "4", "Training" to "Family", "Awards" to "State award 2011", "Cooperative" to "Bagru Chhipa Samiti",
        "Bank" to "Yes", "Artisan card" to "Yes", "Consent" to "Signed", "Recorded by" to "Anuj K", "Interview date" to "22 Sep 2026",
        "Sections done" to "7 of 8", "Notes" to "Prefers mornings",
        "Water source" to "Pond", "Drying" to "Sun, open ground",
    )

    val info = ExplorerInfo(
        title = "Giriraj Prasad Chhipa",
        fields = listOf(
            ExplorerInfoField("Craft", "Dabu printing"),
            ExplorerInfoField("Location", "Bagru, Rajasthan"),
            ExplorerInfoField("Recorded fields", "34 of 40"),
            ExplorerInfoField("Review", "Approved"),
        ) + moreFields.map { ExplorerInfoField(it.first, it.second) },
        counts = com.fieldrepository.app.explorer.data.ExplorerCounts(tools = 6),
    )

    val video = ExplorerEntry(name = "Interview_Dabu-mud-resist_Take-1.mp4", path = "$GIRIRAJ/v1", mediaType = "VIDEO", mediaId = "v1", sizeBytes = 701 * MB, transcriptAvailable = true, previewPath = "/data/media/v1/preview")
    val image = ExplorerEntry(name = "Block-carving_close-up_03.jpg", path = "$GIRIRAJ/i1", mediaType = "IMAGE", mediaId = "i1", sizeBytes = (3.4 * MB).toLong(), pixels = com.fieldrepository.app.explorer.data.ExplorerPixels(4032, 3024))
    val kiln = ExplorerEntry(name = "Kiln-firing_wide_07.mov", path = "$GIRIRAJ/v2", mediaType = "VIDEO", mediaId = "v2", sizeBytes = 220 * MB, previewPath = "/data/media/v2/preview")
    val audio = ExplorerEntry(name = "Section-D_Materials_Group-of-3.m4a", path = "$GIRIRAJ/a1", mediaType = "AUDIO", mediaId = "a1", sizeBytes = (14.2 * MB).toLong(), transcriptAvailable = true, previewPath = "/data/media/a1/preview")
    val dyeVat = ExplorerEntry(name = "Dye-vat_overhead_01.jpg", path = "$GIRIRAJ/i2", mediaType = "IMAGE", mediaId = "i2", sizeBytes = (4.1 * MB).toLong())
    val consent = ExplorerEntry(name = "Consent_form_signed.pdf", path = "$GIRIRAJ/d1", mediaType = "PDF", mediaId = "d1", sizeBytes = (0.8 * MB).toLong(), pages = 2)

    fun files(context: Context): List<ExplorerEntry> {
        val head = listOf(video, image, kiln, audio, dyeVat, consent)
        val videos = (3..9).map { ExplorerEntry(name = "Process_step_0$it.mp4", path = "$GIRIRAJ/pv$it", mediaType = "VIDEO", mediaId = "pv$it", sizeBytes = 8 * MB, previewPath = "/data/media/pv$it/preview") }
        val images = (3..31).map { ExplorerEntry(name = "Block-carving_detail_$it.jpg", path = "$GIRIRAJ/pi$it", mediaType = "IMAGE", mediaId = "pi$it", sizeBytes = 2 * MB) }
        val audios = (2..6).map { ExplorerEntry(name = "Section-${'A' + it}_Answers_0$it.m4a", path = "$GIRIRAJ/pa$it", mediaType = "AUDIO", mediaId = "pa$it", sizeBytes = 36 * MB, transcriptAvailable = true, previewPath = "/data/media/pa$it/preview") }
        val docs = listOf(ExplorerEntry(name = "Field_notes.pdf", path = "$GIRIRAJ/d2", mediaType = "PDF", mediaId = "d2", sizeBytes = MB))
        return (head + videos + images + audios + docs).map { withThumb(context, it) }
    }

    private val tints = mapOf(
        "i1" to (0xFFC9B2A1.toInt() to 0xFF8C6D5A.toInt()),
        "i2" to (0xFFD8C3A0.toInt() to 0xFF9A7B4F.toInt()),
    )

    private fun withThumb(context: Context, entry: ExplorerEntry): ExplorerEntry {
        if (entry.mediaType != "IMAGE") return entry
        val (a, b) = tints[entry.mediaId] ?: (0xFFB7C3CF.toInt() to 0xFF667788.toInt())
        val file = File(context.cacheDir, "design-thumbs/${entry.mediaId}.png")
        if (!file.isFile) {
            file.parentFile?.mkdirs()
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawRect(0f, 0f, 96f, 96f, Paint().apply { shader = LinearGradient(0f, 0f, 96f, 96f, a, b, Shader.TileMode.CLAMP) })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return entry.copy(url = file.toURI().toString())
    }

    val stats = ExplorerStats(
        files = 48,
        bytes = FOLDER_BYTES,
        byType = mapOf(
            "text" to ExplorerTypeStats(1, 40_000),
            "images" to ExplorerTypeStats(31, 61 * MB),
            "videos" to ExplorerTypeStats(9, 980 * MB),
            "audios" to ExplorerTypeStats(6, 196 * MB),
            "transcripts" to ExplorerTypeStats(12, 2 * MB),
            "documents" to ExplorerTypeStats(2, 2 * MB),
            "other" to ExplorerTypeStats(1, MB),
        ),
    )

    val workshopStats = ExplorerStats(
        files = 926,
        bytes = WORKSHOP_BYTES,
        byType = mapOf("images" to ExplorerTypeStats(700, (1.9 * GB).toLong()), "videos" to ExplorerTypeStats(160, (4.6 * GB).toLong()), "audios" to ExplorerTypeStats(66, (0.65 * GB).toLong())),
    )

    fun overview() = ExplorerOverview(
        path = WORKSHOP,
        title = "3rd Craft Toolkit",
        subtitle = "Bagru, Rajasthan · 14–22 Sep 2026 · 12 artisans",
        totals = ExplorerOverviewTotals(
            files = 926, bytes = WORKSHOP_BYTES, fields = ExplorerFilled(418, 480),
            transcripts = ExplorerTranscriptTotals(total = 112, ready = 104, translating = 6, failed = 2),
            review = ExplorerReviewTotals(approved = 9, pending = 3, total = 12),
        ),
        sections = ('A'..'H').map { ExplorerSectionHead(it.toString(), "Section $it") },
        artisans = listOf(
            artisan("mohan", "Mohan Lal Chhipa", "Block carving", 18, 22, 410 * MB, 3, ExplorerReviewTotals(0, 3, 3), "cc p m m p m m"),
            artisan("sunita", "Sunita Devi", "Dabu printing", 30, 61, 880 * MB, 9, ExplorerReviewTotals(0, 2, 2), "ccc p c p c m"),
            artisan("giriraj", "Giriraj Prasad Chhipa", "Dabu printing", 34, 48, FOLDER_BYTES, 12, ExplorerReviewTotals(3, 0, 3), "ccccc p cc"),
            artisan("ramesh", "Ramesh Chhipa", "Block carving", 40, 97, (1.9 * GB).toLong(), 14, ExplorerReviewTotals(4, 0, 4), "cccccccc"),
        ),
    )

    private fun artisan(id: String, name: String, craft: String, filled: Int, files: Int, bytes: Long, transcripts: Int, review: ExplorerReviewTotals, squares: String): ExplorerOverviewArtisan {
        val states = squares.replace(" ", "").map { when (it) { 'c' -> "complete"; 'p' -> "partial"; else -> "missing" } }
        return ExplorerOverviewArtisan(
            id = id, name = name, craft = craft, path = "$WORKSHOP/artisans/$id", fields = ExplorerFilled(filled, 40),
            media = ExplorerFileTotals(files, bytes), transcripts = transcripts, review = review,
            sections = states.mapIndexed { i, s -> ExplorerSectionState(('A' + i).toString(), s) },
        )
    }

    private fun cells(vararg values: String) = values.map { JsonPrimitive(it) }

    fun report(query: String?): ExplorerReport {
        val toolColumns = listOf("Tool", "Material", "Used in", "Linked artisans", "Linked workshops", "Size", "Origin", "Maker", "Age", "Condition", "Notes")
        val tools = listOf(
            cells("Indigo vat stirrer", "Teak wood", "Dyeing", "Giriraj P. Chhipa, Ramesh Chhipa", "3rd Craft Toolkit, Bagru", "1.2 m", "Bagru", "Local carpenter", "20 years", "Good", "Kept by the vat"),
            cells("Indigo mixing pot", "Clay", "Dyeing", "Giriraj P. Chhipa", "3rd Craft Toolkit", "40 L", "Sanganer", "Potter", "3 years", "Good", ""),
            cells("Wooden block (indigo set)", "Sheesham", "Printing", "Giriraj P. Chhipa, Sunita Devi", "3rd Craft Toolkit", "12 cm", "Pethapur", "Block maker", "8 years", "Worn edges", ""),
        )
        val more = (4..120).map { cells("Tool $it", "Wood", "Printing", "Ramesh Chhipa", "3rd Craft Toolkit", "", "", "", "", "", "") }
        val all = tools + more
        val matched = if (query.isNullOrBlank()) all else all.filter { row -> row.any { it.content.contains(query, ignoreCase = true) } }
        val numbers = if (query.isNullOrBlank()) null else listOf(14, 37, 81)
        return ExplorerReport(
            listOf(
                ExplorerSheet("Tools", toolColumns.map { JsonPrimitive(it) }, matched.take(20), totalRows = 120, matchedRows = matched.size, rowNumbers = numbers),
                ExplorerSheet("Processes", listOf(JsonPrimitive("Process")), if (query.isNullOrBlank()) listOf(cells("Dabu mud resist application")) else listOf(cells("Indigo dip")), totalRows = 9, matchedRows = 1),
                ExplorerSheet("Materials", listOf(JsonPrimitive("Material")), if (query.isNullOrBlank()) listOf(cells("Black clay")) else emptyList(), totalRows = 14, matchedRows = if (query.isNullOrBlank()) 14 else 0),
                ExplorerSheet("Artisans", listOf(JsonPrimitive("Name")), if (query.isNullOrBlank()) listOf(cells("Giriraj Prasad Chhipa")) else emptyList(), totalRows = 12, matchedRows = if (query.isNullOrBlank()) 12 else 0),
            ),
            query = query,
        )
    }

    enum class Mode { NORMAL, HANG, FAIL }

    class Api(private val context: Context, var mode: Mode = Mode.NORMAL) : ExplorerApi {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun tree(path: String, status: String?, cursor: String?, slim: Int): ExplorerTree {
            calls += "tree:$path"
            when (mode) {
                Mode.HANG -> awaitCancellation()
                Mode.FAIL -> throw IOException("offline")
                Mode.NORMAL -> Unit
            }
            return when (path) {
                "", ROOT -> ExplorerTree(path = ROOT, crumbs = crumbsRoot, entries = workshops, total = workshops.size, taxonomies = taxonomies, taxonomy = "workshop")
                WORKSHOP -> ExplorerTree(path = WORKSHOP, crumbs = crumbsWorkshop, info = ExplorerInfo("3rd Craft Toolkit", listOf(ExplorerInfoField("Place", "Bagru, Rajasthan"), ExplorerInfoField("Dates", "14–22 Sep 2026"))), entries = artisans, total = artisans.size, taxonomies = taxonomies, taxonomy = "workshop")
                EMPTY -> ExplorerTree(path = EMPTY, crumbs = crumbsWorkshop + ExplorerCrumb("Meera Devi", EMPTY), info = ExplorerInfo("Meera Devi"), entries = emptyList(), total = 0, taxonomies = taxonomies, taxonomy = "workshop")
                else -> ExplorerTree(path = GIRIRAJ, crumbs = crumbsWorkshop + ExplorerCrumb("Giriraj Prasad Chhipa", GIRIRAJ), info = info, entries = files(context), total = 48, taxonomies = taxonomies, taxonomy = "workshop")
            }
        }

        override suspend fun locate(type: String, id: String): JsonElement = JsonObject(mapOf("path" to JsonPrimitive(GIRIRAJ)))
        override suspend fun transcript(id: String) = ExplorerTranscript(mediaId = id, content = "")
        override suspend fun stats(path: String, include: String?): ExplorerStats {
            calls += "stats:$path"
            return if (path == WORKSHOP) workshopStats else stats
        }
        override suspend fun selectionStats(body: ExplorerStatsBody) = ExplorerStats(files = body.mediaIds.size, bytes = 0)
        val reportQueries: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        override suspend fun reportJson(path: String, q: String?, format: String, sheet: String?, offset: Int?): ExplorerReport {
            reportQueries += q
            return report(q)
        }
        override suspend fun languages() = ExplorerLanguages()
        override suspend fun manifestStream(path: String, include: String?, stream: Int): Response<ResponseBody> = Response.success("".toResponseBody(null))
        override suspend fun selectionManifestStream(body: ExplorerSelectionBody): Response<ResponseBody> = Response.success("".toResponseBody(null))
        override suspend fun media(id: String, format: String?, fallback: String?, range: String?): Response<ResponseBody> = Response.success("".toResponseBody(null))
        override suspend fun path(path: String, range: String?): Response<ResponseBody> = Response.success("".toResponseBody(null))
    }

    private fun job(id: String, title: String, kind: ExplorerJobKind, state: ExplorerJobState, at: Long, fileName: String) = ExplorerJob(
        id = id,
        request = ExplorerRequest(title = title, fileName = fileName, kind = kind, sources = if (kind == ExplorerJobKind.FOLDER) listOf(ExplorerSource(WORKSHOP)) else emptyList(), items = if (kind == ExplorerJobKind.FOLDER) emptyList() else listOf(ExplorerItem(path = fileName)), single = kind != ExplorerJobKind.FOLDER),
        createdAt = at,
        state = state,
    )

    fun running(now: Long): ExplorerJob = job("run", "3rd Craft Toolkit", ExplorerJobKind.FOLDER, ExplorerJobState.RUNNING, now, "3rd-Craft-Toolkit.zip").copy(
        doneBytes = (4.43 * GB).toLong(), totalBytes = WORKSHOP_BYTES, doneFiles = 574, totalFiles = 926,
        current = "Interview_Dabu-mud-resist_Take-1.mp4", currentBytes = 266 * MB, currentTotal = 701 * MB,
        speedBps = (6.2 * MB).toLong(), startedAt = now - 12 * 60_000, retrying = 1,
        recent = listOf(
            ExplorerRecent("Interview_Dabu-mud-resist_Take-1.mp4", 701 * MB, ExplorerRecentStatus.RUNNING, 0.38f),
            ExplorerRecent("Kiln-firing_wide_07.mov", 220 * MB, ExplorerRecentStatus.RETRYING),
            ExplorerRecent("Section-D_Materials_Group-of-3.mp4", (14.2 * MB).toLong(), ExplorerRecentStatus.SAVED),
            ExplorerRecent("Block-carving_close-up_03.jpg", (3.4 * MB).toLong(), ExplorerRecentStatus.SAVED),
        ),
    )

    fun runningSmall(now: Long): ExplorerJob = job("runsmall", "Giriraj Prasad Chhipa", ExplorerJobKind.FOLDER, ExplorerJobState.RUNNING, now, "Giriraj-Prasad-Chhipa.zip").copy(
        doneBytes = 770 * MB, totalBytes = FOLDER_BYTES, doneFiles = 30, totalFiles = 48, speedBps = 8 * MB, startedAt = now - 60_000,
        current = "Interview_Dabu-mud-resist_Take-1.mp4", currentBytes = 266 * MB, currentTotal = 701 * MB,
    )

    fun saved(now: Long, title: String = "Giriraj Prasad Chhipa", files: Int = 47, bytes: Long = (1.23 * GB).toLong(), folder: String = com.fieldrepository.app.explorer.portal.ExplorerPortal.DOWNLOAD_FOLDER): ExplorerJob =
        job("saved-$title", title, ExplorerJobKind.FOLDER, ExplorerJobState.DONE, now, "$title.zip").copy(
            doneBytes = bytes, totalBytes = bytes, doneFiles = files, totalFiles = files,
            result = ExplorerResult(location = "Downloads/$folder/$title.zip", uri = "content://downloads/1", saved = files, total = files, bytes = bytes),
        )

    fun failed(now: Long): ExplorerJob = job("failed", "Kiln-firing_wide_07.mov", ExplorerJobKind.FILE, ExplorerJobState.FAILED, now, "Kiln-firing_wide_07.mov").copy(error = "connection lost", totalBytes = 220 * MB)

    fun report(now: Long): ExplorerJob = job("report", "Report (.xlsx)", ExplorerJobKind.REPORT, ExplorerJobState.DONE, now, "3rd-Craft-Toolkit-Report.xlsx").copy(
        totalBytes = (2.1 * MB).toLong(), doneBytes = (2.1 * MB).toLong(),
        result = ExplorerResult(location = "Downloads/${com.fieldrepository.app.explorer.portal.ExplorerPortal.DOWNLOAD_FOLDER}/3rd-Craft-Toolkit-Report.xlsx", uri = "content://downloads/2", saved = 1, total = 1, bytes = (2.1 * MB).toLong(), message = "7 sheets"),
    )

    class More(private val base: ExplorerMore = DesignBoards.More()) : ExplorerMore by base {
        var overviewOf: ExplorerOverview? = null
        override suspend fun overview(path: String, status: String?) = overviewOf ?: base.overview(path, status)
        override suspend fun reportSheets(path: String, status: String?, q: String?) = com.fieldrepository.app.explorer.data.ExplorerReportSheets(
            path = path,
            sheets = listOf("Artisans" to 12, "Tools" to 120, "Processes" to 9, "Materials" to 14, "Media" to 48, "Transcripts" to 12, "Reviews" to 3, "Products" to 0)
                .map { (name, n) -> com.fieldrepository.app.explorer.data.ExplorerReportSheetInfo(key = name.lowercase(), name = name, count = n) },
        )
    }

    const val SEARCH_LOCAL = """{"query":"indigo mud resist","mode":"text","remote":{"status":"off"},"groups":[
{"type":"transcript","label":"Transcripts","total":21,"localTotal":21,"items":[{"type":"transcript","id":"t1","title":"Section D · Materials","subtitle":"Giriraj Prasad Chhipa · 3rd Craft Toolkit","snippet":"…the mud resists the indigo where we press the block…","highlights":[[4,7],[8,15],[20,26]],"recordDate":"2026-09-22","startMs":724000,"mediaType":"audio"}]},
{"type":"tool","label":"Tools","total":3,"localTotal":3,"items":[{"type":"tool","id":"tool1","title":"Indigo vat stirrer","subtitle":"2 artisans · 2 workshops"}]},
{"type":"process","label":"Processes","total":6,"localTotal":6,"items":[{"type":"process","id":"p1","title":"Dabu mud resist application","subtitle":"Giriraj Prasad Chhipa","snippet":"step 4 of 9"}]},
{"type":"artisan","label":"Artisans","total":8,"localTotal":8,"items":[]}
]}"""

    const val SEARCH_BOTH = """{"query":"indigo vat","mode":"text","remote":{"portal":"fieldRepository","label":"Field Repository","status":"ok"},"groups":[
{"type":"transcript","label":"Transcripts","total":19,"localTotal":6,"remoteTotal":13,"items":[{"portal":"fieldRepository","local":false,"type":"transcript","id":"rt1","title":"Bagru dyers · group interview","snippet":"…the indigo vat has to rest for three days…","highlights":[[4,10],[11,14]],"startMs":192000,"mediaType":"audio","openUrl":"https://field.example.org/data?media=rt1"}]},
{"type":"tool","label":"Tools","total":10,"localTotal":5,"remoteTotal":5,"items":[{"portal":"designer","local":true,"type":"tool","id":"tool1","title":"Indigo vat stirrer","subtitle":"2 artisans · 2 workshops"}]},
{"type":"artisan","label":"Artisans","total":8,"localTotal":3,"remoteTotal":5,"items":[{"portal":"designer","local":true,"type":"artisan","id":"giriraj","title":"Giriraj Prasad Chhipa","subtitle":"48 files here, 31 in Field Repository","alsoIn":{"portal":"fieldRepository","id":"fr-giriraj","files":31}}]}
]}"""

    const val SEARCH_NONE = """{"query":"indgo","mode":"text","remote":{"status":"off"},"groups":[],"suggestion":"indigo"}"""
}
