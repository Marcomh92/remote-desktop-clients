# docs/features/INPUT_PIPELINE.md

> **Purpose.** This is the canonical map for **mouse behavior, keyboard handling, and modifier-key changes** in the aRDP flavor. Every class, line range, and constant involved in turning a screen touch / keystroke / IME character into a wire event to the remote desktop lives below.
>
> Read first: `MASTER.md` §3 (glossary), `PATTERNS.md` PAT-002 / PAT-003 / PAT-004, `DESIGN_PRINCIPLES.md` §INV (especially INV-008 / INV-009 / INV-011 / INV-012).
>
> **Scope.** Only the RDP flavor. VNC and SPICE classes are listed for cross-reference but not analyzed in depth.

---

## 0. The shape, in one diagram

```
                                         +---------------------------------------+
                                         |           RemoteCanvasActivity         |
                                         |  (the only canvas host; flavor-shared) |
                                         +---------------------------------------+
                                                       |            ^
                                                       |            | UI events from
                                                       v            | native (handler)
+-----------------------+   touches       +----------------------------------+
| RemoteCanvas          |<---- MotionEvent | RemoteClientsInputListener.kt    |
| extends AppCompatImageView (NOT SurfaceView)  (set as canvas.setOnKeyListener) |
+-----------------------+                    +----------------------------------+
        ^                                            |                ^
        |                                            v                |
        | frames                              +----------------+      |
        |                                     |  TouchInputHandler*  |
        |                                     +----------------+      |
        |                                            |                |
        |                                            v                v
        |                                  +-------------------+   +-------------------------+
        |                                  | RemotePointer      |   | RemoteKeyboard          |
        |                                  | (RDP:RemoteRdpPtr) |   | (RDP:RemoteRdpKb)      |
        |                                  +-------------------+   +-------------------------+
        |                                            |                |
        | writes frames into shared                  v                v
        | Bitmap via JNI                     +-------------------------------------+
        |                                    |        RdpCommunicator                |
        | <--------- JNI static cb ----------|  inputExecutor (SendRdpInputThread)   |
        |     OnGraphicsUpdate               |     sleepBetweenInputEvents 3-5ms    |
        |     OnSettingsChanged              +-------------------------------------+
        |                                            |
        |                                            v
        |                                    +-------------------------------------+
        +----------------------------------- | LibFreeRDP (vendored)                |
                                             | loadLibrary("freerdp-android")       |
                                             | sendKeyEvent / sendCursorEvent / ... |
                                             +-------------------------------------+
```

> **Two parallel pipelines** start at `RemoteCanvasActivity`: touches fan into `TouchInputHandler*` → `RemotePointer`, keys/IME fan into `RemoteClientsInputListener` → `RemoteKeyboard`. Both end at `RdpCommunicator` and its single-thread `inputExecutor`.

---

## 1. The canvas surface (`RemoteCanvas`)

| Property | Value | Source |
|---|---|---|
| Class | `RemoteCanvas extends AppCompatImageView implements Viewable` | `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java:62` |
| Framebuffer | Single `Bitmap` swapped via `setImageDrawable` | `RemoteCanvas.java:73` (`myDrawable`), `RemoteCanvas.reallocateMyDrawable:331-354` |
| Framebuffer impl | `UltraCompactBitmapData` (`!isVnc` always, for RDP) | `RemoteCanvas.java:359-366` (`isRdpSpiceOrOpaque` branch) |
| Soft cursor | Local `Bitmap` blit drawn on top of framebuffer — for VNC it carries the server's `XCursor`/`RichCursor` pixels; for SPICE/Opaque it is the static `R.drawable.cursor` PNG (host-cursor callback is a TODO stub in `android-spice-widget.c:314-319`); for RDP under `CURSOR_AUTO` it is populated by `RemoteCanvas.OnPointerEvent{New,Set}` after the host forwards the AND/XOR-mask bitmap from FreeRDP (see `remoteClientLib/jni/libs/22_freerdp_add_cursor_callback.patch`); overlay layering means the user's view is always: framebuffer pixels, then the host cursor shape on top. | `RemoteCanvas.softCursorMove:782-806`, `RemoteCanvas.needsLocalCursor:374-395`, `RemoteCanvas.OnPointerEventNew:864-869`, `RemoteCanvas.hostCursorFromRdp:930-999` |
| Repaint rate | ≤60 Hz ahead; ≤10 Hz when behind | `RemoteCanvas.reDraw:681-696`, `invalidateCanvasRunnable:153` |
| Layout | `canvas.xml` in `bVNC/src/main/res/layout/` | lines 1-147 |

### Why this matters for input work

- The View being `ImageView` means **touch coords must be transformed by the inverse of the active `Matrix`** to get framebuffer pixels. `RemoteCanvas.computeShiftFromFullToView` (`:488`) does part of this. Coordinates are scaled by density in `TouchInputHandlerGeneric` (see §3).
- Replacing this with a `Surface`/`TextureView`/`GLSurfaceView` would break every input handler. See INV-002.
- The `[canvas]+rdpInputAreaContainer+RemoteToolbar+keyboardIconForAndroidTv+extraKeysToolbar+singleHandOpts+keyboardToggleButton+toolbarToggleButton` stack is fixed in `canvas.xml`. See PAT-007.

### `Viewable` — the protocol-side contract

`remoteClientLib/src/main/java/com/undatech/opaque/Viewable.java` (347 lines) defines the contract `RemoteCanvas` implements. Input-relevant methods (file paths to read):

| Method | Line | Use |
|---|---|---|
| `invalidateMousePosition()` | `:89` | Repaint the soft cursor after a pointer move. Called twice per `RemoteRdpPointer.sendPointerEvent`. |
| `setMousePointerPosition(int,int)` | `:66` | Local cursor positioning only. |
| `mouseMode(boolean relative)` | `:72` | Server-driven relative mouse mode. Sets `RemotePointer.relativeEvents`; if `!touchpadMode`, just shows info and does nothing. |
| `movePanToMakePointerVisible()` | `:269` | Pans the canvas so the mouse stays on screen (touch handlers call it after each click/drag). |
| `getAbsX()/getAbsY()` | `:102-108` | Current pan offset. Input handlers consult these to translate touch coordinates. |
| `isForegrounded()` | `:347` | Used by handlers to suppress sends when the Activity is backgrounded. |
| `getHandler()` | `:324` | UI-thread Handler for posting invalidations. |

---

## 2. Touch → mouse wire path (RDP)

### 2.1 Capture

`RemoteCanvasActivity.onTouchEvent(MotionEvent)` (`RemoteCanvasActivity.java:1280-1289`) — short delegation to the input listener.

```text
MotionEvent
   |
   v
RemoteClientsInputListener.onTouchEvent(MotionEvent)  // RemoteClientsInputListener.kt:124-132
   |
   v
inputHandler.onTouchEvent(MotionEvent)   // selected from inputModeHandlers (TouchInputHandlerGeneric/...)
```

### 2.2 Gesture recognition (`TouchInputHandlerGeneric`)

`bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerGeneric.java:41-740` defines the canonical gestures for tap / double-tap / drag / scroll / multi-touch. Per-gesture mapping:

| Gesture | Method | Wire mapping |
|---|---|---|
| Single tap (confirmed) | `onSingleTapConfirmed` → `performLeftClick:322-329` | `pointer.leftButtonDown` → 50 ms sleep → `pointer.releaseButton` |
| Double tap (stock-accepted, within `ViewConfiguration.getScaledDoubleTapSlop`) | `onDoubleTap` → base `notifyDoubleTap`; **round 9** also routes the relaxed-slop pair through the same `onManualDoubleTap` hook so `TouchInputHandlerTouchpad` overrides that single path | Two left-click cycles with 50 ms gaps (base) or arms the touchpad state machine (touchpad override) |
| **Relaxed-slop double-tap** (raw DOWN/UP tracker, round 9) | `DoubleTapPairTracker.doubleTapTracker`, fed from primary-pointer `ACTION_DOWN/UP` in `onTouchEvent:716-749`; second DOWN that pairs within `slopPx` + `timeoutMs` triggers `onManualDoubleTap(MotionEvent secondDown)` | Suppresses the stock `onSingleTapConfirmed` for UP2 via `suppressNextSingleTapConfirmed` (set in `onManualDoubleTap:486-489`); default base behaviour = `notifyDoubleTap` (two-click). Thresholds are `slopPx = max(pref dp × density, viewConfiguration.getScaledDoubleTapSlop())` and `timeoutMs = max(pref, viewConfiguration.getDoubleTapTimeout())` — defaults reproduce stock but the tracker adds detection for pairs stock rejected. Tracker reset on `ACTION_CANCEL`, `ACTION_POINTER_DOWN` (any non-primary), and `onLongPress` (`TouchInputHandlerTouchpad` / `TouchInputHandlerDirectDragPan` / `TouchInputHandlerSingleHanded`). |
| Long press | `onLongPress` | `pointer.leftButtonDown` (no release) → enter `dragMode`. Haptic via `touchInputDelegate.sendShortVibration`. Round 9 adds `doubleTapTracker.reset()` so a long-press can't pair with a later UP. |
| Pan in `dragMode` / `rightDragMode` / `middleDragMode` | `onTouchEvent` `ACTION_MOVE:498-502` | `pointer.moveMouseButtonDown` (button held, mouse moves) |
| Two-finger tap (2nd pointer up) | `onTouchEvent` `ACTION_POINTER_UP` for pointerId==1: `533-547` | `pointer.rightButtonDown` → `rightDragMode` |
| Three-finger tap (3rd pointer down) | `onTouchEvent` `ACTION_POINTER_DOWN` for pointerId==2: `551-561` | `pointer.middleButtonDown` → `middleDragMode` |
| Two-finger swipe | `onScale:572-647` → `sendScrollEvents:288-310` | `pointer.scrollUp/Down/Left/Right` |
| USB mouse / trackball | `handleMouseActions:174-283` | Maps `MotionEvent.BUTTON_PRIMARY/SECONDARY/TERTIARY`, `AXIS_VSCROLL`, `AXIS_HSCROLL` |
| Hardware volume key scroll | `RemotePointer.hardwareButtonsAsMouseEvents` `:153-156` → `scrollMouse:173-186` | Repeating `scrollUp/Down` via `MouseScroller` runnable, 100 ms |

### 2.2.1 Touchpad-specific gestures (RDP only)

`bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerTouchpad.java` extends the generic handler with three gestures that match the Microsoft RDP Android app, plus the RDP-only velocity-based pointer acceleration curve (see §6). They are gated by `setRdp(boolean)` (`:138-...`); non-RDP touchpad sessions keep the legacy behaviour.

