package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerOpenAction
import com.fieldrepository.app.explorer.data.ExplorerOpenActivity
import com.fieldrepository.app.explorer.data.explorerOpenAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExplorerNotificationIntentsTest {

    @Test
    fun `only the two file actions are honoured`() {
        assertEquals(ExplorerOpenAction.VIEW, explorerOpenAction(ExplorerOpenActivity.ACTION_VIEW))
        assertEquals(ExplorerOpenAction.SHARE, explorerOpenAction(ExplorerOpenActivity.ACTION_SHARE))
        assertNull(explorerOpenAction("android.intent.action.VIEW"))
        assertNull(explorerOpenAction(null))
    }

    @Test
    fun `notification pending intents carry only explicit immutable intents`() {
        val source = sourceOf("explorer/data/ExplorerNotifications.kt").readText()
        val notifications = source.substringBefore("object ExplorerOpen ")
        assertFalse("a notification wraps the implicit view intent", notifications.contains("viewIntent("))
        assertFalse("a notification wraps the implicit share chooser", notifications.contains("shareIntent("))
        val calls = Regex("""PendingIntent\.get(Activity|Broadcast|Service)\(""").findAll(notifications).count()
        val immutable = Regex("""PendingIntent\.FLAG_IMMUTABLE""").findAll(notifications).count()
        assertTrue("no pending intent found", calls > 0)
        assertEquals("a notification pending intent is mutable", calls, immutable)
        assertTrue(notifications.contains("ExplorerOpenActivity.intent(context, jobId, action)"))
    }

    @Test
    fun `the file action activity is explicit and not exported`() {
        val source = sourceOf("explorer/data/ExplorerOpenActivity.kt").readText()
        assertTrue(source.contains("Intent(context, ExplorerOpenActivity::class.java)"))
        val manifest = manifest().readText()
        val entry = manifest.substringAfter("ExplorerOpenActivity\"").substringBefore("/>")
        assertTrue("ExplorerOpenActivity is missing from the manifest", manifest.contains("ExplorerOpenActivity\""))
        assertTrue("ExplorerOpenActivity is exported", entry.contains("android:exported=\"false\""))
        assertFalse("ExplorerOpenActivity takes intents from other apps", entry.contains("intent-filter"))
    }

    private fun appDir(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val app = File(dir, "app/src/main")
            if (File(app, "AndroidManifest.xml").isFile) return app
            val nested = File(dir, "android/app/src/main")
            if (File(nested, "AndroidManifest.xml").isFile) return nested
            dir = dir.parentFile
        }
        error("app/src/main not found above ${File(".").absolutePath}")
    }

    private fun manifest(): File = File(appDir(), "AndroidManifest.xml")

    private fun sourceOf(relative: String): File {
        val pkg = ExplorerOpenActivity::class.java.packageName.removeSuffix(".explorer.data").replace('.', '/')
        return File(appDir(), "java/$pkg/$relative")
    }
}
