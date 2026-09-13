package com.fieldrepository.app.ui

import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * ─────────────────────────────────────────────────────────────────────────────────────────────────
 * THE WALKTHROUGH AS A JOURNEY: ONE SCROLL, A SPINE DOWN ITS LEFT-HAND SIDE, AND A CARD PER STEP.
 *
 * ── WHY NOT A DECK ───────────────────────────────────────────────────────────────────────────────
 *
 * The surface this replaces is an `AlertDialog` with Back/Next/Done/Skip and a
 * "Walkthrough · ${step+1}/${steps.size}" title (MainActivity.kt:7875-7960). A deck cannot be made
 * good enough here for a structural reason rather than an aesthetic one: it shows exactly one card
 * and therefore cannot show a SHAPE. The single most valuable thing this content carries is that the
 * steps are in an ORDER and that the order is the reason the later pickers have anything in them —
 * `steps.ts` says so in its own header and every one of the ten bodies leans on it. A reader who can
 * only ever see card 6 of 12 has been shown a position, not a journey, and the paging controls then
 * cost the bottom of every card to say so.
 *
 * ── WHAT WAS COPIED FROM THE WEB, AND WHAT WAS NOT ───────────────────────────────────────────────
 *
 * The instrument is the web guide's: a numbered dot per step on a progress spine, a card that expands
 * to a "why / what the screen asks for / watch out for" panel, and a ring that fills as you read. The
 * web's STICKY RAIL is deliberately NOT copied: it is `hidden lg:block`, so it does not exist below
 * 1024px and there is no handset layout to be faithful to. What replaced it is one pinned header row
 * carrying the same three facts the rail carries — how far along you are, which step you are on, and
 * the way out.
 *
 * ── WHAT WAS DROPPED FROM THE DESIGNER PORTAL'S VERSION OF THIS FILE, AND WHY ────────────────────
 *
 * This file is a port of `designer-portal/android/.../ui/WalkthroughJourney.kt` (2381 lines). Two
 * declarations did not come across, and neither is scenery:
 *
 *   1. `WALKTHROUGH_FIELDS` (its :569-851) — a 22-key map of the web's `GuideStep.fields`, held to
 *      `steps.ts` by `designer-portal/backend/tests/test_walkthrough_fields_parity.py`, 674 lines of
 *      Python parsing TypeScript on one side and Kotlin on the other. This repository has no such
 *      test. Rather than ship the duplicate WITHOUT its guard — which is the exact failure that
 *      Python file exists to prevent — the strings live on [WalkStep.fields] and
 *      `WalkthroughStepsTest` imports them and compares them to `steps.ts` directly. One register,
 *      one guard, no second parser.
 *   2. `WALK_CAUTION_LEAD` (its :316) — a regex that cut the caution back out of the body at the
 *      words "watch out". Thirty lines of its comment are a post-mortem on the two cautions that
 *      regex's literal predecessor silently dropped. Here the caution is [WalkStep.watch], a list, so
 *      there is no seam to cut and no punctuation for a parser to disagree with — and the ten steps
 *      keep their two-to-four SEPARATE bullets instead of being collapsed into one paragraph.
 *
 * Nothing else was dropped, because there was nothing designer-specific to drop: the designer file
 * contains no sketch canvas, no prototype card, no stage arc, no QR widget and no per-step
 * illustration. Every step renders through the same [WalkthroughRow] → [WalkthroughStepCard], and the
 * only per-step variation is data.
 * ─────────────────────────────────────────────────────────────────────────────────────────────────
 */

// ---------------------------------------------------------------------------------------------
// The rail: one owner of the horizontal axis
// ---------------------------------------------------------------------------------------------

/*
 * `--guide-rail`, IN KOTLIN. The web sets one CSS variable on the step list and derives four things
 * from it: the width of each card's first grid column, and the box the track, the fill and the
 * travelling node are centred in.
 *
 * The one-owner rule is not about transforms — Compose has no inline-transform trap to fall into. It
 * is about a number that four call sites have to agree on: the gutter every step card leaves on its
 * left, and the x the spine's track, fill and travelling node are all drawn on, which is half of it.
 * The web's own mobile value is 2rem of rail plus a 1rem gap; 44dp is that, and it doubles as the
 * left margin of the screen, so there is no second inset for the two of them to disagree about.
 *
 * WHICH IS WHY THIS CONSTANT IS A FLOOR AND NOT THE FIGURE ITSELF. The bubble's diameter follows the
 * reader's font scale (see [WALK_BUBBLE_TEXT]), so the gutter has to be able to grow with it. The
 * single owner is therefore the `railWidth` local in [WalkthroughJourney], computed once from this
 * floor and from the bubble and handed to the spine and to every row. Nothing else in this file may
 * work out a rail width of its own, and nothing may go back to reading this constant directly.
 */
private val WALK_RAIL = 44.dp

/*
 * THE NUMBERED BUBBLE, SIZED FROM ITS OWN TEXT RATHER THAN PINNED TO A NUMBER OF DP.
 *
 * The arithmetic is worth writing down so nobody flattens it back to a constant. `AppearanceScreen`
 * multiplies the reader's own system font scale by another 1.125 when "Larger text" is on
 * (`LARGER_TEXT_SCALE`, AppearanceScreen.kt:102), so a researcher reading at the platform's 2x is
 * really at 2.25x, and 12.sp of digits is then about 27dp tall. A `Modifier.size` hands its child
 * FIXED constraints, so a two-digit step in a flat 30dp box is not a snug fit — it is measured at
 * 30dp, and with `maxLines = 1` and the default `TextOverflow.Clip` the second digit is cut in half.
 * Step numbers are the one thing on this screen a reader cannot recover from context.
 *
 * So the diameter is derived from the text the way [WalkthroughProgressRing] derives its own, by
 * asking the density what the reader's scale actually is instead of guessing. The multiplier is
 * chosen so that AT THE DEFAULT SCALE THE ANSWER IS EXACTLY [WALK_BUBBLE_MIN] — 12dp times 2.5 is
 * 30dp — which means this changes nothing for the reader who has not touched their font size, and
 * grows only for the reader who has. The ceiling keeps it a bubble rather than a saucer.
 *
 * This journey is ten steps and none of them is two digits TODAY. That is not a reason to simplify
 * it: `steps.ts` is edited by the web workstream, the count is derived everywhere else in this
 * feature precisely so a step can be inserted without a human noticing, and a geometry that silently
 * clips at eleven would be the one part of the feature that did not follow.
 */
private val WALK_BUBBLE_TEXT = 12.sp
private val WALK_BUBBLE_MIN = 30.dp
private val WALK_BUBBLE_MAX = 54.dp

/** How far down its row the bubble sits — the Compose spelling of the web's `mt-6` on the bubble. */
private val WALK_BUBBLE_TOP = 14.dp

/** The spine's track. The web's is `w-0.5`, two CSS pixels. */
private val WALK_TRACK = 2.dp

/** The travelling node's radius, and the halo behind it (the web's `ring-4`). */
private val WALK_NODE = 5.dp
private val WALK_NODE_HALO = 4.dp

/** The gap under every card. It is measured as part of the card's own height — see [WalkthroughMetrics]. */
private val WALK_GAP = 12.dp

/** Air above the first card and under the last, so the journey does not start or stop flush. */
private val WALK_TOP = 12.dp
private val WALK_BOTTOM = 40.dp

/** The right-hand margin. The left one is the rail itself, which is doing two jobs on purpose. */
private val WALK_EDGE = 16.dp

/** The corner every card in here is cut to. */
private val WALK_CARD_CORNER = 14.dp

