package io.github.ynkcc.moredisplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ynkcc.moredisplay.core.DisplayPolicy
import io.github.ynkcc.moredisplay.core.Visibility
import io.github.ynkcc.moredisplay.ui.viewmodel.PoliciesViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** List polling interval (ms), same as the Displays page. */
private const val REFRESH_INTERVAL_MS = 3000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoliciesScreen(
    vm: PoliciesViewModel,
    snackbarHostState: SnackbarHostState
) {
    val policies by vm.policies.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val displays by vm.displays.collectAsStateWithLifecycle()
    val apps by vm.installedApps.collectAsStateWithLifecycle()

    // Editor state: editing = null creates new; non-null edits an existing policy (upsert by uid).
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DisplayPolicy?>(null) }
    var pendingDelete by remember { mutableStateOf<DisplayPolicy?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // Re-fetch on entering the page and poll while visible, to stay consistent with the daemon state.
    LaunchedEffect(Unit) {
        vm.reload()
        while (isActive) {
            delay(REFRESH_INTERVAL_MS)
            vm.reload()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add policy")
            }
        }
    ) { padding ->
        if (policies.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No display visibility policies yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A policy controls, per uid, which displays an app can see.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(policies, key = { it.uid }) { policy ->
                    PolicyCard(
                        policy = policy,
                        enabled = !busy,
                        onEdit = { editing = policy; showEditor = true },
                        onRemove = { pendingDelete = policy }
                    )
                }
            }
        }
    }

    if (showEditor) {
        PolicyEditorSheet(
            editing = editing,
            busy = busy,
            displays = displays,
            apps = apps,
            onLoadApps = vm::loadInstalledApps,
            onDismiss = { showEditor = false },
            onSave = { policy ->
                showEditor = false
                vm.addPolicy(
                    uid = policy.uid,
                    packageName = policy.packageName,
                    visibility = policy.visibility,
                    displayIds = policy.displayIds,
                    operatedDisplayId = policy.operatedDisplayId,
                    recordDisplayId = policy.recordDisplayId,
                    launchDisplayId = policy.launchDisplayId
                )
            },
            onDelete = editing?.let { policy ->
                {
                    showEditor = false
                    vm.removePolicy(policy.uid)
                }
            }
        )
    }

    pendingDelete?.let { policy ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete policy") },
            text = {
                Text(
                    "Delete the policy for ${policy.packageName ?: "uid=${policy.uid}"}? " +
                        "The app goes back to default visibility."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { pendingDelete = null; vm.removePolicy(policy.uid) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

/** Policy card: app icon + name + visibility badge + display chips; tap the card to edit. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PolicyCard(
    policy: DisplayPolicy,
    enabled: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    Card(onClick = onEdit, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppIcon(packageName = policy.packageName, size = 40)
                Column(Modifier.weight(1f)) {
                    Text(
                        policy.packageName ?: "uid=${policy.uid}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "uid=${policy.uid}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete policy")
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VisibilityBadge(policy.visibility)
                if (policy.displayIds.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        policy.displayIds.sorted().forEach { id ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    "#$id",
                                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
            val extras = listOfNotNull(
                policy.launchDisplayId?.let { "Opens on #$it" },
                policy.recordDisplayId?.let { "Recording->#$it" },
                policy.operatedDisplayId?.let { "A11y->#$it" }
            ).joinToString("  ")
            if (extras.isNotEmpty()) {
                Text(
                    extras,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VisibilityBadge(v: Visibility) {
    val (label, color) = when (v) {
        Visibility.ALL -> "All" to MaterialTheme.colorScheme.tertiary
        Visibility.ONLY -> "Whitelist only" to MaterialTheme.colorScheme.primary
        Visibility.HIDE -> "Blacklist" to MaterialTheme.colorScheme.error
    }
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.12f)) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            color = color,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}
