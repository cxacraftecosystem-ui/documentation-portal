package com.fieldrepository.app.ui.trace

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.ui.LocalAppPreferences
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see ui/FieldText.kt.
import com.fieldrepository.app.ui.Text
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * **"DID THE TRACE LOSE THE FAINT CONSTRUCTION LINE I DREW?" — the only question this answers.**
 *
 * ── WHY THIS EXISTS AT ALL, WHICH IS PROPERTY FOUR OF THE WHOLE FEATURE ───────────────────────
 *
 * A trace is a THRESHOLD — a decision to discard everything on one side of a line — and an over-traced
 * sketch has lost something: a faint construction line, a smudged tone showing where a curve was felt
 * out. The only person who can tell whether that mattered is the researcher with the actual sheet still
 * in front of them, and they can only tell if they are SHOWN the drawing against the photograph before
 * anything is attached. This is that showing. "Keep the photograph as it is" sits under it and costs
 * one press.
 *
 * ── WHAT THE PORTAL DOES, AND WHY IT IS NOT ENOUGH HERE ───────────────────────────────────────
 *
 * The web stacks two images in one frame and clips the BEFORE layer at a dragged position. That is the
 * right primitive and this keeps it: a wipe is the only affordance that puts both pictures in the SAME
 * PIXELS, and side by side on a 360 dp screen halves both.
 *
 * But a wipe can never show either picture whole, and on a laptop that does not matter because you can
 * drag to an end and read what is there. On a phone it matters twice: the picture is six inches, and
 * **the finger doing the dragging is sitting on the part of the drawing being compared**. So three
 * things change, and each is a handset answer to a handset problem:
 *
 *  1. **A named-state control above the frame — Drawing · Wipe · Photograph · Difference.** Wipe is the
 *     default and opens exactly as the portal does; the two end states are one tap each and show their
 *     picture whole. This is also the accessible equivalent of the portal's arrow-key handling, which
 *     has no counterpart on a touchscreen — a slider role with no key handler advertises a role it does
 *     not honour, and a handset has no keys to honour it with.
 *  2. **The wipe handle lives in a strip BELOW the frame, not on it.** The portal's grip is under
 *     Material's 48 dp minimum and has to be clamped a grip-radius inside the frame because at position
 *     0 half of it is clipped away. Moving it out solves both — and solves the one the portal cannot
 *     have noticed, which is that a thumb on the seam is a thumb over the drawing.
 *  3. **Press and hold anywhere in the frame peeks at the photograph; release returns.** One thumb, no
 *     aim, no handle to acquire. It is the gesture the wipe is standing in for.
 *
 * Pinch-zoom is added for a reason that is not convenience: a pencil line on a 1024 px plate shown at
 * ~360 dp is sub-pixel, so **the failure this comparator exists to catch is invisible at
 * fit-to-screen**. The transform is computed once and both layers are drawn through it, which is the
 * invariant the whole comparison rests on — two layers transformed independently misattribute every
 * line they draw.
 *
 * ── THE PLATE CONTRACT, CARRIED VERBATIM ──────────────────────────────────────────────────────
 *
 * Four decisions, each of which is a bug if reversed. Three of them are the runtime's to honour and are
 * stated at [TraceResult.tracePlate] and [TraceResult.photographPlate]: the photograph comes from the
 * DECODED PIXELS the engine was handed (two decoders hold different EXIF opinions, so one layer can
 * arrive rotated and the other upright, which reads as "the trace came out sideways"); the trace is
 * painted on OPAQUE WHITE (a transparent AFTER layer over the photograph shows the photograph through
 * both layers, so the divider moves and nothing changes, which is indistinguishable from a broken
 * slider); and neither plate may ever reach the record.
 *
 * **The fourth is this file's, and it is enforced rather than assumed:** both plates are the same size,
 * and a mismatch is a REFUSAL. A comparator that quietly letterboxed one layer would be comparing two
 * different framings of the drawing and reporting the difference as a tracing error.
 *
 * A fifth is the panel's and is stated here because this is where it is read: the plates are capped at
 * [TRACE_PLATE_LONG_EDGE_PX] and are usually SMALLER than the drawing, so this file says so under the
 * frame ([traceComparisonReduction]). Both numbers, or somebody judging lost line weight cannot tell
 * whether the loss is the trace's or the plate's.
 *
 * ── REDUCED MOTION ────────────────────────────────────────────────────────────────────────────
 *
 * `LocalAppPreferences.current.reducedMotion`, read the way the rest of this app reads it. There is
 * exactly one animation here — the seam sliding when the four-state control jumps it from one end to
 * the other — and with stillness on it jumps instead. **Nothing is lost**: every signal in this
 * comparator is static (the badges, the seam, the state description), and the slide exists only so
 * somebody can see WHICH way the frame just moved.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * State
 * ──────────────────────────────────────────────────────────────────────────── */

