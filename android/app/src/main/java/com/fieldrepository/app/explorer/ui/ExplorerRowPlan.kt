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
        keys.add(key)
        ops.add { item(key = key) { content() } }
    }

    fun <T> itemsIndexed(
        items: List<T>,
        key: (index: Int, item: T) -> Any,
        itemContent: @Composable LazyItemScope.(index: Int, item: T) -> Unit,
    ) {
        val frozen = items.toList()
        val frozenKeys = frozen.mapIndexed { index, value -> key(index, value) }
        keys.addAll(frozenKeys)
        ops.add { items(frozen.size, key = { frozenKeys[it] }) { itemContent(it, frozen[it]) } }
    }

    fun replay(scope: LazyListScope) {
        for (op in ops) scope.op()
    }

    companion object {
        fun build(content: ExplorerRowPlan.() -> Unit): ExplorerRowPlan = ExplorerRowPlan().apply(content)
    }
}

internal fun <T> fileRowGroups(items: List<T>, perRow: Int, alone: (T) -> Boolean): List<List<T>> {
    if (perRow <= 1) return items.map { listOf(it) }
    val out = ArrayList<List<T>>()
    val pending = ArrayList<T>()
    for (item in items) {
        if (alone(item)) {
            if (pending.isNotEmpty()) {
                out.add(pending.toList())
                pending.clear()
            }
            out.add(listOf(item))
        } else {
            pending.add(item)
            if (pending.size == perRow) {
                out.add(pending.toList())
                pending.clear()
            }
        }
    }
    if (pending.isNotEmpty()) out.add(pending.toList())
    return out
}
