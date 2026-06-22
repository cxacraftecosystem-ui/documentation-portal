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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.ArtisanCreateRequest
import com.fieldrepository.app.data.CraftCreateRequest
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
import com.fieldrepository.app.ui.Body
import com.fieldrepository.app.ui.Canvas
import com.fieldrepository.app.ui.Coral
import com.fieldrepository.app.ui.DarkSurface
import com.fieldrepository.app.ui.FieldRepositoryTheme
import com.fieldrepository.app.ui.Muted
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
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.ArtisanAnswerDto
import com.fieldrepository.app.data.ArtisanDetailDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.data.CreatedRecordDto
import com.fieldrepository.app.data.AppScope
import com.fieldrepository.app.data.LocationDto
import com.fieldrepository.app.data.AppReleaseDto
import com.fieldrepository.app.data.FeedbackDto
import com.fieldrepository.app.data.FeedbackUpsertRequest
import com.fieldrepository.app.data.MediaFileDto
import com.fieldrepository.app.data.AppSettingUpdateRequest
import com.fieldrepository.app.data.PendingReviewDto
import com.fieldrepository.app.data.StagedMedia
import androidx.compose.runtime.DisposableEffect
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
import com.fieldrepository.app.ui.LocationEditor
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
        setContent {
            FieldRepositoryTheme {
                RepositoryApp(repository, googleAuthClient)
            }
        }
    }
}

private enum class EntryMode(
    val label: String,
    val actionTitle: String,
    val editable: Boolean = false
) {
    ARTISAN("Artisan", "Record artisan", editable = true),
    PRODUCT("Product", "Record product", editable = true),
    PROCESS("Process", "Document process", editable = true),
    TOOL("Tool", "Record tool", editable = true),
    QUESTIONNAIRE("Questionnaire", "Take interview", editable = true),
    MEDIA("Miscellaneous Media", "Upload media"),
    VIEW_DATA("View Data", "Browse records"),
    USERS("Users", "Manage users"),
    // Craft and Workshop are the least frequently edited, so they sit last on the dashboard.
    CRAFT("Craft", "Add craft", editable = true),
    WORKSHOP("Workshop", "Record workshop", editable = true)
}

/** Where the user currently is. null-mode dashboard is replaced by this explicit machine. */
private sealed interface Screen {
    data object Dashboard : Screen
    data class Create(val mode: EntryMode, val prefill: Prefill? = null) : Screen
    data class Browse(val mode: EntryMode) : Screen
    data class Edit(val mode: EntryMode, val recordId: String) : Screen
    // Hamburger-only screens (not on the dashboard).
    data object MyActivity : Screen
    data object ToolAssign : Screen
    data object Feedback : Screen
    data object Settings : Screen
}

/** Context carried forward from a just-saved artisan into a follow-up record. */
private data class Prefill(
    val artisanId: String? = null,
    val artisanName: String? = null,
    val place: String? = null,
    val craftId: String? = null,
    val craftName: String? = null
)

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
    EntryMode.USERS -> Icons.Filled.ManageAccounts
}

