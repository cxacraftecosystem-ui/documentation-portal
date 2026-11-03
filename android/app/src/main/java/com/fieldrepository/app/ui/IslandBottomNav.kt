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

const val ISLAND_MAX_VISIBLE = 5

const val ISLAND_MORE_LABEL = "More"

val ISLAND_SHORT_LABELS: Map<String, String> = mapOf(
    "Dashboard" to "Home",
    "Walkthrough" to "Tour",
    "Record" to "Record",
    "Browse" to "Browse",
    "Admin" to "Admin",
    "Account" to "Account",
)

fun islandShortLabel(label: String): String = ISLAND_SHORT_LABELS[label] ?: label

const val ISLAND_LABEL_MAX_SP = 11f
const val ISLAND_LABEL_MIN_SP = 10f
private const val ISLAND_LABEL_STEP_SP = 0.5f

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

fun islandLabelRoom(barWidth: Float, slotCount: Int): Float {
    val fixed = ISLAND_BAR_PADDING.value * 2 + ISLAND_TOUCH_TARGET.value + ISLAND_DIVIDER_GAP.value * 2 + 1f
    return (barWidth.coerceAtMost(ISLAND_MAX_WIDTH.value) - fixed) / slotCount.coerceAtLeast(1) -
        ISLAND_SLOT_PADDING.value * 2
}

class IslandEntry(val label: String, val icon: ImageVector, val onClick: () -> Unit)

class IslandGroup(
    val label: String,
    val entries: List<IslandEntry>,
    val icon: ImageVector = islandGroupIcon(label)
)

internal fun islandGroupIcon(label: String): ImageVector = when (label) {
    NavGroup.RECORD.label -> Icons.Filled.EditNote
    NavGroup.BROWSE.label -> Icons.Filled.FolderOpen
    NavGroup.ADMIN.label -> Icons.Filled.AdminPanelSettings
    NavGroup.ACCOUNT.label -> Icons.Filled.AccountCircle
    else -> Icons.Filled.Apps
}

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

fun islandSlots(roots: List<IslandEntry>, groups: List<IslandGroup>): List<IslandSlot> =
    roots.map { IslandSlot.Root(it) } + groups.filter { it.entries.isNotEmpty() }.map { IslandSlot.Group(it) }

class IslandSplit<T>(val shown: List<T>, val overflow: List<T>) {
    val hasMore: Boolean get() = overflow.isNotEmpty()
}

fun <T> splitIsland(items: List<T>, maxVisible: Int = ISLAND_MAX_VISIBLE): IslandSplit<T> {
    require(maxVisible >= 2) { "the island needs room for at least one destination and More" }
    return if (items.size <= maxVisible) {
        IslandSplit(items, emptyList())
    } else {
        IslandSplit(items.take(maxVisible - 1), items.drop(maxVisible - 1))
    }
}

fun isIslandSlotActive(slot: IslandSlot, currentLabel: String?): Boolean {
    if (currentLabel == null) return false
    return when (slot) {
        is IslandSlot.Root -> slot.entry.label == currentLabel
        is IslandSlot.Group -> slot.group.entries.any { it.label == currentLabel }
    }
}

fun isIslandMoreActive(split: IslandSplit<IslandSlot>, currentLabel: String?): Boolean =
    split.overflow.any { isIslandSlotActive(it, currentLabel) }

private val ISLAND_TOUCH_TARGET = 48.dp
private val ISLAND_BAR_PADDING = 6.dp
private val ISLAND_SLOT_PADDING = 2.dp
private val ISLAND_SLOT_ICON = 20.dp
private val ISLAND_SLOT_ICON_GLYPH = 22.dp
private val ISLAND_DIVIDER_GAP = 4.dp
private val ISLAND_MAX_WIDTH = 560.dp
private val ISLAND_CONTENT_GAP = 8.dp
private const val ISLAND_TWEEN_MS = 180

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
    val stillness = LocalAppPreferences.current.reducedMotion
    val imeOpen by rememberIslandImeVisible()

    val split = splitIsland(islandSlots(roots, groups), maxVisible)
    val moreActive = isIslandMoreActive(split, currentLabel)
    var openGroup by remember { mutableStateOf<String?>(null) }
    var moreOpen by remember { mutableStateOf(false) }

    val slotLabels = split.shown.map { islandShortLabel(it.label) } +
        if (split.hasMore) listOf(ISLAND_MORE_LABEL) else emptyList()
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current

    AnimatedVisibility(
        visible = visible && !imeOpen,
        modifier = modifier,
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
                    val available = islandLabelRoom(maxWidth.value, slotCount).dp
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

    if (moreOpen && split.hasMore) {
        IslandMoreSheet(
            slots = split.overflow,
            currentLabel = currentLabel,
            stillness = stillness,
            onDismissed = { moreOpen = false }
        )
    }
    LaunchedEffect(imeOpen, visible) {
        if (imeOpen || !visible) openGroup = null
    }
}

@Composable
private fun IslandSlotButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
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
            .semantics { contentDescription = label }
            .padding(horizontal = ISLAND_SLOT_PADDING, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
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
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    }
}

private object IslandWholeWindow : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset = IntOffset.Zero
}

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
                            style = MaterialTheme.typography.titleMedium,
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
