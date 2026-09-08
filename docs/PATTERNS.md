# PATTERNS.md

Recurring code idioms used across `:bVNC` and `:remoteClientLib`. Each pattern has a stable identifier (`PAT-*`).

---

## PAT-001 — Protocol-per-flavor

**Rule.** Each remote-desktop protocol (RDP, VNC, SPICE, oVirt/RHEV/Proxmox) gets its own subclass of `RemoteConnection` and its own `Communicator` implementing `RfbConnectable`. The Activity is flavor-agnostic; selection happens at runtime.

**Where.** `RemoteConnectionFactory.kt:35-56` branches on `Utils.isRdp/isVnc/isSpice/isOpaque`. Result is stored as `RemoteConnection rf` and accessed via the `pointerInputHandler` and `keyInputHandler` fields (which the base class itself implements).

**Per-flavor concrete classes.**

| Flavor | `RemoteConnection` subclass | `RfbConnectable` impl | Pointer / Keyboard |
|---|---|---|---|
| RDP | `bVNC/src/main/java/com/iiordanov/bVNC/protocol/RemoteRdpConnection.kt` | `remoteClientLib/src/main/java/com/undatech/opaque/RdpCommunicator.java` (uses vendored `LibFreeRDP`) | `RemoteRdpPointer` + `RemoteRdpKeyboard` |
| VNC | `bVNC/src/main/java/com/iiordanov/bVNC/protocol/RemoteVncConnection.kt` | **inline** (no separate Communicator class; `RemoteConnection` implements `RfbConnectable` and RFB wire bytes live in `RemoteVncConnection` / `RemoteVncKeyboard` / `RemoteVncPointer`) | `RemoteVncPointer` + `RemoteVncKeyboard` (in `:bVNC/input/`) |
| SPICE | `bVNC/src/main/java/com/iiordanov/bVNC/protocol/RemoteSpiceConnection.kt` | `remoteClientLib/src/main/java/com/undatech/opaque/SpiceCommunicator.java` (uses `libspice.so`) | `RemoteSpicePointer` + `RemoteSpiceKeyboard` |
| oVirt | `bVNC/src/main/java/com/iiordanov/bVNC/protocol/RemoteOvirtConnection.kt` | `LibFreeRDP` then SPICE | RDP pointer/keyboard |
| Proxmox | `bVNC/src/main/java/com/iiordanov/bVNC/protocol/RemoteProxmoxConnection.kt` | `RemoteOpaqueConnection` -> oVirt REST → SPICE | SPICE |
| Opaque | `bVNC/src/main/java/com/iiordanov/bVNC/protocol/RemoteOpaqueConnection.kt` | reads .vv file → SPICE | SPICE |

**How to add a new flavor** (if ever needed): write a `RemoteXxxConnection` subclass + an `XxxCommunicator` + a `RemoteXxxPointer` + `RemoteXxxKeyboard` + `XxxKeyboardMapper`, then add a branch in `RemoteConnectionFactory.build` and a new `Utils.isXxx` helper.

---

## PAT-002 — Modifier-key state lives in three places

**Rule.** Modifier state (Ctrl/Alt/Shift/Super) is tracked in three independent variables on different instances. Each has a different lifetime, and they are recombined at the protocol boundary.

| Variable | Owner | Lifetime | Purpose |
|---|---|---|---|
| `hardwareMetaState` | `RemoteKeyboardState` on `RdpCommunicator.remoteKeyboardState` | Until key event drops to zero | Tracks L/R Ctrl/Alt/Shift/Super from real key events (Android keycodes + scancodes). Used to deduplicate VK sends. |
| `onScreenMetaState` | `RemoteRdpKeyboard` (per-flavor) | Until `clearMetaState()` (called from `ExtraKeysPagerAdapter.syncKeyboardModifierState`) | Tracks the sticky on-screen modifier buttons (CTRL/ALT/SHIFT/SUPER on the bottom pager). |
| `remoteKeyboardMetaState` | `RemoteKeyboardState` | Until `RdpCommunicator.close` | "Already sent" state used by `shouldSendModifier(...)` to avoid duplicating modifier-down/up. |

