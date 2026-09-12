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
| Top of stack | `toolbarToggleButton @+id/toolbarToggleButton` (always visible on every flavor; expanded-at-tap FAB that owns the action bar) |
| ^ | `keyboardToggleButton @+id/keyboardToggleButton` (always visible on every flavor; cycles IME on/off) |
| ^ | `singleHandOpts` overlay (visible only in single-handed input mode) |
| ^ | `extraKeysToolbar` ViewPager (bottom, hidden unless extra keys are on; suppressed on RDP) |
| ^ | `keyboardIconForAndroidTv` (TV only) |
| ^ | `RemoteToolbar @+id/toolbar` (set as support action bar; **default `gone`**, shown only when the user taps the `toolbarToggleButton` FAB — anchored to the FAB at expand time via `positionToolbarNextToToggle`) |
| ^ | `rdpInputAreaContainer @+id/rdpInputAreaContainer` (RDP-only, anchored to bottom; default `gone`) |
| Bottom | `RemoteCanvas` (fills parent) |

This stack order is fixed in `bVNC/src/main/res/layout/canvas.xml` (mirrored in `layout-large/canvas.xml`). Adding overlays requires editing that XML in the same order.

**Toolbar / FAB asymmetry.** The two FABs are *both* always visible on every flavor but their persistence differs on purpose:

- `keyboardToggleButton` — session-only position (drag persists for the activity lifetime; not written to DB). No state-gating: the FAB itself is always visible; only its click handler dispatches to the IME / RDP `InputAreaState` machine.
- `toolbarToggleButton` — **per-connection persisted** position. `setupToolbarToggleButton` (`RemoteCanvasActivity.java:1783-1830`) wires a drag-vs-tap `OnTouchListener` (mirror of the keyboard-FAB pattern); on `ACTION_UP` after the finger exceeded `scaledTouchSlop`, `saveToolbarTogglePosition` (`:1928-1938`) writes X/Y via `handler.post(...)` so the SQLite UPDATE in `Database.runWritable` runs off the UI thread. Position is re-applied on every layout pass via `restoreToolbarTogglePosition` (`:1910-1926`, invoked from `offsetOrRestoreSavedToolbarPosition:702-704`). Saved into the legacy `USELASTPOSITIONTOOLBAR`/`_X`/`_Y`/`_MOVED` columns that the now-deleted `moveToolbar` drag handle used — users who never moved the legacy drag handle stay on the gravity default; users who did get the FAB at that position. No DB migration needed.

**Toolbar visibility model.** The toolbar (`RemoteToolbar`) is no longer auto-shown or auto-hidden by touch input. `RemoteCanvasActivity.showActionBar` (`:1583-1589`) is a deliberate no-op kept so the 5 touch-input call sites (`TouchInputHandlerDirectSwipePan`, `TouchInputHandlerDirectDragPan`, `TouchInputHandlerTouchpad` ×3, `TouchInputHandlerGeneric`, `ScrollWheelButton`) continue to compile. The legacy `ActionBarHider` / `ActionBarShower` / `ActionBarPositionSaver` Runnable inner classes and the `OnTouchViewMover toolbarMover` field were deleted. Expansion is exclusively user-driven: tap the FAB. While the IME is up the FAB tap is a no-op (the keyboard owns the screen). The `RemoteToolbar` subclass in `remoteClientLib/src/main/java/com/undatech/opaque/util/RemoteToolbar.java` is now an unused wrapper around `Toolbar` (only adds `setPositionToMakeVisible`, which has no callers); left in place for backward compat.

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

**Rule.** `TouchInputHandlerTouchpad` extends `TouchInputHandlerGeneric` with three gestures that match the Microsoft RDP Android app, plus the RDP-only velocity-based pointer acceleration curve (see §Pointer acceleration curve below). They are gated by `setRdp(boolean)` so non-RDP touchpad sessions keep the legacy behaviour.

