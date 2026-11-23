package com.mobileharness.code.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobileharness.code.R
import com.mobileharness.code.runtime.TemplateCategory
import com.mobileharness.code.runtime.WebTemplate
import com.mobileharness.code.ui.theme.PocketGreen
import com.mobileharness.code.ui.theme.PocketOrange
import java.io.File

/** Full-screen Template Library: browse, search, inspect and turn a template into a WEB project. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateLibraryDialog(
    state: AppUiState,
    onDismiss: () -> Unit,
    onCreateFromTemplate: (WebTemplate) -> Unit,
    onRefresh: () -> Unit,
    onDeleteCached: (WebTemplate) -> Unit,
    loadThumbnail: suspend (String) -> File?,
) {
    val templates = state.templateCatalog
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIndustry by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTemplate = templates.firstOrNull { it.id == selectedTemplateId }
    val importing = state.projectImporting

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                androidx.compose.material3.TopAppBar(
                    title = { Text(stringResource(R.string.template_library_title), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectedTemplate != null) selectedTemplateId = null else onDismiss()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (selectedTemplate == null) {
                            IconButton(onClick = onRefresh, enabled = !state.templateCatalogLoading) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.template_refresh))
                            }
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) }
                    },
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                if (selectedTemplate != null) {
                    TemplateDetail(
                        template = selectedTemplate,
                        importing = importing,
                        isCached = selectedTemplate.id in state.templateCachedIds,
                        onBack = { selectedTemplateId = null },
                        onCreate = {
                            onCreateFromTemplate(selectedTemplate)
                            onDismiss()
                        },
                        onDeleteCached = { onDeleteCached(selectedTemplate) },
                        loadThumbnail = loadThumbnail,
                    )
                } else {
                    TemplateList(
                        templates = templates,
                        searchQuery = searchQuery,
                        onSearchQuery = { searchQuery = it },
                        selectedCategory = selectedCategory,
                        onSelectCategory = { selectedCategory = it },
                        selectedIndustry = selectedIndustry,
                        onSelectIndustry = { selectedIndustry = it },
                        loading = state.templateCatalogLoading,
                        importing = importing,
                        cacheBytes = state.templateCacheBytes,
                        onSelectTemplate = { selectedTemplateId = it.id },
                        loadThumbnail = loadThumbnail,
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateList(
    templates: List<WebTemplate>,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    selectedCategory: String?,
    onSelectCategory: (String?) -> Unit,
    selectedIndustry: String?,
    onSelectIndustry: (String?) -> Unit,
    loading: Boolean,
    importing: Boolean,
    cacheBytes: Long,
    onSelectTemplate: (WebTemplate) -> Unit,
    loadThumbnail: suspend (String) -> File?,
) {
    val categories = templates.map { it.category }.distinct().sortedBy { it.ordinal }
    val industries = templates.map { it.industry }.filter { it.isNotBlank() }.distinct().sorted()
    val filtered = templates.filter { template ->
        (selectedCategory == null || template.category.id == selectedCategory) &&
            (selectedIndustry == null || template.industry == selectedIndustry) &&
            (searchQuery.isBlank() ||
                template.name.contains(searchQuery, ignoreCase = true) ||
                template.description.contains(searchQuery, ignoreCase = true) ||
                template.industry.contains(searchQuery, ignoreCase = true) ||
                template.tags.any { it.contains(searchQuery, ignoreCase = true) })
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.template_search)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { onSelectCategory(null) },
                    label = { Text(stringResource(R.string.template_category_all)) },
                    leadingIcon = { if (selectedCategory == null) Icon(Icons.Default.Check, null, Modifier.size(16.dp)) },
                )
                categories.forEach { category ->
                    AssistChip(
                        onClick = { onSelectCategory(category.id) },
                        label = { Text(categoryLabel(category)) },
                        leadingIcon = { if (selectedCategory == category.id) Icon(Icons.Default.Check, null, Modifier.size(16.dp)) },
                    )
                }
            }
            if (industries.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = { onSelectIndustry(null) },
                        label = { Text(stringResource(R.string.template_industry_all)) },
                        leadingIcon = { if (selectedIndustry == null) Icon(Icons.Default.Check, null, Modifier.size(16.dp)) },
                    )
                    industries.forEach { industry ->
                        AssistChip(
                            onClick = { onSelectIndustry(industry) },
                            label = { Text(industry) },
                            leadingIcon = { if (selectedIndustry == industry) Icon(Icons.Default.Check, null, Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            if (cacheBytes > 0L) {
                Text(
                    stringResource(R.string.template_cache_used, formatBytes(cacheBytes)),
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            loading && filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PocketOrange)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.template_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.template_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.id }) { template ->
                    TemplateCard(
                        template = template,
                        importing = importing,
                        onClick = { onSelectTemplate(template) },
                        loadThumbnail = loadThumbnail,
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: WebTemplate,
    importing: Boolean,
    onClick: () -> Unit,
    loadThumbnail: suspend (String) -> File?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !importing, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TemplateThumbnail(
                url = template.thumbnailUrl,
                icon = template.icon,
                modifier = Modifier.size(54.dp),
                loadThumbnail = loadThumbnail,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(template.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(template)
                }
                Text(template.description, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(stringResource(R.string.template_license, template.license))
                        if (template.industry.isNotBlank()) append(" · ").append(template.industry)
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (template.demoDataOnly) {
                    Spacer(Modifier.height(3.dp))
                    Text(stringResource(R.string.template_demo_data), fontSize = 9.5.sp, color = PocketOrange, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(template: WebTemplate) {
    val (text, color) = when {
        template.backendRequired -> stringResource(R.string.template_needs_backend) to MaterialTheme.colorScheme.error
        template.buildRequired -> stringResource(R.string.template_needs_build) to PocketOrange
        else -> stringResource(R.string.template_ready) to PocketGreen
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.14f)) {
        Text(text, color = color, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun TemplateDetail(
    template: WebTemplate,
    importing: Boolean,
    isCached: Boolean,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onDeleteCached: () -> Unit,
    loadThumbnail: suspend (String) -> File?,
) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TemplateThumbnail(
                    url = template.thumbnailUrl,
                    icon = template.icon,
                    modifier = Modifier.size(72.dp),
                    loadThumbnail = loadThumbnail,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(template.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val subtitle = if (template.industry.isNotBlank()) {
                        "${categoryLabel(template.category)} · ${template.industry}"
                    } else {
                        categoryLabel(template.category)
                    }
                    Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusBadge(template)
            }
        }
        item { Text(template.description, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp) }
        if (template.demoDataOnly) {
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = PocketOrange.copy(alpha = 0.12f), border = androidx.compose.foundation.BorderStroke(1.dp, PocketOrange.copy(alpha = 0.4f))) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = PocketOrange)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.template_demo_data_desc), fontSize = 12.sp, color = PocketOrange)
                    }
                }
            }
        }
        if (template.backendRequired || template.buildRequired) {
            item {
                val message = if (template.backendRequired) stringResource(R.string.template_needs_backend_desc) else stringResource(R.string.template_needs_build_desc)
                Surface(shape = RoundedCornerShape(12.dp), color = PocketOrange.copy(alpha = 0.12f), border = androidx.compose.foundation.BorderStroke(1.dp, PocketOrange.copy(alpha = 0.4f))) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (template.backendRequired) Icons.Default.Warning else Icons.Default.Info, null, tint = PocketOrange)
                        Spacer(Modifier.width(10.dp))
                        Text(message, fontSize = 12.sp, color = PocketOrange)
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(stringResource(R.string.template_license_info), template.license)
                    if (template.downloadSizeBytes != null) {
                        InfoRow(stringResource(R.string.template_size_label), formatBytes(template.downloadSizeBytes))
                    }
                    InfoRow(
                        stringResource(R.string.template_availability),
                        when {
                            template.isBundled -> stringResource(R.string.template_bundled)
                            isCached -> stringResource(R.string.template_cached)
                            template.isRemote -> stringResource(R.string.template_download)
                            else -> stringResource(R.string.template_not_downloadable)
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.template_view_source), fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            runCatching {
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(template.sourceUrl))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).let(context::startActivity)
                            }
                        }) {
                            Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.template_open))
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = onCreate,
                enabled = template.isReady && !importing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (importing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.template_create_project), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (isCached) {
            item {
                TextButton(
                    onClick = onDeleteCached,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.template_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun TemplateThumbnail(
    url: String?,
    icon: String,
    modifier: Modifier = Modifier,
    loadThumbnail: suspend (String) -> File? = { null },
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, url) {
        value = if (url != null) {
            val file = loadThumbnail(url)
            file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
        } else null
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PocketOrange.copy(alpha = 0.12f),
        modifier = modifier,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = stringResource(R.string.template_thumbnail),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(contentAlignment = Alignment.Center) { Text(icon, fontSize = 24.sp) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun categoryLabel(category: TemplateCategory): String = stringResource(
    when (category) {
        TemplateCategory.WALLET -> R.string.template_category_wallet
        TemplateCategory.DASHBOARD -> R.string.template_category_dashboard
        TemplateCategory.ECOMMERCE -> R.string.template_category_ecommerce
        TemplateCategory.CHAT -> R.string.template_category_chat
        TemplateCategory.MEDIA -> R.string.template_category_media
        TemplateCategory.PORTFOLIO -> R.string.template_category_portfolio
        TemplateCategory.PRODUCTIVITY -> R.string.template_category_productivity
        TemplateCategory.TOOLS -> R.string.template_category_tools
        TemplateCategory.FILES -> R.string.template_category_files
        TemplateCategory.LANDING -> R.string.template_category_landing
    },
)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
