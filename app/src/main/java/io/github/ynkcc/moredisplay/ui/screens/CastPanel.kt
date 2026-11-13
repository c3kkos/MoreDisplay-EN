package io.github.ynkcc.moredisplay.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ynkcc.moredisplay.cast.CastController
import io.github.ynkcc.moredisplay.cast.CastDevice
import io.github.ynkcc.moredisplay.cast.CastSettings
import io.github.ynkcc.moredisplay.cast.CastState
import io.github.ynkcc.moredisplay.core.DisplayInfo

/** Top of the Displays tab: cast the display picked in Settings → Cast to a Chromecast, or stop. */
@Composable
internal fun CastPanel(managed: Map<Int, DisplayInfo>) {
    val context = LocalContext.current
    val state by CastController.state.collectAsStateWithLifecycle()
    val config by CastSettings.config.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        picking = true // the notification is optional; go on either way
    }

    val target = config.displayId.takeIf { it >= 0 }
    val targetLabel = target?.let { id ->
        managed[id]?.spec?.name?.takeIf { it.isNotBlank() }?.let { "#$id · $it" } ?: "#$id"
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Cast", style = MaterialTheme.typography.titleMedium)
            when (val s = state) {
                is CastState.Casting -> {
                    Text("Casting display #${s.displayId} to ${s.device}", style = MaterialTheme.typography.bodyMedium)
                    s.soundNote?.let {
                        Text(
                            "No sound on the TV: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { CastController.stop(context) }) { Text("Stop casting") }
                }
                is CastState.Starting -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Starting to cast display #${s.displayId} to ${s.device}…")
                    }
                    OutlinedButton(onClick = { CastController.stop(context) }) { Text("Cancel") }
                }
                else -> {
                    if (s is CastState.Failed) {
                        Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    when {
                        target == null -> Text(
                            "Pick the display to cast in Settings → Cast.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        target !in managed -> Text(
                            "Display #$target (set in Settings → Cast) doesn't exist right now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> Button(onClick = {
                            val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            if (needsPermission) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else picking = true
                        }) { Text("Cast display $targetLabel…") }
                    }
                }
            }
        }
    }

    if (picking && target != null) {
        CastDevicePicker(
            onPick = { device ->
                picking = false
                CastController.start(context, device, target)
            },
            onDismiss = { picking = false }
        )
    }
}

@Composable
private fun CastDevicePicker(onPick: (CastDevice) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val devices by CastController.devices.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        CastController.startDiscovery(context)
        onDispose { CastController.stopDiscovery(context) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cast to") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (devices.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Looking for Cast devices on your Wi-Fi…")
                    }
                } else {
                    devices.forEachIndexed { index, device ->
                        if (index > 0) HorizontalDivider()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(device) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            device.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
