package com.mobileharness.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobileharness.code.device.DeviceAuditEntry
import com.mobileharness.code.device.DeviceCommand
import com.mobileharness.code.device.DeviceCommandCatalog
import com.mobileharness.code.device.DeviceCommandRisk
import com.mobileharness.code.device.PrivilegeRoute
import com.mobileharness.code.device.PrivilegeRouteState

/**
 * Device Control dashboard: connection status (Shizuku / Root / Wireless ADB), a risk-labelled
 * command catalog (READ runs silently, CONTROL asks once, DESTRUCTIVE always asks), a manual command
 * runner, and an audit trail. Every command flows through the existing PrivilegePolicy + risk gate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlScreen(
    state: AppUiState,
    onClose: () -> Unit,
    onRefreshDevTools: () -> Unit,
    onTogglePrivilegeRoute: (PrivilegeRoute, Boolean) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRunCommand: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onClearAudit: () -> Unit,
) {
    val dc = state.deviceControl
    val devTools = state.devTools
    var manualCommand by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device Control") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    IconButton(onClick = onRefreshDevTools) { Icon(Icons.Default.Refresh, null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { RouteStatusCard(devTools.privilegedRoutes, devTools.privilegedOptIns, onTogglePrivilegeRoute, onRequestShizukuPermission) }
            item { DeviceInfoCard(devTools) }

            item { SectionTitle("READ — runs without confirmation") }
            items(DeviceCommandCatalog.read) { cmd ->
                CommandRow(cmd, enabled = dc.running.not(), onRun = { onRunCommand(cmd.command) })
            }

            item { SectionTitle("CONTROL — one confirmation per session") }
            items(DeviceCommandCatalog.control) { cmd ->
                CommandRow(cmd, enabled = dc.running.not(), onRun = { onRunCommand(cmd.command) })
            }

            item { SectionTitle("DESTRUCTIVE — always asks") }
            items(DeviceCommandCatalog.destructive) { cmd ->
                CommandRow(cmd, enabled = dc.running.not(), onRun = { onRunCommand(cmd.command) })
            }

            item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Manual command", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = manualCommand,
                            onValueChange = { manualCommand = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("shell command") },
                            singleLine = true,
                        )
                        Button(
                            onClick = { if (manualCommand.isNotBlank()) onRunCommand(manualCommand) },
                            enabled = dc.running.not() && manualCommand.isNotBlank(),
                        ) {
                            if (dc.running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("Run")
                        }
                    }
                }
            }

            dc.lastResult?.let { result ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Last result", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(result, fontSize = 12.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Audit trail (${dc.auditEntries.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearAudit, enabled = dc.auditEntries.isNotEmpty()) { Text("Clear") }
                }
            }
            items(dc.auditEntries.asReversed()) { entry -> AuditRow(entry) }
        }
    }

    dc.pendingConfirmationCommand?.let { command ->
        val risk = DeviceCommandRisk.of(command)
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Confirm ${risk.name.lowercase()} action") },
            text = { Text("This command is classified as $risk and can affect the device:\n\n$command") },
            confirmButton = { Button(onClick = onConfirm) { Text("Run") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun CommandRow(cmd: DeviceCommand, enabled: Boolean, onRun: () -> Unit) {
    Card {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cmd.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    cmd.command,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RiskBadge(cmd.risk)
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRun, enabled = enabled) { Text("Run") }
        }
    }
}

@Composable
private fun RiskBadge(risk: DeviceCommandRisk) {
    val color = when (risk) {
        DeviceCommandRisk.READ -> Color(0xFF2E7D32)
        DeviceCommandRisk.CONTROL -> Color(0xFFF57F17)
        DeviceCommandRisk.DESTRUCTIVE -> Color(0xFFC62828)
    }
    Text(
        risk.name,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun RouteStatusCard(
    routes: List<PrivilegeRouteState>,
    optIns: Map<PrivilegeRoute, Boolean>,
    onToggle: (PrivilegeRoute, Boolean) -> Unit,
    onRequestShizuku: () -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Privileged routes", fontWeight = FontWeight.SemiBold)
            }
            if (routes.isEmpty()) {
                Text("No routes detected. Tap refresh or grant Shizuku permission.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            routes.forEach { route ->
                val optedIn = optIns[route.route] == true
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(route.route.name.replace('_', ' '), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                !route.available -> "Not available"
                                !route.authorized -> "Permission required"
                                else -> "Level: ${route.level.name}"
                            },
                            fontSize = 11.sp,
                            color = if (route.usable) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (route.route == PrivilegeRoute.SHIZUKU && route.available && !route.authorized) {
                        TextButton(onClick = onRequestShizuku) { Text("Grant") }
                    }
                    Switch(checked = optedIn, onCheckedChange = { onToggle(route.route, it) })
                }
            }
        }
    }
}

@Composable
private fun DeviceInfoCard(devTools: DevToolsState) {
    val info = devTools.deviceInfo ?: return
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Device", fontWeight = FontWeight.SemiBold)
            }
            Text("${info.manufacturer} ${info.model}", fontSize = 13.sp)
            Text("Android ${info.androidRelease} (API ${info.sdkInt}) · ${info.abi}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${info.cpuCount} cores · ${info.physicalRamMb} MB RAM", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AuditRow(entry: DeviceAuditEntry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (entry.outcome.name == "SUCCESS") Icons.Default.Info else Icons.Default.Warning,
            null,
            Modifier.size(14.dp),
            tint = if (entry.outcome.name == "SUCCESS") Color(0xFF2E7D32) else Color(0xFFC62828),
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.command, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${entry.risk.name} · ${entry.route} · ${entry.outcome.name}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
