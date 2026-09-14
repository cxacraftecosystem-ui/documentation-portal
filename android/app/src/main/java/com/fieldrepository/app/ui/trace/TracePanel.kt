package com.fieldrepository.app.ui.trace

// The panel holds the three display plates as platform bitmaps, because that is what the runtime hands
// back and what `TracePlates` takes. Nothing here reads a pixel.
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.fieldrepository.app.ui.LocalAppPreferences
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see ui/FieldText.kt for why a
// bare Material `Text` here would quietly set this panel's headings in the body face.
import com.fieldrepository.app.ui.Text
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/**
 * **"TRACE THIS SKETCH INTO LINE ART" — the handset's surface over [TraceEngineRuntime].**
 *
 * ── WHAT THIS IS FOR ──────────────────────────────────────────────────────────────────────────
 *
 * A researcher photographs a paper sketch, a pattern draft, a rubbing or a hand-drawn tool diagram and
 * turns it into vector line art that can be filed beside the photograph. The web can already do it;
 * this product's premise is a researcher working offline in a village for a fortnight, so the client
 * that could not do it is the client that matters most.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE FOUR PROPERTIES THIS PANEL EXISTS TO HOLD
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * **1. IT NEVER UPLOADS ANYTHING AND NEVER TOUCHES THE ORIGINAL PHOTOGRAPH.** The entire output of
 * this panel is ONE derived file handed to [onAttach]. The host puts it through the ordinary upload
 * door, so eager pre-upload, per-file retry and the offline store all already apply. A panel that
 * uploaded its own file would be a second upload path to keep working offline, in an application whose
 * whole point is working offline. The photograph is opened for READING, twice, and is never written,
 * re-encoded or replaced.
 *
 * **2. IT RUNS ON THE DEVICE, OFF THE UI THREAD, AND THERE IS NO PATH AROUND THAT.** `Pipeline.run` is
 * straight loops over typed arrays; a 12 MP trace is seconds of solid CPU, and on the UI thread that is
 * a FROZEN app, not a slow one. Every method of [TraceEngineRuntime] puts its own `withContext` inside
 * the implementation rather than at the call site, so no future caller can forget — and the same rule
 * holds for the two decoders, the PNG rasteriser and the exporter. This file launches coroutines and
 * never computes.
 *
 * **3. THE ENGINE IS NOT LOADED UNTIL SOMEBODY OPENS THE CARD.** The open half is a separate
 * composable, and the runtime's tables are read inside a `LaunchedEffect` that only exists once it is
 * mounted. Collapsed, this card is a heading, a sentence and a button.
 *
 * **4. "KEEP THE PHOTOGRAPH AS IT IS" IS A REAL ANSWER AND COSTS ONE PRESS.** A trace is a THRESHOLD —
 * a decision to discard everything on one side of a line — and an over-traced sketch has lost
 * something: a faint construction line, a smudged tone showing where a curve was felt out. So the trace
 * is SHOWN BEFORE IT IS ATTACHED, in a comparator built for exactly that question, and declining needs
 * no explanation and writes nothing.
 *
 * ── NOTHING IS ATTACHED WITHOUT SOMEBODY SEEING IT FIRST ──────────────────────────────────────
 *
 * Two buttons, in order: one makes a drawing and shows it, the other attaches the drawing that is on
 * screen. There is NO path from a trace to an attachment that does not pass through a person looking at
 * the result.
 *
 * The "Add the line art" button is disabled while what is on screen is a PREVIEW, and while the
 * settings have moved on since it was made. Saving a preview hands somebody a coarser drawing than the
 * one they approved, with nothing on screen to say so.
 *
 * ── THE THREE THINGS A HANDSET NEEDS THAT THE PORTAL DOES NOT HAVE ────────────────────────────
 *
 *  1. **A visible Cancel.** Cancellation on the web is implicit: a moved slider aborts the running
 *     preview after a debounce and a busy flag merely disables three buttons. That is defensible on a
 *     laptop where a preview is a few hundred milliseconds. It is not defensible here, where a
 *     full-resolution run is seconds to tens of seconds of one core on a mid-range phone, the
 *     researcher is on battery in a village, and somebody is waiting.
 *  2. **Progress driven by the engine's own stage names**, weighted by what this device measured last
 *     time. See [TraceProgressWeights] for why a stage count is not good enough here.
 *  3. **Previews that stop costing battery when nobody is looking**, and that turn themselves off when
 *     THIS phone proves them too slow to be live. See [TRACE_AUTO_PREVIEW_BUDGET_MS].
 *
 * ── ONE BUSY FLAG FOR EVERY DESTINATION ───────────────────────────────────────────────────────
 *
 * A preview, a full trace and an export are the same operation with different endings, so they share
 * one in-flight [Job] and one `running` value. Two independent busy flags mean "the loser would report
 * 'the trace did not finish' while the winner quietly succeeded".
 *
 * A press does not need to remember to disarm a pending preview either — [startRun] cancels and JOINS
 * whatever was running before it starts, so the bug where an attach begins, a preview armed a moment
 * earlier fires and aborts it, and the attach reports "nothing finished" beside a finished drawing is
 * not possible rather than merely handled.
 *
 * ── A CANCELLED TRACE IS NOT A FAILURE ────────────────────────────────────────────────────────
 *
 * A cancel "must never reach the user as one", and this file honours it by never turning a
 * `CancellationException` into an error line.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Numbers this surface owns
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * How long the panel waits after the last edit before re-tracing a preview.
 *
 * **NOT the portal's 220 ms**, and the difference is not taste. That number is tuned for a preview that
 * costs a few hundred milliseconds on a laptop. A 720 px preview was measured at 0.6 s on a desktop's
 * V8 with the adaptive engine and 3.1 s with the shipped flow default, which extrapolates to roughly
 * 2.5–4.4 s and 12–21 s on a mid-range handset — so at 220 ms somebody sliding a control would queue and
 * abandon a trace every fifth of a second and heat the phone for nothing.
 *
 * **450 ms is a starting value and is explicitly NOT MEASURED on a device.** It is roughly the pause
 * between two deliberate thumb adjustments. Re-check it against a real device before treating it as
 * settled; the self-disabling budget below is what stops it doing damage in the meantime.
 */
const val TRACE_PREVIEW_DEBOUNCE_MS: Long = 450L

/**
 * The longest a preview may take before this phone stops running them automatically.
 *
 * ── WHY THE DEVICE DECIDES THIS AND NOT A TABLE ───────────────────────────────────────────────
 *
 * The single unmeasured number in the whole feasibility argument is the desktop-to-handset factor. A
 * constant here that assumed 4x or 7x would be writing that guess into a screen.
 *
 * So nothing is assumed. `totalMillis` comes back from every run, and if a preview on THIS phone with
 * THESE parameters took longer than this, live previewing is switched off and the panel says so in a
 * sentence with the measured number in it. The researcher keeps an "Update the preview" button and
 * loses nothing but the automatic part. Measure the device in front of you rather than the device in
 * the specification.
 *
 * Two and a half seconds is the point at which a live preview stops being live — past it somebody has
 * already looked away.
 */
const val TRACE_AUTO_PREVIEW_BUDGET_MS: Long = 2500L

/**
 * How long the disclosure's chevron takes to turn over.
 *
 * ── THE CHEVRON IS THE ONLY THING THAT MOVES, AND THAT IS A DECISION ──────────────────────────
 *
 * The obvious animation for an accordion is the height of the thing it opens, and this one has none. A
 * height animation on this section would run a measure pass over up to twenty-four control rows on
 * every frame for the length of the tween — on the phone class this whole feature is hardest on, at the
 * moment somebody has just asked to see more — and [TraceSliderRow] re-seeds a remembered thumb
 * position from the committed value, so an animated row is also a row being laid out while its slider
 * is being re-created.
 *
 * So one 18 dp icon turns, which costs a draw-time rotation and nothing else, and it is one of the two
 * places `LocalAppPreferences.current.reducedMotion` has anything to switch off — where it collapses
 * this tween to zero and the chevron simply IS at its new angle.
 */
const val TRACE_DISCLOSURE_TURN_MS: Int = 180

/** What TalkBack is told the header press will DO, in the two directions. */
internal const val TRACE_EXPAND_ACTION: String = "Expand the tracing card"

/** The other direction of the same sentence. See [TRACE_EXPAND_ACTION]. */
internal const val TRACE_COLLAPSE_ACTION: String = "Collapse the tracing card"

/**
 * **THE TITLE, SAID ONCE, IN BOTH STATES — AND ON BOTH CLIENTS.**
 *
 * A card that is called one thing while it is shut and another while it is open is a control whose
 * label changes when you press it, which reads as a different control. One title, chosen once.
 *
 * ── AND THE TIE-BREAK IS THE OTHER CLIENT, NOT TASTE ──────────────────────────────────────────
 *
 * Two candidates were defensible and one had to win: the longer wording names the INPUT, the shorter is
 * shorter. **The shorter wins because it is already the portal's name for this card** — its own
 * `CARD_TITLE` is "Trace a sketch into line art", and its end-to-end tests address the panel by those
 * exact words. A card that does one job under two names across two clients is a defect, and divergence
 * is available only where a clause would be FALSE on one of them. Nothing about this sentence is false
 * on a handset, so there is no exception to claim — and the fact the longer wording named is not lost:
 * the sentence under this heading says which photograph is being traced.
 */
