package com.fieldrepository.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/*
 * THE HAMBURGER MENU'S BEHAVIOUR — what the right-anchored ModalNavigationDrawer needed around it.
 *
 * Audited 2026-10-16. What was wrong, and what each piece below does about it:
 *
 *  1. SCRIM AND SHEET STOPPED 16dp SHORT OF THE SCREEN EDGES. The app's root pads every screen by
 *     16dp, and the drawer lived inside that padding, so the dimmed scrim left an undimmed frame
 *     round the page and the sheet floated 16dp in from the right edge. [appMenuBleed] lays the
 *     drawer out through that padding; the host gives the PAGE its 16dp back inside the drawer.
 *  2. THE SHEET COULD COVER THE WHOLE PHONE. Material's sheet is up to 360dp wide; on a 360dp phone
 *     that left no scrim to tap. [appMenuDrawerMaxWidth] keeps 56dp of page showing, as Material's
 *     own guidance asks.
 *  3. ITS ROUNDED CORNERS WERE ON THE SCREEN EDGE. The sheet is drawn left-to-right inside a
 *     right-to-left host (that is how it is anchored on the right), so Material's "end" corners
 *     landed on the outer edge and the inner edge was square. [APP_MENU_DRAWER_SHAPE] rounds the
 *     inner edge.
 *  4. A SIDEWAYS DRAG ANYWHERE OPENED IT. With gestures on, the closed drawer listens to the whole
 *     page, so a stroke on a sketch canvas, a slider or a carousel could pull the menu across the
 *     work. Gestures are now on only while it is open ([appMenuShowing]), so a swipe still closes it.
 *  5. BACK DURING THE OPENING SLIDE NAVIGATED THE PAGE UNDERNEATH. `isOpen` is false until the slide
 *     settles; [appMenuShowing] reads the target, so Back closes a menu that is still arriving.
 *  6. REDUCED MOTION WAS IGNORED. Open and close always slid. [openAppMenu] and [closeAppMenu] snap
 *     when the person has asked for reduced motion.
 *  7. THE PAGE BEHIND STAYED IN THE ACCESSIBILITY TREE, and the keyboard stayed up. TalkBack could
 *     walk out of the open menu into the dimmed page; [appMenuHidesPage] removes the page from the
 *     tree while the menu shows, and [AppMenuFocusEffect] drops focus and the keyboard on open.
 *
 * Role filtering was checked and was already right: the drawer renders `visibleNavItems`, the same
 * list the island renders. Shared verbatim, package line aside, by both Android apps.
 */

/** The inner edge rounded, in the left-to-right terms the sheet's contents are laid out in. */
val APP_MENU_DRAWER_SHAPE = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)

/** Material's 360dp cap, and never so wide that less than 56dp of the page shows beside it. */
@Composable
fun appMenuDrawerMaxWidth(): Dp =
    minOf(360.dp, LocalConfiguration.current.screenWidthDp.dp - 56.dp).coerceAtLeast(240.dp)

/** Whether the menu is open or on its way open — the state Back, gestures and focus must follow. */
@OptIn(ExperimentalMaterial3Api::class)
val DrawerState.appMenuShowing: Boolean
    get() = isOpen || targetValue == DrawerValue.Open

/** Open the menu, sliding unless the person asked for reduced motion. */
suspend fun DrawerState.openAppMenu(reducedMotion: Boolean) {
    if (reducedMotion) snapTo(DrawerValue.Open) else open()
}

/** Close the menu, sliding unless the person asked for reduced motion. */
suspend fun DrawerState.closeAppMenu(reducedMotion: Boolean) {
    if (reducedMotion) snapTo(DrawerValue.Closed) else close()
}

/**
 * Lays the drawer out [bleed] beyond its parent's padding on every side, so the scrim and the sheet
 * reach the edges the parent padded away from. The page inside must add the same padding back.
 */
fun Modifier.appMenuBleed(bleed: Dp): Modifier = layout { measurable, constraints ->
    val px = bleed.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = px * 2, vertical = px * 2))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(-px, -px)
    }
}

/** While the menu shows, the page under the scrim is not reachable by TalkBack. */
fun Modifier.appMenuHidesPage(showing: Boolean): Modifier =
    if (showing) this.clearAndSetSemantics { } else this

/** Opening the menu drops focus from whatever field had it, and the keyboard with it. */
@Composable
fun AppMenuFocusEffect(drawerState: DrawerState) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val showing = drawerState.appMenuShowing
    LaunchedEffect(showing) {
        if (showing) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
}
