package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.WorkshopPickerState
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.PageResponse
import com.fieldrepository.app.data.ProcessDetailDto
import com.fieldrepository.app.data.ProductDetailDto
import com.fieldrepository.app.data.ToolDetailDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/*
 * THE TWO-LEVEL RECORD PICKER THAT SITS ON TOP OF EVERY UPDATE SURFACE.
 *
 * A workshop dropdown, and under it the records OF that workshop, searchable. Pick one and it opens
 * for editing. The Kotlin twin of `frontend/components/forms/RecordSwitcher.tsx`, rule for rule and
 * — where a string is printed — word for word.
 *
 * ── WHY THE RULES ARE PURE FUNCTIONS AND NOT BRANCHES IN A COMPOSABLE ──────────────────────────
 *
 * `app/build.gradle.kts` carries no `ui-test-junit4` and no Robolectric, so the JVM suite cannot
 * compose a picker and look at it. `RecordPickersTest`, `AccessRosterTest` and `WorkshopOptionsTest`
 * all worked around that the same way, by lifting the ruling out of the composable, and the rulings
 * here need it more than most: three of the states below cannot be produced on a desk on purpose —
 * a workshop holding more than a hundred records of one type, a request that is in flight at the
 * instant somebody reads the screen, and a handset with no signal, which is the state this product
 * exists for and the state nobody has while they are testing it.
 *
 * ── WEB PARITY ────────────────────────────────────────────────────────────────────────────────
 *
 * Every function above `rememberRecordSwitcher` has a TypeScript twin in
 * `frontend/components/forms/RecordSwitcher.tsx`, asserted on both sides (`RecordSwitcherTest.kt`
 * here, `frontend/e2e/record-switcher-unit.spec.ts` there). The option LABELS are asserted
 * byte-for-byte: the two clients were already spelling them identically BY HAND — `RecordPickerScreen`
 * in `MainActivity.kt` builds the same five strings — and hand-spelled agreement is agreement right
 * up until somebody edits one of them.
 *
 * ── AND THE ONE THING THIS FILE DELIBERATELY DOES NOT DO ──────────────────────────────────────
 *
 * IT DECIDES NOTHING ABOUT PERMISSION. It hands `MainActivity` a record id to put in
 * `Screen.Edit(mode, recordId)` — the destination every other route into editing already uses, with
 * the same loads and the same refusals behind it — and the rows it offers come from `GET
 * /{collection}`, which every list route scopes with `viewable_where(current_user)` before it pages.
 * So the picker cannot reach a record the researcher could not already reach through Browse, My
 * Activity or Search. Narrowing a dropdown by workshop SUBTRACTS from what they see; it never adds.
 * A filter is a convenience and the gate is elsewhere. Do not "improve" this into loading a record
 * itself — that would turn a convenience into a second, looser way in.
 */

/**
 * The record types that HAVE an update surface, and the whole list of them.
 *
 * A strict subset of `MainActivity.EntryMode`, and the three that are missing are missing on purpose:
 *
 *  - **WORKSHOP.** A workshop IS the primary dropdown. Filtering workshops by workshop is not a
 *    narrower question, it is the same question twice, and the second dropdown would hold exactly
 *    the one row already chosen above it.
 *  - **MEDIA.** It has no edit form on either client — `EditScreen` routes it to `ViewDataDetail`
 *    and the web's search results open the object itself. There is nothing for a picker to open.
 *  - **QUESTIONNAIRE.** An interview is not identified by a name the way a record is: the existing
 *    picker has to GROUP interviews by artisan set and elect a representative before it can label
 *    one (`interviewGroupKey` / `representativeInterview`). Handing it this control would mean
 *    either a sixth copy of that grouping rule or a list of rows that are not what a researcher
 *    means by "an interview".
 *
 * The web declares the same five in the same order as `RECORD_KINDS`, and the two lists are asserted
 * equal.
 */
enum class RecordSwitchKind(
    /** Lower-case singular, as it reads mid-sentence and on a field label. Matches the web's. */
    val singular: String,
    /** Bare plural, for sentences about the ROWS rather than about the workshop. */
    val plural: String
) {
    ARTISAN("artisan", "artisans"),
    CRAFT("craft", "crafts"),
    PROCESS("process", "processes"),
    PRODUCT("product", "products"),
    TOOL("tool", "tools");

    /**
     * The plural the COUNTING sentences are built around, carrying "in this workshop".
     *
     * That phrase is load-bearing rather than decorative. [listCutNotice] prints this noun verbatim
     * into "Showing 100 of 240 …", and over a workshop-scoped list the bare plural would make that a
     * claim about the REPOSITORY: a reader who takes "240 artisans" as the corpus and then cannot
     * find one of them in the box has been told something false by the one sentence whose whole job
     * is to stop exactly that.
     */
    val noun: String get() = "$plural in this workshop"
}

