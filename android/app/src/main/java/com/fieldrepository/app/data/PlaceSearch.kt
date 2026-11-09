package com.fieldrepository.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PlaceBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    val isPoint: Boolean get() = west == east && south == north
}

data class PlaceHit(
    val id: String,
    val name: String,
    val context: String,
    val lon: Double,
    val lat: Double,
    val bounds: PlaceBounds? = null
)

const val PLACE_SEARCH_MIN_QUERY_LENGTH = 3

private const val PLACE_SEARCH_COUNTRY = "in"

private const val PLACE_SEARCH_LIMIT = 8

fun placeSearchAvailable(key: String): Boolean = key.isNotBlank()

class PlaceSearchException(val status: Int?, message: String) : IOException(message)

internal fun encodeUriComponent(value: String): String = percentEncode(value, "-_.!~*'()", spaceAsPlus = false)

private fun formUrlEncode(value: String): String = percentEncode(value, "*-._", spaceAsPlus = true)

private fun percentEncode(value: String, alsoKeep: String, spaceAsPlus: Boolean): String {
    val out = StringBuilder(value.length)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val ch = code.toChar()
        when {
            code < 128 && (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9') -> out.append(ch)
            code < 128 && alsoKeep.indexOf(ch) >= 0 -> out.append(ch)
            spaceAsPlus && ch == ' ' -> out.append('+')
            else -> out.append('%').append(String.format(java.util.Locale.ROOT, "%02X", code))
        }
    }
    return out.toString()
}

internal fun jsNumber(value: Double): String {
    if (value == 0.0) return "0"

    val raw = java.lang.Double.toString(value)
    val negative = raw.startsWith("-")
    val body = if (negative) raw.substring(1) else raw

    val exponentAt = body.indexOf('E')
    val mantissa = if (exponentAt < 0) body else body.substring(0, exponentAt)
    val exponent = if (exponentAt < 0) 0 else body.substring(exponentAt + 1).toInt()
    val dotAt = mantissa.indexOf('.')
    val whole = if (dotAt < 0) mantissa else mantissa.substring(0, dotAt)
    val fraction = if (dotAt < 0) "" else mantissa.substring(dotAt + 1)

    var digits = (whole + fraction).trimStart('0')
    var scale = exponent - fraction.length
    while (digits.length > 1 && digits.endsWith("0")) {
        digits = digits.dropLast(1)
        scale += 1
    }
    if (digits.isEmpty()) return raw

    val count = digits.length
    val n = count + scale
    val sign = if (negative) "-" else ""

    return sign + when {
        n in 1..21 && count <= n -> digits + "0".repeat(n - count)
        n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
        n in -5..0 -> "0." + "0".repeat(-n) + digits
        else -> {
            val head = if (count == 1) digits else digits.substring(0, 1) + "." + digits.substring(1)
            val power = n - 1
            head + "e" + (if (power >= 0) "+$power" else "-${-power}")
        }
    }
}

internal fun placeSearchUrl(query: String, key: String, proximity: Pair<Double, Double>?): String {
    val parameters = StringBuilder()
        .append("key=").append(formUrlEncode(key))
        .append("&country=").append(formUrlEncode(PLACE_SEARCH_COUNTRY))
        .append("&language=").append(formUrlEncode("en"))
        .append("&limit=").append(formUrlEncode(PLACE_SEARCH_LIMIT.toString()))
        .append("&autocomplete=").append(formUrlEncode("true"))
    if (proximity != null) {
        parameters.append("&proximity=")
            .append(formUrlEncode("${jsNumber(proximity.first)},${jsNumber(proximity.second)}"))
    }
    return "https://api.maptiler.com/geocoding/${encodeUriComponent(query)}.json?$parameters"
}

private val placeSearchJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun placeSearchStatusFailure(status: Int): PlaceSearchException =
    PlaceSearchException(status, "The place search answered $status.")

private fun describeFeature(text: String?, placeName: String?): Pair<String, String> {
    val name = (text ?: "").trim()
    val full = (placeName ?: "").trim()
    if (name.isEmpty()) return full to ""
    if (full.isEmpty() || full == name) return name to ""
    return name to if (full.startsWith("$name,")) full.substring(name.length + 1).trim() else full
}