/**
 * The corner on a "What the screen asks for" chip, AND IT IS DELIBERATELY NOT THE WEB'S.
 *
 * The web sets `rounded-full` on those chips and gets away with it because its card is a wide column
 * and most of its labels are one line — "Notes", "Phone", "Gender". A 360dp handset is not that card.
 * The `review` and `view-data` steps carry section DESCRIPTIONS rather than form labels — "By
 * workshop — every record filed under the workshop it was made in (the view it opens on)" is 89
 * characters, which at 12sp wraps to three lines here. A fully-rounded shape on a three-line box is a
 * stadium with a 25dp radius — a blob, with the first and last words of the sentence sitting inside
 * the curve.
 *
 * A fixed radius reads correctly at BOTH shapes, which is the property actually needed, so it is the
 * geometry that diverges and never the words. The strings are the web's to the character and
 * `WalkthroughStepsTest` holds them there; the corner is this screen's own answer to this screen's
 * own width.
 */
private val WALK_CHIP_CORNER = 9.dp

/**
 * The reading line, as a fraction of the viewport, and it is the WEB'S OWN NUMBER.
 *
 * `useScroll({ offset: ["start 65%", "end 65%"] })` — progress begins when the list's top crosses 65%
 * down the viewport and completes when its bottom reaches the same line. 65% is measured rather than
 * chosen: it is the figure at which the fill reads 100% with the last step's bottom edge still on
 * screen, at every width it was checked at including 360px.
 */
private const val WALK_BAND = 0.65f

/**
 * Where "I am on this step now" is decided, as a fraction of the viewport.
 *
 * The web asks an IntersectionObserver: `viewport={{ margin: "-45% 0px -45% 0px", amount: "some" }}`,
 * which shrinks the observation root to the middle tenth of the screen and fires in both scroll
 * directions the moment any part of a card enters it. There is no intersection observer here, and
 * building one out of per-frame position callbacks would be the jank this screen cannot have — so the
 * same question is answered as geometry instead: the active step is the one whose own extent contains
 * the viewport's midline. The two agree in practice because that band is a tenth of the screen and
 * the shortest collapsed card on a handset is taller than it, so a card in the band is a card over
 * the midline. The divergence to know about is at the very ends, where the web can have NO card in
 * its band and this cannot; here the first and last steps simply hold the readout, which is what a
 * reader would say anyway.
 */
private const val WALK_ACTIVE_BAND = 0.5f

/**
 * How much of a card has to have arrived for it to count as seen — the web's `amount: 0.25`.
 *
 * It is a SECOND threshold on purpose and it is not interchangeable with [WALK_ACTIVE_BAND]. The web
 * puts the two on different elements: a middle-band threshold would reveal a card only once you were
 * already reading it, and a quarter-visible threshold would latch the active readout on the first
 * card and never move it again. Merging them breaks one or the other.
 */
private const val WALK_REVEAL_AT = 0.25f

/** How far a revealed card rises as it fades in — the web's `riseItem(reduce, 10)`. */
private val WALK_RISE = 10.dp

/** The dot arrives, then the card. See [WalkthroughRow]. */
private const val WALK_REVEAL_MS = 260
private const val WALK_CARD_DELAY_MS = 70

/** A little air above a step when it is scrolled to, so it does not land flush against the header. */
private val WALK_SCROLL_HEADROOM = 12.dp

// ---------------------------------------------------------------------------------------------
// The measurements the spine is drawn from
// ---------------------------------------------------------------------------------------------

/**
 * Every card's height, and the arithmetic the spine and the ring are drawn from.
 *
 * ── THIS CLASS IS THE PERFORMANCE THESIS OF THE WHOLE FILE ───────────────────────────────────────
 *
 * A scroll-linked screen has exactly one way to go wrong on the handsets this fleet carries, and it
 * is recomposing on scroll. Three rules keep it out:
 *
 *   1. Heights are snapshotted through `onSizeChanged`, which fires when a size CHANGES — never
 *      through `onGloballyPositioned`, which fires on every scrolled frame for every child and would
 *      turn a fling into a write storm against the very state the spine reads.
 *   2. The spine is read in `drawBehind`. A draw-phase read means a scroll invalidates a REPAINT of
 *      three shapes: no recomposition, no re-layout, no cards measured again.
 *   3. The header reads through `derivedStateOf`, which publishes an Int with at most 101 reachable
 *      values, so it recomposes about a hundred times over a whole journey rather than once per
 *      frame.
 *
 * `@Stable` because Compose has to be told: this is a mutable object with snapshot state inside it,
 * and without the annotation every composable taking it as a parameter is unskippable.
 */
@Stable
private class WalkthroughMetrics(
    /** Every card's id, in the order they are laid out. */
    private val ids: List<String>,
    /** Which of them are numbered steps: the deck without its opening and closing cards. */
    val listRange: IntRange,
    private val scroll: ScrollState,
    private val topPadPx: Float,
    private val bubbleCentrePx: Float,
    private val headroomPx: Float,
) {
    /** Keyed by id and not by index, so a reordered or inserted step cannot read a neighbour's height. */
    private val heights = mutableStateMapOf<String, Int>()

    /** The height of the window the content scrolls inside — not of the content. */
    var viewportPx by mutableIntStateOf(0)
        private set

    fun measureViewport(height: Int) {
        // Guarded, always. An unguarded write here is a write per layout pass, and a write to snapshot
        // state during layout that something reads during layout is the shortest route to an
        // invalidation that re-triggers itself.
        if (viewportPx != height) viewportPx = height
    }

    fun measure(id: String, height: Int) {
        if (heights[id] != height) heights[id] = height
    }

    private fun heightOf(index: Int): Float = (heights[ids[index]] ?: 0).toFloat()

    /** The content-space y of a card's top edge. Linear in the number of cards; there are twelve. */
    fun topOf(index: Int): Float {
        var y = topPadPx
        for (i in 0 until index) y += heightOf(i)
        return y
    }

    private fun bottomOf(index: Int): Float = topOf(index) + heightOf(index)

    /** The spine's two ends: the first numbered bubble's centre and the last one's. */
    fun trackTop(): Float = topOf(listRange.first) + bubbleCentrePx

    fun trackBottom(): Float = topOf(listRange.last) + bubbleCentrePx

    /**
     * How far down the journey the reader has read, 0..1.
     *
     * THE READING LINE IS THE WEB'S, WITH ONE CLAUSE ADDED FOR A PHONE. The web's line sits at
     * [WALK_BAND] of the viewport and progress is how far the list has travelled past it. That works
     * on a laptop because the hero band is tall enough to hold the first card below the line at rest.
     * On a 360dp handset the hero is a card rather than a full-height band, so the first step is
     * ALREADY above the 65% line before anybody has scrolled — and a walkthrough that opens reading
     * "8%" is a walkthrough that has credited the reader with eight percent of a journey they have
     * not started. So the line is 65% of the viewport OR the first card's own top, whichever is
     * higher up the screen: at rest the reading line rests on the first card and progress is exactly
     * zero, and on any layout tall enough for the web's version, this IS the web's version.
     */
    fun progress(): Float {
        val viewport = viewportPx
        if (viewport <= 0) return 0f
        val top = topOf(listRange.first)
        val span = bottomOf(listRange.last) - top
        if (span <= 0f) return 0f
        val line = minOf(viewport * WALK_BAND, top)
        /*
         * THE BOTTOM OF THE SCROLLER IS ONE HUNDRED PER CENT, WHATEVER THE ARITHMETIC ABOVE SAYS.
         *
         * Without this line the ring closes only if the reading line can physically REACH the last
         * step's bottom edge, and whether it can is a property of how tall the two end cards happen
         * to render: the scroller stops at `maxValue`, so progress tops out below one unless
         * `topOf(first) + outro height + the tail spacer` is at least a viewport. On a 360dp handset
         * that inequality holds with room to spare — the opening card alone is most of a screen —
         * which is why nothing shows there. Widen the window and it stops holding: on a tablet the
         * same cards reflow to a fraction of their height while the viewport gets taller, and a
         * reader who has scrolled to Done is looking at a ring reading about eighty per cent.
         *
         * That is precisely the failure [WalkthroughProgressRing] refuses a `CircularProgressIndicator`
         * over, arriving through the numerator instead of through the control. And the reading it
         * produces is not merely ugly, it is FALSE: there is nothing left to scroll, so there is
         * nothing left of the journey.
         *
         * `maxValue > 0` guards the only case this would otherwise get wrong, a deck short enough to
         * fit the window with nothing to scroll at all: there `value` and `maxValue` are both zero on
         * the first frame and the ring would open at a hundred per cent. The real deck cannot do
         * that; a preview handed three cards can.
         */
        if (scroll.maxValue > 0 && scroll.value >= scroll.maxValue) return 1f
        return ((scroll.value + line - top) / span).coerceIn(0f, 1f)
    }

    /** Which step the reader is on: the one whose extent covers the viewport's midline. */
    fun activeIndex(): Int {
        val viewport = viewportPx
        if (viewport <= 0) return listRange.first
        val line = scroll.value + viewport * WALK_ACTIVE_BAND
        for (index in listRange) {
            if (line < bottomOf(index)) return index
        }
        return listRange.last
    }

    /**
     * The furthest card that has come far enough into the window to count as seen, never going back.
     *
     * The latch is carried in by the caller and handed back rather than kept here, because this is
     * called from inside a `derivedStateOf` calculation and a derived calculation must not write
     * snapshot state. It is safe to re-enter for the same scroll offset — the answer is a pure
     * function of the offsets and the latch, and the latch only ever moves one way.
     */
    fun revealedThrough(previous: Int): Int {
        val viewport = viewportPx
        if (viewport <= 0) return previous
        val fold = scroll.value + viewport
        var reached = previous
        for (index in ids.indices) {
            val height = heightOf(index)
            // Nothing has been measured yet on the very first frame, and a run of zero-height cards
            // would otherwise all "arrive" at once at the top of the content.
            if (height <= 0f) break
            if (topOf(index) + height * WALK_REVEAL_AT > fold) break
            reached = maxOf(reached, index)
        }
        return reached
    }

    /** Where the scroller has to be for [index] to sit just under the pinned header. */
    fun scrollTarget(index: Int): Int =
        (topOf(index) - headroomPx).coerceIn(0f, scroll.maxValue.toFloat()).roundToInt()
}

