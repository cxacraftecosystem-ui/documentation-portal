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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fieldrepository.app.data.ConnectivityObserver
import com.fieldrepository.app.ui.richtext.RichTextEditor
import com.fieldrepository.app.ui.richtext.fromJson
import com.fieldrepository.app.ui.richtext.toJson
import kotlinx.serialization.json.JsonElement

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *  A MICROPHONE AND A FORMATTING TOOLBAR, ON THE RECORD FORMS.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The requirement and the three refinements it arrived with are written out at the top of
 * `RecordProseText.kt`, next door, along with the argument for why nothing here may upload a clip.
 * Read that file first; this one draws what it decides.
 *
 * ── WHY THIS IS ONE COMPOSABLE AND NOT AN EDIT IN TWENTY SCREENS ──────────────────────────────
 *
 * Every record form in this app builds its boxes from one private `TextInput` in `MainActivity`.
 * Putting the microphone and the editor HERE, behind two opt-in flags, and letting `TextInput`
 * forward to it means a form gains both controls by changing one argument at one call site — and it
 * means the twenty-odd screens cannot drift apart in what dictation does. `MainActivity.kt` is
 * thirteen thousand lines; a feature spread across twenty of its call sites is a feature nobody can
 * read whole, and this one has a rule ("never upload") that has to be checkable by reading.
 *
 * ── THE ONE THING THIS FILE MUST NEVER DO ─────────────────────────────────────────────────────
 *
 * **Send a clip anywhere.** There is no repository in this file, no retrofit interface, no `okhttp`,
 * no upload, no `MediaRecorder` writing a file that outlives a call, and no import that could
 * acquire one. `POST /media/transcribe` exists in this backend and is open to any signed-in
 * researcher — see `RecordProseText.kt` for why that door is walked past rather than through. Grep
 * this file for `Api` and find nothing; that is the guarantee, and it is made of absence.
 */

/**
 * The language last chosen on a record form, for the life of the process.
 *
 * Process-scoped rather than persisted, and the two halves of that are separate decisions:
 *
 *  * REMEMBERED AT ALL, because a cluster works in one language for a week and an artisan form draws
 *    upwards of thirty boxes. Re-picking Odia per field would make the control slower than typing,
 *    which is the only way a dictation feature can be worse than no dictation feature.
 *  * NOT PERSISTED, because it is a preference of the sitting rather than of the account. These
 *    handsets are shared, and one handed to a colleague for an Odia session must not go on silently
 *    answering in Hindi tomorrow. The browser's copy of this feature keeps it in `localStorage`,
 *    which is the same trade made the other way for a device that is one person's.
 */
private var lastRecordDictationTag: String = RECORD_DICTATION_LANGUAGES.first().tag

/** What a record form's microphone is doing. Three states, because it has two rungs and no upload. */
private enum class RecordDictationState {
    IDLE,

    /** The recogniser is listening and streaming partial results. */
    LISTENING,

    /** Speech has ended and the engine is settling on the final text. */
    WORKING,
}

/**
 * Whether this handset can dictate at all — the question that decides whether a microphone is drawn.
 *
 * A control that looks like every other working control and does nothing teaches its owner that the
 * app is broken, and they stop pressing the ones that work. So the button is not drawn where nothing
 * can answer, and the box says why instead (see [RECORD_DICTATION_UNAVAILABLE]).
 *
 * BOTH HALVES ARE ASKED, and the second is not redundant on the handsets that matter:
 * `isRecognitionAvailable` answers for a bound recogniser service, which some builds without Google
 * speech services lack while still shipping the API 33 on-device engine. Asking only the first would
 * withhold the microphone from a phone that can dictate offline — which is exactly the phone in the
 * courtyard with no signal.
 */
@Composable
internal fun rememberRecordDictationAvailable(): Boolean {
    val context = LocalContext.current
    // Remembered per context: neither answer can change while a form is on screen, because changing
    // one means installing or removing an app.
    return remember(context) { RecordSpeechServices.available(context) }
}

