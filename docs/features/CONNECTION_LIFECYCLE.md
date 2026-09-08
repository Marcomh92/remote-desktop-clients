# docs/features/CONNECTION_LIFECYCLE.md

> What happens from "user taps Connect" through "first frame on screen" through "user taps Disconnect". Focus on the RDP flavor; VNC/SPICE/Opaque diverge only at clearly marked spots.
>
> Read first: `ARCHITECTURE.md` §3 (three flows), §4 (thread model), `DESIGN_PRINCIPLES.md` INV-001/INV-005/INV-008, `features/NATIVE_BRIDGE.md`.

---

## 1. Entry chain (aRDP flavor)

```
Android launcher icon
        |
        v
ConnectionGridActivity                          (bVNC/.../undatech/opaque/ConnectionGridActivity.java:130-207)
   onCreate
      ConnectionLoader.loadFromDatabase           (bVNC/.../undatech/opaque/util/ConnectionLoader.java:41)
      master password prompt (if enabled)
   user taps a bookmark
      launchConnection(view v)                    (ConnectionGridActivity.java:267)
         IntentHelper.getIntent                   (bVNC/.../com/undatech/opaque/IntentHelper.kt:14)
            Intent -> RemoteCanvasActivity
            extras = { connection-id / rdp://uri / ContentValues }
        v
RemoteCanvasActivity                              (bVNC/.../com/iiordanov/bVNC/RemoteCanvasActivity.java:113-1483)
   onCreate                                       (line 274)
      remoteConnection = RemoteConnectionFactory.build(context, canvas, ...)
      remoteCanvasHandler = RemoteCanvasHandler (message-driven UI dispatcher)
      handler.post(REINIT_SESSION)
   onResume/Start
      handler post -> setModes, etc.
   REINIT_SESSION handler case                    (RemoteCanvasHandler.java:510)
      remoteConnection.initializeConnection       (RemoteConnection.java:183)
         -> RemoteRdpConnection.initializeConnection:73-91 (overridden)
            -> super.initializeConnection()
            -> initializeRdpConnection()
               -> new RemoteRdpPointer(...)
               -> new RemoteRdpKeyboard(...)
            -> initializeClipboardMonitor()
            -> new Thread(this::startRdpConnection, "RdpConnectionThread").start()
   startRdpConnection                             (RemoteRdpConnection.kt:46)
      -> SSH tunnel if needed (getRemoteProtocolPort:98)
      -> canvas.waitUntilInflated()               (RemoteCanvas.java)
      -> compute remote W/H from canvas dimensions
      -> rdpComm.setConnectionParameters(...)     (RdpCommunicator.java:321)
      -> rdpComm.connect()
         -> SessionState.connect(context)         (freeRDPCore/.../SessionState.java:69)
            -> LibFreeRDP.setConnectionInfo(...) (line 243)
            -> LibFreeRDP.connect(instance)       (line 198)
               -> System.loadLibrary("freerdp-android")
               -> native freerdp_connect(inst)    (C side)
```

---

## 2. Connection / disconnection messages

`RemoteCanvasHandler.handleMessage` (`bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteCanvasHandler.java:370-689`) is the UI-thread dispatcher for messages arriving from the protocol layer.

### RDP-relevant message IDs

| ID | Trigger | Handler effect |
|---|---|---|
| `REINIT_SESSION` | Boot / reconnect / resolution-driven | `remoteConnection.initializeConnection()`; dismiss progress dialog on success |
| `GRAPHICS_SETTINGS_RECEIVED` | Server pushed new W/H/bpp | `graphicsSettingsReceived = true; notifyAll()` on rfbConn. Dismisses progress dialog. |
| `GRAPHICS_FIRST_FRAME_RECEIVED` | First RDP frame painted | Dismisses progress dialog if not already dismissed |
| `RDP_CONNECT_FAILURE` | Generic connect failure | If `maintainConnection`, call `showFatalMessageAndQuit` |
| `RDP_UNABLE_TO_CONNECT` | Network/auth failure (after one empty-creds retry) | Same |
| `RDP_AUTH_FAILED` | Auth rejected | Same |
| `GET_RDP_CREDENTIALS` | Server demands new credentials mid-session | Show credential prompt |
| `GET_RDP_GATEWAY_CREDENTIALS` | Gateway auth demand | Show credential prompt |
| `DIALOG_RDP_CERT` | Unknown certificate | Show fingerprint prompt; on yes, `setCertificateAcceptedAndNotifyListeners` |
| `SERVER_CUT_TEXT` | Server clipboard push | Update local clipboard |
| `DISCONNECT_NO_MESSAGE` | User Back on progress dialog | `closeConnection(); Utils.justFinish(context)` |
| `DISCONNECT_WITH_MESSAGE` | User cancelled | Same + dialog |

### State-machine shape (RDP)

There is no enum-based state machine. The flags involved:

