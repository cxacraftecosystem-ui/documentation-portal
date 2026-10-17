package com.fieldrepository.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A typed Aadhaar number is never written to this phone's storage in the clear (2026-10-17).
 *
 * The register keeps only a fingerprint and the last four digits (`backend/app/services/
 * aadhaar_fingerprint.py`); the handset still has to send the typed number once, and two things
 * used to keep it on flash in the meantime: the outbox (a queued artisan create or edit waits there
 * for a connection) and the artisan form's draft (kept so a killed app gives the typing back).
 *
 * Both now hold it SEALED: AES-256-GCM under a key generated inside the Android Keystore, which
 * never leaves it ([KeystoreSealer]). [OfflineOutbox] seals every entry as it writes the queue and
 * opens it as it reads, so the plaintext lives only in memory while a sync sends it; a sent entry is
 * removed from the queue, and what remains on flash of it was ciphertext. The draft is cleared on
 * save. If the Keystore cannot be used, the number is NOT written at all: the outbox keeps the last
 * four digits under [WITHHELD_FIELD] and the entry is held in the outbox tray as "needs the Aadhaar
 * number again" until it is typed ([numberWithheld], [retype]); the draft drops it.
 *
 * Nothing here logs.
 */
object AadhaarAtRest {
    /** The body field the API reads the typed number from. */
    const val FIELD = "aadhaarNumber"
    /** Where the sealed number sits in a stored outbox body. */
    const val SEALED_FIELD = "aadhaarNumberSealed"
    /** The last four digits, when the number could not be sealed and was therefore not kept. */
    const val WITHHELD_FIELD = "aadhaarLast4Withheld"
    /** The artisan form's draft key for the typed number. */
    const val DRAFT_FIELD = "aadhaar"
    /** The outbox refusal for an entry held by [numberWithheld]. Said by this phone, not the server. */
    const val RETYPE_REASON = "Needs the Aadhaar number again. This phone can't read the one typed, so nothing was sent."

    private const val PREFIX = "ks1:"
    private val json = Json { ignoreUnknownKeys = true }

    interface Sealer {
        fun seal(plain: ByteArray): ByteArray
        fun open(sealed: ByteArray): ByteArray
    }

    /** A JVM test's sealer; null on a device, where the Keystore is used. */
    @VisibleForTesting
    @Volatile
    internal var sealerForTests: Sealer? = null

    private val keystore: Sealer by lazy { KeystoreSealer() }

    private fun sealer(): Sealer = sealerForTests ?: keystore

