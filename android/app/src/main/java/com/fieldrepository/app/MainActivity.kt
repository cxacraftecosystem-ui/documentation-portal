package com.fieldrepository.app

import android.Manifest
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Timeline
import com.fieldrepository.app.ui.FieldIslandNav
import com.fieldrepository.app.ui.NavGroup
import com.fieldrepository.app.ui.visibleNavItems
import com.fieldrepository.app.ui.IslandEntry
import com.fieldrepository.app.ui.IslandGroup
import com.fieldrepository.app.ui.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.LocalContentColor
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.ArtisanCreateRequest
import com.fieldrepository.app.data.CarryContext
import com.fieldrepository.app.data.CarryContextStore
import com.fieldrepository.app.data.CarryNode
import com.fieldrepository.app.data.CompletionCellDto
import com.fieldrepository.app.data.CompletionMatrixDto
import com.fieldrepository.app.data.CraftCreateRequest
import com.fieldrepository.app.data.DataAccessGrantDto
import com.fieldrepository.app.data.DataAccessScopeItemDto
import com.fieldrepository.app.data.DataAccessTierInfo
import com.fieldrepository.app.data.EntryCommentDto
import com.fieldrepository.app.data.MyGrantsDto
import com.fieldrepository.app.data.RecordRevisionDto
import com.fieldrepository.app.data.DashboardStats
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.GoogleAuthClient
import com.fieldrepository.app.data.LocationRequest
import com.fieldrepository.app.data.ProductCreateRequest
import com.fieldrepository.app.data.QuestionnaireInterviewCreateRequest
import com.fieldrepository.app.data.QuestionnaireInterviewDetailDto
import com.fieldrepository.app.data.QuestionnaireInterviewUpdateRequest
import com.fieldrepository.app.data.QuestionnaireQuestionCreateRequest
import com.fieldrepository.app.data.QuestionnaireQuestionDto
import com.fieldrepository.app.data.QuestionnaireQuestionUpdateRequest
import com.fieldrepository.app.data.QuestionnaireResponseRequest
import com.fieldrepository.app.data.QuestionnaireSectionCreateRequest
import com.fieldrepository.app.data.QuestionnaireSectionDto
import com.fieldrepository.app.data.QuestionnaireSectionUpdateRequest
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.data.ToolCreateRequest
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.WorkshopCreateRequest
import com.fieldrepository.app.data.apiErrorMessage
import com.fieldrepository.app.data.occurrenceDate
import com.fieldrepository.app.ui.ApiKeysScreen
import com.fieldrepository.app.ui.AppPreferences
import com.fieldrepository.app.ui.AppPreferencesStore
import com.fieldrepository.app.ui.AppNavigationDrawerContent
import com.fieldrepository.app.ui.AppearanceScreen
import com.fieldrepository.app.ui.Body
import com.fieldrepository.app.ui.NavDestination
import com.fieldrepository.app.ui.ArtisanPhoneField
import com.fieldrepository.app.ui.artisanPhoneValidationError
import com.fieldrepository.app.ui.Canvas
import com.fieldrepository.app.ui.CarryPrefillBanner
import com.fieldrepository.app.ui.CarryPrefillDefaults
import com.fieldrepository.app.ui.CarryPrefillState
import com.fieldrepository.app.ui.CarryScope
import com.fieldrepository.app.ui.CarryScopeState
import com.fieldrepository.app.ui.carryScope
import com.fieldrepository.app.ui.rememberCarryPrefill
import com.fieldrepository.app.ui.Coral
import com.fieldrepository.app.ui.DataBrowserScreen
import com.fieldrepository.app.ui.FieldRepositoryTheme
import com.fieldrepository.app.ui.ProvideAppPreferences
import com.fieldrepository.app.ui.SearchRecordTypes
import com.fieldrepository.app.ui.SearchScreen
import com.fieldrepository.app.ui.SearchableMultiSelectField
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.asSelectOptions
import com.fieldrepository.app.ui.GrantAccessFields
import com.fieldrepository.app.ui.RequestAccessFields
import com.fieldrepository.app.ui.TaskAdminScreen
import com.fieldrepository.app.ui.field
import com.fieldrepository.app.ui.Muted
import com.fieldrepository.app.ui.resolveDarkTheme
import com.fieldrepository.app.ui.syncAppPreferences
import com.fieldrepository.app.ui.SurfaceCard
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import retrofit2.HttpException
import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.ArtisanAnswerDto
import com.fieldrepository.app.data.ArtisanDetailDto
import com.fieldrepository.app.data.ArtisanQuestionnaireDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.data.CreatedRecordDto
import com.fieldrepository.app.data.AppScope
import com.fieldrepository.app.data.AddressReferenceDto
import com.fieldrepository.app.data.LocationDto
import com.fieldrepository.app.data.AppReleaseDto
import com.fieldrepository.app.data.FeedbackDto
import com.fieldrepository.app.data.FeedbackUpsertRequest
import com.fieldrepository.app.data.MediaFileDto
import com.fieldrepository.app.data.AppSettingUpdateRequest
import com.fieldrepository.app.data.PendingReviewDto
import com.fieldrepository.app.data.StagedMedia
import com.fieldrepository.app.data.TaskDto
import com.fieldrepository.app.data.WorkshopAccessLevelDto
import com.fieldrepository.app.data.WorkshopAssignmentDto
import com.fieldrepository.app.data.WorkshopSubmissionCheckDto
import com.fieldrepository.app.data.titleCasePreview
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import com.fieldrepository.app.data.ProductDetailDto
import com.fieldrepository.app.data.ProcessCreateRequest
import com.fieldrepository.app.data.ProcessDetailDto
import com.fieldrepository.app.data.ProcessStepDto
import com.fieldrepository.app.data.ProcessStepRequest
import com.fieldrepository.app.data.ToolDetailDto
import com.fieldrepository.app.data.WorkshopDetailDto
import androidx.compose.runtime.mutableStateListOf
import com.fieldrepository.app.ui.ArtisanQuestionnairePanel
import com.fieldrepository.app.ui.LocationFieldsSection
import com.fieldrepository.app.ui.artisanLocationRequirementError
import com.fieldrepository.app.ui.MediaThumb
import com.fieldrepository.app.ui.MediaViewerDialog
import com.fieldrepository.app.ui.ProvenanceSection
import com.fieldrepository.app.ui.RecordingIndicator
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tokenStore = TokenStore(applicationContext)
        val repository = FieldRepository(ApiClient.create(tokenStore), tokenStore)
        val googleAuthClient = GoogleAuthClient(this)
        // Appearance is read SYNCHRONOUSLY, before the first frame is composed. That is the whole
        // point of the device-local copy: the account's row arrives over the network, and deciding
        // the theme from it would flash a light app at somebody who chose Dark. The store outlives
        // sign-out on purpose, so the look does not reset every time the session does.
        val preferencesStore = AppPreferencesStore(applicationContext)
        val storedPreferences = preferencesStore.read()
        setContent {
            var preferences by remember { mutableStateOf(storedPreferences) }
            FieldRepositoryTheme(darkTheme = resolveDarkTheme(preferences.theme, isSystemInDarkTheme())) {
                // "Larger text" is applied here — ProvideAppPreferences scales the density every `sp`
                // in the app resolves against, so type grows and the layout does not.
                ProvideAppPreferences(preferences) {
                    RepositoryApp(
                        repository = repository,
                        googleAuthClient = googleAuthClient,
                        preferences = preferences,
                        onPreferencesChanged = { next ->
                            // Apply first, persist second: the switch must feel instant. The screen
                            // itself owns the PUT; the device copy is ours.
                            preferences = next
                            preferencesStore.write(next)
                        }
                    )
                }
            }
        }
    }
}

private enum class EntryMode(
    val label: String,
    val actionTitle: String,
    val editable: Boolean = false,
    /**
     * False = the menu only, no dashboard tile. The web keeps Search and the Data Browser in the
     * nav's "Browse" group and off the dashboard grid (its twelve tiles are the record types plus
     * Users and Settings), so neither earns a tile here either.
     */
    val onDashboard: Boolean = true
) {
    ARTISAN("Artisan", "Record artisan", editable = true),
    PRODUCT("Product", "Record product", editable = true),
    PROCESS("Process", "Document process", editable = true),
    TOOL("Tool", "Record tool", editable = true),
    QUESTIONNAIRE("Questionnaire", "Take interview", editable = true),
    MEDIA("Miscellaneous Media", "Upload media"),
    VIEW_DATA("View Data", "Browse records"),
    // /search on the web. Its nav entry there is labelled "Browse records" — the phrase this app has
    // long used for the View Data card — so the page's OWN title is used instead, rather than putting
    // two identically-named entries in the menu.
    SEARCH("Search", "Search", onDashboard = false),
    // /data on the web: the whole repository as a directory tree, gated on require_dataset_downloader.
    // Named after the page's own title ("Data Browser") for the same reason as SEARCH above.
    DATA_BROWSER("Data Browser", "Data Browser", onDashboard = false),
    // "Tasks" matches the web nav label exactly. The card is the ASSIGNEE's to-do list; assigning work
    // is an admin action and lives in the admin hub.
    TASKS("Tasks", "My tasks"),
    SHARING("Sharing", "Share data access"),
    // Workshop access is the other half of Sharing: Sharing is researcher-to-researcher over records,
    // this is admin-to-researcher over a workshop. Kept as its own card so a new user can find "how do
    // I get into this workshop" without reading the sharing screen first.
    WORKSHOP_ACCESS("Workshop access", "Request workshop access"),
    USERS("Users", "Manage users"),
    // Craft and Workshop are the least frequently edited, so they sit last on the dashboard.
    CRAFT("Craft", "Add craft", editable = true),
    WORKSHOP("Workshop", "Record workshop", editable = true)
}

/** Where the user currently is. null-mode dashboard is replaced by this explicit machine. */
private sealed interface Screen {
    data object Dashboard : Screen
    data class Create(
        val mode: EntryMode,
        val prefill: Prefill? = null,
        /**
         * SEARCH only, and only from a tapped dashboard total: the [SearchRecordTypes] bucket that
         * figure counted. Carried on the destination alongside [prefill] because the search screen
         * owns its own filter state and takes no initial record type.
         */
        val searchFocus: String? = null
    ) : Screen
    data class Browse(val mode: EntryMode) : Screen
    data class Edit(val mode: EntryMode, val recordId: String) : Screen
    // Hamburger-only screens (not on the dashboard).
    data object MyActivity : Screen
    data object ToolAssign : Screen
    data object Feedback : Screen
    data object Settings : Screen
    /** This account's Appearance + Accessibility — /settings on the web. Open to every user. */
    data object Appearance : Screen
    /**
     * The /data directory-tree browser. Its own Screen rather than a Create mode because it owns its
     * whole viewport: it draws its own top bar and lays out with a LazyColumn, which must never be
     * nested inside the scrolling Column the rest of the app renders into.
     */
    data object DataBrowser : Screen
    /**
     * Admin hub, opened from the dashboard "Settings" card (admins only). [section] pre-opens one of
     * its tools, which is how "Assignment board" on the Tasks screen lands straight on the board.
     */
    data class AdminHub(val section: AdminHubEntry? = null) : Screen
}

/** Context carried forward from a just-saved artisan into a follow-up record. */
private data class Prefill(
    val artisanId: String? = null,
    val artisanName: String? = null,
    val place: String? = null,
    val craftId: String? = null,
    val craftName: String? = null
)

/**
 * The same handoff as a carry-context bag, so the in-memory route and the stored one describe the
 * sitting in one vocabulary and cannot drift apart.
 *
 * Nothing regulated crosses over: [CarryContext] has no Aadhaar or Pehchan field to put one in, and
 * neither does [Prefill]. That is deliberate on both — a shared field handset is the last place to
 * park a government identifier.
 */
private fun Prefill.toCarryContext(): CarryContext = CarryContext(
    artisanId = artisanId,
    artisanName = artisanName,
    place = place,
    craftId = craftId,
    craftName = craftName
)

/**
 * [rememberCarryPrefill] with the two things every record form would otherwise repeat: where the bag
 * lives, and whose it is.
 *
 * [handoff] is the [Prefill] a tap on [CarryForwardPanel] carried across in memory. It is seconds
 * old, so it beats anything in storage — and it is banked on arrival, which is what lets a
 * researcher who leaves via the dashboard and comes back an hour later still be offered it.
 */
@Composable
private fun rememberFormCarry(
    repository: FieldRepository,
    enabled: Boolean,
    applies: Set<CarryNode>,
    scopes: List<CarryScope>,
    handoff: Prefill? = null,
    onApply: (CarryContext) -> Unit
): CarryPrefillState {
    val appContext = LocalContext.current.applicationContext
    val store = remember(appContext) { CarryContextStore(appContext) }
    // Read once: cachedUser() re-parses the stored account on every call, and a signed-in user can
    // only change by tearing this whole tree down.
    val userId = remember(repository) { repository.cachedUser()?.id }
    return rememberCarryPrefill(
        store = store,
        userId = userId,
        enabled = enabled,
        handoff = handoff?.toCarryContext(),
        applies = applies,
        scopes = scopes,
        onApply = onApply
    )
}

/**
 * A field as the unsaved-work guard should see it: blank for as long as it still holds exactly what
 * the carry prefill put there.
 *
 * A carried value is the app's suggestion, not the researcher's work, so a prefilled form nobody has
 * touched must not answer "save your changes?" on the way out. The web arrives at the same place
 * from the other side — its guard only latches on real input, so a programmatic prefill never
 * registers at all. Edit the value and it stops matching, and from then on it counts like anything
 * else they typed.
 */
private fun String.exceptCarried(carried: String?): String = if (carried != null && this == carried) "" else this

/** Pictorial icon for each record type, used on the dashboard cards and the drawer. */
private fun EntryMode.icon(): ImageVector = when (this) {
    EntryMode.ARTISAN -> Icons.Filled.Person
    EntryMode.PRODUCT -> Icons.Filled.Inventory2
    EntryMode.PROCESS -> Icons.Filled.AccountTree
    EntryMode.TOOL -> Icons.Filled.Build
    EntryMode.QUESTIONNAIRE -> Icons.Filled.Quiz
    EntryMode.WORKSHOP -> Icons.Filled.Groups
    EntryMode.CRAFT -> Icons.Filled.Brush
    EntryMode.MEDIA -> Icons.Filled.PermMedia
    EntryMode.VIEW_DATA -> Icons.Filled.Visibility
    EntryMode.SEARCH -> Icons.Filled.Search
    EntryMode.DATA_BROWSER -> Icons.Filled.Storage
    EntryMode.TASKS -> Icons.AutoMirrored.Filled.Assignment
    EntryMode.SHARING -> Icons.Filled.Share
    EntryMode.WORKSHOP_ACCESS -> Icons.Filled.LockOpen
    EntryMode.USERS -> Icons.Filled.ManageAccounts
}

@Composable
private fun RepositoryApp(
    repository: FieldRepository,
    googleAuthClient: GoogleAuthClient,
    preferences: AppPreferences,
    onPreferencesChanged: (AppPreferences) -> Unit
) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(repository.cachedUser()) }
    var loading by remember { mutableStateOf(user == null && repository.hasToken()) }
    var error by remember { mutableStateOf<String?>(null) }

    // Persistent login: start from the cached profile so minimise/resume never logs the user out.
    // Refresh in the background and only clear the session if the token is genuinely rejected (401).
    LaunchedEffect(Unit) {
        if (repository.hasToken()) {
            runCatching { repository.refreshUser() }
                .onSuccess { user = it }
                .onFailure { err ->
                    if (err is HttpException && err.code() == 401) {
                        repository.logout()
                        user = null
                        error = "Your session expired. Please sign in again."
                    } else if (user == null) {
                        error = err.message ?: "Unable to reach the server. Check your connection and try again."
                    }
                }
        }
        loading = false
    }

    // Appearance follows the ACCOUNT, not the handset: reconcile this device's copy with
    // /preferences/me once per sign-in. A saved row wins; no row means this device seeds the account,
    // so the look travels to the next device the researcher signs in on. Never throws — a failure
    // simply leaves what the device already had on screen.
    val latestPreferences by rememberUpdatedState(preferences)
    LaunchedEffect(user?.id) {
        if (user == null) return@LaunchedEffect
        onPreferencesChanged(syncAppPreferences(repository, latestPreferences))
    }

    // Offline outbox auto-sync: drain queued entries on login/start, whenever the network returns, and
    // on a periodic fallback. `pendingUploads` powers the "saved offline — uploading" banner.
    val appContext = LocalContext.current.applicationContext
    var pendingUploads by remember { mutableStateOf(0) }
    LaunchedEffect(user) {
        if (user == null) return@LaunchedEffect
        while (true) {
            runCatching { repository.syncOutbox(appContext) }
            pendingUploads = runCatching { repository.pendingUploads(appContext) }.getOrDefault(0)
            delay(if (pendingUploads > 0) 12_000L else 45_000L)
        }
    }
    DisposableEffect(user) {
        if (user == null) return@DisposableEffect onDispose {}
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                scope.launch {
                    runCatching { repository.syncOutbox(appContext) }
                    pendingUploads = runCatching { repository.pendingUploads(appContext) }.getOrDefault(0)
                }
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .padding(16.dp)
    ) {
        when {
            loading -> Text("Loading repository...", color = Muted, modifier = Modifier.align(Alignment.Center))
            user == null -> LoginScreen(
                error = error,
                busy = loading,
                onLogin = { email, password ->
                    scope.launch {
                        loading = true
                        error = null
                        runCatching { repository.login(email, password) }
                            .onSuccess { user = it }
                            .onFailure { error = it.message ?: "Login failed" }
                        loading = false
                    }
                },
                onGoogleLogin = {
                    scope.launch {
                        loading = true
                        error = null
                        runCatching {
                            val idToken = googleAuthClient.getIdToken()
                            repository.loginWithGoogle(idToken)
                        }
                            .onSuccess { user = it }
                            .onFailure { error = it.message ?: "Google sign-in failed" }
                        loading = false
                    }
                }
            )
            else -> HomeScreen(
                repository = repository,
                user = user!!,
                preferences = preferences,
                onPreferencesChanged = onPreferencesChanged,
                onLogout = {
                    scope.launch {
                        runCatching { googleAuthClient.clear() }
                        repository.logout()
                        user = null
                    }
                }
            )
        }
        if (user != null && pendingUploads > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .background(Color(0xFF2A2520), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.CloudOff, contentDescription = null, tint = Color(0xFFE0C9B0), modifier = Modifier.size(16.dp))
                Text(
                    "$pendingUploads entr${if (pendingUploads == 1) "y" else "ies"} saved on this device — uploading when you're online.",
                    color = Color(0xFFE0C9B0),
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LoginScreen(
    error: String?,
    busy: Boolean,
    onLogin: (String, String) -> Unit,
    onGoogleLogin: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Field Repository",
            display = true,
            fontSize = 34.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Document field visits, artisan knowledge, craft practices, objects, tools, conversations and locations in one shared archive.",
            color = Body,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Canvas)) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (!error.isNullOrBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Button(
                    enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                    onClick = { onLogin(email, password) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Signing in..." else "Login")
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = onGoogleLogin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_google_g),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "Please wait..." else "Sign in with Google")
                    }
                }
                // Steer researchers to Google sign-in. Many were typing into the email/password fields
                // (meant only for admin-issued password accounts) and getting locked out.
                Text(
                    "Researchers: please use \"Sign in with Google\" above. The email & password fields are only for special accounts an administrator set up with a password — if you normally use your Google account, do not type a password here.",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    repository: FieldRepository,
    user: UserDto,
    preferences: AppPreferences,
    onPreferencesChanged: (AppPreferences) -> Unit,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<DashboardStats?>(null) }
    var sections by remember { mutableStateOf<List<QuestionnaireSectionDto>>(emptyList()) }
    var crafts by remember { mutableStateOf<List<CraftDto>>(emptyList()) }
    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    var carryForward by remember { mutableStateOf<Prefill?>(null) }
    // "I can no longer see this record" and "there is no signal" are different answers, and the
    // carry prefill treats them differently: only a list that actually arrived is entitled to
    // disown a carried id. Held here because the two lookups below feed every create form.
    var lookupState by remember { mutableStateOf(CarryScopeState.PENDING) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var message by remember { mutableStateOf<String?>(null) }
    // Every gate below mirrors one backend dependency (see the capability block near ROLE_RANK).
    // Do not re-derive a rule inline here: this screen used to hard-code admin-only variants of half
    // of them, which locked professors out of screens the API would happily have served.
    val isAdmin = user.isAdminUser()
    val isMasterAdmin = user.isMasterAdminUser()
    // `require_questionnaire_manager`: Professor and above, or an explicit grant — NOT master-only.
    val isQuestionnaireManager = user.canManageTheQuestionnaire()
    // `require_reviewer`: Field Contributor and above (everyone with someone beneath them on the
    // ladder), or an explicit review grant. Which records they may act on is decided per record by
    // the server's can_review_record.
    val canReview = user.canAccessReview()
    // Provenance (created-by + per-field edit history) on the View Data screen: admins always, plus
    // any user explicitly granted the "view provenance" privilege.
    val canViewProvenance = isAdmin || user.canViewProvenance
    // `require_dataset_downloader`: Professor and above, or an explicit grant.
    val canDownloadDataset = user.canDownloadTheDataset()
    /**
     * Who is allowed the admin-view switch at all: ADMIN (50) and MASTER_ADMIN (60), nobody else.
     *
     * A PROFESSOR (40) is deliberately outside it. The switch does not grant anything — it only
     * NARROWS an admin's own chrome — so on a professor it would be a control that changes nothing
     * they can see, while flipping it would quietly subtract from screens (`adminChrome` below) that
     * their role, not the toggle, is what entitles them to.
     */
    val canToggleAdminView = isAdmin

    // Master admin lands in admin view; other admins opt in from the menu.
    //
    // Kept as a REQUEST and read through [adminView] just below, because the request outlives the
    // entitlement in two ways. A role lowered underneath a live session leaves the flag latched on
    // with no chip left to turn it off, and the "Turn admin view back on" card is reachable by a
    // user who never had the switch in the first place. ANDing the gate in at every read means a
    // stale ON simply cannot survive the gate failing, which a one-shot reset would not guarantee.
    var adminViewRequested by remember { mutableStateOf(isMasterAdmin) }
    val adminView = canToggleAdminView && adminViewRequested

    /**
     * Admin view is a NARROWING switch, exactly as on the web (`adminChromeVisible`): with it OFF an
     * admin browses the repository as an ordinary user, and ADMIN CHROME disappears — the settings
     * hub, user management, managed API keys, workshop-access administration and the task assignment
     * board. It can never widen anything: every role/capability check below is still ANDed with it,
     * and it is `true` for everyone who has no toggle at all (professors, capability grantees), who
     * must not be narrowed by a control they do not own.
     *
     * What is deliberately NOT chrome, matching ADMIN_CHROME_ROUTES on the web: reviewing (a Field
     * Contributor capability an admin merely also holds), being a task ASSIGNEE, the Data Browser and
     * Search — an ordinary user reaches all of those, so "browse as an ordinary user" leaves them be.
     */
    val adminChrome = !isAdmin || adminView

    // Unsaved-changes guard: a record form on screen registers its dirty-state + save action here, and
    // any attempt to leave (system Back / in-app back arrow) is intercepted to offer Save / Discard so
    // an accidental Back never loses a record or its in-progress recordings. [pendingExit] holds the
    // navigation to run once the user decides.
    val unsavedGuard = remember { UnsavedGuard() }
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }

    val context = LocalContext.current
    // Over-the-air update: see if the master admin pushed a newer build than the one installed; if so,
    // force a one-tap self-update. We check both on launch AND every time the app is resumed, so a
    // freshly-pushed update is caught the next time the user foregrounds the app — not only on a cold
    // start. `pushingUpdate` guards the publish action.
    var pendingUpdate by remember { mutableStateOf<AppReleaseDto?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var pushingUpdate by remember { mutableStateOf(false) }
    suspend fun checkForUpdate() {
        runCatching {
            val latest = repository.latestAppRelease()
            if (latest.versionCode > repository.installedVersionCode(context) && !latest.url.isNullOrBlank()) {
                pendingUpdate = latest
            }
        }
    }
    LaunchedEffect(Unit) { checkForUpdate() }
    // Re-check on resume. The activity is the LifecycleOwner; cast defensively so a failure simply
    // falls back to the launch-time check rather than crashing.
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scope.launch { checkForUpdate() }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    // First-run walkthrough: new sign-ups see it automatically; anyone can reopen it from the menu.
    var showWalkthrough by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!walkthroughSeen(context)) showWalkthrough = true }

    // Surface a message, but swallow the noise from a coroutine being cancelled when a screen is
    // left during navigation (e.g. "The coroutine scope left the composition") — that is expected,
    // not a real error, and must not get stuck on screen.
    fun showMessage(text: String?) {
        if (text.isNullOrBlank()) return
        val lower = text.lowercase()
        if ("left the composition" in lower || "was cancelled" in lower || "job was cancelled" in lower) return
        message = text
    }

    // Who may START a new entry of each kind, mirroring the backend dependency on the POST route.
    // A volunteer is deliberately allowed to answer interviews, upload media, browse, use tasks and
    // sharing, and request workshop access — everything `require_record_creator` does NOT cover.
    fun canCreate(mode: EntryMode): Boolean = when (mode) {
        // require_craft_manager / require_workshop_manager: Professor+ by rank, no grant.
        EntryMode.CRAFT -> user.canManageTheCrafts()
        EntryMode.WORKSHOP -> user.canManageTheWorkshops()
        // require_professor on GET/PATCH /users, AND admin chrome: /users is listed in the web's
        // ADMIN_CHROME_ROUTES, so an admin with admin view off loses it while a professor — who has
        // no toggle — keeps it. Role first, toggle second: the toggle can only ever subtract.
        EntryMode.USERS -> user.canManageUsers() && adminChrome
        // require_record_creator: Researcher and above. Showing a field contributor or a volunteer
        // these forms only bought them a 403 after filling one in.
        EntryMode.ARTISAN, EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.TOOL -> user.canCreateRecords()
        // require_dataset_downloader: Professor and above, or an explicit grant — the same gate the
        // dataset download already uses, because /data/tree serves the same archive.
        EntryMode.DATA_BROWSER -> canDownloadDataset
        /*
         * Open to every authenticated user, and QUESTIONNAIRE stays here on purpose even though the
         * ladder moved. POST /questionnaire/interviews folds a post for an artisan set that has
         * already been interviewed into the existing row and only calls `assert_can_create_records`
         * on the branch that OPENS a new one — so a field contributor or a volunteer answering an
         * interview somebody else started is doing the thing these two tiers exist for, on this
         * screen. Gating the screen would take that away to save them a 403 on the rarer case.
         */
        EntryMode.QUESTIONNAIRE, EntryMode.MEDIA, EntryMode.VIEW_DATA, EntryMode.TASKS,
        EntryMode.SHARING, EntryMode.WORKSHOP_ACCESS, EntryMode.SEARCH -> true
    }

    // Keyed on role and on the one grant that still widens a rank floor here. Crafts and workshops
    // used to be in this list; they are rank-only now, so their columns cannot change the answer.
    val dashboardModes = remember(user.role, user.canManageQuestionnaire) {
        EntryMode.entries.filter { it != EntryMode.USERS || user.canManageUsers() }
    }

    /**
     * Where a menu entry / dashboard card actually goes. Almost everything is a Create mode; the
     * screens that own their whole viewport get their own Screen instead (see [Screen.DataBrowser]).
     */
    fun screenFor(mode: EntryMode): Screen =
        if (mode == EntryMode.DATA_BROWSER) Screen.DataBrowser else Screen.Create(mode)

    fun refresh() {
        scope.launch {
            runCatching { repository.stats() }
                .onSuccess { stats = it }
                .onFailure { showMessage(it.message) }
        }
    }

    suspend fun loadLookups() {
        val gotCrafts = runCatching { repository.crafts() }.onSuccess { crafts = it }.isSuccess
        val gotArtisans = runCatching { repository.artisans() }.onSuccess { artisans = it }.isSuccess
        lookupState = if (gotCrafts && gotArtisans) CarryScopeState.LOADED else CarryScopeState.UNAVAILABLE
    }

    fun refreshLookups() {
        scope.launch { loadLookups() }
    }

    LaunchedEffect(Unit) {
        refresh()
        loadLookups()
        runCatching { repository.questionnaireSections() }
            .onSuccess { sections = it }
            .onFailure { showMessage(it.message) }
    }

    // Leave the current screen, but if a form has unsaved work, route through the Save/Discard prompt
    // first so an accidental Back can't silently drop a record or its in-progress recordings.
    //
    // Declared up here, above the routing functions, because they call it — a Kotlin local function is
    // only in scope after its own declaration, and the guard is no use to a router that cannot see it.
    fun attemptExit(leave: () -> Unit) {
        if (unsavedGuard.dirty && unsavedGuard.onSave != null) {
            pendingExit = leave
        } else {
            leave()
        }
    }

    fun goDashboard() {
        message = null
        screen = Screen.Dashboard
    }

    /**
     * The routing table behind the menu — the one place a [NavDestination] turns into a screen.
     *
     * Exhaustive with no `else` branch on purpose. The menu used to be a second, hand-written list of
     * rows living next to the shared [FIELD_NAV_ITEMS] model, and the two silently drifted apart; a
     * catch-all here would let that happen again by letting a new destination render as a row that
     * does nothing when tapped. Without one, adding to the enum breaks the build instead.
     *
     * Deliberately raw: it changes the screen and asks nobody. Anything the USER taps must go through
     * [navigate] instead, which puts the unsaved-changes guard in front of this.
     */
    fun openDestination(destination: NavDestination) {
        message = null
        when (destination) {
            NavDestination.DASHBOARD -> goDashboard()
            // A dialog rather than a screen: the walkthrough overlays wherever the user already was.
            NavDestination.WALKTHROUGH -> showWalkthrough = true
            NavDestination.RECORD_ARTISAN -> screen = screenFor(EntryMode.ARTISAN)
            NavDestination.RECORD_PRODUCT -> screen = screenFor(EntryMode.PRODUCT)
            NavDestination.DOCUMENT_PROCESS -> screen = screenFor(EntryMode.PROCESS)
            NavDestination.RECORD_TOOL -> screen = screenFor(EntryMode.TOOL)
            NavDestination.TAKE_INTERVIEW -> screen = screenFor(EntryMode.QUESTIONNAIRE)
            NavDestination.UPLOAD_MEDIA -> screen = screenFor(EntryMode.MEDIA)
            NavDestination.ADD_CRAFT -> screen = screenFor(EntryMode.CRAFT)
            NavDestination.RECORD_WORKSHOP -> screen = screenFor(EntryMode.WORKSHOP)
            NavDestination.MY_ACTIVITY -> screen = Screen.MyActivity
            NavDestination.TASKS -> screen = screenFor(EntryMode.TASKS)
            // The web's /search. [EntryMode.SEARCH] keeps the page's own title so the two entries that
            // both want the words "Browse records" cannot collide in one menu.
            NavDestination.BROWSE_RECORDS -> screen = screenFor(EntryMode.SEARCH)
            // The web's /data directory tree, which owns its whole viewport (see [Screen.DataBrowser]).
            NavDestination.VIEW_DATA -> screen = screenFor(EntryMode.DATA_BROWSER)
            NavDestination.SHARE_DATA_ACCESS -> screen = screenFor(EntryMode.SHARING)
            NavDestination.ASSIGN_TOOLS -> screen = Screen.ToolAssign
            // Android has no standalone review queue: reviewing happens inside the record browser,
            // which is the surface [EntryMode.VIEW_DATA] opens and where `canReview` is honoured.
            NavDestination.REVIEW -> screen = screenFor(EntryMode.VIEW_DATA)
            NavDestination.SETTINGS_HUB -> screen = Screen.AdminHub()
            NavDestination.MANAGE_USERS -> screen = screenFor(EntryMode.USERS)
            // "Settings" on the web is a two-column page whose global column is this app's admin hub;
            // what is left for the person themselves is Appearance & accessibility.
            NavDestination.SETTINGS -> screen = Screen.Appearance
            NavDestination.GIVE_FEEDBACK -> screen = Screen.Feedback
        }
    }

    /**
     * A menu tap: the unsaved-changes guard, and then [openDestination].
     *
     * The island bar and the drawer used to call the router directly. That made every chip in the new
     * navigation a silent way out of a half-filled artisan form — taking the audio already recorded
     * into it with no prompt — while the back arrow sitting beside them asked. The departure is the
     * same departure whichever control starts it, so it now asks the same question.
     */
    fun navigate(destination: NavDestination) {
        // Closing the sheet is not the navigation and must not wait on the answer: a drawer left open
        // behind the dialog is still open after "Keep editing", covering the form the user chose to
        // stay on.
        scope.launch { drawerState.close() }
        if (destination == NavDestination.WALKTHROUGH) {
            // The one destination that is not a departure — it draws OVER the page you are already on
            // (see the branch above), so there is nothing to save or discard, and prompting would
            // offer to throw away a form that is not going anywhere.
            openDestination(destination)
        } else {
            attemptExit { openDestination(destination) }
        }
    }

    // System back / in-app back: step to the logical previous screen instead of leaving the app.
    fun goBack() {
        message = null
        screen = when (val s = screen) {
            is Screen.Edit -> Screen.Browse(s.mode)
            is Screen.Browse -> Screen.Dashboard
            is Screen.Create -> Screen.Dashboard
            is Screen.MyActivity -> Screen.Dashboard
            is Screen.ToolAssign -> Screen.Dashboard
            is Screen.Feedback -> Screen.Dashboard
            is Screen.Settings -> Screen.Dashboard
            is Screen.Appearance -> Screen.Dashboard
            is Screen.DataBrowser -> Screen.Dashboard
            // One level at a time: from a tool back to the tool list, and only then out. This is
            // what lets the single header arrow replace the in-page "All admin tools" button — the
            // arrow, the system back gesture and the back gesture all route through here.
            is Screen.AdminHub -> if (s.section != null) Screen.AdminHub() else Screen.Dashboard
            is Screen.Dashboard -> Screen.Dashboard
        }
    }

    // Null where the page has nothing of its own to announce, which is the Dashboard and only the
    // Dashboard: it used to head itself "Field Repository" one line under the bar that already says
    // so, and a name repeated a line apart names nothing.
    //
    // The identity line that used to sit under this — name, role and whether admin view was on — is
    // gone too. It was three facts restated on every single screen, and all three are one tap away
    // in the drawer, where the brand block carries "name · Role" and the admin-view row states the
    // setting in words. A header that repeats what the menu already holds is a header spending the
    // page's first line on itself.
    val headerTitle: String? = when (val s = screen) {
        is Screen.Dashboard -> null
        is Screen.Create -> s.mode.actionTitle
        is Screen.Browse -> "Update ${s.mode.label.lowercase()}"
        // A media file has no edit form (the web opens the object itself), so "Edit …" would be a lie
        // for the one non-editable type search can land on.
        is Screen.Edit -> if (s.mode.editable) "Edit ${s.mode.label.lowercase()}" else s.mode.label
        is Screen.MyActivity -> "My Activity"
        is Screen.ToolAssign -> "Assign tools to artisans"
        is Screen.Feedback -> "App feedback"
        is Screen.Settings -> "Settings"
        is Screen.Appearance -> "Appearance & accessibility"
        is Screen.DataBrowser -> "Data Browser"
        // A reviewer who is not an admin lands on the review tool alone (see the AdminHub branch
        // below), so the header must not announce a hub of admin tools they were never given.
        is Screen.AdminHub -> if (s.section == AdminHubEntry.REVIEWS && !(isAdmin && adminChrome)) {
            AdminHubEntry.REVIEWS.label
        } else {
            "Admin tools"
        }
    }

    /**
     * Which menu row is the page you are on — the drawer's `aria-current`. The inverse of [navigate],
     * so the two are read side by side and a row that opens one screen cannot light up on another.
     *
     * `null` where no row honestly matches. Update and edit screens are the case that matters: their
     * record type DOES have a row, but that row starts a NEW record, so highlighting it would tell the
     * user they are somewhere they are not.
     */
    val currentDestination: NavDestination? = when (val s = screen) {
        is Screen.Dashboard -> NavDestination.DASHBOARD
        is Screen.MyActivity -> NavDestination.MY_ACTIVITY
        is Screen.ToolAssign -> NavDestination.ASSIGN_TOOLS
        is Screen.Feedback -> NavDestination.GIVE_FEEDBACK
        is Screen.Appearance -> NavDestination.SETTINGS
        is Screen.DataBrowser -> NavDestination.VIEW_DATA
        is Screen.AdminHub -> NavDestination.SETTINGS_HUB
        // The legacy app-settings screen, which no menu reaches.
        is Screen.Settings -> null
        is Screen.Browse, is Screen.Edit -> null
        is Screen.Create -> when (s.mode) {
            EntryMode.ARTISAN -> NavDestination.RECORD_ARTISAN
            EntryMode.PRODUCT -> NavDestination.RECORD_PRODUCT
            EntryMode.PROCESS -> NavDestination.DOCUMENT_PROCESS
            EntryMode.TOOL -> NavDestination.RECORD_TOOL
            EntryMode.QUESTIONNAIRE -> NavDestination.TAKE_INTERVIEW
            EntryMode.MEDIA -> NavDestination.UPLOAD_MEDIA
            EntryMode.CRAFT -> NavDestination.ADD_CRAFT
            EntryMode.WORKSHOP -> NavDestination.RECORD_WORKSHOP
            EntryMode.TASKS -> NavDestination.TASKS
            EntryMode.SEARCH -> NavDestination.BROWSE_RECORDS
            EntryMode.SHARING -> NavDestination.SHARE_DATA_ACCESS
            EntryMode.VIEW_DATA -> NavDestination.REVIEW
            EntryMode.USERS -> NavDestination.MANAGE_USERS
            // `screenFor` sends this one to [Screen.DataBrowser] instead, so it is unreachable here —
            // mapped anyway rather than defaulted, so the pair stays a total function of EntryMode.
            EntryMode.DATA_BROWSER -> NavDestination.VIEW_DATA
            // Reached from its dashboard tile; the web has no nav entry for it, so neither do we.
            EntryMode.WORKSHOP_ACCESS -> null
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = drawerState.isClosed && screen !is Screen.Dashboard) {
        attemptExit { goBack() }
    }

    // Right-anchored drawer: wrap in RTL so the sheet slides in from the right (web parity),
    // then flip drawer + page content back to LTR so their own layout reads normally.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    /*
                     * The shared menu, the same model the island bar renders from. It filters itself
                     * with `visibleNavItems`, so nothing here decides who sees what — passing
                     * `adminView` through is the whole of this screen's say in it, and that switch can
                     * only ever subtract the two admin-surface rows from somebody who is an admin.
                     */
                    AppNavigationDrawerContent(
                        user = user,
                        adminMode = adminView,
                        currentDestination = currentDestination,
                        onNavigate = ::navigate,
                        pushingUpdate = pushingUpdate,
                        onToggleAdminView = { adminViewRequested = !adminView },
                        onPushUpdate = {
                            scope.launch { drawerState.close() }
                            if (!pushingUpdate) {
                                pushingUpdate = true
                                Toast.makeText(context, "Publishing this version as the update for everyone…", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    runCatching { repository.publishAppUpdate(context) }
                                        .onSuccess { rel ->
                                            // A transient toast (not a banner that lingers at the bottom of every page
                                            // until the app is closed and reopened).
                                            Toast.makeText(
                                                context,
                                                "Update published (v${rel.versionName}). Everyone else gets it automatically on next open.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        .onFailure {
                                            Toast.makeText(context, it.message ?: "Unable to publish the update", Toast.LENGTH_LONG).show()
                                        }
                                    pushingUpdate = false
                                }
                            }
                        },
                        onLogout = { scope.launch { drawerState.close() }; onLogout() }
                    )
                }
            }
        ) {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Ltr,
                LocalUnsavedGuard provides unsavedGuard
            ) {
                /*
                 * Two hosting shapes. Almost everything renders into the scrolling Column below,
                 * under the app's own header. The two screens branched off here own
                 * their whole viewport instead — the Data Browser lays out with a LazyColumn, which
                 * throws if it is measured inside a parent that scrolls the same way, and both draw
                 * their own back control, so the shared chrome would only duplicate it.
                 */
                when (screen) {
                    is Screen.DataBrowser -> DataBrowserScreen(
                        repository = repository,
                        // Back walks one folder up first; this fires only at the top of the tree.
                        onBack = { attemptExit { goBack() } },
                        onMessage = { showMessage(it) }
                    )

                    is Screen.Appearance -> AppearanceScreen(
                        repository = repository,
                        current = preferences,
                        onChanged = onPreferencesChanged,
                        onBack = { attemptExit { goBack() } }
                    )

                    else -> {
                // Hoisted out of the modifier so the island bar can be told how far this page has
                // travelled. It is handed over as a lambda below, never as a number — see
                // FieldIslandNav's `scrollOffset`.
                //
                // Keyed on the screen, because every destination in this branch renders through the one
                // composition slot below and therefore shared one ScrollState: leaving a long form
                // half-way down opened the NEXT screen already scrolled past its own first fields, with
                // nothing on it to explain why. `key` throws the state away when the destination
                // changes, so each page starts where a page starts.
                val pageScroll = key(screen) { rememberScrollState() }
                // Two Columns, and the split is the whole point: the bar lives in the OUTER one, which
                // does not scroll, so it stays put while the page moves underneath it and can collapse
                // in response. It used to be the first child of the scroller, which meant it simply
                // slid off the top — a collapsing bar you cannot see collapse.
                Column(modifier = Modifier.fillMaxSize()) {
        /*
         * The island bar, the web's DynamicIslandNav on the phone.
         *
         * Both this and the drawer are projections of ONE list — `visibleNavItems(user, adminView)`,
         * the same entries, labels, order and permission predicates the web's NAV_ITEMS carries. That
         * is deliberate and it is load-bearing. This block used to build its groups by hand out of
         * `dashboardModes`, and the comment here claimed the two were built from the same filters;
         * they were not, and the divergence was exactly what you would predict from two hand-kept
         * lists. On the device the bar had no Admin group at all, so a master admin could reach
         * Settings hub from the drawer and not from the bar; its Record dropdown carried twelve
         * entries against the drawer's eight, including "Manage users" rendered with a "+" icon
         * because every entry was hardcoded to Icons.Filled.Add; and it offered "Request workshop
         * access", a destination that exists in neither the drawer nor the web.
         *
         * Deriving both from the shared model makes that class of drift impossible rather than
         * merely fixed: an entry added to FIELD_NAV_ITEMS appears in both surfaces with its own icon
         * and its own gate, and one can no longer offer what the other refuses.
         */
        val navItems = visibleNavItems(user, adminView)
        FieldIslandNav(
            modifier = Modifier.fillMaxWidth(),
            // The wordmark is a Dashboard link that is on screen even mid-form, so it goes through the
            // router like any other chip rather than jumping home on its own.
            onBrandClick = { navigate(NavDestination.DASHBOARD) },
            onOpenDrawer = { scope.launch { drawerState.open() } },
            // Null hides the chip outright. ADMIN (50) and MASTER_ADMIN (60) only — see
            // [canToggleAdminView]; a professor's chrome is granted by their role, not by a switch.
            adminMode = if (canToggleAdminView) adminView else null,
            onToggleAdminView = { adminViewRequested = !adminView },
            // The bar highlights by label, so the label has to be looked up from the DESTINATION the
            // user is on. This used to pass `headerTitle` — a page title, matched against nav labels
            // that are written for a menu — so the Dashboard ("Field Repository" against "Dashboard")
            // and most record forms lit nothing, while the handful whose two strings happened to
            // coincide were the only ones that worked.
            currentLabel = navItems.firstOrNull { it.destination == currentDestination }?.label,
            // A lambda, not `pageScroll.value`: reading the offset here would make every frame of
            // every scroll recompose this whole screen. The bar reads it inside its own derived
            // state, where it feeds one Boolean.
            scrollOffset = { pageScroll.value },
            roots = navItems.filter { it.group == null }
                .map { entry -> IslandEntry(entry.label, entry.icon) { navigate(entry.destination) } },
            groups = NavGroup.entries.map { group ->
                val entries = navItems.filter { it.group == group }
                    .map { entry -> IslandEntry(entry.label, entry.icon) { navigate(entry.destination) } }
                IslandGroup(
                    group.label,
                    // Folded into Account rather than appended as its own group, which would render
                    // a second chip with the same label. Leaving the app is not a NavDestination and
                    // cannot be one — a router has no screen to open for it — so the shared model has
                    // no home for it, but the web app hands out this APK and this is the return leg.
                    if (group == NavGroup.ACCOUNT) {
                        entries + IslandEntry("Open the web portal", Icons.Filled.OpenInNew) { openWebPortal(context) }
                    } else {
                        entries
                    }
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(pageScroll),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

        // The dashboard is the root of every path, so only the screens below it get a back arrow —
        // and it is also the only screen with no title of its own, so on the dashboard this whole
        // row is nothing and is not laid out at all. Rendering it empty would spend the parent's
        // 16dp of spacing on a strip with nothing in it.
        val showBack = screen !is Screen.Dashboard
        if (showBack || headerTitle != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (showBack) {
                    IconButton(onClick = { attemptExit { goBack() } }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                headerTitle?.let { title ->
                    Text(
                        title,
                        display = true,
                        modifier = Modifier.weight(1f),
                        fontSize = 28.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        when (val s = screen) {
            is Screen.Dashboard -> {
                carryForward?.let { prefill ->
                    CarryForwardPanel(
                        repository = repository,
                        prefill = prefill,
                        canCreateTool = canCreate(EntryMode.TOOL),
                        onSelect = { mode -> screen = Screen.Create(mode, prefill); carryForward = null },
                        onDismiss = { carryForward = null }
                    )
                }
                DashboardScreen(
                    stats = stats,
                    recentArtisans = artisans,
                    // Only the cards this user may actually START, exactly as the web dashboard
                    // filters its tiles (`visible`) and as the drawer above already filters its
                    // menu. A card that offered nothing but "Update" told a volunteer neither what
                    // they could do nor why the rest was missing. `onDashboard` additionally keeps
                    // the two menu-only destinations (Search, Data Browser) off the grid, as on web.
                    actions = dashboardModes.filter { canCreate(it) && it.onDashboard },
                    roleLabel = roleLabel(user.role),
                    canCreateRecords = user.canCreateRecords(),
                    // `adminSurface(isAdmin(user))` on the web dashboard: the role decides, the
                    // toggle can only take the tile away again.
                    showAdminHub = isAdmin && adminChrome,
                    onOpenAdminHub = { message = null; screen = Screen.AdminHub() },
                    onWalkthrough = { message = null; showWalkthrough = true },
                    onNew = { selected -> message = null; screen = Screen.Create(selected) },
                    onUpdateExisting = { selected -> message = null; screen = Screen.Browse(selected) },
                    // A total is the size of a bucket search already reports, so tapping one lands
                    // in search rather than in a second listing built only for the dashboard.
                    onOpenSearchFor = { recordType ->
                        message = null
                        screen = Screen.Create(EntryMode.SEARCH, searchFocus = recordType)
                    },
                    // `require_reviewer`, not admin chrome (see canReview above): a Field Contributor
                    // gets the way in, everyone else keeps the figure and loses only the tap.
                    onOpenReviews = if (canReview) {
                        ({ message = null; screen = Screen.AdminHub(AdminHubEntry.REVIEWS) })
                    } else null,
                    onOpenArtisan = { artisanId -> message = null; screen = Screen.Edit(EntryMode.ARTISAN, artisanId) }
                )
            }

            is Screen.Browse -> RecordPickerScreen(
                repository = repository,
                mode = s.mode,
                onPick = { recordId -> screen = Screen.Edit(s.mode, recordId) },
                onError = { showMessage(it) }
            )

            is Screen.Create -> when (s.mode) {
                EntryMode.CRAFT -> CraftForm(
                    repository = repository,
                    onDone = { message = "Craft saved"; refresh(); refreshLookups(); goDashboard() },
                    onError = { showMessage(it) }
                )
                EntryMode.ARTISAN -> ArtisanForm(
                    repository = repository,
                    crafts = crafts,
                    lookupState = lookupState,
                    prefill = s.prefill,
                    adminView = adminView,
                    onArtisanCreated = { prefill ->
                        message = "Artisan saved"
                        refresh(); refreshLookups()
                        carryForward = prefill
                        goDashboard()
                    },
                    onDone = { message = "Artisan saved"; refresh(); refreshLookups(); goDashboard() },
                    onError = { showMessage(it) }
                )
                EntryMode.WORKSHOP -> WorkshopForm(
                    repository = repository,
                    artisans = artisans,
                    prefill = s.prefill,
                    adminView = adminView,
                    onDone = { message = "Workshop saved"; refresh(); goDashboard() },
                    onError = { showMessage(it) }
                )
                EntryMode.PRODUCT -> ProductForm(
                    repository = repository,
                    crafts = crafts,
                    artisans = artisans,
                    lookupState = lookupState,
                    prefill = s.prefill,
                    adminView = adminView,
                    onDone = { message = "Product saved"; refresh(); goDashboard() },
                    onError = { showMessage(it) }
                )
                EntryMode.PROCESS -> ProcessForm(
                    repository = repository,
                    adminView = adminView,
                    onDone = { message = "Process saved"; refresh(); goDashboard() },
                    onError = { showMessage(it) }
                )
                EntryMode.VIEW_DATA -> ViewDataScreen(
                    repository = repository,
                    canReview = canReview,
                    masterAdmin = isMasterAdmin,
                    isAdmin = isAdmin,
                    adminChrome = adminChrome,
                    showProvenance = canViewProvenance,
                    canDownloadDataset = canDownloadDataset,
                    onOpenDataBrowser = { message = null; screen = Screen.DataBrowser },
                    onError = { showMessage(it) }
                )
                // /search on the web — open to every signed-in user. It renders into the shared
                // chrome, which already draws the Back pill, so its own back control stays off.
                EntryMode.SEARCH -> {
                    // Opened from a dashboard total: the tap already said which bucket it wanted, so
                    // the screen opens listing exactly that one. `initialRecordType` also implies the
                    // listing itself — sitting on an empty form would make the researcher ask twice.
                    s.searchFocus?.let { focus ->
                        Text(
                            "Every ${searchFocusLabel(focus)} record, newest first. Use the counts above the " +
                                "results to widen it, or search to narrow it.",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                    SearchScreen(
                        repository = repository,
                        onOpenRecord = { recordType, recordId ->
                            message = null
                            screen = Screen.Edit(searchRecordEntryMode(recordType), recordId)
                        },
                        onBack = { attemptExit { goBack() } },
                        initialRecordType = s.searchFocus
                    )
                }
                // Routed as Screen.DataBrowser by `screenFor`, since the browser owns its whole
                // viewport. This branch only exists so the `when` stays exhaustive — and, if anything
                // ever does route here directly, it offers the way in rather than a blank screen.
                EntryMode.DATA_BROWSER -> DataBrowserEntryCard(
                    onOpen = { message = null; screen = Screen.DataBrowser }
                )
                EntryMode.TOOL -> ToolForm(
                    repository = repository,
                    crafts = crafts,
                    artisans = artisans,
                    lookupState = lookupState,
                    prefill = s.prefill,
                    adminView = adminView,
                    onDone = { message = "Tool saved"; refresh(); goDashboard() },
                    onError = { showMessage(it) }
                )
                EntryMode.MEDIA -> AndroidMediaForm(
                    repository = repository,
                    onUploaded = { count ->
                        message = "$count media file${if (count == 1) "" else "s"} uploaded and queued"
                        refresh()
                    },
                    onError = { showMessage(it) }
                )
                EntryMode.QUESTIONNAIRE -> QuestionnaireForm(
                    repository = repository,
                    sections = sections,
                    artisans = artisans,
                    lookupState = lookupState,
                    prefill = s.prefill,
                    canManageQuestionnaire = isQuestionnaireManager,
                    // Carries the admin-view state into the form's "Check completion" matrix, whose
                    // override is `adminMode && isAdmin` on the web.
                    adminView = adminView,
                    onRefreshSections = {
                        runCatching { repository.questionnaireSections() }
                            .onSuccess { sections = it }
                            .onFailure { showMessage(it.message) }
                    },
                    onSync = {
                        runCatching { repository.questionnaireSections() }
                            .onSuccess { sections = it }
                            .onFailure { showMessage(it.message) }
                        loadLookups()
                    },
                    onSubmit = { body ->
                        val created = repository.createQuestionnaireInterview(body)
                        refresh()
                        created.id
                    },
                    onError = { showMessage(it) },
                    onSaved = { message = "Questionnaire interview saved"; refresh(); goDashboard() }
                )
                EntryMode.TASKS -> MyTasksScreen(
                    repository = repository,
                    // Being an assignee is never admin chrome; HANDING WORK OUT is (the web lists
                    // /settings/tasks in ADMIN_CHROME_ROUTES), so the "Assigned by me" view and the
                    // link to the board follow the toggle while the rest of the screen does not.
                    canAssign = isAdmin && adminChrome,
                    onOpenAssignmentBoard = { message = null; screen = Screen.AdminHub(AdminHubEntry.TASKS) },
                    onMessage = { showMessage(it) },
                    onError = { showMessage(it) }
                )
                EntryMode.SHARING -> SharingForm(
                    repository = repository,
                    isAdmin = isAdmin,
                    onError = { showMessage(it) }
                )
                EntryMode.WORKSHOP_ACCESS -> WorkshopAccessScreen(
                    repository = repository,
                    onMessage = { showMessage(it) },
                    onError = { showMessage(it) }
                )
                // The card and the menu entry both disappear when admin view is off, but an admin who
                // was already standing here when they flipped the switch has to be shown the same
                // thing the web's AppShell shows: their own setting, not a permission they lack.
                EntryMode.USERS -> if (adminChrome) {
                    UserManagementForm(
                        repository = repository,
                        isMasterAdmin = isMasterAdmin,
                        onError = { showMessage(it) }
                    )
                } else {
                    AdminViewHiddenCard(
                        label = "User management",
                        blurb = "Roles, promotions, capability grants and account administration live there.",
                        canToggle = canToggleAdminView,
                        onEnable = { adminViewRequested = true }
                    )
                }
            }

            is Screen.Edit -> if (s.mode == EntryMode.QUESTIONNAIRE) {
                InterviewEditLoader(
                    repository = repository,
                    recordId = s.recordId,
                    sections = sections,
                    artisans = artisans,
                    canManageQuestionnaire = isQuestionnaireManager,
                    adminView = adminView,
                    // Every record Delete on the web is `{adminMode ? … : null}` — the role is what
                    // grants it, the toggle is what puts it away while an admin browses as a user.
                    canDelete = isAdmin && adminChrome,
                    onRefreshSections = {
                        runCatching { repository.questionnaireSections() }
                            .onSuccess { sections = it }
                            .onFailure { showMessage(it.message) }
                    },
                    onError = { showMessage(it) },
                    onDone = { message = "Interview updated"; refresh(); goDashboard() }
                )
            } else EditScreen(
                repository = repository,
                mode = s.mode,
                recordId = s.recordId,
                crafts = crafts,
                artisans = artisans,
                adminView = adminView,
                // Same rule as the interview loader above: `adminMode` gates Delete on every web list.
                canDelete = isAdmin && adminChrome,
                onDone = { message = "${s.mode.label} updated"; refresh(); refreshLookups(); goDashboard() },
                onError = { showMessage(it) }
            )

            is Screen.MyActivity -> MyActivityScreen(
                repository = repository,
                userId = user.id,
                onOpen = { mode, recordId -> message = null; screen = Screen.Edit(mode, recordId) },
                onError = { showMessage(it) }
            )

            is Screen.ToolAssign -> ToolAssignScreen(
                repository = repository,
                onError = { showMessage(it) }
            )

            is Screen.Feedback -> FeedbackScreen(
                repository = repository,
                onError = { showMessage(it) }
            )

            is Screen.Settings -> SettingsScreen(
                repository = repository,
                onMessage = { showMessage(it) },
                onError = { showMessage(it) }
            )

            // The whole hub is admin chrome (/admin heads the web's ADMIN_CHROME_ROUTES). The role
            // check comes first and the toggle only subtracts from it.
            is Screen.AdminHub -> when {
                isAdmin && adminChrome -> AdminHubScreen(
                    repository = repository,
                    isMasterAdmin = isMasterAdmin,
                    canReview = canReview,
                    section = s.section,
                    onSectionChange = { next -> screen = Screen.AdminHub(next) },
                    onMessage = { showMessage(it) },
                    onError = { showMessage(it) }
                )
                // Reviewing is a Field Contributor capability an admin merely also holds, so the
                // dashboard's Pending figure hands a non-admin reviewer the review tool ITSELF —
                // never the hub around it, whose list is chrome and would offer them tools the API
                // refuses.
                s.section == AdminHubEntry.REVIEWS && canReview -> ReviewApprovalCard(
                    repository = repository,
                    onError = { showMessage(it) }
                )
                else -> AdminViewHiddenCard(
                    label = "The settings hub",
                    blurb = "It gathers reviews, recovered recordings, feedback, tool assignment and user management in one place.",
                    canToggle = canToggleAdminView,
                    onEnable = { adminViewRequested = true }
                )
            }

            // Hosted above, outside this scrolling Column, because they own their whole viewport.
            is Screen.Appearance, is Screen.DataBrowser -> Unit
        }

        message?.let {
            Text(it, color = Body, modifier = Modifier.padding(bottom = 24.dp))
        }

                }
                }
                }
                }

                /*
                 * The dialogs sit OUTSIDE the layout branch above. They are their own windows, so
                 * where they live in the tree changes nothing visually — but a REQUIRED update prompt
                 * that only existed on the scrolling branch would never reach a user sitting in the
                 * Data Browser or their appearance settings when the resume check fires.
                 */

                // The walkthrough never sits on top of a required-update prompt (that must be handled first).
                if (showWalkthrough && pendingUpdate == null) {
                    WalkthroughDialog(onDismiss = { showWalkthrough = false; markWalkthroughSeen(context) })
                }

                pendingUpdate?.let { release ->
                    // A required update. The dialog is non-dismissable — there is no "Later" and tapping
                    // outside / pressing back does nothing — so the user must install before they can proceed.
                    // (Installing the new APK relaunches the app at the higher version, which clears this check.)
                    AlertDialog(
                        onDismissRequest = { /* required update: cannot be dismissed */ },
                        title = { Text("Update required") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Version ${release.versionName} is available and must be installed to continue using the app.")
                                release.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, fontSize = 12.sp) }
                                updateError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                                if (updateBusy) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text("Downloading and preparing the installer…", color = Muted, fontSize = 12.sp)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !updateBusy,
                                onClick = {
                                    updateBusy = true
                                    updateError = null
                                    scope.launch {
                                        runCatching {
                                            if (!canInstallUpdates(context)) {
                                                requestInstallPermission(context)
                                                throw IllegalStateException("Enable \"Install unknown apps\" for Field Repository in the screen that just opened, then tap Update now again.")
                                            }
                                            val apk = repository.downloadApk(context, release.url!!, release.versionCode)
                                            launchApkInstaller(context, apk)
                                        }.onFailure { updateError = it.message ?: "Unable to download the update — check your connection and try again." }
                                        // Keep `pendingUpdate` set: if the user backs out of the installer the
                                        // dialog must stay until the new version is actually installed.
                                        updateBusy = false
                                    }
                                }
                            ) { Text(if (updateBusy) "Updating…" else "Update now") }
                        }
                    )
                }

                // Unsaved-changes prompt: shown when the user tries to leave a form that still has unsaved
                // work. "Save" runs the form's own validated save (a missing required field keeps them on the
                // form, highlighted); "Discard" leaves and drops the in-progress data; "Keep editing" stays.
                pendingExit?.let { exit ->
                    AlertDialog(
                        onDismissRequest = { pendingExit = null },
                        title = { Text("Unsaved changes") },
                        text = {
                            Text(
                                "You have unsaved changes, including any recordings or media you just captured. " +
                                    "Save them before leaving, or discard them?"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                pendingExit = null
                                // The form validates and, on success, saves and navigates itself. On a missing
                                // required field it stays put with the field highlighted.
                                unsavedGuard.onSave?.invoke()
                            }) { Text("Save") }
                        },
                        dismissButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { pendingExit = null }) { Text("Keep editing") }
                                TextButton(onClick = {
                                    pendingExit = null
                                    unsavedGuard.clear()
                                    exit()
                                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    )
                }
            }
        }
    }
}

/** True when the OS will let us install an APK (always pre-O; needs the per-app grant on O+). */
private fun canInstallUpdates(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

/** Open the system screen where the user grants this app permission to install updates. */
private fun requestInstallPermission(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Hand a downloaded APK to the system package installer (the user taps Install to confirm). */
private fun launchApkInstaller(context: Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}

/**
 * Open the web portal in the user's browser. The web app hands out this APK; this is the return leg
 * of the same trip, for the bulk work a phone is the wrong shape for.
 */
private fun openWebPortal(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://field-repository.vercel.app"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "No app on this device can open web links.", Toast.LENGTH_LONG).show()
    }
}

/** A tapped dashboard total, in the plural the tile itself uses rather than the API's singular. */
private fun searchFocusLabel(recordType: String): String = when (recordType) {
    SearchRecordTypes.ARTISAN -> "artisans"
    SearchRecordTypes.WORKSHOP -> "workshops"
    SearchRecordTypes.PRODUCT -> "products"
    SearchRecordTypes.TOOL -> "tools"
    SearchRecordTypes.MEDIA -> "media"
    else -> "records"
}

/**
 * The record type a search hit belongs to, as the app's own [EntryMode].
 *
 * [SearchRecordTypes] is the contract the search screen reports against; anything unrecognised falls
 * back to ARTISAN rather than throwing, because a new bucket appearing server-side must not crash a
 * tap. MEDIA is included: it has no edit form (the web opens the object itself), and [EditScreen]
 * shows the file with its transcript instead.
 */
private fun searchRecordEntryMode(recordType: String): EntryMode = when (recordType) {
    SearchRecordTypes.ARTISAN -> EntryMode.ARTISAN
    SearchRecordTypes.WORKSHOP -> EntryMode.WORKSHOP
    SearchRecordTypes.PRODUCT -> EntryMode.PRODUCT
    SearchRecordTypes.TOOL -> EntryMode.TOOL
    SearchRecordTypes.MEDIA -> EntryMode.MEDIA
    else -> EntryMode.ARTISAN
}

/**
 * What an admin sees in place of admin chrome they switched off themselves.
 *
 * Deliberately NOT the permission copy a genuine non-admin gets: this is self-inflicted and one tap
 * from being undone, so telling an admin they lack access they in fact hold would be the worse of
 * the two errors. Wording follows the web's `AdminViewHidden` panel.
 *
 * [canToggle] is why this card takes a flag rather than assuming its reader is an admin. A reviewer
 * below admin reaches the review tool from the dashboard's Pending figure, and stepping BACK from it
 * lands on the hub they were never given — where this card used to blame their own admin-view
 * setting and offer them a switch that is not theirs to hold. With no switch, the card says the
 * plain thing instead: the tools are not part of their role.
 */
@Composable
private fun AdminViewHiddenCard(
    label: String,
    blurb: String,
    canToggle: Boolean,
    onEnable: () -> Unit
) {
    val title = if (canToggle) "$label is hidden while admin view is off" else "$label is not part of your role"
    RecordCard(title = title, icon = Icons.Filled.VisibilityOff) {
        Text(
            if (canToggle) {
                "$blurb You switched admin view off, so the repository is behaving exactly as it does " +
                    "for an ordinary user."
            } else {
                "$blurb Those tools belong to administrators; everything your role does reach is in " +
                    "the menu."
            },
            color = Body,
            fontSize = 13.sp
        )
        if (canToggle) {
            Text(
                "Your access has not changed — this is your own setting, not a permission you are missing.",
                color = Muted,
                fontSize = 12.sp
            )
            Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
                Text("Turn admin view back on")
            }
        }
    }
}

/** The way in to the /data directory-tree browser, offered wherever the dataset itself is offered. */
@Composable
private fun DataBrowserEntryCard(onOpen: () -> Unit) {
    RecordCard(title = "Data Browser", icon = Icons.Filled.Storage) {
        Text(
            "Browse the repository as a directory tree, preview media and transcripts, and download " +
                "any folder as a zip with content-type filters.",
            color = Muted,
            fontSize = 12.sp
        )
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open the Data Browser")
        }
    }
}

/** Right-side navigation drawer mirroring the web slide-out menu. */
// ===========================================================================
// Settings — master-admin global configuration (transcription mode + off-peak
// processing window). More options will live here over time.
// ===========================================================================

@Composable
private fun SettingsScreen(
    repository: FieldRepository,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("REFINED_TRANSLATED") }
    var windowEnabled by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf("02:00") }
    var endTime by remember { mutableStateOf("05:00") }

    LaunchedEffect(Unit) {
        runCatching { repository.appSettings() }
            .onSuccess { s ->
                mode = s.transcriptionMode
                windowEnabled = s.batchWindowEnabled
                startTime = s.batchWindowStart
                endTime = s.batchWindowEnd
            }
            .onFailure { onError(it.message ?: "Couldn't load settings") }
        loading = false
    }

    RecordCard(title = "Settings") {
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Loading settings…", color = Muted, fontSize = 13.sp)
            }
            return@RecordCard
        }
        Text("Transcription output", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "How recorded audio is turned into text after upload. Refinement/translation use AI and " +
                "always await your approval before they become the saved transcript.",
            color = Muted,
            fontSize = 12.sp
        )
        SettingsRadioRow("Raw transcript only", "Fastest, lowest cost — the plain speech-to-text.", mode == "RAW") { mode = "RAW" }
        SettingsRadioRow("Refined transcript", "Cleaned into a readable interviewer/interviewee dialogue.", mode == "REFINED") { mode = "REFINED" }
        SettingsRadioRow("Refined + translated to English", "Refined, then translated to English (default).", mode == "REFINED_TRANSLATED") { mode = "REFINED_TRANSLATED" }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Process during an off-peak window", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                Text(
                    "When on, transcription & refinement run only between the times below (IST), so the " +
                        "heavy work happens when nobody is uploading. When off, they run immediately.",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
            Switch(checked = windowEnabled, onCheckedChange = { windowEnabled = it })
        }
        if (windowEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f)) { TimePickerField("Start", startTime) { startTime = it } }
                Box(modifier = Modifier.weight(1f)) { TimePickerField("End", endTime) { endTime = it } }
            }
            Text("Window is in India Standard Time (Asia/Kolkata).", color = Muted, fontSize = 11.sp)
        }

        Button(
            onClick = {
                scope.launch {
                    saving = true
                    runCatching {
                        repository.updateAppSettings(
                            AppSettingUpdateRequest(
                                transcriptionMode = mode,
                                batchWindowEnabled = windowEnabled,
                                batchWindowStart = startTime,
                                batchWindowEnd = endTime
                            )
                        )
                    }.onSuccess {
                        mode = it.transcriptionMode
                        windowEnabled = it.batchWindowEnabled
                        startTime = it.batchWindowStart
                        endTime = it.batchWindowEnd
                        onMessage("Settings saved")
                    }.onFailure { onError(it.message ?: "Couldn't save settings") }
                    saving = false
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving…" else "Save settings")
        }
    }
}

/** A labelled radio option row (title + helper line) used by [SettingsScreen]. */
@Composable
private fun SettingsRadioRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
    }
}

/** A button showing a HH:mm time that opens a 24-hour time picker dialog to change it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val parts = remember(value) { value.split(":").mapNotNull { it.toIntOrNull() } }
    val hour = parts.getOrNull(0)?.coerceIn(0, 23) ?: 0
    val minute = parts.getOrNull(1)?.coerceIn(0, 59) ?: 0
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
        Text("$label: $value")
    }
    if (show) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange("%02d:%02d".format(state.hour, state.minute))
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) }
        )
    }
}

/** Post-save shortcuts that carry the just-saved artisan into a follow-up record. */
@Composable
private fun CarryForwardPanel(
    repository: FieldRepository,
    prefill: Prefill,
    canCreateTool: Boolean,
    onSelect: (EntryMode) -> Unit,
    onDismiss: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    // These shortcuts hand the artisan on in memory, which only survives a tap made from this panel
    // — and the panel dies on dismissal, on navigating away, and whenever Android reclaims a
    // backgrounded app. Banking the same bag covers the route researchers actually take: out via the
    // dashboard, back into a product form later. Web parity — CarryForwardCards remembers on mount.
    LaunchedEffect(prefill) {
        CarryContextStore(appContext).remember(repository.cachedUser()?.id, prefill.toCarryContext())
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Saved ${prefill.artisanName ?: "artisan"} ✓", display = true, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("Keep going for the same artisan — details are pre-filled.", color = Muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onSelect(EntryMode.PRODUCT) }, modifier = Modifier.weight(1f)) { Text("Add product") }
                if (canCreateTool) {
                    Button(onClick = { onSelect(EntryMode.TOOL) }, modifier = Modifier.weight(1f)) { Text("Add tool") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onSelect(EntryMode.QUESTIONNAIRE) }, modifier = Modifier.weight(1f)) { Text("Take interview") }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    stats: DashboardStats?,
    recentArtisans: List<ArtisanDto>,
    /** Already filtered to what this user may start — every card here leads with its "New …". */
    actions: List<EntryMode>,
    /** This user's tier, spelled the way the app spells it, for the short-grid explanation. */
    roleLabel: String,
    /** require_record_creator: Researcher and above. False = the four record cards are gone. */
    canCreateRecords: Boolean,
    showAdminHub: Boolean = false,
    onOpenAdminHub: () -> Unit = {},
    onWalkthrough: () -> Unit = {},
    onNew: (EntryMode) -> Unit,
    onUpdateExisting: (EntryMode) -> Unit,
    /** A tapped total opens search for the [SearchRecordTypes] bucket it counted. */
    onOpenSearchFor: (String) -> Unit,
    /** null = this user cannot review, so "Pending" stays a figure they read but cannot open. */
    onOpenReviews: (() -> Unit)?,
    onOpenArtisan: (String) -> Unit
) {
    val configuration = LocalConfiguration.current
    val columns = when {
        configuration.screenWidthDp >= 840 -> 4
        configuration.screenWidthDp >= 600 -> 3
        else -> 2
    }
    // One list for the whole grid. The admin "Settings" card used to be emitted as its own Row below
    // the grid with `columns - 1` spacers after it, which is exactly why it never lined up with the
    // cards above: a second Row measures independently of the first. It is a tile like any other.
    val tiles = buildList {
        actions.forEach { entry ->
            add(
                DashboardTile(
                    label = entry.label,
                    icon = entry.icon(),
                    primaryIcon = Icons.Filled.Add,
                    primaryLabel = entry.createButtonLabel(),
                    onPrimary = { onNew(entry) },
                    onUpdate = if (entry.editable) ({ onUpdateExisting(entry) }) else null
                )
            )
        }
        if (showAdminHub) {
            add(
                DashboardTile(
                    label = "Settings",
                    icon = Icons.Filled.Tune,
                    primaryIcon = Icons.Filled.Tune,
                    primaryLabel = "Open",
                    onPrimary = onOpenAdminHub
                )
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text("What would you like to do?", display = true, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
        tiles.chunked(columns).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                // Height equalisation, the Compose equivalent of the web grid's default
                // `align-items: stretch`. Without it a Row sizes each child to its own content, so a
                // card whose label wraps to two lines — or one with no "Update" button — was shorter
                // than its neighbours and its buttons sat at a different height. IntrinsicSize.Min
                // measures the row to the tallest card; `fillMaxHeight` then stretches the rest to it.
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
            ) {
                rowItems.forEach { tile ->
                    DashboardActionCard(
                        tile = tile,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
                repeat(columns - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        // A short grid is otherwise unexplained: say WHY the record cards are missing and where the
        // tier comes from, rather than leaving a volunteer to assume the app is broken. Web parity
        // with the dashboard's `!creator` note.
        if (!canCreateRecords) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "You are signed in as $roleLabel. That covers answering existing " +
                            "interviews, uploading media and commenting on records other people " +
                            "opened. Creating artisans, products, processes and tools needs " +
                            "Researcher access or above — ask an admin to raise your tier.",
                        color = Muted,
                        fontSize = 13.sp
                    )
                    TextButton(onClick = onWalkthrough, contentPadding = PaddingValues(0.dp)) {
                        Text("Open the walkthrough")
                    }
                }
            }
        }
        StatsCard(stats = stats, onOpenSearchFor = onOpenSearchFor, onOpenReviews = onOpenReviews)
        if (recentArtisans.isNotEmpty()) {
            Text("Recent artisans", display = true, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
            recentArtisans.take(6).forEach { artisan ->
                val interaction = remember(artisan.id) { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        // Purple is the action colour, so a press reads as "this is a way in" rather
                        // than as a selected row. Composited over the card's own surface instead of
                        // being a second token, so it follows SurfaceCard into either theme.
                        containerColor = if (pressed) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(SurfaceCard)
                        } else {
                            SurfaceCard
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        // The click sits INSIDE the card, not on it: the card clips its own shape, so
                        // the ripple stops at the rounded corner instead of flashing a square.
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                                onClickLabel = "Edit ${artisan.name}"
                            ) { onOpenArtisan(artisan.id) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${artisan.name}. Opens this artisan for editing."
                            }
                            .padding(12.dp)
                    ) {
                        Text(artisan.name, display = true, fontWeight = FontWeight.SemiBold)
                        Text("${artisan.craft?.name ?: "No craft"} · ${artisan.place}", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun EntryMode.createButtonLabel(): String = when (this) {
    EntryMode.MEDIA -> "Upload"
    EntryMode.QUESTIONNAIRE -> "New interview"
    EntryMode.USERS -> "Manage"
    EntryMode.VIEW_DATA -> "Open"
    EntryMode.TASKS -> "Open"
    EntryMode.WORKSHOP_ACCESS -> "Open"
    EntryMode.SEARCH, EntryMode.DATA_BROWSER -> "Open"
    else -> "New"
}

/**
 * One dashboard tile's content: the icon, the display label, the filled primary action and — where
 * the record type can be edited — the outlined "Update" action.
 *
 * Modelled as data rather than as a second card composable so the admin "Settings" card is laid out
 * by the SAME code path (and the same grid) as every record card. Two near-identical card composables
 * emitted from two different Rows was how the two drifted out of alignment in the first place.
 */
private class DashboardTile(
    val label: String,
    val icon: ImageVector,
    val primaryIcon: ImageVector,
    val primaryLabel: String,
    val onPrimary: () -> Unit,
    val onUpdate: (() -> Unit)? = null
)

/**
 * Web parity with `components/DashboardCard.tsx`: a card, a small dark icon tile, the display label,
 * then the filled primary action and the outlined "Update" where editing exists.
 *
 * Two rules make a row of these line up, and both live here rather than in padding tweaks:
 * the caller stretches every card to the row's height (see [DashboardScreen]), and the label carries
 * `weight(1f)` so it — not the buttons — absorbs the slack. That is the web card's `mt-auto`: the
 * action row is pinned to the bottom edge, so it sits at one height across the whole row no matter
 * how many lines each label needs.
 */
@Composable
private fun DashboardActionCard(tile: DashboardTile, modifier: Modifier = Modifier) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Web parity: a small dark-purple tile (bg-purple-800) carrying a light icon. `brandTile`
            // is the theme's slot for exactly this pairing, so it follows light/dark on its own.
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(color = MaterialTheme.field.brandTile, shape = MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    tile.icon,
                    contentDescription = null,
                    tint = MaterialTheme.field.onBrandTile,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                tile.label,
                display = true,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            // The card is only offered when its primary action is allowed (DashboardScreen filters),
            // so this action is unconditional — a card that could only "Update" explained nothing.
            Button(
                onClick = tile.onPrimary,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) { CardButtonLabel(tile.primaryIcon, tile.primaryLabel) }
            tile.onUpdate?.let { update ->
                OutlinedButton(
                    onClick = update,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) { CardButtonLabel(Icons.Filled.Edit, "Update") }
            }
        }
    }
}

/** Icon + single-line label, sized to never wrap inside the narrow dashboard cards. */
@Composable
private fun CardButtonLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(6.dp))
    // Ellipsis, not a silent clip: the cards are two-to-a-row on a phone and "New interview" is wider
    // than the button at that width.
    Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
}

@Composable
private fun StatsCard(
    stats: DashboardStats?,
    /** Every figure but Pending counts a search bucket, so every figure but Pending opens one. */
    onOpenSearchFor: (String) -> Unit,
    /** null = this user cannot review; Pending is then a figure, not a door. */
    onOpenReviews: (() -> Unit)?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.field.brandTile),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("Repository totals", display = true, color = MaterialTheme.field.onBrandTile, fontSize = 24.sp)
            Spacer(Modifier.height(12.dp))
            if (stats == null) {
                Text("Loading...", color = MaterialTheme.field.onBrandTileMuted)
            } else {
                // Same height equalisation as the action grid: a stat whose label wraps must not make
                // its tile taller than the two beside it.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                ) {
                    Stat(
                        "Artisans", stats.totalArtisans, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.ARTISAN) },
                        openDescription = "Opens a search of the artisans"
                    )
                    Stat(
                        "Products", stats.totalProductRecords, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.PRODUCT) },
                        openDescription = "Opens a search of the products"
                    )
                    Stat(
                        "Tools", stats.totalToolRecords, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.TOOL) },
                        openDescription = "Opens a search of the tools"
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                ) {
                    Stat(
                        "Media", stats.totalMediaFiles, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.MEDIA) },
                        openDescription = "Opens a search of the media"
                    )
                    Stat(
                        // The one figure that is not a bucket of records but a queue of work, so it
                        // leads to the queue — and only for whoever may act on it. It still SHOWS for
                        // everyone: a researcher has to be able to see their own backlog.
                        "Pending", stats.pendingSubmissions, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = onOpenReviews,
                        openDescription = "Opens reviews and approvals"
                    )
                    Stat(
                        "Workshops", stats.totalWorkshops, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.WORKSHOP) },
                        openDescription = "Opens a search of the workshops"
                    )
                }
            }
        }
    }
}

/**
 * One figure on the totals tile. [onOpen] is what turns it from a readout into a way in; null leaves
 * the figure exactly as legible and nothing about it invites a tap it would not answer.
 */
@Composable
private fun Stat(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    /** Where the tap leads, for TalkBack — a bare number cannot say. */
    openDescription: String? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            // Clipped before the fill so a tap's ripple stops at the chip's own rounded edge.
            .clip(shape)
            // A chip sitting ON the brand tile: a translucent step of the same purple family rather
            // than a fixed near-black, so it stays one shade off its parent in either theme. The
            // press deepens that same step, because a ripple alone barely registers on it.
            .background(color = MaterialTheme.field.accentOnBrandTile.copy(alpha = if (pressed) 0.34f else 0.18f))
            .then(
                if (onOpen == null) Modifier else Modifier
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClickLabel = openDescription
                    ) { onOpen() }
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOfNotNull("$label: $value", openDescription).joinToString(". ")
                    }
            )
            .padding(12.dp)
    ) {
        Text(value.toString(), display = true, color = MaterialTheme.field.onBrandTile, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = MaterialTheme.field.onBrandTileMuted, fontSize = 12.sp)
    }
}

private val productTypeOptions = listOf("FINISHED_GOOD", "SAMPLE", "RAW_MATERIAL", "COMPONENT", "PACKAGING", "OTHER")
private val marketDemandOptions = listOf("LOW", "MEDIUM", "HIGH", "SEASONAL", "UNKNOWN")
private val makerOptions = listOf("ARTISAN", "LOCAL_BLACKSMITH", "CARPENTER", "WORKSHOP", "FACTORY", "UNKNOWN", "OTHER")
private val traditionOptions = listOf("TRADITIONAL", "MODERN", "HYBRID", "UNKNOWN")
private val statusOptions = listOf("DRAFT", "PENDING", "APPROVED", "REJECTED")

// Role hierarchy, mirroring the backend ROLE_RANK ladder. The record-status control is gated at
// PROFESSOR+ (rank >= 40): they pick any status and default to APPROVED on create; everyone below sees
// a locked "Pending" chip and their new records are forced to PENDING (the backend also silently drops
// any status a below-professor user tries to send).
private val ROLE_RANK = mapOf(
    "CROWDSOURCE_VOLUNTEER" to 10,
    "FIELD_CONTRIBUTOR" to 20,
    "RESEARCHER" to 30,
    "PROFESSOR" to 40,
    "ADMIN" to 50,
    "MASTER_ADMIN" to 60
)
private const val RANK_FIELD_CONTRIBUTOR = 20
private const val RANK_RESEARCHER = 30
private const val RANK_PROFESSOR = 40
private const val RANK_ADMIN = 50

/** Human labels for the ladder, byte-for-byte the server's `ROLE_LABELS` (and the web's). */
private val ROLE_LABELS = mapOf(
    "CROWDSOURCE_VOLUNTEER" to "Crowdsource Volunteer",
    "FIELD_CONTRIBUTOR" to "Field Contributor",
    "RESEARCHER" to "Researcher",
    "PROFESSOR" to "Professor",
    "ADMIN" to "Admin",
    "MASTER_ADMIN" to "Master Admin"
)

private fun roleLabel(role: String?): String = ROLE_LABELS[role] ?: role.orEmpty()
private fun roleRank(role: String?): Int = ROLE_RANK[role] ?: 0
private fun canSetRecordStatus(role: String?): Boolean = roleRank(role) >= RANK_PROFESSOR

/* ────────────────────────────────────────────────────────────────────────────
 * Capability rules — the Kotlin mirror of `backend/app/core/deps.py`.
 *
 * These MUST agree with the server, in both directions. A rule that is stricter than the backend
 * silently removes a screen from someone entitled to it (a professor with no questionnaire builder);
 * a rule that is looser lets a user fill in a whole form and then eat a 403 on save. Both were
 * present before this block existed, which is why every rule now lives in exactly one place, named
 * after the backend dependency it mirrors.
 * ──────────────────────────────────────────────────────────────────────────── */

/** `is_admin` — admin and master admin. */
private fun UserDto.isAdminUser(): Boolean = roleRank(role) >= RANK_ADMIN

/** `is_master_admin`. */
private fun UserDto.isMasterAdminUser(): Boolean = role == "MASTER_ADMIN"

/**
 * `can_create_records` / `require_record_creator` — OPENING an artisan, product, process, tool or
 * interview. Researcher and above.
 *
 * The two tiers below POPULATE records rather than open them: attaching media to an existing
 * artisan, answering an existing interview, commenting. None of those three passes through here, so
 * hiding a "New …" card from a field contributor never hides a contribution path — it removes a form
 * that ended in a 403 after they had filled it in.
 */
private fun UserDto.canCreateRecords(): Boolean = roleRank(role) >= RANK_RESEARCHER

/** `can_access_review` / `require_reviewer` — Field Contributor and above, or an explicit grant. */
private fun UserDto.canAccessReview(): Boolean = roleRank(role) >= RANK_FIELD_CONTRIBUTOR || canReview

/** `can_download_dataset` / `require_dataset_downloader` — Professor and above, or a grant. */
private fun UserDto.canDownloadTheDataset(): Boolean = roleRank(role) >= RANK_PROFESSOR || canDownloadDataset

/** `can_manage_questionnaire` / `require_questionnaire_manager` — Professor and above, or a grant. */
private fun UserDto.canManageTheQuestionnaire(): Boolean =
    roleRank(role) >= RANK_PROFESSOR || canManageQuestionnaire

/**
 * `can_manage_crafts` / `require_craft_manager` — Professor and above, RANK ALONE.
 *
 * The `canManageCrafts` grant is deliberately not consulted. The server stopped reading it (deps.py)
 * because a per-user grant that lifted a researcher over the taxonomy was invisible in the role
 * column, and a client that kept ORing it in offers the Add-craft form to someone the API will
 * refuse. Promote the person instead; deleting a craft is stricter still (admin).
 */
private fun UserDto.canManageTheCrafts(): Boolean = roleRank(role) >= RANK_PROFESSOR

/** `can_manage_workshops` / `require_workshop_manager` — Professor+, rank alone; see above. */
private fun UserDto.canManageTheWorkshops(): Boolean = roleRank(role) >= RANK_PROFESSOR

/** `require_professor` on GET/PATCH /users — the user table opens for Professor and above. */
private fun UserDto.canManageUsers(): Boolean = roleRank(role) >= RANK_PROFESSOR

/** The status a NEW record defaults to for the given role: APPROVED for professor+, PENDING below. */
private fun defaultCreateStatus(role: String?): String = if (canSetRecordStatus(role)) "APPROVED" else "PENDING"
private val genderOptions = listOf("Male", "Female", "Transgender", "Other")

/**
 * App-level controller that lets a record form tell the back navigation "I have unsaved work, and
 * here's how to save it". The shell consults this before leaving a Create/Edit screen so an accidental
 * Back (or the in-app back arrow) shows a Save / Discard prompt instead of silently dropping the
 * record and its in-progress recordings. Exactly one form registers at a time (see
 * [RegisterUnsavedGuard]); it clears itself when the form leaves composition.
 */
private class UnsavedGuard {
    /** Whether the active form currently has unsaved changes worth prompting about. */
    var dirty by mutableStateOf(false)
    /** Runs the active form's own validated save (same as its Save button) — null when no form is shown. */
    var onSave: (() -> Unit)? = null

    fun clear() {
        dirty = false
        onSave = null
    }
}

private val LocalUnsavedGuard = staticCompositionLocalOf<UnsavedGuard?> { null }

/**
 * Register the current form with the app-level [UnsavedGuard] so the Back navigation can offer to save
 * it. [dirty] should be true whenever there is unsaved content (changed fields, or attached/recorded
 * media not yet persisted); [onSave] must perform the SAME validated save the form's Save button does
 * (validation failures keep the user on the form with the offending field highlighted).
 */
@Composable
private fun RegisterUnsavedGuard(dirty: Boolean, onSave: () -> Unit) {
    val guard = LocalUnsavedGuard.current ?: return
    val currentSave by rememberUpdatedState(onSave)
    LaunchedEffect(dirty) { guard.dirty = dirty }
    DisposableEffect(Unit) {
        guard.onSave = { currentSave() }
        onDispose { guard.clear() }
    }
}

/** Shared holder for media attachments, captured GPS, and an optional measurement-grid image. */
private class MediaCaptureState {
    var uris by mutableStateOf<List<Uri>>(emptyList())
    var location by mutableStateOf<LocationRequest?>(null)
    var measurementUri by mutableStateOf<Uri?>(null)

    // Eager-upload bookkeeping. `stagedDeferred` is the in-flight pre-upload per uri; `staged`
    // mirrors the completed results for UI status; `stagedProgress` is 0..1 per uri for the progress
    // bar; `stagedFailed` marks uris whose eager upload errored (retried at save). Managed by
    // MediaCaptureSection.
    val stagedDeferred = mutableMapOf<Uri, Deferred<StagedMedia?>>()
    var staged by mutableStateOf<Map<Uri, StagedMedia>>(emptyMap())
    var stagedProgress by mutableStateOf<Map<Uri, Float>>(emptyMap())
    var stagedFailed by mutableStateOf<Set<Uri>>(emptySet())

    /** Forget all eager-upload state for one uri (used when the user discards a single attachment). */
    fun forget(uri: Uri) {
        stagedDeferred.remove(uri)
        staged = staged - uri
        stagedProgress = stagedProgress - uri
        stagedFailed = stagedFailed - uri
    }

    fun reset() {
        uris = emptyList()
        location = null
        measurementUri = null
        stagedDeferred.clear()
        staged = emptyMap()
        stagedProgress = emptyMap()
        stagedFailed = emptySet()
    }
}

@Composable
private fun rememberMediaCaptureState(): MediaCaptureState = remember { MediaCaptureState() }

/**
 * Start (or restart) the eager pre-upload of ONE attachment to object storage, keeping the shared
 * [MediaCaptureState] progress/staged/failed bookkeeping in sync. This is the single source of truth
 * for "stream a captured file as soon as it is attached" — used both by the capture effects (for every
 * newly-added uri) and by the per-file "Retry" button when an eager upload failed. [uiScope] is a
 * composition-scoped scope used only to fold the result back into UI state; the transfer itself runs
 * on the process-lifetime [AppScope] so it survives recomposition.
 */
private fun startEagerUpload(
    repository: FieldRepository,
    context: Context,
    media: MediaCaptureState,
    uri: Uri,
    uiScope: CoroutineScope
) {
    // Reset any prior bookkeeping for this uri so a retry re-runs from a clean slate.
    media.stagedDeferred.remove(uri)
    media.staged = media.staged - uri
    media.stagedFailed = media.stagedFailed - uri
    media.stagedProgress = media.stagedProgress + (uri to 0f)
    val deferred = AppScope.io.async {
        var lastPct = -1
        runCatching {
            repository.preuploadObject(context, uri) { sent, total ->
                // Throttle to whole-percent changes so a big file doesn't thrash recomposition.
                val pct = if (total > 0L) ((sent * 100) / total).toInt() else 0
                if (pct != lastPct) {
                    lastPct = pct
                    media.stagedProgress = media.stagedProgress + (uri to pct / 100f)
                }
            }
        }.getOrNull()
    }
    media.stagedDeferred[uri] = deferred
    uiScope.launch {
        val result = runCatching { deferred.await() }.getOrNull()
        if (result != null) {
            media.staged = media.staged + (uri to result)
            media.stagedProgress = media.stagedProgress + (uri to 1f)
            media.stagedFailed = media.stagedFailed - uri
        } else {
            media.stagedFailed = media.stagedFailed + uri
        }
    }
}

private fun LocalDate.toIsoInstant(): String =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toString()

/** Parse an ISO datetime/date string (as returned by the API) into a LocalDate, best-effort. */
private fun parseIsoToLocalDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return runCatching { java.time.OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
}

/** Format an ISO datetime string (as returned by the API) into a short readable date, best-effort. */
private fun formatIsoDate(value: String?): String? {
    val date = parseIsoToLocalDate(value) ?: return null
    return runCatching {
        date.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy"))
    }.getOrNull()
}

/** Render a numeric value into an editable string without a trailing ".0" for whole numbers. */
private fun numToText(value: String?): String {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    val number = raw.toDoubleOrNull() ?: return raw
    return if (number % 1.0 == 0.0) number.toLong().toString() else raw
}

private fun numToText(value: Double?): String {
    if (value == null) return ""
    return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}

/**
 * On edit, only send a location when it actually changed. The backend creates a fresh Location row
 * and re-checks per-field ownership for any present field, so resending the unchanged coordinates
 * would both duplicate rows and falsely lock out non-owner contributors.
 */
private fun locationForBody(isEdit: Boolean, current: LocationRequest?, original: LocationDto?): LocationRequest? {
    if (!isEdit) return current
    if (current == null) return null
    if (original != null &&
        kotlin.math.abs(current.latitude - original.latitude) < 1e-6 &&
        kotlin.math.abs(current.longitude - original.longitude) < 1e-6 &&
        // The postal half counts as a change too. Comparing only the coordinate meant a researcher
        // could add the state and pincode to a legacy location, save, and watch both answers vanish.
        current.state == original.state &&
        current.pincode == original.pincode &&
        // And so does the rest of the stated half — district, village, the subject's pin. Same
        // reasoning one rung down: a researcher who opens a legacy record purely to say which
        // district it is in would otherwise watch the answer disappear on save, because the
        // coordinate did not move. Compared as COLUMNS rather than trusting the metadata mirror
        // beneath them: the mirror is written for older phones, and a comparison that can only see
        // it would go blind the day it is finally retired.
        current.district == original.district &&
        current.village == original.village &&
        current.subjectLatitude == original.subjectLatitude &&
        current.subjectLongitude == original.subjectLongitude &&
        current.extraMetadata == original.extraMetadata
    ) {
        return null
    }
    return current
}

/**
 * The reason this form may not open a NEW record yet, or null when it may.
 *
 * The server refuses a create that carries no location, and refuses one whose location does not say
 * which state and district the SUBJECT is in (`require_location`, backend/app/schemas/common.py).
 * Both dropdowns are filled from `GET /reference/address`, which is a pure constant the phone caches
 * for good, so this is answerable in a workshop with no signal — and answering it here rather than
 * at the server is what stops an offline save from sitting in the outbox being retried against a
 * body that can never be accepted, discovered days later a long way from the artisan.
 *
 * NOTHING IS ASKED OF AN EDIT, matching `forbid_clearing_location` exactly. The records written
 * before those columns existed carry no stated address at all, and a researcher who opened one to
 * correct a phone number must be able to save it without inventing a district from a desk. The card
 * flags the gap and invites them to close it; refusing the save would close it by guesswork.
 */
private fun newRecordLocationError(isEdit: Boolean, current: LocationRequest?): String? =
    if (isEdit) null else artisanLocationRequirementError(current)

/**
 * Convert a read-model location into the request payload used by create/update calls.
 *
 * EVERY STATED FIELD HAS TO BE HERE. `attach_location` builds a BRAND NEW Location row out of this
 * body on update — it does not patch the stored one — and `forbid_clearing_location` deliberately
 * lets an update through without a stated address, so anything this function forgets is not
 * rejected, it is erased. That is how district, village and the subject pin came to be silently
 * nulled by a phone opening a web-entered record to correct a phone number.
 */
private fun LocationDto.toRequest(): LocationRequest =
    LocationRequest(
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracy = accuracy,
        address = address,
        placeName = placeName ?: address,
        state = state,
        district = district,
        village = village,
        pincode = pincode,
        subjectLatitude = subjectLatitude,
        subjectLongitude = subjectLongitude,
        capturedAt = capturedAt,
        // The pre-column shape of the same four answers, carried through untouched so a record that
        // only has them keeps them (see the note atop ui/LocationFields.kt).
        extraMetadata = extraMetadata
    )

// ---------------------------------------------------------------------------
// The postal half of an address: state (closed list) and pincode (6 digits).
// ---------------------------------------------------------------------------

/** Indian PIN codes are six digits and never begin with 0 — the first digit is the postal zone. */
internal const val PINCODE_LENGTH = 6

/**
 * The reason [value] is not a usable pincode, or null when it is fine (blank included — the field is
 * optional). Same three checks, in the same order and with the same sentences, as `pincode_error` in
 * backend/app/services/address.py, so the researcher reads one message whether it was caught here or
 * by the API — and reads it before the round trip rather than as a 422 after it.
 */
internal fun pincodeValidationError(value: String?): String? {
    val digits = value?.trim().orEmpty()
    if (digits.isEmpty()) return null
    // ASCII digits only, for the same reason as the Aadhaar validator: Char.isDigit() admits the
    // Devanagari and fullwidth digits an Indic IME produces, and those would be stored verbatim,
    // giving one village two pincodes no query could ever match to each other.
    if (!digits.all { it in '0'..'9' }) return "Pincode must be 6 digits — remove any letters or symbols."
    if (digits.length != PINCODE_LENGTH) return "Pincode must be exactly 6 digits (this one has ${digits.length})."
    if (digits[0] == '0') return "Pincodes never start with 0 — please re-check the first digit."
    return null
}

/** A state name reduced to its comparison key, exactly as `_fold` in services/address.py does it. */
private fun foldStateName(value: String): String =
    value.lowercase().replace("&", "and").filter { it in 'a'..'z' || it in '0'..'9' }

/**
 * The entry of [states] that [text] names, or "" when nothing on the list matches.
 *
 * The geocoder's wording is not the register's ("NCT of Delhi", "Daman and Diu"), so an exact fold is
 * tried first and a containment match second — long enough on either side that a short name like Goa
 * cannot be swallowed by an unrelated word. Returning "" rather than the raw text is deliberate: the
 * list is closed, so a name the API would reject is worse than no suggestion at all, and a value the
 * dropdown cannot show is a value the researcher cannot see, let alone correct.
 */
internal fun matchIndianState(text: String, states: List<String>): String {
    val wanted = foldStateName(text)
    if (wanted.isEmpty()) return ""
    states.firstOrNull { foldStateName(it) == wanted }?.let { return it }
    return states.firstOrNull { entry ->
        val name = foldStateName(entry)
        (name.length >= 5 && wanted.contains(name)) || (wanted.length >= 5 && name.contains(wanted))
    }.orEmpty()
}

/**
 * The record's location, in the two halves it actually has: what the researcher says about the
 * artisan, and what this device says about itself.
 *
 * A thin wrapper now. Everything below the name lives in ui/LocationFields.kt — the two groups, the
 * offline-cached state and district dropdowns, the accuracy gate on the geocoder, and the rule that
 * a point with no address CLEARS the last point's guess instead of leaving it behind. The name is
 * kept because three forms call it and the concurrency of this file is not worth spending on a
 * rename.
 *
 * WHAT THE OLD BODY GOT WRONG, kept here as the reason not to put it back. It offered the geocoder
 * a state and a pincode for every fix regardless of its accuracy radius, and it read a geocoder
 * that answered "nothing" as "leave what is there" — so a pin corrected from Bagru to Dehradun
 * kept Bagru's pincode, silently, on a record that now said Uttarakhand. It also had no field in
 * which a researcher could say where the ARTISAN was, which is why fifteen live records carry a
 * Kharagpur coordinate under a Rajasthani place name typed into a free-text box.
 */
@Composable
private fun LocationAddressEditor(
    repository: FieldRepository,
    value: LocationRequest?,
    // Retained so the three call sites need no edit. LocationCaptureCard drives its own permission
    // flow and its own live fix, so nothing has needed a caller-supplied one-shot read since.
    @Suppress("UNUSED_PARAMETER") onUseGps: () -> LocationRequest?,
    onChange: (LocationRequest?) -> Unit,
    onMessage: (String) -> Unit = {},
    required: Boolean = true,
    isEdit: Boolean = false,
    showRequirementError: Boolean = false
) {
    LocationFieldsSection(
        repository = repository,
        value = value,
        onChange = onChange,
        required = required,
        isEdit = isEdit,
        showRequirementError = showRequirementError,
        onMessage = onMessage
    )
}

/** Two coordinates the researcher would call the same place (the editor re-emits on every keystroke). */
internal fun sameCoordinate(next: LocationRequest, previous: LocationRequest?): Boolean =
    previous != null &&
        kotlin.math.abs(next.latitude - previous.latitude) < 1e-6 &&
        kotlin.math.abs(next.longitude - previous.longitude) < 1e-6

private suspend fun uploadAttachments(
    repository: FieldRepository,
    context: Context,
    media: MediaCaptureState,
    recordType: String,
    recordId: String,
    titleHint: String?,
    caption: String?,
    customSegment: String? = null
) {
    // Resilient: attempt every attachment so one bad file never blocks the rest; the saved record
    // already persisted before this runs, so partial uploads are kept and only the failures surface.
    val failures = mutableListOf<String>()
    media.uris.forEachIndexed { index, uri ->
        // Prefer the eagerly pre-uploaded object (awaiting any still-in-flight transfer); only fall
        // back to a fresh upload if pre-upload never started or failed.
        val staged = media.stagedDeferred[uri]?.let { runCatching { it.await() }.getOrNull() } ?: media.staged[uri]
        val result = runCatching {
            if (staged != null) {
                repository.completeStaged(
                    staged = staged,
                    linkedRecordType = recordType,
                    linkedRecordId = recordId,
                    recordName = titleHint,
                    caption = caption,
                    location = media.location,
                    batchIndex = index + 1,
                    customSegment = customSegment
                )
            } else {
                repository.uploadMedia(
                    context = context,
                    uri = uri,
                    linkedRecordType = recordType,
                    linkedRecordId = recordId,
                    caption = caption,
                    location = media.location,
                    titleHint = titleHint,
                    batchIndex = index + 1,
                    customSegment = customSegment
                )
            }
        }
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            if (error is kotlinx.coroutines.CancellationException) throw error
            failures.add(uri.lastPathSegment ?: "file ${index + 1}")
        }
    }
    if (failures.isNotEmpty()) {
        val allFailed = failures.size == media.uris.size
        val prefix = if (allFailed) "All ${failures.size} media file(s) failed to upload" else "${failures.size} media file(s) failed to upload"
        throw IllegalStateException(
            "$prefix (${failures.joinToString(", ")}). The record was saved — check your connection " +
                "and re-open it from \"Update existing\" to re-attach the media."
        )
    }
}

private suspend fun uploadMeasurement(
    repository: FieldRepository,
    context: Context,
    media: MediaCaptureState,
    recordType: String,
    recordId: String,
    titleHint: String?
) {
    val uri = media.measurementUri ?: return
    repository.uploadMedia(
        context = context,
        uri = uri,
        linkedRecordType = recordType,
        linkedRecordId = recordId,
        caption = "Measurement grid image for ${titleHint.orEmpty()}".trim(),
        location = media.location,
        titleHint = "${titleHint.orEmpty()} measurement grid".trim(),
        batchIndex = 1,
        processingRequests = listOf("MEASUREMENT")
    )
}

/**
 * "Document using grid": pick which dimensions to capture (length / breadth / height); each enabled
 * dimension gets its own grid-photo capture. On capture the photo is sent to the vision model for
 * that one dimension and the returned inches auto-fill the matching field; the photo is ALSO pushed
 * into the shared [media] attach-media batch so it is eager-uploaded, shown in the upload progress
 * list, and saved as media for this record (no separate, invisible grid-upload path).
 */
@Composable
private fun GridMeasurementSection(
    repository: FieldRepository,
    media: MediaCaptureState,
    includeHeight: Boolean = true,
    onLengthBreadth: (length: Double?, breadth: Double?) -> Unit,
    onHeight: (Double) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(setOf<String>()) }
    var capturedUris by remember { mutableStateOf<Map<String, Uri>>(emptyMap()) }
    var status by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingGroup by remember { mutableStateOf<String?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    // group keys: "lengthBreadth" (one photo → both length & breadth) and "height" (one photo).
    fun analyze(group: String, uri: Uri) {
        // Re-capturing a dimension replaces its previous photo: drop the old uri from the shared
        // media batch (and delete its staged object) so the record never keeps a stale grid image.
        capturedUris[group]?.let { previous ->
            if (previous != uri) {
                val deferred = media.stagedDeferred[previous]
                media.forget(previous)
                media.uris = media.uris.filterNot { it == previous }
                AppScope.io.launch { runCatching { deferred?.await()?.let { repository.deleteStaged(it.objectKey) } } }
            }
        }
        capturedUris = capturedUris + (group to uri)
        // Route the grid photo into the shared attach-media batch — this is what makes it visible in
        // the upload progress list and persisted as media on save (in addition to auto-filling dims).
        if (uri !in media.uris) media.uris = media.uris + uri
        status = status + (group to "Analyzing…")
        scope.launch {
            if (group == "lengthBreadth") {
                runCatching { repository.analyzeMeasurementLengthBreadth(context, uri) }
                    .onSuccess { (length, breadth) ->
                        onLengthBreadth(length, breadth)
                        val parts = buildList {
                            if (length != null && length > 0) add("L ${"%.2f".format(length)}\"")
                            if (breadth != null && breadth > 0) add("B ${"%.2f".format(breadth)}\"")
                        }
                        status = status + (group to if (parts.isEmpty()) "Couldn't read a value — enter it manually"
                        else "Measured ${parts.joinToString(" · ")} — fields filled")
                    }
                    .onFailure { status = status + (group to "Analysis failed — enter it manually") }
            } else {
                runCatching { repository.analyzeMeasurement(context, uri, "height") }
                    .onSuccess { value ->
                        if (value != null && value > 0.0) {
                            onHeight(value)
                            status = status + (group to "Measured ${"%.2f".format(value)} in — field filled")
                        } else {
                            status = status + (group to "Couldn't read a value — enter it manually")
                        }
                    }
                    .onFailure { status = status + (group to "Analysis failed — enter it manually") }
            }
        }
    }

    // Discard a grid dimension's photo via the same ✕ cross used elsewhere. This is the *grid-side*
    // removal, so per the required behaviour it ALSO drops the photo from the shared attach-media
    // batch (and deletes its staged object) — a full discard. (The reverse is not coupled: removing
    // the file from the uploaded-media list leaves this grid capture untouched, since that list and
    // capturedUris are independent state.)
    fun discardGroup(group: String) {
        val uri = capturedUris[group] ?: return
        val deferred = media.stagedDeferred[uri]
        media.forget(uri)
        media.uris = media.uris.filterNot { it == uri }
        AppScope.io.launch { runCatching { deferred?.await()?.let { repository.deleteStaged(it.objectKey) } } }
        capturedUris = capturedUris - group
        status = status - group
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val g = pendingGroup
        if (uri != null && g != null) analyze(g, uri)
        pendingGroup = null
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCaptureUri
        val g = pendingGroup
        if (success && uri != null && g != null) analyze(g, uri)
        pendingCaptureUri = null
        pendingGroup = null
    }

    @Composable
    fun GridGroupRow(key: String, label: String, hint: String) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = enabled.contains(key),
                onCheckedChange = { checked -> enabled = if (checked) enabled + key else enabled - key }
            )
            Text(label, color = Body, fontSize = 14.sp)
        }
        if (enabled.contains(key)) {
            Text(hint, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        val uri = createAppFileUri(context, "grid-$key-", ".jpg")
                        pendingGroup = key
                        pendingCaptureUri = uri
                        takePhoto.launch(uri)
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text("Capture photo", maxLines = 1, softWrap = false, fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { pendingGroup = key; pickImage.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text("Pick photo", maxLines = 1, softWrap = false, fontSize = 12.sp) }
            }
            status[key]?.let { Text(it, color = Muted, fontSize = 11.sp) }
            capturedUris[key]?.let { uri ->
                AndroidUriPreview(context = context, uri = uri, onRemove = { discardGroup(key) })
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Document using grid", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "Place the object on a 1-inch grid sheet. Length and breadth are read from a single top-down " +
                "photo; height needs its own side-on photo. The measured inches auto-fill the fields (still editable).",
            color = Muted,
            fontSize = 12.sp
        )
        GridGroupRow("lengthBreadth", "Length & breadth (one photo)", "Top-down photo of the object on the grid — fills both length and breadth.")
        if (includeHeight) {
            GridGroupRow("height", "Height (one photo)", "Side-on photo of the object against the grid — fills height.")
        }
    }
}

/**
 * Every one-of-many field in the record forms. Now a thin adapter over [SearchableSelectField],
 * which keeps this anchored menu for short lists and opens the searchable sheet once the list is
 * long enough to scroll — so the artisan, tool, craft and state pickers all gained a search box
 * without any of the thirty-odd call sites below changing.
 */
@Composable
private fun DropdownField(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    placeholder: String = "Select",
    includeNone: Boolean = true,
    enabled: Boolean = true,
    onSelect: (String) -> Unit
) {
    SearchableSelectField(
        label = label,
        options = remember(options) { options.asSelectOptions() },
        selectedValue = selectedValue,
        placeholder = placeholder,
        includeNone = includeNone,
        enabled = enabled,
        onSelect = onSelect
    )
}

@Composable
private fun StatusDropdown(value: String, onSelect: (String) -> Unit) {
    DropdownField(
        label = "Status",
        options = statusOptions.map { it to it },
        selectedValue = value,
        includeNone = false,
        onSelect = onSelect
    )
}

/**
 * Record-status control honouring the create policy. PROFESSOR+ ([canSetStatus]) get the full status
 * picker (defaulting APPROVED on create); everyone below sees a non-editable "Pending" chip — their
 * records are forced to PENDING and the backend drops any status they try to change.
 */
@Composable
private fun StatusControl(canSetStatus: Boolean, value: String, onSelect: (String) -> Unit) {
    if (canSetStatus) {
        StatusDropdown(value = value, onSelect = onSelect)
    } else {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status", color = Muted, fontSize = 12.sp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .background(SurfaceCard, RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Box(modifier = Modifier.size(8.dp).background(Color(0xFFE2A400), CircleShape))
                Text("Pending", color = Body, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Text("New records are reviewed before they're published.", color = Muted, fontSize = 11.sp)
        }
    }
}

// ---------------------------------------------------------------------------
// Workshop linkage. Field work happens at a workshop, so the workshop is the
// primary context for every record: each form opens with the same picker, above
// the craft / artisan / product (secondary, tertiary) selects. The loading and
// defaulting rules live here once, in [rememberWorkshopPicker], rather than
// being re-typed on each form.
// ---------------------------------------------------------------------------

/**
 * The workshops offered by a record form's picker plus the one currently linked.
 *
 * [baselineId] is what the form treats as "no unsaved change": it moves together with the
 * create-time auto-default (so the default alone never marks a pristine form dirty) but stays put
 * when the user picks a workshop themselves, which is exactly the change the unsaved-work guard
 * should catch.
 *
 * It also owns the SUBMISSION PRE-FLIGHT (`GET /workshops/{id}/submission-check`, web parity with
 * `useWorkshopSelection`). The workshop decides two things the researcher has to learn BEFORE they
 * save rather than after:
 *
 * 1. **Assignment** — a curated workshop 403s a submission from anybody not on its roster.
 * 2. **The window** — a record filed after the workshop ended is accepted, but pinned to PENDING and
 *    flagged, after which only an admin can approve it. [confirmSubmission] surfaces that as a
 *    confirmation and the save proceeds only if the researcher says yes.
 *
 * Both answers are advisory. When the pre-flight cannot be reached the state degrades to a local
 * "this workshop looks like it ended" hint and NEVER blocks the save: a researcher in the field must
 * not lose work to a flaky courtesy request.
 */
/** How far down the occurrence order the create-time default walks looking for a submittable workshop. */
private const val DEFAULT_PROBE_LIMIT = 5

private class WorkshopPickerState(private val repository: FieldRepository, initialId: String) {
    var workshops by mutableStateOf<List<WorkshopDetailDto>>(emptyList())
    var selectedId by mutableStateOf(initialId)
    var baselineId by mutableStateOf(initialId)

    /** Pre-flight answer for the CURRENT selection; null while it loads or when it is unavailable. */
    var check by mutableStateOf<WorkshopSubmissionCheckDto?>(null)
        private set

    /** The answer the open late-submission confirmation is about; null when no dialog is up. */
    var pendingConfirm by mutableStateOf<WorkshopSubmissionCheckDto?>(null)
        private set

    private var awaitingConfirm: CompletableDeferred<Boolean>? = null

    // Successful answers only. A failure is deliberately NOT cached, so a blip retries on the next
    // selection or at submit time instead of disabling the gate for the rest of the session.
    private val answers = mutableMapOf<String, WorkshopSubmissionCheckDto>()

    /** The value to put in a create/update body — null when the record is deliberately unlinked. */
    fun value(): String? = selectedId.ifBlank { null }

    /** True once the user has changed the workshop away from the loaded/auto-defaulted one. */
    fun isDirty(): Boolean = selectedId != baselineId

    /** Pre-select a workshop without counting as an edit (create-time default only). */
    fun applyDefault(id: String) {
        selectedId = id
        baselineId = id
    }

    /**
     * The create-time default: the most recent workshop this user may ACTUALLY submit to.
     *
     * Web parity with `useWorkshopSelection`'s probe. Taking the head of the list outright landed a
     * researcher on a workshop they are not assigned to whenever the newest one was somebody else's —
     * a form that opens already refusing to save. This walks down the occurrence order instead, and
     * only when every recent workshop is out of reach does it settle on the most recent anyway, so
     * the inline warning has something to explain rather than the field being silently empty.
     */
    suspend fun applyMostRecentSubmittable(list: List<WorkshopDetailDto>) {
        if (list.isEmpty()) return
        for (workshop in list.take(DEFAULT_PROBE_LIMIT)) {
            val answer = answerFor(workshop.id)
            // The user may have picked one themselves while the probe was in flight; their choice wins.
            if (selectedId.isNotBlank()) return
            if (answer == null || answer.canSubmit) {
                applyDefault(workshop.id)
                return
            }
        }
        if (selectedId.isBlank()) applyDefault(list.first().id)
    }

    /**
     * Treat the current selection as saved. Used by forms that stay on screen after a save (the
     * questionnaire) so the workshop carries over to the next record without still reading as an
     * unsaved change.
     */
    fun markSaved() {
        baselineId = selectedId
    }

    private suspend fun answerFor(workshopId: String): WorkshopSubmissionCheckDto? {
        if (workshopId.isBlank()) return null
        answers[workshopId]?.let { return it }
        return repository.workshopSubmissionCheck(workshopId)?.also { answers[workshopId] = it }
    }

    /** Bring [check] in step with the current selection. Cache hits cost no request. */
    suspend fun refreshCheck() {
        val asked = selectedId
        check = null
        if (asked.isBlank()) return
        val answer = answerFor(asked)
        // The user may have moved on while the request was in flight; a stale answer must not be
        // shown against a different workshop.
        if (selectedId == asked) check = answer
    }

    /**
     * Call FIRST inside a form's save coroutine, before it sets `saving`. Returns true when the save
     * may go ahead, false only when the researcher backed out of a late submission.
     *
     * No workshop, no answer (offline / endpoint missing), or a workshop still running all return
     * true immediately — the gate exists to inform, never to block.
     */
    suspend fun confirmSubmission(): Boolean {
        val asked = selectedId
        if (asked.isBlank()) return true
        val answer = answerFor(asked) ?: return true
        if (!answer.outOfWindow && !answer.isOver) return true
        val gate = CompletableDeferred<Boolean>()
        awaitingConfirm = gate
        pendingConfirm = answer
        return gate.await()
    }

    /** Close the confirmation and release the waiting save coroutine with the user's answer. */
    fun settleConfirm(confirmed: Boolean) {
        pendingConfirm = null
        awaitingConfirm?.complete(confirmed)
        awaitingConfirm = null
    }
}

/**
 * Loads the workshops this user may submit to, most recent date of occurrence first, and — on
 * CREATE only — pre-selects the most recent one.
 *
 * [initialId] is the workshop already stored on the record being edited; it is never overwritten,
 * so opening an existing record leaves its linkage exactly as saved (and a record deliberately left
 * unlinked stays unlinked, because [isEdit] blocks the default outright). [resetKey] should be the
 * `editing` record so switching records rebuilds the state.
 */
@Composable
private fun rememberWorkshopPicker(
    repository: FieldRepository,
    isEdit: Boolean,
    initialId: String?,
    resetKey: Any? = null
): WorkshopPickerState {
    val state = remember(resetKey) { WorkshopPickerState(repository, initialId.orEmpty()) }
    LaunchedEffect(resetKey) {
        // A failure here is non-fatal: the dropdown simply stays empty and the record saves unlinked,
        // which is better than blocking a field capture on a list request.
        runCatching { repository.workshopsByOccurrence() }.onSuccess { list ->
            state.workshops = list
            // The list is ordered most-recent-occurrence-first; the default is the most recent one
            // this user may actually submit to (see applyMostRecentSubmittable).
            if (!isEdit && state.selectedId.isBlank()) {
                state.applyMostRecentSubmittable(list)
            }
        }
    }
    // Keep the pre-flight answer in step with whatever is selected, including the auto-default, so
    // the warning is already on screen by the time the researcher reaches the save button.
    LaunchedEffect(state, state.selectedId) { state.refreshCheck() }
    return state
}

/**
 * The workshop field every record form mounts as its FIRST field: the picker itself, plus whatever
 * the submission pre-flight has to say about the current pick, plus the late-submission confirmation
 * that [WorkshopPickerState.confirmSubmission] opens. Web parity with `<WorkshopSelect>`.
 *
 * The two warnings are mutually exclusive by design: "you are not assigned" is the harder problem
 * and stating both at once would only bury it.
 */
@Composable
private fun WorkshopField(state: WorkshopPickerState, saving: Boolean = false) {
    val selected = state.workshops.firstOrNull { it.id == state.selectedId }
    val check = state.check
    val blocked = check != null && !check.canSubmit
    // Prefer the server's verdict; fall back to the workshop's own dates when there is no answer.
    val late = if (check != null) {
        check.outOfWindow || check.isOver
    } else {
        state.selectedId.isNotBlank() && workshopEndedLocally(selected)
    }
    val endLabel = formatIsoDate(check?.endDate ?: selected?.endDate ?: selected?.date)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WorkshopDropdown(
            workshops = state.workshops,
            selectedValue = state.selectedId
        ) { state.selectedId = it }
        if (blocked) {
            Text(
                "You are not assigned to this workshop, so saving will be refused. Ask an admin to " +
                    "assign you to it, or pick another workshop.",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )
        } else if (late) {
            Text(
                (if (endLabel == null) "This workshop has already ended." else "This workshop ended on $endLabel.") +
                    " " +
                    if (check == null || check.needsAdminApproval) {
                        "Saving now counts as a late submission and needs an admin's approval."
                    } else {
                        "Saving now is recorded as a late submission."
                    },
                color = Coral,
                fontSize = 12.sp
            )
        }
    }

    state.pendingConfirm?.let { pending ->
        LateSubmissionDialog(
            workshopTitle = pending.title ?: selected?.title,
            endDate = pending.endDate ?: selected?.endDate ?: selected?.date,
            needsAdminApproval = pending.needsAdminApproval,
            saving = saving,
            onConfirm = { state.settleConfirm(true) },
            onCancel = { state.settleConfirm(false) }
        )
    }
}

/**
 * Confirmation shown when a record is about to be saved into a workshop that has already ended.
 *
 * The backend ACCEPTS a late submission but pins it to PENDING and stamps
 * `extraMetadata.workshopSubmission.needsAdminApproval`, after which only an admin or master admin
 * may approve it. That is a real consequence for the researcher — the professor who normally reviews
 * their work cannot clear this one — so it is stated up front instead of discovered in the review
 * queue. Wording is the web's `<LateSubmissionDialog>`, word for word.
 *
 * Admins are the approval authority, so their own late submission is never flagged: they get the
 * shorter sentence and no promise of somebody else's approval.
 */
@Composable
private fun LateSubmissionDialog(
    workshopTitle: String?,
    endDate: String?,
    needsAdminApproval: Boolean,
    saving: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val name = workshopTitle?.trim().orEmpty().ifBlank { "This workshop" }
    val ended = formatIsoDate(endDate)?.let { " ended on $it" } ?: " has already ended"
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text("Late submission") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "$name$ended. " +
                        if (needsAdminApproval) {
                            "Your entry will still be saved, but it is recorded as a late submission: " +
                                "it stays Pending until an admin or master admin approves it — a " +
                                "professor cannot approve it for you."
                        } else {
                            "Your entry will still be saved, and it is recorded as a late submission."
                        }
                )
                Text(
                    "Pick a different workshop above if this record belongs to one that is still running.",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = onConfirm) {
                Text(if (saving) "Saving…" else "Submit anyway")
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onCancel) { Text("Go back") }
        }
    )
}

/**
 * Local "has this workshop ended?", used only when the pre-flight answer is unavailable. Mirrors the
 * backend rule (and the web's `endedLocally`): the whole of the end day is still in-window.
 */
private fun workshopEndedLocally(workshop: WorkshopDetailDto?): Boolean {
    val raw = workshop?.endDate ?: workshop?.date ?: workshop?.startDate ?: return false
    val end = parseIsoToLocalDate(raw) ?: return false
    return LocalDate.now(ZoneId.systemDefault()).isAfter(end)
}

/**
 * The workshop dropdown itself. Styling and behaviour are [DropdownField]'s; only the option labels
 * are workshop-specific. Record forms mount [WorkshopField] rather than this, so they get the
 * pre-flight warnings with it.
 */
@Composable
private fun WorkshopDropdown(
    workshops: List<WorkshopDetailDto>,
    selectedValue: String,
    label: String = "Workshop",
    placeholder: String = "Unlinked",
    enabled: Boolean = true,
    onSelect: (String) -> Unit
) {
    DropdownField(
        label = label,
        options = workshops.map { it.id to workshopOptionLabel(it) },
        selectedValue = selectedValue,
        placeholder = placeholder,
        enabled = enabled,
        onSelect = onSelect
    )
}

/** "Chanderi weaving · 2026-07-12" — the date it took place separates repeat visits to one place. */
private fun workshopOptionLabel(workshop: WorkshopDetailDto): String {
    val title = workshop.title.ifBlank { "Untitled workshop" }
    val day = workshop.occurrenceDate().take(10)
    return if (day.isBlank()) title else "$title · $day"
}

/**
 * The shape an email address has to have, character for character the web form's `EMAIL_RE`
 * (`/^[^\s@]+@[^\s@]+\.[^\s@]+$/` in components/forms/ArtisanForm.tsx): something, an @, something,
 * a dot, something — no spaces anywhere.
 *
 * Deliberately the WEB's rule rather than `Patterns.EMAIL_ADDRESS` or a stricter RFC pattern: the two
 * clients write into one column, and the same artisan must not be accepted on the phone and refused
 * in the browser. "a@b" fails here exactly as it fails there.
 */
private val EMAIL_RE = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")

// ---------------------------------------------------------------------------
// Artisan identity: Aadhaar number and Artisan Pehchan (PM Vishwakarma) card.
//
// The Aadhaar number is the repository's DEDUPLICATION key — the same person documented at two
// workshops by two researchers has to resolve to one artisan, and a unique index on the column is
// what enforces that. A mistyped number is worse than a blank one: it collides with nobody, so it
// silently creates exactly the duplicate the field exists to prevent. Hence the rules below are a
// faithful port of backend/app/services/artisan_identity.py — same three checks, same wording —
// rather than a looser client-side approximation. Mirroring the server also means a researcher on a
// dead connection learns the number is wrong while the card is still in their hand, instead of after
// a round trip that may not even complete.
// ---------------------------------------------------------------------------

private const val AADHAAR_LENGTH = 12

// Verhoeff tables: `D` is the dihedral-group multiplication table, `P` the position permutation
// applied to each digit by its distance from the right. UIDAI computes this checksum over the first
// 11 digits because it catches every single-digit error and every adjacent transposition — the two
// ways a 12-digit number gets misread off a card.
private val VERHOEFF_D = arrayOf(
    intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
    intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
    intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
    intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
    intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
    intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
    intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
    intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
    intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
    intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
)

private val VERHOEFF_P = arrayOf(
    intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
    intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
    intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
    intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
    intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
    intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
    intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
    intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8)
)

/** True when [digits] satisfies the Verhoeff checksum (the 12th digit checks the first 11). */
private fun verhoeffOk(digits: String): Boolean {
    var checksum = 0
    digits.reversed().forEachIndexed { index, char ->
        checksum = VERHOEFF_D[checksum][VERHOEFF_P[index % 8][char - '0']]
    }
    return checksum == 0
}

/**
 * The reason [value] is not a usable Aadhaar number, or null when it is fine (blank included — the
 * field is optional). Each message names the specific problem, because "invalid Aadhaar number"
 * gives a field researcher nothing to act on. Wording matches the API's, so the inline error a
 * researcher sees offline is the same sentence the server would have sent back.
 */
private fun aadhaarValidationError(value: String?): String? {
    val digits = value?.trim().orEmpty()
    if (digits.isEmpty()) return null
    // ASCII digits only, NOT Char.isDigit(): that returns true for Devanagari "१", fullwidth "２" and
    // every other decimal script, and `char - '0'` on one of those indexes far off the end of the
    // Verhoeff tables — an ArrayIndexOutOfBoundsException thrown straight out of a Compose callback.
    // The server rejects the same characters (they would otherwise be stored verbatim and defeat the
    // unique index), so rejecting them here keeps both sides answering the same sentence.
    if (!digits.all { it in '0'..'9' }) return "Aadhaar number must be 12 digits — remove any letters or symbols."
    if (digits.length != AADHAAR_LENGTH) return "Aadhaar number must be exactly 12 digits (this one has ${digits.length})."
    if (digits[0] == '0' || digits[0] == '1') return "Aadhaar numbers never start with 0 or 1 — please re-check the first digit."
    if (!verhoeffOk(digits)) {
        return "That Aadhaar number fails its checksum, so at least one digit is wrong. " +
            "Please re-read the card and enter it again."
    }
    return null
}

/**
 * "123456789012" -> "XXXX XXXX 9012", the form every surface EXCEPT the edit form uses.
 *
 * Aadhaar is regulated personal data, so a browse screen shows only the last four digits — enough to
 * confirm this is the right person, not enough to be a usable identifier. Mirrors the API's own
 * masking (which the data browser, the .xlsx report and exports already get), including its refusal
 * to partially reveal a malformed short value.
 */
private fun maskAadhaar(value: String?): String? {
    val digits = value?.trim().orEmpty()
    if (digits.isEmpty()) return null
    if (digits.length < 4) return "XXXX XXXX XXXX"
    return "XXXX XXXX ${digits.takeLast(4)}"
}

/**
 * Displays the stored bare digits as the "1234 5678 9012" grouping printed on the card, so a
 * researcher can check what they typed against it at a glance.
 *
 * The grouping is presentation only: the state behind the field stays 12 bare digits, which is what
 * gets submitted and what the unique index compares. Doing this with a transformation rather than by
 * rewriting the state is also what keeps the cursor sane — inserting spaces into the value itself
 * jumps the caret every fourth keystroke.
 */
private object AadhaarGroupingTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val grouped = digits.chunked(4).joinToString(" ")
        val mapping = object : OffsetMapping {
            // Two spaces are inserted, after digit 4 and digit 8; each shifts every later offset by one.
            override fun originalToTransformed(offset: Int): Int =
                (offset + ((offset - 1) / 4).coerceIn(0, 2)).coerceIn(0, grouped.length)

            override fun transformedToOriginal(offset: Int): Int =
                (offset - (offset / 5).coerceIn(0, 2)).coerceIn(0, digits.length)
        }
        return TransformedText(AnnotatedString(grouped), mapping)
    }
}

/**
 * The Aadhaar entry field: digits only, capped at 12, shown grouped and submitted bare.
 *
 * [error] is the (blocking) validation failure; [warning] is the non-blocking duplicate notice naming
 * an artisan already recorded with this number — a warning rather than a block, because the
 * researcher in front of the person is better placed than the app to decide what that means.
 *
 * [required] drives the asterisk AND the sentence under the box, and the sentence is the part that
 * matters. A researcher is about to ask a stranger for a government ID number; being able to say why
 * — it is the key that stops the same person becoming two records — is the difference between a
 * reasonable request and an intrusive one.
 */
@Composable
private fun ArtisanAadhaarField(
    value: String,
    error: String?,
    warning: String?,
    required: Boolean,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            // Paste is the common way a wrong character arrives, so filter rather than trust the
            // keyboard — and filter on the ASCII range, because Char.isDigit() would ADMIT the
            // Devanagari and fullwidth digits an Indic IME can produce (see aadhaarValidationError).
            onValueChange = { input -> onValueChange(input.filter { it in '0'..'9' }.take(AADHAAR_LENGTH)) },
            label = { Text(if (required) "Aadhaar number *" else "Aadhaar number") },
            placeholder = { Text("1234 5678 9012") },
            isError = error != null,
            supportingText = {
                if (error != null) {
                    Text(error)
                } else {
                    Text(
                        if (required) {
                            "Required. 12 digits from the artisan's card — it is the key that stops " +
                                "the same artisan being recorded twice by two researchers. Stored " +
                                "securely and shown as XXXX XXXX 9012 everywhere but this form."
                        } else {
                            "This artisan was recorded before an Aadhaar number was required, so the " +
                                "record still saves without one — add it only if the artisan is willing."
                        },
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
            },
            singleLine = true,
            // NumberPassword, not Number: it gives the same digit pad while telling the keyboard this is
            // a secret — IMEs neither learn from nor suggest text typed into a password field, which is
            // the right handling for a regulated identifier.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = AadhaarGroupingTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
        )
        warning?.let { msg -> Text(msg, color = Coral, fontSize = 12.sp) }
    }
}

@Composable
private fun ArtisanMultiSelectField(
    label: String,
    artisans: List<ArtisanDto>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label (${selectedIds.size} selected)", color = Muted, fontSize = 12.sp)
        if (artisans.isEmpty()) {
            Text("No artisans available yet. Create an artisan first.", color = Muted, fontSize = 12.sp)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                artisans.forEach { artisan ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(artisan.id) }
                    ) {
                        Checkbox(checked = selectedIds.contains(artisan.id), onCheckedChange = { onToggle(artisan.id) })
                        Text("${artisan.name} · ${artisan.place}", color = Body, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * Generic many-of-many over (id, label) options. Now a thin adapter over
 * [SearchableMultiSelectField], which trades the wall of checkboxes this used to paint into the
 * form for a summary trigger, the chosen rows as chips, and a searchable sheet with Select all.
 *
 * The sheet hands back a whole set; the callers here own a `Set<String>` in snapshot state and
 * change it one id at a time, so the difference is replayed as toggles. Safe to do in a loop:
 * a snapshot write is visible to the next read on the same thread, so each `onToggle` sees the
 * effect of the one before it rather than all of them racing against one stale set.
 */
@Composable
private fun CheckboxMultiSelectField(
    label: String,
    options: List<Pair<String, String>>,
    selectedIds: Set<String>,
    emptyMessage: String = "No options available.",
    onToggle: (String) -> Unit
) {
    SearchableMultiSelectField(
        label = label,
        options = remember(options) { options.asSelectOptions() },
        selected = selectedIds,
        placeholder = "Select",
        emptyMessage = emptyMessage,
        onSelectedChange = { next ->
            (next - selectedIds).forEach(onToggle)
            (selectedIds - next).forEach(onToggle)
        }
    )
}

@Composable
private fun DatePickerField(label: String, value: LocalDate?, onChange: (LocalDate) -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        OutlinedButton(
            onClick = {
                val initial = value ?: LocalDate.now()
                DatePickerDialog(
                    context,
                    { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day)) },
                    initial.year,
                    initial.monthValue - 1,
                    initial.dayOfMonth
                ).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(value?.toString() ?: "Pick date")
        }
    }
}

private enum class RecPhase { IDLE, RECORDING, PAUSED, RECORDED }

/** Transient outcome of an action button: drives a 5-second green/red flash. */
private enum class ActionStatus { IDLE, SUCCESS, ERROR }

private val SuccessGreen = Color(0xFF2E7D32)
private val FailureRed = Color(0xFFD13438)

/** Auto-reset a SUCCESS/ERROR status back to IDLE after 5 seconds. */
@Composable
private fun AutoResetStatus(status: ActionStatus, onReset: () -> Unit) {
    LaunchedEffect(status) {
        if (status != ActionStatus.IDLE) {
            kotlinx.coroutines.delay(5000)
            onReset()
        }
    }
}

/**
 * Self-contained audio recorder with the questionnaire control flow:
 * Record → (Pause / Stop) → once stopped, three stacked choices: Re-record (discard current and
 * start afresh), Record another (keep current and start a new clip), or Discard (drop the current
 * clip). Clips accumulate in the caller via [onAddClip]; [onRemoveLast] drops the most recent one.
 */
@Composable
private fun AudioClipRecorder(
    clips: List<Uri>,
    onAddClip: (Uri) -> Unit,
    onRemoveLast: () -> Unit,
    onError: (String) -> Unit,
    idleLabel: String = "Record ●"
) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf(RecPhase.IDLE) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    // Alternative to recording live: attach one or more existing audio files from the device, so the
    // user gets both facilities. Each picked file is added to this target's clips just like a recording
    // and is uploaded together with them on save.
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { onAddClip(it) }
    }

    fun startNew() {
        runCatching {
            val file = createAppFile(context, "question-audio-", ".m4a")
            recorder = createAudioRecorder(context, file).also { it.start() }
            recordingFile = file
            phase = RecPhase.RECORDING
        }.onFailure { onError(it.message ?: "Unable to start recording"); phase = RecPhase.IDLE }
    }

    fun stopAndSave() {
        runCatching {
            recorder?.stop()
            recorder?.release()
            recordingFile?.let { onAddClip(uriForFile(context, it)) }
        }.onFailure { onError(it.message ?: "Unable to stop recording") }
        recorder = null
        recordingFile = null
        phase = RecPhase.RECORDED
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { recorder?.stop(); recorder?.release() }; recorder = null }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (phase) {
            RecPhase.IDLE -> OutlinedButton(
                onClick = { startNew() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = pad
            ) { Text(if (clips.isNotEmpty()) "Record another ●" else idleLabel, maxLines = 1, softWrap = false, fontSize = 13.sp) }

            RecPhase.RECORDING -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { runCatching { recorder?.pause(); phase = RecPhase.PAUSED }.onFailure { onError(it.message ?: "Unable to pause") } },
                        modifier = Modifier.weight(1f),
                        contentPadding = pad
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pause", maxLines = 1, softWrap = false, fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = { stopAndSave() }, modifier = Modifier.weight(1f), contentPadding = pad) {
                        StopSquareLabel("Stop")
                    }
                }
            }

            RecPhase.PAUSED -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { runCatching { recorder?.resume(); phase = RecPhase.RECORDING }.onFailure { onError(it.message ?: "Unable to resume") } },
                        modifier = Modifier.weight(1f),
                        contentPadding = pad
                    ) { Text("Resume ●", maxLines = 1, softWrap = false, fontSize = 13.sp) }
                    OutlinedButton(onClick = { stopAndSave() }, modifier = Modifier.weight(1f), contentPadding = pad) {
                        StopSquareLabel("Stop")
                    }
                }
            }

            RecPhase.RECORDED -> {
                Button(onClick = { onRemoveLast(); startNew() }, modifier = Modifier.fillMaxWidth(), contentPadding = pad) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Re-record", maxLines = 1, softWrap = false, fontSize = 13.sp)
                }
                OutlinedButton(onClick = { startNew() }, modifier = Modifier.fillMaxWidth(), contentPadding = pad) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Record another", maxLines = 1, softWrap = false, fontSize = 13.sp)
                }
                OutlinedButton(onClick = { onRemoveLast(); phase = RecPhase.IDLE }, modifier = Modifier.fillMaxWidth(), contentPadding = pad) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Discard", maxLines = 1, softWrap = false, fontSize = 13.sp)
                }
            }
        }
        // Pick an existing audio file as an alternative to recording. Shown only when not mid-capture
        // (i.e. right under the "Record"/"Record another" control), so both options sit together.
        if (phase != RecPhase.RECORDING && phase != RecPhase.PAUSED) {
            OutlinedButton(
                onClick = { pickAudio.launch("audio/*") },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = pad
            ) {
                Icon(Icons.Filled.PermMedia, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add audio file", maxLines = 1, softWrap = false, fontSize = 13.sp)
            }
            // After picking a file the recorder sits in IDLE (no Discard button), so offer a way to
            // undo a mistaken pick by dropping the most recent clip.
            if (phase == RecPhase.IDLE && clips.isNotEmpty()) {
                TextButton(
                    onClick = onRemoveLast,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = pad
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remove last clip", maxLines = 1, softWrap = false, fontSize = 12.sp)
                }
            }
        }
        // One indicator, kept mounted across RECORDING<->PAUSED, so its timer survives a pause and
        // resumes from where it stopped (rather than restarting at 00:00); it also renders the
        // slow-blinking "Paused" cue. A fresh clip (RECORDED -> RECORDING) remounts it from 00:00.
        if (phase == RecPhase.RECORDING || phase == RecPhase.PAUSED) {
            RecordingIndicator(
                getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) },
                paused = phase == RecPhase.PAUSED
            )
        }
        Text("${clips.size} clip(s)", color = Muted, fontSize = 11.sp)
    }
}

/**
 * Reusable capture surface embedded inside every record form. Mirrors the web MediaCaptureField:
 * pick files, take photo/video, record audio, tag GPS, and (optionally) attach a measurement grid.
 */
/** A small red square used as the universal "stop recording" affordance. */
@Composable
private fun StopSquareLabel(text: String = "Stop") {
    Box(
        modifier = Modifier
            .size(13.dp)
            .background(Color(0xFFD13438), RoundedCornerShape(3.dp))
    )
    Spacer(Modifier.width(6.dp))
    Text(text, maxLines = 1, softWrap = false, fontSize = 13.sp)
}

@Composable
private fun MediaCaptureSection(
    repository: FieldRepository,
    media: MediaCaptureState,
    enableMeasurement: Boolean = false,
    emphasizeVideo: Boolean = false,
    // Optional content rendered between the media controls and the location editor (used by process
    // steps for the "record additional information" notes box).
    beforeLocation: (@Composable () -> Unit)? = null,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMeasurement by remember { mutableStateOf(false) }

    // Eager upload: as soon as a file is attached, start streaming it to object storage — every file,
    // any size (no client-side splitting) — so the slow transfer overlaps the time the user spends
    // filling the form and is (usually) finished by the time they tap save. Per-file byte progress
    // drives the progress bar; a failed eager upload is retried by the save path.
    LaunchedEffect(media.uris) {
        media.uris.forEach { uri ->
            if (!media.stagedDeferred.containsKey(uri)) {
                startEagerUpload(repository, context, media, uri, scope)
            }
        }
    }
    // If the user leaves without saving, delete any staged-but-unsaved objects from storage.
    DisposableEffect(Unit) {
        onDispose {
            if (media.uris.isNotEmpty()) {
                val pending = media.stagedDeferred.values.toList()
                AppScope.io.launch {
                    pending.forEach { d -> runCatching { d.await()?.let { repository.deleteStaged(it.objectKey) } } }
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) media.uris = media.uris + uris
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) {
            if (pendingMeasurement) media.measurementUri = uri else media.uris = media.uris + uri
        }
        pendingCaptureUri = null
        pendingMeasurement = false
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) media.uris = media.uris + uri
        pendingCaptureUri = null
    }
    val pickMeasurement = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) media.measurementUri = uri
    }

    LaunchedEffect(Unit) { permissionLauncher.launch(requiredAndroidPermissions()) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Attach media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "Photos, video, audio and files link to this record automatically. Audio is queued for transcription after upload.",
            color = Muted,
            fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { pickMedia.launch("*/*") }, modifier = Modifier.weight(1f)) { Text("Pick files") }
            OutlinedButton(
                onClick = {
                    permissionLauncher.launch(requiredAndroidPermissions())
                    val uri = createAppFileUri(context, "field-photo-", ".jpg")
                    pendingMeasurement = false
                    pendingCaptureUri = uri
                    takePhoto.launch(uri)
                },
                modifier = Modifier.weight(1f)
            ) { Text("Take photo") }
        }
        if (emphasizeVideo) {
            Text("🎥 Video is the preferred format here — capture the action as it happens.", color = Color(0xFFE0C9B0), fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (emphasizeVideo) {
                Button(
                    onClick = {
                        permissionLauncher.launch(requiredAndroidPermissions())
                        val uri = createAppFileUri(context, "field-video-", ".mp4")
                        pendingMeasurement = false
                        pendingCaptureUri = uri
                        takeVideo.launch(uri)
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { CardButtonLabel(Icons.Filled.Videocam, "Record video") }
            } else {
                OutlinedButton(
                    onClick = {
                        permissionLauncher.launch(requiredAndroidPermissions())
                        val uri = createAppFileUri(context, "field-video-", ".mp4")
                        pendingMeasurement = false
                        pendingCaptureUri = uri
                        takeVideo.launch(uri)
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text("Record video", maxLines = 1, softWrap = false, fontSize = 13.sp) }
            }
            OutlinedButton(
                onClick = {
                    permissionLauncher.launch(requiredAndroidPermissions())
                    if (!recording) {
                        runCatching {
                            val file = createAppFile(context, "field-audio-", ".m4a")
                            recorder = createAudioRecorder(context, file).also { it.start() }
                            recordingFile = file
                            recording = true
                            onMessage("Recording audio...")
                        }.onFailure { onError(it.message ?: "Unable to start audio recording") }
                    } else {
                        runCatching {
                            recorder?.stop()
                            recorder?.release()
                            recordingFile?.let { file -> media.uris = media.uris + uriForFile(context, file) }
                        }.onFailure { onError(it.message ?: "Unable to stop audio recording") }
                        recorder = null
                        recordingFile = null
                        recording = false
                        onMessage("Audio recording added")
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) { if (recording) StopSquareLabel("Stop audio") else Text("Record audio ●", maxLines = 1, softWrap = false, fontSize = 13.sp) }
        }
        if (recording) {
            RecordingIndicator(getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) })
        }
        beforeLocation?.invoke()
        LocationAddressEditor(
            repository = repository,
            value = media.location,
            onUseGps = {
                permissionLauncher.launch(requiredAndroidPermissions())
                readLastKnownLocation(context)
            },
            onChange = { media.location = it },
            onMessage = onMessage
        )
        if (enableMeasurement) {
            HorizontalDivider()
            Text("Grid-sheet measurement image (optional)", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(
                "If the server has GEMINI_API_KEY, dimensions are estimated from the grid and fill empty length/breadth. Otherwise enter them manually.",
                color = Muted,
                fontSize = 11.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        permissionLauncher.launch(requiredAndroidPermissions())
                        val uri = createAppFileUri(context, "measure-grid-", ".jpg")
                        pendingMeasurement = true
                        pendingCaptureUri = uri
                        takePhoto.launch(uri)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Capture grid") }
                OutlinedButton(onClick = { pickMeasurement.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Pick grid") }
            }
            media.measurementUri?.let { uri ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Measurement grid",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).background(SurfaceCard, RoundedCornerShape(8.dp))
                    )
                    Text("Grid image ready", color = Body, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { media.measurementUri = null }) { Text("Remove") }
                }
            }
        }
        if (media.uris.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = MaterialTheme.field.brandTile, shape = RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("${media.uris.size} file(s) attached", color = Canvas, fontWeight = FontWeight.SemiBold)
                // Overall upload progress across the whole batch (staged files count as 100%).
                val overall = media.uris.map { (media.stagedProgress[it] ?: 0f).coerceIn(0f, 1f) }.average().toFloat()
                val allDone = media.staged.size >= media.uris.size
                if (!allDone) {
                    LinearProgressIndicator(
                        progress = { overall },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Coral
                    )
                }
                Text(
                    if (allDone) "All uploaded ✓ — ready to save"
                    else "Uploading… ${(overall * 100).toInt()}% (${media.staged.size}/${media.uris.size} files done)",
                    color = SurfaceCard,
                    fontSize = 11.sp
                )
                var attachmentsExpanded by remember { mutableStateOf(false) }
                val attachmentsCap = 6
                val shownAttachments = if (attachmentsExpanded) media.uris else media.uris.take(attachmentsCap)
                shownAttachments.forEach { uri ->
                    AndroidUriPreview(
                        context = context,
                        uri = uri,
                        progress = media.stagedProgress[uri],
                        failed = uri in media.stagedFailed,
                        onRetry = { startEagerUpload(repository, context, media, uri, scope) },
                        onDownload = { saveLocalUriToDevice(context, uri) },
                        onRemove = {
                            // Drop just this file from the batch and clean up its staged object (if any).
                            val deferred = media.stagedDeferred[uri]
                            media.forget(uri)
                            media.uris = media.uris.filterNot { it == uri }
                            AppScope.io.launch {
                                runCatching { deferred?.await()?.let { repository.deleteStaged(it.objectKey) } }
                            }
                        }
                    )
                }
                if (media.uris.size > attachmentsCap) {
                    TextButton(onClick = { attachmentsExpanded = !attachmentsExpanded }) {
                        Text(if (attachmentsExpanded) "Show fewer" else "+${media.uris.size - attachmentsCap} more")
                    }
                }
                TextButton(onClick = {
                    val pending = media.stagedDeferred.values.toList()
                    media.stagedDeferred.clear()
                    media.staged = emptyMap()
                    media.stagedProgress = emptyMap()
                    media.stagedFailed = emptySet()
                    media.uris = emptyList()
                    AppScope.io.launch {
                        pending.forEach { d -> runCatching { d.await()?.let { repository.deleteStaged(it.objectKey) } } }
                    }
                }) { Text("Clear attachments") }
            }
        }
    }
}

/**
 * Eager-upload driver, extracted so any form (not just [MediaCaptureSection]) can stream its attached
 * files to object storage the moment they are added — overlapping the slow transfer with form-filling
 * — and shows live per-file progress. Mirrors the behaviour inside [MediaCaptureSection]: every new
 * uri is pre-uploaded once; byte progress feeds [MediaCaptureState.stagedProgress]; a failed transfer
 * is marked for retry at save. On leaving without saving, staged-but-unsaved objects are deleted.
 */
@Composable
private fun MediaStagingEffect(repository: FieldRepository, media: MediaCaptureState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(media.uris) {
        media.uris.forEach { uri ->
            if (!media.stagedDeferred.containsKey(uri)) {
                startEagerUpload(repository, context, media, uri, scope)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (media.uris.isNotEmpty()) {
                val pending = media.stagedDeferred.values.toList()
                AppScope.io.launch {
                    pending.forEach { d -> runCatching { d.await()?.let { repository.deleteStaged(it.objectKey) } } }
                }
            }
        }
    }
}

/**
 * The "N file(s) attached / uploading…/all uploaded ✓ — ready to save" progress card — the same
 * dark card [MediaCaptureSection] shows for general attachments, reused for the questionnaire's
 * recorded audio clips so they upload as you go with a visible progress bar. [label] names the items
 * (e.g. "recording"); [onRemove] drops a single file (kept in sync with the caller's own state).
 */
@Composable
private fun AttachedUploadsCard(
    context: Context,
    media: MediaCaptureState,
    label: String,
    repository: FieldRepository,
    uris: List<Uri> = media.uris,
    onRemove: (Uri) -> Unit
) {
    if (uris.isEmpty()) return
    val scope = rememberCoroutineScope()
    // Status is computed over just the passed-in subset, so a per-section card reflects only that
    // section's clips while still reading live progress/staged/failed state from the shared batch.
    val doneCount = uris.count { media.staged.containsKey(it) }
    val allDone = doneCount >= uris.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = MaterialTheme.field.brandTile, shape = RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("${uris.size} $label(s) attached", color = Canvas, fontWeight = FontWeight.SemiBold)
        val overall = uris.map { (media.stagedProgress[it] ?: 0f).coerceIn(0f, 1f) }.average().toFloat()
        if (!allDone) {
            LinearProgressIndicator(
                progress = { overall },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Coral
            )
        }
        Text(
            if (allDone) "All uploaded ✓ — ready to save"
            else "Uploading… ${(overall * 100).toInt()}% ($doneCount/${uris.size} files done)",
            color = SurfaceCard,
            fontSize = 11.sp
        )
        var expanded by remember { mutableStateOf(false) }
        val cap = 8
        val shown = if (expanded) uris else uris.take(cap)
        shown.forEach { uri ->
            AndroidUriPreview(
                context = context,
                uri = uri,
                progress = media.stagedProgress[uri],
                failed = uri in media.stagedFailed,
                onRetry = { startEagerUpload(repository, context, media, uri, scope) },
                onDownload = { saveLocalUriToDevice(context, uri) },
                onRemove = { onRemove(uri) }
            )
        }
        if (uris.size > cap) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show fewer" else "+${uris.size - cap} more")
            }
        }
    }
}

/** Lightweight loading placeholder shown while a record's detail is being fetched for editing. */
@Composable
private fun LoadingCard(mode: EntryMode) {
    RecordCard(title = "Loading ${mode.label.lowercase()}") {
        Text("Fetching the latest saved values…", color = Muted, fontSize = 13.sp)
    }
}

/** Dropdown-driven picker for choosing an existing record to edit. */
@Composable
private fun RecordPickerScreen(
    repository: FieldRepository,
    mode: EntryMode,
    onPick: (String) -> Unit,
    onError: (String) -> Unit
) {
    var loading by remember(mode) { mutableStateOf(true) }
    var options by remember(mode) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selected by remember(mode) { mutableStateOf("") }

    LaunchedEffect(mode) {
        loading = true
        runCatching {
            when (mode) {
                EntryMode.ARTISAN -> repository.artisans().map { it.id to "${it.name} · ${it.place}" }
                EntryMode.CRAFT -> repository.crafts().map { it.id to (it.name + (it.place?.let { p -> " · $p" } ?: "")) }
                EntryMode.PRODUCT -> repository.products().map { it.id to "${it.productName} · ${it.artisanName}" }
                EntryMode.PROCESS -> repository.processes().map { it.id to (it.name + (it.product?.productName?.let { p -> " · $p" } ?: "")) }
                EntryMode.TOOL -> repository.tools().map { it.id to "${it.toolkitName} · ${it.artisanName}" }
                EntryMode.WORKSHOP -> repository.workshops().map { it.id to it.title.ifBlank { "Untitled workshop" } }
                EntryMode.MEDIA -> repository.media().map { m ->
                    m.id to (m.caption?.takeIf { it.isNotBlank() } ?: m.originalFilename)
                }
                EntryMode.QUESTIONNAIRE -> {
                    // Idempotent: all saved interview records for the same set of artisan(s) collapse
                    // into one entry (open the most recent); the label notes how many sessions exist.
                    repository.interviews().groupBy { interviewGroupKey(it) }.values.map { group ->
                        val rep = representativeInterview(group)
                        val artisanNames = rep.artisans.mapNotNull { it.artisan?.name }.distinct().joinToString(", ")
                        val parts = listOfNotNull(
                            artisanNames.ifBlank { null },
                            rep.title.takeIf { it.isNotBlank() },
                            if (group.size > 1) "${group.size} sessions" else null
                        )
                        rep.id to parts.joinToString(" · ").ifBlank { "Untitled interview" }
                    }.sortedBy { it.second.lowercase() }
                }
                else -> emptyList()
            }
        }.onSuccess { options = it }.onFailure { onError(it.message ?: "Unable to load records") }
        loading = false
    }

    RecordCard(title = "Update existing ${mode.label.lowercase()}") {
        Text("Pick a record from the dropdown to open and edit it. Edits are attributed to you per field.", color = Muted, fontSize = 12.sp)
        when {
            loading -> Text("Loading ${mode.label.lowercase()} records…", color = Muted)
            options.isEmpty() -> Text("No ${mode.label.lowercase()} records found yet.", color = Muted)
            else -> {
                DropdownField(
                    label = "Select ${mode.label.lowercase()}",
                    options = options,
                    selectedValue = selected,
                    placeholder = "Select a record",
                    includeNone = false,
                    onSelect = { selected = it }
                )
                Button(
                    onClick = { if (selected.isNotBlank()) onPick(selected) },
                    enabled = selected.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open for editing") }
                Text("Or tap a recent record", color = Muted, fontSize = 12.sp)
                options.take(12).forEach { (id, label) ->
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(id) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, color = Body, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("Edit ›", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/** Maps a record type to its admin delete call. */
private suspend fun deleteByMode(repository: FieldRepository, mode: EntryMode, id: String) {
    when (mode) {
        EntryMode.ARTISAN -> repository.deleteArtisan(id)
        EntryMode.PRODUCT -> repository.deleteProduct(id)
        EntryMode.PROCESS -> repository.deleteProcess(id)
        EntryMode.TOOL -> repository.deleteTool(id)
        EntryMode.WORKSHOP -> repository.deleteWorkshop(id)
        EntryMode.CRAFT -> repository.deleteCraft(id)
        EntryMode.QUESTIONNAIRE -> repository.deleteInterview(id)
        else -> throw IllegalArgumentException("This record type cannot be deleted")
    }
}

/** Admin-only destructive action with a confirmation dialog, shown below an edit form. */
@Composable
private fun DeleteRecordSection(
    repository: FieldRepository,
    mode: EntryMode,
    recordId: String,
    onDeleted: () -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val noun = mode.label.lowercase()
    RecordCard(title = "Danger zone") {
        Text("Deleting permanently removes this $noun and its links. This cannot be undone.", color = Muted, fontSize = 12.sp)
        OutlinedButton(
            onClick = { confirm = true },
            enabled = !deleting,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (deleting) "Deleting…" else "Delete this $noun")
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirm = false },
            title = { Text("Delete $noun?") },
            text = { Text("This permanently deletes the record. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        scope.launch {
                            deleting = true
                            runCatching { deleteByMode(repository, mode, recordId) }
                                .onSuccess { confirm = false; deleting = false; onDeleted() }
                                .onFailure { deleting = false; onError(it.message ?: "Unable to delete (admin only)") }
                        }
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

/** Fetches the chosen record's full detail, then renders the matching form in edit mode. */
@Composable
private fun EditScreen(
    repository: FieldRepository,
    mode: EntryMode,
    recordId: String,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    adminView: Boolean,
    canDelete: Boolean,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    when (mode) {
        EntryMode.ARTISAN -> {
            var detail by remember(recordId) { mutableStateOf<ArtisanDetailDto?>(null) }
            var answers by remember(recordId) { mutableStateOf<List<ArtisanAnswerDto>>(emptyList()) }
            var answersLoading by remember(recordId) { mutableStateOf(true) }
            LaunchedEffect(recordId) {
                runCatching { repository.artisan(recordId) }
                    .onSuccess { detail = it }
                    .onFailure { onError(it.message ?: "Unable to load artisan") }
                runCatching { repository.artisanQuestionnaire(recordId) }
                    .onSuccess { answers = it.answered }
                answersLoading = false
            }
            val d = detail
            if (d == null) {
                LoadingCard(mode)
            } else {
                ArtisanForm(
                    repository = repository,
                    crafts = crafts,
                    editing = d,
                    adminView = adminView,
                    onDone = onDone,
                    onError = onError
                )
                ArtisanQuestionnairePanel(answers = answers, loading = answersLoading)
            }
        }
        EntryMode.PRODUCT -> {
            var detail by remember(recordId) { mutableStateOf<ProductDetailDto?>(null) }
            LaunchedEffect(recordId) {
                runCatching { repository.product(recordId) }
                    .onSuccess { detail = it }
                    .onFailure { onError(it.message ?: "Unable to load product") }
            }
            val d = detail
            if (d == null) LoadingCard(mode) else ProductForm(
                repository = repository,
                crafts = crafts,
                artisans = artisans,
                editing = d,
                adminView = adminView,
                onDone = onDone,
                onError = onError
            )
        }
        EntryMode.PROCESS -> {
            var detail by remember(recordId) { mutableStateOf<ProcessDetailDto?>(null) }
            LaunchedEffect(recordId) {
                runCatching { repository.process(recordId) }
                    .onSuccess { detail = it }
                    .onFailure { onError(it.message ?: "Unable to load process") }
            }
            val d = detail
            if (d == null) LoadingCard(mode) else ProcessForm(
                repository = repository,
                editing = d,
                adminView = adminView,
                onDone = onDone,
                onError = onError
            )
        }
        EntryMode.TOOL -> {
            var detail by remember(recordId) { mutableStateOf<ToolDetailDto?>(null) }
            LaunchedEffect(recordId) {
                runCatching { repository.tool(recordId) }
                    .onSuccess { detail = it }
                    .onFailure { onError(it.message ?: "Unable to load tool") }
            }
            val d = detail
            if (d == null) LoadingCard(mode) else ToolForm(
                repository = repository,
                crafts = crafts,
                artisans = artisans,
                editing = d,
                adminView = adminView,
                onDone = onDone,
                onError = onError
            )
        }
        EntryMode.WORKSHOP -> {
            var detail by remember(recordId) { mutableStateOf<WorkshopDetailDto?>(null) }
            LaunchedEffect(recordId) {
                runCatching { repository.workshop(recordId) }
                    .onSuccess { detail = it }
                    .onFailure { onError(it.message ?: "Unable to load workshop") }
            }
            val d = detail
            if (d == null) LoadingCard(mode) else WorkshopForm(
                repository = repository,
                artisans = artisans,
                editing = d,
                adminView = adminView,
                onDone = onDone,
                onError = onError
            )
        }
        EntryMode.CRAFT -> {
            var detail by remember(recordId) { mutableStateOf<CraftDto?>(null) }
            LaunchedEffect(recordId) {
                runCatching { repository.craft(recordId) }
                    .onSuccess { detail = it }
                    .onFailure { onError(it.message ?: "Unable to load craft") }
            }
            val d = detail
            if (d == null) LoadingCard(mode) else CraftForm(
                repository = repository,
                editing = d,
                adminView = adminView,
                onDone = onDone,
                onError = onError
            )
        }
        // A media file has no edit form — the web's search results open the object itself. Search is
        // the only route that lands here, so show the file with its transcript rather than the
        // "cannot be edited" dead end it used to hit.
        EntryMode.MEDIA -> ViewDataDetail(
            repository = repository,
            mode = EntryMode.MEDIA,
            recordId = recordId,
            onError = onError
        )
        else -> Text("This record type cannot be edited here.", color = Muted)
    }
    if (canDelete && mode != EntryMode.MEDIA && mode != EntryMode.USERS && mode != EntryMode.VIEW_DATA) {
        DeleteRecordSection(repository, mode, recordId, onDeleted = onDone, onError = onError)
    }
}

/** Loads an existing interview, then renders the questionnaire form seeded for partial editing. */
@Composable
private fun InterviewEditLoader(
    repository: FieldRepository,
    recordId: String,
    sections: List<QuestionnaireSectionDto>,
    artisans: List<ArtisanDto>,
    canManageQuestionnaire: Boolean,
    adminView: Boolean,
    canDelete: Boolean,
    onRefreshSections: suspend () -> Unit,
    onError: (String) -> Unit,
    onDone: () -> Unit
) {
    var detail by remember(recordId) { mutableStateOf<QuestionnaireInterviewDetailDto?>(null) }
    LaunchedEffect(recordId) {
        runCatching { repository.interview(recordId) }
            .onSuccess { detail = it }
            .onFailure { onError(it.message ?: "Unable to load interview") }
    }
    val d = detail
    if (d == null) {
        LoadingCard(EntryMode.QUESTIONNAIRE)
    } else {
        QuestionnaireForm(
            repository = repository,
            sections = sections,
            artisans = artisans,
            canManageQuestionnaire = canManageQuestionnaire,
            editing = d,
            adminView = adminView,
            onRefreshSections = onRefreshSections,
            onSubmit = { repository.createQuestionnaireInterview(it).id },
            onError = onError,
            onSaved = onDone
        )
        if (canDelete) {
            DeleteRecordSection(repository, EntryMode.QUESTIONNAIRE, recordId, onDeleted = onDone, onError = onError)
        }
    }
}

@Composable
private fun CraftForm(
    repository: FieldRepository,
    editing: CraftDto? = null,
    adminView: Boolean = false,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    val isEdit = editing != null
    var name by remember(editing) { mutableStateOf(editing?.name ?: "") }
    var localName by remember(editing) { mutableStateOf(editing?.localName ?: "") }
    var category by remember(editing) { mutableStateOf(editing?.category ?: "") }
    var place by remember(editing) { mutableStateOf(editing?.place ?: "") }
    var description by remember(editing) { mutableStateOf(editing?.description ?: "") }
    val workshop = rememberWorkshopPicker(repository, isEdit, editing?.workshopId, editing)
    var saving by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }

    fun submit() {
        if (!validateRequired(listOf(
                RequiredCheck(name.isBlank(), { nameError = it }, nameFocus)
            ))) { onError("Please fill the required field highlighted above."); return }
        scope.launch {
            // Late-submission gate: if the chosen workshop has ended, say what that means and save
            // only if the researcher confirms. Returns true immediately when there is nothing to say.
            if (!workshop.confirmSubmission()) return@launch
            saving = true
            val body = CraftCreateRequest(
                name = name.trim(),
                localName = localName.blankToNull(),
                category = category.blankToNull(),
                place = place.blankToNull(),
                description = description.blankToNull(),
                workshopId = workshop.value(),
                recordedAt = if (isEdit) null else Instant.now().toString()
            )
            val queuedOffline = runCatching {
                trySaveOffline(repository, context, isEdit, "craft", offlineFormJson.encodeToString(body),
                    name.trim(), media, name.trim(), "Field media for ${name.trim()}")
            }.getOrElse { false }
            if (queuedOffline) {
                media.reset()
                onError("Saved on this device. It'll upload automatically when you're back online.")
                onDone()
                saving = false
                return@launch
            }
            runCatching {
                val craftId = if (isEdit) {
                    repository.updateCraft(editing!!.id, body).id
                } else {
                    repository.createCraft(body).id
                }
                uploadAttachments(repository, context, media, "craft", craftId, name, "Field media for ${name.trim()}")
            }.onSuccess {
                media.reset()
                onDone()
            }.onFailure { onError(it.message ?: "Unable to save craft") }
            saving = false
        }
    }
    val initialSig = remember(editing) { listOf(name, localName, category, place, description).joinToString("") }
    val dirty = !saving && (
        listOf(name, localName, category, place, description).joinToString("") != initialSig ||
            workshop.isDirty() || media.uris.isNotEmpty() || media.measurementUri != null
    )

    RecordCard(title = if (isEdit) "Edit craft" else "Add craft") {
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        WorkshopField(state = workshop, saving = saving)
        RequiredInput("Craft name", name, nameError, nameFocus, titleCased = true) { name = it }
        TextInput("Local name", localName) { localName = it }
        TextInput("Category", category) { category = it }
        TextInput("Place", place, titleCased = true) { place = it }
        TextInput("Description", description, minLines = 3) { description = it }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "craft", recordId = editing!!.id, onError = onError)
        }
        MediaCaptureSection(repository = repository, media = media, onMessage = onError, onError = onError)
        SaveButton(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update craft" else "Save craft"
        ) { submit() }
    }
}

@Composable
private fun ArtisanForm(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    /** Whether [crafts] arrived, could not be reached, or is still coming — see [rememberFormCarry]. */
    lookupState: CarryScopeState = CarryScopeState.PENDING,
    editing: ArtisanDetailDto? = null,
    prefill: Prefill? = null,
    adminView: Boolean = false,
    onArtisanCreated: (Prefill) -> Unit = {},
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    val isEdit = editing != null
    var name by remember(editing) { mutableStateOf(editing?.name ?: prefill?.artisanName ?: "") }
    var localName by remember(editing) { mutableStateOf(editing?.localName ?: "") }
    var gender by remember(editing) { mutableStateOf(editing?.gender?.takeIf { it.isNotBlank() } ?: "Male") }
    var phone by remember(editing) { mutableStateOf(editing?.phone ?: "") }
    var email by remember(editing) { mutableStateOf(editing?.email ?: "") }
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }
    var address by remember(editing) { mutableStateOf(editing?.address ?: "") }
    var notes by remember(editing) { mutableStateOf(editing?.notes ?: "") }
    // Identity. `aadhaar` holds BARE digits — the 4-4-4 grouping is a visual transformation on the
    // field, never part of the value. The Pehchan card defaults to "available", matching the API's
    // default and the common case, which is why its number is effectively required on create.
    var aadhaar by remember(editing) { mutableStateOf(editing?.aadhaarNumber.orEmpty()) }
    var pehchanAvailable by remember(editing) { mutableStateOf(editing?.pehchanCardAvailable ?: true) }
    var pehchanNumber by remember(editing) { mutableStateOf(editing?.pehchanCardNumber.orEmpty()) }
    var dosItems by remember(editing) { mutableStateOf(splitNumbered(editing?.dos)) }
    var dontsItems by remember(editing) { mutableStateOf(splitNumbered(editing?.donts)) }
    var craftId by remember(editing) { mutableStateOf(editing?.craftId ?: prefill?.craftId ?: "") }
    var newCraftName by remember(editing) { mutableStateOf("") }
    val workshop = rememberWorkshopPicker(repository, isEdit, editing?.workshopId, editing)
    /**
     * The craft and the workshop carry into a new artisan; the ARTISAN in the bag never does.
     *
     * This form's whole job is to create a person who is not yet in the bag, so offering the last
     * one would be worse than useless — it is the "wrong artisan" hazard with the record itself as
     * the casualty. Their place does not carry either: it belongs to that artisan, not to the
     * sitting, and two artisans documented back to back are routinely from different villages. What
     * genuinely transfers is the craft everyone at this workshop practises, and the workshop.
     */
    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.ARTISAN_FORM,
        scopes = listOf(carryScope(CarryNode.CRAFT, lookupState, crafts) { it.id }),
        handoff = prefill
    ) { carried ->
        carried.craftId?.let { craftId = it }
        // Only while the picker still holds whatever it defaulted to itself: a workshop the
        // researcher chose outranks one we remembered, and moving the baseline along with the value
        // is what stops a prefill reading as an unsaved edit on the way out.
        carried.workshopId?.let { if (!workshop.isDirty()) workshop.applyDefault(it) }
    }
    /** "Change": drop the carried craft so the researcher picks from scratch. */
    fun clearCarriedContext() {
        carry.change()
        craftId = ""
    }
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }
    var saving by remember { mutableStateOf(false) }
    val hasCraft = craftId.isNotBlank() || newCraftName.isNotBlank()
    var nameError by remember { mutableStateOf<String?>(null) }
    var placeError by remember { mutableStateOf<String?>(null) }
    var craftError by remember { mutableStateOf<String?>(null) }
    var dosError by remember { mutableStateOf<String?>(null) }
    var dontsError by remember { mutableStateOf<String?>(null) }
    var phoneError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var aadhaarError by remember { mutableStateOf<String?>(null) }
    var pehchanError by remember { mutableStateOf<String?>(null) }
    // Non-blocking: an artisan already recorded with the Aadhaar number being typed.
    var aadhaarDuplicate by remember(editing) { mutableStateOf<String?>(null) }
    /*
     * Aadhaar is what stops one artisan becoming two records, so a NEW artisan must come with one —
     * `POST /artisans` answers 422 without it, and the form would simply be broken if it let the
     * researcher reach that.
     *
     * On EDIT it is required only when the record already carries one. Thousands of artisans were
     * documented before the column existed, and a researcher correcting a phone number on one of
     * those must not be blocked behind a government ID they may have no way to obtain — the choice
     * is between a corrected phone number and no correction at all, never between a phone number and
     * an Aadhaar. Once a record HAS a number, clearing it is still refused: that is data loss rather
     * than a legacy gap. Mirrors `aadhaarRequired` in components/forms/ArtisanForm.tsx.
     */
    val aadhaarRequired = !isEdit || !editing?.aadhaarNumber.isNullOrBlank()
    val nameFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }
    val craftFocus = remember { FocusRequester() }
    val dosFocus = remember { FocusRequester() }
    val dontsFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val aadhaarFocus = remember { FocusRequester() }
    val pehchanFocus = remember { FocusRequester() }

    LaunchedEffect(editing) {
        val existing = editing?.location
        if (existing != null && media.location == null) media.location = existing.toRequest()
    }

    // Pre-flight duplicate check. The moment a complete, well-formed number is on screen, ask the
    // server whether it already belongs to someone — so the researcher hears "you already have this
    // person" while the form is still half empty, rather than as a 409 after filling all of it. The
    // short delay keeps this to one request per number instead of one per keystroke, and a failed
    // lookup is swallowed: this is a courtesy, and the unique index remains the real guarantee.
    LaunchedEffect(aadhaar, editing?.id) {
        aadhaarDuplicate = null
        if (aadhaar.isBlank() || aadhaarValidationError(aadhaar) != null) return@LaunchedEffect
        delay(400)
        val match = runCatching { repository.lookupArtisanByAadhaar(aadhaar) }.getOrNull()?.artisan
        // Editing the very artisan who holds the number is not a duplicate.
        if (match == null || match.id == editing?.id) return@LaunchedEffect
        val where = listOfNotNull(
            match.place?.takeIf { it.isNotBlank() },
            match.craft?.takeIf { it.isNotBlank() }
        ).joinToString(", ")
        aadhaarDuplicate = buildString {
            append(match.name.ifBlank { "Another artisan" })
            if (where.isNotEmpty()) append(" ($where)")
            append(" is already recorded with this Aadhaar number. Open that artisan instead of ")
            append("creating a duplicate.")
        }
    }

    fun submit() {
        val dosText = joinNumbered(dosItems)
        val dontsText = joinNumbered(dontsItems)
        if (!validateRequired(listOf(
                RequiredCheck(name.isBlank(), { nameError = it }, nameFocus),
                RequiredCheck(place.isBlank(), { placeError = it }, placeFocus),
                RequiredCheck(!hasCraft, { craftError = it }, craftFocus),
                RequiredCheck(dosText.isBlank(), { dosError = it }, dosFocus),
                RequiredCheck(dontsText.isBlank(), { dontsError = it }, dontsFocus)
            ))) { onError("Please fill the required field highlighted above."); return }
        // Phone (optional) must be a valid number for its ISD code when present.
        artisanPhoneValidationError(phone)?.let { msg -> phoneError = msg; onError("Fix the phone number highlighted above."); return }
        phoneError = null
        // Email (optional) must look like an address when present — the same shape the web form
        // enforces (EMAIL_RE in components/forms/ArtisanForm.tsx). Accepting "a@b" here while the web
        // refused it meant one artisan got two different verdicts depending on who typed them in.
        if (email.isNotBlank() && !EMAIL_RE.matches(email.trim())) {
            emailError = "Enter a valid email address (name@example.com)."
            runCatching { emailFocus.requestFocus() }
            onError("Fix the email highlighted above."); return
        }
        emailError = null
        // Aadhaar. Presence first, then shape. Both are checked HERE rather than left to the API:
        // this form saves offline, so a form that only learned the number was missing from a 422
        // would let a researcher walk away from the artisan with an unsavable record in hand.
        if (aadhaarRequired && aadhaar.isBlank()) {
            aadhaarError = "Enter the artisan's 12-digit Aadhaar number. It is how the repository " +
                "recognises someone another researcher has already documented."
            runCatching { aadhaarFocus.requestFocus() }
            onError("The Aadhaar number is required — see the highlighted field."); return
        }
        // ...and it must be a genuine number when present — the same three checks the API runs,
        // applied here so a bad digit is caught with the card still in hand.
        aadhaarValidationError(aadhaar)?.let { msg ->
            aadhaarError = msg
            runCatching { aadhaarFocus.requestFocus() }
            onError("Fix the Aadhaar number highlighted above."); return
        }
        aadhaarError = null
        // "Card available = Yes" without a number is the one combination the API refuses outright.
        if (pehchanAvailable && pehchanNumber.isBlank()) {
            pehchanError = "Enter the Artisan Pehchan Card number, or set the card to 'No' if the " +
                "artisan does not hold one."
            runCatching { pehchanFocus.requestFocus() }
            onError("Fix the Artisan Pehchan Card details highlighted above."); return
        }
        pehchanError = null
        // Last, because it is the one check whose answer is further down the form than the focus
        // helpers reach. See newRecordLocationError for why an edit is never asked.
        newRecordLocationError(isEdit, media.location)?.let { onError(it); return }
        scope.launch {
            if (!workshop.confirmSubmission()) return@launch
            saving = true
            val body = ArtisanCreateRequest(
                name = name.trim(),
                localName = localName.blankToNull(),
                gender = gender.blankToNull(),
                phone = phone.blankToNull(),
                email = email.blankToNull(),
                place = place.trim(),
                address = address.blankToNull(),
                notes = notes.blankToNull(),
                // Sent even when empty. `explicitNulls = false` drops a null from the payload, and an
                // omitted key means "leave it alone" to a PATCH — which would make a wrongly entered
                // Aadhaar impossible to retract from the app. The API normalises "" to null, and the
                // column is one it deliberately allows a client to clear.
                aadhaarNumber = aadhaar.trim(),
                // Always sent explicitly, both ways: the API clears a stale card number when this is
                // false, and only an explicit true can move a record back off "No".
                pehchanCardAvailable = pehchanAvailable,
                pehchanCardNumber = if (pehchanAvailable) pehchanNumber.blankToNull() else null,
                dos = dosText,
                donts = dontsText,
                craftId = craftId.ifBlank { null },
                craftName = if (craftId.isBlank()) newCraftName.blankToNull() else null,
                workshopId = workshop.value(),
                status = status,
                recordedAt = if (isEdit) null else Instant.now().toString(),
                location = locationForBody(isEdit, media.location, editing?.location)
            )
            // No connection: save to the device and sync on reconnect, instead of failing the upload.
            val queuedOffline = runCatching {
                trySaveOffline(repository, context, isEdit, "artisan", offlineFormJson.encodeToString(body),
                    name.trim(), media, name.trim(), "Field media for ${name.trim()}")
            }.getOrElse { false }
            if (queuedOffline) {
                media.reset()
                onError("Saved on this device. It'll upload automatically when you're back online.")
                onDone()
                saving = false
                return@launch
            }
            runCatching {
                val artisanId = if (isEdit) {
                    repository.updateArtisan(editing!!.id, body).id
                } else {
                    repository.createArtisan(body).id
                }
                uploadAttachments(repository, context, media, "artisan", artisanId, name, "Field media for ${name.trim()}")
                artisanId
            }.onSuccess { artisanId ->
                if (isEdit) {
                    media.reset()
                    onDone()
                } else {
                    val resolvedCraftName = crafts.firstOrNull { it.id == craftId }?.name ?: newCraftName.blankToNull()
                    val prefillOut = Prefill(
                        artisanId = artisanId,
                        artisanName = name.trim(),
                        place = place.trim(),
                        craftId = craftId.ifBlank { null },
                        craftName = resolvedCraftName
                    )
                    media.reset()
                    onArtisanCreated(prefillOut)
                }
            }.onFailure {
                // A duplicate Aadhaar/Pehchan number comes back as a structured 409 whose message
                // names the artisan already holding it — far more use than "HTTP 409 Conflict", which
                // is all the exception itself says.
                onError(it.apiErrorMessage("Unable to save artisan"))
            }
            saving = false
        }
    }
    // Every value the form can change, in one string — the unsaved-work guard compares it against the
    // value it had on open, so a new field must be listed here or editing it looks like no edit at all.
    fun formSignature(): String = listOf(
        name, localName, gender, phone, email, place, address, notes, aadhaar,
        pehchanAvailable.toString(), pehchanNumber, joinNumbered(dosItems), joinNumbered(dontsItems),
        // The offer resolves a beat after the first composition, so until it does the handoff it was
        // built from stands in — otherwise the baseline and the prefill would disagree for one frame
        // and an untouched form would come out of it reading as edited.
        craftId.exceptCarried(carry.offer?.context?.craftId ?: prefill?.craftId), newCraftName, status
    ).joinToString(" ")
    val initialSig = remember(editing) { formSignature() }
    val dirty = !saving && (
        formSignature() != initialSig ||
            workshop.isDirty() || media.uris.isNotEmpty() || media.measurementUri != null
    )

    RecordCard(title = if (isEdit) "Edit artisan" else "Add artisan") {
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        // Above the workshop picker, so what was filled in is read before any of the fields it filled.
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        WorkshopField(state = workshop, saving = saving)
        RequiredInput("Name", name, nameError, nameFocus, titleCased = true) { name = it }
        TextInput("Local name", localName) { localName = it }
        DropdownField(
            label = "Craft *",
            options = crafts.map { it.id to it.name },
            selectedValue = craftId,
            placeholder = "Select existing craft",
            onSelect = { picked ->
                craftId = picked
                // An explicit pick replaces the remembered craft and retires the banner: from here on
                // what is on screen is the researcher's own choice, not a suggestion.
                crafts.firstOrNull { it.id == picked }?.let {
                    carry.remember(CarryContext(craftId = it.id, craftName = it.name), explicit = true)
                }
            }
        )
        OutlinedTextField(
            value = newCraftName,
            onValueChange = { newCraftName = it },
            label = { Text("Or new craft name") },
            // Web parity (components/forms/ArtisanForm.tsx): the box says WHEN it is the one to fill
            // in, which is the whole point of a field that is only sometimes the right one.
            placeholder = { Text("Used when no existing craft is selected") },
            isError = craftError != null,
            supportingText = craftError?.let { msg -> { Text(msg) } },
            modifier = Modifier.fillMaxWidth().focusRequester(craftFocus)
        )
        RequiredInput("Place", place, placeError, placeFocus, titleCased = true) { place = it }
        DropdownField(
            label = "Gender",
            options = genderOptions.map { it to it },
            selectedValue = gender,
            includeNone = false
        ) { gender = it }
        ArtisanPhoneField(value = phone, error = phoneError) { phone = it; phoneError = null }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; emailError = null },
            label = { Text("Email") },
            isError = emailError != null,
            supportingText = emailError?.let { msg -> { Text(msg) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(emailFocus)
        )
        TextInput("Address", address, minLines = 2) { address = it }
        MultiNoteInput(value = notes) { notes = it }
        // Identity — the same grouped block, in the same position (after notes, before Do's/Don'ts),
        // as the web form's `role="group"` panel. The heading is what makes the dependency between
        // "holds a card" and "card number" legible instead of reading as three unrelated boxes.
        Text("Identity", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "Government identifiers, kept so the same artisan documented at two workshops resolves " +
                "to one record. Stored securely and masked on every shared or exported view.",
            color = Muted,
            fontSize = 12.sp
        )
        ArtisanAadhaarField(
            value = aadhaar,
            error = aadhaarError,
            warning = aadhaarDuplicate,
            required = aadhaarRequired,
            focusRequester = aadhaarFocus
        ) { aadhaar = it; aadhaarError = null }
        DropdownField(
            label = "Artisan Pehchan Card available",
            options = listOf("yes" to "Yes", "no" to "No"),
            selectedValue = if (pehchanAvailable) "yes" else "no",
            includeNone = false
        ) { choice ->
            pehchanAvailable = choice == "yes"
            // Answering No retires the number with the answer, so a disabled box can never leave a
            // card number stranded on a record that says the artisan holds no card.
            if (!pehchanAvailable) { pehchanNumber = ""; pehchanError = null }
        }
        OutlinedTextField(
            value = pehchanNumber,
            // The API stores card numbers upper-cased; showing that as it is typed keeps the box
            // honest about what will actually be saved (web parity).
            onValueChange = { pehchanNumber = it.uppercase(); pehchanError = null },
            label = { Text(if (pehchanAvailable) "Artisan Pehchan Card number *" else "Artisan Pehchan Card number") },
            placeholder = { Text(if (pehchanAvailable) "As printed on the card" else "No card on record") },
            enabled = pehchanAvailable,
            isError = pehchanError != null,
            supportingText = {
                val hint = pehchanError
                    ?: if (pehchanAvailable) {
                        "The PM Vishwakarma artisan ID printed on the card."
                    } else {
                        "Disabled because this artisan holds no Pehchan card. Switch \"available\" to Yes to enter a number."
                    }
                Text(hint, color = if (pehchanError != null) MaterialTheme.colorScheme.error else Muted, fontSize = 12.sp)
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(pehchanFocus)
        )
        NumberedListInput(
            label = "Do's (positive prompt)",
            items = dosItems,
            error = dosError,
            focusRequester = dosFocus,
            helper = "Lessons from years at the craft — the things the artisan has learnt to do. Press Enter for each new point."
        ) { dosItems = it; dosError = null }
        NumberedListInput(
            label = "Don'ts (negative prompt)",
            items = dontsItems,
            error = dontsError,
            focusRequester = dontsFocus,
            helper = "Lessons from years at the craft — the things the artisan has learnt not to do / to avoid. Press Enter for each new point."
        ) { dontsItems = it; dontsError = null }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "artisan", recordId = editing!!.id, onError = onError)
        }
        MediaCaptureSection(repository = repository, media = media, onMessage = onError, onError = onError)
        SaveButton(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update artisan" else "Save artisan"
        ) { submit() }
    }
}

@Composable
private fun WorkshopForm(
    repository: FieldRepository,
    artisans: List<ArtisanDto>,
    editing: WorkshopDetailDto? = null,
    prefill: Prefill? = null,
    adminView: Boolean = false,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    val isEdit = editing != null
    var title by remember(editing) { mutableStateOf(editing?.title ?: "") }
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }
    var description by remember(editing) { mutableStateOf(editing?.description ?: "") }
    var notes by remember(editing) { mutableStateOf(editing?.notes ?: "") }
    var startDate by remember(editing) { mutableStateOf(parseIsoToLocalDate(editing?.startDate)) }
    var endDate by remember(editing) { mutableStateOf(parseIsoToLocalDate(editing?.endDate)) }
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }
    var selectedArtisans by remember(editing) {
        mutableStateOf(
            editing?.artisans?.map { it.artisanId }?.toSet()
                ?: prefill?.artisanId?.let { setOf(it) }
                ?: emptySet()
        )
    }
    var crafts by remember { mutableStateOf<List<CraftDto>>(emptyList()) }
    var selectedCrafts by remember(editing) {
        mutableStateOf(editing?.crafts?.map { it.craftId }?.toSet() ?: emptySet())
    }
    var saving by remember { mutableStateOf(false) }
    var titleError by remember { mutableStateOf<String?>(null) }
    var placeError by remember { mutableStateOf<String?>(null) }
    val titleFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }

    LaunchedEffect(editing) {
        val existing = editing?.location
        if (existing != null && media.location == null) media.location = existing.toRequest()
    }
    LaunchedEffect(Unit) {
        runCatching { repository.crafts() }.onSuccess { crafts = it }
    }

    fun submit() {
        if (!validateRequired(listOf(
                RequiredCheck(title.isBlank(), { titleError = it }, titleFocus),
                RequiredCheck(place.isBlank(), { placeError = it }, placeFocus)
            ))) { onError("Please fill the required field highlighted above."); return }
        newRecordLocationError(isEdit, media.location)?.let { onError(it); return }
        scope.launch {
            saving = true
            val start = (startDate ?: LocalDate.now()).toIsoInstant()
            val end = (endDate ?: startDate ?: LocalDate.now()).toIsoInstant()
            val originalArtisans = editing?.artisans?.map { it.artisanId }?.toSet() ?: emptySet()
            val originalCrafts = editing?.crafts?.map { it.craftId }?.toSet() ?: emptySet()
            // On edit, only send the relation when it changed (the backend replaces & re-checks it).
            val artisanIdsParam = if (!isEdit || selectedArtisans != originalArtisans) selectedArtisans.toList() else null
            val craftIdsParam = if (!isEdit || selectedCrafts != originalCrafts) selectedCrafts.toList() else null
            val body = WorkshopCreateRequest(
                title = title.trim(),
                date = start,
                startDate = start,
                endDate = end,
                place = place.trim(),
                description = description.blankToNull(),
                notes = notes.blankToNull(),
                artisanIds = artisanIdsParam,
                craftIds = craftIdsParam,
                status = status,
                recordedAt = if (isEdit) null else Instant.now().toString(),
                location = locationForBody(isEdit, media.location, editing?.location)
            )
            val queuedOffline = runCatching {
                trySaveOffline(repository, context, isEdit, "workshop", offlineFormJson.encodeToString(body),
                    title.trim(), media, title.trim(), "Field media for ${title.trim()}")
            }.getOrElse { false }
            if (queuedOffline) {
                media.reset()
                onError("Saved on this device. It'll upload automatically when you're back online.")
                onDone()
                saving = false
                return@launch
            }
            runCatching {
                val workshopId = if (isEdit) {
                    repository.updateWorkshop(editing!!.id, body).id
                } else {
                    repository.createWorkshop(body).id
                }
                uploadAttachments(repository, context, media, "workshop", workshopId, title, "Field media for ${title.trim()}")
            }.onSuccess {
                media.reset()
                onDone()
            }.onFailure { onError(it.message ?: "Unable to save workshop") }
            saving = false
        }
    }
    val initialSig = remember(editing) {
        listOf(title, place, description, notes, status, startDate?.toString() ?: "", endDate?.toString() ?: "",
            selectedArtisans.sorted().joinToString(","), selectedCrafts.sorted().joinToString(",")).joinToString("")
    }
    val dirty = !saving && (
        listOf(title, place, description, notes, status, startDate?.toString() ?: "", endDate?.toString() ?: "",
            selectedArtisans.sorted().joinToString(","), selectedCrafts.sorted().joinToString(",")).joinToString("") != initialSig ||
            media.uris.isNotEmpty() || media.measurementUri != null
    )

    RecordCard(title = if (isEdit) "Edit workshop" else "Add workshop") {
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        RequiredInput("Workshop title", title, titleError, titleFocus, titleCased = true) { title = it }
        RequiredInput("Place", place, placeError, placeFocus, titleCased = true) { place = it }
        // Web parity (components/forms/DateRangeField): the two dates are one answer — how long the
        // workshop ran — and they are labelled as one before being split into start and end.
        Text("Workshop duration", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {
                DatePickerField("Start date", startDate) { picked ->
                    startDate = picked
                    if (endDate == null || endDate!!.isBefore(picked)) endDate = picked
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                DatePickerField("End date", endDate) { endDate = it }
            }
        }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        TextInput("Description", description, minLines = 3) { description = it }
        MultiNoteInput(value = notes) { notes = it }
        ArtisanMultiSelectField(
            label = "Linked artisans",
            artisans = artisans,
            selectedIds = selectedArtisans
        ) { id ->
            selectedArtisans = if (selectedArtisans.contains(id)) selectedArtisans - id else selectedArtisans + id
        }
        CheckboxMultiSelectField(
            label = "Crafts covered",
            emptyMessage = "No crafts available yet. Create a craft first.",
            options = crafts.map { it.id to (it.name + (it.place?.let { p -> " · $p" } ?: "")) },
            selectedIds = selectedCrafts
        ) { id ->
            selectedCrafts = if (selectedCrafts.contains(id)) selectedCrafts - id else selectedCrafts + id
        }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "workshop", recordId = editing!!.id, onError = onError)
        }
        MediaCaptureSection(repository = repository, media = media, onMessage = onError, onError = onError)
        SaveButton(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update workshop" else "Save workshop"
        ) { submit() }
    }
}

@Composable
private fun ProductForm(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    /** Whether the two lists above arrived, could not be reached, or are still coming. */
    lookupState: CarryScopeState = CarryScopeState.PENDING,
    editing: ProductDetailDto? = null,
    prefill: Prefill? = null,
    adminView: Boolean = false,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    val isEdit = editing != null
    var productName by remember(editing) { mutableStateOf(editing?.productName ?: "") }
    var localName by remember(editing) { mutableStateOf(editing?.localName ?: "") }
    var craftName by remember(editing) { mutableStateOf(editing?.craftName ?: prefill?.craftName ?: "") }
    var artisanName by remember(editing) { mutableStateOf(editing?.artisanName ?: prefill?.artisanName ?: "") }
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }
    var craftId by remember(editing) { mutableStateOf(editing?.craftId ?: prefill?.craftId ?: "") }
    var artisanId by remember(editing) { mutableStateOf(editing?.artisanId ?: prefill?.artisanId ?: "") }
    var productType by remember(editing) { mutableStateOf(editing?.productType ?: "OTHER") }
    var marketDemand by remember(editing) { mutableStateOf(editing?.marketDemand ?: "UNKNOWN") }
    var timeTaken by remember(editing) { mutableStateOf(editing?.timeTakenToCompleteProduct ?: "") }
    var size by remember(editing) { mutableStateOf(editing?.size ?: "") }
    var length by remember(editing) { mutableStateOf(numToText(editing?.lengthInches)) }
    var breadth by remember(editing) { mutableStateOf(numToText(editing?.breadthInches)) }
    var height by remember(editing) { mutableStateOf(numToText(editing?.heightInches)) }
    var costOfMaking by remember(editing) { mutableStateOf(numToText(editing?.costOfMaking)) }
    var sellingPrice by remember(editing) { mutableStateOf(numToText(editing?.sellingPrice)) }
    var rawMaterials by remember(editing) { mutableStateOf(editing?.rawMaterialsUsed ?: "") }
    var mainTools by remember(editing) { mutableStateOf(editing?.mainToolsUsed ?: "") }
    var functionUse by remember(editing) { mutableStateOf(editing?.productFunctionUse ?: "") }
    var remarks by remember(editing) { mutableStateOf(editing?.remarks ?: "") }
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }
    val workshop = rememberWorkshopPicker(repository, isEdit, editing?.workshopId, editing)
    /**
     * Offer the sitting this researcher was last working in, however they got here — the in-memory
     * [Prefill] only survives a tap made straight off the save screen, and the route they actually
     * take is out via the dashboard and back an hour later.
     *
     * The PRODUCT in the bag is this form's own subject and is never applied here; a tool or a
     * process in it belongs to other forms and is left alone rather than dropped, so they still
     * have it when the researcher gets there.
     */
    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.PRODUCT_FORM,
        // Both dropdowns are built from exactly these two lists, so "absent from the list" answers
        // both "you can no longer reach it" and "this form could not have shown it" at once.
        scopes = listOf(
            carryScope(CarryNode.ARTISAN, lookupState, artisans) { it.id },
            carryScope(CarryNode.CRAFT, lookupState, crafts) { it.id }
        ),
        handoff = prefill
    ) { carried ->
        carried.craftId?.let { craftId = it }
        carried.craftName?.let { craftName = it }
        carried.artisanId?.let { artisanId = it }
        carried.artisanName?.let { artisanName = it }
        carried.place?.let { place = it }
        carried.workshopId?.let { if (!workshop.isDirty()) workshop.applyDefault(it) }
    }
    /** "Change": drop every carried value in one action so the researcher picks from scratch. */
    fun clearCarriedContext() {
        carry.change()
        craftId = ""
        craftName = ""
        artisanId = ""
        artisanName = ""
        place = ""
    }
    var saving by remember { mutableStateOf(false) }
    var productNameError by remember { mutableStateOf<String?>(null) }
    var craftNameError by remember { mutableStateOf<String?>(null) }
    var artisanNameError by remember { mutableStateOf<String?>(null) }
    var placeError by remember { mutableStateOf<String?>(null) }
    val productNameFocus = remember { FocusRequester() }
    val craftNameFocus = remember { FocusRequester() }
    val artisanNameFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }

    LaunchedEffect(editing) {
        val existing = editing?.location
        if (existing != null && media.location == null) media.location = existing.toRequest()
    }

    fun submit() {
        if (!validateRequired(listOf(
                RequiredCheck(productName.isBlank(), { productNameError = it }, productNameFocus),
                RequiredCheck(craftName.isBlank(), { craftNameError = it }, craftNameFocus),
                RequiredCheck(artisanName.isBlank(), { artisanNameError = it }, artisanNameFocus),
                RequiredCheck(place.isBlank(), { placeError = it }, placeFocus)
            ))) { onError("Please fill the required field highlighted above."); return }
        newRecordLocationError(isEdit, media.location)?.let { onError(it); return }
        scope.launch {
            if (!workshop.confirmSubmission()) return@launch
            saving = true
            val body = ProductCreateRequest(
                productName = productName.trim(),
                localName = localName.blankToNull(),
                craftName = craftName.trim(),
                artisanName = artisanName.trim(),
                place = place.trim(),
                productType = productType,
                timeTakenToCompleteProduct = timeTaken.blankToNull(),
                size = size.blankToNull(),
                lengthInches = length.toDoubleOrNull(),
                breadthInches = breadth.toDoubleOrNull(),
                heightInches = height.toDoubleOrNull(),
                costOfMaking = costOfMaking.toDoubleOrNull(),
                sellingPrice = sellingPrice.toDoubleOrNull(),
                marketDemand = marketDemand,
                rawMaterialsUsed = rawMaterials.blankToNull(),
                mainToolsUsed = mainTools.blankToNull(),
                productFunctionUse = functionUse.blankToNull(),
                remarks = remarks.blankToNull(),
                artisanId = artisanId.ifBlank { null },
                craftId = craftId.ifBlank { null },
                workshopId = workshop.value(),
                status = status,
                recordedAt = if (isEdit) null else Instant.now().toString(),
                location = locationForBody(isEdit, media.location, editing?.location)
            )
            // Bank the sitting the moment the record is accepted, so the next form opened from the
            // dashboard already knows where the researcher is.
            val sitting = CarryContext(
                artisanId = artisanId.ifBlank { null },
                artisanName = artisanName.trim(),
                place = place.trim(),
                craftId = craftId.ifBlank { null },
                craftName = craftName.trim(),
                workshopId = workshop.value(),
                workshopName = workshop.workshops.firstOrNull { it.id == workshop.value() }?.title
            )
            val queuedOffline = runCatching {
                trySaveOffline(repository, context, isEdit, "product", offlineFormJson.encodeToString(body),
                    productName.trim(), media, productName.trim(), "Field media for ${productName.trim()}")
            }.getOrElse { false }
            if (queuedOffline) {
                // Offline is the normal case, but a queued product has no id yet, so no process form
                // could link to it. Whatever product was in the bag is dropped rather than left to
                // stand in for the one just recorded — an old product offered under a new one's name
                // is a wrong link.
                carry.prune(CarryNode.PRODUCT)
                carry.remember(sitting)
                media.reset()
                onError("Saved on this device. It'll upload automatically when you're back online.")
                onDone()
                saving = false
                return@launch
            }
            runCatching {
                val productId = if (isEdit) {
                    repository.updateProduct(editing!!.id, body).id
                } else {
                    repository.createProduct(body).id
                }
                uploadAttachments(repository, context, media, "product", productId, productName, "Field media for ${productName.trim()}")
                productId
            }.onSuccess { productId ->
                // The product itself now joins the bag: a process is documented against a product, so
                // the process form should be offering this one rather than making them find it again.
                carry.remember(sitting.copy(productId = productId, productName = productName.trim()))
                media.reset()
                onDone()
            }.onFailure { onError(it.message ?: "Unable to save product") }
            saving = false
        }
    }
    val productSig: () -> String = {
        // The offer resolves a beat after the first composition, so until it does the handoff it was
        // built from stands in — otherwise the baseline and the prefill would disagree for one frame
        // and an untouched form would come out of it reading as edited.
        val carried = carry.offer?.context ?: prefill?.toCarryContext()
        listOf(productName, localName, craftName.exceptCarried(carried?.craftName),
            artisanName.exceptCarried(carried?.artisanName), place.exceptCarried(carried?.place),
            productType, marketDemand, timeTaken, size,
            length, breadth, height, costOfMaking, sellingPrice, rawMaterials, mainTools, functionUse, remarks,
            status, craftId.exceptCarried(carried?.craftId), artisanId.exceptCarried(carried?.artisanId)).joinToString("")
    }
    val initialSig = remember(editing) { productSig() }
    val dirty = !saving && (
        productSig() != initialSig || workshop.isDirty() || media.uris.isNotEmpty() || media.measurementUri != null
    )

    RecordCard(title = if (isEdit) "Edit product" else "Add product") {
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        // Above the workshop picker, so what was filled in is read before any of the fields it filled.
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        WorkshopField(state = workshop, saving = saving)
        RequiredInput("Product name", productName, productNameError, productNameFocus, titleCased = true) { productName = it }
        TextInput("Local name", localName) { localName = it }
        DropdownField("Product type", productTypeOptions.map { it to it }, productType, includeNone = false) { productType = it }
        DropdownField(
            label = "Linked craft (fills craft name)",
            options = crafts.map { it.id to it.name },
            selectedValue = craftId,
            placeholder = "Unlinked / type below"
        ) { id ->
            craftId = id
            crafts.firstOrNull { it.id == id }?.let { craftName = it.name }
            // Once the craft changes, drop a linked artisan that no longer belongs to it.
            if (id.isNotBlank() && artisanId.isNotBlank() && artisans.none { it.id == artisanId && it.craftId == id }) {
                artisanId = ""
            }
        }
        RequiredInput("Craft name", craftName, craftNameError, craftNameFocus, titleCased = true) { craftName = it }
        // Task 6: the artisan dropdown is gated on a linked craft and only lists that craft's artisans.
        val artisanOptionsForCraft = if (craftId.isNotBlank()) {
            artisans.filter { it.craftId == craftId || it.id == artisanId }
        } else {
            artisans
        }
        DropdownField(
            label = "Linked artisan (fills artisan + place)",
            options = artisanOptionsForCraft.map { it.id to "${it.name} · ${it.place}" },
            selectedValue = artisanId,
            placeholder = if (craftId.isBlank()) "Select a linked craft first" else "Unlinked / type below",
            enabled = craftId.isNotBlank()
        ) { id ->
            artisanId = id
            artisans.firstOrNull { it.id == id }?.let {
                artisanName = it.name
                place = it.place
                // An explicit pick replaces the remembered context and retires the banner: from here
                // on the artisan on screen is the researcher's own choice, not a suggestion.
                carry.remember(
                    CarryContext(
                        artisanId = it.id,
                        artisanName = it.name,
                        place = it.place,
                        craftId = craftId.ifBlank { null },
                        craftName = craftName.blankToNull()
                    ),
                    explicit = true
                )
            }
        }
        if (craftId.isNotBlank() && artisanOptionsForCraft.isEmpty()) {
            Text("No artisans are linked to this craft yet.", color = Muted, fontSize = 12.sp)
        }
        RequiredInput("Artisan name", artisanName, artisanNameError, artisanNameFocus, titleCased = true) { artisanName = it }
        RequiredInput("Place", place, placeError, placeFocus, titleCased = true) { place = it }
        TextInput("Time taken to complete", timeTaken) { timeTaken = it }
        TextInput("Size", size) { size = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Length (inches)", length, keyboardType = KeyboardType.Decimal) { length = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Breadth (inches)", breadth, keyboardType = KeyboardType.Decimal) { breadth = it } }
        }
        TextInput("Height (inches)", height, keyboardType = KeyboardType.Decimal) { height = it }
        GridMeasurementSection(
            repository = repository,
            media = media,
            includeHeight = true,
            onLengthBreadth = { l, b -> if (l != null && l > 0) length = numToText(l); if (b != null && b > 0) breadth = numToText(b) },
            onHeight = { height = numToText(it) }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Cost of making", costOfMaking, keyboardType = KeyboardType.Decimal) { costOfMaking = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Selling price", sellingPrice, keyboardType = KeyboardType.Decimal) { sellingPrice = it } }
        }
        DropdownField("Market demand", marketDemandOptions.map { it to it }, marketDemand, includeNone = false) { marketDemand = it }
        TextInput("Raw materials used", rawMaterials, minLines = 2) { rawMaterials = it }
        TextInput("Main tools used", mainTools, minLines = 2) { mainTools = it }
        TextInput("Function or use", functionUse, minLines = 2) { functionUse = it }
        TextInput("Remarks", remarks, minLines = 3) { remarks = it }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "product", recordId = editing!!.id, onError = onError)
        }
        MediaCaptureSection(repository = repository, media = media, onMessage = onError, onError = onError)
        SaveButton(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update product" else "Save product"
        ) { submit() }
    }
}

@Composable
private fun ToolForm(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    /** Whether the two lists above arrived, could not be reached, or are still coming. */
    lookupState: CarryScopeState = CarryScopeState.PENDING,
    editing: ToolDetailDto? = null,
    prefill: Prefill? = null,
    adminView: Boolean = false,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    val stages = rememberMediaCaptureState()
    val isEdit = editing != null
    var toolkitName by remember(editing) { mutableStateOf(editing?.toolkitName ?: "") }
    var localName by remember(editing) { mutableStateOf(editing?.localName ?: "") }
    var englishName by remember(editing) { mutableStateOf(editing?.englishName ?: "") }
    var craftName by remember(editing) { mutableStateOf(editing?.craftName ?: prefill?.craftName ?: "") }
    var artisanName by remember(editing) { mutableStateOf(editing?.artisanName ?: prefill?.artisanName ?: "") }
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }
    var craftId by remember(editing) { mutableStateOf(editing?.craftId ?: prefill?.craftId ?: "") }
    var artisanId by remember(editing) { mutableStateOf(editing?.artisanId ?: prefill?.artisanId ?: "") }
    var processUsedIn by remember(editing) { mutableStateOf(editing?.processUsedIn ?: "") }
    var material by remember(editing) { mutableStateOf(editing?.material ?: "") }
    var yearsInUse by remember(editing) { mutableStateOf(editing?.yearsInUse?.toString() ?: "") }
    var height by remember(editing) { mutableStateOf(numToText(editing?.height)) }
    var width by remember(editing) { mutableStateOf(numToText(editing?.width)) }
    var length by remember(editing) { mutableStateOf(numToText(editing?.lengthInches)) }
    var breadth by remember(editing) { mutableStateOf(numToText(editing?.breadthInches)) }
    var thickness by remember(editing) { mutableStateOf(numToText(editing?.thickness)) }
    var weight by remember(editing) { mutableStateOf(numToText(editing?.weight)) }
    var radius by remember(editing) { mutableStateOf(numToText(editing?.radius)) }
    var maker by remember(editing) { mutableStateOf(editing?.maker ?: "UNKNOWN") }
    var traditionType by remember(editing) { mutableStateOf(editing?.traditionType ?: "UNKNOWN") }
    var replacementCost by remember(editing) { mutableStateOf(numToText(editing?.replacementCost)) }
    var suggestions by remember(editing) { mutableStateOf(editing?.suggestionsForToolImprovement ?: "") }
    var remarks by remember(editing) { mutableStateOf(editing?.remarks ?: "") }
    val workshop = rememberWorkshopPicker(repository, isEdit, editing?.workshopId, editing)
    /**
     * Offer the sitting this researcher was last working in, however they got here.
     *
     * The TOOL in the bag is this form's own subject and is never applied here; a product or a
     * process in it belongs to other forms and is left alone rather than dropped, so they still have
     * it when the researcher gets there.
     */
    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.TOOL_FORM,
        // Both dropdowns are built from exactly these two lists, so "absent from the list" answers
        // both "you can no longer reach it" and "this form could not have shown it" at once.
        scopes = listOf(
            carryScope(CarryNode.ARTISAN, lookupState, artisans) { it.id },
            carryScope(CarryNode.CRAFT, lookupState, crafts) { it.id }
        ),
        handoff = prefill
    ) { carried ->
        carried.craftId?.let { craftId = it }
        carried.craftName?.let { craftName = it }
        carried.artisanId?.let { artisanId = it }
        carried.artisanName?.let { artisanName = it }
        carried.place?.let { place = it }
        carried.workshopId?.let { if (!workshop.isDirty()) workshop.applyDefault(it) }
    }
    /** "Change": drop every carried value in one action so the researcher picks from scratch. */
    fun clearCarriedContext() {
        carry.change()
        craftId = ""
        craftName = ""
        artisanId = ""
        artisanName = ""
        place = ""
    }
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }
    var saving by remember { mutableStateOf(false) }
    var toolkitNameError by remember { mutableStateOf<String?>(null) }
    var craftNameError by remember { mutableStateOf<String?>(null) }
    var artisanNameError by remember { mutableStateOf<String?>(null) }
    var placeError by remember { mutableStateOf<String?>(null) }
    val toolkitNameFocus = remember { FocusRequester() }
    val craftNameFocus = remember { FocusRequester() }
    val artisanNameFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }

    LaunchedEffect(editing) {
        val existing = editing?.location
        if (existing != null && media.location == null) media.location = existing.toRequest()
    }

    fun submit() {
        if (!validateRequired(listOf(
                RequiredCheck(toolkitName.isBlank(), { toolkitNameError = it }, toolkitNameFocus),
                RequiredCheck(craftName.isBlank(), { craftNameError = it }, craftNameFocus),
                RequiredCheck(artisanName.isBlank(), { artisanNameError = it }, artisanNameFocus),
                RequiredCheck(place.isBlank(), { placeError = it }, placeFocus)
            ))) { onError("Please fill the required field highlighted above."); return }
        newRecordLocationError(isEdit, media.location)?.let { onError(it); return }
        scope.launch {
            if (!workshop.confirmSubmission()) return@launch
            saving = true
            val body = ToolCreateRequest(
                toolkitName = toolkitName.trim(),
                localName = localName.blankToNull(),
                englishName = englishName.blankToNull(),
                craftName = craftName.trim(),
                artisanName = artisanName.trim(),
                place = place.trim(),
                processUsedIn = processUsedIn.blankToNull(),
                material = material.blankToNull(),
                yearsInUse = yearsInUse.toIntOrNull(),
                height = height.toDoubleOrNull(),
                width = width.toDoubleOrNull(),
                lengthInches = length.toDoubleOrNull(),
                breadthInches = breadth.toDoubleOrNull(),
                thickness = thickness.toDoubleOrNull(),
                weight = weight.toDoubleOrNull(),
                radius = radius.toDoubleOrNull(),
                maker = maker,
                traditionType = traditionType,
                replacementCost = replacementCost.toDoubleOrNull(),
                suggestionsForToolImprovement = suggestions.blankToNull(),
                remarks = remarks.blankToNull(),
                artisanId = artisanId.ifBlank { null },
                craftId = craftId.ifBlank { null },
                workshopId = workshop.value(),
                status = status,
                recordedAt = if (isEdit) null else Instant.now().toString(),
                location = locationForBody(isEdit, media.location, editing?.location)
            )
            // Bank the sitting the moment the record is accepted, so the next form opened from the
            // dashboard already knows where the researcher is.
            val sitting = CarryContext(
                artisanId = artisanId.ifBlank { null },
                artisanName = artisanName.trim(),
                place = place.trim(),
                craftId = craftId.ifBlank { null },
                craftName = craftName.trim(),
                workshopId = workshop.value(),
                workshopName = workshop.workshops.firstOrNull { it.id == workshop.value() }?.title
            )
            if (!isEdit && !repository.isOnline(context)) {
                val ok = runCatching {
                    val items = media.uris.mapIndexed { i, uri ->
                        com.fieldrepository.app.data.OfflineMediaSpec(uri = uri, caption = "Field media for ${toolkitName.trim()}", recordName = toolkitName.trim(), batchIndex = i + 1)
                    } + stages.uris.mapIndexed { i, uri ->
                        com.fieldrepository.app.data.OfflineMediaSpec(uri = uri, caption = "Process stage step ${i + 1} for ${toolkitName.trim()}", recordName = toolkitName.trim(), stageStep = i + 1)
                    }
                    repository.queueOfflineEntry(context, "tool", offlineFormJson.encodeToString(body), toolkitName.trim(), items)
                }.isSuccess
                if (ok) {
                    // Offline is the normal case, but a queued tool has no id yet, so nothing can be
                    // assigned to it. Whatever tool was in the bag is dropped rather than left to
                    // stand in for the one just recorded — an old tool offered under a new one's
                    // name is a wrong link.
                    carry.prune(CarryNode.TOOL)
                    carry.remember(sitting)
                    media.reset(); stages.reset()
                    onError("Saved on this device. It'll upload automatically when you're back online.")
                    onDone(); saving = false; return@launch
                } else onError("Couldn't save offline")
                saving = false; return@launch
            }
            runCatching {
                val toolId = if (isEdit) {
                    repository.updateTool(editing!!.id, body).id
                } else {
                    repository.createTool(body).id
                }
                uploadAttachments(repository, context, media, "tool", toolId, toolkitName, "Field media for ${toolkitName.trim()}")
                // Each stage capture is uploaded as a numbered process step (STAGE_STEP_n).
                stages.uris.forEachIndexed { index, uri ->
                    repository.uploadMedia(
                        context = context,
                        uri = uri,
                        linkedRecordType = "tool",
                        linkedRecordId = toolId,
                        caption = "Process stage step ${index + 1} for ${toolkitName.trim()}",
                        location = stages.location,
                        titleHint = toolkitName,
                        batchIndex = 1,
                        stageStep = index + 1
                    )
                }
                toolId
            }.onSuccess { toolId ->
                // The tool itself now joins the bag, so assigning it to another artisan opens with
                // the tool already picked instead of hunting it out of a dropdown of seventy.
                carry.remember(sitting.copy(toolId = toolId, toolName = toolkitName.trim()))
                media.reset()
                stages.reset()
                onDone()
            }.onFailure { onError(it.message ?: "Unable to save tool") }
            saving = false
        }
    }
    val toolSig: () -> String = {
        // The offer resolves a beat after the first composition, so until it does the handoff it was
        // built from stands in — otherwise the baseline and the prefill would disagree for one frame
        // and an untouched form would come out of it reading as edited.
        val carried = carry.offer?.context ?: prefill?.toCarryContext()
        listOf(toolkitName, localName, englishName, craftName.exceptCarried(carried?.craftName),
            artisanName.exceptCarried(carried?.artisanName), place.exceptCarried(carried?.place),
            processUsedIn, material,
            yearsInUse, height, width, length, breadth, thickness, weight, radius, maker, traditionType,
            replacementCost, suggestions, remarks, status,
            craftId.exceptCarried(carried?.craftId), artisanId.exceptCarried(carried?.artisanId)).joinToString("")
    }
    val initialSig = remember(editing) { toolSig() }
    val dirty = !saving && (
        toolSig() != initialSig || workshop.isDirty() ||
            media.uris.isNotEmpty() || media.measurementUri != null || stages.uris.isNotEmpty()
    )

    RecordCard(title = if (isEdit) "Edit tool" else "Add tool") {
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        // Above the workshop picker, so what was filled in is read before any of the fields it filled.
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        WorkshopField(state = workshop, saving = saving)
        RequiredInput("Toolkit name", toolkitName, toolkitNameError, toolkitNameFocus, titleCased = true) { toolkitName = it }
        TextInput("Local name", localName) { localName = it }
        TextInput("English name", englishName, titleCased = true) { englishName = it }
        DropdownField(
            label = "Linked craft (fills craft name)",
            options = crafts.map { it.id to it.name },
            selectedValue = craftId,
            placeholder = "Unlinked / type below"
        ) { id ->
            craftId = id
            crafts.firstOrNull { it.id == id }?.let { craftName = it.name }
            // Once the craft changes, drop a linked artisan that no longer belongs to it.
            if (id.isNotBlank() && artisanId.isNotBlank() && artisans.none { it.id == artisanId && it.craftId == id }) {
                artisanId = ""
            }
        }
        RequiredInput("Craft name", craftName, craftNameError, craftNameFocus, titleCased = true) { craftName = it }
        // Task 6: the artisan dropdown is gated on a linked craft and only lists that craft's artisans.
        val artisanOptionsForCraft = if (craftId.isNotBlank()) {
            artisans.filter { it.craftId == craftId || it.id == artisanId }
        } else {
            artisans
        }
        DropdownField(
            label = "Linked artisan (fills artisan + place)",
            options = artisanOptionsForCraft.map { it.id to "${it.name} · ${it.place}" },
            selectedValue = artisanId,
            placeholder = if (craftId.isBlank()) "Select a linked craft first" else "Unlinked / type below",
            enabled = craftId.isNotBlank()
        ) { id ->
            artisanId = id
            artisans.firstOrNull { it.id == id }?.let {
                artisanName = it.name
                place = it.place
                // An explicit pick replaces the remembered context and retires the banner: from here
                // on the artisan on screen is the researcher's own choice, not a suggestion.
                carry.remember(
                    CarryContext(
                        artisanId = it.id,
                        artisanName = it.name,
                        place = it.place,
                        craftId = craftId.ifBlank { null },
                        craftName = craftName.blankToNull()
                    ),
                    explicit = true
                )
            }
        }
        if (craftId.isNotBlank() && artisanOptionsForCraft.isEmpty()) {
            Text("No artisans are linked to this craft yet.", color = Muted, fontSize = 12.sp)
        }
        RequiredInput("Artisan name", artisanName, artisanNameError, artisanNameFocus, titleCased = true) { artisanName = it }
        RequiredInput("Place", place, placeError, placeFocus, titleCased = true) { place = it }
        TextInput("Process used in", processUsedIn) { processUsedIn = it }
        TextInput("Material", material) { material = it }
        TextInput("Years in use", yearsInUse, keyboardType = KeyboardType.Number) { yearsInUse = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Height", height, keyboardType = KeyboardType.Decimal) { height = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Width", width, keyboardType = KeyboardType.Decimal) { width = it } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Length (inches)", length, keyboardType = KeyboardType.Decimal) { length = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Breadth (inches)", breadth, keyboardType = KeyboardType.Decimal) { breadth = it } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Thickness", thickness, keyboardType = KeyboardType.Decimal) { thickness = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Weight", weight, keyboardType = KeyboardType.Decimal) { weight = it } }
        }
        TextInput("Radius", radius, keyboardType = KeyboardType.Decimal) { radius = it }
        GridMeasurementSection(
            repository = repository,
            media = media,
            includeHeight = true,
            onLengthBreadth = { l, b -> if (l != null && l > 0) length = numToText(l); if (b != null && b > 0) breadth = numToText(b) },
            onHeight = { height = numToText(it) }
        )
        DropdownField("Maker", makerOptions.map { it to it }, maker, includeNone = false) { maker = it }
        DropdownField("Tradition type", traditionOptions.map { it to it }, traditionType, includeNone = false) { traditionType = it }
        TextInput("Replacement cost", replacementCost, keyboardType = KeyboardType.Decimal) { replacementCost = it }
        TextInput("Suggestions for improvement", suggestions, minLines = 2) { suggestions = it }
        TextInput("Remarks", remarks, minLines = 3) { remarks = it }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        ToolStagesSection(stages = stages, onMessage = onError, onError = onError)
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "tool", recordId = editing!!.id, onError = onError)
        }
        MediaCaptureSection(repository = repository, media = media, onMessage = onError, onError = onError)
        SaveButton(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update tool" else "Save tool"
        ) { submit() }
    }
}

/** Capture the making/using process of a tool as an ordered set of stage steps. */
@Composable
private fun ToolStagesSection(
    stages: MediaCaptureState,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) stages.uris = stages.uris + uris
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) stages.uris = stages.uris + uri
        pendingCaptureUri = null
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) stages.uris = stages.uris + uri
        pendingCaptureUri = null
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Process stages", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "Document each step of making or using this tool. Captures are archived in order as STAGE_STEP_1, STAGE_STEP_2, …",
            color = Muted,
            fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { pickMedia.launch("*/*") }, modifier = Modifier.weight(1f)) { Text("Add files") }
            OutlinedButton(
                onClick = {
                    permissionLauncher.launch(requiredAndroidPermissions())
                    val uri = createAppFileUri(context, "stage-photo-", ".jpg")
                    pendingCaptureUri = uri
                    takePhoto.launch(uri)
                },
                modifier = Modifier.weight(1f)
            ) { Text("Photo step") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    permissionLauncher.launch(requiredAndroidPermissions())
                    val uri = createAppFileUri(context, "stage-video-", ".mp4")
                    pendingCaptureUri = uri
                    takeVideo.launch(uri)
                },
                modifier = Modifier.weight(1f)
            ) { Text("Video step") }
            OutlinedButton(
                onClick = {
                    permissionLauncher.launch(requiredAndroidPermissions())
                    if (!recording) {
                        runCatching {
                            val file = createAppFile(context, "stage-audio-", ".m4a")
                            recorder = createAudioRecorder(context, file).also { it.start() }
                            recordingFile = file
                            recording = true
                            onMessage("Recording stage audio…")
                        }.onFailure { onError(it.message ?: "Unable to start stage audio") }
                    } else {
                        runCatching {
                            recorder?.stop(); recorder?.release()
                            recordingFile?.let { file -> stages.uris = stages.uris + uriForFile(context, file) }
                        }.onFailure { onError(it.message ?: "Unable to stop stage audio") }
                        recorder = null; recordingFile = null; recording = false
                        onMessage("Stage audio step added")
                    }
                },
                modifier = Modifier.weight(1f)
            ) { if (recording) StopSquareLabel("Stop") else Text("Audio step ●") }
        }
        if (recording) {
            RecordingIndicator(getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) })
        }
        if (stages.uris.isNotEmpty()) {
            Text("${stages.uris.size} stage step(s) captured", color = Muted, fontSize = 12.sp)
            stages.uris.forEachIndexed { index, uri ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Step ${index + 1}", color = Body, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    AndroidUriPreview(context = context, uri = uri)
                }
            }
            TextButton(onClick = { stages.uris = emptyList() }) { Text("Clear stages") }
        }
    }
}

// ===========================================================================
// Process documentation
// ===========================================================================

/** Mutable UI holder for one process step: its name, fixed type, and its own media capture state. */
private class ProcessStepUi(
    val key: String,
    serverId: String?,
    name: String,
    val stepType: String,
    existingMedia: List<MediaFileDto> = emptyList(),
    notes: String? = null
) {
    var serverId by mutableStateOf(serverId)
    var name by mutableStateOf(name)
    // Saved media already attached to this step; mutable so removing one updates the UI live.
    var existingMedia by mutableStateOf(existingMedia)
    var nameError by mutableStateOf<String?>(null)
    val nameFocus = FocusRequester()
    val media = MediaCaptureState()
    // "Record additional information": free-text context for this step. Pre-checked when editing a
    // step that already has notes so the existing text stays visible.
    var recordAdditional by mutableStateOf(!notes.isNullOrBlank())
    var notes by mutableStateOf(notes ?: "")
}

private fun ProcessStepUi.stepTypeLabel(): String =
    if (stepType == "SEQUENTIAL") "Sequential" else "Group of activities"

/** Per-file nomenclature segment for a step's media: 1A/1B… for sequential, 1-G1/1-G2… for groups. */
private fun processStepSegment(stepNumber: Int, stepType: String, fileIndex: Int): String =
    if (stepType == "SEQUENTIAL") "STEP_${stepNumber}${'A' + (fileIndex % 26)}"
    else "STEP_${stepNumber}_G${fileIndex + 1}"

@Composable
private fun ProcessForm(
    repository: FieldRepository,
    editing: ProcessDetailDto? = null,
    adminView: Boolean = false,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEdit = editing != null
    val preMedia = rememberMediaCaptureState()

    var products by remember { mutableStateOf<List<ProductDetailDto>>(emptyList()) }
    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    // "I can no longer see this artisan" and "there is no signal" are different answers, and the
    // carry prefill treats them differently — see [rememberFormCarry].
    var artisanListState by remember { mutableStateOf(CarryScopeState.PENDING) }
    var name by remember(editing) { mutableStateOf(editing?.name ?: "") }
    var artisanId by remember(editing) { mutableStateOf(editing?.product?.artisanId ?: "") }
    var productId by remember(editing) { mutableStateOf(editing?.productId ?: "") }
    var artisanError by remember { mutableStateOf<String?>(null) }
    var preProcessAvailable by remember(editing) { mutableStateOf(editing?.preProcessAvailable ?: false) }
    var notes by remember(editing) { mutableStateOf(editing?.notes ?: "") }
    val workshop = rememberWorkshopPicker(repository, isEdit, editing?.workshopId, editing)
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }
    var saving by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var productError by remember { mutableStateOf<String?>(null) }
    var stepsError by remember { mutableStateOf<String?>(null) }
    var preMediaError by remember { mutableStateOf<String?>(null) }
    // Saved pre-process media for this process; mutable so removing one reflects immediately.
    var existingPreMedia by remember(editing) { mutableStateOf(editing?.media ?: emptyList()) }
    val nameFocus = remember { FocusRequester() }
    var addMenu by remember { mutableStateOf(false) }

    val steps = remember(editing) {
        mutableStateListOf<ProcessStepUi>().apply {
            editing?.steps?.forEach { add(ProcessStepUi(java.util.UUID.randomUUID().toString(), it.id, it.name, it.stepType, it.media, it.notes)) }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { repository.products() }.onSuccess { products = it }
        val gotArtisans = runCatching { repository.artisans() }.onSuccess { artisans = it }.isSuccess
        artisanListState = if (gotArtisans) CarryScopeState.LOADED else CarryScopeState.UNAVAILABLE
    }

    /**
     * The carried product, held until this artisan's product list arrives.
     *
     * A process is documented against a product, so "I just recorded a product, now let me record
     * how it is made" is the most common journey into this form. The product cannot be applied on
     * the spot the way the artisan can: the dropdown it belongs in is fetched per artisan, and that
     * fetch only starts once the prefill has supplied the artisan. So it waits here for one round
     * trip, and the effect below either seats it or drops it.
     */
    val carriedProduct = remember { mutableStateOf<String?>(null) }

    // Offer the sitting this researcher was last working in: the artisan, the workshop, and the
    // product they documented last, which is what this record is about.
    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.PROCESS_FORM,
        // No craft or tool field here, so neither is filled in nor claimed; both stay in the bag.
        scopes = listOf(carryScope(CarryNode.ARTISAN, artisanListState, artisans) { it.id })
    ) { carried ->
        carried.artisanId?.let { artisanId = it }
        carried.productId?.let { carriedProduct.value = it }
        carried.workshopId?.let { if (!workshop.isDirty()) workshop.applyDefault(it) }
    }
    /** "Change": drop the carried artisan and product in one action. */
    fun clearCarriedContext() {
        carry.change()
        carriedProduct.value = null
        artisanId = ""
        productId = ""
    }

    // Products belong to an artisan, so the product list is scoped to the chosen artisan. We re-key
    // this effect on `artisans` and `products` as well as `artisanId`, so it also re-runs once those
    // supporting lists finish loading — otherwise, in edit mode where artisanId is pre-set, the
    // effect would fire once before `artisans` arrived (artisan name unknown → name match skipped)
    // and never run again. On each run we fetch FRESH from the server filtered by artisanId AND the
    // artisan's name (the server OR-matches FK-linked products plus FK-null products with that typed
    // name), then union with any in-memory products whose artisan id/name matches as a fallback for
    // an older server. Loading / empty / error states are surfaced so the dropdown is never silently
    // empty when products actually exist.
    var artisanProducts by remember { mutableStateOf<List<ProductDetailDto>>(emptyList()) }
    var productsLoading by remember { mutableStateOf(false) }
    var productLoadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(artisanId, artisans, products) {
        productLoadError = null
        if (artisanId.isBlank()) {
            artisanProducts = emptyList()
            productsLoading = false
            return@LaunchedEffect
        }
        productsLoading = true
        val selectedArtisanName = artisans.firstOrNull { it.id == artisanId }?.name?.trim()
        val result = runCatching {
            val linked = repository.productsForArtisan(artisanId, selectedArtisanName)
            val broad = if (products.isNotEmpty()) products else runCatching { repository.products() }.getOrDefault(emptyList())
            val byName = broad.filter { p ->
                (p.artisanId != null && p.artisanId == artisanId) ||
                    (!selectedArtisanName.isNullOrBlank() && p.artisanName.trim().equals(selectedArtisanName, ignoreCase = true))
            }
            (linked + byName).distinctBy { it.id }
        }
        productsLoading = false
        result.onSuccess { fetched ->
            artisanProducts = fetched
            // This list is both the dropdown's options and the only proof the carried product is
            // still this artisan's and still reachable, so the deferred half of the prefill resolves
            // here. Deleted, or not this artisan's after all: either way it is dropped from the bag
            // and from the banner rather than offered as a link nobody can follow.
            carriedProduct.value?.let { carried ->
                carriedProduct.value = null
                if (fetched.any { it.id == carried }) productId = carried else carry.prune(CarryNode.PRODUCT)
            }
            // Keep a valid selection: clear product if it is no longer offered for this artisan.
            if (productId.isNotBlank() && fetched.none { it.id == productId }) productId = ""
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            artisanProducts = emptyList()
            productLoadError = "Couldn't load this artisan's products: ${it.message ?: "network error"}. Tap the artisan again to retry."
        }
    }

    fun submit() {
        nameError = null; productError = null; stepsError = null; preMediaError = null; artisanError = null
        var firstInvalid = false
        if (name.isBlank()) { nameError = "This field cannot be empty"; if (!firstInvalid) { firstInvalid = true; runCatching { nameFocus.requestFocus() } } }
        if (artisanId.isBlank()) artisanError = "Please select an artisan"
        if (productId.isBlank()) productError = "Please select a product"
        if (preProcessAvailable && preMedia.uris.isEmpty() && (editing?.media?.isEmpty() != false)) {
            preMediaError = "Attach the pre-process media or uncheck the box"
        }
        if (steps.isEmpty()) stepsError = "Add at least one step"
        steps.forEach { step ->
            step.nameError = null
            if (step.name.isBlank()) {
                step.nameError = "This field cannot be empty"
                if (!firstInvalid) { firstInvalid = true; runCatching { step.nameFocus.requestFocus() } }
            }
        }
        val blocked = nameError != null || productError != null || stepsError != null ||
            preMediaError != null || artisanError != null || steps.any { it.name.isBlank() }
        if (blocked) { onError("Please fill the required fields highlighted above."); return }

        scope.launch {
            if (!workshop.confirmSubmission()) return@launch
            saving = true
            val stepRequests = steps.mapIndexed { i, s ->
                ProcessStepRequest(
                    id = s.serverId,
                    name = s.name.trim(),
                    stepType = s.stepType,
                    sortOrder = i + 1,
                    notes = if (s.recordAdditional) s.notes.trim().ifBlank { null } else null
                )
            }
            val body = ProcessCreateRequest(
                name = name.trim(),
                productId = productId,
                preProcessAvailable = preProcessAvailable,
                notes = notes.blankToNull(),
                status = status,
                steps = stepRequests,
                workshopId = workshop.value(),
                recordedAt = if (isEdit) null else Instant.now().toString()
            )
            // Bank the sitting the moment the record is accepted — queued counts, offline being the
            // normal case — so the next form opened from the dashboard knows where the researcher is.
            val savedArtisan = artisans.firstOrNull { it.id == artisanId }
            val sitting = CarryContext(
                artisanId = artisanId.ifBlank { null },
                artisanName = savedArtisan?.name,
                place = savedArtisan?.place,
                craftId = savedArtisan?.craftId,
                craftName = savedArtisan?.craft?.name,
                // The product this process documents stays in the bag: a second process for the same
                // product is the next thing a researcher does, and it should not need finding again.
                productId = productId.ifBlank { null },
                productName = artisanProducts.firstOrNull { it.id == productId }?.productName,
                workshopId = workshop.value(),
                workshopName = workshop.workshops.firstOrNull { it.id == workshop.value() }?.title
            )
            // Offline: queue the process with its pre-process media (linked to the process) and each
            // step's media (linked to that step on sync, by index), preserving the step nomenclature.
            if (!isEdit && !repository.isOnline(context)) {
                val ok = runCatching {
                    val items = mutableListOf<com.fieldrepository.app.data.OfflineMediaSpec>()
                    if (preProcessAvailable) {
                        preMedia.uris.forEachIndexed { i, uri ->
                            items.add(com.fieldrepository.app.data.OfflineMediaSpec(
                                uri = uri, caption = "Pre-process media for ${name.trim()}", recordName = name.trim(),
                                customSegment = "PRE", batchIndex = i + 1, linkedType = "process"))
                        }
                    }
                    steps.forEachIndexed { index, local ->
                        local.media.uris.forEachIndexed { fileIndex, uri ->
                            items.add(com.fieldrepository.app.data.OfflineMediaSpec(
                                uri = uri, caption = "Process step ${local.name.trim()}", recordName = name.trim(),
                                customSegment = processStepSegment(index + 1, local.stepType, fileIndex),
                                batchIndex = fileIndex + 1, linkedType = "processstep", stepIndex = index))
                        }
                    }
                    repository.queueOfflineEntry(context, "process", offlineFormJson.encodeToString(body), name.trim(), items)
                }.isSuccess
                if (ok) {
                    carry.remember(sitting)
                    preMedia.reset(); steps.forEach { it.media.reset() }
                    onError("Saved on this device. It'll upload automatically when you're back online.")
                    onDone()
                } else onError("Couldn't save offline")
                saving = false
                return@launch
            }
            runCatching {
                val detail = if (isEdit) repository.updateProcess(editing!!.id, body) else repository.createProcess(body)
                if (preProcessAvailable) {
                    uploadAttachments(repository, context, preMedia, "process", detail.id, name, "Pre-process media for ${name.trim()}", customSegment = "PRE")
                }
                detail.steps.forEachIndexed { index, serverStep ->
                    val local = steps.getOrNull(index) ?: return@forEachIndexed
                    local.media.uris.forEachIndexed { fileIndex, uri ->
                        val segment = processStepSegment(index + 1, serverStep.stepType, fileIndex)
                        val staged = local.media.stagedDeferred[uri]?.let { runCatching { it.await() }.getOrNull() } ?: local.media.staged[uri]
                        if (staged != null) {
                            repository.completeStaged(
                                staged = staged,
                                linkedRecordType = "processstep",
                                linkedRecordId = serverStep.id,
                                recordName = name,
                                caption = "Process step ${serverStep.name}",
                                location = local.media.location,
                                batchIndex = fileIndex + 1,
                                customSegment = segment
                            )
                        } else {
                            repository.uploadMedia(
                                context = context,
                                uri = uri,
                                linkedRecordType = "processstep",
                                linkedRecordId = serverStep.id,
                                caption = "Process step ${serverStep.name}",
                                location = local.media.location,
                                titleHint = name,
                                batchIndex = fileIndex + 1,
                                customSegment = segment
                            )
                        }
                    }
                }
            }.onSuccess {
                carry.remember(sitting)
                preMedia.reset()
                steps.forEach { it.media.reset() }
                onDone()
            }.onFailure { onError(it.message ?: "Unable to save process") }
            saving = false
        }
    }
    val procSig: () -> String = {
        // A carried artisan or product is on the same footing as the workshop picker's automatic
        // default: the researcher did not choose it, so an untouched form must not ask them to save
        // it. Picking one themselves retires the offer, and the value starts counting from there.
        val carried = carry.offer?.context
        listOf(name, artisanId.exceptCarried(carried?.artisanId), productId.exceptCarried(carried?.productId),
            notes, status, preProcessAvailable.toString(),
            steps.joinToString("|") { "${it.serverId}~${it.name}~${it.stepType}~${it.notes}~${it.media.uris.size}" }).joinToString("")
    }
    val initialSig = remember(editing) { procSig() }
    val dirty = !saving && (
        procSig() != initialSig || workshop.isDirty() ||
            preMedia.uris.isNotEmpty() || steps.any { it.media.uris.isNotEmpty() }
    )

    RecordCard(title = if (isEdit) "Edit process" else "Document process") {
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        Text(
            "Capture how a product is made, step by step. Each process is tied to a product; multiple people can document the same product's processes.",
            color = Muted,
            fontSize = 12.sp
        )
        // Above the workshop picker, so what was filled in is read before any of the fields it filled.
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        WorkshopField(state = workshop, saving = saving)
        RequiredInput("Name of the process", name, nameError, nameFocus, titleCased = true) { name = it }
        DropdownField(
            label = "Artisan *",
            options = artisans.map { it.id to "${it.name} · ${it.place}" },
            selectedValue = artisanId,
            placeholder = "Select the artisan",
            includeNone = false
        ) { picked ->
            if (picked != artisanId) {
                artisanId = picked
                productId = ""
                carriedProduct.value = null
                // An explicit pick replaces the remembered context and retires the banner: from here
                // on the artisan on screen is the researcher's own choice, not a suggestion.
                artisans.firstOrNull { it.id == picked }?.let {
                    carry.remember(
                        CarryContext(artisanId = it.id, artisanName = it.name, place = it.place),
                        explicit = true
                    )
                }
            }
        }
        if (artisanError != null) Text(artisanError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        DropdownField(
            label = "Product *",
            options = artisanProducts.map { it.id to it.productName },
            selectedValue = productId,
            placeholder = when {
                artisanId.isBlank() -> "Select an artisan first"
                productsLoading -> "Loading products…"
                artisanProducts.isEmpty() -> "No products for this artisan"
                else -> "Select the product this process makes"
            },
            includeNone = false,
            enabled = artisanId.isNotBlank() && !productsLoading && artisanProducts.isNotEmpty()
        ) { picked ->
            productId = picked
            artisanProducts.firstOrNull { it.id == picked }?.let { product ->
                // Two calls, in this order, and the order is the point: pruning first drops the
                // product the banner was offering — the researcher has just overruled it, so it must
                // stop claiming it — and remembering then banks the one they chose. The artisan
                // above is still our suggestion, so the banner stays up saying so.
                carry.prune(CarryNode.PRODUCT)
                val artisan = artisans.firstOrNull { it.id == artisanId }
                carry.remember(
                    CarryContext(
                        artisanId = artisanId.ifBlank { null },
                        artisanName = artisan?.name,
                        place = artisan?.place,
                        craftId = artisan?.craftId,
                        productId = product.id,
                        productName = product.productName
                    )
                )
            }
        }
        when {
            productsLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Loading this artisan's products…", color = Muted, fontSize = 11.sp)
            }
            productLoadError != null -> Text(productLoadError!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            artisanId.isNotBlank() && artisanProducts.isEmpty() ->
                Text("No products found for this artisan yet. Create a product for them first, then return here.", color = Muted, fontSize = 11.sp)
            artisanId.isNotBlank() && artisanProducts.isNotEmpty() ->
                Text("${artisanProducts.size} product(s) available for this artisan.", color = Muted, fontSize = 11.sp)
        }
        if (productError != null) Text(productError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = preProcessAvailable, onCheckedChange = { preProcessAvailable = it })
            Text("Pre-processes available", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        }
        if (preProcessAvailable) {
            Text("Attach the pre-process media (required).", color = Muted, fontSize = 12.sp)
            if (existingPreMedia.isNotEmpty()) {
                Text("Already attached:", color = Muted, fontSize = 11.sp)
                existingPreMedia.forEach { saved ->
                    AndroidSavedMediaPreview(
                        context = context,
                        media = saved,
                        onDelete = {
                            scope.launch {
                                runCatching { repository.deleteMedia(saved.id) }
                                    .onSuccess { existingPreMedia = existingPreMedia.filterNot { it.id == saved.id } }
                                    .onFailure { error -> onError(error.message ?: "Unable to remove media") }
                            }
                        }
                    )
                }
            }
            MediaCaptureSection(repository = repository, media = preMedia, emphasizeVideo = true, onMessage = onError, onError = onError)
            if (preMediaError != null) Text(preMediaError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        HorizontalDivider()
        Text("Steps", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        if (stepsError != null) Text(stepsError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)

        steps.forEachIndexed { index, step ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Step ${index + 1} · ${step.stepTypeLabel()}", color = Body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { steps.removeAt(index) }) { Text("Remove") }
                    }
                    RequiredInput("Name of the step", step.name, step.nameError, step.nameFocus) { step.name = it }
                    if (step.existingMedia.isNotEmpty()) {
                        Text("Already attached:", color = Muted, fontSize = 11.sp)
                        step.existingMedia.forEach { saved ->
                            AndroidSavedMediaPreview(
                                context = context,
                                media = saved,
                                onDelete = {
                                    scope.launch {
                                        runCatching { repository.deleteMedia(saved.id) }
                                            .onSuccess { step.existingMedia = step.existingMedia.filterNot { it.id == saved.id } }
                                            .onFailure { error -> onError(error.message ?: "Unable to remove media") }
                                    }
                                }
                            )
                        }
                    }
                    MediaCaptureSection(
                        repository = repository,
                        media = step.media,
                        emphasizeVideo = true,
                        beforeLocation = {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Checkbox(
                                    checked = step.recordAdditional,
                                    onCheckedChange = { step.recordAdditional = it; if (!it) step.notes = "" }
                                )
                                Text("Record additional information", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                            }
                            if (step.recordAdditional) {
                                MultiNoteInput(label = "Additional context for this step", value = step.notes, resetKey = step.key) { step.notes = it }
                            }
                        },
                        onMessage = onError,
                        onError = onError
                    )
                }
            }
        }

        Box {
            OutlinedButton(onClick = { addMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add Another Step")
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Sequential") },
                    onClick = { steps.add(ProcessStepUi(java.util.UUID.randomUUID().toString(), null, "", "SEQUENTIAL")); addMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Group of activities") },
                    onClick = { steps.add(ProcessStepUi(java.util.UUID.randomUUID().toString(), null, "", "GROUP")); addMenu = false }
                )
            }
        }

        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }

        // Statutory warning per the documentation guidelines.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A2520), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(
                "Note: Different users may contribute to processes created by others. Even when documenting the same process, it is recommended that each researcher documents it individually, so that different perspectives on the same process are preserved.",
                color = Color(0xFFE0C9B0),
                fontSize = 12.sp
            )
        }

        SaveButton(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update process" else "Save process"
        ) { submit() }
    }
}

// ===========================================================================
// View Data — read-only browser for any record type, with transcripts
// ===========================================================================

private val viewDataModes = listOf(
    EntryMode.ARTISAN, EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.TOOL,
    EntryMode.QUESTIONNAIRE, EntryMode.WORKSHOP, EntryMode.CRAFT, EntryMode.MEDIA
)

private fun EntryMode.linkedRecordType(): String = when (this) {
    EntryMode.ARTISAN -> "artisan"
    EntryMode.PRODUCT -> "product"
    EntryMode.TOOL -> "tool"
    EntryMode.WORKSHOP -> "workshop"
    EntryMode.CRAFT -> "craft"
    EntryMode.QUESTIONNAIRE -> "questionnaire"
    EntryMode.PROCESS -> "process"
    else -> name.lowercase()
}

/**
 * Key that collapses all interviews for the SAME set of artisan(s) into one logical record, so the
 * questionnaire dropdowns (browse AND update) are idempotent — multiple saved interview records for
 * the same artisan(s) show once. An interview with no linked artisans stays unique (keyed by its own
 * id) so unrelated artisan-less interviews never merge together.
 */
private fun interviewGroupKey(iv: QuestionnaireInterviewDetailDto): String {
    val ids = iv.artisans.map { it.artisanId }.toSortedSet()
    return if (ids.isEmpty()) "iv:${iv.id}" else "set:${ids.joinToString(",")}"
}

/** The representative (most recently created) interview that a merged entry opens / edits. */
private fun representativeInterview(group: List<QuestionnaireInterviewDetailDto>): QuestionnaireInterviewDetailDto =
    group.maxByOrNull { it.createdAt ?: "" } ?: group.first()

private suspend fun loadViewEntries(repository: FieldRepository, mode: EntryMode): List<Pair<String, String>> = when (mode) {
    // Every list is ordered most-recent-first (createdAt desc; ISO timestamps sort lexically).
    EntryMode.ARTISAN -> repository.artisans().sortedByDescending { it.createdAt ?: "" }.map { it.id to "${it.name} · ${it.place}" }
    EntryMode.CRAFT -> repository.crafts().sortedByDescending { it.createdAt ?: "" }.map { it.id to (it.name + (it.place?.let { p -> " · $p" } ?: "")) }
    EntryMode.PRODUCT -> repository.products().sortedByDescending { it.createdAt ?: "" }.map { it.id to "${it.productName} · ${it.artisanName}" }
    EntryMode.PROCESS -> repository.processes().sortedByDescending { it.createdAt ?: "" }.map { it.id to (it.name + (it.product?.productName?.let { p -> " · $p" } ?: "")) }
    EntryMode.TOOL -> repository.tools().sortedByDescending { it.createdAt ?: "" }.map { it.id to "${it.toolkitName} · ${it.artisanName}" }
    EntryMode.WORKSHOP -> repository.workshops().sortedByDescending { it.createdAt ?: "" }.map { it.id to it.title.ifBlank { "Untitled workshop" } }
    EntryMode.QUESTIONNAIRE -> repository.interviews().sortedByDescending { it.createdAt ?: "" }.map { it.id to it.title.ifBlank { "Untitled interview" } }
    EntryMode.MEDIA -> repository.mediaList().sortedByDescending { it.createdAt ?: "" }.map { m ->
        val tag = m.linkedRecordType?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        m.id to (m.originalFilename.ifBlank { "Media" } + " · " + listOfNotNull(m.mediaType, tag).joinToString(" · "))
    }
    else -> emptyList()
}

// ===========================================================================
// My Activity — every record the current user created, most recent first
// ===========================================================================

private data class ActivityItem(
    val mode: EntryMode,
    val id: String,
    val title: String,
    val subtitle: String,
    val createdAt: String?
)

/** Gather the current user's own records across every type, newest first (ISO timestamps sort lexically). */
private suspend fun loadMyActivity(repository: FieldRepository, userId: String): List<ActivityItem> {
    val items = mutableListOf<ActivityItem>()
    fun mine(createdById: String?) = createdById != null && createdById == userId
    runCatching { repository.artisans() }.getOrDefault(emptyList()).filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.ARTISAN, it.id, it.name, "Artisan · ${it.place}", it.createdAt)) }
    runCatching { repository.products() }.getOrDefault(emptyList()).filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.PRODUCT, it.id, it.productName, "Product · ${it.craftName}", it.createdAt)) }
    runCatching { repository.tools() }.getOrDefault(emptyList()).filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.TOOL, it.id, it.toolkitName, "Tool · ${it.craftName}", it.createdAt)) }
    runCatching { repository.processes() }.getOrDefault(emptyList()).filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.PROCESS, it.id, it.name, "Process" + (it.product?.productName?.let { p -> " · $p" } ?: ""), it.createdAt)) }
    runCatching { repository.crafts() }.getOrDefault(emptyList()).filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.CRAFT, it.id, it.name, "Craft", it.createdAt)) }
    runCatching { repository.workshops() }.getOrDefault(emptyList()).filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.WORKSHOP, it.id, it.title.ifBlank { "Untitled workshop" }, "Workshop", it.createdAt)) }
    runCatching { repository.interviews() }.getOrDefault(emptyList()).filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.QUESTIONNAIRE, it.id, it.title.ifBlank { "Untitled interview" }, "Interview", it.createdAt)) }
    return items.sortedByDescending { it.createdAt ?: "" }
}

@Composable
private fun MyActivityScreen(
    repository: FieldRepository,
    userId: String,
    onOpen: (EntryMode, String) -> Unit,
    onError: (String) -> Unit
) {
    var items by remember { mutableStateOf<List<ActivityItem>?>(null) }
    LaunchedEffect(Unit) {
        runCatching { loadMyActivity(repository, userId) }
            .onSuccess { items = it }
            .onFailure { onError(it.message ?: "Couldn't load your activity"); items = emptyList() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Everything you've recorded, most recent first. Tap an entry to open it.", color = Muted, fontSize = 13.sp)
        val current = items
        when {
            current == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Loading your activity…", color = Muted, fontSize = 13.sp)
            }
            current.isEmpty() -> Text(
                "You haven't recorded anything yet. Create a record from the menu and it will appear here.",
                color = Muted,
                fontSize = 13.sp
            )
            else -> current.forEach { item ->
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(item.mode, item.id) }
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.title, display = true, color = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            item.subtitle + (formatIsoDate(item.createdAt)?.let { " · $it" } ?: ""),
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

// ===========================================================================
// Assign tools to artisans — map one documented tool to many artisans
// ===========================================================================

@Composable
private fun ToolAssignScreen(
    repository: FieldRepository,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var tools by remember { mutableStateOf<List<ToolDetailDto>>(emptyList()) }
    var crafts by remember { mutableStateOf<List<CraftDto>>(emptyList()) }
    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var toolId by remember { mutableStateOf("") }
    var craftIds by remember { mutableStateOf(setOf<String>()) }
    var artisanIds by remember { mutableStateOf(setOf<String>()) }
    var assigned by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { repository.tools() }.onSuccess { tools = it }.onFailure { onError(it.message ?: "Failed to load tools") }
        runCatching { repository.crafts() }.onSuccess { crafts = it }
        runCatching { repository.artisans() }.onSuccess { artisans = it }
    }
    LaunchedEffect(toolId) {
        if (toolId.isBlank()) { assigned = emptyList(); return@LaunchedEffect }
        runCatching { repository.toolArtisans(toolId) }.onSuccess { assigned = it }.onFailure { assigned = emptyList() }
    }

    val artisansForCrafts = remember(artisans, craftIds) {
        artisans.filter { it.craftId != null && craftIds.contains(it.craftId) }
    }
    // Keep the artisan selection within the chosen crafts.
    LaunchedEffect(artisansForCrafts) {
        artisanIds = artisanIds.filter { id -> artisansForCrafts.any { it.id == id } }.toSet()
    }

    RecordCard(title = "Assign a tool to multiple artisans") {
        Text(
            "Map one documented tool to several artisans — across the same or different crafts — instead of re-entering the same tool for each craft.",
            color = Muted,
            fontSize = 12.sp
        )
        DropdownField(
            label = "Tool *",
            options = tools.map { it.id to "${it.toolkitName} · ${it.craftName}" },
            selectedValue = toolId,
            placeholder = "Select a tool",
            includeNone = false
        ) { toolId = it }
        CheckboxMultiSelectField(
            label = "Crafts",
            options = crafts.map { it.id to it.name },
            selectedIds = craftIds,
            emptyMessage = "No crafts available.",
            onToggle = { id -> craftIds = if (craftIds.contains(id)) craftIds - id else craftIds + id }
        )
        CheckboxMultiSelectField(
            label = "Artisans of selected crafts",
            options = artisansForCrafts.map { it.id to "${it.name} · ${it.place}" },
            selectedIds = artisanIds,
            emptyMessage = if (craftIds.isEmpty()) "Select one or more crafts first." else "No artisans for the selected crafts.",
            onToggle = { id -> artisanIds = if (artisanIds.contains(id)) artisanIds - id else artisanIds + id }
        )
        info?.let { Text(it, color = Coral, fontSize = 12.sp) }
        Button(
            onClick = {
                info = null
                if (toolId.isBlank() || artisanIds.isEmpty()) { onError("Pick a tool and at least one artisan."); return@Button }
                scope.launch {
                    saving = true
                    runCatching { repository.assignToolArtisans(toolId, artisanIds.toList()) }
                        .onSuccess { result -> assigned = result; artisanIds = emptySet(); info = "Done. This tool now maps to ${result.size} artisan(s)." }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; onError(it.message ?: "Assignment failed") }
                    saving = false
                }
            },
            enabled = !saving && toolId.isNotBlank() && artisanIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saving) "Assigning…" else "Assign tool to ${artisanIds.size} artisan(s)") }

        if (toolId.isNotBlank()) {
            HorizontalDivider()
            Text("Currently assigned to", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            if (assigned.isEmpty()) {
                Text("Not assigned to any additional artisans yet.", color = Muted, fontSize = 12.sp)
            } else {
                assigned.forEach { artisan ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${artisan.name}${artisan.craft?.name?.let { " · $it" } ?: ""}",
                            color = Body,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { repository.unassignToolArtisan(toolId, artisan.id) }
                                    .onSuccess { assigned = assigned.filter { it.id != artisan.id } }
                                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; onError(it.message ?: "Could not remove") }
                            }
                        }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (!value.isNullOrBlank()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.width(120.dp))
            Text(value, color = Body, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
    }
}

/** A saved media row, its upload provenance, plus the transcript (or a live "transcribing" spinner). */
@Composable
private fun MediaWithTranscript(context: Context, media: MediaFileDto, repository: FieldRepository? = null) {
    AndroidSavedMediaPreview(context = context, media = media)
    // Upload provenance: who added this media file and when.
    val uploader = media.uploadedBy?.name
    val uploadedWhen = formatIsoDate(media.createdAt)
    if (uploader != null || uploadedWhen != null) {
        Text(
            "Uploaded by ${uploader ?: "Unknown"}" + (uploadedWhen?.let { " · $it" } ?: ""),
            color = Muted,
            fontSize = 11.sp
        )
    }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val isAudio = media.mediaType.equals("AUDIO", ignoreCase = true)
    val processing = setOf("QUEUED", "PROCESSING", "PENDING", "RUNNING")
    val done = setOf("COMPLETED", "EMPTY", "DONE")
    // The transcript is hoisted into local state so that approving an AI-refined version (or a fresh
    // "Transcribe now") replaces the shown transcript immediately.
    var transcriptText by remember(media.id) { mutableStateOf(media.transcriptText) }
    var liveStatus by remember(media.id) { mutableStateOf(media.transcriptStatus) }
    var transcribing by remember(media.id) { mutableStateOf(false) }
    val status = liveStatus?.uppercase()
    // Admins/master admins can transcribe (or re-transcribe) on the spot, applying the settings-page mode.
    val isAdmin = repository?.cachedUser()?.role in setOf("ADMIN", "MASTER_ADMIN")

    fun transcribeNow() {
        val repo = repository ?: return
        transcribing = true
        liveStatus = "PROCESSING"
        scope.launch {
            runCatching { repo.transcribeNow(media.id) }
                .onSuccess { updated ->
                    transcriptText = updated.transcriptText
                    liveStatus = updated.transcriptStatus ?: "COMPLETED"
                }
                .onFailure { liveStatus = media.transcriptStatus }
            transcribing = false
        }
    }

    when {
        !transcriptText.isNullOrBlank() -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Transcript", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    // Copy the transcript to the clipboard — available to everyone.
                    TextButton(onClick = { clipboard.setText(AnnotatedString(transcriptText!!)) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy transcript", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", fontSize = 12.sp)
                    }
                }
                // Render with the Markdown renderer so an approved (refined) transcript keeps its
                // formatting/section breaks; a plain raw transcript is unaffected.
                MarkdownText(markdown = transcriptText!!, color = Body)
            }
            // AI refinement controls under the transcript (only where a repository is available, i.e.
            // the record/update & view screens). Turns the raw transcript into a clean conversation,
            // optionally translated to English. Both actions are billable, so they're gated behind a
            // one-time cost confirmation. Approving a refined version saves it in place via onApplied.
            if (repository != null) {
                TranscriptRefineControls(
                    context = context,
                    media = media,
                    repository = repository,
                    onApplied = { newText -> transcriptText = newText }
                )
            }
            if (isAdmin && isAudio && repository != null) {
                TranscribeNowButton(transcribing = transcribing, hasExisting = true) { transcribeNow() }
            }
        }
        transcribing || (isAudio && (status == null || status in processing)) -> {
            // Still processing — show a buffer icon; the transcript appears here once it's ready.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(if (transcribing) "Transcribing now…" else "Transcribing audio…", color = Muted, fontSize = 11.sp)
            }
        }
        isAudio && status in done -> {
            Text("Transcript complete — no speech detected.", color = Muted, fontSize = 11.sp)
            if (isAdmin && repository != null) {
                TranscribeNowButton(transcribing = transcribing, hasExisting = false) { transcribeNow() }
            }
        }
        isAudio -> {
            Text(
                "Transcript: ${liveStatus ?: "—"}" + (media.transcriptError?.let { " — $it" } ?: ""),
                color = Muted,
                fontSize = 11.sp
            )
            if (isAdmin && repository != null) {
                TranscribeNowButton(transcribing = transcribing, hasExisting = false) { transcribeNow() }
            }
        }
    }
}

/** Admin "Transcribe now" / "Re-transcribe" action — runs transcription on the spot via the settings mode. */
@Composable
private fun TranscribeNowButton(transcribing: Boolean, hasExisting: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !transcribing) {
        Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (transcribing) "Transcribing…" else if (hasExisting) "Re-transcribe now" else "Transcribe now", fontSize = 13.sp)
    }
}

/**
 * Everything recorded against one artisan in the questionnaire: the answered Q&A, plus the recordings
 * and notes from every interview the artisan belongs to (alone, in a subset, or in a larger set). A
 * group recording therefore surfaces here for this artisan so it can be validated individually.
 */
@Composable
private fun ArtisanQuestionnaireData(repository: FieldRepository, artisanId: String) {
    val context = LocalContext.current
    var data by remember(artisanId) { mutableStateOf<ArtisanQuestionnaireDto?>(null) }
    var loading by remember(artisanId) { mutableStateOf(true) }
    LaunchedEffect(artisanId) {
        runCatching { repository.artisanQuestionnaire(artisanId) }.onSuccess { data = it }
        loading = false
    }
    val loaded = data
    ArtisanQuestionnairePanel(answers = loaded?.answered ?: emptyList(), loading = loading)
    val interviews = loaded?.interviews.orEmpty().filter { it.media.isNotEmpty() || !it.notes.isNullOrBlank() }
    if (interviews.isNotEmpty()) {
        RecordCard(title = "Questionnaire recordings", icon = Icons.Filled.Quiz) {
            Text(
                "Recordings and notes from every interview this artisan is part of — including ones recorded with others.",
                color = Muted,
                fontSize = 12.sp
            )
            interviews.forEach { interview ->
                HorizontalDivider()
                Text(interview.title.ifBlank { "Interview" }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                val meta = listOfNotNull(
                    formatIsoDate(interview.interviewDate),
                    if (interview.coArtisans.isNotEmpty()) "with ${interview.coArtisans.joinToString(", ")}" else null
                ).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = Muted, fontSize = 11.sp)
                interview.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = Body, fontSize = 12.sp) }
                if (interview.media.isEmpty()) Text("No recordings.", color = Muted, fontSize = 12.sp)
                interview.media.forEach { MediaWithTranscript(context, it, repository) }
            }
        }
    }
}

// SharedPreferences holding the "don't remind me again" acknowledgement for AI-cost prompts, and
// whether the user has seen (or skipped) the first-run walkthrough.
private const val APP_PREFS_NAME = "fieldrepo_prefs"
private const val PREF_AI_COST_ACK = "ai_refine_cost_ack"
private const val PREF_WALKTHROUGH_SEEN = "walkthrough_seen"

private fun aiCostReminderSuppressed(context: Context): Boolean =
    context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_AI_COST_ACK, false)

private fun suppressAiCostReminder(context: Context) {
    context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_AI_COST_ACK, true).apply()
}

/** True once the user has finished or skipped the walkthrough at least once on this device. */
private fun walkthroughSeen(context: Context): Boolean =
    context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_WALKTHROUGH_SEEN, false)

private fun markWalkthroughSeen(context: Context) {
    context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_WALKTHROUGH_SEEN, true).apply()
}

/** One step of the in-app walkthrough: a heading and a short description of a feature area. */
private data class WalkStep(val title: String, val body: String)

/**
 * The walkthrough, which is the WEB GUIDE's ten steps rather than a tour of this app's buttons.
 *
 * What it used to be — dashboard, menu, forms, attach media, the grid — described the interface. But
 * a researcher opening this app for the first time does not need to be told what a menu is; they need
 * to know that a workshop comes before a craft, that a craft comes before an artisan, and that a
 * process hangs off a product. That ordering is the actual thing to learn, it is what /guide teaches
 * on the web, and getting it wrong in the field costs a return trip.
 *
 * The wording is lifted from frontend/components/guide/steps.ts on purpose: the two apps are one
 * product, a researcher moves between them mid-workshop, and a step that is worded differently in
 * each reads as two different instructions.
 */
private val walkthroughSteps = listOf(
    WalkStep(
        "Ten steps, in this order",
        "This is the documentation process the whole repository is built around, and it is the same ten steps in the same order on the web. Work down it once and you will not need the guide again. You can leave at any point and reopen this from the menu."
    ),
    WalkStep(
        "1. Workshop \u00b7 Record workshop",
        "Open the workshop you are documenting under — or create it — before you record anything " +
            "else. Every record you make is scoped to a workshop. Products, tools and interviews all " +
            "carry a linked workshop, and the Data Browser opens on \"By workshop\", which files the " +
            "whole repository under the workshop it was recorded in. On a create form the most recent " +
            "workshop you have access to is preselected, so getting this right once saves you picking " +
            "it on every screen afterwards. Watch out: Create the workshop before you leave for the " +
            "field — it is the container everything else drops into."
    ),
    WalkStep(
        "2. Craft \u00b7 Add craft",
        "Add the craft being documented so artisans, products and tools have something to hang " +
            "off. Craft is the shared vocabulary of the repository: artisans link to a craft, " +
            "products and tools inherit the craft name from it, and the Data Browser groups every " +
            "workshop's contents by craft. Adding it once keeps spellings consistent across " +
            "everyone's records. Watch out: Check the list first — if the craft already exists, reuse " +
            "it instead of creating a near-duplicate spelling."
    ),
    WalkStep(
        "3. Artisan \u00b7 Record artisan",
        "Record the person: who they are, where they work, how to reach them, and what they have " +
            "learnt. The artisan is the anchor of the dataset. Products, processes, tools and " +
            "questionnaire interviews all link back to an artisan record, and the Do's and Don'ts are " +
            "the artisan's own hard-won craft knowledge — the part of the archive that cannot be " +
            "reconstructed later. Watch out: Do's and Don'ts are required. Press Enter for each new " +
            "point — one lesson per line."
    ),
    WalkStep(
        "4. Product \u00b7 Record product",
        "Record one thing this artisan makes, with its measurements, economics and photographs. " +
            "The product record is where the craft becomes measurable: dimensions, cost of making, " +
            "selling price and market demand are the fields researchers compare across regions. Link " +
            "it to the artisan and the craft and the whole chain stays navigable. Watch out: Pick the " +
            "linked craft first — the artisan dropdown stays disabled until a craft is chosen, then " +
            "only lists that craft's artisans."
    ),
    WalkStep(
        "5. Process \u00b7 Document process",
        "Walk through how that product is made, one step at a time, filming each step as it " +
            "happens. The process is the craft itself. A product photograph shows the result; the " +
            "step-by-step record with per-step media shows the knowledge — the sequence, the hand " +
            "movements, the judgement calls that a text description always loses. Watch out: Add a " +
            "step with \"Add Another Step\" and pick Sequential for an ordered stage, or Group of " +
            "activities for things done together."
    ),
    WalkStep(
        "6. Tool \u00b7 Record tool",
        "Record the toolkit the artisan uses: what it is made of, how big it is, who made it, " +
            "what it costs to replace. Tools are the most quietly endangered part of a craft — the " +
            "maker of a tool often disappears before the craft does. Replacement cost, maker and " +
            "tradition type are the fields that record whether the toolchain behind the craft is " +
            "still alive. Watch out: Fill only the dimensions that make sense for the tool — a blade " +
            "has a length and thickness, a wheel has a radius."
    ),
    WalkStep(
        "7. Questionnaire \u00b7 Take interview",
        "Sit down with the artisan and work through the interview sections, recording each answer " +
            "as audio. The questionnaire is the artisan speaking in their own voice and their own " +
            "language. Recorded audio is auto-transcribed on the server, so you get both the original " +
            "recording and searchable text without typing during the interview. Watch out: There is " +
            "one interview per exact set of artisans. If an entry already exists for that set, saving " +
            "adds your answers to it — it never creates a duplicate."
    ),
    WalkStep(
        "8. Miscellaneous Media \u00b7 Upload media",
        "Upload the photographs, video, audio and files that do not belong to any single record. " +
            "Field work produces context that no form has a slot for: the road into the village, the " +
            "market, an unplanned conversation. Miscellaneous Media keeps that material inside the " +
            "repository instead of on a phone that gets wiped. Watch out: Upload stays disabled until " +
            "you pick a Linked record type. If the file belongs to nothing in particular, pick " +
            "\"Miscellaneous Media\" and leave the entry blank."
    ),
    WalkStep(
        "9. Review \u00b7 Track your submissions",
        "Everything you submit goes into the review queue and comes back Approved, Rejected, or " +
            "Sent for revision. Review is what turns a pile of field notes into a dataset anyone can " +
            "cite. It also means you are never the last check on your own work — a reviewer above " +
            "your tier reads every record before it counts as final. Watch out: Below Professor the " +
            "status chip is locked: whatever you create is submitted as Pending. That is normal, not " +
            "an error."
    ),
    WalkStep(
        "10. View Data \u00b7 Browse records",
        "Browse the whole repository as a directory tree and export a report of any subtree. This " +
            "is where the documentation stops being data entry and starts being research material: " +
            "the same records, filed three different ways, previewable in place and downloadable as a " +
            "spreadsheet. Watch out: Pick a folder, then use the breadcrumb to move back up — the " +
            "tree loads lazily as you expand it."
    ),
    WalkStep(
        "Before you leave the field",
        "A missing field is a phone call; a missing recording is another trip. Every artisan you " +
            "spoke to has a record with Do's and Don'ts. Every product you photographed has its " +
            "dimensions and its costs. Every process has its steps in order, and the steps have " +
            "video. Every tool has a material, a maker and a replacement cost. The questionnaire's " +
            "completion matrix has no unexplained gaps. Anything you shot that has no home is in " +
            "Miscellaneous Media."
    )
)

/**
 * First-run (and on-demand) walkthrough: a stepped guide across the app's features with Back / Next /
 * Done and a Skip. New sign-ups see it automatically; everyone can reopen it from the menu. Dismissing
 * (Skip, Done, back, or tap-outside) marks it seen so it doesn't reappear on every launch.
 */
@Composable
private fun WalkthroughDialog(onDismiss: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    val steps = walkthroughSteps
    val current = steps[step]
    val isLast = step == steps.lastIndex
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Walkthrough · ${step + 1}/${steps.size}", color = Muted, fontSize = 11.sp)
                Text(current.title, display = true, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        },
        text = { Text(current.body, fontSize = 14.sp, color = Body) },
        confirmButton = {
            TextButton(onClick = { if (isLast) onDismiss() else step++ }) {
                Text(if (isLast) "Done" else "Next")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
                TextButton(onClick = onDismiss) { Text("Skip") }
            }
        }
    )
}

/**
 * "Refine transcript" / "Refine & translate to English" buttons + the cost-confirmation dialog,
 * spinner, and the Markdown-rendered result. Calls the gpt-4o-mini backed endpoint on demand. The
 * refined conversation is held in local state (not persisted) so it shows immediately and a re-press
 * is the only thing that incurs another cost.
 */
@Composable
private fun TranscriptRefineControls(
    context: Context,
    media: MediaFileDto,
    repository: FieldRepository,
    onApplied: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var refining by remember(media.id) { mutableStateOf(false) }
    var refined by remember(media.id) { mutableStateOf<String?>(null) }
    var refinedTranslated by remember(media.id) { mutableStateOf(false) }
    var refineError by remember(media.id) { mutableStateOf<String?>(null) }
    // Approval state: persisting the refined transcript, plus a one-time "saved" confirmation.
    var applying by remember(media.id) { mutableStateOf(false) }
    var appliedNote by remember(media.id) { mutableStateOf<String?>(null) }
    // When non-null, the cost dialog is open for that mode (false = refine, true = refine+translate).
    var pendingTranslate by remember { mutableStateOf<Boolean?>(null) }
    var dontRemind by remember { mutableStateOf(false) }
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)

    fun runRefine(translate: Boolean) {
        scope.launch {
            refining = true
            refineError = null
            runCatching { repository.refineTranscript(media.id, translate) }
                .onSuccess { resp ->
                    when {
                        !resp.refined.isNullOrBlank() -> { refined = resp.refined; refinedTranslated = translate }
                        resp.available == false -> refineError = resp.message ?: "AI refinement is not configured."
                        else -> refineError = resp.message ?: "No transcript content to refine."
                    }
                }
                .onFailure {
                    if (it !is kotlinx.coroutines.CancellationException) refineError = it.message ?: "Couldn't refine the transcript."
                }
            refining = false
        }
    }

    fun onRefineClick(translate: Boolean) {
        if (aiCostReminderSuppressed(context)) runRefine(translate) else pendingTranslate = translate
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { onRefineClick(false) }, enabled = !refining, modifier = Modifier.weight(1f), contentPadding = pad) {
            Text("Refine transcript", maxLines = 1, softWrap = false, fontSize = 12.sp)
        }
        OutlinedButton(onClick = { onRefineClick(true) }, enabled = !refining, modifier = Modifier.weight(1f), contentPadding = pad) {
            Text("Refine & translate", maxLines = 1, softWrap = false, fontSize = 12.sp)
        }
    }
    if (refining) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("Refining with AI…", color = Muted, fontSize = 11.sp)
        }
    }
    refineError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
    refined?.let { md ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ColorCompat.darkElevated, RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                if (refinedTranslated) "Refined conversation (English)" else "Refined conversation",
                color = Coral,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            MarkdownText(markdown = md, color = Canvas)
            Text(
                "Save this refined version in place of the current transcript?",
                color = SurfaceCard,
                fontSize = 11.sp
            )
            // Approve = persist the refined text as the transcript (uploader/admin only on the server);
            // Reject = just discard this preview, leaving the stored transcript untouched.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        scope.launch {
                            applying = true
                            refineError = null
                            runCatching { repository.applyTranscript(media.id, md) }
                                .onSuccess {
                                    onApplied(md)
                                    appliedNote = "Saved as the transcript ✓"
                                    refined = null
                                }
                                .onFailure {
                                    if (it !is kotlinx.coroutines.CancellationException)
                                        refineError = it.message ?: "Couldn't save the refined transcript."
                                }
                            applying = false
                        }
                    },
                    enabled = !applying,
                    modifier = Modifier.weight(1f),
                    contentPadding = pad
                ) { Text(if (applying) "Saving…" else "Approve", maxLines = 1, softWrap = false, fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { refined = null },
                    enabled = !applying,
                    modifier = Modifier.weight(1f),
                    contentPadding = pad
                ) { Text("Reject", maxLines = 1, softWrap = false, fontSize = 12.sp) }
            }
        }
    }
    appliedNote?.let { Text(it, color = SuccessGreen, fontSize = 11.sp) }

    val mode = pendingTranslate
    if (mode != null) {
        AlertDialog(
            onDismissRequest = { pendingTranslate = null },
            title = { Text("This uses AI and costs extra") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Refining" + (if (mode) " and translating" else "") + " this transcript runs it through an " +
                            "AI model (gpt-4o-mini), which incurs a small extra cost each time you do it. Continue?"
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dontRemind, onCheckedChange = { dontRemind = it })
                        Text("Do not remind me again", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontRemind) suppressAiCostReminder(context)
                    pendingTranslate = null
                    runRefine(mode)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { pendingTranslate = null }) { Text("Cancel") } }
        )
    }
}

/**
 * Minimal Markdown -> rich text renderer for the refined conversation: handles `#`/`##`/`###`
 * headings, `-`/`*` bullets, blank-line spacing, and inline `**bold**` / `*italic*`. Enough to make
 * the interviewer/interviewee dialogue read cleanly without pulling in a Markdown dependency.
 */
@Composable
private fun MarkdownText(markdown: String, color: Color = Body) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        markdown.replace("\r\n", "\n").split("\n").forEach { raw ->
            val line = raw.trim()
            // A Markdown horizontal rule (---, ***, ___, three or more) becomes a long section-break line.
            val isRule = line.length >= 3 && (line.all { it == '-' } || line.all { it == '*' } || line.all { it == '_' })
            when {
                line.isEmpty() -> Spacer(Modifier.height(2.dp))
                isRule -> HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Muted)
                line.startsWith("### ") -> Text(parseInlineMarkdown(line.removePrefix("### ")), display = true, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                line.startsWith("## ") -> Text(parseInlineMarkdown(line.removePrefix("## ")), display = true, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                line.startsWith("# ") -> Text(parseInlineMarkdown(line.removePrefix("# ")), display = true, color = color, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                line.startsWith("- ") || line.startsWith("* ") -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text("•  ", color = color, fontSize = 13.sp)
                    Text(parseInlineMarkdown(line.drop(2)), color = color, fontSize = 13.sp)
                }
                else -> Text(parseInlineMarkdown(line), color = color, fontSize = 13.sp)
            }
        }
    }
}

/** Parse inline `**bold**` and `*italic*` spans of a single Markdown line into an AnnotatedString. */
private fun parseInlineMarkdown(text: String) = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

/** Loads and renders all media attached to a record (by linkedRecordType/Id) with transcripts. */
@Composable
private fun RecordMediaSection(
    repository: FieldRepository,
    context: Context,
    linkedType: String,
    recordId: String,
    onError: (String) -> Unit
) {
    var media by remember(linkedType, recordId) { mutableStateOf<List<MediaFileDto>>(emptyList()) }
    var loading by remember(linkedType, recordId) { mutableStateOf(true) }
    LaunchedEffect(linkedType, recordId) {
        loading = true
        runCatching { repository.mediaForRecord(linkedType, recordId) }
            .onSuccess { media = it }
            .onFailure { onError(it.message ?: "Unable to load media") }
        loading = false
    }
    HorizontalDivider()
    Text("Media & transcripts", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    when {
        loading -> Text("Loading media…", color = Muted, fontSize = 12.sp)
        media.isEmpty() -> Text("No media attached.", color = Muted, fontSize = 12.sp)
        else -> media.forEach { MediaWithTranscript(context, it, repository) }
    }
}

private val StarGold = Color(0xFFF5B301)

/** Tappable 1–5 star input for a quantitative rating (0 = not yet rated). */
@Composable
private fun StarRatingInput(rating: Int, max: Int = 5, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..max).forEach { i ->
            IconButton(onClick = { onChange(if (rating == i) 0 else i) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (i <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "$i star${if (i == 1) "" else "s"}",
                    tint = if (i <= rating) StarGold else Muted,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

/** Read-only star display for a saved rating. */
@Composable
private fun StarRatingDisplay(rating: Int, max: Int = 5) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..max).forEach { i ->
            Icon(
                imageVector = if (i <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = null,
                tint = if (i <= rating) StarGold else Muted,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("$rating / $max", color = Muted, fontSize = 12.sp)
    }
}

/** A labelled 1–5 star input row used for each quantitative aspect on the feedback form. */
@Composable
private fun LabeledStarRating(label: String, rating: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        StarRatingInput(rating = rating, onChange = onChange)
    }
}

/** A labelled read-only star row used to show a saved aspect rating to the master admin. */
@Composable
private fun LabeledRatingDisplay(label: String, rating: Int?) {
    if (rating == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        StarRatingDisplay(rating)
    }
}

/** A labelled qualitative answer shown to the master admin (skipped when the user left it blank). */
@Composable
private fun QualitativeRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = Body, fontSize = 13.sp)
    }
}

/**
 * Hamburger-menu screen where any user gives — and later updates — their own detailed feedback on the
 * app: an overall rating plus per-aspect quantitative star ratings (ease of use, reliability,
 * performance, design, features, recommend) and several qualitative prompts (role, what they like,
 * what to improve, bugs, feature requests, and a general comment). Seeded with their last submission.
 */
@Composable
private fun FeedbackScreen(repository: FieldRepository, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    // Quantitative (0 = not yet rated).
    var rating by remember { mutableStateOf(0) }
    var easeOfUse by remember { mutableStateOf(0) }
    var reliability by remember { mutableStateOf(0) }
    var performance by remember { mutableStateOf(0) }
    var design by remember { mutableStateOf(0) }
    var features by remember { mutableStateOf(0) }
    var recommend by remember { mutableStateOf(0) }
    // Qualitative.
    var role by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var likeMost by remember { mutableStateOf("") }
    var improve by remember { mutableStateOf("") }
    var bugs by remember { mutableStateOf("") }
    var featureRequests by remember { mutableStateOf("") }

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<FeedbackDto?>(null) }
    var status by remember { mutableStateOf(ActionStatus.IDLE) }
    AutoResetStatus(status) { status = ActionStatus.IDLE }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { repository.myFeedback() }
            .onSuccess { fb ->
                if (fb.id.isNotBlank()) {
                    existing = fb
                    rating = fb.rating ?: 0
                    easeOfUse = fb.easeOfUse ?: 0
                    reliability = fb.reliability ?: 0
                    performance = fb.performance ?: 0
                    design = fb.design ?: 0
                    features = fb.features ?: 0
                    recommend = fb.recommend ?: 0
                    role = fb.role.orEmpty()
                    comment = fb.comment.orEmpty()
                    likeMost = fb.likeMost.orEmpty()
                    improve = fb.improve.orEmpty()
                    bugs = fb.bugs.orEmpty()
                    featureRequests = fb.featureRequests.orEmpty()
                }
            }
            .onFailure { onError(it.message ?: "Unable to load your feedback") }
        loading = false
    }

    RecordCard(title = "App feedback") {
        Text(
            "Tell us how the app is working for you — rate it on a few aspects and add anything you'd " +
                "like in your own words. Everything is optional; fill in what's relevant. You can come " +
                "back and update this at any time.",
            color = Muted,
            fontSize = 12.sp
        )
        if (loading) {
            Text("Loading your feedback…", color = Muted, fontSize = 12.sp)
        } else {
            // ---- Quantitative ----
            Text("Ratings", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("Overall rating", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
            StarRatingInput(rating = rating) { rating = it }
            Text(
                when (rating) {
                    0 -> "Tap a star to rate"
                    1 -> "Poor"
                    2 -> "Fair"
                    3 -> "Good"
                    4 -> "Very good"
                    else -> "Excellent"
                },
                color = Muted,
                fontSize = 12.sp
            )
            LabeledStarRating("Ease of use", easeOfUse) { easeOfUse = it }
            LabeledStarRating("Reliability / stability", reliability) { reliability = it }
            LabeledStarRating("Speed / performance", performance) { performance = it }
            LabeledStarRating("Design / look & feel", design) { design = it }
            LabeledStarRating("Features / completeness", features) { features = it }
            LabeledStarRating("How likely you'd recommend it", recommend) { recommend = it }

            HorizontalDivider()
            // ---- Qualitative ----
            Text("In your words", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            TextInput("Your role (e.g. researcher, field documenter)", role) { role = it }
            TextInput("What do you like most?", likeMost, minLines = 2) { likeMost = it }
            TextInput("What should we improve?", improve, minLines = 2) { improve = it }
            TextInput("Any bugs or issues you hit?", bugs, minLines = 2) { bugs = it }
            TextInput("Features you'd like to see", featureRequests, minLines = 2) { featureRequests = it }
            TextInput("Anything else (general comments)", comment, minLines = 3) { comment = it }

            val anyProvided = rating > 0 || easeOfUse > 0 || reliability > 0 || performance > 0 ||
                design > 0 || features > 0 || recommend > 0 ||
                listOf(role, comment, likeMost, improve, bugs, featureRequests).any { it.isNotBlank() }
            Button(
                onClick = {
                    if (!anyProvided) {
                        onError("Add at least one rating or a written answer first.")
                        return@Button
                    }
                    scope.launch {
                        saving = true
                        runCatching {
                            repository.upsertMyFeedback(
                                FeedbackUpsertRequest(
                                    rating = rating.takeIf { it > 0 },
                                    easeOfUse = easeOfUse.takeIf { it > 0 },
                                    reliability = reliability.takeIf { it > 0 },
                                    performance = performance.takeIf { it > 0 },
                                    design = design.takeIf { it > 0 },
                                    features = features.takeIf { it > 0 },
                                    recommend = recommend.takeIf { it > 0 },
                                    role = role.blankToNull(),
                                    comment = comment.blankToNull(),
                                    likeMost = likeMost.blankToNull(),
                                    improve = improve.blankToNull(),
                                    bugs = bugs.blankToNull(),
                                    featureRequests = featureRequests.blankToNull()
                                )
                            )
                        }
                            .onSuccess { existing = it; status = ActionStatus.SUCCESS }
                            .onFailure {
                                if (it !is kotlinx.coroutines.CancellationException) {
                                    status = ActionStatus.ERROR
                                    onError(it.message ?: "Unable to save your feedback")
                                }
                            }
                        saving = false
                    }
                },
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (status) {
                        ActionStatus.SUCCESS -> SuccessGreen
                        ActionStatus.ERROR -> FailureRed
                        ActionStatus.IDLE -> MaterialTheme.colorScheme.primary
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        saving -> "Saving…"
                        status == ActionStatus.SUCCESS -> "Saved ✓"
                        existing != null -> "Update feedback"
                        else -> "Submit feedback"
                    }
                )
            }
            existing?.updatedAt?.let { formatIsoDate(it)?.let { d -> Text("Last updated $d", color = Muted, fontSize = 11.sp) } }
        }
    }
}

/**
 * Master-admin-only card on the View Data screen: every user's feedback, grouped in a dropdown
 * sorted by user, with the selected user's quantitative rating and qualitative comment shown below.
 */
@Composable
private fun MasterFeedbackCard(repository: FieldRepository, onError: (String) -> Unit) {
    var feedback by remember { mutableStateOf<List<FeedbackDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedUserId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { repository.allFeedback() }
            .onSuccess { feedback = it }
            .onFailure { onError(it.message ?: "Unable to load feedback") }
        loading = false
    }

    RecordCard(title = "User feedback") {
        Text("Qualitative and quantitative feedback submitted by the team. Pick a user to read theirs.", color = Muted, fontSize = 12.sp)
        when {
            loading -> Text("Loading feedback…", color = Muted, fontSize = 12.sp)
            feedback.isEmpty() -> Text("No feedback submitted yet.", color = Muted, fontSize = 12.sp)
            else -> {
                val avg = feedback.mapNotNull { it.rating }.takeIf { it.isNotEmpty() }?.average()
                Text(
                    "${feedback.size} user(s) gave feedback" + (avg?.let { " · average rating ${"%.1f".format(it)} / 5" } ?: ""),
                    color = Body,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                val options = feedback
                    .sortedBy { (it.user?.name ?: it.userId).lowercase() }
                    .map { fb ->
                        val name = fb.user?.name ?: "Unknown user"
                        fb.userId to (name + (fb.rating?.let { " · $it★" } ?: ""))
                    }
                DropdownField(
                    label = "Feedback by user",
                    options = options,
                    selectedValue = selectedUserId,
                    placeholder = "Select a user",
                    includeNone = false
                ) { selectedUserId = it }
                feedback.firstOrNull { it.userId == selectedUserId }?.let { fb ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceCard, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(fb.user?.name ?: "Unknown user", display = true, color = Body, fontWeight = FontWeight.SemiBold)
                        fb.user?.email?.let { Text(it, color = Muted, fontSize = 11.sp) }
                        if (!fb.role.isNullOrBlank()) Text("Role: ${fb.role}", color = Muted, fontSize = 11.sp)

                        // Quantitative: overall + each aspect that was rated.
                        if (fb.rating != null) {
                            Text("Overall", color = Muted, fontSize = 12.sp)
                            StarRatingDisplay(fb.rating!!)
                        }
                        LabeledRatingDisplay("Ease of use", fb.easeOfUse)
                        LabeledRatingDisplay("Reliability", fb.reliability)
                        LabeledRatingDisplay("Performance", fb.performance)
                        LabeledRatingDisplay("Design", fb.design)
                        LabeledRatingDisplay("Features", fb.features)
                        LabeledRatingDisplay("Would recommend", fb.recommend)

                        // Qualitative: each prompt the user answered.
                        val hasText = listOf(fb.likeMost, fb.improve, fb.bugs, fb.featureRequests, fb.comment).any { !it.isNullOrBlank() }
                        if (hasText) HorizontalDivider()
                        QualitativeRow("Likes most", fb.likeMost)
                        QualitativeRow("To improve", fb.improve)
                        QualitativeRow("Bugs / issues", fb.bugs)
                        QualitativeRow("Feature requests", fb.featureRequests)
                        QualitativeRow("General comments", fb.comment)

                        val nothing = fb.rating == null && fb.easeOfUse == null && fb.reliability == null &&
                            fb.performance == null && fb.design == null && fb.features == null &&
                            fb.recommend == null && !hasText
                        if (nothing) Text("No details provided.", color = Muted, fontSize = 12.sp)
                        formatIsoDate(fb.updatedAt)?.let { Text("Updated $it", color = Muted, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

/**
 * Master-admin-only recovery card on the View Data screen. Lists media whose original record was
 * later deleted — the file itself is safe in object storage, only its link was nulled — so those
 * recordings stay visible (and playable, with transcripts) instead of disappearing. Each can be
 * re-attached to an existing record of its own type, after which it shows under that record again.
 */
@Composable
private fun OrphanRecordingsCard(repository: FieldRepository, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var orphans by remember { mutableStateOf<List<MediaFileDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Re-link target option lists, loaded once per record type present among the orphans.
    var targetOptions by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    val selections = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Recording queued for permanent deletion, awaiting confirmation (destructive: removes the DB row
    // AND the S3 object — the file is gone for good, unlike re-linking which preserves it).
    var pendingDelete by remember { mutableStateOf<MediaFileDto?>(null) }

    suspend fun loadOptionsFor(types: Set<String>) {
        val map = targetOptions.toMutableMap()
        types.forEach { t ->
            if (map.containsKey(t)) return@forEach
            runCatching {
                when (t.lowercase()) {
                    "questionnaire", "questionnaireinterview" -> repository.interviews().map { it.id to it.title.ifBlank { "Untitled interview" } }
                    "product" -> repository.products().map { it.id to "${it.productName} · ${it.artisanName}" }
                    "tool" -> repository.tools().map { it.id to "${it.toolkitName} · ${it.artisanName}" }
                    "artisan" -> repository.artisans().map { it.id to it.name }
                    "craft" -> repository.crafts().map { it.id to it.name }
                    "workshop" -> repository.workshops().map { it.id to it.title.ifBlank { "Untitled workshop" } }
                    else -> emptyList()
                }
            }.onSuccess { map[t] = it }
        }
        targetOptions = map
    }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { repository.orphanedMedia() }
            .onSuccess {
                orphans = it
                loadOptionsFor(it.mapNotNull { m -> m.linkedRecordType }.toSet())
            }
            .onFailure { onError(it.message ?: "Unable to load recovered recordings") }
        loading = false
    }

    RecordCard(title = "Recovered recordings") {
        Text(
            "Recordings & clips whose original record was deleted afterwards. The files are safe in " +
                "storage — play them here, and optionally re-attach each to an existing record so it " +
                "appears under it again.",
            color = Muted,
            fontSize = 12.sp
        )
        when {
            loading -> Text("Loading…", color = Muted, fontSize = 12.sp)
            orphans.isEmpty() -> Text("None — every recording is attached to a live record.", color = Muted, fontSize = 12.sp)
            else -> {
                Text("${orphans.size} recovered file(s)", color = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                orphans.forEach { m ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceCard, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val typeLabel = m.linkedRecordType?.replaceFirstChar { it.uppercase() } ?: "Record"
                        Text("Originally a $typeLabel attachment (that record was deleted)", color = Muted, fontSize = 11.sp)
                        m.uploadedBy?.name?.let { Text("Recorded by $it", color = Muted, fontSize = 11.sp) }
                        MediaWithTranscript(context, m, repository)
                        val opts = targetOptions[m.linkedRecordType].orEmpty()
                        if (opts.isEmpty()) {
                            Text("No existing ${typeLabel.lowercase()} to re-link to.", color = Muted, fontSize = 11.sp)
                        } else {
                            DropdownField(
                                label = "Re-link to a ${typeLabel.lowercase()}",
                                options = opts,
                                selectedValue = selections[m.id].orEmpty(),
                                placeholder = "Select a record",
                                includeNone = false
                            ) { selections[m.id] = it }
                            Button(
                                onClick = {
                                    val target = selections[m.id]
                                    if (target.isNullOrBlank()) { onError("Pick a record to re-link to first."); return@Button }
                                    scope.launch {
                                        busy = busy + m.id
                                        runCatching { repository.relinkMedia(m.id, m.linkedRecordType ?: "", target) }
                                            .onSuccess { orphans = orphans.filterNot { it.id == m.id }; selections.remove(m.id) }
                                            .onFailure { onError(it.message ?: "Unable to re-link this recording") }
                                        busy = busy - m.id
                                    }
                                },
                                enabled = m.id !in busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (m.id in busy) "Re-linking…" else "Re-link this recording") }
                        }
                        // Permanent deletion of a single recovered recording (file + DB row). Guarded by a
                        // confirmation dialog because it is irreversible.
                        OutlinedButton(
                            onClick = { pendingDelete = m },
                            enabled = m.id !in busy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (m.id in busy) "Working…" else "Permanently delete")
                        }
                    }
                }
            }
        }
    }
    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Permanently delete recording?") },
            text = {
                Text(
                    "This removes the file from storage and the database for good. It cannot be undone, " +
                        "and the recording can no longer be re-linked. Delete “${toDelete.originalFilename}”?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            busy = busy + toDelete.id
                            runCatching { repository.deleteMedia(toDelete.id) }
                                .onSuccess { orphans = orphans.filterNot { it.id == toDelete.id }; selections.remove(toDelete.id) }
                                .onFailure { onError(it.message ?: "Unable to delete this recording") }
                            busy = busy - toDelete.id
                        }
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

// Sentinel id for the "Check completion" entry in the questionnaire dropdown (not a real record id).
private const val COMPLETION_OPTION_ID = "__completion__"

/** Entries of the admin "Settings" hub, each opening an existing admin composable/screen. */
private enum class AdminHubEntry(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val masterOnly: Boolean = false,
    val reviewGated: Boolean = false
) {
    REVIEWS("Reviews & approvals", "Approve or reject records the team submitted.", Icons.Filled.ManageAccounts, reviewGated = true),
    // GET /media/orphans and POST /media/{id}/relink are `require_admin`, and the web exposes the
    // same recovery table on /admin to any admin — so this is not master-only.
    RECOVERED("Recovered recordings", "Play & re-link recordings whose record was deleted.", Icons.Filled.RecordVoiceOver),
    FEEDBACK("User feedback", "Everyone's app feedback, grouped by user.", Icons.Filled.RateReview, masterOnly = true),
    TOOLS("Assign tools to artisans", "Link tools to the artisans who use them.", Icons.Filled.Build),
    // Label + description verbatim from ADMIN_LINKS on the web's /settings page.
    TASKS(
        "Task assignment",
        "Hand documentation work to the people below you, then hold it to account.",
        Icons.AutoMirrored.Filled.Assignment
    ),
    WORKSHOPS("Workshop assignments", "Choose who may submit entries for each workshop.", Icons.Filled.Groups),
    ACCESS_REQUESTS("Workshop access requests", "Approve or deny requests to work in a workshop.", Icons.Filled.LockOpen),
    // Every /secrets route is require_master_admin, not require_admin: handing out live provider
    // credentials (reveal returns plaintext) is a different class of power from managing people.
    API_KEYS(
        "API keys",
        "Rotate, test and reveal the provider keys the repository runs on.",
        Icons.Filled.VpnKey,
        masterOnly = true
    ),
    SETTINGS("Settings", "Transcription output and off-peak processing.", Icons.Filled.Tune, masterOnly = true)
}

/**
 * Admin "Settings" hub, opened from the dashboard card. Lists the administrative tools and opens each
 * one in place (reusing the existing composables/screens). Back from a sub-tool returns to the list.
 */
@Composable
private fun AdminHubScreen(
    repository: FieldRepository,
    isMasterAdmin: Boolean,
    canReview: Boolean,
    /**
     * Which tool is open, owned by the HOST rather than by this screen.
     *
     * It used to be local state with its own BackHandler and its own in-page "All admin tools"
     * button, which is how the app ended up with two back controls stacked on one screen — the
     * header's arrow (which left the hub) and a rounded rectangle (which popped the tool). Hoisting
     * it into `Screen.AdminHub.section` lets `goBack()` pop one level, so the arrow already in the
     * header does both jobs and the extra button is gone.
     */
    section: AdminHubEntry? = null,
    onSectionChange: (AdminHubEntry?) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    // Loaded once for the Workshop assignments tool (which needs the researcher directory).
    var directory by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { repository.userDirectory() }.onSuccess { directory = it } }

    val entries = AdminHubEntry.entries.filter { (!it.masterOnly || isMasterAdmin) && (!it.reviewGated || canReview) }
    val current = section

    if (current == null) {
        RecordCard(title = "Admin tools", icon = Icons.Filled.Tune) {
            Text("Administrative tools and settings.", color = Muted, fontSize = 12.sp)
            entries.forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSectionChange(entry) }
                        .background(SurfaceCard, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(color = MaterialTheme.field.brandTile, shape = RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(entry.icon, contentDescription = null, tint = Canvas, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.label, display = true, color = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(entry.description, color = Muted, fontSize = 12.sp)
                    }
                    Text("›", color = Muted, fontSize = 20.sp)
                }
            }
        }
    } else {
        // No back control here on purpose. The screen already has one — the circular arrow in the
        // page header — and `goBack()` now pops the open tool before leaving the hub.
        when (current) {
            AdminHubEntry.REVIEWS -> ReviewApprovalCard(repository = repository, onError = onError)
            AdminHubEntry.RECOVERED -> OrphanRecordingsCard(repository = repository, onError = onError)
            AdminHubEntry.FEEDBACK -> MasterFeedbackCard(repository = repository, onError = onError)
            AdminHubEntry.TOOLS -> ToolAssignScreen(repository = repository, onError = onError)
            // The hub's own "All admin tools" pill is the back control here, so the board's built-in
            // arrow stays off (`onBack = null`).
            AdminHubEntry.TASKS -> TaskAdminScreen(
                repository = repository,
                onBack = null,
                onMessage = onMessage,
                onError = onError
            )
            AdminHubEntry.WORKSHOPS -> WorkshopAssignmentCard(repository = repository, directory = directory, onMessage = onMessage, onError = onError)
            AdminHubEntry.ACCESS_REQUESTS -> WorkshopAccessQueueCard(repository = repository, onMessage = onMessage, onError = onError)
            // Same as the task board above: the hub's "All admin tools" pill is the back control,
            // so the screen's own arrow stays off (`onBack = null`).
            AdminHubEntry.API_KEYS -> ApiKeysScreen(
                repository = repository,
                onBack = null,
                onMessage = onMessage,
                onError = onError
            )
            AdminHubEntry.SETTINGS -> SettingsScreen(repository = repository, onMessage = onMessage, onError = onError)
        }
    }
}

@Composable
private fun ViewDataScreen(
    repository: FieldRepository,
    canReview: Boolean = false,
    masterAdmin: Boolean = false,
    isAdmin: Boolean = false,
    /** False only for an admin browsing with admin view OFF; always true for everyone else. */
    adminChrome: Boolean = true,
    showProvenance: Boolean = false,
    canDownloadDataset: Boolean = false,
    onOpenDataBrowser: (() -> Unit)? = null,
    onError: (String) -> Unit
) {
    var mode by remember { mutableStateOf(EntryMode.ARTISAN) }
    var options by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedId by remember { mutableStateOf("") }
    var loadingList by remember { mutableStateOf(false) }

    // Questionnaire-only filter: pick involved artisan(s), then the dependent dropdown lists the
    // interviews any of them were part of. Loaded once when the questionnaire mode is selected.
    var interviewsDetailed by remember { mutableStateOf<List<QuestionnaireInterviewDetailDto>>(emptyList()) }
    var artisanFilterList by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var selectedArtisanIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Sections (with their questions) so the dropdown can show the "A1"-style section+question codes
    // each interview actually answered. Loaded alongside the interviews in questionnaire mode.
    var questionnaireSections by remember { mutableStateOf<List<QuestionnaireSectionDto>>(emptyList()) }

    // Interviews that involve at least one of the selected artisans. A questionnaire with several
    // artisans appears as long as ONE selected artisan matches. The label LEADS with the artisan
    // name(s) — then the section/question span it covers (e.g. "A1–A5" from the section codes +
    // question numbers), then the title and place — so the list is easy to scan by who was interviewed.
    val questionnaireOptions = remember(interviewsDetailed, selectedArtisanIds, artisanFilterList, questionnaireSections) {
        if (selectedArtisanIds.isEmpty()) emptyList()
        else {
            val nameById = artisanFilterList.associate { it.id to it.name }
            // questionId -> "A1" style code (section code + question number), built from the sections.
            val codeByQuestionId = questionnaireSections.flatMap { sec ->
                sec.questions.map { q -> q.id to "${sec.code}${q.sortOrder}" }
            }.toMap()
            interviewsDetailed
                .filter { iv -> iv.artisans.any { it.artisanId in selectedArtisanIds } }
                // Idempotency: every saved interview record for the SAME set of artisan(s) collapses
                // into ONE entry, regardless of how many update-saves or which researcher(s) made them —
                // so the dropdown isn't cluttered with duplicates. The most recent save is the
                // representative the entry opens; the detail view then aggregates ALL records in this
                // group so nothing recorded under a sibling save is ever hidden. The label reflects the
                // section/question coverage and researcher(s) across the whole group.
                .groupBy { interviewGroupKey(it) }
                .map { (_, group) ->
                    val representative = representativeInterview(group)
                    val artisanNames = representative.artisans
                        .mapNotNull { link -> link.artisan?.name ?: nameById[link.artisanId] }
                        .distinct()
                        .joinToString(", ")
                        .ifBlank { "Unknown artisan" }
                    // The section/question codes answered across every save in this group, in order.
                    val answeredCodes = group
                        .flatMap { iv -> iv.responses }
                        .mapNotNull { codeByQuestionId[it.questionId] }
                        .distinct()
                        .sorted()
                    val qSpan = when {
                        answeredCodes.isEmpty() -> null
                        answeredCodes.size <= 4 -> answeredCodes.joinToString(" ")
                        else -> "${answeredCodes.first()}–${answeredCodes.last()} (${answeredCodes.size})"
                    }
                    val researchers = group.mapNotNull { it.createdBy?.name }.distinct().joinToString(", ")
                    val rest = listOfNotNull(
                        qSpan,
                        representative.title.takeIf { it.isNotBlank() },
                        representative.place?.takeIf { it.isNotBlank() },
                        researchers.ifBlank { null }?.let { "by $it" },
                        if (group.size > 1) "${group.size} sessions" else null
                    ).joinToString(" · ")
                    representative.id to (if (rest.isBlank()) artisanNames else "$artisanNames · $rest")
                }
                .sortedBy { it.second.lowercase() }
        }
    }

    LaunchedEffect(mode) {
        loadingList = true
        selectedId = ""
        selectedArtisanIds = emptySet()
        if (mode == EntryMode.QUESTIONNAIRE) {
            runCatching {
                val interviews = repository.interviews()
                val arts = repository.artisans()
                // Sections power the "A1"-style section+question codes in the dropdown label; a failure
                // here must not block the list, so it's fetched leniently and defaults to empty.
                questionnaireSections = runCatching { repository.questionnaireSections() }.getOrDefault(emptyList())
                interviews to arts
            }.onSuccess { (interviews, arts) ->
                interviewsDetailed = interviews
                // Only list artisans actually involved in an interview; build from the interview
                // links so it stays correct even past the artisans() page cap.
                val byId = arts.associateBy { it.id }
                val involved = LinkedHashMap<String, ArtisanDto>()
                interviews.forEach { iv ->
                    iv.artisans.forEach { link -> (link.artisan ?: byId[link.artisanId])?.let { involved.putIfAbsent(it.id, it) } }
                }
                artisanFilterList = involved.values.sortedBy { it.name.lowercase() }
            }.onFailure { onError(it.message ?: "Unable to load questionnaires") }
        } else {
            runCatching { loadViewEntries(repository, mode) }
                .onSuccess { options = it }
                .onFailure { onError(it.message ?: "Unable to load list") }
        }
        loadingList = false
    }
    // Keep the chosen questionnaire valid as the artisan filter changes (only in questionnaire mode).
    LaunchedEffect(questionnaireOptions, mode) {
        if (mode == EntryMode.QUESTIONNAIRE && selectedId.isNotBlank() && selectedId != COMPLETION_OPTION_ID && questionnaireOptions.none { it.first == selectedId }) {
            selectedId = ""
        }
    }

    RecordCard(title = "View data") {
        Text("Pick a record type, then an entry, to view it — including any transcribed audio.", color = Muted, fontSize = 12.sp)
        DropdownField(
            label = "Record type",
            options = viewDataModes.map { it.name to it.label },
            selectedValue = mode.name,
            includeNone = false
        ) { picked -> viewDataModes.firstOrNull { it.name == picked }?.let { mode = it } }
        when {
            loadingList -> Text("Loading ${mode.label.lowercase()}…", color = Muted)
            mode == EntryMode.QUESTIONNAIRE -> {
                ArtisanMultiSelectField(
                    label = "Involved artisan(s)",
                    artisans = artisanFilterList,
                    selectedIds = selectedArtisanIds
                ) { id ->
                    selectedArtisanIds = if (selectedArtisanIds.contains(id)) selectedArtisanIds - id else selectedArtisanIds + id
                }
                val hasArtisan = selectedArtisanIds.isNotEmpty()
                // "Check completion" is always offered first — it opens the artisans x sections matrix
                // (all artisans). The artisan-filtered questionnaire entries follow once an artisan is picked.
                val secondaryOptions = listOf(COMPLETION_OPTION_ID to "▦ Check completion (all artisans)") +
                    (if (hasArtisan) questionnaireOptions else emptyList())
                DropdownField(
                    label = "Select questionnaire",
                    options = secondaryOptions,
                    selectedValue = selectedId,
                    placeholder = "Check completion, or pick an artisan then a questionnaire",
                    includeNone = false,
                    enabled = true
                ) { selectedId = it }
                if (hasArtisan && questionnaireOptions.isEmpty()) {
                    Text("No questionnaires involve the selected artisan(s) yet — \"Check completion\" still shows the full matrix.", color = Muted, fontSize = 12.sp)
                }
            }
            options.isEmpty() -> Text("No ${mode.label.lowercase()} records yet.", color = Muted)
            else -> DropdownField(
                label = "Select ${mode.label.lowercase()}",
                options = options,
                selectedValue = selectedId,
                placeholder = "Select a record",
                includeNone = false
            ) { selectedId = it }
        }
    }
    // The completion override is `adminMode && isAdmin(user)` on the web — the role decides, the
    // admin-view toggle can only take the ability to override away again.
    val canOverrideCompletion = isAdmin && adminChrome
    if (selectedId == COMPLETION_OPTION_ID && mode == EntryMode.QUESTIONNAIRE) {
        CompletionMatrixCard(repository = repository, artisanId = null, canEdit = canOverrideCompletion, onError = onError)
    } else if (selectedId.isNotBlank()) {
        ViewDataDetail(
            repository = repository,
            mode = mode,
            recordId = selectedId,
            canOverrideCompletion = canOverrideCompletion,
            showProvenance = showProvenance,
            onError = onError
        )
    }
    if (canDownloadDataset) {
        // The dataset lives behind the same entitlement whichever way you take it out: the browser
        // walks it folder by folder, the card below pulls the whole thing in one zip.
        onOpenDataBrowser?.let { DataBrowserEntryCard(onOpen = it) }
        DatasetDownloadCard(repository = repository, onError = onError)
    }
    // Reviews & approvals moved to the admin "Settings" hub for admins; a non-admin who was granted the
    // review permission still reaches the queue here, since they have no admin hub. Reviewing is NOT
    // admin chrome (the web keeps /review open with admin view off), so when the hub is hidden the
    // queue has to reappear here — otherwise the toggle would take away a capability, not just chrome.
    if (canReview && !(isAdmin && adminChrome)) {
        ReviewApprovalCard(repository = repository, onError = onError)
    }
}

/**
 * One field a reviewer may correct in place. [key] is the API column name — it travels verbatim in
 * the `fields` map and is validated against the record type's own PATCH schema, so a typo here is a
 * 422 rather than a silent no-op.
 */
private data class ReviewField(val key: String, val label: String, val multiline: Boolean = false)

/**
 * The fields the review screen offers per record type: the name-like columns and the prose a reviewer
 * is actually qualified to fix ("a misspelt village or a craft name in the wrong column").
 *
 * Deliberately NOT offered here: status (that moves through approve/reject/revise), the workshop,
 * location and linked-record lists (all refused by the API with a 422), the measurement/price decimals
 * and the validated identity fields (Aadhaar, Pehchan, phone, email) — those belong on the record's
 * own edit screen where their formatting and duplicate rules are enforced by the form.
 */
private fun reviewEditableFields(recordType: String): List<ReviewField> = when (recordType.lowercase()) {
    "artisan" -> listOf(
        ReviewField("name", "Name"),
        ReviewField("localName", "Local name"),
        ReviewField("place", "Place"),
        ReviewField("address", "Address", multiline = true),
        ReviewField("notes", "Notes", multiline = true),
        ReviewField("dos", "Do's", multiline = true),
        ReviewField("donts", "Don'ts", multiline = true)
    )
    "workshop" -> listOf(
        ReviewField("title", "Workshop title"),
        ReviewField("place", "Place"),
        ReviewField("description", "Description", multiline = true),
        ReviewField("notes", "Notes", multiline = true)
    )
    "product" -> listOf(
        ReviewField("productName", "Product name"),
        ReviewField("localName", "Local name"),
        ReviewField("craftName", "Craft name"),
        ReviewField("artisanName", "Artisan name"),
        ReviewField("place", "Place"),
        ReviewField("rawMaterialsUsed", "Raw materials used", multiline = true),
        ReviewField("mainToolsUsed", "Main tools used", multiline = true),
        ReviewField("productFunctionUse", "Function / use", multiline = true),
        ReviewField("remarks", "Remarks", multiline = true)
    )
    "tool" -> listOf(
        ReviewField("toolkitName", "Toolkit name"),
        ReviewField("localName", "Local name"),
        ReviewField("englishName", "English name"),
        ReviewField("craftName", "Craft name"),
        ReviewField("artisanName", "Artisan name"),
        ReviewField("place", "Place"),
        ReviewField("processUsedIn", "Process used in", multiline = true),
        ReviewField("material", "Material"),
        ReviewField("suggestionsForToolImprovement", "Suggestions for improvement", multiline = true),
        ReviewField("remarks", "Remarks", multiline = true)
    )
    "process" -> listOf(
        ReviewField("name", "Name of the process"),
        ReviewField("notes", "Notes", multiline = true)
    )
    "questionnaire" -> listOf(
        ReviewField("title", "Interview title"),
        ReviewField("place", "Place"),
        ReviewField("language", "Language"),
        ReviewField("notes", "Notes", multiline = true)
    )
    "media" -> listOf(
        ReviewField("caption", "Caption", multiline = true),
        ReviewField("transcriptText", "Transcript", multiline = true)
    )
    else -> emptyList()
}

/** The record's stored values for the fields above, so the editor opens on what is actually saved. */
private suspend fun loadReviewRecordValues(
    repository: FieldRepository,
    recordType: String,
    recordId: String
): Map<String, String> = when (recordType.lowercase()) {
    "artisan" -> repository.artisan(recordId).let {
        mapOf(
            "name" to it.name, "localName" to it.localName.orEmpty(), "place" to it.place,
            "address" to it.address.orEmpty(), "notes" to it.notes.orEmpty(),
            "dos" to it.dos.orEmpty(), "donts" to it.donts.orEmpty()
        )
    }
    "workshop" -> repository.workshop(recordId).let {
        mapOf(
            "title" to it.title, "place" to it.place,
            "description" to it.description.orEmpty(), "notes" to it.notes.orEmpty()
        )
    }
    "product" -> repository.product(recordId).let {
        mapOf(
            "productName" to it.productName, "localName" to it.localName.orEmpty(),
            "craftName" to it.craftName, "artisanName" to it.artisanName, "place" to it.place,
            "rawMaterialsUsed" to it.rawMaterialsUsed.orEmpty(),
            "mainToolsUsed" to it.mainToolsUsed.orEmpty(),
            "productFunctionUse" to it.productFunctionUse.orEmpty(),
            "remarks" to it.remarks.orEmpty()
        )
    }
    "tool" -> repository.tool(recordId).let {
        mapOf(
            "toolkitName" to it.toolkitName, "localName" to it.localName.orEmpty(),
            "englishName" to it.englishName.orEmpty(), "craftName" to it.craftName,
            "artisanName" to it.artisanName, "place" to it.place,
            "processUsedIn" to it.processUsedIn.orEmpty(), "material" to it.material.orEmpty(),
            "suggestionsForToolImprovement" to it.suggestionsForToolImprovement.orEmpty(),
            "remarks" to it.remarks.orEmpty()
        )
    }
    "process" -> repository.process(recordId).let {
        mapOf("name" to it.name, "notes" to it.notes.orEmpty())
    }
    "questionnaire" -> repository.interview(recordId).let {
        mapOf(
            "title" to it.title, "place" to it.place.orEmpty(),
            "language" to it.language.orEmpty(), "notes" to it.notes.orEmpty()
        )
    }
    "media" -> repository.mediaItem(recordId).let {
        mapOf("caption" to it.caption.orEmpty(), "transcriptText" to it.transcriptText.orEmpty())
    }
    else -> emptyMap()
}

/**
 * Admin/reviewer-only: the queue of records still awaiting review (status PENDING). Each card can be
 * approved, rejected, EDITED in place, or sent back for revision; the list refreshes so cleared items
 * drop off. Gated by [canReview] (any admin, or a user the master admin granted the review permission).
 */
@Composable
private fun ReviewApprovalCard(repository: FieldRepository, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<PendingReviewDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { repository.pendingReviews() }
                .onSuccess { pending = it }
                .onFailure { onError(it.apiErrorMessage("Unable to load the review queue")) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    RecordCard(title = "Reviews & approvals") {
        Text(
            "Records submitted by the team that are still pending review. Approve to publish them, fix a " +
                "small mistake yourself with Edit, send it back for revision with comments, or reject it.",
            color = Muted,
            fontSize = 12.sp
        )
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Loading pending records…", color = Muted, fontSize = 12.sp)
            }
            pending.isEmpty() -> Text("Nothing pending — everything has been reviewed. 🎉", color = Muted, fontSize = 12.sp)
            else -> {
                Text("${pending.size} record(s) awaiting review", color = Body, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                info?.let { Text(it, color = SuccessGreen, fontSize = 11.sp) }
                pending.forEach { item ->
                    // Keyed by the record, not by position in the list. Every row here remembers state
                    // of its own — which panel is open, the half-typed rejection comment, the edit
                    // baseline loaded from the server — and resolving a row REMOVES it, so without a
                    // key Compose hands all of that to whichever record slides up into the vacated
                    // slot. A reviewer could finish typing a comment about one submission and send it
                    // against another.
                    key(item.recordType, item.id) {
                        PendingReviewRow(
                            repository = repository,
                            item = item,
                            onResolved = { message ->
                                // Approve / reject / revise all move the record out of PENDING, so drop
                                // it from the list rather than re-fetching the whole queue. Matched on
                                // the same pair as the key above, because an id alone does not name a
                                // row here: the queue is several tables concatenated, so dropping by id
                                // could take an unreviewed record of another type off the screen with it.
                                pending = pending.filterNot { it.recordType == item.recordType && it.id == item.id }
                                info = message
                            },
                            onInfo = { info = it },
                            onError = onError
                        )
                    }
                }
            }
        }
    }
}

/** One queue row, with its inline edit / send-for-revision panels. */
@Composable
private fun PendingReviewRow(
    repository: FieldRepository,
    item: PendingReviewDto,
    onResolved: (String) -> Unit,
    onInfo: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // null | "edit" | "revise" — at most one panel open, so the row never grows two forms tall.
    var panel by remember { mutableStateOf<String?>(null) }
    var confirmReject by remember { mutableStateOf(false) }
    var reviseNote by remember { mutableStateOf("") }
    var editNote by remember { mutableStateOf("") }
    // Loaded values (the baseline the diff is taken against) and the reviewer's working copy.
    var original by remember { mutableStateOf<Map<String, String>?>(null) }
    val edited = remember { mutableStateMapOf<String, String>() }
    val fields = remember(item.recordType) { reviewEditableFields(item.recordType) }

    fun act(ok: String, resolves: Boolean, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            runCatching { block() }
                .onSuccess { if (resolves) onResolved(ok) else onInfo(ok) }
                .onFailure { onError(it.apiErrorMessage("That review action didn't go through")) }
            busy = false
        }
    }

    // Load the record only when the reviewer actually opens the editor — the queue can be long, and
    // fetching every record up front would be a request per row for panels nobody opens.
    LaunchedEffect(panel) {
        if (panel != "edit" || original != null || fields.isEmpty()) return@LaunchedEffect
        runCatching { loadReviewRecordValues(repository, item.recordType, item.id) }
            .onSuccess { values ->
                original = values
                edited.clear()
                edited.putAll(values)
            }
            .onFailure {
                panel = null
                onError(it.apiErrorMessage("Unable to open ${item.label} for editing"))
            }
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.label, color = Body, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(
                    item.recordType.replaceFirstChar { it.uppercase() },
                    item.place?.takeIf { it.isNotBlank() },
                    item.createdBy?.name?.takeIf { it.isNotBlank() }?.let { "by $it" },
                    formatIsoDate(item.createdAt)
                ).joinToString(" · "),
                color = Muted,
                fontSize = 11.sp
            )
            if (item.needsAdminApproval) {
                Text(
                    "Submitted outside its workshop's dates — only an admin can edit or approve it.",
                    color = Coral,
                    fontSize = 11.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { act("Approved ${item.label}", resolves = true) { repository.approveRecord(item.recordType, item.id) } },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text(if (busy) "Working…" else "Approve", maxLines = 1, fontSize = 13.sp) }
                OutlinedButton(
                    // Asks first, as the web's review page does. Rejection is the end of the road for a
                    // submission and it sits one tap away from "Send for revision", the recoverable
                    // action with almost the same meaning; on a phone, where the two buttons are a
                    // thumb-width apart, doing it on the first tap was a mis-tap away from telling a
                    // contributor their work was thrown out.
                    onClick = { confirmReject = true },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text("Reject", maxLines = 1, fontSize = 13.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { panel = if (panel == "edit") null else "edit" },
                    enabled = !busy && fields.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (panel == "edit") "Close" else "Edit", maxLines = 1, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { panel = if (panel == "revise") null else "revise" },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text(if (panel == "revise") "Close" else "Send for revision", maxLines = 1, fontSize = 12.sp) }
            }

            when (panel) {
                "revise" -> {
                    Text(
                        "Comments are required — they are what the creator sees and fixes. The record goes " +
                            "back to them and returns for review once they edit it.",
                        color = Muted,
                        fontSize = 11.sp
                    )
                    TextInput("What needs to change?", reviseNote, minLines = 2) { reviseNote = it }
                    Button(
                        enabled = !busy && reviseNote.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            act("Sent ${item.label} back for revision", resolves = true) {
                                repository.reviseRecord(item.recordType, item.id, reviseNote.trim())
                            }
                        }
                    ) { Text(if (busy) "Sending…" else "Send back with comments") }
                }
                "edit" -> {
                    val baseline = original
                    if (baseline == null) {
                        Text("Loading ${item.recordType}…", color = Muted, fontSize = 12.sp)
                    } else {
                        Text(
                            "Fix the values here instead of bouncing the record back. This does NOT approve " +
                                "it — the status stays pending, and the change is recorded against your name.",
                            color = Muted,
                            fontSize = 11.sp
                        )
                        fields.forEach { field ->
                            val value = edited[field.key].orEmpty()
                            TextInput(field.label, value, minLines = if (field.multiline) 2 else 1) {
                                edited[field.key] = it
                            }
                            // Name-like columns are title-cased server-side, so show what will land.
                            if (field.key in com.fieldrepository.app.data.TITLE_CASE_FIELDS) {
                                titleCasePreview(value)?.let { normalised ->
                                    Text("Will be saved as “$normalised”", color = Muted, fontSize = 11.sp)
                                }
                            }
                        }
                        TextInput("Why? (optional, logged with the edit)", editNote) { editNote = it }
                        // Only the keys that actually moved travel — the API logs the diff, and sending
                        // an unchanged value would put a no-op line in the record's edit history.
                        val changed = fields
                            .map { it.key }
                            .filter { key -> edited[key].orEmpty() != baseline[key].orEmpty() }
                            .associateWith { key -> edited[key].orEmpty() }
                        Button(
                            enabled = !busy && changed.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                act("Edited ${item.label} (${changed.size} field(s))", resolves = false) {
                                    repository.editReviewedRecord(item.recordType, item.id, changed, editNote)
                                    // Re-read so the baseline matches what the server actually stored —
                                    // it title-cases names, so the box must show the normalised value.
                                    val fresh = loadReviewRecordValues(repository, item.recordType, item.id)
                                    original = fresh
                                    edited.clear()
                                    edited.putAll(fresh)
                                    editNote = ""
                                }
                            }
                        ) {
                            Text(
                                when {
                                    busy -> "Saving…"
                                    changed.isEmpty() -> "No changes yet"
                                    else -> "Save ${changed.size} change(s)"
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Word for word the web review page's rejection confirmation, including the pointer at the softer
    // action, so a reviewer who works on both surfaces is answering the same question either way.
    if (confirmReject) {
        AlertDialog(
            onDismissRequest = { confirmReject = false },
            title = { Text("Reject this record?") },
            text = {
                Text(
                    "${item.label} will show as rejected to the contributor. If you want them to fix " +
                        "it and resubmit, close this and use Send for revision instead."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReject = false
                    act("Rejected ${item.label}", resolves = true) {
                        repository.rejectRecord(item.recordType, item.id)
                    }
                }) { Text("Reject record", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReject = false }) { Text("Cancel") } }
        )
    }
}

/** Bottom-of-screen control to pull the entire dataset into a structured zip in Downloads. */
@Composable
private fun DatasetDownloadCard(repository: FieldRepository, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var reportBusy by remember { mutableStateOf(false) }
    var reportMessage by remember { mutableStateOf<String?>(null) }

    RecordCard(title = "Download entire dataset") {
        Text(
            "Pulls every record and media file into a single zip, organised as Workshops → crafts → " +
                "artisans → products (with their processes), tools and questionnaires. This can take a " +
                "while as all resources are fetched, then compiled.",
            color = Muted,
            fontSize = 12.sp
        )
        if (downloading) {
            val fraction = if (total > 0) done.toFloat() / total.toFloat() else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text("Compiling $done / $total files (${(fraction * 100).toInt()}%)", color = Muted, fontSize = 12.sp)
        }
        resultMessage?.let { Text(it, color = Body, fontSize = 12.sp) }
        Button(
            onClick = {
                if (downloading) return@Button
                resultMessage = null
                downloading = true
                done = 0
                total = 0
                scope.launch {
                    runCatching {
                        repository.downloadDataset(context) { d, t -> done = d; total = t }
                    }.onSuccess { res ->
                        resultMessage = "Saved to ${res.displayLocation} — ${res.saved}/${res.total} files" +
                            if (res.failed > 0) " (${res.failed} could not be fetched)" else ""
                    }.onFailure { onError(it.message ?: "Unable to download the dataset") }
                    downloading = false
                }
            },
            enabled = !downloading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (downloading) "Downloading…" else "Download all data (.zip)")
        }
        HorizontalDivider()
        Text(
            "Prefer a spreadsheet? Download a styled .xlsx report of the whole dataset — one sheet per " +
                "record type, with the relationships between them preserved.",
            color = Muted,
            fontSize = 12.sp
        )
        reportMessage?.let { Text(it, color = Body, fontSize = 12.sp) }
        OutlinedButton(
            onClick = {
                if (reportBusy) return@OutlinedButton
                reportMessage = null
                reportBusy = true
                scope.launch {
                    runCatching { repository.downloadReport(context) }
                        .onSuccess { location -> reportMessage = "Report saved to $location" }
                        .onFailure { onError(it.message ?: "Unable to download the report") }
                    reportBusy = false
                }
            },
            enabled = !reportBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (reportBusy) "Preparing report…" else "Download report (.xlsx)")
        }
    }
}

// Completion-matrix colours: section done (green), flagged for review (amber), to redo (red),
// and not-yet-started (neutral). Green also covers a section recorded in any group/superset the
// artisan belongs to, or individually.
private val CompletionGreen = Color(0xFF2E9E5B)
private val CompletionAmber = Color(0xFFE2A400)
private val CompletionRed = Color(0xFFD25141)
private val CompletionEmpty = Color(0xFFE7E0D6)

private fun completionColor(status: String?, derived: Boolean): Color = when (status) {
    "COMPLETED" -> CompletionGreen
    "NEEDS_REVIEW" -> CompletionAmber
    "NEEDS_REDO" -> CompletionRed
    else -> if (derived) CompletionGreen else CompletionEmpty
}

/**
 * The "Check completion" matrix: artisans down the rows, questionnaire sections across the columns.
 * A cell is green when that section has been recorded for the artisan (individually, or in any group
 * / larger superset they were part of) or an admin marked it complete; amber = requires review; red =
 * needs redoing; neutral = not started. Pinch to zoom in/out. Admins/master admins tap a cell to set
 * its status. Pass [artisanId] to show just that one artisan (the per-artisan View Data view).
 */
@Composable
private fun CompletionMatrixCard(
    repository: FieldRepository,
    artisanId: String? = null,
    canEdit: Boolean = false,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var matrix by remember(artisanId) { mutableStateOf<CompletionMatrixDto?>(null) }
    var loading by remember(artisanId) { mutableStateOf(true) }
    // Local override of cells so a tap reflects immediately without a full reload.
    var cellOverrides by remember(artisanId) { mutableStateOf<Map<Pair<String, String>, String?>>(emptyMap()) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun reload() {
        scope.launch {
            loading = true
            runCatching { repository.completionMatrix(artisanId) }
                .onSuccess { matrix = it; cellOverrides = emptyMap() }
                .onFailure { onError(it.message ?: "Unable to load completion") }
            loading = false
        }
    }
    LaunchedEffect(artisanId) { reload() }

    RecordCard(title = "Check completion", icon = Icons.Filled.GridView) {
        Text(
            "Sections recorded per artisan — green is done (counted whether recorded individually or " +
                "as part of any group/superset). Pinch to zoom." +
                if (canEdit) " Tap a cell to set its status." else "",
            color = Muted,
            fontSize = 12.sp
        )
        // Legend.
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            CompletionLegendChip(CompletionGreen, "Completed")
            CompletionLegendChip(CompletionAmber, "Requires review")
            CompletionLegendChip(CompletionRed, "Needs redo")
            CompletionLegendChip(CompletionEmpty, "Not started")
        }

        val data = matrix
        when {
            loading -> Text("Loading completion…", color = Muted, fontSize = 12.sp)
            data == null || data.sections.isEmpty() -> Text("No questionnaire sections defined yet.", color = Muted, fontSize = 12.sp)
            data.artisans.isEmpty() -> Text("No artisans to show.", color = Muted, fontSize = 12.sp)
            else -> {
                val grid = data
                val cellByKey: Map<Pair<String, String>, CompletionCellDto> =
                    remember(grid) { grid.cells.associateBy { c -> c.artisanId to c.sectionId } }
                val nameWidth = 132.dp
                val colWidth = 52.dp
                val rowHeight = 40.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(440.dp)
                        .clipToBounds()
                        .pointerInput(Unit) {
                            // Two-finger pinch zooms + pans; a plain tap falls through to the cells.
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.5f, 3f)
                                offset += pan
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                            .horizontalScroll(rememberScrollState())
                    ) {
                        // Header row: corner + section codes.
                        Row {
                            CompletionHeaderCell("Artisan", nameWidth, rowHeight, alignStart = true)
                            data.sections.forEach { section ->
                                CompletionHeaderCell(section.code, colWidth, rowHeight)
                            }
                        }
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            data.artisans.forEach { artisan ->
                                Row {
                                    Box(
                                        modifier = Modifier
                                            .width(nameWidth)
                                            .height(rowHeight)
                                            .background(Canvas)
                                            .padding(horizontal = 6.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text(artisan.name, color = Body, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    data.sections.forEach { section ->
                                        val key = artisan.id to section.id
                                        val cell = cellByKey[key]
                                        val status = if (cellOverrides.containsKey(key)) cellOverrides[key] else cell?.status
                                        val derived = cell?.derived == true
                                        Box(
                                            modifier = Modifier
                                                .width(colWidth)
                                                .height(rowHeight)
                                                .padding(1.dp)
                                                .background(completionColor(status, derived), RoundedCornerShape(3.dp))
                                                .then(
                                                    if (canEdit) Modifier.clickable { editing = key } else Modifier
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (status == "COMPLETED" || (status == null && derived)) {
                                                Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            } else if (status == "NEEDS_REVIEW") {
                                                Text("!", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            } else if (status == "NEEDS_REDO") {
                                                Text("✗", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val cellToEdit = editing
    val loadedMatrix = matrix
    if (cellToEdit != null && canEdit && loadedMatrix != null) {
        val section = loadedMatrix.sections.firstOrNull { s -> s.id == cellToEdit.second }
        val sectionName = if (section != null) "${section.code} · ${section.title}" else "section"
        val artisan = loadedMatrix.artisans.firstOrNull { a -> a.id == cellToEdit.first }
        val artisanName = artisan?.name ?: "artisan"
        fun apply(status: String?) {
            editing = null
            cellOverrides = cellOverrides + (cellToEdit to status)
            scope.launch {
                runCatching { repository.setCompletionCell(cellToEdit.first, cellToEdit.second, status) }
                    .onFailure {
                        onError(it.message ?: "Unable to update status")
                        // Roll back the optimistic change on failure.
                        cellOverrides = cellOverrides - cellToEdit
                    }
            }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
            title = { Text("Mark $sectionName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("for $artisanName", color = Muted, fontSize = 12.sp)
                    CompletionStatusButton(CompletionGreen, "Completed") { apply("COMPLETED") }
                    CompletionStatusButton(CompletionAmber, "Requires review") { apply("NEEDS_REVIEW") }
                    CompletionStatusButton(CompletionRed, "Needs to be redone") { apply("NEEDS_REDO") }
                    TextButton(onClick = { apply(null) }) { Text("Clear (use auto-detected)") }
                }
            }
        )
    }
}

@Composable
private fun CompletionLegendChip(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun CompletionHeaderCell(text: String, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, alignStart: Boolean = false) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(SurfaceCard)
            .padding(horizontal = 4.dp),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center
    ) {
        Text(text, color = Body, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompletionStatusButton(color: Color, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.size(18.dp).background(color, RoundedCornerShape(4.dp)))
        Text(label, color = Body, fontSize = 14.sp)
    }
}

@Composable
private fun ViewDataDetail(
    repository: FieldRepository,
    mode: EntryMode,
    recordId: String,
    /** Admin AND admin view on — the web's `adminMode && isAdmin(user)` for the completion matrix. */
    canOverrideCompletion: Boolean = false,
    showProvenance: Boolean = false,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    when (mode) {
        EntryMode.PROCESS -> {
            var detail by remember(recordId) { mutableStateOf<ProcessDetailDto?>(null) }
            LaunchedEffect(recordId) {
                runCatching { repository.process(recordId) }.onSuccess { detail = it }.onFailure { onError(it.message ?: "Unable to load process") }
            }
            val d = detail ?: return run { LoadingCard(mode) }
            RecordCard(title = d.name.ifBlank { "Process" }) {
                if (showProvenance) ProvenanceSection(meta = d.extraMetadata, createdByName = d.createdBy?.name)
                DetailRow("Product", d.product?.productName)
                DetailRow("Pre-processes", if (d.preProcessAvailable) "Yes" else "No")
                DetailRow("Notes", d.notes)
                DetailRow("Status", d.status)
                if (d.media.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Pre-process media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    d.media.forEach { MediaWithTranscript(context, it, repository) }
                }
                d.steps.forEach { step ->
                    HorizontalDivider()
                    Text("Step ${step.sortOrder} · ${if (step.stepType == "SEQUENTIAL") "Sequential" else "Group"} — ${step.name}", color = Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    step.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, fontSize = 12.sp) }
                    if (step.media.isEmpty()) Text("No media.", color = Muted, fontSize = 12.sp)
                    step.media.forEach { MediaWithTranscript(context, it, repository) }
                }
            }
        }
        EntryMode.ARTISAN -> {
            var d by remember(recordId) { mutableStateOf<ArtisanDetailDto?>(null) }
            LaunchedEffect(recordId) { runCatching { repository.artisan(recordId) }.onSuccess { d = it }.onFailure { onError(it.message ?: "Unable to load artisan") } }
            val v = d ?: return run { LoadingCard(mode) }
            RecordCard(title = v.name.ifBlank { "Artisan" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Craft", v.craft?.name)
                DetailRow("Place", v.place)
                DetailRow("Gender", v.gender)
                DetailRow("Phone", v.phone)
                DetailRow("Email", v.email)
                DetailRow("Address", v.address)
                DetailRow("Notes", v.notes)
                // Browsing is a shared surface, so the Aadhaar is masked here even though the edit
                // form (same record, same DTO) shows it in full to the person about to correct it.
                DetailRow("Aadhaar number", maskAadhaar(v.aadhaarNumber))
                DetailRow("Pehchan card", if (v.pehchanCardAvailable) "Yes" else "No")
                DetailRow("Pehchan card number", v.pehchanCardNumber)
                NumberedListDisplay("Do's (positive prompt)", v.dos)
                NumberedListDisplay("Don'ts (negative prompt)", v.donts)
                DetailRow("Status", v.status)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError)
            }
            // Per-artisan completion: the same matrix, scoped to just this artisan (one row).
            CompletionMatrixCard(repository = repository, artisanId = recordId, canEdit = canOverrideCompletion, onError = onError)
            // Everything recorded against this artisan in the questionnaire — answers + the recordings
            // from every interview they belong to (alone, in a subset, or a larger set), so a group
            // recording surfaces here for this artisan to be validated individually.
            ArtisanQuestionnaireData(repository = repository, artisanId = recordId)
        }
        EntryMode.PRODUCT -> {
            var d by remember(recordId) { mutableStateOf<ProductDetailDto?>(null) }
            LaunchedEffect(recordId) { runCatching { repository.product(recordId) }.onSuccess { d = it }.onFailure { onError(it.message ?: "Unable to load product") } }
            val v = d ?: return run { LoadingCard(mode) }
            RecordCard(title = v.productName.ifBlank { "Product" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Craft", v.craftName)
                DetailRow("Artisan", v.artisanName)
                DetailRow("Place", v.place)
                DetailRow("Type", v.productType)
                DetailRow("Market", v.marketDemand)
                DetailRow("Materials", v.rawMaterialsUsed)
                DetailRow("Tools", v.mainToolsUsed)
                DetailRow("Function", v.productFunctionUse)
                DetailRow("Remarks", v.remarks)
                DetailRow("Status", v.status)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError)
            }
        }
        EntryMode.TOOL -> {
            var d by remember(recordId) { mutableStateOf<ToolDetailDto?>(null) }
            LaunchedEffect(recordId) { runCatching { repository.tool(recordId) }.onSuccess { d = it }.onFailure { onError(it.message ?: "Unable to load tool") } }
            val v = d ?: return run { LoadingCard(mode) }
            RecordCard(title = v.toolkitName.ifBlank { "Tool" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Craft", v.craftName)
                DetailRow("Artisan", v.artisanName)
                DetailRow("Place", v.place)
                DetailRow("Material", v.material)
                DetailRow("Maker", v.maker)
                DetailRow("Tradition", v.traditionType)
                DetailRow("Used in", v.processUsedIn)
                DetailRow("Remarks", v.remarks)
                DetailRow("Status", v.status)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError)
            }
        }
        EntryMode.WORKSHOP -> {
            var d by remember(recordId) { mutableStateOf<WorkshopDetailDto?>(null) }
            LaunchedEffect(recordId) { runCatching { repository.workshop(recordId) }.onSuccess { d = it }.onFailure { onError(it.message ?: "Unable to load workshop") } }
            val v = d ?: return run { LoadingCard(mode) }
            RecordCard(title = v.title.ifBlank { "Workshop" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Place", v.place)
                DetailRow("Description", v.description)
                DetailRow("Notes", v.notes)
                DetailRow("Status", v.status)
                DetailRow("Artisans", v.artisans.mapNotNull { it.artisan?.name }.joinToString(", ").ifBlank { null })
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError)
            }
        }
        EntryMode.CRAFT -> {
            var d by remember(recordId) { mutableStateOf<CraftDto?>(null) }
            LaunchedEffect(recordId) { runCatching { repository.craft(recordId) }.onSuccess { d = it }.onFailure { onError(it.message ?: "Unable to load craft") } }
            val v = d ?: return run { LoadingCard(mode) }
            RecordCard(title = v.name.ifBlank { "Craft" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Local name", v.localName)
                DetailRow("Category", v.category)
                DetailRow("Place", v.place)
                DetailRow("Description", v.description)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError)
            }
        }
        EntryMode.QUESTIONNAIRE -> {
            // A "questionnaire" here is the WHOLE set of saved interview records for the same set of
            // artisan(s). We aggregate responses AND media across every record in that group, so a
            // recording attached to one sibling save is visible no matter which entry was opened — the
            // core fix for "not all features of the record(s) are visible".
            var members by remember(recordId) { mutableStateOf<List<QuestionnaireInterviewDetailDto>?>(null) }
            var groupMedia by remember(recordId) { mutableStateOf<List<MediaFileDto>>(emptyList()) }
            LaunchedEffect(recordId) {
                runCatching {
                    val all = repository.interviews()
                    val selected = all.firstOrNull { it.id == recordId } ?: repository.interview(recordId)
                    val key = interviewGroupKey(selected)
                    val group = all.filter { interviewGroupKey(it) == key }.ifEmpty { listOf(selected) }
                    // Media is pulled per record through the media endpoint (which carries uploader +
                    // transcript), then de-duplicated by id across the whole group.
                    val mediaById = LinkedHashMap<String, MediaFileDto>()
                    group.forEach { m ->
                        runCatching { repository.mediaForRecord("questionnaire", m.id) }.getOrDefault(emptyList())
                            .forEach { mediaById[it.id] = it }
                    }
                    group to mediaById.values.toList()
                }.onSuccess { (g, media) -> members = g; groupMedia = media }
                    .onFailure { onError(it.message ?: "Unable to load interview") }
            }
            val g = members ?: return run { LoadingCard(mode) }
            val rep = representativeInterview(g)
            RecordCard(title = rep.title.ifBlank { "Interview" }) {
                if (showProvenance) ProvenanceSection(meta = rep.extraMetadata, createdByName = rep.createdBy?.name)
                DetailRow("Artisans", rep.artisans.mapNotNull { it.artisan?.name }.joinToString(", ").ifBlank { null })
                DetailRow("Place", rep.place)
                DetailRow("Language", rep.language)
                DetailRow("Notes", rep.notes)
                DetailRow("Status", rep.status)
                val researchers = g.mapNotNull { it.createdBy?.name }.distinct().joinToString(", ")
                if (researchers.isNotBlank()) DetailRow("Interviewer(s)", researchers)
                if (g.size > 1) DetailRow("Merged from", "${g.size} interview records for these artisan(s)")
                // Answers aggregated across every record in the group (deduped by question + text).
                val allResponses = g.flatMap { it.responses }
                    .filter { !it.answerText.isNullOrBlank() }
                    .distinctBy { it.questionId to it.answerText }
                if (allResponses.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Answers", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    allResponses.forEach { r -> DetailRow(r.answeredBy?.name ?: "Answer", r.answerText) }
                }
                HorizontalDivider()
                Text("Recordings & media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                if (groupMedia.isEmpty()) Text("No media attached.", color = Muted, fontSize = 12.sp)
                else groupMedia.forEach { MediaWithTranscript(context, it, repository) }
            }
        }
        EntryMode.MEDIA -> {
            var d by remember(recordId) { mutableStateOf<MediaFileDto?>(null) }
            LaunchedEffect(recordId) { runCatching { repository.mediaItem(recordId) }.onSuccess { d = it }.onFailure { onError(it.message ?: "Unable to load media") } }
            val v = d ?: return run { LoadingCard(mode) }
            RecordCard(title = v.originalFilename.ifBlank { "Media" }) {
                DetailRow("Type", v.mediaType)
                DetailRow("Caption", v.caption)
                v.linkedRecordType?.takeIf { it.isNotBlank() }?.let { DetailRow("Linked to", it.replaceFirstChar { c -> c.uppercase() }) }
                MediaWithTranscript(context, v, repository)
            }
        }
        else -> Text("This record type cannot be viewed here.", color = Muted)
    }
    // Comments (Comment tier) + edit history (owner/admin) for this record — same data-access model as web.
    if (mode in setOf(
            EntryMode.ARTISAN, EntryMode.PRODUCT, EntryMode.TOOL, EntryMode.WORKSHOP,
            EntryMode.PROCESS, EntryMode.QUESTIONNAIRE, EntryMode.CRAFT, EntryMode.MEDIA
        )
    ) {
        RecordCollabSection(repository, mode.linkedRecordType(), recordId, onError)
    }
}

/**
 * Per-record comments + edit history, powered by the data-access API. Anyone who can view the record
 * can read comments; posting needs Comment-tier access (or owner/admin). Edit history is owner/admin
 * only (the endpoint 403s otherwise, which we hide). Mirrors the web CollabPanel.
 */
@Composable
private fun RecordCollabSection(
    repository: FieldRepository,
    recordType: String,
    recordId: String,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var comments by remember(recordId) { mutableStateOf<List<EntryCommentDto>>(emptyList()) }
    var revisions by remember(recordId) { mutableStateOf<List<RecordRevisionDto>?>(null) }
    var draft by remember(recordId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            runCatching { comments = repository.entryComments(recordType, recordId) }
                .onFailure { onError(it.message ?: "Unable to load comments") }
            // Edit history is owner/admin only; silently hide if not permitted.
            revisions = runCatching { repository.recordRevisions(recordType, recordId) }.getOrNull()
        }
    }

    LaunchedEffect(recordType, recordId) { reload() }

    RecordCard(title = "Comments & edit history") {
        Text("Comments", display = true, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (comments.isEmpty()) Text("No comments yet.", color = Muted, fontSize = 12.sp)
        comments.forEach { c ->
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(c.body, color = Body, fontSize = 13.sp)
                Text("${c.author?.name ?: "Someone"} · ${c.createdAt.take(10)}", color = Muted, fontSize = 11.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Add a comment (needs comment access)", draft) { draft = it } }
            Button(
                enabled = !busy && draft.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { repository.addEntryComment(recordType, recordId, draft.trim()); draft = "" }
                            .onSuccess { reload() }
                            .onFailure { onError(it.message ?: "Unable to post comment") }
                        busy = false
                    }
                }
            ) { Text("Post") }
        }
        revisions?.let { revs ->
            HorizontalDivider()
            Text("Edit history", display = true, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (revs.isEmpty()) Text("No edits recorded.", color = Muted, fontSize = 12.sp)
            revs.forEach { r ->
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("${r.editedBy?.name ?: "Unknown"} · ${r.createdAt.take(10)}", color = Muted, fontSize = 11.sp)
                    r.changes.forEach { (field, change) ->
                        Text(
                            "$field: ${jsonText(change.old)} → ${jsonText(change.new)}",
                            color = Body, fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/** Render a JSON value (string/number/bool/null) from an edit-history change for display. */
private fun jsonText(value: kotlinx.serialization.json.JsonElement?): String {
    if (value == null || value is kotlinx.serialization.json.JsonNull) return "—"
    val prim = value as? kotlinx.serialization.json.JsonPrimitive ?: return value.toString()
    return prim.content
}

/** Record types a miscellaneous-media upload can be linked to (item: Misc Media). */
private val mediaLinkModes = listOf(
    EntryMode.ARTISAN, EntryMode.WORKSHOP, EntryMode.CRAFT, EntryMode.TOOL,
    EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.QUESTIONNAIRE, EntryMode.MEDIA
)

@Composable
private fun AndroidMediaForm(
    repository: FieldRepository,
    onUploaded: (Int) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var mediaTitle by remember { mutableStateOf("") }
    var linkedMode by remember { mutableStateOf<EntryMode?>(null) }
    var linkedEntryId by remember { mutableStateOf("") }
    var entryOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loadingEntries by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf("") }
    var location by remember { mutableStateOf<LocationRequest?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf(ActionStatus.IDLE) }
    var uploadFraction by remember { mutableStateOf(0f) }
    var uploadProgressText by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var savedMedia by remember { mutableStateOf<List<com.fieldrepository.app.data.MediaFileDto>>(emptyList()) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    AutoResetStatus(uploadStatus) { uploadStatus = ActionStatus.IDLE }

    fun refreshMedia() {
        scope.launch {
            runCatching { repository.media() }
                .onSuccess { savedMedia = it }
                .onFailure { error -> onError(error.message ?: "Unable to load saved media") }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) selectedUris = selectedUris + uris
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) selectedUris = selectedUris + uri
        pendingCaptureUri = null
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) selectedUris = selectedUris + uri
        pendingCaptureUri = null
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(requiredAndroidPermissions())
        refreshMedia()
    }
    // When the linked record type changes, load that type's entries for the second dropdown.
    LaunchedEffect(linkedMode) {
        linkedEntryId = ""
        entryOptions = emptyList()
        val mode = linkedMode ?: return@LaunchedEffect
        loadingEntries = true
        runCatching { loadViewEntries(repository, mode) }
            .onSuccess { entryOptions = it }
            .onFailure { error -> onError(error.message ?: "Unable to load ${mode.label} entries") }
        loadingEntries = false
    }

    RecordCard(title = "Capture media") {
        // "transcription", not "Whisper transcription": the backend now runs a provider chain
        // (ElevenLabs → Deepgram → Whisper), so naming one of them tells the researcher something
        // that is only sometimes true. The web misc-media screen already says it this way.
        Text(
            "Images, videos, audio and files upload to the same repository backend. Audio is queued for transcription after upload.",
            color = Muted,
            fontSize = 12.sp
        )
        Button(onClick = { pickMedia.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
            Text("Pick multiple files")
        }
        OutlinedButton(
            onClick = {
                permissionLauncher.launch(requiredAndroidPermissions())
                val uri = createAppFileUri(context, "field-photo-", ".jpg")
                pendingCaptureUri = uri
                takePhoto.launch(uri)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Take image")
        }
        OutlinedButton(
            onClick = {
                permissionLauncher.launch(requiredAndroidPermissions())
                val uri = createAppFileUri(context, "field-video-", ".mp4")
                pendingCaptureUri = uri
                takeVideo.launch(uri)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Record video")
        }
        OutlinedButton(
            onClick = {
                permissionLauncher.launch(requiredAndroidPermissions())
                if (!recording) {
                    runCatching {
                        val file = createAppFile(context, "field-audio-", ".m4a")
                        recorder = createAudioRecorder(context, file).also { it.start() }
                        recordingFile = file
                        recording = true
                        localMessage = "Recording audio..."
                    }.onFailure { error ->
                        onError(error.message ?: "Unable to start audio recording")
                    }
                } else {
                    runCatching {
                        recorder?.stop()
                        recorder?.release()
                        recordingFile?.let { file -> selectedUris = selectedUris + uriForFile(context, file) }
                    }.onFailure { error ->
                        onError(error.message ?: "Unable to stop audio recording")
                    }
                    recorder = null
                    recordingFile = null
                    recording = false
                    localMessage = "Audio recording added to batch"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (recording) StopSquareLabel("Stop audio recording") else Text("Record audio ●")
        }
        if (recording) {
            RecordingIndicator(getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) })
        }
        TextInput("Media title / object name", mediaTitle) { mediaTitle = it }
        DropdownField(
            label = "Linked record type *",
            options = mediaLinkModes.map { it.name to it.label },
            selectedValue = linkedMode?.name ?: "",
            placeholder = "Choose the type of record",
            includeNone = false
        ) { picked -> linkedMode = mediaLinkModes.firstOrNull { it.name == picked } }
        DropdownField(
            label = "Linked entry (optional)",
            options = entryOptions,
            selectedValue = linkedEntryId,
            placeholder = when {
                linkedMode == null -> "Select a record type first"
                loadingEntries -> "Loading…"
                entryOptions.isEmpty() -> "No entries for this type"
                else -> "Select an entry"
            },
            includeNone = true,
            enabled = linkedMode != null && !loadingEntries
        ) { linkedEntryId = it }
        TextInput("Caption", caption, minLines = 2) { caption = it }
        // Web parity (app/(protected)/media/page.tsx): the GPS block closes the form, after the
        // caption — it describes the upload rather than being one of the things being described.
        LocationAddressEditor(
            repository = repository,
            value = location,
            onUseGps = {
                permissionLauncher.launch(requiredAndroidPermissions())
                readLastKnownLocation(context)
            },
            onChange = { location = it },
            onMessage = { localMessage = it }
        )
        if (selectedUris.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = MaterialTheme.field.brandTile, shape = RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("${selectedUris.size} file(s) ready", color = Canvas, fontWeight = FontWeight.SemiBold)
                var batchExpanded by remember { mutableStateOf(false) }
                val batchCap = 8
                val shownBatch = if (batchExpanded) selectedUris else selectedUris.take(batchCap)
                shownBatch.forEach { uri ->
                    AndroidUriPreview(
                        context = context,
                        uri = uri,
                        onDownload = { saveLocalUriToDevice(context, uri) },
                        onRemove = { selectedUris = selectedUris.filterNot { it == uri } }
                    )
                }
                if (selectedUris.size > batchCap) {
                    TextButton(onClick = { batchExpanded = !batchExpanded }) {
                        Text(if (batchExpanded) "Show fewer" else "+${selectedUris.size - batchCap} more")
                    }
                }
                TextButton(onClick = { selectedUris = emptyList() }) {
                    Text("Clear batch")
                }
            }
        }
        localMessage?.let { Text(it, color = Muted, fontSize = 12.sp) }
        if (uploading) {
            LinearProgressIndicator(progress = { uploadFraction }, modifier = Modifier.fillMaxWidth())
            uploadProgressText?.let { Text(it, color = Muted, fontSize = 12.sp) }
        }
        Button(
            onClick = {
                scope.launch {
                    uploading = true
                    uploadFraction = 0f
                    uploadProgressText = null
                    val uris = selectedUris
                    // Sizes drive the overall %/ETA across the whole batch (cheap metadata reads).
                    val sizes = uris.map { queryMediaSize(context, it) }
                    val totalBytes = sizes.sum().coerceAtLeast(1L)
                    val startMs = System.currentTimeMillis()
                    var completedBytes = 0L
                    var success = 0
                    val failedUris = mutableListOf<Uri>()
                    var cancelled = false
                    uris.forEachIndexed { index, uri ->
                        if (cancelled) return@forEachIndexed
                        var lastPct = -1
                        val result = runCatching {
                            repository.uploadMedia(
                                context = context,
                                uri = uri,
                                linkedRecordType = linkedMode?.linkedRecordType() ?: "",
                                linkedRecordId = linkedEntryId,
                                caption = caption,
                                location = location,
                                titleHint = mediaTitle.ifBlank { caption },
                                batchIndex = index + 1,
                                onProgress = { sent, _ ->
                                    val done = completedBytes + sent
                                    val pct = ((done.toFloat() / totalBytes) * 100f).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        uploadFraction = pct / 100f
                                        val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
                                        val rate = if (elapsed > 0) done / elapsed else 0.0
                                        val eta = if (rate > 0) ((totalBytes - done) / rate).toLong() else -1L
                                        uploadProgressText = "File ${index + 1}/${uris.size} · $pct%" +
                                            (if (eta >= 0) " · ${formatEta(eta)}" else "")
                                    }
                                }
                            )
                        }
                        when {
                            result.isSuccess -> success++
                            result.exceptionOrNull() is kotlinx.coroutines.CancellationException -> cancelled = true
                            else -> failedUris.add(uri)
                        }
                        completedBytes += sizes[index]
                        uploadFraction = (completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                    }
                    uploading = false
                    uploadProgressText = null
                    uploadFraction = 0f
                    when {
                        cancelled -> Unit
                        failedUris.isEmpty() -> {
                            selectedUris = emptyList()
                            mediaTitle = ""
                            caption = ""
                            localMessage = null
                            uploadStatus = ActionStatus.SUCCESS
                            refreshMedia()
                            onUploaded(success)
                        }
                        else -> {
                            // Keep only the failed files staged so the user can retry just those.
                            selectedUris = failedUris.toList()
                            uploadStatus = ActionStatus.ERROR
                            val msg = if (success == 0) {
                                "All ${failedUris.size} file(s) failed to upload. Check your internet connection, then tap Upload to retry — the files are still staged."
                            } else {
                                "$success uploaded, ${failedUris.size} failed. Tap Upload to retry the remaining file(s)."
                            }
                            localMessage = msg
                            if (success > 0) refreshMedia()
                            onError(msg)
                        }
                    }
                }
            },
            enabled = selectedUris.isNotEmpty() && linkedMode != null && !uploading && !recording,
            // Keep the green/red visible even while the button is disabled (the batch clears on success).
            colors = if (uploadStatus == ActionStatus.IDLE) ButtonDefaults.buttonColors() else {
                val c = if (uploadStatus == ActionStatus.SUCCESS) SuccessGreen else FailureRed
                ButtonDefaults.buttonColors(
                    containerColor = c, contentColor = Color.White,
                    disabledContainerColor = c, disabledContentColor = Color.White
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    uploading -> "Uploading..."
                    uploadStatus == ActionStatus.SUCCESS -> "Uploaded ✓"
                    uploadStatus == ActionStatus.ERROR -> "Upload failed — tap to retry"
                    linkedMode == null -> "Choose a record type"
                    else -> "Upload batch"
                }
            )
        }
        if (savedMedia.isNotEmpty()) {
            Text("Recent saved media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            savedMedia.take(10).forEach { item ->
                AndroidSavedMediaPreview(
                    context = context,
                    media = item,
                    onDelete = {
                        scope.launch {
                            runCatching { repository.deleteMedia(item.id) }
                                .onSuccess {
                                    savedMedia = savedMedia.filterNot { it.id == item.id }
                                    localMessage = "Media removed"
                                }
                                .onFailure { error -> onError(error.message ?: "Unable to remove media") }
                        }
                    }
                )
            }
        }
    }
}

/** Best-effort byte size of a content Uri (for upload ETA); 0 when unknown. */
private fun queryMediaSize(context: Context, uri: Uri): Long {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)
}

/** Compact human estimate for an upload countdown, e.g. "~45s left" / "~2m 10s left". */
private fun formatEta(seconds: Long): String {
    if (seconds <= 0) return "almost done"
    if (seconds < 60) return "~${seconds}s left"
    return "~${seconds / 60}m ${seconds % 60}s left"
}

private fun mediaTypeFromMime(mime: String?): String = when {
    mime == null -> "DOCUMENT"
    mime.startsWith("image/") -> "IMAGE"
    mime.startsWith("video/") -> "VIDEO"
    mime.startsWith("audio/") -> "AUDIO"
    mime == "application/pdf" -> "PDF"
    else -> "DOCUMENT"
}

@Composable
private fun AndroidUriPreview(
    context: Context,
    uri: Uri,
    onRemove: (() -> Unit)? = null,
    progress: Float? = null,
    failed: Boolean = false,
    // Per-file actions. [onRetry] re-runs just this file's upload (shown when it failed); [onDownload]
    // saves a copy of the captured media straight to the device's Downloads — available regardless of
    // upload state, so the user never loses the media even when the network is failing.
    onRetry: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null
) {
    val mimeType = remember(uri) { context.contentResolver.getType(uri) }
    val mediaType = remember(mimeType) { mediaTypeFromMime(mimeType) }
    var showViewer by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        MediaThumb(
            uri = uri,
            mediaType = mediaType,
            title = uri.lastPathSegment.orEmpty(),
            subtitle = mimeType ?: "Unknown file type",
            onOpen = {
                if (mediaType in IN_APP_PLAYABLE) showViewer = true else openUri(context, uri, mimeType)
            }
        )
        if (onRemove != null) {
            DiscardBadge(
                contentDescription = "Discard ${uri.lastPathSegment.orEmpty()}",
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
    when {
        failed -> Text(
            "Upload failed — tap Retry, or Download to keep a copy on this device.",
            color = MaterialTheme.colorScheme.error,
            fontSize = 11.sp
        )
        progress != null && progress < 1f -> {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = Coral
            )
            Text("Uploading ${(progress * 100).toInt()}%", color = SurfaceCard, fontSize = 10.sp)
        }
        progress != null && progress >= 1f -> Text("Uploaded ✓", color = SuccessGreen, fontSize = 10.sp)
    }
    if (onRetry != null || onDownload != null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (failed && onRetry != null) {
                TextButton(
                    onClick = onRetry,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry", fontSize = 12.sp)
                }
            }
            if (onDownload != null) {
                TextButton(
                    onClick = onDownload,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download", fontSize = 12.sp)
                }
            }
        }
    }
    if (showViewer) {
        MediaViewerDialog(uri = uri, mediaType = mediaType, onDismiss = { showViewer = false })
    }
}

/** A small circular "✕" badge pinned to a media tile's top-right corner to discard/remove that file. */
@Composable
private fun DiscardBadge(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .padding(2.dp)
            .size(28.dp)
            .background(Color(0xCC1A1A1A), CircleShape)
    ) {
        Icon(Icons.Filled.Close, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun AndroidSavedMediaPreview(
    context: Context,
    media: com.fieldrepository.app.data.MediaFileDto,
    onDelete: (() -> Unit)? = null
) {
    val uri = remember(media.url) { media.url?.let(Uri::parse) }
    var showViewer by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (uri == null) {
        // No preview URL (e.g. an old/broken row) — still offer removal when allowed.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(media.originalFilename, color = Body, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (onDelete != null) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Remove", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxWidth()) {
            MediaThumb(
                uri = uri,
                mediaType = media.mediaType,
                title = media.originalFilename,
                subtitle = listOfNotNull(media.mimeType ?: media.mediaType, media.transcriptStatus?.let { "Transcript: $it" }).joinToString(" · "),
                onOpen = {
                    if (media.mediaType in IN_APP_PLAYABLE) showViewer = true else openUri(context, uri, media.mimeType)
                }
            )
            if (onDelete != null) {
                DiscardBadge(
                    contentDescription = "Remove ${media.originalFilename}",
                    onClick = { confirmDelete = true },
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { saveMediaToDevice(context, media.url, media.originalFilename, media.mimeType) }) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save to device", fontSize = 12.sp)
            }
            if (onDelete != null) {
                TextButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Remove", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (showViewer) {
            MediaViewerDialog(
                uri = uri,
                mediaType = media.mediaType,
                onSave = { saveMediaToDevice(context, media.url, media.originalFilename, media.mimeType) },
                onDismiss = { showViewer = false }
            )
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            title = { Text("Remove this media?") },
            text = { Text("\"${media.originalFilename}\" will be permanently deleted from this record and from storage.") }
        )
    }
}

private val IN_APP_PLAYABLE = setOf("IMAGE", "VIDEO", "AUDIO")

@Composable
private fun QuestionnaireForm(
    repository: FieldRepository,
    sections: List<QuestionnaireSectionDto>,
    artisans: List<ArtisanDto>,
    /** Whether [artisans] arrived, could not be reached, or is still coming. */
    lookupState: CarryScopeState = CarryScopeState.PENDING,
    canManageQuestionnaire: Boolean,
    prefill: Prefill? = null,
    editing: QuestionnaireInterviewDetailDto? = null,
    adminView: Boolean = false,
    onRefreshSections: suspend () -> Unit,
    onSync: suspend () -> Unit = onRefreshSections,
    onSubmit: suspend (QuestionnaireInterviewCreateRequest) -> String,
    onError: (String) -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEdit = editing != null
    var syncing by remember { mutableStateOf(false) }
    var saveState by remember { mutableStateOf(SaveState.IDLE) }
    var title by remember(editing) { mutableStateOf(editing?.title ?: prefill?.artisanName?.let { "Interview with $it" } ?: "") }
    var selectedArtisans by remember(editing) {
        mutableStateOf(
            editing?.artisans?.map { it.artisanId }?.toSet()
                ?: prefill?.artisanId?.let { setOf(it) }
                ?: emptySet()
        )
    }
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }
    // Hindi is the primary/default language; the dropdown lists English + the major Indian languages.
    var language by remember(editing) { mutableStateOf(editing?.language?.takeIf { it.isNotBlank() } ?: "Hindi") }
    var notes by remember(editing) { mutableStateOf(editing?.notes ?: "") }
    var capturedLocation by remember(editing) { mutableStateOf(editing?.location?.toRequest()) }
    val workshop = rememberWorkshopPicker(repository, isEdit, editing?.workshopId, editing)
    /**
     * Open on the artisan this researcher was last documenting.
     *
     * An interview is the record most often taken straight after a product or a tool — the artisan
     * is sitting right there — so the artisan is the one thing worth carrying in. Nothing narrower
     * transfers: an interview covers a person, not their products, and this form has no field for
     * one.
     */
    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.QUESTIONNAIRE_FORM,
        scopes = listOf(carryScope(CarryNode.ARTISAN, lookupState, artisans) { it.id }),
        handoff = prefill
    ) { carried ->
        carried.artisanId?.let { selectedArtisans = setOf(it) }
        carried.workshopId?.let { if (!workshop.isDirty()) workshop.applyDefault(it) }
    }
    /** "Change": drop the carried artisan so the researcher picks from scratch. */
    fun clearCarriedContext() {
        carry.change()
        selectedArtisans = emptySet()
    }
    // Status policy, identical to every other record form and to the web questionnaire page: a
    // professor+ picks any status (APPROVED by default on create), everyone below sees the locked
    // "Pending" chip. Without this control the interview was the ONE record type an interviewing
    // professor could not approve as they filed it — the request defaulted to PENDING regardless.
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }
    var titleError by remember { mutableStateOf<String?>(null) }
    val titleFocus = remember { FocusRequester() }
    val questions = remember(sections) { sections.flatMap { it.questions }.filter { it.isActive } }
    // Seed answers from existing responses so an interviewer can fill remaining questions.
    val answers = remember(questions, editing) {
        questions.associate { q ->
            q.id to mutableStateOf(editing?.responses?.firstOrNull { it.questionId == q.id }?.answerText ?: "")
        }
    }
    // Attach-media batch (photos/videos/audio/files) with eager upload + progress, exactly like the
    // other record forms — so questionnaire interviews can carry general media, with the array,
    // progress bar and previews visible while filling the form.
    val media = rememberMediaCaptureState()
    // Recorded/picked questionnaire audio clips get their OWN eager-upload batch so each clip streams
    // to storage the moment it is captured and shows in a live "N recording(s) attached" progress
    // card — exactly like the attach-media array on the other forms. Finalised with per-section /
    // per-question captions at save time (see the save handler below).
    val qMedia = rememberMediaCaptureState()
    MediaStagingEffect(repository = repository, media = qMedia)
    // The interview's already-saved media, loaded in edit mode so earlier recordings stay visible
    // (and aren't lost) — shown under the relevant section and in an "other media" block.
    var savedMedia by remember(editing?.id) { mutableStateOf<List<MediaFileDto>>(emptyList()) }
    LaunchedEffect(editing?.id) {
        val ed = editing ?: return@LaunchedEffect
        runCatching {
            // Show saved recordings/media from EVERY interview record for the same set of artisan(s),
            // not just the one opened — so a recording captured on a sibling save is visible (and not
            // lost) here too. De-duplicated by media id across the group.
            val key = interviewGroupKey(ed)
            val groupIds = runCatching { repository.interviews().filter { interviewGroupKey(it) == key }.map { it.id } }
                .getOrDefault(emptyList())
                .ifEmpty { listOf(ed.id) }
                .let { if (ed.id in it) it else it + ed.id }
            val byId = LinkedHashMap<String, MediaFileDto>()
            groupIds.forEach { gid ->
                runCatching { repository.mediaForRecord("questionnaire", gid) }.getOrDefault(emptyList())
                    .forEach { byId[it.id] = it }
            }
            byId.values.toList()
        }.onSuccess { savedMedia = it }
    }
    // The section the user most recently added to / updated this session — drives the green
    // "Most recent changes by you were made over here ↑" pointer shown beneath that section.
    var lastEditedSectionId by remember { mutableStateOf<String?>(null) }
    // Clips keyed by target: a question id (individual mode) or "section:<id>" (whole-section mode).
    var questionAudio by remember { mutableStateOf<Map<String, List<Uri>>>(emptyMap()) }
    fun sectionIdForKey(key: String): String? =
        if (key.startsWith("section:")) key.removePrefix("section:")
        else sections.firstOrNull { sec -> sec.questions.any { it.id == key } }?.id
    // Forget a clip's eager-upload state and delete its staged (not-yet-saved) object from storage.
    fun dropStaged(uri: Uri) {
        val deferred = qMedia.stagedDeferred[uri]
        qMedia.forget(uri)
        qMedia.uris = qMedia.uris.filterNot { it == uri }
        AppScope.io.launch { runCatching { deferred?.await()?.let { repository.deleteStaged(it.objectKey) } } }
    }
    fun addClip(key: String, uri: Uri) {
        questionAudio = questionAudio + (key to ((questionAudio[key] ?: emptyList()) + uri))
        // Mirror into the eager-upload batch so it starts streaming + shows in the progress card.
        qMedia.uris = qMedia.uris + uri
        lastEditedSectionId = sectionIdForKey(key)
    }
    fun removeLastClip(key: String) {
        val list = questionAudio[key] ?: return
        val removed = list.lastOrNull()
        questionAudio = questionAudio + (key to list.dropLast(1))
        if (removed != null) dropStaged(removed)
        lastEditedSectionId = sectionIdForKey(key)
    }
    // Remove a single clip by uri (used by the progress card's per-file ✕), keeping both the keyed
    // map and the eager-upload batch in sync no matter where the removal was triggered.
    fun removeClipUri(uri: Uri) {
        val entry = questionAudio.entries.firstOrNull { uri in it.value } ?: return
        questionAudio = questionAudio + (entry.key to entry.value.filterNot { it == uri })
        dropStaged(uri)
        lastEditedSectionId = sectionIdForKey(entry.key)
    }
    // Whether a saved media item (by its caption) belongs to a given section — used to surface
    // earlier recordings under the right section in edit mode. Exact match on the captions this form
    // writes, with a resilient prefix fallback if a prompt/title was edited after recording.
    fun captionBelongsToSection(caption: String?, section: QuestionnaireSectionDto): Boolean {
        val cap = caption?.trim().orEmpty()
        if (cap.isEmpty()) return false
        val expected = buildSet {
            add("Section audio: ${section.code} ${section.title}".trim())
            section.questions.forEach { q -> add("Question audio: ${q.sectionCode}${q.sortOrder} ${q.prompt}".trim()) }
        }
        if (cap in expected) return true
        if (cap.startsWith("Section audio:")) {
            val rest = cap.removePrefix("Section audio:").trim()
            return rest == section.code || rest.startsWith("${section.code} ")
        }
        if (cap.startsWith("Question audio:")) {
            val rest = cap.removePrefix("Question audio:").trim()
            return rest.startsWith(section.code) && rest.length > section.code.length && rest[section.code.length].isDigit()
        }
        return false
    }
    // One take for the whole section is how these interviews are actually conducted: the researcher
    // sits down with the artisan and talks through the section, rather than starting and stopping a
    // recorder between every question. Per-question capture stays a click away for the times it is
    // wanted, but it is not the shape of the work and so it is not the default.
    var recordMode by remember { mutableStateOf("SECTION") }
    var expandedSections by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBuilder by remember { mutableStateOf(false) }

    if (canManageQuestionnaire && !isEdit) {
        // Defer the heavy builder so opening the questionnaire tab never composes hundreds of rows at once.
        OutlinedButton(onClick = { showBuilder = !showBuilder }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showBuilder) "Hide questionnaire builder" else "Open questionnaire builder")
        }
        if (showBuilder) {
            QuestionnaireBuilder(repository, sections, onRefreshSections, onError)
        }
    }
    // Available to every user, including least-privilege: pull the latest sections/questions
    // (and artisans) from the database on demand. Flashes green "Synchronised" for 5s on success,
    // red on failure.
    var syncStatus by remember { mutableStateOf(ActionStatus.IDLE) }
    AutoResetStatus(syncStatus) { syncStatus = ActionStatus.IDLE }
    val syncContainer = when (syncStatus) {
        ActionStatus.SUCCESS -> SuccessGreen
        ActionStatus.ERROR -> FailureRed
        ActionStatus.IDLE -> MaterialTheme.colorScheme.primary
    }
    Button(
        onClick = {
            if (syncing) return@Button
            scope.launch {
                syncing = true
                runCatching { onSync() }
                    .onSuccess { syncStatus = ActionStatus.SUCCESS }
                    .onFailure {
                        if (it !is kotlinx.coroutines.CancellationException) {
                            syncStatus = ActionStatus.ERROR
                            onError(it.message ?: "Unable to synchronize")
                        }
                    }
                syncing = false
            }
        },
        enabled = !syncing,
        colors = ButtonDefaults.buttonColors(containerColor = syncContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                syncing -> "Synchronizing…"
                syncStatus == ActionStatus.SUCCESS -> "Synchronised ✓"
                syncStatus == ActionStatus.ERROR -> "Sync failed — tap to retry"
                else -> "Synchronize with Database"
            }
        )
    }

    // Completion overview, collapsed by default, sitting right under the sync control. Composing the
    // matrix (and loading it) is deferred until expanded. Kept in View Data too.
    val questionnaireIsAdmin = remember { repository.cachedUser()?.role in setOf("ADMIN", "MASTER_ADMIN") }
    var showCompletion by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showCompletion = !showCompletion }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (showCompletion) "Hide completion overview" else "Check completion")
    }
    if (showCompletion) {
        // `canOverride={adminMode && isAdmin(user)}` on the web's questionnaire page: the role is
        // checked first, and admin view can only take the override away.
        CompletionMatrixCard(
            repository = repository,
            artisanId = null,
            canEdit = questionnaireIsAdmin && adminView,
            onError = onError
        )
    }

    RecordCard(title = if (isEdit) "Edit interview" else "Add questionnaire interview") {
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        if (isEdit) {
            Text("Add or update answers below. Existing answers from other interviewers are preserved unless you change them.", color = Muted, fontSize = 12.sp)
        }
        // Above the workshop picker, so what was filled in is read before any of the fields it filled.
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        WorkshopField(state = workshop, saving = saveState == SaveState.SAVING)
        RequiredInput("Interview title", title, titleError, titleFocus, titleCased = true) { title = it }
        // Web parity (app/(protected)/questionnaire/page.tsx): title → place → language → status →
        // the artisans this interview is about. There is deliberately NO date field: the server
        // derives interviewDate from recordedAt, which is when the interview was actually captured.
        TextInput("Place", place, titleCased = true) { place = it }
        // Language of the interview: Hindi primary, then English + the major scheduled Indian
        // languages. Any pre-existing free-text value is preserved as an extra option.
        val languageOptions = remember(language) {
            val base = listOf(
                "Hindi", "English", "Bengali", "Marathi", "Telugu", "Tamil", "Gujarati", "Urdu",
                "Kannada", "Odia", "Malayalam", "Punjabi", "Assamese", "Maithili", "Sanskrit",
                "Konkani", "Nepali", "Manipuri (Meitei)", "Bodo", "Dogri", "Kashmiri", "Santali",
                "Sindhi", "Other"
            )
            val withExisting = if (language.isNotBlank() && base.none { it.equals(language, ignoreCase = true) }) {
                listOf(language) + base
            } else base
            withExisting.map { it to it }
        }
        DropdownField(
            label = "Language",
            options = languageOptions,
            selectedValue = language,
            placeholder = "Select language",
            includeNone = false
        ) { language = it }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        ArtisanMultiSelectField(
            label = "Linked artisans",
            artisans = artisans,
            selectedIds = selectedArtisans
        ) { id ->
            val adding = !selectedArtisans.contains(id)
            selectedArtisans = if (adding) selectedArtisans + id else selectedArtisans - id
            // Naming an artisan is an explicit pick, so it replaces the remembered context and
            // retires the banner: from here on the selection is the researcher's own, not a
            // suggestion. Only on the way IN — unticking says who the interview is not about, which
            // is no statement about where the researcher is sitting.
            if (adding) {
                artisans.firstOrNull { it.id == id }?.let {
                    carry.remember(
                        CarryContext(
                            artisanId = it.id,
                            artisanName = it.name,
                            place = it.place,
                            craftId = it.craftId,
                            craftName = it.craft?.name
                        ),
                        explicit = true
                    )
                }
            }
        }
        DropdownField(
            label = "Recording mode",
            options = listOf(
                "INDIVIDUAL" to "Record individual questions",
                "SECTION" to "Record the entire section at once"
            ),
            selectedValue = recordMode,
            includeNone = false
        ) { recordMode = it }
        // On by default: keep the UI to just the record button; reveal answer boxes only on demand.
        var hideAnswers by remember { mutableStateOf(true) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Do not display answer text boxes", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Text("On by default — show only the record button. Toggle off to type written answers.", color = Muted, fontSize = 11.sp)
            }
            Switch(checked = hideAnswers, onCheckedChange = { hideAnswers = it })
        }
        LocationAddressEditor(
            repository = repository,
            value = capturedLocation,
            onUseGps = { readLastKnownLocation(context) },
            onChange = { capturedLocation = it },
            onMessage = onError
        )
        Text("Tap a section to answer its questions. Only answered questions are saved.", color = Muted, fontSize = 12.sp)
        sections.forEach { section ->
            val activeQuestions = section.questions.filter { it.isActive }
            if (activeQuestions.isNotEmpty()) {
                val expanded = expandedSections.contains(section.id)
                val sectionSavedMedia = if (isEdit) savedMedia.filter { captionBelongsToSection(it.caption, section) } else emptyList()
                // A question counts as "answered" if it has a typed answer OR a recording. Reading the
                // reactive `questionAudio` map here is what makes the count update the moment a clip is
                // recorded/attached (not only when text is typed). Live per-question/section clips AND
                // already-saved recordings (edit mode) both count; a whole-section recording marks every
                // question in that section as answered.
                val sectionRecorded = (questionAudio["section:${section.id}"]?.isNotEmpty() == true) ||
                    sectionSavedMedia.any { it.caption?.trim()?.startsWith("Section audio:") == true }
                val answeredCount = if (sectionRecorded) activeQuestions.size else activeQuestions.count { q ->
                    val hasText = (answers[q.id]?.value?.trim().orEmpty()).isNotEmpty()
                    val hasLiveClip = questionAudio[q.id]?.isNotEmpty() == true
                    val hasSavedClip = sectionSavedMedia.any { m ->
                        val cap = m.caption?.trim().orEmpty()
                        cap.startsWith("Question audio:") && run {
                            val rest = cap.removePrefix("Question audio:").trim()
                            rest == "${q.sectionCode}${q.sortOrder}" || rest.startsWith("${q.sectionCode}${q.sortOrder} ")
                        }
                    }
                    hasText || hasLiveClip || hasSavedClip
                }
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedSections = if (expanded) expandedSections - section.id else expandedSections + section.id
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${section.code}. ${section.title}", display = true, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
                                Text(
                                    "${activeQuestions.size} questions · $answeredCount answered" +
                                        (if (sectionSavedMedia.isNotEmpty()) " · ${sectionSavedMedia.size} saved recording(s)" else ""),
                                    color = Muted,
                                    fontSize = 11.sp
                                )
                            }
                            Text(if (expanded) "Hide ▲" else "Open ▼", color = Body, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        // Only the expanded section composes its inputs, which keeps the screen responsive.
                        if (expanded) {
                            if (recordMode == "SECTION") {
                                // One consolidated recording for the whole section.
                                val sectionKey = "section:${section.id}"
                                Text("Record this entire section in one take.", color = Muted, fontSize = 12.sp)
                                AudioClipRecorder(
                                    clips = questionAudio[sectionKey] ?: emptyList(),
                                    onAddClip = { uri -> addClip(sectionKey, uri) },
                                    onRemoveLast = { removeLastClip(sectionKey) },
                                    onError = onError,
                                    idleLabel = "Record section ●"
                                )
                                HorizontalDivider()
                            }
                            activeQuestions.forEach { question ->
                                Text("${question.sortOrder}. ${question.prompt}", color = Muted, fontSize = 12.sp)
                                if (recordMode == "INDIVIDUAL") {
                                    AudioClipRecorder(
                                        clips = questionAudio[question.id] ?: emptyList(),
                                        onAddClip = { uri -> addClip(question.id, uri) },
                                        onRemoveLast = { removeLastClip(question.id) },
                                        onError = onError
                                    )
                                }
                                if (!hideAnswers) {
                                    TextInput("Answer", answers[question.id]?.value.orEmpty(), minLines = 3) { value ->
                                        answers[question.id]?.let { state -> state.value = value }
                                        lastEditedSectionId = section.id
                                    }
                                }
                            }
                            // Per-section live upload-progress card: the clips recorded/picked for THIS
                            // section (its questions in individual mode, or its one consolidated clip in
                            // section mode), so the user can verify this section's recordings on the go —
                            // in addition to the all-recordings card at the very bottom.
                            val sectionUploadUris = questionAudio.entries
                                .filter { sectionIdForKey(it.key) == section.id }
                                .flatMap { it.value }
                            if (sectionUploadUris.isNotEmpty()) {
                                HorizontalDivider()
                                Text("This section's recordings (uploading)", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                AttachedUploadsCard(
                                    context = context,
                                    media = qMedia,
                                    label = "recording",
                                    repository = repository,
                                    uris = sectionUploadUris
                                ) { uri -> removeClipUri(uri) }
                            }
                            // In edit mode, surface this section's already-saved recordings/media so the
                            // user can see (and not lose) what was captured earlier, right where it belongs.
                            if (isEdit && sectionSavedMedia.isNotEmpty()) {
                                HorizontalDivider()
                                Text("Saved recordings & media for this section", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                sectionSavedMedia.forEach { MediaWithTranscript(context, it, repository) }
                            }
                        }
                    }
                }
                // Green pointer beneath the section the user most recently added to / updated this session.
                if (lastEditedSectionId == section.id) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp)
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                        Text("Most recent changes by you were made over here", color = SuccessGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        // Live upload-progress card for the recorded/picked question & section audio clips: each
        // streams to storage as you record it, with a progress bar and "all uploaded ✓ — ready to
        // save" status, just like the attach-media array. Removing a clip here also clears it from
        // the matching question/section.
        if (qMedia.uris.isNotEmpty()) {
            HorizontalDivider()
            Text("All recordings (every section)", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text("Every question/section clip across the whole interview, uploading as you record. They link to this interview on save.", color = Muted, fontSize = 12.sp)
            AttachedUploadsCard(context = context, media = qMedia, label = "recording", repository = repository) { uri -> removeClipUri(uri) }
        }
        // Any saved media not tied to a specific section (general attachments, or recordings whose
        // prompt/title changed) — shown in edit mode so nothing is ever hidden or lost.
        if (isEdit) {
            val otherSavedMedia = savedMedia.filterNot { m -> sections.any { captionBelongsToSection(m.caption, it) } }
            if (otherSavedMedia.isNotEmpty()) {
                HorizontalDivider()
                Text("Other saved recordings & media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                otherSavedMedia.forEach { MediaWithTranscript(context, it, repository) }
            }
        }
        // Attach photos, video, audio files and other media to this interview (with live upload
        // progress) — the same media array used by every other record form.
        MediaCaptureSection(repository = repository, media = media, onMessage = onError, onError = onError)
        MultiNoteInput(value = notes) { notes = it }
        fun submit() {
            if (!validateRequired(listOf(
                    RequiredCheck(title.isBlank(), { titleError = it }, titleFocus)
                ))) { onError("Please fill the required field highlighted above."); return }
                // Asked of a new interview even when it will FOLD into an existing one for the same
                // artisan set: POST /questionnaire/interviews validates the whole body before it
                // decides which of the two it is doing, so the stated address is required either way.
                newRecordLocationError(isEdit, capturedLocation)?.let { onError(it); return }
                scope.launch {
                    // Late-submission gate first, so the save button does not sit in "Saving…" while
                    // the confirmation is on screen.
                    if (!workshop.confirmSubmission()) return@launch
                    saveState = SaveState.SAVING
                    val now = Instant.now().toString()
                    // Only send answers this interviewer actually added or changed; untouched answers
                    // (including those entered by other interviewers) are left exactly as they were.
                    val responsesToSend = questions.mapNotNull { question ->
                        val current = answers[question.id]?.value?.trim().orEmpty()
                        val initial = editing?.responses?.firstOrNull { it.questionId == question.id }?.answerText?.trim().orEmpty()
                        if (current.isNotBlank() && current != initial) {
                            QuestionnaireResponseRequest(questionId = question.id, answerText = current)
                        } else null
                    }
                    // Offline: queue the interview + every recorded clip (with its section/question
                    // nomenclature) and attachments, to upload on reconnect. New interviews only.
                    if (!isEdit && !repository.isOnline(context)) {
                        val ok = runCatching {
                            val request = QuestionnaireInterviewCreateRequest(
                                title = title.trim(),
                                place = place.blankToNull(),
                                language = language.blankToNull(),
                                notes = notes.blankToNull(),
                                status = status,
                                artisanIds = selectedArtisans.toList(),
                                workshopId = workshop.value(),
                                location = capturedLocation,
                                responses = responsesToSend,
                                recordedAt = now
                            )
                            val questionsById = questions.associateBy { it.id }
                            val sectionsById = sections.associateBy { it.id }
                            val items = mutableListOf<com.fieldrepository.app.data.OfflineMediaSpec>()
                            questionAudio.forEach { (key, uris) ->
                                val caption: String
                                val hint: String
                                val sectionCodePart: String?
                                val questionNumberPart: String?
                                if (key.startsWith("section:")) {
                                    val section = sectionsById[key.removePrefix("section:")]
                                    caption = "Section audio: ${section?.code ?: ""} ${section?.title ?: ""}".trim()
                                    hint = title.ifBlank { section?.title ?: "Section recording" }
                                    sectionCodePart = section?.code
                                    questionNumberPart = "SEC"
                                } else {
                                    val question = questionsById[key]
                                    caption = "Question audio: ${question?.sectionCode ?: ""}${question?.sortOrder ?: ""} ${question?.prompt ?: ""}".trim()
                                    hint = title.ifBlank { question?.prompt ?: "Question recording" }
                                    sectionCodePart = question?.sectionCode
                                    questionNumberPart = question?.sortOrder?.toString()
                                }
                                uris.forEachIndexed { index, uri ->
                                    val baseName = questionnaireClipBaseName(context, sectionCodePart, questionNumberPart, title, uri)
                                        .let { if (uris.size > 1) "${it}_${index + 1}" else it }
                                    items.add(com.fieldrepository.app.data.OfflineMediaSpec(
                                        uri = uri, caption = caption, recordName = hint,
                                        overrideBaseName = baseName, batchIndex = index + 1))
                                }
                            }
                            media.uris.forEachIndexed { i, uri ->
                                items.add(com.fieldrepository.app.data.OfflineMediaSpec(
                                    uri = uri,
                                    caption = "Field media for ${title.trim().ifBlank { "interview" }}",
                                    recordName = title.ifBlank { "Interview" },
                                    batchIndex = i + 1))
                            }
                            repository.queueOfflineEntry(context, "questionnaire", offlineFormJson.encodeToString(request),
                                title.trim().ifBlank { "Interview" }, items)
                        }.isSuccess
                        if (ok) {
                            media.reset(); qMedia.reset(); questionAudio = emptyMap()
                            title = ""; selectedArtisans = emptySet(); place = ""; language = "Hindi"; notes = ""
                            status = defaultCreateStatus(repository.cachedUser()?.role)
                            capturedLocation = null; answers.values.forEach { it.value = "" }
                            // Interviews are usually captured back-to-back at one workshop, so the
                            // selection carries over — re-baselined so it isn't flagged as unsaved.
                            workshop.markSaved()
                            saveState = SaveState.SAVED
                            delay(SAVED_CONFIRM_MS)
                            onSaved()
                        } else {
                            saveState = SaveState.IDLE
                            onError("Couldn't save offline")
                        }
                        return@launch
                    }
                    runCatching {
                        val interviewId = if (isEdit) {
                            val original = editing!!
                            val originalArtisans = original.artisans.map { it.artisanId }.toSet()
                            repository.updateQuestionnaireInterview(
                                original.id,
                                QuestionnaireInterviewUpdateRequest(
                                    title = title.trim(),
                                    place = place.blankToNull(),
                                    language = language.blankToNull(),
                                    notes = notes.blankToNull(),
                                    // Unauthorized status changes are dropped server-side either way.
                                    status = status,
                                    artisanIds = if (selectedArtisans != originalArtisans) selectedArtisans.toList() else null,
                                    responses = responsesToSend.ifEmpty { null },
                                    workshopId = workshop.value(),
                                    location = locationForBody(true, capturedLocation, original.location)
                                )
                            )
                            original.id
                        } else {
                            onSubmit(
                                QuestionnaireInterviewCreateRequest(
                                    title = title.trim(),
                                    place = place.blankToNull(),
                                    language = language.blankToNull(),
                                    notes = notes.blankToNull(),
                                    status = status,
                                    artisanIds = selectedArtisans.toList(),
                                    workshopId = workshop.value(),
                                    location = capturedLocation,
                                    responses = responsesToSend,
                                    recordedAt = now
                                )
                            )
                        }
                        // Upload all recorded clips, whether keyed by question id or by section.
                        val questionsById = questions.associateBy { it.id }
                        val sectionsById = sections.associateBy { it.id }
                        questionAudio.forEach { (key, uris) ->
                            val caption: String
                            val hint: String
                            // Nomenclature parts: section code + question number (or "SEC" for a whole
                            // section take), and the interview name — fed into the per-clip filename
                            // SECTION_QUESTION_INTERVIEWNAME_DURATIONHHMMSS_DATETIMEDDMMYYYYHHMM.
                            val sectionCodePart: String?
                            val questionNumberPart: String?
                            if (key.startsWith("section:")) {
                                val section = sectionsById[key.removePrefix("section:")]
                                caption = "Section audio: ${section?.code ?: ""} ${section?.title ?: ""}".trim()
                                hint = title.ifBlank { section?.title ?: "Section recording" }
                                sectionCodePart = section?.code
                                questionNumberPart = "SEC"
                            } else {
                                val question = questionsById[key]
                                caption = "Question audio: ${question?.sectionCode ?: ""}${question?.sortOrder ?: ""} ${question?.prompt ?: ""}".trim()
                                hint = title.ifBlank { question?.prompt ?: "Question recording" }
                                sectionCodePart = question?.sectionCode
                                questionNumberPart = question?.sortOrder?.toString()
                            }
                            uris.forEachIndexed { index, uri ->
                                val baseName = questionnaireClipBaseName(
                                    context = context,
                                    sectionCode = sectionCodePart,
                                    questionNumber = questionNumberPart,
                                    interviewName = title,
                                    uri = uri
                                // Append the clip index when a target has more than one clip, so two
                                // recordings of the same question in the same minute never collide.
                                ).let { if (uris.size > 1) "${it}_${index + 1}" else it }
                                // Prefer the eagerly pre-uploaded object (awaiting any still-in-flight
                                // transfer); only fall back to a fresh upload if staging never ran/failed.
                                val staged = qMedia.stagedDeferred[uri]?.let { runCatching { it.await() }.getOrNull() }
                                    ?: qMedia.staged[uri]
                                if (staged != null) {
                                    repository.completeStaged(
                                        staged = staged,
                                        linkedRecordType = "questionnaire",
                                        linkedRecordId = interviewId,
                                        recordName = hint,
                                        caption = caption,
                                        location = null,
                                        batchIndex = index + 1,
                                        overrideBaseName = baseName
                                    )
                                } else {
                                    repository.uploadMedia(
                                        context = context,
                                        uri = uri,
                                        linkedRecordType = "questionnaire",
                                        linkedRecordId = interviewId,
                                        caption = caption,
                                        location = null,
                                        titleHint = hint,
                                        batchIndex = index + 1,
                                        overrideBaseName = baseName
                                    )
                                }
                            }
                        }
                        // General attach-media batch (photos/videos/files/extra audio) — eager-uploaded
                        // with progress while filling the form, finalised and linked to the interview here.
                        media.location = capturedLocation
                        uploadAttachments(
                            repository, context, media, "questionnaire", interviewId,
                            title.ifBlank { "Interview" },
                            "Field media for ${title.trim().ifBlank { "interview" }}"
                        )
                    }.onFailure {
                        saveState = SaveState.IDLE
                        onError(it.message ?: "Unable to save questionnaire")
                        return@launch
                    }
                    // Clear the staged-media bookkeeping so leaving the form doesn't delete the objects
                    // we just linked (the dispose cleanup only runs when uris are still pending).
                    media.reset()
                    // The recorded clips are now persisted on the interview — clear the eager-upload
                    // bookkeeping (so leaving doesn't delete the just-linked objects) and the keyed map
                    // (so a second save can't re-upload them).
                    qMedia.reset()
                    questionAudio = emptyMap()
                    // Bank the sitting: the interview itself does not join the bag — nothing else
                    // links to one — but the artisan it was taken with is exactly where the
                    // researcher still is.
                    artisans.firstOrNull { it.id in selectedArtisans }?.let { interviewed ->
                        carry.remember(
                            CarryContext(
                                artisanId = interviewed.id,
                                artisanName = interviewed.name,
                                place = interviewed.place,
                                craftId = interviewed.craftId,
                                craftName = interviewed.craft?.name,
                                workshopId = workshop.value(),
                                workshopName = workshop.workshops.firstOrNull { it.id == workshop.value() }?.title
                            )
                        )
                    }
                    if (!isEdit) {
                        title = ""
                        selectedArtisans = emptySet()
                        place = ""
                        language = "Hindi"
                        notes = ""
                        status = defaultCreateStatus(repository.cachedUser()?.role)
                        capturedLocation = null
                        answers.values.forEach { it.value = "" }
                        // The workshop stays selected for the next interview (same field session),
                        // re-baselined so the carry-over isn't reported as an unsaved change.
                        workshop.markSaved()
                    }
                    saveState = SaveState.SAVED
                    delay(SAVED_CONFIRM_MS)
                    onSaved()
                }
        }
        val qSig: () -> String = {
            // The offer resolves a beat after the first composition, so until it does the handoff it
            // was built from stands in — otherwise the baseline and the prefill would disagree for
            // one frame and an untouched form would come out of it reading as edited.
            val carriedArtisan = carry.offer?.context?.artisanId ?: prefill?.artisanId
            listOf(title, place, language, notes, status,
                selectedArtisans.filterNot { it == carriedArtisan }.sorted().joinToString(","),
                answers.entries.joinToString("|") { "${it.key}=${it.value.value}" }).joinToString("")
        }
        val initialSig = remember(editing) { qSig() }
        // Any changed field, an unsaved general attachment, or an unsaved recorded clip makes the
        // interview "dirty" so an accidental Back offers to save it (including in-progress recordings).
        val dirty = qSig() != initialSig || workshop.isDirty() || qMedia.uris.isNotEmpty() || media.uris.isNotEmpty()
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        SaveButton(
            state = saveState,
            idleLabel = if (isEdit) "Update interview" else "Save questionnaire"
        ) { submit() }
        Text("Recordings and attached media upload as you go and link to this interview automatically; audio is queued for transcription.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun QuestionnaireBuilder(
    repository: FieldRepository,
    sections: List<QuestionnaireSectionDto>,
    onRefresh: suspend () -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var newCode by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }
    // Sections render collapsed by default; a section's editors + questions are only composed when it
    // is expanded, so opening the builder never composes hundreds of fields at once (was an ANR/crash).
    var expandedSections by remember { mutableStateOf(setOf<String>()) }

    RecordCard(title = "Questionnaire builder") {
        Text("Master admin controls for adding, editing, removing, moving sections, and moving questions between sections. Tap a section to expand and edit it.", color = Muted, fontSize = 12.sp)
        TextInput("New section code", newCode) { newCode = it }
        TextInput("New section title", newTitle) { newTitle = it }
        Button(
            onClick = {
                scope.launch {
                    runCatching {
                        repository.createQuestionnaireSection(QuestionnaireSectionCreateRequest(code = newCode.trim(), title = newTitle.trim()))
                        newCode = ""
                        newTitle = ""
                        onRefresh()
                    }.onFailure { onError(it.message ?: "Unable to add section") }
                }
            },
            enabled = newCode.isNotBlank() && newTitle.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add section") }

        sections.forEachIndexed { sectionIndex, section ->
            val expanded = expandedSections.contains(section.id)
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedSections = if (expanded) expandedSections - section.id else expandedSections + section.id
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${section.sortOrder}. ${section.code} - ${section.title}", display = true, fontWeight = FontWeight.SemiBold)
                            Text("${section.questions.size} question(s)", color = Muted, fontSize = 11.sp)
                        }
                        Text(if (expanded) "Hide ▲" else "Edit ▼", color = Body, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    // Heavy editors + per-question rows compose only for the expanded section.
                    if (expanded) {
                        var code by remember(section.id, section.code) { mutableStateOf(section.code) }
                        var sectionTitle by remember(section.id, section.title) { mutableStateOf(section.title) }
                        var newPrompt by remember(section.id) { mutableStateOf("") }
                        TextInput("Code", code) { code = it }
                        TextInput("Title", sectionTitle) { sectionTitle = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            repository.updateQuestionnaireSection(section.id, QuestionnaireSectionUpdateRequest(code = code.trim(), title = sectionTitle.trim(), isActive = true))
                                            onRefresh()
                                        }.onFailure { onError(it.message ?: "Unable to update section") }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Save") }
                            OutlinedButton(
                                enabled = sectionIndex > 0,
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            repository.reorderQuestionnaireSections(moveIds(sections.map { it.id }, sectionIndex, -1))
                                            onRefresh()
                                        }.onFailure { onError(it.message ?: "Unable to move section") }
                                    }
                                }
                            ) { Text("Up") }
                            OutlinedButton(
                                enabled = sectionIndex < sections.lastIndex,
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            repository.reorderQuestionnaireSections(moveIds(sections.map { it.id }, sectionIndex, 1))
                                            onRefresh()
                                        }.onFailure { onError(it.message ?: "Unable to move section") }
                                    }
                                }
                            ) { Text("Down") }
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        repository.deleteQuestionnaireSection(section.id)
                                        onRefresh()
                                    }.onFailure { onError(it.message ?: "Unable to remove section") }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Remove section") }

                        section.questions.forEachIndexed { questionIndex, question ->
                            var prompt by remember(question.id, question.prompt) { mutableStateOf(question.prompt) }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(color = Canvas, shape = RoundedCornerShape(12.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("${question.sortOrder}. ${question.sectionCode} ${question.prompt}", color = Muted, fontSize = 12.sp)
                                TextInput("Prompt", prompt, minLines = 2) { prompt = it }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                runCatching {
                                                    repository.updateQuestionnaireQuestion(question.id, QuestionnaireQuestionUpdateRequest(prompt = prompt.trim(), isActive = true))
                                                    onRefresh()
                                                }.onFailure { onError(it.message ?: "Unable to update question") }
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Save") }
                                    OutlinedButton(
                                        enabled = questionIndex > 0,
                                        onClick = {
                                            scope.launch {
                                                runCatching {
                                                    repository.reorderQuestionnaireQuestions(section.id, moveIds(section.questions.map { it.id }, questionIndex, -1))
                                                    onRefresh()
                                                }.onFailure { onError(it.message ?: "Unable to move question") }
                                            }
                                        }
                                    ) { Text("Up") }
                                    OutlinedButton(
                                        enabled = questionIndex < section.questions.lastIndex,
                                        onClick = {
                                            scope.launch {
                                                runCatching {
                                                    repository.reorderQuestionnaireQuestions(section.id, moveIds(section.questions.map { it.id }, questionIndex, 1))
                                                    onRefresh()
                                                }.onFailure { onError(it.message ?: "Unable to move question") }
                                            }
                                        }
                                    ) { Text("Down") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        enabled = sectionIndex > 0,
                                        onClick = {
                                            scope.launch {
                                                runCatching {
                                                    repository.updateQuestionnaireQuestion(question.id, QuestionnaireQuestionUpdateRequest(sectionId = sections[sectionIndex - 1].id))
                                                    onRefresh()
                                                }.onFailure { onError(it.message ?: "Unable to move question") }
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Prev section") }
                                    OutlinedButton(
                                        enabled = sectionIndex < sections.lastIndex,
                                        onClick = {
                                            scope.launch {
                                                runCatching {
                                                    repository.updateQuestionnaireQuestion(question.id, QuestionnaireQuestionUpdateRequest(sectionId = sections[sectionIndex + 1].id))
                                                    onRefresh()
                                                }.onFailure { onError(it.message ?: "Unable to move question") }
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Next section") }
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            runCatching {
                                                repository.deleteQuestionnaireQuestion(question.id)
                                                onRefresh()
                                            }.onFailure { onError(it.message ?: "Unable to remove question") }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Remove question") }
                            }
                        }

                        TextInput("New question", newPrompt, minLines = 2) { newPrompt = it }
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        repository.createQuestionnaireQuestion(QuestionnaireQuestionCreateRequest(sectionId = section.id, prompt = newPrompt.trim()))
                                        newPrompt = ""
                                        onRefresh()
                                    }.onFailure { onError(it.message ?: "Unable to add question") }
                                }
                            },
                            enabled = newPrompt.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add question") }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserManagementForm(
    repository: FieldRepository,
    isMasterAdmin: Boolean,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    fun refreshUsers() {
        scope.launch {
            loading = true
            runCatching { repository.users() }
                .onSuccess { users = it }
                .onFailure { onError(it.message ?: "Unable to load users") }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshUsers()
    }
    // Each user is collapsed by default (an accordion); tapping the header expands it to reveal the
    // grantable privileges, so a long user list stays scannable.
    var expandedUsers by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Who is doing the managing. Read from the cached profile rather than threaded in, so the rules
    // below can mirror `assert_role` / `assert_can_manage_target` without changing every call site.
    val actor = remember { repository.cachedUser() }
    val actorRank = remember(actor) { roleRank(actor?.role) }
    val actorIsMaster = isMasterAdmin
    val actorIsAdmin = actorRank >= RANK_ADMIN
    // `assert_role`: a user may assign roles at or below their OWN tier, and only the master admin
    // can mint another master admin. Highest first, matching the web picker's order.
    val assignableRoles = remember(actorRank, actorIsMaster) {
        ROLE_RANK.entries
            .filter { it.value <= actorRank && (it.key != "MASTER_ADMIN" || actorIsMaster) }
            .sortedByDescending { it.value }
            .map { it.key to roleLabel(it.key) }
    }

    RecordCard(title = "Users and access") {
        Text(
            "Professors and above can move a user along the six-tier ladder (never above their own " +
                "tier); admins can additionally grant or revoke questionnaire-builder, record " +
                "review & approval, view-provenance and dataset-download access. Craft and workshop " +
                "creation are not grantable — they come with Professor, so promote instead. Tap a " +
                "user to expand and manage them.",
            color = Muted,
            fontSize = 12.sp
        )
        if (loading) {
            Text("Loading users...", color = Muted)
        }
        users.forEach { appUser ->
            val isMaster = appUser.role == "MASTER_ADMIN"
            // A grant is moot once the target reaches Professor: the rank already confers the power,
            // exactly as `has_rank(user, "PROFESSOR") or user.canX` reads on the server.
            val targetIsProfessorPlus = roleRank(appUser.role) >= RANK_PROFESSOR
            // `assert_can_manage_target`: the master admin manages everyone but other masters;
            // everyone else manages strictly lower tiers only.
            val canManageTarget = if (actorIsMaster) !isMaster else roleRank(appUser.role) < actorRank
            // PATCH /users is `require_professor`, but a non-admin professor may only change `role`
            // (the server 403s any other field) — so the capability toggles need admin and above.
            val canEditGrants = actorIsAdmin && canManageTarget && !targetIsProfessorPlus
            val expanded = expandedUsers.contains(appUser.id)
            // Count of granted privileges, for the collapsed summary line. One entry per toggle
            // rendered below, and in the same order — the list used to name crafts and workshops
            // (which are no longer grantable) while omitting the dataset toggle that is, so the
            // summary counted five things that were not the five on screen.
            val grantedCount = listOf(
                targetIsProfessorPlus || appUser.canManageQuestionnaire,
                roleRank(appUser.role) >= RANK_FIELD_CONTRIBUTOR || appUser.canReview,
                roleRank(appUser.role) >= RANK_ADMIN || appUser.canViewProvenance,
                targetIsProfessorPlus || appUser.canDownloadDataset
            ).count { it }
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedUsers = if (expanded) expandedUsers - appUser.id else expandedUsers + appUser.id
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(appUser.name, display = true, fontWeight = FontWeight.SemiBold)
                            Text("${appUser.email} · ${roleLabel(appUser.role)}", color = Muted, fontSize = 12.sp)
                            Text(
                                if (isMaster) "All privileges (master admin)"
                                else "$grantedCount of 4 privileges granted",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                        Text(if (expanded) "Hide ▲" else "Manage ▼", color = Body, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (expanded) {
                        HorizontalDivider()
                        // The six-tier ladder, not an admin/researcher switch: the previous two-state
                        // button could neither reach Crowdsource Volunteer, Field Contributor or
                        // Professor nor be used by a professor at all, and demoting an admin dropped
                        // them straight past Professor to Researcher.
                        if (canManageTarget && !isMaster && assignableRoles.isNotEmpty()) {
                            DropdownField(
                                label = "Role",
                                options = assignableRoles,
                                selectedValue = appUser.role,
                                includeNone = false
                            ) { nextRole ->
                                if (nextRole != appUser.role) {
                                    scope.launch {
                                        runCatching { repository.updateUserRole(appUser.id, nextRole); refreshUsers() }
                                            .onFailure { onError(it.apiErrorMessage("Unable to update role")) }
                                    }
                                }
                            }
                            Text(
                                "Professor and above already hold every privilege below; the toggles are " +
                                    "for lifting a single power for someone lower on the ladder.",
                                color = Muted,
                                fontSize = 11.sp
                            )
                            HorizontalDivider()
                        }
                        GrantToggleRow(
                            label = "Questionnaire builder",
                            granted = targetIsProfessorPlus || appUser.canManageQuestionnaire,
                            enabled = canEditGrants,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserQuestionnaireAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.message ?: "Unable to update questionnaire access") }
                                }
                            }
                        )
                        /*
                         * "Craft creation" and "Workshop creation" WERE HERE, and are gone.
                         *
                         * They set `canManageCrafts` / `canManageWorkshops`, two columns the server
                         * deliberately stopped reading (`can_manage_crafts` in deps.py): both powers
                         * are Professor-by-rank alone now, because a grant that lifted someone below
                         * the taxonomy over it was invisible in the role column and nobody auditing
                         * the user table could see who held it. The switches still flipped, still
                         * saved, and granted nothing — a control that looks like it confers access
                         * and does not is worse than no control at all. Promote the person instead.
                         * Web parity: frontend/app/(protected)/users/page.tsx dropped the same two.
                         */
                        GrantToggleRow(
                            label = "Record review & approval",
                            granted = roleRank(appUser.role) >= RANK_FIELD_CONTRIBUTOR || appUser.canReview,
                            // Review is conferred by rank from Field Contributor up, not Professor.
                            enabled = actorIsAdmin && canManageTarget && roleRank(appUser.role) < RANK_FIELD_CONTRIBUTOR,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserReviewAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.message ?: "Unable to update review access") }
                                }
                            }
                        )
                        GrantToggleRow(
                            label = "View provenance",
                            granted = roleRank(appUser.role) >= RANK_ADMIN || appUser.canViewProvenance,
                            // Provenance comes with admin, so only sub-admin tiers need the grant.
                            enabled = actorIsAdmin && canManageTarget && roleRank(appUser.role) < RANK_ADMIN,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserProvenanceAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.message ?: "Unable to update provenance access") }
                                }
                            }
                        )
                        GrantToggleRow(
                            label = "Download entire dataset",
                            granted = targetIsProfessorPlus || appUser.canDownloadDataset,
                            enabled = canEditGrants,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserDatasetAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.message ?: "Unable to update dataset access") }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cross-researcher data sharing. A researcher can request access to another's data at a tier
 * (Download < Comment < Edit, with definitions shown), and manage requests/grants on their own data:
 * approve, deny, change tier, or revoke. Mirrors the web Sharing page.
 */
@Composable
private fun SharingForm(
    repository: FieldRepository,
    isAdmin: Boolean,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val myId = repository.cachedUser()?.id
    var tiers by remember { mutableStateOf<List<DataAccessTierInfo>>(emptyList()) }
    var grants by remember { mutableStateOf(MyGrantsDto()) }
    var directory by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true
            runCatching {
                tiers = repository.dataAccessTiers()
                grants = repository.dataAccessGrants()
                directory = repository.userDirectory().filter { it.id != myId }
            }.onFailure { onError(it.message ?: "Unable to load sharing data") }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun run(block: suspend () -> Unit, ok: String) {
        scope.launch {
            busy = true
            runCatching { block() }
                .onSuccess { reload() }
                .onFailure { onError(it.message ?: "Action failed") }
            busy = false
        }
    }

    RecordCard(title = "Access tiers", icon = Icons.Filled.Share) {
        if (tiers.isEmpty()) Text("Loading…", color = Muted)
        tiers.forEach { t ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when (t.tier) {
                        "DOWNLOAD" -> "Download (minimum)"
                        "COMMENT" -> "Comment (medium)"
                        else -> "Edit (maximum)"
                    },
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
                Text(t.description, color = Muted, fontSize = 12.sp)
            }
        }
    }

    // Both cards ask for several people at once and report each person separately — see
    // ui/SharingBatch.kt, which owns the batch and the partial-failure panel.
    RecordCard(title = "Request access") {
        RequestAccessFields(
            repository = repository,
            directory = directory,
            outgoing = grants.outgoing,
            onChanged = { reload() }
        )
    }

    RecordCard(title = "Grant access to your data") {
        GrantAccessFields(
            repository = repository,
            directory = directory,
            incoming = grants.incoming,
            onChanged = { reload() },
            onError = onError
        )
    }

    RecordCard(title = "Access to your data") {
        val incoming = grants.incoming
        if (incoming.isEmpty()) Text("No requests yet.", color = Muted, fontSize = 13.sp)
        incoming.forEach { g ->
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(g.grantee?.name ?: g.granteeId, display = true, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${g.grantee?.email ?: ""} · ${tierLabel(g.tier)} · ${g.status}" +
                            (if (g.allData) " · all data" else " · ${g.scopeItems.size} records"),
                        color = Muted, fontSize = 12.sp
                    )
                    if (!g.requestNote.isNullOrBlank()) Text("“${g.requestNote}”", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (g.status) {
                            "PENDING" -> {
                                Button(enabled = !busy, onClick = { run({ repository.decideDataAccess(g.id, "GRANTED", g.tier) }, "Granted") }) { Text("Approve") }
                                OutlinedButton(enabled = !busy, onClick = { run({ repository.decideDataAccess(g.id, "DENIED", null) }, "Denied") }) { Text("Deny") }
                            }
                            "GRANTED" -> OutlinedButton(enabled = !busy, onClick = { run({ repository.revokeDataAccess(g.id) }, "Revoked") }) { Text("Revoke") }
                            else -> OutlinedButton(enabled = !busy, onClick = { run({ repository.deleteDataAccess(g.id) }, "Removed") }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }

    RecordCard(title = "Your access to others' data") {
        val outgoing = grants.outgoing
        if (outgoing.isEmpty()) Text("No access yet.", color = Muted, fontSize = 13.sp)
        outgoing.forEach { g ->
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(g.owner?.name ?: g.ownerId, display = true, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${g.owner?.email ?: ""} · ${tierLabel(g.tier)} · ${g.status}",
                        color = Muted, fontSize = 12.sp
                    )
                    OutlinedButton(enabled = !busy, onClick = { run({ repository.deleteDataAccess(g.id) }, "Removed") }) {
                        Text(if (g.status == "PENDING") "Withdraw" else "Remove")
                    }
                }
            }
        }
    }
    // Workshop assignments moved to the admin "Settings" hub (see AdminHubScreen).
}

// ===========================================================================
// Workshop access — ONE row per (workshop, user) holds the whole two-sided
// conversation: an admin grants and revokes, a user requests and is approved
// or denied. Only status == GRANTED confers access; a PENDING row confers
// nothing, and DENIED/REVOKED rows are kept as history rather than deleted.
// ===========================================================================

/** The ladder, weakest first — the order the pickers offer it in. */
private val workshopAccessLevels = listOf("VIEW", "CONTRIBUTE", "EDIT")

/** CONTRIBUTE is what "assigned to a workshop" has always meant, so it is the default offered. */
private const val DEFAULT_WORKSHOP_LEVEL = "CONTRIBUTE"

private fun workshopLevelLabel(level: String): String = when (level) {
    "VIEW" -> "View"
    "CONTRIBUTE" -> "Contribute"
    "EDIT" -> "Edit"
    else -> level
}

private fun workshopAccessStatusLabel(status: String): String = when (status) {
    "PENDING" -> "Waiting for approval"
    "GRANTED" -> "Granted"
    "DENIED" -> "Denied"
    "REVOKED" -> "Revoked"
    else -> status
}

// @Composable: `Coral` is now a theme-aware getter (ui/Theme.kt), so reading it needs a composition.
// Every call site is already inside one (a `Text(color = …)` argument).
@Composable
@ReadOnlyComposable
private fun workshopAccessStatusColor(status: String): Color = when (status) {
    "GRANTED" -> SuccessGreen
    "PENDING" -> Coral
    else -> FailureRed
}

/** Level options for a picker, with the API's own descriptions as labels once they have loaded. */
private fun workshopLevelOptions(levels: List<WorkshopAccessLevelDto>): List<Pair<String, String>> =
    if (levels.isEmpty()) workshopAccessLevels.map { it to workshopLevelLabel(it) }
    else levels.map { it.level to workshopLevelLabel(it.level) }

/** "Kutch weaving · 2026-07-12", falling back to the id-only title when the row carries no workshop. */
private fun assignmentWorkshopLabel(row: WorkshopAssignmentDto): String =
    row.workshop?.let { workshopOptionLabel(it) } ?: "Workshop ${row.workshopId}"

/**
 * Admin-only: the roster for ONE workshop, at levels. Only GRANTED researchers may submit entries for
 * it, and out-of-window submissions are flagged for approval.
 *
 * Uses the per-user grant/patch/revoke endpoints rather than the whole-set PUT, because the set-based
 * call silently REVOKES everybody it does not mention — one stale checkbox state and an admin removes
 * a colleague they never looked at. Every action here changes exactly the one row it names.
 */
@Composable
private fun WorkshopAssignmentCard(
    repository: FieldRepository,
    directory: List<UserDto>,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var workshops by remember { mutableStateOf<List<WorkshopDetailDto>>(emptyList()) }
    var levels by remember { mutableStateOf<List<WorkshopAccessLevelDto>>(emptyList()) }
    var selectedWorkshop by remember { mutableStateOf("") }
    var roster by remember { mutableStateOf<List<WorkshopAssignmentDto>>(emptyList()) }
    var loadingRoster by remember { mutableStateOf(false) }
    var busyUserId by remember { mutableStateOf<String?>(null) }
    // The "add someone" row at the bottom.
    var addUserId by remember { mutableStateOf("") }
    var addLevel by remember { mutableStateOf(DEFAULT_WORKSHOP_LEVEL) }

    LaunchedEffect(Unit) {
        runCatching { workshops = repository.workshopsByOccurrence() }
            .onFailure { onError(it.apiErrorMessage("Unable to load workshops")) }
        runCatching { levels = repository.workshopAccessLevels() }
    }

    fun loadRoster(workshopId: String) {
        if (workshopId.isBlank()) { roster = emptyList(); return }
        scope.launch {
            loadingRoster = true
            runCatching { repository.workshopAssignments(workshopId) }
                .onSuccess { roster = it }
                .onFailure { onError(it.apiErrorMessage("Unable to load the roster")) }
            loadingRoster = false
        }
    }

    /** Run one row-scoped action, then re-read the roster so the shown state is the server's. */
    fun act(userId: String, ok: String, block: suspend () -> Unit) {
        scope.launch {
            busyUserId = userId
            runCatching { block() }
                .onSuccess { onMessage(ok); loadRoster(selectedWorkshop) }
                .onFailure { onError(it.apiErrorMessage("That change didn't go through")) }
            busyUserId = null
        }
    }

    RecordCard(title = "Workshop assignments", icon = Icons.Filled.Groups) {
        Text(
            "Pick a workshop, then choose who may work in it and at what level. A workshop with nobody " +
                "granted stays open to everyone.",
            color = Muted,
            fontSize = 12.sp
        )
        DropdownField(
            label = "Workshop",
            options = workshops.map { it.id to workshopOptionLabel(it) },
            selectedValue = selectedWorkshop,
            placeholder = "Select workshop",
            onSelect = { selectedWorkshop = it; addUserId = ""; loadRoster(it) }
        )
        if (selectedWorkshop.isBlank()) return@RecordCard

        if (loadingRoster) {
            Text("Loading the roster…", color = Muted, fontSize = 12.sp)
        } else if (roster.isEmpty()) {
            Text("Nobody is assigned yet — this workshop is open to everyone.", color = Muted, fontSize = 12.sp)
        }
        // Granted first (the people who actually have access), then anyone waiting, then the history.
        val ordered = roster.sortedBy { row ->
            when (row.status) { "GRANTED" -> 0; "PENDING" -> 1; else -> 2 }
        }
        ordered.forEach { row ->
            val busy = busyUserId == row.userId
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.user?.name ?: row.userId, display = true, color = Body, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            row.user?.email?.takeIf { it.isNotBlank() },
                            workshopLevelLabel(row.accessLevel)
                        ).joinToString(" · "),
                        color = Muted,
                        fontSize = 11.sp
                    )
                    Text(workshopAccessStatusLabel(row.status), color = workshopAccessStatusColor(row.status), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    row.requestNote?.takeIf { it.isNotBlank() }?.let { Text("Asked: “$it”", color = Muted, fontSize = 11.sp) }
                    row.decisionNote?.takeIf { it.isNotBlank() }?.let { Text("Decision: “$it”", color = Muted, fontSize = 11.sp) }
                    DropdownField(
                        label = "Access level",
                        options = workshopLevelOptions(levels),
                        selectedValue = row.accessLevel,
                        includeNone = false,
                        enabled = !busy,
                        onSelect = { level ->
                            if (level != row.accessLevel) {
                                act(row.userId, "${row.user?.name ?: "Access"} set to ${workshopLevelLabel(level)}") {
                                    repository.updateWorkshopAccess(selectedWorkshop, row.userId, accessLevel = level)
                                }
                            }
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (row.status == "GRANTED") {
                            OutlinedButton(
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    act(row.userId, "Access revoked") {
                                        repository.revokeWorkshopAccess(selectedWorkshop, row.userId)
                                    }
                                }
                            ) { Text(if (busy) "Working…" else "Revoke", maxLines = 1, fontSize = 13.sp) }
                        } else {
                            Button(
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    act(row.userId, "Access granted") {
                                        repository.grantWorkshopAccess(selectedWorkshop, row.userId, row.accessLevel)
                                    }
                                }
                            ) { Text(if (busy) "Working…" else "Grant", maxLines = 1, fontSize = 13.sp) }
                            if (row.status == "PENDING") {
                                OutlinedButton(
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        act(row.userId, "Request denied") {
                                            repository.updateWorkshopAccess(selectedWorkshop, row.userId, status = "DENIED")
                                        }
                                    }
                                ) { Text("Deny", maxLines = 1, fontSize = 13.sp) }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Add a researcher", display = true, color = Body, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        // Anyone already on the roster is reachable through their own row above, so they are not
        // offered here — granting them again from this picker would be a second way to do one thing.
        val onRoster = roster.map { it.userId }.toSet()
        val addable = directory.filterNot { it.id in onRoster }
        if (addable.isEmpty()) {
            Text("Everyone in the directory already has a row on this workshop.", color = Muted, fontSize = 12.sp)
        } else {
            DropdownField(
                label = "Researcher",
                options = addable.map { it.id to "${it.name} · ${it.email}" },
                selectedValue = addUserId,
                placeholder = "Select researcher",
                onSelect = { addUserId = it }
            )
            DropdownField(
                label = "Access level",
                options = workshopLevelOptions(levels),
                selectedValue = addLevel,
                includeNone = false,
                onSelect = { addLevel = it }
            )
            levels.firstOrNull { it.level == addLevel }?.description?.takeIf { it.isNotBlank() }
                ?.let { Text(it, color = Muted, fontSize = 11.sp) }
            Button(
                enabled = busyUserId == null && addUserId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val target = addUserId
                    act(target, "Access granted") {
                        repository.grantWorkshopAccess(selectedWorkshop, target, addLevel)
                        addUserId = ""
                    }
                }
            ) { Text("Grant access") }
        }
    }
}

/**
 * User-side workshop access: ask for one or more workshops at once, and see where every request got to.
 *
 * Multi-select because that is how the need arrives — a researcher joining a project needs the same
 * access to a whole season of workshops, and filing them one at a time produces a queue nobody works
 * through. Asking twice is safe: the API is idempotent per workshop and reports what it did with each.
 */
@Composable
private fun WorkshopAccessScreen(
    repository: FieldRepository,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var workshops by remember { mutableStateOf<List<WorkshopDetailDto>>(emptyList()) }
    var levels by remember { mutableStateOf<List<WorkshopAccessLevelDto>>(emptyList()) }
    var mine by remember { mutableStateOf<List<WorkshopAssignmentDto>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var level by remember { mutableStateOf(DEFAULT_WORKSHOP_LEVEL) }
    var note by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    suspend fun loadMine() {
        runCatching { repository.myWorkshopAccess() }
            .onSuccess { mine = it }
            .onFailure { onError(it.apiErrorMessage("Unable to load your access")) }
    }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { workshops = repository.workshopsByOccurrence() }
            .onFailure { onError(it.apiErrorMessage("Unable to load workshops")) }
        runCatching { levels = repository.workshopAccessLevels() }
        loadMine()
        loading = false
    }

    RecordCard(title = "Request workshop access", icon = Icons.Filled.LockOpen) {
        Text(
            "Pick every workshop you need to work in, then send one request. An admin approves it and " +
                "sets the level you get.",
            color = Muted,
            fontSize = 12.sp
        )
        if (loading) {
            Text("Loading workshops…", color = Muted, fontSize = 12.sp)
        } else {
            CheckboxMultiSelectField(
                label = "Workshops",
                options = workshops.map { it.id to workshopOptionLabel(it) },
                selectedIds = selected,
                emptyMessage = "No workshops to request yet.",
                onToggle = { id -> selected = if (id in selected) selected - id else selected + id }
            )
            DropdownField(
                label = "Access level you need",
                options = workshopLevelOptions(levels),
                selectedValue = level,
                includeNone = false,
                onSelect = { level = it }
            )
            levels.firstOrNull { it.level == level }?.description?.takeIf { it.isNotBlank() }
                ?.let { Text(it, color = Muted, fontSize = 11.sp) }
            TextInput("Why do you need access? (optional)", note, minLines = 2) { note = it }
            Button(
                enabled = !busy && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { repository.requestWorkshopAccess(selected.toList(), level, note) }
                            .onSuccess { result ->
                                // Say what actually happened per workshop: pressing the button twice is
                                // expected, and "already pending" is a different answer from "sent".
                                val counts = result.outcomes.groupingBy { it.outcome }.eachCount()
                                val parts = listOfNotNull(
                                    counts["CREATED"]?.let { "$it sent" },
                                    counts["RE_REQUESTED"]?.let { "$it asked again" },
                                    counts["ALREADY_PENDING"]?.let { "$it already waiting" },
                                    counts["ALREADY_GRANTED"]?.let { "$it already granted" }
                                )
                                onMessage("Workshop access: ${parts.joinToString(", ").ifBlank { "request sent" }}")
                                selected = emptySet()
                                note = ""
                                loadMine()
                            }
                            .onFailure { onError(it.apiErrorMessage("Unable to send the request")) }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Sending…" else "Request access (${selected.size})") }
        }
    }

    RecordCard(title = "My workshop access") {
        Text(
            "Everything you hold, everything you are waiting on, and anything that was refused.",
            color = Muted,
            fontSize = 12.sp
        )
        if (mine.isEmpty()) {
            Text("You have not asked for — or been given — access to any workshop yet.", color = Muted, fontSize = 12.sp)
        }
        mine.forEach { row ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(assignmentWorkshopLabel(row), display = true, color = Body, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            workshopAccessStatusLabel(row.status),
                            color = workshopAccessStatusColor(row.status),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("· ${workshopLevelLabel(row.accessLevel)}", color = Muted, fontSize = 12.sp)
                    }
                    row.decisionNote?.takeIf { it.isNotBlank() }?.let { Text("“$it”", color = Muted, fontSize = 11.sp) }
                    row.decidedBy?.name?.let { Text("Decided by $it${formatIsoDate(row.decidedAt)?.let { d -> " · $d" } ?: ""}", color = Muted, fontSize = 11.sp) }
                    if (row.status == "DENIED" || row.status == "REVOKED") {
                        Text("You can ask again above — that starts a fresh request.", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/**
 * Admin-only: the workshop-access approval queue across ALL workshops, so an approver works from one
 * list. Opening each workshop's roster in turn is how requests sit unanswered for a week.
 */
@Composable
private fun WorkshopAccessQueueCard(
    repository: FieldRepository,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<WorkshopAssignmentDto>>(emptyList()) }
    var levels by remember { mutableStateOf<List<WorkshopAccessLevelDto>>(emptyList()) }
    var showAll by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf<String?>(null) }
    // Level the admin will hand out, per pending row — seeded from what the user asked for.
    val chosenLevel = remember { mutableStateMapOf<String, String>() }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { repository.workshopAccessQueue(if (showAll) "ALL" else "PENDING") }
                .onSuccess { rows = it }
                .onFailure { onError(it.apiErrorMessage("Unable to load the access queue")) }
            loading = false
        }
    }
    LaunchedEffect(showAll) { refresh() }
    LaunchedEffect(Unit) { runCatching { levels = repository.workshopAccessLevels() } }

    fun decide(row: WorkshopAssignmentDto, status: String) {
        scope.launch {
            busyId = row.id
            runCatching {
                repository.decideWorkshopAccess(row.id, status, chosenLevel[row.id] ?: row.accessLevel)
            }
                .onSuccess {
                    onMessage(if (status == "GRANTED") "Access granted to ${row.user?.name ?: "the researcher"}" else "Request denied")
                    refresh()
                }
                .onFailure { onError(it.apiErrorMessage("That decision didn't go through")) }
            busyId = null
        }
    }

    RecordCard(title = "Workshop access requests", icon = Icons.Filled.LockOpen) {
        Text(
            "Researchers asking to work in a workshop, oldest first. Approving gives them the level you " +
                "pick — it does not put them on the roster, so an open workshop stays open to everyone else.",
            color = Muted,
            fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showAll, onClick = { showAll = false }, label = { Text("Pending") })
            FilterChip(selected = showAll, onClick = { showAll = true }, label = { Text("Full history") })
        }
        when {
            loading -> Text("Loading requests…", color = Muted, fontSize = 12.sp)
            rows.isEmpty() -> Text(
                if (showAll) "No workshop access rows yet." else "Nothing waiting — the queue is clear. 🎉",
                color = Muted,
                fontSize = 12.sp
            )
            else -> rows.forEach { row ->
                val busy = busyId == row.id
                val pending = row.status == "PENDING"
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.user?.name ?: row.userId, display = true, color = Body, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                row.user?.email?.takeIf { it.isNotBlank() },
                                assignmentWorkshopLabel(row),
                                formatIsoDate(row.createdAt)
                            ).joinToString(" · "),
                            color = Muted,
                            fontSize = 11.sp
                        )
                        Text(
                            "${workshopAccessStatusLabel(row.status)} · asked for ${workshopLevelLabel(row.accessLevel)}",
                            color = workshopAccessStatusColor(row.status),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        row.requestNote?.takeIf { it.isNotBlank() }?.let { Text("“$it”", color = Muted, fontSize = 11.sp) }
                        if (pending) {
                            DropdownField(
                                label = "Grant at level",
                                options = workshopLevelOptions(levels),
                                selectedValue = chosenLevel[row.id] ?: row.accessLevel,
                                includeNone = false,
                                enabled = !busy,
                                onSelect = { chosenLevel[row.id] = it }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    onClick = { decide(row, "GRANTED") }
                                ) { Text(if (busy) "Working…" else "Approve", maxLines = 1, fontSize = 13.sp) }
                                OutlinedButton(
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    onClick = { decide(row, "DENIED") }
                                ) { Text("Deny", maxLines = 1, fontSize = 13.sp) }
                            }
                        } else {
                            row.decisionNote?.takeIf { it.isNotBlank() }?.let { Text("Decision: “$it”", color = Muted, fontSize = 11.sp) }
                            Text(
                                "Already decided — change it from Workshop assignments.",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ===========================================================================
// Tasks — the assignee's to-do list. An admin hands work out as a batch; each
// person owns, sees and reports progress on their own row.
// ===========================================================================

private fun taskStatusLabel(status: String): String = when (status) {
    "OPEN" -> "Open"
    "IN_PROGRESS" -> "In progress"
    "DONE" -> "Done"
    "CANCELLED" -> "Cancelled"
    else -> status
}

// @Composable for the same reason as workshopAccessStatusColor: Coral/Muted/Body are theme getters.
@Composable
@ReadOnlyComposable
private fun taskStatusColor(status: String): Color = when (status) {
    "DONE" -> SuccessGreen
    "IN_PROGRESS" -> Coral
    "CANCELLED" -> Muted
    else -> Body
}

/** The statuses an ASSIGNEE may move a task between. Cancelling belongs to whoever handed it out. */
private val assigneeTaskStatuses = listOf("OPEN", "IN_PROGRESS", "DONE")

/**
 * One line describing everything the task asks for: the record kinds, the questionnaire sections and
 * the artisans it is scoped to. Built from the server-resolved names so nothing has to be looked up.
 */
private fun taskScopeSummary(task: TaskDto): String {
    val parts = mutableListOf<String>()
    if (task.recordTypeLabels.isNotEmpty()) {
        parts += task.targetCount?.let { "${task.recordTypeLabels.joinToString(", ")} (target $it)" }
            ?: task.recordTypeLabels.joinToString(", ")
    }
    if (task.sections.isNotEmpty()) {
        val codes = task.sections.joinToString(", ") { it.code }
        parts += if (task.sections.size == 1) "questionnaire section $codes" else "questionnaire sections $codes"
    }
    if (task.artisans.isNotEmpty()) {
        parts += if (task.artisans.size <= 3) {
            "for ${task.artisans.joinToString(", ") { it.name }}"
        } else {
            "for ${task.artisans.size} artisans"
        }
    }
    return parts.joinToString(" · ")
}

/**
 * My tasks. Admins can also flip to the work they handed out ("Assigned by me"), which is the same
 * rows read through `view=created` — the endpoint 403s that view for everyone else, so the toggle is
 * only offered to admins.
 */
@Composable
private fun MyTasksScreen(
    repository: FieldRepository,
    /**
     * Admin AND admin view on. Being an ASSIGNEE is open to everyone and is never narrowed; handing
     * work out is admin chrome, so both the "Assigned by me" view (the endpoint 403s it for everyone
     * else) and the route to the assignment board follow the toggle.
     */
    canAssign: Boolean,
    onOpenAssignmentBoard: (() -> Unit)? = null,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val myId = remember { repository.cachedUser()?.id }
    var view by remember { mutableStateOf("assigned") }
    // Flipping admin view off while "Assigned by me" is showing must not strand the user on a view
    // they can no longer switch away from.
    LaunchedEffect(canAssign) { if (!canAssign) view = "assigned" }
    var statusFilter by remember { mutableStateOf("") }
    var tasks by remember { mutableStateOf<List<TaskDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { repository.tasks(view = view, status = statusFilter.ifBlank { null }) }
                .onSuccess { tasks = it }
                .onFailure { onError(it.apiErrorMessage("Unable to load your tasks")) }
            loading = false
        }
    }
    LaunchedEffect(view, statusFilter) { refresh() }

    /** Assignee-side change: move the status, or report how much is done. */
    fun update(task: TaskDto, status: String? = null, progressCount: Int? = null, ok: String) {
        scope.launch {
            busyId = task.id
            runCatching { repository.updateTaskProgress(task.id, status = status, progressCount = progressCount) }
                .onSuccess { updated ->
                    tasks = tasks.map { if (it.id == updated.id) updated else it }
                    onMessage(ok)
                }
                .onFailure { onError(it.apiErrorMessage("Unable to update the task")) }
            busyId = null
        }
    }

    RecordCard(title = "Tasks", icon = Icons.AutoMirrored.Filled.Assignment) {
        Text(
            "Work assigned to you, with what it covers and how far along you are. Move the status as you " +
                "go so whoever assigned it can see where things stand.",
            color = Muted,
            fontSize = 12.sp
        )
        if (canAssign) {
            // Web parity with the strip on /tasks: say where handing work out actually happens, and
            // offer the way there, rather than leaving the board undiscoverable outside the hub.
            Text(
                "Handing work out happens on the assignment board: one scope — record types, an " +
                    "artisan subset, questionnaire sections, a target count — given to several " +
                    "people at once, with the accountability rollup beside it.",
                color = Muted,
                fontSize = 12.sp
            )
            onOpenAssignmentBoard?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Assignment board")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = view == "assigned", onClick = { view = "assigned" }, label = { Text("Assigned to me") })
                FilterChip(selected = view == "created", onClick = { view = "created" }, label = { Text("Assigned by me") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(selected = statusFilter.isBlank(), onClick = { statusFilter = "" }, label = { Text("All") })
            listOf("OPEN", "IN_PROGRESS", "DONE").forEach { value ->
                FilterChip(
                    selected = statusFilter == value,
                    onClick = { statusFilter = if (statusFilter == value) "" else value },
                    label = { Text(taskStatusLabel(value)) }
                )
            }
        }
        OutlinedButton(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (loading) "Loading tasks…" else "Refresh")
        }
        when {
            loading -> Text("Loading tasks…", color = Muted, fontSize = 12.sp)
            tasks.isEmpty() -> Text(
                if (view == "created") "You have not assigned any work yet."
                else "Nothing is assigned to you right now.",
                color = Muted,
                fontSize = 12.sp
            )
            else -> tasks.forEach { task ->
                TaskCard(
                    task = task,
                    // Only the assignee may move a task; an admin looking at "assigned by me" is
                    // reading someone else's row and the API would refuse the write anyway.
                    editable = task.assigneeId != null && task.assigneeId == myId,
                    busy = busyId == task.id,
                    onStatus = { status -> update(task, status = status, ok = "Marked ${taskStatusLabel(status).lowercase()}") },
                    onProgress = { count -> update(task, progressCount = count, ok = "Progress reported") }
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskDto,
    editable: Boolean,
    busy: Boolean,
    onStatus: (String) -> Unit,
    onProgress: (Int) -> Unit
) {
    // Seeded from the server value and re-seeded whenever the server value moves, so a successful
    // report leaves the box showing what was actually stored (the API clamps to the target).
    var reported by remember(task.id, task.progressCount) { mutableStateOf(task.progressCount.toString()) }
    val scopeLine = taskScopeSummary(task)

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(task.title.ifBlank { "Field task" }, display = true, color = Body, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(taskStatusLabel(task.status), color = taskStatusColor(task.status), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (task.isOverdue) Text("· Overdue", color = FailureRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                listOfNotNull(
                    task.workshopTitle?.takeIf { it.isNotBlank() },
                    formatIsoDate(task.dueAt)?.let { "due $it" },
                    // Naming the assignee is only useful on somebody else's row — on my own list it
                    // would print my own name against every single task.
                    if (!editable) task.assignee?.name?.takeIf { it.isNotBlank() }?.let { "for $it" } else null
                ).joinToString(" · ").ifBlank { "No workshop or due date" },
                color = Muted,
                fontSize = 11.sp
            )
            if (scopeLine.isNotBlank()) Text(scopeLine, color = Body, fontSize = 12.sp)
            task.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, fontSize = 12.sp) }

            // Reported vs derived, side by side: "says 8, repository sees 2" is the signal worth seeing.
            val target = task.targetCount
            val derived = task.derivedCount
            Text(
                buildString {
                    append("Reported ${task.progressCount}")
                    if (target != null) append(" of $target")
                    if (derived != null) {
                        append(" · repository sees $derived")
                        task.derivedTarget?.let { append(" of $it") }
                    }
                },
                color = Muted,
                fontSize = 11.sp
            )
            task.percentComplete?.let { percent ->
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            }

            if (!editable) return@Column
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                assigneeTaskStatuses.forEach { value ->
                    val current = task.status == value
                    OutlinedButton(
                        enabled = !busy && !current,
                        onClick = { onStatus(value) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                    ) { Text(if (current) "✓ ${taskStatusLabel(value)}" else taskStatusLabel(value), maxLines = 1, fontSize = 12.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = reported,
                    onValueChange = { raw -> reported = raw.filter { it.isDigit() }.take(6) },
                    label = { Text(if (target != null) "Done so far (of $target)" else "Done so far") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = !busy && reported.toIntOrNull() != null && reported.toIntOrNull() != task.progressCount,
                    onClick = { reported.toIntOrNull()?.let(onProgress) }
                ) { Text(if (busy) "Saving…" else "Report") }
            }
        }
    }
}

private fun tierLabel(tier: String): String = when (tier) {
    "DOWNLOAD" -> "Download"
    "COMMENT" -> "Comment"
    "EDIT" -> "Edit"
    else -> tier
}

@Composable
private fun GrantToggleRow(label: String, granted: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Body, fontSize = 13.sp)
            Text(if (granted) "Granted" else "Not granted", color = Muted, fontSize = 11.sp)
        }
        OutlinedButton(enabled = enabled, onClick = { onToggle(!granted) }) {
            Text(if (granted) "Revoke" else "Grant")
        }
    }
}

@Composable
private fun RecordCard(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Canvas),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                    Text(title, display = true, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                Text(title, display = true, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            content()
        }
    }
}

/** Serializer for offline-queued create requests (record forms persist their request as JSON). */
private val offlineFormJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Offline save: when there's no connection, persist this new record (and copies of its captured media)
 * to the local outbox and return true so the form can confirm "saved on device" and navigate. Returns
 * false when online (the form should take its normal upload path). Only for NEW records — edits need
 * the existing server record. The outbox auto-syncs on reconnect.
 */
private suspend fun trySaveOffline(
    repository: FieldRepository,
    context: Context,
    isEdit: Boolean,
    type: String,
    payloadJson: String,
    label: String,
    media: MediaCaptureState,
    recordName: String?,
    caption: String?
): Boolean {
    if (isEdit || repository.isOnline(context)) return false
    repository.queueOffline(context, type, payloadJson, label, media.uris, recordName, caption)
    return true
}

/** Save-button lifecycle: idle, in-flight (spinner + "Saving…"), then a brief "Saved ✓" confirmation. */
private enum class SaveState { IDLE, SAVING, SAVED }

/**
 * Primary save button that reflects the save lifecycle: a buffering spinner with "Saving…" while the
 * record (and its uploads) are processing, then "Saved ✓" before the screen navigates away. Disabled
 * except when IDLE so a record can't be double-submitted. Drive it with a [SaveState]: set SAVING on
 * submit, SAVED on success (then navigate after a short beat), back to IDLE on failure.
 */
@Composable
private fun SaveButton(
    state: SaveState,
    idleLabel: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(onClick = onClick, enabled = enabled && state == SaveState.IDLE, modifier = modifier) {
        when (state) {
            SaveState.SAVING -> {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current
                )
                Spacer(Modifier.width(8.dp))
                Text("Saving…")
            }
            SaveState.SAVED -> {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Saved ✓")
            }
            SaveState.IDLE -> Text(idleLabel)
        }
    }
}

/** How long the "Saved ✓" confirmation lingers before the screen returns to the dashboard. */
private const val SAVED_CONFIRM_MS = 750L

/**
 * "Will be saved as …" — the API title-cases every name-like column on WRITE, so the form shows the
 * normalised value BEFORE saving rather than silently rewriting the researcher's text afterwards.
 * Nothing is shown when what they typed is already exactly what will be stored.
 */
@Composable
private fun TitleCaseHint(value: String) {
    val normalised = titleCasePreview(value) ?: return
    Text("Will be saved as “$normalised”", color = Muted, fontSize = 11.sp)
}

/**
 * Set [titleCased] on a field whose column is in the server's TITLE_CASE_FIELDS (see TextFormat.kt).
 *
 * [keyboardType] mirrors the web form's `type` attribute: every measurement, count and price box is
 * `<input type="number">` there, and leaving it at the default here opened the full QWERTY keyboard
 * for a field that only ever takes digits — the one place on a phone where that difference is felt.
 */
@Composable
private fun TextInput(
    label: String,
    value: String,
    minLines: Int = 1,
    titleCased: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            minLines = minLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth()
        )
        if (titleCased) TitleCaseHint(value)
    }
}

/** Split a stored newline-separated list into editable rows (always at least one, for the empty case). */
private fun splitNumbered(value: String?): List<String> =
    value?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: listOf("")

/** Collapse editable rows back into the stored newline-separated form (blank rows dropped). */
private fun joinNumbered(items: List<String>): String =
    items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

/**
 * A required, numbered multi-point input. Each row is one numbered bullet; pressing Enter inside a row
 * splits it into a new bullet (so the user just types a point and hits Enter for the next). Rows can be
 * removed individually, and "+ Add point" appends an empty one. Backed by a List<String>; persist with
 * [joinNumbered]. Used for an artisan's Do's (positive prompt) and Don'ts (negative prompt).
 */
@Composable
private fun NumberedListInput(
    label: String,
    items: List<String>,
    error: String?,
    focusRequester: FocusRequester? = null,
    helper: String? = null,
    onChange: (List<String>) -> Unit
) {
    val rows = items.ifEmpty { listOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text("$label *", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        helper?.let { Text(it, color = Muted, fontSize = 12.sp) }
        rows.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${index + 1}.", color = Muted, fontSize = 14.sp)
                OutlinedTextField(
                    value = item,
                    onValueChange = { raw ->
                        if (raw.contains('\n')) {
                            // Enter pressed: commit text before the break, push the remainder to new bullet(s).
                            val segments = raw.split('\n')
                            val updated = rows.toMutableList()
                            updated[index] = segments.first().trim()
                            updated.addAll(index + 1, segments.drop(1).map { it.trim() })
                            onChange(updated)
                        } else {
                            val updated = rows.toMutableList()
                            updated[index] = raw
                            onChange(updated)
                        }
                    },
                    isError = error != null && index == 0,
                    minLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .let { if (index == 0 && focusRequester != null) it.focusRequester(focusRequester) else it }
                )
                if (rows.size > 1) {
                    IconButton(onClick = {
                        val updated = rows.toMutableList().also { it.removeAt(index) }
                        onChange(updated.ifEmpty { listOf("") })
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove point", tint = Muted)
                    }
                }
            }
        }
        TextButton(onClick = { onChange(rows + "") }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add point")
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
}

// Notes can hold several distinct entries. They are stored in the existing single `notes` column,
// joined by a blank line, and split back on a blank line for editing. A note may itself span lines.
private const val NOTE_SEPARATOR = "\n\n"

private fun splitNotes(value: String?): List<String> =
    value?.split(Regex("\\n\\s*\\n"))?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

private fun joinNotes(items: List<String>): String =
    items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(NOTE_SEPARATOR)

/**
 * Multi-note editor: several free-text notes, each its own multi-line field, with an "Add note" button
 * and per-note remove. Drop-in for a single notes field — reads/writes the same stored string (notes
 * joined by a blank line). Optional, unlike the required numbered Do's/Don'ts.
 */
@Composable
private fun MultiNoteInput(label: String = "Notes", value: String, resetKey: Any? = null, onValueChange: (String) -> Unit) {
    var rows by remember(resetKey) { mutableStateOf(splitNotes(value).ifEmpty { listOf("") }) }
    fun emit(updated: List<String>) {
        val next = updated.ifEmpty { listOf("") }
        rows = next
        onValueChange(joinNotes(next))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        rows.forEachIndexed { index, note ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { v -> emit(rows.toMutableList().also { it[index] = v }) },
                    label = { Text(if (rows.size > 1) "Note ${index + 1}" else "Note") },
                    minLines = 2,
                    modifier = Modifier.weight(1f)
                )
                if (rows.size > 1) {
                    IconButton(onClick = { emit(rows.toMutableList().also { it.removeAt(index) }) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove note", tint = Muted)
                    }
                }
            }
        }
        TextButton(onClick = { emit(rows + "") }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add note")
        }
    }
}

/** Read-only render of a stored newline-separated list as a numbered list; hidden when empty. */
@Composable
private fun NumberedListDisplay(label: String, value: String?) {
    val items = value?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        items.forEachIndexed { index, item ->
            Text("${index + 1}. $item", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        }
    }
}

/** A mandatory text field: shows a trailing asterisk and an inline error when left empty. */
@Composable
private fun RequiredInput(
    label: String,
    value: String,
    error: String?,
    focusRequester: FocusRequester,
    minLines: Int = 1,
    titleCased: Boolean = false,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("$label *") },
            isError = error != null,
            supportingText = error?.let { msg -> { Text(msg) } },
            minLines = minLines,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
        )
        if (titleCased) TitleCaseHint(value)
    }
}

/** One required field's validation hooks: whether it is blank, how to flag it, and where to focus. */
private class RequiredCheck(
    val isBlank: Boolean,
    val setError: (String?) -> Unit,
    val focus: FocusRequester
)

/**
 * Clears prior errors, then on the first blank required field flags it, scrolls/focuses it into
 * view, and returns false. Returns true when every required field is filled.
 */
private fun validateRequired(checks: List<RequiredCheck>): Boolean {
    checks.forEach { it.setError(null) }
    val firstMissing = checks.firstOrNull { it.isBlank } ?: return true
    firstMissing.setError("This field cannot be empty")
    runCatching { firstMissing.focus.requestFocus() }
    return false
}

private fun requiredAndroidPermissions(): Array<String> {
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.READ_MEDIA_IMAGES
        permissions += Manifest.permission.READ_MEDIA_VIDEO
        permissions += Manifest.permission.READ_MEDIA_AUDIO
    }
    return permissions.toTypedArray()
}

private fun createAppFile(context: Context, prefix: String, suffix: String): File {
    val directory = File(context.cacheDir, "field-captures").apply { mkdirs() }
    return File.createTempFile(prefix, suffix, directory)
}

private fun createAppFileUri(context: Context, prefix: String, suffix: String): Uri {
    return uriForFile(context, createAppFile(context, prefix, suffix))
}

private fun uriForFile(context: Context, file: File): Uri {
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Download a previously-uploaded media file to the device's public Downloads folder via the system
 * DownloadManager (shows a notification + progress, and survives the app being backgrounded). Works
 * without storage permissions on Android 10+. The URL is the object-storage GET the previews already
 * stream from, so it is directly fetchable.
 */
private fun saveMediaToDevice(context: Context, url: String?, filename: String, mimeType: String?) {
    if (url.isNullOrBlank()) {
        Toast.makeText(context, "This file has no downloadable URL.", Toast.LENGTH_LONG).show()
        return
    }
    val safeName = filename.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "media" }
    runCatching {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(safeName)
            .setDescription("Saving to Downloads")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(context, "Saving \"$safeName\" to Downloads…", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "Couldn't save: ${it.message ?: "download failed"}", Toast.LENGTH_LONG).show()
    }
}

/**
 * Save a locally-captured (content://) attachment straight into the device's public Downloads folder
 * by streaming its bytes from the content resolver. Unlike [saveMediaToDevice] — which hands a remote
 * URL to the system DownloadManager — this works for files that have NOT been uploaded yet (or whose
 * upload failed), so a user can always keep the media on-device while the network is unreliable.
 */
private fun saveLocalUriToDevice(context: Context, uri: Uri) {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
    val rawName = run {
        var name: String? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
            }
        }
        name ?: uri.lastPathSegment ?: "field-media-${System.currentTimeMillis()}"
    }
    val safeName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "field-media-${System.currentTimeMillis()}" }
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Couldn't create a Downloads entry")
            resolver.openOutputStream(target)?.use { out ->
                resolver.openInputStream(uri)?.use { input -> input.copyTo(out) }
                    ?: throw IllegalStateException("Couldn't read the media")
            } ?: throw IllegalStateException("Couldn't open the Downloads file")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(target, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, safeName)
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("Couldn't read the media")
        }
        Toast.makeText(context, "Saved \"$safeName\" to Downloads", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "Couldn't save: ${it.message ?: "download failed"}", Toast.LENGTH_LONG).show()
    }
}

private fun openUri(context: Context, uri: Uri, mimeType: String?) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (mimeType.isNullOrBlank()) {
            data = uri
        } else {
            setDataAndType(uri, mimeType)
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open media"))
    }
}

/** A media file's duration as HHMMSS (zero-padded), read from its metadata; "000000" if unknown. */
private fun mediaDurationHHMMSS(context: Context, uri: Uri): String {
    val ms = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(0L)
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d%02d%02d".format(h, m, s)
}

/**
 * Build the questionnaire recording filename base per the required nomenclature:
 * `SECTION_QUESTION_INTERVIEWNAME_DURATIONHHMMSS_DATETIMEDDMMYYYYHHMM`. The repository sanitises this
 * and appends the file extension. For a whole-section recording the question slot is "SEC".
 */
private fun questionnaireClipBaseName(
    context: Context,
    sectionCode: String?,
    questionNumber: String?,
    interviewName: String?,
    uri: Uri
): String {
    fun token(value: String?, fallback: String): String =
        value?.trim()?.replace(Regex("[^A-Za-z0-9]+"), "")?.uppercase()?.take(40)?.ifBlank { fallback } ?: fallback
    val section = token(sectionCode, "SEC")
    val question = token(questionNumber, "SEC")
    val name = token(interviewName, "INTERVIEW")
    val duration = mediaDurationHHMMSS(context, uri)
    val stamp = java.text.SimpleDateFormat("ddMMyyyyHHmm", java.util.Locale.US).format(java.util.Date())
    return listOf(section, question, name, duration, stamp).joinToString("_")
}

private fun createAudioRecorder(context: Context, file: File): MediaRecorder {
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }
    return recorder.apply {
        // VOICE_RECOGNITION routes capture through the platform's voice pre-processing (noise
        // suppression / AGC) tuned for clean speech without the aggressive echo-cancellation of the
        // call path — i.e. less background noise and better transcription accuracy than raw MIC.
        // Fall back to MIC on the rare device that doesn't expose the recognition source.
        runCatching { setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION) }
            .onFailure { setAudioSource(MediaRecorder.AudioSource.MIC) }
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        // Mono speech at 44.1 kHz / 96 kbps: clear voice, modest file size, ideal for transcription.
        setAudioChannels(1)
        setAudioSamplingRate(44_100)
        setAudioEncodingBitRate(96_000)
        setOutputFile(file.absolutePath)
        prepare()
    }
}

/**
 * The device's last known position, with NO name attached to it.
 *
 * WHAT WAS HERE AND WHY IT IS GONE. This function used to stamp every coordinate it produced with
 * `placeName = "Android precise location"`, and that string is on all fifteen live records that
 * carry a location. It is wrong three times over. It is not a place name — no human ever called
 * anywhere that. It is not precise — the same fifteen rows carry accuracy radii up to 2.5 km, which
 * is a mobile-network estimate rather than a reading. And by occupying the field it made the record
 * look as though the place had been identified when nothing had identified it, which is how a desk
 * in Kharagpur came to be filed as seven workshops across Rajasthan, Gujarat and Uttarakhand.
 *
 * A place name is now either a real name or absent, and absent is the honest answer here: this
 * reads a cached fix out of the platform and knows nothing whatever about where that is.
 */
private fun readLastKnownLocation(context: Context): LocationRequest? {
    val hasFine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return null

    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    val location = providers.mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { it.time }
    return location?.let {
        LocationRequest(
            latitude = it.latitude,
            longitude = it.longitude,
            altitude = it.altitude.takeIf { _ -> it.hasAltitude() },
            accuracy = it.accuracy.toDouble().takeIf { _ -> it.hasAccuracy() }
        )
    }
}

private fun moveIds(ids: List<String>, index: Int, direction: Int): List<String> {
    val nextIndex = index + direction
    if (nextIndex !in ids.indices) return ids
    return ids.toMutableList().also {
        val item = it[index]
        it[index] = it[nextIndex]
        it[nextIndex] = item
    }
}

private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }

private object ColorCompat {
    val darkElevated = androidx.compose.ui.graphics.Color(0xFF252320)
}