**Reconstitution.** At the protocol-method boundary, the handler composes the effective metaState, typically `onScreenMetaState | metaState` (RDP/SPICE) or `onScreenMetaState | hardwareMetaState | metaState` (VNC). For RDP specifically, the line `int combinedMetaState = metaState | remoteInput.getKeyboard().getMetaState();` in `RemoteRdpPointer.sendPointerEvent` (`RemoteRdpPointer.java:112`) is the only direct cross-class read.

**Files to read in this pattern.**
- `remoteClientLib/src/main/java/com/undatech/opaque/input/RemoteKeyboard.java` (lines 33-63: mask constants; 71-80: state vars; 285-371: `convertEventMetaState`; 391-416: `shouldDropModifierKeys`).
- `remoteClientLib/src/main/java/com/undatech/opaque/input/RemoteKeyboardState.java` (36-192: `detectHardwareMetaState`; 194-214: `shouldSendModifier`; 216-222: `updateRemoteMetaState`).
- `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpKeyboard.java:36` (the `processLocalKeyEvent` gate).
- `remoteClientLib/src/main/java/com/undatech/opaque/RdpCommunicator.java:222` (`sendModifierKeys`).

**Why three places.** Each maps onto a different lifecycle:
1. Hardware modifiers come and go per `KeyEvent`. They need to be derived on the fly and remembered just long enough to avoid a double-send.
2. On-screen sticky modifiers persist across many keys until the user toggles them; syncing them to the visual state requires `ExtraKeysPagerAdapter.syncKeyboardModifierState`.
3. The "already sent" state is the protocol's own bookkeeping — it should not leak past the connection.

**Anti-pattern.** Editing one variable without the others. The most likely refactor for "modifier handling changes" is to consolidate these into one state object; before doing so, enumerate all readers and writers (use `gitnexus_query({query: "hardwareMetaState onScreenMetaState"})`).

---

## PAT-003 — Single input executor per `RdpCommunicator`

**Rule.** Every keyboard/mouse event sent to RDP is enqueued on `RdpCommunicator.inputExecutor`, a single-thread, `Thread.MAX_PRIORITY` executor. Name: `SendRdpInputThread`.

**Why.** Deterministic event ordering on the wire. Without it, two threads (UI + a hardware-key worker) could interleave modifier-down vs. modifier-up events and confuse the remote OS. The `3ms` / `5ms` throttle between sends (`RdpCommunicator.java:289-294`) lives on this thread.

**What NOT to do.**
- Do not call `LibFreeRDP.sendKeyEvent` / `sendCursorEvent` / `sendUnicodeKeyEvent` directly from the UI thread.
- Do not create a second executor per send — that defeats the ordering guarantee.
- Do not bypass the executor for "fast path" optimizations — the order of VKs matters.

**Where.** `RdpCommunicator.java:62-69` (executor declaration), `writePointerEvent` (`:156`), `writeKeyEvent` (`:179`), `sendModifierKeys` (`:222`), `processVirtualKey` (`:239`), `processUnicodeKey` (`:263`).

---

## PAT-004 — Sticky modifier UI synced to the keyboard via the INV-010 bridge

**Rule.** On-screen CTRL/ALT/SHIFT/SUPER buttons live in two places: the legacy 3-page `ExtraKeysView` pager (VNC/SPICE/Opaque) and the RDP-only `ModifierRowView` (the new 8-key row above the IME). Both implement the same canonical bridge — `clearMetaState()` + `onScreen*Toggle()` per active modifier — to keep `RemoteRdpKeyboard.onScreenMetaState` in agreement with the visual state.

**Where.**
- Legacy (VNC / SPICE / Opaque):
  - `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ExtraKeysView.java`
  - `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ExtraKeysPagerAdapter.java:106-155`
  - `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RemoteExtraKeysHandler.java:60-87`
  - `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/SpecialButton.java`
  - `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/SpecialButtonState.java`
  - `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ExtraKeysConstants.java:40-69`
