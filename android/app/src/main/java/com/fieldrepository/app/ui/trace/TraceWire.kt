package com.fieldrepository.app.ui.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class TraceFailureKind {
    OUT_OF_MEMORY,

    ENGINE_ERROR,

    FRAME_MISMATCH,

    IMAGE_UNREADABLE,

    IMAGE_EMPTY,
}

const val TRACE_DETAIL_MAX: Int = 160

fun traceSentence(kind: TraceFailureKind, detail: String = ""): String {
    val trimmed = detail.trim().let { if (it.isNotEmpty() && it.length <= TRACE_DETAIL_MAX) it else "" }
    return when (kind) {
        TraceFailureKind.OUT_OF_MEMORY ->
            "Ran out of memory. Set resolution to Fast, avoid Canny, or close other apps."

        TraceFailureKind.ENGINE_ERROR ->
            if (trimmed.isEmpty()) {
                "Couldn't trace this photo. Try another engine or Fast resolution."
            } else {
                "Couldn't trace this photo: $trimmed"
            }

        TraceFailureKind.FRAME_MISMATCH ->
            "The drawing is unaffected, but the page was rectified. Turn off “Rectify the page” to compare." +
                if (trimmed.isEmpty()) "" else " ($trimmed)"

        TraceFailureKind.IMAGE_UNREADABLE ->
            "Can't read this photo here. Try tracing it on the web."

        TraceFailureKind.IMAGE_EMPTY ->
            "This photo is empty. Choose another."
    }
}

class TraceHostFailure(
    val kind: TraceFailureKind,
    val detail: String = "",
    cause: Throwable? = null,
) : Exception(traceSentence(kind, detail), cause)

const val TRACE_VERB_LINE: Byte = 0

const val TRACE_VERB_QUAD: Byte = 1

const val TRACE_VERB_CUBIC: Byte = 2

class TraceStyle(
    val stroke: Int?,
    val strokeWidth: Float,
    val fill: Int?,
    val fillRule: String,
    val cap: String,
    val join: String,
    val miterLimit: Float,
    val opacity: Float,
)

class TraceGeometry(
    val coords: FloatArray,
    val verbs: ByteArray,
    val verbStarts: IntArray,
    val coordStarts: IntArray,
    val closed: ByteArray,
    val styleTable: List<TraceStyle>,
    val styleIndex: IntArray,
) {
    val shapeCount: Int get() = closed.size

    fun isClosed(shape: Int): Boolean = closed[shape].toInt() != 0

    fun styleOf(shape: Int): TraceStyle = styleTable[styleIndex[shape]]

    fun validate() {
        val n = shapeCount
        if (verbStarts.size != n + 1) bad("verbStarts is ${verbStarts.size} for $n shapes")
        if (coordStarts.size != n + 1) bad("coordStarts is ${coordStarts.size} for $n shapes")
        if (styleIndex.size != n) bad("styleIndex is ${styleIndex.size} for $n shapes")
        if (n > 0 && styleTable.isEmpty()) bad("the style table is empty for $n shapes")
        if (verbStarts[n] != verbs.size) {
            bad("verbs is ${verbs.size} long, verbStarts ends at ${verbStarts[n]}")
        }
        if (coordStarts[n] != coords.size) {
            bad("coords is ${coords.size} long, coordStarts ends at ${coordStarts[n]}")
        }
        for (i in 0 until n) {
            if (verbStarts[i] < 0 || verbStarts[i] > verbStarts[i + 1]) {
                bad("verbStarts does not increase at shape $i")
            }
            if (coordStarts[i] < 0 || coordStarts[i] > coordStarts[i + 1]) {
                bad("coordStarts does not increase at shape $i")
            }
            if (styleIndex[i] !in styleTable.indices) bad("shape $i names style ${styleIndex[i]}")
            var want = 2
            for (v in verbStarts[i] until verbStarts[i + 1]) {
                want += when (verbs[v]) {
                    TRACE_VERB_LINE -> 2
                    TRACE_VERB_QUAD -> 4
                    TRACE_VERB_CUBIC -> 6
                    else -> bad("shape $i has verb ${verbs[v]}, which is not a line, quad or cubic")
                }
            }
            val have = coordStarts[i + 1] - coordStarts[i]
            if (have != want) bad("shape $i has $have coordinates where its verbs need $want")
        }
    }

    private fun bad(why: String): Nothing =
        throw TraceHostFailure(TraceFailureKind.ENGINE_ERROR, why)
}

