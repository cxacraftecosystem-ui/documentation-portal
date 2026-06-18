package com.fieldrepository.app

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
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
import com.fieldrepository.app.data.MediaFileDto
import com.fieldrepository.app.data.StagedMedia
import androidx.compose.runtime.DisposableEffect
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
    // Master admin lands in admin view; other admins opt in from the menu.
    var adminView by remember { mutableStateOf(isMasterAdmin) }

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
            is Screen.Dashboard -> Screen.Dashboard
        }
    }

    val headerTitle = when (val s = screen) {
        is Screen.Dashboard -> "Field Repository"
        is Screen.Create -> s.mode.actionTitle
        is Screen.Browse -> "Update ${s.mode.label.lowercase()}"
        is Screen.Edit -> "Edit ${s.mode.label.lowercase()}"
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = drawerState.isClosed && screen !is Screen.Dashboard) {
        goBack()
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
                        modes = dashboardModes.filter { canCreate(it) },
                        onDashboard = { goDashboard(); scope.launch { drawerState.close() } },
                        onSelect = { entry -> screen = Screen.Create(entry); scope.launch { drawerState.close() } },
                        onToggleAdminView = { adminView = !adminView },
                        onLogout = { scope.launch { drawerState.close() }; onLogout() }
                    )
                }
            }
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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
            BackPill(onClick = { goBack() })
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
        }

        message?.let {
            Text(it, color = Body, modifier = Modifier.padding(bottom = 24.dp))
        }
                }
            }
        }
    }
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
@Composable
private fun AppDrawerContent(
    user: UserDto,
    adminView: Boolean,
    isAdmin: Boolean,
    modes: List<EntryMode>,
    onDashboard: () -> Unit,
    onSelect: (EntryMode) -> Unit,
    onToggleAdminView: () -> Unit,
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
        NavigationDrawerItem(
            label = { Text("Dashboard") },
            selected = false,
            icon = { Icon(Icons.Filled.Dashboard, contentDescription = null) },
            onClick = onDashboard,
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

/** Shared holder for media attachments, captured GPS, and an optional measurement-grid image. */
private class MediaCaptureState {
    var uris by mutableStateOf<List<Uri>>(emptyList())
    var location by mutableStateOf<LocationRequest?>(null)
    var measurementUri by mutableStateOf<Uri?>(null)

    // Eager-upload bookkeeping. `stagedDeferred` is the in-flight pre-upload per uri; `staged`
    // mirrors the completed results for UI status. Managed by MediaCaptureSection.
    val stagedDeferred = mutableMapOf<Uri, Deferred<StagedMedia?>>()
    var staged by mutableStateOf<Map<Uri, StagedMedia>>(emptyMap())

    fun reset() {
        uris = emptyList()
        location = null
        measurementUri = null
        stagedDeferred.clear()
        staged = emptyMap()
    }
}

@Composable
private fun rememberMediaCaptureState(): MediaCaptureState = remember { MediaCaptureState() }

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
 * that one dimension and the returned inches auto-fill the matching field; the photo is also kept so
 * the caller can upload it as media on save. [onValue] reports the measured value, [onUrisChange]
 * the captured photos keyed by dimension.
 */
@Composable
private fun GridMeasurementSection(
    repository: FieldRepository,
    includeHeight: Boolean = true,
    onLengthBreadth: (length: Double?, breadth: Double?) -> Unit,
    onHeight: (Double) -> Unit,
    onUrisChange: (Map<String, Uri>) -> Unit
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
        capturedUris = capturedUris + (group to uri)
        onUrisChange(capturedUris)
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
            capturedUris[key]?.let { AndroidUriPreview(context = context, uri = it) }
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

/** Upload the per-dimension grid photos as media linked to a saved record (best-effort). */
private suspend fun uploadGridImages(
    repository: FieldRepository,
    context: Context,
    gridUris: Map<String, Uri>,
    location: LocationRequest?,
    recordType: String,
    recordId: String,
    titleHint: String?
) {
    gridUris.forEach { (dimension, uri) ->
        runCatching {
            repository.uploadMedia(
                context = context,
                uri = uri,
                linkedRecordType = recordType,
                linkedRecordId = recordId,
                caption = "${dimension.replaceFirstChar { it.uppercase() }} grid (measurement) for ${titleHint.orEmpty()}".trim(),
                location = location,
                titleHint = "${titleHint.orEmpty()} ${dimension} grid".trim(),
                batchIndex = 1,
                customSegment = "GRID_${dimension.uppercase()}"
            )
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

    // Eager upload: as soon as a file is attached, start pushing it to object storage so the
    // transfer is (usually) finished by the time the user taps save.
    LaunchedEffect(media.uris) {
        media.uris.forEach { uri ->
            if (!media.stagedDeferred.containsKey(uri)) {
                // Long audio/video is split into parts at save time, so it can't be eagerly staged as a
                // single object — the deferred resolves to null and the save path uploads (splitting) it.
                val deferred = AppScope.io.async {
                    if (repository.willSplit(context, uri)) null
                    else runCatching { repository.preuploadObject(context, uri) }.getOrNull()
                }
                media.stagedDeferred[uri] = deferred
                scope.launch {
                    val result = runCatching { deferred.await() }.getOrNull()
                    if (result != null) media.staged = media.staged + (uri to result)
                }
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
                Text(
                    if (media.staged.size >= media.uris.size) "All uploaded ✓ — ready to save"
                    else "Uploading… ${media.staged.size}/${media.uris.size} done",
                    color = SurfaceCard,
                    fontSize = 11.sp
                )
                media.uris.take(6).forEach { uri -> AndroidUriPreview(context = context, uri = uri) }
                if (media.uris.size > 6) Text("+${media.uris.size - 6} more", color = SurfaceCard, fontSize = 12.sp)
                TextButton(onClick = {
                    val pending = media.stagedDeferred.values.toList()
                    media.stagedDeferred.clear()
                    media.staged = emptyMap()
                    media.uris = emptyList()
                    AppScope.io.launch {
                        pending.forEach { d -> runCatching { d.await()?.let { repository.deleteStaged(it.objectKey) } } }
                    }
                }) { Text("Clear attachments") }
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
                EntryMode.QUESTIONNAIRE -> repository.interviews().map { it.id to (it.title.ifBlank { "Untitled interview" }) }
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

    RecordCard(title = if (isEdit) "Edit craft" else "Add craft") {
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
            onClick = {
                if (!validateRequired(listOf(
                        RequiredCheck(name.isBlank(), { nameError = it }, nameFocus)
                    ))) { onError("Please fill the required field highlighted above."); return@Button }
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
            },
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
    var gender by remember(editing) { mutableStateOf(editing?.gender ?: "") }
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

    RecordCard(title = if (isEdit) "Edit artisan" else "Add artisan") {
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
        TextInput("Gender", gender) { gender = it }
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
            onClick = {
                if (!validateRequired(listOf(
                        RequiredCheck(name.isBlank(), { nameError = it }, nameFocus),
                        RequiredCheck(place.isBlank(), { placeError = it }, placeFocus),
                        RequiredCheck(!hasCraft, { craftError = it }, craftFocus)
                    ))) { onError("Please fill the required field highlighted above."); return@Button }
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
            },
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

    RecordCard(title = if (isEdit) "Edit workshop" else "Add workshop") {
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
            onClick = {
                if (!validateRequired(listOf(
                        RequiredCheck(title.isBlank(), { titleError = it }, titleFocus),
                        RequiredCheck(place.isBlank(), { placeError = it }, placeFocus)
                    ))) { onError("Please fill the required field highlighted above."); return@Button }
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
            },
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
    var gridUris by remember { mutableStateOf<Map<String, Uri>>(emptyMap()) }
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

    RecordCard(title = if (isEdit) "Edit product" else "Add product") {
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
            includeHeight = true,
            onLengthBreadth = { l, b -> if (l != null && l > 0) length = numToText(l); if (b != null && b > 0) breadth = numToText(b) },
            onHeight = { height = numToText(it) },
            onUrisChange = { gridUris = it }
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
            onClick = {
                if (!validateRequired(listOf(
                        RequiredCheck(productName.isBlank(), { productNameError = it }, productNameFocus),
                        RequiredCheck(craftName.isBlank(), { craftNameError = it }, craftNameFocus),
                        RequiredCheck(artisanName.isBlank(), { artisanNameError = it }, artisanNameFocus),
                        RequiredCheck(place.isBlank(), { placeError = it }, placeFocus)
                    ))) { onError("Please fill the required field highlighted above."); return@Button }
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
                        uploadGridImages(repository, context, gridUris, media.location, "product", productId, productName)
                    }.onSuccess {
                        media.reset()
                        onDone()
                    }.onFailure { onError(it.message ?: "Unable to save product") }
                    saving = false
                }
            },
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
    var gridUris by remember { mutableStateOf<Map<String, Uri>>(emptyMap()) }
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

    RecordCard(title = if (isEdit) "Edit tool" else "Add tool") {
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
            includeHeight = true,
            onLengthBreadth = { l, b -> if (l != null && l > 0) length = numToText(l); if (b != null && b > 0) breadth = numToText(b) },
            onHeight = { height = numToText(it) },
            onUrisChange = { gridUris = it }
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
            onClick = {
                if (!validateRequired(listOf(
                        RequiredCheck(toolkitName.isBlank(), { toolkitNameError = it }, toolkitNameFocus),
                        RequiredCheck(craftName.isBlank(), { craftNameError = it }, craftNameFocus),
                        RequiredCheck(artisanName.isBlank(), { artisanNameError = it }, artisanNameFocus),
                        RequiredCheck(place.isBlank(), { placeError = it }, placeFocus)
                    ))) { onError("Please fill the required field highlighted above."); return@Button }
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
                        uploadGridImages(repository, context, gridUris, media.location, "tool", toolId, toolkitName)
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
            },
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
    val existingMedia: List<MediaFileDto> = emptyList(),
    notes: String? = null
) {
    var serverId by mutableStateOf(serverId)
    var name by mutableStateOf(name)
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

    // Products belong to an artisan, so the product list is scoped to the chosen artisan. We match
    // BOTH ways so the dropdown is never wrongly empty: (a) products the server links to this artisan
    // by id (fetched directly, so it works even with >100 total products), unioned with (b) products
    // already loaded whose artisan *name* matches (covers products saved without a linked artisanId,
    // e.g. when only the name was typed). Name match is trimmed + case-insensitive.
    var artisanProducts by remember { mutableStateOf<List<ProductDetailDto>>(emptyList()) }
    LaunchedEffect(artisanId, products) {
        if (artisanId.isBlank()) {
            artisanProducts = emptyList()
            return@LaunchedEffect
        }
        val selectedArtisanName = artisans.firstOrNull { it.id == artisanId }?.name?.trim()
        val linked = runCatching { repository.productsForArtisan(artisanId) }.getOrDefault(emptyList())
        val byName = products.filter { p ->
            (p.artisanId != null && p.artisanId == artisanId) ||
                (!selectedArtisanName.isNullOrBlank() && p.artisanName.trim().equals(selectedArtisanName, ignoreCase = true))
        }
        artisanProducts = (linked + byName).distinctBy { it.id }
    }

    RecordCard(title = if (isEdit) "Edit process" else "Document process") {
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
            placeholder = if (artisanId.isBlank()) "Select an artisan first" else "Select the product this process makes",
            includeNone = false,
            enabled = artisanId.isNotBlank()
        ) { productId = it }
        if (productError != null) Text(productError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = preProcessAvailable, onCheckedChange = { preProcessAvailable = it })
            Text("Pre-processes available", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        }
        if (preProcessAvailable) {
            Text("Attach the pre-process media (required).", color = Muted, fontSize = 12.sp)
            if (editing != null && editing.media.isNotEmpty()) {
                Text("Already attached:", color = Muted, fontSize = 11.sp)
                editing.media.forEach { AndroidSavedMediaPreview(context = context, media = it) }
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
                        step.existingMedia.forEach { AndroidSavedMediaPreview(context = context, media = it) }
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
            onClick = {
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
                if (blocked) { onError("Please fill the required fields highlighted above."); return@Button }

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
            },
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
private fun MediaWithTranscript(context: Context, media: MediaFileDto) {
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
    when {
        !media.transcriptText.isNullOrBlank() -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Transcript", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(media.transcriptText!!, color = Body, fontSize = 13.sp)
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
        else -> media.forEach { MediaWithTranscript(context, it) }
    }
}

@Composable
private fun ViewDataScreen(repository: FieldRepository, onError: (String) -> Unit) {
    var mode by remember { mutableStateOf(EntryMode.ARTISAN) }
    var options by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedId by remember { mutableStateOf("") }
    var loadingList by remember { mutableStateOf(false) }

    LaunchedEffect(mode) {
        loadingList = true
        selectedId = ""
        runCatching { loadViewEntries(repository, mode) }
            .onSuccess { options = it }
            .onFailure { onError(it.message ?: "Unable to load list") }
        loadingList = false
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
        ViewDataDetail(repository = repository, mode = mode, recordId = selectedId, onError = onError)
    }
    DatasetDownloadCard(repository = repository, onError = onError)
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
                DetailRow("Product", d.product?.productName)
                DetailRow("Pre-processes", if (d.preProcessAvailable) "Yes" else "No")
                DetailRow("Notes", d.notes)
                DetailRow("Status", d.status)
                if (d.media.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Pre-process media", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    d.media.forEach { MediaWithTranscript(context, it) }
                }
                d.steps.forEach { step ->
                    HorizontalDivider()
                    Text("Step ${step.sortOrder} · ${if (step.stepType == "SEQUENTIAL") "Sequential" else "Group"} — ${step.name}", color = Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    step.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, fontSize = 12.sp) }
                    if (step.media.isEmpty()) Text("No media.", color = Muted, fontSize = 12.sp)
                    step.media.forEach { MediaWithTranscript(context, it) }
                }
            }
        }
        EntryMode.ARTISAN -> {
            var d by remember(recordId) { mutableStateOf<ArtisanDetailDto?>(null) }
            LaunchedEffect(recordId) { runCatching { repository.artisan(recordId) }.onSuccess { d = it }.onFailure { onError(it.message ?: "Unable to load artisan") } }
            val v = d ?: return run { LoadingCard(mode) }
            RecordCard(title = v.name.ifBlank { "Artisan" }) {
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
                DetailRow("Local name", v.localName)
                DetailRow("Category", v.category)
                DetailRow("Place", v.place)
                DetailRow("Description", v.description)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError)
            }
        }
        EntryMode.QUESTIONNAIRE -> {
            var d by remember(recordId) { mutableStateOf<QuestionnaireInterviewDetailDto?>(null) }
            LaunchedEffect(recordId) { runCatching { repository.interview(recordId) }.onSuccess { d = it }.onFailure { onError(it.message ?: "Unable to load interview") } }
            val v = d ?: return run { LoadingCard(mode) }
            RecordCard(title = v.title.ifBlank { "Interview" }) {
                DetailRow("Place", v.place)
                DetailRow("Language", v.language)
                DetailRow("Notes", v.notes)
                DetailRow("Status", v.status)
                if (v.responses.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Answers", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    v.responses.forEach { r -> DetailRow(r.answeredBy?.name ?: "Answer", r.answerText) }
                }
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError)
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
                    AndroidUriPreview(context = context, uri = uri)
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
            savedMedia.take(10).forEach { media ->
                AndroidSavedMediaPreview(context = context, media = media)
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
private fun AndroidUriPreview(context: Context, uri: Uri) {
    val mimeType = remember(uri) { context.contentResolver.getType(uri) }
    val mediaType = remember(mimeType) { mediaTypeFromMime(mimeType) }
    var showViewer by remember { mutableStateOf(false) }
    MediaThumb(
        uri = uri,
        mediaType = mediaType,
        title = uri.lastPathSegment.orEmpty(),
        subtitle = mimeType ?: "Unknown file type",
        onOpen = {
            if (mediaType in IN_APP_PLAYABLE) showViewer = true else openUri(context, uri, mimeType)
        }
    )
    if (showViewer) {
        MediaViewerDialog(uri = uri, mediaType = mediaType, onDismiss = { showViewer = false })
    }
}

@Composable
private fun AndroidSavedMediaPreview(context: Context, media: com.fieldrepository.app.data.MediaFileDto) {
    val uri = remember(media.url) { media.url?.let(Uri::parse) }
    var showViewer by remember { mutableStateOf(false) }
    if (uri == null) {
        Text(media.originalFilename, color = Body, fontSize = 12.sp)
        return
    }
    MediaThumb(
        uri = uri,
        mediaType = media.mediaType,
        title = media.originalFilename,
        subtitle = listOfNotNull(media.mimeType ?: media.mediaType, media.transcriptStatus?.let { "Transcript: $it" }).joinToString(" · "),
        onOpen = {
            if (media.mediaType in IN_APP_PLAYABLE) showViewer = true else openUri(context, uri, media.mimeType)
        }
    )
    TextButton(onClick = { saveMediaToDevice(context, media.url, media.originalFilename, media.mimeType) }) {
        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Save to device", fontSize = 12.sp)
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
    var language by remember(editing) { mutableStateOf(editing?.language ?: "") }
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
    // Clips keyed by target: a question id (individual mode) or "section:<id>" (whole-section mode).
    var questionAudio by remember { mutableStateOf<Map<String, List<Uri>>>(emptyMap()) }
    fun addClip(key: String, uri: Uri) {
        questionAudio = questionAudio + (key to ((questionAudio[key] ?: emptyList()) + uri))
    }
    fun removeLastClip(key: String) {
        val list = questionAudio[key] ?: return
        questionAudio = questionAudio + (key to list.dropLast(1))
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
        TextInput("Language", language) { language = it }
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
                val answeredCount = activeQuestions.count { (answers[it.id]?.value?.trim().orEmpty()).isNotEmpty() }
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
                                Text("${activeQuestions.size} questions · $answeredCount answered", color = Muted, fontSize = 11.sp)
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
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        TextInput("Notes", notes, minLines = 3) { notes = it }
        Button(
            onClick = {
                if (!validateRequired(listOf(
                        RequiredCheck(title.isBlank(), { titleError = it }, titleFocus)
                    ))) { onError("Please fill the required field highlighted above."); return@Button }
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
                            if (key.startsWith("section:")) {
                                val section = sectionsById[key.removePrefix("section:")]
                                caption = "Section audio: ${section?.code ?: ""} ${section?.title ?: ""}".trim()
                                hint = title.ifBlank { section?.title ?: "Section recording" }
                            } else {
                                val question = questionsById[key]
                                caption = "Question audio: ${question?.sectionCode ?: ""}${question?.sortOrder ?: ""} ${question?.prompt ?: ""}".trim()
                                hint = title.ifBlank { question?.prompt ?: "Question recording" }
                            }
                            uris.forEachIndexed { index, uri ->
                                repository.uploadMedia(
                                    context = context,
                                    uri = uri,
                                    linkedRecordType = "questionnaire",
                                    linkedRecordId = interviewId,
                                    caption = caption,
                                    location = null,
                                    titleHint = hint,
                                    batchIndex = index + 1
                                )
                            }
                        }
                    }.onFailure {
                        onError(it.message ?: "Unable to save questionnaire")
                        return@launch
                    }
                    if (!isEdit) {
                        title = ""
                        selectedArtisans = emptySet()
                        place = ""
                        language = ""
                        notes = ""
                        capturedLocation = null
                        answers.values.forEach { it.value = "" }
                        questionAudio = emptyMap()
                    }
                    onSaved()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isEdit) "Update interview" else "Save questionnaire")
        }
        Text("Use the Media tab to attach photos, videos, audio recordings and GPS-tagged field files to this interview.", color = Muted, fontSize = 12.sp)
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

    RecordCard(title = "Users and questionnaire access") {
        Text(
            "Admins can review users here. The master admin can grant or revoke questionnaire-builder access.",
            color = Muted,
            fontSize = 12.sp
        )
        if (loading) {
            Text("Loading users...", color = Muted)
        }
        users.forEach { appUser ->
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appUser.name, fontWeight = FontWeight.SemiBold)
                    Text("${appUser.email} - ${appUser.role}", color = Muted, fontSize = 12.sp)
                    val isMaster = appUser.role == "MASTER_ADMIN"
                    val canEditGrants = isMasterAdmin && !isMaster
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

private fun createAudioRecorder(context: Context, file: File): MediaRecorder {
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }
    return recorder.apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
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
