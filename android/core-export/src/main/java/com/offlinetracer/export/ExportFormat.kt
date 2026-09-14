package com.offlinetracer.export

import com.offlinetracer.vector.Mat2D
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecShape
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Every container this app can write, plus the two it deliberately refuses to write itself.
 *
 * [JPEG] and [WEBP] are listed because the *user* picks them, not because `:core-export` encodes
 * them: a hand-rolled baseline JPEG would be measurably worse than the one already sitting in
 * every platform, so [Exporter] throws for those two and the Android / web shells route them
 * through `Bitmap.compress` and `canvas.toBlob`. Silently substituting PNG bytes under a `.jpg`
 * name is the failure this split exists to make impossible.
 *
 * The enum also carries the two facts that decide **delivery** rather than encoding —
 * [belongsInPhotoGallery] and [consequence]. They live here because both are properties of the
 * container, and a host that has to re-derive either one gets it wrong: routing on the MIME prefix
 * puts SVG in the camera roll, and a format chosen with nothing said about it fails in another app
 * hours later.
 */
enum class ExportFormat(
    /** File extension with no leading dot. */
    val extension: String,
    /** IANA media type, suitable for a `ClipData`, an `Intent` or a `Blob`. */
    val mimeType: String,
    /** True when the format stores resolution-independent geometry rather than pixels. */
    val isVector: Boolean,
    /**
     * True only for the containers a photo gallery can decode and put on screen.
     *
     * **This property exists because the MIME prefix is the wrong test and was used as one.**
     * `image/svg+xml` and `image/vnd.dxf` both begin with `image/`, so a host that decides "is this a
     * picture?" by looking at the prefix files an SVG or a DXF into the platform's picture collection
     * — the camera roll — where no gallery on any Android build can render it. That produces the worst
     * shape an export bug can take: the bytes are correct, the file is written, it is indexed, and
     * then the user taps it and is told it cannot be opened, with their photo roll polluted by
     * thumbnails that will never load. Whether a gallery can show a file is a fact about the
     * container, so it is declared here, per format, and never inferred from a string.
     *
     * [TIFF] is deliberately false even though it holds pixels. Android's own decoders
     * (`BitmapFactory`, `ImageDecoder`) have never supported TIFF, so a TIFF in the picture
     * collection fails in exactly the same way an SVG does — this module writes baseline TIFF for
     * print and scanning work on a computer, and it belongs where a file manager can reach it.
     * [BMP] is true because the platform decoder does read BMP, so a gallery genuinely displays it.
     */
    val belongsInPhotoGallery: Boolean,
    /**
     * The format's name at the point of choice, where "SVG" alone means nothing to most people.
     *
     * Wording both clients show lives in shared code (`docs/DESIGN.md` §9) so the two apps cannot
     * describe one format differently; format wording lives beside the format it describes.
     */
    val choiceLabel: String,
    /**
     * One line stating what happens when the user tries to *open* the saved file.
     *
     * This is the other half of the same defect: a user who picks a format nothing warned them about
     * has been set up to fail hours later, in another app, with nothing connecting the failure back
     * to the export. Every format says its consequence before it is chosen, not after.
     */
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

    /** True for the pixel containers this module encodes or the platform encodes. */
    val isRaster: Boolean
        get() = this == PNG || this == JPEG || this == WEBP || this == TIFF || this == BMP

    /**
     * True when the bytes must be produced by the host platform rather than by `:core-export`.
     * Callers are expected to branch on this *before* calling [Exporter.export]; the exception
     * thrown there is a backstop, not the intended control flow.
     */
    val isPlatformEncoded: Boolean get() = this == JPEG || this == WEBP
}

