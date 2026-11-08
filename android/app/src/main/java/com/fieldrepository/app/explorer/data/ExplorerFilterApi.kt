package com.fieldrepository.app.explorer.data

import android.content.Context
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.QueryMap

@Serializable
data class ExplorerFacetOption(val value: String = "", val label: String = "", val hint: String? = null, val count: Int? = null)

@Serializable
data class ExplorerFacet(
    val facet: String = "",
    val param: String = "",
    val label: String = "",
    val options: List<ExplorerFacetOption> = emptyList(),
    val truncated: Boolean = false,
    val selected: List<String> = emptyList(),
)

@Serializable
data class ExplorerDateBounds(val min: String? = null, val max: String? = null)

@Serializable
data class ExplorerPortalOption(val value: String = "", val label: String = "", val available: Boolean = false, val status: String? = null)

@Serializable
data class ExplorerPortalChoices(val options: List<ExplorerPortalOption> = emptyList())

@Serializable
data class ExplorerFacets(
    val facets: List<ExplorerFacet> = emptyList(),
    val dateBounds: ExplorerDateBounds? = null,
    val portal: ExplorerPortalChoices? = null,
)

@Serializable
data class ExplorerActiveValue(val value: String = "", val label: String = "")

@Serializable
data class ExplorerActiveFilter(val param: String = "", val facet: String = "", val label: String = "", val values: List<ExplorerActiveValue> = emptyList())

@Serializable
data class ExplorerAppliedFilters(val active: List<ExplorerActiveFilter> = emptyList(), val count: Int = 0)

@Serializable
data class ExplorerPeerRow(
    val portal: String = "",
    val portalLabel: String = "",
    val local: Boolean = false,
    val type: String = "",
    val id: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val place: String? = null,
    val recordDate: String? = null,
    val craftCodes: List<String> = emptyList(),
    val href: String? = null,
    val openUrl: String? = null,
) {
    val remote: Pair<String, String>?
        get() {
            val parts = href.orEmpty().substringBefore('?').trim('/').split('/')
            if (parts.size >= 3 && parts[0] == "remote" && parts[1].isNotBlank() && parts[2].isNotBlank()) return parts[1] to parts[2]
            return if (type.isNotBlank() && id.isNotBlank()) type to id else null
        }
}

@Serializable
data class ExplorerPeerPage(
    val portal: String = "",
    val portalLabel: String = "",
    val status: String = "ok",
    val items: List<ExplorerPeerRow> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val pages: Int = 0,
    val notApplied: List<String> = emptyList(),
)

interface ExplorerFilterApi {
    @GET("data/filters")
    suspend fun filters(@Query("path") path: String? = null, @QueryMap filters: Map<String, String> = emptyMap()): ExplorerFacets

    @GET("data/peer")
    suspend fun peer(
        @Query("type") type: String? = null,
        @Query("q") q: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = PEER_PAGE_SIZE,
        @QueryMap filters: Map<String, String> = emptyMap(),
    ): ExplorerPeerPage

    companion object {
        const val PEER_PAGE_SIZE = 20
    }
}

object ExplorerFilterApis {
    @Volatile var override: ExplorerFilterApi? = null

    @Volatile private var made: ExplorerFilterApi? = null

    val NONE: ExplorerFilterApi = object : ExplorerFilterApi {
        override suspend fun filters(path: String?, filters: Map<String, String>) = ExplorerFacets()
        override suspend fun peer(type: String?, q: String?, page: Int, pageSize: Int, filters: Map<String, String>) = ExplorerPeerPage(status = "unavailable")
    }

    fun of(context: Context): ExplorerFilterApi = override ?: (if (ExplorerEnvironment.apiOverride != null) NONE else null) ?: made ?: synchronized(this) {
        made ?: Retrofit.Builder()
            .baseUrl(ExplorerPortal.baseUrl())
            .client(ExplorerPortal.client(context.applicationContext))
            .addConverterFactory(ExplorerJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ExplorerFilterApi::class.java)
            .also { made = it }
    }