/**
 * THE SEPARATOR BETWEEN A LABEL'S TWO HALVES: U+00B7 MIDDLE DOT, SPACED.
 *
 * Named rather than typed into five string templates, because it is the character the TypeScript
 * twin has to match and a hyphen or an en dash in one of ten templates is a difference no compiler
 * has an opinion about. It is also already what this app uses wherever a row names two things —
 * [workshopScopeLabel], `RecordPickerScreen` — so a reader who has met one option list recognises
 * the next.
 */
const val LABEL_SEPARATOR = " · "

/** `"Ram Kumar · Bagru"`, or just `"Ram Kumar"` when the second half is blank. */
private fun joined(head: String?, tail: String?): String {
    val left = head?.trim().orEmpty()
    val right = tail?.trim().orEmpty()
    return when {
        left.isEmpty() -> right
        right.isEmpty() -> left
        else -> "$left$LABEL_SEPARATOR$right"
    }
}

/**
 * One dropdown row's text, from the row's own DTO.
 *
 * Five overloads rather than one over a common interface, because these five `@Serializable` classes
 * share no supertype and inventing one would mean touching the wire models to serve a picker.
 * [recordOptionLabel] below is the shared tail every one of them goes through, so the "what if it is
 * all blank" rule is stated once.
 */
fun recordOptionLabel(record: ArtisanDto): String =
    recordOptionLabel(RecordSwitchKind.ARTISAN, joined(record.name, record.place))

fun recordOptionLabel(record: CraftDto): String =
    recordOptionLabel(RecordSwitchKind.CRAFT, joined(record.name, record.place))

fun recordOptionLabel(record: ProcessDetailDto): String =
    recordOptionLabel(RecordSwitchKind.PROCESS, joined(record.name, record.product?.productName))

fun recordOptionLabel(record: ProductDetailDto): String =
    recordOptionLabel(RecordSwitchKind.PRODUCT, joined(record.productName, record.artisanName))

fun recordOptionLabel(record: ToolDetailDto): String =
    recordOptionLabel(RecordSwitchKind.TOOL, joined(record.toolkitName, record.artisanName))

/**
 * The label, or a named fallback when every field it reads is blank.
 *
 * A row whose name is empty still has to be pickable — it is a real record and the researcher may
 * well be opening it BECAUSE its name is empty — so it names its type rather than rendering a blank
 * option the eye slides straight past. "Untitled artisan" is the same shape as the "Untitled
 * workshop" the workshop pickers already print, for the same reason.
 */
fun recordOptionLabel(kind: RecordSwitchKind, label: String): String =
    label.trim().ifBlank { "Untitled ${kind.singular}" }

/**
 * THE CONTROL'S OWN HEADING — "Open a different artisan".
 *
 * A function rather than a literal at the call site because the caller is the one that draws the card
 * around it (`RecordCard` on this client, the `<section>` on the web), and a heading typed at the
 * call site is a heading that says something slightly different on each surface within a release. It
 * is the same sentence the web prints above its two dropdowns, and both sides assert it.
 */
fun recordSwitcherTitle(kind: RecordSwitchKind): String = "Open a different ${kind.singular}"

/**
 * How far the loading of one workshop's records has got.
 *
 * Deliberately three values and not a boolean, and deliberately NOT collapsed into "do we have
 * rows". `RecordPickers.kt` carries the long-form argument next to `craftChangeClearsArtisan`: "no
 * records" is a claim about the repository, and a screen that makes it while the answer is in flight
 * — or after the answer failed to arrive — is stating as fact something it has no basis for. The
 * researcher's reasonable response to "no artisans are linked to this workshop" is to go and create
 * one, which is how a duplicate gets filed.
 */
enum class RecordListState { PENDING, LOADED, UNAVAILABLE }

/**
 * WHICH workshop the rows on screen belong to, compared with the one now selected.
 *
 * A separate question from [RecordListState] because the two come apart for a whole frame every time
 * the workshop changes: the previous workshop's rows are still in state, the new workshop's request
 * has been issued, and anything said about "this workshop" in that window is said about the wrong
 * one. Callers pass the workshop the LOADED rows came from, never the selected one, or this
 * degenerates into `state == LOADED` and stops being worth asking.
 */
