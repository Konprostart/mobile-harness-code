package com.mobileharness.code.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileharness.code.model.AiCapability
import com.mobileharness.code.model.AiProvider
import com.mobileharness.code.model.AiProviderCatalog
import com.mobileharness.code.model.AiProviderCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mobileharness.code.ui.theme.PocketBlue
import com.mobileharness.code.ui.theme.PocketOrange

/**
 * One shared, searchable AI provider library used by every AI tool. Selecting a provider calls
 * [onSelect]; the caller fills its base URL, wire protocol and default model into the Custom API
 * profile. Favorites and recent-use are persisted through the provided callbacks and reloaded
 * fresh each time the sheet opens, so the library stays offline and per-user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderLibrarySheet(
    onDismiss: () -> Unit,
    onSelect: (AiProvider) -> Unit,
    getFavorites: () -> Set<String>,
    getRecents: () -> List<String>,
    onToggleFavorite: (String) -> Unit,
    catalogRefreshing: Boolean = false,
    catalogUpdatedAtMillis: Long = 0L,
    onRefreshCatalog: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<AiProviderCategory?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<AiProvider?>(null) }
    var favorites by remember { mutableStateOf(getFavorites()) }
    val recents = remember { getRecents() }

    fun toggleFavorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        onToggleFavorite(id)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 18.dp),
        ) {
            val shown = detail
            if (shown == null) {
                // ── List view ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI Provider Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "${AiProviderCatalog.current().size} providers · ${catalogSubtitle(catalogUpdatedAtMillis)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onRefreshCatalog, enabled = !catalogRefreshing) {
                        if (catalogRefreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Refresh, "Refresh catalog", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search providers, routers, gateways…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(8.dp))

                // Category chips
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = category == null && !favoritesOnly,
                        onClick = { category = null; favoritesOnly = false },
                        label = { Text("All") },
                    )
                    FilterChip(
                        selected = favoritesOnly,
                        onClick = { favoritesOnly = !favoritesOnly; if (favoritesOnly) category = null },
                        label = { Text("Favorites") },
                        leadingIcon = {
                            Icon(
                                if (favoritesOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                                null,
                                Modifier.size(16.dp),
                            )
                        },
                    )
                    AiProviderCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = if (category == cat) null else cat; favoritesOnly = false },
                            label = { Text(cat.label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                val filtered = remember(query, category, favoritesOnly, favorites) {
                    AiProviderCatalog.current().filter { provider ->
                        (query.isBlank() || provider.matches(query)) &&
                            (category == null || provider.category == category) &&
                            (!favoritesOnly || provider.id in favorites)
                    }.sortedWith(compareBy<AiProvider> { it.category.ordinal }.thenBy { it.name.lowercase() })
                }

                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("No matching providers.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (query.isBlank() && category == null && !favoritesOnly && recents.isNotEmpty()) {
                            item { Text("Recently used", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(recents.mapNotNull(AiProviderCatalog::byId), key = { "recent-${it.id}" }) { provider ->
                                ProviderRow(
                                    provider = provider,
                                    isFavorite = provider.id in favorites,
                                    onToggleFavorite = { toggleFavorite(provider.id) },
                                    onClick = { detail = provider },
                                )
                            }
                            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) }
                        }
                        items(filtered, key = { it.id }) { provider ->
                            ProviderRow(
                                provider = provider,
                                isFavorite = provider.id in favorites,
                                onToggleFavorite = { toggleFavorite(provider.id) },
                                onClick = { detail = provider },
                            )
                        }
                    }
                }
            } else {
                // ── Detail view ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { detail = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    Text(shown.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { toggleFavorite(shown.id) }) {
                        Icon(
                            if (shown.id in favorites) Icons.Filled.Star else Icons.Filled.StarBorder,
                            "Favorite",
                            tint = if (shown.id in favorites) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            DetailSection("Base URL", shown.baseUrl.ifBlank { "(enter manually)" }, monospace = true)
                            DetailSection("Protocol", shown.wireProtocol.name.lowercase().replace('_', ' '))
                            DetailSection("Default model", shown.defaultModel.ifBlank { "(choose or enter a model)" }, monospace = true)
                            DetailSection("Authentication", shown.authType.label)
                            DetailSection("Category", shown.category.label)
                            if (shown.docsUrl.isNotBlank()) DetailSection("Documentation", shown.docsUrl, monospace = true)
                            if (shown.notes.isNotBlank()) DetailSection("Note", shown.notes)

                            if (shown.capabilities.isNotEmpty()) {
                                Text("Capabilities", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    shown.capabilities.forEach { capability ->
                                        CapabilityBadge(capability)
                                    }
                                }
                            }

                            if (shown.needsAdapter) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "This provider uses its own API and needs an adapter — it cannot be used directly yet.",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    }
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = PocketBlue.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, PocketBlue.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !shown.needsAdapter) { onSelect(shown) },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.AutoAwesome, null, tint = PocketBlue, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        if (shown.needsAdapter) "Adapter required" else "Use this provider",
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (shown.needsAdapter) MaterialTheme.colorScheme.onSurfaceVariant else PocketBlue,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: AiProvider,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (provider.needsAdapter) {
                        Spacer(Modifier.width(6.dp))
                        Text("adapter", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, modifier = Modifier.background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
                Text(provider.baseUrl.ifBlank { "Custom endpoint" }, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(provider.category.label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    "Favorite",
                    tint = if (isFavorite) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, value: String, monospace: Boolean = false) {
    Column {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 13.sp,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Compact human label for the catalog's last-refresh time, used in the sheet header. */
private fun catalogSubtitle(lastUpdatedMillis: Long): String {
    if (lastUpdatedMillis <= 0L) return "bundled · never refreshed"
    val whenStr = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(lastUpdatedMillis))
    return "last updated $whenStr"
}

@Composable
private fun CapabilityBadge(capability: AiCapability) {
    Surface(
        color = PocketBlue.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(Color(0xFF58C99C), CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(capability.label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = PocketBlue)
        }
    }
}
