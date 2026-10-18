package com.fieldrepository.app.recording

import android.Manifest
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fieldrepository.app.MainActivity
import com.fieldrepository.app.data.QCompleteSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/**
 * DESIGN §9 test 51: the app is killed mid-take and the take is offered back on the next launch.
 *
 * Two runs with the process killed in between, so `android/scripts/recording-device-tests.sh` drives
 * it: `-e killPhase 1` starts a take and leaves it recording, the script force-stops the app, then
 * `-e killPhase 2` (a fresh process) finds the draft the service wrote before it started recording,
 * checks the recovery scanner offers it, and remuxes the partial file into a playable take.
 */
@RunWith(AndroidJUnit4::class)
class RecordingKillDeviceTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    else GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val phase get() = InstrumentationRegistry.getArguments().getString("killPhase")

    @Test fun phase1_aTakeIsRecordingWhenTheAppIsKilled() {
        assumeTrue(phase == "1")
        ActivityScenario.launch(MainActivity::class.java)
        RecordingDrafts.read(context.filesDir, KEY)?.let { RecordingDrafts.delete(context.filesDir, it, keepAudio = false) }
        val draft = RecordingDraft(
            clientClipKey = KEY, sessionKey = "device-kill-session", questionnaireId = "device-test",
            adtsPath = File(RecordingDrafts.dir(context.filesDir), "$KEY.aac").absolutePath,
            deviceCapturedAt = Instant.now().toString(), subjects = listOf(QCompleteSubject("a1")), label = "Section D"
        )
        RecordingDrafts.write(context.filesDir, draft)
        RecordingService.start(context, draft)
        val end = System.currentTimeMillis() + 10_000
        while (RecordingService.state.value !is RecordingService.State.Recording && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue(RecordingService.state.value is RecordingService.State.Recording)
        Thread.sleep(4_000)
        assertTrue("audio is being written", File(draft.adtsPath).length() > 0)
        // Left recording on purpose: the script kills the process now.
    }

    @Test fun phase2_theKilledTakeIsOfferedBackAndPlays() {
        assumeTrue(phase == "2")
        val draft = RecordingDrafts.read(context.filesDir, KEY)
        assertTrue("the draft written before recording survived the kill", draft != null)
        assertTrue("it was never finished", !draft!!.finished)
        val offered = RecoveryScanner.recoverable(RecordingDrafts.all(context.filesDir), activeClipKey = null)
        assertEquals("offered back as \"Recovered a recording\"", KEY, offered.firstOrNull { it.clientClipKey == KEY }?.clientClipKey)
        val m4a = File(draft.adtsPath.removeSuffix(".aac") + ".m4a")
        val result = Remuxer.remux(File(draft.adtsPath), m4a)
        assertTrue("the partial take remuxes into audio", result.durationMs > 1_000 && m4a.length() > 0)
        m4a.delete()
        RecordingDrafts.delete(context.filesDir, draft, keepAudio = false)
    }

    private companion object { const val KEY = "device-kill-test" }
}
