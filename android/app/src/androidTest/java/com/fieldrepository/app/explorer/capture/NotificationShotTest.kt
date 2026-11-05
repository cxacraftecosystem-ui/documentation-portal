package com.fieldrepository.app.explorer.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.portal.DesignTheme
import com.fieldrepository.app.explorer.shots.DesignBoards
import com.fieldrepository.app.explorer.ui.audioItem
import com.fieldrepository.app.explorer.ui.audioLines
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NotificationShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun androidAudioNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        runCatching { instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close() }
        compose.setContent { DesignTheme(false) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) } }
        compose.waitForIdle()
        val wav = File(context.cacheDir, "design-board.wav")
        if (!wav.exists()) wav.writeBytes(DesignBoards.silentWav(DesignBoards.AUDIO_MS / 1000))
        val entry = DesignBoards.audioEntry
        val item = audioItem(entry, wav.toURI().toString(), audioLines(DesignBoards.audioTranscript()))!!
        instrumentation.runOnMainSync { ExplorerAudio.play(context, item, 1_100_000) }
        Thread.sleep(4_000)
        val device = UiDevice.getInstance(instrumentation)
        device.openNotification()
        Thread.sleep(2_500)
        val root = File(context.getExternalFilesDir(null), "design-fidelity/AndroidAudioPlayer")
        root.mkdirs()
        device.takeScreenshot(File(root, "impl-android-notification.png"))
        device.pressBack()
        instrumentation.runOnMainSync { ExplorerAudio.stop() }
    }

}