/** What the frame is showing. */
enum class TraceCompareMode {
    /** The trace, whole. */
    DRAWING,

    /** Both, split by a draggable seam. The default. */
    WIPE,

    /** The photograph, whole. */
    PHOTOGRAPH,

    /**
     * The two subtracted from each other — black where they agree, bright where they do not.
     *
     * The arithmetic both clients implement is stated at [traceDifferenceRow] — an absolute difference
     * per channel, deliberately not a luminance difference, because a luminance difference needs a set
     * of weights and there is more than one standard set.
     *
     * IT IS FOURTH AND NOT FIRST. The wipe answers the question this comparator exists for one strip at
     * a time and is what somebody reaches for; the difference answers it everywhere at once and is what
     * they reach for when the wipe has left them unsure. It is also the only one of the four that costs
     * a third bitmap, so it is built on the first press rather than with the other two.
     */
    DIFFERENCE,
}

/**
 * Where the seam sits when the comparator opens: **0, with the drawing filling the frame.**
 *
 * Mirrors the portal's own start position, and the reason is worth keeping: the thing being judged is
 * the TRACE, so the trace is what somebody should be looking at before they touch anything. Dragging
 * then reveals the photograph underneath it. Passing the two the other way round is the obvious mistake.
 */
const val TRACE_COMPARE_START: Float = 0f

/**
 * How long a finger must stay down before "peek at the photograph" starts.
 *
 * Not zero, and the reason is the pinch: a two-finger gesture puts one finger down first, and a peek
 * that began on contact would flash the photograph at the start of every zoom. 220 ms is under
 * Android's own long-press threshold (500 ms) because this is not a long press — somebody is holding to
 * look, not holding to open a menu, and half a second of nothing happening reads as the gesture not
 * existing.
 */
private const val TRACE_PEEK_HOLD_MS: Long = 220L

/** The most a plate may be magnified. Beyond this a 1024 px plate is showing its own pixels. */
private const val TRACE_MAX_ZOOM: Float = 6f

/** Milliseconds the seam takes to travel when a mode button moves it. Zero under stillness. */
private const val TRACE_SEAM_SLIDE_MS: Int = 180