- RDP-only:
  - `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ModifierRowView.java` — three-state per modifier (OFF / ON one-shot / LOCKED). 800 ms double-tap = LOCKED (`DOUBLE_TAP_WINDOW_MS` matches `RdpKeyboardMapper.checkToggleModifierLock:712-730`).
  - `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RdpModifierRowHandler.java:308-317` — `syncRowStateToKeyboard()` is the INV-010 bridge for the RDP row.

**Flow (RDP row).**
1. User taps Ctrl on `ModifierRowView`. State transitions OFF → ON (one-shot); double-tap within 800 ms promotes ON → LOCKED.
2. `onRowModifierStateChanged:298-300` fires. `syncRowStateToKeyboard` clears `onScreenMetaState` and re-applies every modifier where `rowView.isOnOrLocked(...)` is true.
3. The next non-modifier key dispatch triggers `RemoteRdpKeyboard.fireKeyDispatchedIfApplicable` (INV-016). The handler's `onKeyDispatched:351-355` calls `rowView.consumeOnModifiers()`, which clears ON (non-locked) modifiers and re-bridges LOCKED ones.
4. Pointer events do NOT consume modifiers. Locked modifiers survive across multiple dispatched keys; tap to release.

**Locking.** Double-tap within 800 ms = lock the modifier ON. Locked modifiers are preserved by `RdpKeyboardMapper.resetModifierKeysAfterInput` (`:668-688`) AND by `ModifierRowView`'s own state machine. `clearlAllModifiers()` (`:664-666`) passes `force=true` — the canonical reset path that remains unused outside `RemoteConnection.closeConnection`.

---

## PAT-005 — Framebuffer is one shared Bitmap, painted by ImageView

**Rule.** The framebuffer is `AbstractBitmapData.mbitmap`, allocated as ARGB_8888 for RDP. The View is `RemoteCanvas extends AppCompatImageView`. Native renders into the bitmap via JNI; the ImageView just paints the bitmap with current zoom matrix.

**Implications.**
- Touch coordinates must be transformed by the inverse of `Matrix` to get framebuffer-pixel coordinates — see `RemoteCanvas.computeShiftFromFullToView` (`:488`).
- Resize happens only on server-driven resolution change (`OnSettingsChanged`), not on View resize.
- The drawable on the View is replaced via `setImageDrawable` on a posted Runnable (`drawableSetter` in `RemoteCanvas.java:158`), not synchronously, to avoid touching the View from the native render thread.

**Where.**
- `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java`
- `bVNC/src/main/java/com/iiordanov/bVNC/AbstractBitmapData.java:177`
- `bVNC/src/main/java/com/iiordanov/bVNC/UltraCompactBitmapData.java:82`

---

## PAT-006 — Bitmap-aware static-init for native libraries

**Rule.** Native libraries are loaded in the static initializer of the JVM-side communicator:
- `LibFreeRDP.java:80-108` — loads `libfreerdp-android.so`, then calls `freerdp_get_jni_version()` to verify a minimum version (>= 2.5.1) and then probes H.264 support.
- `SpiceCommunicator.java:64-67` — loads `libspice.so` and `libgstreamer_android.so`.

Any class that holds JNI state must not be instantiated before the static initializer runs. In practice this is fine because both classes are constructed once, lazily, from `RemoteConnectionFactory.build`.

---

## PAT-007 — Strict canvas layout with overlays

**Rule.** The canvas Activity uses a single `FrameLayout` (`canvasLayout`) and stacks overlays on top of the `RemoteCanvas` ImageView:

| Z-order | Element |
|---|---|
| Top of stack | `keyboardToggleButton @+id/keyboardToggleButton` (RDP-only, last child so it stays on top; default `gone`) |
| ^ | `singleHandOpts` overlay (visible only in single-handed input mode) |
| ^ | `extraKeysToolbar` ViewPager (bottom, hidden unless extra keys are on; suppressed on RDP) |
| ^ | `keyboardIconForAndroidTv` (TV only) |
| ^ | `RemoteToolbar` (set as support action bar; right side) |
| ^ | `rdpInputAreaContainer @+id/rdpInputAreaContainer` (RDP-only, anchored to bottom; default `gone`) |
| Bottom | `RemoteCanvas` (fills parent) |

