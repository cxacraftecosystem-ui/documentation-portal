package com.fieldrepository.app.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerJob
import com.fieldrepository.app.explorer.data.ExplorerJobKind
import com.fieldrepository.app.explorer.data.ExplorerJobState
import com.fieldrepository.app.explorer.data.ExplorerRequest
import com.fieldrepository.app.explorer.data.ExplorerResult
import com.fieldrepository.app.explorer.data.ExplorerSize
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.explorer.ui.DownloadChoice
import com.fieldrepository.app.explorer.ui.DownloadSheet
import com.fieldrepository.app.explorer.ui.DownloadsSheet
import com.fieldrepository.app.explorer.ui.FileRow
import com.fieldrepository.app.explorer.ui.SizeLoad
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExplorerSheetsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val size = SizeLoad.Known(ExplorerSize(files = 926, bytes = 7_150_000_000, largest = 701L * 1024 * 1024))

    @QFeature("X2")
    @Test fun onMobileDataTheSheetWarnsWithTheSizeAndOffersWaitForWifi() {
        val started = mutableListOf<Pair<DownloadChoice, Boolean>>()
        compose.setContent {
            MaterialTheme {
                DownloadSheet(
                    title = "Bagru workshop",
                    selection = false,
                    size = size,
                    metered = true,
                    online = true,
                    freeBytes = 64L * 1024 * 1024 * 1024,
                    onStart = { choice, wifiOnly -> started += choice to wifiOnly },
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.sheet.metered").assertIsDisplayed()
        compose.onNodeWithText(ExplorerText.useMobileData).assertIsDisplayed()
        compose.onNodeWithText(ExplorerText.waitForWifi).performClick()
        compose.waitForIdle()
        assertEquals(listOf(DownloadChoice.EVERYTHING to true), started)
    }

    @QFeature("X2")
    @Test fun tooLittleFreeSpaceIsSaidBeforeStarting() {
        compose.setContent {
            MaterialTheme {
                DownloadSheet(
                    title = "Bagru workshop",
                    selection = false,
                    size = size,
                    metered = false,
                    online = true,
                    freeBytes = 1_000_000,
                    onStart = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.sheet.space").assertIsDisplayed()
    }

    @QFeature("X10")
    @Test fun aFinishedDownloadOffersOpenAndShare() {
        val opened = mutableListOf<String>()
        val shared = mutableListOf<String>()
        val job = ExplorerJob(
            id = "j1",
            request = ExplorerRequest(title = "Bagru workshop", fileName = "Bagru.zip", kind = ExplorerJobKind.FOLDER),
            createdAt = 1L,
            state = ExplorerJobState.DONE,
            result = ExplorerResult(uri = "content://downloads/1", mimeType = "application/zip", saved = 3, total = 3),
        )
        compose.setContent {
            MaterialTheme {
                DownloadsSheet(
                    jobs = listOf(job),
                    onCancel = {},
                    onRetry = {},
                    onOpen = { opened += it.id },
                    onShare = { shared += it.id },
                    onDismissJob = {},
                    onClearFinished = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.job.done").assertIsDisplayed()
        compose.onNodeWithTag("explorer.job.open").performClick()
        compose.onNodeWithText(ExplorerText.share).performClick()
        compose.waitForIdle()
        assertEquals(listOf("j1"), opened)
        assertEquals(listOf("j1"), shared)
    }

    @QFeature("X10")
    @Test fun eachFileRowKeepsItsOwnSpinnerAndATapOpensTheFile() {
        val first = ExplorerEntry(name = "Dabu.mp4", path = "w/v1", mediaId = "v1", mediaType = "VIDEO", sizeBytes = 701L * 1024 * 1024)
        val second = ExplorerEntry(name = "Block.jpg", path = "w/p1", mediaId = "p1", mediaType = "IMAGE", sizeBytes = 2_700_000)
        val running = ExplorerJob(
            id = "s1",
            request = ExplorerRequest(title = "Dabu.mp4", fileName = "Dabu.mp4", kind = ExplorerJobKind.FILE, single = true),
            createdAt = 1L,
            state = ExplorerJobState.RUNNING,
            doneBytes = 350L * 1024 * 1024,
            totalBytes = 701L * 1024 * 1024,
        )
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column {
                    FileRow(
                        entry = first, selecting = false, selected = false, textOpen = false, transcriptOpen = false, transcript = null,
                        saveJob = running, online = true, onOpen = { opened += "v1" }, onLongPress = {}, onToggle = {}, onText = {},
                        onTranscript = {}, onPreview = {}, onSave = {}, onCancelSave = {},
                    )
                    FileRow(
                        entry = second, selecting = false, selected = false, textOpen = false, transcriptOpen = false, transcript = null,
                        saveJob = null, online = true, onOpen = { opened += "p1" }, onLongPress = {}, onToggle = {}, onText = {},
                        onTranscript = {}, onPreview = {}, onSave = {}, onCancelSave = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.save.cancel.v1").assertIsDisplayed()
        compose.onNodeWithTag("explorer.save.p1").assertIsDisplayed()
        compose.onNodeWithText("Block.jpg").performClick()
        compose.waitForIdle()
        assertEquals(listOf("p1"), opened)
    }
}