// ---------------------------------------------------------------------------------------------
// Cutting a step into the blocks a card draws
// ---------------------------------------------------------------------------------------------

/**
 * One step, cut into the four blocks a card draws.
 *
 * The card asks this and nothing else, which is what keeps it ignorant of where any of the four came
 * from: two are cut out of [WalkStep.body] and two are read straight off the step.
 */
internal data class WalkthroughFacets(
    /** The collapsed line under the title: what you are doing at this point. One sentence. */
    val summary: String,
    /** The panel's prose: why the dataset needs this step. */
    val detail: String,
    /**
     * The form labels the screen asks for, in screen order, one entry per chip.
     *
     * Empty for the two ends of the deck, which teach no screen. Empty means the card draws no
     * heading, exactly as it does for [watch].
     */
    val fields: List<String>,
    /** The cautions, one entry per bullet. Empty for the two ends of the deck, which have none. */
    val watch: List<String>,
)

/**
 * Cut [step] into the blocks the card draws.
 *
 * ONLY ONE SEAM IS CUT HERE, AND IT IS THE ONE THE PROSE ACTUALLY GUARANTEES: the end of the first
 * sentence of [WalkStep.body], which is the web's `summary` where the web's `why` begins. The
 * designer portal's version of this function cut a SECOND seam — the caution, out of the same string,
 * at the words "watch out" — and thirty lines of its comment are the post-mortem on the two cautions
 * a literal `"Watch out:"` silently dropped when two steps happened to write "WATCH OUT, BECAUSE…"
 * with a comma. Here the caution is [WalkStep.watch] and the labels are [WalkStep.fields], both
 * lists, so there is no punctuation for a parser to disagree with and a step with none answers
 * `emptyList()` rather than a list holding one blank string. The card decides whether to draw a
 * heading by asking whether a list is empty, and a heading over an empty bullet is a latent defect
 * rather than a layout choice.
 *
 * The first-sentence cut is deliberately conservative: a full stop only counts if it is followed by a
 * space and a capital, if the character before it is a letter or digit, and if there is already a
 * sentence's worth of text in front of it. A greedier rule would cut "e.g." in half. There is no
 * "e.g." in any of the twelve bodies today — that was checked rather than assumed — and the guard is
 * here so that adding one is not a silent typographical bug in a summary line.
 */
internal fun walkthroughFacets(step: WalkStep): WalkthroughFacets {
    val prose = step.body.trim()
    val cut = walkthroughFirstSentenceEnd(prose)
    return WalkthroughFacets(
        summary = prose.substring(0, cut).trim(),
        detail = prose.substring(cut).trim(),
        fields = step.fields,
        watch = step.watch,
    )
}

/** The index just past the first sentence of [prose], or its whole length if it has only one. */
private fun walkthroughFirstSentenceEnd(prose: String): Int {
    // Below this a "sentence" is a fragment, and a two-word collapsed line teaches nobody anything.
    val floor = 40
    var index = floor
    while (index < prose.length - 1) {
        val here = prose[index]
        if (here == '.' && prose[index + 1] == ' ') {
            val before = prose[index - 1]
            val next = prose.getOrNull(index + 2)
            val abbreviation = index >= 2 && prose[index - 2] == '.'
            if (before.isLetterOrDigit() && !abbreviation && next != null && next.isUpperCase()) {
                return index + 1
            }
        }
        index++
    }
    return prose.length
}

/**
 * A step's title split into the feature's name and the control you tap — the web's `label` and its
 * `action` pill, which [WalkStep.title] carries as one string joined by a middle dot.
 *
 * Falls back to the whole title with no chip rather than guessing, so a title that ever loses its
 * separator renders as a heading instead of as a heading and an empty pill.
 *
 * `internal` RATHER THAN PRIVATE, AND FOR A TEST RATHER THAN FOR A SECOND CALLER. The cross-client
 * parity assertion compares this side's feature names against the web's `GuideStep.label`, and the
 * feature name is only recoverable from a title by applying THIS function. A test that re-split the
 * title with its own `indexOf(" · ")` would be a second copy of the rule, green on the day it was
 * written and silently covering nothing the day the separator changed — which is the same class of
 * defect as the hand-copied register this whole feature exists to stop. The codebase's own precedent
 * for widening purely so a test can see something is `internal fun navBadge` in AppNavigation.kt.
 */
internal fun walkthroughTitleParts(title: String): Pair<String, String?> {
    val dot = title.indexOf(" · ")
    if (dot < 0) return title to null
    return title.substring(0, dot).trim() to title.substring(dot + 3).trim().ifEmpty { null }
}

// ---------------------------------------------------------------------------------------------
// Is a screen reader driving?
// ---------------------------------------------------------------------------------------------

/**
 * Whether TalkBack (or any touch-exploration service) is driving this screen.
 *
 * ── THIS IS A CORRECTNESS REQUIREMENT AND NOT A PREFERENCE ───────────────────────────────────────
 *
 * The reveal in [WalkthroughRow] is a `graphicsLayer` whose `alpha` starts at 0. A layer at
 * `alpha = 0f` makes `NodeCoordinator.isTransparent` true, which Compose turns into
 * `setVisibleToUser(false)` on the accessibility node — FOR THAT NODE AND EVERY DESCENDANT — and
 * TalkBack filters exactly those nodes out of its traversal. A scroll-driven reveal would therefore
 * hand a blind reader a three-step walkthrough and no indication that the other seven exist, because
 * the cards that have not been scrolled to are not merely invisible, they are absent from the tree.
 *
 * The fix is to switch the reveal OFF, not to work around it. A card is content, so what the reveal
 * may cost is a fade — never the card.
 *
 * `isTouchExplorationEnabled` and NOT `isEnabled`: `isEnabled` is true for any accessibility service
 * at all, including a password manager or a screen-dimming filter, neither of which navigates by
 * traversal and neither of which should lose the animation.
 *
 * OBSERVED RATHER THAN SAMPLED ONCE, because TalkBack has a volume-key shortcut: a reader who turns
 * it on while this dialog is open must not be left with the seven cards that had not yet arrived
 * still filtered out of the tree.
 */