/**
 * The platform questions, asked once per process.
 *
 * ── WHY THIS IS CACHED ────────────────────────────────────────────────────────────────────────
 *
 * `MainActivity`'s `TextInput` forwards every record-form box through [RecordProseField], and an
 * artisan form draws upwards of thirty of them in one pass. Both calls below reach the package
 * manager, and thirty package queries during the first composition of a form is a stutter on exactly
 * the budget handsets this app is built for — while the answers cannot change without an app being
 * installed or removed, which cannot happen while this process is in the foreground.
 *
 * `runCatching` on both, because `SpeechRecognizer`'s static queries are IPC into a service that a
 * cut-down OEM build may not have at all, and a form that crashes over a missing speech service is
 * a form nobody can fill in.
 */
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

/**
 * The languages this handset's OFFLINE engine has refused, for the life of the process.
 *
 * A MEASUREMENT OF THIS PHONE, worth more than any catalogue, and worth keeping: an artisan form has
 * thirty boxes and paying the same refusal thirty times is thirty pauses for the same answer. Only
 * the offline engine's refusals are written here — a "no" from a network speech service says nothing
 * about the packs on the phone, and recording it under the same key would retire a rung that works
 * the next time there is signal.
 *
 * Process-scoped, because a language pack downloaded this afternoon must be found by tomorrow's
 * launch without anybody clearing data.
 */
private object RecordEngineRefusals {
    private val refused = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun record(tag: String) {
        refused.add(tag)
    }

    fun has(tag: String): Boolean = refused.contains(tag)
}

