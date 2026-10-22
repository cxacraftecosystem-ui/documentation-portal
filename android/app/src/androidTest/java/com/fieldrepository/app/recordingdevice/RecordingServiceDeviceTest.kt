package com.fieldrepository.app.recordingdevice
import com.fieldrepository.app.recording.*

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import com.fieldrepository.app.MainActivity
import com.fieldrepository.app.questionnaire.data.QCompleteSubject
import com.fieldrepository.app.questionnaire.data.QTargetRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * DESIGN §9 tests 49 and 50 on a device: a take keeps recording through an app switch and a locked
 * screen, and every way out of it (Stop, Back leaving the session, a call taking audio focus) ends
 * the take and keeps its audio. Run by `android/scripts/recording-device-tests.sh`.
 */
@RunWith(AndroidJUnit4::class)
class RecordingServiceDeviceTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    else GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val made = mutableListOf<RecordingDraft>()

    @Before fun openApp() {
        // A microphone foreground service may only start while the app is visible.
        scenario = ActivityScenario.launch(MainActivity::class.java)
        RecordingService.clearFinished()
    }

    @After fun tidy() {
        if (RecordingService.state.value is RecordingService.State.Recording) RecordingService.stop(context)
        waitFor(10_000) { RecordingService.state.value !is RecordingService.State.Recording }
        made.forEach { RecordingDrafts.delete(context.filesDir, RecordingDrafts.read(context.filesDir, it.clientClipKey) ?: it, keepAudio = false) }
        RecordingService.clearFinished()
        scenario.close()
    }

    private fun startTake(): RecordingDraft {
        val key = "device-test-" + UUID.randomUUID()
        val draft = RecordingDraft(
            clientClipKey = key, sessionKey = "device-test-session", questionnaireId = "device-test",
            adtsPath = File(RecordingDrafts.dir(context.filesDir), "$key.aac").absolutePath,
            deviceCapturedAt = Instant.now().toString(), targets = listOf(QTargetRef(questionLineageId = "lin_test")),
            subjects = listOf(QCompleteSubject("a1")), label = "D3"
        )
        RecordingDrafts.write(context.filesDir, draft)
        made += draft
        RecordingService.start(context, draft)
        assertTrue("the take started", waitFor(10_000) { RecordingService.state.value is RecordingService.State.Recording })
        return draft
    }

    private fun waitFor(ms: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (condition()) return true; Thread.sleep(100) }
        return condition()
    }

    private fun assertGrows(file: File, why: String) {
        val before = file.length()
        Thread.sleep(3_000)
        assertTrue("$why: ${file.name} grew past $before bytes", file.length() > before)
        assertTrue("$why: still recording", RecordingService.state.value is RecordingService.State.Recording)
    }

    /** The take ended for [reason], and its draft says finished with audio kept as `.m4a`. */
    private fun assertEndedAndKept(draft: RecordingDraft, reason: String) {
        assertTrue("the take ended", waitFor(15_000) { RecordingService.state.value is RecordingService.State.Finished })
        assertEquals(reason, (RecordingService.state.value as RecordingService.State.Finished).reason)
        assertTrue("remuxed and kept", waitFor(15_000) {
            RecordingDrafts.read(context.filesDir, draft.clientClipKey)?.m4aPath?.let { File(it).length() > 0 } == true
        })
        val saved = RecordingDrafts.read(context.filesDir, draft.clientClipKey)!!
        assertTrue(saved.finished)
        assertTrue("it has a duration", saved.durationMs > 0)
    }

    @Test fun anAppSwitchAndALockedScreenKeepRecording() {
        val draft = startTake()
        val adts = File(draft.adtsPath)
        assertGrows(adts, "in the app")
        device.pressHome()
        assertGrows(adts, "after switching away")
        device.sleep()
        assertGrows(adts, "with the screen locked")
        device.wakeUp()
        RecordingService.stop(context)
        assertEndedAndKept(draft, "stop")
    }

    @Test fun leavingTheSessionEndsTheTakeAndKeepsIt() {
        // Back on the workspace (and leaving it any other way) stops the take with reason "leave".
        val draft = startTake()
        Thread.sleep(1_500)
        RecordingService.stop(context, "leave")
        assertEndedAndKept(draft, "leave")
    }

    @Test fun aCallTakingAudioFocusEndsTheTakeAndKeepsIt() {
        val draft = startTake()
        Thread.sleep(1_500)
        // A phone call takes audio focus for voice communication; this asks for it the same way.
        val audio = context.getSystemService(AudioManager::class.java)
        val call = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        audio.requestAudioFocus(call)
        try {
            assertEndedAndKept(draft, "call")
        } finally {
            audio.abandonAudioFocusRequest(call)
        }
    }
}
