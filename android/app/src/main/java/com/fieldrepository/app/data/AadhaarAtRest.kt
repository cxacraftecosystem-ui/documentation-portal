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

object AadhaarAtRest {
    const val FIELD = "aadhaarNumber"
    const val SEALED_FIELD = "aadhaarNumberSealed"
    const val WITHHELD_FIELD = "aadhaarLast4Withheld"
    const val DRAFT_FIELD = "aadhaar"
    const val RETYPE_REASON = "Needs the Aadhaar number again. This phone can't read the one typed, so nothing was sent."

    private const val PREFIX = "ks1:"
    private val json = Json { ignoreUnknownKeys = true }

    interface Sealer {
        fun seal(plain: ByteArray): ByteArray
        fun open(sealed: ByteArray): ByteArray
    }

    @VisibleForTesting
    @Volatile
    internal var sealerForTests: Sealer? = null

    private val keystore: Sealer by lazy { KeystoreSealer() }

    private fun sealer(): Sealer = sealerForTests ?: keystore

    fun sealValue(value: String): String? = runCatching {
        PREFIX + Base64.getEncoder().encodeToString(sealer().seal(value.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    fun openValue(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return runCatching {
            String(sealer().open(Base64.getDecoder().decode(stored.removePrefix(PREFIX))), Charsets.UTF_8)
        }.getOrNull()
    }

    fun isSealed(value: String?): Boolean = value?.startsWith(PREFIX) == true

    private fun worthSealing(value: String?): Boolean =
        !value.isNullOrBlank() && !value.contains('X', ignoreCase = true)

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

    fun numberWithheld(payloadJson: String): Boolean = payloadJson.contains(WITHHELD_FIELD)

    fun withheldLast4(payloadJson: String): String? {
        if (!numberWithheld(payloadJson)) return null
        val digits = (parseObject(payloadJson)?.get(WITHHELD_FIELD) as? JsonPrimitive)?.contentOrNull
        return digits?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    }

    fun retype(payloadJson: String, number: String): String? {
        val body = parseObject(payloadJson) ?: return null
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in body) {
            when (key) {
                WITHHELD_FIELD, SEALED_FIELD, FIELD -> out[FIELD] = JsonPrimitive(number)
                else -> out[key] = value
            }
        }
        if (FIELD !in out) out[FIELD] = JsonPrimitive(number)
        return json.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    fun canSeal(): Boolean = sealValue("000000000000") != null

    fun sealDraft(fields: Map<String, JsonElement>): Map<String, JsonElement> {
        val number = draftString(fields[DRAFT_FIELD])
        if (!worthSealing(number) || isSealed(number)) return fields
        val sealed = sealValue(number!!) ?: return fields - DRAFT_FIELD
        return fields + (DRAFT_FIELD to tagged(sealed))
    }

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
