package com.fieldrepository.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

class RequestContractFixturesTest {
    private val app = "fr-android"
    private val root = "com/fieldrepository/app"
    private val wire: Json = ApiClient.json
    private val samples: JsonObject = Json.parseToJsonElement(locate("shared/request-contract-samples.json").readText()).jsonObject

    private val createOnly: Set<String> =
        (samples["createOnly"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet().orEmpty()

    private val neverBlank: Set<String> =
        (samples["neverBlank"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet().orEmpty()

    private fun locate(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$relative not found above ${File("").absolutePath}")
    }

    private data class Call(val owner: String, val function: String, val method: String, val path: String, val body: Type)

    private val verbs = mapOf(
        retrofit2.http.POST::class.java to "POST",
        retrofit2.http.PUT::class.java to "PUT",
        retrofit2.http.PATCH::class.java to "PATCH",
        retrofit2.http.DELETE::class.java to "DELETE",
    )

    private fun interfaces(): List<Class<*>> {
        val loader = javaClass.classLoader!!
        val names = sortedSetOf<String>()
        val places = mutableSetOf<File>()
        ApiClient::class.java.protectionDomain?.codeSource?.location?.let { places += File(it.toURI()) }
        loader.getResources(root).toList().forEach { url ->
            when (url.protocol) {
                "file" -> places += File(url.toURI()).let { dir -> generateSequence(dir) { it.parentFile }.drop(root.count { it == '/' } + 1).first() }
                "jar" -> places += File((url.openConnection() as java.net.JarURLConnection).jarFileURL.toURI())
            }
        }
        for (place in places) {
            if (place.isDirectory) {
                val base = File(place, root)
                if (base.isDirectory) base.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { file ->
                    names += root.replace('/', '.') + "." + file.relativeTo(base).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.')
                }
            } else if (place.isFile && place.name.endsWith(".jar")) {
                java.util.jar.JarFile(place).use { jar ->
                    jar.entries().asSequence().map { it.name }
                        .filter { it.startsWith("$root/") && it.endsWith(".class") }
                        .forEach { names += it.removeSuffix(".class").replace('/', '.') }
                }
            }
        }
        return names.mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .filter { it.isInterface && !it.name.contains("\$DefaultImpls") }
    }

    private fun verbOf(method: Method): Pair<String, String>? {
        for (annotation in method.annotations) {
            verbs[annotation.annotationClass.java]?.let { verb ->
                val value = annotation.annotationClass.java.getMethod("value").invoke(annotation) as String
                return verb to value
            }
            if (annotation is retrofit2.http.HTTP && annotation.hasBody) return annotation.method to annotation.path
        }
        return null
    }

    private fun calls(): List<Call> = interfaces().flatMap { type ->
        runCatching { type.declaredMethods.toList() }.getOrDefault(emptyList()).mapNotNull { method ->
            val (verb, path) = verbOf(method) ?: return@mapNotNull null
            val index = method.parameterAnnotations.indexOfFirst { list -> list.any { it is retrofit2.http.Body } }
            if (index < 0) return@mapNotNull null
            val wirePath = when {
                path.startsWith("http") -> path
                path.startsWith("/") -> path
                else -> "/api/$path"
            }
            Call(type.simpleName, method.name, verb, wirePath.substringBefore('?'), method.genericParameterTypes[index])
        }
    }.distinctBy { listOf(it.owner, it.function, it.method, it.path) }

    private fun raw(type: Type): Class<*>? = when (type) {
        is Class<*> -> type
        is ParameterizedType -> type.rawType as? Class<*>
        is WildcardType -> type.upperBounds.firstOrNull()?.let(::raw)
        else -> null
    }

    private fun overrideFor(dto: String, field: String): JsonElement? =
        (samples["overrides"] as? JsonObject)?.get(dto)?.let { it as? JsonObject }?.get(field)

    private fun stringFor(name: String): JsonElement {
        (samples["fields"] as? JsonObject)?.get(name)?.let { return it }
        val suffixes = samples["suffixes"] as? JsonObject ?: JsonObject(emptyMap())
        suffixes.entries.sortedByDescending { it.key.length }.firstOrNull { name.endsWith(it.key) }?.let { return it.value }
        return JsonPrimitive(samples["placeholder"]?.let { (it as JsonPrimitive).content } ?: "Sample text")
    }

    private fun sample(desc: SerialDescriptor, name: String, dto: String, blank: Boolean, depth: Int): JsonElement {
        overrideFor(dto, name)?.let { return it }
        if (blank && desc.isNullable && desc.kind == PrimitiveKind.STRING && name !in neverBlank) return JsonPrimitive("   ")
        val serial = desc.serialName.removeSuffix("?")
        if (serial.startsWith("kotlinx.serialization.json.")) return buildJsonObject { }
        if (desc.isInline) return sample(desc.getElementDescriptor(0), name, dto, blank, depth + 1)
        return when (val kind = desc.kind) {
            PrimitiveKind.STRING -> stringFor(name)
            PrimitiveKind.BOOLEAN -> (samples["booleans"] as? JsonObject)?.get(name) ?: JsonPrimitive(false)
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE ->
                (samples["integers"] as? JsonObject)?.get(name) ?: JsonPrimitive(1)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE ->
                (samples["numbers"] as? JsonObject)?.get(name) ?: JsonPrimitive(1.5)
            PrimitiveKind.CHAR -> JsonPrimitive("a")
            SerialKind.ENUM -> JsonPrimitive(desc.getElementName(0))
            StructureKind.LIST -> if (depth > 6) buildJsonArray { } else buildJsonArray {
                add(sample(desc.getElementDescriptor(0), name.removeSuffix("s"), dto, blank, depth + 1))
            }
            StructureKind.MAP -> buildJsonObject { }
            StructureKind.CLASS, StructureKind.OBJECT -> if (depth > 6) buildJsonObject { } else buildJsonObject {
                val nested = desc.serialName.substringAfterLast('.').removeSuffix("?")
                for (i in 0 until desc.elementsCount) {
                    put(desc.getElementName(i), sample(desc.getElementDescriptor(i), desc.getElementName(i), nested, blank, depth + 1))
                }
            }
            is PolymorphicKind, SerialKind.CONTEXTUAL -> stringFor(name)
            else -> JsonNull
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fixture(call: Call, blank: Boolean): JsonObject {
        val serializer = serializer(call.body) as KSerializer<Any>
        val dto = serializer.descriptor.serialName.substringAfterLast('.').removeSuffix("?")
        val sampled = sample(serializer.descriptor, "", dto, blank, 0)
        val generated = if (call.method == "POST" || sampled !is JsonObject) sampled
        else JsonObject(sampled.mapValues { (key, value) -> if (key in createOnly) JsonNull else value })
        val decoded = Json { ignoreUnknownKeys = false; isLenient = false }.decodeFromJsonElement(serializer, generated)
        val body = wire.encodeToJsonElement(serializer, decoded)
        return buildJsonObject {
            put("app", app)
            put("interface", call.owner)
            put("function", call.function)
            put("dto", dto)
            put("method", call.method)
            put("path", call.path)
            put("variant", if (blank) "blank-optionals" else "filled")
            put("body", body)
        }
    }

    @Test
    fun `every request body the app sends is written out for the backend to validate`() {
        val calls = calls()
        assertTrue("found only ${calls.size} request bodies; the interface scan is broken", calls.size >= 40)
        val entries = mutableListOf<JsonElement>()
        val untyped = mutableListOf<String>()
        val broken = mutableListOf<String>()
        for (call in calls) {
            val type = raw(call.body)
            val serializable = runCatching { serializer(call.body) }.getOrNull()
            if (serializable == null || type == null || type.name.startsWith("kotlinx.serialization.json.") ||
                type.name.startsWith("okhttp3.") || Map::class.java.isAssignableFrom(type)
            ) {
                untyped += "${call.owner}.${call.function} ${call.method} ${call.path} (${call.body.typeName})"
                continue
            }
            for (blank in listOf(false, true)) {
                runCatching { fixture(call, blank) }
                    .onSuccess { entries += it }
                    .onFailure { broken += "${call.owner}.${call.function}: ${it.message}" }
            }
        }
        val out = File("build/request-contract/android-requests.json")
        out.parentFile.mkdirs()
        out.writeText(
            Json { prettyPrint = true }.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("app", app)
                    put("entries", JsonArray(entries))
                    put("untyped", JsonArray(untyped.map(::JsonPrimitive)))
                }
            )
        )
        println("Wrote ${entries.size} request fixtures to ${out.absolutePath}; ${untyped.size} untyped bodies")
        assertTrue("these DTOs could not be built from their own fields:\n" + broken.joinToString("\n"), broken.isEmpty())
    }
}