| Gesture | Where | Wire mapping |
|---|---|---|
| Cursor fling | `TouchInputHandlerTouchpad.java:247-280` + inner `Flinger:607-679` | `pointer.moveMouse(...)` per tick. `FLING_TICK_MS=20`, `FLING_NOISE_PX_PER_S=200` floor. The per-tick damping factor is a runtime-configurable instance field `flingDamp` (default `FLING_DAMP=0.86f`); `RemoteCanvasActivity.getFlingResistanceDamp` (slider → `0.92f - slider*0.01f`, default slider 6 → 0.86 = legacy) pushes it via `setFlingDamp:130-132` from `getInputHandlerById:1267-1268` and `onResume:911-917`. Velocity scaled by `cbrt(zoom) * sensitivity / density`. Round 5 added `viewable.movePanToMakePointerVisible()` after each per-tick `moveMouse(...)` (line `:667`), mirroring `onScroll` / `performTapClick`, so the viewport keeps the flung cursor visible while the IME is open. Edge-clamped ticks stop the flinger early. Cancels on new touch-down and returns false while the adaptive double-tap state machine is `PENDING` or `DRAGGING`. `EdgePinRepeater` is deliberately not invoked here — INV-023 (panning during edge-pin would shift the finger out of the 24 dp band). |
| Long-press = synthesized right click | `onLongPress:373-...` | `pointer.rightButtonDown(x, y, meta)` immediately, then a `viewable.getHandler().postDelayed(... releaseButton, 40)` fires the matching up while the finger is still down. `rightDragMode` is intentionally **not** set so no drag cursor follows; the parent UP branch's `releaseButton` is idempotent. `onScroll` is suppressed during `rightDragMode` (which only the two-finger-tap path enters). Clears `rdpDoubleTapPending` / `rdpDoubleTapDragging`, stops `edgePinRepeater`, and calls `doubleTapTracker.reset()` (round 9) for mutual exclusion with the adaptive double-tap and drag-hold edge pinning. |
| **Adaptive** double-tap-and-hold = press-and-drag OR double-click | `onDoubleTap` (delegates to `onManualDoubleTap` override at `:425-455`); `onTouchEvent:382-442` + helpers `commitDoubleTapDrag` + `emitDoubleTapDoubleClick` + `cancelDoubleTapGesture` | The 2nd tap's `DOWN` enters `onManualDoubleTap`, which the touchpad override arms into `PENDING` and sends NOTHING. `ACTION_MOVE` past `rdpTouchSlop = max(2, DRAG_THRESHOLD_DP * density)`, `DRAG_THRESHOLD_DP = 8f` (round 9; was `2f` round 4) commits a left-button press-and-drag; `ACTION_UP` without movement emits two `performTapClick` pairs (a true double-click); `ACTION_CANCEL` releases the held button. The touchpad override also sets `stockDoubleTapFired = true` + `suppressNextSingleTapConfirmed = true` so the base `notifyDoubleTap` (two-click default) never fires on top of the state machine's output. Round 9 replaced the buffered-`onSingleTapUp` approach (structurally unreachable — stock `GestureDetector` distance-rejects every pair that reaches `onSingleTapUp`); see INV-028 and `DoubleTapPairTracker` below. Round 4 replaced the `getScaledTouchSlop()/2` heuristic. |
| **Drag-hold edge pinning** (round 4) | Inner `EdgePinRepeater:686-740`; started/updated from `updateEdgePinRepeater:575-600` driven by `onTouchEvent` `ACTION_MOVE` while `rdpDoubleTapDragging` | While a committed double-click+drag is pinned in the 24 dp canvas-edge band (`EDGE_PIN_BAND_DP`), posts 20 ms ticks on `viewable.getHandler()` that call `pointer.moveMouseButtonDown` (LEFT held) with constant velocity 100 dp/s (`EDGE_PIN_SPEED_DP_PER_S`). Displacement: `vx*dt*sensitivity/displayDensity*cbrt(zoom)`. Stops on `ACTION_UP`/`ACTION_CANCEL`/`ACTION_DOWN`/`setRdp(false)`/`onLongPress`, finger leaving the band, or remote-desktop edge clamp. |

