package com.fieldrepository.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fieldrepository.app.data.ConnectivityObserver
import com.fieldrepository.app.ui.richtext.RichTextEditor
import com.fieldrepository.app.ui.richtext.fromJson
import com.fieldrepository.app.ui.richtext.toJson
import kotlinx.serialization.json.JsonElement

private var lastRecordDictationTag: String = RECORD_DICTATION_LANGUAGES.first().tag

private enum class RecordDictationState {
    IDLE,

    LISTENING,

    WORKING,
}

@Composable
internal fun rememberRecordDictationAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { RecordSpeechServices.available(context) }
}

private object RecordSpeechServices {
    @Volatile private var cached: Boolean? = null

    fun available(context: Context): Boolean = cached ?: run {
        val answer = networkEngine(context) || onDeviceEngine(context)
        cached = answer
        answer
    }

    fun networkEngine(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    fun onDeviceEngine(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
}

private object RecordEngineRefusals {
    private val refused = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun record(tag: String) {
        refused.add(tag)
    }

    fun has(tag: String): Boolean = refused.contains(tag)
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
internal fun RecordDictationButton(
    enabled: Boolean,
    onPartial: (String) -> Unit,
    onCommit: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(RecordDictationState.IDLE) }
    var showLanguages by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf(lastRecordDictationTag) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    var plan by remember { mutableStateOf<List<RecordDictationRung>>(emptyList()) }

    val currentPartial by rememberUpdatedState(onPartial)
    val currentCommit by rememberUpdatedState(onCommit)
    val currentError by rememberUpdatedState(onError)

    fun conditionsNow() = RecordDictationConditions(
        languageLabel = recordDictationLabel(tag),
        onDeviceEngine = RecordSpeechServices.onDeviceEngine(context),
        networkRecogniser = RecordSpeechServices.networkEngine(context),
        online = ConnectivityObserver.isOnline(context),
        deviceRefusedLanguage = RecordEngineRefusals.has(tag),
    )

    fun release() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        state = RecordDictationState.IDLE
    }

    var beginAt: (RecordDictationRung) -> Unit = {}

    fun advance(from: RecordDictationRung) {
        val index = plan.indexOf(from)
        val next = if (index < 0) null else plan.getOrNull(index + 1)
        if (next != null) {
            beginAt(next)
            return
        }
        val sentence = recordDictationNothingLeftSentence(conditionsNow())
        release()
        currentError(sentence)
    }

    fun buildRecognizer(
        onDevice: Boolean,
        onNetworkFailure: () -> Unit,
        onLanguageUnavailable: () -> Unit,
    ): SpeechRecognizer? {
        val created = runCatching {
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrNull() ?: return null

        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { state = RecordDictationState.LISTENING }
            override fun onBeginningOfSpeech() { state = RecordDictationState.LISTENING }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { state = RecordDictationState.WORKING }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) currentPartial(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                currentPartial("")
                if (text.isNotBlank()) currentCommit(text)
                release()
            }

            override fun onError(error: Int) {
                currentPartial("")
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "Nothing recognised. Check the language and speak closer."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No speech heard. Tap the mic and start speaking right away."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Microphone access is off. Allow it in Settings, then try again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        onNetworkFailure()
                        return
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "Speech service busy. Try again in a moment."
                    SpeechRecognizer.ERROR_AUDIO ->
                        "Can't use the microphone — another app may have it."
                    13, 12 -> {
                        onLanguageUnavailable()
                        return
                    }
                    else ->
                        "Dictation stopped (code $error). Try again or type."
                }
                release()
                currentError(message)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        return created
    }

    fun intentFor(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    fun listen(onDevice: Boolean) {
        val here =
            if (onDevice) RecordDictationRung.ON_DEVICE_PACK else RecordDictationRung.NETWORK_RECOGNISER
        release()
        val built = buildRecognizer(
            onDevice,
            onLanguageUnavailable = {
                if (onDevice) RecordEngineRefusals.record(tag)
                advance(here)
            },
            onNetworkFailure = { advance(here) },
        )
        if (built == null) {
            release()
            currentError("Speech recognition didn't start.")
            return
        }
        recognizer = built
        state = RecordDictationState.WORKING
        runCatching { built.startListening(intentFor()) }.onFailure {
            release()
            currentError("Speech recognition didn't start.")
        }
    }

    beginAt = { next ->
        when (next) {
            RecordDictationRung.ON_DEVICE_PACK -> listen(onDevice = true)
            RecordDictationRung.NETWORK_RECOGNISER -> listen(onDevice = false)
        }
    }

    fun beginWalk() {
        val conditions = conditionsNow()
        val rungs = recordDictationRungs(conditions)
        plan = rungs
        val first = rungs.firstOrNull()
        if (first == null) {
            currentError(recordDictationNothingLeftSentence(conditions))
            return
        }
        beginAt(first)
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginWalk()
        } else {
            currentError("Dictation needs the microphone.")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            enabled = enabled,
            onClick = {
                when (state) {
                    RecordDictationState.LISTENING, RecordDictationState.WORKING ->
                        runCatching { recognizer?.stopListening() }
                    RecordDictationState.IDLE -> {
                        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
                            beginWalk()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            }
        ) {
            Icon(
                if (state == RecordDictationState.IDLE) Icons.Filled.Mic else Icons.Filled.Stop,
                contentDescription = if (state == RecordDictationState.IDLE) {
                    "Dictate this answer in ${recordDictationLabel(tag)}"
                } else {
                    "Stop dictating"
                },
                tint = if (state == RecordDictationState.IDLE) {
                    MaterialTheme.field.muted
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(20.dp)
            )
        }
        if (state == RecordDictationState.IDLE) {
            TextButton(
                onClick = { showLanguages = !showLanguages },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(
                    recordDictationLabel(tag),
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }

    if (showLanguages) {
        AlertDialog(
            onDismissRequest = { showLanguages = false },
            title = { Text("Dictation language") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SearchableSelectField(
                        label = "Speak this answer in",
                        options = remember {
                            RECORD_DICTATION_LANGUAGES.map { SelectOption(it.tag, it.label) }
                        },
                        selectedValue = tag,
                        includeNone = false,
                        onSelect = { chosen ->
                            if (chosen.isNotBlank()) {
                                tag = chosen
                                lastRecordDictationTag = chosen
                            }
                            showLanguages = false
                        }
                    )
                    Text(
                        "Offline languages depend on this phone's installed packs.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguages = false }) { Text("Close") }
            },
        )
    }
}

@Composable
fun RecordProseField(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    enabled: Boolean = true,
    rich: Boolean = false,
    dictate: Boolean = false,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    resetKey: Any? = null,
    help: String? = null,
    errorText: String? = null,
    focusRequester: FocusRequester? = null,
    capitalization: KeyboardCapitalization? = null,
    below: @Composable () -> Unit = {},
) {
    var spoken by remember(resetKey) { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val singleLine = !rich && minLines <= 1
    val caps = capitalization ?: when (keyboardType) {
        KeyboardType.Email, KeyboardType.Uri, KeyboardType.Password, KeyboardType.NumberPassword,
        KeyboardType.Number, KeyboardType.Decimal, KeyboardType.Phone -> KeyboardCapitalization.None
        else -> KeyboardCapitalization.Sentences
    }

    var dictationError by remember(resetKey) { mutableStateOf<String?>(null) }

    val available = rememberRecordDictationAvailable()
    val showMic = dictate && available

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (rich) {
            var seed by remember(resetKey) { mutableStateOf(richSeedOf(value)) }
            var mine by remember(resetKey) { mutableStateOf(value) }
            LaunchedEffect(value, resetKey) {
                if (value != mine) {
                    seed = richSeedOf(value)
                    mine = value
                }
            }
            RichTextEditor(
                value = seed,
                onChange = { next ->
                    val stored = recordStoredFromDoc(fromJson(next)).orEmpty()
                    mine = stored
                    onValueChange(stored)
                },
                enabled = enabled,
                label = label.orEmpty(),
                help = help,
                dictate = dictate,
                onError = { message -> dictationError = message },
            )
        } else {
            OutlinedTextField(
                value = if (spoken.isBlank()) value else appendSpokenToRecord(value, spoken),
                onValueChange = { raw -> if (spoken.isBlank()) onValueChange(raw) },
                label = label?.let { { Text(it) } },
                placeholder = placeholder?.let {
                    { Text(it, color = MaterialTheme.field.placeholder) }
                },
                enabled = enabled,
                readOnly = spoken.isNotBlank(),
                isError = errorText != null,
                supportingText = errorText?.let { message -> { Text(message) } },
                minLines = minLines,
                singleLine = singleLine,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    capitalization = caps,
                    imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                trailingIcon = if (!showMic) null else {
                    {
                        RecordDictationButton(
                            enabled = enabled,
                            onPartial = { partial -> spoken = partial },
                            onCommit = { finalText ->
                                val merged = appendSpokenToRecord(value, finalText)
                                spoken = ""
                                dictationError = null
                                onValueChange(merged)
                            },
                            onError = { message ->
                                spoken = ""
                                dictationError = message
                            },
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .let { base -> focusRequester?.let { base.focusRequester(it) } ?: base }
            )
            help?.let { Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall) }
        }

        if (spoken.isNotBlank()) {
            Text("Listening — speak now.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        dictationError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (dictate && !available) {
            Text(RECORD_DICTATION_UNAVAILABLE, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        below()
    }
}

private fun richSeedOf(stored: String): JsonElement? {
    val doc = recordDocFromStored(stored)
    return if (doc.blocks.isEmpty()) null else toJson(doc)
}