internal const val TRACE_CARD_TITLE: String = "Trace a sketch into line art"

/* ────────────────────────────────────────────────────────────────────────────
 * What the collapsed card remembers
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What this card has already done, held by the panel so that closing it is not the same as never having
 * opened it.
 *
 * ── MARKS AND TEXT ARE CHEAP, SO THEY ARE KEPT. PIXELS ARE EXPENSIVE, SO THEY ARE NOT ─────────
 *
 * Everything in here is a string, an int or a flag. The [TraceResult] is NOT, and must not become a
 * field of this class: it carries two 1024 px display plates — about 8.4 MB — and the panel's whole
 * arrangement is that opening it starts a runtime and holds those plates and closing it drops them,
 * which is the right trade on a phone whose other job right now is a camera preview.
 *
 * ── SO THE COLLAPSED CARD REPORTS AND DOES NOT PROMISE ────────────────────────────────────────
 *
 * It reports what HAPPENED ("a full-resolution trace of sheet-3.jpg, 412 paths") and the sentence
 * beside it says plainly that the drawing itself is not kept. Somebody who reopened expecting their
 * drawing and found an empty comparator would have been told a specific untruth by the screen. That is
 * still the whole of the report an accordion answers: a collapsed card that says only its own title is
 * indistinguishable from one nobody has touched.
 */
@Stable
internal class TraceCardMemory {

    /** The photograph the last finished trace was made from, by name. Blank when there was none. */
    var tracedName by mutableStateOf("")
        private set

    /**
     * Paths and nodes off that trace, for the one figure that says how much drawing came out.
     *
     * `mutableIntStateOf` rather than `mutableStateOf` — Compose's own advice for an Int, and free here:
     * a boxed state allocates on every write, and these two are written once per finished trace on the
     * phone class this feature is hardest on.
     */
    var shapeCount by mutableIntStateOf(0)
        private set

    var nodeCount by mutableIntStateOf(0)
        private set

    /** True while what was made was a preview — a coarser drawing that may not be attached. */
    var wasPreview by mutableStateOf(true)
        private set

    /** What was handed to the host from this card, by name, or blank if nothing was. */
    var attachedName by mutableStateOf("")
        private set

    fun recordTrace(sourceName: String, shapes: Int, nodes: Int, preview: Boolean) {
        tracedName = sourceName
        shapeCount = shapes
        nodeCount = nodes
        wasPreview = preview
    }

    fun recordAttachment(fileLabel: String) {
        attachedName = fileLabel
    }

    /**
     * Forget the drawing, keeping the attachment.
     *
     * A result belongs to ONE photograph and a summary left standing under a different one describes a
     * drawing that was never made from it. The ATTACHMENT is a different kind of fact — it is a file
     * that went to the host, whichever photograph the card is pointed at — so it survives.
     */
    fun forgetTrace() {
        tracedName = ""
        shapeCount = 0
        nodeCount = 0
        wasPreview = true
    }
}

/**
 * What the COLLAPSED card says has already happened, in one sentence, or null when nothing has.
 *
 * IT NAMES THE PHOTOGRAPH AND THE COUNT, NOT ONE OR THE OTHER. "412 paths" alone does not say which
 * sheet; the sheet alone does not say whether anything came out of it. Both, or neither.
 *
 * Pure — plain counts, flags and strings — so `TracePanelTest` can pin the wording on the JVM with no
 * composition to run it in.
 */
internal fun traceCardSummary(
    tracedName: String,
    shapeCount: Int,
    nodeCount: Int,
    wasPreview: Boolean,
    attachedName: String,
): String? {
    val parts = mutableListOf<String>()
    if (tracedName.isNotBlank()) {
        parts += if (wasPreview) {
            "a preview traced from “${tracedName.trim()}”"
        } else {
            "traced from “${tracedName.trim()}”"
        }
        parts += "$shapeCount paths · $nodeCount nodes"
    }
    if (attachedName.isNotBlank()) parts += "added as “${attachedName.trim()}”"
    // Nothing traced and nothing added is not a state anybody put this card into — it should read as the
    // invitation it was before it was ever opened.
    if (parts.isEmpty()) return null
    return parts.joinToString(" · ")
}

/* ────────────────────────────────────────────────────────────────────────────
 * The panel
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The whole surface, collapsed until somebody asks for it.
 *
 * ── THE HOST BOUNDARY, WHICH IS THE WHOLE OF WHAT THIS PANEL KNOWS ABOUT THE APP ──────────────
 *
 * Four parameters and one callback. It knows nothing about records, fields, forms, stages or uploads,
 * and it must stay that way: the moment it knew about a field it would be a second place that decides
 * what a field may hold, and the moment it knew about an upload it would be a second upload path.
 *
 * @param photograph the image to trace, as a `content://` Uri the host already holds and has read
 *   access to — or NULL when the host has nothing chosen yet.
 *
 *   ⚠ NULL IS A STATE AND NOT AN ERROR, and this parameter was non-null until 2026-09-14. The host
 *   mounted the card with `?.let { }`, so a form with no photograph on it yet rendered NO CARD AT
 *   ALL — and a researcher opening a tool or a product form reported the tracer missing from those
 *   pages. It was not missing; it was unfindable, which for a feature is the same thing.
 *
 *   The collapsed card never reads this: it is a title, a description and a disclosure header, and
 *   it is worth showing whether or not a photograph exists, because that is how somebody learns the
 *   tool is there. Only the OPEN half needs an image, and with none it says so and names the picker
 *   rather than drawing a second one. Web `TracePanel` takes the same value for the same reason —
 *   see its `image` prop, which documents this in the same words.
 *   permission for. Read twice for pixels and never written.
 * @param currentFileName the name of whatever is already attached where this drawing would go, or null.
 *   Used for exactly one sentence — [tracePanelReplaceWarning] — because only the host can know whether
 *   attaching replaces something, and a warning before a destructive write is the last place to guess.
 * @param enabled false for a read-only surface. It gates OPENING the card (which starts a runtime and
 *   decodes a photograph) and every control inside it. Collapsing is always allowed.
 * @param onAttach called with a `content://` Uri for ONE newly written SVG, from a button somebody
 *   pressed and from nowhere else. **The host owns that file and that Uri afterwards** — put it through
 *   the ordinary media door; this panel does not delete it, does not upload it and does not follow it.
 * @param subjectHint what the host already knows about the material, if anything, as a key of
 *   [TRACE_SUBJECT_FOR_CATEGORY]. Seeds the subject picker VISIBLY and applies nothing. Null is the
 *   ordinary case in this product today and opens on the default.
 * @param exporter the writers behind the "Save this drawing" card. Defaulted to the real one; a host
 *   that wants no export step at all passes [TraceExporterUnavailable].
 */