**Pointer acceleration curve (round 9, RDP-only).** `TouchInputHandlerTouchpad.getDelta(distance, rawDx, rawDy, eventTimeMs):628-665` applies the velocity-based `PointerAccelerationCurve` only when `isRdp`; non-RDP keeps the legacy `computeAcceleration(float)` math byte-for-byte. Per-event gain cache (`lastCurveEventTime` + `lastCurveGain`, `:685-693`) ensures the EMA advances exactly once per `MotionEvent` — `getX`/`getY` process the same event so the second axis reuses the cached gain. Sub-pixel carry (`carryX` / `carryY` + `carryFor:670-683`, truncation toward zero) handles slow movements below 1 px/event. Configured by `setPointerAccel(enabled, gainLow, gainHigh):171-175`; resets the curve EMA + the carry on `onDown` (`:351-354`), `setRdp(false)` (`:165-167`), and the config setter itself.

**Wiring.**

**Wiring.**
- `RemoteCanvasActivity.setInputHandler:1415-...` calls `touchInputHandler.setRdp(true)` (implicit via the `TouchInputHandlerTouchpad` constructor path + `onCreateOptionsMenu`).
- `RemoteCanvasActivity.onDestroy:1456-...` calls `setRdp(false)` so any in-flight fling runnable is cancelled and `dragMode` / `rightDragMode` / `middleDragMode` are cleared. If a drag is in flight, `setRdp(false)` defensively releases the held button at the current pointer position. `setRdp(false)` also clears the adaptive-double-tap state AND stops the `edgePinRepeater` (round 4), AND (round 9) drops the `pointerAccelCurve` EMA + the sub-pixel carry for teardown safety.
- `ConnectionBean.getDefaultInputMode:183-193` writes `TOUCHPAD_MODE` for new RDP connections. Stored `INPUTMODE` values are not migrated.

**Round-4 tunables wired through the same instance setters:**
- `RemoteCanvasActivity.onCreate:311` calls `canvas.setEdgeThresholdDp(getEdgeThresholdDpPref())` (replaces the legacy `Constants.H/W_THRESH` constants).
- `RemoteCanvasActivity.onResume:905-918` re-pushes all three: `setEdgeThresholdDp` + `setAccelerationStrength` + `setFlingDamp`. **Round 9 amend**: also calls `setPointerAccel(getRdpPointerAccelEnabled(), getRdpPointerAccelLowGain(), getRdpPointerAccelHighGain())` (RDP-only; non-RDP handlers ignore it).
- `RemoteCanvasActivity.getInputHandlerById:1237-1268` calls `setAccelerationStrength` at `:1245` and `setFlingDamp` at `:1267-1268` whenever a touchpad handler is returned — so freshly constructed handlers pick up live slider values without an activity recreate. **Round 9 amend**: also calls `setPointerAccel(...)` at `:1321-1324` for the freshly built touchpad handler.

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

**Why `rdpFullViewHeight` (round 3).** The IME hides part of the canvas and `recomputeRdpViewport` lowers `canvas.setVisibleDesktopHeight(...)` (and only that — the framebuffer is not reallocated, see INV-002). Round 1/2 used `canvas.visibleHeight` for the floor, which is **self-referential**: the floor shrinks with the viewport, the scaled bitmap exactly covers the (shrunk) viewport, and `RemoteCanvas.movePanToMakePointerVisible`'s pan gate (`fbHeight < getVisibleDesktopHeight()`) goes false — vertical panning to follow the cursor behind the IME stops working on devices where the window does not resize when the IME opens. Round 3 captures the physical full-screen height once when the IME is closed (`RemoteCanvas.rdpFullViewHeight`, init `-1`, package-scope accessors at `:911-920`), and `computeMinimumScale` uses it. The floor is recomputed on every `zoomOut` and `changeZoom` call so it tracks the live full-screen height across rotation / multi-window resize.

**Pinch-to-zoom clamp (round 5 — verified, no code change).** `ZoomScaling.changeZoom:131-144` clamps the new scale to `[computeMinimumScale, 4.0]`. The floor is recomputed live per call, so a zoom-out lands exactly on the cover-scale floor for the current `rdpFullViewHeight`. The only caveat: a user override of the global scaling pref to `FIT_CENTER` / `CENTER` makes pinch a no-op (pre-existing, unrelated to the floor).

