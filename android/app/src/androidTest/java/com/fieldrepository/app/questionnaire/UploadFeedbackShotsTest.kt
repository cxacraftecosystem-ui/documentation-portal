package com.fieldrepository.app.questionnaire

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.explorer.portal.DesignTheme
import com.fieldrepository.app.questionnaire.data.QChipForm
import com.fieldrepository.app.questionnaire.data.QChipPerson
import com.fieldrepository.app.questionnaire.data.QChipSession
import com.fieldrepository.app.questionnaire.data.QChipTarget
import com.fieldrepository.app.questionnaire.data.QChips
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.RecordingSort
import com.fieldrepository.app.questionnaire.ui.AttachKind
import com.fieldrepository.app.questionnaire.ui.InPlaceTake
import com.fieldrepository.app.questionnaire.ui.InstrumentQuestion
import com.fieldrepository.app.questionnaire.ui.InstrumentSection
import com.fieldrepository.app.questionnaire.ui.PickedFile
import com.fieldrepository.app.questionnaire.ui.RecordingRow
import com.fieldrepository.app.questionnaire.ui.RecordingSortChoice
import com.fieldrepository.app.questionnaire.ui.SectionCard
import com.fieldrepository.app.questionnaire.ui.SectionStatus
import com.fieldrepository.app.questionnaire.ui.UploadDuplicate
import com.fieldrepository.app.questionnaire.ui.UploadRow
import com.fieldrepository.app.questionnaire.ui.UploadSheetBody
import com.fieldrepository.app.questionnaire.ui.WholeSessionBlock
import com.fieldrepository.app.ui.SelectOption
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class UploadFeedbackShotsTest {
    @get:org.junit.Rule
    val compose = createComposeRule()

    private val sections = listOf(
        InstrumentSection("secE", "E", "Process of making and workflow", listOf(InstrumentQuestion("lE1", "E1", "Describe the process.", "secE"))),
        InstrumentSection("secF", "F", "Tools and equipment", listOf(InstrumentQuestion("lF1", "F1", "Which tools?", "secF")))
    )
    private val uploaded = InPlaceTake("r1", "aud-1", "r1", "Take 1 · 41:02", "13 Nov · 10:42", 2_462_000)
    private val uploading = InPlaceTake("local:c1", "local:c1", null, "Take 2 · 42:10", "Uploading 40%", 2_530_000, "/data/user/0/c1.m4a")

    private fun file(name: String, sha: String, ms: Long) = PickedFile("/x/$name", sha, 10, "audio/mp4", "m4a", Instant.parse("2026-11-13T05:12:00Z"), ms, name = name)

    private val rows = listOf(
        UploadRow(file("Sandesh_part1.m4a", "sha1", 2_530_000)),
        UploadRow(file("Sandesh_part1.m4a", "sha1", 2_530_000), UploadDuplicate.InThisList),
        UploadRow(file("Sandesh_part2.m4a", "sha2", 1_083_000), UploadDuplicate.OnThisPhone),
        UploadRow(file("Meera_intro.m4a", "sha3", 412_000), UploadDuplicate.OnServer(LocalDate.parse("2026-11-13"), "s-1", "r-3", sameSession = true)),
        UploadRow(file("Sandesh_old.m4a", "sha4", 1_920_000), UploadDuplicate.OnServer(LocalDate.parse("2026-11-13"), "s-2", "r-4", sessionTitle = "Sandesh P · Bagru · 13 Nov"))
    )

    private fun chips(session: String, code: String, title: String, artisan: String, day: String, captured: String, ms: Long) = QChips(
        session = QChipSession("s-$artisan", session), questionnaire = QChipForm("f1", "4th Craft Toolkit Workshop"),
        targets = listOf(QChipTarget(kind = "SECTION", sectionId = "sec$code", sectionCode = code, sectionTitle = title)),
        artisans = listOf(QChipPerson("a-$artisan", artisan)), sessionDate = day, capturedOn = captured, durationMs = ms
    )

    private val recordings = listOf(
        QRecording(
            id = "r1", sessionId = "s-1", capturedAt = "2026-11-13T05:12:00Z", createdAt = "2026-11-16T06:40:00Z",
            chips = chips("Sandesh P · Bagru · 13 Nov", "E", "Process of making", "Sandesh P", "2026-11-13", "2026-11-13", 2_530_000)
        ),
        QRecording(
            id = "r2", sessionId = "s-2", capturedAt = "2026-11-15T09:30:00Z", createdAt = "2026-11-15T10:05:00Z",
            chips = chips("Meera Devi · Bagru · 15 Nov", "F", "Tools and equipment", "Meera Devi", "2026-11-15", "2026-11-15", 1_083_000)
        ),
        QRecording(
            id = "r3", sessionId = "s-3", capturedAt = "2026-11-14T07:00:00Z", createdAt = "2026-11-14T07:45:00Z",
            chips = chips("Giriraj · Bagru · 14 Nov", "D", "Materials", "Giriraj", "2026-11-14", "2026-11-14", 412_000)
        )
    )

    private fun save(board: String, name: String, bitmap: Bitmap) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "design-fidelity/$board")
        root.mkdirs()
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(board: String, variant: String? = null, tablet: Boolean = false, dark: Boolean = false, phoneHeight: Int = 844, content: @Composable () -> Unit) {
        val widthDp = if (tablet) 1280 else 390
        val heightDp = if (tablet) 800 else phoneHeight
        compose.setContent {
            DesignTheme(dark) {
                CompositionLocalProvider(LocalDensity provides Density(if (tablet) 1f else 2f, 1f)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        Box(Modifier.requiredSize(widthDp.dp, heightDp.dp).background(MaterialTheme.colorScheme.background).testTag("design.frame"), contentAlignment = Alignment.TopCenter) {
                            content()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(600)
        compose.waitForIdle()
        val suffix = listOfNotNull(variant, if (tablet) "tablet" else null, if (dark) "dark" else null).joinToString("-")
        save(board, "impl-android" + if (suffix.isEmpty()) "" else "-$suffix", compose.onNodeWithTag("design.frame").captureToImage().asAndroidBitmap())
    }

    @Composable
    private fun wholeSession() {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            sections.forEach { s ->
                SectionCard(section = s, status = SectionStatus(s.id, 0, 10, inSectionTake = false, typedOnly = false), open = false, byQuestion = false, onToggle = {}, pair = {}) {}
            }
            WholeSessionBlock(takes = listOf(uploaded, uploading), open = null, showPair = true, onOpen = {}, onRecord = {}, onUpload = {})
        }
    }

    @Composable
    private fun duplicates() {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)), contentAlignment = Alignment.TopCenter) {
            Surface(
                color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.padding(top = 32.dp).widthIn(max = 640.dp).fillMaxWidth().fillMaxSize()
            ) {
                UploadSheetBody(
                    rows = rows, checking = false, attach = AttachKind.SECTION, sectionCode = "E", sectionQuestions = 10,
                    questionOptions = listOf(SelectOption("lE1", "E1 · Describe the process.")), questionId = null, sessionDate = LocalDate.parse("2026-11-13"),
                    onAttach = {}, onQuestion = {}, onChoose = {}, onRemove = {}, onUpload = {}, onDismiss = {},
                    modifier = Modifier.padding(top = 20.dp)
                )
            }
        }
    }

    @Composable
    private fun recordingsList(sort: String) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(QuestionnaireCopy.t("recordingsTitle"), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            RecordingSortChoice(sort) {}
            RecordingSort.sorted(recordings, sort).forEach { r -> RecordingRow(r, null, null, onPlay = {}, onOpenSession = {}) }
        }
    }

    @QFeature("Q3") @Test fun wholeSessionPhone() = shoot("whole-AndroidSession") { wholeSession() }
    @QFeature("Q3") @Test fun wholeSessionTablet() = shoot("whole-AndroidSession", tablet = true) { wholeSession() }
    @QFeature("Q3") @Test fun wholeSessionPhoneDark() = shoot("whole-AndroidSession", dark = true) { wholeSession() }
    @QFeature("Q3") @Test fun wholeSessionTabletDark() = shoot("whole-AndroidSession", tablet = true, dark = true) { wholeSession() }
    @QFeature("Q4") @Test fun duplicatesPhone() = shoot("upload-AndroidDuplicates", phoneHeight = 1000) { duplicates() }
    @QFeature("Q4") @Test fun duplicatesTablet() = shoot("upload-AndroidDuplicates", tablet = true) { duplicates() }
    @QFeature("Q4") @Test fun duplicatesPhoneDark() = shoot("upload-AndroidDuplicates", dark = true, phoneHeight = 1000) { duplicates() }
    @QFeature("Q4") @Test fun duplicatesTabletDark() = shoot("upload-AndroidDuplicates", tablet = true, dark = true) { duplicates() }
    @QFeature("E5") @Test fun sortAddedPhone() = shoot("recordings-AndroidSort", "added") { recordingsList(RecordingSort.ADDED) }
    @QFeature("E5") @Test fun sortAddedTablet() = shoot("recordings-AndroidSort", "added", tablet = true) { recordingsList(RecordingSort.ADDED) }
    @QFeature("E5") @Test fun sortRecordedPhone() = shoot("recordings-AndroidSort", "recorded") { recordingsList(RecordingSort.RECORDED) }
    @QFeature("E5") @Test fun sortRecordedTablet() = shoot("recordings-AndroidSort", "recorded", tablet = true) { recordingsList(RecordingSort.RECORDED) }
    @QFeature("E5") @Test fun sortAddedPhoneDark() = shoot("recordings-AndroidSort", "added", dark = true) { recordingsList(RecordingSort.ADDED) }
}