| Gesture | Where | Wire mapping |
|---|---|---|
| Cursor fling (release velocity → decaying tick) | `onFling:247-280` + inner `Flinger:607-679` | `pointer.moveMouse(...)` per tick. `FLING_TICK_MS=20`, `FLING_NOISE_PX_PER_S=200` floor → up to ~50 events/s. The per-tick damping factor is now a runtime-configurable instance field `flingDamp` (default `FLING_DAMP=0.86`); `RemoteCanvasActivity.getFlingResistanceDamp` (`0.92f - slider*0.01f`, slider default 6 → 0.86 = legacy) pushes it via `setFlingDamp` from `getInputHandlerById:1267-1268` and `onResume:911-917`. Velocity scaled by `cbrt(zoom) * sensitivity / density`. **Round 5**: each tick calls `viewable.movePanToMakePointerVisible()` (`:667`) after `moveMouse`, before the edge-pinned early-return check, so the viewport pans to keep the flung cursor visible while the IME is open (mirrors `onScroll` / `performTapClick`). Edge-clamped ticks (beforeX==afterX && beforeY==afterY) stop the flinger early instead of ticking down through the noise floor. Cancels on new touch-down and returns false while the adaptive double-tap state machine is `PENDING` or `DRAGGING`. |
| Long-press = synthesized right click (full down + ~40 ms delayed up) | `onLongPress:373-...` | `pointer.rightButtonDown(x, y, meta)` immediately, then a `viewable.getHandler().postDelayed(... releaseButton, 40)` fires the matching up while the finger is still down. `rightDragMode` is **not** set, so no drag cursor follows; the parent UP branch's `releaseButton` is idempotent. Mutually exclusive with the adaptive double-tap and the drag-hold edge pin: clears `rdpDoubleTapPending` / `rdpDoubleTapDragging`, stops `edgePinRepeater`, and calls `doubleTapTracker.reset()` (round 9) so the next gesture is not armed by a phantom continuation. |
| **Adaptive** double-tap-and-hold = press-and-drag OR double-click | `onDoubleTap` (delegates to `onManualDoubleTap`) + `onManualDoubleTap` override at `:425-455` + `onTouchEvent` ACTION_MOVE/UP branches; helpers `commitDoubleTapDrag` + `emitDoubleTapDoubleClick` + `cancelDoubleTapGesture` + `updateEdgePinRepeater` | The 2nd tap's `DOWN` enters the base `onManualDoubleTap` hook, which the touchpad override arms into the same `PENDING` state machine and sends NOTHING to the server. `ACTION_MOVE` past `rdpTouchSlop = max(2, DRAG_THRESHOLD_DP * density)`, `DRAG_THRESHOLD_DP = 8f` (round 9; was `2f` round 4 — the 2 dp value was below typical finger jitter and converted intended double-clicks into drags) commits a left-button press-and-drag (`pointer.leftButtonDown` + `dragMode=true`); `ACTION_UP` without movement emits two `performTapClick` pairs (a true double-click); `ACTION_CANCEL` releases the held button. Round 9 wired `onDoubleTap` → `onManualDoubleTap` so a stock-accepted pair and a relaxed-slop pair both feed the same state machine; the touchpad override also sets `stockDoubleTapFired = true` + `suppressNextSingleTapConfirmed = true` so the base `notifyDoubleTap` (two-click default) never fires on top of the state machine's output, and the stock `onSingleTapConfirmed` scheduled for the second UP does not add a third click behind the state machine. Round 4 replaced the `getScaledTouchSlop()/2` heuristic. `onFling` is gated off while pending/dragging. |
| **Drag-hold edge pinning** (round 4) | Inner `EdgePinRepeater:686-740`; driven from `onTouchEvent:433-438` (`ACTION_MOVE` while `rdpDoubleTapDragging`) | While a committed double-click+drag holds the finger in the 24 dp band (`EDGE_PIN_BAND_DP=24f`) of any canvas edge, posts 20 ms ticks on `viewable.getHandler()` that call `pointer.moveMouseButtonDown(...)` (LEFT held) with constant velocity 100 dp/s (`EDGE_PIN_SPEED_DP_PER_S`). Displacement formula matches `Flinger.run`: `vx*dt*sensitivity/displayDensity*cbrt(zoom)`. Stops on `ACTION_UP`, `ACTION_CANCEL`, `ACTION_DOWN`, `setRdp(false)`, `onLongPress`, finger leaving the band, or remote desktop edge clamp (`beforeX==afterX && beforeY==afterY`). RDP-only; `setRdp(false)` also calls `edgePinRepeater.stop()` for teardown safety. |

`TouchInputHandlerTouchpad.setRdp(true)` is called from `RemoteCanvasActivity.setInputHandler` (`:1415-...`) and `setRdp(false)` from `onDestroy` (`:1456-...`) so the gating reflects the live flavor. New RDP connections default to touchpad via `ConnectionBean.getDefaultInputMode` (`:183-193`).

### 2.3 Pointer dispatch — `RemoteRdpPointer`

`bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpPointer.java:11-134`. Subclass of `RemotePointer` (`bVNC/.../RemotePointer.java`) which extends `com.undatech.opaque.input.RemotePointer`.

**Wire constants** (FreeRDP `POINTER_FLAGS_*` / our synthetic naming):

| Constant | Value | Meaning |
|---|---|---|
| `POINTER_FLAGS_WHEEL` | `0x0200` | Wheel event marker |
| `POINTER_FLAGS_WHEEL_NEGATIVE` | `0x0100` | Wheel negative direction |
| `MOUSE_BUTTON_MOVE` | `0x0800` | Hover/move flag |
| `MOUSE_BUTTON_LEFT` | `0x1000` | Left button |
| `MOUSE_BUTTON_RIGHT` | `0x2000` | Right button |
| `MOUSE_BUTTON_MIDDLE` | `0x4000` | Middle button |
| `MOUSE_BUTTON_SCROLL_UP` | `0x0200 \| 0x0078` | Vertical scroll-up (3 lines) |
| `MOUSE_BUTTON_SCROLL_DOWN` | `0x0200 \| 0x0100 \| 0x0088` | Vertical scroll-down (3 lines) |
| `POINTER_DOWN_MASK` (inherited from base) | `0x8000` | "Button down" bit |

**Key state machine** (`pointerMask` / `prevPointerMask`):

| Method | Line | Effect |
|---|---|---|
| `leftButtonDown` | `RemoteRdpPointer.java:43-46` | `pointerMask = LEFT \| POINTER_DOWN_MASK`, then `sendButtonDownOrMoveButtonDown` |
| `middleButtonDown` | `:48-52` | similar with `MIDDLE` |
| `rightButtonDown` | `:54-58` | similar with `RIGHT` |
| `scrollUp/Down` | `:60-70` | `pointerMask = SCROLL_* \| POINTER_DOWN_MASK`, then `sendPointerEvent` |
| `scrollLeft/Right` | `:72-80` | **TODO stubs — events dropped.** See INV-012. |
| `moveMouse` | `:82-86` | `pointerMask = MOVE \| prevPointerMask` (preserves held button) |
| `moveMouseButtonDown` | `:88-92` | `pointerMask = MOVE \| POINTER_DOWN_MASK` |
| `moveMouseButtonUp` | `:94-98` | `pointerMask = MOVE` |
| `releaseButton` | `:100-105` | Sets `pointerMask = MOVE`, sends, then `prevPointerMask = 0` |
| `sendButtonDownOrMoveButtonDown` | `:34-40` | Smart reuse: if `prevPointerMask == pointerMask`, just `moveMouseButtonDown`; otherwise send fresh event |
| `sendPointerEvent` | `:110-136` | The wire-emit. Combines `metaState \| remoteInput.getKeyboard().getMetaState()` (modifier piggy-back), clears previous button when `!isMoving && prevPointerMask != pointerMask`, calls `canvas.invalidateMousePosition()`. **Round 9 dedup:** writes the `MOVE \| pointerMask` PDU only when it differs from `pointerMask` (i.e. `(pointerMask & MOUSE_BUTTON_MOVE) == 0`); when `pointerMask` already contains MOVE, only the bare `pointerMask` PDU is sent. See INV-027 — drag ticks and plain moves now emit one PDU instead of two byte-identical duplicates. |

**The wire sequence for a touch tap:**

```
leftButtonDown(x, y, m)                  [RemoteRdpPointer.java:43-46]
   pointerMask = LEFT | DOWN            (MOVE bit NOT set)
   sendButtonDownOrMoveButtonDown (1st: sends fresh event)
        sendPointerEvent(...)
           protocomm.writePointerEvent(..., MOUSE_BUTTON_MOVE | LEFT | DOWN, false)
           protocomm.writePointerEvent(..., LEFT | DOWN, false)
[50 ms later, in TouchInputHandlerGeneric.performLeftClick :327]
releaseButton(x, y, m)                    [RemoteRdpPointer.java:100-105]
   pointerMask = MOVE                    (MOVE bit set, no buttons)
   sendPointerEvent(...)
           protocomm.writePointerEvent(..., MOVE, false)   // round 9: single PDU, no MOVE|MOVE duplicate
   prevPointerMask = 0
```

**The wire sequence for a drag (button held while panning):**

```
leftButtonDown(x0, y0, m)        -> sends MOVE|LEFT|DOWN then LEFT|DOWN  (MOVE bit NOT in pointerMask)
pan via ACTION_MOVE
moveMouseButtonDown(xi, yi, m)   -> pointerMask = MOVE | LEFT | DOWN
                                  sendPointerEvent(...)
                                       protocomm.writePointerEvent(..., MOVE|LEFT|DOWN)   // round 9: single PDU
                                  ...repeats per tick...
pan ends
moveMouseButtonUp(xn, yn, m)     -> pointerMask = MOVE
                                  sendPointerEvent(...)
                                       protocomm.writePointerEvent(..., MOVE)              // round 9: single PDU
[no explicit releaseButton called for the drag end path —
 the path depends on which onTouchEvent ACTION_UP branch fires; see :498-502]
```

### 2.4 Touch-mode handlers (`TouchInputHandler*`)

| Class | Selects via `RemoteCanvasActivity.getInputHandlerById:1237-1268` | Behavior |
|---|---|---|
| `TouchInputHandlerGeneric` (abstract base) | default | Pan + tap + drag + scroll; described above |
| `TouchInputHandlerTouchpad` | `TOUCHPAD_MODE` | Single-finger drag = relative mouse move. Sets `pointer.setRelativeEvents(true)`. **Default for new RDP connections.** RDP-only gestures (fling / long-press=right-click / double-tap-hold=drag) gated by `setRdp(boolean)`; see §2.2.1. |
| `TouchInputHandlerDirectDragPan` | `itemInputDragPanZoomMouse` | Single-finger drag = absolute mouse move (pan + tap) |
| `TouchInputHandlerDirectSwipePan` | `itemInputTouchPanZoomMouse` | Two-finger pan; one-finger absolute move |
| `TouchInputHandlerSingleHanded` | `itemInputSingleHanded` | Single-handed overlay buttons (`singleHandOpts`); no direct canvas touch dispatch |

Selection is persisted in `Constants.selectedInputMethodTag` via `RemoteCanvasActivity.setInputMode:1394-...`. For new RDP connections, `ConnectionBean.getDefaultInputMode:183-193` writes `TOUCHPAD_MODE` straight into the row; legacy rows (where `INPUTMODE` was never populated) take the Activity-side fallback at `RemoteCanvasActivity.setModes`.

---

## 3. Keyboard → RDP wire path

### 3.1 Capture

There is no `RemoteCanvasActivity.onKeyDown/Up/dispatchKeyEvent`. Instead:

1. `RemoteCanvasActivity.setInputHandler(TouchInputHandler handler)` (`:1207-1218`) registers `canvas.setOnKeyListener(inputListener)` where `inputListener` is `RemoteClientsInputListener` (`bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteClientsInputListener.kt:40-152`).
2. `RemoteClientsInputListener.onKey(View?, int keyCode, KeyEvent evt)` (`:52-74`):
   - If `keyCode == KEYCODE_MENU`: delegates back to `activity.onKeyDown/onKeyUp` (the standard Android menu flow). Returns `false`.
   - Otherwise: calls `keyInputHandler.onKeyDownEvent(...)` / `onKeyUpEvent(...)` where `keyInputHandler` is `RemoteConnection` (which extends `KeyInputHandler`). Then calls `resetOnScreenKeys(keyCode)` to clear sticky modifiers when a SHIFT key is pressed.

### 3.2 Per-flavor dispatch

`RemoteConnection.onKeyDownEvent/onKeyUpEvent` (`bVNC/src/main/java/com/iiordanov/bVNC/protocol/RemoteConnection.java`) — base class simply calls `keyboard.processLocalKeyEvent(keyCode, evt, 0)`. Concrete subclasses (RDP/VNC/SPICE) inject their keyboard impl in the constructor.

### 3.3 `RemoteRdpKeyboard.processLocalKeyEvent`

`bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpKeyboard.java:36-89`. Per-call pipeline:

```
processLocalKeyEvent(int keyCode, KeyEvent evt, int additionalMetaState)
   ├─ shouldDropModifierKeys(evt)                              [RemoteKeyboard.java:391-416]
   │     drops repeated/touchscreen-originated modifier KeyEvents
   ├─ rdpcomm.remoteKeyboardState.detectHardwareMetaState(evt) [RemoteKeyboardState.java:36-192]
   │     tracks hardware L/R Ctrl/Alt/Shift/Super in hardwareMetaState
   ├─ if !isInNormalProtocol -> return false
   ├─ pointer.hardwareButtonsAsMouseEvents(keyCode, evt, metaState | onScreenMetaState)  [RemotePointer.java:141-165]
   │     BACK/CAMERA -> right-click; VOLUME -> scroll; DPAD-as-pointer
   ├─ DOWN branch: metaState = onScreenMetaState | metaState     [RemoteRdpKeyboard.java:72-78]
   ├─ UP branch:   upMeta = lastDownMetaState & ~onScreenMetaState  [RemoteRdpKeyboard.java:75-90, round 6]
   │     The bitwise `& ~onScreenMetaState` lets any on-screen modifier that is
   │     still physically held stay pressed across this key's UP (so LMENU
   │     survives the Tab-tap half of Alt+Tab). One-shot modifiers consumed
   │     by the matching DOWN have already been cleared from onScreenMetaState
   │     via consumeOnModifiers → syncRowStateToKeyboard, so Alt+A wire is unchanged.
   ├─ rdpcomm.writeKeyEvent(keyCode, metaState, down)          [RdpCommunicator.java:179-187]
   │     stores metaState; does NOT yet send a key
   ├─ injectMetaState(evt, metaState | lastDownMetaState)      [RemoteKeyboard.java:373-389]
   └─ keyboardMapper.processAndroidKeyEvent(evt, isRepeat)     [RdpKeyboardMapper.java:458-534]
         ├─ ACTION_DOWN  -> processVirtualKey / processUnicodeKey
         ├─ ACTION_UP    -> processVirtualKey / processUnicodeKey (down=false)
         ├─ ACTION_MULTIPLE -> processUnicodeKey for each char
         └─ on real key down with modifier active -> resetModifierKeysAfterInput(false)
```

