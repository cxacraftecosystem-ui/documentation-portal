package com.fieldrepository.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The records a researcher is currently working with — artisan, craft, workshop, product, tool,
 * process — remembered across app restarts and offered to the next form they open.
 *
 * The Kotlin twin of the web's `frontend/lib/carryContext.ts` — same fields, same ladder, same
 * twelve-hour window, same wording out of [describeCarryAge], [describeCarryDetail] and
 * [describeCarryImplied], so the banner reads identically on a phone and on a laptop.
 *
 * WHAT PROBLEM THIS SOLVES. `CarryForwardPanel` already offers the just-saved artisan as follow-up
 * shortcuts on the dashboard, but only until something else happens: the panel is in-memory state
 * that dies on dismissal, on navigating elsewhere, and on the process being killed — which Android
 * does routinely to a backgrounded app on the cheap phones these are. Come back to the app at the
 * next sitting and every field is typed again — including the PRODUCT, re-picked out of a dropdown
 * for the process that documents how it was made.
 *
 * WHERE IT LIVES. SharedPreferences, alongside [TokenStore]: synchronous, so the form can be
 * prefilled in the same composition rather than a frame later, and entirely local, so it works with
 * no connection — which is the normal state in Bagru, Akola and Jammu.
 *
 * PER USER, ALWAYS. A field phone gets handed between researchers. Every row is keyed by user id
 * AND repeats it inside the payload, so a context written under one account can never surface under
 * another.
 *
 * WHAT IT MAY HOLD. One id and one display name per record type, plus the artisan's place — the
 * things that describe the SITTING. Aadhaar and Pehchan numbers are regulated PII: they must never
 * be copied between records, still less parked in shared-preferences on a shared handset, and there
 * is deliberately no field here they could occupy.
 *
 * ── PRECEDENCE ────────────────────────────────────────────────────────────────────────────────
 * The bag accumulates, so it can hold a product AND a tool AND the artisan both belong to, and the
 * whole point is that they stay mutually consistent. Records are not a flat set: a product belongs
 * to an artisan, who belongs to a craft; a process belongs to a product. [CarryNode.parent] states
 * that ladder once and [mergeCarryContext] enforces three rules over it. Getting this wrong does
 * not degrade a convenience, it attaches a product to an artisan who never made it, which is a
 * falsified row in a research corpus.
 */

/**
 * Every record type that can be in context, ANCESTOR-FIRST.
 *
 * Declaration order is load-bearing: [mergeCarryContext] walks `entries` top-down so a contradicted
 * parent has already dropped its children by the time those children are considered.
 */
@Serializable
enum class CarryNode {
    WORKSHOP, CRAFT, ARTISAN, PRODUCT, TOOL, PROCESS;

    /**
     * Who owns whom. The workshop is deliberately a root rather than anybody's parent: it is the
     * sitting, not a link in the chain, and it outlives an artisan switch because the researcher is
     * still standing in the same courtyard.
     */
    val parent: CarryNode?
        get() = when (this) {
            WORKSHOP, CRAFT -> null
            ARTISAN -> CRAFT
            PRODUCT, TOOL -> ARTISAN
            PROCESS -> PRODUCT
        }

    /** Everything that hangs off this record, however deep — what a contradiction takes with it. */
    val descendants: List<CarryNode>
        get() = entries.filter { candidate ->
            generateSequence(candidate.parent) { it.parent }.any { it == this }
        }

    /** How the record type is named to the researcher, in the banner and the provenance line. */
    val label: String get() = name.lowercase()
}

