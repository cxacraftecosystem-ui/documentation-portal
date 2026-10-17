package com.fieldrepository.app.ui

import com.fieldrepository.app.data.TwoFactorStatusDto
import com.fieldrepository.app.data.TwoStepSwitch
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SHARED PRIMITIVES (owner, 2026-10-17) on the handset: the OTP rules (the web's `lib/otp.ts`,
 * held to the same cases as `e2e/primitives-unit.spec.ts`), the two-step switch's Off / Setting up /
 * On logic, and source checks that every setting is an [AppToggle] and every 6-digit surface an
 * [OtpCodeField].
 */
class PrimitivesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    // --- OTP --------------------------------------------------------------------------------------

    @Test
    fun `a code is exactly six ascii digits`() {
        assertEquals(6, OtpCode.LENGTH)
        assertTrue(OtpCode.isComplete("123456"))
        assertFalse(OtpCode.isComplete("12345"))
        assertFalse(OtpCode.isComplete("1234567"))
        assertFalse(OtpCode.isComplete("12345a"))
        assertEquals("123", OtpCode.onlyDigits("a1b2-c3 ٤"))
    }

    @Test
    fun `typing advances and letters are refused`() {
        var code = ""
        for (typed in listOf("4", "8", "x", "2", "-", "6")) code = OtpCode.accept(code, code + typed)
        assertEquals("4826", code)
        assertEquals(4, OtpCode.activeBox(code))
        code = OtpCode.accept(code, code + "19")
        assertEquals("482619", code)
        assertEquals(5, OtpCode.activeBox(code))
        // A seventh digit cannot land.
        assertEquals("482619", OtpCode.accept(code, code + "7"))
    }

    @Test
    fun `a pasted or autofilled code fills every box and replaces a half-typed one`() {
        assertEquals("123456", OtpCode.accept("", "123 456"))
        assertEquals("987654", OtpCode.accept("", "Your code is 987654, valid 30s"))
        assertEquals("345678", OtpCode.accept("12", "12345678"))
        assertTrue(OtpCode.isComplete(OtpCode.accept("", "123-456")))
    }

    @Test
    fun `backspace steps back one box`() {
        assertEquals("12345", OtpCode.backspace("123456"))
        assertEquals("", OtpCode.backspace(""))
        assertEquals("1234", OtpCode.accept("12345", "1234"))
    }

    @Test
    fun `each box is announced as digit n of 6`() {
        assertEquals("Digit 1 of 6", OtpCode.digitLabel(0))
        assertEquals("Digit 6 of 6", OtpCode.digitLabel(5))
    }

    // --- The two-step switch ----------------------------------------------------------------------

    private val off = TwoFactorStatusDto(enabled = false, pending = false)
    private val pendingOnly = TwoFactorStatusDto(enabled = false, pending = true)
    private val on = TwoFactorStatusDto(enabled = true, recoveryCodesRemaining = 8, enabledAt = "2026-10-17T09:00:00Z")

    @Test
    fun `only a verified code turns the switch on`() {
        assertEquals(TwoStepSwitch.View.OFF, TwoStepSwitch.view(off, settingUpHere = false))
        assertEquals(TwoStepSwitch.View.SETTING_UP, TwoStepSwitch.view(off, settingUpHere = true))
        // A setup left over from an earlier visit is OFF - the bug the owner hit on the emulator.
        assertEquals(TwoStepSwitch.View.OFF, TwoStepSwitch.view(pendingOnly, settingUpHere = false))
        assertEquals(TwoStepSwitch.View.ON, TwoStepSwitch.view(on, settingUpHere = false))
        assertEquals(TwoStepSwitch.View.OFF, TwoStepSwitch.view(null, settingUpHere = false))
    }

    @Test
    fun `a short trip to the authenticator keeps the setup and a long absence drops it`() {
        assertFalse(TwoStepSwitch.abandonedAfterAway(settingUpHere = true, awayMillis = 20_000L))
        assertTrue(TwoStepSwitch.abandonedAfterAway(settingUpHere = true, awayMillis = TwoStepSwitch.AWAY_LIMIT_MILLIS + 1))
        assertFalse(TwoStepSwitch.abandonedAfterAway(settingUpHere = false, awayMillis = Long.MAX_VALUE))
    }

    @Test
    fun `the status line says it in plain words`() {
        val date = { _: String -> "17 Oct 2026" }
        assertEquals(
            "On since 17 Oct 2026 · 8 recovery codes left",
            TwoStepSwitch.statusLine(on, TwoStepSwitch.View.ON, date)
        )
        assertEquals(
            "On since 17 Oct 2026 · 1 recovery code left",
            TwoStepSwitch.statusLine(on.copy(recoveryCodesRemaining = 1), TwoStepSwitch.View.ON, date)
        )
        assertTrue(TwoStepSwitch.statusLine(off, TwoStepSwitch.View.SETTING_UP, date).startsWith("Setting up"))
        assertTrue(TwoStepSwitch.statusLine(pendingOnly, TwoStepSwitch.View.OFF, date).startsWith("Off."))
    }

    @Test
    fun `the status decodes pending and an older server reads as not pending`() {
        val withPending = json.decodeFromString(
            TwoFactorStatusDto.serializer(),
            """{"enabled":false,"pending":true,"recovery_codes_remaining":0,"enabled_at":null}"""
        )
        assertTrue(withPending.pending)
        val older = json.decodeFromString(
            TwoFactorStatusDto.serializer(),
            """{"enabled":false,"recovery_codes_remaining":0,"enabled_at":null}"""
        )
        assertFalse(older.pending)
    }

    // --- Source checks ----------------------------------------------------------------------------

    private fun source(path: String) = repoFile(path, "../$path").readText()

    @Test
    fun `the toggle is a real switch row`() {
        val toggle = source("android/app/src/main/java/com/fieldrepository/app/ui/AppToggle.kt")
        assertTrue(toggle.contains("role = Role.Switch"))
        assertTrue(toggle.contains("heightIn(min = 56.dp)"))
        assertTrue(toggle.contains("onCheckedChange = null"))
    }

    @Test
    fun `every six-digit surface uses the OTP field and recovery codes have their own`() {
        val screen = source("android/app/src/main/java/com/fieldrepository/app/ui/TwoStepVerificationScreen.kt")
        val password = source("android/app/src/main/java/com/fieldrepository/app/ui/AccountRecovery.kt")
        for (text in listOf(screen, password)) {
            assertTrue(text.contains("OtpCodeField("))
            assertTrue(text.contains("Use a recovery code instead"))
            assertTrue(text.contains("RecoveryCodeBox("))
        }
        assertTrue(screen.contains("AppToggle("))
        assertTrue(screen.contains("label = \"Two-step verification\""))
        assertTrue(screen.contains("cancelTwoFactorSetup()"))
        assertFalse(screen.contains("Text(\"Off.\""))
        val otp = source("android/app/src/main/java/com/fieldrepository/app/ui/OtpCode.kt")
        assertTrue(otp.contains("FieldDisplayFontFamily"))
        assertTrue(otp.contains("\"tnum\""))
        assertTrue(otp.contains("KeyboardType.NumberPassword"))
        assertTrue(otp.contains("ContentType.SmsOtpCode"))
    }

    @Test
    fun `no setting is a bare switch or checkbox on the converted screens`() {
        val main = source("android/app/src/main/java/com/fieldrepository/app/MainActivity.kt")
        assertFalse(main.contains("Checkbox(checked = preProcessAvailable"))
        assertFalse(main.contains("Checkbox(checked = dontRemind"))
        assertFalse(main.contains("Switch(checked = windowEnabled"))
        assertFalse(main.contains("Switch(checked = !hideAnswers"))
        assertFalse(main.contains("if (appUser.twoFactorEnabled) \"On\" else \"Off\""))
        assertFalse(source("android/app/src/main/java/com/fieldrepository/app/ui/AddPerson.kt").contains("Checkbox("))
        assertFalse(source("android/app/src/main/java/com/fieldrepository/app/ui/AccessRosterScreen.kt").contains("Checkbox("))
        // The drawer's Admin view is the shared switch, as the web's island nav is (DynamicIslandNav.tsx).
        val drawer = source("android/app/src/main/java/com/fieldrepository/app/ui/AppNavigation.kt")
        assertTrue(drawer.contains("label = \"Admin view\""))
        assertFalse(drawer.contains("\"Admin view: ON\""))
        assertFalse(drawer.contains("\"Admin view: OFF\""))
    }

    @Test
    fun `a busy toggle names its wait and only two-step setup says Setting up`() {
        val toggle = source("android/app/src/main/java/com/fieldrepository/app/ui/AppToggle.kt")
        assertTrue(toggle.contains("busyLabel: String = \"Working\""))
        assertFalse(toggle.contains("stateDescription = \"Setting up\""))
        val screen = source("android/app/src/main/java/com/fieldrepository/app/ui/TwoStepVerificationScreen.kt")
        assertTrue(screen.contains("busyLabel = if (view == TwoStepSwitch.View.SETTING_UP) \"Setting up\" else \"Working\""))
    }

    @Test
    fun `media cards draw real Retry and Download buttons`() {
        val downloads = source("android/app/src/main/java/com/fieldrepository/app/ui/MediaDownloads.kt")
        assertTrue(downloads.contains("fun MediaActionButton("))
        assertTrue(downloads.contains("heightIn(min = 48.dp)"))
        assertFalse(downloads.contains("Text(\"Download\", fontSize = 12.sp"))
        val main = source("android/app/src/main/java/com/fieldrepository/app/MainActivity.kt")
        assertFalse(main.contains("Text(\"Retry\", fontSize = 12.sp)"))
        assertTrue(main.contains("MediaAction.RETRY"))
    }
}