@Composable
private fun RepositoryApp(repository: FieldRepository, googleAuthClient: GoogleAuthClient) {
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
                onLogout = {
                    scope.launch {
                        runCatching { googleAuthClient.clear() }
                        repository.logout()
                        user = null
                    }
                }
            )
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
            fontFamily = FontFamily.Serif,
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
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<DashboardStats?>(null) }
    var sections by remember { mutableStateOf<List<QuestionnaireSectionDto>>(emptyList()) }
    var crafts by remember { mutableStateOf<List<CraftDto>>(emptyList()) }
    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    var carryForward by remember { mutableStateOf<Prefill?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var message by remember { mutableStateOf<String?>(null) }
    val isAdmin = user.role == "MASTER_ADMIN" || user.role == "ADMIN"
    val isMasterAdmin = user.role == "MASTER_ADMIN"
    val isQuestionnaireManager = isMasterAdmin || user.canManageQuestionnaire
    val canReview = isAdmin || user.canReview
    // Provenance (created-by + per-field edit history) on the View Data screen: admins always, plus
    // any user explicitly granted the "view provenance" privilege.
    val canViewProvenance = isAdmin || user.canViewProvenance
    // Full-dataset download on the View Data screen: admins always, plus any user explicitly granted
    // the "download dataset" privilege.
    val canDownloadDataset = isAdmin || user.canDownloadDataset
    // Master admin lands in admin view; other admins opt in from the menu.
    var adminView by remember { mutableStateOf(isMasterAdmin) }

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

    fun canCreate(mode: EntryMode): Boolean = when (mode) {
        EntryMode.CRAFT -> isAdmin || user.canManageCrafts
        EntryMode.WORKSHOP -> isAdmin || user.canManageWorkshops
        EntryMode.USERS -> isAdmin
        else -> true
    }

    val dashboardModes = remember(user.role, user.canManageQuestionnaire, user.canManageCrafts, user.canManageWorkshops) {
        EntryMode.entries.filter { it != EntryMode.USERS || isAdmin }
    }

    fun refresh() {
        scope.launch {
            runCatching { repository.stats() }
                .onSuccess { stats = it }
                .onFailure { showMessage(it.message) }
        }
    }

    suspend fun loadLookups() {
        runCatching { repository.crafts() }.onSuccess { crafts = it }
        runCatching { repository.artisans() }.onSuccess { artisans = it }
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

    fun goDashboard() {
        message = null
        screen = Screen.Dashboard
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
            is Screen.Dashboard -> Screen.Dashboard
        }
    }

    // Leave the current screen, but if a form has unsaved work, route through the Save/Discard prompt
    // first so an accidental Back can't silently drop a record or its in-progress recordings.
    fun attemptExit(navigate: () -> Unit) {
        if (unsavedGuard.dirty && unsavedGuard.onSave != null) {
            pendingExit = navigate
        } else {
            navigate()
        }
    }

    val headerTitle = when (val s = screen) {
        is Screen.Dashboard -> "Field Repository"
        is Screen.Create -> s.mode.actionTitle
        is Screen.Browse -> "Update ${s.mode.label.lowercase()}"
        is Screen.Edit -> "Edit ${s.mode.label.lowercase()}"
        is Screen.MyActivity -> "My Activity"
        is Screen.ToolAssign -> "Assign tools to artisans"
        is Screen.Feedback -> "App feedback"
        is Screen.Settings -> "Settings"
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
                    AppDrawerContent(
                        user = user,
                        adminView = adminView,
                        isAdmin = isAdmin,
                        isMasterAdmin = isMasterAdmin,
                        pushingUpdate = pushingUpdate,
                        modes = dashboardModes.filter { canCreate(it) },
                        onDashboard = { goDashboard(); scope.launch { drawerState.close() } },
                        onSelect = { entry -> screen = Screen.Create(entry); scope.launch { drawerState.close() } },
                        onMyActivity = { message = null; screen = Screen.MyActivity; scope.launch { drawerState.close() } },
                        onAssignTools = { message = null; screen = Screen.ToolAssign; scope.launch { drawerState.close() } },
                        onFeedback = { message = null; screen = Screen.Feedback; scope.launch { drawerState.close() } },
                        onWalkthrough = { showWalkthrough = true; scope.launch { drawerState.close() } },
                        onToggleAdminView = { adminView = !adminView },
                        onSettings = { message = null; screen = Screen.Settings; scope.launch { drawerState.close() } },
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    headerTitle,
                    fontFamily = FontFamily.Serif,
                    fontSize = 28.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text("${user.name} · ${user.role}${if (adminView) " · admin view" else ""}", color = Muted, fontSize = 13.sp)
            }
            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                Icon(Icons.Filled.Menu, contentDescription = "Open menu", tint = MaterialTheme.colorScheme.primary)
            }
        }

        if (screen !is Screen.Dashboard) {
            BackPill(onClick = { attemptExit { goBack() } })
        }

        when (val s = screen) {
            is Screen.Dashboard -> {
                carryForward?.let { prefill ->
                    CarryForwardPanel(
                        prefill = prefill,
                        canCreateTool = canCreate(EntryMode.TOOL),
                        onSelect = { mode -> screen = Screen.Create(mode, prefill); carryForward = null },
                        onDismiss = { carryForward = null }
                    )
                }
                DashboardScreen(
                    stats = stats,
                    recentArtisans = artisans,
                    actions = dashboardModes,
                    canCreate = { canCreate(it) },
                    onNew = { selected -> message = null; screen = Screen.Create(selected) },
                    onUpdateExisting = { selected -> message = null; screen = Screen.Browse(selected) }
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
                    showProvenance = canViewProvenance,
                    canDownloadDataset = canDownloadDataset,
                    onError = { showMessage(it) }
                )
                EntryMode.TOOL -> ToolForm(
                    repository = repository,
                    crafts = crafts,
                    artisans = artisans,
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
                    prefill = s.prefill,
                    canManageQuestionnaire = isQuestionnaireManager,
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
                EntryMode.USERS -> UserManagementForm(
                    repository = repository,
                    isMasterAdmin = isMasterAdmin,
                    onError = { showMessage(it) }
                )
            }

            is Screen.Edit -> if (s.mode == EntryMode.QUESTIONNAIRE) {
                InterviewEditLoader(
                    repository = repository,
                    recordId = s.recordId,
                    sections = sections,
                    artisans = artisans,
                    canManageQuestionnaire = isQuestionnaireManager,
                    adminView = adminView,
                    canDelete = isAdmin,
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
                canDelete = isAdmin,
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
        }

        message?.let {
            Text(it, color = Body, modifier = Modifier.padding(bottom = 24.dp))
        }

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

/** Rounded back control with a real icon; steps to the previous screen. */
@Composable
private fun BackPill(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Back")
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
        Text("Transcription output", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
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
                Text("Process during an off-peak window", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
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

@Composable
private fun AppDrawerContent(
    user: UserDto,
    adminView: Boolean,
    isAdmin: Boolean,
    isMasterAdmin: Boolean,
    pushingUpdate: Boolean,
    modes: List<EntryMode>,
    onDashboard: () -> Unit,
    onSelect: (EntryMode) -> Unit,
    onMyActivity: () -> Unit,
    onAssignTools: () -> Unit,
    onFeedback: () -> Unit,
    onWalkthrough: () -> Unit,
    onToggleAdminView: () -> Unit,
    onSettings: () -> Unit,
    onPushUpdate: () -> Unit,
    onLogout: () -> Unit
) {
    ModalDrawerSheet {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Field Repository", fontFamily = FontFamily.Serif, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface)
                Text("${user.name} · ${user.role}", color = Muted, fontSize = 12.sp)
            }
            // Prominent red logout, top-right of the menu.
            OutlinedButton(
                onClick = onLogout,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD13438)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD13438)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Filled.Logout, contentDescription = "Logout", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Logout")
            }
        }
        HorizontalDivider()
        // The menu can be long (esp. for admins), so the items scroll within the remaining height
        // while the header/logout stay pinned.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            NavigationDrawerItem(
                label = { Text("Dashboard") },
                selected = false,
                icon = { Icon(Icons.Filled.Dashboard, contentDescription = null) },
                onClick = onDashboard,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
            NavigationDrawerItem(
                label = { Text("My Activity") },
                selected = false,
                icon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                onClick = onMyActivity,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
            modes.forEach { entry ->
                NavigationDrawerItem(
                    label = { Text(entry.actionTitle) },
                    selected = false,
                    icon = { Icon(entry.icon(), contentDescription = null) },
                    onClick = { onSelect(entry) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
            HorizontalDivider()
            NavigationDrawerItem(
                label = { Text("Assign tools to artisans") },
                selected = false,
                icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                onClick = onAssignTools,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
            NavigationDrawerItem(
                label = { Text("Give app feedback") },
                selected = false,
                icon = { Icon(Icons.Filled.RateReview, contentDescription = null) },
                onClick = onFeedback,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
            NavigationDrawerItem(
                label = { Text("App walkthrough / guide") },
                selected = false,
                icon = { Icon(Icons.Filled.Quiz, contentDescription = null) },
                onClick = onWalkthrough,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
            if (isAdmin) {
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text(if (adminView) "Admin view: ON" else "Admin view: OFF") },
                    selected = adminView,
                    icon = { Icon(Icons.Filled.ManageAccounts, contentDescription = null) },
                    onClick = onToggleAdminView,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
            if (isMasterAdmin) {
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    selected = false,
                    icon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    onClick = onSettings,
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(if (pushingUpdate) "Publishing update…" else "Push update to all") },
                    selected = false,
                    icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
                    onClick = { if (!pushingUpdate) onPushUpdate() },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    }
}

/** Post-save shortcuts that carry the just-saved artisan into a follow-up record. */
@Composable
private fun CarryForwardPanel(
    prefill: Prefill,
    canCreateTool: Boolean,
    onSelect: (EntryMode) -> Unit,
    onDismiss: () -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Saved ${prefill.artisanName ?: "artisan"} ✓", fontFamily = FontFamily.Serif, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
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
    actions: List<EntryMode>,
    canCreate: (EntryMode) -> Boolean,
    onNew: (EntryMode) -> Unit,
    onUpdateExisting: (EntryMode) -> Unit
) {
    val configuration = LocalConfiguration.current
    val columns = when {
        configuration.screenWidthDp >= 840 -> 4
        configuration.screenWidthDp >= 600 -> 3
        else -> 2
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text("What would you like to do?", fontFamily = FontFamily.Serif, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
        actions.chunked(columns).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { entry ->
                    DashboardActionCard(
                        entry = entry,
                        canCreate = canCreate(entry),
                        modifier = Modifier.weight(1f),
                        onNew = { onNew(entry) },
                        onUpdateExisting = { onUpdateExisting(entry) }
                    )
                }
                repeat(columns - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        StatsCard(stats)
        if (recentArtisans.isNotEmpty()) {
            Text("Recent artisans", fontFamily = FontFamily.Serif, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
            recentArtisans.take(6).forEach { artisan ->
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(artisan.name, fontWeight = FontWeight.SemiBold)
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
    else -> "New"
}

@Composable
private fun DashboardActionCard(
    entry: EntryMode,
    canCreate: Boolean,
    modifier: Modifier = Modifier,
    onNew: () -> Unit,
    onUpdateExisting: () -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Canvas),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(color = ColorCompat.darkElevated, shape = RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(entry.icon(), contentDescription = null, tint = Canvas, modifier = Modifier.size(22.dp))
            }
            Text(entry.label, fontFamily = FontFamily.Serif, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (canCreate) {
                Button(
                    onClick = onNew,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) { CardButtonLabel(Icons.Filled.Add, entry.createButtonLabel()) }
            }
            if (entry.editable) {
                OutlinedButton(
                    onClick = onUpdateExisting,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp)
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
    Text(text, maxLines = 1, softWrap = false, fontSize = 13.sp)
}

@Composable
private fun StatsCard(stats: DashboardStats?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("Repository totals", color = Canvas, fontFamily = FontFamily.Serif, fontSize = 24.sp)
            Spacer(Modifier.height(12.dp))
            if (stats == null) {
                Text("Loading...", color = SurfaceCard)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Stat("Artisans", stats.totalArtisans, Modifier.weight(1f))
                    Stat("Products", stats.totalProductRecords, Modifier.weight(1f))
                    Stat("Tools", stats.totalToolRecords, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Stat("Media", stats.totalMediaFiles, Modifier.weight(1f))
                    Stat("Pending", stats.pendingSubmissions, Modifier.weight(1f))
                    Stat("Workshops", stats.totalWorkshops, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(color = ColorCompat.darkElevated, shape = RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(value.toString(), color = Canvas, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = SurfaceCard, fontSize = 12.sp)
    }
}

private val productTypeOptions = listOf("FINISHED_GOOD", "SAMPLE", "RAW_MATERIAL", "COMPONENT", "PACKAGING", "OTHER")
private val marketDemandOptions = listOf("LOW", "MEDIUM", "HIGH", "SEASONAL", "UNKNOWN")
private val makerOptions = listOf("ARTISAN", "LOCAL_BLACKSMITH", "CARPENTER", "WORKSHOP", "FACTORY", "UNKNOWN", "OTHER")
private val traditionOptions = listOf("TRADITIONAL", "MODERN", "HYBRID", "UNKNOWN")
private val statusOptions = listOf("DRAFT", "PENDING", "APPROVED", "REJECTED")
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
        kotlin.math.abs(current.longitude - original.longitude) < 1e-6
    ) {
        return null
    }
    return current
}

/** Convert a read-model location into the request payload used by create/update calls. */
private fun LocationDto.toRequest(): LocationRequest =
    LocationRequest(
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracy = accuracy,
        address = address,
        placeName = placeName ?: address
    )

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
        Text("Document using grid", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
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
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedValue }?.second
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(
                    selectedLabel ?: placeholder,
                    color = if (selectedLabel != null) Body else Muted,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(if (expanded) "▴" else "▾", color = Muted)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(SurfaceCard)
            ) {
                if (includeNone) {
                    DropdownMenuItem(
                        text = { Text(placeholder, color = Muted) },
                        trailingIcon = { if (selectedValue.isBlank()) Text("✓", color = Coral) },
                        onClick = { onSelect(""); expanded = false }
                    )
                }
                options.forEach { (value, text) ->
                    val isSelected = value == selectedValue
                    DropdownMenuItem(
                        text = { Text(text, color = if (isSelected) Coral else Body, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { if (isSelected) Text("✓", color = Coral) },
                        onClick = { onSelect(value); expanded = false }
                    )
                }
            }
        }
    }
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

/** Generic checkbox multi-select over (id, label) options, mirroring ArtisanMultiSelectField. */
@Composable
private fun CheckboxMultiSelectField(
    label: String,
    options: List<Pair<String, String>>,
    selectedIds: Set<String>,
    emptyMessage: String = "No options available.",
    onToggle: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label (${selectedIds.size} selected)", color = Muted, fontSize = 12.sp)
        if (options.isEmpty()) {
            Text(emptyMessage, color = Muted, fontSize = 12.sp)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                options.forEach { (id, text) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(id) }
                    ) {
                        Checkbox(checked = selectedIds.contains(id), onCheckedChange = { onToggle(id) })
                        Text(text, color = Body, fontSize = 13.sp)
                    }
                }
            }
        }
    }
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
        Text("Attach media", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
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
        LocationEditor(
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
                    .background(color = ColorCompat.darkElevated, shape = RoundedCornerShape(12.dp))
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
                media.uris.take(6).forEach { uri ->
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
                if (media.uris.size > 6) Text("+${media.uris.size - 6} more", color = SurfaceCard, fontSize = 12.sp)
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
            .background(color = ColorCompat.darkElevated, shape = RoundedCornerShape(12.dp))
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
        uris.take(8).forEach { uri ->
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
        if (uris.size > 8) Text("+${uris.size - 8} more", color = SurfaceCard, fontSize = 12.sp)
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
    var saving by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }

    fun submit() {
        if (!validateRequired(listOf(
                RequiredCheck(name.isBlank(), { nameError = it }, nameFocus)
            ))) { onError("Please fill the required field highlighted above."); return }
        scope.launch {
            saving = true
            runCatching {
                val body = CraftCreateRequest(
                    name = name.trim(),
                    localName = localName.blankToNull(),
                    category = category.blankToNull(),
                    place = place.blankToNull(),
                    description = description.blankToNull(),
                    recordedAt = if (isEdit) null else Instant.now().toString()
                )
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
            media.uris.isNotEmpty() || media.measurementUri != null
    )

    RecordCard(title = if (isEdit) "Edit craft" else "Add craft") {
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        RequiredInput("Craft name", name, nameError, nameFocus) { name = it }
        TextInput("Local name", localName) { localName = it }
        TextInput("Category", category) { category = it }
        TextInput("Place", place) { place = it }
        TextInput("Description", description, minLines = 3) { description = it }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "craft", recordId = editing!!.id, onError = onError)
        }
        MediaCaptureSection(repository = repository, media = media, onMessage = onError, onError = onError)
        Button(
            onClick = { submit() },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving..." else if (isEdit) "Update craft" else "Save craft")
        }
    }
}

@Composable
private fun ArtisanForm(
    repository: FieldRepository,
    crafts: List<CraftDto>,
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
    var craftId by remember(editing) { mutableStateOf(editing?.craftId ?: prefill?.craftId ?: "") }
    var newCraftName by remember(editing) { mutableStateOf("") }
    var status by remember(editing) { mutableStateOf(editing?.status ?: "PENDING") }
    var saving by remember { mutableStateOf(false) }
    val hasCraft = craftId.isNotBlank() || newCraftName.isNotBlank()
    var nameError by remember { mutableStateOf<String?>(null) }
    var placeError by remember { mutableStateOf<String?>(null) }
    var craftError by remember { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }
    val craftFocus = remember { FocusRequester() }

    LaunchedEffect(editing) {
        val existing = editing?.location
        if (existing != null && media.location == null) media.location = existing.toRequest()
    }

    fun submit() {
        if (!validateRequired(listOf(
                RequiredCheck(name.isBlank(), { nameError = it }, nameFocus),
                RequiredCheck(place.isBlank(), { placeError = it }, placeFocus),
                RequiredCheck(!hasCraft, { craftError = it }, craftFocus)
            ))) { onError("Please fill the required field highlighted above."); return }
        scope.launch {
            saving = true
            runCatching {
                val body = ArtisanCreateRequest(
                    name = name.trim(),
                    localName = localName.blankToNull(),
                    gender = gender.blankToNull(),
                    phone = phone.blankToNull(),
                    email = email.blankToNull(),
                    place = place.trim(),
                    address = address.blankToNull(),
                    notes = notes.blankToNull(),
                    craftId = craftId.ifBlank { null },
                    craftName = if (craftId.isBlank()) newCraftName.blankToNull() else null,
                    status = status,
                    recordedAt = if (isEdit) null else Instant.now().toString(),
                    location = locationForBody(isEdit, media.location, editing?.location)
                )
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
            }.onFailure { onError(it.message ?: "Unable to save artisan") }
            saving = false
        }
    }
    val initialSig = remember(editing) {
        listOf(name, localName, gender, phone, email, place, address, notes, craftId, newCraftName, status).joinToString("")
    }
    val dirty = !saving && (
        listOf(name, localName, gender, phone, email, place, address, notes, craftId, newCraftName, status).joinToString("") != initialSig ||
            media.uris.isNotEmpty() || media.measurementUri != null
    )

    RecordCard(title = if (isEdit) "Edit artisan" else "Add artisan") {
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        RequiredInput("Name", name, nameError, nameFocus) { name = it }
        TextInput("Local name", localName) { localName = it }
        DropdownField(
            label = "Craft *",
            options = crafts.map { it.id to it.name },
            selectedValue = craftId,
            placeholder = "Select existing craft",
            onSelect = { craftId = it }
        )
        OutlinedTextField(
            value = newCraftName,
            onValueChange = { newCraftName = it },
            label = { Text("Or new craft name") },
            isError = craftError != null,
            supportingText = craftError?.let { msg -> { Text(msg) } },
            modifier = Modifier.fillMaxWidth().focusRequester(craftFocus)
        )
        RequiredInput("Place", place, placeError, placeFocus) { place = it }
        DropdownField(
            label = "Gender",
            options = genderOptions.map { it to it },
            selectedValue = gender,
            includeNone = false
        ) { gender = it }
        TextInput("Phone", phone) { phone = it }
        TextInput("Email", email) { email = it }
        TextInput("Address", address, minLines = 2) { address = it }
        TextInput("Notes", notes, minLines = 3) { notes = it }
        StatusDropdown(value = status) { status = it }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "artisan", recordId = editing!!.id, onError = onError)
        }
        MediaCaptureSection(repository = repository, media = media, onMessage = onError, onError = onError)
        Button(
            onClick = { submit() },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving..." else if (isEdit) "Update artisan" else "Save artisan")
        }
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
    var status by remember(editing) { mutableStateOf(editing?.status ?: "PENDING") }
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
        scope.launch {
            saving = true
            runCatching {
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
        RequiredInput("Workshop title", title, titleError, titleFocus) { title = it }
        RequiredInput("Place", place, placeError, placeFocus) { place = it }
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
        StatusDropdown(value = status) { status = it }
        TextInput("Description", description, minLines = 3) { description = it }
        TextInput("Notes", notes, minLines = 3) { notes = it }
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
        Button(
            onClick = { submit() },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving..." else if (isEdit) "Update workshop" else "Save workshop")
        }
    }
}

@Composable
private fun ProductForm(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
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
    var status by remember(editing) { mutableStateOf(editing?.status ?: "PENDING") }
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
        scope.launch {
            saving = true
            runCatching {
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
                    status = status,
                    recordedAt = if (isEdit) null else Instant.now().toString(),
                    location = locationForBody(isEdit, media.location, editing?.location)
                )
                val productId = if (isEdit) {
                    repository.updateProduct(editing!!.id, body).id
                } else {
                    repository.createProduct(body).id
                }
                uploadAttachments(repository, context, media, "product", productId, productName, "Field media for ${productName.trim()}")
            }.onSuccess {
                media.reset()
                onDone()
            }.onFailure { onError(it.message ?: "Unable to save product") }
            saving = false
        }
    }
    val productSig: () -> String = {
        listOf(productName, localName, craftName, artisanName, place, productType, marketDemand, timeTaken, size,
            length, breadth, height, costOfMaking, sellingPrice, rawMaterials, mainTools, functionUse, remarks,
            status, craftId, artisanId).joinToString("")
    }
    val initialSig = remember(editing) { productSig() }
    val dirty = !saving && (productSig() != initialSig || media.uris.isNotEmpty() || media.measurementUri != null)

    RecordCard(title = if (isEdit) "Edit product" else "Add product") {
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        RequiredInput("Product name", productName, productNameError, productNameFocus) { productName = it }
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
        RequiredInput("Craft name", craftName, craftNameError, craftNameFocus) { craftName = it }
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
            }
        }
        if (craftId.isNotBlank() && artisanOptionsForCraft.isEmpty()) {
            Text("No artisans are linked to this craft yet.", color = Muted, fontSize = 12.sp)
        }
        RequiredInput("Artisan name", artisanName, artisanNameError, artisanNameFocus) { artisanName = it }
        RequiredInput("Place", place, placeError, placeFocus) { place = it }
        TextInput("Time taken to complete", timeTaken) { timeTaken = it }
        TextInput("Size", size) { size = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Length (inches)", length) { length = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Breadth (inches)", breadth) { breadth = it } }
        }
        TextInput("Height (inches)", height) { height = it }
        GridMeasurementSection(
            repository = repository,
            media = media,
            includeHeight = true,
            onLengthBreadth = { l, b -> if (l != null && l > 0) length = numToText(l); if (b != null && b > 0) breadth = numToText(b) },
            onHeight = { height = numToText(it) }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Cost of making", costOfMaking) { costOfMaking = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Selling price", sellingPrice) { sellingPrice = it } }
        }
        DropdownField("Market demand", marketDemandOptions.map { it to it }, marketDemand, includeNone = false) { marketDemand = it }
        TextInput("Raw materials used", rawMaterials, minLines = 2) { rawMaterials = it }
        TextInput("Main tools used", mainTools, minLines = 2) { mainTools = it }
        TextInput("Function or use", functionUse, minLines = 2) { functionUse = it }
        TextInput("Remarks", remarks, minLines = 3) { remarks = it }
        StatusDropdown(value = status) { status = it }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "product", recordId = editing!!.id, onError = onError)
        }
        MediaCaptureSection(repository = repository, media = media, onMessage = onError, onError = onError)
        Button(
            onClick = { submit() },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving..." else if (isEdit) "Update product" else "Save product")
        }
    }
}

@Composable
private fun ToolForm(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
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
    var status by remember(editing) { mutableStateOf(editing?.status ?: "PENDING") }
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
        scope.launch {
            saving = true
            runCatching {
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
                    status = status,
                    recordedAt = if (isEdit) null else Instant.now().toString(),
                    location = locationForBody(isEdit, media.location, editing?.location)
                )
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
            }.onSuccess {
                media.reset()
                stages.reset()
                onDone()
            }.onFailure { onError(it.message ?: "Unable to save tool") }
            saving = false
        }
    }
    val toolSig: () -> String = {
        listOf(toolkitName, localName, englishName, craftName, artisanName, place, processUsedIn, material,
            yearsInUse, height, width, length, breadth, thickness, weight, radius, maker, traditionType,
            replacementCost, suggestions, remarks, status, craftId, artisanId).joinToString("")
    }
    val initialSig = remember(editing) { toolSig() }
    val dirty = !saving && (
        toolSig() != initialSig || media.uris.isNotEmpty() || media.measurementUri != null || stages.uris.isNotEmpty()
    )

    RecordCard(title = if (isEdit) "Edit tool" else "Add tool") {
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        RequiredInput("Toolkit name", toolkitName, toolkitNameError, toolkitNameFocus) { toolkitName = it }
        TextInput("Local name", localName) { localName = it }
        TextInput("English name", englishName) { englishName = it }
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
        RequiredInput("Craft name", craftName, craftNameError, craftNameFocus) { craftName = it }
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
            }
        }
        if (craftId.isNotBlank() && artisanOptionsForCraft.isEmpty()) {
            Text("No artisans are linked to this craft yet.", color = Muted, fontSize = 12.sp)
        }
        RequiredInput("Artisan name", artisanName, artisanNameError, artisanNameFocus) { artisanName = it }
        RequiredInput("Place", place, placeError, placeFocus) { place = it }
        TextInput("Process used in", processUsedIn) { processUsedIn = it }
        TextInput("Material", material) { material = it }
        TextInput("Years in use", yearsInUse) { yearsInUse = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Height", height) { height = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Width", width) { width = it } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Length (inches)", length) { length = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Breadth (inches)", breadth) { breadth = it } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Thickness", thickness) { thickness = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Weight", weight) { weight = it } }
        }
        TextInput("Radius", radius) { radius = it }
        GridMeasurementSection(
            repository = repository,
            media = media,
            includeHeight = true,
            onLengthBreadth = { l, b -> if (l != null && l > 0) length = numToText(l); if (b != null && b > 0) breadth = numToText(b) },
            onHeight = { height = numToText(it) }
        )
        DropdownField("Maker", makerOptions.map { it to it }, maker, includeNone = false) { maker = it }
        DropdownField("Tradition type", traditionOptions.map { it to it }, traditionType, includeNone = false) { traditionType = it }
        TextInput("Replacement cost", replacementCost) { replacementCost = it }
        TextInput("Suggestions for improvement", suggestions, minLines = 2) { suggestions = it }
        TextInput("Remarks", remarks, minLines = 3) { remarks = it }
        StatusDropdown(value = status) { status = it }
        ToolStagesSection(stages = stages, onMessage = onError, onError = onError)
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "tool", recordId = editing!!.id, onError = onError)
        }
        MediaCaptureSection(repository = repository, media = media, onMessage = onError, onError = onError)
        Button(
            onClick = { submit() },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving..." else if (isEdit) "Update tool" else "Save tool")
        }
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
        Text("Process stages", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
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
    var name by remember(editing) { mutableStateOf(editing?.name ?: "") }
    var artisanId by remember(editing) { mutableStateOf(editing?.product?.artisanId ?: "") }
    var productId by remember(editing) { mutableStateOf(editing?.productId ?: "") }
    var artisanError by remember { mutableStateOf<String?>(null) }
    var preProcessAvailable by remember(editing) { mutableStateOf(editing?.preProcessAvailable ?: false) }
    var notes by remember(editing) { mutableStateOf(editing?.notes ?: "") }
    var status by remember(editing) { mutableStateOf(editing?.status ?: "PENDING") }
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
        runCatching { repository.artisans() }.onSuccess { artisans = it }
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
            saving = true
            runCatching {
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
                    recordedAt = if (isEdit) null else Instant.now().toString()
                )
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
                preMedia.reset()
                steps.forEach { it.media.reset() }
                onDone()
            }.onFailure { onError(it.message ?: "Unable to save process") }
            saving = false
        }
    }
    val procSig: () -> String = {
        listOf(name, artisanId, productId, notes, status, preProcessAvailable.toString(),
            steps.joinToString("|") { "${it.serverId}~${it.name}~${it.stepType}~${it.notes}~${it.media.uris.size}" }).joinToString("")
    }
    val initialSig = remember(editing) { procSig() }
    val dirty = !saving && (
        procSig() != initialSig || preMedia.uris.isNotEmpty() || steps.any { it.media.uris.isNotEmpty() }
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
        RequiredInput("Name of the process", name, nameError, nameFocus) { name = it }
        DropdownField(
            label = "Artisan *",
            options = artisans.map { it.id to "${it.name} · ${it.place}" },
            selectedValue = artisanId,
            placeholder = "Select the artisan",
            includeNone = false
        ) { picked ->
            if (picked != artisanId) { artisanId = picked; productId = "" }
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
        ) { productId = it }
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
        Text("Steps", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
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
                                TextInput("Additional context for this step", step.notes, minLines = 3) { step.notes = it }
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

        StatusDropdown(value = status) { status = it }

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

        Button(
            onClick = { submit() },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving..." else if (isEdit) "Update process" else "Save process")
        }
    }
}

