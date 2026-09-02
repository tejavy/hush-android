package space.tejaswi.hush.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.tejaswi.hush.data.BlockAction
import space.tejaswi.hush.data.Rule
import space.tejaswi.hush.data.asClock
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

data class InstalledApp(val packageName: String, val label: String, val icon: Drawable)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(
    initial: Rule,
    onSave: (Rule) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var rule by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }

    val apps by produceState<List<InstalledApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(ctx.packageManager) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial.packages.isEmpty()) "New rule" else "Edit rule") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
                    TextButton(onClick = { onSave(rule) }, enabled = rule.packages.isNotEmpty()) { Text("Save") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = rule.name,
                    onValueChange = { rule = rule.copy(name = it) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("All day", Modifier.weight(1f))
                    Switch(checked = rule.allDay, onCheckedChange = { rule = rule.copy(allDay = it) })
                }
                if (!rule.allDay) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TimeButton("From", rule.startMinutes) { rule = rule.copy(startMinutes = it) }
                        TimeButton("Until", rule.endMinutes) { rule = rule.copy(endMinutes = it) }
                    }
                    if (rule.startMinutes > rule.endMinutes) Text(
                        "Runs overnight into the next day.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Text("Days", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { d ->
                        FilterChip(
                            selected = d in rule.days,
                            onClick = {
                                rule = rule.copy(days = if (d in rule.days) rule.days - d else rule.days + d)
                            },
                            label = {
                                Text(DayOfWeek.of(d).getDisplayName(TextStyle.NARROW, Locale.getDefault()))
                            },
                        )
                    }
                }
            }

            item {
                Text("When blocked", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = rule.action == BlockAction.HOLD,
                        onClick = { rule = rule.copy(action = BlockAction.HOLD) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Hold until later") }
                    SegmentedButton(
                        selected = rule.action == BlockAction.DISCARD,
                        onClick = { rule = rule.copy(action = BlockAction.DISCARD) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Discard") }
                }
                Text(
                    if (rule.action == BlockAction.HOLD)
                        "Notifications are hidden and reappear after the window ends (within ~10 min)."
                    else "Notifications are dismissed permanently.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text("Apps (${rule.packages.size} selected)", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val list = apps
            if (list == null) {
                item { Text("Loading apps…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                val filtered = list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                    .sortedWith(compareByDescending<InstalledApp> { it.packageName in rule.packages }.thenBy { it.label.lowercase() })
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in rule.packages
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            rule = rule.copy(packages = if (checked) rule.packages - app.packageName else rule.packages + app.packageName)
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val bmp = remember(app.packageName) { app.icon.toBitmap(96, 96).asImageBitmap() }
                        Image(bmp, contentDescription = null, Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label)
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked = checked, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeButton(label: String, minutes: Int, onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    OutlinedButton(onClick = {
        TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, minutes / 60, minutes % 60, true).show()
    }) { Text("$label ${minutes.asClock()}") }
}

internal fun loadLaunchableApps(pm: PackageManager): List<InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .distinctBy { it.activityInfo.packageName }
        .map { ri ->
            InstalledApp(
                packageName = ri.activityInfo.packageName,
                label = ri.loadLabel(pm).toString(),
                icon = ri.loadIcon(pm),
            )
        }
        .sortedBy { it.label.lowercase() }
}
