package com.fieldrepository.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.fieldrepository.app.ui.DarkSurface
import com.fieldrepository.app.ui.FieldRepositoryTheme
import com.fieldrepository.app.ui.Muted
import com.fieldrepository.app.ui.SurfaceCard
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import retrofit2.HttpException
import android.app.DatePickerDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.data.CreatedRecordDto
import com.fieldrepository.app.data.MediaFileDto
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

private enum class EntryMode(val label: String, val actionTitle: String) {
    ARTISAN("Artisan", "Record artisan"),
    PRODUCT("Product", "Record product"),
    TOOL("Tool", "Record tool"),
    QUESTIONNAIRE("Questionnaire", "Take interview"),
    WORKSHOP("Workshop", "Record workshop"),
    CRAFT("Craft", "Add craft"),
    MEDIA("Media", "Upload media"),
    USERS("Users", "Manage users")
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
    // null == dashboard landing; a value == a specific capture screen.
    var mode by remember { mutableStateOf<EntryMode?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val isAdmin = user.role == "MASTER_ADMIN" || user.role == "ADMIN"
    val isQuestionnaireManager = user.role == "MASTER_ADMIN" || user.canManageQuestionnaire
    val availableModes = remember(user.role, user.canManageQuestionnaire) {
        EntryMode.entries.filter { entryMode -> entryMode != EntryMode.USERS || isAdmin }
    }

    fun refresh() {
        scope.launch {
            runCatching { repository.stats() }
                .onSuccess { stats = it }
                .onFailure { message = it.message }
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
            .onFailure { message = it.message }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (mode == null) "Field Repository" else mode!!.actionTitle,
                    fontFamily = FontFamily.Serif,
                    fontSize = 28.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text("${user.name} · ${user.role}", color = Muted, fontSize = 13.sp)
            }
            Box {
                OutlinedButton(onClick = { menuOpen = true }) { Text("☰ Menu") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Dashboard") }, onClick = { mode = null; menuOpen = false })
                    availableModes.forEach { entry ->
                        DropdownMenuItem(text = { Text(entry.actionTitle) }, onClick = { mode = entry; menuOpen = false })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Logout") },
                        onClick = {
                            menuOpen = false
                            onLogout()
                        }
                    )
                }
            }
        }

        if (mode != null) {
            TextButton(onClick = { mode = null }) { Text("← Back to dashboard") }
        }

        when (mode) {
            null -> DashboardScreen(
                stats = stats,
                recentArtisans = artisans,
                actions = availableModes,
                onSelect = { selected -> mode = selected }
            )
            EntryMode.CRAFT -> CraftForm(
                repository = repository,
                onSaved = {
                    message = "Craft saved with attached media"
                    refresh()
                    refreshLookups()
                },
                onError = { message = it }
            )
            EntryMode.ARTISAN -> ArtisanForm(
                repository = repository,
                crafts = crafts,
                onSaved = {
                    message = "Artisan saved with attached media"
                    refresh()
                    refreshLookups()
                },
                onError = { message = it }
            )
            EntryMode.WORKSHOP -> WorkshopForm(
                repository = repository,
                artisans = artisans,
                onSaved = {
                    message = "Workshop saved with attached media"
                    refresh()
                },
                onError = { message = it }
            )
            EntryMode.PRODUCT -> ProductForm(
                repository = repository,
                crafts = crafts,
                artisans = artisans,
                onSaved = {
                    message = "Product saved with attached media"
                    refresh()
                },
                onError = { message = it }
            )
            EntryMode.TOOL -> ToolForm(
                repository = repository,
                crafts = crafts,
                artisans = artisans,
                onSaved = {
                    message = "Tool saved with attached media"
                    refresh()
                },
                onError = { message = it }
            )
            EntryMode.MEDIA -> AndroidMediaForm(
                repository = repository,
                onUploaded = { count ->
                    message = "$count media file${if (count == 1) "" else "s"} uploaded and queued"
                    refresh()
                },
                onError = { message = it }
            )
            EntryMode.QUESTIONNAIRE -> QuestionnaireForm(
                repository = repository,
                sections = sections,
                artisans = artisans,
                canManageQuestionnaire = isQuestionnaireManager,
                onRefreshSections = {
                    runCatching { repository.questionnaireSections() }
                        .onSuccess { sections = it }
                        .onFailure { message = it.message }
                },
                onSubmit = { body ->
                    val created = repository.createQuestionnaireInterview(body)
                    refresh()
                    created.id
                },
                onError = { message = it },
                onSaved = {
                    message = "Questionnaire interview saved"
                    refresh()
                }
            )
            EntryMode.USERS -> UserManagementForm(
                repository = repository,
                isMasterAdmin = user.role == "MASTER_ADMIN",
                onError = { message = it }
            )
        }

        message?.let {
            Text(it, color = Body, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun DashboardScreen(
    stats: DashboardStats?,
    recentArtisans: List<ArtisanDto>,
    actions: List<EntryMode>,
    onSelect: (EntryMode) -> Unit
) {
    val configuration = LocalConfiguration.current
    val columns = when {
        configuration.screenWidthDp >= 840 -> 4
        configuration.screenWidthDp >= 600 -> 3
        else -> 2
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text("What would you like to record?", fontFamily = FontFamily.Serif, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
        actions.chunked(columns).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { entry ->
                    DashboardActionCard(entry = entry, modifier = Modifier.weight(1f)) { onSelect(entry) }
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

@Composable
private fun DashboardActionCard(entry: EntryMode, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Canvas),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .height(116.dp)
            .clickable(onClick = onClick)
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
                Text(entry.label.take(1), color = Canvas, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Text(entry.actionTitle, fontFamily = FontFamily.Serif, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("Create new entry", color = Muted, fontSize = 11.sp)
        }
    }
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

    fun reset() {
        uris = emptyList()
        location = null
        measurementUri = null
    }
}

@Composable
private fun rememberMediaCaptureState(): MediaCaptureState = remember { MediaCaptureState() }

private fun LocalDate.toIsoInstant(): String =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toString()

private suspend fun uploadAttachments(
    repository: FieldRepository,
    context: Context,
    media: MediaCaptureState,
    recordType: String,
    recordId: String,
    titleHint: String?,
    caption: String?
) {
    media.uris.forEachIndexed { index, uri ->
        repository.uploadMedia(
            context = context,
            uri = uri,
            linkedRecordType = recordType,
            linkedRecordId = recordId,
            caption = caption,
            location = media.location,
            titleHint = titleHint,
            batchIndex = index + 1
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

@Composable
private fun DropdownField(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    placeholder: String = "Select",
    includeNone: Boolean = true,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedValue }?.second
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLabel ?: placeholder, modifier = Modifier.weight(1f))
                Text("▾", color = Muted)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (includeNone) {
                    DropdownMenuItem(text = { Text(placeholder) }, onClick = { onSelect(""); expanded = false })
                }
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
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

/**
 * Reusable capture surface embedded inside every record form. Mirrors the web MediaCaptureField:
 * pick files, take photo/video, record audio, tag GPS, and (optionally) attach a measurement grid.
 */
@Composable
private fun MediaCaptureSection(
    media: MediaCaptureState,
    enableMeasurement: Boolean = false,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMeasurement by remember { mutableStateOf(false) }

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    permissionLauncher.launch(requiredAndroidPermissions())
                    val uri = createAppFileUri(context, "field-video-", ".mp4")
                    pendingMeasurement = false
                    pendingCaptureUri = uri
                    takeVideo.launch(uri)
                },
                modifier = Modifier.weight(1f)
            ) { Text("Record video") }
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
                modifier = Modifier.weight(1f)
            ) { Text(if (recording) "Stop audio" else "Record audio") }
        }
        OutlinedButton(
            onClick = {
                permissionLauncher.launch(requiredAndroidPermissions())
                val loc = readLastKnownLocation(context)
                media.location = loc
                onMessage(
                    loc?.let { "Location tagged: ${"%.5f".format(it.latitude)}, ${"%.5f".format(it.longitude)}" }
                        ?: "No GPS fix yet; try again after location warms up."
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (media.location != null) "GPS tagged ✓ (tap to refresh)" else "Use current GPS")
        }
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
                media.uris.take(6).forEach { uri -> AndroidUriPreview(context = context, uri = uri) }
                if (media.uris.size > 6) Text("+${media.uris.size - 6} more", color = SurfaceCard, fontSize = 12.sp)
                TextButton(onClick = { media.uris = emptyList() }) { Text("Clear attachments") }
            }
        }
    }
}

@Composable
private fun CraftForm(
    repository: FieldRepository,
    onSaved: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    var name by remember { mutableStateOf("") }
    var localName by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    RecordCard(title = "Add craft") {
        TextInput("Craft name", name) { name = it }
        TextInput("Local name", localName) { localName = it }
        TextInput("Category", category) { category = it }
        TextInput("Place", place) { place = it }
        TextInput("Description", description, minLines = 3) { description = it }
        MediaCaptureSection(media = media, onMessage = onError, onError = onError)
        Button(
            onClick = {
                scope.launch {
                    saving = true
                    runCatching {
                        val created = repository.createCraft(
                            CraftCreateRequest(
                                name = name.trim(),
                                localName = localName.blankToNull(),
                                category = category.blankToNull(),
                                place = place.blankToNull(),
                                description = description.blankToNull(),
                                recordedAt = Instant.now().toString()
                            )
                        )
                        uploadAttachments(repository, context, media, "craft", created.id, name, "Field media for ${name.trim()}")
                    }.onSuccess {
                        name = ""; localName = ""; category = ""; place = ""; description = ""
                        media.reset()
                        onSaved()
                    }.onFailure { onError(it.message ?: "Unable to save craft") }
                    saving = false
                }
            },
            enabled = name.isNotBlank() && !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving..." else "Save craft")
        }
    }
}

@Composable
private fun ArtisanForm(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    onSaved: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    var name by remember { mutableStateOf("") }
    var localName by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var craftId by remember { mutableStateOf("") }
    var newCraftName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("PENDING") }
    var saving by remember { mutableStateOf(false) }
    val hasCraft = craftId.isNotBlank() || newCraftName.isNotBlank()

    RecordCard(title = "Add artisan") {
        TextInput("Name", name) { name = it }
        TextInput("Local name", localName) { localName = it }
        DropdownField(
            label = "Craft",
            options = crafts.map { it.id to it.name },
            selectedValue = craftId,
            placeholder = "Select existing craft",
            onSelect = { craftId = it }
        )
        TextInput("Or new craft name", newCraftName) { newCraftName = it }
        TextInput("Place", place) { place = it }
        TextInput("Gender", gender) { gender = it }
        TextInput("Phone", phone) { phone = it }
        TextInput("Email", email) { email = it }
        TextInput("Address", address, minLines = 2) { address = it }
        TextInput("Notes", notes, minLines = 3) { notes = it }
        StatusDropdown(value = status) { status = it }
        MediaCaptureSection(media = media, onMessage = onError, onError = onError)
        Button(
            onClick = {
                scope.launch {
                    saving = true
                    runCatching {
                        val artisan = repository.createArtisan(
                            ArtisanCreateRequest(
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
                                recordedAt = Instant.now().toString(),
                                location = media.location
                            )
                        )
                        uploadAttachments(repository, context, media, "artisan", artisan.id, name, "Field media for ${name.trim()}")
                    }.onSuccess {
                        name = ""; localName = ""; gender = ""; phone = ""; email = ""
                        place = ""; address = ""; notes = ""; craftId = ""; newCraftName = ""
                        status = "PENDING"
                        media.reset()
                        onSaved()
                    }.onFailure { onError(it.message ?: "Unable to save artisan") }
                    saving = false
                }
            },
            enabled = name.isNotBlank() && place.isNotBlank() && hasCraft && !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving..." else "Save artisan")
        }
    }
}

@Composable
private fun WorkshopForm(
    repository: FieldRepository,
    artisans: List<ArtisanDto>,
    onSaved: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    var title by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf<LocalDate?>(null) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    var status by remember { mutableStateOf("PENDING") }
    var selectedArtisans by remember { mutableStateOf<Set<String>>(emptySet()) }
    var saving by remember { mutableStateOf(false) }

    RecordCard(title = "Add workshop") {
        TextInput("Workshop title", title) { title = it }
        TextInput("Place", place) { place = it }
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
        MediaCaptureSection(media = media, onMessage = onError, onError = onError)
        Button(
            onClick = {
                scope.launch {
                    saving = true
                    runCatching {
                        val start = (startDate ?: LocalDate.now()).toIsoInstant()
                        val end = (endDate ?: startDate ?: LocalDate.now()).toIsoInstant()
                        val created = repository.createWorkshop(
                            WorkshopCreateRequest(
                                title = title.trim(),
                                date = start,
                                startDate = start,
                                endDate = end,
                                place = place.trim(),
                                description = description.blankToNull(),
                                notes = notes.blankToNull(),
                                artisanIds = selectedArtisans.toList(),
                                status = status,
                                recordedAt = Instant.now().toString(),
                                location = media.location
                            )
                        )
                        uploadAttachments(repository, context, media, "workshop", created.id, title, "Field media for ${title.trim()}")
                    }.onSuccess {
                        title = ""; place = ""; description = ""; notes = ""
                        startDate = null; endDate = null; status = "PENDING"
                        selectedArtisans = emptySet()
                        media.reset()
                        onSaved()
                    }.onFailure { onError(it.message ?: "Unable to save workshop") }
                    saving = false
                }
            },
            enabled = title.isNotBlank() && place.isNotBlank() && !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving..." else "Save workshop")
        }
    }
}

@Composable
private fun ProductForm(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    onSaved: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    var productName by remember { mutableStateOf("") }
    var localName by remember { mutableStateOf("") }
    var craftName by remember { mutableStateOf("") }
    var artisanName by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var craftId by remember { mutableStateOf("") }
    var artisanId by remember { mutableStateOf("") }
    var productType by remember { mutableStateOf("OTHER") }
    var marketDemand by remember { mutableStateOf("UNKNOWN") }
    var timeTaken by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var length by remember { mutableStateOf("") }
    var breadth by remember { mutableStateOf("") }
    var costOfMaking by remember { mutableStateOf("") }
    var sellingPrice by remember { mutableStateOf("") }
    var rawMaterials by remember { mutableStateOf("") }
    var mainTools by remember { mutableStateOf("") }
    var functionUse by remember { mutableStateOf("") }
    var remarks by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("PENDING") }
    var saving by remember { mutableStateOf(false) }

    RecordCard(title = "Add product") {
        TextInput("Product name", productName) { productName = it }
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
        }
        TextInput("Craft name", craftName) { craftName = it }
        DropdownField(
            label = "Linked artisan (fills artisan + place)",
            options = artisans.map { it.id to "${it.name} · ${it.place}" },
            selectedValue = artisanId,
            placeholder = "Unlinked / type below"
        ) { id ->
            artisanId = id
            artisans.firstOrNull { it.id == id }?.let {
                artisanName = it.name
                place = it.place
            }
        }
        TextInput("Artisan name", artisanName) { artisanName = it }
        TextInput("Place", place) { place = it }
        TextInput("Time taken to complete", timeTaken) { timeTaken = it }
        TextInput("Size", size) { size = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Length (inches)", length) { length = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Breadth (inches)", breadth) { breadth = it } }
        }
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
        MediaCaptureSection(media = media, enableMeasurement = true, onMessage = onError, onError = onError)
        Button(
            onClick = {
                scope.launch {
                    saving = true
                    runCatching {
                        val created = repository.createProduct(
                            ProductCreateRequest(
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
                                recordedAt = Instant.now().toString(),
                                location = media.location
                            )
                        )
                        uploadAttachments(repository, context, media, "product", created.id, productName, "Field media for ${productName.trim()}")
                        uploadMeasurement(repository, context, media, "product", created.id, productName)
                    }.onSuccess {
                        productName = ""; localName = ""; craftName = ""; artisanName = ""; place = ""
                        craftId = ""; artisanId = ""; productType = "OTHER"; marketDemand = "UNKNOWN"
                        timeTaken = ""; size = ""; length = ""; breadth = ""; costOfMaking = ""; sellingPrice = ""
                        rawMaterials = ""; mainTools = ""; functionUse = ""; remarks = ""; status = "PENDING"
                        media.reset()
                        onSaved()
                    }.onFailure { onError(it.message ?: "Unable to save product") }
                    saving = false
                }
            },
            enabled = productName.isNotBlank() && craftName.isNotBlank() && artisanName.isNotBlank() && place.isNotBlank() && !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving..." else "Save product")
        }
    }
}

