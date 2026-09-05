package cloud.kosch.scenicpath

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class PoiExternalLink(
    val label: String,
    val url: String,
)

data class ScenicPoiDetails(
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val openingHours: String? = null,
    val officialLinks: List<PoiExternalLink> = emptyList(),
    val googleMapsUrl: String? = null,
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val ratingSource: String? = null,
    val openNow: Boolean? = null,
    val loadingFinished: Boolean = false,
)

/**
 * On-demand details resolver for the map popup.
 *
 * Discovery deliberately stays lightweight. Rich contact data is fetched only after the user
 * taps a marker. Every OSM/Photon marker already carries its original OSM object id in its
 * Scenic Path id, allowing an exact OSM element lookup without fuzzy place matching.
 *
 * Ratings are never invented. Existing provider ratings are preserved. When a remotely
 * configured Scenic Path backend is available, the resolver may add Google Places rating,
 * review count, official website, phone and formatted address. The default physical-device
 * debug build skips that backend lookup because 10.0.2.2 is emulator-local.
 */
object PoiDetailsResolver {
    suspend fun resolve(point: ScenePointUi): ScenicPoiDetails = withContext(Dispatchers.IO) {
        val baseline = baseline(point)
        val (osm, provider) = coroutineScope {
            val osmJob = async(Dispatchers.IO) {
                runCatching { resolveOsm(point) }.getOrNull()
            }
            val providerJob = async(Dispatchers.IO) {
                if (hasRemoteBackend()) runCatching { resolveProvider(point) }.getOrNull() else null
            }
            osmJob.await() to providerJob.await()
        }
        merge(baseline, osm, provider).copy(loadingFinished = true)
    }

    private fun baseline(point: ScenePointUi): ScenicPoiDetails {
        val legacyUrl = point.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val isMapsUrl = legacyUrl?.contains("google.", ignoreCase = true) == true &&
            legacyUrl.contains("map", ignoreCase = true)
        val links = if (legacyUrl != null && !isMapsUrl) listOf(PoiExternalLink("Official website", legacyUrl)) else emptyList()
        return ScenicPoiDetails(
            officialLinks = links,
            googleMapsUrl = if (isMapsUrl) legacyUrl else googleMapsSearchUrl(point),
            rating = point.rating,
            ratingCount = point.ratingCount,
            ratingSource = if (point.rating != null) point.attribution ?: "Place provider" else null,
            openNow = point.openNow,
        )
    }

