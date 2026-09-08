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
| Soft cursor | Local `Bitmap` blit drawn on top of framebuffer | `RemoteCanvas.softCursorMove:754-770` |
| Repaint rate | ≤60 Hz ahead; ≤10 Hz when behind | `RemoteCanvas.reDraw:681-696`, `invalidateCanvasRunnable:153` |
| Layout | `canvas.xml` in `bVNC/src/main/res/layout/` | lines 1-147 |

### Why this matters for input work

- The View being `ImageView` means **touch coords must be transformed by the inverse of the active `Matrix`** to get framebuffer pixels. `RemoteCanvas.computeShiftFromFullToView` (`:488`) does part of this. Coordinates are scaled by density in `TouchInputHandlerGeneric` (see §3).
- Replacing this with a `Surface`/`TextureView`/`GLSurfaceView` would break every input handler. See INV-002.
- The `[canvas]+singleHandOpts+extraKeysToolbar+keyboardIconForAndroidTv+toolbar` stack is fixed in `canvas.xml`. See PAT-007.

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
| Single tap (confirmed) | `onSingleTapConfirmed:316-320` → `performLeftClick:322-329` | `pointer.leftButtonDown` → 50 ms sleep → `pointer.releaseButton` |
| Double tap | `onDoubleTap:334-348` | Two left-click cycles with 50 ms gaps |
| Long press | `onLongPress:353-369` | `pointer.leftButtonDown` (no release) → enter `dragMode`. Haptic via `touchInputDelegate.sendShortVibration`. |
| Pan in `dragMode` / `rightDragMode` / `middleDragMode` | `onTouchEvent` `ACTION_MOVE:498-502` | `pointer.moveMouseButtonDown` (button held, mouse moves) |
| Two-finger tap (2nd pointer up) | `onTouchEvent` `ACTION_POINTER_UP` for pointerId==1: `533-547` | `pointer.rightButtonDown` → `rightDragMode` |
| Three-finger tap (3rd pointer down) | `onTouchEvent` `ACTION_POINTER_DOWN` for pointerId==2: `551-561` | `pointer.middleButtonDown` → `middleDragMode` |
| Two-finger swipe | `onScale:572-647` → `sendScrollEvents:288-310` | `pointer.scrollUp/Down/Left/Right` |
| USB mouse / trackball | `handleMouseActions:174-283` | Maps `MotionEvent.BUTTON_PRIMARY/SECONDARY/TERTIARY`, `AXIS_VSCROLL`, `AXIS_HSCROLL` |
| Hardware volume key scroll | `RemotePointer.hardwareButtonsAsMouseEvents` `:153-156` → `scrollMouse:173-186` | Repeating `scrollUp/Down` via `MouseScroller` runnable, 100 ms |

### 2.2.1 Touchpad-specific gestures (RDP only)

`bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerTouchpad.java` extends the generic handler with three gestures that match the Microsoft RDP Android app. They are gated by `setRdp(boolean)` (`:77-101`); non-RDP touchpad sessions keep the legacy behaviour.

| Gesture | Where | Wire mapping |
|---|---|---|
| Cursor fling (release velocity → decaying tick) | `onFling:204-237` + inner `Flinger:510-578` | `pointer.moveMouse(...)` per tick. `FLING_TICK_MS=20`, `FLING_DAMP=0.86` per tick, `FLING_NOISE_PX_PER_S=200` floor → up to ~50 events/s. Velocity scaled by `cbrt(zoom) * sensitivity / density`. Cancels on new touch-down and returns false while the adaptive double-tap state machine is `PENDING` or `DRAGGING`. |
| Long-press = synthesized right click (full down + ~40 ms delayed up) | `onLongPress:268-300` | `pointer.rightButtonDown(x, y, meta)` immediately, then a `viewable.getHandler().postDelayed(... releaseButton, 40)` fires the matching up while the finger is still down. `rightDragMode` is **not** set, so no drag cursor follows; the parent UP branch's `releaseButton` is idempotent. Mutually exclusive with the adaptive double-tap: clears `rdpDoubleTapPending` / `rdpDoubleTapDragging` so the next double-tap is not armed by a phantom continuation. |
| **Adaptive** double-tap-and-hold = press-and-drag OR double-click | `onDoubleTap:315-327`, `onTouchEvent:338-381`, helpers `commitDoubleTapDrag:389-400` + `emitDoubleTapDoubleClick:406-413` + `cancelDoubleTapGesture:419-427` | The 2nd tap's `DOWN` arms `PENDING` and sends NOTHING to the server. `ACTION_MOVE` past half the touch slop (`rdpTouchSlop = getScaledTouchSlop()/2`) commits a left-button press-and-drag (`pointer.leftButtonDown` + `dragMode=true`); `ACTION_UP` without movement emits two `performTapClick` pairs (a true double-click); `ACTION_CANCEL` releases the held button. The legacy round-2 behaviour (immediate press-and-drag that maximized title bars via Windows DBLCLK on the 2nd DOWN) is replaced — Windows no longer sees a synthesized DBLCLK, only a press+drag. `onFling` is gated off while pending/dragging. |

