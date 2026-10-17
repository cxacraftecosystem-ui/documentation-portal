package com.fieldrepository.app.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * THE STATUS BANNERS' FOOTPRINT, HANDED TO EVERY PAGE.
 *
 * The offline line, the server-unreachable line and the "N entries saved on this device" outbox
 * banner float over the bottom of the window, above the island bar, drawn by the app root so they
 * follow the reader across every screen. Floating is the point — and it is also how they hid the
 * end of every long form: the page reserved room for the bar and nothing for the banners, so with
 * the wifi off "Upload batch" and "Save artisan" sat underneath two purple cards and the page would
 * not scroll any further (release smoke test 2026-10-17, shots doc/83, doc/84).
 *
 * So the root MEASURES the banner stack and publishes its height here; every page adds it to the
 * bottom padding of its scroll content, and the app's message surface sits above it. Measured, not
 * assumed: the banners wrap to three lines at a large font scale, and the outbox banner grows when
 * its file list is opened.
 */

/** The space between the top of the banner stack and whatever must clear it. */
val BANNER_STACK_GAP: Dp = 8.dp

/**
 * How much bottom space the banner stack takes from a page: its measured height plus the gap, or
 * nothing at all while no banner is showing — a page must not keep a hole for a banner that is gone.
 */
fun bannerClearance(stackHeight: Dp, shown: Boolean): Dp =
    if (shown && stackHeight > 0.dp) stackHeight + BANNER_STACK_GAP else 0.dp

/**
 * The bottom padding a page's scroll content needs: room for the island bar AND for the banners
 * over it, so the last field or button scrolls clear of both.
 */
fun pageBottomReserve(islandReserve: Dp, bannerClearance: Dp): Dp = islandReserve + bannerClearance

/** The banner stack's current clearance (see [bannerClearance]); null outside the signed-in app. */
val LocalBannerClearance = staticCompositionLocalOf<State<Dp>?> { null }

/**
 * Whether a banner is on screen right now, read off its clearance: the root reserves nothing while
 * no banner is drawn — none to show, or the keyboard is up and they have stood down. The "Could not
 * connect" error is only redundant while this is true; otherwise it is the only thing saying so.
 */
fun bannerDrawn(clearance: Dp): Boolean = clearance > 0.dp

/**
 * Whether an error is only the banner's own news: "Could not connect" while the offline (or
 * server-unreachable) banner is already on screen. Raising it anyway stacked a second card saying
 * the same thing, and on the Upload media form it sat behind the banner (smoke test, doc/75).
 * Every other error still shows — offline does not make a refused save any less refused.
 */
fun bannerAlreadySays(text: String, tone: AppMessageTone, bannerShowing: Boolean): Boolean =
    bannerShowing && tone == AppMessageTone.ERROR &&
        text.trim().startsWith(com.fieldrepository.app.data.CONNECTION_FAILED_SENTENCE.substringBefore('.'))
