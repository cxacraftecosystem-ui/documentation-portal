package com.fieldrepository.app.explorer.portal

import androidx.compose.runtime.Composable
import com.fieldrepository.app.ui.FieldRepositoryTheme

@Composable
fun DesignTheme(dark: Boolean, content: @Composable () -> Unit) = FieldRepositoryTheme(darkTheme = dark, content = content)