/** Whether a runtime permission is already granted. Local, so nothing here depends on a screen. */
private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * The microphone for one record-form field. **On-device rungs only.**
 *
 * [onPartial] receives the recogniser's running guess so the host can render it INSIDE the box the
 * researcher is watching; [onCommit] receives the final text; [onError] receives a sentence. Drawing
 * the partial anywhere other than the box it will be saved from is what makes dictation feel
 * untrustworthy — the researcher cannot tell whether the words they can see are the words that will
 * be kept.
 *
 * `internal` rather than private because the rich-text editor hosts it too: one microphone, one
 * ladder and one set of sentences on both kinds of box. The sibling repository has two, and its own
 * comments record the consequence — a box that draws a mic under a line saying there is no dictation.
 */
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

    /**
     * The rungs THIS tap is walking, computed once and then walked.
     *
     * Computed once rather than re-derived after each failure: re-deriving would let a rung that has
     * just failed be chosen again, and that is a bounce with no exit.
     */
    var plan by remember { mutableStateOf<List<RecordDictationRung>>(emptyList()) }

    // rememberUpdatedState so a listener created once per session always calls the CURRENT lambdas.
    // Capturing them directly would have a long dictation writing its words into the composable's
    // first-frame closure — which, on a repeated row, means writing into whichever row was open when
    // the recogniser started.
    val currentPartial by rememberUpdatedState(onPartial)
    val currentCommit by rememberUpdatedState(onCommit)
    val currentError by rememberUpdatedState(onError)

    /**
     * Everything the ladder is allowed to know, read FRESH at the moment it is asked.
     *
     * Not remembered, because every one of these can change between two taps on the same field: the
     * researcher walks out of the courtyard and the connection appears, or the engine refuses a
     * language and that refusal becomes a measured fact for the rest of the run.
     */
    fun conditionsNow() = RecordDictationConditions(
        languageLabel = recordDictationLabel(tag),
        onDeviceEngine = RecordSpeechServices.onDeviceEngine(context),
        networkRecogniser = RecordSpeechServices.networkEngine(context),
        online = ConnectivityObserver.isOnline(context),
        deviceRefusedLanguage = RecordEngineRefusals.has(tag),
    )

    /** Put the engine down and go back to IDLE. Called on every exit path, including the happy one. */
    fun release() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        state = RecordDictationState.IDLE
    }

    var beginAt: (RecordDictationRung) -> Unit = {}

    /**
     * This rung could not do it. Step to the next, or say why there is no next one.
     *
     * ONLY CALLED WHERE NOBODY HAS SPOKEN YET, which is a precondition rather than a coincidence: the
     * platform recogniser refuses a language within a moment of `startListening`, so stepping on
     * costs nothing. A rung that failed AFTER an utterance would have to report instead — silently
     * re-opening a microphone at somebody who has finished speaking loses their words with no account
     * of where they went.
     */
    fun advance(from: RecordDictationRung) {
        val index = plan.indexOf(from)
        val next = if (index < 0) null else plan.getOrNull(index + 1)
        if (next != null) {
            beginAt(next)
            return
        }
        // Composed from CURRENT conditions rather than from the plan's stale copy: by now the engine
        // may have refused the language, and that is the fact that decides which sentence is true.
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
                // Cleared FIRST and unconditionally. A final result that arrives empty — which
                // happens on a clipped utterance — must not leave the last partial guess painted over
                // the box looking committed when nothing was saved.
                currentPartial("")
                if (text.isNotBlank()) currentCommit(text)
                release()
            }

            override fun onError(error: Int) {
                currentPartial("")
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "Nothing was recognised. Check that the language beside the microphone " +
                            "matches what you are speaking, and try again closer to the phone."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No speech was heard. Tap the microphone and begin speaking straight away " +
                            "— it stops listening after a few seconds of silence."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "This app does not have permission to use the microphone. Grant it under " +
                            "Settings › Apps › permissions, then tap the microphone again."
                    // NOT reported: stepped past. A network failure on the offline rung is nonsense
                    // and on the network rung means the next rung (if any) is the one to try — and
                    // if there is no next rung, `advance` produces the sentence that names the
                    // connection, which is the true cause.
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        onNetworkFailure()
                        return
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        "The phone's speech service is busy. Wait a moment and tap the microphone again."
                    SpeechRecognizer.ERROR_AUDIO ->
                        "The microphone could not be read. Something else on the phone may be using it."
                    /*
                      12 is ERROR_LANGUAGE_NOT_SUPPORTED and 13 is ERROR_LANGUAGE_UNAVAILABLE, both
                      added in API 33 and both written as literals because this module builds against
                      minSdk 26 and the constants would not resolve. They are handled by MOVING ON
                      rather than by explaining: the offline engine reports 13 for a pack that is not
                      downloaded, and the network engine may well serve the same language. Only when
                      the ladder is exhausted does anybody get a sentence — and then it names a fix,
                      because no number of further taps downloads a language pack.
                    */
                    13, 12 -> {
                        onLanguageUnavailable()
                        return
                    }
                    else ->
                        "Dictation stopped unexpectedly (code $error). Type the answer in, or try again."
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
        // Asked for explicitly as well as by EXTRA_LANGUAGE: some OEM recognisers ignore the former
        // unless the preference is also present and silently answer in the device locale instead,
        // which turns a Tamil sentence into a page of phonetic English nobody can correct.
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
                // Remembered only for the OFFLINE engine — see [RecordEngineRefusals].
                if (onDevice) RecordEngineRefusals.record(tag)
                advance(here)
            },
            onNetworkFailure = { advance(here) },
        )
        if (built == null) {
            release()
            currentError("This phone would not start its speech recogniser.")
            return
        }
        recognizer = built
        state = RecordDictationState.WORKING
        runCatching { built.startListening(intentFor()) }.onFailure {
            release()
            currentError("This phone would not start its speech recogniser.")
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
            // Said HERE and not two engine timeouts later: every rung the ladder dropped was dropped
            // for a reason this phone already knows, and making somebody wait to be told so is the
            // same failure as telling them to type it in too early.
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
            // Asked at the point of use and refused HERE means refused for dictation specifically,
            // which is a different thing from a launch-time batch declined months ago on a shared
            // handset. Say what was refused and what it was for.
            currentError("Dictation needs the microphone. Nothing was recorded.")
        }
    }

    /**
     * A live recogniser is a held microphone. Compose disposes this button whenever the row collapses
     * or the form scrolls it out of the tree, and an engine that survives that keeps the hardware and
     * goes on writing results into a lambda whose field is gone.
     */
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
                    // Stop means "I have finished the sentence": `stopListening` asks the engine to
                    // finalise what it already has, where `cancel` would throw it away. Somebody who
                    // has just spoken forty words and tapped the obvious button must not lose them.
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
        // The language, named on the control rather than hidden in a menu. It is the single most
        // common cause of a dictation that produces nonsense, and a researcher who cannot see which
        // language is armed has no way to connect the two. Hidden while listening, where the row has
        // no room and the answer cannot be changed anyway.
        if (state == RecordDictationState.IDLE) {
            TextButton(
                onClick = { showLanguages = !showLanguages },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Text(
                    recordDictationLabel(tag),
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }

    /*
     * ── THE PICKER IS A DIALOG, AND THAT IS FORCED BY WHERE THIS CONTROL IS DRAWN ─────────────
     *
     * This composable is mounted in an `OutlinedTextField`'s `trailingIcon` slot, which is measured
     * at icon width — a few dozen dp. The sibling repository emits its language list as an inline
     * `Column(Modifier.fillMaxWidth())` from the same position; here that lays out inside the icon
     * slot and arrives as a squeezed, half-clipped control, which is precisely the "visible and
     * unusable" shape this feature is otherwise careful to avoid. A dialog is measured against the
     * window instead of against its parent, so it is the only container that can be opened from
     * here. The sibling makes the identical argument for its own long-running panel; this applies it
     * one control earlier.
     *
     * It is a dialog rather than a `DropdownMenu` because `SearchableSelectField` already decides
     * between a menu and a searchable sheet on the size of the list, and nesting that decision
     * inside a menu would give a researcher two overlapping surfaces to dismiss.
     */
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
                        // No "None": a microphone with no language is a control with no meaning, and
                        // the picker's empty row would be a way to break dictation from inside
                        // dictation.
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
                        // Said here because this is where somebody chooses a language their phone may
                        // not have, and the alternative is discovering it as a refusal after a tap.
                        "The languages a phone can dictate offline depend on the packs installed on " +
                            "it. If one is refused here, another usually works.",
                        color = MaterialTheme.field.muted,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguages = false }) { Text("Close") }
            },
        )
    }
}