This stack order is fixed in `bVNC/src/main/res/layout/canvas.xml` (mirrored in `layout-large/canvas.xml`). Adding overlays requires editing that XML in the same order.

---

## PAT-008 — Session thread model

**Rule.** A long-running session uses three threads (described in `ARCHITECTURE.md` §4). The pattern to remember:

| Thread | Created in | Killed in |
|---|---|---|
| `connectionThread` | `RemoteRdpConnection.initializeConnection:73-91` | `RemoteConnection.closeConnection:278-310` (via `interrupt`) |
| `inputExecutor` ("SendRdpInputThread") | `RdpCommunicator` field initializer | Never explicitly — leaks until process death |
| `DisconnectThread` | `RdpCommunicator.close:204-210` | Self-terminates when `freerdp_disconnect` returns; not joined |

Future refactors should normalize the leak (shut down `inputExecutor` in `close`, or move to a daemon ScheduledExecutorService).

---

## PAT-009 — `RemoteCanvasHandler` message IDs

**Rule.** All UI-thread communication from the protocol layer to the Activity uses integer message IDs defined in `RemoteClientLibConstants`. The `RemoteCanvasHandler.handleMessage` switch in `RemoteCanvasHandler.java` is the dispatcher.

**Common IDs.**
- `REINIT_SESSION` — boot / rebuild / reconnect
- `GRAPHICS_SETTINGS_RECEIVED`, `GRAPHICS_FIRST_FRAME_RECEIVED` — server-driven resolution / first frame
- `RDP_CONNECT_FAILURE`, `RDP_UNABLE_TO_CONNECT`, `RDP_AUTH_FAILED` — error paths
- `GET_RDP_CREDENTIALS`, `GET_RDP_GATEWAY_CREDENTIALS` — mid-session auth prompts
- `DIALOG_RDP_CERT` — certificate fingerprint prompt
- `SERVER_CUT_TEXT` — server clipboard push
- `DISCONNECT_NO_MESSAGE`, `DISCONNECT_WITH_MESSAGE` — user-back on progress dialog

**Where.** `remoteClientLib/src/main/java/com/undatech/opaque/RemoteClientLibConstants.java` (definitions), `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteCanvasHandler.java:370-689` (dispatcher).

---

## PAT-010 — Settings via SharedPreferences in `generalSettings`

**Rule.** All cross-connection global preferences live in a single SharedPreferences file `"generalSettings"`. The wrappers `Utils.querySharedPreferenceBoolean/String/Int` and `Utils.setSharedPreference*` handle every read/write.

**Where.** `bVNC/src/main/java/com/iiordanov/bVNC/Utils.java:541-602`. The XML tree is built at runtime by `GlobalPreferencesFragment` from `bVNC/src/main/res/xml/global_preferences*.xml` (base + flavor overlay).

---

## PAT-011 — Per-connection columns in one SQLite row

**Rule.** Each saved connection is one row in the `CONNECTION_BEAN` table. There are ~107 columns including RDP-specific ones (`RDPDOMAIN`, `REMOTEFX`, `RDPGATEWAYHOSTNAME`, `RDPSECURITY`, `ENABLEGFX`, etc.). ConnectionBean extends `AbstractConnectionBean`, which holds all `GEN_FIELD_*` constants and a single `GEN_CREATE` SQL string.

**Implication.** Adding a setting means: add a column on `AbstractConnectionBean`, add a `DBV_*` constant + migration step in `Database.java`, add a UI field in `aRDP.java` + `main_rdp.xml`. There is no "settings are key/value" option.

**Where.**
- `bVNC/src/main/java/com/iiordanov/bVNC/AbstractConnectionBean.java` — the row schema
- `bVNC/src/main/java/com/iiordanov/bVNC/ConnectionBean.java` — operations
- `bVNC/src/main/java/com/iiordanov/bVNC/Database.java` — schema migrations

---

## PAT-012 — StrictMode opt-out, not opt-in

**Rule.** `RemoteCanvasActivity.onCreate` (`:294-297`) installs `StrictMode.ThreadPolicy.permitAll().build()`. This is intentional: FreeRDP callbacks run on native threads and the JVM-side `updateBitmap` path would otherwise trip violations even with proper synchronization. `VmPolicy` is left at default.

