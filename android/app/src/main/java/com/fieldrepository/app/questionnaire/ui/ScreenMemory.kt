package com.fieldrepository.app.questionnaire.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

class ScreenMemory {
    private val values = HashMap<String, Any?>()

    @Suppress("UNCHECKED_CAST")
    fun <T> getOrPut(key: String, init: () -> T): T = values.getOrPut(key) { init() } as T

    fun child(key: String): ScreenMemory = getOrPut("child:$key") { ScreenMemory() }

    fun forgetChild(key: String) { values.remove("child:$key") }

    val size: Int get() = values.size
}

class ScreenMemories<K>(private val cap: Int = 16) {
    private val byScreen = object : LinkedHashMap<K, ScreenMemory>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, ScreenMemory>?): Boolean = size > cap
    }

    fun arrive(screen: K, restoring: Boolean): ScreenMemory =
        if (restoring) byScreen.getOrPut(screen) { ScreenMemory() } else ScreenMemory().also { byScreen[screen] = it }

    fun has(screen: K): Boolean = byScreen.containsKey(screen)
}

val LocalScreenMemory = staticCompositionLocalOf<ScreenMemory?> { null }

@Composable
fun <T> rememberKept(key: String, init: () -> T): MutableState<T> {
    val memory = LocalScreenMemory.current
    return remember(memory, key) { memory?.getOrPut(key) { mutableStateOf(init()) } ?: mutableStateOf(init()) }
}

@Composable
fun <T : Any> rememberKeptValue(key: String, init: () -> T): T {
    val memory = LocalScreenMemory.current
    return remember(memory, key) { memory?.getOrPut(key, init) ?: init() }
}

val LocalFormsScroll = staticCompositionLocalOf<androidx.compose.foundation.ScrollState?> { null }
