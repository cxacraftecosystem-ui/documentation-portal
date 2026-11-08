package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

internal class ExplorerRowPlan {
    private val ops = ArrayList<LazyListScope.() -> Unit>()
    private val keys = ArrayList<Any>()

    val size: Int get() = keys.size

    val rowKeys: List<Any> get() = keys.toList()

    fun item(key: Any, content: @Composable LazyItemScope.() -> Unit) {
        keys += key
        ops += { item(key = key) { content() } }
    }

    fun <T> itemsIndexed(
        items: List<T>,
        key: (index: Int, item: T) -> Any,
        itemContent: @Composable LazyItemScope.(index: Int, item: T) -> Unit,
    ) {
        val frozen = items.toList()
        val frozenKeys = frozen.mapIndexed { index, value -> key(index, value) }
        keys += frozenKeys
        ops += { items(frozen.size, key = { frozenKeys[it] }) { itemContent(it, frozen[it]) } }
    }

    fun replay(scope: LazyListScope) {
        for (op in ops) scope.op()
    }

    companion object {
        fun build(content: ExplorerRowPlan.() -> Unit): ExplorerRowPlan = ExplorerRowPlan().apply(content)
    }
}
