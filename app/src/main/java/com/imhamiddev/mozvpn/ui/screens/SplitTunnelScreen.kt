package com.imhamiddev.mozvpn.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.imhamiddev.mozvpn.data.SettingsStore
import com.imhamiddev.mozvpn.data.SettingsStore.SplitTunnelMode
import com.imhamiddev.mozvpn.data.model.ConnectionState
import com.imhamiddev.mozvpn.ui.components.MozCard
import com.imhamiddev.mozvpn.ui.components.SectionTitle
import com.imhamiddev.mozvpn.vpn.MozVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

private enum class ListFilter { ALL, SELECTED }

/**
 * Per-app split tunneling. The user picks one of three modes and then ticks apps:
 *  - All apps: no split tunneling.
 *  - Bypass selected: ticked apps skip the VPN.
 *  - Only selected: only ticked apps use the VPN.
 * Changes are saved immediately; if the VPN is running a banner offers to reconnect so they apply.
 */
@Composable
fun SplitTunnelScreen(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val vpnState by MozVpnService.state.collectAsState()

    var mode by remember { mutableStateOf(settingsStore.splitTunnelMode) }
    var excluded by remember { mutableStateOf(settingsStore.excludedApps) }
    var included by remember { mutableStateOf(settingsStore.includedApps) }
    var dirty by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ListFilter.ALL) }
    var showSystem by remember { mutableStateOf(false) }

    val apps by produceState<List<AppEntry>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { info: ApplicationInfo ->
                    val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    AppEntry(info.packageName, info.loadLabel(pm).toString(), system)
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }
    }

    val selected = if (mode == SplitTunnelMode.INCLUDE) included else excluded

    fun setSelected(next: Set<String>) {
        if (mode == SplitTunnelMode.INCLUDE) {
            included = next
            settingsStore.includedApps = next
        } else {
            excluded = next
            settingsStore.excludedApps = next
        }
        dirty = true
    }

    fun changeMode(next: SplitTunnelMode) {
        if (next == mode) return
        mode = next
        settingsStore.splitTunnelMode = next
        dirty = true
    }

    val visibleApps = remember(apps, query, filter, showSystem, selected) {
        apps.orEmpty().filter { app ->
            val matchesQuery = query.isBlank() || app.label.contains(query, ignoreCase = true)
            val matchesFilter = filter == ListFilter.ALL || app.packageName in selected
            val matchesSystem = showSystem || !app.isSystem || app.packageName in selected
            matchesQuery && matchesFilter && matchesSystem
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
                Text(
                    "Split tunnel",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Choose which apps use the VPN",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    SectionTitle("Mode", Modifier.padding(top = 8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeOption(
                            title = "All apps",
                            description = "Everything goes through the VPN",
                            selected = mode == SplitTunnelMode.OFF,
                            onClick = { changeMode(SplitTunnelMode.OFF) },
                        )
                        ModeOption(
                            title = "Bypass selected apps",
                            description = "Selected apps skip the VPN, all others use it",
                            selected = mode == SplitTunnelMode.EXCLUDE,
                            onClick = { changeMode(SplitTunnelMode.EXCLUDE) },
                        )
                        ModeOption(
                            title = "Only selected apps",
                            description = "Only selected apps use the VPN, all others skip it",
                            selected = mode == SplitTunnelMode.INCLUDE,
                            onClick = { changeMode(SplitTunnelMode.INCLUDE) },
                        )
                    }
                }

                if (dirty && vpnState != ConnectionState.DISCONNECTED) {
                    item {
                        MozCard(
                            modifier = Modifier.fillMaxWidth(),
                            accent = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                "Reconnect to apply your changes",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    MozVpnService.reconnect(context)
                                    dirty = false
                                },
                                shape = RoundedCornerShape(24.dp),
                            ) { Text("Reconnect now") }
                        }
                    }
                }

                if (mode == SplitTunnelMode.OFF) {
                    item {
                        Text(
                            "Split tunneling is off. Pick a mode above to choose apps.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp, start = 4.dp),
                        )
                    }
                } else {
                    if (mode == SplitTunnelMode.INCLUDE && included.isEmpty()) {
                        item {
                            Text(
                                "No apps selected yet - until you pick at least one, all apps will use the VPN.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                            )
                        }
                    }
                    item {
                        SectionTitle(
                            text = if (mode == SplitTunnelMode.INCLUDE) "Apps using the VPN" else "Apps bypassing the VPN",
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search apps") },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = filter == ListFilter.ALL,
                                onClick = { filter = ListFilter.ALL },
                                label = { Text("All") },
                            )
                            FilterChip(
                                selected = filter == ListFilter.SELECTED,
                                onClick = { filter = ListFilter.SELECTED },
                                label = { Text("Selected (${selected.size})") },
                            )
                            FilterChip(
                                selected = showSystem,
                                onClick = { showSystem = !showSystem },
                                label = { Text("System") },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = { setSelected(selected + visibleApps.map { it.packageName }) },
                                enabled = visibleApps.isNotEmpty(),
                            ) { Text("Select shown") }
                            TextButton(
                                onClick = { setSelected(selected - visibleApps.map { it.packageName }.toSet()) },
                                enabled = selected.isNotEmpty(),
                            ) { Text("Clear shown") }
                        }
                    }

                    if (apps == null) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (visibleApps.isEmpty()) {
                        item {
                            Text(
                                "No apps match.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else {
                        items(visibleApps, key = { it.packageName }) { app ->
                            val checked = app.packageName in selected
                            AppRow(
                                app = app,
                                checked = checked,
                                onToggle = {
                                    setSelected(if (checked) selected - app.packageName else selected + app.packageName)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    MozCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        accent = if (selected) MaterialTheme.colorScheme.primary else null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                val bitmap = icon
                if (bitmap != null) {
                    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Switch(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}
