package com.fieldrepository.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE LONG LISTS ARE LAZY, AND THE PICKERS ASK THE SERVER — held at the source (DC-A34, DC-A09).
 *
 * Both defects were invisible in a review of one screen: an eager `forEach` inside the page column and
 * a twenty-page walk at start-up each read as ordinary code, and each only costs anything on a
 * deployment with thousands of rows. So the shape is pinned here: the long-list screens lay out the
 * page's ONE LazyColumn (`ui/LazyPage.kt`) and never paint their rows into the scrolling column, and
 * no picker walks the lookup budget any more.
 */
class LazyPagesSourceTest {

    private val main = repoSource(
        "app/src/main/java/com/fieldrepository/app/MainActivity.kt",
        "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
    )
    private val consolidated = repoSource(
        "app/src/main/java/com/fieldrepository/app/ui/ConsolidatedQuestionnaireScreen.kt",
        "android/app/src/main/java/com/fieldrepository/app/ui/ConsolidatedQuestionnaireScreen.kt",
    )

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        return source.substring(start, source.indexOf("\n}\n", start))
    }

    @Test
    fun `the long-list screens are their own lazy scroller`() {
        for (signature in listOf("private fun MyActivityScreen(", "private fun RecordPickerScreen(")) {
            val screen = body(main, signature)
            assertTrue(signature, screen.contains("chrome: LazyPageChrome"))
            assertTrue(signature, screen.contains("PageLazyColumn(chrome"))
            assertTrue(signature, screen.contains("items("))
            assertFalse("$signature must not paint rows eagerly", screen.contains(".forEach"))
            assertFalse(signature, screen.contains("verticalScroll"))
        }
        val index = body(consolidated, "private fun ConsolidatedIndex(")
        assertTrue(index.contains("PageLazyColumn(chrome"))
        assertTrue(index.contains("items(rows"))
        assertFalse(index.contains(".forEach"))
    }

    @Test
    fun `the host keeps the header, the clearance and the Back restore for lazy pages`() {
        assertTrue(main.contains("val lazyPage = screen is Screen.MyActivity || screen is Screen.Browse"))
        // The quick-return header hooks the LIST's scroll on a lazy page.
        assertTrue(main.contains("rememberQuickReturnHeader(pageList, pinned = searchOpen)"))
        assertTrue(main.contains("connection = quickReturn.connection"))
        // The island bar and banners are cleared by the END of the list, as by the column.
        assertTrue(main.contains("bottomPadding = pageReserve"))
        assertTrue(main.contains("topPadding = pageTop"))
        // Back from a record puts the list back on the item it was showing.
        assertTrue(main.contains("lazyScrollMemory[shownScreen] = LazyScrollMark("))
        assertTrue(main.contains("pageList.scrollToItem(mark.index, mark.offset)"))
    }

    @Test
    fun `cards still read the record, and Update stays its own button`() {
        val picker = body(main, "private fun RecordPickerScreen(")
        assertTrue(picker.contains(".clickable(role = Role.Button, onClickLabel = \"Open\") { onView(id) }"))
        val activity = body(main, "private fun ActivityCard(")
        assertTrue(activity.contains("onOpen(item.mode, item.id)"))
    }

    @Test
    fun `no picker walks the lookup budget or prints a cut sentence`() {
        assertFalse(main.contains("pageBudget = LOOKUP_PAGE_BUDGET"))
        assertFalse(main.contains("pageBudget = ARTISAN_PAGE_BUDGET"))
        assertFalse(main.contains("listCutNotice("))
        assertFalse(consolidated.contains("LOOKUP_PAGE_BUDGET"))
        assertFalse(consolidated.contains("listCutNotice("))
    }
}
