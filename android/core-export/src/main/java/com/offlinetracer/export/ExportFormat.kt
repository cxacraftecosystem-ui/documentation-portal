package com.offlinetracer.export

import com.offlinetracer.vector.Mat2D
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecShape
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    val isVector: Boolean,
    val belongsInPhotoGallery: Boolean,
    val choiceLabel: String,
    val consequence: String,
) {
    PNG(
        extension = "png",
        mimeType = "image/png",
        isVector = false,
        belongsInPhotoGallery = true,
        choiceLabel = "PNG",
        consequence = "Opens anywhere — gallery, chat, printer. Pixels, so lines soften if it is enlarged.",
    ),
    JPEG(
        extension = "jpg",
        mimeType = "image/jpeg",
        isVector = false,
        belongsInPhotoGallery = true,
        choiceLabel = "JPEG",
        consequence = "Opens anywhere, always on a solid background, and it blurs fine lines slightly.",
    ),
    WEBP(
        extension = "webp",
        mimeType = "image/webp",
        isVector = false,
        belongsInPhotoGallery = true,
        choiceLabel = "WEBP",
        consequence = "Opens on any recent phone and browser; some print and desktop tools still refuse it.",
    ),
    SVG(
        extension = "svg",
        mimeType = "image/svg+xml",
        isVector = true,
        belongsInPhotoGallery = false,
        choiceLabel = "SVG — vector",
        consequence = "The editable line art, sharp at any size, for cutting and plotting. " +
            "Needs a vector app or a browser; a phone gallery cannot show it.",
    ),
    PDF(
        extension = "pdf",
        mimeType = "application/pdf",
        isVector = true,
        belongsInPhotoGallery = false,
        choiceLabel = "PDF",
        consequence = "Prints at the exact size and opens in any PDF reader. Not editable line art.",
    ),
    EPS(
        extension = "eps",
        mimeType = "application/postscript",
        isVector = true,
        belongsInPhotoGallery = false,
        choiceLabel = "EPS",
        consequence = "For print shops and older cutting software. Almost nothing on a phone opens it.",
    ),
    DXF(
        extension = "dxf",
        mimeType = "image/vnd.dxf",
        isVector = true,
        belongsInPhotoGallery = false,
        choiceLabel = "DXF — CAD",
        consequence = "Millimetres, for CAD, laser cutters and CNC. Nothing on a phone will display it.",
    ),
    TIFF(
        extension = "tiff",
        mimeType = "image/tiff",
        isVector = false,
        belongsInPhotoGallery = false,
        choiceLabel = "TIFF",
        consequence = "For print and scanning work on a computer. Android itself cannot decode TIFF, " +
            "so no gallery on this device will show it.",
    ),
    BMP(
        extension = "bmp",
        mimeType = "image/bmp",
        isVector = false,
        belongsInPhotoGallery = true,
        choiceLabel = "BMP",
        consequence = "Uncompressed pixels for old software. Very large files; PNG is better otherwise.",
    ),
    PROJECT(
        extension = "otproj",
        mimeType = "application/json",
        isVector = false,
        belongsInPhotoGallery = false,
        choiceLabel = "Project",
        consequence = "Reopens in Offline Tracer with its trace settings. It is not a picture.",
    );

    val isRaster: Boolean
        get() = this == PNG || this == JPEG || this == WEBP || this == TIFF || this == BMP

    val isPlatformEncoded: Boolean get() = this == JPEG || this == WEBP
}

data class ExportOptions(
    val format: ExportFormat,
    val width: Int = 0,
    val height: Int = 0,
    val scale: Float = 1f,
    val background: Int? = null,
    val quality: Int = 95,
    val dpi: Int = 300,
    val precision: Int = 2,
    val includeMetadata: Boolean = true,
    val flattenLayers: Boolean = false,
) {
    val isVector: Boolean get() = format.isVector

    val extension: String get() = format.extension

    val mimeType: String get() = format.mimeType

    val belongsInPhotoGallery: Boolean get() = format.belongsInPhotoGallery

    val effectiveDpi: Int get() = if (dpi > 0) dpi else 72
}

internal object ExportGeom {

    const val POINTS_PER_INCH = 72f

    const val MM_PER_INCH = 25.4f

    private val POW10 = longArrayOf(
        1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L, 100_000_000L,
    )

