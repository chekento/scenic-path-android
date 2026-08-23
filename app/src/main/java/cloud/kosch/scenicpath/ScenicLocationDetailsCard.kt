package cloud.kosch.scenicpath

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.floor

/** Rich map popup shared by all Scenic marker categories. */
@Composable
fun ScenicLocationDetailsCard(
    highlight: ScenePointUi,
    details: ScenicPoiDetails,
    detailsLoading: Boolean,
    onClose: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCall: (String) -> Unit,
    onEmail: (String) -> Unit,
    onOpenOsm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.985f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
    ) {
        Column(
            Modifier
                .heightIn(max = 590.dp)
                .verticalScroll(rememberScrollState())
                .padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = if (highlight.includedInRoute) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        scenicCategoryLaneFor(highlight).emoji,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(highlight.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        richSceneTypeLabel(highlight),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close location info") }
            }

            if (highlight.includedInRoute) {
                Text(
                    "✓ Automatically included in this journey",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val routeFacts = buildList {
                if (highlight.distanceFromRouteMeters > 0) add("${highlight.distanceFromRouteMeters} m from route")
                add("Suggested stop ${highlight.suggestedDwellMinutes} min")
                highlight.personalMatch?.let { add("${it.toInt()}% match") }
            }
            Text(routeFacts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)

            RatingBlock(
                rating = details.rating ?: highlight.rating,
                ratingCount = details.ratingCount ?: highlight.ratingCount,
                ratingSource = details.ratingSource,
                googleMapsUrl = details.googleMapsUrl,
                onOpenUrl = onOpenUrl,
            )

            if (details.openNow != null || highlight.openNow != null) {
                val open = details.openNow ?: highlight.openNow
                AssistChip(
                    onClick = {},
                    label = { Text(if (open == true) "Open now" else "Currently closed") },
                    leadingIcon = { Icon(if (open == true) Icons.Default.CheckCircle else Icons.Default.Schedule, null, Modifier.size(18.dp)) },
                    enabled = false,
                )
            }

            details.address?.let {
                DetailRow(Icons.Default.Place, "Address", it)
            }
            details.openingHours?.let {
                DetailRow(Icons.Default.Schedule, "Opening hours", it)
            }

            if (details.phone != null || details.email != null) {
                HorizontalDivider()
                Text("Contact", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                details.phone?.let { phone ->
                    FilledTonalButton(onClick = { onCall(phone) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Phone, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(phone)
                    }
                }
                details.email?.let { email ->
                    OutlinedButton(onClick = { onEmail(email) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Email, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(email, maxLines = 1)
                    }
                }
            }

            if (details.officialLinks.isNotEmpty()) {
                HorizontalDivider()
                Text("Official & reference links", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                details.officialLinks.forEach { link ->
                    OutlinedButton(onClick = { onOpenUrl(link.url) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(linkIcon(link.label), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(link.label, Modifier.weight(1f))
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                    }
                }
            }

            highlight.rationale?.takeIf { it.isNotBlank() }?.let {
                HorizontalDivider()
                Text("Why here: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (detailsLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading official place details…", style = MaterialTheme.typography.labelSmall)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenOsm, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Map, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("OSM")
                }
                details.googleMapsUrl?.let { mapsUrl ->
                    Button(onClick = { onOpenUrl(mapsUrl) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Star, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Maps & ratings")
                    }
                }
            }

            if (!highlight.attribution.isNullOrBlank()) {
                Text(highlight.attribution, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Ratings are shown only when supplied by a configured rating provider; Scenic Path does not invent review scores.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RatingBlock(
    rating: Double?,
    ratingCount: Int?,
    ratingSource: String?,
    googleMapsUrl: String?,
    onOpenUrl: (String) -> Unit,
) {
    if (rating != null) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
        ) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(starText(rating), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(String.format(Locale.US, "%.1f / 5", rating), fontWeight = FontWeight.Bold)
                    val meta = buildList {
                        ratingCount?.let { add("${NumberFormat.getIntegerInstance().format(it)} reviews") }
                        ratingSource?.let { add(it) }
                    }.joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall)
                }
                if (googleMapsUrl != null) {
                    IconButton(onClick = { onOpenUrl(googleMapsUrl) }) { Icon(Icons.Default.OpenInNew, "Open live ratings") }
                }
            }
        }
    } else if (googleMapsUrl != null) {
        AssistChip(
            onClick = { onOpenUrl(googleMapsUrl) },
            label = { Text("★ Open live ratings") },
            leadingIcon = { Icon(Icons.Default.StarOutline, null, Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(9.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun starText(rating: Double): String {
    val bounded = rating.coerceIn(0.0, 5.0)
    val full = floor(bounded).toInt()
    val roundedUp = if (bounded - full >= 0.5 && full < 5) 1 else 0
    val filled = (full + roundedUp).coerceAtMost(5)
    return "★".repeat(filled) + "☆".repeat(5 - filled)
}

private fun richSceneTypeLabel(highlight: ScenePointUi): String {
    val category = scenicCategoryLaneFor(highlight).label
    val subtype = highlight.subtype?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
        ?.takeIf { it.isNotBlank() && !category.contains(it, ignoreCase = true) }
    return listOfNotNull(category, subtype).joinToString(" · ")
}

private fun linkIcon(label: String) = when {
    label.contains("Wikipedia", ignoreCase = true) -> Icons.Default.MenuBook
    label.contains("Wikidata", ignoreCase = true) -> Icons.Default.DataObject
    label.contains("Instagram", ignoreCase = true) || label.contains("Facebook", ignoreCase = true) -> Icons.Default.Share
    else -> Icons.Default.Language
}