**Notable divergence from VNC:** in `RemoteRdpKeyboard.processLocalKeyEvent:53`, the metaState is `metaState | onScreenMetaState` — `hardwareMetaState` is **not** piggy-backed. In VNC's `RemoteVncKeyboard.processLocalKeyEvent`, it **is** (`metaState | onScreenMetaState | hardwareMetaState` at line 342 of `RemoteVncKeyboard.java`). The reason: for RDP, hardware modifier state is consumed inside `RdpKeyboardMapper.processAndroidKeyEvent` and emitted as VKs (each modifier has its own VK), not piggy-backed onto pointer events.

### 3.4 Keycode → VK translation (`RdpKeyboardMapper`)

`remoteClientLib/src/main/java/com/undatech/opaque/input/RdpKeyboardMapper.java` (full file). The big table is `keymapAndroid[]` at `:241-444`. Highlights:

| Android keycode | Windows VK | Line |
|---|---|---|
| `KEYCODE_0..9` | `VK_KEY_0..VK_KEY_9` (`0x30-0x39`) | `:247-256` |
| `KEYCODE_A..Z` | `VK_KEY_A..VK_KEY_Z` (`0x41-0x5A`) | `:258-283` |
| `KEYCODE_DEL` | `VK_BACK` (`0x08`) | `:285` |
| `KEYCODE_ENTER` | `VK_RETURN` (`0x0D`) | `:289` |
| `KEYCODE_NUMPAD_ENTER` | `VK_RETURN \| VK_EXT_KEY` | `:290` |
| `KEYCODE_SPACE` | `VK_SPACE` (`0x20`) | `:291` |
| `KEYCODE_SHIFT_LEFT` | `VK_LSHIFT` (`0xA0`) | `:292` |
| `KEYCODE_SHIFT_RIGHT` | `VK_RSHIFT` (`0xA1`) | `:293` |
| `KEYCODE_DPAD_*` | `VK_{DIR} \| VK_EXT_KEY` | `:295-298` |
| `KEYCODE_ALT_LEFT` | `VK_LMENU` (`0xA4`) | `:350` |
| `KEYCODE_ALT_RIGHT` | `VK_RMENU \| VK_EXT_KEY` (`0xA5`) | `:351` |
| `KEYCODE_META_LEFT` | `VK_LWIN \| VK_EXT_KEY` (`0x5B`) | `:352` |
| `KEYCODE_META_RIGHT` | `VK_RWIN \| VK_EXT_KEY` (`0x5C`) | `:353` |
| `KEYCODE_TAB` | `VK_TAB` (`0x09`) | `:327` |
| `KEYCODE_BACK` | `VK_ESCAPE` (`0x1B`) — RDP/TV short-circuit at `RemoteClientsInputListener.kt:60-62` returns `false` first, so BACK no longer reaches this mapping for RDP/TV (see §4.3.1, §4.3.6) | `:343` |
| `KEYCODE_F1..F12` | `VK_F1..VK_F12` (`0x70-0x7B`) | `:313-324` |

Numeric constants for the extended-table lookups live in `remoteClientLib:...:freeRDPCore/res/values/integers.xml`; consumed at `RdpKeyboardMapper.java:373-441`.

### 3.5 IME / soft keyboard (`BaseInputConnection`)

`RemoteCanvas.onCreateInputConnection(EditorInfo)` (`RemoteCanvas.java:823-832`) returns a `BaseInputConnection` with:

- `EditorInfo.IME_FLAG_NO_FULLSCREEN`
- `inputType` from `softwareKeyboardType` SharedPreference (lookup at `RemoteCanvas.java:834-847`)

When the user commits text from the IME, Android delivers a `KeyEvent` with `keyCode == KEYCODE_UNKNOWN` and `getCharacters()` returning the typed string. `RemoteRdpKeyboard.processLocalKeyEvent:70-81` splits the string into per-character events and forwards each as `KeyEvent(ACTION_MULTIPLE)`. Each char then goes through `RdpKeyboardMapper.processAndroidKeyEvent:492` (ACTION_MULTIPLE branch → `processUnicodeKey`).

The IME is shown/hidden via `Utils.showKeyboard` / `Utils.hideKeyboard` (`Utils.java:742-754`). `softKeyboardUp` flag at `RemoteCanvasActivity.java:142`.

### 3.6 Repeat-key behavior

`RemoteKeyboard.repeatKeyEvent(...)` starts `KeyRepeater` (`remoteClientLib:...:input/KeyRepeater.java`). The mapper **ignores repeats** (`RdpKeyboardMapper.java:475, 478, 492`).

---

## 4. Modifier keys

The aRDP fork ships a **new RDP-only modifier row** that replaces the legacy 3-page extra-keys pager on the RDP flavor. VNC/SPICE/Opaque continue using the legacy pager unchanged.

### 4.1 Mask constants

`remoteClientLib/src/main/java/com/undatech/opaque/input/RemoteKeyboard.java:33-63`:

```text
CTRL_MASK   = KeyEvent.META_CTRL_LEFT_ON        // :54
SHIFT_MASK  = KeyEvent.META_SHIFT_LEFT_ON       // :57
ALT_MASK    = KeyEvent.META_ALT_LEFT_ON         // :58
SUPER_MASK  = KeyEvent.META_META_LEFT_ON        // :59
RCTRL_MASK  = KeyEvent.META_CTRL_RIGHT_ON        // :60
RSHIFT_MASK = KeyEvent.META_SHIFT_RIGHT_ON      // :61
RALT_MASK   = KeyEvent.META_ALT_RIGHT_ON        // :62
RSUPER_MASK = KeyEvent.META_META_RIGHT_ON       // :63
```

PC scancodes `SCAN_LEFTCTRL=29, SCAN_RIGHTCTRL=97, SCAN_LEFTALT=56, SCAN_RIGHTALT=100, SCAN_LEFTSHIFT=42, SCAN_RIGHTSHIFT=54, SCAN_LEFTSUPER=125, SCAN_RIGHTSUPER=126` are also defined at `RemoteKeyboard.java:33-52`.

### 4.2 Three independent modifier state variables (PAT-002)

| Variable | Class | Set by | Reset by |
|---|---|---|---|
| `hardwareMetaState` | `RemoteKeyboardState` on `RdpCommunicator.remoteKeyboardState` | `detectHardwareMetaState(KeyEvent)` per-key | Never explicitly except for the per-key delta read path |
| `onScreenMetaState` | `RemoteRdpKeyboard` (per-flavor keyboard) | `onScreen{Ctrl,Alt,Shift,Super}Toggle/Off` (`:114-199`) | `clearMetaState()` (`:213-215`) — called from both `ExtraKeysPagerAdapter.syncKeyboardModifierState` (legacy pager) and `RdpModifierRowHandler.syncRowStateToKeyboard` (RDP row) |
| `remoteKeyboardMetaState` | `RemoteKeyboardState` | `updateRemoteMetaState(...)` (`:216-222`) | `RdpCommunicator.close` (only resets session, not state) |

### 4.3 RDP-only modifier row + "123" extra-keys grid

The RDP flavor hosts its own modifier UI inside `rdpInputAreaContainer` (`bVNC/src/main/res/layout/rdp_input_area.xml`):

| Element | Role |
|---|---|
| `ModifierRowView` | Horizontally-scrollable row of 8 keys in order: `[Win, Shift, Ctrl, Alt, Del, Esc, Tab, 123]`. Row height `27dp` (round 5; was 40 dp round 4, 48 dp round 1), per-button width `dp(42)` (round 5; was 56 dp round 4); `makeButton:189-204` zeroes `minHeight`/`minimumHeight` for parity with `ExtraKeysView` and adds `setTextSize(TypedValue.COMPLEX_UNIT_SP, 12)` so all 8 keys share the same text size. The `123` button's rest state (`refreshToggleVisual:353-363`) reuses `COLOR_OFF_BG` / `COLOR_OFF_FG` (matching Del/Esc/Tab) — the prior teal `COLOR_TOGGLE_OFF_BG/FG` constants were deleted; the EXTRA-state highlight (`COLOR_TOGGLE_ON_BG/FG`) is preserved as state feedback while the grid is open. **Round 6** adds idempotent `setRowHeightDp(int)` (`:236-249`) and `setKeySizeDp(int)` (`:253-274`; button width + label text scaled as `12sp × dp/42`, 8 sp floor) so user-tunable SeekBars can resize the row without rebuilding buttons. See `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ModifierRowView.java:61-364`. |
| `RdpExtraGridPanel` | `ExtraKeysView`-based grid (ESC/F1-F6/DEL, TAB/F7-F12/BKSP, HOME/END/PGUP/PGDN/INS/LEFT/DOWN/RIGHT). Toggled by the `123` button. Round 5: `init:76-96` inserts the inner `ExtraKeysView` with `MATCH_PARENT × MATCH_PARENT` `LinearLayout.LayoutParams` (`:93-95`) so the GridLayout's FILL rowSpecs distribute the 192 dp parent height. The default `WRAP_CONTENT` insert caused `ExtraKeysView.reload` (per-button `param.height = 0` on non-Lollipop) to collapse into an empty translucent slab. `ExtraKeysView.java` and `ExtraKeysPagerAdapter.java` (legacy VNC pager) are byte-identical. See `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RdpExtraGridPanel.java:43-112`. |
| `RdpModifierRowHandler` | Orchestrator. Inflates the layout into the activity's container, owns the row+grid, bridges modifier state, consumes one-shot modifiers on dispatch. See `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RdpModifierRowHandler.java:71-406`. |

**Three-state per modifier:** OFF / ON (one-shot, consumed by next dispatched key) / LOCKED (survives consumption, tap to release). The 800 ms double-tap window is the same one used by `RdpKeyboardMapper.checkToggleModifierLock` (`ModifierRowView.DOUBLE_TAP_WINDOW_MS`).

**State machine for the input-area itself:** `InputAreaState` enum (`bVNC/src/main/java/com/iiordanov/bVNC/input/InputAreaState.java`) with three values:

| State | Visible surface |
|---|---|
| `NONE` | No input area. Container hidden. |
| `KEYBOARD` | Software IME + modifier row above it. |
| `EXTRA` | Extra-keys grid + modifier row above it (IME hidden). |

Transitions are driven by `RemoteCanvasActivity.setInputAreaState:1648-1664` and gated by `Utils.isRdp(this)` — non-RDP flavors never enter the state machine.

**Viewport ownership (round 3).** `recomputeRdpViewport` (`RemoteCanvasActivity.java:1677-1708`) is the single source of truth for `canvas.setVisibleDesktopHeight` and `rdpInputAreaContainer.setTranslationY` on RDP. It is called from three places:

1. The RDP-only `ViewCompat.setOnApplyWindowInsetsListener` on `canvasLayout` (`RemoteCanvasActivity.onCreate:322-...`) — reads `WindowInsetsCompat.Type.ime().bottom`, applies a bogus-IME guard (`ime > imeMaxHeight/2 → ime=0`, where `imeMaxHeight` is `canvas.getRdpFullViewHeight() > 0 ? rdpFullViewHeight : canvasLayout.getHeight() + lastImeHeightPx`), drives `InputAreaState` (`KEYBOARD ↔ NONE` only), and calls `recomputeRdpViewport` at `:343`. Returns insets unchanged so children still dispatch.
2. The end of `relayoutViews` (RDP-gated at `RemoteCanvasActivity.java:621`) — re-applies the viewport math on rotation, resize, and IME-driven layout passes.
3. The end of `setInputAreaState` (RDP-gated at `:1661-1663`) — re-applies after the container's `VISIBLE ↔ GONE` flip.

**Full-height capture (`rdpFullViewHeight`).** Captured inside `recomputeRdpViewport` only — never elsewhere. When `lastImeHeightPx == 0` (IME closed), `canvas.setRdpFullViewHeight(canvas.getHeight())`; when the IME is already open on the first call, `setRdpFullViewHeight(canvas.getHeight() + lastImeHeightPx)` so the floor matches the pre-IME physical height. The field is package-scope (`RemoteCanvas.java:117` init, `:911-920` accessors) and is consumed by `ZoomScaling.computeMinimumScale:223-235` for the cover floor (see PAT-014 / INV-017).

**Container translation above the IME.** `imeOverlap = max(0, lastImeHeightPx − (rdpFullViewHeight − canvasH))` is the single formula for both window models: if the window did not resize (`canvasH ≈ rdpFullViewHeight`), `imeOverlap ≈ ime` and the container is lifted; if the window did resize (`canvasH ≈ rdpFullViewHeight − ime`), `imeOverlap ≈ 0` and the bottom-gravity container stays put. Replaces the round-1/2 `rdpContainerTranslation` heuristic, which depended on `setSoftInputMode(SOFT_INPUT_ADJUST_RESIZE)` to fire `getWindowVisibleDisplayFrame().bottom`.