**Where.**
- `bVNC/src/main/java/com/iiordanov/bVNC/ZoomScaling.java:223-235` (`computeMinimumScale`, `computeCoverScale`); `:131-144` (`changeZoom` clamp).
- `RemoteCanvas.rdpFullViewHeight` field at `:117`; setter called only from `RemoteCanvasActivity.recomputeRdpViewport:1677-1708`. Never set to the shrunk viewport.

---

## PAT-015 — RDP-only `InputAreaState` state machine

**Rule.** The RDP flavor replaces the legacy 3-page extra-keys pager with a single state machine (`InputAreaState`) that owns the IME, the modifier row, and the "123" extra-keys grid. VNC/SPICE/Opaque never enter the state machine.

| State | Visible surface | Notes |
|---|---|---|
| `NONE` | None | Container hidden. Default on session start. |
| `KEYBOARD` | Software IME + modifier row above it | Transition triggered by `keyboardToggleButton` tap, the round-3 IME insets listener (`onCreate:322-...`), the legacy `relayoutViews` 19% heuristic, or `onBackPressed`. |
| `EXTRA` | Extra-keys grid + modifier row above it (IME hidden) | Triggered by `123` button. Survives IME hide; only an explicit `KEYBOARD` transition (`123` again, `onBackPressed`, or `hideKeyboardAndExtraKeys`) collapses it. |

**Owner.** `RemoteCanvasActivity.setInputAreaState:1648-1664` is the only mutator. `updateRdpInputAreaVisibility:1717-...` reapplies visibility after the state changes, then `recomputeRdpViewport` is called (RDP-gated at `:1661-1663`) so the viewport tracks the new container visibility.

**Viewport owner (round 3).** `recomputeRdpViewport:1677-1708` is the single source of truth for `canvas.setVisibleDesktopHeight` and `rdpInputAreaContainer.setTranslationY` on RDP. Called from the IME insets listener at `:343`, the end of `relayoutViews` (RDP-gated at `:621`), and the end of `setInputAreaState` (RDP-gated at `:1661-1663`). The legacy `relayoutViews` shrink block is RDP-gated out (`:521-524`).

**Gate.** Every RDP-specific branch (the insets listener at `:322-...`, `onBackPressed:1793-1821`, `setInputHandler:1415-...`, `onCreateOptionsMenu:1132-...`, `setInputAreaState:1648-1664`) is gated by `Utils.isRdp(this)`. Non-RDP flavors remain byte-identical to the pre-RDP UX.

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
| `edgeThresholdDp` (default 35, max 60) | `getEdgeThresholdDpPref` → `dp` | `RemoteCanvas.edgeThreshDp` field (default `EDGE_THRESH_DP=35f` retained at `:70-71`); `setEdgeThresholdDp:915-917` | `onCreate:311`, `onResume:911` |
| `mouseAccelerationStrength` (default 10, max 20) — **hidden on RDP since round 9** | `getMouseAccelerationStrength` → `slider/10f` | `RemotePointer.accelerationStrength` field (default `DEFAULT_ACCELERATION_STRENGTH=1.0f`); `setAccelerationStrength:318-319` | `onResume:914`, `getInputHandlerById:1245`. Round 9: `GlobalPreferencesFragment.onCreatePreferences` hides the row when `Utils.isRdp(getContext())`; the `RemoteCanvasActivity.getMouseAccelerationStrength` getter is unchanged for non-RDP consumers. |
| `flingResistance` (default 6, max 12) | `getFlingResistanceDamp` → `0.92f - slider*0.01f` (slider 6 → 0.86 = legacy `FLING_DAMP`) | `TouchInputHandlerTouchpad.flingDamp` field (default `FLING_DAMP=0.86f` retained); `setFlingDamp:130-132` | `onResume:911-917`, `getInputHandlerById:1267-1268` |

**RDP-only carve-out (round 9 — pointer acceleration curve).** Three prefs and one action live only in the `global_preferences_rdp.xml` overlay; pushed to `TouchInputHandlerTouchpad.setPointerAccel(boolean, float, float)` (RDP-only — non-RDP handlers ignore the call):