// ===========================================================================
// View Data — read-only browser for any record type, with transcripts
// ===========================================================================

private val viewDataModes = listOf(
    EntryMode.ARTISAN, EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.TOOL,
    EntryMode.QUESTIONNAIRE, EntryMode.WORKSHOP, EntryMode.CRAFT
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
    EntryMode.ARTISAN -> repository.artisans().map { it.id to "${it.name} · ${it.place}" }
    EntryMode.CRAFT -> repository.crafts().map { it.id to (it.name + (it.place?.let { p -> " · $p" } ?: "")) }
    EntryMode.PRODUCT -> repository.products().map { it.id to "${it.productName} · ${it.artisanName}" }
    EntryMode.PROCESS -> repository.processes().map { it.id to (it.name + (it.product?.productName?.let { p -> " · $p" } ?: "")) }
    EntryMode.TOOL -> repository.tools().map { it.id to "${it.toolkitName} · ${it.artisanName}" }
    EntryMode.WORKSHOP -> repository.workshops().map { it.id to it.title.ifBlank { "Untitled workshop" } }
    EntryMode.QUESTIONNAIRE -> repository.interviews().map { it.id to it.title.ifBlank { "Untitled interview" } }
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
                        Text(item.title, color = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
    val status = media.transcriptStatus?.uppercase()
    val isAudio = media.mediaType.equals("AUDIO", ignoreCase = true)
    val processing = setOf("QUEUED", "PROCESSING", "PENDING", "RUNNING")
    val done = setOf("COMPLETED", "EMPTY", "DONE")
    // The transcript is hoisted into local state so that approving an AI-refined version replaces the
    // shown transcript immediately (the backend has already persisted it for the next load).
    var transcriptText by remember(media.id) { mutableStateOf(media.transcriptText) }
    when {
        !transcriptText.isNullOrBlank() -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Transcript", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
        }
        isAudio && (status == null || status in processing) -> {
            // Still processing — show a buffer icon; the transcript appears here once it's ready.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Transcribing audio…", color = Muted, fontSize = 11.sp)
            }
        }
        isAudio && status in done -> {
            Text("Transcript complete — no speech detected.", color = Muted, fontSize = 11.sp)
        }
        isAudio -> {
            Text(
                "Transcript: ${media.transcriptStatus}" + (media.transcriptError?.let { " — $it" } ?: ""),
                color = Muted,
                fontSize = 11.sp
            )
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

private val walkthroughSteps = listOf(
    WalkStep(
        "Welcome to Field Repository",
        "This quick tour shows how to document artisans, crafts, products, tools, workshops, processes " +
            "and interviews — and how recordings, transcripts and media work. You can Skip any time and " +
            "reopen this from the menu later."
    ),
    WalkStep(
        "Your dashboard",
        "The home screen shows repository totals and a tile for each record type. Tap a tile's “New” " +
            "to add a record, or “Update existing” to find and edit one."
    ),
    WalkStep(
        "The menu (☰)",
        "Tap the menu at the top-right to jump straight to any record type, your own activity, app " +
            "feedback, this walkthrough, and to sign out."
    ),
    WalkStep(
        "Artisans, crafts & products",
        "Fill the form fields (required ones are highlighted). Products and tools link to a craft and an " +
            "artisan — choose the linked craft first, then the artisan, and the names fill in for you."
    ),
    WalkStep(
        "Attach photos, video & audio",
        "Every form has an attach-media section: capture or pick files. They upload as you go with a live " +
            "progress card, and you can remove any file with the ✕ cross before saving."
    ),
    WalkStep(
        "Measure with the grid",
        "On products and tools, “Document using grid” reads length/breadth/height from a photo of the " +
            "object on a 1-inch grid sheet and fills the fields. Remove a grid photo with its ✕."
    ),
    WalkStep(
        "Questionnaire interviews",
        "Pick the artisan(s), then record each question — or a whole section in one take. Clips upload as " +
            "you record, shown in a per-section card and an all-recordings card, and audio is transcribed " +
            "automatically."
    ),
    WalkStep(
        "Transcripts & AI refine",
        "Under each audio transcript, tap “Refine transcript” (or “Refine & translate”) to turn it " +
            "into a clean interviewer/interviewee conversation, then Approve it to save it in place of the " +
            "raw text. These use AI and cost a little, so you'll be asked to confirm."
    ),
    WalkStep(
        "Browse & view data",
        "“View Data → Browse records” lets you pick a record type and entry to view everything, " +
            "including transcribed audio. For questionnaires, choose the involved artisan(s) first."
    ),
    WalkStep(
        "Staying up to date",
        "When a newer app version is published you'll be prompted to update the next time you open the app " +
            "— just tap “Update now”. That's it — you're ready to go!"
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
                Text(current.title, fontFamily = FontFamily.Serif, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
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
                line.startsWith("### ") -> Text(parseInlineMarkdown(line.removePrefix("### ")), color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                line.startsWith("## ") -> Text(parseInlineMarkdown(line.removePrefix("## ")), color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                line.startsWith("# ") -> Text(parseInlineMarkdown(line.removePrefix("# ")), color = color, fontWeight = FontWeight.Bold, fontSize = 17.sp)
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
    Text("Media & transcripts", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
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
            Text("Ratings", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
            Text("In your words", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
                        Text(fb.user?.name ?: "Unknown user", color = Body, fontWeight = FontWeight.SemiBold)
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

@Composable
private fun ViewDataScreen(repository: FieldRepository, canReview: Boolean = false, masterAdmin: Boolean = false, showProvenance: Boolean = false, canDownloadDataset: Boolean = false, onError: (String) -> Unit) {
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
        if (mode == EntryMode.QUESTIONNAIRE && selectedId.isNotBlank() && questionnaireOptions.none { it.first == selectedId }) {
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
                DropdownField(
                    label = "Select questionnaire",
                    options = questionnaireOptions,
                    selectedValue = selectedId,
                    placeholder = when {
                        !hasArtisan -> "Select an involved artisan first"
                        questionnaireOptions.isEmpty() -> "No questionnaires for the selected artisan(s)"
                        else -> "Select a questionnaire"
                    },
                    includeNone = false,
                    enabled = hasArtisan && questionnaireOptions.isNotEmpty()
                ) { selectedId = it }
                if (hasArtisan && questionnaireOptions.isEmpty()) {
                    Text("No questionnaires involve the selected artisan(s) yet.", color = Muted, fontSize = 12.sp)
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
    if (selectedId.isNotBlank()) {
        ViewDataDetail(repository = repository, mode = mode, recordId = selectedId, showProvenance = showProvenance, onError = onError)
    }
    if (canDownloadDataset) {
        DatasetDownloadCard(repository = repository, onError = onError)
    }
    if (canReview) {
        ReviewApprovalCard(repository = repository, onError = onError)
    }
    // Master-admin recovery: recordings whose original record was deleted stay visible & playable
    // here (and can be re-linked), so no field audio is silently lost.
    if (masterAdmin) {
        OrphanRecordingsCard(repository = repository, onError = onError)
    }
    // App feedback, under every other section: the master admin reviews everyone's feedback here,
    // grouped by user. Ordinary users give/update their own feedback from the hamburger menu instead.
    if (masterAdmin) {
        MasterFeedbackCard(repository = repository, onError = onError)
    }
}

/**
 * Admin/reviewer-only: the queue of records still awaiting review (status PENDING). Each card can be
 * approved or rejected in place; the list refreshes so cleared items drop off. Gated by [canReview]
 * (any admin, or a user the master admin granted the review permission).
 */
@Composable
private fun ReviewApprovalCard(repository: FieldRepository, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<PendingReviewDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var actingId by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { repository.pendingReviews() }
                .onSuccess { pending = it }
                .onFailure { onError(it.message ?: "Unable to load the review queue") }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    RecordCard(title = "Reviews & approvals") {
        Text(
            "Records submitted by the team that are still pending review. Approve to publish them, or " +
                "reject to send them back.",
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
                                    formatIsoDate(item.createdAt)
                                ).joinToString(" · "),
                                color = Muted,
                                fontSize = 11.sp
                            )
                            val busy = actingId == item.id
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = {
                                        actingId = item.id
                                        scope.launch {
                                            runCatching { repository.approveRecord(item.recordType, item.id) }
                                                .onSuccess {
                                                    pending = pending.filterNot { it.id == item.id }
                                                    info = "Approved ${item.label}"
                                                }
                                                .onFailure { e -> onError(e.message ?: "Unable to approve") }
                                            actingId = null
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                                ) { Text(if (busy) "Working…" else "Approve", maxLines = 1, fontSize = 13.sp) }
                                OutlinedButton(
                                    onClick = {
                                        actingId = item.id
                                        scope.launch {
                                            runCatching { repository.rejectRecord(item.recordType, item.id) }
                                                .onSuccess {
                                                    pending = pending.filterNot { it.id == item.id }
                                                    info = "Rejected ${item.label}"
                                                }
                                                .onFailure { e -> onError(e.message ?: "Unable to reject") }
                                            actingId = null
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                                ) { Text("Reject", maxLines = 1, fontSize = 13.sp) }
                            }
                        }
                    }
                }
            }
        }
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
    }
}

@Composable
private fun ViewDataDetail(
    repository: FieldRepository,
    mode: EntryMode,
    recordId: String,
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
                    Text("Pre-process media", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
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
                DetailRow("Status", v.status)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError)
            }
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
                    Text("Answers", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    allResponses.forEach { r -> DetailRow(r.answeredBy?.name ?: "Answer", r.answerText) }
                }
                HorizontalDivider()
                Text("Recordings & media", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                if (groupMedia.isEmpty()) Text("No media attached.", color = Muted, fontSize = 12.sp)
                else groupMedia.forEach { MediaWithTranscript(context, it, repository) }
            }
        }
        else -> Text("This record type cannot be viewed here.", color = Muted)
    }
}

/** Record types a miscellaneous-media upload can be linked to (item: Misc Media). */
private val mediaLinkModes = listOf(
    EntryMode.ARTISAN, EntryMode.WORKSHOP, EntryMode.CRAFT, EntryMode.TOOL,
    EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.QUESTIONNAIRE
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
        Text(
            "Images, videos, audio and files upload to the same repository backend. Audio is queued for Whisper transcription after upload.",
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
        LocationEditor(
            value = location,
            onUseGps = {
                permissionLauncher.launch(requiredAndroidPermissions())
                readLastKnownLocation(context)
            },
            onChange = { location = it },
            onMessage = { localMessage = it }
        )
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
        if (selectedUris.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = ColorCompat.darkElevated, shape = RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("${selectedUris.size} file(s) ready", color = Canvas, fontWeight = FontWeight.SemiBold)
                selectedUris.take(8).forEach { uri ->
                    AndroidUriPreview(
                        context = context,
                        uri = uri,
                        onDownload = { saveLocalUriToDevice(context, uri) },
                        onRemove = { selectedUris = selectedUris.filterNot { it == uri } }
                    )
                }
                if (selectedUris.size > 8) {
                    Text("+${selectedUris.size - 8} more", color = SurfaceCard, fontSize = 12.sp)
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
            Text("Recent saved media", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
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
    var recordMode by remember { mutableStateOf("INDIVIDUAL") }
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

    RecordCard(title = if (isEdit) "Edit interview" else "Add questionnaire interview") {
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        if (isEdit) {
            Text("Add or update answers below. Existing answers from other interviewers are preserved unless you change them.", color = Muted, fontSize = 12.sp)
        }
        RequiredInput("Interview title", title, titleError, titleFocus) { title = it }
        ArtisanMultiSelectField(
            label = "Linked artisans",
            artisans = artisans,
            selectedIds = selectedArtisans
        ) { id ->
            selectedArtisans = if (selectedArtisans.contains(id)) selectedArtisans - id else selectedArtisans + id
        }
        TextInput("Place", place) { place = it }
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
        LocationEditor(
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
                                Text("${section.code}. ${section.title}", color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Serif, fontSize = 18.sp)
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
                                Text("This section's recordings (uploading)", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
                                Text("Saved recordings & media for this section", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
            Text("All recordings (every section)", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            Text("Every question/section clip across the whole interview, uploading as you record. They link to this interview on save.", color = Muted, fontSize = 12.sp)
            AttachedUploadsCard(context = context, media = qMedia, label = "recording", repository = repository) { uri -> removeClipUri(uri) }
        }
        // Any saved media not tied to a specific section (general attachments, or recordings whose
        // prompt/title changed) — shown in edit mode so nothing is ever hidden or lost.
        if (isEdit) {
            val otherSavedMedia = savedMedia.filterNot { m -> sections.any { captionBelongsToSection(m.caption, it) } }
            if (otherSavedMedia.isNotEmpty()) {
                HorizontalDivider()
                Text("Other saved recordings & media", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                otherSavedMedia.forEach { MediaWithTranscript(context, it, repository) }
            }
        }
        // Attach photos, video, audio files and other media to this interview (with live upload
        // progress) — the same media array used by every other record form.
        MediaCaptureSection(repository = repository, media = media, onMessage = onError, onError = onError)
        TextInput("Notes", notes, minLines = 3) { notes = it }
        fun submit() {
            if (!validateRequired(listOf(
                    RequiredCheck(title.isBlank(), { titleError = it }, titleFocus)
                ))) { onError("Please fill the required field highlighted above."); return }
                scope.launch {
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
                                    artisanIds = if (selectedArtisans != originalArtisans) selectedArtisans.toList() else null,
                                    responses = responsesToSend.ifEmpty { null },
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
                                    artisanIds = selectedArtisans.toList(),
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
                    if (!isEdit) {
                        title = ""
                        selectedArtisans = emptySet()
                        place = ""
                        language = "Hindi"
                        notes = ""
                        capturedLocation = null
                        answers.values.forEach { it.value = "" }
                    }
                    onSaved()
                }
        }
        val qSig: () -> String = {
            listOf(title, place, language, notes, selectedArtisans.sorted().joinToString(","),
                answers.entries.joinToString("|") { "${it.key}=${it.value.value}" }).joinToString("")
        }
        val initialSig = remember(editing) { qSig() }
        // Any changed field, an unsaved general attachment, or an unsaved recorded clip makes the
        // interview "dirty" so an accidental Back offers to save it (including in-progress recordings).
        val dirty = qSig() != initialSig || qMedia.uris.isNotEmpty() || media.uris.isNotEmpty()
        RegisterUnsavedGuard(dirty = dirty) { submit() }
        Button(
            onClick = { submit() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isEdit) "Update interview" else "Save questionnaire")
        }
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
                            Text("${section.sortOrder}. ${section.code} - ${section.title}", fontWeight = FontWeight.SemiBold)
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

    RecordCard(title = "Users and access") {
        Text(
            "Admins can review users here. The master admin can grant or revoke questionnaire-builder, " +
                "craft/workshop creation, record review & approval, and view-provenance access. Tap a " +
                "user to expand and manage their privileges.",
            color = Muted,
            fontSize = 12.sp
        )
        if (loading) {
            Text("Loading users...", color = Muted)
        }
        users.forEach { appUser ->
            val isMaster = appUser.role == "MASTER_ADMIN"
            val canEditGrants = isMasterAdmin && !isMaster
            val expanded = expandedUsers.contains(appUser.id)
            // Count of granted privileges, for the collapsed summary line.
            val grantedCount = if (isMaster) 5 else listOf(
                appUser.canManageQuestionnaire, appUser.canManageCrafts, appUser.canManageWorkshops,
                appUser.canReview, appUser.canViewProvenance
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
                            Text(appUser.name, fontWeight = FontWeight.SemiBold)
                            Text("${appUser.email} · ${appUser.role}", color = Muted, fontSize = 12.sp)
                            Text(
                                if (isMaster) "All privileges (master admin)" else "$grantedCount of 5 privileges granted",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                        Text(if (expanded) "Hide ▲" else "Manage ▼", color = Body, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (expanded) {
                        HorizontalDivider()
                        GrantToggleRow(
                            label = "Questionnaire builder",
                            granted = isMaster || appUser.canManageQuestionnaire,
                            enabled = canEditGrants,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserQuestionnaireAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.message ?: "Unable to update questionnaire access") }
                                }
                            }
                        )
                        GrantToggleRow(
                            label = "Craft creation",
                            granted = isMaster || appUser.canManageCrafts,
                            enabled = canEditGrants,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserCraftAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.message ?: "Unable to update craft access") }
                                }
                            }
                        )
                        GrantToggleRow(
                            label = "Workshop creation",
                            granted = isMaster || appUser.canManageWorkshops,
                            enabled = canEditGrants,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserWorkshopAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.message ?: "Unable to update workshop access") }
                                }
                            }
                        )
                        GrantToggleRow(
                            label = "Record review & approval",
                            granted = isMaster || appUser.canReview,
                            enabled = canEditGrants,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserReviewAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.message ?: "Unable to update review access") }
                                }
                            }
                        )
                        GrantToggleRow(
                            label = "View provenance",
                            granted = isMaster || appUser.canViewProvenance,
                            enabled = canEditGrants,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserProvenanceAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.message ?: "Unable to update provenance access") }
                                }
                            }
                        )
                        GrantToggleRow(
                            label = "Download entire dataset",
                            granted = isMaster || appUser.role == "ADMIN" || appUser.canDownloadDataset,
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
private fun RecordCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Canvas),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontFamily = FontFamily.Serif, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
private fun TextInput(label: String, value: String, minLines: Int = 1, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = minLines,
        modifier = Modifier.fillMaxWidth()
    )
}

/** A mandatory text field: shows a trailing asterisk and an inline error when left empty. */
@Composable
private fun RequiredInput(
    label: String,
    value: String,
    error: String?,
    focusRequester: FocusRequester,
    minLines: Int = 1,
    onValueChange: (String) -> Unit
) {
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
            accuracy = it.accuracy.toDouble().takeIf { _ -> it.hasAccuracy() },
            placeName = "Android precise location"
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
