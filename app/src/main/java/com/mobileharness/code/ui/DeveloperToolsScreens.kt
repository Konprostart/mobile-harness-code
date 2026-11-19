package com.mobileharness.code.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileharness.code.R
import androidx.compose.ui.res.stringResource
import com.mobileharness.code.devtools.CrashRecord
import com.mobileharness.code.devtools.CpuUsage
import com.mobileharness.code.devtools.DeviceInfoSnapshot
import com.mobileharness.code.network.RouteLogEntry
import com.mobileharness.code.network.RouteStage
import com.mobileharness.code.ui.theme.PocketOrange
import java.text.DateFormat
import java.util.Date

/** State for the Developer Tools panel, populated by [com.mobileharness.code.ui.MainViewModel]. */
data class DevToolsState(
    val loading: Boolean = false,
    val deviceInfo: DeviceInfoSnapshot? = null,
    val appPssBytes: Long = 0L,
    val javaHeapBytes: Long = 0L,
    val javaHeapMaxBytes: Long = 0L,
    val cpuUsage: CpuUsage? = null,
    val crashLogs: List<CrashRecord> = emptyList(),
    val routingLog: List<RouteLogEntry> = emptyList(),
    val refreshedAtMillis: Long? = null,
)

@Composable
fun DeveloperToolsContent(
    state: DevToolsState,
    onRefresh: () -> Unit,
    onClearCrashLogs: () -> Unit,
    onClearRoutingLog: () -> Unit = {},
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, null, Modifier.size(17.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.devtools_refresh))
            }
        }

        val info = state.deviceInfo
        DevToolsCard(stringResource(R.string.devtools_device_info)) {
            if (info == null) {
                DevToolsHint(stringResource(R.string.devtools_not_loaded))
            } else {
                DevToolsInfoRow(stringResource(R.string.devtools_device_model), "${info.manufacturer} ${info.model}")
                DevToolsInfoRow(stringResource(R.string.devtools_abi), info.abi)
                DevToolsInfoRow(stringResource(R.string.devtools_android_version), "${info.androidRelease} (API ${info.sdkInt})")
                DevToolsInfoRow(stringResource(R.string.devtools_cpu_cores), info.cpuCount.toString())
                if (info.cpuMaxFreqMhz > 0) {
                    DevToolsInfoRow(stringResource(R.string.devtools_cpu_max_freq), "${info.cpuMaxFreqMhz} MHz")
                }
                DevToolsInfoRow(stringResource(R.string.devtools_physical_ram), formatDevBytes(info.physicalRamMb * 1024L * 1024L))
                DevToolsInfoRow(stringResource(R.string.devtools_available_ram), formatDevBytes(info.availableRamMb * 1024L * 1024L))
            }
        }

        DevToolsCard(stringResource(R.string.devtools_live_monitor)) {
            DevToolsInfoRow(stringResource(R.string.devtools_app_memory), formatDevBytes(state.appPssBytes))
            DevToolsInfoRow(
                stringResource(R.string.devtools_java_heap),
                "${formatDevBytes(state.javaHeapBytes)} / ${formatDevBytes(state.javaHeapMaxBytes)}",
            )
            CpuUsageRow(state.cpuUsage)
        }

        DevToolsCard(stringResource(R.string.devtools_crash_history)) {
            if (state.crashLogs.isEmpty()) {
                DevToolsHint(stringResource(R.string.devtools_no_crashes))
            } else {
                state.crashLogs.forEach { record -> CrashEntry(record) }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onClearCrashLogs,
                enabled = state.crashLogs.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.DeleteSweep, null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.devtools_clear_crashes))
            }
        }

        DevToolsCard(stringResource(R.string.devtools_routing_log)) {
            if (state.routingLog.isEmpty()) {
                DevToolsHint(stringResource(R.string.devtools_no_routing_events))
            } else {
                state.routingLog.forEach { entry -> RoutingLogEntryRow(entry) }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onClearRoutingLog,
                enabled = state.routingLog.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.DeleteSweep, null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.devtools_clear_routing_log))
            }
        }
    }
}

@Composable
private fun CpuUsageRow(usage: CpuUsage?) {
    DevToolsInfoRow(
        stringResource(R.string.devtools_cpu_usage),
        if (usage == null) stringResource(R.string.devtools_cpu_measuring) else "${usage.totalPercent}%",
    )
    if (usage != null && usage.perCorePercent.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            usage.perCorePercent.forEachIndexed { index, percent ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = "C$index $percent%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CrashEntry(record: CrashRecord) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.clickable { expanded = !expanded }.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        record.throwableSummary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatCrashTime(record.timestampMillis)} · v${record.appVersion}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.devtools_thread, record.threadName),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    record.stackTrace,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun RoutingLogEntryRow(entry: RouteLogEntry) {
    val stageLabel = when (entry.stage) {
        RouteStage.ATTEMPT -> stringResource(R.string.devtools_routing_attempt)
        RouteStage.FALLBACK -> stringResource(R.string.devtools_routing_fallback)
        RouteStage.EXHAUSTED -> stringResource(R.string.devtools_routing_exhausted)
        RouteStage.BUDGET_HALTED -> stringResource(R.string.devtools_routing_budget_halted)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${entry.routeId} · ${entry.model}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stageLabel,
                    fontSize = 11.sp,
                    color = if (entry.stage == RouteStage.FALLBACK) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.detail?.let { detail ->
                Text(
                    detail,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DevToolsCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun DevToolsInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

@Composable
private fun DevToolsHint(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun formatDevBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private fun formatCrashTime(timestampMillis: Long): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(timestampMillis))
}.getOrDefault("")