**IME-up collapses `KEYBOARD → NONE` but leaves `EXTRA` alone** (so the grid survives the IME hiding); the legacy 3-page pager is suppressed for RDP. The 19% `r.bottom` heuristic in `relayoutViews:557-606` is the fallback for non-RDP flavors and for the RDP container-visibility transitions; the legacy `setVisibleDesktopHeight + relativePan` shrink block at `relayoutViews:521-524` is RDP-gated out (`if (!Utils.isRdp(this))`). Back-press when in `KEYBOARD` or `EXTRA` collapses to `NONE` instead of finishing the Activity (`onBackPressed:1803-1807`); the round-5 double-back disconnect path (`onBackPressed:1808-1817`) handles the IME-hidden case — see §4.3.1.

**Bridging to `onScreenMetaState` (INV-010):** `RdpModifierRowHandler.syncRowStateToKeyboard:308-317` calls `keyboard.clearMetaState()` then `keyboard.onScreen{Ctrl,Alt,Shift,Super}Toggle()` for every modifier where `rowView.isOnOrLocked(...)` is true — the canonical INV-010 pattern, used by both bridges.

**One-shot consumption hook:** `RemoteRdpKeyboard.KeyDispatchedListener` (`:29-38, 118-135`) fires after every non-modifier `ACTION_DOWN`/`ACTION_MULTIPLE` dispatch. `RdpModifierRowHandler` registers itself in `attachKeyboardIfAvailable:274-286` and its `onKeyDispatched:351-355` calls `rowView.consumeOnModifiers()`, which clears ON (non-locked) modifiers and re-bridges the remaining LOCKED ones. Pointers do **not** consume modifiers. **Round 6**: the listener skips `KEYCODE_TAB` (`:127-131` early-return) so a Tab tap does NOT consume a one-shot modifier — Alt+Tab / Ctrl+Tab / Shift+Tab cycle instead of pressing-and-releasing the modifier. After `consumeOnModifiers`, the handler explicitly calls `syncRowStateToKeyboard` (`:374-380`) so LOCKED bits stay in `onScreenMetaState` while consumed one-shot bits fall out (consumption is now internal — `consumeOnModifiers` no longer fires its own listener to avoid a double-bridge).

**Modifier release on row toggle (round 6, INV-024):** `RdpModifierRowHandler.onRowModifierStateChanged:308-322` snapshots `keyboard.getOnScreenMetaState()` (new public getter at `RemoteKeyboard.java:202-208`), calls `syncRowStateToKeyboard`, then computes `released = prev & ~now` and invokes `keyboard.releaseOnScreenModifiers(released)`. That delegates to `RdpCommunicator.releaseModifierKeys(int)` (`:236-256`), which walks `modifierMap` and for every bit currently held per `RemoteKeyboardState.isRemoteKeyDown(int)` (`:220-232`) fires a single VK UP via `sendKeyEventOnNewThread`. The guard against `isRemoteKeyDown` is what prevents a spurious LMENU release when the user toggles Alt ON then OFF without pressing a key in between. See INV-024.

**Reset on disconnect (INV-008, RDP-side fix):** `RdpModifierRowHandler.resetRowState:228-234` clears every row modifier AND calls `keyboard.clearMetaState()`. `RemoteCanvasActivity.hideKeyboardAndExtraKeys` wires it into the disconnect path. The legacy `clearMetaState()` call in `RemoteConnection.closeConnection` (the only pre-existing reset) is retained.