    /** `ks1:<base64>` of the sealed value, or null when the Keystore cannot be used. */
    fun sealValue(value: String): String? = runCatching {
        PREFIX + Base64.getEncoder().encodeToString(sealer().seal(value.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    /** The plain value of a [sealValue] result, or null when it cannot be opened. */
    fun openValue(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return runCatching {
            String(sealer().open(Base64.getDecoder().decode(stored.removePrefix(PREFIX))), Charsets.UTF_8)
        }.getOrNull()
    }

    fun isSealed(value: String?): Boolean = value?.startsWith(PREFIX) == true

    /** A real typed number, as opposed to blank or the mask a form posts back unchanged. */
    private fun worthSealing(value: String?): Boolean =
        !value.isNullOrBlank() && !value.contains('X', ignoreCase = true)

    /** An outbox body as it may be WRITTEN: the typed number sealed, or withheld. */
    fun sealPayload(payloadJson: String): String {
        val body = parseObject(payloadJson) ?: return payloadJson
        val number = (body[FIELD] as? JsonPrimitive)?.contentOrNull
        if (!worthSealing(number)) return payloadJson
        val sealed = sealValue(number!!)
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in body) {
            if (key != FIELD) {
                out[key] = value
            } else if (sealed != null) {
                out[SEALED_FIELD] = JsonPrimitive(sealed)
            } else {
                out[WITHHELD_FIELD] = JsonPrimitive(number.filter { it in '0'..'9' }.takeLast(4))
            }
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    /**
     * An outbox body as it is READ: the sealed number opened back into [FIELD].
     *
     * A number that was withheld, or whose seal can no longer be opened (the Keystore key is gone
     * after a restore or on a new device), is NOT silently dropped: the body keeps [WITHHELD_FIELD]
     * (the last four, or empty when even those are unknown), so [numberWithheld] stays true and the
     * sync pass holds the entry for the number to be typed again ([retype]). Dropping the field would
     * replay a correction as "keep the number on record" — answered 200, the new number lost — and a
     * create as a bare 422. The marker never reaches the wire: the replay decodes the body into a
     * typed request that has no such field.
     */
    fun openPayload(stored: String): String {
        if (!stored.contains(SEALED_FIELD) && !stored.contains(WITHHELD_FIELD)) return stored
        val body = parseObject(stored) ?: return stored
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in body) {
            when (key) {
                SEALED_FIELD -> {
                    val plain = (value as? JsonPrimitive)?.contentOrNull?.let(::openValue)
                    if (plain != null) {
                        out[FIELD] = JsonPrimitive(plain)
                    } else if (WITHHELD_FIELD !in body) {
                        out[WITHHELD_FIELD] = JsonPrimitive("")
                    }
                }
                else -> out[key] = value
            }
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    /** True when a body is waiting for the number to be typed again. Never sent while true. */
    fun numberWithheld(payloadJson: String): Boolean = payloadJson.contains(WITHHELD_FIELD)

    /** The last four digits of a withheld number, when they were kept; null otherwise. */
    fun withheldLast4(payloadJson: String): String? {
        if (!numberWithheld(payloadJson)) return null
        val digits = (parseObject(payloadJson)?.get(WITHHELD_FIELD) as? JsonPrimitive)?.contentOrNull
        return digits?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    }

    /**
     * The body with the number typed again in place of the withheld marker, or null when [payloadJson]
     * is not a JSON object. In memory only: the outbox seals it again as it writes ([sealPayload]).
     */
    fun retype(payloadJson: String, number: String): String? {
        val body = parseObject(payloadJson) ?: return null
        val out = LinkedHashMap<String, JsonElement>()
        // In the marker's place, so the body reads as it did when it was typed.
        for ((key, value) in body) {
            when (key) {
                WITHHELD_FIELD, SEALED_FIELD, FIELD -> out[FIELD] = JsonPrimitive(number)
                else -> out[key] = value
            }
        }
        if (FIELD !in out) out[FIELD] = JsonPrimitive(number)
        return json.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    /** Whether this phone can keep a typed number sealed right now (the Keystore answers). */
    fun canSeal(): Boolean = sealValue("000000000000") != null

    /**
     * A form draft as it may be WRITTEN: the typed number sealed, or left out. Draft values are
     * tagged (`DraftValues`: `{"t":"s","v":"…"}`); only a string under [DRAFT_FIELD] is touched.
     */
    fun sealDraft(fields: Map<String, JsonElement>): Map<String, JsonElement> {
        val number = draftString(fields[DRAFT_FIELD])
        if (!worthSealing(number) || isSealed(number)) return fields
        val sealed = sealValue(number!!) ?: return fields - DRAFT_FIELD
        return fields + (DRAFT_FIELD to tagged(sealed))
    }

    /** A form draft as it is RESTORED: the number opened, or blank when it cannot be. */
    fun openDraft(fields: Map<String, JsonElement>): Map<String, JsonElement> {
        val stored = draftString(fields[DRAFT_FIELD]) ?: return fields
        if (!isSealed(stored)) return fields
        return fields + (DRAFT_FIELD to tagged(openValue(stored) ?: ""))
    }

    private fun tagged(value: String): JsonElement =
        JsonObject(mapOf("t" to JsonPrimitive("s"), "v" to JsonPrimitive(value)))

    private fun draftString(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return null
        if ((obj["t"] as? JsonPrimitive)?.contentOrNull != "s") return null
        return (obj["v"] as? JsonPrimitive)?.contentOrNull
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}

/** AES-256-GCM under a non-exportable key generated in the Android Keystore. Output: IV || ciphertext. */
private class KeystoreSealer : AadhaarAtRest.Sealer {
    private val alias = "aadhaar-at-rest-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}
