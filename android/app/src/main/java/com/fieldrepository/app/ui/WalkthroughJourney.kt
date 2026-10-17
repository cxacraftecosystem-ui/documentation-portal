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
 * THE WALKTHROUGH AS A JOURNEY: ONE SCROLL, A SPINE THAT FILLS AS YOU READ, AND A CARD PER STEP.
 *
 * `WalkthroughSteps.kt` holds the words. `WalkthroughScreen.kt` holds the window, the seen-flag and
 * the exits. This file holds the EXPERIENCE — the thing the owner actually asked for when they said
 * the handset's walkthrough "is nowhere as good as the walkthrough on web".
 *
 * ── WHY A DECK OF CARDS COULD NOT BE MADE GOOD ENOUGH ────────────────────────────────────────────
 *
 * What shipped before this was twenty-five pages behind a Next button. Every fact on it was right and
 * it still was not the web's walkthrough, because the web's walkthrough is not a list of facts — it
 * is a JOURNEY you travel down with your thumb, and the travelling is the teaching. A reader on
 * `/guide` sees a purple spine grow beside the cards as they scroll, a dot riding down it, a ring
 * closing in the corner. What they learn from that is not any one step: it is that the steps are IN
 * AN ORDER and that they are somewhere in it. A paged dialog cannot say that. Next is a door closing
 * behind you: you cannot see where you came from, you cannot skim ahead, and "step 14 of 23" is a
 * number you are told rather than a distance you can feel. That is the whole gap, and no amount of
 * better copy on a card closes it.
 *
 * ── WHAT WAS COPIED FROM THE WEB, MECHANIC FOR MECHANIC ──────────────────────────────────────────
 *
 * `frontend/components/guide/GuideJourney.tsx` — the scroll-linked spine: one value driving a fill,
 * a travelling node and a progress readout, none of which re-render React. `GuideStepCard.tsx` — the
 * once-only reveal, the numbered bubble, the expandable detail panel, the chevron. `GuideRail.tsx` —
 * the ring, the hard-rounded percent and the trick that keeps it under a hundred renders.
 *
 * ── AND THE ONE THING THAT WAS DELIBERATELY NOT COPIED: THE RAIL ─────────────────────────────────
 *
 * The web's sticky rail — the 260px column with the ring, the percent, the active-step readout and
 * the clickable step list — is `hidden lg:block`. It does not exist below 1024px. `GuideRail.tsx`
 * says so in its own first sentence: "large screens only — on phones the spine alone carries the
 * reader". So a phone reader on the web gets the hero, the spine, the cards and the outro, and
 * nothing else, and copying the rail onto a 360dp handset would not be parity — it would be
 * inventing a third design neither client has.
 *
 * There is one divergence from that, it is deliberate, and it is written down here rather than left
 * to be discovered. The rail's CONTENT — where am I, out of how many — is not rail furniture; it is
 * the answer to the question this whole surface exists to answer, and the web reader gets it for
 * free from a page header that says "…{N} steps, in the order you do them" sitting above the fold.
 * A dialog has no page header. So one slim, non-scrolling row is pinned at the top of this screen
 * carrying exactly the rail's content and nothing else: the ring, the percent, the step counter, the
 * active step's name and Skip. Laid out horizontally, because a phone's spare axis is horizontal. It
 * is about 64dp of chrome instead of a 260dp column, and it must NOT be allowed to grow a step-list
 * navigator — that is the part of the rail with genuinely no room, and the web's own copy of it has
 * a height ceiling with a comment explaining which five rows it silently swallowed the last time
 * somebody added chrome above it.
 *
 * ── NOTHING HERE AWAITS A REQUEST ────────────────────────────────────────────────────────────────
 *
 * No repository, no coroutine that fetches, no suspend call except the two that scroll. The steps are
 * a compiled-in `listOf` and the icons ship in the APK, because the fortnight this describes happens
 * in courtyards with no bar of signal.
 * ─────────────────────────────────────────────────────────────────────────────────────────────────
 */

// ---------------------------------------------------------------------------------------------
// The rail: one owner of the horizontal axis
// ---------------------------------------------------------------------------------------------

/*
 * `--guide-rail`, IN KOTLIN. The web sets one CSS variable on the step list and derives four things
 * from it: the width of each card's first grid column, and the box the track, the fill and the
 * travelling node are centred in. The comment on `GuideJourney.tsx` records what it replaced —
 * "three independent guesses reconciled by `-translate-x-1/2`" — and that the guesses had already
 * broken in production, every numbered bubble sitting a full half-width right of the spine it was
 * supposed to be on.
 *
 * Compose has no inline-transform trap to fall into, but the one-owner rule is not about transforms:
 * it is about a number that four call sites have to agree on — the gutter every step card leaves on
 * its left, and the x the spine's track, fill and travelling node are all drawn on, which is half of
 * it. The web's own mobile value is 2rem of rail plus a 1rem gap; 44dp is that, and it doubles as the
 * left margin of the screen, so there is no second inset for the two of them to disagree about.
 *
 * WHICH IS WHY THIS CONSTANT IS A FLOOR AND NOT THE FIGURE ITSELF. The bubble's diameter follows the
 * reader's font scale (see [WALK_BUBBLE_TEXT] below), so the gutter has to be able to grow with it.
 * The single owner is therefore the `railWidth` local in [WalkthroughJourney], computed once from this
 * floor and from the bubble and handed to the spine and to every row. Nothing else in this file may
 * work out a rail width of its own, and nothing may go back to reading this constant directly.
 */
private val WALK_RAIL = 44.dp

/*
 * THE NUMBERED BUBBLE, SIZED FROM ITS OWN TEXT RATHER THAN PINNED TO A NUMBER OF DP.
 *
 * An earlier version of this file was a flat `30.dp` under a comment claiming the two-digit steps
 * "still centre inside it at any font scale". They do not, and the arithmetic is worth writing down
 * so nobody flattens it back. `ProvideAppPreferences` multiplies the reader's own system font scale by
 * another 1.125 when "Larger text" is on, so a designer reading at the platform's 2x is really at
 * 2.25x, and 12.sp of digits is then 27dp tall. Two of them are about 32dp wide. A `Modifier.size`
 * hands its child FIXED constraints, so "23" in a 30dp box is not a snug fit — it is measured at 30dp,
 * and with `maxLines = 1` and the default `TextOverflow.Clip` the second digit is cut in half. Step
 * numbers are the one thing on this screen a reader cannot recover from context.
 *
 * So the diameter is derived from the text the way [WalkthroughProgressRing] derives its own, by
 * asking the density what the reader's scale actually is instead of guessing. The multiplier is
 * chosen so that AT THE DEFAULT SCALE THE ANSWER IS EXACTLY [WALK_BUBBLE_MIN] — 12dp times 2.5 is
 * 30dp — which means this changes nothing for the reader who has not touched their font size, and
 * grows only for the reader who has. The ceiling keeps it a bubble rather than a saucer.
 */
private val WALK_BUBBLE_TEXT = 12.sp
private val WALK_BUBBLE_MIN = 30.dp
private val WALK_BUBBLE_MAX = 54.dp

/** How far down its row the bubble sits — the Compose spelling of the web's `mt-6` on the bubble. */
private val WALK_BUBBLE_TOP = 14.dp

/** The spine's track. The web's is `w-1`, four CSS pixels — the 4dp floor every progress indicator keeps. */
private val WALK_TRACK = 4.dp

/** The travelling node's radius, and the halo behind it (the web's `ring-4 ring-purple-100`). */
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
 * and every one of its labels is one line — "Status", "Notes", "Phone". A 360dp handset is not that
 * card. Four of the web's own steps carry section DESCRIPTIONS rather than labels (see
 * [WALKTHROUGH_FIELDS]) and the longest of them runs past a hundred and twenty characters, which at
 * 12sp wraps to four lines here. A fully-rounded shape on a four-line box is a stadium with a 34dp
 * radius — a blob, with the first and last words of the sentence sitting inside the curve.
 *
 * A fixed radius reads correctly at BOTH shapes, which is the property actually needed, so it is the
 * geometry that diverges and never the words. The strings are the web's to the character and a test
 * holds them there; the corner is this screen's own answer to this screen's own width.
 */
private val WALK_CHIP_CORNER = 9.dp

