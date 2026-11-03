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

private val WALK_RAIL = 44.dp

private val WALK_BUBBLE_TEXT = 12.sp
private val WALK_BUBBLE_MIN = 30.dp
private val WALK_BUBBLE_MAX = 54.dp

private val WALK_BUBBLE_TOP = 14.dp

private val WALK_TRACK = 4.dp

private val WALK_NODE = 5.dp
private val WALK_NODE_HALO = 4.dp

private val WALK_GAP = 12.dp

private val WALK_TOP = 12.dp
private val WALK_BOTTOM = 40.dp

private val WALK_EDGE = 16.dp

private val WALK_CARD_CORNER = 14.dp

private val WALK_CHIP_CORNER = 9.dp

private const val WALK_BAND = 0.65f

private const val WALK_ACTIVE_BAND = 0.5f

private const val WALK_REVEAL_AT = 0.25f

private val WALK_RISE = 10.dp

private const val WALK_REVEAL_MS = 260
private const val WALK_CARD_DELAY_MS = 70

private val WALK_SCROLL_HEADROOM = 12.dp

private val WALK_CAUTION_LEAD = Regex("""\bwatch out\b[\s,:;—–-]*""", RegexOption.IGNORE_CASE)

@Stable
private class WalkthroughMetrics(
    private val ids: List<String>,
    val listRange: IntRange,
    private val scroll: ScrollState,
    private val topPadPx: Float,
    private val bubbleCentrePx: Float,
    private val headroomPx: Float,
) {
    private val heights = mutableStateMapOf<String, Int>()

    var viewportPx by mutableIntStateOf(0)
        private set

    fun measureViewport(height: Int) {
        if (viewportPx != height) viewportPx = height
    }

    fun measure(id: String, height: Int) {
        if (heights[id] != height) heights[id] = height
    }

    private fun heightOf(index: Int): Float = (heights[ids[index]] ?: 0).toFloat()

    fun topOf(index: Int): Float {
        var y = topPadPx
        for (i in 0 until index) y += heightOf(i)
        return y
    }

    private fun bottomOf(index: Int): Float = topOf(index) + heightOf(index)

    fun trackTop(): Float = topOf(listRange.first) + bubbleCentrePx

    fun trackBottom(): Float = topOf(listRange.last) + bubbleCentrePx

    fun progress(): Float {
        val viewport = viewportPx
        if (viewport <= 0) return 0f
        val top = topOf(listRange.first)
        val span = bottomOf(listRange.last) - top
        if (span <= 0f) return 0f
        val line = minOf(viewport * WALK_BAND, top)
        if (scroll.maxValue > 0 && scroll.value >= scroll.maxValue) return 1f
        return ((scroll.value + line - top) / span).coerceIn(0f, 1f)
    }

    fun activeIndex(): Int {
        val viewport = viewportPx
        if (viewport <= 0) return listRange.first
        val line = scroll.value + viewport * WALK_ACTIVE_BAND
        for (index in listRange) {
            if (line < bottomOf(index)) return index
        }
        return listRange.last
    }

    fun revealedThrough(previous: Int): Int {
        val viewport = viewportPx
        if (viewport <= 0) return previous
        val fold = scroll.value + viewport
        var reached = previous
        for (index in ids.indices) {
            val height = heightOf(index)
            if (height <= 0f) break
            if (topOf(index) + height * WALK_REVEAL_AT > fold) break
            reached = maxOf(reached, index)
        }
        return reached
    }

    fun scrollTarget(index: Int): Int =
        (topOf(index) - headroomPx).coerceIn(0f, scroll.maxValue.toFloat()).roundToInt()
}

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
        "Sub-category (required)",
        "State of origin",
        "State of practice",
        "GI status",
        "Place",
        "Description",
        "Craft media",
    ),
    "artisan" to listOf(
        "Name (required)",
        "Local name",
        "Workshop",
        "Craft (required)",
        "Place (required)",
        "Gender",
        "Date of birth (required)",
        "Craft start date (required)",
        "Experience (years)",
        "Experience (months)",
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
        "Craft (required)",
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
        "Linked crafts (required)",
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
        "Interview title (optional)",
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

private val WALKTHROUGH_DECK_FIELDS: Map<String, List<String>> = mapOf(
    "contributor-interview" to WALKTHROUGH_FIELDS.getValue("questionnaire"),
    "contributor-media" to WALKTHROUGH_FIELDS.getValue("media"),
    "contributor-review" to WALKTHROUGH_FIELDS.getValue("review"),
    "admin-workshop" to WALKTHROUGH_FIELDS.getValue("workshop"),
    "admin-craft" to WALKTHROUGH_FIELDS.getValue("craft"),
    "admin-review" to WALKTHROUGH_FIELDS.getValue("review"),
    "admin-view-data" to WALKTHROUGH_FIELDS.getValue("view-data"),
)

private val WALKTHROUGH_ALL_FIELDS: Map<String, List<String>> =
    WALKTHROUGH_FIELDS + WALKTHROUGH_DECK_FIELDS

internal data class WalkthroughFacets(
    val summary: String,
    val detail: String,
    val fields: List<String>,
    val watch: List<String>,
)

internal fun walkthroughFacets(step: WalkStep): WalkthroughFacets {
    val body = step.body.trim()
    val marker = WALK_CAUTION_LEAD.find(body)
    val prose = (if (marker != null) body.substring(0, marker.range.first) else body).trim()
    val caution = if (marker != null) body.substring(marker.range.last + 1).trim() else ""
    val cut = walkthroughFirstSentenceEnd(prose)
    return WalkthroughFacets(
        summary = prose.substring(0, cut).trim(),
        detail = step.why.trim().ifEmpty { prose.substring(cut).trim() },
        fields = WALKTHROUGH_ALL_FIELDS[step.id].orEmpty(),
        watch = step.watch.map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { if (caution.isEmpty()) emptyList() else listOf(caution) },
    )
}

private fun walkthroughFirstSentenceEnd(prose: String): Int {
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

private fun walkthroughTitleParts(title: String): Pair<String, String?> {
    val dot = title.indexOf(" · ")
    if (dot < 0) return title to null
    return title.substring(0, dot).trim() to title.substring(dot + 3).trim().ifEmpty { null }
}

@Composable
private fun walkthroughScreenReaderActive(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var active by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose { }
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            active = enabled
        }
        manager.addTouchExplorationStateChangeListener(listener)
        active = manager.isTouchExplorationEnabled
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return active
}

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
    if (steps.size < 3) return

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val listRange = remember(steps) { 1..(steps.size - 2).coerceAtLeast(1) }

    val journeyTotal = deck.journey.size

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

    val percent by remember(metrics) {
        derivedStateOf { (metrics.progress() * 100f).roundToInt().coerceIn(0, 100) }
    }
    val activeIndex by remember(metrics) { derivedStateOf { metrics.activeIndex() } }
    val revealedThrough by remember(metrics) {
        var frontier = -1
        derivedStateOf {
            frontier = metrics.revealedThrough(frontier)
            frontier
        }
    }

    var expandedId by rememberSaveable(steps) {
        mutableStateOf<String?>(steps[listRange.first].id)
    }

    fun travelTo(index: Int) {
        if (index !in steps.indices) return
        expandedId = steps[index].id
        scope.launch {
            withFrameNanos { }
            val target = metrics.scrollTarget(index)
            if (reduceMotion) scrollState.scrollTo(target) else scrollState.animateScrollTo(target)
        }
    }

    BackHandler(enabled = true) { onFinish() }

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
                .onSizeChanged { metrics.measureViewport(it.height) }
                .verticalScroll(scrollState)
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
                    if (!reduceMotion) {
                        val centre = Offset(railCentrePx, top + travelled)
                        drawCircle(color = haloColour, radius = nodePx + haloPx, center = centre)
                        drawCircle(color = spineColour, radius = nodePx, center = centre)
                    }
                },
        ) {
            Spacer(modifier = Modifier.height(WALK_TOP))

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

            Spacer(modifier = Modifier.height(WALK_BOTTOM))
        }
    }
}

