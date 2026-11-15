package com.fieldrepository.app.questionnaire.portal

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QArtisanOption
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.ui.QuestionnaireHome
import com.fieldrepository.app.questionnaire.ui.QuestionnaireV2Nav
import com.fieldrepository.app.ui.FieldRepositoryTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterviewShotsTest {
    @get:Rule(order = 0)
    val compose = createComposeRule()

    private val errors = mutableListOf<String>()

    @get:Rule(order = 1)
    val evidence = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            val root = File(app.getExternalFilesDir(null), "design-fidelity/interview-failures")
            root.mkdirs()
            val name = "${description.methodName}-$device"
            runCatching {
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE) }.getOrElse { "tree unavailable: $it" }
            File(root, "$name.txt").writeText(listOf("errors: $errors", tree).joinToString("\n\n"))
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: Context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private fun arg(key: String): String? = args.getString(key)?.takeIf { it.isNotBlank() }
    private val device get() = arg("interviewDevice") ?: "phone"
    private val artisans: List<QArtisanOption>
        get() = arg("interviewArtisans").orEmpty().split(";").filter { it.contains("|") }.map {
            val (id, name) = it.split("|", limit = 2)
            QArtisanOption(id, name, "Puri")
        }

    @Before fun signIn() {
        assumeTrue("runs only against the interview CI stack (pass -e interviewToken)", arg("interviewToken") != null)
        val store = TokenStore(app)
        store.setToken(arg("interviewToken"))
        store.setUser(UserDto(id = arg("interviewUserId")!!, email = arg("interviewUserEmail")!!, name = arg("interviewUserName")!!, role = arg("interviewUserRole") ?: "RESEARCHER"))
        app.getSharedPreferences("questionnaire-v2", Context.MODE_PRIVATE).edit().putBoolean("batteryAsked", true).commit()
    }

    private fun t(key: String, vararg values: Pair<String, Any?>) = QuestionnaireCopy.t(key, *values)

    private fun home(dark: Boolean) {
        compose.setContent {
            FieldRepositoryTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
                    QuestionnaireHome(
                        artisans = artisans,
                        meId = arg("interviewUserId"),
                        meName = arg("interviewUserName"),
                        formsTab = {},
                        onNewForm = {},
                        onUpdateApp = {},
                        onError = { errors += it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    private val sessionIds: Map<String, String>
        get() = arg("interviewSessions").orEmpty().split(";").filter { it.contains("|") }.associate {
            val (note, id) = it.split("|", limit = 2)
            note to id
        }

    private fun shown(text: String) = hasText(text, substring = true, ignoreCase = true) or hasContentDescription(text, substring = true, ignoreCase = true)

    private fun waitFor(text: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(shown(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun node(text: String): SemanticsNodeInteraction {
        val merged = compose.onAllNodes(shown(text))
        return if (merged.fetchSemanticsNodes().isNotEmpty()) merged.onFirst() else compose.onAllNodes(shown(text), useUnmergedTree = true).onFirst()
    }

    private fun tap(text: String) {
        waitFor(text)
        runCatching { node(text).performScrollTo() }
        node(text).performClick()
        compose.waitForIdle()
    }

    private fun tapDescribed(description: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasContentDescription(description)).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun shot(board: String, dark: Boolean, variant: String = "") {
        compose.waitForIdle()
        Thread.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("no screenshot")
        val root = File(app.getExternalFilesDir(null), "design-fidelity/interview-$board")
        root.mkdirs()
        val name = listOf("impl-android", variant, device, if (dark) "dark" else "").filter { it.isNotEmpty() }.joinToString("-")
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun openSession(note: String, dark: Boolean, fallback: String? = null) {
        val id = sessionIds[note]
        if (id != null) QuestionnaireV2Nav.openServerSession.value = id
        home(dark)
        if (id == null) {
            val found = runCatching { waitFor(note, 15_000) }.isSuccess
            tap(if (found || fallback == null) note else fallback)
        }
        waitFor(t("review"))
    }

    private fun openSection(code: String, title: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(title, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        tap(title)
        waitFor(t("chipSection", "code" to code))
    }

    private fun list(dark: Boolean) {
        home(dark)
        waitFor(t("openToday"))
        waitFor(t("sessionsFinished"))
        shot("Main", dark)
    }

    private fun newSession(dark: Boolean) {
        home(dark)
        tap(t("newSession"))
        waitFor(t("fieldRecordBy"))
        waitFor(t("fromThisWorkshop"))
        shot("New", dark)
    }

    private fun closed(dark: Boolean) {
        openSession("CI board by section", dark)
        waitFor(t("questionsOfTotal", "answered" to 18, "total" to 108))
        waitFor(t("inSectionTake"))
        shot("SessionClosed", dark)
    }

    private fun bySection(dark: Boolean) {
        openSession("CI board by section", dark)
        openSection("E", "Process of making and workflow")
        waitFor(t("recordSection"))
        waitFor(t("typedPreview", "text" to "Rough cutting"))
        shot("Sections", dark)
    }

    private fun byQuestion(dark: Boolean) {
        openSession("CI board by question", dark)
        openSection("E", "Process of making and workflow")
        waitFor(t("record"))
        assumeNoSectionDock()
        shot("Question", dark)
    }

    private fun assumeNoSectionDock() {
        check(compose.onAllNodesWithText(t("recordSection")).fetchSemanticsNodes().isEmpty()) { "by question there is no Record section" }
    }

    private fun menu(dark: Boolean) {
        openSession("CI board by section", dark)
        tapDescribed(t("sessionOptions"))
        waitFor(t("recordByHelper"))
        waitFor(t("joinCodeAndQr"))
        waitFor(t("mergeIntoAnotherSession"))
        shot("Menu", dark)
    }

    private fun recording(dark: Boolean) {
        val (code, title) = if (dark) "F" to "Tools, equipment, ergonomics and technological improvement" else "E" to "Process of making and workflow"
        openSession("CI fresh", dark)
        openSection(code, title)
        tap(t("recordSection"))
        waitFor(t("recordingSection", "code" to code))
        check(compose.onAllNodesWithText(t("upload")).fetchSemanticsNodes().isEmpty()) { "Upload hides while recording" }
        shot("SectionRecording", dark)
        tap(t("stop"))
    }

    private fun upload(dark: Boolean) {
        openSession("CI fresh", dark)
        openSection("E", "Process of making and workflow")
        tap(t("upload"))
        waitFor(t("uploadRecordings"))
        shot("Upload", dark)
    }

    private fun answeredBy(dark: Boolean) {
        val (code, title) = if (dark) "C" to "Migration, mobility and changing livelihoods" else "B" to "Family, community, gender and knowledge transfer"
        openSession("CI group", dark, fallback = "Ramesh Maharana")
        openSection(code, title)
        tap(t("recordSection"))
        waitFor(t("recordingSection", "code" to code))
        Thread.sleep(1500)
        tap(t("stop"))
        waitFor(t("whoAnsweredTitle"))
        shot("AnsweredBy", dark)
    }

    private fun already(dark: Boolean) {
        openSession("CI board by section", dark)
        openSection("A", "Origin, history, place and personal journey")
        tap(t("recordSection"))
        waitFor(t("alreadyRecordedCode", "code" to "A"))
        shot("Already", dark)
    }

    private fun review(dark: Boolean) {
        openSession("CI board by section", dark)
        tap(t("review"))
        waitFor(t("questionsAnswered", "answered" to 18, "total" to 108))
        waitFor(t("keepGoing"))
        shot("Review", dark)
    }

    private fun readOnly(dark: Boolean) {
        openSession("CI board finished", dark)
        waitFor("Read only.")
        check(compose.onAllNodesWithText(t("recordSection")).fetchSemanticsNodes().isEmpty()) { "a finished session offers no Record section" }
        shot("States", dark, "read-only")
    }

    @QFeature("Q7") @Test fun listLight() = list(false)
    @QFeature("Q7") @Test fun listDark() = list(true)
    @QFeature("Q1") @Test fun newSessionLight() = newSession(false)
    @QFeature("Q1") @Test fun newSessionDark() = newSession(true)
    @QFeature("Q2") @QFeature("Q5") @Test fun closedLight() = closed(false)
    @QFeature("Q2") @QFeature("Q5") @Test fun closedDark() = closed(true)
    @QFeature("Q2") @QFeature("Q3") @Test fun bySectionLight() = bySection(false)
    @QFeature("Q2") @QFeature("Q3") @Test fun bySectionDark() = bySection(true)
    @QFeature("Q1") @QFeature("Q3") @Test fun byQuestionLight() = byQuestion(false)
    @QFeature("Q1") @QFeature("Q3") @Test fun byQuestionDark() = byQuestion(true)
    @QFeature("Q8") @Test fun menuLight() = menu(false)
    @QFeature("Q8") @Test fun menuDark() = menu(true)
    @QFeature("Q3") @Test fun recordingLight() = recording(false)
    @QFeature("Q3") @Test fun recordingDark() = recording(true)
    @QFeature("Q4") @Test fun uploadLight() = upload(false)
    @QFeature("Q4") @Test fun uploadDark() = upload(true)
    @QFeature("Q3") @Test fun answeredByLight() = answeredBy(false)
    @QFeature("Q3") @Test fun answeredByDark() = answeredBy(true)
    @Test fun alreadyLight() = already(false)
    @Test fun alreadyDark() = already(true)
    @QFeature("Q6") @Test fun reviewLight() = review(false)
    @QFeature("Q6") @Test fun reviewDark() = review(true)
    @QFeature("Q6") @Test fun readOnlyLight() = readOnly(false)
    @QFeature("Q6") @Test fun readOnlyDark() = readOnly(true)
}
