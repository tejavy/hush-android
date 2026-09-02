package space.tejaswi.hush.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import space.tejaswi.hush.data.AppState
import space.tejaswi.hush.data.ManualMode
import space.tejaswi.hush.data.RuleEngine
import space.tejaswi.hush.data.StateRepository
import java.time.LocalDateTime

/**
 * Tap: toggle between "block now" and "follow schedule".
 * (PAUSED is only reachable from the app; a three-way cycle on a tile is confusing.)
 */
class HushTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onStartListening() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            job = s.launch {
                StateRepository.get(this@HushTileService).state.collect { render(it) }
            }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        val repo = StateRepository.get(this)
        scope?.launch {
            val mode = repo.current().manualMode
            repo.setManualMode(
                if (mode == ManualMode.BLOCK_NOW) ManualMode.SCHEDULE else ManualMode.BLOCK_NOW
            )
        }
    }

    private fun render(state: AppState) {
        val tile = qsTile ?: return
        val now = LocalDateTime.now()
        val subtitle = when (state.manualMode) {
            ManualMode.BLOCK_NOW -> {
                tile.state = Tile.STATE_ACTIVE
                "Blocking now"
            }
            ManualMode.SCHEDULE -> {
                tile.state = Tile.STATE_INACTIVE
                if (RuleEngine.anythingActive(state, now)) "Schedule active" else "Schedule idle"
            }
            ManualMode.PAUSED -> {
                tile.state = Tile.STATE_INACTIVE
                "Paused"
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.updateTile()
    }
}
