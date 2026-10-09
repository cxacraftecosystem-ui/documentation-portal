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

/**
 * NOTHING THIS MODULE SHIPS MAY CONTAIN A CLEARTEXT `http://<host>/api/`, BECAUSE THE PUBLISH WORKFLOW
 * REFUSES ANY RELEASE THAT DOES.
 *
 * `.github/workflows/publish-android.yml`, step "Assert the APK points at THIS product's API and not
 * at a laptop", unpacks the signed release APK and greps EVERY byte of it for a cleartext API base —
 * the localhost release, which installs, opens and reaches nothing. It cannot tell a wrong
 * `DEFAULT_API_BASE_URL` from a sentence that merely mentions a development base, and it must not
 * try: it is the last thing between a laptop's `local.properties` and the fleet.
 *
 * THAT IS NOT HYPOTHETICAL. The targetSdk 37 upgrade added a debug-only refusal toast in
 * `ui/LocalNetworkAccess.kt` that spelled out the loopback URL. The file is in `src/main`, so the
 * literal shipped in the release dex as well, and the release built from that tree failed the guard:
 * every tag push would have stopped there, after the APK was built and signed. Nothing before a tag
 * runs that guard. This does, on every pull request that touches `android/`, with the GUARD'S OWN
 * PATTERN read out of the workflow, so the two cannot drift apart.
 *
 * WHAT IS READ: the classes compiled from `src/main` (string literals sit in each class's constant
 * pool verbatim) and the resource XML under `src/main/res` with its comments removed, because aapt2
 * drops comments and keeps everything else. Third-party libraries are outside it; the guard itself,
 * run against a real release on 2026-10-09, found nothing of theirs.
 *
 * The one cleartext base allowed through is `BuildConfig.DEFAULT_API_BASE_URL` itself: on a developer's
 * machine `local.properties` may legitimately point it at their own backend. A runner never has that
 * file, and the publish workflow asserts the production base separately.
 */
class CleartextApiBaseTest {

    /** The guard's own `grep -aoE` pattern, taken from the workflow rather than copied from it. */
    private val guard: Regex by lazy {
        val workflow = repoSource(".github/workflows/publish-android.yml")
        val pattern = Regex("""grep -aoE '(http://[^']+)'""").find(workflow)?.groupValues?.get(1)
            ?: throw AssertionError(
                "publish-android.yml no longer greps the release APK with `grep -aoE 'http://…'`. Read the " +
                    "API-base step there and point this test at the pattern it uses now."
            )
        Regex(pattern)
    }

    /** Every class compiled from this module's `src/main`, by path, whichever form the test runtime holds them in. */
    private fun compiledClasses(): Map<String, ByteArray> {
        // Both links are nullable in the JDK's own annotations; a missing one is a failure, never a pass.
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
        // An empty or wrong directory would pass every assertion below, so the scan proves its reach first.
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