`TouchInputHandlerTouchpad.setRdp(true)` is called from `RemoteCanvasActivity.setInputHandler` (`:1351`) and `setRdp(false)` from `onDestroy` (`:1376-1378`) so the gating reflects the live flavor. New RDP connections default to touchpad via `ConnectionBean.getDefaultInputMode` (`:183-193`).

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
| `sendPointerEvent` | `:107-134` | The wire-emit. Combines `metaState \| remoteInput.getKeyboard().getMetaState()` (modifier piggy-back), clears previous button when `!isMoving && prevPointerMask != pointerMask`, calls `canvas.invalidateMousePosition()`, writes `MOVE\|pointerMask` then `pointerMask`. |

**The wire sequence for a touch tap:**

```
leftButtonDown(x, y, m)                  [RemoteRdpPointer.java:43-46]
   pointerMask = LEFT | DOWN
   sendButtonDownOrMoveButtonDown (1st: sends fresh event)
        sendPointerEvent(...)
           protocomm.writePointerEvent(..., MOUSE_BUTTON_MOVE | LEFT | DOWN, false)
           protocomm.writePointerEvent(..., LEFT | DOWN, false)
[50 ms later, in TouchInputHandlerGeneric.performLeftClick :327]
releaseButton(x, y, m)                    [RemoteRdpPointer.java:100-105]
   pointerMask = MOVE
   sendPointerEvent(..., false)
        protocomm.writePointerEvent(..., MOVE | LEFT (still set because prevPointerMask != 0), false)
        protocomm.writePointerEvent(..., MOVE, false)
   prevPointerMask = 0
```

**The wire sequence for a drag (button held while panning):**

```
leftButtonDown(x0, y0, m)        -> sends DOWN at (x0, y0)
pan via ACTION_MOVE
moveMouseButtonDown(xi, yi, m)   -> sends MOVE|DOWN repeatedly
pan ends
moveMouseButtonUp(xn, yn, m)     -> sends MOVE
[no explicit releaseButton called for the drag end path —
 the path depends on which onTouchEvent ACTION_UP branch fires; see :498-502]
```

### 2.4 Touch-mode handlers (`TouchInputHandler*`)

| Class | Selects via `RemoteCanvasActivity.getInputHandlerById:1054-1081` | Behavior |
|---|---|---|
| `TouchInputHandlerGeneric` (abstract base) | default | Pan + tap + drag + scroll; described above |
| `TouchInputHandlerTouchpad` | `TOUCHPAD_MODE` | Single-finger drag = relative mouse move. Sets `pointer.setRelativeEvents(true)`. **Default for new RDP connections.** RDP-only gestures (fling / long-press=right-click / double-tap-hold=drag) gated by `setRdp(boolean)`; see §2.2.1. |
| `TouchInputHandlerDirectDragPan` | `itemInputDragPanZoomMouse` | Single-finger drag = absolute mouse move (pan + tap) |
| `TouchInputHandlerDirectSwipePan` | `itemInputTouchPanZoomMouse` | Two-finger pan; one-finger absolute move |
| `TouchInputHandlerSingleHanded` | `itemInputSingleHanded` | Single-handed overlay buttons (`singleHandOpts`); no direct canvas touch dispatch |