/**
 * ONE PROSE BOX ON A RECORD FORM, with either control, both, or neither.
 *
 * ── THE TWO FLAGS ARE OPT-IN AND DEFAULT TO OFF, WHICH IS NOT LAZINESS ────────────────────────
 *
 * `MainActivity`'s `TextInput` forwards to this composable from ~200 call sites, almost all of them
 * single-line boxes for a name, a code, a phone number, a price or a date. A microphone beside a
 * money box is noise — nobody dictates "one thousand two hundred and fifty rupees fifty paise" into
 * a costing sheet, and the recogniser returns words where the column wants digits — and a formatting
 * toolbar over a two-word village name is a control that can only get in the way. So the default for
 * both is off and each larger box asks for what it wants, by name, at its own call site. That is the
 * user's own rule: *"only the larger text boxes need to have the rich text features."*
 *
 * ── WHY THE ERROR SENTENCE IS DRAWN HERE AND NOT HANDED UP ────────────────────────────────────
 *
 * Every record screen has a different way of showing a message — a snackbar, a banner, a local
 * `var`, nothing at all — and threading a channel through twenty of them would guarantee that some
 * of them dropped it. The sibling repository learned that at a cost: its editor's dictation
 * `onError` was `{ }` at one call site and swallowed **every** sentence the control produces, so a
 * designer who spoke a passage watched it produce nothing with no account of why. Here the sentence
 * lands directly under the box that failed, always, with no call site able to discard it.
 *
 * @param rich Draw the rich-text editor instead of a plain box. **Larger narrative boxes only.**
 * @param dictate Draw the on-device microphone. Never uploads — see the file header.
 */
