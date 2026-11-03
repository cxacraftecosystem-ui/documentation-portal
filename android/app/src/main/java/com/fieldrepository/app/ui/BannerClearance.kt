package com.fieldrepository.app.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val BANNER_STACK_GAP: Dp = 8.dp

fun bannerClearance(stackHeight: Dp, shown: Boolean): Dp =
    if (shown && stackHeight > 0.dp) stackHeight + BANNER_STACK_GAP else 0.dp

fun pageBottomReserve(islandReserve: Dp, bannerClearance: Dp): Dp = islandReserve + bannerClearance

val LocalBannerClearance = staticCompositionLocalOf<State<Dp>?> { null }

fun bannerDrawn(clearance: Dp): Boolean = clearance > 0.dp

fun bannerAlreadySays(text: String, tone: AppMessageTone, bannerShowing: Boolean): Boolean =
    bannerShowing && tone == AppMessageTone.ERROR &&
        text.trim().startsWith(com.fieldrepository.app.data.CONNECTION_FAILED_SENTENCE.substringBefore('.'))
