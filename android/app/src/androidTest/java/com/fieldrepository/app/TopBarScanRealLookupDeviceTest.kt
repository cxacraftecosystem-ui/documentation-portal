package com.fieldrepository.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.ArtisanCardCode
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.FieldRepositoryApi
import com.fieldrepository.app.data.OfflineOutbox
import com.fieldrepository.app.data.PendingEntry
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.data.dispatchScan
import com.fieldrepository.app.ui.AppSearchHeader
import com.fieldrepository.app.ui.FieldRepositoryTheme
import com.fieldrepository.app.ui.TopBarScanSheet
import com.fieldrepository.app.ui.fieldScanServices
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.UUID

/**
 * The top-bar scanner on a device with the REAL header, the REAL field services and the REAL offline
 * lookup of cards already queued in this phone's outbox (REQUIREMENTS_2026-10-20 §6.1). The API is a
 * closed loopback port, so every network call fails as "no signal" and nothing is signed in or sent.
 */
@RunWith(AndroidJUnit4::class)
class TopBarScanRealLookupDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aCardWaitingToSyncIsNamedOfflineRatherThanUnknown() {
        val card = (ArtisanCardCode.parse(CARD) as ArtisanCardCode.Result.Ok).card
        val repository = FieldRepository(
            Retrofit.Builder()
                .baseUrl("http://127.0.0.1:9/")
                .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(FieldRepositoryApi::class.java),
            TokenStore(context),
        )
        val services = fieldScanServices(repository, context)
        val entry = PendingEntry(
            id = "device-test-${UUID.randomUUID()}",
            type = "product",
            payloadJson = """{"artisanId":"${ArtisanCardCode.pendingValue(card)}","productName":"Indigo saree"}""",
            label = "Indigo saree",
            createdAt = Instant.now().toString(),
        )
        runBlocking { OfflineOutbox.enqueue(context, entry) }
        try {
            rule.setContent {
                FieldRepositoryTheme(darkTheme = false) {
                    var open by remember { mutableStateOf(false) }
                    AppSearchHeader(appName = "Field Repository", logo = {}, onOpenSearch = {}, onScan = { open = true })
                    if (open) {
                        TopBarScanSheet(
                            dispatch = { raw -> dispatchScan(raw, services) },
                            onAction = { null },
                            onDismiss = { open = false },
                            scanner = { _, _, _ -> },
                        )
                    }
                }
            }
            rule.onNodeWithContentDescription("Scan a code").performClick()
            rule.onNodeWithTag("scan-typed").performTextInput(CARD)
            rule.onNodeWithTag("scan-lookup").performClick()
            val said = ArtisanCardCode.pendingChip(ArtisanCardCode.pendingValue(card))
            rule.waitUntil(15_000) { rule.onAllNodes(hasText(said)).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            runBlocking { OfflineOutbox.discard(context, entry.id) }
        }
    }

    private companion object {
        /** A well-formed Designer Repository card (the shared scan fixture's). */
        const val CARD = "D-7K3QM-9XA2F-HQ"
    }
}
