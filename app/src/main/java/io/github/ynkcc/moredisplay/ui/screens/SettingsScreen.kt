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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.ynkcc.moredisplay.App
import io.github.ynkcc.moredisplay.cast.CastSettings
import io.github.ynkcc.moredisplay.core.ConnectionStatus
import io.github.ynkcc.moredisplay.core.DisplayInfo
import io.github.ynkcc.moredisplay.eye.EyeControl
import io.github.ynkcc.moredisplay.eye.EyeSettings
import io.github.ynkcc.moredisplay.eye.ViewerController
import androidx.compose.ui.text.font.FontFamily

/** Settings: one switch per eye control. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val enabled by EyeSettings.enabled.collectAsStateWithLifecycle()
    val touch by EyeSettings.touch.collectAsStateWithLifecycle()

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
                    "The eye shows a virtual display in a picture-in-picture window on this screen. " +
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
                HorizontalDivider()
                Text("Touch control", style = MaterialTheme.typography.titleSmall)
                SwitchRow(
                    title = "Touch control in the full-screen eye",
                    description = "Expand the eye to full screen and use the app by touch (taps, swipes, pinch). " +
                        "The viewer's Back button sends Back to the app; your own Back shrinks the viewer. " +
                        "A picture-in-picture window can't receive touches, so PiP stays view only.",
                    checked = touch.enabled,
                    onChange = { on -> EyeSettings.setTouch(touch.copy(enabled = on)) }
                )
                SwitchRow(
                    title = "Show touches",
                    description = "A dot under each finger on the eye's picture.",
                    checked = touch.showTouches,
                    onChange = { on -> EyeSettings.setTouch(touch.copy(showTouches = on)) }
                )
            }
        }

        CastSettingsCard()

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("adb usage", style = MaterialTheme.typography.titleMedium)
                Text(
                    "adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity " +
                        "--es action eye --ei display_id 2 --es state toggle",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
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

/** Settings → Cast: which display to cast and how. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CastSettingsCard() {
    val config by CastSettings.config.collectAsStateWithLifecycle()
    var displays by remember { mutableStateOf<List<DisplayInfo>?>(null) }
    LaunchedEffect(Unit) {
        val repo = App.displays()
        if (repo.connectionStatus.value != ConnectionStatus.CONNECTED) repo.connect()
        displays = repo.listDisplays().getOrNull().orEmpty().sortedBy { it.displayId }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cast", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sends a virtual display to a Chromecast at its own resolution, 1:1, while this screen stays free. " +
                    "Start it from the Displays tab. Expect a delay of about 2 to 5 seconds. " +
                    "Copy-protected video (Netflix and similar) stays black.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Display to cast", style = MaterialTheme.typography.titleSmall)
            val list = displays
            when {
                list == null -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
                list.isEmpty() -> Text(
                    "No virtual display yet: create one in the Displays tab.",
                    style = MaterialTheme.typography.bodySmall
                )
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { d ->
                        FilterChip(
                            selected = config.displayId == d.displayId,
                            onClick = { CastSettings.update { it.copy(displayId = d.displayId) } },
                            label = {
                                val name = d.spec.name.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                                Text("#${d.displayId}$name  ${d.spec.width}×${d.spec.height}")
                            }
                        )
                    }
                }
            }

            Text("Video quality", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 2_000, 4_000, 6_000, 8_000, 12_000, 16_000, 20_000).forEach { kbps ->
                    FilterChip(
                        selected = config.bitrateKbps == kbps,
                        onClick = { CastSettings.update { it.copy(bitrateKbps = kbps) } },
                        label = { Text(if (kbps == 0) "Auto" else "${kbps / 1_000} Mbit/s") }
                    )
                }
            }
            Text(
                "Auto: 4 Mbit/s up to 720p, 8 Mbit/s up to 1080p.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Playback", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.smooth,
                    onClick = { CastSettings.update { it.copy(smooth = true) } },
                    label = { Text("Smooth") }
                )
                FilterChip(
                    selected = !config.smooth,
                    onClick = { CastSettings.update { it.copy(smooth = false) } },
                    label = { Text("Low delay") }
                )
            }
            Text(
                "Smooth keeps more video ready on the TV, so Wi-Fi hiccups don't cause pauses (about 2 s more delay). " +
                    "Low delay shows changes sooner but may pause on a busy network.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Frame rate", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60).forEach { fps ->
                    FilterChip(
                        selected = config.fps == fps,
                        onClick = { CastSettings.update { it.copy(fps = fps) } },
                        label = { Text("$fps fps") }
                    )
                }
            }

            HorizontalDivider()
            SwitchRow(
                title = "Cast the app's sound",
                description = "Vector only. The sound of the apps set to \"Open on\" the cast display goes to the TV " +
                    "instead of this speaker, until the cast stops. Apps that forbid sound capture stay silent.",
                checked = config.sound,
                onChange = { on -> CastSettings.update { it.copy(sound = on) } }
            )
            HorizontalDivider()
            SwitchRow(
                title = "Keep the tablet awake while casting",
                description = "Keeps this screen on, dimmed. Turn it on if the cast app pauses when the screen turns off.",
                checked = config.keepAwake,
                onChange = { on -> CastSettings.update { it.copy(keepAwake = on) } }
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
