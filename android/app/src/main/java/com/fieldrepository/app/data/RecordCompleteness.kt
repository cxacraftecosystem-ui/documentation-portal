package com.fieldrepository.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * RECORD COMPLETENESS — which fields a record still lacks (2026-10-18).
 *
 * A line-for-line port of the server's `services/completeness.py` and the web's
 * `lib/completenessCore.ts`, over the same table: `shared/record-completeness.json`, of which
 * `assets/record-completeness.json` is a committed copy held byte-equal by the backend test and by
 * `RecordCompletenessTest`.
 *
 * The server's block rides on every artisan, product, tool, workshop and interview it returns
 * ([CompletenessDto]) and is cached with the record. This evaluator draws the meter where there is no
 * block: a form being filled in, an outbox draft, a record that exists only on this phone. Nothing
 * here asks the network, so it works in a courtyard with no tower.
 *
 * The block carries field names, never values, and no rule covers an identity number, phone, email,
 * address or gender.
 */
@Serializable
data class CompletenessRule(
    val key: String,
    val label: String,
    val words: String,
    val level: String,
    val check: String,
    val path: String? = null,
    val paths: List<String>? = null,
    val empty: List<String>? = null,
    val of: String? = null,
    val min: Int? = null,
    val source: String? = null,
)

@Serializable
data class CompletenessRules(
    val contract: String = "",
    val version: Int = 0,
    val checks: List<String> = emptyList(),
    val kinds: Map<String, List<CompletenessRule>> = emptyMap(),
)

@Serializable
data class CompletenessMissingDto(
    val key: String,
    val label: String,
    val words: String,
    val level: String,
) {
    val required: Boolean get() = level == "required"
}

/** The `completeness` block every record response carries (and this evaluator produces). */
@Serializable
data class CompletenessDto(
    val rulesVersion: Int = 0,
    val filled: Int = 0,
    val total: Int = 0,
    val percent: Int = 0,
    val requiredMissing: Int = 0,
    val missing: List<CompletenessMissingDto> = emptyList(),
) {
    val complete: Boolean get() = missing.isEmpty()
}

/** The record kinds the table covers, keyed by the mode names the app routes on. */
object CompletenessKind {
    const val ARTISAN = "artisan"
    const val PRODUCT = "product"
    const val TOOL = "tool"
    const val INTERVIEW = "interview"
    const val WORKSHOP = "workshop"

    /** `EntryMode.name` → kind, or null for a mode the table has no rules for. */
    fun forMode(modeName: String): String? = when (modeName) {
        "ARTISAN" -> ARTISAN
        "PRODUCT" -> PRODUCT
        "TOOL" -> TOOL
        "WORKSHOP" -> WORKSHOP
        "QUESTIONNAIRE" -> INTERVIEW
        else -> null
    }
}

/** The list filter, sent as `completeness=`. Null asks for every record. */
enum class CompletenessFilter(val wire: String?, val label: String) {
    ALL(null, "All"),
    INCOMPLETE("incomplete", "Incomplete"),
    REQUIRED("required", "Missing required");

    companion object {
        fun parse(value: String?): CompletenessFilter = entries.firstOrNull { it.name == value } ?: ALL
    }
}

private fun present(value: Any?): Boolean = when (value) {
    null -> false
    is String -> value.isNotBlank()
    else -> true
}

private fun satisfied(rule: CompletenessRule, values: Map<String, Any?>, counts: Map<String, Int>): Boolean =
    when (rule.check) {
        "present" -> present(values[rule.path])
        // Exactly "not in empty", as on the server: the columns are NOT NULL enums.
        "notIn" -> values[rule.path] !in rule.empty.orEmpty()
        "anyPresent" -> rule.paths.orEmpty().any { present(values[it]) }
        "count" -> (counts[rule.of] ?: 0) >= (rule.min ?: 1)
        else -> error("unknown check ${rule.check}")
    }

/** The block for one record: names of what is missing, never values. */
fun evaluateCompleteness(
    rules: CompletenessRules,
    kind: String,
    values: Map<String, Any?>,
    counts: Map<String, Int> = emptyMap(),
): CompletenessDto {
    val items = rules.kinds[kind].orEmpty()
    // Required first, then file order (sortedBy is stable).
    val missing = items.filterNot { satisfied(it, values, counts) }.sortedBy { if (it.level == "required") 0 else 1 }
    val total = items.size
    val filled = total - missing.size
    return CompletenessDto(
        rulesVersion = rules.version,
        filled = filled,
        total = total,
        percent = if (total == 0) 100 else filled * 100 / total,
        requiredMissing = missing.count { it.level == "required" },
        missing = missing.map { CompletenessMissingDto(it.key, it.label, it.words, it.level) },
    )
}

/** "3 fields left: craft start date, photo, product price" / "Complete". */
fun completenessSentence(block: CompletenessDto): String {
    val count = block.missing.size
    if (count == 0) return "Complete"
    val head = block.missing.take(3).joinToString(", ") { it.words }
    val more = if (count > 3) ", +${count - 3} more" else ""
    return "$count ${if (count == 1) "field" else "fields"} left: $head$more"
}

/** "5 of 7 fields filled" — the ring's spoken name. */
fun completenessAria(block: CompletenessDto): String = "${block.filled} of ${block.total} fields filled"

/** The higher version wins; a table that is not this contract never replaces the one in hand. */
fun pickCompletenessRules(current: CompletenessRules, candidate: CompletenessRules?): CompletenessRules =
    if (candidate != null && candidate.contract == "record-completeness" && candidate.kinds.isNotEmpty() &&
        candidate.version > current.version
    ) candidate else current