@Composable
private fun walkthroughScreenReaderActive(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    // Seeded from the initialiser so the very first composition is already right; a reveal that
    // started and then aborted would be worse than one that never ran.
    var active by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        // A device with no accessibility manager at all is not a device this can answer for; the
        // seeded false stands and the journey animates as normal.
        if (manager == null) return@DisposableEffect onDispose { }
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            active = enabled
        }
        manager.addTouchExplorationStateChangeListener(listener)
        // Re-read after registering rather than trusting the seed: between the initialiser above and
        // this line the state can have changed, and that window is precisely a volume-key shortcut.
        active = manager.isTouchExplorationEnabled
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return active
}

// ---------------------------------------------------------------------------------------------
// The journey
// ---------------------------------------------------------------------------------------------

/**
 * The whole walkthrough as one scroll: a pinned header, a spine, and a card per step.
 *
 * @param steps the deck — an opening card, the numbered journey, a closing card. [walkthroughSteps].
 * @param reduceMotion decided ONCE by [WalkthroughDialog] and threaded down; see its comment for why
 *   it is not asked per card.
 * @param onOpen leave for a step's screen. The caller closes the walkthrough and marks it seen.
 * @param onFinish the one exit. Skip, Done and the back gesture with nothing open all call it.
 */
@Composable
internal fun WalkthroughJourney(
    steps: List<WalkStep>,
    reduceMotion: Boolean,
    onOpen: (NavDestination) -> Unit,
    onFinish: () -> Unit,
) {
    // Three is the smallest shape this draws: an opening card, at least one numbered step, a closing
    // card. The real deck is twelve and this cannot fire; the guard is so that a preview handed a
    // two-card stub renders nothing rather than indexing off the end of the list.
    if (steps.size < 3) return

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    /*
     * THE NUMBERED PART OF THE DECK: everything except the opening and closing cards.
     *
     * `walkthroughStepNumber` is the one place a step number may come from and it answers null for
     * the two ends, so the range is derived from the list's own shape rather than from a constant
     * `1..10` — insert a step and this follows, which is the same rule the titles obey by carrying no
     * number of their own. The coercion is for a deck too short to have ends, which cannot happen
     * with the real list and would otherwise be an index crash in a preview.
     */
    val listRange = remember(steps) { 1..(steps.size - 2).coerceAtLeast(1) }

    // The denominator every "Step n of m" on this screen counts against, taken from the journey and
    // never from the deck. The two are different numbers on purpose: numbering the opening and
    // closing cards as steps would put a reader at "Step 1 of 12" on a card whose own first sentence
    // says there are ten of them.
    val journeyTotal = walkthroughJourney.size

    /*
     * THE HORIZONTAL AXIS, DECIDED ONCE, HERE, AND HANDED TO EVERYTHING THAT DRAWS ON IT.
     *
     * This is the [WALK_RAIL] one-owner rule actually being kept. The bubble's diameter grows with
     * the reader's font scale, so the gutter it sits in has to grow with it or a 54dp bubble ends up
     * straddling a 44dp column and lying across the card beside it. Both numbers are therefore
     * computed in this one place: the spine's centre is `railWidth / 2`, every row's gutter is
     * `railWidth`, and every bubble is `bubbleSize`. There is no second opinion about either anywhere
     * in the file — a rail whose width is worked out twice is a spine that misses its dots the first
     * time somebody changes one of them.
     *
     * Both are pure functions of `density`, which is also the [WalkthroughMetrics] key below, so a
     * font-scale change (which this activity handles WITHOUT being recreated — AndroidManifest.xml:38
     * declares `configChanges` including `fontScale`) rebuilds the geometry and the measurements
     * together rather than leaving one describing the other's old layout.
     */
    val bubbleSize = with(density) { WALK_BUBBLE_TEXT.toDp() * 2.5f }
        .coerceIn(WALK_BUBBLE_MIN, WALK_BUBBLE_MAX)
    val railWidth = maxOf(WALK_RAIL, bubbleSize + 10.dp)

    val metrics = remember(steps, scrollState, density) {
        WalkthroughMetrics(
            ids = steps.map { it.id },
            listRange = listRange,
            scroll = scrollState,
            topPadPx = with(density) { WALK_TOP.toPx() },
            bubbleCentrePx = with(density) { (WALK_BUBBLE_TOP + bubbleSize / 2).toPx() },
            headroomPx = with(density) { WALK_SCROLL_HEADROOM.toPx() },
        )
    }

    /*
     * THE THREE DERIVED READINGS. Each one publishes a small value that changes rarely, which is what
     * keeps a scroll off the recomposer — see the [WalkthroughMetrics] KDoc.
     *
     * ⚠ AND WHERE EACH ONE IS *READ* IS AS LOAD-BEARING AS THE `derivedStateOf` AROUND IT. `Column`
     * is an INLINE composable, so its content lambda has no restart scope of its own: the body below
     * is part of THIS function's scope, every row and all. Passing `percent` and `activeIndex` to
     * [WalkthroughHeader] as `Int` arguments would perform both snapshot reads HERE, which makes a
     * scroll invalidate the scope that emits every card. The cost is not a redraw: `percent` has 101
     * reachable values and `activeIndex` ten, so one pass down the journey would rebuild this
     * Column's modifier chain and re-issue all twelve (skippable) row calls about a hundred times —
     * and on a fling those hundred land inside the second or so the fling lasts, which is one to
     * three per FRAME on the handsets this fleet actually carries. That is precisely the
     * recomposition-per-scroll-frame this file was written to avoid, arrived at through the front
     * door.
     *
     * So the two header readings are handed down as `() -> Int` and read inside the composables that
     * print them. The reader is not deferred for elegance; it is deferred so that the SCOPE that
     * changes is the ring and the readout.
     *
     * `revealedThrough` STAYS READ HERE, and that is not an oversight. A row's reveal is a
     * `targetValue` handed to `animateFloatAsState`, which has to be read in composition to start an
     * animation at all — there is no draw-phase spelling of "this card has arrived". It publishes at
     * most twelve times for the whole page, one per card, and it is short-circuited away entirely by
     * `revealAll` below.
     */
    val percent by remember(metrics) {
        derivedStateOf { (metrics.progress() * 100f).roundToInt().coerceIn(0, 100) }
    }
    val activeIndex by remember(metrics) { derivedStateOf { metrics.activeIndex() } }
    val revealedThrough by remember(metrics) {
        // The latch lives in the remember block rather than in state, for the reason the method's own
        // KDoc gives: a derived calculation must not write snapshot state.
        var frontier = -1
        derivedStateOf {
            frontier = metrics.revealedThrough(frontier)
            frontier
        }
    }

    /*
     * WHICH CARD IS OPEN — an id and never an index.
     *
     * `rememberSaveable` because `AndroidManifest.xml:38` declares `configChanges` for orientation,
     * screenSize, uiMode, density and fontScale, so a rotation, a theme change and a font-size change
     * never recreate this activity and never touch this state. What recreates it is the system
     * reclaiming a backgrounded app, and on the handsets this work runs on that is not an edge case:
     * a researcher eight cards down puts the phone down to photograph something and the camera takes
     * the memory. The scroll position survives the same event for free, because `rememberScrollState`
     * is `Saver`-backed; between them the reader comes back to the paragraph they were reading rather
     * than to the top.
     *
     * An id rather than an index for the same reason `walkthroughStepNumber` matches on id: a saved
     * index is a card that quietly becomes a different card the day a step is inserted above it.
     *
     * The first step is open on arrival, which is the web's behaviour and its stated reason: so the
     * shape of a step is obvious without the reader having to discover that the cards expand.
     */
    var expandedId by rememberSaveable(steps) {
        mutableStateOf<String?>(steps[listRange.first].id)
    }

    /**
     * Open a step and travel to it.
     *
     * ── ONE FRAME OF DELAY, AND IT IS LOAD-BEARING ───────────────────────────────────────────────
     *
     * The card is expanded first and the target is computed a frame later, because expanding a card
     * changes the height of everything under it and a target computed before the layout has settled
     * scrolls to where the step USED to be. The web does the same thing for the same reason:
     * `requestAnimationFrame(() => scrollToStep(hash, true))`, one frame so the expanded card has its
     * final height.
     *
     * ── AND THE SCROLL ITSELF BRANCHES ON REDUCED MOTION ─────────────────────────────────────────
     *
     * `scrollTo` under the preference, `animateScrollTo` otherwise. SMOOTH SCROLLING IS MOTION: it is
     * a screenful of text sliding under somebody who asked for nothing to slide.
     */
    fun travelTo(index: Int) {
        if (index !in steps.indices) return
        expandedId = steps[index].id
        scope.launch {
            withFrameNanos { }
            val target = metrics.scrollTarget(index)
            if (reduceMotion) scrollState.scrollTo(target) else scrollState.animateScrollTo(target)
        }
    }

    /*
     * ── THE SYSTEM BACK GESTURE ──────────────────────────────────────────────────────────────────
     *
     * `dismissOnBackPress` is false on the window (see [WalkthroughDialog]) so that back has exactly
     * one listener rather than two with different opinions, and this is it.
     *
     * Back means "undo the last thing I did". With a panel open, that is the panel. With nothing open
     * it is the walkthrough itself, and what it leaves to is the reason this is a dialog: the screen
     * underneath was never unmounted, so closing reveals it. There is no stack to pop and no
     * destination to compute, which is what rules out a back press finishing the activity and
     * dropping somebody onto the launcher.
     *
     * THE COST, WHICH IS REAL AND IS ACCEPTED: the first step is open on arrival, so on first run it
     * takes two presses to leave — one closes a panel the reader did not open, one leaves. The
     * alternative is a second saveable flag recording who opened what, and a second piece of state
     * that has to survive process death to answer one gesture is worse than one extra press that has
     * visible feedback. It goes through [onFinish] like every other exit, so the seen flag is written
     * whichever way somebody leaves.
     */
    BackHandler(enabled = true) {
        if (expandedId != null) expandedId = null else onFinish()
    }

    /*
     * WHEN NOTHING WAITS TO BE REVEALED, AND THE TWO VERY DIFFERENT REASONS FOR IT.
     *
     * Reduced motion is the obvious half: the reveal is an animation and the reader asked for none,
     * so every card is simply there. Nothing on this screen may exist ONLY as motion, and a card is
     * content, so what the preference removes is the fade and never the card.
     *
     * The screen-reader half is not a preference at all, it is a correctness requirement, and
     * [walkthroughScreenReaderActive] carries the whole argument: a `graphicsLayer` at `alpha = 0f`
     * reports `isVisibleToUser = false` for itself and every descendant, and TalkBack drops exactly
     * those nodes from its traversal.
     *
     * They are ORed into one value rather than checked separately at the three call sites below,
     * because "is this row revealed" must have one answer and three copies of a two-term condition is
     * how one of them later loses a term.
     */
    val revealAll = reduceMotion || walkthroughScreenReaderActive()

    val trackColour = MaterialTheme.field.surface300
    val spineColour = MaterialTheme.colorScheme.primary
    val haloColour = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    val trackPx = with(density) { WALK_TRACK.toPx() }
    val railCentrePx = with(density) { (railWidth / 2).toPx() }
    val nodePx = with(density) { WALK_NODE.toPx() }
    val haloPx = with(density) { WALK_NODE_HALO.toPx() }

    Column(modifier = Modifier.fillMaxSize()) {
        WalkthroughHeader(
            steps = steps,
            // Lambdas and not values: see the note over the derived readings. Reading either of these
            // HERE would put the scroll back in the recompose scope that emits every row.
            activeIndex = { activeIndex },
            percent = { percent },
            reduceMotion = reduceMotion,
            onFinish = onFinish,
        )
        HorizontalDivider(color = MaterialTheme.field.hairline)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // BEFORE `verticalScroll`, so this is the size of the WINDOW and not of the content.
                // The content's own height is the thing the scroller measures with an infinite budget
                // and it is not what the reading line is a fraction of.
                .onSizeChanged { metrics.measureViewport(it.height) }
                .verticalScroll(scrollState)
                /*
                 * ── THE SPINE: A TRACK, A FILL AND A NODE, PAINTED AND NOT COMPOSED ──────────────
                 *
                 * Inside the scroll modifier, so this draws in the CONTENT's coordinate space and
                 * travels with it exactly the way the web's spine is a child of the step list rather
                 * than a fixed overlay. The reads of `metrics` and of the scroll position happen in
                 * the draw phase, so a scroll invalidates a repaint of three shapes and nothing else
                 * — no recomposition, no re-layout, no cards measured again.
                 */
                .drawBehind {
                    if (metrics.viewportPx <= 0) return@drawBehind
                    val top = metrics.trackTop()
                    val span = metrics.trackBottom() - top
                    if (span <= 0f) return@drawBehind
                    val travelled = span * metrics.progress()
                    val left = railCentrePx - trackPx / 2f
                    val radius = CornerRadius(trackPx / 2f)
                    drawRoundRect(
                        color = trackColour,
                        topLeft = Offset(left, top),
                        size = Size(trackPx, span),
                        cornerRadius = radius,
                    )
                    if (travelled > 0f) {
                        drawRoundRect(
                            color = spineColour,
                            topLeft = Offset(left, top),
                            size = Size(trackPx, travelled),
                            cornerRadius = radius,
                        )
                    }
                    /*
                     * THE TRAVELLING NODE IS NOT DRAWN UNDER REDUCED MOTION, AND THE FILL STILL IS.
                     * The web makes exactly this cut, and the distinction is worth keeping: a LENGTH
                     * is a state, and a reader who asked for no movement still wants to see how far
                     * along they are. A dot whose only job is to ride is pure motion, and it has no
                     * static counterpart worth drawing.
                     */
                    if (!reduceMotion) {
                        val centre = Offset(railCentrePx, top + travelled)
                        drawCircle(color = haloColour, radius = nodePx + haloPx, center = centre)
                        drawCircle(color = spineColour, radius = nodePx, center = centre)
                    }
                },
        ) {
            Spacer(modifier = Modifier.height(WALK_TOP))

            /*
             * THE TWO ENDS ARE RENDERED OUTSIDE THE LOOP, AND THAT IS A PERFORMANCE DECISION AS WELL
             * AS A STRUCTURAL ONE.
             *
             * Structurally they belong outside: the web's hero and outro are siblings of the step
             * list rather than items in it, they carry no number, and nothing about them is a step.
             *
             * The performance half is less obvious and is worth writing down, because it is the kind
             * of thing that gets "tidied" back. Compose skips a composable whose parameters have not
             * changed — but only if every parameter is stable, and a LAMBDA counts. The hero's button
             * needs [travelTo], which closes over locals of this function and so cannot be memoised;
             * handing that lambda to a row inside the loop would hand a fresh instance to every row
             * on every recomposition of this Column, and the reveal frontier recomposes this Column a
             * dozen times over one read. Eleven cards would then be rebuilt for a button that only
             * one of them draws. Outside the loop it is one row.
             */
            val hero = steps.first()
            WalkthroughRow(
                number = null,
                journeyTotal = journeyTotal,
                railWidth = railWidth,
                bubbleSize = bubbleSize,
                expanded = false,
                revealed = revealAll || revealedThrough >= 0,
                reduceMotion = reduceMotion,
                onMeasured = { height -> metrics.measure(hero.id, height) },
            ) {
                WalkthroughHeroCard(step = hero, onStart = { travelTo(listRange.first) })
            }

            for (index in listRange) {
                val step = steps[index]
                // Keyed by the step's own id rather than by its position in the loop. The list is a
                // compiled-in constant today, so this changes nothing today; it is what stops a
                // card's remembered state — its reveal animation, its expansion — from being
                // inherited by a different step the day one is inserted above it.
                key(step.id) {
                    WalkthroughRow(
                        number = walkthroughStepNumber(step),
                        journeyTotal = journeyTotal,
                        railWidth = railWidth,
                        bubbleSize = bubbleSize,
                        expanded = expandedId == step.id,
                        revealed = revealAll || index <= revealedThrough,
                        reduceMotion = reduceMotion,
                        onMeasured = { height -> metrics.measure(step.id, height) },
                    ) {
                        WalkthroughStepCard(
                            step = step,
                            expanded = expandedId == step.id,
                            reduceMotion = reduceMotion,
                            onToggle = {
                                expandedId = if (expandedId == step.id) null else step.id
                            },
                            onOpen = onOpen,
                        )
                    }
                }
            }

            val outro = steps.last()
            WalkthroughRow(
                number = null,
                journeyTotal = journeyTotal,
                railWidth = railWidth,
                bubbleSize = bubbleSize,
                expanded = false,
                revealed = revealAll || revealedThrough >= steps.lastIndex,
                reduceMotion = reduceMotion,
                onMeasured = { height -> metrics.measure(outro.id, height) },
            ) {
                WalkthroughOutroCard(step = outro, onFinish = onFinish)
            }

            /*
             * Air under the last card, and it is deliberately OUTSIDE the measured rows. The spine
             * ends at the last numbered bubble, so this tail is scrollable distance that the fill has
             * already finished — which is the web's own behaviour: the fill reads 100% with the last
             * step's bottom edge still on screen, not after you have scrolled past it into the outro.
             */
            Spacer(modifier = Modifier.height(WALK_BOTTOM))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The pinned header: the rail's content, laid out along a phone's spare axis
// ---------------------------------------------------------------------------------------------

/**
 * How far along, which step, and the way out — the three facts the web's sticky rail carries, in the
 * only shape a handset has room for.
 *
 * NO LIVE REGION HERE, ON PURPOSE. A `liveRegion` on the readout would have TalkBack interrupt
 * whatever it was reading to announce a new step number every time a card crossed the midline, which
 * on a fling is a dozen interruptions. The position is carried instead on each bubble's own
 * `contentDescription` — "Step 4 of 10" — which is announced when the reader actually arrives at
 * that card, in the traversal order the cards are already in.
 */
@Composable
private fun WalkthroughHeader(
    steps: List<WalkStep>,
    activeIndex: () -> Int,
    percent: () -> Int,
    reduceMotion: Boolean,
    onFinish: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // No fixed height anywhere in here. A 64dp header is 64dp until somebody reads at twice
            // the system font size with the app's own "Larger text" multiplied on top of it, and then
            // it is a clipped header. It wraps instead and the journey gets what is left.
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WalkthroughProgressRing(percent = percent)

        Column(
            modifier = Modifier
                .weight(1f)
                // One TalkBack stop for the three lines, in reading order, rather than three stops
                // for one fact.
                .semantics(mergeDescendants = true) { },
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                "WALKTHROUGH",
                color = MaterialTheme.field.muted,
                style = FieldTextStyles.Eyebrow,
            )
            /*
             * THE ACTIVE STEP, SWAPPED RATHER THAN SUBSTITUTED.
             *
             * The direction is read off the transition itself — a reader scrolling down should see
             * the next step arrive from below — which is why the target state is the INDEX rather
             * than the words: `initialState` and `targetState` are both in scope inside the spec, so
             * no separate "which way was that" state has to be kept.
             */
            AnimatedContent(
                // The one place this reading is unwrapped, and it is inside this composable on
                // purpose — see the note over the parameter list in [WalkthroughJourney].
                targetState = activeIndex(),
                transitionSpec = {
                    val forward = targetState >= initialState
                    val swap = if (reduceMotion) {
                        // A cross-fade at the app's shortest duration, and NOT a `snap()`. Reduced
                        // motion asks for no MOVEMENT rather than for no change at all, and an
                        // instant substitution of a line of text reads as a rendering glitch rather
                        // than as a change. When the DEVICE is the one asking, Compose's own
                        // `MotionDurationScale` collapses this to zero anyway.
                        fadeIn(tween(90)) togetherWith fadeOut(tween(90))
                    } else {
                        val travel = 3
                        (slideInVertically(tween(200)) { height ->
                            if (forward) height / travel else -height / travel
                        } + fadeIn(tween(200))) togetherWith
                            (slideOutVertically(tween(160)) { height ->
                                if (forward) -height / travel else height / travel
                            } + fadeOut(tween(160)))
                    }
                    // No size transform: the readout's two lines change length on every step, and a
                    // header that resizes itself under the reader's thumb is a lurch.
                    swap using null
                },
                label = "walkthrough-active-step",
            ) { index ->
                val step = steps.getOrNull(index) ?: steps.first()
                val number = walkthroughStepNumber(step)
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        /*
                         * DERIVED, NEVER TYPED, AND TWO DENOMINATORS THAT ARE DIFFERENT ON PURPOSE.
                         *
                         * The journey is the numbered steps; the deck is those plus an opening card
                         * and a closing checklist. Numbering the ends as steps would put a reader at
                         * "Step 1 of 12" on a card whose own first sentence says there are ten —
                         * the opening card contradicting itself in the header above it. So a
                         * numbered step says which step it is and the two ends say which page they
                         * are; both answer "where am I, out of how many" and neither claims to be
                         * something it is not. The active step is always a numbered one, so the
                         * second branch is a floor rather than a state anybody reaches.
                         */
                        if (number != null) {
                            "Step $number of ${walkthroughJourney.size}"
                        } else {
                            "Page ${index + 1} of ${steps.size}"
                        },
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                    Text(
                        step.title,
                        display = true,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        // Two lines and then an ellipsis, and the full title is never only here — it
                        // is the heading of the card the reader is looking at while this is on
                        // screen. Truncating a duplicate is not the same as losing a sentence.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        /*
         * SKIP IS ON THE SCREEN AT EVERY POINT OF THE JOURNEY, not only at the end, and it is in one
         * place rather than travelling with the reader. An exit that has to be scrolled to is an exit
         * somebody hunts for on the card where they most want it. It calls the same [onFinish] as
         * Done and as the back gesture, so there is no second behaviour to keep in step — only a
         * label.
         */
        TextButton(onClick = onFinish) { Text("Skip") }
    }
}

/**
 * The progress ring: the rail's own instrument, drawn rather than approximated.
 *
 * EXPLICITLY NOT A `CircularProgressIndicator`. Material's determinate indicator leaves a gap at the
 * top of the track, so a reader who has finished the journey is shown a ring that is VISIBLY not
 * closed — which reads as "almost", on the one card whose whole message is that they are done.
 * Two `drawArc` calls answer the same question with no gap and no apology.
 */
@Composable
private fun WalkthroughProgressRing(percent: () -> Int) {
    val density = LocalDensity.current
    val track = MaterialTheme.field.surface300
    val fill = MaterialTheme.colorScheme.primary
    val diameter: Dp = with(density) { (12.sp.toDp() * 3.6f) }.coerceIn(44.dp, 76.dp)
    val strokePx = with(density) { 5.dp.toPx() }
    // Once, here, and then used three times below. Calling the lambda per use would be three snapshot
    // reads that can in principle disagree, which is a ring, a number and a spoken percentage telling
    // a reader three slightly different things about one position.
    val reading = percent().coerceIn(0, 100)
    val sweep = 360f * (reading / 100f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(diameter)
            /*
             * ONE NODE, DESCRIBED AS WHAT IT IS. `clearAndSetSemantics` because the "42%" inside it is
             * the ring's own label rather than a second thing to read, and `progressBarRangeInfo`
             * because that is the property TalkBack states as a percentage — the drawn arc is
             * therefore ANNOUNCED and not merely painted, which is the difference between a beautiful
             * progress indicator and an accessible one.
             */
            .clearAndSetSemantics {
                contentDescription = "Walkthrough progress"
                progressBarRangeInfo = ProgressBarRangeInfo(reading / 100f, 0f..1f)
            }
            .drawBehind {
                val inset = strokePx / 2f
                val box = Size(size.width - strokePx, size.height - strokePx)
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = box,
                    style = Stroke(width = strokePx),
                )
                if (sweep > 0f) {
                    drawArc(
                        color = fill,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = box,
                        // Round, like the web's `strokeLinecap="round"`, so the arc's leading edge
                        // does not read as a cut.
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
            },
    ) {
        Text(
            "$reading%",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// One row of the journey: the numbered dot, then the card
// ---------------------------------------------------------------------------------------------

/**
 * A step's dot and its card, side by side in the gutter the spine is drawn in.
 *
 * ── THE REVEAL: THE DOT ARRIVES, THEN THE CARD ───────────────────────────────────────────────────
 *
 * Two animations off one `revealed` flag, the second delayed by [WALK_CARD_DELAY_MS]. That ordering
 * is the web's and it is not decoration: the dot is the thing on the spine, so a reader following the
 * line downward sees the line reach a point and the card then arrive at it. Reversed, the card
 * appears next to an empty rail and the dot looks like an afterthought.
 */
@Composable
private fun WalkthroughRow(
    /** The step's position in the journey, or null for the two ends, which wear no dot. */
    number: Int?,
    journeyTotal: Int,
    /** The gutter the spine is drawn down. Decided once in [WalkthroughJourney]; never recomputed. */
    railWidth: Dp,
    bubbleSize: Dp,
    expanded: Boolean,
    revealed: Boolean,
    reduceMotion: Boolean,
    onMeasured: (Int) -> Unit,
    card: @Composable () -> Unit,
) {
    val risePx = with(LocalDensity.current) { WALK_RISE.toPx() }
    val dotReveal by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = WALK_REVEAL_MS),
        label = "walkthrough-dot-reveal",
    )
    val cardReveal by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = if (reduceMotion) {
            snap()
        } else {
            tween(durationMillis = WALK_REVEAL_MS, delayMillis = WALK_CARD_DELAY_MS)
        },
        label = "walkthrough-card-reveal",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            /*
             * MEASURED HERE AND NOT INSIDE THE CARD, and the padding is deliberately to the RIGHT of
             * this callback in the chain so the reported height INCLUDES the gap underneath. Every
             * child of the scrolling Column is one of these rows, so tops accumulate exactly with no
             * arrangement spacing left over for `WalkthroughMetrics.topOf` to forget about. Swap the
             * two modifiers and every card's computed top drifts by 12dp times its index, which on
             * the tenth card is a scroll target 120dp short of the step it names.
             */
            .onSizeChanged { onMeasured(it.height) }
            .padding(bottom = WALK_GAP, end = WALK_EDGE),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.width(railWidth),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (number != null) {
                WalkthroughBubble(
                    number = number,
                    total = journeyTotal,
                    size = bubbleSize,
                    expanded = expanded,
                    reduceMotion = reduceMotion,
                    modifier = Modifier.graphicsLayer {
                        alpha = dotReveal
                        translationY = (1f - dotReveal) * risePx
                    },
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    alpha = cardReveal
                    translationY = (1f - cardReveal) * risePx
                },
        ) {
            card()
        }
    }
}

/**
 * The numbered dot on the spine.
 *
 * ── IT IS NOT DECORATIVE HERE, AND IT IS ON THE WEB ──────────────────────────────────────────────
 *
 * The web marks its bubble `aria-hidden`, and that is right THERE: its cards are `<li>` inside an
 * `<ol>`, so a screen reader announces "list item 3 of 10" out of the STRUCTURE and the painted
 * number would be a second copy of a fact the document already carries. A Compose `Column` carries no
 * such structure. Hiding this dot would therefore take the position with it and leave a TalkBack
 * reader working from a smaller truth than the sighted reader beside them. So the dot is described,
 * once, with the sentence the number means, and it sits immediately before the card it numbers so the
 * two are read in order.
 *
 * `clearAndSetSemantics` rather than a bare description because the "3" inside it would otherwise be
 * a second node reading a bare digit. And this dot is OUTSIDE the card's own clickable node, so the
 * description cannot collide with the card's label the way a merged child's would.
 */
@Composable
private fun WalkthroughBubble(
    number: Int,
    total: Int,
    /** Diameter, derived from the reader's own font scale in [WalkthroughJourney]. Never a constant. */
    size: Dp,
    expanded: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (expanded && !reduceMotion) 1.12f else 1f,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 180),
        label = "walkthrough-bubble-scale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .padding(top = WALK_BUBBLE_TOP)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(size)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clearAndSetSemantics { contentDescription = "Step $number of $total" },
    ) {
        Text(
            "$number",
            display = true,
            color = MaterialTheme.colorScheme.onPrimary,
            // The same unit [size] was derived from, so the box and the digits inside it scale
            // together and a two-digit step cannot outgrow its own bubble.
            fontSize = WALK_BUBBLE_TEXT,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The step card
// ---------------------------------------------------------------------------------------------

/**
 * One step: a head you can press, and a detail panel that opens under it.
 *
 * ── THREE ACCESSIBILITY AFFORDANCES ON THE HEAD, AND ALL THREE ARE LOAD-BEARING ──────────────────
 *
 * `stateDescription` says whether it is open, `onClickLabel` says what pressing it will do, and
 * `Role.Button` says what kind of thing it is. Drop the state description and a TalkBack reader
 * cannot tell an open card from a closed one because the panel below is simply more text in the
 * traversal. Drop the click label and the announcement is the whole merged card followed by
 * "double tap to activate", with no hint that activating it is what reveals the rest.
 *
 * `clickable` and never `selectable`: selection is a set-membership idea and this is a disclosure.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WalkthroughStepCard(
    step: WalkStep,
    expanded: Boolean,
    reduceMotion: Boolean,
    onToggle: () -> Unit,
    onOpen: (NavDestination) -> Unit,
) {
    val facets = remember(step) { walkthroughFacets(step) }
    val parts = remember(step) { walkthroughTitleParts(step.title) }
    val shape = RoundedCornerShape(WALK_CARD_CORNER)
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 180),
        label = "walkthrough-chevron",
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.field.surface50),
        border = BorderStroke(1.dp, MaterialTheme.field.hairline),
        shape = shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .clickable(
                        onClickLabel = if (expanded) {
                            "Hide the detail for this step"
                        } else {
                            "Show the detail for this step"
                        },
                        role = Role.Button,
                        onClick = onToggle,
                    )
                    // The 48dp floor this app puts under every control it thought about.
                    .heightIn(min = 48.dp)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                step.icon?.let { glyph ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.field.brandTile, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            glyph,
                            // DECORATIVE, AND EVERY GLYPH ON THIS SCREEN IS. This is the picture of a
                            // step whose name is the very next thing in the same merged label, so
                            // describing it would cost one label per stop too many. The one glyph in
                            // this card that carries information rather than repeating it is the
                            // chevron, and it is answered by the row's state description instead.
                            contentDescription = null,
                            tint = MaterialTheme.field.onBrandTile,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // A FlowRow and not a Row: at twice the font size the action chip cannot sit
                    // beside a two-word feature name, and a Row would squeeze one of them to nothing
                    // rather than putting the chip on its own line. "Miscellaneous Media · Upload
                    // media" is the one that proves it at default scale on a 360dp handset.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            parts.first,
                            style = FieldTextStyles.CardTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        parts.second?.let { action ->
                            Text(
                                action,
                                style = FieldTextStyles.Badge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .background(MaterialTheme.field.surface200, CircleShape)
                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Text(
                        facets.summary,
                        color = MaterialTheme.field.body,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .graphicsLayer { rotationZ = turn },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.field.muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            /*
             * ── THE DETAIL PANEL ─────────────────────────────────────────────────────────────────
             *
             * `AnimatedVisibility` with `expandVertically` is the web's `height: 0 → auto` inside an
             * `AnimatePresence`, with the clip the height animation needs already inside it. The CARD
             * itself is not clipped by anything this file adds, and does not need to be: Compose's
             * focus and press indication is a state layer drawn INSIDE the control, so the hazard the
             * web guards against — an outline drawn two pixels outside the border box, erased on
             * three sides by a clip — does not exist here.
             *
             * Under reduced motion this collapses to `snap()` and is NOT removed. The panel is
             * content, not decoration; what the preference asks for is that it stop sliding.
             */
            AnimatedVisibility(
                visible = expanded,
                enter = if (reduceMotion) {
                    expandVertically(snap(), expandFrom = Alignment.Top) + fadeIn(snap())
                } else {
                    expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(180))
                },
                exit = if (reduceMotion) {
                    shrinkVertically(snap(), shrinkTowards = Alignment.Top) + fadeOut(snap())
                } else {
                    shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(120))
                },
                label = "walkthrough-detail",
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = MaterialTheme.field.hairline)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.field.surface100,
                                // The panel rounds its own bottom, the way the web's does with
                                // `rounded-b-lg`, so the tinted block stays inside the card's corners
                                // without the card having to clip anything of its own.
                                RoundedCornerShape(
                                    bottomStart = WALK_CARD_CORNER,
                                    bottomEnd = WALK_CARD_CORNER,
                                ),
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        /*
                         * THREE CONDITIONAL SECTIONS, IN THE WEB CARD'S OWN ORDER, and every one of
                         * them asks whether it has anything to draw BEFORE it draws a heading. A
                         * heading over an empty block is the defect the facet cutter's `emptyList()`
                         * contract exists to make impossible, and the two ends of the deck plus any
                         * future step with no form behind it are what exercise it.
                         */
                        if (facets.detail.isNotBlank()) {
                            WalkthroughPanelSection(
                                icon = Icons.Filled.Lightbulb,
                                heading = "Why this step exists",
                            ) {
                                Text(
                                    facets.detail,
                                    color = MaterialTheme.field.body,
                                    fontSize = 14.sp,
                                    lineHeight = 21.sp,
                                )
                            }
                        }

                        if (facets.fields.isNotEmpty()) {
                            WalkthroughPanelSection(
                                icon = Icons.Filled.Checklist,
                                heading = "What the screen asks for",
                            ) {
                                /*
                                 * A CHIP CLOUD AND NOT A BULLET LIST, for the shape of the data.
                                 * Most of these entries are short labels — "Notes", "Phone",
                                 * "Gender" — and a bullet each would run the `tool` step to
                                 * twenty-seven lines for twenty-seven words. A FlowRow packs the
                                 * short ones three and four to a line and lets a long one take a
                                 * full width of its own, which is what the web's `flex flex-wrap`
                                 * does with these same strings.
                                 *
                                 * NOTHING HERE ANIMATES, and the web's version does — its chips
                                 * arrive on a 0.025s stagger. That is not a reduced-motion decision
                                 * (this panel already branches on the preference one level up) but a
                                 * COUNT one: twenty-three of these on the `product` card and
                                 * twenty-seven on `tool`, each an animation started on the frame the
                                 * panel opens. The whole file exists to keep per-frame work off
                                 * these handsets, and a stagger nobody asked for is the last place
                                 * to spend it.
                                 *
                                 * The chip is sized in `sp` and padded in `dp`, so it grows with the
                                 * reader's font scale and reflows instead of clipping — the same
                                 * rule the numbered bubble follows by deriving its diameter rather
                                 * than declaring one.
                                 */
                                val chip = RoundedCornerShape(WALK_CHIP_CORNER)
                                val chipFill = MaterialTheme.field.surface50
                                val chipEdge = MaterialTheme.field.hairline
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    facets.fields.forEach { label ->
                                        Text(
                                            label,
                                            color = MaterialTheme.field.body,
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp,
                                            modifier = Modifier
                                                .background(chipFill, chip)
                                                .border(1.dp, chipEdge, chip)
                                                .padding(horizontal = 10.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }

                        if (facets.watch.isNotEmpty()) {
                            WalkthroughPanelSection(
                                icon = Icons.Filled.WarningAmber,
                                heading = "Watch out for",
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    facets.watch.forEach { note ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(top = 8.dp)
                                                    .size(4.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.primary,
                                                        CircleShape,
                                                    ),
                                            )
                                            Text(
                                                note,
                                                color = MaterialTheme.field.body,
                                                fontSize = 14.sp,
                                                lineHeight = 21.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        step.destination?.let { destination ->
                            WalkthroughOpenButton(destination = destination, onOpen = onOpen)
                        }
                    }
                }
            }
        }
    }
}

/** A heading and its block. Never drawn for an empty block — see [WalkthroughStepCard]. */
@Composable
private fun WalkthroughPanelSection(
    icon: ImageVector,
    heading: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                // Decorative: the heading beside it is the label, and a description here would make
                // TalkBack read the picture of the heading before the heading.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                heading,
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        content()
    }
}

// ---------------------------------------------------------------------------------------------
// The two ends
// ---------------------------------------------------------------------------------------------

/**
 * The opening card: what this is, and a button into step one.
 *
 * The web's pointer-driven radial wash and its GSAP split-headline are deliberately NOT copied.
 * There is no pointer on a handset for the first, and GSAP is not on this classpath for the second —
 * `app/build.gradle.kts` declares no animation library at all, and everything in this file is
 * `androidx.compose.animation`.
 */
@Composable
private fun WalkthroughHeroCard(step: WalkStep, onStart: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.field.brandTile),
        shape = RoundedCornerShape(WALK_CARD_CORNER),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "WALKTHROUGH",
                color = MaterialTheme.field.onBrandTileMuted,
                style = FieldTextStyles.Eyebrow,
            )
            Text(
                step.title,
                display = true,
                color = MaterialTheme.field.onBrandTile,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            )
            Text(
                // The opening card has no caution and needs no summary line: it IS the summary, so it
                // is rendered whole rather than cut at a seam it does not have.
                step.body,
                color = MaterialTheme.field.onBrandTileMuted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(
                    // Primary is too dark to sit ON the brand tile; this is the role that exists for
                    // exactly that, and it is why `FieldTokens` carries it separately (Theme.kt:222).
                    containerColor = MaterialTheme.field.accentOnBrandTile,
                    contentColor = MaterialTheme.field.brandTile,
                ),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Start at step 1") }
        }
    }
}