/**
 * The reading line, as a fraction of the viewport, and it is the WEB'S OWN NUMBER.
 *
 * `useScroll({ offset: ["start 65%", "end 65%"] })` — progress begins when the list's top crosses 65%
 * down the viewport and completes when its bottom reaches the same line. `GuideJourney.tsx` records
 * at length that 65% is measured rather than chosen: it is the figure at which the fill reads 100%
 * with the last step's bottom edge still on screen, at every width it was checked at including 360px.
 * The public landing page's "How it works" reuses the identical instrument with the identical number
 * so that both surfaces teach the same gesture, and this is the third.
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
 * It is a SECOND threshold on purpose and it is not interchangeable with [WALK_ACTIVE_BAND].
 * `GuideStepCard.tsx` puts the two on different elements and the skill spells out why: "Merging them
 * breaks one or the other." A middle-band threshold would reveal a card only once you were already
 * reading it; a quarter-visible threshold would latch the active readout on the first card and never
 * move it again.
 */
private const val WALK_REVEAL_AT = 0.25f

/** How far a revealed card rises as it fades in — the web's `riseItem(reduce, 10)`. */
private val WALK_RISE = 10.dp

/** The dot arrives, then the card. See [WalkthroughRow]. */
private const val WALK_REVEAL_MS = 260
private const val WALK_CARD_DELAY_MS = 70

/** A little air above a step when it is scrolled to, so it does not land flush against the header. */
private val WALK_SCROLL_HEADROOM = 12.dp

/**
 * The seam the caution is lifted at: the WORDS "watch out", however the prose punctuates them.
 *
 * ⚠ IT WAS THE LITERAL `"Watch out:"` AND THAT SILENTLY DROPPED TWO CAUTIONS — the two loudest ones
 * in the deck. `WalkStep.body`'s contract names the marker as "the words 'Watch out:'", and twenty-one
 * of the twenty-three journey steps write exactly that. The other two raise their voice instead:
 * "…all on the device. WATCH OUT, BECAUSE THIS ONE COSTS SOMEBODY ELSE'S WORK: a queued CORRECTION to
 * an existing record replaces that record whole" (`offline`) and "…recorded in the earlier steps.
 * WATCH OUT, AND THIS IS THE ONE TO REMEMBER: choosing a record COPIES its values onto this stage"
 * (`design-workshop-stages`). A comma is not a colon, so `indexOf("Watch out:", ignoreCase = true)`
 * answered -1 for both, [walkthroughFacets] handed the card an empty `watch` list, and the card —
 * correctly, by its own rule that a heading is never drawn over an empty block — rendered NO "Watch
 * out for" section at all. The warning did not vanish; it stayed buried in the last third of the
 * "Why this step exists" prose, with no heading, no bullet and no icon over it. On the web both are
 * `watch[]` entries under a triangle.
 *
 * WHAT MAKES THAT WORSE THAN AN ORDINARY LAYOUT BUG is which two steps drew it. Every step's caution
 * is the half a skimmer still takes in; these two are the ones that cost somebody ELSE's fortnight —
 * a queued correction overwriting a colleague's edits with nobody told, and a reference pick copying
 * values that a later edit does not rewrite. And nothing could see it: `WalkthroughStepsTest` asks
 * whether the body `contains("watch out", ignoreCase = true)`, which is true of both, so the suite
 * was green while the section it was standing guard over was not being drawn.
 *
 * SO THE CONTRACT IS THE WORDS AND NOT THE PUNCTUATION. A step may lead with a colon, a comma, a
 * dash or a clause of its own before the colon — whatever it leads with is kept, because
 * "BECAUSE THIS ONE COSTS SOMEBODY ELSE'S WORK: a queued correction…" reads correctly under a
 * "Watch out for" heading and rewriting it to fit a parser would be a machine editing copy. The
 * separators are consumed so a bullet never opens on a stray comma. `\b` on both ends so a body that
 * ever says "watchtower" or "outbox" cannot be mistaken for a seam.
 */
private val WALK_CAUTION_LEAD = Regex("""\bwatch out\b[\s,:;—–-]*""", RegexOption.IGNORE_CASE)

// ---------------------------------------------------------------------------------------------
// The measurements the spine is drawn from
// ---------------------------------------------------------------------------------------------

/**
 * Where every card sits in the scrolling content, and the three answers derived from that.
 *
 * ── WHY THIS IS A CLASS AND NOT FOUR LOCALS ──────────────────────────────────────────────────────
 *
 * Every number here is read from two places that are not composition — a `drawBehind` lambda and a
 * `derivedStateOf` calculation — and the whole performance argument below rests on the reads
 * happening THERE and not in the composable. A remembered object with methods is the shape that makes
 * that hard to get wrong by accident: there is no way to "just read the value" at the call site,
 * because there is no value at the call site.
 *
 * ── THE PERFORMANCE ARGUMENT, WHICH IS THE POINT OF THE WHOLE FILE ───────────────────────────────
 *
 * A scrolling page changes its offset on every frame. If a composable read `scrollState.value` in its
 * own composition, every one of those frames would invalidate it — and on this screen that composable
 * is a Column holding twenty-five cards, so a single flick would recompose twenty-five cards sixty
 * times a second on a handset that has other things to do. `AppNavigation.rememberIslandScrolledPast`
 * already argues this for the island bar and states the payoff in as many words: the read happens
 * inside `derivedStateOf`, which makes the scroll state a dependency of ONE derived value and of
 * nothing else in the tree, and because that value only PUBLISHES when it actually changes, "scrolling
 * a thousand pixels therefore recomposes the bar exactly twice … and recomposes nothing else, ever."
 *
 * That is the Compose spelling of what the web means by "one MotionValue, three consumers, none of
 * them re-render React". The consumers here divide the same way:
 *
 *   the spine's fill and its travelling node   →  read [progress] inside `Modifier.drawBehind`, which
 *                                                 REPAINTS without recomposing or re-laying-out
 *                                                 anything. `MapScreen` makes the identical argument
 *                                                 for its row flash: a repaint of one row, not a
 *                                                 recomposition per frame.
 *   the percent, the ring and the active-step  →  read through `derivedStateOf` which publishes an
 *                                                 Int. There are 101 reachable percentages, so the
 *                                                 header costs at most a hundred recompositions for
 *                                                 the entire page, which is exactly the ceiling the
 *                                                 web buys with its rounded `useMotionValueEvent`.
 *
 * ── AND WHY THE HEIGHTS ARE SNAPSHOTTED RATHER THAN THE POSITIONS ────────────────────────────────
 *
 * `onGloballyPositioned` fires on every scrolled frame, because a scroll moves every card. That makes
 * it the wrong tool for "where does this card sit in the content" and `DwRankableList` rejects it for
 * this exact reason before using it for something else. A card's HEIGHT, on the other hand, changes
 * only when the card changes — when its panel opens, or when the text reflows at a new font scale —
 * so `onSizeChanged` writing a height that is guarded by an equality check writes a handful of times
 * per session and never once during a scroll. Tops are then accumulated from the heights, in order,
 * which is exact because every child of the scrolling Column is one of these rows and each row's
 * measured height already includes the gap under it.
 *
 * The honest cost, which is not a defect and must not be "fixed": opening a card changes the height
 * of the content, so the denominator moves and the fill shifts a little under the reader at that
 * instant. The web has precisely the same property — `useScroll` re-measures its target when the list
 * grows — and the alternative, latching a denominator taken before the expansion, would make the fill
 * report a distance that is no longer there.
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

    /** The content-space y of a card's top edge. Linear in the number of cards; there are 25. */
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
         * that inequality holds with hundreds of dp to spare — the opening card alone is most of a
         * screen — which is why nothing showed. Widen the window and it stops holding: at 800dp the
         * same cards reflow to a third of their height while the viewport gets taller, and a reader
         * who has scrolled to Done is looking at a ring reading about eighty per cent.
         *
         * That is precisely the failure [WalkthroughProgressRing] refuses a `CircularProgressIndicator`
         * over — "a workshop at 100% renders as a ring that is visibly not closed … reads as
         * 'almost'" — arriving through the numerator instead of through the control. And the reading
         * it produces is not merely ugly, it is FALSE: there is nothing left to scroll, so there is
         * nothing left of the journey.
         *
         * `maxValue > 0` guards the only case this would otherwise get wrong, a deck short enough to
         * fit the window with nothing to scroll at all: there `value` and `maxValue` are both zero on
         * the first frame and the ring would open at a hundred per cent. The real deck cannot do that;
         * a preview handed three cards can.
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
     * function of the offsets and the latch, and the latch only ever moves one way — which is the
     * same argument `rememberIslandScrolledPast` makes for keeping its hysteresis in a captured var.
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
// What each screen asks for
// ---------------------------------------------------------------------------------------------

