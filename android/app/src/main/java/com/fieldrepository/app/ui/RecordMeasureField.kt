package com.fieldrepository.app.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.fieldrepository.app.data.KnownRectangle
import com.fieldrepository.app.data.MeasurePoint
import com.fieldrepository.app.data.MeasureResult
import com.fieldrepository.app.data.MeasureSegment
import com.fieldrepository.app.data.PhotoMeasure
import com.fieldrepository.app.data.RoundedValue
import com.fieldrepository.app.data.ScaleReference
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Measure a dimension from a photograph" ON A RECORD FORM — the on-device, offline, re-derivable
 * half of the measurement story.
 *
 * ── WHAT THIS IS FOR ──────────────────────────────────────────────────────────────────────────
 *
 * The product and tool record forms document a physical object's dimensions in inches, and the only
 * machine route they have ever offered is `GridMeasurementSection` in `MainActivity.kt`, which posts
 * the photograph to the measurement endpoint and has a vision model ESTIMATE the number — the
 * prompt's own verb. That route costs money per call, cannot be re-derived by anybody afterwards, and,
 * being network-only, fails every single time in the courtyard where the object is actually in the
 * researcher's hands.
 *
 * This panel runs `PhotoMeasure` — plane projective geometry, no network, no model — on a photograph
 * already in the form's own attach-media batch. THE INSIGHT THAT MAKES IT CHEAP: the grid sheet the
 * vision route already asks for is itself a perfect deterministic reference. A 1-inch grid means the
 * researcher can mark across five squares and say "that is five inches" — a scale reference, typed
 * once, with no model, no request and no per-call cost. The photograph the vision route wants is
 * ALREADY a reference photograph; it only ever needed measuring rather than inferring.
 *
 * The two routes sit one under the other on both forms, geometry first, because that is the one that
 * should be reached for first. Neither replaces the other: geometry needs a reference of known length
 * in the frame and the model does not, so they fail in different places.
 *
 * ── THERE IS NO GEOMETRY IN THIS FILE, AND THERE MUST NEVER BE ────────────────────────────────
 *
 * No scale factor, no pixels-per-inch, no unit conversion, no error bar. Every one of those already
 * exists exactly once — `PhotoMeasure.measureBySameScale`, `measureByRectification`, `convertLength`,
 * `roundToUncertainty` — and each is pinned value-for-value by `PhotoMeasureTest`. A panel that
 * re-derived any of them would be the beginning of a SECOND implementation of the same plane geometry,
 * differing from the web's silently and only in the fourth digit, in a number printed on a record
 * sheet nobody can re-measure.
 *
 * THE ONE PIECE OF ARITHMETIC BELOW IS NOT GEOMETRY — IT IS THE COLUMN. [RECORD_DECIMALS] and the
 * rounding at [recordProposalText] come from the storage layout and from nothing about the
 * photograph, which is why they are allowed here and the geometry is not.
 *
 * ── IT PROPOSES; IT DOES NOT WRITE ────────────────────────────────────────────────────────────
 *
 * [RecordMeasureField]'s `onPropose` fires ONLY from a per-dimension button that prints the figure it
 * is about to write and names the value it would replace. Nothing here writes into a form field by
 * itself, and no reading at all is shown until every mark has been moved into position: a reading
 * taken off the default layout would be a confident number about nothing.
 *
 * ── AND IT REPORTS HOW IT MEASURED, NOT ONLY WHAT ─────────────────────────────────────────────
 *
 * `onPropose` carries a third argument, the technique. This file computes no geometry and decides
 * nothing about it: the value is `MeasureResult.Measurement.method`, passed to the caller untouched,
 * so that a record saved from a proposal can say `PHOTO_GEOMETRY` and name the geometry a later reader
 * would have to repeat. What the caller does with it is `MeasurementMarkers`' business, including the
 * rule that drops the claim the moment somebody types over the number.
 *
 * ── WHAT DIFFERS FROM THE DESIGN-WORKSHOP SIBLING, AND WHY ────────────────────────────────────
 *
 * There, this file is a thin adapter: the panel itself is a stage-registry surface mounted on
 * registry-declared fields, and the adapter's whole job is translating a record COLUMN into the
 * `FieldDto` that panel already understands. This repository has no stage registry and no such panel,
 * so the panel is here, self-contained, and the translation step does not exist. The rules it carries
 * are the sibling's, one for one: config that survives a collapse, seeded-but-unplaced marks, the
 * refusal to read before every mark is placed, the same-plane warning, the replace warning, the
 * propose-per-destination list and the "rounded to what the error bar earned" closing line. What is
 * NOT carried over is the registry round trip — there is no second answer to "is this unit a length?"
 * to keep in step here, because [measurableDimensions] asks `PhotoMeasure.LENGTH_UNITS` directly,
 * which is the same one map.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * A record-form column, as the panel needs to see it
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One dimension COLUMN of a record form, and the unit that column is stored in.
 *
 * [unit] IS THE LOAD-BEARING FIELD and it is not decoration. A record form has no registry to declare
 * a unit, so the unit has to be asserted HERE, and asserting one that is not true of the column is a
 * silent, plausible, uncorrectable error — a centimetre figure written into a column everything
 * downstream reads as inches.
 *
 * So: a column whose unit is not actually known does not get a [RecordDimension]. See
 * [TOOL_MEASURE_DIMENSIONS] for the one that is deliberately excluded on exactly that ground.
 */
@Immutable
data class RecordDimension(
    /** The request-body key — `lengthInches`, `breadthInches`, `heightInches`. */
    val column: String,
    /** What the form's own box is labelled, so the proposal button names the box it will fill. */
    val label: String,
    /** A key of `PhotoMeasure.LENGTH_UNITS`. Record dimensions are inches throughout. */
    val unit: String = "in",
)

/**
 * The product form's three dimension columns, in the order the form renders them.
 *
 * `ProductCreateRequest` carries `lengthInches` / `breadthInches` / `heightInches`, and every one of
 * them says its unit in its own name.
 */
val PRODUCT_MEASURE_DIMENSIONS: List<RecordDimension> = listOf(
    RecordDimension("lengthInches", "Length (inches)"),
    RecordDimension("breadthInches", "Breadth (inches)"),
    RecordDimension("heightInches", "Height (inches)"),
)

/**
 * The tool form's dimension columns — the same three as the product's.
 *
 * ── WHY THE TOOL'S OTHER "Height" BOX IS NOT HERE ─────────────────────────────────────────────
 *
 * `ToolCreateRequest` carries TWO heights and only one of them can be a measurement destination.
 * `heightInches` below is the one this proposes into: it states its unit in its own name, exactly as
 * length and breadth do. The tool form's other box, bound to `ToolCreateRequest.height`, is a bare
 * decimal that declares no unit anywhere — not in the column name, not in the schema, not on the label
 * the researcher reads — and it keeps holding whatever was typed, in a unit nothing records.
 *
 * That is not an oversight to tidy up later. A geometric measurement's whole advantage over the vision
 * model is that the number can be trusted, and a trustworthy number in a field that does not say what
 * it measures is not better than a bad one — it is the same costing error with more confidence behind
 * it. So the unit-less column is never offered here, and `MEASUREMENT_DIMENSIONS` refuses a marker
 * naming it for the same reason one layer down.
 *
 * `RecordMeasureFieldTest` asserts that `height` is never a destination here.
 */
