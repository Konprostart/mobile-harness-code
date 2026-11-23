package com.mobileharness.code.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** Full-screen Template Library: browse, search, inspect and turn a template into a WEB project. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateLibraryDialog(
    state: AppUiState,
    onDismiss: () -> Unit,
    onCreateFromTemplate: (WebTemplate) -> Unit,
) {
    val templates = state.templateCatalog
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
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
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.action_close)) }
                    },
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                if (selectedTemplate != null) {
                    TemplateDetail(
                        template = selectedTemplate,
                        importing = importing,
                        onBack = { selectedTemplateId = null },
                        onCreate = {
                            onCreateFromTemplate(selectedTemplate)
                            onDismiss()
                        },
                    )
                } else {
                    TemplateList(
                        templates = templates,
                        searchQuery = searchQuery,
                        onSearchQuery = { searchQuery = it },
                        selectedCategory = selectedCategory,
                        onSelectCategory = { selectedCategory = it },
                        loading = state.templateCatalogLoading,
                        importing = importing,
                        onSelectTemplate = { selectedTemplateId = it.id },
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
    loading: Boolean,
    importing: Boolean,
    onSelectTemplate: (WebTemplate) -> Unit,
) {
    val categories = templates.map { it.category }.distinct().sortedBy { it.ordinal }
    val filtered = templates.filter { template ->
        (selectedCategory == null || template.category.id == selectedCategory) &&
            (searchQuery.isBlank() ||
                template.name.contains(searchQuery, ignoreCase = true) ||
                template.description.contains(searchQuery, ignoreCase = true) ||
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
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.id }) { template ->
                    TemplateCard(template = template, importing = importing, onClick = { onSelectTemplate(template) })
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(template: WebTemplate, importing: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !importing, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = PocketOrange.copy(alpha = 0.12f), modifier = Modifier.size(46.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(template.icon, fontSize = 22.sp) }
            }
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
                    stringResource(R.string.template_license, template.license) + " · " + categoryLabel(template.category),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
    onBack: () -> Unit,
    onCreate: () -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp), color = PocketOrange.copy(alpha = 0.12f), modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(template.icon, fontSize = 30.sp) }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(template.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(categoryLabel(template.category), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusBadge(template)
            }
        }
        item { Text(template.description, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp) }
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
                    InfoRow(
                        stringResource(R.string.template_availability),
                        when {
                            template.isBundled -> stringResource(R.string.template_bundled)
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