/**
 * The closing card: the checklist, the whole process in one line, and Done.
 *
 * The recap row is every step as a numbered chip, and like the count on the opening card it is
 * DERIVED from [walkthroughJourney] rather than written out. A hand-written recap is a list that is
 * correct on the day it is typed and silently wrong the day after a step is inserted — which is
 * exactly what the deck this replaces did with its hand-typed title numbers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WalkthroughOutroCard(step: WalkStep, onFinish: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.field.surface50),
        border = BorderStroke(1.dp, MaterialTheme.field.hairline),
        shape = RoundedCornerShape(WALK_CARD_CORNER),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                step.icon?.let { glyph ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.field.brandTile, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            glyph,
                            contentDescription = null,
                            tint = MaterialTheme.field.onBrandTile,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                Text(
                    step.title,
                    style = FieldTextStyles.CardTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                step.body,
                color = MaterialTheme.field.body,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )

            HorizontalDivider(color = MaterialTheme.field.hairline)

            Text(
                "THE WHOLE PROCESS, IN ONE LINE",
                color = MaterialTheme.field.muted,
                style = FieldTextStyles.FieldLabel,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                walkthroughJourney.forEachIndexed { index, journeyStep ->
                    Text(
                        "${index + 1} ${walkthroughTitleParts(journeyStep.title).first}",
                        color = MaterialTheme.field.body,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .background(MaterialTheme.field.surface100, CircleShape)
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }

            Button(
                onClick = onFinish,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Done") }
        }
    }
}