val TOOL_MEASURE_DIMENSIONS: List<RecordDimension> = listOf(
    RecordDimension("lengthInches", "Length (inches)"),
    RecordDimension("breadthInches", "Breadth (inches)"),
    RecordDimension("heightInches", "Height (inches)"),
)

/**
 * The dimensions a measurement may actually be proposed into — those whose declared unit
 * `PhotoMeasure` can convert to.
 *
 * ONE MAP, ONE PREDICATE. The eligibility question is asked of `PhotoMeasure.LENGTH_UNITS`, the very
 * map the conversion later goes through, so a unit this module cannot convert can never become a
 * destination it writes into. A future `RecordDimension("weightGrams", …, unit = "g")` is dropped here
 * rather than silently proposed into.
 */
internal fun measurableDimensions(dimensions: List<RecordDimension>): List<RecordDimension> =
    dimensions.filter { it.unit in PhotoMeasure.LENGTH_UNITS }

/**
 * How many decimal places a dimension column on a product or tool record can actually hold.
 *
 * READ OFF THE COLUMN AND NOTHING ELSE. The dimension columns are decimals with two places, and
 * Postgres rounds a third decimal away on assignment with nobody told — no 422, no warning, no trace.
 * The API does not catch it either: these fields are bounded but declare no decimal places, so 4.213
 * is accepted and 4.21 is stored.
 *
 * THE SAME CONSTANT, WITH THE SAME VALUE, LIVES IN THE WEB CLIENT'S OWN RECORD-MEASURE ADAPTER. If the
 * column ever widens, both move together.
 */
internal const val RECORD_DECIMALS: Int = 2

/**
 * The panel's figure fitted to [RECORD_DECIMALS], or null when it already fits.
 *
 * `BigDecimal` rather than a `Double` round, because what arrives here is decimal TEXT and the whole
 * question is which decimal digit survives: `1.005 * 100` in binary floating point is
 * 100.49999999999999, so `(value * 100).roundToInt() / 100.0` answers 1.00 where the text the
 * researcher was shown plainly reads 1.005. HALF_UP, the same direction as the web's `Math.round` on a
 * positive value.
 *
 * Null on anything that is not a number, which is the honest answer for a value nobody clamped.
 */
private fun clampedToColumn(offered: String): BigDecimal? =
    offered.toBigDecimalOrNull()
        ?.takeIf { it.scale() > RECORD_DECIMALS }
        ?.setScale(RECORD_DECIMALS, RoundingMode.HALF_UP)

/**
 * The accepted proposal, as the text a record form's box holds — ROUNDED TO WHAT THE COLUMN HOLDS.
 *
 * ── WHY THE ROUNDING IS HERE AND NOT LEFT TO POSTGRES ─────────────────────────────────────────
 *
 * `PhotoMeasure.roundToUncertainty` quotes a value to the decimal place its own error bar reaches, and
 * caps that at FOUR; a zoomed mark on a close-up routinely earns three. The box it is proposed into
 * holds two — see [RECORD_DECIMALS] — so an unclamped 4.213 is saved, accepted, and stored as 4.21,
 * with nothing on any screen saying a digit went.
 *
 * Rounding here rather than at the column means the number the researcher accepts is the number that
 * is stored, and [recordProposalNote] is what puts the difference on screen.
 *
 * A VALUE THAT ROUNDS TO ZERO IS REFUSED, NOT STORED — the empty string, which [RecordMeasureField]
 * treats as "propose nothing". A stored `0.00` in a dimension column does not read as "under five
 * thousandths of an inch"; it reads as a measurement of nothing, and it is printed that way on the
 * record sheet. The refusal is said out loud by [recordProposalNote] rather than left as a button that
 * did nothing.
 *
 * ── AND IT MUST NOT GO THROUGH `numToText` ────────────────────────────────────────────────────
 *
 * `MainActivity.numToText(Double?)` renders a whole number without its decimal point: 12.0 becomes
 * "12". Every other producer of a record dimension is a typed string or a database decimal, so that is
 * harmless for them and is why the helper reads the way it does. It is NOT harmless for this one. A
 * reading of 12.0 in ± 0.4 in is a claim about a tenth of an inch; "12" is a claim about an inch.
 * Dropping that digit throws away the only surviving trace of the measurement's quality, in the
 * direction of overstating it — which is why a figure ALREADY inside the column's two places is
 * returned exactly as the panel wrote it, trailing zero and all.
 */
internal fun recordProposalText(offered: String): String {
    val clamped = clampedToColumn(offered) ?: return offered
    return if (clamped.signum() == 0) "" else clamped.toPlainString()
}

/**
 * WHAT THE RESEARCHER IS TOLD WHEN THE COLUMN COULD NOT HOLD WHAT WAS MEASURED. Null when it could.
 *
 * A cap that is applied silently is the defect, not the cap: the panel's own closing line tells the
 * researcher that the number of digits is the only thing left saying how well it was measured, so a
 * digit removed on the way into the box has to be accounted for on the same screen. Said only when it
 * actually happened — a note under every proposal is noise that trains a reader past the one proposal
 * where it matters.
 *
 * BOTH NUMBERS ARE IN IT, and the box is named, because the note appears below the panel rather than on
 * the button: "4.213" is on the button the researcher just pressed and "4.21" is what is now in a box
 * further up the form, and a sentence that named neither would leave them to find the difference.
 */
internal fun recordProposalNote(dimension: RecordDimension, offered: String): String? {
    val clamped = clampedToColumn(offered) ?: return null
    if (clamped.signum() == 0) {
        return "That measures about $offered ${dimension.unit}, which rounds to zero in " +
            "“${dimension.label}” — that box holds $RECORD_DECIMALS decimal places. A stored 0 " +
            "would read as “measured, and it is nothing”, so nothing was put in it."
    }
    return "“${dimension.label}” holds $RECORD_DECIMALS decimal places, so the measured " +
        "$offered ${dimension.unit} went in as ${clamped.toPlainString()}."
}

/**
 * **"THERE IS SOMETHING IN THIS BOX ALREADY, AND THIS BUTTON REPLACES IT."**
 *
 * Null rather than an empty string when there is nothing to warn about: nothing there is not a quieter
 * warning, it is the absence of one, and a caller cannot then accidentally render an empty box. Blank
 * -checked rather than only null-checked, because a form box holds "" and can hold whitespace.
 *
 * Pure, so `RecordMeasureFieldTest` can pin the wording on the JVM with no composition to run it in.
 */
internal fun panelReplaceWarning(current: String?): String? {
    val trimmed = current?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return "“$trimmed” is in this field now. This replaces it."
}

/**
 * The chip label for one photograph in the panel's chooser.
 *
 * Position rather than filename, deliberately. The panel shows these only when there is more than one
 * photograph, on a phone, in sun, and what the researcher needs is "which of the ones I just took"; a
 * camera's `IMG_20260914_113455.jpg` truncated to fit a chip answers a different question. It also
 * means this file needs no copy of the `OpenableColumns.DISPLAY_NAME` query.
 *
 * (The design-workshop sibling names the grid captures here, because its capture state records a
 * PURPOSE per attachment. `MediaCaptureState` on this client does not — grid photographs are pushed
 * into the same undifferentiated `uris` list — so the label cannot say more than the position without
 * inventing it. Naming them would need that state to carry the purpose, which is another lane's file.)
 */