Selection is persisted in `Constants.selectedInputMethodTag` via `RemoteCanvasActivity.setInputMode:1186-1205`. For new RDP connections, `ConnectionBean.getDefaultInputMode:183-193` writes `TOUCHPAD_MODE` straight into the row; legacy rows (where `INPUTMODE` was never populated) take the Activity-side fallback at `RemoteCanvasActivity.setModes`.

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
   ├─ metaState = onScreenMetaState | metaState
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
| `KEYCODE_BACK` | `VK_ESCAPE` (`0x1B`) | `:343` |
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
| `ModifierRowView` | Horizontally-scrollable row of 8 keys in order: `[Win, Shift, Ctrl, Alt, Del, Esc, Tab, 123]`. See `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ModifierRowView.java:61-361`. |
| `RdpExtraGridPanel` | `ExtraKeysView`-based grid (ESC/F1-F6/DEL, TAB/F7-F12/BKSP, HOME/END/PGUP/PGDN/INS/LEFT/DOWN/RIGHT). Toggled by the `123` button. See `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RdpExtraGridPanel.java:43-106`. |
| `RdpModifierRowHandler` | Orchestrator. Inflates the layout into the activity's container, owns the row+grid, bridges modifier state, consumes one-shot modifiers on dispatch. See `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RdpModifierRowHandler.java:71-406`. |

**Three-state per modifier:** OFF / ON (one-shot, consumed by next dispatched key) / LOCKED (survives consumption, tap to release). The 800 ms double-tap window is the same one used by `RdpKeyboardMapper.checkToggleModifierLock` (`ModifierRowView.DOUBLE_TAP_WINDOW_MS`).

**State machine for the input-area itself:** `InputAreaState` enum (`bVNC/src/main/java/com/iiordanov/bVNC/input/InputAreaState.java`) with three values:

| State | Visible surface |
|---|---|
| `NONE` | No input area. Container hidden. |
| `KEYBOARD` | Software IME + modifier row above it. |
| `EXTRA` | Extra-keys grid + modifier row above it (IME hidden). |

Transitions are driven by `RemoteCanvasActivity.setInputAreaState:1612-1628` and gated by `Utils.isRdp(this)` — non-RDP flavors never enter the state machine.

**Viewport ownership (round 3).** `recomputeRdpViewport` (`RemoteCanvasActivity.java:1641-1672`) is the single source of truth for `canvas.setVisibleDesktopHeight` and `rdpInputAreaContainer.setTranslationY` on RDP. It is called from three places:

1. The RDP-only `ViewCompat.setOnApplyWindowInsetsListener` on `canvasLayout` (`RemoteCanvasActivity.onCreate:315-339`) — reads `WindowInsetsCompat.Type.ime().bottom`, applies a bogus-IME guard (`ime > imeMaxHeight/2 → ime=0`, where `imeMaxHeight` is `canvas.getRdpFullViewHeight() > 0 ? rdpFullViewHeight : canvasLayout.getHeight() + lastImeHeightPx`), drives `InputAreaState` (`KEYBOARD ↔ NONE` only), and calls `recomputeRdpViewport`. Returns insets unchanged so children still dispatch.
2. The end of `relayoutViews` (RDP-gated at `RemoteCanvasActivity.java:609-615`) — re-applies the viewport math on rotation, resize, and IME-driven layout passes.
3. The end of `setInputAreaState` (RDP-gated at `:1625-1627`) — re-applies after the container's `VISIBLE ↔ GONE` flip.

**Full-height capture (`rdpFullViewHeight`).** Captured inside `recomputeRdpViewport` only — never elsewhere. When `lastImeHeightPx == 0` (IME closed), `canvas.setRdpFullViewHeight(canvas.getHeight())`; when the IME is already open on the first call, `setRdpFullViewHeight(canvas.getHeight() + lastImeHeightPx)` so the floor matches the pre-IME physical height. The field is package-scope (`RemoteCanvas.java:109` init, `:876-882` accessors) and is consumed by `ZoomScaling.computeMinimumScale:223-235` for the cover floor (see PAT-014 / INV-017).

