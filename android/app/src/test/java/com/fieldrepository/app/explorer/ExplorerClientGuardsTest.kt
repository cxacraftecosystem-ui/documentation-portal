package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerAudioService
import com.fieldrepository.app.explorer.ui.ExplorerLinkRequest
import com.fieldrepository.app.explorer.ui.ExplorerViewLinks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExplorerClientGuardsTest {

    private val web = "https://portal.example.org"
    private val own = "com.example.ownapp"

    private fun controllerAllowed(controllerPackage: String, trusted: Boolean): Boolean {
        val rules = Class.forName(ExplorerAudioService::class.java.packageName + ".ExplorerAudioKt")
        val method = rules.getDeclaredMethod(
            "explorerControllerAllowed",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(null, controllerPackage, own, trusted) as Boolean
    }

    private fun linkSavesFiles(request: ExplorerLinkRequest): Boolean {
        val rules = Class.forName(ExplorerLinkRequest::class.java.packageName + ".ExplorerLinkConfirmKt")
        val method = rules.getDeclaredMethod("explorerLinkSavesFiles", ExplorerLinkRequest::class.java)
        method.isAccessible = true
        return method.invoke(null, request) as Boolean
    }

    @Test
    fun `another app cannot connect to the audio session and read transcript lines`() {
        assertFalse(controllerAllowed("com.attacker.harvester", trusted = false))
        assertFalse(controllerAllowed("", trusted = false))
    }

    @Test
    fun `this app and trusted system controllers still connect`() {
        assertTrue(controllerAllowed(own, trusted = false))
        assertTrue(controllerAllowed("com.android.systemui", trusted = true))
    }

    @Test
    fun `the audio service filters controllers before handing out the session`() {
        val source = sourceOf("explorer/data/ExplorerAudio.kt").readText()
        val service = source.substringAfter("class ExplorerAudioService")
        assertFalse(
            "onGetSession returns the session to every caller",
            Regex("""override fun onGetSession\([^)]*\)\s*:\s*MediaSession\?\s*=\s*session\s*\n""").containsMatchIn(service),
        )
        assertTrue("the session has no connect callback", service.contains(".setCallback("))
    }

    @Test
    fun `a link that would save media or a bundle asks first`() {
        val media = ExplorerViewLinks.parse("$web/data/go/media/m-1", web)!!
        val bundle = ExplorerViewLinks.parse("$web/data?bundle=b-1", web)!!
        val open = ExplorerViewLinks.parse("$web/data/go/open/artisan/a-1", web)!!
        val view = ExplorerViewLinks.parse("$web/data?viewId=v-1", web)!!
        assertTrue(linkSavesFiles(media))
        assertTrue(linkSavesFiles(bundle))
        assertFalse(linkSavesFiles(open))
        assertFalse(linkSavesFiles(view))
    }

    @Test
    fun `the explorer screen holds link downloads until the user confirms`() {
        val source = sourceOf("explorer/ui/ExplorerScreen.kt").readText()
        val effect = source.substringAfter("LaunchedEffect(linkRequest)").substringBefore("followGo(request.go)")
        assertTrue("a /data/go/media or bundle link downloads with no confirmation", effect.contains("explorerLinkSavesFiles(request)"))
    }

    private fun sourceOf(relative: String): File {
        val pkg = ExplorerAudioService::class.java.packageName.removeSuffix(".explorer.data").replace('.', '/')
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in listOf("src/main/java", "app/src/main/java", "android/app/src/main/java")) {
                val candidate = File(dir, "$path/$pkg/$relative")
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        error("$relative not found from ${File(".").absolutePath}")
    }
}
