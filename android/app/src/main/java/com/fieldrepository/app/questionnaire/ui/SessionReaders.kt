package com.fieldrepository.app.questionnaire.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalSessionReaders = staticCompositionLocalOf<(@Composable (sessionId: String, refresh: Int) -> Unit)?> { null }