fun recordsAreForWorkshop(loadedForWorkshop: String?, workshopId: String): Boolean =
    loadedForWorkshop != null && loadedForWorkshop == workshopId

/**
 * THE SENTENCE UNDER THE RECORD DROPDOWN, or null when the control speaks for itself.
 *
 * Ordered so the states that must never be mistaken for each other are tested first, and the one
 * that matters most is last: "there are no records here" is only ever said once the rows on screen
 * are KNOWN to be this workshop's and KNOWN to have arrived.
 *
 * ── WHAT IS DELIBERATELY NOT HERE: "NO MATCHES" ───────────────────────────────────────────────
 *
 * An empty SEARCH is not a state this function has an opinion about. The options list is never
 * narrowed by the query — [SearchableSelectField] filters it inside the sheet — so a `shown` counted
 * here could only ever be the unfiltered size, and a "no matches" arm hung off it would be a branch
 * no input could reach. The sheet already answers that question where the reader is looking while
 * they type: its `emptyMessage` and its "12 of 74 match" count line, which is read aloud on every
 * keystroke. The web says the same thing in the same place.
 *
 * Every string here is the web's, byte for byte, and both sides assert it.
 */
fun recordListMessage(
    kind: RecordSwitchKind,
    state: RecordListState,
    loadedForWorkshop: String?,
    workshopId: String,
    shown: Int
): String? = when {
    // Named as a LOADING failure and not as an empty workshop, and it says the open record is fine —
    // because the commonest way to reach this arm is a researcher in a courtyard with no signal, and
    // the question they are actually asking at that moment is whether their work is still there.
    state == RecordListState.UNAVAILABLE ->
        "These ${kind.plural} could not be loaded. The record open below is unaffected."
    // Covers both "the first request has not answered yet" and "the workshop changed a frame ago and
    // these are still the previous one's rows". Neither is a fact about the workshop now selected.
    state == RecordListState.PENDING || !recordsAreForWorkshop(loadedForWorkshop, workshopId) ->
        "Loading this workshop's records…"
    shown == 0 ->
        "No ${kind.noun} yet. Pick another workshop above, or file the first one."
    else -> null
}

/**
 * HOW LONG A KEYSTROKE WAITS BEFORE IT COSTS A REQUEST.
 *
 * 350ms, which is what every list page on the web already uses for its own live search
 * (`app/(protected)/products/page.tsx` and its four siblings all say `setTimeout(…, 350)`). Matching
 * them is not tidiness: a researcher uses the browser and the handset in one session, and a control
 * that felt slower or twitchier on one than on the other reads as the product being inconsistent
 * rather than as a different debounce. The web twin is `SEARCH_DEBOUNCE_MS` and the two are asserted
 * equal.
 */
const val SEARCH_DEBOUNCE_MS = 350L

/**
 * SHOULD A KEYSTROKE REACH THE SERVER AT ALL?
 *
 * ONLY when the list on screen is not the whole answer — and for a workshop-scoped list that is the
 * UNCOMMON case, which is the entire reason this is a predicate rather than an unconditional request
 * behind a debounce.
 *
 * The arithmetic: `GET /{collection}?workshopId=…` is clamped to 100 rows server-side
 * (`normalize_pagination`, `MAX_PAGE_SIZE`) and cannot be widened from any client. One WORKSHOP's
 * records are a small fraction of a table — the sibling deployment counted 878 products over 196
 * workshops — so the first page is, in practice, all of them, and every match anybody could type is
 * already on the device. Filtering those locally is instant, costs nothing, and — the half that
 * matters for this product — WORKS WITH NO SIGNAL. Firing a request per keystroke into that would be
 * pure loss: slower, offline-fragile, and unable to find anything the local filter could not.
 *
 * When the list IS cut the opposite holds absolutely: the rows past the cut are not on the device,
 * no amount of local filtering can reach them, and a search box that quietly searched only the first
 * hundred is the exact lie `listCutNotice` exists to end. Then, and only then, the debounced request
 * goes out.
 *
 * A blank query never searches the server either way — that is not a search, it is the list.
 */
fun shouldSearchServer(loaded: Int, total: Int, query: String): Boolean =
    total > loaded && query.isNotBlank()