    fun num(value: Float, precision: Int): String {
        val p = if (precision < 0) 0 else if (precision > 8) 8 else precision
        val d = value.toDouble()
        val finite = if (d.isNaN() || d.isInfinite()) 0.0 else d
        val safe = if (finite > 1e9) 1e9 else if (finite < -1e9) -1e9 else finite
        val scale = POW10[p]
        var r = (safe * scale).roundToLong()
        if (r == 0L) return "0"
        val sb = StringBuilder(24)
        if (r < 0L) {
            sb.append('-')
            r = -r
        }
        sb.append(r / scale)
        var frac = r % scale
        if (frac != 0L) {
            sb.append('.')
            var div = scale / 10L
            var pending = 0
            while (div > 0L) {
                val digit = (frac / div).toInt()
                frac %= div
                div /= 10L
                if (digit == 0) {
                    pending++
                } else {
                    var i = 0
                    while (i < pending) {
                        sb.append('0')
                        i++
                    }
                    pending = 0
                    sb.append('0' + digit)
                }
            }
        }
        return sb.toString()
    }

    fun scaleXY(o: ExportOptions, docW: Float, docH: Float): FloatArray {
        val w = if (docW.isFinite() && docW > 0f) docW else 1f
        val h = if (docH.isFinite() && docH > 0f) docH else 1f
        val base = if (o.scale.isFinite() && o.scale > 0f) o.scale else 1f
        var sx = if (o.width > 0) o.width / w else Float.NaN
        var sy = if (o.height > 0) o.height / h else Float.NaN
        if (sx.isNaN() && sy.isNaN()) {
            sx = base
            sy = base
        } else if (sx.isNaN()) {
            sx = sy
        } else if (sy.isNaN()) {
            sy = sx
        }
        if (!sx.isFinite() || sx <= 0f) sx = base
        if (!sy.isFinite() || sy <= 0f) sy = base
        return floatArrayOf(sx, sy)
    }

    fun outputSize(o: ExportOptions, docW: Float, docH: Float): IntArray {
        val s = scaleXY(o, docW, docH)
        val w = if (o.width > 0) o.width else (docW * s[0]).roundToInt()
        val h = if (o.height > 0) o.height else (docH * s[1]).roundToInt()
        return intArrayOf(if (w < 1) 1 else w, if (h < 1) 1 else h)
    }

    fun scaleDocument(doc: VecDocument, sx: Float, sy: Float): VecDocument {
        if (sx == 1f && sy == 1f) return doc
        val m = Mat2D.scale(sx, sy)
        val strokeScale = sqrt(abs(sx * sy))
        val layers = ArrayList<VecLayer>(doc.layers.size)
        for (layer in doc.layers) {
            val shapes = ArrayList<VecShape>(layer.shapes.size)
            for (shape in layer.shapes) {
                shapes.add(
                    shape.copy(
                        path = shape.path.transform(m),
                        style = shape.style.copy(strokeWidth = shape.style.strokeWidth * strokeScale),
                    )
                )
            }
            layers.add(layer.copy(shapes = shapes))
        }
        return VecDocument(doc.width * sx, doc.height * sy, layers, doc.background)
    }

    fun geometryBounds(doc: VecDocument): FloatArray? {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var any = false
        for (layer in doc.layers) {
            if (!layer.visible) continue
            for (shape in layer.shapes) {
                val path = shape.path
                if (path.isEmpty()) continue
                val b = path.bounds()
                if (b.size < 4) continue
                if (!b[0].isFinite() || !b[1].isFinite() || !b[2].isFinite() || !b[3].isFinite()) continue
                val stroke = shape.style.stroke
                val pad = if (stroke != null && ((stroke ushr 24) and 0xFF) != 0 &&
                    shape.style.strokeWidth > 0f
                ) shape.style.strokeWidth * 0.5f else 0f
                if (b[0] - pad < minX) minX = b[0] - pad
                if (b[1] - pad < minY) minY = b[1] - pad
                if (b[2] + pad > maxX) maxX = b[2] + pad
                if (b[3] + pad > maxY) maxY = b[3] + pad
                any = true
            }
        }
        if (!any) return null
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    fun red(argb: Int): Float = ((argb ushr 16) and 0xFF) / 255f

    fun green(argb: Int): Float = ((argb ushr 8) and 0xFF) / 255f

    fun blue(argb: Int): Float = (argb and 0xFF) / 255f

    fun alpha(argb: Int): Float = ((argb ushr 24) and 0xFF) / 255f
}