**Container translation above the IME.** `imeOverlap = max(0, lastImeHeightPx − (rdpFullViewHeight − canvasH))` is the single formula for both window models: if the window did not resize (`canvasH ≈ rdpFullViewHeight`), `imeOverlap ≈ ime` and the container is lifted; if the window did resize (`canvasH ≈ rdpFullViewHeight − ime`), `imeOverlap ≈ 0` and the bottom-gravity container stays put. Replaces the round-1/2 `rdpContainerTranslation` heuristic, which depended on `setSoftInputMode(SOFT_INPUT_ADJUST_RESIZE)` to fire `getWindowVisibleDisplayFrame().bottom`.

**Viewport / cursor diagnostics.** BUG-002 (cursor travels under the soft keyboard) added a dedicated `RdpViewport` log tag at every site that participates in this state machine: the IME insets listener, `recomputeRdpViewport`, `setInputAreaState`, the legacy 19% `relayoutViews` heuristic, `setVisibleDesktopHeight` / `setRdpFullViewHeight` setters, and the `movePanToMakePointerVisible` / `itemCenterMouse` / `setNewPointerPosition` paths. See §12 and `known_issues/BUG-002-cursor-under-soft-keyboard.md`.

**IME-up collapses `KEYBOARD → NONE` but leaves `EXTRA` alone** (so the grid survives the IME hiding); the legacy 3-page pager is suppressed for RDP. The 19% `r.bottom` heuristic in `relayoutViews:550-599` is the fallback for non-RDP flavors and for the RDP container-visibility transitions; the legacy `setVisibleDesktopHeight + relativePan` shrink block at `relayoutViews:514-517` is RDP-gated out (`if (!Utils.isRdp(this))`). Back-press when in `KEYBOARD` or `EXTRA` collapses to `NONE` instead of finishing the Activity (`onBackPressed:1734-1752`).

**Bridging to `onScreenMetaState` (INV-010):** `RdpModifierRowHandler.syncRowStateToKeyboard:308-317` calls `keyboard.clearMetaState()` then `keyboard.onScreen{Ctrl,Alt,Shift,Super}Toggle()` for every modifier where `rowView.isOnOrLocked(...)` is true — the canonical INV-010 pattern, used by both bridges.

**One-shot consumption hook:** `RemoteRdpKeyboard.KeyDispatchedListener` (`:29-38, 118-135`) fires after every non-modifier `ACTION_DOWN`/`ACTION_MULTIPLE` dispatch. `RdpModifierRowHandler` registers itself in `attachKeyboardIfAvailable:274-286` and its `onKeyDispatched:351-355` calls `rowView.consumeOnModifiers()`, which clears ON (non-locked) modifiers and re-bridges the remaining LOCKED ones. Pointers do **not** consume modifiers.

**Reset on disconnect (INV-008, RDP-side fix):** `RdpModifierRowHandler.resetRowState:228-234` clears every row modifier AND calls `keyboard.clearMetaState()`. `RemoteCanvasActivity.hideKeyboardAndExtraKeys` wires it into the disconnect path. The legacy `clearMetaState()` call in `RemoteConnection.closeConnection` (the only pre-existing reset) is retained.