@Serializable
data class CarryContext(
    val workshopId: String? = null,
    val workshopName: String? = null,
    val craftId: String? = null,
    val craftName: String? = null,
    val artisanId: String? = null,
    val artisanName: String? = null,
    val place: String? = null,
    val productId: String? = null,
    val productName: String? = null,
    val toolId: String? = null,
    val toolName: String? = null,
    val processId: String? = null,
    val processName: String? = null
) {
    /**
     * True when no record is named at all. A bare workshop is not context: the workshop picker
     * already defaults to the most recent one, so offering it back would be a banner about nothing.
     */
    val isEmpty: Boolean
        get() = CarryNode.entries.none { it != CarryNode.WORKSHOP && mentions(it) }

    /** Drop blanks so `""` from a cleared field never counts as a remembered value. */
    fun sanitized(): CarryContext = CarryContext(
        workshopId = workshopId.clean(),
        workshopName = workshopName.clean(),
        craftId = craftId.clean(),
        craftName = craftName.clean(),
        artisanId = artisanId.clean(),
        artisanName = artisanName.clean(),
        place = place.clean(),
        productId = productId.clean(),
        productName = productName.clean(),
        toolId = toolId.clean(),
        toolName = toolName.clean(),
        processId = processId.clean(),
        processName = processName.clean()
    )

    fun idOf(node: CarryNode): String? = when (node) {
        CarryNode.WORKSHOP -> workshopId
        CarryNode.CRAFT -> craftId
        CarryNode.ARTISAN -> artisanId
        CarryNode.PRODUCT -> productId
        CarryNode.TOOL -> toolId
        CarryNode.PROCESS -> processId
    }

    fun nameOf(node: CarryNode): String? = when (node) {
        CarryNode.WORKSHOP -> workshopName
        CarryNode.CRAFT -> craftName
        CarryNode.ARTISAN -> artisanName
        CarryNode.PRODUCT -> productName
        CarryNode.TOOL -> toolName
        CarryNode.PROCESS -> processName
    }

    /** True when this record is named at all — by id or, for an unlinked record, by name alone. */
    fun mentions(node: CarryNode): Boolean =
        idOf(node) != null || nameOf(node) != null || (node == CarryNode.ARTISAN && place != null)

    /** True when the patch describes this record but does not link it. */
    internal fun namedWithoutLink(node: CarryNode): Boolean = idOf(node) == null && mentions(node)

    /** This record blanked out, the rest of the bag untouched. */
    fun without(node: CarryNode): CarryContext = when (node) {
        CarryNode.WORKSHOP -> copy(workshopId = null, workshopName = null)
        CarryNode.CRAFT -> copy(craftId = null, craftName = null)
        CarryNode.ARTISAN -> copy(artisanId = null, artisanName = null, place = null)
        CarryNode.PRODUCT -> copy(productId = null, productName = null)
        CarryNode.TOOL -> copy(toolId = null, toolName = null)
        CarryNode.PROCESS -> copy(processId = null, processName = null)
    }

    internal fun withId(node: CarryNode, id: String?): CarryContext = when (node) {
        CarryNode.WORKSHOP -> copy(workshopId = id)
        CarryNode.CRAFT -> copy(craftId = id)
        CarryNode.ARTISAN -> copy(artisanId = id)
        CarryNode.PRODUCT -> copy(productId = id)
        CarryNode.TOOL -> copy(toolId = id)
        CarryNode.PROCESS -> copy(processId = id)
    }

    /** Everything the incoming patch states, laid over what is already here. */
    internal fun layeredWith(incoming: CarryContext): CarryContext = CarryContext(
        workshopId = incoming.workshopId ?: workshopId,
        workshopName = incoming.workshopName ?: workshopName,
        craftId = incoming.craftId ?: craftId,
        craftName = incoming.craftName ?: craftName,
        artisanId = incoming.artisanId ?: artisanId,
        artisanName = incoming.artisanName ?: artisanName,
        place = incoming.place ?: place,
        productId = incoming.productId ?: productId,
        productName = incoming.productName ?: productName,
        toolId = incoming.toolId ?: toolId,
        toolName = incoming.toolName ?: toolName,
        processId = incoming.processId ?: processId,
        processName = incoming.processName ?: processName
    )

    /**
     * Drop this record and everything that hangs off it. Used when the screen that would have
     * prefilled it finds the researcher can no longer reach it.
     */
    fun pruned(node: CarryNode): CarryContext =
        (listOf(node) + node.descendants).fold(this) { context, dead -> context.without(dead) }

    /**
     * Keep only the record types a given form actually uses.
     *
     * Unlike [pruned] this does NOT cascade: it is a view of the bag, not an edit to it. A product
     * form has no process field, so the process is not shown and not applied there, but it stays in
     * storage for the process form that does. Narrowing is what keeps the banner honest — it may
     * only name what was really put into the fields in front of the researcher.
     */
    fun selecting(nodes: Set<CarryNode>): CarryContext =
        CarryNode.entries.filterNot { it in nodes }.fold(this) { context, dropped -> context.without(dropped) }

    /**
     * The narrowest record named here — the product rather than its artisan, the process rather
     * than its product. Read in reverse ladder order, so the deepest match wins and ties are
     * decided the same way every time.
     */
    fun narrowestNode(): CarryNode? = CarryNode.entries.lastOrNull { mentions(it) }
}

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Fold a patch into the context already held.
 *
 * THREE RULES, in this order, over the [CarryNode.parent] ladder:
 *
 *  1. **A different record replaces its own subtree.** Naming product P when the bag holds product Q
 *     drops Q and Q's process — that process documented Q, and re-pointing it at P would invent a
 *     fact. Naming a different artisan drops their products, tools and place the same way.
 *
 *  2. **A name without a link is also a contradiction.** A screen that reports `artisanName =
 *     "Shyam"` with no `artisanId` has an unlinked record, so the artisan id in the bag is no longer
 *     what the name refers to. Keeping it would silently attach the next product to the previous
 *     artisan — the exact failure this file exists to prevent — so the id goes and the name stays.
 *
 *  3. **A parent the patch did not name cannot vouch for a child it never had.** Once anything in
 *     the chain has been contradicted, every ancestor above the patch's own subject is walked up and
 *     dropped until one the patch actually mentions is reached. A craft left behind from the previous
 *     artisan is a stale parent, and a stale parent is how a product ends up filed under the wrong
 *     craft.
 *
 * Everything NOT under a contradicted record survives: that is what makes this a bag rather than a
 * pointer. Documenting a tool and then a product for the same artisan leaves both in context.
 */
