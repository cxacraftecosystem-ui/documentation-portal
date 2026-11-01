package com.fieldrepository.app.questionnaire.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * WHAT A LIST LEAVES BEHIND WHEN ONE OF ITS RECORDS IS OPENED (owner, 1 Nov 2026): the rows it had
 * loaded, every page of them, its search and filters, and the scroller itself, so Back from the
 * record lands on the row that was tapped and never on the first record of page 1.
 *
 * In memory and nothing else. A list's rows are not Parcelable and can run to thousands, so they are
 * no candidate for `rememberSaveable` (a saved-state Bundle that large is a crash, not a feature);
 * what must survive a restart already does (the kept filters in [com.fieldrepository.app.questionnaire.data.FilterStore]).
 *
 * The host hands one memory to each screen it draws ([LocalScreenMemory]) and decides when it is
 * kept: Back from a record finds the same memory, a fresh way in from the menu a new one. Screens
 * read it through [rememberKept]; with no memory provided (a test, a dialog) that is plain `remember`.
 */
class ScreenMemory {
    private val values = HashMap<String, Any?>()

    @Suppress("UNCHECKED_CAST")
    fun <T> getOrPut(key: String, init: () -> T): T = values.getOrPut(key) { init() } as T

    /** A nested memory, e.g. one route of a screen that routes inside itself. */
    fun child(key: String): ScreenMemory = getOrPut("child:$key") { ScreenMemory() }

    /** Forget [key]'s nested memory, so the next [child] call starts over (a fresh way in). */
    fun forgetChild(key: String) { values.remove("child:$key") }

    val size: Int get() = values.size
}

/**
 * Memories per screen for a host whose navigation is a state value (both apps' `Screen`). The newest
 * [cap] screens are kept, so a long walk does not grow without bound; the list a record was opened
 * from is always among the newest two.
 */
class ScreenMemories<K>(private val cap: Int = 16) {
    private val byScreen = object : LinkedHashMap<K, ScreenMemory>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, ScreenMemory>?): Boolean = size > cap
    }

    /**
     * The memory for [screen] as it is arrived at: the one it left behind when [restoring] (Back from
     * a record), a new one otherwise.
     */
    fun arrive(screen: K, restoring: Boolean): ScreenMemory =
        if (restoring) byScreen.getOrPut(screen) { ScreenMemory() } else ScreenMemory().also { byScreen[screen] = it }

    fun has(screen: K): Boolean = byScreen.containsKey(screen)
}

/** The memory of the screen being drawn, or null where nothing keeps one. */
val LocalScreenMemory = staticCompositionLocalOf<ScreenMemory?> { null }

/** A state kept in the screen's [LocalScreenMemory] under [key]; plain `remember` with none. */
@Composable
fun <T> rememberKept(key: String, init: () -> T): MutableState<T> {
    val memory = LocalScreenMemory.current
    return remember(memory, key) { memory?.getOrPut(key) { mutableStateOf(init()) } ?: mutableStateOf(init()) }
}

/** An object (a scroll or list state) kept the same way. */
@Composable
fun <T : Any> rememberKeptValue(key: String, init: () -> T): T {
    val memory = LocalScreenMemory.current
    return remember(memory, key) { memory?.getOrPut(key, init) ?: init() }
}

/**
 * The scroller the questionnaire home draws its Forms tab in. A forms library that opens a form in
 * place (FR) puts the list's position back through it when the form is closed.
 */
val LocalFormsScroll = staticCompositionLocalOf<androidx.compose.foundation.ScrollState?> { null }
