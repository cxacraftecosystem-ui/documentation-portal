package com.fieldrepository.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.fieldrepository.app.data.CarryContext
import com.fieldrepository.app.data.CarryContextStore
import com.fieldrepository.app.data.CarryNode

/**
 * The glue between [CarryContextStore] and one create form: resolve the carried context once, hand
 * it to the form, and keep the banner above it telling the truth about what was filled in.
 *
 * The Kotlin twin of the web's `useCarryContext` hook in `components/forms/CarryContextBanner.tsx`
 * — same settle-once rule, same visibility check, same narrowing, so a phone and a laptop offer the
 * same prefill from the same stored bag.
 *
 * WHERE THIS IS MEANT TO BE CALLED. Each create form in `MainActivity.kt` calls
 * [rememberCarryPrefill] once, near its other `remember` state, and renders [CarryPrefillBanner] as
 * the first thing inside the form — above the workshop picker, so it is read before any of the
 * fields it filled in. The forms and what each of them should pass are listed on
 * [CarryPrefillDefaults].
 *
 * VISIBILITY AND STALENESS are one check, deliberately. Both "this artisan was deleted" and "this
 * artisan belongs to a workshop I lost access to" show up identically: the id is absent from the
 * list the form just loaded. Either way that record is dropped without a word — an error about a
 * record the researcher cannot see explains nothing and blocks a form they came here to fill in.
 * The rest of the bag survives: losing sight of one product is no reason to make them re-pick the
 * artisan too. When a list could not be loaded at all (offline, the normal state in Bagru) the
 * offer still stands, because suppressing the prefill exactly when the network is down would
 * disable the feature in the conditions it was written for.
 */

/** How a caller reports a list it loaded, because "not visible to me" and "no signal" differ. */
enum class CarryScopeState { PENDING, LOADED, UNAVAILABLE }

/**
 * One list the form has loaded, and therefore one record type it can vouch for. A carried record
 * absent from a loaded list was deleted or sits behind access the researcher has lost; either way it
 * is dropped rather than offered, along with everything that hung off it.
 */
data class CarryScope(val node: CarryNode, val state: CarryScopeState, val ids: List<String>)

/** `carryScope(CarryNode.ARTISAN, state, artisans) { it.id }` — the shape every call site has. */
fun <T> carryScope(node: CarryNode, state: CarryScopeState, rows: List<T>, id: (T) -> String): CarryScope =
    CarryScope(node, state, rows.map(id))

/** What was offered, where it came from, and how old it is — everything the banner needs. */
data class CarryOffer(
    val context: CarryContext,
    val source: CarryOfferSource,
    val savedAt: Long,
    val origin: CarryNode?
)

@Stable
class CarryPrefillState internal constructor(
    private val store: CarryContextStore,
    private val userId: String?
) {
    /** The offer that was actually applied to the form, or null while nothing has been. */
    var offer by mutableStateOf<CarryOffer?>(null)
        internal set

    /** The apply must happen once and never again: the researcher may have edited the fields since. */
    internal var settled = false

    /** Clear the prefill and forget the context. Wired to "Change". */
    fun change() {
        offer = null
        store.clear(userId)
    }

    /**
     * Retire the banner while keeping the stored context — for the one case that is not a dismissal:
     * the researcher has replaced the selection with their own, so nothing on screen is a suggestion
     * any more and the banner would be describing a value that is no longer there.
     */
    fun retire() {
        offer = null
    }

    /**
     * Forget one carried record once the form learns it cannot be used — the list that would have
     * proved it reachable only arrives after the prefill (a process form cannot ask for an artisan's
     * products until it has the artisan). Its children go with it, and the banner stops claiming it.
     */
    fun prune(node: CarryNode) {
        store.prune(userId, node)
        val current = offer ?: return
        if (!current.context.mentions(node)) return
        val pruned = current.context.pruned(node)
        offer = if (pruned.isEmpty) {
            null
        } else {
            current.copy(
                context = pruned,
                origin = current.origin?.takeIf { pruned.mentions(it) } ?: pruned.narrowestNode()
            )
        }
    }

    /**
     * Record the context the researcher is now in. Call on an explicit pick and after a save.
     * An explicit pick also retires the banner: the value stopped being a suggestion.
     */
    fun remember(patch: CarryContext, explicit: Boolean = false) {
        store.remember(userId, patch)
        if (explicit) offer = null
    }
}

/**
 * Resolve a carried context for one create form and hand it to the caller once.
 *
 * @param handoff the context a tap straight off a save screen carried in memory — the freshest
 *   possible, so it is banked immediately and wins over anything stored.
 * @param applies the record types this form has fields for. Everything else in the bag is left in
 *   storage untouched but is neither applied nor named in the banner: a product form does not fill
 *   in a process, so it must not claim to have.
 */