private fun JsonArray.finiteNumberAt(index: Int): Double? {
    val primitive = getOrNull(index) as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun JsonObject.string(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

internal fun parsePlaceSearchResponse(body: String): List<PlaceHit> {
    val root = runCatching { placeSearchJson.parseToJsonElement(body).jsonObject }.getOrElse {
        throw PlaceSearchException(502, "The place search answered with something that could not be read.")
    }
    val features = root["features"] as? JsonArray ?: return emptyList()
    return features
        .mapNotNull { it as? JsonObject }
        .mapNotNull { feature ->
            val centre = feature["center"] as? JsonArray ?: return@mapNotNull null
            val lon = centre.finiteNumberAt(0) ?: return@mapNotNull null
            val lat = centre.finiteNumberAt(1) ?: return@mapNotNull null
            Triple(feature, lon, lat)
        }
        .mapIndexed { index, (feature, lon, lat) ->
            val (name, context) = describeFeature(feature.string("text"), feature.string("place_name"))
            val box = (feature["bbox"] as? JsonArray)?.takeIf { it.size == 4 }
            val bounds = box?.let {
                val west = it.finiteNumberAt(0)
                val south = it.finiteNumberAt(1)
                val east = it.finiteNumberAt(2)
                val north = it.finiteNumberAt(3)
                if (west == null || south == null || east == null || north == null) null
                else PlaceBounds(west = west, south = south, east = east, north = north)
            }
            PlaceHit(
                id = feature.string("id") ?: "hit-$index",
                name = name,
                context = context,
                lon = lon,
                lat = lat,
                bounds = bounds
            )
        }
}

fun describePlaceSearchFailure(error: Throwable): String {
    val status = (error as? PlaceSearchException)?.status
    return when {
        status == null || status == 408 ->
            "No connection, so places cannot be looked up right now. The map still works, and the coordinate boxes need no network at all."
        status >= 500 || status == 429 ->
            "The place search is not answering just now — this is the service, not your connection. Try again in a moment."
        else ->
            "Place search is not available right now. Move the map or type the coordinates."
    }
}

private val placeSearchClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

internal fun placeSearchBias(proximity: Pair<Double, Double>?): Pair<Double, Double>? =
    proximity?.takeIf {
        it.first.isFinite() && it.second.isFinite() && it.first in -180.0..180.0 && it.second in -90.0..90.0
    }

suspend fun searchPlaces(
    query: String,
    key: String,
    proximity: Pair<Double, Double>?
): List<PlaceHit> {
    if (!placeSearchAvailable(key)) {
        throw PlaceSearchException(503, "Place search is not available right now.")
    }
    val request = Request.Builder().url(placeSearchUrl(query, key, placeSearchBias(proximity))).get().build()

    val body = suspendCancellableCoroutine { continuation ->
        val call = placeSearchClient.newCall(request)
        continuation.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(PlaceSearchException(null, e.message ?: "No answer"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        if (continuation.isActive) continuation.resumeWithException(placeSearchStatusFailure(it.code))
                        return
                    }
                    val text = runCatching { it.body?.string().orEmpty() }.getOrElse { error ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                PlaceSearchException(null, error.message ?: "The answer stopped part-way through.")
                            )
                        }
                        return
                    }
                    if (continuation.isActive) continuation.resume(text)
                }
            }
        })
    }
    return parsePlaceSearchResponse(body)
}

const val PLACE_MAX_ZOOM = 14

const val PLACE_POINT_ZOOM = 13

sealed interface PlaceCamera {
    data class Frame(val south: Double, val west: Double, val north: Double, val east: Double) : PlaceCamera

    data class Point(val lat: Double, val lon: Double, val zoom: Int) : PlaceCamera
}

fun cameraForPlace(hit: PlaceHit): PlaceCamera {
    val bounds = hit.bounds
    if (bounds != null && !bounds.isPoint) {
        return PlaceCamera.Frame(
            south = bounds.south,
            west = bounds.west,
            north = bounds.north,
            east = bounds.east
        )
    }
    return PlaceCamera.Point(lat = hit.lat, lon = hit.lon, zoom = PLACE_POINT_ZOOM)
}

fun leafletCameraScript(camera: PlaceCamera): String = when (camera) {
    is PlaceCamera.Frame ->
        "if(window.__map){window.__map.fitBounds(" +
            "[[${jsNumber(camera.south)},${jsNumber(camera.west)}]," +
            "[${jsNumber(camera.north)},${jsNumber(camera.east)}]]," +
            "{maxZoom:$PLACE_MAX_ZOOM,padding:[24,24]});}"
    is PlaceCamera.Point ->
        "if(window.__map){window.__map.setView(" +
            "[${jsNumber(camera.lat)},${jsNumber(camera.lon)}],${camera.zoom});}"
}