@Composable
private fun WalkthroughHeader(
    deck: WalkthroughDeck,
    activeIndex: () -> Int,
    percent: () -> Int,
    reduceMotion: Boolean,
    onFinish: () -> Unit,
) {
    val steps = deck.steps

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WalkthroughProgressRing(percent = percent)

        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { },
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                "WALKTHROUGH",
                color = MaterialTheme.field.muted,
                style = FieldTextStyles.Eyebrow,
            )
            AnimatedContent(
                targetState = activeIndex(),
                transitionSpec = {
                    val forward = targetState >= initialState
                    val swap = if (reduceMotion) {
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
                    swap using null
                },
                label = "walkthrough-active-step",
            ) { index ->
                val step = steps.getOrNull(index) ?: steps.first()
                val number = walkthroughStepNumber(deck, step)
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        if (number != null) {
                            "Step $number of ${deck.journey.size}"
                        } else {
                            "Page ${index + 1} of ${steps.size}"
                        },
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        step.title,
                        display = true,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        lineHeight = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        TextButton(onClick = onFinish) { Text("Skip") }
    }
}

@Composable
private fun WalkthroughProgressRing(percent: () -> Int) {
    val density = LocalDensity.current
    val track = FieldProgressDefaults.trackColor
    val fill = FieldProgressDefaults.indicatorColor
    val diameter: Dp = with(density) { (12.sp.toDp() * 3.6f) }.coerceIn(44.dp, 76.dp)
    val strokePx = with(density) { 5.dp.toPx() }
    val reading = percent().coerceIn(0, 100)
    val sweep = 360f * (reading / 100f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(diameter)
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
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
            },
    ) {
        Text(
            "$reading%",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun WalkthroughRow(
    number: Int?,
    journeyTotal: Int,
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

@Composable
private fun WalkthroughBubble(
    number: Int,
    total: Int,
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
            fontSize = WALK_BUBBLE_TEXT,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

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
                        style = MaterialTheme.typography.bodyMedium,
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
                                    style = MaterialTheme.typography.bodyMedium,
                                    lineHeight = 21.sp,
                                )
                            }
                        }

                        if (facets.fields.isNotEmpty()) {
                            WalkthroughPanelSection(
                                icon = Icons.Filled.Checklist,
                                heading = "What the screen asks for",
                            ) {
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
                                            style = MaterialTheme.typography.bodySmall,
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
                                                style = MaterialTheme.typography.bodyMedium,
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
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                heading,
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        content()
    }
}

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
                style = MaterialTheme.typography.headlineSmall,
                lineHeight = 28.sp,
            )
            Text(
                step.body,
                color = MaterialTheme.field.onBrandTileMuted,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 21.sp,
            )
            ActionRow {
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.field.accentOnBrandTile,
                        contentColor = MaterialTheme.field.brandTile,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Start at step 1") }
            }

            val others = decks.filter { it.id != deck.id }
            if (others.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.field.onBrandTileMuted.copy(alpha = 0.3f))
                Text(
                    if (others.size == 1) "THE OTHER WALKTHROUGH" else "THE OTHER WALKTHROUGHS",
                    color = MaterialTheme.field.onBrandTileMuted,
                    style = FieldTextStyles.Eyebrow,
                )
                others.forEach { other ->
                    ActionRow {
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
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 15.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

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
                style = MaterialTheme.typography.bodyMedium,
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
                        style = MaterialTheme.typography.bodySmall,
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
