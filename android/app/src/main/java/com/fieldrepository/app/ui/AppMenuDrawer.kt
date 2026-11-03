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

val APP_MENU_DRAWER_SHAPE = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)

@Composable
fun appMenuDrawerMaxWidth(): Dp =
    minOf(360.dp, LocalConfiguration.current.screenWidthDp.dp - 56.dp).coerceAtLeast(240.dp)

@OptIn(ExperimentalMaterial3Api::class)
val DrawerState.appMenuShowing: Boolean
    get() = isOpen || targetValue == DrawerValue.Open

suspend fun DrawerState.openAppMenu(reducedMotion: Boolean) {
    if (reducedMotion) snapTo(DrawerValue.Open) else open()
}

suspend fun DrawerState.closeAppMenu(reducedMotion: Boolean) {
    if (reducedMotion) snapTo(DrawerValue.Closed) else close()
}

fun Modifier.appMenuBleed(bleed: Dp): Modifier = layout { measurable, constraints ->
    val px = bleed.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = px * 2, vertical = px * 2))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(-px, -px)
    }
}

fun Modifier.appMenuHidesPage(showing: Boolean): Modifier =
    if (showing) this.clearAndSetSemantics { } else this

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
