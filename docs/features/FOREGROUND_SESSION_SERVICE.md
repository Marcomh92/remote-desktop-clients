# docs/features/FOREGROUND_SESSION_SERVICE.md

> Foreground `Service` that keeps the in-flight remote-desktop session process alive while the user is not looking at the canvas (device locked, app backgrounded, another window in front). Hosts a single notification with a content intent that returns the user to their live session.
>
> Read first: `ARCHITECTURE.md` §3.3 (disconnect → teardown), `features/CONNECTION_LIFECYCLE.md` §4, `DESIGN_PRINCIPLES.md` PAT-017 / INV-026.

---

## 1. Purpose

Android may kill background services when the user locks the device, backgrounds the app, or runs another app for a while. That termination dropped the active remote-desktop connection mid-session. A foreground service holds the process at foreground priority (immune to background-kill) and surfaces an ongoing notification so the user can see the session is alive and tap to return.

---

## 2. Component

| Field | Value |
|---|---|
| Class | `bVNC/src/main/java/com/iiordanov/bVNC/RemoteSessionService.java` (extends `android.app.Service`) |
| Library | `:bVNC` (shared UI module). Inherited by all 8 flavor wrappers via manifest merge — no per-flavor gating. |
| `NOTIFICATION_ID` | `0x52445353` ('RDSS') — stable so re-invocations update the existing notification in place |
| `CHANNEL_ID` | `remote_session_service` |
| Channel importance | `NotificationManager.IMPORTANCE_LOW` — does not beep or vibrate |
| Small icon | `@drawable/ic_screen_black_48dp` (existing vector screen icon, no new drawable) |
| Actions | `ACTION_START` = `com.iiordanov.bVNC.action.START_REMOTE_SESSION`, `ACTION_STOP` = `com.iiordanov.bVNC.action.STOP_REMOTE_SESSION` |

---

## 3. Lifecycle