| Pref | Default → consumer value | Consumer field / setter | Push sites |
|---|---|---|---|
| `rdpPointerAccelEnabled` (default true) | `getRdpPointerAccelEnabled` → `boolean` | `pointerAccelCurve.enabled` via `setConfig(enabled, gainLow, gainHigh)`; `setPointerAccel:171-175` | `onResume:965-967`, `getInputHandlerById:1321-1324` |
| `rdpPointerAccelLowGainPct` (default 100, max 300; clamped to [25, 300]) | `getRdpPointerAccelLowGain` → `slider/100f` | `pointerAccelCurve.gainLow` | same as `rdpPointerAccelEnabled` |
| `rdpPointerAccelHighGainPct` (default 300, max 600; clamped to [100, 600] and never below `getRdpPointerAccelLowGain()`) | `getRdpPointerAccelHighGain` → `slider/100f` | `pointerAccelCurve.gainHigh` | same as `rdpPointerAccelEnabled` |
| `rdpPointerAccelReset` (click action) | n/a — restores `rdpPointerAccelLowGainPct`/`HighGainPct`/`Enabled` to their defaults via `GlobalPreferencesFragment.resetRdpPointerAccel:54-69` and toasts `rdp_pointer_accel_reset_toast` | (UI-only; no consumer field) | (user taps the row in Settings) |

The setter chain `setPointerAccel → setConfig → reset()` drops the curve EMA + the sub-pixel carry so a stale half-gesture can't survive a slider move. Two push sites only (`onResume` + `getInputHandlerById`) for the same reason as the round-6 modifier-row sizing: there is no lazily-built handler that can miss an `onResume` push.

**RDP-only carve-out (round 6).** The modifier-row sizing knobs follow the same shape, but live in the RDP-only overlay file `global_preferences_rdp.xml` (the base `global_preferences.xml` is untouched):

| Slider | Default → consumer value | Consumer field / setter | Push sites |
|---|---|---|---|
| `rdpModifierKeyHeightDp` (default 27, max 56) | `getRdpModifierKeyHeightDp` → `dp` | `ModifierRowView.appliedRowHeightDp` (setter at `:248-251`); `setRowHeightDp:248-251` (sets `ViewGroup.LayoutParams.height = dp` on the row container; buttons auto-stretch `MATCH_PARENT`) | `onResume:923-927`, `setInputHandler:1461-1463` |
| `rdpModifierKeySizeDp` (default 42, max 72) | `getRdpModifierKeySizeDp` → `dp` | `ModifierRowView.appliedKeySizeDp` (setter at `:267-271`); `setKeySizeDp:267-271` (sets `lp.width = dp` on every modifier / action / toggle button; label text scales as `12sp × dp/42`, 8 sp floor) | same as height |

Constants for the round-6 sliders live at `bVNC/src/main/java/com/iiordanov/bVNC/Constants.java:178-179` (keys) and `:199-200` (defaults `27` / `42`); the two prefs are declared in `bVNC/src/main/res/xml/global_preferences_rdp.xml:5-14`. `RdpModifierRowHandler.applyModifierRowSizing(int, int)` (`bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RdpModifierRowHandler.java:243-246`) is the single push fan-out — it delegates to both `setRowHeightDp` / `setKeySizeDp` and is idempotent, so calling it from both `onResume` and `setInputHandler` is safe.

Constants for the round-9 sliders live at `Constants.java:182-185` (keys) and `:211-213` (defaults `true` / `100` / `300`); the four prefs are declared in `global_preferences_rdp.xml:14-33`. `TouchInputHandlerTouchpad.setPointerAccel(boolean, float, float)` is the single push fan-out and is idempotent.

**Why the RDP-only separation.** The base prefs file is shared across all four flavors and lives at the protocol-agnostic layer; modifier-row sizing + pointer acceleration are meaningful only for RDP (VNC/SPICE/Opaque use the legacy 3-page pager, see PAT-004, and the legacy `computeAcceleration` math for cursor scaling). Keeping these sliders in the RDP overlay means non-RDP wrappers never see the prefs in their settings UI. This is the intended split for future flavor-specific tunables — declare them in the `global_preferences_<flavor>.xml` overlay, not the base file.

