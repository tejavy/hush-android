package space.tejaswi.hush.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import space.tejaswi.hush.data.AppState
import space.tejaswi.hush.data.BlockAction
import space.tejaswi.hush.data.ManualMode
import space.tejaswi.hush.data.Rule
import space.tejaswi.hush.data.RuleEngine
import space.tejaswi.hush.data.StateRepository
import space.tejaswi.hush.data.asClock
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HushTheme { HushApp() } }
    }
}

@Composable
fun HushTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun HushApp() {
    val ctx = LocalContext.current
    val repo = remember { StateRepository.get(ctx) }
    val state by repo.state.collectAsStateWithLifecycle(initialValue = AppState())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Rule?>(null) }
    var showLog by remember { mutableStateOf(false) }

    if (showLog) {
        BackHandler { showLog = false }
        LogScreen(state = state, onBack = { showLog = false })
        return
    }

    editing?.let { rule ->
        BackHandler { editing = null }
        RuleEditorScreen(
            initial = rule,
            onSave = { scope.launch { repo.upsertRule(it) }; editing = null },
            onDelete = { scope.launch { repo.deleteRule(rule.id) }; editing = null },
            onBack = { editing = null },
        )
        return
    }

    HomeScreen(
        state = state,
        onModeChange = { scope.launch { repo.setManualMode(it) } },
        onToggleRule = { rule, on -> scope.launch { repo.upsertRule(rule.copy(enabled = on)) } },
        onEdit = { editing = it },
        onAdd = { editing = Rule() },
        onOpenLog = { showLog = true },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: AppState,
    onModeChange: (ManualMode) -> Unit,
    onToggleRule: (Rule, Boolean) -> Unit,
    onEdit: (Rule) -> Unit,
    onAdd: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val ctx = LocalContext.current
    var listenerEnabled by remember { mutableStateOf(isListenerEnabled(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { listenerEnabled = isListenerEnabled(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hush") },
                actions = {
                    IconButton(onClick = onOpenLog) { Icon(Icons.Default.History, contentDescription = "Notification log") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add rule") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!listenerEnabled) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Notification access required", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Hush can't see or hide notifications until you allow it in system settings.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("Open settings") }
                    }
                }
            }

            item {
                Text("Mode", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = listOf(
                        ManualMode.SCHEDULE to "Schedule",
                        ManualMode.BLOCK_NOW to "Block now",
                        ManualMode.PAUSED to "Paused",
                    )
                    modes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = state.manualMode == mode,
                            onClick = { onModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tip: add the Hush tile to Quick Settings for a one-tap “Block now” switch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.rules.isEmpty()) item {
                Text(
                    "No rules yet. Tap + to pick some apps and a time window.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(state.rules, key = { it.id }) { rule ->
                RuleCard(rule, state, onToggle = { onToggleRule(rule, it) }, onClick = { onEdit(rule) })
            }
        }
    }
}

@Composable
private fun RuleCard(rule: Rule, state: AppState, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val activeNow = state.manualMode != ManualMode.PAUSED &&
        (state.manualMode == ManualMode.BLOCK_NOW && rule.enabled || RuleEngine.isActive(rule, LocalDateTime.now()))
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append(if (rule.allDay) "All day" else "${rule.startMinutes.asClock()} – ${rule.endMinutes.asClock()}")
                        append(" · ")
                        append(daysLabel(rule.days))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${rule.packages.size} app${if (rule.packages.size == 1) "" else "s"} · " +
                        (if (rule.action == BlockAction.HOLD) "hold" else "discard") +
                        (if (activeNow) " · ACTIVE" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (activeNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
    }
}

fun daysLabel(days: Set<Int>): String = when {
    days.size == 7 -> "Every day"
    days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
    days == setOf(6, 7) -> "Weekends"
    else -> days.sorted().joinToString(" ") {
        DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
}

fun isListenerEnabled(ctx: android.content.Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
