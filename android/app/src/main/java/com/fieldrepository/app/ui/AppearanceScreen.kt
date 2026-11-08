package com.fieldrepository.app.ui

import com.fieldrepository.app.data.apiErrorMessage
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.PreferencesDto
import com.fieldrepository.app.data.WeeklyDigestPreferenceDto
import com.fieldrepository.app.data.WeeklyDigestRules
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

const val THEME_SYSTEM: String = "system"

const val THEME_LIGHT: String = "light"

const val THEME_DARK: String = "dark"

val THEME_CHOICES: List<String> = listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)

const val LARGER_TEXT_SCALE: Float = 1.125f

@Immutable
data class AppPreferences(
    val theme: String = THEME_SYSTEM,
    val reducedMotion: Boolean = false,
    val largerText: Boolean = false,
    val highContrast: Boolean = false
) {
    val fontScale: Float get() = if (largerText) LARGER_TEXT_SCALE else 1f

    companion object {
        val Default: AppPreferences = AppPreferences()
    }
}

fun resolveDarkTheme(theme: String, systemDark: Boolean): Boolean = when (theme) {
    THEME_DARK -> true
    THEME_LIGHT -> false
    else -> systemDark
}

fun normalizeThemeChoice(value: String?): String =
    if (value != null && value in THEME_CHOICES) value else THEME_SYSTEM

fun PreferencesDto.toAppPreferences(): AppPreferences = AppPreferences(
    theme = normalizeThemeChoice(theme),
    reducedMotion = reducedMotion,
    largerText = largerText,
    highContrast = highContrast
)

class AppPreferencesStore(context: Context) {
    private val store = context.applicationContext
        .getSharedPreferences("field_repository_preferences", Context.MODE_PRIVATE)

    fun read(): AppPreferences = AppPreferences(
        theme = normalizeThemeChoice(store.getString(KEY_THEME, null)),
        reducedMotion = store.getBoolean(KEY_REDUCED_MOTION, false),
        largerText = store.getBoolean(KEY_LARGER_TEXT, false),
        highContrast = store.getBoolean(KEY_HIGH_CONTRAST, false)
    )

    fun write(preferences: AppPreferences) {
        store.edit()
            .putString(KEY_THEME, normalizeThemeChoice(preferences.theme))
            .putBoolean(KEY_REDUCED_MOTION, preferences.reducedMotion)
            .putBoolean(KEY_LARGER_TEXT, preferences.largerText)
            .putBoolean(KEY_HIGH_CONTRAST, preferences.highContrast)
            .apply()
    }

    fun clear() {
        store.edit().clear().apply()
    }

    fun pendingSince(): Long? = store.getLong(KEY_PENDING_SINCE, 0L).takeIf { it > 0L }

    fun markPending(at: Long = System.currentTimeMillis()) {
        store.edit().putLong(KEY_PENDING_SINCE, at).apply()
    }

    fun clearPending(at: Long?) {
        if (at == null || pendingSince() == at) store.edit().remove(KEY_PENDING_SINCE).apply()
    }

    private companion object {
        const val KEY_PENDING_SINCE = "pendingSince"
        const val KEY_THEME = "theme"
        const val KEY_REDUCED_MOTION = "reducedMotion"
        const val KEY_LARGER_TEXT = "largerText"
        const val KEY_HIGH_CONTRAST = "highContrast"
    }
}

suspend fun syncAppPreferences(
    repository: FieldRepository,
    local: AppPreferences,
    store: AppPreferencesStore? = null
): AppPreferences {
    val remote = runCatching { repository.myPreferences() }.getOrNull()
    val pending = store?.pendingSince()
    if (remote == null || localPreferencesWin(pending, remote.updatedAt)) {
        runCatching { repository.savePreferences(local.toBody()) }
            .onSuccess { store?.clearPending(pending) }
        return local
    }
    store?.clearPending(pending)
    val resolved = remote.toAppPreferences()
    store?.write(resolved)
    return resolved
}

internal fun localPreferencesWin(pendingSince: Long?, remoteUpdatedAt: String?): Boolean {
    if (pendingSince == null) return false
    val remoteAt = remoteUpdatedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        ?: return true
    return pendingSince > remoteAt
}

private fun AppPreferences.toBody(): PreferencesDto = PreferencesDto(
    theme = normalizeThemeChoice(theme),
    reducedMotion = reducedMotion,
    largerText = largerText,
    highContrast = highContrast
)

val LocalAppPreferences = staticCompositionLocalOf { AppPreferences.Default }

@Composable
fun ProvideAppPreferences(preferences: AppPreferences, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val density = remember(base, preferences.fontScale) {
        if (preferences.fontScale == 1f) base else Density(base.density, base.fontScale * preferences.fontScale)
    }
    CompositionLocalProvider(
        LocalAppPreferences provides preferences,
        LocalDensity provides density,
        content = content
    )
}

private data class ThemeOption(val value: String, val label: String, val helper: String, val icon: ImageVector)

private val THEME_OPTIONS = listOf(
    ThemeOption(
        value = THEME_SYSTEM,
        label = "Match my device",
        helper = "Matches your device.",
        icon = Icons.Filled.PhoneAndroid
    ),
    ThemeOption(
        value = THEME_LIGHT,
        label = "Light",
        helper = "Paper white.",
        icon = Icons.Filled.LightMode
    ),
    ThemeOption(
        value = THEME_DARK,
        label = "Dark",
        helper = "For low light.",
        icon = Icons.Filled.DarkMode
    )
)

private data class AccessibilityOption(
    val label: String,
    val helper: String,
    val read: (AppPreferences) -> Boolean,
    val write: (AppPreferences, Boolean) -> AppPreferences
)