@Composable
fun TracePanel(
    photograph: Uri?,
    currentFileName: String?,
    enabled: Boolean,
    onAttach: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    subjectHint: String? = null,
    onMessage: (String) -> Unit = {},
    onError: (String) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    /*
      WHAT THIS CARD HAS ALREADY DONE, KEYED ON NOTHING so it outlives the open half. Collapsing this
      card would otherwise be indistinguishable from never having opened it, which is half of the report
      an accordion answers — see [TraceCardMemory] for what is kept, what is deliberately not, and why
      the sentence underneath does not promise a drawing.
    */
    val memory = remember { TraceCardMemory() }

    if (!open) {
        val summary = traceCardSummary(
            tracedName = memory.tracedName,
            shapeCount = memory.shapeCount,
            nodeCount = memory.nodeCount,
            wasPreview = memory.wasPreview,
            attachedName = memory.attachedName,
        )
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // THE TITLE ROW IS THE CONTROL. An inert Row here — an icon and some text with no chevron,
            // no state description and no press — would answer a tap on its heading differently from
            // every other disclosure in this app and would tell a screen reader nothing about whether
            // it was open.
            TracePanelDisclosureHeader(
                icon = Icons.Filled.Gesture,
                title = TRACE_CARD_TITLE,
                expanded = false,
                // The gate. A read-only surface must not be openable, because opening it starts a
                // runtime and decodes a photograph.
                toggleEnabled = enabled,
                expandAction = TRACE_EXPAND_ACTION,
                collapseAction = TRACE_COLLAPSE_ACTION,
                onToggle = { open = true },
            )
            if (summary == null) {
                Text(
                    "Turns the pencil in this photograph into vector line work — the same drawing, as " +
                        "lines that print at any size without going blocky. The result is added as a " +
                        "separate file; the photograph itself is never changed. It runs on this " +
                        "device and needs no connection.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            } else {
                // THE STATE, IN WORDS, WITHOUT EXPANDING ANYTHING — drawn in the foreground colour for
                // its reason: this is the thing somebody came back to read, not chrome around it.
                Text(
                    summary,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Text(
                    // SAID PLAINLY. Somebody who reopened expecting the comparator they left would have
                    // been told something specific and false by a card that said nothing.
                    "The drawing itself is not kept while this is closed — it is two full-size " +
                        "pictures, and holding them would cost this phone the memory the trace needs. " +
                        "Opening this again traces afresh; anything already added stays added.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            // KEPT RATHER THAN REPLACED BY THE HEADER: a row that happens to be tappable is not a
            // button anybody can SEE.
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Gesture, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (summary == null) "Trace a sketch" else "Open the tracing card again",
                    fontSize = 13.sp,
                )
            }
        }
        return
    }

    if (photograph == null) {
        /*
          OPENED WITH NOTHING TO TRACE. The card is worth showing on a form that has taken no
          photograph yet — that is how the tool is discovered — so opening it has to have an honest
          answer rather than a blank panel or a dead button.
          NO SECOND PICKER HERE, deliberately. This form already has one, directly above; a chooser
          in this card would be a second way in with its own state to keep, and the one thing worse
          than a hidden feature is two of it. So it points at the picker that exists.
        */
        TraceEmptyState(enabled = enabled, modifier = modifier, onClose = { open = false })
        return
    }

    TracePanelOpen(
        photograph = photograph,
        currentFileName = currentFileName,
        enabled = enabled,
        subjectHint = subjectHint,
        memory = memory,
        modifier = modifier,
        onClose = { open = false },
        onAttach = onAttach,
        onMessage = onMessage,
        onError = onError,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TracePanelOpen(
    photograph: Uri,
    currentFileName: String?,
    enabled: Boolean,
    subjectHint: String?,
    /** What the collapsed card will report. Owned by the panel, so it outlives this composable. */
    memory: TraceCardMemory,
    modifier: Modifier,
    onClose: () -> Unit,
    onAttach: (Uri) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // PROPERTY 3: the runtime and the exporter are constructed HERE, inside the open half, so a
    // collapsed card has loaded nothing.
    val runtime = rememberTraceRuntime()
    val exporter = rememberTraceExporter()
    val availability = runtime.availability
    val sourceName = remember(photograph) { traceDisplayName(context, photograph) }

    var presets by remember { mutableStateOf<TracePresetTables?>(null) }
    var params by remember { mutableStateOf<TraceValues?>(null) }

    /**
     * The tree as the last PRESET left it — the baseline the "you changed this" marks measure against.
     *
     * Set by a style and by nothing else, which is the engine's shape: a style REPLACES the settings, so
     * it becomes both the live parameters and the baseline; a subject is a one-way modifier that says
     * something about the material rather than about the drawing wanted, so it leaves the baseline
     * alone.
     */
    var baseline by remember { mutableStateOf<TraceValues?>(null) }

    var styleId by remember { mutableStateOf("") }
    var subjectId by remember { mutableStateOf(traceSubjectFor(subjectHint)) }
    var subjectSeeded by remember { mutableStateOf(subjectHint != null) }

    var result by remember { mutableStateOf<TraceResult?>(null) }
    /** The tree the on-screen [result] was produced from, so "these settings have moved on" is exact. */
    var resultWire by remember { mutableStateOf<String?>(null) }

    /**
     * Which region of the photograph the engine is handed, or null for all of it.
     *
     * Cleared with the photograph: a frame chosen on one sheet is meaningless on the next, and leaving
     * it applied would trace a region of a photograph nobody framed.
     */
    var frame by remember { mutableStateOf<TraceFrameChoice?>(null) }

    /*
      THE THIRD PLATE, BUILT ON THE FIRST PRESS AND NOT WITH THE OTHER TWO.

      Held here rather than inside the comparator because building it needs a `Bitmap`, and
      `TraceCompare.kt` is deliberately the file that does not know what one is. Cleared whenever a new
      result arrives: a difference belongs to ONE pair of plates, and one left on screen under a newer
      drawing is a picture of a comparison that is no longer being made.

      NOT RECYCLED when it is dropped. Compose holds a bitmap through an `ImageBitmap` for as long as
      the frame is on screen, and recycling one that is still being drawn throws in the middle of
      unsaved work. Dropping the reference is enough — it is 4.2 MB of garbage the moment the panel
      stops pointing at it.
    */
    var difference by remember { mutableStateOf<Bitmap?>(null) }
    var differenceRefusal by remember { mutableStateOf("") }
    var differenceRunning by remember { mutableStateOf(false) }

    var running by remember { mutableStateOf<TraceRunKind?>(null) }
    var stopping by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<TraceProgress?>(null) }
    var weights by remember { mutableStateOf(TraceProgressWeights.Unweighted) }

    var notice by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var advancedOpen by remember { mutableStateOf(false) }
    var autoPreview by remember { mutableStateOf(true) }
    var attaching by remember { mutableStateOf(false) }

    var job by remember { mutableStateOf<Job?>(null) }

    /**
     * Which photograph the in-flight run belongs to, as a number that only ever goes up.
     *
     * ── THE BUG THIS EXISTS FOR, WHICH `cancelAndJoin` ALONE DOES NOT CLOSE ───────────────────
     *
     * A trace is seconds to tens of seconds. If the photograph changes while one is running, the run
     * must not be allowed to finish and write ITS drawing into the panel's state — a trace of sheet A
     * shown under sheet B's name is a drawing somebody would attach believing it came from what they
     * are looking at, which is the single worst outcome this panel is written against.
     *
     * Cancellation is most of the answer and is not all of it. Kotlin does not poll for cancellation
     * between two ordinary statements: a run that is INSIDE `runtime.trace` when the cancel arrives
     * throws at the next suspension point, but a run that has just RETURNED from it is a few
     * assignments away from `result = traced` with no suspension in between, and a cancel landing in
     * that window is simply lost. So the token is read after the trace returns and before anything is
     * written, and the write is skipped if it has moved.
     */
    var runToken by remember { mutableIntStateOf(0) }

    /*
      ONE PATCH AT A TIME. Every parameter change is a round trip through the runtime's own
      `withOverrides`, which hops to a background dispatcher — so two taps in quick succession would
      otherwise both read the same `params`, both apply their own patch to it, and the second would land
      on top of the first with the first's change erased. The mutex makes each patch read the tree AFTER
      the previous one wrote it, which is the only ordering that cannot lose an edit.
    */
    val patchLock = remember { Mutex() }

    /*
      PREVIEWS STOP WHILE NOBODY IS LOOKING. A trace is seconds of solid arithmetic; running one because
      a slider moved just before somebody answered a phone call is battery spent on a picture nobody
      will see. Full-resolution runs are deliberately NOT gated on this — somebody who starts a
      twenty-second trace and locks the screen to wait for it should get their drawing.
    */
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    val stale = result != null && resultWire != null && resultWire != params?.wire

    /* ── Loading the engine's own tables ────────────────────────────────────────────────────── */

    LaunchedEffect(runtime) {
        loading = true
        failure = null
        try {
            val tables = runtime.presets()
            val defaults = runtime.defaults()
            presets = tables
            params = defaults
            baseline = defaults
            styleId = defaults.styleId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // Named where it is true rather than reported as "this phone cannot trace". The engine is
            // compiled into this APK and every phone has it, so a failure here is this load and not this
            // device, and those are different sentences with different remedies.
            failure = "The tracing engine did not start on this device. " +
                (t.message?.takeIf { it.isNotBlank() } ?: "No reason was reported.")
        }
        loading = false
    }

    /* ── Running one trace ──────────────────────────────────────────────────────────────────── */

    /**
     * Start a run, superseding whatever was running.
     *
     * `cancelAndJoin` and not a bare `cancel`, deliberately: the new run WAITS for the old one to
     * actually stop before it touches any state. Without the join the two bodies race, and the loser's
     * cleanup runs after the winner's set-up — which is how a finished trace ends up beside a spinner
     * that never clears. It is also what makes "Stopping…" honest, because the old run's `finally` is
     * reached only when the runtime really has stopped.
     */
    fun startRun(kind: TraceRunKind, debounceMs: Long) {
        val values = params ?: return
        val previous = job
        // WHICH PHOTOGRAPH THIS RUN IS ABOUT, READ AT THE PRESS AND NOT AT THE ANSWER. See [runToken].
        val token = runToken
        val tracedName = sourceName
        job = scope.launch {
            previous?.cancelAndJoin()
            if (debounceMs > 0L) delay(debounceMs)
            running = kind
            stopping = false
            progress = null
            failure = null
            try {
                val outcome = runtime.trace(
                    TraceRequest(
                        photograph = photograph,
                        params = values,
                        kind = kind,
                        frame = frame,
                    ),
                ) { progress = it }
                // THE PHOTOGRAPH MAY HAVE MOVED WHILE THIS RAN. Everything below writes the panel's
                // visible state, and every one of those writes would be about the wrong sheet. The
                // `finally` still runs — this run really has stopped and the spinner it owns must
                // clear — and the effect that changed the photograph has already dropped the old
                // drawing and armed a fresh preview.
                if (token != runToken) return@launch
                when (outcome) {
                    is TraceOutcome.Refused -> {
                        result = null
                        resultWire = null
                        difference = null
                        differenceRefusal = ""
                        failure = outcome.reason
                    }

                    is TraceOutcome.Done -> {
                        val traced = outcome.result
                        result = traced
                        // The old difference belongs to the old pair of plates. See the note where it
                        // is declared.
                        difference = null
                        differenceRefusal = ""
                        /*
                          THE PANEL IS RE-RENDERED FROM `appliedParams`, NOT FROM WHAT WAS SENT.
                          Auto-detection runs before the first stage, so a request and its result can
                          legitimately differ; dropping this would leave a dock that says one thing and
                          a drawing produced by another.
                        */
                        params = traced.appliedParams
                        resultWire = traced.appliedParams.wire
                        // WHAT THE COLLAPSED CARD WILL REPORT, written where the drawing arrives and
                        // named after the photograph THIS run was started on rather than whatever the
                        // panel is pointed at by the time it lands.
                        memory.recordTrace(
                            sourceName = tracedName,
                            shapes = traced.shapeCount,
                            nodes = traced.nodeCount,
                            preview = traced.isPreview,
                        )
                        if (traced.stages.isNotEmpty()) weights = TraceProgressWeights.from(traced.stages)
                        if (kind == TraceRunKind.PREVIEW &&
                            traced.totalMillis > TRACE_AUTO_PREVIEW_BUDGET_MS
                        ) {
                            // Measured on THIS phone, said with the number in it. See the constant.
                            autoPreview = false
                            notice = "That preview took ${traceSeconds(traced.totalMillis)} on this " +
                                "phone, so the panel has stopped re-tracing by itself. Change what you " +
                                "like and press “Update the preview” when you are ready."
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // NEVER AN ERROR. A cancel must never reach the user as one. Rethrown so the coroutine
                // machinery sees a cancellation rather than a swallowed one, which is what keeps
                // `cancelAndJoin` above correct.
                throw cancelled
            } catch (t: Throwable) {
                // GUARDED FOR THE SAME REASON THE SUCCESS PATH IS. A failure sentence belongs to the
                // photograph the run was about; printed under a different one it reads as "this sheet
                // cannot be traced" about a sheet nothing has been tried on.
                if (token != runToken) return@launch
                result = null
                resultWire = null
                failure = "The trace could not be completed. " +
                    (t.message?.takeIf { it.isNotBlank() } ?: "No reason was reported.")
            } finally {
                running = null
                stopping = false
                progress = null
            }
        }
    }

    /** Arm a preview, if a preview is a thing this panel should be doing right now. */
    fun armPreview() {
        if (!autoPreview || !resumed) return
        // A full-resolution run is the answer somebody asked for; a preview must not evict it.
        if (running?.isFullResolution == true) return
        startRun(TraceRunKind.PREVIEW, TRACE_PREVIEW_DEBOUNCE_MS)
    }

    /*
      ONE PREVIEW WHEN THE PANEL OPENS, and never a second one it did not ask for.

      A panel that opened onto an empty frame would make somebody press a button to find out what the
      feature even does, and the answer to "what will this look like" is the whole reason the preview
      exists. Keyed so it fires once per opening: `primed` is remembered beside the rest of the panel's
      state, so closing and re-opening asks again and a recomposition does not.
    */
    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(loading, autoPreview, resumed) {
        if (loading || params == null || primed) return@LaunchedEffect
        if (!autoPreview || !resumed) return@LaunchedEffect
        primed = true
        startRun(TraceRunKind.PREVIEW, 0L)
    }

    /**
     * Everything on screen that was derived from the photograph, dropped in one place.
     *
     * ONE FUNCTION AND NOT FIVE ASSIGNMENTS AT EVERY SITE, because the way a reset like this comes to be
     * incomplete is by being written out twice with one line missing from the second copy. The frame
     * goes with the rest: a region chosen on one sheet is meaningless on the next.
     */
    fun forgetDerivations() {
        result = null
        resultWire = null
        difference = null
        differenceRefusal = ""
        frame = null
        memory.forgetTrace()
    }

    /**
     * THE PHOTOGRAPH CHANGED UNDER THIS PANEL — stop what is running and drop what it produced.
     *
     * ── WHY THIS IS AN EFFECT AND NOT A CALLBACK ──────────────────────────────────────────────
     *
     * The host owns which photograph this panel is pointed at, and it can change it while this panel is
     * shut, or open, or MID-TRACE. So the reset hangs off the FACT that it changed rather than off any
     * press, because there is no press here to hang it on.
     *
     * ── AND WHY IT CANCELS FIRST AND ARMS LAST ────────────────────────────────────────────────
     *
     * `runToken++` before the cancel, so a run that is between its last suspension point and its first
     * assignment cannot write (see [runToken]); `cancelAndJoin` and not a bare `cancel`, so the old
     * run's `finally` — which owns the spinner — has really run before a new one starts; then the
     * drawing is dropped, and only then is a preview armed. `armPreview` declines when auto-preview is
     * off or a full run is in flight, which are both the right answers.
     *
     * The first composition is not a change: `derivedFrom` starts on the photograph the panel opened
     * with, so this does nothing until something moves it. Without that guard, opening the panel would
     * cancel the one preview it opens with.
     */
    var derivedFrom by remember { mutableStateOf(photograph) }
    LaunchedEffect(photograph) {
        if (derivedFrom == photograph) return@LaunchedEffect
        derivedFrom = photograph
        runToken++
        job?.cancelAndJoin()
        job = null
        running = null
        stopping = false
        progress = null
        failure = null
        notice = null
        forgetDerivations()
        armPreview()
    }

    /**
     * Build the difference plate, once, for the pair of plates currently on screen.
     *
     * ON A BACKGROUND DISPATCHER, because it reads and writes every pixel of a 1024 px pair — about a
     * million iterations of [traceDifferenceRow] — and doing that on the composition would drop frames
     * on exactly the phones this feature is hardest on.
     *
     * A REFUSAL IS A SENTENCE AND NOT A DISABLED CHIP. A phone that could not spare a third 4.2 MB
     * bitmap has lost one of four views and nothing else; the drawing, the wipe and the two whole
     * pictures are all still there, so what is owed is a line saying which view is missing.
     */
    fun buildDifference() {
        if (differenceRunning || difference != null) return
        val traced = result ?: return
        val photographPlate = traced.photographPlate ?: return
        val tracePlate = traced.tracePlate ?: return
        differenceRunning = true
        differenceRefusal = ""
        scope.launch {
            val built = withContext(Dispatchers.Default) {
                TracePlates.differencePlate(photographPlate, tracePlate)
            }
            // Dropped if somebody moved on while it was being built: a plate for a pair of plates that
            // are no longer on screen is a picture of a comparison nobody is making.
            if (result === traced) {
                difference = built
                differenceRefusal = if (built == null) TRACE_DIFFERENCE_REFUSAL else ""
            }
            differenceRunning = false
        }
    }

    /** Apply a patch through the ENGINE's own merge-and-sanitise, then arm a preview. */
    fun patch(over: Map<String, TraceValue>) {
        scope.launch {
            patchLock.withLock {
                val base = params ?: return@withLock
                val next = try {
                    runtime.withOverrides(base, over)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    failure = "That setting could not be applied. " +
                        (t.message?.takeIf { it.isNotBlank() } ?: "No reason was reported.")
                    return@withLock
                }
                params = next
                notice = null
            }
            armPreview()
        }
    }

    /** Apply a style: the engine's own complete tree for that preset. */
    fun pickStyle(id: String) {
        scope.launch {
            patchLock.withLock {
                val base = params ?: return@withLock
                val name = tracePresetName(presets?.styles.orEmpty(), id)
                val next = try {
                    runtime.applyStyle(base, id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    failure = "That style could not be applied. " +
                        (t.message?.takeIf { it.isNotBlank() } ?: "No reason was reported.")
                    return@withLock
                }
                notice = traceOverwriteNotice("The “$name” style", base, next)
                params = next
                // A style REPLACES the settings, so it becomes the baseline too.
                baseline = next
                styleId = next.styleId.ifBlank { id }
            }
            armPreview()
        }
    }

    /** Apply a subject: the engine's own adjustment, on top of whatever is there. */
    fun pickSubject(id: String) {
        scope.launch {
            patchLock.withLock {
                val base = params ?: return@withLock
                val name = tracePresetName(presets?.subjects.orEmpty(), id)
                val next = try {
                    runtime.applySubject(base, id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    failure = "That adjustment could not be applied. " +
                        (t.message?.takeIf { it.isNotBlank() } ?: "No reason was reported.")
                    return@withLock
                }
                notice = traceOverwriteNotice("The “$name” adjustment", base, next)
                params = next
                subjectId = id
                subjectSeeded = false
            }
            armPreview()
        }
    }

    /**
     * Write the drawing on screen as ONE file and hand its Uri to the host.
     *
     * **THIS IS THE WHOLE OF THIS PANEL'S OUTPUT.** It writes into this app's own files directory and
     * calls [onAttach]. It does not upload, does not queue, does not touch a record and does not delete
     * the file afterwards — see [traceAttachFile] for why the host owns it from here.
     */
    fun attach() {
        val traced = result ?: return
        if (traced.isPreview) return
        attaching = true
        scope.launch {
            val uri = traceAttachFile(context, traced.svg, sourceName)
            attaching = false
            if (uri == null) {
                onError(TRACE_ATTACH_WRITE_FAILED)
                return@launch
            }
            onAttach(uri)
            // Recorded so the COLLAPSED card can say a file went to the host from here. An attachment
            // outlives a change of photograph, unlike the drawing that made it.
            memory.recordAttachment(
                traceExportFileName(sourceName, "svg", TRACE_ATTACH_SUFFIX)
            )
            onMessage("The line art has been added. The photograph is unchanged.")
        }
    }

    /* ── The surface ────────────────────────────────────────────────────────────────────────── */

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TracePanelDisclosureHeader(
            icon = Icons.Filled.Gesture,
            title = TRACE_CARD_TITLE,
            expanded = true,
            // Collapsing is ALWAYS allowed, even where the surface is read-only: it writes nothing, and
            // somebody who can see a card mid-trace must be able to put it away. Expanding keeps its
            // gate (see the collapsed half); this is the other direction.
            toggleEnabled = true,
            expandAction = TRACE_EXPAND_ACTION,
            collapseAction = TRACE_COLLAPSE_ACTION,
            onToggle = onClose,
        )

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Starting the tracing engine…", color = MaterialTheme.field.muted, fontSize = 12.sp)
            }
            return@Column
        }

        val current = params
        val tables = presets
        if (current == null || tables == null) {
            TracePanelNote(warning = true, text = failure ?: TRACE_ENGINE_SILENT_SENTENCE, polite = true)
            return@Column
        }

        /* ── Which part of the photograph ───────────────────────────────────────────────────── */

        TraceFramePanel(
            photograph = photograph,
            applied = frame,
            enabled = enabled && running == null,
            onApply = {
                frame = it
                // The drawing on screen came from the OLD frame, so a preview of the new one is what
                // somebody who just pressed that button is asking for. `armPreview` declines when
                // auto-preview is off or a full run is in flight, which are both the right answers.
                armPreview()
            },
        )

        /* ── What came back ─────────────────────────────────────────────────────────────────── */

        val traced = result
        val photographPlate = traced?.photographPlate
        val tracePlate = traced?.tracePlate
        /*
          AN ABSENCE IS A SENTENCE. Composing the comparator only when a result exists and putting
          NOTHING in its place is the same empty area this repository keeps having to distinguish from a
          place with no records. `traceComparisonStatus` holds the five answers and the reason each is
          worded as it is.
        */
        val comparisonStatus = traceComparisonStatus(
            hasPlates = photographPlate != null && tracePlate != null,
            running = running != null,
            failed = failure != null,
            plateRefusal = traced?.plateRefusal.orEmpty(),
            hasResult = traced != null,
        )
        // THE HEADING IS ALWAYS DRAWN, whether or not there is anything under it: a section that only
        // exists once it has content leaves somebody unable to tell "there is nothing here yet" from
        // "this feature is not on this screen".
        TracePanelLabel("The trace against the photograph")
        if (comparisonStatus.isNotEmpty()) {
            TracePanelNote(
                warning = traced?.plateRefusal?.isNotBlank() == true,
                text = comparisonStatus,
                polite = true,
            )
        }
        if (traced != null) {
            if (photographPlate != null && tracePlate != null) {
                TraceCompare(
                    photograph = photographPlate.asImageBitmap(),
                    trace = tracePlate.asImageBitmap(),
                    tracedWidth = traced.width,
                    tracedHeight = traced.height,
                    difference = difference?.asImageBitmap(),
                    differenceRefusal = differenceRefusal,
                    onDifferenceWanted = { buildDifference() },
                    enabled = enabled && running == null,
                )
            }
            TraceStatsRow(traced)
            if (traced.isPreview) {
                TracePanelNote(
                    warning = false,
                    // The numbers are in the sentence because "this is a preview" without them does not
                    // say how much coarser.
                    text = "This is a preview at ${traced.workingWidth}×${traced.workingHeight}, not " +
                        "the full ${traced.width}×${traced.height}. Press “Trace the sketch” for the " +
                        "drawing that gets added.",
                    polite = true,
                )
            }
            if (stale) {
                TracePanelNote(
                    warning = false,
                    text = "The settings have changed since this drawing was made.",
                    polite = true,
                )
            }
            if (traced.autoSubjectId.isNotBlank()) {
                TracePanelNote(
                    warning = false,
                    text = "The engine applied the “${tracePresetName(tables.subjects, traced.autoSubjectId)}” " +
                        "subject adjustment on its own.",
                )
            }
            TraceNotes(traced.notes)
        }

        /* ── Progress and cancellation ──────────────────────────────────────────────────────── */

        if (running != null) {
            TraceProgressRow(
                kind = running,
                progress = progress,
                weights = weights,
                stopping = stopping,
                enabled = enabled,
            ) {
                // Set BEFORE the cancel so the sentence is on screen while the engine finishes the
                // sub-step it is in. The `finally` above clears it when the runtime really has stopped,
                // so "Stopping…" lasts exactly as long as stopping takes rather than for a guessed
                // interval — which is the only version of this anybody can trust.
                stopping = true
                job?.cancel()
            }
        }

        failure?.let { TracePanelNote(warning = true, text = it, polite = true) }
        notice?.let { TracePanelNote(warning = false, text = it, polite = true) }

        val missing = remember(current) { traceMissingKeys(current) }
        if (missing.isNotEmpty()) {
            TracePanelNote(
                warning = true,
                text = "${missing.size} of this app's ${TRACE_PARAM_COUNT} settings were not offered " +
                    "by the tracing engine on this device and are not shown: ${missing.joinToString(", ")}. " +
                    "The trace still runs; this app and the engine are a version apart.",
            )
        }

        /* ── Presets ────────────────────────────────────────────────────────────────────────── */

        TraceStylePicker(
            styles = tables.styles,
            selectedId = styleId,
            enabled = enabled && running == null,
            onPick = { pickStyle(it) },
        )
        TraceSubjectPicker(
            subjects = tables.subjects,
            selectedId = subjectId,
            enabled = enabled && running == null,
            seededFromHost = subjectSeeded,
            onPick = { pickSubject(it) },
        )
        if (subjectSeeded) {
            // The seed is a PRE-SELECTION and nothing has been applied yet, which has to be said or the
            // control is claiming a state the tree is not in. One press applies it.
            OutlinedButton(
                onClick = { pickSubject(subjectId) },
                enabled = enabled && running == null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    "Adjust for ${tracePresetName(tables.subjects, subjectId).lowercase(Locale.ROOT)}",
                    fontSize = 13.sp,
                )
            }
        }
        traced?.let {
            TraceStyleSuggestion(
                styles = tables.styles,
                suggestedStyleId = it.suggestedStyleId,
                currentStyleId = styleId,
                enabled = enabled && running == null,
                onApply = { picked -> pickStyle(picked) },
            )
        }

        /* ── The controls ───────────────────────────────────────────────────────────────────── */

        val changedFromPreset = remember(baseline, current) {
            baseline?.let { traceChangedLabels(it, current) }.orEmpty().toSet()
        }
        /*
          WHICH TIERS ARE ACTUALLY IN FRONT OF SOMEBODY RIGHT NOW.

          PRIMARY always; ADVANCED while the disclosure is open; and EXPORT only while the export card is
          really composed, which it is not until a trace has finished. Without that third condition a
          style that moved `output.background` while the card WAS on screen would make the panel print
          "One setting that is not on screen has moved: White background" directly above the chips that
          were showing it.
        */
        val exportVisible = traced != null
        val visibleTiers = remember(advancedOpen, exportVisible) {
            setOfNotNull(
                TraceTier.PRIMARY,
                TraceTier.ADVANCED.takeIf { advancedOpen },
                TraceTier.EXPORT.takeIf { exportVisible },
            )
        }
        val hiddenChanged = remember(baseline, current, visibleTiers) {
            baseline?.let { traceChangedHiddenLabels(it, current, visibleTiers) }.orEmpty()
        }
        // What THIS press would reveal, which is a narrower question than "what is out of sight" — see
        // `traceChangedBehindDisclosure` for why the toggle must not count the export tier.
        val changedBehindDisclosure = remember(baseline, current) {
            baseline?.let { traceChangedBehindDisclosure(it, current) }.orEmpty()
        }

        TRACE_CONTROLS.filter { it.tier == TraceTier.PRIMARY }.forEach { control ->
            TraceControlRow(
                control = control,
                values = current,
                availability = availability,
                changed = control.label in changedFromPreset,
                enabled = enabled && running == null,
                onPatch = { patch(it) },
            )
        }

        TraceAdvancedSection(
            values = current,
            availability = availability,
            open = advancedOpen,
            changedFromPreset = changedFromPreset,
            hiddenChanged = hiddenChanged,
            changedBehindDisclosure = changedBehindDisclosure,
            // THE PRESS STAYS LIVE WHILE A TRACE RUNS and the rows inside it do not. Reading what a
            // style just did to a folded-away setting is exactly what somebody watching a twenty-second
            // trace wants to do, and it changes nothing; moving a slider mid-run would.
            enabled = enabled,
            rowsEnabled = enabled && running == null,
            onToggle = { advancedOpen = !advancedOpen },
            onPatch = { patch(it) },
        )

        /* ── Previews ───────────────────────────────────────────────────────────────────────── */

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = autoPreview,
                onCheckedChange = { autoPreview = it },
                enabled = enabled,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Re-trace a preview as I change settings", color = MaterialTheme.field.body, fontSize = 12.sp)
                Text(
                    if (resumed) {
                        "A small, fast trace after each pause. Turn it off to save battery."
                    } else {
                        "Paused while this screen is in the background."
                    },
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        OutlinedButton(
            onClick = { startRun(TraceRunKind.PREVIEW, 0L) },
            enabled = enabled && running == null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Update the preview", fontSize = 13.sp)
        }

        /* ── The two full-resolution buttons ────────────────────────────────────────────────── */

        val bar = traceCostRefusal(current, availability)
        bar?.let { TracePanelNote(warning = true, text = it) }
        if (availability.measuredOn == null) {
            // AN UNMEASURED CEILING IS A GUESS, AND IT SAYS SO. What has and has not been weighed is
            // written down where it is used.
            Text(
                "No timing has been measured on this model of phone yet, so the limits above are " +
                    "cautious estimates rather than readings.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = { startRun(TraceRunKind.ATTACH, 0L) },
                enabled = enabled && running == null && bar == null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Gesture, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Trace the sketch", fontSize = 13.sp)
            }
            Button(
                onClick = { attach() },
                // THE ONE GUARD THAT MATTERS: what is on screen must be the full-resolution drawing,
                // made from the settings that are on screen now. A preview or a stale result would hand
                // somebody a file that is not the one they approved.
                enabled = enabled && running == null && !attaching &&
                    traced != null && !traced.isPreview && !stale,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                /*
                  IT NAMES THE THING, NOT THE DESTINATION. "Attach as Line art" would name a field this
                  panel cannot see — the host owns where the file goes — and on a screen that may stack
                  more than one derivation card, two buttons naming one destination is somebody pressing
                  the wrong one and losing a file they cannot get back. The grammar is the portal's:
                  say what you are adding.
                */
                Text("Add the line art", fontSize = 13.sp)
            }
            /*
              DECLINING IS A FIRST-CLASS OUTCOME AND NOT A CANCEL. A threshold is a decision to discard
              everything on one side of it, and the person who can tell whether that mattered is the one
              with the actual sheet in front of them. It needs no explanation and nothing is written.
            */
            TextButton(onClick = onClose, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Keep the photograph as it is", fontSize = 12.sp)
            }
        }

        /*
          THE EXPORT STEP.

          `TraceExportCard` writes files to the phone — SVG, PNG, PDF, EPS and DXF — and it owns the
          "White background" chips that `TraceParams.kt` relocates to `TraceTier.EXPORT`. The card draws
          the chips and calls back; this host patches through the runtime's own `withOverrides` and
          re-traces, because `output.background` is a LEAF OF THE PARAMETER TREE read at the last
          pipeline stage and a second copy of that machinery inside the export card would be a second
          thing that can disagree about what the current parameters are.

          The background is read from `appliedParams` and not from what was sent, because auto-detection
          runs before the first stage and the card must describe the document that exists.
        */
        if (traced != null) {
            TraceExportCard(
                traceSvg = traced.svg,
                geometry = traced.geometry,
                documentWidth = traced.width,
                documentHeight = traced.height,
                documentBackground = traceDocumentBackground(current),
                sourceName = sourceName,
                shapeCount = traced.shapeCount,
                nodeCount = traced.nodeCount,
                frameNote = traced.frameNote,
                isPreview = traced.isPreview,
                exporter = exporter,
                // ONE BUSY FLAG ACROSS THE WHOLE SURFACE. See the file header.
                busy = running != null || attaching,
                onBusyChange = { attaching = it },
                onError = onError,
                onBackgroundChange = { white ->
                    val control = TRACE_TOGGLES.first { it.key == "output.background" }
                    patch(control.patch(white))
                },
                onNeedFullResolution = { startRun(TraceRunKind.ATTACH, 0L) },
            )
        }

        tracePanelReplaceWarning(currentFileName)?.let {
            TracePanelNote(warning = true, text = it)
        }
        Text(
            TRACE_ATTACH_SENTENCE,
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        /* ── The way out, at the point the work ends ────────────────────────────────────────── */

        /*
         * THE SECOND DOOR. This card has a frame chooser, a comparator, two dozen control rows, a
         * preview switch, three buttons and an export card between its header and here — so somebody
         * who has just traced a sheet and read the stats is a whole screen below the only other way
         * out. Same composable, same word.
         *
         * "Keep the photograph as it is" is NOT this and does not replace it: that is a first-class
         * OUTCOME — a decision that the trace is not good enough, made by the only person who can make
         * it — and it sits with the buttons it is an alternative to. This is a door.
         */
        TracePanelCollapseButton(prominent = true, title = TRACE_CARD_TITLE, onClick = onClose)
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * What this device will not do, and why
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The sentence barring a full-resolution run on this device, or null when there is nothing to bar.
 *
 * ── WHY THIS REFUSES RATHER THAN QUIETLY SUBSTITUTING ─────────────────────────────────────────
 *
 * The obvious fix for a slow edge engine on a phone is to swap it for a fast one. That is the worst
 * available option and the one the vendoring discipline exists to prevent: one sheet of paper would
 * then produce two different drawings depending on which client traced it, and the engine's whole
 * discipline — float32 quantisation, a 1e-4 parity tolerance, a cross-runtime fixture corpus — is that
 * it does not. So the handset refuses, NAMES THE REMEDY, and somebody chooses; both clients then agree
 * because they are running the same parameters.
 *
 * The numbers behind it: the flow engine was measured at 5.7x every alternative at the same size, with
 * 13,037 of a 16,655 ms trace inside one stage, which extrapolates to 67–117 s on a mid-range handset
 * against 12–20 s for the adaptive engine. [TraceAvailability] carries both ceilings and whether
 * anybody has actually measured them.
 */
internal fun traceCostRefusal(
    values: TraceValues,
    availability: TraceAvailability,
): String? {
    // No "can this phone trace at all" question is asked here, because there is not one to ask.
    // Everything this function refuses is about HOW BIG.
    val longEdge = values.number("preprocess.workingLongEdge")?.toInt() ?: return null
    if (longEdge > availability.maxWorkingLongEdge) {
        return "This phone has been measured up to ${availability.maxWorkingLongEdge} px and the trace " +
            "resolution is set to $longEdge px. Choose a lower resolution."
    }
    if (values.choice("edge.engine") == "FDOG" && longEdge > availability.fdogMaxWorkingLongEdge) {
        // THE REMEDY NAMES THE PRESS BY ITS CURRENT NAME, and takes that name from the constant both
        // clients share rather than from a copy of it here. A refusal that sends somebody to a control
        // the screen does not have is worse than one that names no control at all, because it reads as
        // the application describing a different version of itself.
        return "The Flow edge engine at $longEdge px is far slower than this phone can finish in a " +
            "reasonable time — it has been measured up to ${availability.fdogMaxWorkingLongEdge} px. " +
            "Either lower the trace resolution, or choose a different edge engine under " +
            "“$TRACE_DISCLOSURE_ACTION”. The portal will produce the same drawing from whichever " +
            "you choose."
    }
    return null
}

/**
 * "3.4 seconds" / "820 milliseconds", for a sentence rather than for a table.
 *
 * **`Locale.ROOT`, PINNED AT THE FORMATTER.** `app/build.gradle.kts` runs the unit tests as en_US
 * precisely so a locale bug in a formatter can fail a test, and its own comment forbids "fixing" such a
 * failure by changing the build file. A `%.1f` under a locale with a comma decimal separator would
 * write "3,4 seconds" on every handset set to one of those languages.
 */
internal fun traceSeconds(millis: Long): String =
    if (millis < 1000L) {
        "$millis milliseconds"
    } else {
        String.format(Locale.ROOT, "%.1f seconds", millis / 1000.0)
    }

/* ────────────────────────────────────────────────────────────────────────────
 * Progress
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The stage that is running, the bar, and the way out.
 *
 * **THE LABEL IS THE PRIMARY SIGNAL AND THE BAR IS SECONDARY**, which is the order the measurements
 * imply: two stages are most of the wall clock, so any bar will appear to stall. The label is the
 * engine's own string, rendered as sent.
 *
 * A PREVIEW SHOWS NO BAR AT ALL, because there is nothing to drive one with: the vendored worker calls
 * its preview entry point without a listener, so no stage events are emitted. A bar with no events
 * would sit at zero and read as a hang; a working line is the truth.
 */
@Composable
private fun TraceProgressRow(
    kind: TraceRunKind?,
    progress: TraceProgress?,
    weights: TraceProgressWeights,
    stopping: Boolean,
    enabled: Boolean,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val sentence = when {
            stopping -> "Stopping…"
            // THE ENGINE'S OWN LIST, NOT THE TWELVE-ROW TABLE. `TRACE_ENGINE_STAGES` is read from
            // `Stages.ALL` at run time and is nineteen long; passing the table instead would speak a
            // stage number that is wrong for five of the seven ids the two lists share.
            progress != null -> traceProgressSentence(progress, TRACE_ENGINE_STAGES)
            kind == TraceRunKind.PREVIEW -> "Tracing a preview…"
            else -> "Tracing…"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(sentence, color = MaterialTheme.field.body, fontSize = 12.sp)
        }

        if (kind != null && kind.isFullResolution && progress != null) {
            val fraction = weights.fractionAt(progress.stageId, progress.fraction)
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
            if (!weights.measured) {
                // The bar is a STAGE COUNT until this device has finished one trace, and a stage count
                // will visibly stall. Saying so costs one line and stops it reading as a hang.
                Text(
                    "The bar counts stages, not time, until this phone has finished one trace.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        TextButton(
            onClick = onCancel,
            enabled = enabled && !stopping,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            // "Stopping…" and not a button that vanishes. The engine checks cancellation between stages
            // and inside the long ones, so the worst case is one sub-step — seconds at full resolution.
            // A control that promised instant would be wrong, and a control that appeared to do nothing
            // for four seconds is worse.
            Text(if (stopping) "Stopping…" else "Stop", fontSize = 12.sp)
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * What the pipeline said, and what it produced
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * **EVERY SENTENCE THE PIPELINE PRODUCED, WITHOUT EXCEPTION.**
 *
 * `Pipeline.kt` calls showing them a REQUIREMENT: "a pipeline that silently discards 4 000 paths and
 * one that found nothing look identical on screen otherwise", which is the ambiguity this project takes
 * most seriously.
 *
 * No filtering, no de-duplication, no "only the important ones", and no truncation with a "show more".
 * The notes carry the matte's removed fraction and its alarm above 60%, the dropped-blob and
 * dropped-path counts, the "no paths were produced" remedy sentence, the downscale statement and the
 * outcome of perspective correction — and which of those matters is a judgement only the person looking
 * at the drawing can make.
 */
@Composable
private fun TraceNotes(notes: List<String>) {
    if (notes.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        notes.forEach { note ->
            Text(note, color = MaterialTheme.field.body, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

/** Paths, nodes and how long it took — the portal's own stats row. */
@Composable
private fun TraceStatsRow(result: TraceResult) {
    Text(
        "${result.shapeCount} paths · ${result.nodeCount} nodes · ${traceSeconds(result.totalMillis)}",
        color = MaterialTheme.field.muted,
        fontSize = 11.sp,
    )
}

/* ────────────────────────────────────────────────────────────────────────────
 * The one disclosure
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * **EVERYTHING THAT IS NOT ESSENTIAL, BEHIND ONE PRESS.**
 *
 * ── THE REPORT THIS ANSWERS ───────────────────────────────────────────────────────────────────
 *
 * "Selecting this functionality exposes all settings simultaneously, which can overwhelm the user." A
 * 6" screen makes that worse than a laptop does rather than better, because every row costs a scroll
 * and somebody looking for the one control they came for has to read past two dozen they did not. What
 * the panel opens with is the frame summary, the comparison, the style and subject presets, the six
 * controls that change the KIND of drawing that comes out ([TRACE_PRIMARY_KEYS]), the preview controls
 * and the two full-resolution buttons. Everything else is in here.
 *
 * ── THE FOUR PROPERTIES THAT MAKE THAT SAFE RATHER THAN MERELY TIDIER ─────────────────────────
 *
 *  1. **NOTHING BECOMES UNREACHABLE, BY CONSTRUCTION.** The rows above this section and the rows inside
 *     it are selected from ONE `tier` field by opposite tests, so the two halves are exhaustive and
 *     disjoint and a control added to [TRACE_CONTROLS] lands in one of them without anybody choosing.
 *     `TraceParamsTest` asserts exactly that sum, because the way a later tidy-up loses a control is not
 *     by deleting it — it is by leaving a gap between two lists that somebody maintains by hand.
 *
 *  2. **THE COUNT IS DERIVED, AND IT COUNTS ROWS RATHER THAN TABLE ENTRIES.** [traceAdvancedRevealed]
 *     drops a control whose leaf this device's engine copy did not send — the same set
 *     [traceMissingKeys] reports further up — so the toggle cannot promise a row the press does not
 *     produce. The portal's own button once read "Show all 32 controls" and revealed 25.
 *
 *  3. **WHAT IS HIDDEN CAN STILL ANNOUNCE ITSELF.** A folded-away control that no longer holds its
 *     preset's value says so ON THE TOGGLE — the press somebody is about to make — and names itself in
 *     a sentence underneath it. A setting quietly affecting the drawing from out of sight is the single
 *     defect class this panel is most written against.
 *
 *  4. **COLLAPSING DESTROYS NOTHING.** Every parameter lives in the open half's own `params`, which is
 *     the sanitised tree the runtime handed back; these rows only read it, and the toggle writes one
 *     Boolean.
 *
 * ── AND WHAT IS DELIBERATELY NOT IN HERE, WHERE THE PORTAL PUT IT ─────────────────────────────
 *
 * The portal's one disclosure swallowed its frame chooser and its download buttons too. Neither is in
 * this one. [TraceFramePanel] is ALREADY a single row with a summary line that is true whether it is
 * open or shut — it is the thing this section is being built to be, so nesting it would be a second
 * disclosure over a surface that has one, and unmounting it on collapse would throw away an aimed
 * rectangle for no gain. The export card is on the step that writes the file. See
 * [traceDisclosureBlurb], which is worded to claim neither of them.
 */
@Composable
private fun TraceAdvancedSection(
    values: TraceValues,
    availability: TraceAvailability,
    open: Boolean,
    changedFromPreset: Set<String>,
    /** Everything out of sight anywhere, for the sentence. Named, so somebody can go and look. */
    hiddenChanged: List<String>,
    /** Only what THIS press reveals, for the count on the toggle. The two are different questions. */
    changedBehindDisclosure: List<String>,
    enabled: Boolean,
    /** The rows are frozen mid-trace; the press is not. See the call site. */
    rowsEnabled: Boolean,
    onToggle: () -> Unit,
    onPatch: (Map<String, TraceValue>) -> Unit,
) {
    val groups = remember(values) { traceAdvancedGroups(values) }
    // ONE DERIVATION SITE FOR THE NUMBER, in the file that owns the table — not a second sum here that
    // could disagree with it after somebody changes what counts as a drawn row.
    val revealed = remember(values) { traceAdvancedRevealed(values) }

    // The second of the two animations on this surface, and the other thing reduced motion switches
    // off. See [TRACE_DISCLOSURE_TURN_MS] for why the height is not animated and the chevron is.
    val stillness = LocalAppPreferences.current.reducedMotion
    val turn by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(durationMillis = if (stillness) 0 else TRACE_DISCLOSURE_TURN_MS),
        label = "trace-advanced-chevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A BORDER AND A GROUND, so this reads as one section rather than as a button with some
            // loose rows under it. A press followed by unbounded content is not an accordion — nothing
            // on screen would say where the revealed settings stop and the preview controls begin.
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (revealed == 0) {
            /*
              AN EMPTY SECTION AND A SECTION THAT IS NOT THERE ARE DIFFERENT STATES, and so are an empty
              one and a broken one. This can only happen on a build whose engine is far enough apart to
              have dropped every advanced leaf, which is the same skew the missing-keys note above is
              already reporting in detail — but a toggle offering nought settings is a control that does
              nothing, and silently omitting the section would leave somebody who used it on the portal
              unable to tell it from a feature this application lacks.
            */
            TracePanelLabel("The other settings")
            Text(
                "None of this app's other $TRACE_ADVANCED_COUNT settings were offered by the " +
                    "tracing engine on this device, so there is nothing behind this section. The " +
                    "trace still runs on the settings above; this app and the engine are a version " +
                    "apart.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                /*
                  A REAL CONTROL, AND THREE SEPARATE THINGS A SCREEN READER IS OWED.

                  `mergeDescendants` makes the label, the count and the changed mark ONE announcement
                  instead of three stops on the way to the press. `stateDescription` says what the
                  section IS — "Expanded" / "Collapsed", the same two words every other disclosure in
                  this app uses. `onClickLabel` says what the press will DO, in the verb grammar TalkBack
                  speaks it in. And `Role.Button` is what stops it being announced as plain text with a
                  mysterious action on it.

                  The chevron carries none of that to somebody who cannot see it, which is the whole
                  reason all three are written out.
                */
                .semantics(mergeDescendants = true) {
                    stateDescription = traceDisclosureState(open)
                }
                .clickable(
                    enabled = enabled,
                    onClickLabel = traceDisclosureClickLabel(open, revealed),
                    role = Role.Button,
                ) { onToggle() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = turn },
            )
            Text(
                // Never assembled here. The words and the number both come from the table's own file —
                // see [TRACE_PARAM_COUNT] for the incident that rule exists to prevent, and
                // [TRACE_DISCLOSURE_ACTION] for why the phrase is not this client's to choose.
                traceDisclosureLabel(open, revealed, changedBehindDisclosure.size),
                color = MaterialTheme.field.body,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }

        if (!open) {
            Text(
                traceDisclosureBlurb(revealed),
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            // PROGRESSIVE DISCLOSURE IS ONLY HONEST IF WHAT IT HIDES CAN STILL ANNOUNCE ITSELF. The
            // toggle carries the COUNT because somebody who has learned to skip a paragraph still reads
            // the button they are about to press; this NAMES them, because a count on its own cannot be
            // acted on.
            traceHiddenChangedSentence(hiddenChanged)?.let {
                TracePanelNote(warning = false, text = it, polite = true)
            }
            return@Column
        }

        groups.forEach { (group, rows) ->
            // THE TABLE'S OWN HEADINGS, UNCHANGED. Somebody looks for a control by the pipeline stage it
            // belongs to, so the taxonomy inside the disclosure is the same one outside it.
            TracePanelLabel(group)
            rows.forEach { control ->
                TraceControlRow(
                    control = control,
                    values = values,
                    availability = availability,
                    changed = control.label in changedFromPreset,
                    enabled = rowsEnabled,
                    onPatch = onPatch,
                )
            }
        }
        TRACE_CUT.forEach { (key, why) ->
            // The cut list is DRAWN, not merely commented. Somebody looking for the thinning control
            // needs to find the answer where they looked for the control.
            Text(
                "“$key” is deliberately not offered here. $why",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * One control
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One row of the panel, whichever kind of control it is.
 *
 * ── A CONTROL WHOSE LEAF THE ENGINE DID NOT SEND IS NOT DRAWN AT ALL ──────────────────────────
 *
 * `read` returns null when the runtime's engine copy has no such leaf, and the row is skipped rather
 * than drawn at its minimum — see [TraceSlider.read] for why a plausible-looking zero is the worse
 * answer. The panel counts what it skipped with [traceMissingKeys] and says so, so a version skew shows
 * up as a sentence rather than as a control that silently stops existing.
 *
 * ── EVERY HINT IS ALWAYS-VISIBLE TEXT, NEVER A TOOLTIP ────────────────────────────────────────
 *
 * A phone has no hover, and these hints are the upstream's own words and **the only documentation
 * somebody offline for a fortnight has**. The portal renders them the same way, and this is one of the
 * few places where copying it exactly is the right answer rather than a starting point.
 */
@Composable
private fun TraceControlRow(
    control: TraceControl,
    values: TraceValues,
    availability: TraceAvailability,
    changed: Boolean,
    enabled: Boolean,
    onPatch: (Map<String, TraceValue>) -> Unit,
) {
    // Read out here, at the function's own level, so a leaf the engine did not send can `return` rather
    // than needing a labelled escape from inside a layout lambda.
    val inactive = traceInactiveReason(control, values)
    when (control) {
        is TraceSlider -> {
            val value = control.read(values) ?: return
            TraceSliderRow(control, value, changed, enabled, inactive, onPatch)
        }

        is TraceToggle -> {
            val on = control.read(values) ?: return
            TraceToggleRow(control, on, changed, enabled, inactive, onPatch)
        }

        is TraceChoice -> {
            val selected = control.read(values) ?: return
            TraceChoiceRow(control, selected, changed, enabled, inactive, onPatch)
        }

        is TraceNumberChoice -> {
            val value = control.read(values) ?: return
            TraceNumberChoiceRow(control, value, availability, changed, enabled, inactive, onPatch)
        }
    }
}

/**
 * A slider, with its value held locally while a thumb is on it.
 *
 * ── WHY THE VALUE IS NOT COMMITTED ON EVERY TICK ──────────────────────────────────────────────
 *
 * The portal patches on every change, and it can afford to: its sanitiser is a synchronous function in
 * the same page. Here every commit hops to a background dispatcher and back through a mutex, so
 * committing per pixel of drag would be hundreds of round trips for one adjustment, on a phone, on
 * battery. The local value drives the READOUT so the number under the thumb is still live; the commit
 * happens when the thumb lifts.
 *
 * ── AND WHY THE TRACK IS 0..1 RATHER THAN THE PARAMETER'S OWN RANGE ───────────────────────────
 *
 * Because three of these sliders are not linear ([TraceScale]). Driving Material's `Slider` in TRAVEL
 * space and converting through [valueAt] keeps one mapping in one place, and makes a square-law control
 * and a linear one the same code path rather than two.
 */
@Composable
private fun TraceSliderRow(
    control: TraceSlider,
    value: Double,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, TraceValue>) -> Unit,
) {
    // Re-seeded whenever the COMMITTED value changes, so a preset that moves this control moves the
    // thumb with it — `remember(value)` and not a bare `remember`.
    var travel by remember(value) { mutableFloatStateOf(control.fractionOf(value)) }
    val live = control.valueAt(travel)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TraceRowHeading(control.label, traceFormatValue(live, control.step), changed)
        Slider(
            value = travel,
            onValueChange = { travel = it },
            onValueChangeFinished = { onPatch(control.patch(control.valueAt(travel))) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    // The bare Slider announces a 0..1 travel figure, which is meaningless for a control
                    // whose value is "1000 pixels" or "0.35" — and doubly so on the three sliders whose
                    // travel is not linear in their value. The state description is the number the row
                    // is showing.
                    stateDescription = "${control.label}, ${traceFormatValue(live, control.step)}"
                },
            enabled = enabled,
            valueRange = 0f..1f,
        )
        TraceRowTail(control, inactive)
    }
}

/** A toggle: the switch and its label on one row, everything else underneath. */
@Composable
private fun TraceToggleRow(
    control: TraceToggle,
    on: Boolean,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, TraceValue>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(checked = on, onCheckedChange = { onPatch(control.patch(it)) }, enabled = enabled)
            Box(modifier = Modifier.weight(1f)) {
                TraceRowHeading(control.label, null, changed)
            }
        }
        TraceRowTail(control, inactive)
    }
}

/**
 * A one-of-many control, as chips.
 *
 * CHIPS AND NOT A DROPDOWN, because none of these lists reaches five options and this app's own
 * searchable select opens a sheet at eight — "below it there is nothing to search, and making somebody
 * cross a sheet and dismiss a keyboard to pick one of four is worse than the dropdown it replaced".
 * Every chip is a 40 dp target in a `FlowRow` that wraps rather than clipping at a 200% font scale.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TraceChoiceRow(
    control: TraceChoice,
    selected: String,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, TraceValue>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TraceRowHeading(control.label, null, changed)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            control.options.forEach { option ->
                TracePanelChip(
                    label = option.label,
                    selected = option.value == selected,
                    enabled = enabled,
                ) { onPatch(control.patch(option.value)) }
            }
        }
        TraceRowTail(control, inactive)
    }
}

/**
 * The trace resolution, as three named options with their cost in the row.
 *
 * An option this device has not been measured to survive is DRAWN AND DISABLED rather than hidden, so
 * somebody who used it on the portal can see both that it exists and that this phone will not do it —
 * which is a different thing from the option never having been there. The ceiling comes from the
 * runtime, which is the half that can measure it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TraceNumberChoiceRow(
    control: TraceNumberChoice,
    value: Double,
    availability: TraceAvailability,
    changed: Boolean,
    enabled: Boolean,
    inactive: String?,
    onPatch: (Map<String, TraceValue>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TraceRowHeading(control.label, null, changed)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            control.options.forEach { option ->
                val reachable = option.value <= availability.maxWorkingLongEdge.toDouble()
                TracePanelChip(
                    label = option.label,
                    selected = abs(option.value - value) < 0.5,
                    enabled = enabled && reachable,
                ) { onPatch(control.patch(option.value)) }
            }
        }
        control.options.firstOrNull { abs(it.value - value) < 0.5 }?.let {
            Text(it.note, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        TraceRowTail(control, inactive)
    }
}

/** The label, its numeric readout, and the mark saying a preset's value has been moved. */
@Composable
private fun TraceRowHeading(label: String, readout: String?, changed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // "Changed" is carried by the WORD and not only by a colour or a weight, so it survives
            // greyscale, colour blindness, direct sunlight on a village screen, and a screen reader that
            // never sees the styling at all.
            if (changed) "$label · changed" else label,
            color = MaterialTheme.field.body,
            fontSize = 12.sp,
            fontWeight = if (changed) FontWeight.SemiBold else FontWeight.Normal,
        )
        readout?.let {
            Text(it, color = MaterialTheme.field.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** The upstream's sentence, this client's sentence, and "this does nothing right now" if it does not. */
@Composable
private fun TraceRowTail(control: TraceControl, inactive: String?) {
    Text(control.hint, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 16.sp)
    control.handsetNote?.let {
        Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
    inactive?.let {
        // A SENTENCE, NOT A DISABLED ROW. Greying the control out would stop somebody setting a value
        // for the configuration they are about to switch to, and would say "you may not" where the truth
        // is "this is not being read". See [traceInactiveReason].
        Text(
            "Not used by the current settings. $it",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The open card when the form holds no photograph yet.
 *
 * WHY THERE IS A STATE HERE AT ALL rather than a card that hides or a header that will not open.
 * A tool or product form can be opened long before anything is photographed, and the tracer is worth
 * knowing about at that moment — it is part of deciding whether to photograph the sketch flat on a
 * table or propped against a wall. A card that appears only once an image exists is a card nobody
 * discovers, which is exactly how this was reported: "missing from the tools and products pages".
 *
 * AND WHY IT DOES NOT OFFER A PICKER. The form already has one, immediately above this card. A
 * second chooser here would be a second way in, with its own selection to keep in step with the
 * attachment list, and two entry points that can disagree about which photograph is "the" one. So
 * this names the control that exists rather than growing a rival to it. The web panel answers the
 * same state the same way, deliberately — see its `image` prop.
 */
@Composable
private fun TraceEmptyState(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TracePanelDisclosureHeader(
            icon = Icons.Filled.Gesture,
            title = TRACE_CARD_TITLE,
            expanded = true,
            toggleEnabled = enabled,
            expandAction = TRACE_EXPAND_ACTION,
            collapseAction = TRACE_COLLAPSE_ACTION,
            onToggle = onClose,
        )
        Text(
            "Add a photograph above and this will turn the pencil in it into vector line work — the " +
                "same drawing, as lines that print at any size without going blocky. The result is " +
                "added as a separate file; the photograph itself is never changed. It runs on this " +
                "device and needs no connection.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}
