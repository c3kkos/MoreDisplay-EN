package io.github.ynkcc.moredisplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.res.painterResource
import io.github.ynkcc.moredisplay.R
import io.github.ynkcc.moredisplay.core.DisplayInfo
import io.github.ynkcc.moredisplay.eye.EyeControl
import io.github.ynkcc.moredisplay.eye.EyeRequest
import io.github.ynkcc.moredisplay.eye.EyeSettings
import io.github.ynkcc.moredisplay.eye.ViewerController
import io.github.ynkcc.moredisplay.core.Privilege
import io.github.ynkcc.moredisplay.core.VirtualDisplayFlags
import io.github.ynkcc.moredisplay.ui.BuiltInDisplay
import io.github.ynkcc.moredisplay.ui.CreateHistoryStore
import io.github.ynkcc.moredisplay.ui.viewmodel.DisplaysViewModel
import io.github.ynkcc.moredisplay.ui.viewmodel.label

/** Live polling interval for the list (ms). The daemon is the source of truth; polling makes external changes show up too. */
private const val REFRESH_INTERVAL_MS = 3000L

@Composable
fun DisplaysScreen(
    vm: DisplaysViewModel,
    snackbarHostState: SnackbarHostState
) {
    val byChannel by vm.displaysByChannel.collectAsStateWithLifecycle()
    val workModes by vm.workModes.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val watching by ViewerController.watching.collectAsStateWithLifecycle()
    val eyeEnabled by EyeSettings.enabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // null = closed; non-null = create on that channel
    var createChannel by rememberSaveable { mutableStateOf<String?>(null) }

    // Refresh on entering the page and poll while visible, so the list reflects the daemon state in real time.
    LaunchedEffect(Unit) {
        vm.refresh()
        while (isActive) {
            delay(REFRESH_INTERVAL_MS)
            vm.refresh()
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val connectedChannels = workModes.keys.sortedByDescending { it.priority() }

    // The device's own screen (display 0) is always listed first as the default managed display.
    val builtIn = remember { BuiltInDisplay.query(context) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "builtin-header") { BuiltInSectionHeader() }
            item(key = "builtin") { BuiltInDisplayCard(builtIn) }
            item(key = "builtin-spacer") { Spacer(Modifier.height(4.dp)) }

            if (connectedChannels.isEmpty()) {
                item(key = "no-channel") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("No channel connected", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Connect on the Home page to create virtual displays; Shizuku and Xposed (Vector/LSPosed) each manage their own.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                connectedChannels.forEach { channel ->
                    item(key = "header-${channel.name}") {
                        ChannelSectionHeader(
                            channel = channel,
                            count = byChannel[channel].orEmpty().size,
                            enabled = !busy,
                            onCreate = { createChannel = channel.name }
                        )
                    }
                    val list = byChannel[channel].orEmpty()
                    if (list.isEmpty()) {
                        item(key = "empty-${channel.name}") {
                            Text(
                                "No managed displays on this channel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    } else {
                        items(list, key = { it.displayId }) { info ->
                            DisplayCard(
                                info = info,
                                channel = channel,
                                enabled = !busy,
                                onRemove = { vm.removeDisplay(info.displayId) },
                                eyeVisible = eyeEnabled[EyeControl.CARD_BUTTON] ?: true,
                                watching = watching == info.displayId,
                                onEye = {
                                    ViewerController.apply(context, EyeRequest.TOGGLE, info.displayId)
                                }
                            )
                        }
                    }
                    item(key = "spacer-${channel.name}") { Spacer(Modifier.height(4.dp)) }
                }
            }
        }
    }

    createChannel?.let { channelName ->
        Privilege.entries.firstOrNull { it.name == channelName }?.let { channel ->
            CreateDisplayDialog(
                channel = channel,
                busy = busy,
                onDismiss = { createChannel = null },
                onCreate = { w, h, dpi, name, flags ->
                    createChannel = null
                    CreateHistoryStore.record(w, h, dpi)
                    vm.createDisplay(channel, w, h, dpi, name, flags)
                }
            )
        }
    }
}

/** Section header for the device's built-in screen. */
@Composable
private fun BuiltInSectionHeader() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Built-in", style = MaterialTheme.typography.titleMedium)
            Text(
                "  1 display (default)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The device's own screen: always present, can't be removed and is never hidden from apps. */
@Composable
private fun BuiltInDisplayCard(builtIn: BuiltInDisplay?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Display #${builtIn?.displayId ?: BuiltInDisplay.ID}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                AssistChip(onClick = {}, enabled = false, label = { Text("Built-in · default") })
            }
            if (builtIn != null) {
                Text(
                    builtIn.sizeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "name=${builtIn.name}  ${"%.0f".format(builtIn.refreshRate)}Hz",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Device screen details unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "The device's own screen. Always managed; it can't be removed or hidden from apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Channel section header: channel name + display count + its own create button. */
@Composable
private fun ChannelSectionHeader(
    channel: Privilege,
    count: Int,
    enabled: Boolean,
    onCreate: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(channel.label(), style = MaterialTheme.typography.titleMedium)
            Text(
                "  $count display(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCreate, enabled = enabled) {
                Icon(Icons.Filled.Add, contentDescription = "Create on ${channel.label()}")
            }
        }
    }
}

@Composable
private fun DisplayCard(
    info: DisplayInfo,
    channel: Privilege,
    enabled: Boolean,
    onRemove: () -> Unit,
    eyeVisible: Boolean,
    watching: Boolean,
    onEye: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Display #${info.displayId}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                if (eyeVisible) {
                    IconButton(onClick = onEye) {
                        Icon(
                            painterResource(if (watching) R.drawable.ic_eye else R.drawable.ic_eye_off),
                            contentDescription = if (watching) "Hide the viewer" else "Show in a picture-in-picture viewer",
                            tint = if (watching) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove")
                }
            }
            Text(
                "${info.spec.width}×${info.spec.height} @ ${info.spec.densityDpi}dpi",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "name=${info.spec.name}  flags=0x${Integer.toHexString(info.spec.flags)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            info.owner?.let { owner ->
                Text(
                    "owner=${owner.packageName ?: "?"}(${owner.uid})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CreateDisplayDialog(
    channel: Privilege,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (Int, Int, Int, String, Int) -> Unit
) {
    // No preset size/dpi anymore; starts empty and is filled in by the user or from history.
    var width by rememberSaveable { mutableStateOf("") }
    var height by rememberSaveable { mutableStateOf("") }
    var dpi by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("MoreDisplay") }
    var flagsText by rememberSaveable { mutableStateOf("0xffcb") }
    var history by remember { mutableStateOf(CreateHistoryStore.load()) }

    val w = width.toIntOrNull() ?: 0
    val h = height.toIntOrNull() ?: 0
    val d = dpi.toIntOrNull() ?: 0
    val flags = parseFlags(flagsText)
    val valid = w in 1..7680 && h in 1..7680 && d in 10..2000 && flags != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create on ${channel.label()}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (history.isNotEmpty()) {
                    // Recently used sizes, side by side; tap to fill in quickly.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.take(CreateHistoryStore.MAX_ENTRIES).forEach { e ->
                            AssistChip(onClick = {
                                width = e.width.toString()
                                height = e.height.toString()
                                dpi = e.dpi.toString()
                            }, label = { Text(e.toString()) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = width, onValueChange = { width = it },
                        label = { Text("Width") },
                        placeholder = { Text("e.g. 1080", style = MaterialTheme.typography.bodySmall) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = width.isNotEmpty() && w !in 1..7680,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = height, onValueChange = { height = it },
                        label = { Text("Height") },
                        placeholder = { Text("e.g. 1920", style = MaterialTheme.typography.bodySmall) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = height.isNotEmpty() && h !in 1..7680,
                        modifier = Modifier.weight(1f)
                    )
                    }
                    OutlinedTextField(
                    value = dpi, onValueChange = { dpi = it },
                    label = { Text("Density (dpi)") },
                    placeholder = { Text("e.g. 320", style = MaterialTheme.typography.bodySmall) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = dpi.isNotEmpty() && d !in 10..2000,
                    modifier = Modifier.fillMaxWidth()
                    )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Flags", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = flagsText, onValueChange = { flagsText = it },
                    label = { Text("mFlags") },
                    isError = flags == null,
                    singleLine = true,
                    supportingText = {
                        Text(
                            flags?.let { "Parsed as 0x${Integer.toHexString(it)} ($it)" }
                                ?: "Invalid flags value; enter a hex number starting with 0x or a decimal number",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    FLAG_OPTIONS.joinToString("\n") { (label, bit) ->
                        "0x${Integer.toHexString(bit)} = $label"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy,
                onClick = { flags?.let { onCreate(w, h, d, name, it) } }
            ) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Parses the flags input: supports hexadecimal (any case) and plain decimal; returns null if invalid. */
private fun parseFlags(text: String): Int? {
    val s = text.trim()
    if (s.isEmpty()) return null
    val body = if (s.startsWith("0x", ignoreCase = true)) s.substring(2) else s
    if (body.isEmpty() || body.any { it !in "0123456789abcdefABCDEF" }) return null
    val radix = if (s.startsWith("0x", ignoreCase = true)) 16 else 10
    val value = body.toLongOrNull(radix) ?: return null
    return if (value in 0..0xFFFFFFFFL) value.toInt() else null
}

/** Display ordering aligned with DaemonProvider.priority. */
private fun Privilege.priority(): Int = when (this) {
    Privilege.LSPOSED -> 100
    Privilege.SHELL_SHIZUKU -> 50
    Privilege.ROOT -> 10
    Privilege.NONE -> 0
}

private val FLAG_OPTIONS = listOf(
    "PUBLIC" to VirtualDisplayFlags.PUBLIC,
    "PRESENTATION" to VirtualDisplayFlags.PRESENTATION,
    "SECURE" to VirtualDisplayFlags.SECURE,
    "OWN_CONTENT_ONLY" to VirtualDisplayFlags.OWN_CONTENT_ONLY,
    "AUTO_MIRROR" to VirtualDisplayFlags.AUTO_MIRROR,
    "SUPPORTS_TOUCH" to VirtualDisplayFlags.SUPPORTS_TOUCH,
    "SHOULD_SHOW_SYSTEM_DECORATIONS" to VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS,
    "OWN_FOCUS" to VirtualDisplayFlags.OWN_FOCUS,
    "ALWAYS_UNLOCKED" to VirtualDisplayFlags.ALWAYS_UNLOCKED,
    "TRUSTED" to VirtualDisplayFlags.TRUSTED
)
