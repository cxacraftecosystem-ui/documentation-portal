package com.fieldrepository.app.ui

import android.view.ViewTreeObserver
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/*
 * THE ISLAND, AT THE BOTTOM OF THE PHONE.
 *
 * ── WHAT CHANGED ON 2026-10-16, AND WHY ──────────────────────────────────────────────────────────
 *
 * The island used to be pinned to the TOP of every screen: a wordmark, Dashboard and Walkthrough as
 * loose chips, the four group dropdowns, the admin-view chip and the hamburger, shedding width in
 * five measured density tiers and collapsing into a header once the page scrolled. On a phone that
 * put every destination at the far end of the screen from the thumb, and the five tiers existed only
 * because eight controls were competing for one strip.
 *
 * The owner's ruling moved it to the BOTTOM and capped what it shows directly:
 *
 *  - AT MOST [ISLAND_MAX_VISIBLE] DESTINATIONS. A destination here is one top-level slot of the
 *    island — a loose entry (Dashboard, Walkthrough) or a group (Record, Browse, Admin, Account) —
 *    in the island's existing order, after role filtering. Five or fewer are all shown. More than
 *    five shows the first four and a fifth "More" slot; see [splitIsland].
 *  - "MORE" OPENS A SHEET listing the rest with the same icons and labels, the active destination
 *    highlighted. When the screen you are on lives behind "More", "More" itself is lit, so the bar
 *    never claims you are nowhere; see [isIslandMoreActive].
 *  - THE HAMBURGER STAYS, at the bar's right end and outside the five. It is not a destination: it
 *    opens the drawer, which is still the full list in one place AND the only home of the
 *    admin-view switch, "Push update to all" and Logout. Dropping it would strand all three.
 *  - THE WORDMARK AND THE ADMIN-VIEW CHIP LEFT THE BAR. Dashboard is a slot of its own, so a brand
 *    link to the same place was a second copy of it; the admin-view switch is in the drawer, which
 *    states it in words.
 *  - IT NO LONGER COLLAPSES ON SCROLL. Collapsing turned the top bar into a header; at the bottom
 *    there is no header to become, and a bar that comes and goes under a moving thumb is a bar the
 *    thumb misses. It hides for exactly one reason: the keyboard is open (see
 *    [rememberIslandImeVisible]), because a form being typed into needs that strip more than the
 *    menu does.
 *
 * What did NOT change is what makes it the island: the same 28dp pill, the same surface, hairline and
 * shadow, the same `surface100` wash and primary tint on the active slot, the same group dropdowns,
 * the same 180ms tweens — and every one of them snaps when the person has asked for reduced motion
 * (`LocalAppPreferences.current.reducedMotion`). It still renders exactly [visibleNavItems] through
 * the host, so it can no more offer an unauthorised destination than the drawer can.
 *
 * This file is shared VERBATIM, package line aside, by the designer portal's and the documentation
 * portal's Android apps.
 * Keep the two in step: a fix made here is copied there.
 */

/** How many slots the island shows before the overflow goes behind "More". */
const val ISLAND_MAX_VISIBLE = 5

/** The overflow slot's label — also its accessible name. */
const val ISLAND_MORE_LABEL = "More"

/**
 * THE WORDS THE BAR PRINTS (2026-10-17). The bar's labels used to be the menu's own, and "Walkthrough"
 * is wide enough that on a phone narrower than about 380dp the bar dropped EVERY label and drew bare
 * glyphs. The bar now prints a short word for the long names and keeps the full name as the slot's
 * accessible name, so a screen reader still says "Walkthrough" and "Dashboard". A label not in this
 * map is printed as it is. The drawer, the dropdowns and the "More" sheet keep the full names.
 */
val ISLAND_SHORT_LABELS: Map<String, String> = mapOf(
    "Dashboard" to "Home",
    "Walkthrough" to "Tour",
    "Record" to "Record",
    "Browse" to "Browse",
    "Admin" to "Admin",
    "Account" to "Account",
)

/** The word the bar prints for [label]. */
fun islandShortLabel(label: String): String = ISLAND_SHORT_LABELS[label] ?: label

/** The bar's label size, and the smallest it shrinks to before it gives up words for glyphs. */
const val ISLAND_LABEL_MAX_SP = 12f
const val ISLAND_LABEL_MIN_SP = 11f
private const val ISLAND_LABEL_STEP_SP = 0.5f