| Flag | Where | Meaning |
|---|---|---|
| `isInNormalProtocol` | `RdpCommunicator` field | True once `OnConnectionSuccess` fires and before `OnDisconnecting`. Gates all input. |
| `maintainConnection` | `RemoteConnection.maintainConnection` | True between `initializeConnection()` and the user-driven `closeConnection()`. Controls whether to show fatal-message dialog. |
| `graphicsSettingsReceived` | `RemoteConnection.graphicsSettingsReceived` | Used to gate resolution-driven reconnection. |
| `authenticationAttempted`, `gatewayAuthenticationAttempted` | `RdpCommunicator` | Set true after first auth attempt; drives retry behavior. |
| `reattemptWithoutCredentials` | `RdpCommunicator.OnDisconnecting:427-452` | One-shot flag for the empty-creds retry to disambiguate network vs. auth failure. |

### Reconnect logic (RDP)

| Trigger | Effect |
|---|---|
| `OnDisconnecting` w/ `reattemptWithoutCredentials && !isInNormalProtocol` | Re-init session once with empty credentials to distinguish error class. |
| `OnDisconnecting` w/ `authenticationAttempted && !isInNormalProtocol` | Post `GET_RDP_CREDENTIALS` for user re-entry. |
| `OnSettingsChanged` when server dims differ from saved | Persist new dims, post `REINIT_SESSION`. |
| User taps a bookmark | Full new session (no in-place reconnect). |

There is **no mid-session reconnect of the transport**. The session reconnect cookie MS-RDPEFS equivalent is **not implemented** (see gap in §6).

---

## 3. Framebuffer pipeline

### 3.1 Push pipeline (FreeRDP → JVM → canvas)

```
native FreeRDP graphics subsystem
   |
   v  (JNI callback)
LibFreeRDP.OnGraphicsUpdate(long inst, int x, int y, int w, int h)       [static, LibFreeRDP.java:619]
   |
   v
RdpCommunicator.OnGraphicsUpdate(int x, int y, int w, int h)            [RdpCommunicator.java:574]
   |
   +--> Bitmap bitmap = viewable.getBitmap()                              [viewable.getBitmap:668 -> AbstractBitmapData.getMbitmap]
   |
   +--> LibFreeRDP.updateGraphics(instance, bitmap, x, y, w, h)           [LibFreeRDP.java:486, native]
   |       freeRDP writes pixels into the supplied Bitmap at (x,y,w,h)
   |
   +--> viewable.reDraw(x, y, w, h)                                       [RemoteCanvas.reDraw:681]
           postInvalidate(rect) after a 60Hz throttle (16.6666 ms); fall back to 10Hz when behind.
```

### 3.2 `RemoteCanvas.reDraw` — the backpressure gate

`RemoteCanvas.reDraw(int, int, int, int)` at `:681-696`:

```text
now = SystemClock.uptimeMillis
if (now - lastDraw > 16.6666) {
    postInvalidate(rect, ...)            // immediate
    lastDraw = now
} else {
    handler.postDelayed(invalidateCanvasRunnable, 100)  // coalesce to 10Hz
}
```

`invalidateCanvasRunnable = this::postInvalidate` (`RemoteCanvas.java:153`).

### 3.3 Bitmap allocation & recycling

`UltraCompactBitmapData` (`bVNC/.../UltraCompactBitmapData.java`):

| Operation | Method | Line |
|---|---|---|
| Constructor | `mbitmap = Bitmap.createBitmap(w, h, ARGB_8888)`; `memGraphics = new Canvas(mbitmap)`; `setHasAlpha(false)` | `:38-60` |
| Native write entry | `updateBitmap(Bitmap src, int x, int y, int w, int h)` — `synchronized (mbitmap) { memGraphics.drawBitmap(src, x, y, null) }` | `:82-87` |
| Draw into ImageView | `UltraCompactBitmapDrawable.draw(canvas)` — `synchronized (this) { canvas.drawBitmap(data.mbitmap, 0f, 0f, paint); canvas.drawBitmap(softCursor, cursorRect.left, cursorRect.top, paint); }` | `:148-156` |
| Dispose | `AbstractBitmapData.dispose:248-261` — recycles `mbitmap`, nulls `drawable`, `memGraphics` |

**Allocation strategy** — `RemoteCanvas.reallocateMyDrawable:331-354`:

- For RDP (`!isVnc`), always `UltraCompactBitmapData` — one bitmap equal to framebuffer size, ARGB_8888.
- VNC branches between `FullBufferBitmapData`, `CompactBitmapData`, and `UltraCompactBitmapData` based on `BCFactory.getInstance().getBCActivityManager().getMemoryClass(...)`.

### 3.4 Resolution change

`RdpCommunicator.OnSettingsChanged(int width, int height, int bpp)` (`:467-478`):

- Sends `GRAPHICS_SETTINGS_RECEIVED` message.
- If server dims differ from saved (`connection.getRdpWidth/Height`), calls `saveConnectionSettings(...)` and posts `REINIT_SESSION`.
- Otherwise calls `viewable.reallocateDrawable(width, height)` directly.

### 3.5 Coordinate scaling / zoom