@Composable
private fun ToolForm(
    repository: FieldRepository,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    onSaved: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    var toolkitName by remember { mutableStateOf("") }
    var localName by remember { mutableStateOf("") }
    var englishName by remember { mutableStateOf("") }
    var craftName by remember { mutableStateOf("") }
    var artisanName by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var craftId by remember { mutableStateOf("") }
    var artisanId by remember { mutableStateOf("") }
    var processUsedIn by remember { mutableStateOf("") }
    var material by remember { mutableStateOf("") }
    var yearsInUse by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("") }
    var length by remember { mutableStateOf("") }
    var breadth by remember { mutableStateOf("") }
    var thickness by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("") }
    var maker by remember { mutableStateOf("UNKNOWN") }
    var traditionType by remember { mutableStateOf("UNKNOWN") }
    var replacementCost by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf("") }
    var remarks by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("PENDING") }
    var saving by remember { mutableStateOf(false) }

    RecordCard(title = "Add tool") {
        TextInput("Toolkit name", toolkitName) { toolkitName = it }
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
        }
        TextInput("Craft name", craftName) { craftName = it }
        DropdownField(
            label = "Linked artisan (fills artisan + place)",
            options = artisans.map { it.id to "${it.name} · ${it.place}" },
            selectedValue = artisanId,
            placeholder = "Unlinked / type below"
        ) { id ->
            artisanId = id
            artisans.firstOrNull { it.id == id }?.let {
                artisanName = it.name
                place = it.place
            }
        }
        TextInput("Artisan name", artisanName) { artisanName = it }
        TextInput("Place", place) { place = it }
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
        DropdownField("Maker", makerOptions.map { it to it }, maker, includeNone = false) { maker = it }
        DropdownField("Tradition type", traditionOptions.map { it to it }, traditionType, includeNone = false) { traditionType = it }
        TextInput("Replacement cost", replacementCost) { replacementCost = it }
        TextInput("Suggestions for improvement", suggestions, minLines = 2) { suggestions = it }
        TextInput("Remarks", remarks, minLines = 3) { remarks = it }
        StatusDropdown(value = status) { status = it }
        MediaCaptureSection(media = media, enableMeasurement = true, onMessage = onError, onError = onError)
        Button(
            onClick = {
                scope.launch {
                    saving = true
                    runCatching {
                        val created = repository.createTool(
                            ToolCreateRequest(
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
                                recordedAt = Instant.now().toString(),
                                location = media.location
                            )
                        )
                        uploadAttachments(repository, context, media, "tool", created.id, toolkitName, "Field media for ${toolkitName.trim()}")
                        uploadMeasurement(repository, context, media, "tool", created.id, toolkitName)
                    }.onSuccess {
                        toolkitName = ""; localName = ""; englishName = ""; craftName = ""; artisanName = ""; place = ""
                        craftId = ""; artisanId = ""; processUsedIn = ""; material = ""; yearsInUse = ""
                        height = ""; width = ""; length = ""; breadth = ""; thickness = ""; weight = ""; radius = ""
                        maker = "UNKNOWN"; traditionType = "UNKNOWN"; replacementCost = ""; suggestions = ""; remarks = ""
                        status = "PENDING"
                        media.reset()
                        onSaved()
                    }.onFailure { onError(it.message ?: "Unable to save tool") }
                    saving = false
                }
            },
            enabled = toolkitName.isNotBlank() && craftName.isNotBlank() && artisanName.isNotBlank() && place.isNotBlank() && !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving..." else "Save tool")
        }
    }
}

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
    var linkedRecordType by remember { mutableStateOf("") }
    var linkedRecordId by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var location by remember { mutableStateOf<LocationRequest?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var savedMedia by remember { mutableStateOf<List<com.fieldrepository.app.data.MediaFileDto>>(emptyList()) }
    var localMessage by remember { mutableStateOf<String?>(null) }

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
            Text(if (recording) "Stop audio recording" else "Record audio")
        }
        OutlinedButton(
            onClick = {
                permissionLauncher.launch(requiredAndroidPermissions())
                val currentLocation = readLastKnownLocation(context)
                location = currentLocation
                localMessage = currentLocation?.let {
                    "Location tagged: ${it.latitude}, ${it.longitude}"
                } ?: "No current GPS fix available yet; try again after location warms up."
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Use current GPS")
        }
        TextInput("Media title / object name", mediaTitle) { mediaTitle = it }
        TextInput("Linked record type", linkedRecordType) { linkedRecordType = it }
        TextInput("Linked record ID", linkedRecordId) { linkedRecordId = it }
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
        Button(
            onClick = {
                scope.launch {
                    uploading = true
                    runCatching {
                        selectedUris.forEachIndexed { index, uri ->
                            repository.uploadMedia(
                                context = context,
                                uri = uri,
                                linkedRecordType = linkedRecordType,
                                linkedRecordId = linkedRecordId,
                                caption = caption,
                                location = location,
                                titleHint = mediaTitle.ifBlank { caption },
                                batchIndex = index + 1
                            )
                        }
                    }.onSuccess {
                        val count = selectedUris.size
                        selectedUris = emptyList()
                        mediaTitle = ""
                        caption = ""
                        localMessage = null
                        refreshMedia()
                        onUploaded(count)
                    }.onFailure { error ->
                        onError(error.message ?: "Unable to upload media batch")
                    }
                    uploading = false
                }
            },
            enabled = selectedUris.isNotEmpty() && !uploading && !recording,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (uploading) "Uploading..." else "Upload batch")
        }
        if (savedMedia.isNotEmpty()) {
            Text("Recent saved media", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            savedMedia.take(10).forEach { media ->
                AndroidSavedMediaPreview(context = context, media = media)
            }
        }
    }
}

