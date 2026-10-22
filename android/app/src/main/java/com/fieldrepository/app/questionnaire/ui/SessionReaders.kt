package com.fieldrepository.app.questionnaire.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * C10 (UNIFY §1.3): what the portal's readers (completeness, tasks, stage counts, export) make of a
 * session, drawn on its read view under the session's details. The readers are server-side and each
 * portal shows them in its own record language, so the portal provides the drawing; null draws
 * nothing. [refresh] changes after a review so the block is read again.
 */
val LocalSessionReaders = staticCompositionLocalOf<(@Composable (sessionId: String, refresh: Int) -> Unit)?> { null }
