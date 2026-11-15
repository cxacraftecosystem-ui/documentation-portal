package com.fieldrepository.app.questionnaire

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.MaterialTheme
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
import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.data.ExplorerAudioState
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerMoreApi
import com.fieldrepository.app.explorer.portal.DesignTheme
import com.fieldrepository.app.explorer.shots.DesignBoards
import com.fieldrepository.app.explorer.ui.ExplorerMediaContent
import com.fieldrepository.app.explorer.ui.ExplorerMediaLoader
import com.fieldrepository.app.explorer.ui.audioItem
import com.fieldrepository.app.explorer.ui.audioLines
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.ui.InPlaceTake
import com.fieldrepository.app.questionnaire.ui.InPlaceTakes
import com.fieldrepository.app.questionnaire.ui.InstrumentQuestion
import com.fieldrepository.app.questionnaire.ui.InstrumentSection
import com.fieldrepository.app.questionnaire.ui.OverflowItem
import com.fieldrepository.app.questionnaire.ui.QuestionRow
import com.fieldrepository.app.questionnaire.ui.QuestionState
import com.fieldrepository.app.questionnaire.ui.QuestionStatus
import com.fieldrepository.app.questionnaire.ui.RecordUploadPair
import com.fieldrepository.app.questionnaire.ui.SectionCard
import com.fieldrepository.app.questionnaire.ui.SectionStatus
import com.fieldrepository.app.questionnaire.ui.TakesList
import com.fieldrepository.app.transcript.data.Transcript
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TakesInPlaceShotsTest {
    @get:Rule
    val compose = createComposeRule()

    private val section = InstrumentSection(
        "secE", "E", "Process of making and workflow",
        listOf(
            InstrumentQuestion("lE1", "E1", "Please describe the complete process from raw material to finished product, including the stages that contribute most to quality.", "secE"),
            InstrumentQuestion("lE2", "E2", "Which stages require the highest level of skill, precision or are hardest for beginners to learn, and why?", "secE"),
            InstrumentQuestion("lE3", "E3", "Which part of the work takes the most time or creates the greatest difficulty, and what change in the tool or process would make it easier, faster or safer?", "secE"),
            InstrumentQuestion("lE4", "E4", "Which stage causes the most wastage, and what happens to the waste generated there?", "secE")
        )
    )

    private val questionTake = InPlaceTake("r3", DesignBoards.AUDIO_ID, "r3", "15 Nov · 10:42", null, DesignBoards.AUDIO_MS)
    private val sectionTake = InPlaceTake("r1", DesignBoards.AUDIO_ID, "r1", QuestionnaireCopy.t("sectionTake") + " · 41:02", "15 Nov · 10:42", DesignBoards.AUDIO_MS)
    private val partTwo = InPlaceTake("r2", "aud-part-2", "r2", QuestionnaireCopy.t("takePart", "n" to 2) + " · 4:51", "15 Nov · 11:20", 291_000)
    private val localTake = InPlaceTake("local:c1", "local:c1", null, "15 Nov · 11:40", QuestionnaireCopy.s("upload.onDevice"), 192_000)
    private val withheldTake = InPlaceTake("r9", "aud-withheld", "r9", "15 Nov · 10:42", null, 192_000)

    private val loader = object : ExplorerMediaLoader {
        override suspend fun source(entry: ExplorerEntry): String? = "https://example.invalid/${entry.mediaId}"
        override suspend fun transcript(mediaId: String): Transcript? = if (mediaId == DesignBoards.AUDIO_ID) DesignBoards.audioTranscript() else null
    }

    @Before fun fakes() {
        ExplorerMoreApi.override = DesignBoards.More()
    }

    @After fun reset() {
        ExplorerMoreApi.override = null
        ExplorerAudio.stage(ExplorerAudioState())
    }

    private fun stage(mediaId: String) {
        val entry = InPlaceTakes.entry(questionTake.copy(mediaKey = mediaId), "E2")
        val lines = audioLines(if (mediaId == DesignBoards.AUDIO_ID) DesignBoards.audioTranscript() else null)
        val item = audioItem(entry, "https://example.invalid/$mediaId", lines) ?: return
        ExplorerAudio.stage(ExplorerAudioState(item = item, playing = true, positionMs = 1_100_000, durationMs = DesignBoards.AUDIO_MS, speed = 1f, line = lines.lastOrNull { it.startMs <= 1_100_000 }))
    }

    private fun save(board: String, name: String, bitmap: Bitmap) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "design-fidelity/$board")
        root.mkdirs()
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(board: String, variant: String? = null, tablet: Boolean = false, dark: Boolean = false, embed: Boolean = true, content: @Composable () -> Unit) {
        val widthDp = if (tablet) 1280 else 390
        val heightDp = if (tablet) 800 else 844
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
        val name = "impl-android" + if (suffix.isEmpty()) "" else "-$suffix"
        save(board, name, compose.onNodeWithTag("design.frame").captureToImage().asAndroidBitmap())
        if (embed) save(board, name.replace("impl-android", "impl-android-embed"), compose.onNodeWithTag("questionnaire.take.preview").captureToImage().asAndroidBitmap())
    }

    @Composable
    private fun questionOpen(take: InPlaceTake, state: QuestionState = QuestionState.QUESTION_TAKE) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionCard(
                section = section, status = SectionStatus(section.id, 2, 10, inSectionTake = false, typedOnly = false), open = true, byQuestion = false, onToggle = {},
                pair = {
                    RecordUploadPair(
                        recordLabel = QuestionnaireCopy.s("workspace.recordSection"), uploadLabel = QuestionnaireCopy.t("upload"),
                        onRecord = {}, onUpload = {}, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                    )
                }
            ) {
                QuestionRow(
                    question = section.questions[1], status = QuestionStatus(state, take.durationMs), byQuestion = false, locked = false,
                    typedPreview = null, typing = false, overflow = listOf(OverflowItem(QuestionnaireCopy.t("typeAnAnswer"), Icons.Filled.Keyboard) {}),
                    showPair = false, showUpload = true, onRecord = {}, onUpload = {}, onOpenTyped = {}, onOpenTakes = {},
                    playOpen = true,
                    inPlace = { TakesList(null, listOf(take), "Q:lE2", InPlaceTakes.openKey("Q:lE2", take), "E2", {}, loader = loader) }
                ) {}
            }
        }
    }

    @Composable
    private fun sectionOpen() {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionCard(
                section = section, status = SectionStatus(section.id, 4, 10, inSectionTake = true, typedOnly = false), open = true, byQuestion = false, onToggle = {},
                pair = {
                    RecordUploadPair(
                        recordLabel = QuestionnaireCopy.s("workspace.recordSection"), uploadLabel = QuestionnaireCopy.t("upload"),
                        onRecord = {}, onUpload = {}, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                    )
                }
            ) {
                TakesList(QuestionnaireCopy.t("takesHeading"), listOf(sectionTake, partTwo), "S:secE", InPlaceTakes.openKey("S:secE", sectionTake), "Section E", {}, onManage = {}, loader = loader)
                section.questions.forEach { q ->
                    QuestionRow(
                        question = q, status = QuestionStatus(QuestionState.IN_SECTION_TAKE), byQuestion = false, locked = false,
                        typedPreview = null, typing = false, overflow = listOf(OverflowItem(QuestionnaireCopy.t("typeAnAnswer"), Icons.Filled.Keyboard) {}),
                        showPair = false, showUpload = true, onRecord = {}, onUpload = {}, onOpenTyped = {}, onOpenTakes = {},
                        playOpen = q.displayCode == "E3"
                    ) {}
                }
            }
        }
    }

    @Composable
    private fun explorersOwn() {
        val entry = InPlaceTakes.entry(questionTake, "E2")
        ExplorerMediaContent(listOf(entry), 0, 0.0, 1, onSave = {}, onDismiss = {}, loader = loader, knownDurationMs = DesignBoards.AUDIO_MS, positioned = false)
    }

    @QFeature("TP9") @Test fun questionTakeOpen() { stage(DesignBoards.AUDIO_ID); shoot("takes-Main") { questionOpen(questionTake) } }
    @QFeature("TP9") @Test fun questionTakeOpenTablet() { stage(DesignBoards.AUDIO_ID); shoot("takes-Main", tablet = true) { questionOpen(questionTake) } }
    @QFeature("TP9") @Test fun explorersOwnPreview() { stage(DesignBoards.AUDIO_ID); shoot("takes-Main", "explorer", embed = false) { explorersOwn() } }
    @QFeature("TP9") @Test fun explorersOwnPreviewTablet() { stage(DesignBoards.AUDIO_ID); shoot("takes-Main", "explorer", tablet = true, embed = false) { explorersOwn() } }
    @QFeature("TP9") @Test fun sectionTakeOpen() { stage(DesignBoards.AUDIO_ID); shoot("takes-AndroidSectionTake") { sectionOpen() } }
    @QFeature("TP9") @Test fun sectionTakeOpenTablet() { stage(DesignBoards.AUDIO_ID); shoot("takes-AndroidSectionTake", tablet = true) { sectionOpen() } }
    @QFeature("TP9") @Test fun sectionTakeOpenDark() { stage(DesignBoards.AUDIO_ID); shoot("takes-AndroidSectionTake", dark = true) { sectionOpen() } }
    @QFeature("TP9") @Test fun questionTakeOpenDark() { stage(DesignBoards.AUDIO_ID); shoot("takes-AndroidDark", dark = true) { questionOpen(questionTake) } }
    @QFeature("TP9") @Test fun questionTakeOpenDarkTablet() { stage(DesignBoards.AUDIO_ID); shoot("takes-AndroidDark", tablet = true, dark = true) { questionOpen(questionTake) } }
    @QFeature("TP9") @Test fun explorersOwnPreviewDark() { stage(DesignBoards.AUDIO_ID); shoot("takes-AndroidDark", "explorer", dark = true, embed = false) { explorersOwn() } }
    @QFeature("TP9") @Test fun localTakeOpen() { stage(localTake.mediaKey); shoot("takes-AndroidStates", "local") { questionOpen(localTake, QuestionState.ON_DEVICE) } }
    @QFeature("TP9") @Test fun localTakeOpenDark() { stage(localTake.mediaKey); shoot("takes-AndroidStates", "local", dark = true) { questionOpen(localTake, QuestionState.ON_DEVICE) } }
    @QFeature("TP9") @Test fun withheldTakeOpen() { stage(withheldTake.mediaKey); shoot("takes-AndroidStates", "withheld") { questionOpen(withheldTake) } }
    @QFeature("TP9") @Test fun withheldTakeOpenDark() { stage(withheldTake.mediaKey); shoot("takes-AndroidStates", "withheld", dark = true) { questionOpen(withheldTake) } }
}