**Floating keyboard toggle button (every flavor, round 7):** `keyboardToggleButton` (`@+id/keyboardToggleButton`, in `canvasLayout` z-order; round-7 PAT-007 puts `toolbarToggleButton` above it). Round 7: no `android:visibility` attribute — visible by default on **all four** flavors (bVNC / aRDP / aSPICE / Opaque); `Utils.isRdp(this)` is no longer gating. Drag-vs-tap is distinguished by an inline `OnTouchListener` that moves the View on `ACTION_MOVE` once finger motion exceeds `getScaledTouchSlop()` and dispatches `performClick()` on `ACTION_UP` otherwise (`RemoteCanvasActivity.onCreateOptionsMenu:1131-1174`). Position is session-only (no SharedPreferences; contrast with the new `toolbarToggleButton` FAB which IS persisted — see PAT-007). The click handler `onKeyboardToggleButtonClicked:1757-1773` branches on `Utils.isRdp(this)`: RDP routes to `rdpModifierRowHandler.onBackToKeyboard()` (and falls back to `setInputAreaState(KEYBOARD)` if the row handler hasn't been built yet); non-RDP calls `showKeyboard()` / `hideKeyboard()` directly, which sets `softKeyboardUp` and drives the legacy 19 % `relayoutViews` heuristic on the next `onGlobalLayout`. Both `showKeyboard` (`:1617-1626`) and `hideKeyboard` (`:1628-1638`) reset `toolbarExpanded = false` so a stale flag can't trap the next toggle-FAB tap as a no-op. Round 5 visual: 40 dp × 40 dp (was `wrap_content`), 5 dp padding, hamburger icon (`@drawable/ic_baseline_menu_48`); background drawable is `@drawable/bg_keyboard_toggle.xml` (13 dp corners, `#40000000` 25 % black). Round 6 visual: 32 dp × 32 dp, padding 4 dp; `bg_keyboard_toggle.xml` corners 10 dp, fill `#33000000`. `canvas.xml` and `layout-large/canvas.xml` are kept byte-identical at `:155-166` (round 5/6) and now also at `:168-178` for the new `toolbarToggleButton` FAB.

**Floating toolbar-toggle button (every flavor, round 7):** `toolbarToggleButton` (`@+id/toolbarToggleButton`, **last child** of `canvasLayout`; topmost of stack per PAT-007). Lives at `right|center`; the toolbar's `leftHandedModeTag` gravity is mirrored onto the FAB at activity start so the FAB begins on the same side as the toolbar it owns (`RemoteCanvasActivity.continueConnecting:477-485`). After that, the FAB can be dragged independently and the FAB position is **persisted per-connection** to the legacy `USELASTPOSITIONTOOLBAR_X` / `_Y` / `_MOVED` columns (same columns the now-removed `moveToolbar` menu item wrote — round-7 FAB asymmetry, see PAT-007). Drag-vs-tap wired by `setupToolbarToggleButton:1783-1830` (same `OnTouchListener` pattern as the keyboard FAB): `ACTION_DOWN` captures finger position and current FAB offset; `ACTION_MOVE` past `scaledTouchSlop` animates `x/y`; `ACTION_UP` dispatches `performClick()` if no drag, else saves X/Y via `saveToolbarTogglePosition:1928-1938`. The save runs `connection.save(this)` on `handler.post(...)` so the SQLite `UPDATE` in `Database.runWritable` happens off the UI thread (replaces the deleted `ActionBarPositionSaver` pattern). Position is re-applied on every layout pass through `restoreToolbarTogglePosition:1910-1926` (called from the legacy `offsetOrRestoreSavedToolbarPosition:702-704` hook); out-of-bounds positions leave the FAB at its gravity default. The click handler `toggleToolbarExpansion:1839-1850` calls `actionBar.show()` / `actionBar.hide()` and updates the `toolbarExpanded` flag — while `softKeyboardUp` is true the FAB tap is a no-op (the IME owns the screen). At expand time the toolbar is anchored to the FAB via `positionToolbarNextToToggle:1859-1901`: preferred left of the FAB; fallback right if there isn't room; clamped to canvas bounds. Visual: 32 dp × 32 dp, 8 dp padding, `@drawable/bg_keyboard_toggle.xml` background, `@drawable/ic_overflow_vertical_24.xml` Material vertical 3-dot icon (tint `#A0A0A0`). `contentDescription=@string/show_menu` (`bVNC/src/main/res/values/strings.xml:1011`).

**Toolbar visibility model (round 7).** The `RemoteToolbar` action bar is no longer auto-shown or auto-hidden by touch input. `RemoteCanvasActivity.showActionBar` (`:1583-1589`) is a deliberate no-op — kept so the 5 touch-input call sites in `:bVNC/src/main/java/com/iiordanov/bVNC/input/` and `ScrollWheelButton` continue to compile. The legacy `ActionBarHider` / `ActionBarShower` / `ActionBarPositionSaver` Runnable inner classes and the `OnTouchViewMover toolbarMover` field were deleted. The toolbar starts hidden (`canvas.xml:25` declares `visibility="gone"` on `<com.undatech.opaque.util.RemoteToolbar>`); only the `toolbarToggleButton` FAB or a normal `invalidateOptionsMenu` path brings it up. The `RemoteToolbar` subclass in `remoteClientLib/src/main/java/com/undatech/opaque/util/RemoteToolbar.java` is now an unused wrapper around `Toolbar` (only adds `setPositionToMakeVisible`, no callers); left in place for backward compat. See `docs/PATTERNS.md` PAT-007 and `docs/features/UI_SHELL.md` §6.1 / §6.2.

### 4.3.1 Double back-press disconnects (round 5, RDP-only)

The double-back disconnect branch is reachable only after **two** independent gates clear. (a) Predictive-back must be disabled — see §4.3.6 and ADR-0002 — so the framework dispatches BACK to `Activity.onBackPressed`. (b) The canvas View's `OnKeyListener` (`RemoteClientsInputListener.kt:60-62`) must return `false` for `KEYCODE_BACK` on RDP (and TV), instead of consuming it; the round-6-follow-up fix added this `isTv || Utils.isRdp(activity)` short-circuit. Without (b), the listener would forward BACK to the remote as `VK_ESCAPE` and return `true` (via `RdpKeyboardMapper.keymapAndroid[KEYCODE_BACK] = VK_ESCAPE`, `RdpKeyboardMapper.java:343`), and `onBackPressed` would never run.

After both gates clear, the existing KEYBOARD/EXTRA → NONE collapse branch (`RemoteCanvasActivity.onBackPressed:1803-1807`) handles the IME-visible case, and `onBackPressed:1808-1817` adds an RDP-gated double-back branch for the IME-hidden case. First back-press when `inputAreaState == NONE` stamps `lastBackPressForDisconnect = SystemClock.uptimeMillis()`, toasts `R.string.back_press_to_disconnect` ("Press back again to disconnect"), and returns without forwarding the press to the remote. A second press within `DOUBLE_BACK_DISCONNECT_WINDOW_MS = 2000L` calls `disconnectAndFinishActivity()`. The timestamp is cleared in `onPause:895`. The TV branch (`:1794-1797`, immediate disconnect) and the non-RDP back-keystroke forward (`:1818-1820`, `inputListener.onKey(KEYCODE_BACK)` — only reached for non-RDP non-TV) are unchanged. The new string lives at `bVNC/src/main/res/values/strings.xml:83`.

### 4.3.2 Pinch-to-zoom clamp (round 5 — verified, no code change)

`ZoomScaling.changeZoom:131-144` clamps `newScale` to `[computeMinimumScale, 4.0]` per gesture tick, so pinch-out lands exactly on the cover-scale floor for the current `rdpFullViewHeight`. The floor is recomputed every call (rotation / multi-window resize). `TouchInputHandlerTouchpad` super-feeds `MyScaleGestureDetector` (every multi-pointer event falls through to `TouchInputHandlerGeneric.onTouchEvent:421`), and fresh RDP connections default to `ScaleType.MATRIX` / `ZoomScaling`. The only caveat: a user override of the global scaling pref to `FIT_CENTER` / `CENTER` makes pinch a no-op (pre-existing, unrelated to the floor).

### 4.3.3 Modifier row sizing (round 6 — RDP-only)

Two independent SeekBars live **only** in `global_preferences_rdp.xml` (the base `global_preferences.xml` is untouched). `RemoteCanvasActivity` exposes them as `getRdpModifierKeyHeightDp()` / `getRdpModifierKeySizeDp()` (`:1329-1335`) reading `Constants.rdpModifierKeyHeightDp` / `Constants.rdpModifierKeySizeDp` with defaults `DEFAULT_RDP_MODIFIER_KEY_HEIGHT_DP = 27` and `DEFAULT_RDP_MODIFIER_KEY_SIZE_DP = 42`. Both values are pushed via `RdpModifierRowHandler.applyModifierRowSizing(int, int)` (`:233-247`) which delegates to the idempotent `ModifierRowView.setRowHeightDp(int)` / `setKeySizeDp(int)` (`:236-274`).

| Slider | Default → max | Maps to | Effect |
|---|---|---|---|
| `rdpModifierKeyHeightDp` | 27 → 56 | `ModifierRowView.setRowHeightDp` | `ViewGroup.LayoutParams.height = dp` on the row container; buttons auto-stretch `MATCH_PARENT`. |
| `rdpModifierKeySizeDp` | 42 → 72 | `ModifierRowView.setKeySizeDp` | `lp.width = dp` on every modifier / action / toggle button; label text scales as `12sp × dp/42` with an 8 sp floor (so a dragged-to-minimum key stays readable). |

**Push sites.** Live re-apply on return from Settings: `RemoteCanvasActivity.onResume` (`:916-928`) — same pattern as the round-4 tunables (PAT-016). Also pushed right after `rdpModifierRowHandler.onKeyboardReady()` inside `setInputHandler` (`:1455-1462`) so a freshly built handler picks up the live values. Row-height changes re-trigger the RDP viewport recompute via the existing `onGlobalLayout` listener (PAT-015). The setters store their applied values in `appliedRowHeightDp` / `appliedKeySizeDp` so future rebuilds can re-apply them (getters at `:298-308`).

### 4.3.4 Alt+Tab cycling fix (round 6 — RDP-only)

Round-5 modifier behaviour sent `LMENU DOWN, TAB DOWN/UP, LMENU UP` as a chord; Windows committed to the last-used window instead of cycling the switcher. Locked Alt suffered the same way because tapping Tab consumed the one-shot modifier state. The fix has four pieces, all RDP-only:

1. **Tab exempt from consumption** (`RemoteRdpKeyboard.fireKeyDispatchedIfApplicable:127-131`). The `KeyDispatchedListener` early-returns for `KEYCODE_TAB` (and all standard modifier keycodes) so a Tab tap does not clear ON-state one-shot modifiers. Ctrl+Tab / Shift+Tab inherit cycling for free.
2. **`ACTION_UP` keeps physically-held modifiers down** (`RemoteRdpKeyboard.processLocalKeyEvent:75-90`). New mask: `upMeta = lastDownMetaState & ~onScreenMetaState`. Consumed one-shot bits have already been cleared from `onScreenMetaState` by `consumeOnModifiers` → `syncRowStateToKeyboard`, so they fall out of `upMeta` naturally. Anything still held on the wire (LMENU while the user is mid-Alt+Tab) stays in `upMeta` and stays pressed across the Tab tap, so Windows sees a continuous Alt hold.
3. **Locked modifiers re-bridged after consumption** (`RdpModifierRowHandler.onKeyDispatched:374-380`). The handler now calls `syncRowStateToKeyboard` directly after `consumeOnModifiers`, so LOCKED bits keep flowing into `onScreenMetaState` while consumed one-shot bits fall out. `consumeOnModifiers` itself no longer fires `modListener` (`ModifierRowView.consumeOnModifiers:316-336`) — consumption is internal, not a user toggle, so the listener call was a no-op-with-state-jitter.
4. **Toggle-OFF physically releases held VK exactly once** (`RdpModifierRowHandler.onRowModifierStateChanged:308-322`, `RdpCommunicator.releaseModifierKeys:236-256`, `RemoteKeyboardState.isRemoteKeyDown:220-232`, `RemoteRdpKeyboard.releaseOnScreenModifiers:143-152`, `RemoteKeyboard.getOnScreenMetaState:202-208`). When the user taps a row modifier OFF, the handler snapshots `prev = keyboard.getOnScreenMetaState()`, calls `syncRowStateToKeyboard`, computes `released = prev & ~now`, and for every bit in `released` that is currently physically held (`isRemoteKeyDown(mask)` is true) fires a single VK UP via `sendKeyEventOnNewThread`. The `isRemoteKeyDown` guard is what stops a spurious LMENU release when the user toggles Alt ON then OFF without pressing a key in between. See INV-024.

**Behaviour matrix.**

| Sequence | Wire outcome |
|---|---|
| Tap Alt (one-shot) → Tab → Tab → Tab | `LMENU DOWN, TAB DOWN/UP × 3, LMENU UP` — Windows cycles the switcher. |
| Tap Alt (LOCKED, 800 ms double-tap) → Tab | `LMENU DOWN, TAB DOWN/UP, LMENU still DOWN` — Windows opens/cycles and stays in Alt mode. |
| Tap Alt → press A | `LMENU DOWN, A DOWN/UP, LMENU UP` — Alt+A unchanged from round 5. |
| Tap Alt, tap Alt (toggle OFF) without a key in between | `LMENU DOWN, LMENU UP` (no spurious extras). |
| Hardware keyboard Ctrl+Tab / Shift+Tab | Same cycling behaviour, routed through the same `fireKeyDispatchedIfApplicable` skip. |

VNC/SPICE paths are byte-identical: `RemoteVncKeyboard` / `RemoteSpiceKeyboard` do not implement `KeyDispatchedListener`, do not call `releaseOnScreenModifiers`, and are unaffected.

**Disconnect/teardown release.** `RdpModifierRowHandler.resetRowState` (`:228-234`) still clears the row state and calls `keyboard.clearMetaState()` (INV-008); held on-wire VKs are released via `RemoteConnection.closeConnection`'s dummy key-up sweep, not via `releaseModifierKeys` (which is the toggle-only path).

### 4.3.5 Pinch-zoom in fit-to-screen (round 6 — all flavors with fit-to-screen scaling)

`FitToScreenScaling` previously inherited `AbstractScaling`'s default no-op `changeZoom`, so pinch in fit-to-screen mode silently did nothing. `FitToScreenScaling.changeZoom:91-141` is a new override that mirrors `ZoomScaling.changeZoom` with a single change: the floor is `minimumScale` (the precomputed fit-to-screen scale for the current framebuffer / view), not the live `computeMinimumScale` — so pinch-out lands exactly on the fit level, never below. Scale clamped to `[minimumScale, 4.0]`; focal-anchored pan via `RemoteCanvas.absolutePan((int)newXPan, (int)newYPan)` (called only when `oldScale != newScale`). 1:1 snap at `(0.95, 1.05)` matches `ZoomScaling`.

**Why `absolutePan` and not `relativePan`.** `AbstractScaling.canvasZoomer.isAbleToPan()` is hard-coded `false` for `FitToScreenScaling` (pre-existing); `RemoteCanvas.relativePan` checks that gate and silently no-ops. `absolutePan` (the round-3 path) sets `absoluteXPosition/absoluteYPosition` directly with edge clamping and the same `resetScroll()` redraw — no `isAbleToPan` check. Single-finger drag semantics are unchanged (still no pan in fit-to-screen).

### 4.3.6 Predictive-back manifest opt-out + BACK pass-through (round 6 + round-6-follow-up — project-wide)

`bVNC/src/main/AndroidManifest.xml:29` declares `android:enableOnBackInvokedCallback="false"` on `<application>`. With `targetSdk=36` on Android 13+, the predictive-back dispatch kills the legacy `Activity.onBackPressed` path silently — round-5's double-back disconnect (`RemoteCanvasActivity.onBackPressed:1808-1817`, see §4.3.1) never ran. The manifest flag opts out and restores the framework dispatch to the legacy `onBackPressed` callback for the entire `<application>`, which inherits to every wrapper (`aRDP-app`, `freeaRDP-app`, `bVNC-app`, `freebVNC-app`, `aSPICE-app`, `freeaSPICE-app`, `Opaque-app`, `CustomVnc-app`).

The manifest opt-out alone is **not sufficient** for connected RDP sessions. `RemoteCanvasActivity.setInputHandler` registers `canvas.setOnKeyListener(inputListener)` where `inputListener` is `RemoteClientsInputListener`. The listener intercepts every hardware key at the View level (before `Activity.onKeyDown`/`dispatchKeyEvent`) and would forward `KEYCODE_BACK` to the remote as `VK_ESCAPE` (via `RdpKeyboardMapper.keymapAndroid[KEYCODE_BACK] = VK_ESCAPE`, `RdpKeyboardMapper.java:343`), then return `true` — which causes the View to consume the press and prevents `Activity.onBackPressed` from running regardless of the manifest flag. The round-6-follow-up fix at `RemoteClientsInputListener.kt:60-62` short-circuits BACK to `return false` when `isTv || Utils.isRdp(activity)`, so RDP (and TV, as before) BACK falls through to the Activity. Non-RDP non-TV behaviour is unchanged: BACK is still forwarded to the remote as `VK_ESCAPE`. Together the manifest flag (framework dispatch) and the listener pass-through (View-level non-consumption) form the complete mechanism that makes the round-5 disconnect flow reachable.

**Accepted side-effect.** `ConnectionListActivity` and `GlobalPreferencesActivity` share the manifest, so they also lose the predictive-back animation on Android 13+ devices. Both still handle back correctly (legacy `onBackPressed` / `onBackPressed` in `GlobalPreferencesFragment`) — only the visual animation is missing. See ADR-0002 for the revisit-trigger (proper `OnBackPressedDispatcher` support would let this flag come off).

### 4.4 Legacy on-screen sticky modifier pager (VNC / SPICE / Opaque only)

`bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ExtraKeysView.java` — the middle page of the bottom pager in `canvas.xml`. State in `SpecialButton` (`bVNC/.../extrakeys/SpecialButton.java:31-35`) and `SpecialButtonState.java`. On RDP the pager stays hidden (see §4.3), so this path is no longer the canonical entry point.

The mapping is built in `ExtraKeysPagerAdapter.java:106-122`:

```text
page 1 = [[ESC, TAB, SHIFT, PGUP, PGDN, HOME, UP, END],
          [CTRL, SUPER, ALT, DEL, |, LEFT, DOWN, RIGHT]]
```

With `setSpecialButtonsChangeListener(this::syncKeyboardModifierState)`.

- `RemoteExtraKeysHandler.onExtraKeyButtonClick:60-77` handles a tap:
  1. Reads the current state with `readSpecialButton(SpecialButton, true)` — `autoSetInActive=true` means non-locked buttons auto-clear after one use.
  2. Calls `buildMetaState` (`:79-87`) OR'ing `META_*_ON | META_*_LEFT_ON` for each active modifier.
  3. Synthesizes a `KeyEvent` with that metaState and dispatches via `remoteKeyboard.processLocalKeyEvent(...)`.

- `ExtraKeysPagerAdapter.syncKeyboardModifierState(view)` (`:147-155`) is the legacy INV-010 bridge — same `clearMetaState` + `onScreen*Toggle` sequence.

### 4.5 Double-tap-to-lock (both modifier UIs)

`RdpKeyboardMapper.processToggleButton(int keycode)` (`:622-662`) — handles the on-screen CTRL/ALT/SHIFT/WIN toggles. Double-tap within **800 ms** = lock the modifier ON (`checkToggleModifierLock:712-730`):

```text
if (lastModifierKeyCode != keycode) { lastModifierKeyCode = keycode; lastModifierTime = now; return false; }
if (lastModifierTime + 800 > now)  { lastModifierTime = 0;    return true; }   // locked
else                               { lastModifierTime = now;  return false; }
```

`ModifierRowView` uses the same 800 ms window inline (`DOUBLE_TAP_WINDOW_MS = 800L`). Locked modifiers are preserved by `resetModifierKeysAfterInput` (`:668-688`); only `(!isLocked || force)` paths release them. `clearlAllModifiers()` (`:664-666`) passes `force=true` — the canonical reset path that remains unused outside the `RemoteConnection.closeConnection` site.

### 4.6 `convertEventMetaState` — `KeyEvent.META_*` → internal masks

`RemoteKeyboard.convertEventMetaState(KeyEvent event, int eventMetaState)` (`:285-371`):

- Maps `KeyEvent.META_*_ON` flags to `CTRL/SHIFT/ALT/SUPER_MASK` (and `R*_MASK` for right side).
- **`META_ALT_LEFT_ON` is ignored** for events from a real hardware keyboard or when `isUnicode` is true (`:301-314`). This is so that the **AltGr** symbol entry on European keyboards still produces characters. Any refactor must keep this exception.
- Right-side modifier bits clear their corresponding left-side mask (`:328, 341, 354, 367`).

### 4.7 `shouldDropModifierKeys`

`RemoteKeyboard.shouldDropModifierKeys(KeyEvent)` (`:391-416`) drops `KEYCODE_*_LEFT/RIGHT` modifier events when `isNoQwertyKbd(context)` — i.e., when the event originates from a virtual/touchscreen keyboard and would generate noise on the remote.

### 4.8 `Ctrl+Alt+Del` emulation — special-cased

`RemoteRdpKeyboard.sendMetaKey(MetaKeyBean meta)` (`:91-135`) — branches on `meta.getKeySym()`. For `MetaKeyBean.keyCtrlAltDel` (`:124-131`):

```text
int savedMetaState = onScreenMetaState;
rfb.writeKeyEvent(0, CTRL_MASK | ALT_MASK, false);                                       // stores meta
keyboardMapper.processAndroidKeyEvent(new KeyEvent(ACTION_DOWN, KEYCODE_FORWARD_DEL), false);  // 112 = KEYCODE_FORWARD_DEL
keyboardMapper.processAndroidKeyEvent(new KeyEvent(ACTION_UP,   KEYCODE_FORWARD_DEL), false);
rfb.writeKeyEvent(0, savedMetaState, false);
```

The TODO comment at `:125` ("I should not need to treat this specially anymore") hints the special casing is on a hit list. The chain is selected from menu `R.id.itemCtrlAltDel` (`RemoteCanvasActivity.java:1148-1150`) and from `MetaKeyDialog.sendCurrentKey` (`MetaKeyDialog.java:494-514`).

### 4.9 `MetaKeyDialog`

`bVNC/src/main/java/com/iiordanov/bVNC/dialogs/MetaKeyDialog.java:69-659` — the dialog launched from menu `itemSpecialKeys`. Four checkboxes (Shift/Ctrl/Alt/Super at line `:251-254`) let the user define a `MetaKeyBean` that is persisted in SQLCipher. To send:

1. `MetaCheckListener` (`:637-658`) toggles the bit in `_currentKeyBean.getMetaFlags()`.
2. `sendCurrentKey` (`:494-514`) calls `remoteConnection.getKeyboard().sendMetaKey(_currentKeyBean)`.

Hardware-key interaction:

- `onKeyDown/onKeyUp` (`:444-492`) read `KeyEvent.META_SHIFT_ON` / `META_ALT_ON` to toggle the Shift/Alt checkboxes.
- `KEYCODE_SEARCH` toggles Ctrl (`:467-469`). **Quirk to be aware of**: this is non-standard — SEARCH is a phone-era key, not commonly used. Likely a refactor candidate.

Layout: `bVNC/src/main/res/layout/metakey.xml`.

---

## 5. Hardware-key special handling

| Hardware key | Where | Effect |
|---|---|---|
| `KEYCODE_VOLUME_UP` | `RemotePointer.hardwareButtonsAsMouseEvents:153-156` + `scrollMouse:173-186` | Repeating `scrollUp` via `MouseScroller` runnable (100 ms cadence) |
| `KEYCODE_VOLUME_DOWN` | same | Repeating `scrollDown` |
| `KEYCODE_BACK` on no-Qwerty / pre-gingerbread / virtual hard-key | `RemotePointer.shouldBeRightClick:68-91` | Right-click via `rightClickMouse` |
| `KEYCODE_CAMERA` | same | Right-click on legacy devices |
| `KEYCODE_DPAD_*` (when `useDpadAsPointer=true`) | `RemotePointer.hardwareButtonsAsMouseEvents:155-158` | Mouse movement |
| `KEYCODE_DPAD_CENTER` on TV | `RdpKeyboardMapper:286-288` | Maps to `VK_RETURN` |
| Samsung DEX meta keys | `bVNC/src/main/java/com/iiordanov/util/SamsungDexUtils.kt` | Reflective capture via `SemWindowManager.requestMetaKeyEvent` |

---

## 6. Settings that affect input (for "user wants to tune" scenarios)

| Key | Purpose | Default | Where |
|---|---|---|---|
| `scrollSpeed` | Slider 0–6 → `(slider+1)/7f` | `6` | `global_preferences.xml:44`, `Constants.scrollSpeed` |
| `touchpadSensitivity` | Slider → `(slider+1) * 0.6f` (round 3; was `0.4f`, too slow on 560dpi devices) | `4` | `global_preferences.xml:49`, `Constants.touchpadSensitivity`, `RemoteCanvasActivity.getTouchpadSensitivityMultiplier:1303-...` |
| **`edgeThresholdDp`** (round 4) | Slider → edge-pan distance in dp; pushes `RemoteCanvas.setEdgeThresholdDp` (default reproduces the prior hardcoded 35 dp) | `35` | `global_preferences.xml:52-56`, `Constants.edgeThresholdDp` / `DEFAULT_EDGE_THRESHOLD_DP`, `RemoteCanvasActivity.getEdgeThresholdDpPref:1304-1306` |
| **`mouseAccelerationStrength`** (round 4, **hidden on RDP since round 9**) | Slider/10 → `RemotePointer.accelerationStrength`. `computeAcceleration` lerps pass-through and the legacy curve via `delta = delta + (curve - delta) * strength`; 0 = no accel, 1 = legacy curve exactly, >1 = exaggerated. The existing `accelerated` boolean gate is unchanged. **Round 9**: `GlobalPreferencesFragment.onCreatePreferences` hides the slider when `Utils.isRdp(getContext())` — RDP touchpad sessions use the new `rdpPointerAccel*` curve instead. Non-RDP touchpad still reads this value via `RemoteCanvasActivity.getMouseAccelerationStrength`. | `10` (→ 1.0) | `global_preferences.xml:57-61`, `Constants.mouseAccelerationStrength` / `DEFAULT_MOUSE_ACCELERATION_STRENGTH`, `RemoteCanvasActivity.getMouseAccelerationStrength:1308-1311`; consumed at `TouchInputHandlerTouchpad.computeAcceleration` (non-RDP path) |
| **`rdpPointerAccelEnabled`** (round 9, RDP-only) | `SwitchPreferenceCompat` — enables the new velocity-based acceleration curve on RDP. When disabled, RDP touchpad reduces to the legacy `computeAcceleration` math (same as non-RDP, gated by `accelerated`). | `true` | `global_preferences_rdp.xml:15-19`, `Constants.rdpPointerAccelEnabled` / `DEFAULT_RDP_POINTER_ACCEL_ENABLED`, `RemoteCanvasActivity.getRdpPointerAccelEnabled:1381-1383`; pushed via `TouchInputHandlerTouchpad.setPointerAccel:171-175` |
| **`rdpPointerAccelLowGainPct`** (round 9, RDP-only) | SeekBar → slow-movement gain for `PointerAccelerationCurve`. Slider clamped to 25–300, divided by 100 → effective 0.25–3.0×. The curve knee is `V_REF_DP_PER_S = 500f` (see `PointerAccelerationCurve.java:43`). | `100` (→ 1.0×) | `global_preferences_rdp.xml:20-24`, `Constants.rdpPointerAccelLowGainPct` / `DEFAULT_RDP_POINTER_ACCEL_LOW_GAIN_PCT`, `RemoteCanvasActivity.getRdpPointerAccelLowGain:1385-1390` |
| **`rdpPointerAccelHighGainPct`** (round 9, RDP-only) | SeekBar → fast-movement gain for the curve. Slider clamped to 100–600, divided by 100 → effective 1.0–6.0×. Always clamped to be ≥ `getRdpPointerAccelLowGain()` so the curve never inverts. | `300` (→ 3.0×) | `global_preferences_rdp.xml:25-29`, `Constants.rdpPointerAccelHighGainPct` / `DEFAULT_RDP_POINTER_ACCEL_HIGH_GAIN_PCT`, `RemoteCanvasActivity.getRdpPointerAccelHighGain:1392-1397` |
| **`rdpPointerAccelReset`** (round 9, RDP-only) | Click `Preference` — restores the three `rdpPointerAccel*` values to their defaults via `GlobalPreferencesFragment.resetRdpPointerAccel:54-69`, then toasts `rdp_pointer_accel_reset_toast`. Updates the visible Switch + SeekBars in place (no recursive fragment pop). | (action) | `global_preferences_rdp.xml:30-33`, `Constants.rdpPointerAccelReset`, `GlobalPreferencesFragment.resetRdpPointerAccel:54-69` |
| **`flingResistance`** (round 4) | Slider → `0.92f - slider*0.01f` (slider default 6 → damp 0.86 = legacy `FLING_DAMP`); higher slider = more resistance = shorter fling | `6` | `global_preferences.xml:62-66`, `Constants.flingResistance` / `DEFAULT_FLING_RESISTANCE`, `RemoteCanvasActivity.getFlingResistanceDamp:1399-1402`; consumed at `TouchInputHandlerTouchpad.Flinger.run:637-638` via `setFlingDamp` |
| `softwareKeyboardType` | ListPreference | `TYPE_NULL` | `global_preferences.xml:40` |
| `preferSendingUnicode` | Per-connection bool | `true` | `Constants.preferSendingUnicode`, `ConnectionBean.PREFERSENDINGUNICODE` |
| `useDpadAsPointer` | Per-connection | varies | `Constants.useDpadAsArrows` |
| `rotateDpad` | Per-connection bool | false | `Constants.rotateDpad` |
| `layoutMap` | Per-connection keyboard layout | `English (US)` | `ConnectionBean.LAYOUTMAP` |
| `rAltAsIsoL3Shift` | Treat right-Alt as ISO L3 Shift (VNC overlay) | `true` | `global_preferences_vnc.xml:5` |
| `defaultInputMethod` | Per-flavor input-mode default. **For RDP new connections**, `ConnectionBean.getDefaultInputMode:183-193` returns `TOUCHPAD_MODE` (touchpad with RDP-only gestures) instead of the SharedPreferences default. Stored `INPUTMODE` values are not migrated. | RDP touchpad, otherwise swipe-pan | `Constants.defaultInputMethodTag` |

**`doubleTapSlopDp`** (round 9): the SeekBar max was raised 48 → 150 dp (`global_preferences.xml:67-71`); default stays at 24 dp. Note for tuning: `slopPx = max(pref dp × density, viewConfiguration.getScaledDoubleTapSlop())` — values below the framework threshold (~100 dp on most devices) do not tighten stock detection, they only widen beyond it. Round 9 dropped the timeout slider's hardcoded 100 ms floor in favour of `viewConfiguration.getDoubleTapTimeout()` (per-Activity snapshot, see `TouchInputHandlerGeneric:160-170`).

**Push path for round-4 tunables.** Each new setting follows the same `global_preferences.xml` row → `Constants.*` key+default → `RemoteCanvasActivity` getter → consumer setter sequence, and is re-applied on every `getInputHandlerById` (so a freshly constructed touchpad handler picks up the slider value) and in `onResume` (live re-apply on return from Settings).

**Round-9 RDP-only carve-out.** The three `rdpPointerAccel*` keys live only in `global_preferences_rdp.xml` (overlay, not base) and are pushed to `TouchInputHandlerTouchpad.setPointerAccel(enabled, lowGain, highGain)` from `RemoteCanvasActivity.onResume:965-967` and `getInputHandlerById:1321-1324` (the same two push sites as the round-6 modifier-row sizing). The legacy `mouseAccelerationStrength` slider is hidden on RDP via `GlobalPreferencesFragment.onCreatePreferences` — non-RDP touchpad still reads it via `RemoteCanvasActivity.getMouseAccelerationStrength` and `TouchInputHandlerTouchpad.computeAcceleration` (legacy math). The curve is consulted exactly once per `MotionEvent` via `lastCurveEventTime` (`:685-693`); density-normalized raw finger deltas feed the velocity input. RDP-only sub-pixel carry (`carryX`/`carryY` via `carryFor`, truncation toward zero) handles slow movements below 1 px/event. Resets on `onDown`, `setRdp(false)`, and `setPointerAccel`.

`Constants.scrollSpeed` → `(slider+1)/7f` is consumed in:
- `RemoteCanvasActivity.getScrollRate():1106-1109` → `scrollRate` passed into each `TouchInputHandler` constructor
- Inside `TouchInputHandlerGeneric`:
  - `maxSwipeSpeed = round(7 * scrollRate)` (`:120`)
  - `baseSwipeDist = 10dp / scrollRate` (`:137`)

`Constants.touchpadSensitivity` → `(slider+1) * 0.6f` is consumed in:
- `RemoteCanvasActivity.getTouchpadSensitivityMultiplier():1282-1286` → `pointer.setSensitivity(...)` (`:1227`)

---

## 7. State transitions / lifecycle touches

| Event | Effect on input state |
|---|---|
| `OnConnectionSuccess(long)` (`RdpCommunicator.java:413-418`) | `isInNormalProtocol = true`. Gates all input in `RemoteRdpKeyboard.processLocalKeyEvent`. |
| `OnConnectionFailure(long)` | `isInNormalProtocol = false`. |
| `OnSettingsChanged(w, h, bpp)` | Reallocate drawable; possibly `REINIT_SESSION`. **Pointer position is NOT reset** — known leak. |
| `OnDisconnecting` | Credential retry path. |
| `OnDisconnected` | Posts disconnect message. |
| `RemoteConnection.closeConnection:278-310` | Sets `maintainConnection=false`; calls `keyboard.clearMetaState()` + dummy key-up; **does NOT** reset `RemoteKeyboardState.hardwareMetaState` or `remoteKeyboardMetaState`. See INV-008. |

---

## 8. Hot spots — likely places to modify for the planned input rework

Ranked by likelihood (most likely first).

1. `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpPointer.java`
   - `sendPointerEvent:107-134` (modifier piggy-back, prev-pointer-mask clearing)
   - `sendButtonDownOrMoveButtonDown:34-40`
   - `moveMouseButtonDown:88-92` — sticky LEFT/RIGHT/MIDDLE preserved from `prevPointerMask`
   - `releaseButton:100-105`
   - `scrollLeft/scrollRight:73-80` — currently no-op; you may want to add horizontal scroll via `LibFreeRDP.sendCursorEvent` with horizontal wheel flags.
2. `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpKeyboard.java`
   - `processLocalKeyEvent:53-109`
   - `sendMetaKey:137-181` (Ctrl+Alt+Del special case)
   - `KeyDispatchedListener:29-38` + `setKeyDispatchedListener:36-38` + `fireKeyDispatchedIfApplicable:118-135` — one-shot consumption hook used by the RDP modifier row.
3. `bVNC/src/main/java/com/iiordanov/bVNC/input/InputAreaState.java` — RDP-only `NONE` / `KEYBOARD` / `EXTRA` enum.
4. `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ModifierRowView.java` — horizontally-scrollable row of 8 keys; OFF/ON/LOCKED per modifier; 800 ms double-tap = LOCKED.
5. `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RdpModifierRowHandler.java` — orchestrator (RDP-only). `attach/onKeyboardReady/onInputAreaStateChanged/resetRowState/onKeyDispatched/syncRowStateToKeyboard`.
6. `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RdpExtraGridPanel.java` — "123" extra-keys grid (F-keys + navigation).
7. `bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerTouchpad.java` — `setRdp(boolean):138-...`; fling (`Flinger` inner class, `FLING_TICK_MS=20`, runtime-configurable `flingDamp` default `FLING_DAMP=0.86f`, `FLING_NOISE_PX_PER_S=200`), `onFling:247-280` (returns false while adaptive-double-tap is `PENDING` or `DRAGGING`), `onLongPress:373-...` synthesized right click (down + 40 ms delayed up), clears adaptive-double-tap state + `edgePinRepeater` + `doubleTapTracker` for mutual exclusion. `onDoubleTap` now delegates to `onManualDoubleTap` override at `:425-455` (round 9); `onTouchEvent` carries the per-event `pointerAccelCurve.gain(...)` path, the `lastCurveEventTime` cache (`:685-693`), and the per-axis sub-pixel carry (`carryX`/`carryY` + `carryFor`). Helpers `commitDoubleTapDrag` / `emitDoubleTapDoubleClick` / `cancelDoubleTapGesture` / `updateEdgePinRepeater` implement the adaptive state machine plus round-4 drag-hold edge pinning via `EdgePinRepeater` (`EDGE_PIN_BAND_DP=24f`, `EDGE_PIN_SPEED_DP_PER_S=100f`). `rdpTouchSlop` is `max(2, 8*density)` (`DRAG_THRESHOLD_DP=8f` — round 9; was `2f` round 4; was `getScaledTouchSlop()/2` round 2/3). `setPointerAccel(boolean, float, float):171-175` is the new RDP-only config push.
8. `remoteClientLib/src/main/java/com/undatech/opaque/input/RdpKeyboardMapper.java`
   - `keymapAndroid[]` initialization at `:241-444`
   - `processAndroidKeyEvent:458-534`
   - `processToggleButton:622-662`, `resetModifierKeysAfterInput:668-688`, `clearlAllModifiers:664-666`, `checkToggleModifierLock:712-730`
9. `remoteClientLib/src/main/java/com/undatech/opaque/input/RemoteKeyboardState.java`
   - `detectHardwareMetaState:36-192`
   - `shouldSendModifier:194-214`
   - `updateRemoteMetaState:216-222`
10. `remoteClientLib/src/main/java/com/undatech/opaque/input/RemoteKeyboard.java`
    - `convertEventMetaState:285-371`
    - `shouldDropModifierKeys:391-416`
    - `onScreen{Ctrl,Alt,Shift,Super}{Toggle,Off}:114-199`
    - `clearMetaState:213-215`, `getMetaState:201-203`, `lastDownMetaState:80`
11. `remoteClientLib/src/main/java/com/undatech/opaque/RdpCommunicator.java`
    - `writePointerEvent:156-167`
    - `writeKeyEvent:179-187`
    - `sendModifierKeys:222-234`
    - `processVirtualKey:239-251`
    - `processUnicodeKey:263-274`
    - `inputExecutor:62-69`, `sleepBetweenInputEvents:289-294`
12. `bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerGeneric.java`
    - `handleMouseActions:174-283`
    - `sendScrollEvents:288-310`
    - `onSingleTapConfirmed/onDoubleTap/onLongPress:316-369`
    - `dragMode` / `rightDragMode` / `middleDragMode` flags at `:58-67`
13. `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteVncPointer.java` + `RemoteSpicePointer.java` — `moveMouseButtonDown` (`:101-104` VNC, `:100-103` SPICE) preserves sticky LEFT/RIGHT/MIDDLE from `prevPointerMask` (cross-flavor defect fix).
14. `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RemoteExtraKeysHandler.java:60-87` (legacy VNC/SPICE/Opaque pager)
15. `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ExtraKeysPagerAdapter.java:147-155` (legacy INV-010 bridge)
16. `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteClientsInputListener.kt:52-152`
17. `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java` — `setInputAreaState:1648-1664`, `updateRdpInputAreaVisibility:1717-...`, `onKeyboardToggleButtonClicked:1757-1773` (RDP `InputAreaState` cycle vs. plain `showKeyboard` / `hideKeyboard` on non-RDP — round 7), `setInputHandler:1415-...` (RDP handler attach), `keyboardToggleButton` inline drag-vs-tap listener `:1131-1174` (round-7 click handler dispatches via `onKeyboardToggleButtonClicked`), `toolbarToggleButton` inline drag-vs-tap listener + click + persistence `:1783-1830` (`setupToolbarToggleButton`), `toggleToolbarExpansion:1839-1850`, `positionToolbarNextToToggle:1859-1901`, `restoreToolbarTogglePosition:1910-1926` + `saveToolbarTogglePosition:1928-1938` (round 7), `showActionBar:1583-1589` (deliberate no-op — round 7), RDP-only IME insets listener on `canvasLayout` at `:322-...`, `recomputeRdpViewport:1677-1708` (single RDP viewport owner — round 3), `getTouchpadSensitivityMultiplier:1303-...` (round 3 coefficient `0.6f`), RDP-gated legacy shrink block at `relayoutViews:521-524`, end-of-relayout RDP recompute at `:621`, `onBackPressed:1803-1807` (KEYBOARD/EXTRA collapse) + `:1808-1817` (round 5 double-back disconnect), `setModes` touchpad fallback at `:866-867`. **Round-4:** `onCreate:311` pushes `setEdgeThresholdDp`; `onResume:905-918` re-pushes all three tunables; `getInputHandlerById:1237-1268` (esp. `:1245` `setAccelerationStrength`, `:1267-1268` `setFlingDamp`); prefs `getEdgeThresholdDpPref:1304-1306`, `getMouseAccelerationStrength:1308-1311`, `getFlingResistanceDamp:1313-1316`. **Round-6:** `onResume:916-928` re-pushes `applyModifierRowSizing(getRdpModifierKeyHeightDp(), getRdpModifierKeySizeDp())`; `setInputHandler:1455-1462` pushes the same sizing right after `onKeyboardReady`; `getRdpModifierKeyHeightDp` / `getRdpModifierKeySizeDp` at `:1332-1338`. **Round-7:** `continueConnecting:477-485` mirrors the toolbar's `leftHandedModeTag` gravity onto the new `toolbarToggleButton` FAB; the legacy `ActionBarHider` / `ActionBarShower` / `ActionBarPositionSaver` Runnable inner classes and the `toolbarMover` field were deleted; `offsetOrRestoreSavedToolbarPosition:702-704` now forwards to `restoreToolbarTogglePosition()`.
18. `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java:823-832`, `:738-744`, `:733-735`, **`rdpFullViewHeight` field `:117`, accessors `:911-920` (round 3)**, **`edgeThreshDp` field `:71` + `EDGE_THRESH_DP` constant `:70` + `setEdgeThresholdDp:915-917` + consumption in `movePanToMakePointerVisible:560-...` (round 4)**
19. `bVNC/src/main/java/com/iiordanov/bVNC/dialogs/MetaKeyDialog.java:251-254`, `:444-492`, `:494-514`, `:637-658`
20. `bVNC/src/main/java/com/iiordanov/bVNC/ZoomScaling.java:223-235` — RDP-only `computeMinimumScale` cover-scale clamp; round 3 reads `canvas.rdpFullViewHeight` instead of the shrunk `visibleHeight`. **Round-6 pair:** `bVNC/src/main/java/com/iiordanov/bVNC/FitToScreenScaling.java:91-141` — new `changeZoom` override mirroring `ZoomScaling`; floor is the precomputed `minimumScale`, focal-anchored pan via `RemoteCanvas.absolutePan`.
21. `bVNC/src/main/java/com/iiordanov/bVNC/ConnectionBean.java:183-193` — `getDefaultInputMode` writes `TOUCHPAD_MODE` for new RDP connections.
22. **Round-6 modifier-release path (RDP-only):** `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RdpModifierRowHandler.java:308-322` (`onRowModifierStateChanged` snapshots `getOnScreenMetaState`, bridges, then `kb.releaseOnScreenModifiers(released)`); `:374-380` (`onKeyDispatched` re-bridges after `consumeOnModifiers`); `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ModifierRowView.java:236-296` (`setRowHeightDp/setKeySizeDp/resizeKeyButton` + applied-dp getters) + `:316-336` (`consumeOnModifiers` no longer fires listener); `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpKeyboard.java:75-90` (ACTION_UP mask `lastDownMetaState & ~onScreenMetaState`) + `:127-131` (KEYCODE_TAB exempt from `KeyDispatchedListener`) + `:143-152` (`releaseOnScreenModifiers`); `remoteClientLib/src/main/java/com/undatech/opaque/input/RemoteKeyboard.java:202-208` (`getOnScreenMetaState`); `remoteClientLib/src/main/java/com/undatech/opaque/input/RemoteKeyboardState.java:220-232` (`isRemoteKeyDown`); `remoteClientLib/src/main/java/com/undatech/opaque/RdpCommunicator.java:236-256` (`releaseModifierKeys`). See INV-024.
23. **Round-6 manifest:** `bVNC/src/main/AndroidManifest.xml:29` — `android:enableOnBackInvokedCallback="false"` on `<application>`. Inherited by every wrapper. See INV-025 + ADR-0002.

---

## 9. Invariants the input rework must NOT break

(Recap from `DESIGN_PRINCIPLES.md` §INV; reproduced here because the input work has the highest blast radius.)

- INV-001 — every input event goes through `RdpCommunicator.inputExecutor`. Do not call `LibFreeRDP.sendKeyEvent` etc. from elsewhere.
- INV-003 — `UltraCompactBitmapData.updateBitmap` is `synchronized (mbitmap)`. Don't touch that lock.
- INV-006 — frame repaint throttle; bypass it and native bitmap-copy contention appears.
- INV-008 — on-screen modifier state MUST be cleared on disconnect. For the RDP modifier row the path is `RdpModifierRowHandler.resetRowState()` (called from `RemoteCanvasActivity.hideKeyboardAndExtraKeys`); VNC/SPICE/Opaque retain the older `RemoteConnection.closeConnection` → `keyboard.clearMetaState()` site. `hardwareMetaState` and `remoteKeyboardMetaState` still leak across reconnects — that is the remaining gap.
- INV-010 — sticky-modifier ↔ keyboard mirroring only via the canonical `clearMetaState` + `onScreen*Toggle` bridge. Two callers: legacy `ExtraKeysPagerAdapter.syncKeyboardModifierState` and RDP `RdpModifierRowHandler.syncRowStateToKeyboard`.
- INV-011 — `POINTER_DOWN_MASK = 0x8000` and the "release previous before new down" guard in `sendPointerEvent`. Removing this causes the remote OS to see two buttons held simultaneously.
- INV-013 — `RemoteKeyboardState.shouldSendModifier` deduplicates modifier sends.
- INV-014 — IME text pipe via `BaseInputConnection`.
- INV-016 — `RemoteRdpKeyboard.KeyDispatchedListener` fires only on `ACTION_DOWN` / `ACTION_MULTIPLE`, only for non-modifier keycodes (round 6 also exempts `KEYCODE_TAB`), only on the successful dispatch path. Listeners must NOT consume LOCKED modifiers and must NOT release the modifier bits used by `lastDownMetaState` (which the round-6 ACTION_UP mask `lastDownMetaState & ~onScreenMetaState` re-derives per key) for the replay. `ModifierRowView.consumeOnModifiers` no longer fires its listener — consumption is internal; the handler's `onKeyDispatched` re-bridges via `syncRowStateToKeyboard` (round 6).
- INV-017 — `ZoomScaling.computeMinimumScale` for RDP returns a cover scale (`max(viewW/fbW, viewH/fbH)`) so no black borders appear at minimum zoom; non-RDP returns `canvas.getMinimumScale()` unchanged. The floor uses `canvas.rdpFullViewHeight` (the PHYSICAL full-screen height captured when the IME is closed) — never the shrunk `visibleHeight`. The self-referential `visibleHeight` floor was the round-2 bug that disabled `movePanToMakePointerVisible`'s vertical pan when the window did not resize. The floor is recomputed every call so it tracks the live `rdpFullViewHeight`.
- INV-018 — `InputAreaState` (RDP-only) drives the entire `@+id/rdpInputAreaContainer` lifecycle. `setInputAreaState` is the only mutator; visibility is reapplied after the state is set. Anything that hides the IME on RDP must not collapse `EXTRA → NONE`. Round 3: the `KEYBOARD ↔ NONE` transition is also driven by the IME insets listener (`onCreate:322-...`) on top of the legacy `relayoutViews` 19% heuristic — non-RDP flavors do not enter the listener. Round 5: the IME-hidden `onBackPressed` branch (`:1808-1817`) does NOT mutate `InputAreaState` — only `disconnectAndFinishActivity` is reachable from there.
- INV-019 — All RDP-only UX elements (`ModifierRowView`, `RdpExtraGridPanel`, `RdpModifierRowHandler`, `rdpInputAreaContainer`, `recomputeRdpViewport`, the IME insets listener, RDP touchpad gestures, the round-5 `movePanToMakePointerVisible()` call inside `Flinger.run:667`, the round-5 "123" grid `MATCH_PARENT` insert in `RdpExtraGridPanel.init:93-95`, the round-5 double-back disconnect in `onBackPressed:1808-1817`, the round-6 modifier row sizing prefs `rdpModifierKeyHeightDp` / `rdpModifierKeySizeDp` and their `applyModifierRowSizing` push sites, the round-6 Alt+Tab cycling fix in `RemoteRdpKeyboard.fireKeyDispatchedIfApplicable:127-131` and `processLocalKeyEvent:75-90`, the round-6 toggle-OFF `releaseOnScreenModifiers` / `releaseModifierKeys` path, **round 9**: `PointerAccelerationCurve` (the `isRdp` gate inside `TouchInputHandlerTouchpad.getDelta`), the `rdpPointerAccel*` settings + the `rdpPointerAccelReset` action, `sendPointerEvent`'s MOVE-dedup guard (RDP-only — VNC/SPICE pointers untouched), and `DoubleTapPairTracker` integration via the `TouchInputHandlerTouchpad` `isRdp` guard inside `onManualDoubleTap`'s override) are gated by `Utils.isRdp(this)` or by the `TouchInputHandlerTouchpad` `setRdp(true)` guard. **Round-7 carve-out:** `keyboardToggleButton` and `toolbarToggleButton` FABs are visible on every flavor — they are NOT RDP-only. The keyboard FAB click handler (`onKeyboardToggleButtonClicked:1757-1773`) still branches on `Utils.isRdp(this)` (RDP routes into `RdpModifierRowHandler.onBackToKeyboard`; non-RDP calls `showKeyboard` / `hideKeyboard`). Non-RDP flavors (bVNC/aSPICE/Opaque) must remain byte-identical to the pre-RDP UX for the RDP-only subsystems.
- INV-020 (round 3) — `recomputeRdpViewport` is the **single source of truth** for `canvas.setVisibleDesktopHeight` and `rdpInputAreaContainer.setTranslationY` on RDP. The legacy `relayoutViews` shrink block (`setVisibleDesktopHeight + relativePan`) is RDP-gated out (`:521-524`). `rdpFullViewHeight` is only ever set to the physical full-screen height (`canvas.getHeight()` when IME closed, or `canvas.getHeight() + lastImeHeightPx` if the IME was already open on first capture) — never to the shrunk viewport.
- INV-021 (round 4, **amended round 9**) — Runtime-configurable tunables reproduce prior hardcoded behavior at their default slider values **for non-RDP sessions** (the legacy `mouseAccelerationStrength` 1.0 / `edgeThresholdDp` 35 dp / `flingResistance` damp 0.86). Pushes happen in `onCreate` (edge only), `onResume` (live re-apply), and on every `getInputHandlerById` (freshly constructed touchpad handler picks up the slider). Round 9 amend: RDP sessions use a new velocity-based acceleration curve (`PointerAccelerationCurve`); non-RDP sessions keep the legacy `computeAcceleration` byte-for-byte. The `accelerated` boolean gate on `RemotePointer` is unchanged. The legacy `mouseAccelerationStrength` slider is hidden in `GlobalPreferencesFragment` when `Utils.isRdp(getContext())` (RDP-only carve-out — non-RDP flavors still see it and still read it from `RemoteCanvasActivity.getMouseAccelerationStrength`).
- INV-022 (round 4, **amended round 9**) — `TouchInputHandlerTouchpad.rdpTouchSlop` is a fixed `max(2, DRAG_THRESHOLD_DP * density)` px threshold, not the legacy `getScaledTouchSlop()/2`. Round 9 amend: `DRAG_THRESHOLD_DP = 8f` (was `2f`); the previous 2 dp value was below typical finger jitter and converted intended double-clicks into drags. Movement past the threshold commits a press-and-drag; near-zero movement yields a double-click. Modifying this knob changes the disambiguation boundary between drag and double-click.
- INV-023 (round 4) — `EdgePinRepeater` (RDP-only) keeps the remote cursor moving while a committed double-click+drag is pinned in the 24 dp canvas-edge band. Displacement uses the same `vx*dt*sensitivity/displayDensity*cbrt(zoom)` formula as `Flinger.run` but with constant velocity (no damping). Stops on `ACTION_UP`/`ACTION_CANCEL`/`ACTION_DOWN`/`setRdp(false)`/`onLongPress`, finger leaving the band, or remote-desktop edge clamp (`beforeX==afterX && beforeY==afterY`).
- INV-024 (round 6) — On-wire modifier VK release is exclusively a user-toggle-OFF side effect. ONLY the path `RdpModifierRowHandler.onRowModifierStateChanged` (which fires on a row toggle, not on a key dispatch or a disconnect) may call `RemoteRdpKeyboard.releaseOnScreenModifiers(int)` → `RdpCommunicator.releaseModifierKeys(int)`, and the release path is gated by `RemoteKeyboardState.isRemoteKeyDown(int)` so a bit that was never physically sent DOWN cannot be released. Key consumption (`consumeOnModifiers` on `KeyDispatchedListener`), `resetRowState` on disconnect, and `clearMetaState` must NOT call `releaseModifierKeys` — they only mutate `onScreenMetaState`; held-on-wire VKs are released via the round-5 dummy key-up sweep in `RemoteConnection.closeConnection`. The legacy VNC/SPICE pager (`RemoteExtraKeysHandler`) is not affected.
- INV-025 (round 6 + round-6-follow-up) — Predictive-back is opted out project-wide via `android:enableOnBackInvokedCallback="false"` on `<application>` in `bVNC/src/main/AndroidManifest.xml`. Inherited by every wrapper. The manifest flag is **necessary but not sufficient** for connected RDP sessions: it restores framework dispatch to `Activity.onBackPressed`, but the canvas View's `OnKeyListener` must also return `false` for `KEYCODE_BACK` on RDP (and TV) — see `RemoteClientsInputListener.kt:60-62` — otherwise the listener forwards BACK to the remote as `VK_ESCAPE` (`RdpKeyboardMapper.java:343`) and consumes the press at the View level. Together the manifest flag and the listener pass-through make round-5's double-back disconnect (`onBackPressed:1808-1817`) reachable on Android 13+. If `OnBackPressedDispatcher` support is added properly (per-flavor handlers in `RemoteCanvasActivity` / `ConnectionListActivity` / `GlobalPreferencesActivity`), the manifest flag MUST be removed — see ADR-0002. Until then, document both pieces as a deliberate, scoped opt-out.
- INV-027 (round 9) — `RemoteRdpPointer.sendPointerEvent:130-136` emits at most one `MOUSE_BUTTON_MOVE`-flagged PDU per call: the `MOUSE_BUTTON_MOVE | pointerMask` write happens only when it differs from `pointerMask` itself (i.e. `(pointerMask & MOUSE_BUTTON_MOVE) == 0`). When `pointerMask` already contains the MOVE bit (drag ticks, plain moves), only the bare `pointerMask` PDU is sent. The previous unconditional two-PDU emit produced byte-identical duplicates for drag/move ticks (e.g. `0x9800, 0x9800` while held), which xrdp reportedly misreads as two button-down edges per tick. The guard does NOT alter button-press sequences (`0x9800, 0x9000`), release/scroll sequences, the INV-011 release-previous-button guard, or coordinates. RDP-only; VNC/SPICE pointers are untouched. Residual risk: if xrdp's grab still churns when drag ticks carry `DOWN`, follow-up MOVE coalescing may be needed; device verification is pending.
- INV-028 (round 9) — The relaxed-slop double-tap detector is `DoubleTapPairTracker` (`:remoteClientLib/.../input/DoubleTapPairTracker.java`), fed from raw primary-pointer `ACTION_DOWN`/`ACTION_UP` in `TouchInputHandlerGeneric.onTouchEvent:716-749`. Effective thresholds are framework-floored: `slopPx = max(doubleTapSlopDp × density, viewConfiguration.getScaledDoubleTapSlop())` and `timeoutMs = max(doubleTapTimeoutMs, viewConfiguration.getDoubleTapTimeout())`; defaults therefore behave like stock, but the tracker adds detection for pairs stock rejected (so a real-finger double-tap drifting > ~8 dp still emits a double-click). The tracker replaces the round-3 buffered-`onSingleTapUp` detector, which was structurally unreachable (the stock detector pre-empted every pair that reached `onSingleTapUp`). `stockDoubleTapFired` is a per-dispatch flag set in `onDoubleTap` (stock path) and checked after the detector feed; when true, the manual hook is skipped. `suppressNextSingleTapConfirmed` is set in `onManualDoubleTap` and cleared at the start of the next primary `ACTION_DOWN` (not in `onSingleTapUp`, which would re-introduce the round-3 4-click bug). Reset on `ACTION_CANCEL`, `ACTION_POINTER_DOWN` (multi-touch transition), and from every subclass `onLongPress`. Touchpad handlers override `onManualDoubleTap` to arm the adaptive state machine instead of the base two-click default.

---

## 10. Open questions / known unknowns

- Whether the user wants scancode-based key input (RDP supports it; current code uses VK only). Would require new JNI bindings.
- Whether the planned refactor wants to unify the three modifier state variables (PAT-002) or keep them separate.
- Whether `KEYCODE_SEARCH` mapping in `MetaKeyDialog` should be replaced with a real checkable key or removed.
- Behavior when the user enables both an on-screen sticky and a hardware modifier simultaneously — currently both apply (INV-013 dedups the duplicate VK sends only).
- What should happen on disconnect for the IME state — currently no explicit clear.

---

## 11. Walkthrough — how a Ctrl+C reaches the wire

1. User holds Ctrl on a hardware keyboard.
2. Android delivers `KeyEvent(ACTION_DOWN, KEYCODE_CTRL_LEFT, META_CTRL_ON)`.
3. `RemoteCanvasActivity` → `canvas.setOnKeyListener` → `RemoteClientsInputListener.onKey`.
4. Listener dispatches to `RemoteConnection.onKeyDownEvent` (via the base implementing `KeyInputHandler`).
5. `RemoteRdpKeyboard.processLocalKeyEvent(CTRL_LEFT, evt, 0)` runs. `RemoteKeyboardState.detectHardwareMetaState` adds `CTRL_MASK` to `hardwareMetaState`.
6. `pointer.hardwareButtonsAsMouseEvents(...)` returns false (CTRL is not a mouse shortcut).
7. `metaState = onScreenMetaState | 0` (or whatever).
8. `rdpcomm.writeKeyEvent(CTRL_LEFT, metaState, true)` — stores meta, does not yet send.
9. `injectMetaState(...)`.
10. `RdpKeyboardMapper.processAndroidKeyEvent(evt, isRepeat=false)` — at `:292` maps `KEYCODE_SHIFT_LEFT`? **NO** — at `:347` (or relevant VK row for ctrl). For `KEYCODE_CTRL_LEFT` / `KEYCODE_CTRL_RIGHT` the lookup is at `keymapExt` `:373-441` (extended key constants file).
11. Calls `processVirtualKey(vkCode, true)` on the `KeyProcessingListener` (the `RdpCommunicator`).
12. `RdpCommunicator.processVirtualKey:239-251` runs on `inputExecutor`:
    - Calls `sendModifierKeys(true)` (`:222-234`) which walks `modifierMap` and sends VKs for any modifiers that need to be added. `shouldSendModifier` suppresses duplicates.
    - Calls `LibFreeRDP.sendKeyEvent(session.getInstance(), vkCode, true)`.
13. User releases Ctrl. Same flow with `down=false`. `sendModifierKeys(false)` releases any modifiers that should now be released.

---

End of `INPUT_PIPELINE.md`.