@Composable
fun RecordProseField(
    /**
     * The floating label, or **null** on a screen that draws its own heading above the box.
     *
     * Nullable because this app has two field conventions and both are load-bearing where they are
     * used: the record forms put the name inside the box as a Material label, and some admin panels
     * put a heading above it. A control that forced the first would draw the name twice on the
     * second, which is how a shared component gets forked.
     */
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    enabled: Boolean = true,
    rich: Boolean = false,
    dictate: Boolean = false,
    /** Shown inside an empty box. Ignored by the rich editor, which has nowhere to put one. */
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** Re-seed the rich editor when the form loads a different record into the same composition. */
    resetKey: Any? = null,
    /** Drawn under the box, above any dictation sentence. The form's own help for this field. */
    help: String? = null,
    /** Drawn under the box — `TitleCaseHint` and friends, so a caller keeps its existing extras. */
    below: @Composable () -> Unit = {},
) {
    /**
     * The recogniser's running guess, drawn in the box but NOT yet in the store.
     *
     * Kept apart from [value] rather than appended to it as it grows, and the separation is the
     * point: a partial is REVISED as the sentence continues — "the weaver" becomes "the weavers of
     * Bhuj" — so appending each one would leave the box holding every draft of the sentence
     * concatenated. Held apart, the last partial is simply replaced by the next, and by the final
     * text when it arrives.
     */
    var spoken by remember(resetKey) { mutableStateOf("") }

    /** The last dictation failure, shown under the box until the next attempt clears it. */
    var dictationError by remember(resetKey) { mutableStateOf<String?>(null) }

    val available = rememberRecordDictationAvailable()
    val showMic = dictate && available

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (rich) {
            /*
             * ── THE SEED IS HELD IN STATE, NOT DERIVED FROM [value] EVERY RECOMPOSITION ────────
             *
             * **THIS IS THE ONE THING IN THIS FILE THAT WOULD MAKE THE EDITOR UNUSABLE IF IT WERE
             * SIMPLIFIED.** `RichTextEditor` re-seeds itself from its `value` prop through a
             * `LaunchedEffect(value)` whenever the incoming document's signature differs from the
             * one it last emitted, and re-seeding moves the caret to the start of the document. Its
             * own file calls that "the single most common way a home-grown editor becomes unusable
             * for long-form writing".
             *
             * What comes back here is not what the editor sent: it went through
             * `recordStoredFromDoc` into a `String?` and back out through `recordDocFromStored`, and
             * for an UNFORMATTED document that round trip is a flatten — same text, and a document
             * whose signature can still differ (a trailing space trimmed, an empty paragraph
             * dropped). Derived naively the chain would be: type a character → store prose → parent
             * re-renders → new seed → the editor decides the document changed underneath it → the
             * caret jumps to character zero. On the second keystroke. For ever.
             *
             * So the seed changes only when the value arrived from somewhere OTHER than this editor:
             * a record finishing its load after the form composed, or a different record being
             * opened. [mine] is what tells the two apart.
             */
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
                    // `recordStoredFromDoc` is the ONE place in this app that decides what lands in a
                    // record's `String?` column, and it is the same rule
                    // `frontend/components/richtext/storedRichText.ts` applies in the browser. Read
                    // its block comment before changing it: the wrong answer renders as visible JSON
                    // braces in a CSV, a workbook and a reviewer's edit box, and it does so silently.
                    val stored = recordStoredFromDoc(fromJson(next)).orEmpty()
                    // Recorded BEFORE the value goes up, so the round trip that comes back is
                    // recognised as this editor's own and does not re-seed it.
                    mine = stored
                    onValueChange(stored)
                },
                enabled = enabled,
                // The editor requires a label; a caller that draws its own heading passes null and
                // gets an empty one rather than a duplicate.
                label = label.orEmpty(),
                help = help,
                // Passed through rather than assumed, so that `rich = true, dictate = false` means
                // what it says. The editor hosts its own microphone; without this it would draw one
                // for a caller that asked for the editor and not for dictation.
                dictate = dictate,
                // Wired, always. The default is `{ }` and the default is a known defect — see the
                // parameter's own documentation in `RichTextEditor.kt`. Landing it in the same strip
                // the plain box uses also means a record form reports a dictation failure the same
                // way whichever kind of box it happened in.
                onError = { message -> dictationError = message },
            )
            Text(RECORD_RICH_TEXT_NOTE, color = MaterialTheme.field.muted, fontSize = 11.sp)
        } else {
            OutlinedTextField(
                // While a partial is streaming the box shows what has been heard SO FAR, appended to
                // what was already there. Rendering it anywhere else — a strip below, a toast — is
                // what makes dictation feel untrustworthy: the researcher cannot tell whether the
                // words they can see are the words that will be saved. Here they are in the box.
                value = if (spoken.isBlank()) value else appendSpokenToRecord(value, spoken),
                onValueChange = { raw -> if (spoken.isBlank()) onValueChange(raw) },
                label = label?.let { { Text(it) } },
                placeholder = placeholder?.let {
                    { Text(it, color = MaterialTheme.field.placeholder) }
                },
                enabled = enabled,
                // Read-only for the seconds the recogniser is running. A keystroke landing in the
                // middle of a stream is overwritten by the next partial, so the alternative is a box
                // that silently discards typing — which reads as a broken keyboard.
                readOnly = spoken.isNotBlank(),
                minLines = minLines,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
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
                modifier = Modifier.fillMaxWidth()
            )
            help?.let { Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp) }
        }

        // Three words. The researcher is watching the words appear in the box; anything longer here
        // describes what they can already see.
        if (spoken.isNotBlank()) {
            Text("Listening — speak now.", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
        dictationError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
        /*
         * THE HONEST ABSENCE. A box that was asked for a microphone and has no recogniser to give it
         * says so, once, rather than showing nothing — because "this screen has no dictation" and
         * "this phone cannot dictate" look identical to somebody who was told the feature exists, and
         * the second is a fact about their handset they can act on. Only on boxes that ASKED for a
         * mic: printing it under a phone-number field would be twenty copies of a sentence about a
         * control nobody wanted there.
         *
         * IT APPLIES TO THE RICH BOX TOO, and that is the whole reason this port unified the two
         * availability rules. The editor hosts the same microphone and asks the same question, so
         * when the answer is no, neither kind of box draws a control and both say the same sentence.
         * The sibling repository could not do this: its editor asks a wider question (it counts a
         * server route as dictation), so a phone with no speech service still drew a mic there, and a
         * line saying "there is no dictation here" underneath would have had the screen arguing with
         * itself.
         */
        if (dictate && !available) {
            Text(RECORD_DICTATION_UNAVAILABLE, color = MaterialTheme.field.muted, fontSize = 11.sp)
        }
        below()
    }
}

/**
 * A stored column value as the `JsonElement?` the editor opens.
 *
 * Through the document and back out as canonical JSON rather than handed over as a raw string,
 * because the editor's `fromJson` would read a bare string as prose and would never recognise a
 * stored document. `recordDocFromStored` is where that decision lives — which shape the column is
 * in, and how each is read — and this is only the adaptor to the editor's parameter type.
 *
 * Null for an empty document, which is the editor's own spelling of "nothing has been written here".
 */
private fun richSeedOf(stored: String): JsonElement? {
    val doc = recordDocFromStored(stored)
    return if (doc.blocks.isEmpty()) null else toJson(doc)
}
