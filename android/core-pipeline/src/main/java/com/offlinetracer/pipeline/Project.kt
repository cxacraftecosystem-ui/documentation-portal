package com.offlinetracer.pipeline

import com.offlinetracer.vector.FillRule
import com.offlinetracer.vector.LineCap
import com.offlinetracer.vector.LineJoin
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecPath
import com.offlinetracer.vector.VecPoint
import com.offlinetracer.vector.VecSeg
import com.offlinetracer.vector.VecShape
import com.offlinetracer.vector.VecStyle
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProjectMeta(
    val id: String = "",
    val name: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val tags: List<String> = emptyList(),
    val subjectId: String = "",
    val favourite: Boolean = false,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val thumbnailPath: String = "",
)

@Serializable
data class ProjectDocument(
    val meta: ProjectMeta = ProjectMeta(),
    val params: TraceParams = TraceParams(),
    val layersJson: String = "",
    val historyVersion: Int = 1,
    val schemaVersion: Int = ProjectCodec.SCHEMA_VERSION,
)

object ProjectCodec {

    const val SCHEMA_VERSION = 1

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun encode(doc: ProjectDocument): String = json.encodeToString(
        ProjectDocument.serializer(),
        doc.copy(schemaVersion = SCHEMA_VERSION, params = doc.params.sanitized()),
    )