/**
 * One page of one workshop's records, already reduced to what a picker needs.
 *
 * [total] is kept beside the options and is not an optimisation: it is the half that says whether
 * this list is the whole answer, and dropping it is how a cut list comes to render
 * indistinguishably from an empty workshop. See `RecordPickers.listCutNotice`.
 */
data class RecordSwitchPage(val options: List<SelectOption>, val total: Int)

/**
 * Ask the repository for one workshop's records of one kind.
 *
 * The `when` is the one place the five DTOs are turned into rows, so `RecordSwitcher` below has no
 * per-kind branch in it at all and the labels cannot diverge between the two things that draw them.
 */
suspend fun loadRecordSwitchPage(
    repository: FieldRepository,
    kind: RecordSwitchKind,
    workshopId: String,
    search: String? = null
): RecordSwitchPage = when (kind) {
    RecordSwitchKind.ARTISAN ->
        repository.artisansForWorkshopPage(workshopId, search).toSwitchPage { recordOptionLabel(it) }
    RecordSwitchKind.CRAFT ->
        repository.craftsForWorkshopPage(workshopId, search).toSwitchPage { recordOptionLabel(it) }
    RecordSwitchKind.PROCESS ->
        repository.processesForWorkshopPage(workshopId, search).toSwitchPage { recordOptionLabel(it) }
    RecordSwitchKind.PRODUCT ->
        repository.productsForWorkshopPage(workshopId, search).toSwitchPage { recordOptionLabel(it) }
    RecordSwitchKind.TOOL ->
        repository.toolsForWorkshopPage(workshopId, search).toSwitchPage { recordOptionLabel(it) }
}

/** The envelope reduced to rows, keeping `total`. [id] is read reflection-free, per DTO, by lambda. */
private inline fun <T> PageResponse<T>.toSwitchPage(label: (T) -> String): RecordSwitchPage =
    RecordSwitchPage(items.map { SelectOption(idOf(it), label(it)) }, total)

/**
 * The id of a row, for the five DTOs this file knows about.
 *
 * A `when` over types rather than an interface, for the reason [recordOptionLabel]'s overloads give:
 * these are wire models and adding a marker interface to them to serve a picker would put a UI
 * concern into the layer that has to match the server byte for byte. The `else` cannot be reached
 * from [loadRecordSwitchPage], which is the only caller, and throws rather than returning "" so that
 * a sixth kind added without a branch here fails loudly at its first use instead of rendering a list
 * of options that all share one blank id.
 */
private fun idOf(row: Any?): String = when (row) {
    is ArtisanDto -> row.id
    is CraftDto -> row.id
    is ProcessDetailDto -> row.id
    is ProductDetailDto -> row.id
    is ToolDetailDto -> row.id
    else -> throw IllegalArgumentException("RecordSwitcher cannot identify a ${row?.javaClass?.simpleName}")
}

/**
 * ADD ROWS WITHOUT EVER REMOVING ONE, first writer wins.
 *
 * The same rule as `mergeArtisansById` next door, over `SelectOption` instead of `ArtisanDto`, and it
 * is what makes a server search safe to run under a control that is ALSO filtering locally. The list
 * grows to the union of "this workshop's first page" and "everything matching what has been typed so
 * far"; the sheet then narrows that union by the same query, so a row that no longer matches is
 * hidden by the filter rather than torn out from under a finger mid-scroll. It also means the record
 * currently open cannot vanish from its own picker because somebody typed.
 */
fun mergeRecordOptions(previous: List<SelectOption>, incoming: List<SelectOption>): List<SelectOption> {
    if (incoming.isEmpty()) return previous
    val seen = previous.mapTo(HashSet()) { it.value }
    val added = incoming.filter { it.value !in seen }
    return if (added.isEmpty()) previous else previous + added
}

/**
 * The switcher's own state: which workshop, which rows, how far they got, and what has been typed.
 *
 * The WORKSHOP half is not here — it is [WorkshopPickerState], reused rather than re-implemented, for
 * the reason spelled out where its visibility was widened in `MainActivity.kt`. This object owns only
 * the record half.
 */