class TraceDecoded(
    val svg: String,
    val geometry: TraceGeometry,
    val background: Int?,
    val width: Int,
    val height: Int,
    val workingWidth: Int,
    val workingHeight: Int,
    val shapeCount: Int,
    val nodeCount: Int,
    val stages: List<TraceStageTiming>,
    val totalMillis: Long,
    val notes: List<String>,
    val appliedParams: TraceValues,
    val autoSubjectId: String,
    val suggestedStyleId: String,
)

fun traceArgbRowToRgba(pixels: IntArray, width: Int, out: ByteArray, rowStart: Int) {
    var o = rowStart
    for (x in 0 until width) {
        val p = pixels[x]
        out[o] = ((p ushr 16) and 0xFF).toByte()
        out[o + 1] = ((p ushr 8) and 0xFF).toByte()
        out[o + 2] = (p and 0xFF).toByte()
        out[o + 3] = ((p ushr 24) and 0xFF).toByte()
        o += 4
    }
}

const val TRACE_DECODE_MAX_EDGE_PX: Int = 4096

fun traceWorkingSize(width: Int, height: Int, maxEdge: Int = TRACE_DECODE_MAX_EDGE_PX): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= 0 || maxEdge <= 0 || longest <= maxEdge) return width to height
    val scale = maxEdge.toDouble() / longest.toDouble()
    val w = maxOf(1, Math.round(width * scale).toInt())
    val h = maxOf(1, Math.round(height * scale).toInt())
    return w to h
}

const val TRACE_DEFAULT_MAX_WORKING_EDGE: Int = 2048

const val TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE: Int = 1024

fun traceCeilings(totalRamBytes: Long?): Pair<Int, Int> {
    val small = totalRamBytes == null || totalRamBytes < 3L * 1024L * 1024L * 1024L
    return if (small) {
        (TRACE_DEFAULT_MAX_WORKING_EDGE / 2) to (TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE / 2)
    } else {
        TRACE_DEFAULT_MAX_WORKING_EDGE to TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE
    }
}

private val traceJson = Json

fun traceValuesOf(wire: String): TraceValues {
    val tree = runCatching { traceJson.parseToJsonElement(wire) }.getOrNull() as? JsonObject
        ?: throw TraceHostFailure(
            TraceFailureKind.ENGINE_ERROR,
            "a parameter tree that is not a JSON object",
        )
    val leaves = LinkedHashMap<String, TraceValue>()
    traceFlatten(tree, "", leaves)
    return TraceValues(leaves, wire)
}

private fun traceFlatten(tree: JsonObject, prefix: String, out: MutableMap<String, TraceValue>) {
    for ((key, value) in tree) {
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        when {
            value is JsonNull -> out[path] = TraceValue.Absent
            value is JsonObject -> traceFlatten(value, path, out)
            value is JsonArray -> Unit
            value is JsonPrimitive -> out[path] = traceLeafOf(value, path)
            else -> Unit
        }
    }
}

private fun traceLeafOf(primitive: JsonPrimitive, path: String): TraceValue {
    if (primitive.isString) return TraceValue.Choice(primitive.content)
    return when (val raw = primitive.content) {
        "true" -> TraceValue.Flag(true)
        "false" -> TraceValue.Flag(false)
        else -> raw.toDoubleOrNull()?.let { TraceValue.Num(it) }
            ?: throw TraceHostFailure(
                TraceFailureKind.ENGINE_ERROR,
                "$path is neither a number, a flag nor a name",
            )
    }
}

fun tracePatchJson(patch: Map<String, TraceValue>): String {
    val out = StringBuilder(patch.size * 24 + 2)
    out.append('{')
    var first = true
    for ((key, value) in patch) {
        if (!first) out.append(',')
        first = false
        out.append(traceJsonString(key)).append(':')
        when (value) {
            is TraceValue.Num -> {
                if (!value.value.isFinite()) {
                    throw TraceHostFailure(
                        TraceFailureKind.ENGINE_ERROR,
                        "$key was set to ${value.value}, which is not a number the engine can be sent",
                    )
                }
                out.append(value.value.toString())
            }
            is TraceValue.Flag -> out.append(if (value.value) "true" else "false")
            is TraceValue.Choice -> out.append(traceJsonString(value.value))
            TraceValue.Absent -> out.append("null")
        }
    }
    out.append('}')
    return out.toString()
}

fun traceJsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when {
            ch == '"' -> out.append("\\\"")
            ch == '\\' -> out.append("\\\\")
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch == '\t' -> out.append("\\t")
            ch == '\b' -> out.append("\\b")
            ch.code == 0x0C -> out.append("\\f")
            ch < ' ' || ch.code == 0x2028 || ch.code == 0x2029 ->
                out.append("\\u").append(String.format("%04x", ch.code))
            else -> out.append(ch)
        }
    }
    out.append('"')
    return out.toString()
}