/**
 * The web's `GuideStep.fields` — "the real form labels, in screen order, with (required) marked" —
 * keyed by the step id the two clients already join on.
 *
 * ── THIS IS A COPY, IT IS DELIBERATELY A COPY, AND THE COPY IS PINNED ────────────────────────────
 *
 * Every string below is `frontend/components/guide/steps.ts`'s, carried across verbatim. The web's
 * file is the register: one list, one owner, one place a rename lands. Two machines hold this copy to
 * it. `WalkthroughStepsTest` reads `steps.ts` at runtime and asserts that every step the web teaches
 * hands its card exactly the web's list, in the web's order; and
 * `backend/tests/test_questionnaire_form_contract.py` reads the `questionnaire` entry below and holds
 * it, `steps.ts` and `docs/WALKTHROUGH.md` to the interview form itself.
 *
 * ⚠ SO DO NOT EDIT A STRING HERE TO MATCH AN ANDROID FORM. If a label on this handset genuinely
 * differs, the web is the register and BOTH change, in one commit.
 *
 * ONE STRING LITERAL PER LINE AND NEVER A `+` CONCATENATION, however long the line runs. The Python
 * parser on the other side of the questionnaire join reads every quoted run inside a `listOf(…)` as
 * one entry; two literals joined by `+` would arrive there as two chips.
 *
 * The steps this handset teaches and the web does not — `browse-records`, `offline`, `account` — have
 * no key here, because there is no web register to copy from and a list typed off an Android form
 * would be a second register with nothing holding it to anything. A missing key answers
 * `emptyList()`, and the card draws NO HEADING over an empty block.
 */
private val WALKTHROUGH_FIELDS: Map<String, List<String>> = mapOf(
    "workshop" to listOf(
        "Workshop title (required)",
        "Place (required)",
        "Start and end date",
        "Description",
        "Notes",
        "Linked artisans",
        "Crafts covered",
        "Workshop media",
        "Location (GPS fix or map pin)",
    ),
    "craft" to listOf(
        "Craft name (required)",
        "Local name",
        "Category",
        "Place",
        "Description",
        "Craft media",
    ),
    "artisan" to listOf(
        "Name (required)",
        "Local name",
        "Workshop",
        "Craft (required)",
        "Or new craft name",
        "Place (required)",
        "Gender",
        "Phone",
        "Email",
        "Address",
        "Notes",
        "Do's (required)",
        "Don'ts (required)",
        "Artisan media",
        "Location (GPS fix or map pin)",
    ),
    "product" to listOf(
        "Product name (required)",
        "Local name",
        "Workshop",
        "Product type",
        "Linked craft (fills craft name)",
        "Craft name (required)",
        "Linked artisan (fills artisan + place)",
        "Artisan name (required)",
        "Place (required)",
        "Time taken to complete",
        "Size",
        "Length (inches)",
        "Breadth (inches)",
        "Height (inches)",
        "Cost of making",
        "Selling price",
        "Market demand",
        "Raw materials used",
        "Main tools used",
        "Function or use",
        "Remarks",
        "Product media",
        "Location (GPS fix or map pin)",
    ),
    "process" to listOf(
        "Name of the process (required)",
        "Artisan (required)",
        "Product (required)",
        "Per step: Name of the step (required)",
        "Per step: additional context notes (optional)",
        "Per step: attached media",
    ),
    "tool" to listOf(
        "Toolkit name (required)",
        "Local name",
        "English name",
        "Workshop",
        "Linked crafts (fills craft name)",
        "Craft name (required)",
        "Linked artisans (fills artisan + place)",
        "Artisan name (required)",
        "Place (required)",
        "Process used in",
        "Material",
        "Years in use",
        "Height (cm)",
        "Width (cm)",
        "Length (inches)",
        "Breadth (inches)",
        "Height (inches)",
        "Thickness",
        "Weight",
        "Radius",
        "Maker",
        "Tradition type",
        "Replacement cost",
        "Suggestions for improvement",
        "Remarks",
        "Process stages",
        "Tool media",
        "Location (GPS fix or map pin)",
    ),
    "questionnaire" to listOf(
        "Interview title (required)",
        "Place",
        "Language",
        "Workshop",
        "Questionnaire",
        "Status",
        "Artisans interviewed",
        "Per question: \"Record this question\" audio, or typed answer",
        "Interview notes",
    ),
    "media" to listOf(
        "Capture media — images, video, audio and documents",
        "Media title / object name",
        "Linked record type (required)",
        "Linked entry (optional)",
        "Caption",
        "Location (GPS fix or map pin)",
    ),
    "review" to listOf(
        "Pending — submitted, waiting for a reviewer",
        "Approved — final, counted in the dataset",
        "Needs revision — comments explain what to change",
        "Rejected — not going into the dataset",
    ),
    "view-data" to listOf(
        "By workshop — every record filed under the workshop it was made in (the view it opens on)",
        "By uploader — a workshop's records filed under the researcher who uploaded them",
        "By media type — every file filed by what kind of file it is",
        "Download report (.xlsx)",
        "Download any folder as a zip, with content-type filters",
    ),
)

/**
 * The same register for the OTHER TWO DECKS — and it is the same register, not a second one.
 *
 * A card in the contributor's or the admin's deck that opens a screen the researcher's deck already
 * teaches is handed THAT screen's list, by reference, so one form is described one way whichever deck
 * the reader is in. Step ids are unique across every deck (`WalkthroughStepsTest` asserts it), which
 * is what lets the card look a facet up in one flat map without knowing which deck it came from.
 *
 * Cards about a screen the web walkthrough does not teach — the access roster, the user list, the
 * settings hub — have no entry, for the reason the header above gives.
 */
private val WALKTHROUGH_DECK_FIELDS: Map<String, List<String>> = mapOf(
    "contributor-interview" to WALKTHROUGH_FIELDS.getValue("questionnaire"),
    "contributor-media" to WALKTHROUGH_FIELDS.getValue("media"),
    "contributor-review" to WALKTHROUGH_FIELDS.getValue("review"),
    "admin-workshop" to WALKTHROUGH_FIELDS.getValue("workshop"),
    "admin-craft" to WALKTHROUGH_FIELDS.getValue("craft"),
    "admin-review" to WALKTHROUGH_FIELDS.getValue("review"),
    "admin-view-data" to WALKTHROUGH_FIELDS.getValue("view-data"),
)

/**
 * Every deck's field register, in one lookup.
 *
 * The card asks [walkthroughFacets] for a step's facets and knows nothing about which deck the step
 * came from, which is the property that keeps one card composable drawing every deck. A step id is
 * unique across all decks — `WalkthroughStepsTest` asserts it — so a flat merge cannot resolve one id
 * to two lists.
 */
private val WALKTHROUGH_ALL_FIELDS: Map<String, List<String>> =
    WALKTHROUGH_FIELDS + WALKTHROUGH_DECK_FIELDS

// ---------------------------------------------------------------------------------------------
// The prose, split into the blocks the web's card renders
// ---------------------------------------------------------------------------------------------