**Consequence.** Other Activities do **not** need this. Only the canvas host disables StrictMode.

---

## PAT-013 — RDP-only touchpad gestures, gated by `setRdp(boolean)`

**Rule.** `TouchInputHandlerTouchpad` extends `TouchInputHandlerGeneric` with three gestures that match the Microsoft RDP Android app. They are gated by `setRdp(boolean)` so non-RDP touchpad sessions keep the legacy behaviour.

| Gesture | Where | Wire mapping |
|---|---|---|
| Cursor fling | `TouchInputHandlerTouchpad.java:247-280` + inner `Flinger:607-676` | `pointer.moveMouse(...)` per tick. `FLING_TICK_MS=20`, `FLING_NOISE_PX_PER_S=200` floor. The per-tick damping factor is a runtime-configurable instance field `flingDamp` (default `FLING_DAMP=0.86f`); `RemoteCanvasActivity.getFlingResistanceDamp` (slider → `0.92f - slider*0.01f`, default slider 6 → 0.86 = legacy) pushes it via `setFlingDamp:111-113` from `getInputHandlerById:1261-1263` and `onResume:911-913`. Velocity scaled by `cbrt(zoom) * sensitivity / density`. Edge-clamped ticks stop the flinger early. Cancels on new touch-down and returns false while the adaptive double-tap state machine is `PENDING` or `DRAGGING`. |
| Long-press = synthesized right click | `onLongPress:311-344` | `pointer.rightButtonDown(x, y, meta)` immediately, then a `viewable.getHandler().postDelayed(... releaseButton, 40)` fires the matching up while the finger is still down. `rightDragMode` is intentionally **not** set so no drag cursor follows; the parent UP branch's `releaseButton` is idempotent. `onScroll` is suppressed during `rightDragMode` (which only the two-finger-tap path enters). Clears `rdpDoubleTapPending` / `rdpDoubleTapDragging` and stops `edgePinRepeater` for mutual exclusion with the adaptive double-tap and drag-hold edge pinning. |
| **Adaptive** double-tap-and-hold = press-and-drag OR double-click | `onDoubleTap:359-371`, `onTouchEvent:382-442`, helpers `commitDoubleTapDrag:450-461` + `emitDoubleTapDoubleClick:467-474` + `cancelDoubleTapGesture:480-488` | The 2nd tap's `DOWN` arms `PENDING` and sends NOTHING. `ACTION_MOVE` past a tiny fixed slop (`rdpTouchSlop = max(2, DRAG_THRESHOLD_DP * density)`, `DRAG_THRESHOLD_DP=2f` — round 4) commits a left-button press-and-drag; `ACTION_UP` without movement emits two `performTapClick` pairs (a true double-click); `ACTION_CANCEL` releases the held button. Round 4 replaced the `getScaledTouchSlop()/2` heuristic — that was too coarse and cancelled genuine intended drags with tiny finger motion. |
| **Drag-hold edge pinning** (round 4) | Inner `EdgePinRepeater:686-740`; started/updated from `updateEdgePinRepeater:575-600` driven by `onTouchEvent:433-438` (`ACTION_MOVE` while `rdpDoubleTapDragging`) | While a committed double-click+drag is pinned in the 24 dp canvas-edge band (`EDGE_PIN_BAND_DP`), posts 20 ms ticks on `viewable.getHandler()` that call `pointer.moveMouseButtonDown` (LEFT held) with constant velocity 100 dp/s (`EDGE_PIN_SPEED_DP_PER_S`). Displacement: `vx*dt*sensitivity/displayDensity*cbrt(zoom)`. Stops on `ACTION_UP`/`ACTION_CANCEL`/`ACTION_DOWN`/`setRdp(false)`/`onLongPress`, finger leaving the band, or remote-desktop edge clamp. |