    fun decode(text: String): ProjectDocument {
        val parsed = try {
            json.decodeFromString(ProjectDocument.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException(
                "This file is not a readable Offline Tracer project: ${e.message}", e,
            )
        }
        return parsed.copy(params = parsed.params.sanitized())
    }

    fun encodeDocument(doc: VecDocument): String {
        val layers = ArrayList<ProjectLayerDto>(doc.layers.size)
        for (layer in doc.layers) {
            val shapes = ArrayList<ProjectShapeDto>(layer.shapes.size)
            for (shape in layer.shapes) shapes.add(shapeToDto(shape))
            layers.add(
                ProjectLayerDto(
                    id = layer.id,
                    name = layer.name,
                    visible = layer.visible,
                    locked = layer.locked,
                    opacity = layer.opacity,
                    shapes = shapes,
                )
            )
        }
        return json.encodeToString(
            ProjectDocDto.serializer(),
            ProjectDocDto(
                schemaVersion = SCHEMA_VERSION,
                width = doc.width,
                height = doc.height,
                background = doc.background,
                layers = layers,
            ),
        )
    }

    fun decodeDocument(text: String): VecDocument {
        if (text.isBlank()) return VecDocument(0f, 0f, emptyList(), null)
        val dto = try {
            json.decodeFromString(ProjectDocDto.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException(
                "This drawing is not readable: ${e.message}", e,
            )
        }
        val layers = ArrayList<VecLayer>(dto.layers.size)
        for (layer in dto.layers) {
            val shapes = ArrayList<VecShape>(layer.shapes.size)
            for (shape in layer.shapes) shapes.add(dtoToShape(shape))
            layers.add(
                VecLayer(
                    id = layer.id,
                    name = layer.name,
                    shapes = shapes,
                    visible = layer.visible,
                    locked = layer.locked,
                    opacity = layer.opacity,
                )
            )
        }
        return VecDocument(dto.width, dto.height, layers, dto.background)
    }

    private const val KIND_LINE = 0
    private const val KIND_CUBIC = 1
    private const val KIND_QUAD = 2

    private fun shapeToDto(shape: VecShape): ProjectShapeDto {
        val path = shape.path
        val kinds = IntArray(path.segments.size)
        var floats = 0
        for (segment in path.segments) {
            floats += when (segment) {
                is VecSeg.Line -> 2
                is VecSeg.Cubic -> 6
                is VecSeg.Quad -> 4
            }
        }
        val coords = FloatArray(floats)
        var k = 0
        var i = 0
        for (segment in path.segments) {
            when (segment) {
                is VecSeg.Line -> {
                    kinds[i] = KIND_LINE
                    coords[k] = segment.to.x
                    coords[k + 1] = segment.to.y
                    k += 2
                }
                is VecSeg.Cubic -> {
                    kinds[i] = KIND_CUBIC
                    coords[k] = segment.c1.x
                    coords[k + 1] = segment.c1.y
                    coords[k + 2] = segment.c2.x
                    coords[k + 3] = segment.c2.y
                    coords[k + 4] = segment.to.x
                    coords[k + 5] = segment.to.y
                    k += 6
                }
                is VecSeg.Quad -> {
                    kinds[i] = KIND_QUAD
                    coords[k] = segment.c.x
                    coords[k + 1] = segment.c.y
                    coords[k + 2] = segment.to.x
                    coords[k + 3] = segment.to.y
                    k += 4
                }
            }
            i++
        }
        val style = shape.style
        return ProjectShapeDto(
            startX = path.start.x,
            startY = path.start.y,
            kinds = kinds,
            coords = coords,
            closed = path.closed,
            id = path.id,
            widths = path.strokeWidths?.copyOf(),
            stroke = style.stroke,
            strokeWidth = style.strokeWidth,
            fill = style.fill,
            fillRule = style.fillRule.name,
            cap = style.cap.name,
            join = style.join.name,
            miterLimit = style.miterLimit,
            opacity = style.opacity,
        )
    }

    private fun dtoToShape(dto: ProjectShapeDto): VecShape {
        val segments = ArrayList<VecSeg>(dto.kinds.size)
        var i = 0
        var k = 0
        while (i < dto.kinds.size) {
            val kind = dto.kinds[i]
            val need = when (kind) {
                KIND_LINE -> 2
                KIND_CUBIC -> 6
                KIND_QUAD -> 4
                else -> -1
            }
            if (need < 0 || k + need > dto.coords.size) break
            when (kind) {
                KIND_LINE -> segments.add(VecSeg.Line(VecPoint(dto.coords[k], dto.coords[k + 1])))
                KIND_CUBIC -> segments.add(
                    VecSeg.Cubic(
                        VecPoint(dto.coords[k], dto.coords[k + 1]),
                        VecPoint(dto.coords[k + 2], dto.coords[k + 3]),
                        VecPoint(dto.coords[k + 4], dto.coords[k + 5]),
                    )
                )
                KIND_QUAD -> segments.add(
                    VecSeg.Quad(
                        VecPoint(dto.coords[k], dto.coords[k + 1]),
                        VecPoint(dto.coords[k + 2], dto.coords[k + 3]),
                    )
                )
            }
            k += need
            i++
        }
        val path = VecPath(
            start = VecPoint(dto.startX, dto.startY),
            segments = segments,
            closed = dto.closed,
            id = dto.id,
            strokeWidths = dto.widths?.copyOf(),
        )
        val style = VecStyle(
            stroke = dto.stroke,
            strokeWidth = dto.strokeWidth,
            fill = dto.fill,
            fillRule = fillRuleOf(dto.fillRule),
            cap = capOf(dto.cap),
            join = joinOf(dto.join),
            miterLimit = dto.miterLimit,
            opacity = dto.opacity,
        )
        return VecShape(path, style)
    }

    private fun fillRuleOf(name: String): FillRule =
        if (name.trim().uppercase() == "NONZERO") FillRule.NONZERO else FillRule.EVENODD

    private fun capOf(name: String): LineCap = when (name.trim().uppercase()) {
        "BUTT" -> LineCap.BUTT
        "SQUARE" -> LineCap.SQUARE
        else -> LineCap.ROUND
    }

    private fun joinOf(name: String): LineJoin = when (name.trim().uppercase()) {
        "MITER" -> LineJoin.MITER
        "BEVEL" -> LineJoin.BEVEL
        else -> LineJoin.ROUND
    }
}

@Serializable
private class ProjectDocDto(
    val schemaVersion: Int = ProjectCodec.SCHEMA_VERSION,
    val width: Float = 0f,
    val height: Float = 0f,
    val background: Int? = null,
    val layers: List<ProjectLayerDto> = emptyList(),
)

@Serializable
private class ProjectLayerDto(
    val id: String = "",
    val name: String = "",
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f,
    val shapes: List<ProjectShapeDto> = emptyList(),
)

@Serializable
private class ProjectShapeDto(
    val startX: Float = 0f,
    val startY: Float = 0f,
    val kinds: IntArray = IntArray(0),
    val coords: FloatArray = FloatArray(0),
    val closed: Boolean = false,
    val id: String = "",
    val widths: FloatArray? = null,
    val stroke: Int? = 0xFF000000.toInt(),
    val strokeWidth: Float = 1.5f,
    val fill: Int? = null,
    val fillRule: String = "EVENODD",
    val cap: String = "ROUND",
    val join: String = "ROUND",
    val miterLimit: Float = 4f,
    val opacity: Float = 1f,
)
