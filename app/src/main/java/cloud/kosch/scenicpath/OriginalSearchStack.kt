package cloud.kosch.scenicpath

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Locale

/**
 * Canonical Scenic Path place-search orchestration.
 *
 * This intentionally keeps the original search lanes alive instead of replacing them with one
 * provider-specific shortcut:
 *  - Android/backend search for broad place coverage and local device knowledge.
 *  - Photon/OpenStreetMap for fast type-ahead, typo tolerance and location bias.
 *  - Nominatim/OpenStreetMap only on an explicit Search action for exact street + house number.
 *
 * Each lane is isolated so a temporary provider failure cannot empty the complete result list.
 * Exact-address results win ranking when explicitly requested; Photon remains the primary live
 * type-ahead lane. Results are de-duplicated after ranking rather than allowing the first provider
 * response to suppress richer alternatives.
 */
object OriginalSearchStack {
    suspend fun search(
        context: Context,
        query: String,
        bias: GeoPoint? = null,
        exactAddressRequested: Boolean = false,
        maxResults: Int = 16,
    ): List<PlaceSuggestion> = coroutineScope {
        val normalized = query.trim()
        if (normalized.length < 2 || maxResults <= 0) return@coroutineScope emptyList()

        val standardJob = async {
            runCatching { ScenicApi.searchPlaces(context, normalized, bias) }
                .getOrElse { emptyList() }
        }
        val photonJob = async {
            runCatching { OsmPlaceSearch.search(normalized, bias) }
                .getOrElse { emptyList() }
        }
        val exactJob = async {
            if (exactAddressRequested && normalized.length >= 3) {
                runCatching { OsmAddressSearch.search(normalized, bias, maxResults = 12) }
                    .getOrElse { emptyList() }
            } else {
                emptyList()
            }
        }

        mergeSuggestions(
            query = normalized,
            bias = bias,
            exact = exactJob.await(),
            photon = photonJob.await(),
            standard = standardJob.await(),
            maxResults = maxResults,
        )
    }

    internal fun mergeSuggestions(
        query: String,
        bias: GeoPoint?,
        exact: List<PlaceSuggestion>,
        photon: List<PlaceSuggestion>,
        standard: List<PlaceSuggestion>,
        maxResults: Int = 16,
    ): List<PlaceSuggestion> {
        if (maxResults <= 0) return emptyList()

        data class Ranked(
            val suggestion: PlaceSuggestion,
            val sourcePriority: Int,
            val sourceIndex: Int,
        )

        val ranked = buildList {
            exact.forEachIndexed { index, item -> add(Ranked(item, 300, index)) }
            photon.forEachIndexed { index, item -> add(Ranked(item, 200, index)) }
            standard.forEachIndexed { index, item -> add(Ranked(item, 100, index)) }
        }

        val normalizedQuery = query.trim().lowercase(Locale.ROOT)

        fun queryBonus(item: PlaceSuggestion): Int {
            if (normalizedQuery.isBlank()) return 0
            val title = item.title.trim().lowercase(Locale.ROOT)
            val subtitle = item.subtitle.trim().lowercase(Locale.ROOT)
            return when {
                title == normalizedQuery -> 60
                title.startsWith(normalizedQuery) -> 45
                title.contains(normalizedQuery) -> 30
                subtitle.contains(normalizedQuery) -> 15
                else -> 0
            }
        }

        fun biasBonus(item: PlaceSuggestion): Int {
            val point = bias ?: return 0
            val delta = kotlin.math.abs(item.point.lat - point.lat) + kotlin.math.abs(item.point.lon - point.lon)
            return when {
                delta < 0.05 -> 12
                delta < 0.20 -> 8
                delta < 0.60 -> 4
                else -> 0
            }
        }

        val sorted = ranked.sortedWith(
            compareByDescending<Ranked> {
                it.sourcePriority + queryBonus(it.suggestion) + biasBonus(it.suggestion)
            }.thenBy { it.sourceIndex }
        )

        val seen = mutableSetOf<String>()
        return buildList {
            sorted.forEach { rankedItem ->
                val item = rankedItem.suggestion
                val coordinateKey = "%.5f,%.5f".format(Locale.US, item.point.lat, item.point.lon)
                val titleKey = item.title.trim().lowercase(Locale.ROOT)
                val key = "$coordinateKey:$titleKey"
                if (seen.add(key)) add(item)
                if (size >= maxResults) return@buildList
            }
        }
    }
}