- `RemoteCanvas.computeShiftFromFullToView:488-495` — computes `shiftX/shiftY` for input coordinate translation.
- `RemoteCanvas.resetScroll:500-506` — `scrollTo(((absoluteXPosition - shiftX) * scale), ...)`.
- `AbstractScaling` (`bVNC/src/main/java/com/iiordanov/bVNC/AbstractScaling.java:33`) defines scale modes: `FitToScreenScaling`, `OneToOneScaling`, `ZoomScaling` (default).
- `ZoomScaling.changeZoom:130` — applies pinch-to-zoom with focus-point math. Bounds: `[minimumScale, 4.0]`.

---

## 4. Disconnect / teardown

```
User taps Disconnect (or another window comes forward)
   |
   v
RemoteCanvasActivity.disconnectAndFinishActivity:1181
   remoteConnection.closeConnection(); Utils.justFinish(this)
                                                |
                                                v
RemoteConnection.closeConnection:278-310
   1. maintainConnection = false
   2. keyboard.clearMetaState()                    (only onScreenMetaState; see INV-008)
   3. send a dummy key-up to free any held VM keys
   4. rfbConn.close()                              -> RdpCommunicator.close
        - isInNormalProtocol = false
        - Spawn DisconnectThread                   -> calls LibFreeRDP.disconnect (native freerdp_disconnect)
   5. connectionThread.interrupt()                 (the one from RemoteRdpConnection.initializeConnection)
   6. handler.removeCallbacksAndMessages(null)
   7. sshConnection.terminateSSHTunnel()           (if SSH tunnel was used)
   8. canvas.writeScreenshotToFile(...)            (if thumbnails enabled)
   9. onDestroy()                                  -> cancels clipboardMonitorTimer, nulls clipboard monitor/decoder

Activity onDestroy
   remoteConnection.closeConnection()              (idempotent re-run)
   System.gc()
```

`RdpCommunicator.close` (`:204-210`) does not explicitly shut down `inputExecutor` or join `DisconnectThread` — both leak until process death. See `DESIGN_PRINCIPLES.md` §"Known gaps".

---

## 5. Multi-instance / multi-window considerations

- Each `RdpCommunicator` owns its own `inputExecutor`, `DisconnectThread`, and `sessionMap` entry.
- `RdpCommunicator.patchFreeRdpCore()` (`:100-112`) replaces `GlobalApp.sessionMap` (a plain `HashMap` upstream) with `Collections.synchronizedMap(...)` to support multiple sessions.
- `RemoteConnection.handler` is a **static** field — this is the documented single-instance limitation. See INV-005 and the gap listing.
- `ConnectionGridActivity` does not enforce a limit on launched connections.

For the planned aRDP work, this is mostly relevant if you start a connection while another is in flight (rare).

---

## 6. Known gaps in lifecycle

| Gap | Where | Why this matters |
|---|---|---|
| `RemoteConnection.handler` is a static field | `RemoteConnection.java` | Multi-instance racing |
| `hardwareMetaState` and `remoteKeyboardMetaState` not reset on close | `RemoteConnection.closeConnection`, `RdpCommunicator.close` | Modifier leak across reconnects (INV-008) |
| No explicit shutdown of `inputExecutor` / `DisconnectThread` | `RdpCommunicator.java:62, ~210` | Thread leak; low priority |
| `OnSettingsChanged` path calls `viewable.reallocateDrawable` from the native callback thread | `RdpCommunicator:467-478` | Mitigated by `synchronized (RemoteCanvas.this)`; bitmap allocation runs on the native thread — long-running OOM can stall the native render path |
| `MyDrawable.dispose` is only called on the reallocate-error path | `RemoteCanvas.java:348` | Slow leak under many rotations |
| No RDP reconnect cookie | n/a | Multi-window tab reconnection would need this |
| `GlobalApp.sessionMap` workaround done via reflection (`patchFreeRdpCore`) | `RdpCommunicator:100-112` | Should be contributed upstream |

---

## 7. Where to look when something is wrong

| Symptom | First file to check |
|---|---|
| Blank canvas after connect | `RemoteCanvasHandler.handleMessage:635-646` (progress dialog dismiss logic), `UltraCompactBitmapData.updateBitmap:82` |
| Pointer events not reaching remote | `RemoteRdpPointer.sendPointerEvent:107`, `RdpCommunicator.inputExecutor`, `isInNormalProtocol` |
| Keyboard events not reaching remote | `RemoteRdpKeyboard.processLocalKeyEvent:36-89`, `RdpCommunicator.processVirtualKey:239` |
| "Disconnect right after connect" | `RdpCommunicator.OnDisconnecting:427-452`, `RemoteCanvasHandler:RDP_*_FAILED` |
| Stuck on progress dialog | `GRAPHICS_FIRST_FRAME_RECEIVED` not firing — check `LibFreeRDP.OnGraphicsUpdate:619` dispatch |
| Bitmap recycled too early | `UltraCompactBitmapData.dispose:248` called from rotation/error path |