    private fun resolveOsm(point: ScenePointUi): ScenicPoiDetails? {
        val ref = parseOsmRef(point.id) ?: return null
        val connection = (URL("https://api.openstreetmap.org/api/0.6/${ref.type}/${ref.id}.json").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_500
            readTimeout = 4_500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME}")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299 || text.isBlank()) return null
            val root = JSONObject(text)
            val elements = root.optJSONArray("elements") ?: return null
            val tags = (0 until elements.length())
                .asSequence()
                .mapNotNull { elements.optJSONObject(it) }
                .firstOrNull { it.optLong("id", -1L) == ref.id }
                ?.optJSONObject("tags") ?: return null
            fromOsmTags(tags)
        } finally {
            connection.disconnect()
        }
    }

    private fun fromOsmTags(tags: JSONObject): ScenicPoiDetails {
        val website = firstNonBlank(tags, "contact:website", "website", "url")
        val phone = firstNonBlank(tags, "contact:phone", "phone", "contact:mobile", "mobile")
        val email = firstNonBlank(tags, "contact:email", "email")
        val openingHours = tags.optString("opening_hours").trim().takeIf { it.isNotBlank() }
        val address = address(tags)

        val links = buildList {
            website?.let { add(PoiExternalLink("Official website", normalizeUrl(it))) }
            firstNonBlank(tags, "contact:instagram", "instagram")?.let { social ->
                add(PoiExternalLink("Instagram", normalizeSocialUrl("instagram.com", social)))
            }
            firstNonBlank(tags, "contact:facebook", "facebook")?.let { social ->
                add(PoiExternalLink("Facebook", normalizeSocialUrl("facebook.com", social)))
            }
            tags.optString("wikipedia").trim().takeIf { it.isNotBlank() }?.let { wiki ->
                wikipediaUrl(wiki)?.let { add(PoiExternalLink("Wikipedia", it)) }
            }
            tags.optString("wikidata").trim().takeIf { it.isNotBlank() }?.let { qid ->
                add(PoiExternalLink("Wikidata", "https://www.wikidata.org/wiki/${qid.trim()}"))
            }
        }.distinctBy { it.url }

        return ScenicPoiDetails(
            address = address,
            phone = phone,
            email = email,
            openingHours = openingHours,
            officialLinks = links,
        )
    }

    private fun resolveProvider(point: ScenePointUi): ScenicPoiDetails? {
        val base = BuildConfig.SCENIC_API_BASE_URL.trimEnd('/')
        val name = URLEncoder.encode(point.name, Charsets.UTF_8.name())
        val url = "$base/v1/poi-details?name=$name&lat=${point.point.lat}&lon=${point.point.lon}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_000
            readTimeout = 3_500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ScenicPath-Android/${BuildConfig.VERSION_NAME}")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299 || text.isBlank()) return null
            val json = JSONObject(text)
            val officialUrl = json.optString("website").trim().takeIf { it.isNotBlank() }
            ScenicPoiDetails(
                address = json.optString("address").trim().takeIf { it.isNotBlank() },
                phone = json.optString("phone").trim().takeIf { it.isNotBlank() },
                openingHours = json.optString("openingHours").trim().takeIf { it.isNotBlank() },
                officialLinks = officialUrl?.let { listOf(PoiExternalLink("Official website", normalizeUrl(it))) }.orEmpty(),
                googleMapsUrl = json.optString("googleMapsUrl").trim().takeIf { it.isNotBlank() },
                rating = json.optDouble("rating", Double.NaN).takeIf { it.isFinite() },
                ratingCount = if (json.has("ratingCount") && !json.isNull("ratingCount")) json.optInt("ratingCount") else null,
                ratingSource = json.optString("ratingSource").trim().takeIf { it.isNotBlank() },
                openNow = if (json.has("openNow") && !json.isNull("openNow")) json.optBoolean("openNow") else null,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun merge(vararg values: ScenicPoiDetails?): ScenicPoiDetails {
        var result = ScenicPoiDetails()
        values.filterNotNull().forEach { next ->
            result = ScenicPoiDetails(
                address = next.address ?: result.address,
                phone = next.phone ?: result.phone,
                email = next.email ?: result.email,
                openingHours = next.openingHours ?: result.openingHours,
                officialLinks = (next.officialLinks + result.officialLinks).distinctBy { it.url },
                googleMapsUrl = next.googleMapsUrl ?: result.googleMapsUrl,
                rating = next.rating ?: result.rating,
                ratingCount = next.ratingCount ?: result.ratingCount,
                ratingSource = next.ratingSource ?: result.ratingSource,
                openNow = next.openNow ?: result.openNow,
            )
        }
        return result
    }

    private fun googleMapsSearchUrl(point: ScenePointUi): String {
        val query = URLEncoder.encode("${point.name} ${point.point.lat},${point.point.lon}", Charsets.UTF_8.name())
        return "https://www.google.com/maps/search/?api=1&query=$query"
    }

    private data class OsmRef(val type: String, val id: Long)

    private fun parseOsmRef(id: String): OsmRef? {
        val match = Regex("(?:precision-osm|coverage-osm|rapid-osm|photon-corridor|photon)-([A-Za-z]+)-(\\d+)").find(id)
            ?: return null
        val type = when (match.groupValues[1].lowercase(Locale.ROOT)) {
            "n", "node" -> "node"
            "w", "way" -> "way"
            "r", "relation" -> "relation"
            else -> return null
        }
        return OsmRef(type, match.groupValues[2].toLongOrNull() ?: return null)
    }

    private fun firstNonBlank(tags: JSONObject, vararg keys: String): String? = keys
        .asSequence()
        .map { tags.optString(it).trim() }
        .firstOrNull { it.isNotBlank() }

    private fun address(tags: JSONObject): String? {
        tags.optString("addr:full").trim().takeIf { it.isNotBlank() }?.let { return it }
        val street = tags.optString("addr:street").trim()
        val house = tags.optString("addr:housenumber").trim()
        val postcode = tags.optString("addr:postcode").trim()
        val city = tags.optString("addr:city").trim().ifBlank { tags.optString("addr:place").trim() }
        val line1 = listOf(street, house).filter { it.isNotBlank() }.joinToString(" ")
        val line2 = listOf(postcode, city).filter { it.isNotBlank() }.joinToString(" ")
        return listOf(line1, line2).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
    }

    private fun normalizeUrl(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    private fun normalizeSocialUrl(host: String, value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val handle = trimmed.removePrefix("@").trimStart('/')
        return "https://$host/$handle"
    }

    private fun wikipediaUrl(value: String): String? {
        val split = value.split(':', limit = 2)
        if (split.size != 2) return null
        val language = split[0].lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z-]{2,12}")) } ?: return null
        val title = URLEncoder.encode(split[1].replace(' ', '_'), Charsets.UTF_8.name()).replace("+", "%20")
        return "https://$language.wikipedia.org/wiki/$title"
    }

    private fun hasRemoteBackend(): Boolean {
        val base = BuildConfig.SCENIC_API_BASE_URL.lowercase(Locale.ROOT)
        return base.startsWith("https://") ||
            (base.startsWith("http://") && !base.contains("10.0.2.2") && !base.contains("127.0.0.1") && !base.contains("localhost"))
    }
}