**Why two push sites instead of three.** The round-6 modifier-row sizing and the round-9 pointer-acceleration both target fields on objects that are either already constructed (`ModifierRowView` inside `RdpModifierRowHandler`) or are pushed lazily only via the touchpad handler (`TouchInputHandlerTouchpad.setPointerAccel`). Neither has a lazily-built handler that can miss an `onResume` push. `onResume` covers the Settings-return live re-apply; `getInputHandlerById` covers a freshly-built touchpad handler (round 9 only — the round-6 modifier row is pushed from `setInputHandler`, not `getInputHandlerById`).

**Why three push sites (round 4 still).** `onCreate` alone is insufficient because the activity isn't recreated when Settings changes the slider. `onResume` alone is insufficient because a fresh `TouchInputHandlerTouchpad` constructed inside `getInputHandlerById` is created lazily after `onCreate`/`onResume` have already fired. `getInputHandlerById` alone is insufficient because some setters (`setEdgeThresholdDp` on `RemoteCanvas`) live on a singleton-style object that exists long before the touchpad handler is built.

**Invariant.** Default slider values reproduce exact legacy behavior. See INV-021 (non-RDP carve-out — round 9 amend: RDP defaults intentionally differ).

---

## PAT-017 — Foreground service for an in-flight long-running session

**Rule.** Long-running in-process tasks that must survive Android's background-kill (lock screen, app backgrounded, another window in front) get a `Service` subclass promoted to a foreground service. The pattern has three legs:

1. **Single canonical start.** One Activity owns the `start` call, invoked from a foreground context (API 21+ → `startService`, API 26+ → `startForegroundService`). Subsequent calls only refresh the notification.
2. **Single canonical stop.** One teardown method owns the `stop` call, and that method is the funnel for every disconnect path (user-initiated, remote-initiated, error-induced). A defensive second call from the most likely host teardown (typically `Activity.onDestroy`) is permitted because the stop is idempotent.
3. **Stable notification.** Notification ID is hardcoded and never randomized per call (Android updates the existing slot in place). Channel importance `LOW` (or lower) so the ongoing notification does not beep when re-emitted. Content intent re-uses the original launching Intent's extras so tapping returns the user to the same task surface.

**Why three legs.**
- The canonical stop is the most violated: every disconnect path must reach it, or the service leaks. `RemoteConnection.closeConnection` is the existing single-funnel for all RDP disconnects (user, remote, error, re-init failure), so it is the right host for the stop call.
- The defensive second call exists because `closeConnection` may be skipped on a rare Activity-destroy path; the stop is idempotent so calling it twice is safe.
- The stable notification ID matters because Android deduplicates by `(channelId, notificationId)`. A randomized ID per start produces a stack of stale notifications.

**API 26+ background-start guard.** `Service.stop` is invoked from places that may run while the host Activity is backgrounded. API 26+ throws `IllegalStateException` from `startService` in that case. The `stop` implementation wraps the dispatch in a `try { ... } catch (IllegalStateException) { log }` and swallows the expected failure — the service will already be stopped (or never started) by the time this runs.

**Foreground-context requirement.** `startForegroundService` (API 26+) requires a foreground calling context, otherwise the service crashes on `startForeground` within 5 s. The Activity `onCreate` is always foreground, so it is the safe host. Do NOT call `start` from a background `Handler`, a worker thread, or a `BroadcastReceiver` without re-routing.

**Why this matters.** The Microsoft RDP Android app and most production remote-desktop clients run an RDP session inside a foreground service for exactly this reason — the OS otherwise reclaims the process and the user is back at the launcher icon with their session terminated. Foreground priority holds the process alive and the persistent notification makes the session state visible.

**Where (aRDP fork).**

