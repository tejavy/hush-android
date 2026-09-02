package space.tejaswi.hush.data

import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.util.UUID

/**
 * What to do with a blocked notification.
 *  HOLD    – snooze it; it re-surfaces once the rule stops applying (see HOLD_INTERVAL_MS).
 *  DISCARD – cancel it outright.
 */
@Serializable
enum class BlockAction { HOLD, DISCARD }

@Serializable
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New rule",
    val enabled: Boolean = true,
    val packages: Set<String> = emptySet(),
    /** ISO day-of-week values: 1 = Monday … 7 = Sunday. Refers to the day the window *starts*. */
    val days: Set<Int> = (1..7).toSet(),
    val allDay: Boolean = false,
    val startMinutes: Int = 22 * 60,
    val endMinutes: Int = 7 * 60,
    val action: BlockAction = BlockAction.HOLD,
)

/** The on-demand switch. */
@Serializable
enum class ManualMode {
    /** Follow rule schedules. */
    SCHEDULE,
    /** Block every app in every enabled rule right now, regardless of time. */
    BLOCK_NOW,
    /** Let everything through, ignoring schedules. */
    PAUSED,
}

@Serializable
data class AppState(
    val rules: List<Rule> = emptyList(),
    val manualMode: ManualMode = ManualMode.SCHEDULE,
    /** Notification logging (captures message text before the sender can delete it). */
    val logEnabled: Boolean = false,
    /** Empty = log every app. */
    val logPackages: Set<String> = emptySet(),
    val logRetentionDays: Int = 30,
)

sealed interface Decision {
    data object Allow : Decision
    data object Discard : Decision
    data object Hold : Decision
}

object RuleEngine {
    /**
     * Held notifications are snoozed in short chunks rather than "until the rule ends".
     * When a snooze expires Android re-posts the notification, the listener sees it again
     * and re-snoozes if the rule still applies. That makes manual mode changes, edited
     * schedules and reboots all self-heal without any alarm bookkeeping. Cost: up to this
     * much latency before held notifications reappear after a rule ends.
     */
    const val HOLD_INTERVAL_MS = 10L * 60 * 1000

    fun isActive(rule: Rule, now: LocalDateTime): Boolean {
        if (!rule.enabled) return false
        val today = now.dayOfWeek.value
        if (rule.allDay) return today in rule.days

        val m = now.hour * 60 + now.minute
        return if (rule.startMinutes <= rule.endMinutes) {
            today in rule.days && m >= rule.startMinutes && m < rule.endMinutes
        } else {
            // Window crosses midnight, e.g. 22:00 → 07:00
            val yesterday = now.dayOfWeek.minus(1).value
            (today in rule.days && m >= rule.startMinutes) ||
                (yesterday in rule.days && m < rule.endMinutes)
        }
    }

    fun decide(state: AppState, packageName: String, now: LocalDateTime): Decision {
        val applicable = when (state.manualMode) {
            ManualMode.PAUSED -> return Decision.Allow
            ManualMode.BLOCK_NOW -> state.rules.filter { it.enabled && packageName in it.packages }
            ManualMode.SCHEDULE -> state.rules.filter { packageName in it.packages && isActive(it, now) }
        }
        if (applicable.isEmpty()) return Decision.Allow
        // If any applicable rule says discard, discard wins.
        return if (applicable.any { it.action == BlockAction.DISCARD }) Decision.Discard else Decision.Hold
    }

    /** True if at least one rule is currently blocking something. Used by the tile subtitle. */
    fun anythingActive(state: AppState, now: LocalDateTime): Boolean = when (state.manualMode) {
        ManualMode.PAUSED -> false
        ManualMode.BLOCK_NOW -> state.rules.any { it.enabled && it.packages.isNotEmpty() }
        ManualMode.SCHEDULE -> state.rules.any { isActive(it, now) && it.packages.isNotEmpty() }
    }
}

fun Int.asClock(): String = "%02d:%02d".format(this / 60, this % 60)