@Composable
fun rememberCarryPrefill(
    store: CarryContextStore,
    userId: String?,
    enabled: Boolean = true,
    handoff: CarryContext? = null,
    applies: Set<CarryNode> = CarryNode.entries.toSet(),
    scopes: List<CarryScope> = emptyList(),
    onApply: (CarryContext) -> Unit
): CarryPrefillState {
    val state = remember(store, userId) { CarryPrefillState(store, userId) }
    // The caller passes a fresh lambda and a fresh list every recomposition; the effect must fire on
    // a list resolving, not on the parent redrawing.
    val apply by rememberUpdatedState(onApply)
    val liveScopes by rememberUpdatedState(scopes)
    val signature = scopes.joinToString("|") { "${it.node}:${it.state}:${it.ids.size}" }

    LaunchedEffect(enabled, userId, signature) {
        if (!enabled || state.settled || userId.isNullOrBlank()) return@LaunchedEffect

        val fromHandoff = handoff?.sanitized()?.takeIf { !it.isEmpty }
        val stored = if (fromHandoff != null) null else store.read(userId)
        var context = fromHandoff ?: stored?.context ?: run {
            state.settled = true
            return@LaunchedEffect
        }

        // Wait for the form to know whether it can see these records — but only while there is still
        // an answer coming for something the context actually names.
        if (liveScopes.any { it.state == CarryScopeState.PENDING && context.mentions(it.node) }) return@LaunchedEffect

        val unreachable = liveScopes.filter { scope ->
            scope.state == CarryScopeState.LOADED && context.idOf(scope.node)?.let { it !in scope.ids } == true
        }
        unreachable.forEach { context = context.pruned(it.node) }

        state.settled = true
        // Storage keeps the whole bag; only the offer is narrowed to what this form can fill in.
        if (fromHandoff != null) store.remember(userId, context) else unreachable.forEach { store.prune(userId, it.node) }
        val offered = context.selecting(applies)
        if (offered.isEmpty) {
            if (context.isEmpty) store.clear(userId)
            return@LaunchedEffect
        }

        apply(offered)
        state.offer = CarryOffer(
            context = offered,
            source = if (fromHandoff != null) CarryOfferSource.HANDOFF else CarryOfferSource.RESUMED,
            savedAt = stored?.savedAt ?: System.currentTimeMillis(),
            origin = stored?.originNode?.takeIf { offered.mentions(it) } ?: offered.narrowestNode()
        )
    }

    return state
}

/** The banner for a [CarryPrefillState], or nothing at all when no prefill happened. */
@Composable
fun CarryPrefillBanner(state: CarryPrefillState, onChange: () -> Unit, modifier: Modifier = Modifier) {
    val offer = state.offer ?: return
    CarryContextBanner(
        context = offer.context,
        source = offer.source,
        savedAt = offer.savedAt,
        onChange = onChange,
        modifier = modifier,
        origin = offer.origin
    )
}

/**
 * What each create form passes, kept in one place so the web and the app cannot drift.
 *
 * These mirror the `applies` lists on the web forms exactly: a form may only prefill — and may only
 * claim to have prefilled — the record types it actually has fields for.
 *
 *  - **ArtisanForm** (`MainActivity.kt`, the `EntryMode.ARTISAN` branch): [ARTISAN_FORM]. The artisan
 *    in the bag never carries into a form whose whole job is to create a new one, and neither does
 *    their place — it belongs to that artisan, not to the sitting.
 *  - **ProductForm** (`EntryMode.PRODUCT`): [PRODUCT_FORM].
 *  - **ToolForm** (`EntryMode.TOOL`): [TOOL_FORM].
 *  - **ProcessForm** (`EntryMode.PROCESS`): [PROCESS_FORM]. The product is the deferred half: its
 *    dropdown is fetched per artisan, so it is applied when that list lands and
 *    [CarryPrefillState.prune] is called with [CarryNode.PRODUCT] if it is not in it.
 *  - **QuestionnaireForm** (`EntryMode.QUESTIONNAIRE`): [QUESTIONNAIRE_FORM].
 */
object CarryPrefillDefaults {
    val ARTISAN_FORM = setOf(CarryNode.CRAFT, CarryNode.WORKSHOP)
    val PRODUCT_FORM = setOf(CarryNode.CRAFT, CarryNode.ARTISAN, CarryNode.WORKSHOP)
    val TOOL_FORM = setOf(CarryNode.CRAFT, CarryNode.ARTISAN, CarryNode.WORKSHOP)
    val PROCESS_FORM = setOf(CarryNode.ARTISAN, CarryNode.PRODUCT, CarryNode.WORKSHOP)
    val QUESTIONNAIRE_FORM = setOf(CarryNode.ARTISAN, CarryNode.WORKSHOP)
}