/**
 * One step's body, cut into the pieces a card actually lays out.
 *
 * ── WHY THIS IS DERIVED RATHER THAN STORED, TODAY ────────────────────────────────────────────────
 *
 * The web's `GuideStep` carries nine fields and three of them are the ones a card needs to lay out
 * separately: `summary` (the collapsed line), `why` (the panel's prose) and `watch[]` (the cautions).
 * [WalkStep] carries the same material in one string, and the KDoc over [WalkStep.body] states the
 * contract that makes it separable: "four blocks in one paragraph, always in this order … and then —
 * introduced by the words 'Watch out:' — the thing that has actually gone wrong for somebody." That
 * order "is the durable half of the contract between the two clients".
 *
 * So this reads the seam the prose already has rather than inventing one, and it MOVES sentences
 * instead of writing them. Nothing here rewords anything: the summary is the body's own first
 * sentence and the caution is the body's own tail, both verbatim. That matters more than it looks
 * like it does — `WalkthroughSteps.kt` spends a paragraph on the five occasions the web's cautions
 * were written from a neighbour's copy instead of from the source and were therefore false, and a
 * paraphrase generated here would be exactly that failure with a machine doing the paraphrasing.
 *
 * ── AND WHY IT IS THE SEAM RATHER THAN THE DESTINATION ───────────────────────────────────────────
 *
 * When [WalkStep] grows real `summary` and `watch` fields, this function is the one place that has to
 * change and the card above it does not have to change at all. That is the whole reason it exists as
 * a function with a return type rather than as three expressions inlined into the card.
 *
 * ── THE WEB'S `fields[]`: A DECISION THAT WAS MADE HERE AND THEN OVERRULED ───────────────────────
 *
 * This block used to say the web's third panel section had no Android counterpart and must not have
 * one. THE ARGUMENT IS KEPT, because it is what the answer had to be built around and deleting it
 * would let somebody arrive at the version it correctly refused:
 *
 *     "Those are the WEB'S labels in the WEB'S screen order; four of the web's own cards already
 *     cannot obey the rule and carry section descriptions instead; and enumerating twenty-three
 *     Android forms from this file would be copy written from copy, which is the failure both
 *     walkthrough files exist to prevent."
 *
 * WHAT CHANGED IS THAT THE OWNER ASKED FOR THE BLOCK, on 2026-08-31: "walkthrough on phone has same
 * number of cards, but not exactly the same content, the name of the fields in bubble are missing
 * from there, facilitate that as well." They were right. The two decks already matched card for
 * card, in one order, under one set of ids — and the handset's card was a section short of the
 * web's, so a designer who read the guide on a laptop and opened it in a courtyard met a card that
 * had stopped saying what the screen would ask them for.
 *
 * AND THE OLD ARGUMENT DEFEATED EXACTLY ONE IMPLEMENTATION: hand-transcribing twenty-three Android
 * forms into a Kotlin literal and hoping nobody edits one of them. It never touched the other
 * one — carrying the WEB'S register, unchanged, under a guarantee a machine enforces. That is
 * [WALKTHROUGH_FIELDS]. There is still one register and it is still `steps.ts`; this file holds a
 * copy of it rather than a paraphrase of this app's forms; and
 * `backend/tests/test_walkthrough_fields_parity.py` reads both source files and fails naming the
 * step and the string on a one-word difference. The objection was to an UNGUARDED duplicate, and it
 * is answered by the guard rather than by the omission.
 *
 * The last sentence of the old paragraph survives unchanged and now does work in the other
 * direction: the card renders no heading for a block it has nothing to put under, which is what
 * `offline` and the two ends of the deck get, because [WALKTHROUGH_FIELDS] has no key for them.
 */
internal data class WalkthroughFacets(
    /** The collapsed line under the title: what you are doing at this point. One sentence. */
    val summary: String,
    /** The panel's prose: why the dataset needs this, and what the screen will ask for. */
    val detail: String,
    /**
     * The form labels the screen asks for, in screen order, one entry per chip — the web's
     * `GuideStep.fields`, straight out of [WALKTHROUGH_FIELDS].
     *
     * Empty for the two ends of the deck and for `offline`, which teach no screen. Empty means the
     * card draws no heading, exactly as it does for [watch].
     */
    val fields: List<String>,
    /** The cautions, one entry per bullet. Empty for the two ends of the deck, which have none. */
    val watch: List<String>,
)

/**
 * Cut [step]'s body at the two seams its own contract guarantees.
 *
 * The first-sentence cut is deliberately conservative: a full stop only counts if it is followed by a
 * space and a capital, if the word it ends is longer than two letters, and if there is already a
 * sentence's worth of text in front of it. A greedier rule would cut "e.g." in half. There is no
 * "e.g." in any of the twenty-five bodies today — that was checked rather than assumed — and the
 * guard is here so that adding one is not a silent typographical bug in a summary line.
 *
 * If the body has no caution the list comes back empty rather than holding one blank string, because
 * the card decides whether to draw a heading by asking whether the list is empty, and a heading over
 * an empty bullet is the latent defect the web card has and this one must not copy.
 *
 * THE FIELDS ARE LOOKED UP RATHER THAN CUT, and that is the one thing in here that is not a seam in
 * the prose. They are not in [WalkStep.body] to be found: they are the web's register, carried in
 * [WALKTHROUGH_FIELDS] and held to `steps.ts` by a test. This function is still the ONE place a card
 * gets a facet from, which is what keeps the card ignorant of where any of them came from — and a
 * step with no key answers `emptyList()` on the same terms an absent caution does.
 */
internal fun walkthroughFacets(step: WalkStep): WalkthroughFacets {
    val body = step.body.trim()
    // FIRST match and not last: a caution may quote the words again inside itself, and the seam is
    // where the caution STARTS. `find` is that by definition, which is why it is used rather than a
    // hand-rolled scan.
    val marker = WALK_CAUTION_LEAD.find(body)
    val prose = (if (marker != null) body.substring(0, marker.range.first) else body).trim()
    val caution = if (marker != null) body.substring(marker.range.last + 1).trim() else ""
    val cut = walkthroughFirstSentenceEnd(prose)
    return WalkthroughFacets(
        summary = prose.substring(0, cut).trim(),
        detail = prose.substring(cut).trim(),
        fields = WALKTHROUGH_ALL_FIELDS[step.id].orEmpty(),
        watch = if (caution.isEmpty()) emptyList() else listOf(caution),
    )
}

