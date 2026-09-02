# Hush

Per-app notification blocking on a schedule, plus a Quick Settings tile for on-demand blocking.

## How it works
- `HushListenerService` (a `NotificationListenerService`) sees every notification as it's posted.
- `RuleEngine` decides Allow / Hold / Discard from the rules + manual mode.
- **Hold** snoozes the notification in 10-minute chunks. When a snooze expires Android re-posts it,
  the listener re-evaluates, and re-snoozes if still blocked. No alarms, no boot receivers, and
  edits to rules or mode take effect on the next re-post automatically.
- **Discard** cancels it outright.
- `HushTileService` toggles Schedule ↔ Block now from Quick Settings.

## Notification log
Optional (off by default). Every notification the listener sees is written to a local Room DB
*before* any blocking decision. Messaging apps that use `MessagingStyle` (WhatsApp, Signal,
Telegram…) are stored one row per message with sender + timestamp, so a later
"This message was deleted" update can't erase what was already captured. Search, filter by app,
restrict to chosen apps, 30-day retention, clear button. Nothing leaves the device.

Limits: only what reaches the notification shade is captured — if the chat is open on screen, or
that chat's notifications are muted in WhatsApp, there's nothing to log. Media/images show up only
as their placeholder text.

## Build
Open in Android Studio (Ladybug or newer) and run. Then:
1. Grant notification access when prompted (Settings → Notifications → Notification access → Hush).
2. Add the Hush tile to Quick Settings (edit tiles → drag Hush in).

### Signed release

Release builds fail closed unless these user-scoped environment variables are present:

- `HUSH_KEYSTORE_PATH`
- `HUSH_STORE_PASSWORD`
- `HUSH_KEY_ALIAS`
- `HUSH_KEY_PASSWORD`

Keep the keystore and credentials outside the repository. Build and check a signed release with:

```powershell
.\gradlew.bat test lintRelease assembleRelease
```

## Known limits
- A blocked notification may still make a brief sound/vibration before it's snoozed — listeners
  act *after* the system posts it. To fully silence, use Hold together with the app's notification
  channel set to silent, or a system Mode.
- Ongoing notifications (foreground services, media) are never touched.
- Some OEMs (Xiaomi, Oppo, etc.) kill background listeners aggressively; whitelist Hush from
  battery optimisation if it stops working.