| Step | Owner | Where |
|---|---|---|
| Service declared | Manifest `<service>` with `foregroundServiceType="dataSync"`, `exported="false"` | `bVNC/src/main/AndroidManifest.xml:95-98` |
| Permissions declared | `POST_NOTIFICATIONS` (API 33+), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` (API 34+) | `bVNC/src/main/AndroidManifest.xml:11-13` |
| Start | `RemoteCanvasActivity.onCreate` snapshots the launching Intent extras into `private Bundle sessionLaunchExtras` (field `:182`, assignment `:429`); after `REINIT_SESSION` is posted (`:432`), only when `connection.isReadyForConnection()` is true (`:426`), calls `startRemoteSessionService()` (`:433`); helper at `:1469-1486` reads `connection.getNickname()/getAddress()` and calls `RemoteSessionService.start(this, sessionLaunchExtras, nickname, address)`. See §4.1 for the Android 13+ `POST_NOTIFICATIONS` runtime prompt. | `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java:182, 426-434, 1469-1486` |
| Stop — canonical | `RemoteConnection.closeConnection` (single canonical close point for user- / remote- / error-initiated disconnects) calls `RemoteSessionService.stop(context)` at the very end | `bVNC/src/main/java/com/iiordanov/bVNC/protocol/RemoteConnection.java:315` (import `:44`) |
| Stop — defensive | `RemoteCanvasActivity.onDestroy` also calls `RemoteSessionService.stop(this)` after `closeConnection` so a leak is impossible even if the canonical path was skipped | `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java:1571` |
| Service handles STOP | `onStartCommand(ACTION_STOP)` → `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()`; returns `START_NOT_STICKY` | `RemoteSessionService.java:107-112` |
| Background-start guard | `RemoteSessionService.stop(...)` swallows `IllegalStateException` because `closeConnection` may run while the activity is already backgrounded (API 26+ restricts background `startService`) | `RemoteSessionService.java:83-90` |

The canonical start/stop pair is idempotent on both sides. `RemoteSessionService.start` re-sending on the same Intent only refreshes the notification; `RemoteSessionService.stop` on an already-stopped service is a no-op (besides the swallowed exception).

---

## 4. Notification

| Slot | Source | Fallback |
|---|---|---|
| Title | `connection.getNickname()` | `R.string.remote_session_notification_title` ("Remote session active") |
| Text | `connection.getAddress()` | `R.string.remote_session_notification_text` ("Tap to return to your session.") |
| Content intent | `PendingIntent` wrapping `RemoteCanvasActivity` with `FLAG_ACTIVITY_SINGLE_TOP \| FLAG_ACTIVITY_CLEAR_TOP \| FLAG_ACTIVITY_NEW_TASK` + `ACTION_MAIN + CATEGORY_LAUNCHER`, re-using the snapshot of the launching Intent's extras; flags `FLAG_UPDATE_CURRENT \| FLAG_IMMUTABLE` | Falls back to the package launcher intent if the activity class cannot be resolved (`GeneralUtils.getClassByName("com.iiordanov.bVNC.RemoteCanvasActivity")` at `RemoteSessionService.java:173`) |
| Builder flags | `setOngoing(true)`, `setOnlyAlertOnce(true)`, `setShowWhen(false)`, `setCategory(CATEGORY_SERVICE)`, `setPriority(PRIORITY_LOW)` | — |

Channel name and description are `remote_session_notification_channel_name` ("Active remote sessions") and `remote_session_notification_channel_description` ("Keeps the remote-desktop session alive while the app is in the background.") — `bVNC/src/main/res/values/strings.xml:369-372`.

Tapping the notification re-enters the `singleInstance` `RemoteCanvasActivity` (see `bVNC/src/main/AndroidManifest.xml:49`) with the original connection extras, so the user returns to the same session even if Android reaped the process while it was backgrounded.

### 4.1 Runtime POST_NOTIFICATIONS prompt (Android 13+)

`POST_NOTIFICATIONS` is a **runtime permission** on API 33+ — declared in `bVNC/src/main/AndroidManifest.xml:11`, requested at runtime by `RemoteCanvasActivity.startRemoteSessionService` (`:1469-1486`):

- Guarded by `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU` and `ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED`. The SDK constant is available on every API level; only the runtime prompt is gated.
- A registered `ActivityResultLauncher<String> notificationPermissionLauncher` (`:187-205`) fires the system permission UI via `notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)` (`:1477`).
- The service **starts regardless of the prompt outcome**. Foreground-service promotion and the connection keepalive are independent of notification visibility — per Android behavior, the OS keeps the foreground service running even when the user denies the permission, only the notification UI is suppressed.
- On grant, the launcher callback (`:189-204`) re-issues `RemoteSessionService.start(this, sessionLaunchExtras, nickname, address)` directly — bypassing `startRemoteSessionService()` to avoid re-entering the permission check. On deny, no callback side-effect runs; the service remains in the suppressed-notification state.

The launcher is registered once, near `sessionLaunchExtras` (`:187`), so the prompt can only be shown during an active `RemoteCanvasActivity` lifetime. On API < 33 the permission is install-time and no prompt fires.

---

## 5. Disconnect paths that funnel through the canonical close point

`RemoteConnection.closeConnection` (`:279-316`) is the single funnel. Every disconnect path reaches it:

| Path | Call site |
|---|---|
| User taps disconnect | `RemoteCanvasActivity.disconnectAndFinishActivity` (`:1456` → `remoteConnection.closeConnection()` at `:1457`) |
| Remote-initiated disconnect (RDP `RDP_CONNECT_FAILURE` / `RDP_UNABLE_TO_CONNECT` / `RDP_AUTH_FAILED`) | `RemoteCanvasHandler.handleMessage:490-504` → `showFatalMessageAndQuit` → `closeConnection` |
| Resolution-driven re-init (server dims differ) | `RdpCommunicator.OnSettingsChanged` → `REINIT_SESSION` — does **not** call `closeConnection`; the foreground service keeps running across the re-init since it was already started in the original `onCreate:433` |
| `DISCONNECT_NO_MESSAGE` / `DISCONNECT_WITH_MESSAGE` (user Back on progress dialog) | `RemoteCanvasHandler.handleMessage:479-481, :669-673` → `closeConnection` |
| `DISCONNECT_*` flavor / oVirt error paths | `RemoteCanvasHandler` → `disconnectAndShowMessage` (`RemoteConnection.java:146-154`) → `closeConnection` |
| `RemoteCanvasActivity.onDestroy` | `:1556` calls `remoteConnection.closeConnection()` (idempotent re-run) |

The defensive `RemoteSessionService.stop(this)` in `onDestroy:1571` is a belt-and-braces fallback for the rare path where `closeConnection` was skipped — `RemoteSessionService.stop` is itself idempotent, so calling it twice (once inside `closeConnection`, once in `onDestroy`) is safe.

---

## 6. Bounds

### In scope

- Foreground-service lifecycle (start / stop / idempotency).
- Notification construction (channel, importance, icon, title, text).
- Content intent that re-launches the live session via the snapshot of the original extras.
- Manifest permissions + `foregroundServiceType="dataSync"` (required by API 34+).
- Runtime `POST_NOTIFICATIONS` request on Android 13+ (see §4.1) — the system prompt and the on-grant re-issue of `startForeground`.

### Out of scope

- Per-connection user preference to enable / disable the service.
- Multi-instance connection handling — pre-existing `RemoteConnection.handler` static-field limitation (INV-005) is shared by the foreground service.
- User-visible "what is this notification?" help text.
- `OSP-0100` typed permission declarations.

---

## 7. Manifest inheritance

The service is declared in `bVNC/src/main/AndroidManifest.xml:95-98`. All eight `android-application` wrapper modules (`bVNC-app`, `freebVNC-app`, `aRDP-app`, `freeaRDP-app`, `aSPICE-app`, `freeaSPICE-app`, `Opaque-app`, `CustomVnc-app`) inherit the service and its permissions through the standard library-manifest merge — no per-flavor gating. The aRDP-only re-targeting in `MASTER.md` is about development focus, not a build-time restriction; the shared library still ships to every wrapper.

---

## 8. Where this doc ties into others

- `ARCHITECTURE.md` §3.3 — disconnect → teardown flow now ends with the foreground-service stop.
- `features/CONNECTION_LIFECYCLE.md` §4 — same flow at the feature level.
- `PATTERNS.md` PAT-017 — the cross-cutting foreground-service lifecycle pattern.
- `DESIGN_PRINCIPLES.md` INV-026 — the must-start-after-`isReadyForConnection`, must-stop-in-`closeConnection` invariant.