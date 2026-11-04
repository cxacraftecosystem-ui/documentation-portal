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

@Immutable
class LazyPageChrome(
    val state: LazyListState,
    val connection: NestedScrollConnection,
    val topPadding: Dp,
    val bottomPadding: Dp,
    val header: @Composable () -> Unit
)

const val LAZY_PAGE_HEADER_KEY = "lazy-page-header"

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

@Immutable
data class LazyScrollMark(val index: Int, val offset: Int)

fun lazyRestoreReady(mark: LazyScrollMark, totalItems: Int): Boolean = totalItems > mark.index
