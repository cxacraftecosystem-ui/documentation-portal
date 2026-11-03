package com.fieldrepository.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class CarryNode {
    WORKSHOP, CRAFT, ARTISAN, PRODUCT, TOOL, PROCESS;

    val parent: CarryNode?
        get() = when (this) {
            WORKSHOP, CRAFT -> null
            ARTISAN -> CRAFT
            PRODUCT, TOOL -> ARTISAN
            PROCESS -> PRODUCT
        }

    val descendants: List<CarryNode>
        get() = entries.filter { candidate ->
            generateSequence(candidate.parent) { it.parent }.any { it == this }
        }

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
    val isEmpty: Boolean
        get() = CarryNode.entries.none { it != CarryNode.WORKSHOP && mentions(it) }

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

    fun mentions(node: CarryNode): Boolean =
        idOf(node) != null || nameOf(node) != null || (node == CarryNode.ARTISAN && place != null)

    internal fun namedWithoutLink(node: CarryNode): Boolean = idOf(node) == null && mentions(node)

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

    fun pruned(node: CarryNode): CarryContext =
        (listOf(node) + node.descendants).fold(this) { context, dead -> context.without(dead) }

    fun selecting(nodes: Set<CarryNode>): CarryContext =
        CarryNode.entries.filterNot { it in nodes }.fold(this) { context, dropped -> context.without(dropped) }

    fun narrowestNode(): CarryNode? = CarryNode.entries.lastOrNull { mentions(it) }
}

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

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
        var parent = incoming.narrowestNode()?.parent
        while (parent != null) {
            if (incoming.mentions(parent)) break
            merged = merged.without(parent)
            parent = parent.parent
        }
    }

    return merged
}

@Serializable
data class StoredCarryContext(
    val context: CarryContext,
    val savedAt: Long,
    val userId: String,
    val originNode: CarryNode? = null
)

class CarryContextStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun read(userId: String?, now: Long = System.currentTimeMillis()): StoredCarryContext? {
        if (userId.isNullOrBlank()) return null
        val raw = preferences.getString(key(userId), null) ?: return null
        val row = runCatching { json.decodeFromString(StoredCarryContext.serializer(), raw) }.getOrNull()
        if (row == null || row.userId != userId || now - row.savedAt > TTL_MS) {
            clear(userId)
            return null
        }
        val sanitized = row.context.sanitized()
        if (sanitized.isEmpty) {
            clear(userId)
            return null
        }
        return row.copy(context = sanitized, originNode = row.originNode ?: sanitized.narrowestNode())
    }

    fun remember(userId: String?, patch: CarryContext, now: Long = System.currentTimeMillis()) {
        if (userId.isNullOrBlank()) return
        val previous = read(userId, now)
        val merged = mergeCarryContext(previous?.context, patch)
        if (merged.isEmpty) return
        val origin = patch.sanitized().narrowestNode() ?: previous?.originNode ?: merged.narrowestNode()
        write(userId, StoredCarryContext(context = merged, savedAt = now, userId = userId, originNode = origin))
    }

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

        const val TTL_MS: Long = 12L * 60L * 60L * 1000L

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

        fun describeCarryDetail(context: CarryContext): String =
            listOfNotNull(context.place, context.craftName, context.workshopName).joinToString(" · ")

        fun describeCarryChain(context: CarryContext): List<Pair<String, String>> =
            CarryNode.entries.mapNotNull { node ->
                context.nameOf(node)?.let { name -> node.label.replaceFirstChar { it.uppercase() } to name }
            }

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