| Component | File | Notes |
|---|---|---|
| Service class | `bVNC/src/main/java/com/iiordanov/bVNC/RemoteSessionService.java` | `extends android.app.Service`, declared `foregroundServiceType="dataSync"`, `exported="false"` (`bVNC/src/main/AndroidManifest.xml:95-98`). Static `start(Context, Bundle, String, String)` and `stop(Context)` are the only public API. |
| Notification channel | `remote_session_service`, importance `IMPORTANCE_LOW` | Created lazily on first start (`ensureChannel:129-142`). Channel id, name, description are hardcoded constants — do not randomize. |
| Notification ID | `0x52445353` ('RDSS') | Stable across re-invocations so the slot is reused. |
| Start call site | `RemoteCanvasActivity.onCreate:433` (after `REINIT_SESSION` at `:432`, only when `connection.isReadyForConnection()` at `:426`) | Launching Intent extras snapshotted into `private Bundle sessionLaunchExtras` at `:182` (assignment `:429`). |
| Canonical stop call site | `RemoteConnection.closeConnection:315` (single canonical close point for user / remote / error disconnects) | Funnel verified at `RemoteCanvasActivity.disconnectAndFinishActivity:1456-1457` + `RemoteCanvasHandler.handleMessage` (`RDP_CONNECT_FAILURE` / `RDP_UNABLE_TO_CONNECT` / `RDP_AUTH_FAILED` → `showFatalMessageAndQuit` → `closeConnection`). |
| Defensive stop call site | `RemoteCanvasActivity.onDestroy:1571` | Idempotent re-run after `closeConnection`. |
| **Android 13+ `POST_NOTIFICATIONS` prompt** | `RemoteCanvasActivity.startRemoteSessionService:1469-1486` + `notificationPermissionLauncher:187-205` | Gated on `Build.VERSION.SDK_INT >= TIRAMISU` + `ContextCompat.checkSelfPermission(...) != PERMISSION_GRANTED`. The system dialog fires via `notificationPermissionLauncher.launch(...)` (`:1477`); the service **starts regardless of the prompt outcome** — the OS keeps the foreground service running without the notification UI on deny. The launcher callback (`:189-204`) re-issues `RemoteSessionService.start(...)` directly on grant (bypassing the helper to avoid re-entering the permission check). On API < 33 the permission is install-time; no prompt fires. See `features/FOREGROUND_SESSION_SERVICE.md` §4.1. |
| Manifest inheritance | `:bVNC` library → all 8 wrapper APKs (`bVNC-app`, `freebVNC-app`, `aRDP-app`, `freeaRDP-app`, `aSPICE-app`, `freeaSPICE-app`, `Opaque-app`, `CustomVnc-app`) | No per-flavor gating. Permissions `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` declared in `:bVNC/AndroidManifest.xml:11-13` and merged into every wrapper. |

**Invariant.** The service MUST be started in `RemoteCanvasActivity.onCreate` only after `connection.isReadyForConnection()` is true and MUST be stopped by `RemoteConnection.closeConnection` so every disconnect path is covered. The defensive `RemoteCanvasActivity.onDestroy` call exists only as a belt-and-braces fallback — see INV-026. The `POST_NOTIFICATIONS` prompt is optional: the service starts regardless, the prompt only governs whether the notification UI is visible.

**Anti-patterns.**
- Calling `start` from a background thread or `BroadcastReceiver` on API 26+ → `ForegroundServiceDidNotStartInTimeException` within 5 s.
- Randomizing the notification ID per call → notification stack instead of one slot.
- Setting channel importance to `IMPORTANCE_DEFAULT` / `IMPORTANCE_HIGH` → beeps on every re-emit.
- Calling `stop` from a non-canonical path (e.g., a single UI button) → service leaks if any other path forgets to stop it.
- Forgetting `PendingIntent.FLAG_IMMUTABLE` on API 31+ → `IllegalArgumentException` on `getActivity(...)`.

---

## Pointer convention

`docs/*.md` files use **repo-relative paths** (no leading `/`), backticks for filenames (`bVNC/src/main/java/com/...`). When pointing to a method, include the line range as `:startLine-endLine` to make agent-driven lookups trivial. Example: `RemoteRdpPointer.sendPointerEvent:107-134`.
