package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A PAGE WHOSE LIST IS ITS SCROLLER (DC-A34).
 *
 * Every screen used to render into one `Column(Modifier.verticalScroll(...))`, which composes and
 * measures EVERY row of a list whether or not it is on screen — My Activity composed each of a
 * researcher's records, up to fourteen thousand cards, before the first frame. A LazyColumn composes
 * only what is visible, but it cannot sit inside a parent that scrolls the same way (it throws on
 * infinite height), so a long-list screen does not go inside the page column at all: the host hands
 * it this chrome and the screen lays out ONE LazyColumn whose first item is the page header and whose
 * remaining items are the screen's own header cards and then its rows.
 *
 * What the column used to give the page, the chrome carries across:
 *  • [connection] — the quick-return header's nested-scroll hook (`ui/AppSearch.kt`);
 *  • [topPadding] — the header's measured reserve, so nothing sits under it at rest;
 *  • [bottomPadding] — the island bar and the offline / outbox banners' clearance, so the last row
 *    scrolls clear of both (`pageBottomReserve`);
 *  • [state] — keyed on the destination by the host, which also records and restores its position
 *    when Back returns from a record.
 */
@Immutable
class LazyPageChrome(
    val state: LazyListState,
    val connection: NestedScrollConnection,
    val topPadding: Dp,
    val bottomPadding: Dp,
    val header: @Composable () -> Unit
)

/** The key of the page-header item, so a screen's own keys can never collide with it. */
const val LAZY_PAGE_HEADER_KEY = "lazy-page-header"

/**
 * The page's one scroller: the header, then [content]. [spacing] is the gap between items — the page
 * column's own 16dp by default.
 */
@Composable
fun PageLazyColumn(
    chrome: LazyPageChrome,
    spacing: Dp = 16.dp,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        state = chrome.state,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(chrome.connection),
        contentPadding = PaddingValues(top = chrome.topPadding, bottom = chrome.bottomPadding),
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        item(key = LAZY_PAGE_HEADER_KEY, contentType = LAZY_PAGE_HEADER_KEY) { chrome.header() }
        content()
    }
}

/**
 * Where a lazy page was, as the host writes it down on leaving and hands it back on Back.
 * Index and offset rather than pixels: a lazy list has no pixel position for rows it has not measured.
 */
@Immutable
data class LazyScrollMark(val index: Int, val offset: Int)

/** True once a restore to [mark] can land: the list holds the item it names. */
fun lazyRestoreReady(mark: LazyScrollMark, totalItems: Int): Boolean = totalItems > mark.index
