package com.fieldrepository.app.ui

import com.fieldrepository.app.data.ArtisanCreateRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C18: look the artisan up first; Pehchan starts unanswered; "Not shared by artisan" is explicit. */
class ArtisanLookupTest {

    @Test
    fun `twelve digits are an Aadhaar number, anything else is a name`() {
        assertEquals(LookupKind.AADHAAR, lookupKind("2341 2341 2346"))
        assertEquals(LookupKind.AADHAAR, lookupKind("234123412346"))
        assertEquals(LookupKind.NAME, lookupKind("Ramesh"))
        assertEquals(LookupKind.NAME, lookupKind("23412341234"))
        assertEquals(LookupKind.EMPTY, lookupKind("   "))
    }

    @Test
    fun `record a new artisan carries what was typed`() {
        assertEquals(LookupStart(aadhaar = "234123412346"), lookupStart(" 2341 2341 2346 "))
        assertEquals(LookupStart(name = "Ramesh Chhipa"), lookupStart("  Ramesh Chhipa "))
        assertEquals(LookupStart(), lookupStart(""))
    }

    @Test
    fun `the not-shared outcome is absent from every body that does not carry it`() {
        val json = Json { encodeDefaults = false; explicitNulls = false }
        val plain = json.encodeToString(ArtisanCreateRequest(name = "A", place = "B", aadhaarNumber = "234123412346"))
        assertFalse(plain.contains("aadhaarNotShared"))
        val declined = json.encodeToString(ArtisanCreateRequest(name = "A", place = "B", aadhaarNumber = "", aadhaarNotShared = true))
        assertTrue(declined.contains("\"aadhaarNotShared\":true"))
    }

    @Test
    fun `the form opens on the lookup and the Pehchan question starts unanswered`() {
        val source = repoSource(
            "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            "src/main/java/com/fieldrepository/app/MainActivity.kt",
        )
        assertTrue(source.contains("if (!lookupSettled) {"))
        assertTrue(source.contains("mutableStateOf<Boolean?>(editing?.pehchanCardAvailable)"))
        assertTrue(source.contains("label = \"Not shared by artisan\""))
    }

    @Test
    fun `an artisan with a stored Aadhaar is never offered not-shared and never sends it blank`() {
        assertFalse(aadhaarNotSharedOffered("234123412346"))
        assertTrue(aadhaarNotSharedOffered(null))
        assertTrue(aadhaarNotSharedOffered("  "))
        // A stale or forced "not shared" on a record with a number keeps the number.
        assertEquals("234123412346", aadhaarForBody("234123412346", notShared = true, storedAadhaar = "234123412346"))
        assertEquals("", aadhaarForBody("", notShared = true, storedAadhaar = null))
        assertEquals("234123412346", aadhaarForBody(" 234123412346 ", notShared = false, storedAadhaar = null))
        val source = repoSource(
            "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            "src/main/java/com/fieldrepository/app/MainActivity.kt",
        )
        assertTrue(source.contains("if (notSharedOffered) AppToggle("))
        assertTrue(source.contains("aadhaarNumber = aadhaarForBody(aadhaar, aadhaarNotShared, editing?.aadhaarNumber)"))
        assertFalse(source.contains("aadhaarNumber = if (aadhaarNotShared) \"\" else"))
    }
}
