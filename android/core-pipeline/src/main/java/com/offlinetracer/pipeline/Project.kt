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

/**
 * What a saved project contains.
 *
 * **Every field has a default.** That is not laziness; it is the whole forward-compatibility strategy.
 * A project written by build 4 and opened by build 3 is missing fields build 3 has never heard of, and
 * a project written by build 3 and opened by build 4 is missing fields build 4 added. With defaults on
 * everything, both directions decode: the reader fills in what it cannot find and the user's drawing
 * opens. Without them, `kotlinx.serialization` throws `MissingFieldException` and the file is simply
 * lost — which for the only copy of somebody's traced artwork is not a recoverable error.
 */
@Serializable
data class ProjectMeta(
    val id: String = "",
    val name: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val tags: List<String> = emptyList(),
    /** A [Subjects] id, or blank. Blank means the user never chose one, not that they chose "photo". */
    val subjectId: String = "",
    val favourite: Boolean = false,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val thumbnailPath: String = "",
)

/**
 * One project file: its metadata, the parameters that produced it, and the drawing itself.
 *
 * @param layersJson the vector document, encoded by [ProjectCodec.encodeDocument]. Nested as a string
 *   rather than as a structure so that the geometry — by far the largest part of the file and the part
 *   most likely to gain fields — versions independently of the envelope around it. A reader that cannot
 *   understand a newer geometry schema still recovers the name, the tags and every parameter.
 * @param historyVersion the undo-history format the host layer wrote alongside this file.
 * @param schemaVersion the envelope's own version, stamped by [ProjectCodec.encode]. A file with no
 *   version field at all is from before versioning existed and reads as version 1.
 */
@Serializable
data class ProjectDocument(
    val meta: ProjectMeta = ProjectMeta(),
    val params: TraceParams = TraceParams(),
    val layersJson: String = "",
    val historyVersion: Int = 1,
    val schemaVersion: Int = ProjectCodec.SCHEMA_VERSION,
)

/**
 * Reads and writes the project format.
 *
 * Three properties are load-bearing and are asserted by `ProjectTest`:
 *
 *  1. **Round-trip exactness.** `decode(encode(d)) == d` for every document, including the geometry:
 *     the path data is stored as raw coordinate arrays rather than as an SVG `d` string precisely so
 *     that no rounding sits between a save and the next load. An editor that loses a thousandth of a
 *     pixel per save loses a visible amount after fifty saves.
 *  2. **Unknown fields are ignored, never fatal.** A file from a newer build opens.
 *  3. **Missing fields fall back to defaults.** A file from an older build opens.
 *
 * What it does *not* do is silently accept nonsense: input that is not JSON at all raises
 * [IllegalArgumentException] with a sentence the UI can show. Returning a blank project for a corrupt
 * file would be the worst of both worlds — the user would see an empty canvas, save over it, and lose
 * the thing that was actually still there on disk.
 */
object ProjectCodec {

    /**
     * The envelope schema version.
     *
     * Bump this only when the *meaning* of an existing field changes, never for an addition — an added
     * field with a default is already backwards and forwards compatible, and bumping for it would make
     * the version number useless as a signal that something needs migrating.
     */
    const val SCHEMA_VERSION = 1

    private val json = Json {
        prettyPrint = true
        // `prettyPrintIndent` is deliberately left at its default: setting it is an opt-in experimental
        // API, and a warning-free build is worth more than a two-space indent.
        // Write every field even when it equals its default: a project file is a document a human may
        // have to read or diff, and "the field is absent" and "the field is at its default" being the
        // same thing on disk makes both of those jobs guesswork.
        encodeDefaults = true
        // Property 2: a field this build has never heard of is skipped rather than fatal.
        ignoreUnknownKeys = true
        // Property 3 for *values* as well as fields: an enum constant that no longer exists, or an
        // explicit null where this build wants a value, falls back to the property's default instead
        // of taking the file down. Both are exactly what an older build's file looks like.
        coerceInputValues = true
    }

    /** Serialises [doc], stamping the current [SCHEMA_VERSION] and clamping the parameters. */
    fun encode(doc: ProjectDocument): String = json.encodeToString(
        ProjectDocument.serializer(),
        doc.copy(schemaVersion = SCHEMA_VERSION, params = doc.params.sanitized()),
    )

    /**
     * Parses a project.
     *
     * The parameters come back through [TraceParams.sanitized], because a saved file is one of the three
     * untrusted sources that function exists for: it may carry a value that was legal in the build that
     * wrote it and is not legal now, and no stage downstream re-checks.
     *
     * @throws IllegalArgumentException if [text] is not JSON. Missing and unknown fields never throw.
     */
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

    /** Serialises a vector document for [ProjectDocument.layersJson]. */
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

    /**
     * Parses a vector document.
     *
     * Blank input returns an empty document rather than raising. That is not leniency for its own sake:
     * a project created and saved before anything was traced legitimately has an empty `layersJson`, and
     * treating the commonest possible state as a corrupt file would be absurd.
     *
     * @throws IllegalArgumentException if [text] is non-blank and not JSON.
     */
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

    // -------------------------------------------------------------------------------------------
    // Geometry <-> DTO
    // -------------------------------------------------------------------------------------------

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
        // An index walk rather than a for-each so that a truncated `coords` array — or a segment kind
        // written by a future build — stops the walk instead of throwing. A partially recovered path is
        // worth more to the user than an exception.
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

    /**
     * Enum names are matched by hand and fall back to the default rather than being decoded as enums.
     *
     * `FillRule`, `LineCap` and `LineJoin` live in `:core-vector`, which is not a serialisation module
     * and carries no `@Serializable`. Storing the *name* and parsing it here means a rename over there
     * cannot invalidate every saved project over here, and an unrecognised name degrades to a sane
     * default instead of taking the file down.
     */
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

/**
 * On-disk shape of a [VecDocument].
 *
 * Deliberately *not* `VecDocument` itself with `@Serializable` bolted on: the vector types belong to
 * `:core-vector`, which has no serialisation dependency and must stay free to change its field names.
 * A DTO layer means exactly one file has to be edited when either side moves.
 *
 * Kept as `class` rather than `data class` because two of the fields are arrays, and a generated
 * `equals` on an array compares identities. Nothing compares DTOs — they exist for the width of one
 * encode or decode call — so the generated members would be pure liability.
 */
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

/**
 * One path plus its style, flattened.
 *
 * The geometry is two parallel arrays — one segment kind per segment, and the coordinates packed
 * end-to-end (2 floats for a line, 6 for a cubic, 4 for a quad) — rather than a list of tagged objects.
 * A traced photograph runs to tens of thousands of segments, and a JSON object per segment turns a
 * 400 KB file into a 6 MB one for no benefit to any reader.
 *
 * It is also *not* an SVG `d` string, which would have been the obvious compact choice: `d` rounds to a
 * fixed precision, and a save/load cycle that quietly moves every point is unacceptable in an editor.
 */
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