fun mergeCarryContext(previous: CarryContext?, patch: CarryContext): CarryContext {
    val incoming = patch.sanitized()
    var merged = previous?.sanitized() ?: CarryContext()
    var contradicted = false

    for (node in CarryNode.entries) {
        val incomingId = incoming.idOf(node)
        val changed = if (incomingId != null) {
            incomingId != merged.idOf(node)
        } else {
            incoming.namedWithoutLink(node) && merged.idOf(node) != null
        }
        if (!changed) continue
        merged = (listOf(node) + node.descendants).fold(merged) { context, stale -> context.without(stale) }
        merged = merged.withId(node, incomingId)
        contradicted = true
    }

    merged = merged.layeredWith(incoming)

    if (contradicted) {
        // "Mentions" is any field, not just the id: a patch that gives a craft NAME has told us
        // which craft it means, even where the record it came from carries no craft link.
        var parent = incoming.narrowestNode()?.parent
        while (parent != null) {
            if (incoming.mentions(parent)) break
            merged = merged.without(parent)
            parent = parent.parent
        }
    }

    return merged
}

/** A stored context plus the bookkeeping that decides whether it is still worth offering. */
@Serializable
data class StoredCarryContext(
    val context: CarryContext,
    /** Epoch ms of the last write: drives both the expiry and the "20 minutes ago" line. */
    val savedAt: Long,
    /** Repeated inside the row so a context can never be read back under the wrong account. */
    val userId: String,
    /**
     * The record the last write was ABOUT — the narrowest thing named in that patch. It is what lets
     * the banner say "from the PRODUCT you saved" rather than guessing, and what makes the implied
     * parents ("its artisan and craft came with it") nameable.
     */
    val originNode: CarryNode? = null
)

class CarryContextStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun read(userId: String?, now: Long = System.currentTimeMillis()): StoredCarryContext? {
        if (userId.isNullOrBlank()) return null
        val raw = preferences.getString(key(userId), null) ?: return null
        val row = runCatching { json.decodeFromString(StoredCarryContext.serializer(), raw) }.getOrNull()
        // Somebody else's row, an expired one, or a row an older build wrote in another shape: all
        // discarded on sight rather than left for the next reader to re-check.
        if (row == null || row.userId != userId || now - row.savedAt > TTL_MS) {
            clear(userId)
            return null
        }
        val sanitized = row.context.sanitized()
        if (sanitized.isEmpty) {
            clear(userId)
            return null
        }
        // Rows written before the bag grew past the artisan have no origin; the narrowest record
        // they do name is exactly what the write was about, so nothing needs migrating.
        return row.copy(context = sanitized, originNode = row.originNode ?: sanitized.narrowestNode())
    }

    /**
     * Record the context the researcher is working in. The patch is a claim about ONE record and its
     * chain of parents; [mergeCarryContext] decides what of the previous context can survive it.
     */
    fun remember(userId: String?, patch: CarryContext, now: Long = System.currentTimeMillis()) {
        if (userId.isNullOrBlank()) return
        val previous = read(userId, now)
        val merged = mergeCarryContext(previous?.context, patch)
        if (merged.isEmpty) return
        val origin = patch.sanitized().narrowestNode() ?: previous?.originNode ?: merged.narrowestNode()
        write(userId, StoredCarryContext(context = merged, savedAt = now, userId = userId, originNode = origin))
    }

    /**
     * Forget one record — used when the screen that would have prefilled it discovers the researcher
     * can no longer reach it (deleted, or in a workshop whose access lapsed). Its children go with
     * it: they were only ever reachable through it.
     */
    fun prune(userId: String?, node: CarryNode, now: Long = System.currentTimeMillis()) {
        if (userId.isNullOrBlank()) return
        val stored = read(userId, now) ?: return
        if (!stored.context.mentions(node)) return
        val pruned = stored.context.pruned(node)
        if (pruned.isEmpty) {
            clear(userId)
            return
        }
        val origin = stored.originNode?.takeIf { pruned.mentions(it) } ?: pruned.narrowestNode()
        // The saved-at stamp is left alone: pruning is our bookkeeping, not the researcher doing
        // work, and refreshing it would quietly extend a sitting that ended hours ago.
        write(userId, stored.copy(context = pruned, originNode = origin))
    }

    fun clear(userId: String?) {
        if (userId.isNullOrBlank()) return
        preferences.edit().remove(key(userId)).apply()
    }

    private fun write(userId: String, row: StoredCarryContext) {
        preferences.edit().putString(key(userId), json.encodeToString(StoredCarryContext.serializer(), row)).apply()
    }

    private fun key(userId: String) = "$KEY_PREFIX$userId"

    companion object {
        private const val PREFS = "field_repository_carry_context"
        private const val KEY_PREFIX = "context:"

        /**
         * Twelve hours.
         *
         * A carried context describes one sitting — a day at a workshop in Bagru, a morning in
         * Akola. Twelve hours spans the longest realistic one (arrive at eight, pack up at eight)
         * while guaranteeing that a night always ends it: nobody opens the app next morning and
         * finds yesterday's artisan waiting in today's first form. Context from three days ago is
         * not context, it is a trap.
         */
        const val TTL_MS: Long = 12L * 60L * 60L * 1000L

        /**
         * How long ago, in the words a person would use — deliberately coarse. Verbatim parity with
         * `describeCarryAge` in frontend/lib/carryContext.ts.
         */
        fun describeCarryAge(savedAt: Long, now: Long = System.currentTimeMillis()): String {
            val minutes = ((now - savedAt).coerceAtLeast(0L) / 60_000L).toInt()
            return when {
                minutes < 1 -> "just now"
                minutes == 1 -> "1 minute ago"
                minutes < 60 -> "$minutes minutes ago"
                minutes < 120 -> "1 hour ago"
                else -> "${minutes / 60} hours ago"
            }
        }

        /** "Bagru · Hand Block Printing · Bagru Workshop" — the middle dot every label here uses. */
        fun describeCarryDetail(context: CarryContext): String =
            listOfNotNull(context.place, context.craftName, context.workshopName).joinToString(" · ")

        /**
         * Everything the context holds, ancestor-first and each one labelled by what it IS —
         * "Product" to "Jajam".
         *
         * This is the visible half of the precedence rules. A prefill the researcher does not read
         * is how a product gets attached to the wrong artisan, and a bag that quietly implies
         * parents ("this product, so of course this artisan and this craft") is that hazard with
         * more moving parts. So every record in the bag is named on screen with its type, implied
         * or chosen: nothing is assumed silently.
         */
        fun describeCarryChain(context: CarryContext): List<Pair<String, String>> =
            CarryNode.entries.mapNotNull { node ->
                context.nameOf(node)?.let { name -> node.label.replaceFirstChar { it.uppercase() } to name }
            }

        /** The parents this record dragged along with it, named: "Its artisan and craft came with it." */
        fun describeCarryImplied(context: CarryContext, origin: CarryNode?): String {
            if (origin == null) return ""
            val parents = generateSequence(origin.parent) { it.parent }
                .filter { context.mentions(it) }
                .map { it.label }
                .toList()
            if (parents.isEmpty()) return ""
            val list = if (parents.size == 1) parents.first()
            else parents.dropLast(1).joinToString(", ") + " and " + parents.last()
            return "Its $list came with it."
        }
    }
}