/** The index just past the first sentence of [prose], or its whole length if it has only one. */
private fun walkthroughFirstSentenceEnd(prose: String): Int {
    // Below this a "sentence" is a fragment, and a two-word collapsed line teaches nobody anything.
    // 16, not more: the web's summaries are now one short sentence ("Add the craft being documented.").
    val floor = 16
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
 */
private fun walkthroughTitleParts(title: String): Pair<String, String?> {
    val dot = title.indexOf(" · ")
    if (dot < 0) return title to null
    return title.substring(0, dot).trim() to title.substring(dot + 3).trim().ifEmpty { null }
}

// ---------------------------------------------------------------------------------------------
// Is a screen reader driving?
// ---------------------------------------------------------------------------------------------

/**
 * Whether the reader is exploring by touch — TalkBack and its kind — kept live while this is open.
 *
 * ── WHY A SCROLL-LINKED REVEAL HAS TO ASK THIS, AND THE CLAIM THAT USED TO STAND HERE ────────────
 *
 * The reveal in [WalkthroughRow] fades each card in as it arrives, by writing `alpha` inside a
 * `graphicsLayer` lambda. An earlier version of that comment argued the reveal was therefore safe for
 * a screen reader, on the grounds that the cards "are ALWAYS in the composition and always in the
 * semantics tree" and that alpha "alters how the row is PAINTED and nothing about whether it exists".
 * The first half is true. The second half is false, and it is false in exactly the way that costs a
 * blind reader the page.
 *
 * Compose decides what to tell the accessibility framework about a node with
 * `SemanticsUtils.isVisible`, which is `!isTransparent && !contains(InvisibleToUser)`, and hands the
 * answer to `AccessibilityNodeInfoCompat.setVisibleToUser`. `SemanticsNode.isTransparent` asks its
 * coordinator, and `NodeCoordinator.isTransparent` is, in as many words, "this coordinator has a layer
 * whose last alpha was at or below zero, OR the coordinator that wraps it is transparent". A
 * `graphicsLayer` holding `alpha = 0f` therefore reports NOT VISIBLE TO USER for itself and, because
 * that walk goes up through every parent, for every descendant inside it. TalkBack filters exactly
 * that flag out of its traversal order. So a card that has not been revealed yet is precisely as
 * absent to a screen reader as it would have been under an `AnimatedVisibility` — the very failure the
 * old comment was congratulating itself on avoiding. A sighted reader would have seen a walkthrough of
 * twenty-three steps and a TalkBack reader the two or three that happened to fit the first screen.
 *
 * ── SO THE REVEAL IS SWITCHED OFF RATHER THAN WORKED AROUND ──────────────────────────────────────
 *
 * There is no way to keep the fade and keep the semantics: the transparency test is structural, it
 * reads the layer rather than anything this file could annotate, and floors the alpha at some
 * not-quite-zero value would be a card painted at two percent opacity, which is a lie to the sighted
 * reader in exchange for a truth to the blind one. When somebody is exploring by touch, every row is
 * simply revealed from the start, which is the same branch reduced motion already takes.
 *
 * ── `isTouchExplorationEnabled` AND NOT `isEnabled` ──────────────────────────────────────────────
 *
 * `isEnabled` is true when ANY accessibility service is running, and on a real handset that includes
 * password managers, clipboard tools and automation apps whose users can see the screen perfectly
 * well. Switching the animation off for them would be turning off a feature on a guess. Touch
 * exploration is the narrow question actually being asked here: does the reader reach this content by
 * moving focus through nodes rather than by scrolling with a thumb?
 *
 * ── AND IT IS OBSERVED RATHER THAN SAMPLED ONCE ──────────────────────────────────────────────────
 *
 * [walkthroughReduceMotion] reads its setting once per `Context` and says why that is the right trade:
 * an IPC per frame is not worth a page turn, and nobody changes their animator scale mid-dialog. This
 * one is the opposite case and must not copy that shape. TalkBack has a volume-key shortcut and is
 * routinely turned on WHILE an app is open — by the one reader for whom a stale answer here means a
 * walkthrough with three steps in it. So a listener is registered for as long as the composable is
 * alive, and removed with it.
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
 * The whole walkthrough as one scrolling journey: a hero, a numbered card per step against a
 * scroll-linked spine, and a closing card.
 *
 * ── THE ENTRY POINT, AND WHAT THE CALLER STILL OWNS ──────────────────────────────────────────────
 *
 * The window, the dismissal and the seen-flag stay in [WalkthroughDialog] — this composable draws and
 * nothing else, so it can be dropped into any container that gives it a size. [onFinish] and [onOpen]
 * are handed straight through and are the same single pair of lambdas the paged version had: there is
 * still exactly one way out of this screen and it still writes the flag, which is the property that
 * stops the walkthrough reappearing tomorrow for somebody who dismissed it today.
 *
 * ── IT TAKES A DECK AND IT USED TO TAKE A LIST, AND THE OLD SIGNATURE WAS A LIE ───────────────
 *
 * This parameter was `steps: List<WalkStep>` under a sentence claiming it was handed in "rather than
 * read from the top-level `val` so that a preview or a test can render three of them". That was true
 * of the SIGNATURE and false of the BODY. Four things inside read the designer's `walkthroughJourney`
 * global directly — the progress meter's denominator, the header's "Step n of m", the step numbers
 * themselves, and the outro's recap chips — so a second deck passed in here would have rendered with
 * every card unnumbered, a meter denominated in somebody else's twenty-three, and THE DESIGNER'S
 * TWENTY-THREE RECAP CHIPS ON ITS CLOSING CARD. Nothing would have crashed; it would simply have been
 * the wrong deck's arithmetic, on the one surface whose whole subject is "where am I, out of how
 * many". Taking the [WalkthroughDeck] rather than its `steps` is what makes that unrepresentable
 * instead of merely avoided, because the journey and the deck now arrive together and neither can be
 * fetched from a global.
 *
 * @param deck the walkthrough being read: its opening card, its numbered journey, its closing card.
 * @param decks every deck this build carries, for the switcher on the opening card. A DEFAULT AND NOT
 *   A GATE — see [walkthroughDeckFor]: the role picks which deck opens and takes nothing away, so
 *   every other deck has to be one tap from the first card or this client is narrower than the web's,
 *   which reaches all three from a switcher on an ungated page.
 * @param onChooseDeck the reader picked another deck. The caller swaps it; this composable does not
 *   hold the choice, for the same reason it does not hold the seen flag — one owner per piece of
 *   state, and the window is the thing that outlives a scroll position.
 * @param reduceMotion read ONCE by the caller and threaded down. Do not re-read it per card: the
 *   answer involves a `ContentResolver` round trip to the settings provider, and twenty-five cards
 *   asking the same question twenty-five times is twenty-four IPCs for one Boolean.
 * @param onOpen leave for a step's screen. The caller closes the walkthrough and routes through the
 *   unsaved-changes guard; this composable only says which destination was pressed.
 * @param onFinish close the walkthrough and mark it seen.
 */
@Composable
internal fun WalkthroughJourney(
    deck: WalkthroughDeck,
    decks: List<WalkthroughDeck>,
    onChooseDeck: (WalkthroughDeck) -> Unit,
    reduceMotion: Boolean,
    onOpen: (NavDestination) -> Unit,
    onFinish: () -> Unit,
) {
    val steps = deck.steps
    // Three is the smallest shape this draws: an opening card, at least one numbered step, a closing
    // card. The real deck is twenty-five and this cannot fire; the guard is so that a preview handed
    // a two-card stub renders nothing rather than indexing off the end of the list.
    if (steps.size < 3) return

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    /*
     * THE NUMBERED PART OF THE DECK: everything except the opening and closing cards.
     *
     * `walkthroughStepNumber` is the one place a step number may come from and it answers null for
     * the two ends, so the range is derived from the list's own shape rather than from the constant
     * 1..23 — insert a step and this follows, which is the same rule the titles obey by carrying no
     * number of their own. The coercion is for a deck too short to have ends, which cannot happen
     * with the real list and would otherwise be an index crash in a preview.
     */
    val listRange = remember(steps) { 1..(steps.size - 2).coerceAtLeast(1) }

    // The denominator every "Step n of m" on this screen counts against, taken from THIS DECK's
    // journey and never from its deck. The two are different numbers on purpose: numbering the
    // opening and closing cards as steps would put a reader at "Step 1 of 25" on a card whose own
    // first sentence says there are twenty-three of them.
    //
    // ⚠ AND IT IS `deck.journey` RATHER THAN THE `walkthroughJourney` GLOBAL IT READ UNTIL THE
    // SECOND DECK LANDED. That global is the designer's twenty-three; read here it would have
    // denominated the inspector's three-step deck in it, so a reader on the inspector's last card
    // would have watched a progress ring stop at thirteen per cent with nothing left to scroll.
    val journeyTotal = deck.journey.size

    /*
     * THE HORIZONTAL AXIS, DECIDED ONCE, HERE, AND HANDED TO EVERYTHING THAT DRAWS ON IT.
     *
     * This is the `--guide-rail` rule in [WALK_RAIL] actually being kept. The bubble's diameter grows
     * with the reader's font scale (see [WALK_BUBBLE_TEXT]), so the gutter it sits in has to grow with
     * it or a 54dp bubble ends up straddling a 44dp column and lying across the card beside it. Both
     * numbers are therefore computed in this one place: the spine's centre is `railWidth / 2`, every
     * row's gutter is `railWidth`, and every bubble is `bubbleSize`. There is no second opinion about
     * either anywhere in the file, which is the whole point — a rail whose width is worked out twice
     * is a spine that misses its dots the first time somebody changes one of them.
     *
     * Both are pure functions of `density`, which is also the [WalkthroughMetrics] key below, so a
     * font-scale change (which this activity handles WITHOUT being recreated) rebuilds the geometry
     * and the measurements together rather than leaving one describing the other's old layout.
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
     * keeps a scroll off the recomposer — see the class KDoc above.
     *
     * ⚠ AND WHERE EACH ONE IS *READ* IS AS LOAD-BEARING AS THE `derivedStateOf` AROUND IT. This
     * paragraph used to end "None of them is read by the scrolling Column itself; they are read by the
     * pinned header and by the cards' own reveal", and two thirds of that was false in a way nothing
     * on screen shows. `Column` is an INLINE composable, so its content lambda has no restart scope of
     * its own: the body below is part of THIS function's scope, twenty-five rows and all. Passing
     * `percent` and `activeIndex` as Int arguments to [WalkthroughHeader] therefore performed both
     * snapshot reads HERE, which made a scroll invalidate the scope that emits every card. The cost
     * was not a redraw: `percent` has 101 reachable values and `activeIndex` twenty-three, so one pass
     * down the journey rebuilt this Column's modifier chain and re-issued all twenty-five (skippable)
     * row calls about a hundred and twenty times — and on a fling those hundred and twenty land inside
     * the second or so the fling lasts, which is one to three per FRAME on the SM-M325F this fleet
     * actually carries. That is precisely the recomposition-per-scroll-frame this whole file was
     * written to avoid, arrived at through the front door.
     *
     * So the two header readings are handed down as `() -> Int` and read inside the composables that
     * print them. The reader is not deferred for elegance; it is deferred so that the SCOPE that
     * changes is the ring and the readout, which is what the web buys with its rounded
     * `useMotionValueEvent` and its `useTransform`-ed ring.
     *
     * `revealedThrough` STAYS READ HERE, and that is not an oversight. A row's reveal is a
     * `targetValue` handed to `animateFloatAsState`, which has to be read in composition to start an
     * animation at all — there is no draw-phase spelling of "this card has arrived". It publishes at
     * most twenty-five times for the whole page, one per card, which is exactly what the web pays for
     * the same effect, and it is short-circuited away entirely by `revealAll` below.
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
     * `rememberSaveable` for the reason the paged version needed it: `AndroidManifest.xml` declares
     * `configChanges` for orientation, screenSize, uiMode, density and fontScale, so a rotation, a
     * theme change and a font-size change never recreate this activity and never touched this state.
     * What recreates it is the system reclaiming a backgrounded app, and on the handsets this
     * fortnight runs on that is not an edge case — a designer twenty cards down puts the phone down
     * to photograph something and the camera takes the memory. The scroll position survives the same
     * event for free, because `rememberScrollState` is `Saver`-backed; between them the reader comes
     * back to the paragraph they were reading rather than to the top.
     *
     * An id rather than an index for the same reason `walkthroughStepNumber` matches on id: a saved
     * index is a card that quietly becomes a different card the day a step is inserted above it.
     *
     * The first step is open on arrival, and the web says why in its own words: "so the shape of a
     * step is obvious without the reader having to discover that the cards expand".
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
     * scrolls to where the step USED to be. The web does the same thing for the same reason and says
     * so: `requestAnimationFrame(() => scrollToStep(hash, true))`, "one frame so the expanded card has
     * its final height".
     *
     * ── AND THE SCROLL ITSELF BRANCHES ON REDUCED MOTION ─────────────────────────────────────────
     *
     * `scrollTo` under the preference, `animateScrollTo` otherwise. SMOOTH SCROLLING IS MOTION: it is
     * a screenful of text sliding under somebody who asked for nothing to slide. `guideMotion.ts`
     * gates its own `scrollIntoView` behaviour on exactly this and the page's start button exists "for
     * exactly one reason: `onStart` must jump instantly". `DwMediaCarousel` already makes the same
     * split on this side, in the same words.
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
     * ONE PRESS LEAVES. Back closes the walkthrough whether or not a step is open; a step collapses
     * by tapping it. It used to collapse the open step first, and since the first step is open on
     * arrival that made a first-run reader press twice to leave a screen they never touched (release
     * smoke 2026-10-17). What it leaves to is the reason this is a dialog: the screen underneath was
     * never unmounted, so closing reveals it — the dashboard on first run, whatever form the
     * researcher re-opened this from otherwise. There is no stack to pop and no destination to
     * compute, which rules out a back press finishing the activity and dropping somebody onto the
     * launcher. It goes through [onFinish] like every other exit, so the flag is written whichever
     * way somebody leaves.
     */
    BackHandler(enabled = true) { onFinish() }

    /*
     * WHEN NOTHING WAITS TO BE REVEALED, AND THE TWO VERY DIFFERENT REASONS FOR IT.
     *
     * Reduced motion is the obvious half: the reveal is an animation and the reader asked for none, so
     * every card is simply there. `DwGalleryFloor` states the rule this obeys — nothing on this screen
     * may exist ONLY as motion — and a card is content, so what the preference removes is the fade,
     * never the card.
     *
     * The screen-reader half is not a preference at all, it is a correctness requirement, and
     * [walkthroughScreenReaderActive] carries the whole argument: a `graphicsLayer` at `alpha = 0f`
     * reports `isVisibleToUser = false` for itself and every descendant, and TalkBack drops exactly
     * those nodes from its traversal. Revealing everything up front is what keeps the walkthrough
     * twenty-three steps long for a reader who never scrolls it with a thumb.
     *
     * They are ORed into one value rather than checked separately at the three call sites below,
     * because "is this row revealed" must have one answer and three copies of a two-term condition is
     * how one of them later loses a term.
     */
    val revealAll = reduceMotion || walkthroughScreenReaderActive()

    val trackColour = FieldProgressDefaults.trackColor
    val spineColour = MaterialTheme.colorScheme.primary
    val haloColour = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    val trackPx = with(density) { WALK_TRACK.toPx() }
    val railCentrePx = with(density) { (railWidth / 2).toPx() }
    val nodePx = with(density) { WALK_NODE.toPx() }
    val haloPx = with(density) { WALK_NODE_HALO.toPx() }

    Column(modifier = Modifier.fillMaxSize()) {
        WalkthroughHeader(
            deck = deck,
            // Lambdas and not values: see the note over the derived readings. Reading either of these
            // HERE would put the scroll back in the recompose scope that emits all twenty-five rows.
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
                 * — no recomposition, no re-layout, no twenty-five cards measured again.
                 *
                 * The web builds this from a full-height element scaled with `scaleY` rather than
                 * from an animated height, for the same reason: one composited operation per frame.
                 * Here the equivalent is arithmetic in the draw scope, which is cheaper still.
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
                     * THE TRAVELLING NODE IS NOT DRAWN UNDER REDUCED MOTION, and the fill still is.
                     * The web makes exactly this cut — `{reduce ? null : <motion.span …/>}` — and the
                     * distinction is worth keeping: a LENGTH is a state, and a reader who asked for
                     * no movement still wants to see how far along they are. A dot whose only job is
                     * to ride is pure motion, and it has no static counterpart worth drawing.
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
             * handing that lambda to a row inside the loop would hand a fresh instance to all
             * twenty-five rows on every recomposition of this Column, and the reveal frontier
             * recomposes this Column two dozen times over one read. Twenty-four cards would then be
             * rebuilt for a button that only one of them draws. Outside the loop it is one row.
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
                WalkthroughHeroCard(
                    step = hero,
                    deck = deck,
                    decks = decks,
                    onChooseDeck = onChooseDeck,
                    onStart = { travelTo(listRange.first) },
                )
            }

            for (index in listRange) {
                val step = steps[index]
                // Keyed by the step's own id rather than by its position in the loop. The list is a
                // compiled-in constant today, so this changes nothing today; it is what stops a card's
                // remembered state — its reveal animation, its expansion — from being inherited by a
                // different step the day one is inserted above it.
                key(step.id) {
                    WalkthroughRow(
                        number = walkthroughStepNumber(deck, step),
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
                WalkthroughOutroCard(step = outro, journey = deck.journey, onFinish = onFinish)
            }

            /*
             * Air under the last card, and it is deliberately OUTSIDE the measured rows. The spine
             * ends at the last numbered bubble, so this tail is scrollable distance that the fill has
             * already finished — which is the web's own behaviour: "the fill reads 100% with the last
             * step's bottom edge still on screen", not after you have scrolled past it into the outro.
             */
            Spacer(modifier = Modifier.height(WALK_BOTTOM))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The pinned header: the rail's content, laid out along a phone's spare axis
// ---------------------------------------------------------------------------------------------

/**
 * Where you are, out of how many — pinned above the journey and never scrolling with it.
 *
 * ── THERE IS NO LIVE REGION ON THIS, AND THAT IS A CHANGE FROM THE PAGED VERSION ─────────────────
 *
 * The dialog this replaces put `liveRegion = Polite` on its counter, and that was right THERE: a page
 * turn was the only result of pressing Next, so without an announcement a TalkBack user pressed a
 * button and heard nothing at all. Nothing on this screen is like that. The readout changes because
 * the READER scrolled, roughly two dozen times per pass, and announcing every change would interrupt
 * a screen-reader user two dozen times a page to tell them what they just did. The web reached the
 * same conclusion for the same node and its own accessibility contract states it: "There is
 * deliberately no `aria-live` on the rail … announcing it would interrupt a screen-reader user ten
 * times per page for information they already have." The information is not dropped, it is moved to
 * where a reader meets it: every numbered bubble carries "Step n of m" as its own description, so a
 * TalkBack reader is told the position of each step as they arrive at it, and the ring below reports
 * the same progress as a proper progress semantic when they focus it. That is one announcement per
 * step, attached to the step, instead of two dozen from a node nobody is looking at.
 */
/*
 * ── WHY THE TWO SCROLL READINGS ARRIVE AS LAMBDAS ────────────────────────────────────────────────
 *
 * @param activeIndex which numbered step the reader is on. Read HERE, in this composable's own
 *   restart scope, rather than by the caller: [WalkthroughJourney] emits the twenty-five-row Column
 *   from the same scope it would have read it in (`Column` is inline and has no scope of its own), so
 *   an Int parameter would put a scroll-driven value in the recompose scope of every card on screen.
 *   Twenty-three changes for the whole page land on this Row instead.
 * @param percent the journey's progress. Not read here at all — it is handed straight down to
 *   [WalkthroughProgressRing], which is the only thing that prints it, so its hundred-and-one changes
 *   recompose one ring and nothing else. Do not "simplify" either of these back to a value.
 */
@Composable
private fun WalkthroughHeader(
    deck: WalkthroughDeck,
    activeIndex: () -> Int,
    percent: () -> Int,
    reduceMotion: Boolean,
    onFinish: () -> Unit,
) {
    // The deck being read, unpacked once. It arrives as the deck rather than as its `steps` because
    // the readout below needs BOTH halves — which card the reader is on, and how many numbered steps
    // that card's own deck has — and handing down only the first is how the denominator came to be
    // fetched from a global belonging to a different deck.
    val steps = deck.steps

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
             * The web's rail does this with `AnimatePresence mode="wait"` around one keyed child, so
             * the outgoing label finishes leaving before the incoming one arrives; `AnimatedContent`
             * is the same instrument. The direction is read off the transition itself — a reader
             * scrolling down should see the next step arrive from below — which is why the target
             * state is the INDEX rather than the words: `initialState` and `targetState` are both in
             * scope inside the spec, so no separate "which way was that" state has to be kept.
             */
            AnimatedContent(
                // The one place this reading is unwrapped, and it is inside this composable on
                // purpose — see the note over the parameter list.
                targetState = activeIndex(),
                transitionSpec = {
                    val forward = targetState >= initialState
                    val swap = if (reduceMotion) {
                        // A cross-fade at the app's shortest duration, and NOT a `snap()`. Reduced
                        // motion asks for no MOVEMENT rather than for no change at all, and an
                        // instant substitution of a line of text reads as a rendering glitch rather
                        // than as a change. This is the call `DwMediaCarousel` makes and the one the
                        // paged walkthrough made before it; keeping the number identical is the point.
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
                    // header that resizes itself under the reader's thumb is the lurch the paged
                    // version refused for the same reason.
                    swap using null
                },
                label = "walkthrough-active-step",
            ) { index ->
                val step = steps.getOrNull(index) ?: steps.first()
                val number = walkthroughStepNumber(deck, step)
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        /*
                         * DERIVED, NEVER TYPED, AND TWO DENOMINATORS THAT ARE DIFFERENT ON PURPOSE.
                         *
                         * A deck's journey is its numbered steps; the deck is those plus an opening
                         * card and a closing checklist. Numbering the ends as steps would put a
                         * reader at "Step 1 of 25" on a card whose own first sentence says there are
                         * twenty-three — the opening card contradicting itself in the header above
                         * it. So a numbered step says which step it is and the two ends say which
                         * page they are; both answer "where am I, out of how many" and neither
                         * claims to be something it is not. The active step is always a numbered
                         * one, so the second branch is a floor rather than a state anybody reaches.
                         *
                         * ⚠ BOTH NUMBERS BELONG TO THE DECK IN FRONT OF THE READER. The first one
                         * read the designer's `walkthroughJourney` global, which is right for
                         * exactly one of the decks this app carries and silently wrong for the
                         * other — "Step 2 of 23" printed over a deck with three cards in it.
                         */
                        if (number != null) {
                            "Step $number of ${deck.journey.size}"
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
 * ── DRAWN, AND NOT A `CircularProgressIndicator` ─────────────────────────────────────────────────
 *
 * `WorkshopListScreen`'s completeness ring already refused that control and wrote down the reason:
 * "that control leaves a gap at the top for its indeterminate animation, so a workshop at 100%
 * renders as a ring that is visibly not closed — which reads, on a list a designer is scanning to
 * decide what is finished, as 'almost'." A walkthrough that reads 99-and-a-bit at the end of itself
 * has the same problem. Two arcs from twelve o'clock with a round cap is the shape, and it is copied
 * from there rather than shared with it, because that file belongs to the design-workshop screens and
 * a helper lifted across for a second caller is a helper both of them then have to agree about.
 *
 * ── THE RING IS SIZED IN sp, WHICH LOOKS WRONG AND IS NOT ────────────────────────────────────────
 *
 * There is a number inside it. A box sized in dp with text sized in sp is a box the text outgrows the
 * moment the reader turns their font size up — and this app multiplies the system font scale by
 * another 1.125 of its own when "Larger text" is on, so it gets there sooner than most. Converting a
 * text size to dp asks the density what the reader's scale actually is instead of guessing, which is
 * the same move `rememberIslandWidths` makes when it measures the nav bar's words rather than pricing
 * them at "~7.5dp a character". The clamp keeps it a ring rather than a dinner plate.
 *
 * ── AND THE READING ARRIVES AS A LAMBDA, WHICH IS THE WHOLE OF THIS SCREEN'S SCROLL BUDGET ───────
 *
 * @param percent the journey's progress, unwrapped INSIDE this composable and nowhere above it. It is
 *   a scroll-driven value with a hundred and one reachable states, and the scope that reads it is the
 *   scope that recomposes: read here, that is this Box and the one `Text` in it; read one level up in
 *   [WalkthroughHeader] it would also be the header Row, and two levels up in [WalkthroughJourney] it
 *   would be the twenty-five-row Column, because `Column` is inline and shares its caller's scope.
 *   This is the Compose spelling of the web rail's `useMotionValueEvent` + hard-rounded `setPercent`,
 *   whose own comment gives the same ceiling in the same words: "at most 100 times across the whole
 *   page rather than once per scroll frame".
 */
@Composable
private fun WalkthroughProgressRing(percent: () -> Int) {
    val density = LocalDensity.current
    val track = FieldProgressDefaults.trackColor
    val fill = FieldProgressDefaults.indicatorColor
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
 * The web reveals a card once, when a quarter of it has come into view, and staggers four children
 * inside it — icon tile, label row, summary, chevron — which its own comment calls "the reading
 * order, enforced in time". This does the same thing one level up: the numbered dot fades and rises
 * first and the card follows [WALK_CARD_DELAY_MS] later, which is the reading order of a row rather
 * than of a card and costs two animations per step instead of five. Twenty-five cards times five
 * `graphicsLayer` nodes is a real bill on a mid-range handset for a forty-millisecond difference
 * nobody has ever described.
 *
 * ── THE REVEAL IS PAINT, WHICH BUYS A GREAT DEAL AND DOES NOT BUY ACCESSIBILITY ──────────────────
 *
 * Alpha and a ten-dp offset, both written inside a `graphicsLayer` lambda. That keeps the whole thing
 * off the layout pass — they are properties of the layer, so nothing is measured again while it plays
 * — and it keeps every card in the composition, where an `AnimatedVisibility` would have removed the
 * ones below the fold outright.
 *
 * IT DOES NOT, HOWEVER, KEEP THEM VISIBLE TO A SCREEN READER, and an earlier version of this comment
 * claimed that it did. `NodeCoordinator.isTransparent` treats a layer at `alpha <= 0f` as transparent
 * and propagates that up through every parent, and Compose turns it straight into
 * `setVisibleToUser(false)`, which is the flag TalkBack filters its traversal on. An unrevealed card
 * is therefore just as unreachable as a removed one. That is not worked around here; it is switched
 * off at the source — [walkthroughScreenReaderActive] makes `revealed` true for every row the moment
 * somebody is exploring by touch, so this animation simply does not run for the reader it would have
 * cost. The full argument, including why floors and fudges were rejected, is on that function.
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
             * this callback in the chain so the reported height includes the gap underneath. Every
             * child of the scrolling Column is one of these rows, so tops accumulate exactly with no
             * arrangement spacing left over for the arithmetic to forget about.
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
 * `GuideStepCard.tsx` marks its bubble `aria-hidden`, and that is right there: the cards are `<li>`
 * inside an `<ol>`, so a screen reader announces "list item 3 of 19" out of the STRUCTURE and the
 * painted number would be a second copy of a fact the document already carries. A Compose `Column`
 * carries no such structure. Hiding this dot would therefore take the position with it and leave a
 * TalkBack reader working from a smaller truth than the sighted reader beside them — which is the
 * rule `DwGalleryFloor` states for its own counts. So the dot is described, once, with the sentence
 * the number means, and it sits immediately before the card it numbers so the two are read in order.
 *
 * `clearAndSetSemantics` rather than a bare description because the "3" inside it would otherwise be
 * a second node reading a bare digit. And this dot is OUTSIDE the card's own clickable node, so the
 * description cannot collide with the card's label the way a merged child's would.
 *
 * The scale on expansion is the web's `animate={{ scale: expanded && !reduce ? 1.12 : 1 }}` and it is
 * pinned to 1 under reduced motion, where the panel opening under it is the thing that says which
 * card is open.
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
 * ── ONE CARD OPEN AT A TIME, AND THE FIRST OPEN ON ARRIVAL ───────────────────────────────────────
 *
 * Both are the web's behaviour and the second is the one that matters: a reader who arrives at a page
 * of twenty-three shut cards has to DISCOVER that they open, and a shape that has to be discovered is
 * a shape most people never see.
 *
 * ── WHAT ANSWERS A PRESS, IN THE THREE PLACES IT HAS TO BE ANSWERED ──────────────────────────────
 *
 * `stateDescription` says "Expanded" / "Collapsed"; `onClickLabel` says what the press will DO; and
 * [Role.Button] says what kind of thing this is. The chevron says none of the three to somebody who
 * cannot see it, which is why it is decoration on top of them rather than instead of them —
 * `DwPanelDisclosureHeader` had this exact argument out three cards at a time and it is the app's
 * settled position. `clickable` and never `selectable`: a disclosure is not a choice among options,
 * and "selected / not selected" tells a reader nothing about the fact that pressing reveals something.
 *
 * The web's equivalent is a conditional `aria-controls` — pointed at the panel only while the panel
 * is in the document, because "pointing at an id that is not there is worse than not pointing". There
 * is no `aria-controls` in Compose and none is invented here: the state description carries the same
 * fact and carries it in both states.
 *
 * ── THE PANEL'S THREE BLOCKS, IN THE WEB'S OWN ORDER ─────────────────────────────────────────────
 *
 * "Why this step exists", then "What the screen asks for", then "Watch out for", then the door.
 * `GuideStepCard.tsx` lays them out in exactly that sequence and the sequence is the lesson: why you
 * are here, what you will be asked, what goes wrong. The middle block landed on 2026-08-31 — see the
 * overruled paragraph over [WalkthroughFacets] — and it goes in the MIDDLE rather than on the end,
 * because a caution read before the form it is about is a caution about nothing.
 *
 * ── AND THE HEADINGS ARE CONDITIONAL ─────────────────────────────────────────────────────────────
 *
 * A section is drawn only if it has something in it. The web's card renders "What the screen asks
 * for" and "Watch out for" unconditionally over `.map` calls that can both be empty, which is a
 * heading with nothing under it waiting to happen on the first step that has no caution. Two ends of
 * this deck genuinely have none — and `offline`, which teaches a behaviour rather than a screen, has
 * no fields, so it is the third card that would otherwise have drawn one.
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
                            /*
                             * DECORATIVE, AND EVERY GLYPH ON THIS SCREEN IS. This is the picture of a
                             * step whose name is the very next thing in the same merged label, and
                             * `DwMediaCarousel` records what naming both costs: "one label per stop
                             * too many". The one glyph in this card that carries information rather
                             * than repeating it is the chevron, and it is answered by the row's state
                             * description instead of by a description of its own.
                             */
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
                    // rather than putting the chip on its own line.
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
             * The web animates `height: 0 → auto` inside an `AnimatePresence` and clips the panel —
             * only the panel — because the height animation needs it. `AnimatedVisibility` with
             * `expandVertically` is the same thing with the clip already inside it, which is why the
             * CARD is not clipped by anything this file adds: the web's rule about that exists
             * because its focus ring is an outline drawn two pixels OUTSIDE the border box and
             * clipping the card would erase it on three sides. Compose's own focus and press
             * indication is a state layer drawn inside the control, so the same hazard does not
             * exist here, and the card's own Material surface clip is what keeps a ripple from
             * painting over a rounded corner.
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
                                 * Most of these entries are short labels — "Status", "Notes",
                                 * "Phone" — and a bullet each would run the `tool` step to thirty
                                 * lines for thirty words. A FlowRow packs the short ones three and
                                 * four to a line and lets a long one take a full width of its own,
                                 * which is what the web's `flex flex-wrap` does with these same
                                 * strings.
                                 *
                                 * NOTHING HERE ANIMATES, and the web's version does — its chips
                                 * arrive on a 0.025s stagger. That is not a reduced-motion decision
                                 * (this panel already branches on the preference one level up) but a
                                 * count one: twenty-five of these on the `product` card and thirty
                                 * on `tool`, each an animation started on the frame the panel opens,
                                 * on the SM-M325F this fleet actually carries. The whole file exists
                                 * to keep per-frame work off that handset, and a stagger nobody
                                 * asked for is the last place to spend it.
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
 * The opening band.
 *
 * The web's hero is a dark section with a pointer-driven radial wash and a GSAP headline that splits
 * itself into words. NEITHER IS COPIED, and neither is an omission. There is no pointer on a handset,
 * so the wash has nothing to follow and its own author says it is the only pointer-position effect in
 * the whole guide; and GSAP is not on this classpath, exists on the web for exactly one animation, and
 * adding an animation library to an APK to fade in seven words would be the trade in reverse. What
 * carries over is what the band is FOR: the dark brand tile, the eyebrow, the count that is derived
 * rather than typed, and a button that starts the journey.
 *
 * ── AND, SINCE THERE ARE TWO DECKS, THE SWITCHER ─────────────────────────────────────
 *
 * `tracks.ts` is explicit that a role-chosen deck is "a DEFAULT, NOT A GATE": every deck stays in the
 * bundle for everybody and the page's switcher reaches all of them, so the role picks what opens and
 * takes nothing away. A handset that chose a deck by role and offered no way to the others would be
 * enforcing as a GATE what the web offers as a default — narrower than the web, on the client with
 * fewer screens, which is the wrong direction for the only difference to run in.
 *
 * IT LIVES ON THE OPENING CARD AND NOT IN THE PINNED HEADER, and that is a decision about one row of
 * chrome rather than a layout preference. The header already carries a progress ring, a two-line
 * readout and Skip across the width of a 360dp handset; a fourth control there is either a truncated
 * label or a second row of header on every card of the journey. Choosing which walkthrough to read is
 * also a decision made BEFORE reading one, which is where this card sits. The cost is written down
 * rather than hidden: a reader at the bottom of a deck has to scroll back to the top to switch. With
 * three cards on the inspector's deck and a Skip that reopens from the menu, that was judged cheaper
 * than the row of chrome — and if a third deck ever makes it expensive, the honest fix is a switcher
 * on the closing card too, not a smaller header.
 */
@Composable
private fun WalkthroughHeroCard(
    step: WalkStep,
    deck: WalkthroughDeck,
    decks: List<WalkthroughDeck>,
    onChooseDeck: (WalkthroughDeck) -> Unit,
    onStart: () -> Unit,
) {
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
            ActionRow {
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

            /*
             * THE OTHER DECKS, NAMED AND NOT COUNTED.
             *
             * Only the ones that are not open — a switcher offering the deck you are already reading
             * is a control with nothing to do, and on a register of two it is half the control. Each
             * button says the deck's own `name` and then who it is for, because "Inspecting a
             * workshop" on its own does not tell a designer whether it is theirs to read, and this
             * surface's whole job is telling a newcomer what things are.
             *
             * The block disappears entirely when there is one deck, which is how this reads on a
             * build that has not grown a second one — no heading over nothing, the same rule the step
             * card keeps for an empty facet.
             */
            val others = decks.filter { it.id != deck.id }
            if (others.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.field.onBrandTileMuted.copy(alpha = 0.3f))
                Text(
                    if (others.size == 1) "THE OTHER WALKTHROUGH" else "THE OTHER WALKTHROUGHS",
                    color = MaterialTheme.field.onBrandTileMuted,
                    style = FieldTextStyles.Eyebrow,
                )
                others.forEach { other ->
                    TextButton(
                        onClick = { onChooseDeck(other) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                other.name,
                                color = MaterialTheme.field.accentOnBrandTile,
                                style = FieldTextStyles.Link,
                            )
                            Text(
                                other.audience,
                                color = MaterialTheme.field.onBrandTileMuted,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The closing card: the checklist, the whole process in one line, and Done.
 *
 * The recap row is the web's outro section 1 — every step as a numbered chip — and like the count on
 * the opening card it is DERIVED from the journey rather than written out. A hand-written recap is a
 * list that is correct on the day it is typed and silently wrong the day after a step is inserted,
 * which is the failure the titles' missing numbers already avoid.
 *
 * ⚠ [journey] IS A PARAMETER AND USED TO BE THE `walkthroughJourney` GLOBAL, WHICH IS THE DESIGNER'S.
 * That read was the worst of the four this file carried: the other three printed the wrong NUMBER,
 * and this one would have printed the designer's twenty-three subjects — "Record workshop", "Record
 * artisan", "Upload media" — as the recap of an INSPECTOR's deck, under a heading saying they were
 * the whole process, on the last card an inspector reads.
 *
 * @param journey the numbered steps of the deck this closing card belongs to. Never the deck itself:
 *   the two ends are not steps and must not appear among their own recap chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WalkthroughOutroCard(
    step: WalkStep,
    journey: List<WalkStep>,
    onFinish: () -> Unit,
) {
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
                journey.forEachIndexed { index, journeyStep ->
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

            ActionRow {
                Button(
                    onClick = onFinish,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Done") }
            }
        }
    }
}