@Stable
class RecordSwitcherState internal constructor(
    private val repository: FieldRepository,
    private val kind: RecordSwitchKind
) {
    var options: List<SelectOption> by mutableStateOf(emptyList())
        private set

    var state: RecordListState by mutableStateOf(RecordListState.PENDING)
        private set

    /** The workshop the rows in [options] were loaded for; null before the first answer. */
    var loadedForWorkshop: String? by mutableStateOf(null)
        private set

    /** How many rows the server says this workshop holds of this kind, under no search. */
    var total: Int by mutableStateOf(0)
        private set

    /** What is in the sheet's filter box, mirrored out of it so the debounce can see it. */
    var query: String by mutableStateOf("")

    /** The cut sentence, or null when this list is whole. */
    val cutNotice: String?
        get() = if (state == RecordListState.LOADED) {
            listCutNotice(options.size, total, kind.noun, ListCutReach.SEARCH)
        } else {
            null
        }

    val message: String?
        get() = recordListMessage(kind, state, loadedForWorkshop, selectedWorkshopId, options.size)

    /** Set by the loader below so [message] can compare it with [loadedForWorkshop]. */
    internal var selectedWorkshopId: String by mutableStateOf("")

    /**
     * THE WORKSHOP'S OWN PAGE OF RECORDS.
     *
     * `options = emptyList()` on the way in is not tidying, it is the correctness line in this
     * function. [mergeRecordOptions] is additive on purpose — but rows belonging to workshop A must
     * never survive into workshop B's list, where they would be offered as that workshop's records
     * and would open under a filter that does not contain them. A workshop change is the one moment
     * this list is REPLACED rather than added to.
     */
    internal suspend fun loadWorkshop(workshopId: String) {
        selectedWorkshopId = workshopId
        state = RecordListState.PENDING
        options = emptyList()
        loadedForWorkshop = null
        total = 0
        if (workshopId.isBlank()) return
        runCatching { loadRecordSwitchPage(repository, kind, workshopId) }
            .onSuccess { page ->
                options = page.options
                total = page.total
                loadedForWorkshop = workshopId
                state = RecordListState.LOADED
            }
            .onFailure { failure ->
                // Rethrowing skips the state write, and must: the composition that cancelled this
                // load is gone, and a state object nobody reads has nothing to report to.
                if (failure is CancellationException) throw failure
                // Offline, a dead tunnel, a transient 5xx. It must NOT fall through to "no records in
                // this workshop" — a failed request is no evidence at all about the repository.
                state = RecordListState.UNAVAILABLE
            }
    }

    /**
     * THE ROWS PAST THE CUT, fetched by name. Merged, never substituted — see [mergeRecordOptions].
     *
     * A failure is silent ON PURPOSE. The local matches are still on screen and still correct; the
     * only thing that has not happened is a widening. Reporting an error here would tell a researcher
     * with no signal that something is broken, when what is true is that they already hold every row
     * this device has.
     */
    internal suspend fun searchServer(workshopId: String, term: String) {
        runCatching { loadRecordSwitchPage(repository, kind, workshopId, term) }
            .onSuccess { page ->
                // Guarded because the workshop may have changed while this was in flight, and these
                // rows belong to the workshop it was ASKED for.
                if (loadedForWorkshop == workshopId) options = mergeRecordOptions(options, page.options)
            }
            .onFailure { failure -> if (failure is CancellationException) throw failure }
    }
}

/**
 * Loads one workshop's records and keeps them in step with the workshop chosen above.
 *
 * [workshop] is the shared picker state — pass the SAME one the [RecordSwitcher] composable draws, so
 * that choosing a workshop and reloading the records are one event and not two that can disagree.
 */
// `internal` because it takes [WorkshopPickerState], which is internal for the reason given where
// its visibility was widened in `MainActivity.kt`. Nothing outside this module composes a switcher.
@Composable
internal fun rememberRecordSwitcher(
    repository: FieldRepository,
    kind: RecordSwitchKind,
    workshop: WorkshopPickerState
): RecordSwitcherState {
    val state = remember(repository, kind) { RecordSwitcherState(repository, kind) }

    LaunchedEffect(state, workshop.selectedId) { state.loadWorkshop(workshop.selectedId) }

    /**
     * THE DEBOUNCE — and note what it is guarding, which is not what a debounce usually guards.
     *
     * [shouldSearchServer] is asked FIRST, so for the ordinary case — a workshop whose records fit in
     * one page — no delay is ever awaited and no request is ever made, however fast anybody types.
     * The sheet's own filter is the complete answer there, it is instant, and it is the half of this
     * control that keeps working with no signal at all.
     *
     * Only when the list is genuinely cut does typing start costing requests, and then it costs one
     * per 350ms of quiet rather than one per keystroke: `LaunchedEffect` keyed on the query cancels
     * the previous coroutine at the `delay` on every further keystroke, which is the structured-
     * concurrency spelling of `clearTimeout`.
     */
    LaunchedEffect(state, workshop.selectedId, state.query, state.total, state.options.size) {
        if (!shouldSearchServer(state.options.size, state.total, state.query)) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        state.searchServer(workshop.selectedId, state.query.trim())
    }

    return state
}

