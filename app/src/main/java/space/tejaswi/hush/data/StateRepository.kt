package space.tejaswi.hush.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("hush")
private val KEY_STATE = stringPreferencesKey("state_json")
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Single source of truth. The whole state is one JSON blob – it's tiny (a handful of rules),
 * and DataStore gives us atomic writes and a Flow for free without dragging in Room.
 */
class StateRepository(private val context: Context) {

    val state: Flow<AppState> = context.dataStore.data.map { prefs ->
        prefs[KEY_STATE]?.let { runCatching { json.decodeFromString<AppState>(it) }.getOrNull() }
            ?: AppState()
    }

    suspend fun current(): AppState = state.first()

    suspend fun update(transform: (AppState) -> AppState) {
        context.dataStore.edit { prefs ->
            val old = prefs[KEY_STATE]?.let { runCatching { json.decodeFromString<AppState>(it) }.getOrNull() }
                ?: AppState()
            prefs[KEY_STATE] = json.encodeToString(transform(old))
        }
    }

    suspend fun setManualMode(mode: ManualMode) = update { it.copy(manualMode = mode) }

    suspend fun upsertRule(rule: Rule) = update { s ->
        val idx = s.rules.indexOfFirst { it.id == rule.id }
        s.copy(rules = if (idx >= 0) s.rules.toMutableList().apply { set(idx, rule) } else s.rules + rule)
    }

    suspend fun setLogEnabled(on: Boolean) = update { it.copy(logEnabled = on) }
    suspend fun setLogPackages(pkgs: Set<String>) = update { it.copy(logPackages = pkgs) }

    suspend fun deleteRule(id: String) = update { s -> s.copy(rules = s.rules.filterNot { it.id == id }) }

    companion object {
        @Volatile private var instance: StateRepository? = null
        fun get(context: Context): StateRepository =
            instance ?: synchronized(this) {
                instance ?: StateRepository(context.applicationContext).also { instance = it }
            }
    }
}
