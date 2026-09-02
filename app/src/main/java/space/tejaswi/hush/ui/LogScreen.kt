package space.tejaswi.hush.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.tejaswi.hush.data.AppState
import space.tejaswi.hush.data.HushDatabase
import space.tejaswi.hush.data.LogEntry
import space.tejaswi.hush.data.StateRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(state: AppState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { StateRepository.get(ctx) }
    val dao = remember { HushDatabase.get(ctx).log() }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var pkgFilter by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val entries by dao.search(query.trim(), pkgFilter).collectAsStateWithLifecycle(initialValue = emptyList())
    val packages by dao.packages().collectAsStateWithLifecycle(initialValue = emptyList())

    // package → label cache, resolved off the main thread
    val labels by produceState(initialValue = emptyMap<String, String>(), packages) {
        value = withContext(Dispatchers.IO) {
            packages.associateWith { p ->
                runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }
                    .getOrDefault(p)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification log") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Logging settings") }
                    IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteSweep, "Clear log") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Logging", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = state.logEnabled, onCheckedChange = { scope.launch { repo.setLogEnabled(it) } })
            }
            Text(
                if (state.logEnabled) {
                    val scopeText = if (state.logPackages.isEmpty()) "all apps" else "${state.logPackages.size} selected apps"
                    "Capturing $scopeText · kept ${state.logRetentionDays} days · stored only on this device"
                } else "Off — nothing is being recorded.",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search messages") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            if (packages.isNotEmpty()) Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = pkgFilter == null, onClick = { pkgFilter = null }, label = { Text("All") })
                packages.forEach { p ->
                    FilterChip(
                        selected = pkgFilter == p,
                        onClick = { pkgFilter = if (pkgFilter == p) null else p },
                        label = { Text(labels[p] ?: p) },
                    )
                }
            }

            HorizontalDivider()

            if (entries.isEmpty()) {
                Text(
                    if (state.logEnabled) "Nothing logged yet." else "Turn logging on to start capturing.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                var lastDate: LocalDate? = null
                entries.forEach { e ->
                    val date = Instant.ofEpochMilli(e.postedAt).atZone(ZoneId.systemDefault()).toLocalDate()
                    if (date != lastDate) {
                        lastDate = date
                        item(key = "h$date") {
                            Text(
                                date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")),
                                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    item(key = e.id) { LogRow(e, labels[e.packageName] ?: e.packageName) }
                }
            }
        }
    }

    if (showSettings) LogAppsDialog(
        selected = state.logPackages,
        onChange = { scope.launch { repo.setLogPackages(it) } },
        onDismiss = { showSettings = false },
    )

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear the whole log?") },
        text = { Text("This can't be undone.") },
        confirmButton = { TextButton(onClick = { scope.launch { dao.clear() }; confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun LogRow(e: LogEntry, appLabel: String) {
    val time = Instant.ofEpochMilli(e.postedAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row {
            Text(
                buildString {
                    append(appLabel)
                    val who = e.sender ?: e.title
                    if (!who.isNullOrBlank()) append(" · ").append(who)
                    if (e.sender != null && !e.title.isNullOrBlank() && e.title != e.sender) append(" in ").append(e.title)
                },
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(e.text, style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}

/** Pick which apps to log. Empty selection = everything. */
@Composable
private fun LogAppsDialog(selected: Set<String>, onChange: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<InstalledApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(ctx.packageManager) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apps to log") },
        text = {
            Column {
                Text(
                    if (selected.isEmpty()) "None selected = log every app." else "${selected.size} selected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth(),
                )
                val list = apps
                if (list == null) Text("Loading…") else LazyColumn(Modifier.height(360.dp)) {
                    val filtered = list.filter { query.isBlank() || it.label.contains(query, true) }
                        .sortedWith(compareByDescending<InstalledApp> { it.packageName in selected }.thenBy { it.label.lowercase() })
                    items(filtered, key = { it.packageName }) { app ->
                        val on = app.packageName in selected
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onChange(if (on) selected - app.packageName else selected + app.packageName)
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(app.label, Modifier.weight(1f))
                            Checkbox(checked = on, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
