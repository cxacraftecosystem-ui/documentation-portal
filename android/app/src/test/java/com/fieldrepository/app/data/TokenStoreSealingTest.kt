package com.fieldrepository.app.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session token is sealed before it is written (2026-10-17): see the note on [TokenStore.getToken].
 * The Keystore cannot run in a JVM unit test, so a reversible stand-in plays its part; what is pinned
 * here is everything around it — nothing in the file is the token, an older build's plain token is
 * sealed on first read and its plain copy deleted, a sealed value that no longer opens reads as signed
 * out, and a phone whose Keystore refuses keeps the token rather than losing it.
 * A Keystore that cannot be asked at this moment is not a wiped key: the sealed value stays (2026-10-18).
 */
class TokenStoreSealingTest {
    /** `works = false` is a Keystore that refuses outright; `unavailable` one that cannot be asked right now. */
    private class FakeSealer(var works: Boolean = true, var unavailable: Boolean = false) : TokenSealer {
        override fun seal(plain: String): String? = if (works && !unavailable) "v1:sealed:" + plain.reversed() else null
        override fun open(sealed: String): OpenResult = when {
            unavailable -> OpenResult.Unavailable
            works && sealed.startsWith("v1:sealed:") -> OpenResult.Opened(sealed.removePrefix("v1:sealed:").reversed())
            else -> OpenResult.Unreadable
        }
    }

    /** Just enough SharedPreferences for TokenStore: strings, remove, clear, apply/commit. */
    private class MemoryPreferences : SharedPreferences {
        val values = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String?, defValue: String?): String? = values[key] as String? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            private var clear = false
            override fun putString(key: String?, value: String?) = apply { staged[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = apply { removed += key!! }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) values.clear()
                removed.forEach { values.remove(it) }
                values.putAll(staged)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    @Test
    fun `the file never holds the token itself`() {
        val prefs = MemoryPreferences()
        val store = TokenStore(prefs, FakeSealer())
        store.setToken("eyJ.secret.token")
        assertEquals("eyJ.secret.token", store.getToken())
        assertFalse("no plain copy", prefs.values.values.any { it == "eyJ.secret.token" })
        assertNull(prefs.getString("jwt", null))

        store.setToken(null)
        assertNull(store.getToken())
        assertTrue(prefs.values.isEmpty())
    }

    @Test
    fun `an older build's plain token is sealed on first read and nobody is signed out`() {
        val prefs = MemoryPreferences().apply { values["jwt"] = "legacy-token" }
        assertEquals("legacy-token", TokenStore(prefs, FakeSealer()).getToken())
        assertNull("the plain copy is gone", prefs.getString("jwt", null))
        assertEquals("v1:sealed:" + "legacy-token".reversed(), prefs.getString("jwt_sealed", null))
        assertEquals("legacy-token", TokenStore(prefs, FakeSealer()).getToken())
    }

    @Test
    fun `a sealed token that no longer opens reads as signed out and is dropped`() {
        val prefs = MemoryPreferences().apply { values["jwt_sealed"] = "v1:from-a-wiped-key" }
        assertNull(TokenStore(prefs, FakeSealer()).getToken())
        assertNull(prefs.getString("jwt_sealed", null))
    }

    @Test
    fun `a keystore that cannot be asked just now keeps the sealed token for the next read`() {
        val sealed = "v1:sealed:" + "after-a-reboot".reversed()
        val prefs = MemoryPreferences().apply { values["jwt_sealed"] = sealed }
        val sealer = FakeSealer(unavailable = true)
        assertNull("signed out for this read", TokenStore(prefs, sealer).getToken())
        assertEquals("but the sealed token is kept", sealed, prefs.getString("jwt_sealed", null))
        sealer.unavailable = false
        assertEquals("after-a-reboot", TokenStore(prefs, sealer).getToken())
    }

    @Test
    fun `a phone whose keystore refuses keeps the token as it always was`() {
        val prefs = MemoryPreferences()
        val store = TokenStore(prefs, FakeSealer(works = false))
        store.setToken("kept-token")
        assertEquals("kept-token", store.getToken())
        assertEquals("kept-token", prefs.getString("jwt", null))
    }

    @Test
    fun `the real sealer answers null rather than throwing where there is no keystore`() {
        // A JVM has no AndroidKeyStore provider, which is exactly the broken-phone case.
        val sealer = KeystoreTokenSealer("test-alias")
        assertNull(sealer.seal("x"))
        assertEquals("no keystore to ask is not a reason to delete", OpenResult.Unavailable, sealer.open("v1:AAAA:BBBB"))
        assertEquals(OpenResult.Unreadable, sealer.open("not-sealed"))
        assertEquals(OpenResult.Unreadable, sealer.open("v1:not base64!:BBBB"))
    }
}