@Composable
private fun AndroidUriPreview(context: Context, uri: Uri) {
    val mimeType = remember(uri) { context.contentResolver.getType(uri) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = Color(0xFF181715), shape = RoundedCornerShape(10.dp))
            .clickable { openUri(context, uri, mimeType) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (mimeType?.startsWith("image/") == true) {
            AsyncImage(
                model = uri,
                contentDescription = uri.lastPathSegment,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).background(SurfaceCard, RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                modifier = Modifier.size(64.dp).background(SurfaceCard, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text((mimeType ?: "file").substringBefore('/').uppercase().take(5), color = Body, fontSize = 11.sp)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(uri.lastPathSegment.orEmpty(), color = Canvas, fontSize = 12.sp)
            Text(mimeType ?: "Unknown file type", color = SurfaceCard, fontSize = 11.sp)
        }
        Text("Open", color = SurfaceCard, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
private fun AndroidSavedMediaPreview(context: Context, media: com.fieldrepository.app.data.MediaFileDto) {
    val uri = remember(media.url) { media.url?.let(Uri::parse) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = SurfaceCard, shape = RoundedCornerShape(10.dp))
            .clickable(enabled = uri != null) {
                if (uri != null) openUri(context, uri, media.mimeType)
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (media.mediaType == "IMAGE" && uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = media.caption ?: media.originalFilename,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).background(Canvas, RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                modifier = Modifier.size(64.dp).background(Canvas, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(media.mediaType.take(5), color = Body, fontSize = 11.sp)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(media.originalFilename, color = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(media.mimeType ?: media.mediaType, color = Muted, fontSize = 11.sp)
            media.transcriptStatus?.let { Text("Transcript: $it", color = Muted, fontSize = 11.sp) }
        }
        if (uri != null) Text("Open", color = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
private fun QuestionnaireForm(
    repository: FieldRepository,
    sections: List<QuestionnaireSectionDto>,
    artisans: List<ArtisanDto>,
    canManageQuestionnaire: Boolean,
    onRefreshSections: suspend () -> Unit,
    onSubmit: suspend (QuestionnaireInterviewCreateRequest) -> String,
    onError: (String) -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var selectedArtisans by remember { mutableStateOf<Set<String>>(emptySet()) }
    var place by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var capturedLocation by remember { mutableStateOf<LocationRequest?>(null) }
    val questions = remember(sections) { sections.flatMap { it.questions }.filter { it.isActive } }
    val answers = remember(questions) { questions.associate { it.id to mutableStateOf("") } }
    var recordingQuestionId by remember { mutableStateOf<String?>(null) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var questionAudio by remember { mutableStateOf<Map<String, List<Uri>>>(emptyMap()) }
    var expandedSections by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBuilder by remember { mutableStateOf(false) }

    if (canManageQuestionnaire) {
        // Defer the heavy builder so opening the questionnaire tab never composes hundreds of rows at once.
        OutlinedButton(onClick = { showBuilder = !showBuilder }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showBuilder) "Hide questionnaire builder" else "Open questionnaire builder")
        }
        if (showBuilder) {
            QuestionnaireBuilder(repository, sections, onRefreshSections, onError)
        }
    }

    RecordCard(title = "Add questionnaire interview") {
        TextInput("Interview title", title) { title = it }
        ArtisanMultiSelectField(
            label = "Linked artisans",
            artisans = artisans,
            selectedIds = selectedArtisans
        ) { id ->
            selectedArtisans = if (selectedArtisans.contains(id)) selectedArtisans - id else selectedArtisans + id
        }
        TextInput("Place", place) { place = it }
        TextInput("Language", language) { language = it }
        OutlinedButton(
            onClick = {
                val loc = readLastKnownLocation(context)
                capturedLocation = loc
                onError(
                    loc?.let { "Location tagged: ${"%.5f".format(it.latitude)}, ${"%.5f".format(it.longitude)}" }
                        ?: "No GPS fix yet; try again after location warms up."
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (capturedLocation != null) "GPS tagged ✓ (tap to refresh)" else "Use current GPS")
        }
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
                            activeQuestions.forEach { question ->
                                Text("${question.sortOrder}. ${question.prompt}", color = Muted, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = {
                                            if (recordingQuestionId == question.id) {
                                                runCatching {
                                                    recorder?.stop()
                                                    recorder?.release()
                                                    recordingFile?.let { file ->
                                                        questionAudio = questionAudio + (question.id to ((questionAudio[question.id] ?: emptyList()) + uriForFile(context, file)))
                                                    }
                                                }.onFailure { onError(it.message ?: "Unable to stop question audio") }
                                                recorder = null
                                                recordingFile = null
                                                recordingQuestionId = null
                                            } else {
                                                runCatching {
                                                    val file = createAppFile(context, "question-audio-", ".m4a")
                                                    recorder = createAudioRecorder(context, file).also { it.start() }
                                                    recordingFile = file
                                                    recordingQuestionId = question.id
                                                }.onFailure { onError(it.message ?: "Unable to start question audio") }
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (recordingQuestionId == question.id) "Stop" else "Record")
                                    }
                                    Text(
                                        "${questionAudio[question.id]?.size ?: 0} clip(s)",
                                        color = Muted,
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    )
                                }
                                TextInput("Answer", answers[question.id]?.value.orEmpty(), minLines = 3) { value ->
                                    answers[question.id]?.let { state -> state.value = value }
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
                scope.launch {
                    val now = Instant.now().toString()
                    runCatching {
                        val interviewId = onSubmit(
                            QuestionnaireInterviewCreateRequest(
                                title = title.trim(),
                                place = place.blankToNull(),
                                language = language.blankToNull(),
                                notes = notes.blankToNull(),
                                artisanIds = selectedArtisans.toList(),
                                location = capturedLocation,
                                responses = questions.mapNotNull { question ->
                                    val answer = answers[question.id]?.value?.trim().orEmpty()
                                    if (answer.isBlank()) null else QuestionnaireResponseRequest(questionId = question.id, answerText = answer)
                                },
                                recordedAt = now
                            )
                        )
                        questions.forEach { question ->
                            questionAudio[question.id].orEmpty().forEachIndexed { index, uri ->
                                repository.uploadMedia(
                                    context = context,
                                    uri = uri,
                                    linkedRecordType = "questionnaire",
                                    linkedRecordId = interviewId,
                                    caption = "Question audio: ${question.sectionCode}${question.sortOrder} ${question.prompt}",
                                    location = null,
                                    titleHint = title.ifBlank { question.prompt },
                                    batchIndex = index + 1
                                )
                            }
                        }
                    }.onFailure {
                        onError(it.message ?: "Unable to save questionnaire")
                        return@launch
                    }
                    title = ""
                    selectedArtisans = emptySet()
                    place = ""
                    language = ""
                    notes = ""
                    capturedLocation = null
                    answers.values.forEach { it.value = "" }
                    questionAudio = emptyMap()
                    onSaved()
                }
            },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save questionnaire")
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

    RecordCard(title = "Questionnaire builder") {
        Text("Master admin controls for adding, editing, removing, moving sections, and moving questions between sections.", color = Muted, fontSize = 12.sp)
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
            var code by remember(section.id, section.code) { mutableStateOf(section.code) }
            var sectionTitle by remember(section.id, section.title) { mutableStateOf(section.title) }
            var newPrompt by remember(section.id) { mutableStateOf("") }
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${section.sortOrder}. ${section.code} - ${section.title}", fontWeight = FontWeight.SemiBold)
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
                    val included = appUser.role == "MASTER_ADMIN" || appUser.canManageQuestionnaire
                    Text(
                        if (included) "Can manage questionnaire" else "Cannot manage questionnaire",
                        color = Body,
                        fontSize = 12.sp
                    )
                    OutlinedButton(
                        enabled = isMasterAdmin && appUser.role != "MASTER_ADMIN",
                        onClick = {
                            scope.launch {
                                runCatching {
                                    repository.updateUserQuestionnaireAccess(appUser.id, !appUser.canManageQuestionnaire)
                                    refreshUsers()
                                }.onFailure { onError(it.message ?: "Unable to update questionnaire access") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (included) "Revoke questionnaire access" else "Grant questionnaire access")
                    }
                }
            }
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