**Floating keyboard toggle button (RDP-only):** `keyboardToggleButton` (`@+id/keyboardToggleButton`, last child of `canvasLayout` for top z-order) starts `gone`, shown only when `Utils.isRdp(this)`. Drag-vs-tap is distinguished by an inline `OnTouchListener` that moves the View on `ACTION_MOVE` once finger motion exceeds `getScaledTouchSlop()` and dispatches `performClick()` on `ACTION_UP` otherwise (`RemoteCanvasActivity:1048-1103`). Position is session-only (no SharedPreferences). The button cycles `NONE → KEYBOARD → EXTRA → NONE` via `onKeyboardToggleButtonClicked:1592-1610`.

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
| `touchpadSensitivity` | Slider → `(slider+1) * 0.6f` (round 3; was `0.4f`, too slow on 560dpi devices) | `4` | `global_preferences.xml:49`, `Constants.touchpadSensitivity`, `RemoteCanvasActivity.getTouchpadSensitivityMultiplier:1282-1286` |
| `softwareKeyboardType` | ListPreference | `TYPE_NULL` | `global_preferences.xml:40` |
| `preferSendingUnicode` | Per-connection bool | `true` | `Constants.preferSendingUnicode`, `ConnectionBean.PREFERSENDINGUNICODE` |
| `useDpadAsPointer` | Per-connection | varies | `Constants.useDpadAsArrows` |
| `rotateDpad` | Per-connection bool | false | `Constants.rotateDpad` |
| `layoutMap` | Per-connection keyboard layout | `English (US)` | `ConnectionBean.LAYOUTMAP` |
| `rAltAsIsoL3Shift` | Treat right-Alt as ISO L3 Shift (VNC overlay) | `true` | `global_preferences_vnc.xml:5` |
| `defaultInputMethod` | Per-flavor input-mode default. **For RDP new connections**, `ConnectionBean.getDefaultInputMode:183-193` returns `TOUCHPAD_MODE` (touchpad with RDP-only gestures) instead of the SharedPreferences default. Stored `INPUTMODE` values are not migrated. | RDP touchpad, otherwise swipe-pan | `Constants.defaultInputMethodTag` |

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
7. `bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerTouchpad.java` — `setRdp(boolean):77-101`; fling (`Flinger` inner class, `FLING_TICK_MS=20`, `FLING_DAMP=0.86`, `FLING_NOISE_PX_PER_S=200`), `onFling:204-237` (returns false while adaptive-double-tap is `PENDING` or `DRAGGING`), `onLongPress:268-300` synthesized right click (down + 40 ms delayed up) and clears the adaptive-double-tap state for mutual exclusion, `onDoubleTap:315-327` arms `PENDING` only (no click), `onTouchEvent:338-381` + helpers `commitDoubleTapDrag:389-400` / `emitDoubleTapDoubleClick:406-413` / `cancelDoubleTapGesture:419-427` implement the round-3 adaptive double-tap state machine.
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
17. `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java` — `setInputAreaState:1612-1628`, `updateRdpInputAreaVisibility:1681-1689`, `onKeyboardToggleButtonClicked:1699-1712`, `setInputHandler:1350-1356` (RDP handler attach), `keyboardToggleButton` inline drag-vs-tap listener `:1048-1103`, RDP-only IME insets listener on `canvasLayout` at `:315-339`, `recomputeRdpViewport:1641-1672` (single RDP viewport owner — round 3), `getTouchpadSensitivityMultiplier:1282-1286` (round 3 coefficient `0.6f`), RDP-gated legacy shrink block at `relayoutViews:514-517`, end-of-relayout RDP recompute at `:609-615`, `onBackPressed:1734-1752`, `setModes` touchpad fallback at `:866-867`.
18. `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java:823-832`, `:738-744`, `:733-735`, **`rdpFullViewHeight` field `:109`, accessors `:876-882` (round 3)**
19. `bVNC/src/main/java/com/iiordanov/bVNC/dialogs/MetaKeyDialog.java:251-254`, `:444-492`, `:494-514`, `:637-658`
20. `bVNC/src/main/java/com/iiordanov/bVNC/ZoomScaling.java:223-235` — RDP-only `computeMinimumScale` cover-scale clamp; round 3 reads `canvas.rdpFullViewHeight` instead of the shrunk `visibleHeight`.
21. `bVNC/src/main/java/com/iiordanov/bVNC/ConnectionBean.java:183-193` — `getDefaultInputMode` writes `TOUCHPAD_MODE` for new RDP connections.

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
- INV-016 — `RemoteRdpKeyboard.KeyDispatchedListener` fires only on `ACTION_DOWN` / `ACTION_MULTIPLE`, only for non-modifier keycodes, only on the successful dispatch path. Listeners must NOT consume LOCKED modifiers and must NOT release the modifier bits used by `lastDownMetaState` for the ACTION_UP replay.
- INV-017 — `ZoomScaling.computeMinimumScale` for RDP returns a cover scale (`max(viewW/fbW, viewH/fbH)`) so no black borders appear at minimum zoom; non-RDP returns `canvas.getMinimumScale()` unchanged. The floor uses `canvas.rdpFullViewHeight` (the PHYSICAL full-screen height captured when the IME is closed) — never the shrunk `visibleHeight`. The self-referential `visibleHeight` floor was the round-2 bug that disabled `movePanToMakePointerVisible`'s vertical pan when the window did not resize. The floor is recomputed every call so it tracks the live `rdpFullViewHeight`.
- INV-018 — `InputAreaState` (RDP-only) drives the entire `@+id/rdpInputAreaContainer` lifecycle. `setInputAreaState` is the only mutator; visibility is reapplied after the state is set. Anything that hides the IME on RDP must not collapse `EXTRA → NONE`. Round 3: the `KEYBOARD ↔ NONE` transition is also driven by the IME insets listener (`onCreate:315-339`) on top of the legacy `relayoutViews` 19% heuristic — non-RDP flavors do not enter the listener.
- INV-019 — All RDP-only UX elements (`ModifierRowView`, `RdpExtraGridPanel`, `RdpModifierRowHandler`, `keyboardToggleButton`, `rdpInputAreaContainer`, `recomputeRdpViewport`, the IME insets listener, RDP touchpad gestures) are gated by `Utils.isRdp(this)`. Non-RDP flavors (bVNC/aSPICE/Opaque) must remain byte-identical to the pre-RDP UX.
- INV-020 (round 3) — `recomputeRdpViewport` is the **single source of truth** for `canvas.setVisibleDesktopHeight` and `rdpInputAreaContainer.setTranslationY` on RDP. The legacy `relayoutViews` shrink block (`setVisibleDesktopHeight + relativePan`) is RDP-gated out (`:514-517`). `rdpFullViewHeight` is only ever set to the physical full-screen height (`canvas.getHeight()` when IME closed, or `canvas.getHeight() + lastImeHeightPx` if the IME was already open on first capture) — never to the shrunk viewport.

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