internal fun recordPhotoLabel(index: Int, total: Int): String =
    if (total > 1) "Photo ${index + 1} of $total" else "Photo ${index + 1}"

/**
 * A rounded value as the web's `toFixed(decimals)` renders it.
 *
 * NO SECOND ROUNDING HAPPENS HERE, which is what keeps `%.2f` (HALF_UP on the decimal rendering) from
 * disagreeing with JavaScript's `toFixed`: `PhotoMeasure.roundToUncertainty` has already rounded the
 * binary value to exactly this many places through its own `Math.round` port, so the formatter has
 * nothing left to decide. Reaching for `%.2f` on a RAW measurement would reintroduce the divergence
 * that `PhotoMeasure`'s header is about.
 *
 * `Locale.ROOT` AND NEVER THE HANDSET'S, and this is the locale trap this repository has already been
 * bitten by once. A researcher whose phone is set to a locale with a comma decimal separator would
 * otherwise have "12,4" written into a box the form parses with `toDoubleOrNull()`, which answers null
 * — the measurement would vanish at save with no message at all. The unit-test JVM is pinned to en_US
 * (`app/build.gradle.kts`), so an unpinned formatter here would pass its tests and fail in the field,
 * which is exactly what that pin exists to expose.
 */
internal fun formatRounded(rounded: RoundedValue): String =
    String.format(Locale.ROOT, "%.${rounded.decimals}f", rounded.value)

/**
 * The one-line description of a configured-but-collapsed card, or null when nothing has been set up.
 *
 * Nothing placed and nothing typed is not a configuration — the card should read as the invitation it
 * was before anybody opened it, rather than as a set-up nobody made.
 */
internal fun measureSummary(
    marksPlaced: Int,
    marksNeeded: Int,
    fourCorner: Boolean,
    referenceLength: String,
    referenceUnit: String,
    rectWidth: String,
    rectHeight: String,
    rectUnit: String,
): String? {
    val size = if (fourCorner) {
        val width = rectWidth.trim()
        val height = rectHeight.trim()
        if (width.isEmpty() || height.isEmpty()) null else "$width × $height $rectUnit rectangle"
    } else {
        val length = referenceLength.trim()
        if (length.isEmpty()) null else "$length $referenceUnit reference"
    }
    if (marksPlaced <= 0 && size == null) return null

    val parts = mutableListOf<String>()
    parts += if (marksPlaced <= 0) "no marks placed yet" else "$marksPlaced of $marksNeeded marks placed"
    parts += size ?: if (fourCorner) "no rectangle size yet" else "no reference length yet"
    parts += if (fourCorner) "four-corner method" else "same-plane method"
    return parts.joinToString(" · ")
}

/**
 * The units a [RecordDimension] may name, exposed so the tie can be asserted rather than believed.
 *
 * Not read by the code above — [measurableDimensions] asks `PhotoMeasure.LENGTH_UNITS` itself — and
 * read by `RecordMeasureFieldTest`, which holds every unit in [PRODUCT_MEASURE_DIMENSIONS] and
 * [TOOL_MEASURE_DIMENSIONS] to it. If that assertion ever needs this set widened, the change belongs in
 * `PhotoMeasure` and its web authority, never here.
 */
internal val RECORD_UNITS: Set<String> get() = PhotoMeasure.LENGTH_UNITS.keys

/* ────────────────────────────────────────────────────────────────────────────
 * The marks, and everything a person places or types
 * ──────────────────────────────────────────────────────────────────────────── */

private enum class MeasureMode { SCALE, RECTIFY }

private enum class MarkId { REF_A, REF_B, C0, C1, C2, C3, TGT_A, TGT_B }

/**
 * One mark: where it is, at what zoom it was last positioned, and whether it has been placed at all.
 *
 * STORED AS A FRACTION OF THE PHOTOGRAPH (0..1), NOT IN PIXELS, and that is a deliberate difference
 * from the sibling implementation. There, a decoded working copy of known size is owned by the panel
 * and marks are kept in its pixels. Here the photograph is decoded by the image loader, whose decoded
 * size depends on what it was asked for and can legitimately differ between one expansion of this card
 * and the next. A mark in pixels would then silently refer to a different place on the same
 * photograph. A fraction refers to the same place whatever the decode, and pixels are recovered at
 * measure time by multiplying by the decoded size — which is the space `PhotoMeasure` wants, and is
 * safe because both of its entry points are ratios and are therefore invariant to that scale.
 *
 * [zoomWhenPlaced] is kept rather than a sigma for the same reason: a sigma in image pixels would go
 * stale with the decode, while "the zoom this mark was last positioned at" stays true and is turned
 * into a sigma against the CURRENT decode by `PhotoMeasure.markSigmaForDisplayScale`.
 */
private data class Mark(
    val fx: Double,
    val fy: Double,
    val zoomWhenPlaced: Float,
    val placed: Boolean,
)

private val SCALE_MARKS = listOf(MarkId.REF_A, MarkId.REF_B, MarkId.TGT_A, MarkId.TGT_B)
private val RECTIFY_MARKS =
    listOf(MarkId.C0, MarkId.C1, MarkId.C2, MarkId.C3, MarkId.TGT_A, MarkId.TGT_B)

/**
 * The badge on each handle and the sentence a screen reader gets.
 *
 * EVERY HANDLE CARRIES ITS OWN NAME, so which mark is which never depends on where it happens to be or
 * on the colour it is drawn in. Reference and corner handles are filled and the object's two ends are
 * outlined, but that distinction is decoration on top of the label rather than the thing carrying it —
 * the panel has to work in bright sun, in greyscale, and read aloud.
 */
private val MARK_BADGE: Map<MarkId, String> = mapOf(
    MarkId.REF_A to "R1",
    MarkId.REF_B to "R2",
    MarkId.C0 to "1",
    MarkId.C1 to "2",
    MarkId.C2 to "3",
    MarkId.C3 to "4",
    MarkId.TGT_A to "A",
    MarkId.TGT_B to "B",
)

private val MARK_NAME: Map<MarkId, String> = mapOf(
    MarkId.REF_A to "Reference, first end",
    MarkId.REF_B to "Reference, second end",
    MarkId.C0 to "Rectangle corner 1",
    MarkId.C1 to "Rectangle corner 2, along the width edge from corner 1",
    MarkId.C2 to "Rectangle corner 3, diagonally opposite corner 1",
    MarkId.C3 to "Rectangle corner 4",
    MarkId.TGT_A to "The dimension, first end",
    MarkId.TGT_B to "The dimension, second end",
)

/**
 * Where each mark starts, as a fraction of the photograph.
 *
 * SEEDED RATHER THAN EMPTY, because an empty photograph with an instruction to tap six times is a
 * state a researcher can get wrong — a stray tap makes a mark nobody wanted — and cannot see the shape
 * of. Seeded marks show what is being asked for immediately. They are also flagged unplaced, and NO
 * MEASUREMENT IS SHOWN until every mark has been moved or tapped into position: a reading taken off
 * the default layout would be a confident number about nothing at all.
 */