/**
 * One export request. Sizes are in pixels for raster formats and in document units for vector
 * formats; [dpi] is what converts document units to physical units in PDF, EPS and DXF.
 *
 * `width`/`height` of 0 mean "document size". Supplying only one of them keeps the aspect ratio,
 * which is what every share sheet in the app actually wants and what stops a "1024 wide" request
 * from silently squashing a portrait trace.
 */
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
    /** Mirrors [ExportFormat.isVector] so callers only need the options object. */
    val isVector: Boolean get() = format.isVector

    /** File extension with no leading dot. */
    val extension: String get() = format.extension

    /** IANA media type for the produced bytes. */
    val mimeType: String get() = format.mimeType

    /**
     * Mirrors [ExportFormat.belongsInPhotoGallery] so a host holding only the options object can
     * route the write without reaching for the MIME string — see that property for why the string is
     * the wrong test.
     */
    val belongsInPhotoGallery: Boolean get() = format.belongsInPhotoGallery

    /** Effective dots-per-inch, never zero or negative — 72 is the PostScript/PDF identity. */
    val effectiveDpi: Int get() = if (dpi > 0) dpi else 72
}

/**
 * Shared geometry and formatting used by all four vector writers.
 *
 * Kept in one place because the y-flip, the points-per-document-unit conversion and the number
 * formatting have to agree across PDF, EPS, DXF and SVG or the same trace exports at four
 * different sizes.
 */
internal object ExportGeom {

    /** PDF and PostScript both define the user-space unit as 1/72 inch. */
    const val POINTS_PER_INCH = 72f

    /** DXF is emitted in millimetres because that is what CAM and laser software expects. */
    const val MM_PER_INCH = 25.4f

    private val POW10 = longArrayOf(
        1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L, 100_000_000L,
    )

    /**
     * Fixed-precision decimal with trailing zeros trimmed, independent of the default locale.
     *
     * Written by hand rather than delegating to `String.format`: the locale-sensitive formatters
     * emit a comma decimal separator in half of Europe, and a single `1,5` in a PDF content stream
     * or an SVG `d` attribute makes the whole file unparseable. That failure only reproduces on
     * the affected device, which makes it exactly the kind of bug that ships.
     */
    fun num(value: Float, precision: Int): String {
        val p = if (precision < 0) 0 else if (precision > 8) 8 else precision
        val d = value.toDouble()
        val finite = if (d.isNaN() || d.isInfinite()) 0.0 else d
        // Clamped before scaling so `roundToLong` can never saturate: a saturated Long negates to
        // itself and would print a coordinate with the wrong sign.
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

    /**
     * Horizontal and vertical scale factors from document units to output units, as `[sx, sy]`.
     * Honours an explicit width and/or height first and [ExportOptions.scale] otherwise; giving
     * only one dimension mirrors it onto the other so the aspect ratio survives.
     */
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

    /** Raster output size in whole pixels as `[w, h]`, never smaller than 1x1. */
    fun outputSize(o: ExportOptions, docW: Float, docH: Float): IntArray {
        val s = scaleXY(o, docW, docH)
        val w = if (o.width > 0) o.width else (docW * s[0]).roundToInt()
        val h = if (o.height > 0) o.height else (docH * s[1]).roundToInt()
        return intArrayOf(if (w < 1) 1 else w, if (h < 1) 1 else h)
    }

    /**
     * A copy of [doc] with every path scaled by `sx`/`sy` and stroke widths scaled by the
     * geometric mean, so a 2x export has 2x-thick strokes rather than hairlines.
     *
     * Deliberately rebuilds the document instead of calling `VecDocument.transform`: only the
     * shapes are supposed to move here, while the page box is rewritten explicitly.
     */
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

    /**
     * Union of every visible path's bounds in document coordinates, widened by half the stroke
     * width so a stroked outline is enclosed rather than bisected. Returns `null` when the
     * document carries no drawable geometry at all.
     */
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

    /** Red channel of a packed ARGB int as 0..1. */
    fun red(argb: Int): Float = ((argb ushr 16) and 0xFF) / 255f

    /** Green channel of a packed ARGB int as 0..1. */
    fun green(argb: Int): Float = ((argb ushr 8) and 0xFF) / 255f

    /** Blue channel of a packed ARGB int as 0..1. */
    fun blue(argb: Int): Float = (argb and 0xFF) / 255f

    /** Alpha channel of a packed ARGB int as 0..1. */
    fun alpha(argb: Int): Float = ((argb ushr 24) and 0xFF) / 255f
}