## 12. Viewport / cursor diagnostics (BUG-002)

When the cursor appears to travel under the soft keyboard on aRDP (i.e. `movePanToMakePointerVisible` does not pan the canvas to keep the cursor in view), reproduce the issue with
`adb logcat -c && adb logcat -s RdpViewport:V` open in a second terminal. The
dedicated tag is emitted by:

- `RemoteCanvasActivity` — IME insets listener (`imeInsets: rawIme=… guardMax=…`),
  `recomputeRdpViewport` (consolidated `canvasW / canvasH / rdpFullH / imeH /
  containerH / usableH / visibleDesktopH / inputArea / softKbdUp / ptrX / ptrY`),
  `setInputAreaState` (`inputAreaState: NONE -> KEYBOARD (imeH=… softKbdUp=…)`),
  the legacy 19% `relayoutViews` heuristic (`relayoutViews.softKbd: true ->
  false` / `false -> true`), and the `itemCenterMouse` handler
  (`itemCenterMouse: before` + `itemCenterMouse: after landsInVisibleY=…`).
- `RemoteCanvas` — `setVisibleDesktopHeight` / `setRdpFullViewHeight` (on change)
  and `movePanToMakePointerVisible` (full `panX/panY/panned/newX/newY/visH/
  cursorInVisibleX/cursorInVisibleY` snapshot).
- `RemotePointer` — `setY` and `setNewPointerPosition` (throttled to ≥8 framebuffer-
  pixel delta) with `inVisX` / `inVisY` flags relative to the canvas visible area,
  plus `movePointer` and `movePointerToMakeVisible` for the explicit-centering paths.

Per-field-change dedup in each emitter keeps the stream readable during
reproduction. The full bug report and the suspected root-cause list live at
`known_issues/BUG-002-cursor-under-soft-keyboard.md`.

---

End of `INPUT_PIPELINE.md`.
