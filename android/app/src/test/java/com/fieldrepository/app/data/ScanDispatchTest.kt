package com.fieldrepository.app.data

import com.fieldrepository.app.ui.fieldScanCardKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The top-bar dispatcher (`data/ScanDispatch.kt`) against the shared fixture `scan-dispatch.json`
 * (REQUIREMENTS_2026-10-20 §6.3): the same rows the web's `scan-dispatch-unit.spec.ts` reads, in
 * both portals. This app carries no DPW decoders, so it runs the field column; the designer app runs
 * both.
 */
class ScanDispatchTest {
    private val portal = ScanPortal.FIELD
    private val codecs: ScanCodecs? = null
    private val cardKind: (String) -> ScanCardKind = ::fieldScanCardKind

    private val fixtureText: String =
        javaClass.classLoader!!.getResourceAsStream("scan-dispatch.json")!!.bufferedReader().use { it.readText() }
    private val rows: List<JsonObject> =
        (Json.parseToJsonElement(fixtureText).jsonObject["rows"] as JsonArray).map { it.jsonObject }

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun services(forPortal: ScanPortal, row: JsonObject): ScanServices {
        val mock = row["services"]?.jsonObject?.get(forPortal.wire)?.jsonObject
        return object : ScanServices {
            override val portal = forPortal
            override val codecs: ScanCodecs? = if (forPortal == ScanPortal.DESIGNER) this@ScanDispatchTest.codecs else null
            override fun cardKind(raw: String) = this@ScanDispatchTest.cardKind(raw)
            override suspend fun resolveCard(raw: String): ScanCardAnswer = when (mock?.str("card")) {
                "found" -> ScanCardAnswer.Found("cmartisan00000001", "Meena Devi")
                "replaced" -> ScanCardAnswer.Replaced("cmartisan00000001")
                "peer" -> ScanCardAnswer.Peer
                "refused" -> ScanCardAnswer.Refused("Server refusal.")
                "offline" -> ScanCardAnswer.Offline
                else -> throw AssertionError("${row.str("name")}: no card answer mocked for ${forPortal.wire}")
            }
            override suspend fun lookUpRecord(recordType: String, id: String): ScanRecordAnswer = when (mock?.str("record")) {
                "found" -> ScanRecordAnswer.Found("Meena Devi")
                "missing" -> ScanRecordAnswer.Missing
                "offline" -> ScanRecordAnswer.Offline
                else -> throw AssertionError("${row.str("name")}: no record answer mocked for ${forPortal.wire}")
            }
        }
    }

    private fun actionType(action: ScanAction?): String? = when (action) {
        null -> null
        is ScanAction.Open -> "open"
        is ScanAction.Add -> "add"
        is ScanAction.Redeem -> "redeem"
        is ScanAction.JoinSession -> "joinSession"
        is ScanAction.RequestAccess -> "requestAccess"
    }

    private fun check(forPortal: ScanPortal) = runBlocking {
        for (row in rows) {
            val name = row.str("name")!!
            val input = row.str("input")!!
            val want = row["expect"]!!.jsonObject[forPortal.wire]!!.jsonObject
            val got = dispatchScan(input, services(forPortal, row))
            assertEquals("$name (${forPortal.wire})", want.str("outcome"), got.kind.name)
            want.str("title")?.let { assertEquals("$name title", it, got.title) }
            want.str("action")?.let { assertEquals("$name action", it, actionType(got.action)) }
            want.str("href")?.let {
                val target = when (val a = got.action) {
                    is ScanAction.Open -> a.target
                    is ScanAction.Add -> a.target
                    else -> null
                }
                assertEquals("$name href", it, target?.href)
            }
            want.str("joinCode")?.let { assertEquals("$name joinCode", it, (got.action as? ScanAction.JoinSession)?.joinCode) }
            want.str("clientKey")?.let { assertEquals("$name clientKey", it, (got.action as? ScanAction.JoinSession)?.qr?.clientKey) }
            row.str("secret")?.let { secret -> assertFalse("$name echoes the secret", "${got.title} ${got.detail}".contains(secret)) }
            if (row["aadhaar"]?.jsonPrimitive?.boolean == true) {
                assertNull("$name offers an action", got.action)
                val said = "${got.title} ${got.detail}"
                for (i in 0..input.length - 8 step 8) assertFalse("$name echoes the payload", said.contains(input.substring(i, i + 8)))
            }
        }
    }

    @Test
    fun `every fixture row gives the field column's outcome`() = check(ScanPortal.FIELD)

    @Test
    fun `this app is the field portal`() = assertEquals(ScanPortal.FIELD, portal)

    @Test
    fun `the fixture is the web's, byte for byte`() {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val web = File(dir, "frontend/e2e/fixtures/scan-dispatch.json")
            if (web.isFile) {
                assertEquals(web.readText().replace("\r\n", "\n"), fixtureText.replace("\r\n", "\n"))
                return
            }
            dir = dir.parentFile
        }
        throw AssertionError("frontend/e2e/fixtures/scan-dispatch.json not found")
    }

    @Test
    fun `a lookup that throws reads as offline, never as a crash`() = runBlocking {
        val failing = object : ScanServices {
            override val portal = ScanPortal.FIELD
            override val codecs: ScanCodecs? = null
            override fun cardKind(raw: String) = this@ScanDispatchTest.cardKind(raw)
            override suspend fun resolveCard(raw: String): ScanCardAnswer = throw java.io.IOException("no signal")
            override suspend fun lookUpRecord(recordType: String, id: String): ScanRecordAnswer = ScanRecordAnswer.Missing
        }
        assertEquals(ScanOutcomeKind.OFFLINE, dispatchScan("D-7K3QM-9XA2F-HQ", failing).kind)
    }
}
