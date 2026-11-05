package com.fieldrepository.app.explorer

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import com.fieldrepository.app.explorer.ui.PreflightSheet
import com.fieldrepository.app.explorer.ui.ProgressCard
import com.fieldrepository.app.explorer.ui.RecentFilesCard
import com.fieldrepository.app.explorer.data.ExplorerRecent
import com.fieldrepository.app.explorer.data.ExplorerRecentStatus
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
        compose.onNodeWithText("Block").performClick()
        compose.waitForIdle()
        assertEquals(listOf("p1"), opened)
    }

    @QFeature("X10")
    @Test fun aSavedFileRowIsShadedAndOffersOpen() {
        val entry = ExplorerEntry(name = "Dabu.mp4", path = "w/v1", mediaId = "v1", mediaType = "VIDEO", sizeBytes = 701L * 1024 * 1024)
        val saved = ExplorerJob(
            id = "s2",
            request = ExplorerRequest(title = "Dabu.mp4", fileName = "Dabu.mp4", kind = ExplorerJobKind.FILE, single = true, source = entry.key),
            createdAt = 1L,
            state = ExplorerJobState.DONE,
            result = ExplorerResult(uri = "content://downloads/2", saved = 1, total = 1),
        )
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                FileRow(
                    entry = entry, selecting = false, selected = false, textOpen = false, transcriptOpen = false, transcript = null,
                    saveJob = null, online = true, onOpen = {}, onLongPress = {}, onToggle = {}, onText = {},
                    onTranscript = {}, onPreview = {}, onSave = {}, onCancelSave = {}, savedJob = saved, onOpenSaved = { opened += "v1" },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.save.open.v1").performClick()
        compose.waitForIdle()
        assertEquals(listOf("v1"), opened)
    }

    @QFeature("X10")
    @Test fun theProgressCardShowsBytesFilesSpeedTimeAndTheCurrentFile() {
        val job = ExplorerJob(
            id = "z1",
            request = ExplorerRequest(title = "3rd Craft Toolkit", fileName = "Toolkit.zip", kind = ExplorerJobKind.FOLDER),
            createdAt = 1L,
            state = ExplorerJobState.RUNNING,
            startedAt = System.currentTimeMillis() - 60_000,
            doneBytes = 620, totalBytes = 1_000, doneFiles = 574, totalFiles = 926,
            current = "W/Interview_Take-1.mp4", currentBytes = 266, currentTotal = 701,
            speedBps = 6_500_000, retrying = 1,
            recent = listOf(
                ExplorerRecent("Interview_Take-1.mp4", 701, ExplorerRecentStatus.RUNNING, 0.38f),
                ExplorerRecent("Kiln-firing_wide_07.mov", 220, ExplorerRecentStatus.RETRYING),
                ExplorerRecent("Block-carving_close-up_03.jpg", 3_400_000, ExplorerRecentStatus.SAVED),
            ),
        )
        val paused = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Column {
                    ProgressCard(job, network = "Wi-Fi", onPause = { paused += "z1" }, onResume = {}, onCancel = {})
                    RecentFilesCard(job)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Downloading 3rd Craft Toolkit").assertIsDisplayed()
        compose.onNodeWithText("62%").assertIsDisplayed()
        compose.onAllNodesWithText("574 of 926").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("explorer.progress.now").assertIsDisplayed()
        compose.onNodeWithText("1 file failed and will retry at the end.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("explorer.recent.saved").assertExists()
        compose.onNodeWithTag("explorer.recent.retrying").assertExists()
        compose.onNodeWithTag("explorer.progress.pause").performClick()
        compose.waitForIdle()
        assertEquals(listOf("z1"), paused)
    }

    @QFeature("X2")
    @Test fun thePreflightSheetOffersWifiAndASmallerDownload() {
        val plan = com.fieldrepository.app.explorer.data.ExplorerPreflight.of(
            size = ExplorerSize(files = 926, bytes = 7_150_000_000, largest = 701L * 1024 * 1024, byKind = mapOf(
                com.fieldrepository.app.explorer.data.ExplorerKind.VIDEOS to com.fieldrepository.app.explorer.data.ExplorerTypeStats(57, 5_250_000_000),
                com.fieldrepository.app.explorer.data.ExplorerKind.IMAGES to com.fieldrepository.app.explorer.data.ExplorerTypeStats(869, 1_900_000_000),
            )),
            link = com.fieldrepository.app.explorer.data.ExplorerLink(online = true, metered = true),
            freeBytes = 64L * 1024 * 1024 * 1024,
            speedBps = null,
            location = "Downloads/DesignWorkshop/Toolkit.zip",
        )
        val started = mutableListOf<Pair<Boolean, String?>>()
        compose.setContent {
            MaterialTheme {
                PreflightSheet(title = "3rd Craft Toolkit", plan = plan, onStart = { w, i -> started += w to i }, onDismiss = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("explorer.preflight.metered").assertExists()
        compose.onNodeWithTag("explorer.preflight.location").assertExists()
        compose.onNodeWithTag("explorer.preflight.smaller").performClick()
        compose.onNodeWithTag("explorer.preflight.start").performClick()
        compose.waitForIdle()
        assertEquals(1, started.size)
        assertEquals(true, started[0].first)
        assertEquals(false, started[0].second.isNullOrBlank())
    }

    @QFeature("X10")
    @Test fun recentFilesOffersTheWholeList() {
        val job = ExplorerJob(
            id = "z2",
            request = ExplorerRequest(title = "Toolkit", fileName = "Toolkit.zip", kind = ExplorerJobKind.FOLDER),
            createdAt = 1L,
            state = ExplorerJobState.RUNNING,
            doneFiles = 3, totalFiles = 926,
            recent = listOf(ExplorerRecent("a.jpg", 10, ExplorerRecentStatus.SAVED)),
        )
        var asked = 0
        compose.setContent { MaterialTheme { RecentFilesCard(job, onAll = { asked++ }) } }
        compose.waitForIdle()
        compose.onNodeWithText("All 926").performClick()
        compose.waitForIdle()
        assertEquals(1, asked)
    }

    @QFeature("X2")
    @Test fun thePreflightSheetLetsYouPickAnotherFolder() {
        val plan = com.fieldrepository.app.explorer.data.ExplorerPreflight.of(
            size = ExplorerSize(files = 926, bytes = 7_150_000_000, largest = 701L * 1024 * 1024),
            link = com.fieldrepository.app.explorer.data.ExplorerLink(online = true, metered = false),
            freeBytes = 64L * 1024 * 1024 * 1024,
            speedBps = null,
            location = "Field work/Toolkit.zip",
        )
        var changed = 0
        var reset = 0
        compose.setContent {
            MaterialTheme {
                PreflightSheet(title = "Toolkit", plan = plan, onStart = { _, _ -> }, onDismiss = {}, onChangeFolder = { changed++ }, onResetFolder = { reset++ })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Field work/Toolkit.zip").assertExists()
        compose.onNodeWithTag("explorer.preflight.change").performClick()
        compose.onNodeWithTag("explorer.preflight.reset").performClick()
        compose.waitForIdle()
        assertEquals(1, changed)
        assertEquals(1, reset)
    }
}