private val SEEDS: Map<MarkId, Pair<Double, Double>> = mapOf(
    MarkId.REF_A to (0.14 to 0.84),
    MarkId.REF_B to (0.52 to 0.84),
    MarkId.C0 to (0.20 to 0.20),
    MarkId.C1 to (0.80 to 0.22),
    MarkId.C2 to (0.82 to 0.78),
    MarkId.C3 to (0.18 to 0.76),
    MarkId.TGT_A to (0.30 to 0.42),
    MarkId.TGT_B to (0.72 to 0.44),
)

private data class ScalePreset(val label: String, val length: Double, val unit: String)
private data class RectPreset(val label: String, val width: Double, val height: Double, val unit: String)

/**
 * Things a researcher in this programme actually has to hand, with the sizes they actually are.
 *
 * A preset removes the one step most likely to be got wrong — typing the reference length on a phone
 * keyboard in a courtyard. The grid sheet is first because it is the one this app already asks them to
 * carry: the vision route's own instructions say to place the object on a 1-inch grid, so five squares
 * across is a five-inch reference already in the frame. NOTHING IS PRESELECTED: a reference the
 * researcher did not choose is a reference nobody checked was in the photograph.
 */
private val SCALE_PRESETS = listOf(
    ScalePreset("Grid, 5 squares = 5 in", 5.0, "in"),
    ScalePreset("Scale card, 100 mm", 100.0, "mm"),
    ScalePreset("Steel rule, 300 mm", 300.0, "mm"),
    ScalePreset("A4 short edge, 210 mm", 210.0, "mm"),
    ScalePreset("₹5 coin, 23 mm", 23.0, "mm"),
)

private val RECT_PRESETS = listOf(
    RectPreset("A4 sheet", 210.0, 297.0, "mm"),
    RectPreset("A5 sheet", 148.0, 210.0, "mm"),
    RectPreset("Bank/ID card", 85.6, 54.0, "mm"),
)

private const val MIN_ZOOM = 1f

/**
 * Past this the screen is showing interpolation rather than photograph.
 *
 * TIED TO [WORKING_COPY_MAX_PX], and lower than the sibling's 12. The working copy here is capped at
 * about 2048 px on its long edge and is drawn into a viewport around 1000 px wide, so past roughly 8x
 * there is no more photograph to magnify — and the error bar, which narrows as the zoom rises (see
 * `markSigmaForDisplayScale`), would go on narrowing on marks the image cannot actually support. A
 * ceiling that lets the bar claim precision the pixels do not have is worse than one that stops early.
 */
private const val MAX_ZOOM = 8f

/**
 * How large a working copy the image loader is asked for, on the long edge.
 *
 * The default would size the decode to the viewport — about 1000 px — and a mark could then never be
 * placed more precisely than one screen pixel however far in the researcher zoomed. Asking for more
 * buys real detail to zoom into; asking for the ORIGINAL would put a 12-megapixel frame (48 MB at four
 * bytes a pixel) in memory on a phone whose other job right now is the camera. 2048 is about 12 MB and
 * is twice the viewport, which is what [MAX_ZOOM] is set against.
 */
private const val WORKING_COPY_MAX_PX = 2048

/**
 * Never taller than it is wide, and never flatter than 2:1.
 *
 * A portrait frame drawn at its true shape is about 450dp of card on the handset this feature exists
 * for; a panorama drawn at its true shape leaves a strip too shallow to aim a mark in, and aiming is
 * the whole job. Between the two bounds the viewport is EXACTLY the shape of the working copy, so
 * there is no letterbox on either edge; outside them the letterbox comes back, which is the smaller of
 * the two costs.
 */
private const val MIN_VIEWPORT_RATIO = 1f
private const val MAX_VIEWPORT_RATIO = 2f

/** The disc is small enough to see past; the transparent box around it is large enough to grab. */
private val MARK_TOUCH = 44.dp
private val MARK_DISC = 26.dp

/** The mark the cursor sits on when a method is chosen — the first one that method asks for. */
private fun firstMark(mode: MeasureMode): MarkId =
    if (mode == MeasureMode.SCALE) MarkId.REF_A else MarkId.C0

/**
 * Everything a researcher TYPED OR PLACED, held by the card rather than by its open half.
 *
 * ── WHY THIS CLASS EXISTS ─────────────────────────────────────────────────────────────────────
 *
 * The open half is REMOVED FROM THE COMPOSITION the instant the card collapses. Were these fields
 * `remember`s inside it, collapsing would destroy the four corner marks, the reference length, the
 * chosen photograph and the chosen method, and re-expanding would present an untouched card. That
 * makes the one exit expensive enough that nobody uses it, so the only way past a configured card is
 * to scroll the whole of it. A collapse is only cheap if nothing is lost.
 *
 * MARKS AND TEXT ARE CHEAP, SO THEY ARE KEPT. Six marks are six pairs of doubles and a flag; five
 * strings are five strings. All of it is work a person did with their hands that this app has no way
 * to redo for them.
 *
 * PIXELS ARE EXPENSIVE, SO THEY ARE NOT. The decoded working copy is NOT a field of this class and
 * must not become one — it is the largest single allocation the feature makes, and re-decoding costs a
 * few hundred milliseconds off the main thread. `zoom` and `pan` stay in the open half with it: they
 * describe a view of a bitmap that no longer exists.
 *
 * `remember` AND NOT `rememberSaveable`: a collapse does not leave the composition, so `remember` is
 * exactly the lifetime this needs.
 */
private class MeasureConfig {
    var mode by mutableStateOf(MeasureMode.SCALE)
    var photoIndex by mutableStateOf(0)
    var referenceLength by mutableStateOf("")
    var referenceUnit by mutableStateOf("in")
    var rectWidth by mutableStateOf("")
    var rectHeight by mutableStateOf("")
    var rectUnit by mutableStateOf("mm")
    var selected by mutableStateOf(firstMark(MeasureMode.SCALE))

    /** The rounding note from the LAST proposal, which describes that press and no other. */
    var columnNote by mutableStateOf<String?>(null)

    /**
     * A snapshot state MAP, not a `mutableStateOf(Map)`: the drag handlers read and write it from
     * inside long-lived `pointerInput` lambdas, and a snapshot map is read at the moment of access
     * rather than at the moment the lambda was composed. That is the difference between a drag that
     * follows the finger and one that keeps snapping back to where the mark was when the gesture
     * detector was installed.
     */
    val marks = mutableStateMapOf<MarkId, Mark>()

    val needed: List<MarkId> get() = if (mode == MeasureMode.SCALE) SCALE_MARKS else RECTIFY_MARKS

    val placedCount: Int get() = needed.count { marks[it]?.placed == true }

    val allPlaced: Boolean get() = needed.all { marks[it]?.placed == true }

    /** Put every mark back where it starts, unplaced. */
    fun seed() {
        SEEDS.forEach { (id, seed) ->
            marks[id] = Mark(fx = seed.first, fy = seed.second, zoomWhenPlaced = 1f, placed = false)
        }
    }