/**
 * The control: the workshop above, its records below, and whatever the two have to say about
 * themselves.
 *
 * [onPick] receives the chosen record's id and nothing else. The caller turns that into
 * `Screen.Edit(mode, recordId)` — see the file header for why this composable does not go anywhere
 * near loading the record itself.
 *
 * [currentId] is the record the screen is editing right now, so the picker can show what it is
 * pointing at. It is compared against the OPTIONS rather than stored, which is what makes the control
 * self-correcting: picking sets no local state, so if the caller declines to navigate the dropdown
 * goes on showing the record that is actually open.
 */
// `internal`, for the same reason as [rememberRecordSwitcher] above.
@Composable
internal fun RecordSwitcher(
    kind: RecordSwitchKind,
    workshop: WorkshopPickerState,
    switcher: RecordSwitcherState,
    currentId: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The workshop the record being edited is linked to, when the caller knows it. Powers the one
     * affordance that makes the "most recent workshop" default liveable — see below. Null simply
     * withholds that shortcut; nothing else changes.
     */
    currentWorkshopId: String? = null
) {
    val workshopOptions = remember(workshop.workshops) {
        workshop.workshops.map { SelectOption(it.id, workshopScopeLabel(it)) }
    }
    val showsCurrentRecord = switcher.options.any { it.value == currentId }

    /**
     * "The one this record is filed under" — the escape hatch for the default.
     *
     * The primary dropdown defaults to the most recent workshop the account may submit to, which is
     * the right default for a researcher who has just come off a day in the field and is the
     * behaviour asked for. It is however NOT usually the workshop of the record already open, and
     * with no way back to that one the control would answer "which other record can I edit" while
     * quietly refusing to answer "which of this record's siblings". One button, only when there is
     * somewhere for it to go.
     */
    val canJumpToRecordWorkshop = currentWorkshopId != null &&
        currentWorkshopId != workshop.selectedId &&
        workshopOptions.any { it.value == currentWorkshopId }

    // NO HEADING IS DRAWN HERE. [recordSwitcherTitle] belongs to whatever card the caller puts this
    // in — `RecordCard` already renders a title in the app's display face, and a second one inside
    // the card would be the same sentence twice in two different sizes.
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SearchableSelectField(
            label = "Workshop",
            options = workshopOptions,
            selectedValue = workshop.selectedId,
            placeholder = if (workshopOptions.isEmpty()) "No workshops recorded yet" else "Select a workshop",
            // A workshop is what this control is FILTERED BY, so there is no "none" row: an absent
            // workshop cannot narrow a list, and the singular `workshopId` filter the five list
            // routes accept has no spelling for "unlinked" to send even if it did. See the report in
            // the switcher's own header for what that leaves unreachable from here.
            includeNone = false,
            onSelect = { workshop.selectedId = it }
        )

        if (canJumpToRecordWorkshop) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { workshop.selectedId = currentWorkshopId }) {
                    Text("Show this ${kind.singular}'s own workshop", fontSize = 12.sp)
                }
            }
        }

        SearchableSelectField(
            label = kind.singular.replaceFirstChar { it.uppercase() },
            options = switcher.options,
            selectedValue = if (showsCurrentRecord) currentId else "",
            placeholder = "Search ${kind.singular} records…",
            includeNone = false,
            enabled = workshop.selectedId.isNotBlank() && switcher.state != RecordListState.UNAVAILABLE,
            // FORCED ON rather than left to the eight-option threshold, which is the same call the
            // web's `ComboBox` makes for the same reason: a workshop with six records and a workshop
            // with sixty are one control, and one that is searchable on Tuesday and not on Wednesday
            // teaches the researcher not to trust the box. Forcing it is also what makes [onSearch]
            // meaningful — the anchored menu has no filter box to report from.
            searchable = true,
            onSearch = { switcher.query = it },
            onSelect = { picked -> if (picked.isNotBlank() && picked != currentId) onPick(picked) }
        )

        switcher.message?.let {
            Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp)
        }
        switcher.cutNotice?.let {
            Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp)
        }
    }
}