**Wiring.**
- `RemoteCanvasActivity.setInputHandler:1350-1356` calls `touchInputHandler.setRdp(true)` (implicit via the `TouchInputHandlerTouchpad` constructor path + `onCreateOptionsMenu`).
- `RemoteCanvasActivity.onDestroy:1378-1391` calls `setRdp(false)` so any in-flight fling runnable is cancelled and `dragMode` / `rightDragMode` / `middleDragMode` are cleared. If a drag is in flight, `setRdp(false)` defensively releases the held button at the current pointer position. `setRdp(false)` also clears the adaptive-double-tap state AND stops the `edgePinRepeater` (round 4) for teardown safety.
- `ConnectionBean.getDefaultInputMode:183-193` writes `TOUCHPAD_MODE` for new RDP connections. Stored `INPUTMODE` values are not migrated.

**Round-4 tunables wired through the same instance setters:**
- `RemoteCanvasActivity.onCreate:307` calls `canvas.setEdgeThresholdDp(getEdgeThresholdDpPref())` (replaces the legacy `Constants.H/W_THRESH` constants).
- `RemoteCanvasActivity.onResume:906-913` re-pushes all three: `setEdgeThresholdDp` + `setAccelerationStrength` + `setFlingDamp`.
- `RemoteCanvasActivity.getInputHandlerById:1240` calls `setAccelerationStrength` and `:1261-1263` calls `setFlingDamp` whenever a touchpad handler is returned — so freshly constructed handlers pick up live slider values without an activity recreate.

---

## PAT-014 — RDP cover-scale zoom minimum (no black borders)

**Rule.** `ZoomScaling.computeMinimumScale(canvas)` returns a "cover" scale — the smallest scale at which the framebuffer fully covers the viewport in both dimensions — when running on RDP. Non-RDP flavors return `canvas.getMinimumScale()` unchanged.

```text
viewW = canvas.getWidth();
viewH = (canvas.getRdpFullViewHeight() > 0) ? canvas.getRdpFullViewHeight() : canvas.getHeight();
fbW   = canvas.getImageWidth();
fbH   = canvas.getImageHeight();
cover = max(viewW / fbW, viewH / fbH);
```

**Why `rdpFullViewHeight` (round 3).** The IME hides part of the canvas and `recomputeRdpViewport` lowers `canvas.setVisibleDesktopHeight(...)` (and only that — the framebuffer is not reallocated, see INV-002). Round 1/2 used `canvas.visibleHeight` for the floor, which is **self-referential**: the floor shrinks with the viewport, the scaled bitmap exactly covers the (shrunk) viewport, and `RemoteCanvas.movePanToMakePointerVisible`'s pan gate (`fbHeight < getVisibleDesktopHeight()`) goes false — vertical panning to follow the cursor behind the IME stops working on devices where the window does not resize when the IME opens. Round 3 captures the physical full-screen height once when the IME is closed (`RemoteCanvas.rdpFullViewHeight`, init `-1`, package-scope accessors at `:876-882`), and `computeMinimumScale` uses it. The floor is recomputed on every `zoomOut` and `changeZoom` call so it tracks the live full-screen height across rotation / multi-window resize.

**Where.**
- `bVNC/src/main/java/com/iiordanov/bVNC/ZoomScaling.java:223-235` (`computeMinimumScale`, `computeCoverScale`).
- `RemoteCanvas.rdpFullViewHeight` field at `:109`; setter called only from `RemoteCanvasActivity.recomputeRdpViewport:1641-1672`. Never set to the shrunk viewport.

---

## PAT-015 — RDP-only `InputAreaState` state machine

**Rule.** The RDP flavor replaces the legacy 3-page extra-keys pager with a single state machine (`InputAreaState`) that owns the IME, the modifier row, and the "123" extra-keys grid. VNC/SPICE/Opaque never enter the state machine.

| State | Visible surface | Notes |
|---|---|---|
| `NONE` | None | Container hidden. Default on session start. |
| `KEYBOARD` | Software IME + modifier row above it | Transition triggered by `keyboardToggleButton` tap, the round-3 IME insets listener (`onCreate:315-339`), the legacy `relayoutViews` 19% heuristic, or `onBackPressed`. |
| `EXTRA` | Extra-keys grid + modifier row above it (IME hidden) | Triggered by `123` button. Survives IME hide; only an explicit `KEYBOARD` transition (`123` again, `onBackPressed`, or `hideKeyboardAndExtraKeys`) collapses it. |

