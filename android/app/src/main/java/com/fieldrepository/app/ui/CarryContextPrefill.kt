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

enum class CarryScopeState { PENDING, LOADED, UNAVAILABLE }

data class CarryScope(val node: CarryNode, val state: CarryScopeState, val ids: List<String>)

fun <T> carryScope(node: CarryNode, state: CarryScopeState, rows: List<T>, id: (T) -> String): CarryScope =
    CarryScope(node, state, rows.map(id))

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
    var offer by mutableStateOf<CarryOffer?>(null)
        internal set

    internal var settled = false

    fun change() {
        offer = null
        store.clear(userId)
    }

    fun retire() {
        offer = null
    }

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

    fun remember(patch: CarryContext, explicit: Boolean = false) {
        store.remember(userId, patch)
        if (explicit) offer = null
    }
}

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

        if (liveScopes.any { it.state == CarryScopeState.PENDING && context.mentions(it.node) }) return@LaunchedEffect

        val unreachable = liveScopes.filter { scope ->
            scope.state == CarryScopeState.LOADED && context.idOf(scope.node)?.let { it !in scope.ids } == true
        }
        unreachable.forEach { context = context.pruned(it.node) }

        state.settled = true
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

object CarryPrefillDefaults {
    val ARTISAN_FORM = setOf(CarryNode.CRAFT, CarryNode.WORKSHOP)
    val PRODUCT_FORM = setOf(CarryNode.CRAFT, CarryNode.ARTISAN, CarryNode.WORKSHOP)
    val TOOL_FORM = setOf(CarryNode.CRAFT, CarryNode.ARTISAN, CarryNode.WORKSHOP)
    val PROCESS_FORM = setOf(CarryNode.ARTISAN, CarryNode.PRODUCT, CarryNode.WORKSHOP)
    val QUESTIONNAIRE_FORM = setOf(CarryNode.ARTISAN, CarryNode.WORKSHOP)
}
