package io.github.ynkcc.moredisplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ynkcc.moredisplay.core.ConnectionStatus
import io.github.ynkcc.moredisplay.core.DaemonLocation
import io.github.ynkcc.moredisplay.core.Privilege
import io.github.ynkcc.moredisplay.core.WorkModeInfo
import io.github.ynkcc.moredisplay.ui.viewmodel.MainViewModel

@Composable
fun HomeScreen(
    vm: MainViewModel,
    snackbarHostState: SnackbarHostState
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val workModes by vm.workModes.collectAsStateWithLifecycle()
    val available by vm.availablePrivileges.collectAsStateWithLifecycle()

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ConnectionCard(
                status = status,
                error = error,
                onConnect = { vm.connect() },
                onDisconnect = { vm.disconnect() },
                onRefresh = { vm.refreshProviders() }
            )

            ChannelsCard(connected = workModes)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Available access methods", style = MaterialTheme.typography.titleMedium)
                    val pending = available.filter { it !in workModes.keys }
                    if (available.isEmpty()) {
                        Text(
                            "No access method detected: make sure this module is enabled in Vector (or LSPosed), or that Shizuku is running.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (pending.isEmpty()) {
                        Text(
                            "All available channels are connected.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Not connected yet; tap Connect to add:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pending.forEach { AssistChip(onClick = {}, label = { Text(it.label()) }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    status: ConnectionStatus,
    error: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (status) {
                    ConnectionStatus.CONNECTED -> Icon(
                        Icons.Filled.CheckCircle, null, tint = Color(0xFF43A047)
                    )
                    ConnectionStatus.ERROR -> Icon(
                        Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error
                    )
                    ConnectionStatus.BINDING, ConnectionStatus.RECONNECTING ->
                        CircularProgressIndicator(Modifier.height(20.dp))
                    else -> Unit
                }
                Text(
                    "  ${status.label()}",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    enabled = status != ConnectionStatus.BINDING
                ) { Text("Connect") }
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = status == ConnectionStatus.CONNECTED
                ) { Text("Disconnect") }
                OutlinedButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, null)
                    Text("  Refresh")
                }
            }
        }
    }
}

/** Connected channel list: each channel shows its own daemon location and capabilities. */
@Composable
private fun ChannelsCard(connected: Map<Privilege, WorkModeInfo>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Connected channels", style = MaterialTheme.typography.titleMedium)
            if (connected.isEmpty()) {
                Text(
                    "No channel connected. Shizuku and Xposed (Vector/LSPosed) can work at the same time, each managing its own virtual displays.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                connected.entries
                    .sortedByDescending { it.key.priority() }
                    .forEachIndexed { index, (privilege, mode) ->
                        if (index > 0) HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row {
                                Text(
                                    privilege.label(),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "  Daemon: ${mode.daemonLocation.label()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FeatureChip("Launch bypass", mode.capabilities.launchOnDisplayBypass)
                                FeatureChip("Always-unlocked display", mode.capabilities.unlockedDisplay)
                                FeatureChip("Privileged Surface", mode.capabilities.privilegedSurface)
                                FeatureChip("Per-uid visibility", mode.capabilities.perUidDisplayVisibility)
                                FeatureChip("Recording redirect", mode.capabilities.recordRedirection)
                                FeatureChip("Accessibility redirect", mode.capabilities.accessibilityRedirection)
                                FeatureChip("Hide from recents", mode.capabilities.recentsGate)
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun FeatureChip(text: String, enabled: Boolean = true) {
    Text(
        text = if (enabled) "✓ $text" else "✗ $text",
        style = MaterialTheme.typography.bodySmall,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun ConnectionStatus.label(): String = when (this) {
    ConnectionStatus.IDLE -> "Not connected"
    ConnectionStatus.BINDING -> "Connecting..."
    ConnectionStatus.CONNECTED -> "Connected"
    ConnectionStatus.DISCONNECTED -> "Disconnected"
    ConnectionStatus.ERROR -> "Connection error"
    ConnectionStatus.RECONNECTING -> "Reconnecting..."
}

private fun Privilege.label(): String = when (this) {
    Privilege.LSPOSED -> "Xposed / Vector (system_server)"
    Privilege.ROOT -> "Root"
    Privilege.SHELL_SHIZUKU -> "Shizuku"
    Privilege.NONE -> "None"
}

/** Display ordering aligned with DaemonProvider.priority. */
private fun Privilege.priority(): Int = when (this) {
    Privilege.LSPOSED -> 100
    Privilege.SHELL_SHIZUKU -> 50
    Privilege.ROOT -> 10
    Privilege.NONE -> 0
}

private fun DaemonLocation.label(): String = when (this) {
    DaemonLocation.SYSTEM_SERVER -> "in system_server"
    DaemonLocation.STANDALONE_PROCESS -> "standalone process"
    DaemonLocation.IN_APP -> "in app"
}
