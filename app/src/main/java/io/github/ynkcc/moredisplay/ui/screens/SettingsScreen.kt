package io.github.ynkcc.moredisplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ynkcc.moredisplay.eye.EyeControl
import io.github.ynkcc.moredisplay.eye.EyeSettings
import io.github.ynkcc.moredisplay.eye.ViewerController

/** Settings: one switch per eye control. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val enabled by EyeSettings.enabled.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Eye controls", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The eye shows a virtual display in a picture-in-picture window on this screen, view only. " +
                        "It only moves the display's picture; the display and its apps are not touched. " +
                        "Choose where the eye can be used from.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                EyeControl.entries.forEachIndexed { index, control ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(control.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                control.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = enabled[control] ?: true,
                            onCheckedChange = { on ->
                                EyeSettings.setEnabled(control, on)
                                ViewerController.refreshControls(context)
                            }
                        )
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("adb usage", style = MaterialTheme.typography.titleMedium)
                Text(
                    "adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity " +
                        "--es action eye --ei display_id 2 --es state toggle",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Text(
                    "state: on, off or toggle (default). Without display_id, the last watched display is used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