    fun place(id: MarkId, fx: Double, fy: Double, zoom: Float) {
        marks[id] = Mark(fx = fx.coerceIn(0.0, 1.0), fy = fy.coerceIn(0.0, 1.0), zoomWhenPlaced = zoom, placed = true)
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The mount
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The deterministic measurement panel, on a record form.
 *
 * [photos] is the form's OWN attach-media batch (`MediaCaptureState.uris`) — every photograph the
 * researcher has attached to this record, grid shots included, because `GridMeasurementSection` routes
 * its graph-paper captures into that same list. Nothing extra is captured for this panel and nothing
 * extra is uploaded: it READS the batch, and a photograph taken thirty seconds ago with no signal is
 * exactly as measurable as one that has finished uploading.
 *
 * IT DRAWS NOTHING WHEN THERE IS NO IMAGE IN THE BATCH, so a record with no photographs looks exactly
 * as it did before this existed. (A batch entry that is audio, video or a PDF simply fails to decode
 * and says so on its own chip — the same outcome as a photograph whose provider has revoked the grant,
 * and not worth an error the researcher cannot act on.)
 *
 * @param current what the form's dimension boxes hold right now, keyed by column — used for one
 *   sentence, the "this replaces it" warning under each proposal button.
 * @param onPropose fired ONLY from a proposal button, with the column, the text to put in it, and
 *   which geometry produced it — `"SCALE"` or `"RECTIFIED"`, straight off
 *   `MeasureResult.Measurement.method` with no mapping in between. The caller sends it as the
 *   `technique` of a `PHOTO_GEOMETRY` marker; see `data/MeasurementMarkers.geometryMarker`, which
 *   drops any value it does not recognise rather than letting the server refuse the save.
 */
@Composable
internal fun RecordMeasureField(
    dimensions: List<RecordDimension>,
    current: Map<String, String>,
    photos: List<Uri>,
    enabled: Boolean = true,
    onPropose: (column: String, text: String, technique: String?) -> Unit,
) {
    val targets = remember(dimensions) { measurableDimensions(dimensions) }
    if (targets.isEmpty()) return
    if (photos.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    // Seeded inside `remember` rather than during composition: writing snapshot state while composing
    // is how a composable comes to recompose itself forever.
    val config = remember { MeasureConfig().also { it.seed() } }
    val index = config.photoIndex.coerceIn(0, photos.size - 1)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Measure a dimension from a photograph",
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Marks you place, measured against something of a known size in the same photo. " +
                        "No network, no model, and anybody can check it later from the marks.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Close" else "Open") }
        }
        if (expanded) {
            MeasurePanelOpen(
                config = config,
                photo = photos[index],
                photoCount = photos.size,
                photoIndex = index,
                targets = targets,
                current = current,
                enabled = enabled,
                onPropose = onPropose,
                onClose = { expanded = false },
            )
        } else {
            measureSummary(
                marksPlaced = config.placedCount,
                marksNeeded = config.needed.size,
                fourCorner = config.mode == MeasureMode.RECTIFY,
                referenceLength = config.referenceLength,
                referenceUnit = config.referenceUnit,
                rectWidth = config.rectWidth,
                rectHeight = config.rectHeight,
                rectUnit = config.rectUnit,
            )?.let { Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp) }
        }
    }
}

/**
 * The open half: the decoded photograph, the marks on it, what the reference is, and the reading.
 *
 * ── WHY THE VIEWPORT'S SIZE IS STATE AND NOT `BoxWithConstraints` ─────────────────────────────
 *
 * The measurement needs the scale the photograph is drawn at, which depends on the size of the box it
 * is drawn into. `BoxWithConstraints` would hand that over — but it is a `SubcomposeLayout`, so its
 * content composes during the LAYOUT pass, after this function has already run. A measurement computed
 * in there could not be read back out by anything below it, and assigning it to a captured variable
 * would read null every time. `onSizeChanged` puts the size in ordinary state instead: one extra frame
 * on first layout, and everything after that is plain composition in one pass, in reading order.
 */