private val ACCESSIBILITY_OPTIONS = listOf(
    AccessibilityOption(
        label = "Reduce motion",
        helper = "Turns off animations. Your device setting still applies.",
        read = { it.reducedMotion },
        write = { preferences, next -> preferences.copy(reducedMotion = next) }
    ),
    AccessibilityOption(
        label = "Larger text",
        helper = "About 10% larger.",
        read = { it.largerText },
        write = { preferences, next -> preferences.copy(largerText = next) }
    ),
    AccessibilityOption(
        label = "High contrast",
        helper = "Stronger borders and darker muted text.",
        read = { it.highContrast },
        write = { preferences, next -> preferences.copy(highContrast = next) }
    )
)

@Composable
fun AppearanceScreen(
    repository: FieldRepository,
    current: AppPreferences,
    onChanged: (AppPreferences) -> Unit,
    onBack: () -> Unit,
    onOpenMyAiKeys: () -> Unit,
    onOpenTwoStepVerification: () -> Unit,
    modifier: Modifier = Modifier,
    onSignOut: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    val systemDark = isSystemInDarkTheme()
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val store = remember { AppPreferencesStore(appContext) }

    LaunchedEffect(Unit) {
        val resolved = syncAppPreferences(repository, current, store)
        if (resolved != current) onChanged(resolved)
    }

    fun apply(next: AppPreferences) {
        if (next == current) return
        onChanged(next)
        error = null
        saveJob?.cancel()
        val changedAt = System.currentTimeMillis()
        store.markPending(changedAt)
        saveJob = scope.launch {
            saving = true
            runCatching { repository.savePreferences(next.toBody()) }
                .onSuccess { store.clearPending(changedAt) }
                .onFailure {
                    if (it !is kotlinx.coroutines.CancellationException && !repository.isOnline(appContext)) {
                        error = "Saved on this phone. Syncs when you're online."
                    } else if (it !is kotlinx.coroutines.CancellationException) {
                        error = it.apiErrorMessage("Unable to save preferences")
                    }
                }
            saving = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "Appearance & accessibility",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "How the app looks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.field.muted
            )
        }

        PreferenceCard {
            PreferenceCardHeading(Icons.Filled.Palette, "Appearance")
            Text(
                buildString {
                    append("Applies everywhere you sign in.")
                    if (current.theme == THEME_SYSTEM) {
                        append(" Your device is currently ")
                        append(if (systemDark) THEME_DARK else THEME_LIGHT)
                        append(".")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted
            )
            THEME_OPTIONS.forEach { option ->
                ThemeChoiceRow(
                    option = option,
                    selected = current.theme == option.value,
                    onSelect = { apply(current.copy(theme = option.value)) }
                )
            }
        }

        PreferenceCard {
            PreferenceCardHeading(Icons.Filled.Accessibility, "Accessibility")
            Text(
                "Applies instantly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted
            )
            ACCESSIBILITY_OPTIONS.forEach { option ->
                AccessibilitySwitchRow(
                    label = option.label,
                    helper = option.helper,
                    checked = option.read(current),
                    onCheckedChange = { next -> apply(option.write(current, next)) }
                )
            }
            SaveStatusLine(saving = saving, error = error)
        }

        PreferenceCard {
            PreferenceCardHeading(Icons.Filled.VpnKey, "My AI keys")
            Text(
                "Optional. Use your own OpenAI, Gemini or Claude key for AI features, billed to you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted
            )
            OutlinedButton(onClick = onOpenMyAiKeys) { Text("Open my AI keys") }
        }

        WeeklyDigestCard(repository = repository)

        AccountPasswordCard(repository = repository, onSignOut = onSignOut)

        PreferenceCard {
            PreferenceCardHeading(Icons.Filled.Lock, "Two-step verification")
            Text(
                "Also ask for a code from an authenticator app at sign-in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted
            )
            OutlinedButton(onClick = onOpenTwoStepVerification) { Text("Open two-step verification") }
        }
    }
}

@Composable
private fun PreferenceCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun PreferenceCardHeading(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.field.brandTile),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.field.onBrandTile,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ThemeChoiceRow(option: ThemeOption, selected: Boolean, onSelect: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.field.hairline,
                shape = shape
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FieldRadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    option.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(option.helper, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        }
    }
}

@Composable
private fun WeeklyDigestCard(repository: FieldRepository) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<WeeklyDigestPreferenceDto?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        state = runCatching { repository.weeklyDigestPreference() }.getOrNull()
    }
    val current = state
    if (current == null || !WeeklyDigestRules.showSwitch(current)) return
    PreferenceCard {
        PreferenceCardHeading(Icons.Filled.Event, WeeklyDigestRules.LABEL)
        AppToggle(
            label = WeeklyDigestRules.LABEL,
            description = WeeklyDigestRules.description(current),
            checked = current.enabled,
            busy = saving,
            busyLabel = "Saving",
            onCheckedChange = { next ->
                error = null
                scope.launch {
                    saving = true
                    val (_, failure) = WeeklyDigestRules.toggle(
                        current,
                        next,
                        save = { repository.saveWeeklyDigestPreference(it) },
                        show = { state = it }
                    )
                    error = failure?.apiErrorMessage("Couldn't save. Try again.")
                    saving = false
                }
            }
        )
        SaveStatusLine(saving = saving, error = error)
    }
}

@Composable
private fun AccessibilitySwitchRow(
    label: String,
    helper: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    AppToggle(label = label, description = helper, checked = checked, onCheckedChange = onCheckedChange)
}

@Composable
private fun SaveStatusLine(saving: Boolean, error: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saving && error == null) {
            FieldCircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            error ?: if (saving) "Saving to your account…" else "Saves automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.field.muted
        )
    }
}