    fun forget() {
        made = null
    }
}

object ExplorerPeerTypes {
    fun browseKindOf(path: String): String? {
        val head = path.split('/').firstOrNull { it.isNotBlank() }
        return if (head == "by-type" || head == "media-types") "media" else null
    }

    fun statusLine(page: ExplorerPeerPage): String? = when (page.status) {
        "off" -> "${page.portalLabel.ifBlank { "The other portal" }} search is turned off."
        "notPermitted" -> "You don't have access to ${page.portalLabel.ifBlank { "the other portal" }}."
        "unavailable" -> "${page.portalLabel.ifBlank { "The other portal" }} isn't answering. Try again soon."
        "filteredOut" -> "Nothing in ${page.portalLabel.ifBlank { "the other portal" }} can match these filters."
        else -> null
    }
}

object ExplorerFacetKeys {
    private val FALLBACK = listOf(
        ExplorerFilterKey.WORKSHOP,
        ExplorerFilterKey.CRAFT,
        ExplorerFilterKey.ARTISAN,
        ExplorerFilterKey.MEDIA_TYPE,
        ExplorerFilterKey.RECORDED_BY,
        ExplorerFilterKey.LANGUAGE,
    )

    fun otherAvailable(facets: ExplorerFacets?): Boolean =
        facets?.portal?.options.orEmpty().any { it.value == ExplorerFilters.OTHER_PORTAL && it.available }

    fun keys(facets: ExplorerFacets?, filters: ExplorerFilters): List<ExplorerFilterKey> {
        val offered = if (facets == null) FALLBACK.toSet() else facets.facets.mapNotNull { f -> ExplorerFilterKey.entries.firstOrNull { it.facet == f.facet } }.toSet()
        val picked = ExplorerFilterKey.entries.filter { filters[it].isNotEmpty() }.toSet()
        val portal = if (otherAvailable(facets) || filters.includesOther) setOf(ExplorerFilterKey.PORTAL) else emptySet()
        val wanted = offered + picked + portal
        return ExplorerFilterKey.entries.filter { it in wanted }
    }

    fun options(facets: ExplorerFacets?, key: ExplorerFilterKey): List<ExplorerFilterOption> {
        if (key == ExplorerFilterKey.PORTAL) {
            val served = facets?.portal?.options.orEmpty()
            val mine = served.firstOrNull { it.value == ExplorerFilters.THIS_PORTAL }
            val other = served.firstOrNull { it.value == ExplorerFilters.OTHER_PORTAL && it.available }
            return listOfNotNull(
                ExplorerFilterOption(ExplorerFilters.THIS_PORTAL, mine?.label?.takeIf { it.isNotBlank() } ?: ExplorerFilters.portalLabel(ExplorerFilters.THIS_PORTAL)),
                other?.let { ExplorerFilterOption(ExplorerFilters.OTHER_PORTAL, it.label.ifBlank { ExplorerFilters.portalLabel(ExplorerFilters.OTHER_PORTAL) }) },
            )
        }
        return facets?.facets.orEmpty().firstOrNull { it.facet == key.facet }?.options.orEmpty().map { ExplorerFilterOption(it.value, it.label.ifBlank { it.value }, it.count) }
    }

    fun portalSelection(picked: Set<String>): Set<String> =
        if (picked.isEmpty() || picked == setOf(ExplorerFilters.THIS_PORTAL)) emptySet() else picked

    fun labels(applied: ExplorerAppliedFilters?): Map<String, String> =
        applied?.active.orEmpty().flatMap { f -> f.values.map { it.value to it.label } }.filter { it.second.isNotBlank() }.toMap()

    fun summary(applied: ExplorerAppliedFilters?, filters: ExplorerFilters): String {
        val served = applied?.active.orEmpty().filter { it.values.isNotEmpty() }
        if (served.isEmpty()) return filters.summary()
        return served.joinToString(", ") { f -> "${f.label}: ${f.values.joinToString(", ") { it.label.ifBlank { it.value } }}" }
    }
}
