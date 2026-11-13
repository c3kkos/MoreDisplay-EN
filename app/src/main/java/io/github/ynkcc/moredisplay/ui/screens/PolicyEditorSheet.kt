package io.github.ynkcc.moredisplay.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ynkcc.moredisplay.ui.BuiltInDisplay
import io.github.ynkcc.moredisplay.core.DisplayInfo
import io.github.ynkcc.moredisplay.core.DisplayPolicy
import io.github.ynkcc.moredisplay.core.Visibility
import io.github.ynkcc.moredisplay.ui.viewmodel.AppEntry

/**
 * Card-style policy editor form ([editing] null means create, non-null means edit an existing policy;
 * saving upserts by uid, and edit mode additionally offers a delete button).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PolicyEditorSheet(
    editing: DisplayPolicy?,
    busy: Boolean,
    displays: Map<Int, DisplayInfo>,
    apps: List<AppEntry>?,
    onLoadApps: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (DisplayPolicy) -> Unit,
    onDelete: (() -> Unit)?
) {
    var selectedApp by remember {
        mutableStateOf(editing?.packageName?.let { AppEntry(it, it, editing.uid) })
    }
    var uidText by remember { mutableStateOf(editing?.uid?.toString() ?: "") }
    var visibility by remember { mutableStateOf(editing?.visibility ?: Visibility.ALL) }
    var selectedIds by remember { mutableStateOf(editing?.displayIds ?: emptySet()) }
    var record by remember { mutableStateOf(editing?.recordDisplayId ?: -1) }
    var operated by remember { mutableStateOf(editing?.operatedDisplayId ?: -1) }
    var launch by remember { mutableStateOf(editing?.launchDisplayId ?: -1) }
    var showPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val uid = uidText.toIntOrNull() ?: -1
    val valid = uid >= 0
    val displayIds = displays.keys.sorted()

    LaunchedEffect(Unit) { onLoadApps() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (editing == null) "Add policy" else "Edit policy",
                style = MaterialTheme.typography.titleLarge
            )

            // -- Target app --
            Text("Target app", style = MaterialTheme.typography.titleSmall)
            OutlinedCard(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppIcon(packageName = selectedApp?.packageName, size = 40)
                    val app = selectedApp
                    Column(Modifier.weight(1f)) {
                        if (app == null) {
                            Text("Tap to choose an app", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "You can also enter a uid manually below (e.g. a system uid)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${app.packageName} · uid=${app.uid}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = uidText,
                onValueChange = { uidText = it },
                label = { Text("uid") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = uidText.isNotEmpty() && !valid,
                supportingText = {
                    if (uidText.isNotEmpty() && !valid) Text("uid must be a non-negative integer")
                },
                modifier = Modifier.fillMaxWidth()
            )

            // -- Visibility --
            Text("Visibility", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Visibility.entries.forEach { v ->
                    FilterChip(
                        selected = visibility == v,
                        onClick = {
                            visibility = v
                            if (v == Visibility.ALL) selectedIds = emptySet()
                        },
                        label = { Text(visibilityLabel(v)) }
                    )
                }
            }
            Text(
                when (visibility) {
                    Visibility.ALL -> "Unrestricted: the app can see all displays."
                    Visibility.ONLY -> "Whitelist: the app can only see the checked displays."
                    Visibility.HIDE -> "Blacklist: the app cannot see the checked displays."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // -- Applicable displays (meaningless for ALL, so hidden) --
            if (visibility != Visibility.ALL) {
                Text("Applicable displays", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The built-in screen is the default managed display: always visible, so locked.
                    FilterChip(
                        selected = visibility == Visibility.ONLY,
                        onClick = {},
                        enabled = false,
                        label = { Text("#${BuiltInDisplay.ID} Built-in") }
                    )
                    displayIds.filter { it != BuiltInDisplay.ID }.forEach { id ->
                        FilterChip(
                            selected = id in selectedIds,
                            onClick = {
                                selectedIds =
                                    if (id in selectedIds) selectedIds - id else selectedIds + id
                            },
                            label = { Text(displayLabel(displays, id)) }
                        )
                    }
                }
                Text(
                    if (displayIds.none { it != BuiltInDisplay.ID }) {
                        "The built-in screen is always visible to every app. Create virtual displays on the Displays page to add more."
                    } else {
                        "The built-in screen is always visible to every app."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // -- Open on --
            Text("Open on", style = MaterialTheme.typography.titleSmall)
            Text(
                "The display this app always opens on, whoever starts it (home screen, notification, ...). " +
                    "Pick a virtual display to keep the app off your screen; watch it with the eye. " +
                    "If the app is already open, close it from Recents once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RedirectChips(
                displayIds = displayIds,
                displays = displays,
                selected = launch,
                onSelect = { launch = it },
                noneLabel = "#${BuiltInDisplay.ID} Built-in (as usual)"
            )
            val launchVisible = launch < 0 || when (visibility) {
                Visibility.ALL -> true
                Visibility.ONLY -> launch in selectedIds
                Visibility.HIDE -> launch !in selectedIds
            }
            if (!launchVisible) {
                Text(
                    "The app would open on a display it isn't allowed to see. Tick it in Visibility above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // -- Recording redirect --
            Text("Recording redirect", style = MaterialTheme.typography.titleSmall)
            Text(
                "Redirect the screen-recording source to the selected display",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RedirectChips(
                displayIds = displayIds,
                displays = displays,
                selected = record,
                onSelect = { record = it }
            )

            // -- Accessibility redirect --
            Text("Accessibility redirect", style = MaterialTheme.typography.titleSmall)
            Text(
                "Redirect accessibility gestures to the selected display",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RedirectChips(
                displayIds = displayIds,
                displays = displays,
                selected = operated,
                onSelect = { operated = it }
            )

            // -- Actions --
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = valid && !busy,
                    onClick = {
                        onSave(
                            DisplayPolicy(
                                uid = uid,
                                packageName = selectedApp?.packageName,
                                visibility = visibility,
                                displayIds =
                                    if (visibility == Visibility.ALL) emptySet() else selectedIds,
                                operatedDisplayId = operated.takeIf { it >= 0 },
                                recordDisplayId = record.takeIf { it >= 0 },
                                launchDisplayId = launch.takeIf { it >= 0 }
                            )
                        )
                    }
                ) { Text("Save") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showPicker) {
        AppPickerSheet(
            apps = apps,
            onPick = { app ->
                selectedApp = app
                uidText = app.uid.toString()
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete policy") },
            text = { Text("Delete the policy for uid=$uid? The app goes back to default visibility.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

/** Single-choice redirect chips: the built-in screen (= no redirect) + each managed virtual display. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RedirectChips(
    displayIds: List<Int>,
    displays: Map<Int, DisplayInfo>,
    selected: Int,
    onSelect: (Int) -> Unit,
    noneLabel: String = "#${BuiltInDisplay.ID} Built-in (no redirect)"
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == -1,
            onClick = { onSelect(-1) },
            label = { Text(noneLabel) }
        )
        displayIds.filter { it != BuiltInDisplay.ID }.forEach { id ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = { Text(displayLabel(displays, id)) }
            )
        }
    }
}

/** App picker: search (name / package / uid) + scrollable list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    apps: List<AppEntry>?,
    onPick: (AppEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search app / package / uid") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
            when {
                apps == null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                apps.isEmpty() -> Text(
                    "No installed apps could be read",
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> {
                    val filtered = apps.filter { app ->
                        query.isBlank() ||
                            app.label.contains(query, ignoreCase = true) ||
                            app.packageName.contains(query, ignoreCase = true) ||
                            app.uid.toString() == query.trim()
                    }
                    if (filtered.isEmpty()) {
                        Text(
                            "No matching apps",
                            Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.65f)
                    ) {
                        items(filtered, key = { "${it.packageName}:${it.uid}" }) { app ->
                            AppRow(app = app, onClick = { onPick(app) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIcon(packageName = app.packageName, size = 40)
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${app.packageName} · uid=${app.uid}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** App icon: lazily loaded by package name and rasterized; shows a placeholder circle if unavailable. */
@Composable
internal fun AppIcon(packageName: String?, size: Int) {
    val context = LocalContext.current
    val icon: ImageBitmap? = if (packageName.isNullOrBlank()) null
    else remember(packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        }.getOrNull()
    }
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(size.dp)
        )
    } else {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(size.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("?", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

internal fun displayLabel(displays: Map<Int, DisplayInfo>, id: Int): String {
    val name = displays[id]?.spec?.name
    return if (name.isNullOrBlank() || name == "MoreDisplay") "#$id" else "#$id ($name)"
}

internal fun visibilityLabel(v: Visibility): String = when (v) {
    Visibility.ALL -> "All"
    Visibility.ONLY -> "Whitelist only"
    Visibility.HIDE -> "Blacklist"
}