/**
 * The largest label size, from [maxSp] down to [minSp] in half-point steps, at which the widest
 * label fits [available] (both in dp, [widestAt] measuring at a size in sp) — or null, and the bar
 * draws glyphs only, when even [minSp] does not fit. Every slot uses the one size: a bar whose words
 * are three different sizes reads as three kinds of thing. Pure; pinned by `IslandBottomNavTest`.
 */
fun islandLabelSize(
    available: Float,
    widestAt: (Float) -> Float,
    maxSp: Float = ISLAND_LABEL_MAX_SP,
    minSp: Float = ISLAND_LABEL_MIN_SP,
): Float? {
    var size = maxSp
    while (size >= minSp - 0.001f) {
        if (widestAt(size) <= available) return size
        size -= ISLAND_LABEL_STEP_SP
    }
    return null
}

/** One destination in the island: what it says, what it looks like, what it does. */
class IslandEntry(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * One dropdown slot and the destinations behind it — the web's Record / Browse / Admin / Account.
 *
 * [icon] defaults from the label because the web has no group icons to copy and the caller should
 * not have to invent one: on a narrow phone the glyph IS the slot.
 */
class IslandGroup(
    val label: String,
    val entries: List<IslandEntry>,
    val icon: ImageVector = islandGroupIcon(label)
)

/**
 * The glyph that stands in for a group label. Deliberately distinct from every icon a slot beside it
 * can carry — the Browse folder against the Dashboard grid, the Account bust against the Walkthrough
 * compass — because on a narrow phone the user is telling them apart by shape alone.
 */
internal fun islandGroupIcon(label: String): ImageVector = when (label) {
    NavGroup.RECORD.label -> Icons.Filled.EditNote
    NavGroup.BROWSE.label -> Icons.Filled.FolderOpen
    NavGroup.ADMIN.label -> Icons.Filled.AdminPanelSettings
    NavGroup.ACCOUNT.label -> Icons.Filled.AccountCircle
    else -> Icons.Filled.Apps
}

// ---------------------------------------------------------------------------------------------
// The pure model — which slots show, what goes behind "More", and when "More" is lit
// ---------------------------------------------------------------------------------------------

/** One top-level slot of the island: a loose destination, or a group that opens a dropdown. */
sealed class IslandSlot {
    abstract val label: String
    abstract val icon: ImageVector

    class Root(val entry: IslandEntry) : IslandSlot() {
        override val label: String get() = entry.label
        override val icon: ImageVector get() = entry.icon
    }

    class Group(val group: IslandGroup) : IslandSlot() {
        override val label: String get() = group.label
        override val icon: ImageVector get() = group.icon
    }
}

/**
 * The island's slots in render order: the loose entries first, then every group that has anything
 * in it for this user. An empty group is dropped HERE, before the count, so a role whose Admin group
 * filtered to nothing is never pushed behind "More" by a slot that would not have been drawn.
 */
fun islandSlots(roots: List<IslandEntry>, groups: List<IslandGroup>): List<IslandSlot> =
    roots.map { IslandSlot.Root(it) } + groups.filter { it.entries.isNotEmpty() }.map { IslandSlot.Group(it) }

/** What the bar draws directly ([shown]) and what lives behind "More" ([overflow]). */
class IslandSplit<T>(val shown: List<T>, val overflow: List<T>) {
    /** Whether the bar draws a "More" slot at all — only when something is actually behind it. */
    val hasMore: Boolean get() = overflow.isNotEmpty()
}

/**
 * The five-slot rule. Up to [maxVisible] items are all shown. Past that, the first `maxVisible - 1`
 * are shown IN THEIR EXISTING ORDER and the rest go behind "More", which takes the last slot — so the
 * bar never draws more than [maxVisible] slots, "More" included, and never draws a "More" with
 * nothing behind it. A sixth slot pushes TWO behind "More", never one: a "More" hiding a single item
 * would cost the same slot the item itself would have taken.
 */
fun <T> splitIsland(items: List<T>, maxVisible: Int = ISLAND_MAX_VISIBLE): IslandSplit<T> {
    require(maxVisible >= 2) { "the island needs room for at least one destination and More" }
    return if (items.size <= maxVisible) {
        IslandSplit(items, emptyList())
    } else {
        IslandSplit(items.take(maxVisible - 1), items.drop(maxVisible - 1))
    }
}

/**
 * Whether [slot] is where the user is: a loose entry whose label is the current one, or a group that
 * holds it. Matched by label because the bar's model is labels — the host looks the label up from
 * the current destination, so a page title can never stand in for it.
 */
fun isIslandSlotActive(slot: IslandSlot, currentLabel: String?): Boolean {
    if (currentLabel == null) return false
    return when (slot) {
        is IslandSlot.Root -> slot.entry.label == currentLabel
        is IslandSlot.Group -> slot.group.entries.any { it.label == currentLabel }
    }
}

/** "More" is lit exactly when the screen the user is on lives behind it. */
fun isIslandMoreActive(split: IslandSplit<IslandSlot>, currentLabel: String?): Boolean =
    split.overflow.any { isIslandSlotActive(it, currentLabel) }

// ---------------------------------------------------------------------------------------------
// Dimensions
// ---------------------------------------------------------------------------------------------

/** Material's minimum tap target, and the height of every slot. */
private val ISLAND_TOUCH_TARGET = 48.dp
private val ISLAND_BAR_PADDING = 6.dp
/** A slot's own padding either side of its label, part of what a label has to fit inside. */
private val ISLAND_SLOT_PADDING = 2.dp
private val ISLAND_SLOT_ICON = 20.dp
/** A glyph standing in for a word is read at arm's length, so it is drawn larger. */
private val ISLAND_SLOT_ICON_GLYPH = 22.dp
/** The hairline between the destinations and the hamburger, and the air either side of it. */
private val ISLAND_DIVIDER_GAP = 4.dp
/**
 * The widest the bar grows. A tablet is wide enough to stretch five slots into a strip of air; the
 * island is a pill, so past this it stays a pill and centres.
 */
private val ISLAND_MAX_WIDTH = 560.dp
/** Between the bar's top edge and the last line of the page scrolled above it. */
private val ISLAND_CONTENT_GAP = 8.dp
private const val ISLAND_TWEEN_MS = 180

// ---------------------------------------------------------------------------------------------
// The host scaffold
// ---------------------------------------------------------------------------------------------

/**
 * The page with the island floating over its bottom edge.
 *
 * [content] is told how much bottom padding to give its LAST child — the island's measured height
 * plus [ISLAND_CONTENT_GAP] — so the final field or button of a long form scrolls clear of the bar
 * instead of ending underneath it. Measured, not assumed: a 200% font scale or a tablet changes the
 * bar's height, and a guessed reserve is exactly how a Save button ends up behind a pill. While the
 * bar is hidden (the keyboard is open) the reserve is zero, so the page does not keep a hole for a
 * bar that is not there.
 *
 * The island is composed AFTER the content so it draws on top of it.
 */
@Composable
fun IslandScaffold(
    island: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (contentBottomPadding: Dp) -> Unit
) {
    var islandHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val reserve = if (islandHeight > 0) with(density) { islandHeight.toDp() } + ISLAND_CONTENT_GAP else 0.dp
    Box(modifier = modifier.fillMaxSize()) {
        content(reserve)
        island(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { islandHeight = it.height }
        )
    }
}

/**
 * Whether the soft keyboard is open, read two ways because neither alone covers the fleet.
 *
 * `WindowInsets.ime` is the Compose reading, and it is exact wherever the window is edge to edge —
 * every handset on Android 15+, and every handset in an app that calls `enableEdgeToEdge()`. On an
 * older phone whose window still fits the system bars, the platform resizes the window for the
 * keyboard and consumes the inset before Compose sees it, so that reading stays zero. The ROOT
 * view's insets are not consumed, so the view-tree listener catches that case.
 */
@Composable
fun rememberIslandImeVisible(): State<Boolean> {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val view = LocalView.current
    val fromView = remember(view) { mutableStateOf(false) }
    DisposableEffect(view) {
        fun read() {
            fromView.value = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener { read() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        read()
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return remember(ime, density, fromView) {
        derivedStateOf { ime.getBottom(density) > 0 || fromView.value }
    }
}

// ---------------------------------------------------------------------------------------------
// The bar
// ---------------------------------------------------------------------------------------------

/**
 * The web's dynamic island, at the bottom of the phone. See the header of this file.
 *
 * @param roots the loose destinations, in order (Dashboard, Walkthrough).
 * @param groups the dropdown groups, in order; empty ones are skipped.
 * @param onOpenDrawer the hamburger — the full menu, the admin-view switch and Logout.
 * @param currentLabel the label of the destination the user is on, the web's `aria-current`.
 * @param visible false hides the bar (it also hides itself while the keyboard is open).
 */
@Composable
fun FieldIslandNav(
    roots: List<IslandEntry>,
    groups: List<IslandGroup>,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    currentLabel: String? = null,
    visible: Boolean = true,
    maxVisible: Int = ISLAND_MAX_VISIBLE
) {
    // "Stops animations and transitions" is a promise the user made the app; a bar that slides and
    // fades anyway would be the one place it is broken.
    val stillness = LocalAppPreferences.current.reducedMotion
    val imeOpen by rememberIslandImeVisible()

    val split = splitIsland(islandSlots(roots, groups), maxVisible)
    val moreActive = isIslandMoreActive(split, currentLabel)
    var openGroup by remember { mutableStateOf<String?>(null) }
    var moreOpen by remember { mutableStateOf(false) }

    // The words each slot must fit, "More" included — the SHORT ones the bar prints — measured in the
    // face and font scale in force, never estimated per character, which is the guess the old top
    // bar spent five tiers undoing.
    val slotLabels = split.shown.map { islandShortLabel(it.label) } +
        if (split.hasMore) listOf(ISLAND_MORE_LABEL) else emptyList()
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current

    AnimatedVisibility(
        visible = visible && !imeOpen,
        modifier = modifier,
        // Down and out, the way a bottom bar leaves; the reverse on the way back.
        enter = if (stillness) EnterTransition.None else {
            fadeIn(tween(ISLAND_TWEEN_MS)) + slideInVertically(tween(ISLAND_TWEEN_MS)) { it / 2 }
        },
        exit = if (stillness) ExitTransition.None else {
            fadeOut(tween(120)) + slideOutVertically(tween(120)) { it / 2 }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Zero wherever the host has already paid the system bars (both apps' roots do), and
                // exactly the navigation bar wherever it has not — so the pill sits above the gesture
                // pill or the three-button bar either way, never under it and never twice above it.
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = ISLAND_MAX_WIDTH)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(1.dp, MaterialTheme.field.hairline)
            ) {
                BoxWithConstraints {
                    val slotCount = slotLabels.size.coerceAtLeast(1)
                    val slotWidth = (maxWidth - ISLAND_BAR_PADDING * 2 - ISLAND_TOUCH_TARGET -
                        ISLAND_DIVIDER_GAP * 2 - 1.dp) / slotCount
                    // Every slot carries its word, or none does: a bar where three slots are named
                    // and two are bare glyphs reads as two different kinds of thing. The words shrink
                    // to ISLAND_LABEL_MIN_SP before the bar gives them up; the full name is the
                    // slot's accessible name either way.
                    val available = slotWidth - ISLAND_SLOT_PADDING * 2
                    val labelSp = remember(slotLabels, labelStyle, density, measurer, available) {
                        islandLabelSize(available.value, { size ->
                            val style = labelStyle.copy(fontSize = size.sp)
                            slotLabels.maxOfOrNull { label ->
                                with(density) { measurer.measure(label, style).size.width.toDp() }.value
                            } ?: 0f
                        })
                    }
                    val labelSize: TextUnit? = labelSp?.sp

                    Row(
                        modifier = Modifier.padding(ISLAND_BAR_PADDING),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        split.shown.forEach { slot ->
                            Box(modifier = Modifier.weight(1f)) {
                                when (slot) {
                                    is IslandSlot.Root -> IslandSlotButton(
                                        label = slot.label,
                                        icon = slot.icon,
                                        selected = isIslandSlotActive(slot, currentLabel),
                                        labelSize = labelSize,
                                        stillness = stillness,
                                        role = Role.Tab,
                                        onClick = slot.entry.onClick
                                    )

                                    is IslandSlot.Group -> {
                                        IslandSlotButton(
                                            label = slot.label,
                                            icon = slot.icon,
                                            selected = isIslandSlotActive(slot, currentLabel),
                                            labelSize = labelSize,
                                            stillness = stillness,
                                            role = Role.Button,
                                            onClick = { openGroup = slot.label }
                                        )
                                        // Anchored to its slot; from the bottom of the screen it
                                        // opens UPWARD on its own, which is the direction a thumb
                                        // already travels.
                                        DropdownMenu(
                                            expanded = openGroup == slot.label,
                                            onDismissRequest = { openGroup = null }
                                        ) {
                                            slot.group.entries.forEach { entry ->
                                                val here = entry.label == currentLabel
                                                val tint = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.field.body
                                                DropdownMenuItem(
                                                    text = { Text(entry.label, color = tint) },
                                                    leadingIcon = {
                                                        Icon(entry.icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                                                    },
                                                    modifier = Modifier
                                                        .background(if (here) MaterialTheme.field.surface100 else Color.Transparent)
                                                        .semantics { selected = here },
                                                    onClick = {
                                                        openGroup = null
                                                        entry.onClick()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (split.hasMore) {
                            Box(modifier = Modifier.weight(1f)) {
                                IslandSlotButton(
                                    label = ISLAND_MORE_LABEL,
                                    icon = Icons.Filled.MoreHoriz,
                                    selected = moreActive,
                                    labelSize = labelSize,
                                    stillness = stillness,
                                    role = Role.Button,
                                    onClick = { moreOpen = true }
                                )
                            }
                        }

                        Spacer(Modifier.width(ISLAND_DIVIDER_GAP))
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(24.dp)
                                .background(MaterialTheme.field.hairline)
                        )
                        Spacer(Modifier.width(ISLAND_DIVIDER_GAP))

                        // Never hidden and never moved: the one control that must be findable without
                        // thinking, because the drawer behind it holds Logout.
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier.size(ISLAND_TOUCH_TARGET)
                        ) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = "Open menu",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    // Outside the AnimatedVisibility, so a sheet that is open when the keyboard appears is not torn
    // down mid-read; it is dismissed by its own scrim, its own rows or Back.
    if (moreOpen && split.hasMore) {
        IslandMoreSheet(
            slots = split.overflow,
            currentLabel = currentLabel,
            stillness = stillness,
            onDismissed = { moreOpen = false }
        )
    }
    // A dropdown left open over a bar that has just hidden would be pointing at nothing.
    LaunchedEffect(imeOpen, visible) {
        if (imeOpen || !visible) openGroup = null
    }
}

/**
 * One slot: a glyph over its word, or the glyph alone with the word as its accessible name. The
 * whole slot is the tap target — at least [ISLAND_TOUCH_TARGET] tall and its full share of the bar
 * wide — so the slack belongs to the finger, not to the gap.
 */
@Composable
private fun IslandSlotButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    /** The bar's label size, or null for the glyph alone. See [islandLabelSize]. */
    labelSize: TextUnit?,
    stillness: Boolean,
    role: Role,
    onClick: () -> Unit
) {
    val showLabel = labelSize != null
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.field.surface100 else Color.Transparent,
        animationSpec = if (stillness) snap() else tween(ISLAND_TWEEN_MS),
        label = "islandSlotBackground"
    )
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.field.body,
        animationSpec = if (stillness) snap() else tween(ISLAND_TWEEN_MS),
        label = "islandSlotTint"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ISLAND_TOUCH_TARGET)
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .selectable(selected = selected, onClick = onClick, role = role)
            // The FULL name, always: the word printed may be the short one ("Tour" for Walkthrough),
            // and without a word the slot is a bare image that has to say what it is out loud.
            .semantics { contentDescription = label }
            .padding(horizontal = ISLAND_SLOT_PADDING, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            // The word, or the slot's own description, is the name; a second one would stutter.
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(if (showLabel) ISLAND_SLOT_ICON else ISLAND_SLOT_ICON_GLYPH)
        )
        if (labelSize != null) {
            Text(
                islandShortLabel(label),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = labelSize),
                color = tint,
                maxLines = 1,
                // The slot already says the full name; the short word read too would stutter.
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The "More" sheet
// ---------------------------------------------------------------------------------------------

/** Places the sheet's popup over the whole window, whatever slot opened it. */
private object IslandWholeWindow : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = IntOffset.Zero
}

/**
 * The destinations that did not fit, in the island's order, with the same icons and labels.
 *
 * A focusable [Popup] rather than Material's `ModalBottomSheet`, for one reason: the reduced-motion
 * switch. `ModalBottomSheet` always slides, and the app promised the person who turned that switch
 * on that nothing would. Here the slide and the scrim fade are this file's own tweens and snap to
 * nothing under [stillness]. The popup is its own focusable window, so Back closes it and TalkBack
 * reads it as a pane.
 *
 * A loose destination is one row; a group is its heading and then one row per entry, so nothing in a
 * hidden group is more than one tap away. The row for the screen the user is on is lit.
 */
@Composable
private fun IslandMoreSheet(
    slots: List<IslandSlot>,
    currentLabel: String?,
    stillness: Boolean,
    onDismissed: () -> Unit
) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    fun close() {
        shown.targetState = false
    }
    // Removed from composition only once the exit has finished, so the slide out is seen; under
    // reduced motion there is no exit to wait for and this fires on the next frame.
    LaunchedEffect(shown.isIdle, shown.currentState, shown.targetState) {
        if (shown.isIdle && !shown.currentState && !shown.targetState) onDismissed()
    }
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp

    Popup(
        popupPositionProvider = IslandWholeWindow,
        onDismissRequest = { close() },
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visibleState = shown,
                enter = if (stillness) EnterTransition.None else fadeIn(tween(ISLAND_TWEEN_MS)),
                exit = if (stillness) ExitTransition.None else fadeOut(tween(120))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(onClickLabel = "Close", onClick = { close() })
                        .semantics { contentDescription = "Close the list of more destinations" }
                )
            }
            AnimatedVisibility(
                visibleState = shown,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = if (stillness) EnterTransition.None else {
                    fadeIn(tween(ISLAND_TWEEN_MS)) + slideInVertically(tween(ISLAND_TWEEN_MS)) { it }
                },
                exit = if (stillness) ExitTransition.None else {
                    fadeOut(tween(120)) + slideOutVertically(tween(120)) { it }
                }
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = ISLAND_MAX_WIDTH)
                        .fillMaxWidth()
                        .heightIn(max = maxSheetHeight)
                        .semantics { paneTitle = "More destinations" },
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 10.dp,
                    border = BorderStroke(1.dp, MaterialTheme.field.hairline)
                ) {
                    Column(
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 10.dp, bottom = 16.dp)
                    ) {
                        // The grab handle every bottom sheet in the app wears, so this reads as one.
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .width(32.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.field.hairline)
                        )
                        Text(
                            ISLAND_MORE_LABEL,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp)
                        )
                        slots.forEach { slot ->
                            when (slot) {
                                is IslandSlot.Root -> IslandSheetRow(
                                    label = slot.label,
                                    icon = slot.icon,
                                    selected = slot.label == currentLabel,
                                    stillness = stillness,
                                    onClick = {
                                        slot.entry.onClick()
                                        close()
                                    }
                                )

                                is IslandSlot.Group -> {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 4.dp)
                                            .semantics { heading() },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            slot.icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.field.muted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            slot.label.uppercase(),
                                            style = FieldTextStyles.FieldLabel,
                                            color = MaterialTheme.field.muted
                                        )
                                    }
                                    slot.group.entries.forEach { entry ->
                                        IslandSheetRow(
                                            label = entry.label,
                                            icon = entry.icon,
                                            selected = entry.label == currentLabel,
                                            stillness = stillness,
                                            onClick = {
                                                entry.onClick()
                                                close()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One destination in the sheet, lit the way an island slot is lit when it is where you are. */
@Composable
private fun IslandSheetRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    stillness: Boolean,
    onClick: () -> Unit
) {
    val background by animateColorAsState(
        targetValue = if (selected) MaterialTheme.field.surface100 else Color.Transparent,
        animationSpec = if (stillness) snap() else tween(ISLAND_TWEEN_MS),
        label = "islandSheetRowBackground"
    )
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.field.body
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(min = ISLAND_TOUCH_TARGET)
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .selectable(selected = selected, onClick = onClick, role = Role.Button)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
    }
}