/**
 * Counts a form cannot see — photographs already on the record, a linked roster — are taken from the
 * server's block: a count rule it does not list as missing is met. [add] is what the form itself holds
 * (photographs attached on this screen, artisans picked). The web's `liveCounts`, line for line.
 */
fun liveCompletenessCounts(
    rules: CompletenessRules,
    kind: String,
    stored: CompletenessDto?,
    add: Map<String, Int> = emptyMap(),
): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()
    for (rule in rules.kinds[kind].orEmpty()) {
        val of = rule.of ?: continue
        if (rule.check != "count") continue
        val met = stored != null && stored.missing.none { it.key == rule.key }
        counts[of] = (if (met) rule.min ?: 1 else 0) + (add[of] ?: 0)
    }
    return counts
}

/**
 * The rules in force on this phone, from three tiers (the designer portal's `StageSchemaStore` shape):
 *
 *  1. `filesDir/record-completeness.json`, what the last fetch wrote (filesDir, never cacheDir, which
 *     Android empties under storage pressure);
 *  2. `assets/record-completeness.json`, built into the APK, so a phone that never had signal has rules;
 *  3. the network (`GET /records/completeness-rules`, through the OkHttp disk cache that
 *     [OfflineReadCache] installs, which revalidates with the server's ETag), whose answer replaces tier
 *     1 only when its `version` is higher.
 *
 * A failed fetch costs freshness and nothing else.
 */
object RecordCompletenessStore {
    private const val FILE = "record-completeness.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val memory = AtomicReference<CompletenessRules?>(null)

    fun decode(text: String): CompletenessRules? =
        runCatching { json.decodeFromString(CompletenessRules.serializer(), text) }.getOrNull()
            ?.takeIf { it.contract == "record-completeness" && it.kinds.isNotEmpty() }

    /** What is in hand this process, without touching disk. */
    fun peek(): CompletenessRules? = memory.get()

    /** Memory, then disk, then the bundled asset — never the network. The higher version wins. */
    suspend fun load(context: Context): CompletenessRules = withContext(Dispatchers.IO) {
        memory.get()?.let { return@withContext it }
        val bundled = decode(context.assets.open(FILE).bufferedReader().use { it.readText() })
            ?: error("assets/$FILE is missing or malformed")
        val resolved = pickCompletenessRules(bundled, readFile(File(context.filesDir, FILE)))
        memory.set(resolved)
        resolved
    }

    /** Fetch, keep the answer when it is newer, and return what is in force. */
    suspend fun refresh(context: Context, fetch: suspend () -> String): CompletenessRules {
        val current = load(context)
        val fetched = runCatching { fetch() }.getOrNull()?.let(::decode)
        val chosen = pickCompletenessRules(current, fetched)
        if (chosen !== current) {
            withContext(Dispatchers.IO) { runCatching { File(context.filesDir, FILE).writeText(json.encodeToString(CompletenessRules.serializer(), chosen)) } }
            memory.set(chosen)
        }
        return chosen
    }

    internal fun readFile(file: File): CompletenessRules? =
        if (file.isFile) decode(runCatching { file.readText() }.getOrDefault("")) else null
}

// ──────────────────────────────────────────────────────────────────────────────────────────────
// Record → values, for records that carry no server block (outbox, local-only, an older server)
// ──────────────────────────────────────────────────────────────────────────────────────────────

fun ArtisanDetailDto.completenessValues(): Map<String, Any?> = mapOf(
    "dateOfBirth" to dateOfBirth,
    "craftStartDate" to craftStartDate,
    "craftId" to craftId,
    "dos" to dos,
    "donts" to donts,
    "locationId" to location,
)

fun ProductDetailDto.completenessValues(): Map<String, Any?> = mapOf(
    "artisanId" to artisanId,
    "sellingPrice" to sellingPrice,
    "costOfMaking" to costOfMaking,
    "timeTakenToCompleteProduct" to timeTakenToCompleteProduct,
    "lengthInches" to lengthInches,
    "breadthInches" to breadthInches,
    "heightInches" to heightInches,
    "marketDemand" to marketDemand,
)

fun ToolDetailDto.completenessValues(): Map<String, Any?> = mapOf(
    "artisanId" to artisanId,
    "localName" to localName,
    "material" to material,
    "lengthInches" to lengthInches,
    "breadthInches" to breadthInches,
    "heightInches" to heightInches,
    "maker" to maker,
    "traditionType" to traditionType,
    "replacementCost" to replacementCost,
)

/** The record's own block, else the local evaluator's over what the record carries. */
fun ArtisanDetailDto.completenessBlock(rules: CompletenessRules, counts: Map<String, Int> = emptyMap()): CompletenessDto =
    completeness ?: evaluateCompleteness(rules, CompletenessKind.ARTISAN, completenessValues(), counts)

fun ProductDetailDto.completenessBlock(rules: CompletenessRules, counts: Map<String, Int> = emptyMap()): CompletenessDto =
    completeness ?: evaluateCompleteness(rules, CompletenessKind.PRODUCT, completenessValues(), counts)

fun ToolDetailDto.completenessBlock(rules: CompletenessRules, counts: Map<String, Int> = emptyMap()): CompletenessDto =
    completeness ?: evaluateCompleteness(rules, CompletenessKind.TOOL, completenessValues(), counts)

fun WorkshopDetailDto.completenessValues(): Map<String, Any?> = mapOf(
    "startDate" to startDate,
    "endDate" to endDate,
)

fun QuestionnaireInterviewDetailDto.completenessValues(): Map<String, Any?> = mapOf(
    "interviewDate" to interviewDate,
    "place" to place,
    "language" to language,
)