/* ────────────────────────────────────────────────────────────────────────────
 * The comparator
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The two plates, in one frame.
 *
 * @param photograph the pixels the engine was handed, at the same size as [trace].
 * @param trace the trace, painted on opaque white.
 * @param tracedWidth the frame the DRAWING was traced in, so the reduction can be stated. The plates
 *   are capped at [TRACE_PLATE_LONG_EDGE_PX] and are usually smaller than this.
 * @param difference the third plate, once it has been built. Null until it is asked for.
 * @param differenceRefusal why there is no third plate, or empty while there is still hope of one.
 * @param onDifferenceWanted called each time the fourth chip is pressed. Building the plate is the
 *   caller's job, because this file is deliberately the one that does not know what a `Bitmap` is.
 * @param enabled false while a run is in flight; the pictures stay, the gestures stop.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TraceCompare(
    photograph: ImageBitmap,
    trace: ImageBitmap,
    tracedWidth: Int,
    tracedHeight: Int,
    modifier: Modifier = Modifier,
    difference: ImageBitmap? = null,
    differenceRefusal: String = "",
    onDifferenceWanted: () -> Unit = {},
    enabled: Boolean = true,
) {
    /*
      DECISION 4 OF THE PLATE CONTRACT, ENFORCED RATHER THAN ASSUMED. One rounding pixel is the
      tolerance; anything more and the two layers are different framings of the same drawing, and every
      difference the wipe shows would be attributed to the tracing.

      A DEFENSIVE GUARD, and its sentence is true of the path that would reach it. The runtime builds
      both plates from one `traceWorkingSize` result, so today they cannot disagree; the one condition
      that used to produce a mismatch is caught upstream and comes back as `TraceResult.plateRefusal`
      with no plates at all. What this stops is the next person wiring a pair from two different
      sources — and since the plate build is not fatal, "the trace itself is unaffected and can still be
      attached" is a description of what actually happens rather than a claim the code contradicts.
    */
    val widthGap = abs(photograph.width - trace.width)
    val heightGap = abs(photograph.height - trace.height)
    if (widthGap > 1 || heightGap > 1) {
        TracePanelNote(
            warning = true,
            text = "The two pictures came back at different sizes — the photograph at " +
                "${photograph.width}×${photograph.height} and the drawing at " +
                "${trace.width}×${trace.height} — so they cannot be laid over each other. The trace " +
                "itself is unaffected and can still be attached; only this comparison is unavailable.",
        )
        return
    }

    val stillness = LocalAppPreferences.current.reducedMotion

    var mode by remember { mutableStateOf(TraceCompareMode.WIPE) }
    var seam by remember { mutableFloatStateOf(TRACE_COMPARE_START) }
    var peeking by remember { mutableStateOf(false) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    /*
      ONE NUMBER DECIDES WHAT IS DRAWN, whatever set it. A mode button writes the seam to an end and a
      drag writes it to wherever the thumb is, so there is no second piece of state that could disagree
      with the first about which picture is on screen — the failure two independent flags always
      eventually produce. Peek is the one exception and it is deliberately NOT written into `seam`: a
      peek must leave the seam exactly where it was put, or letting go would lose somebody's place.
    */
    val target = when {
        peeking -> 1f
        mode == TraceCompareMode.DRAWING -> 0f
        mode == TraceCompareMode.PHOTOGRAPH -> 1f
        else -> seam
    }
    val shown by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = if (stillness) 0 else TRACE_SEAM_SLIDE_MS),
        label = "trace-compare-seam",
    )

    val percent = (shown * 100f).roundToInt()
    // CAPITALISED, because these two words are the BADGES drawn over the picture, and a spoken
    // description that names its controls differently from the way they are written is describing
    // something the listener cannot then point at.
    val speech = "$percent% Photograph, ${100 - percent}% Traced drawing"

    val aspect = if (photograph.height > 0) {
        photograph.width.toFloat() / photograph.height.toFloat()
    } else {
        1f
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {

        /* ── The four states ────────────────────────────────────────────────────────────────── */

        // A FLOW ROW AND NOT A ROW. Four Material buttons at their 40 dp minimum do not fit across
        // 360 dp, and a fixed Row would squeeze the labels rather than wrapping them — which on the
        // narrowest handsets this app is used from is a control that reads as broken.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TracePanelChip(
                label = "Drawing",
                selected = mode == TraceCompareMode.DRAWING,
                enabled = enabled,
            ) { mode = TraceCompareMode.DRAWING }
            TracePanelChip(
                label = "Wipe",
                selected = mode == TraceCompareMode.WIPE,
                enabled = enabled,
            ) { mode = TraceCompareMode.WIPE }
            TracePanelChip(
                label = "Photograph",
                selected = mode == TraceCompareMode.PHOTOGRAPH,
                enabled = enabled,
            ) { mode = TraceCompareMode.PHOTOGRAPH }
            TracePanelChip(
                label = TRACE_DIFFERENCE_LABEL,
                selected = mode == TraceCompareMode.DIFFERENCE,
                // Pressable even after a refusal: pressing it is how somebody reads the sentence saying
                // why there is nothing there, and a chip that went dead with no explanation is the
                // state this whole panel is written against.
                enabled = enabled,
            ) {
                mode = TraceCompareMode.DIFFERENCE
                // Asked every press rather than once. The caller ignores it when the plate is already
                // built, and somebody who pressed it during a low-memory moment gets a second try
                // without having to re-trace.
                if (difference == null) onDifferenceWanted()
            }
        }

        /* ── The frame ──────────────────────────────────────────────────────────────────────── */

        Box(
            modifier = Modifier
                .fillMaxWidth()
                /*
                  THE PHOTOGRAPH'S OWN RATIO, and not a fixed 16:9: a portrait A4 sheet in a landscape
                  frame loses most of the drawing off the top and bottom, which is precisely the part of
                  a sketch somebody is checking.
                */
                .aspectRatio(aspect)
                .clipToBounds()
                .background(MaterialTheme.field.surface200, RoundedCornerShape(10.dp))
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, TRACE_MAX_ZOOM)
                        pan += panChange
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            /*
                              A HOLD, NOT A TOUCH. `tryAwaitRelease` returning null from the timeout
                              means the finger is still down, which is the only state in which peeking
                              is what was meant. A pinch's first finger releases as soon as the
                              transform detector consumes it, so this does not fire during a zoom.
                            */
                            val releasedEarly = withTimeoutOrNull(TRACE_PEEK_HOLD_MS) {
                                tryAwaitRelease()
                            }
                            if (releasedEarly == null) {
                                peeking = true
                                tryAwaitRelease()
                                peeking = false
                            }
                        },
                    )
                }
                .semantics {
                    // The frame IS the picture. TalkBack gets the same two facts a sighted reader has:
                    // what is on screen, and in what proportion. In the difference mode the proportion
                    // is meaningless — there is one picture, not two laid over each other — so it says
                    // what that picture is instead of reading out a seam nobody can see.
                    contentDescription = if (mode == TraceCompareMode.DIFFERENCE) {
                        TRACE_DIFFERENCE_DESCRIPTION
                    } else {
                        "The traced drawing laid over the photograph it came from. $speech."
                    }
                },
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas

                /*
                  ONE TRANSFORM, BOTH LAYERS. Computed here and used for both `drawImage` calls, so it
                  is not possible for the two pictures to be scaled or panned differently — which is the
                  invariant the whole comparison rests on. Independently transformed layers do not fail
                  loudly; they show a drawing that appears to have drifted off its own photograph.
                */
                val iw = photograph.width.toFloat()
                val ih = photograph.height.toFloat()
                val fit = min(size.width / iw, size.height / ih)
                val scale = fit * zoom
                val dw = iw * scale
                val dh = ih * scale
                // Panning is clamped to the picture's own overhang, so a plate can never be flicked off
                // the frame and left as an empty grey box somebody has to guess how to undo.
                val slackX = max(0f, (dw - size.width) / 2f)
                val slackY = max(0f, (dh - size.height) / 2f)
                val px = pan.x.coerceIn(-slackX, slackX)
                val py = pan.y.coerceIn(-slackY, slackY)
                val left = (size.width - dw) / 2f + px
                val top = (size.height - dh) / 2f + py
                val dstOffset = IntOffset(left.roundToInt(), top.roundToInt())
                val dstSize = IntSize(dw.roundToInt(), dh.roundToInt())

                /*
                  THE DIFFERENCE PLATE IS A WHOLE PICTURE, NOT A LAYER. It is already the two plates
                  combined, so there is no seam to draw and nothing to clip — and it is drawn through
                  the SAME transform as the other two, so somebody who zoomed into a corner in the wipe
                  and then pressed the fourth chip is looking at the same corner.

                  A press-and-hold still peeks at the photograph from here, and that is deliberate: "is
                  that bright patch a line I actually drew" is exactly the question the difference plate
                  provokes, and the photograph is the only thing that answers it.
                */
                if (mode == TraceCompareMode.DIFFERENCE && !peeking) {
                    if (difference != null) {
                        drawImage(image = difference, dstOffset = dstOffset, dstSize = dstSize)
                    }
                    return@Canvas
                }

                drawImage(image = trace, dstOffset = dstOffset, dstSize = dstSize)

                val divider = size.width * shown.coerceIn(0f, 1f)
                if (divider > 0f) {
                    clipRect(left = 0f, top = 0f, right = divider, bottom = size.height) {
                        drawImage(image = photograph, dstOffset = dstOffset, dstSize = dstSize)
                    }
                    if (divider < size.width) {
                        // The seam. Two strokes, dark under light, so it is visible against a white
                        // sheet AND against a black line without depending on either.
                        drawRect(
                            color = Color.Black.copy(alpha = 0.45f),
                            topLeft = Offset(divider - 1.5f, 0f),
                            size = Size(3f, size.height),
                        )
                        drawRect(
                            color = Color.White.copy(alpha = 0.9f),
                            topLeft = Offset(divider - 0.5f, 0f),
                            size = Size(1f, size.height),
                        )
                    }
                }
            }

            /*
              THE BADGES ARE CLIPPED BY THE SAME NUMBER AS THE LAYER THEY NAME, which is not decoration:
              a badge visible over the picture it does not name is a label pointing at the wrong thing,
              and at a 40% wipe both would otherwise sit over the same half of the sheet.
            */
            if (mode == TraceCompareMode.DIFFERENCE && !peeking) {
                // One badge, because there is one picture and it is neither of the two the other badges
                // name. Without it a nearly black frame is indistinguishable from a plate that failed
                // to draw — and near-black is what a GOOD trace's difference looks like.
                TraceCompareBadge(TRACE_DIFFERENCE_LABEL, Modifier.align(Alignment.TopStart))
            } else {
                if (shown > 0.18f) {
                    TraceCompareBadge("Photograph", Modifier.align(Alignment.TopStart))
                }
                if (shown < 0.82f) {
                    TraceCompareBadge("Traced drawing", Modifier.align(Alignment.TopEnd))
                }
            }
            if (zoom > 1.01f) {
                TraceCompareBadge(
                    "${(zoom * 10f).roundToInt() / 10f}× — pinch out to fit",
                    Modifier.align(Alignment.BottomStart),
                )
            }
        }

        /* ── The wipe strip ─────────────────────────────────────────────────────────────────── */

        if (mode == TraceCompareMode.WIPE) {
            TraceWipeStrip(
                position = shown,
                enabled = enabled && !peeking,
                speech = speech,
                onPosition = { seam = it.coerceIn(0f, 1f) },
            )
            Text(
                // Said once, under the control, because a gesture nobody is told about is a gesture
                // nobody uses — and this one is the reason the strip can afford to be below the frame.
                "Drag the strip to wipe between the two. Press and hold the picture to see the " +
                    "photograph, and let go to come back.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        /* ── What the pictures are, and what they are not ───────────────────────────────────── */

        if (mode == TraceCompareMode.DIFFERENCE) {
            if (difference == null) {
                TracePanelNote(
                    warning = differenceRefusal.isNotBlank(),
                    // A press that produced nothing must say which of the two it was. "Working on it"
                    // and "it could not be done" are the same blank frame otherwise, and somebody who
                    // waits for the first when it was the second waits forever.
                    text = differenceRefusal.ifBlank { TRACE_DIFFERENCE_PENDING },
                    polite = true,
                )
            }
            Text(
                TRACE_DIFFERENCE_NOTE,
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        /*
          TWO FACTS ABOUT THE PICTURES THEMSELVES, SAID NEXT TO THEM.

          The white is otherwise stated only as the note on the "White background" toggle, which sits in
          the EXPORT group behind the disclosure on a different part of the panel. Somebody who never
          opens that step sees a white drawing over their photograph with no explanation, and the
          obvious conclusion is that the trace flooded their sheet.

          The reduction would otherwise be stated nowhere at all, which is worse: without it somebody
          judging lost line weight at 1024 against a 4096 trace cannot tell whether the loss is the
          trace's or the plate's — the one question this comparator exists to answer, asked about the
          comparator.
        */
        val reduction = traceComparisonReduction(
            plateWidth = photograph.width,
            plateHeight = photograph.height,
            tracedWidth = tracedWidth,
            tracedHeight = tracedHeight,
        )
        Text(
            if (reduction.isEmpty()) TRACE_COMPARE_WHITE_NOTE else "$TRACE_COMPARE_WHITE_NOTE $reduction",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The strip
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The seam's handle, in 48 dp of its own space below the picture.
 *
 * Below the frame and not on it, for the three reasons in this file's header. It is also the only place
 * a handle can be a full 48 dp without covering the drawing: inside the frame, Material's minimum
 * target is roughly a seventh of a 360 dp screen's width, sitting on top of the thing being judged.
 */
@Composable
private fun TraceWipeStrip(
    position: Float,
    enabled: Boolean,
    speech: String,
    onPosition: (Float) -> Unit,
) {
    var width by remember { mutableFloatStateOf(0f) }
    val handlePx = with(LocalDensity.current) { 48.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { width = it.width.toFloat() }
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = enabled,
                state = rememberDraggableState { delta ->
                    if (width > 0f) onPosition(position + delta / width)
                },
            )
            .pointerInput(enabled, width) {
                if (!enabled) return@pointerInput
                // Tap to jump. Somebody who wants "mostly photograph" should not have to drag there
                // from wherever the seam happens to be.
                detectTapGestures { offset ->
                    if (width > 0f) onPosition(offset.x / width)
                }
            }
            .semantics {
                /*
                  THE PORTAL'S SLIDER ROLE AND ITS VALUE TEXT, IN COMPOSE'S DIALECT.
                  `progressBarRangeInfo` is what TalkBack announces as a proportion; `stateDescription`
                  replaces the bare number with the sentence a person can act on. The four-state control
                  above the frame is the route in for somebody who cannot drag at all.
                */
                progressBarRangeInfo = ProgressBarRangeInfo(position.coerceIn(0f, 1f), 0f..1f)
                stateDescription = speech
                contentDescription = "Wipe between the photograph and the traced drawing"
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .align(Alignment.Center)
                .background(MaterialTheme.field.surface300, RoundedCornerShape(2.dp)),
        )
        // The handle, clamped so its whole 48 dp stays on screen at both ends. The portal has the same
        // clamp and has to apply it INSIDE the clipped frame, which is what costs it half a grip at
        // position 0.
        val travel = max(0f, width - handlePx)
        val offsetPx = (position.coerceIn(0f, 1f) * travel).roundToInt()
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetPx, 0) }
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.UnfoldMore,
                // Decorative: the strip's own semantics carry the meaning, and a second announcement
                // here would have TalkBack read the control twice.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(90f),
            )
        }
    }
}

/** A picture's name, drawn over the corner of the half it belongs to. */
@Composable
private fun TraceCompareBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
