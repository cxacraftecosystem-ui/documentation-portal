package com.fieldrepository.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.FieldRepositoryApi
import com.fieldrepository.app.data.QuestionnaireV2
import com.fieldrepository.app.data.ScanAction
import com.fieldrepository.app.data.ScanServices
import com.fieldrepository.app.data.ScanTarget
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.data.dispatchScan
import com.fieldrepository.app.ui.AppSearchHeader
import com.fieldrepository.app.ui.FieldRepositoryTheme
import com.fieldrepository.app.ui.TopBarScanSheet
import com.fieldrepository.app.ui.fieldScanServices
import com.fieldrepository.app.ui.runFieldScanAction
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID

/**
 * On a device (REQUIREMENTS_2026-10-20 §6.3): the header's scan button opens the top-bar sheet, and a
 * typed session code is answered with Join, and pressing it starts the session. The services are
 * the app's own; the API is a closed loopback port, so nothing is signed in or sent.
 */
@RunWith(AndroidJUnit4::class)
class TopBarScanDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    /**
     * The app's own services (`fieldScanServices`), not a fake: the session code and the session QR
     * are classified by the real dispatcher with the real card rule. Their repository is pointed at a
     * closed loopback port, so a lookup that reaches for the network fails as "no signal".
     */
    private val services: ScanServices = fieldScanServices(
        FieldRepository(
            Retrofit.Builder()
                .baseUrl("http://127.0.0.1:9/")
                .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(FieldRepositoryApi::class.java),
            TokenStore(InstrumentationRegistry.getInstrumentation().targetContext),
        ),
        InstrumentationRegistry.getInstrumentation().targetContext,
    )

    @Test
    fun theHeaderScanButtonOpensTheSheetAndATypedSessionCodeStartsTheSession() {
        var joined: String? = null
        rule.setContent {
            FieldRepositoryTheme(darkTheme = false) {
                var open by remember { mutableStateOf(false) }
                AppSearchHeader(appName = "Field Repository", logo = {}, onOpenSearch = {}, onScan = { open = true })
                if (open) {
                    TopBarScanSheet(
                        dispatch = { raw -> dispatchScan(raw, services) },
                        onAction = { action ->
                            joined = (action as? ScanAction.JoinSession)?.joinCode
                            null
                        },
                        onDismiss = { open = false },
                        scanner = { _, _, _ -> },
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Scan a code").performClick()
        rule.onNodeWithTag("top-bar-scan-sheet").assertIsDisplayed()
        rule.onNodeWithTag("scan-typed").performTextInput("ab3-k9z")
        rule.onNodeWithTag("scan-lookup").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("scan-action") }
        rule.onNodeWithTag("scan-action").assertTextEquals("Join").performClick()
        rule.waitUntil(5_000) { joined != null }
        org.junit.Assert.assertEquals("AB3K9Z", joined)
    }

    /**
     * The real action, not a fake: a typed session QR (the payload a researcher's phone shows) is
     * answered with Join, and pressing it runs `runFieldScanAction`, which creates the session in this
     * phone's outbox under the QR's `clientKey` (`outbox.startSession`) and hands back the session to
     * open. Offline by design, so nothing is signed in or sent for it to work.
     */
    @Test
    fun aScannedSessionQrStartsTheSessionInTheOutbox() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clientKey = "device-test-${UUID.randomUUID()}"
        val qr = """{"v":1,"clientKey":"$clientKey","questionnaireId":"cmq0questionnaire01","sessionDate":"2026-10-20","participants":[]}"""
        var opened: ScanTarget? = null
        rule.setContent {
            FieldRepositoryTheme(darkTheme = false) {
                TopBarScanSheet(
                    dispatch = { raw -> dispatchScan(raw, services) },
                    onAction = { action -> runFieldScanAction(context, action, meId = null) { opened = it } },
                    onDismiss = {},
                    scanner = { _, _, _ -> },
                )
            }
        }
        rule.onNodeWithTag("scan-typed").performTextInput(qr)
        rule.onNodeWithTag("scan-lookup").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("scan-action") }
        rule.onNodeWithTag("scan-action").assertTextEquals("Join").performClick()
        rule.waitUntil(10_000) { opened != null }
        org.junit.Assert.assertEquals(ScanTarget.Session(clientKey), opened)
        val unit = runBlocking { QuestionnaireV2.get(context).outbox.unit(clientKey) }
        org.junit.Assert.assertNotNull("the session is in the outbox", unit)
        org.junit.Assert.assertEquals("cmq0questionnaire01", unit?.questionnaireId)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
