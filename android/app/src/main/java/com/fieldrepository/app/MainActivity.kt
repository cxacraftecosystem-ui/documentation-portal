package com.fieldrepository.app

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
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
import com.fieldrepository.app.data.QuestionnaireQuestionDto
import com.fieldrepository.app.data.QuestionnaireResponseRequest
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
import java.io.File
import java.time.Instant

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

private enum class EntryMode(val label: String) {
    CRAFT("Craft"),
    ARTISAN("Artisan"),
    WORKSHOP("Workshop"),
    PRODUCT("Product"),
    TOOL("Tool"),
    MEDIA("Media"),
    QUESTIONNAIRE("Questionnaire")
}

@Composable
private fun RepositoryApp(repository: FieldRepository, googleAuthClient: GoogleAuthClient) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<UserDto?>(null) }
    var loading by remember { mutableStateOf(repository.hasToken()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (repository.hasToken()) {
            runCatching { repository.currentUser() }
                .onSuccess { user = it }
                .onFailure {
                    repository.logout()
                    error = it.message
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
                onLogin = { email, password ->
                    loading = true
                    error = null
                    runCatching { repository.login(email, password) }
                        .onSuccess { user = it }
                        .onFailure { error = it.message ?: "Login failed" }
                    loading = false
                },
                onGoogleLogin = {
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
    onLogin: suspend (String, String) -> Unit,
    onGoogleLogin: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("ankits1802@gmail.com") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

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
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            onLogin(email, password)
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Signing in..." else "Login")
                }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            onGoogleLogin()
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Please wait..." else "Sign in with Google")
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
    var questions by remember { mutableStateOf<List<QuestionnaireQuestionDto>>(emptyList()) }
    var mode by remember { mutableStateOf(EntryMode.ARTISAN) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            runCatching { repository.stats() }
                .onSuccess { stats = it }
                .onFailure { message = it.message }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        runCatching { repository.questionnaireQuestions() }
            .onSuccess { questions = it }
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
                Text("Field Repository", fontFamily = FontFamily.Serif, fontSize = 30.sp, color = MaterialTheme.colorScheme.onBackground)
                Text("${user.name} - ${user.role}", color = Muted, fontSize = 13.sp)
            }
            TextButton(onClick = onLogout) { Text("Logout") }
        }

        StatsCard(stats)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            EntryMode.entries.forEach { entryMode ->
                FilterChip(
                    selected = mode == entryMode,
                    onClick = { mode = entryMode },
                    label = { Text(entryMode.label) }
                )
            }
        }

        when (mode) {
            EntryMode.CRAFT -> CraftForm(
                onSubmit = { body ->
                    runCatching { repository.createCraft(body) }
                        .onSuccess {
                            message = "Craft saved"
                            refresh()
                        }
                        .onFailure { message = it.message ?: "Unable to save craft" }
                }
            )
            EntryMode.ARTISAN -> ArtisanForm(
                onSubmit = { body ->
                    runCatching { repository.createArtisan(body) }
                        .onSuccess {
                            message = "Artisan saved"
                            refresh()
                        }
                        .onFailure { message = it.message ?: "Unable to save artisan" }
                }
            )
            EntryMode.WORKSHOP -> WorkshopForm(
                onSubmit = { body ->
                    runCatching { repository.createWorkshop(body) }
                        .onSuccess {
                            message = "Workshop saved"
                            refresh()
                        }
                        .onFailure { message = it.message ?: "Unable to save workshop" }
                }
            )
            EntryMode.PRODUCT -> ProductForm(
                onSubmit = { body ->
                    runCatching { repository.createProduct(body) }
                        .onSuccess {
                            message = "Product saved"
                            refresh()
                        }
                        .onFailure { message = it.message ?: "Unable to save product" }
                }
            )
            EntryMode.TOOL -> ToolForm(
                onSubmit = { body ->
                    runCatching { repository.createTool(body) }
                        .onSuccess {
                            message = "Tool saved"
                            refresh()
                        }
                        .onFailure { message = it.message ?: "Unable to save tool" }
                }
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
                questions = questions,
                onSubmit = { body ->
                    runCatching { repository.createQuestionnaireInterview(body) }
                        .onSuccess {
                            message = "Questionnaire interview saved"
                            refresh()
                        }
                        .onFailure { message = it.message ?: "Unable to save questionnaire" }
                }
            )
        }

        message?.let {
            Text(it, color = Body, modifier = Modifier.padding(bottom = 24.dp))
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

@Composable
private fun CraftForm(onSubmit: suspend (CraftCreateRequest) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    RecordCard(title = "Add craft") {
        TextInput("Craft name", name) { name = it }
        TextInput("Category", category) { category = it }
        TextInput("Place", place) { place = it }
        TextInput("Description", description, minLines = 3) { description = it }
        Button(
            onClick = {
                scope.launch {
                    val now = Instant.now().toString()
                    onSubmit(
                        CraftCreateRequest(
                            name = name.trim(),
                            category = category.blankToNull(),
                            place = place.blankToNull(),
                            description = description.blankToNull(),
                            recordedAt = now
                        )
                    )
                    name = ""
                    category = ""
                    place = ""
                    description = ""
                }
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save craft")
        }
    }
}

@Composable
private fun ArtisanForm(onSubmit: suspend (ArtisanCreateRequest) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var craftName by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    RecordCard(title = "Add artisan") {
        TextInput("Name", name) { name = it }
        TextInput("Craft", craftName) { craftName = it }
        TextInput("Place", place) { place = it }
        TextInput("Phone", phone) { phone = it }
        TextInput("Notes", notes, minLines = 3) { notes = it }
        Button(
            onClick = {
                scope.launch {
                    val now = Instant.now().toString()
                    onSubmit(
                        ArtisanCreateRequest(
                            name = name.trim(),
                            place = place.trim(),
                            craftName = craftName.trim(),
                            phone = phone.blankToNull(),
                            notes = notes.blankToNull(),
                            recordedAt = now
                        )
                    )
                    name = ""
                    craftName = ""
                    place = ""
                    phone = ""
                    notes = ""
                }
            },
            enabled = name.isNotBlank() && craftName.isNotBlank() && place.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save artisan")
        }
    }
}

@Composable
private fun WorkshopForm(onSubmit: suspend (WorkshopCreateRequest) -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    RecordCard(title = "Add workshop") {
        TextInput("Workshop title", title) { title = it }
        TextInput("Place", place) { place = it }
        TextInput("Description", description, minLines = 3) { description = it }
        TextInput("Notes", notes, minLines = 3) { notes = it }
        Button(
            onClick = {
                scope.launch {
                    val now = Instant.now().toString()
                    onSubmit(
                        WorkshopCreateRequest(
                            title = title.trim(),
                            date = now,
                            startDate = now,
                            endDate = now,
                            place = place.trim(),
                            description = description.blankToNull(),
                            notes = notes.blankToNull(),
                            recordedAt = now
                        )
                    )
                    title = ""
                    place = ""
                    description = ""
                    notes = ""
                }
            },
            enabled = title.isNotBlank() && place.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save workshop")
        }
    }
}

@Composable
private fun ProductForm(onSubmit: suspend (ProductCreateRequest) -> Unit) {
    val scope = rememberCoroutineScope()
    var productName by remember { mutableStateOf("") }
    var craftName by remember { mutableStateOf("") }
    var artisanName by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var remarks by remember { mutableStateOf("") }
    var length by remember { mutableStateOf("") }
    var breadth by remember { mutableStateOf("") }

    RecordCard(title = "Add product") {
        TextInput("Product name", productName) { productName = it }
        TextInput("Craft name", craftName) { craftName = it }
        TextInput("Artisan name", artisanName) { artisanName = it }
        TextInput("Place", place) { place = it }
        TextInput("Length inches", length) { length = it }
        TextInput("Breadth inches", breadth) { breadth = it }
        TextInput("Remarks", remarks, minLines = 3) { remarks = it }
        Button(
            onClick = {
                scope.launch {
                    val now = Instant.now().toString()
                    onSubmit(
                        ProductCreateRequest(
                            productName = productName.trim(),
                            craftName = craftName.trim(),
                            artisanName = artisanName.trim(),
                            place = place.trim(),
                            lengthInches = length.toDoubleOrNull(),
                            breadthInches = breadth.toDoubleOrNull(),
                            remarks = remarks.blankToNull(),
                            recordedAt = now
                        )
                    )
                    productName = ""
                    craftName = ""
                    artisanName = ""
                    place = ""
                    length = ""
                    breadth = ""
                    remarks = ""
                }
            },
            enabled = productName.isNotBlank() && craftName.isNotBlank() && artisanName.isNotBlank() && place.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save product")
        }
    }
}

@Composable
private fun ToolForm(onSubmit: suspend (ToolCreateRequest) -> Unit) {
    val scope = rememberCoroutineScope()
    var toolkitName by remember { mutableStateOf("") }
    var craftName by remember { mutableStateOf("") }
    var artisanName by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var material by remember { mutableStateOf("") }
    var remarks by remember { mutableStateOf("") }
    var length by remember { mutableStateOf("") }
    var breadth by remember { mutableStateOf("") }

    RecordCard(title = "Add tool") {
        TextInput("Toolkit name", toolkitName) { toolkitName = it }
        TextInput("Craft name", craftName) { craftName = it }
        TextInput("Artisan name", artisanName) { artisanName = it }
        TextInput("Place", place) { place = it }
        TextInput("Material", material) { material = it }
        TextInput("Length inches", length) { length = it }
        TextInput("Breadth inches", breadth) { breadth = it }
        TextInput("Remarks", remarks, minLines = 3) { remarks = it }
        Button(
            onClick = {
                scope.launch {
                    val now = Instant.now().toString()
                    onSubmit(
                        ToolCreateRequest(
                            toolkitName = toolkitName.trim(),
                            craftName = craftName.trim(),
                            artisanName = artisanName.trim(),
                            place = place.trim(),
                            material = material.blankToNull(),
                            lengthInches = length.toDoubleOrNull(),
                            breadthInches = breadth.toDoubleOrNull(),
                            remarks = remarks.blankToNull(),
                            recordedAt = now
                        )
                    )
                    toolkitName = ""
                    craftName = ""
                    artisanName = ""
                    place = ""
                    material = ""
                    length = ""
                    breadth = ""
                    remarks = ""
                }
            },
            enabled = toolkitName.isNotBlank() && craftName.isNotBlank() && artisanName.isNotBlank() && place.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save tool")
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
    var linkedRecordType by remember { mutableStateOf("") }
    var linkedRecordId by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var location by remember { mutableStateOf<LocationRequest?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var localMessage by remember { mutableStateOf<String?>(null) }

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
                selectedUris.take(6).forEach { uri ->
                    Text(uri.lastPathSegment.orEmpty(), color = SurfaceCard, fontSize = 12.sp)
                }
                if (selectedUris.size > 6) {
                    Text("+${selectedUris.size - 6} more", color = SurfaceCard, fontSize = 12.sp)
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
                        for (uri in selectedUris) {
                            repository.uploadMedia(
                                context = context,
                                uri = uri,
                                linkedRecordType = linkedRecordType,
                                linkedRecordId = linkedRecordId,
                                caption = caption,
                                location = location
                            )
                        }
                    }.onSuccess {
                        val count = selectedUris.size
                        selectedUris = emptyList()
                        caption = ""
                        localMessage = null
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
    }
}

@Composable
private fun QuestionnaireForm(
    questions: List<QuestionnaireQuestionDto>,
    onSubmit: suspend (QuestionnaireInterviewCreateRequest) -> Unit
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var artisanIds by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val selectedQuestions = remember(questions) { questions.filter { it.sectionCode != "RESP" }.take(8) }
    val answers = remember(selectedQuestions) {
        selectedQuestions.associate { it.id to mutableStateOf("") }
    }

    RecordCard(title = "Add questionnaire interview") {
        TextInput("Interview title", title) { title = it }
        TextInput("Artisan IDs (comma-separated)", artisanIds) { artisanIds = it }
        TextInput("Place", place) { place = it }
        TextInput("Language", language) { language = it }
        selectedQuestions.forEach { question ->
            Text(
                "${question.sectionCode}${question.sortOrder}. ${question.prompt}",
                color = Muted,
                fontSize = 12.sp
            )
            TextInput("Answer", answers[question.id]?.value.orEmpty(), minLines = 3) { value ->
                answers[question.id]?.let { state -> state.value = value }
            }
        }
        TextInput("Notes", notes, minLines = 3) { notes = it }
        Button(
            onClick = {
                scope.launch {
                    val now = Instant.now().toString()
                    onSubmit(
                        QuestionnaireInterviewCreateRequest(
                            title = title.trim(),
                            place = place.blankToNull(),
                            language = language.blankToNull(),
                            notes = notes.blankToNull(),
                            artisanIds = artisanIds.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                            responses = selectedQuestions.mapNotNull { question ->
                                val answer = answers[question.id]?.value?.trim().orEmpty()
                                if (answer.isBlank()) null else QuestionnaireResponseRequest(questionId = question.id, answerText = answer)
                            },
                            recordedAt = now
                        )
                    )
                    title = ""
                    artisanIds = ""
                    place = ""
                    language = ""
                    notes = ""
                    answers.values.forEach { it.value = "" }
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

private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }

private object ColorCompat {
    val darkElevated = androidx.compose.ui.graphics.Color(0xFF252320)
}
