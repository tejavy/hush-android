package space.tejaswi.hush.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import space.tejaswi.hush.data.AppState
import space.tejaswi.hush.data.Decision
import space.tejaswi.hush.data.HushDatabase
import space.tejaswi.hush.data.LogExtractor
import space.tejaswi.hush.data.RuleEngine
import space.tejaswi.hush.data.StateRepository
import java.time.LocalDateTime

class HushListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Kept warm so onNotificationPosted never blocks on disk. */
    @Volatile private var state = AppState()

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            StateRepository.get(this@HushListenerService).state.collect { state = it }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        scope.launch { prune() }
        // Sweep anything already sitting in the shade when we (re)connect, e.g. after a reboot
        // or after the user grants the permission mid-evening.
        activeNotifications?.forEach { handle(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    private fun handle(sbn: StatusBarNotification) {
        // Ongoing = foreground services, media players, etc. Can't be cancelled and shouldn't be.
        if (sbn.isOngoing || sbn.packageName == packageName) return
        // Group summaries carry no content of their own; the children will be handled individually.
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return

        // Log first, block second — the whole point is to capture text before it can vanish.
        val s = state
        if (s.logEnabled && (s.logPackages.isEmpty() || sbn.packageName in s.logPackages)) {
            val entries = LogExtractor.extract(sbn)
            if (entries.isNotEmpty()) scope.launch {
                runCatching { HushDatabase.get(this@HushListenerService).log().insertAll(entries) }
                    .onFailure { Log.w(TAG, "log insert failed", it) }
            }
        }

        when (RuleEngine.decide(state, sbn.packageName, LocalDateTime.now())) {
            Decision.Allow -> Unit
            Decision.Discard -> {
                Log.d(TAG, "discard ${sbn.packageName}")
                cancelNotification(sbn.key)
            }
            Decision.Hold -> {
                Log.d(TAG, "hold ${sbn.packageName}")
                snoozeNotification(sbn.key, RuleEngine.HOLD_INTERVAL_MS)
            }
        }
    }

    private suspend fun prune() {
        val days = state.logRetentionDays.coerceAtLeast(1)
        HushDatabase.get(this).log().pruneBefore(System.currentTimeMillis() - days * 86_400_000L)
    }

    companion object {
        private const val TAG = "HushListener"
    }
}