@Composable
private fun MeasurePanelOpen(
    config: MeasureConfig,
    photo: Uri,
    photoCount: Int,
    photoIndex: Int,
    targets: List<RecordDimension>,
    current: Map<String, String>,
    enabled: Boolean,
    onPropose: (column: String, text: String, technique: String?) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    /*
     * Sized deliberately rather than left to the loader — see [WORKING_COPY_MAX_PX] for the two ways
     * the default is wrong (a decode no bigger than the viewport cannot be zoomed into; the original is
     * 48 MB on a phone that is also running the camera).
     *
     * ⚠ AND THE EXPLICIT SIZE IS NOT A TUNING KNOB — REMOVING IT HANGS THIS PANEL. Without one, the
     * loader resolves the request's size FROM THE COMPOSABLE THAT DRAWS THE PAINTER. Nothing here draws
     * it until the state is `Success`, because the viewport shows a sentence while it is loading and
     * the mark handles cannot be positioned before the decoded size is known. So the size would wait
     * on a draw that waits on the size, and the card would sit on "Opening the photograph…" for ever,
     * on every handset, with no error anywhere.
     */
    val painter = rememberAsyncImagePainter(
        model = remember(photo) {
            ImageRequest.Builder(context)
                .data(photo)
                .size(WORKING_COPY_MAX_PX, WORKING_COPY_MAX_PX)
                .build()
        },
    )
    val state = painter.state
    val intrinsic = painter.intrinsicSize
    val imgW = if (intrinsic.isSpecified) intrinsic.width else 0f
    val imgH = if (intrinsic.isSpecified) intrinsic.height else 0f

    // Keyed on the photograph: a view of a picture that is no longer on screen is not a view of
    // anything, and carrying a 6x zoom across a swap would open the next photograph somewhere in its
    // top-left corner with no way to tell why.
    var zoom by remember(photo) { mutableStateOf(1f) }
    var pan by remember(photo) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    val viewW = viewport.width.toFloat()
    val viewH = viewport.height.toFloat()
    // Screen pixels per image pixel at zoom 1 — `ContentScale.Fit`'s own factor, recomputed here
    // because every mark position and the whole error bar depend on it.
    val fit = if (imgW > 0f && imgH > 0f && viewW > 0f && viewH > 0f) {
        min(viewW / imgW, viewH / imgH)
    } else {
        0f
    }
    val drawnW = imgW * fit
    val drawnH = imgH * fit
    val ratio = if (imgW > 0f && imgH > 0f) {
        (imgW / imgH).coerceIn(MIN_VIEWPORT_RATIO, MAX_VIEWPORT_RATIO)
    } else {
        4f / 3f
    }

    fun screenOf(mark: Mark): Offset = Offset(
        x = viewW / 2f + ((mark.fx.toFloat() * imgW - imgW / 2f) * fit) * zoom + pan.x,
        y = viewH / 2f + ((mark.fy.toFloat() * imgH - imgH / 2f) * fit) * zoom + pan.y,
    )

    fun fractionOf(point: Offset): Pair<Double, Double> {
        if (fit <= 0f) return 0.5 to 0.5
        val ix = ((point.x - viewW / 2f - pan.x) / zoom) / fit + imgW / 2f
        val iy = ((point.y - viewH / 2f - pan.y) / zoom) / fit + imgH / 2f
        return (ix / imgW).toDouble() to (iy / imgH).toDouble()
    }

    /** Keep the photograph from being panned off the edge of its own viewport. */
    fun clampPan(offered: Offset): Offset {
        val slackX = max(0f, (drawnW * zoom - viewW) / 2f)
        val slackY = max(0f, (drawnH * zoom - viewH) / 2f)
        return Offset(offered.x.coerceIn(-slackX, slackX), offered.y.coerceIn(-slackY, slackY))
    }

    val result = measureNow(config = config, imgW = imgW, imgH = imgH, fit = fit)
    val scrollPhotos = rememberScrollState()
    val scrollPresets = rememberScrollState()
    val scrollMarks = rememberScrollState()

    if (photoCount > 1) {
        PanelLabel("Which photograph")
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(scrollPhotos),
        ) {
            repeat(photoCount) { position ->
                FilterChip(
                    selected = position == photoIndex,
                    onClick = {
                        if (position != photoIndex) {
                            config.photoIndex = position
                            // MARKS BELONG TO A PHOTOGRAPH. Keeping them across a swap would leave six
                            // marks sitting at the same fractions of a completely different picture,
                            // still flagged "placed", and the reading taken off them would be a
                            // confident number about the wrong object.
                            config.seed()
                            config.selected = firstMark(config.mode)
                            config.columnNote = null
                        }
                    },
                    label = { Text(recordPhotoLabel(position, photoCount), fontSize = 11.sp) },
                )
            }
        }
    }

    PanelLabel("Method")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = config.mode == MeasureMode.SCALE,
            onClick = { config.mode = MeasureMode.SCALE; config.selected = firstMark(MeasureMode.SCALE) },
            label = { Text("Same plane, two marks", fontSize = 11.sp) },
        )
        FilterChip(
            selected = config.mode == MeasureMode.RECTIFY,
            onClick = { config.mode = MeasureMode.RECTIFY; config.selected = firstMark(MeasureMode.RECTIFY) },
            label = { Text("Four corners", fontSize = 11.sp) },
        )
    }
    // THE AMBER CARD THE GEOMETRY MODULE REQUIRES ITS CALLER TO RENDER. `measureBySameScale` is only
    // true when the reference and the object lie in one plane square to the sensor, and it cannot tell
    // whether they do — a scale bar flat on a table and a pot standing on it are not in the same plane,
    // and the pot's height read that way is wrong silently, plausibly, and by an amount nothing
    // downstream can detect. Said here EVERY TIME the method is on screen, not once in a help page,
    // because the researcher who needs it is the one who has not thought about it.
    PanelNote(
        warning = config.mode == MeasureMode.SCALE,
        text = if (config.mode == MeasureMode.SCALE) {
            "This is only true when the reference and the thing you are measuring lie in the SAME FLAT " +
                "PLANE, square to the camera. A ruler on the table and a pot standing on it are not in " +
                "the same plane, and the pot will read wrong with no sign of it. If in doubt, use the " +
                "four-corner method."
        } else {
            "Mark the four corners of something whose real size you know — an A4 sheet, a card, the " +
                "grid sheet — IN ORDER AROUND IT, not in reading order. The tilt of that surface is " +
                "then divided out, and both ends of the object must sit on it."
        },
    )

    /*
     * ── EVERY GESTURE IS READ ON THIS BOX, AND NOT ON THE PHOTOGRAPH INSIDE IT ────────────────
     *
     * The `Image` below carries a `graphicsLayer` with the zoom and the pan on it, and Compose
     * back-transforms pointer positions into the LOCAL, PRE-TRANSFORM space of whichever node reads
     * them. A tap detector on the Image would therefore be handed coordinates that already have the
     * zoom and pan divided out — and [fractionOf] would divide them out a second time, so a mark
     * placed at 4× would land nowhere near the finger. The matching half of the same mistake is a pan
     * drag read on the Image: its delta arrives in local units, so the photograph would slide at a
     * quarter of the speed of the finger at 4×.
     *
     * This Box has no layer of its own, so what it reads is plain viewport pixels — the same space
     * [screenOf] puts the mark handles in, and the space [fractionOf] is written to invert. ONE SPACE
     * FOR ALL THREE, which is what makes them checkable against each other by reading them.
     *
     * The handles still get first refusal: they are children, Compose dispatches to the innermost hit
     * node first, and a consumed change is ignored by the detectors here. So dragging a mark moves the
     * mark, and dragging anywhere else pans.
     */
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .heightIn(min = 96.dp)
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .clipToBounds()
            .onSizeChanged { viewport = it }
            // Tap to place the mark the cursor is on. TAP AND NOT DRAG-ONLY, because on a phone the
            // place a researcher wants a mark is usually under their own finger already — and because a
            // drag that starts on the photograph is a pan.
            .pointerInput(enabled, fit, viewport) {
                if (!enabled || fit <= 0f) return@pointerInput
                detectTapGestures { offset ->
                    val (fx, fy) = fractionOf(offset)
                    config.place(config.selected, fx, fy, zoom)
                    // Advance to the next mark this method still wants, so the whole set can be placed
                    // by tapping around the photograph without going back to the chips.
                    config.selected =
                        config.needed.firstOrNull { config.marks[it]?.placed != true } ?: config.selected
                }
            }
            .pointerInput(enabled, fit, viewport) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, drag ->
                    change.consume()
                    pan = clampPan(pan + drag)
                }
            },
    ) {
        if (state is AsyncImagePainter.State.Success) {
            Image(
                painter = painter,
                contentDescription = "The photograph being measured",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
            )
            config.needed.forEach { id ->
                val mark = config.marks[id] ?: return@forEach
                val at = screenOf(mark)
                MarkHandle(
                    badge = MARK_BADGE.getValue(id),
                    name = MARK_NAME.getValue(id),
                    placed = mark.placed,
                    selected = id == config.selected,
                    outlined = id == MarkId.TGT_A || id == MarkId.TGT_B,
                    onClick = { config.selected = id },
                    modifier = Modifier
                        .offset {
                            val half = MARK_TOUCH.toPx() / 2f
                            IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt())
                        }
                        .pointerInput(id, enabled, fit, zoom) {
                            if (!enabled || fit <= 0f || imgW <= 0f || imgH <= 0f) return@pointerInput
                            detectDragGestures(onDragStart = { config.selected = id }) { change, drag ->
                                change.consume()
                                val held = config.marks[id] ?: return@detectDragGestures
                                // The drag arrives in SCREEN pixels and the mark is a fraction of the
                                // photograph, so the conversion divides by the fit AND by the zoom.
                                // Dividing by only one of them is a mark that runs away from the finger
                                // the moment anybody zooms in.
                                config.place(
                                    id,
                                    held.fx + drag.x / (fit * zoom) / imgW,
                                    held.fy + drag.y / (fit * zoom) / imgH,
                                    zoom,
                                )
                            }
                        },
                )
            }
        } else {
            Text(
                when (state) {
                    is AsyncImagePainter.State.Error ->
                        "This file could not be opened as a photograph on this device, so it cannot be " +
                            "measured. Pick another one from the batch."
                    else -> "Opening the photograph…"
                },
                color = MaterialTheme.field.muted,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Center).padding(12.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        ) {
            ZoomButton(zoomIn = false, enabled = enabled && zoom > MIN_ZOOM) {
                zoom = max(MIN_ZOOM, zoom / 1.5f)
                pan = clampPan(pan)
            }
            ZoomButton(zoomIn = true, enabled = enabled && zoom < MAX_ZOOM) {
                zoom = min(MAX_ZOOM, zoom * 1.5f)
                pan = clampPan(pan)
            }
        }
    }

    // ZOOMING IN GENUINELY MAKES THE MEASUREMENT BETTER and the readout says so, so the current zoom
    // belongs on screen beside the marks it is about.
    Text(
        "Zoom ${String.format(Locale.ROOT, "%.1f", zoom)}×. A mark is only as well placed as the zoom it " +
            "was placed at, and the error bar below is worked out from exactly that.",
        color = MaterialTheme.field.muted,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )

    PanelLabel("Tap the photograph to place")
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(scrollMarks),
    ) {
        config.needed.forEach { id ->
            val placed = config.marks[id]?.placed == true
            FilterChip(
                selected = id == config.selected,
                onClick = { config.selected = id },
                label = { Text(MARK_BADGE.getValue(id) + if (placed) "" else " ·", fontSize = 11.sp) },
            )
        }
    }
    Text(MARK_NAME.getValue(config.selected), color = MaterialTheme.field.muted, fontSize = 11.sp)

    // The nudge pad moves the selected mark by ONE IMAGE PIXEL a press — the finest step the working
    // copy holds. It is what makes a mark placeable at all where the edge being aimed at is under the
    // researcher's own fingertip.
    //
    // CHEVRONS AND NOT `Icons.AutoMirrored.Filled.KeyboardArrowLeft`, which is what the deprecation
    // warning on the plain one asks for. Auto-mirroring is right for an arrow that means "back" and
    // wrong for one that means "one pixel to the left of the screen": under an RTL locale the
    // mirrored icon would point right while the mark still moved left, which is a control that lies
    // about what it does. The chevrons are not direction-sensitive and are not deprecated.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Nudge ${MARK_BADGE.getValue(config.selected)}",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f),
        )
        NudgeButton(Icons.Filled.ChevronLeft, "left", enabled) { nudge(config, -1.0, 0.0, imgW, imgH, zoom) }
        NudgeButton(Icons.Filled.ChevronRight, "right", enabled) { nudge(config, 1.0, 0.0, imgW, imgH, zoom) }
        NudgeButton(Icons.Filled.KeyboardArrowUp, "up", enabled) { nudge(config, 0.0, -1.0, imgW, imgH, zoom) }
        NudgeButton(Icons.Filled.KeyboardArrowDown, "down", enabled) { nudge(config, 0.0, 1.0, imgW, imgH, zoom) }
    }

    if (config.mode == MeasureMode.SCALE) {
        PanelLabel("How long is the reference, really?")
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(scrollPresets),
        ) {
            SCALE_PRESETS.forEach { preset ->
                OutlinedButton(
                    onClick = { config.referenceLength = trimNumber(preset.length); config.referenceUnit = preset.unit },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) { Text(preset.label, fontSize = 10.sp, maxLines = 1) }
            }
        }
        OutlinedTextField(
            value = config.referenceLength,
            onValueChange = { config.referenceLength = it },
            label = { Text("Reference length") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        UnitChips(selected = config.referenceUnit, enabled = enabled) { config.referenceUnit = it }
    } else {
        PanelLabel("How big is that rectangle, really?")
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(scrollPresets),
        ) {
            RECT_PRESETS.forEach { preset ->
                OutlinedButton(
                    onClick = {
                        config.rectWidth = trimNumber(preset.width)
                        config.rectHeight = trimNumber(preset.height)
                        config.rectUnit = preset.unit
                    },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) { Text(preset.label, fontSize = 10.sp, maxLines = 1) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = config.rectWidth,
                onValueChange = { config.rectWidth = it },
                label = { Text("Width, 1→2") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = config.rectHeight,
                onValueChange = { config.rectHeight = it },
                label = { Text("Height, 2→3") },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        UnitChips(selected = config.rectUnit, enabled = enabled) { config.rectUnit = it }
    }

    MeasurementReadout(
        result = result,
        config = config,
        targets = targets,
        current = current,
        enabled = enabled,
        onPropose = { dimension, text, offered, technique ->
            // Cleared on every press, not only set: a note left standing from the previous proposal
            // would describe a rounding that did not happen to this one.
            config.columnNote = recordProposalNote(dimension, offered)
            // A blank means the figure rounded to zero in the column, which `columnNote` has just said
            // in as many words. Writing "" over a number the researcher typed would be a silent
            // deletion on top of a failure they can already see.
            //
            // AND NO MARKER IS EMITTED FOR IT EITHER, because this returns before the caller can record
            // one — which is the honest outcome: nothing was written, so there is nothing whose method
            // could be described.
            if (text.isNotBlank()) onPropose(dimension.column, text, technique)
        },
    )
    config.columnNote?.let { note ->
        Text(note, color = MaterialTheme.field.onWarningContainer, fontSize = 11.sp, lineHeight = 16.sp)
    }
    // A second door at the foot, where the work actually ends. The one in the header is a photograph, a
    // nudge pad, two text fields and a readout away by the time anybody wants it, and a collapse that
    // costs a scroll is a collapse nobody uses.
    TextButton(onClick = onClose) { Text("Close") }
}

/**
 * The measurement, or null when there must not be one yet.
 *
 * Null means "nothing to show": a mark still unplaced, a reference nobody has typed, or a photograph
 * that has not finished decoding. A REFUSAL IS NOT NULL — it is a [MeasureResult.Refusal] carrying a
 * sentence for the researcher, and it is shown.
 *
 * THE PER-MARK SIGMA IS THE WORST OF THE MARKS IN USE, because a measurement is only as well aimed as
 * its sloppiest end. Each mark's own sigma comes from the zoom it was last positioned at, which is why
 * zooming in genuinely narrows the error bar and why a mark placed at 1× goes on widening it until it
 * is re-placed.
 */
private fun measureNow(config: MeasureConfig, imgW: Float, imgH: Float, fit: Float): MeasureResult? {
    if (imgW <= 0f || imgH <= 0f || fit <= 0f) return null
    if (!config.allPlaced) return null

    fun pointOf(id: MarkId): MeasurePoint {
        val mark = config.marks.getValue(id)
        return MeasurePoint(mark.fx * imgW, mark.fy * imgH)
    }

    val sigma = config.needed.maxOf { id ->
        PhotoMeasure.markSigmaForDisplayScale((fit * config.marks.getValue(id).zoomWhenPlaced).toDouble())
    }
    val target = MeasureSegment(pointOf(MarkId.TGT_A), pointOf(MarkId.TGT_B))

    return if (config.mode == MeasureMode.SCALE) {
        val length = config.referenceLength.trim().toDoubleOrNull() ?: return null
        PhotoMeasure.measureBySameScale(
            reference = ScaleReference(
                from = pointOf(MarkId.REF_A),
                to = pointOf(MarkId.REF_B),
                length = length,
                unit = config.referenceUnit,
            ),
            target = target,
            markSigmaPx = sigma,
        )
    } else {
        val width = config.rectWidth.trim().toDoubleOrNull() ?: return null
        val height = config.rectHeight.trim().toDoubleOrNull() ?: return null
        PhotoMeasure.measureByRectification(
            corners = listOf(MarkId.C0, MarkId.C1, MarkId.C2, MarkId.C3).map { pointOf(it) },
            rectangle = KnownRectangle(width = width, height = height, unit = config.rectUnit),
            target = target,
            markSigmaPx = sigma,
        )
    }
}

/** Move the selected mark by one image pixel. Placing a mark by nudging counts as placing it. */
private fun nudge(config: MeasureConfig, dx: Double, dy: Double, imgW: Float, imgH: Float, zoom: Float) {
    if (imgW <= 0f || imgH <= 0f) return
    val id = config.selected
    val mark = config.marks[id] ?: return
    config.place(id, mark.fx + dx / imgW, mark.fy + dy / imgH, zoom)
}

/**
 * The reading, or the reason there is not one.
 *
 * @param onPropose (dimension, the text to write, the figure before the column clamped it, the
 *   geometry that produced it).
 */
@Composable
private fun MeasurementReadout(
    result: MeasureResult?,
    config: MeasureConfig,
    targets: List<RecordDimension>,
    current: Map<String, String>,
    enabled: Boolean,
    onPropose: (RecordDimension, String, String, String?) -> Unit,
) {
    if (!config.allPlaced) {
        val remaining = config.needed.filter { config.marks[it]?.placed != true }
        Text(
            "${remaining.size} of ${config.needed.size} marks still to place " +
                "(${remaining.joinToString(", ") { MARK_BADGE.getValue(it) }}). Nothing is measured " +
                "until every mark is where it belongs — a reading off the marks as they were laid out " +
                "would be a confident number about nothing.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        return
    }

    if (result == null) {
        Text(
            if (config.mode == MeasureMode.SCALE) {
                "Type how long the reference really is, and the measurement appears here."
            } else {
                "Type how big the rectangle really is, and the measurement appears here."
            },
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
        )
        return
    }

    if (result is MeasureResult.Refusal) {
        // A refusal, with its reason. It is NOT styled as an error, because nothing has gone wrong with
        // the researcher's work — the marks simply do not support a number, and the sentence says
        // which. See `PhotoMeasure`'s header for why a refusal beats a number with no error bar.
        PanelNote(warning = true, text = result.reason)
        return
    }

    val measurement = result as MeasureResult.Measurement
    val shown = PhotoMeasure.roundToUncertainty(measurement.value, measurement.uncertainty)
    val doubt = PhotoMeasure.roundToUncertainty(measurement.uncertainty, measurement.uncertainty)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Measured — not saved yet",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "${formatRounded(shown)} ± ${formatRounded(doubt)} ${measurement.unit}",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "That is ±${String.format(Locale.ROOT, "%.1f", measurement.relativeUncertainty * 100)}%, from a " +
                "reference ${measurement.referencePixels.roundToInt()} pixels long and an object " +
                "${measurement.targetPixels.roundToInt()} pixels long in this working copy. The error bar is " +
                "how far the answer moves when each mark is nudged by the amount a mark can be placed to at " +
                "the zoom it was placed at — it is not a guess about the camera, and it does not include the " +
                "reference being the wrong length.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        measurement.tiltCorrection?.let { tilt ->
            Text(
                buildString {
                    append("Correcting for the tilt of that surface changed this by ")
                    append(String.format(Locale.ROOT, "%.1f", tilt * 100))
                    append("%")
                    measurement.uncorrectedValue?.let { uncorrected ->
                        append(" — two marks alone would have read ")
                        append(String.format(Locale.ROOT, "%.1f", uncorrected))
                        append(" ")
                        append(measurement.unit)
                    }
                    append(". ")
                    append(
                        if (tilt < 0.01) {
                            "That is small enough that the two-mark method would have done here."
                        } else {
                            "That is why the four corners were worth marking."
                        }
                    )
                },
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        PanelLabel("Propose this into")
        targets.forEach { dimension ->
            val converted = PhotoMeasure.convertLength(measurement.value, measurement.unit, dimension.unit)
            val convertedDoubt =
                PhotoMeasure.convertLength(measurement.uncertainty, measurement.unit, dimension.unit)
            if (converted == null || convertedDoubt == null) {
                // A unit this module cannot convert must not become a destination. Said out loud rather
                // than silently omitted, so nobody wonders where the button went.
                Text(
                    "${dimension.label} is measured in ${dimension.unit}, which this cannot convert to.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                )
                return@forEach
            }
            val offered = formatRounded(PhotoMeasure.roundToUncertainty(converted, convertedDoubt))
            val text = recordProposalText(offered)
            Button(
                onClick = { onPropose(dimension, text, offered, measurement.method) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("${dimension.label}: $offered ${dimension.unit}", fontSize = 13.sp)
            }
            // The warning stays a line under the button it belongs to, in the warning colour, rather
            // than a bordered box: this card has one button PER dimension, and three bordered boxes
            // interleaved with three buttons would break the list of destinations into six things a
            // researcher has to re-read as a list.
            panelReplaceWarning(current[dimension.column])?.let {
                Text(it, color = MaterialTheme.field.onWarningContainer, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }

        Text(
            "The figure is rounded to the precision its own error bar reaches, because once it is in " +
                "the field the error bar is gone — the number of digits is the only thing left saying " +
                "how well it was measured.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * Pieces
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One mark handle.
 *
 * THE TOUCH TARGET IS 44dp AND THE DISC IS 26dp, which is not a contradiction. A 44dp disc drawn on a
 * photograph would hide the very corner it is marking, and six of them would overlap. So the disc is
 * small enough to see past and the transparent box around it is large enough to grab.
 */
@Composable
private fun MarkHandle(
    badge: String,
    name: String,
    placed: Boolean,
    selected: Boolean,
    outlined: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(MARK_TOUCH)
            .semantics {
                contentDescription = if (placed) name else "$name, not placed yet"
            }
            .pointerInput(onClick) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(MARK_DISC)
                .background(
                    color = if (outlined) Color.White.copy(alpha = 0.85f) else accent,
                    shape = CircleShape,
                )
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else Color.White,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                badge,
                color = if (outlined) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(text, color = MaterialTheme.field.body, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun PanelNote(warning: Boolean, text: String) {
    Text(
        text,
        color = if (warning) MaterialTheme.field.onWarningContainer else MaterialTheme.field.muted,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (warning) MaterialTheme.field.warningContainer else MaterialTheme.field.surface50,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
    )
}

@Composable
private fun UnitChips(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        // THE UNITS COME FROM THE GEOMETRY MODULE, not from a list written here: a unit offered on this
        // row that `convertLength` does not know would be a chip that produces a measurement no
        // destination can accept.
        PhotoMeasure.LENGTH_UNITS.keys.forEach { unit ->
            FilterChip(
                selected = unit == selected,
                onClick = { onSelect(unit) },
                enabled = enabled,
                label = { Text(unit, fontSize = 11.sp) },
            )
        }
    }
}

@Composable
private fun ZoomButton(zoomIn: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.size(44.dp),
    ) {
        Icon(
            if (zoomIn) Icons.Filled.Add else Icons.Filled.Remove,
            contentDescription = if (zoomIn) "Zoom in" else "Zoom out",
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun NudgeButton(icon: ImageVector, direction: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.size(44.dp),
    ) {
        Icon(icon, contentDescription = "Nudge the selected mark $direction", modifier = Modifier.size(18.dp))
    }
}

/**
 * A preset's size as text, without a trailing ".0" on a whole number.
 *
 * Its own helper rather than `MainActivity.numToText`, which is private to that file — and which this
 * must not grow to depend on for the reason [recordProposalText] gives: the two round differently on
 * purpose, and one of them is allowed to drop a decimal point while the other is not.
 */
private fun trimNumber(value: Double): String =
    if (abs(value % 1.0) < 1e-9) value.toLong().toString() else value.toString()
