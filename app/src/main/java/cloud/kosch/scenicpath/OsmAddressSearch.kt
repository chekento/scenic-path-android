package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Explicit address search for start/destination selection.
 *
 * Photon remains the type-ahead source. Nominatim is intentionally used only after an explicit
 * Search/IME action so the public development endpoint is not hit on every keystroke. This gives
 * Scenic Path reliable street + house-number lookup without abusing an autocomplete service.
 */
object OsmAddressSearch {
    private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"

    suspend fun search(
        query: String,
        bias: GeoPoint? = null,
        maxResults: Int = 10,
    ): List<PlaceSuggestion> = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        if (normalized.length < 3) return@withContext emptyList()

        val encoded = URLEncoder.encode(normalized, Charsets.UTF_8.name())
        val language = Locale.getDefault().toLanguageTag().ifBlank { "de" }
        val biasQuery = bias?.let { point ->
            // viewbox biases global search but does not exclude a deliberately distant destination.
            val lonSpan = 0.8
            val latSpan = 0.55
            "&viewbox=${point.lon - lonSpan},${point.lat + latSpan},${point.lon + lonSpan},${point.lat - latSpan}&bounded=0"
        }.orEmpty()
        val url = "$NOMINATIM_URL?format=jsonv2&addressdetails=1&namedetails=1&dedupe=1&limit=${maxResults.coerceIn(1, 12)}&accept-language=${URLEncoder.encode(language, Charsets.UTF_8.name())}&q=$encoded$biasQuery"

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} address-search")
        }

        try {
            if (connection.responseCode !in 200..299) return@withContext emptyList()
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val rows = JSONArray(text)

            buildList {
                for (index in 0 until rows.length()) {
                    val item = rows.optJSONObject(index) ?: continue
                    val lat = item.optString("lat").toDoubleOrNull() ?: continue
                    val lon = item.optString("lon").toDoubleOrNull() ?: continue
                    val address = item.optJSONObject("address")
                    val namedetails = item.optJSONObject("namedetails")

                    val houseNumber = address?.optString("house_number")?.takeIf { it.isNotBlank() }
                    val road = listOf("road", "pedestrian", "residential", "footway", "path")
                        .firstNotNullOfOrNull { key -> address?.optString(key)?.takeIf { it.isNotBlank() } }
                    val locality = listOf("city", "town", "village", "municipality", "suburb")
                        .firstNotNullOfOrNull { key -> address?.optString(key)?.takeIf { it.isNotBlank() } }
                    val postcode = address?.optString("postcode")?.takeIf { it.isNotBlank() }
                    val named = namedetails?.optString("name")?.takeIf { it.isNotBlank() }
                    val displayName = item.optString("display_name").takeIf { it.isNotBlank() }.orEmpty()

                    val streetTitle = listOfNotNull(road, houseNumber).joinToString(" ").takeIf { it.isNotBlank() }
                    val title = streetTitle ?: named ?: displayName.substringBefore(',').ifBlank { normalized }
                    val compactLocality = listOfNotNull(postcode, locality).joinToString(" ").takeIf { it.isNotBlank() }
                    val subtitle = buildList {
                        if (streetTitle != null && compactLocality != null) add(compactLocality)
                        if (displayName.isNotBlank()) add(displayName)
                        add("OpenStreetMap · exact address")
                    }.distinct().joinToString(" · ")

                    val osmType = item.optString("osm_type")
                    val osmId = item.optString("osm_id")
                    add(
                        PlaceSuggestion(
                            id = "nominatim-$osmType-$osmId",
                            title = title,
                            subtitle = subtitle,
                            point = GeoPoint(lat, lon),
                        )
                    )
                }
            }.distinctBy { suggestion ->
                "${suggestion.title.lowercase(Locale.ROOT)}:${"%.5f".format(Locale.US, suggestion.point.lat)}:${"%.5f".format(Locale.US, suggestion.point.lon)}"
            }
        } finally {
            connection.disconnect()
        }
    }
}