**Owner.** `RemoteCanvasActivity.setInputAreaState:1612-1628` is the only mutator. `updateRdpInputAreaVisibility:1681-1689` reapplies visibility after the state changes, then `recomputeRdpViewport` is called (RDP-gated) so the viewport tracks the new container visibility.

**Viewport owner (round 3).** `recomputeRdpViewport:1641-1672` is the single source of truth for `canvas.setVisibleDesktopHeight` and `rdpInputAreaContainer.setTranslationY` on RDP. Called from the IME insets listener, the end of `relayoutViews` (RDP-gated at `:609-615`), and the end of `setInputAreaState` (RDP-gated at `:1625-1627`). The legacy `relayoutViews` shrink block is RDP-gated out (`:514-517`).

**Gate.** Every RDP-specific branch (relayout branches `:454-599`, `:609-615`, the insets listener at `:315-339`, `onBackPressed:1734-1752`, `setInputHandler:1350-1356`, `onCreateOptionsMenu:1048-1103`, `setInputAreaState:1612-1628`) is gated by `Utils.isRdp(this)`. Non-RDP flavors remain byte-identical to the pre-RDP UX.

---

## PAT-016 — Runtime-configurable input tunables (slider → instance field)

**Rule.** Input behaviour knobs that used to be hardcoded constants now follow a single shape — a `SeekBarPreference` row in `global_preferences.xml` (base file, protocol-agnostic), a `Constants.<KEY>` string + `DEFAULT_*` int, a getter on `RemoteCanvasActivity` returning the consumed value, and an instance-field setter on the consumer pushed at three call sites:

1. `RemoteCanvasActivity.onCreate` (initial apply — edge only),
2. `RemoteCanvasActivity.onResume` (live re-apply on return from Settings — all three),
3. `RemoteCanvasActivity.getInputHandlerById` (so a freshly constructed handler picks up the live slider without recreating the activity).

Defaults reproduce the prior hardcoded value, so the slider at its default reproduces legacy behavior byte-for-byte.

**Where (round 4).**

| Slider | Default → consumer value | Consumer field / setter | Push sites |
|---|---|---|---|
| `edgeThresholdDp` (default 35, max 60) | `getEdgeThresholdDpPref` → `dp` | `RemoteCanvas.edgeThreshDp` field (default `EDGE_THRESH_DP=35f` retained); `setEdgeThresholdDp:915-917` | `onCreate:307`, `onResume:906` |
| `mouseAccelerationStrength` (default 10, max 20) | `getMouseAccelerationStrength` → `slider/10f` | `RemotePointer.accelerationStrength` field (default `DEFAULT_ACCELERATION_STRENGTH=1.0f`); `setAccelerationStrength:318-319` | `onResume:909`, `getInputHandlerById:1240` |
| `flingResistance` (default 6, max 12) | `getFlingResistanceDamp` → `0.92f - slider*0.01f` (slider 6 → 0.86 = legacy `FLING_DAMP`) | `TouchInputHandlerTouchpad.flingDamp` field (default `FLING_DAMP=0.86f` retained); `setFlingDamp:111-113` | `onResume:911-913`, `getInputHandlerById:1261-1263` |

**Why three push sites.** `onCreate` alone is insufficient because the activity isn't recreated when Settings changes the slider. `onResume` alone is insufficient because a fresh `TouchInputHandlerTouchpad` constructed inside `getInputHandlerById` is created lazily after `onCreate`/`onResume` have already fired. `getInputHandlerById` alone is insufficient because some setters (`setEdgeThresholdDp` on `RemoteCanvas`) live on a singleton-style object that exists long before the touchpad handler is built.

**Invariant.** Default slider values reproduce exact legacy behavior. See INV-021.

---

## Pointer convention

`docs/*.md` files use **repo-relative paths** (no leading `/`), backticks for filenames (`bVNC/src/main/java/com/...`). When pointing to a method, include the line range as `:startLine-endLine` to make agent-driven lookups trivial. Example: `RemoteRdpPointer.sendPointerEvent:107-134`.
