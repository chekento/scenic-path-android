package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Development place search backed by OpenStreetMap data through Photon.
 *
 * Photon explicitly supports search-as-you-type, typo tolerance and location bias.
 * The public komoot instance is suitable only for moderate development/testing use;
 * production must point the Scenic Path backend at a self-hosted or contracted service.
 */
object OsmPlaceSearch {
    private const val PHOTON_DEMO_URL = "https://photon.komoot.io"

    suspend fun search(query: String, bias: GeoPoint? = null): List<PlaceSuggestion> =
        withContext(Dispatchers.IO) {
            val normalized = query.trim()
            if (normalized.length < 2) return@withContext emptyList()

            val encoded = URLEncoder.encode(normalized, Charsets.UTF_8.name())
            val localeLanguage = Locale.getDefault().language.takeIf { it.length == 2 } ?: "de"
            val biasQuery = bias?.let {
                "&lat=${it.lat}&lon=${it.lon}&zoom=12&location_bias_scale=0.35"
            }.orEmpty()
            val url = "$PHOTON_DEMO_URL/api?q=$encoded&limit=8&lang=$localeLanguage$biasQuery"

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3_500
                readTimeout = 3_500
                setRequestProperty("Accept", "application/geo+json, application/json")
                setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME} development")
            }

            try {
                if (connection.responseCode !in 200..299) return@withContext emptyList()
                val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val features = JSONObject(text).optJSONArray("features") ?: return@withContext emptyList()

                buildList {
                    for (index in 0 until features.length()) {
                        val feature = features.optJSONObject(index) ?: continue
                        val geometry = feature.optJSONObject("geometry") ?: continue
                        val coordinates = geometry.optJSONArray("coordinates") ?: continue
                        if (coordinates.length() < 2) continue
                        val lon = coordinates.optDouble(0, Double.NaN)
                        val lat = coordinates.optDouble(1, Double.NaN)
                        if (!lat.isFinite() || !lon.isFinite()) continue

                        val properties = feature.optJSONObject("properties") ?: JSONObject()
                        val name = properties.optString("name").ifBlank {
                            properties.optString("street").ifBlank { normalized }
                        }
                        val houseNumber = properties.optString("housenumber").takeIf { it.isNotBlank() }
                        val street = properties.optString("street").takeIf { it.isNotBlank() }
                        val postcode = properties.optString("postcode").takeIf { it.isNotBlank() }
                        val city = listOf("city", "locality", "district", "county")
                            .firstNotNullOfOrNull { key -> properties.optString(key).takeIf { it.isNotBlank() } }
                        val state = properties.optString("state").takeIf { it.isNotBlank() }
                        val country = properties.optString("country").takeIf { it.isNotBlank() }

                        val address = buildList {
                            val streetLine = listOfNotNull(street, houseNumber).joinToString(" ").takeIf { it.isNotBlank() }
                            streetLine?.let(::add)
                            listOfNotNull(postcode, city).joinToString(" ").takeIf { it.isNotBlank() }?.let(::add)
                            state?.takeIf { it != city }?.let(::add)
                            country?.let(::add)
                        }.distinct().joinToString(" · ")

                        val osmType = properties.optString("osm_type")
                        val osmId = properties.optString("osm_id")
                        val id = if (osmId.isNotBlank()) "photon-$osmType-$osmId" else "photon-$lat-$lon"

                        add(
                            PlaceSuggestion(
                                id = id,
                                title = name,
                                subtitle = listOf(address, "OpenStreetMap · Photon")
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
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
