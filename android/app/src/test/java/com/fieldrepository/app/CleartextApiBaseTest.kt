package com.fieldrepository.app

import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.ui.repoFile
import com.fieldrepository.app.ui.repoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class CleartextApiBaseTest {

    private val guard: Regex by lazy {
        val workflow = repoSource(".github/workflows/publish-android.yml")
        val pattern = Regex("""grep -aoE '(http://[^']+)'""").find(workflow)?.groupValues?.get(1)
            ?: throw AssertionError(
                "publish-android.yml no longer greps the release APK with `grep -aoE 'http://…'`. Read the " +
                    "API-base step there and point this test at the pattern it uses now."
            )
        Regex(pattern)
    }

    private fun compiledClasses(): Map<String, ByteArray> {
        val location = ApiClient::class.java.protectionDomain?.codeSource?.location
            ?: throw AssertionError("cannot tell where :app's compiled classes are: ApiClient has no code source")
        val root = File(location.toURI())
        return if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }
        } else {
            ZipFile(root).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .associate { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }
            }
        }
    }

    private fun offendersIn(where: String, text: String): List<String> =
        guard.findAll(text).map { it.value }
            .filter { it != BuildConfig.DEFAULT_API_BASE_URL }
            .map { "$where: $it" }
            .toList()

    private val why =
        "publish-android.yml refuses to publish a release whose bytes contain a cleartext API base, help " +
            "text included, so each string below would stop the next tag push after the APK is built and " +
            "signed. Reword it so that no `http://<host>/api/` appears (ui/LocalNetworkAccess.kt's refusal " +
            "toast shows how: it names 127.0.0.1 without spelling out the URL)."

    @Test
    fun `the guard's pattern catches the sentence that once blocked a release, and not the production base`() {
        assertTrue(guard.containsMatchIn("or use adb reverse with http://127.0.0.1:8000/api/."))
        assertTrue(guard.containsMatchIn("apiBaseUrl=http://10.0.2.2:8000/api/"))
        assertFalse(guard.containsMatchIn("https://d2b34i3e92al6i.cloudfront.net/api/"))
    }

    @Test
    fun `no class compiled from src main carries a cleartext API base`() {
        val classes = compiledClasses()
        assertTrue("only ${classes.size} classes were found: this is not reading :app's compiled output", classes.size > 200)
        assertTrue(
            "ui/LocalNetworkAccess.kt's classes are not among those scanned",
            classes.keys.any { it.endsWith("com/fieldrepository/app/ui/LocalNetworkAccessKt.class") }
        )
        val offenders = classes.flatMap { (name, bytes) -> offendersIn(name, String(bytes, Charsets.ISO_8859_1)) }
        assertEquals(why, emptyList<String>(), offenders.distinct().sorted())
    }

    @Test
    fun `no resource under src main res carries a cleartext API base`() {
        val res = repoFile("src/main/res/values/strings.xml", "android/app/src/main/res/values/strings.xml")
            .parentFile?.parentFile
            ?: throw AssertionError("strings.xml was found outside any src/main/res/values directory")
        val xml = res.walkTopDown().filter { it.isFile && it.name.endsWith(".xml") }.toList()
        assertTrue("no resource XML found under ${res.absolutePath}", xml.isNotEmpty())
        val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val offenders = xml.flatMap { file ->
            offendersIn(file.relativeTo(res).invariantSeparatorsPath, comment.replace(file.readText(), ""))
        }
        assertEquals(why, emptyList<String>(), offenders.distinct().sorted())
    }
}
