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
| `TouchInputHandlerTouchpad` | `TOUCHPAD_MODE` | Single-finger drag = relative mouse move. Sets `pointer.setRelativeEvents(true)`. |
| `TouchInputHandlerDirectDragPan` | `itemInputDragPanZoomMouse` | Single-finger drag = absolute mouse move (pan + tap) |
| `TouchInputHandlerDirectSwipePan` | `itemInputTouchPanZoomMouse` | Two-finger pan; one-finger absolute move |
| `TouchInputHandlerSingleHanded` | `itemInputSingleHanded` | Single-handed overlay buttons (`singleHandOpts`); no direct canvas touch dispatch |

Selection is persisted in `Constants.selectedInputMethodTag` via `RemoteCanvasActivity.setInputMode:1186-1205`.

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

## 4. Modifier keys — the focus area for upcoming work

### 4.1 Mask constants

`remoteClientLib/src/main/java/com/undatech/opaque/input/RemoteKeyboard.java:33-63`:

```text
CTRL_MASK   = KeyEvent.META_CTRL_LEFT_ON        // :54
SHIFT_MASK  = KeyEvent.META_SHIFT_LEFT_ON       // :57
ALT_MASK    = KeyEvent.META_ALT_LEFT_ON         // :58
SUPER_MASK  = KeyEvent.META_META_LEFT_ON        // :59
RCTRL_MASK  = KeyEvent.META_CTRL_RIGHT_ON       // :60
RSHIFT_MASK = KeyEvent.META_SHIFT_RIGHT_ON      // :61
RALT_MASK   = KeyEvent.META_ALT_RIGHT_ON        // :62
RSUPER_MASK = KeyEvent.META_META_RIGHT_ON       // :63
```

PC scancodes `SCAN_LEFTCTRL=29, SCAN_RIGHTCTRL=97, SCAN_LEFTALT=56, SCAN_RIGHTALT=100, SCAN_LEFTSHIFT=42, SCAN_RIGHTSHIFT=54, SCAN_LEFTSUPER=125, SCAN_RIGHTSUPER=126` are also defined at `RemoteKeyboard.java:33-52`.

### 4.2 Three independent modifier state variables (PAT-002)

| Variable | Class | Set by | Reset by |
|---|---|---|---|
| `hardwareMetaState` | `RemoteKeyboardState` on `RdpCommunicator.remoteKeyboardState` | `detectHardwareMetaState(KeyEvent)` per-key | Never explicitly except for the per-key delta read path |
| `onScreenMetaState` | `RemoteRdpKeyboard` (per-flavor keyboard) | `onScreen{Ctrl,Alt,Shift,Super}Toggle/Off` (`:114-199`) | `clearMetaState()` (`:213-215`) — called from `ExtraKeysPagerAdapter.syncKeyboardModifierState` only |
| `remoteKeyboardMetaState` | `RemoteKeyboardState` | `updateRemoteMetaState(...)` (`:216-222`) | `RdpCommunicator.close` (only resets session, not state) |

### 4.3 On-screen sticky modifier UI

`bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ExtraKeysView.java` — the middle page of the bottom pager in `canvas.xml`. State in `SpecialButton` (`bVNC/.../extrakeys/SpecialButton.java:31-35`) and `SpecialButtonState.java`.

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

- `ExtraKeysPagerAdapter.syncKeyboardModifierState(view)` (`:147-155`):
  1. `keyboard.clearMetaState()` — `onScreenMetaState = 0`.
  2. For each active CTRL/ALT/SHIFT/SUPER, call `keyboard.onScreen{Ctrl,Alt,Shift,Super}Toggle()`.

### 4.4 Double-tap-to-lock

`RdpKeyboardMapper.processToggleButton(int keycode)` (`:622-662`) — handles the on-screen CTRL/ALT/SHIFT/WIN toggles. Double-tap within **800 ms** = lock the modifier ON (`checkToggleModifierLock:712-730`):

```text
if (lastModifierKeyCode != keycode) { lastModifierKeyCode = keycode; lastModifierTime = now; return false; }
if (lastModifierTime + 800 > now)  { lastModifierTime = 0;    return true; }   // locked
else                               { lastModifierTime = now;  return false; }
```

Locked modifiers are preserved by `resetModifierKeysAfterInput` (`:668-688`); only `(!isLocked || force)` paths release them. `clearlAllModifiers()` (`:664-666`) passes `force=true` — this is the canonical reset path that is currently **unused** (INV-009).

### 4.5 `convertEventMetaState` — `KeyEvent.META_*` → internal masks

`RemoteKeyboard.convertEventMetaState(KeyEvent event, int eventMetaState)` (`:285-371`):

- Maps `KeyEvent.META_*_ON` flags to `CTRL/SHIFT/ALT/SUPER_MASK` (and `R*_MASK` for right side).
- **`META_ALT_LEFT_ON` is ignored** for events from a real hardware keyboard or when `isUnicode` is true (`:301-314`). This is so that the **AltGr** symbol entry on European keyboards still produces characters. Any refactor must keep this exception.
- Right-side modifier bits clear their corresponding left-side mask (`:328, 341, 354, 367`).

### 4.6 `shouldDropModifierKeys`

`RemoteKeyboard.shouldDropModifierKeys(KeyEvent)` (`:391-416`) drops `KEYCODE_*_LEFT/RIGHT` modifier events when `isNoQwertyKbd(context)` — i.e., when the event originates from a virtual/touchscreen keyboard and would generate noise on the remote.

### 4.7 `Ctrl+Alt+Del` emulation — special-cased

`RemoteRdpKeyboard.sendMetaKey(MetaKeyBean meta)` (`:91-135`) — branches on `meta.getKeySym()`. For `MetaKeyBean.keyCtrlAltDel` (`:124-131`):

```text
int savedMetaState = onScreenMetaState;
rfb.writeKeyEvent(0, CTRL_MASK | ALT_MASK, false);                                       // stores meta
keyboardMapper.processAndroidKeyEvent(new KeyEvent(ACTION_DOWN, KEYCODE_FORWARD_DEL), false);  // 112 = KEYCODE_FORWARD_DEL
keyboardMapper.processAndroidKeyEvent(new KeyEvent(ACTION_UP,   KEYCODE_FORWARD_DEL), false);
rfb.writeKeyEvent(0, savedMetaState, false);
```

The TODO comment at `:125` ("I should not need to treat this specially anymore") hints the special casing is on a hit list. The chain is selected from menu `R.id.itemCtrlAltDel` (`RemoteCanvasActivity.java:1148-1150`) and from `MetaKeyDialog.sendCurrentKey` (`MetaKeyDialog.java:494-514`).

### 4.8 `MetaKeyDialog`

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
| `touchpadSensitivity` | Slider → `(slider+1) * 0.4f` | `4` | `global_preferences.xml:49`, `Constants.touchpadSensitivity` |
| `softwareKeyboardType` | ListPreference | `TYPE_NULL` | `global_preferences.xml:40` |
| `preferSendingUnicode` | Per-connection bool | `true` | `Constants.preferSendingUnicode`, `ConnectionBean.PREFERSENDINGUNICODE` |
| `useDpadAsPointer` | Per-connection | varies | `Constants.useDpadAsArrows` |
| `rotateDpad` | Per-connection bool | false | `Constants.rotateDpad` |
| `layoutMap` | Per-connection keyboard layout | `English (US)` | `ConnectionBean.LAYOUTMAP` |
| `rAltAsIsoL3Shift` | Treat right-Alt as ISO L3 Shift (VNC overlay) | `true` | `global_preferences_vnc.xml:5` |

`Constants.scrollSpeed` → `(slider+1)/7f` is consumed in:
- `RemoteCanvasActivity.getScrollRate():1106-1109` → `scrollRate` passed into each `TouchInputHandler` constructor
- Inside `TouchInputHandlerGeneric`:
  - `maxSwipeSpeed = round(7 * scrollRate)` (`:120`)
  - `baseSwipeDist = 10dp / scrollRate` (`:137`)

`Constants.touchpadSensitivity` → `(slider+1) * 0.4f` is consumed in:
- `RemoteCanvasActivity.getTouchpadSensitivityMultiplier():1111-1114` → `pointer.setSensitivity(...)` (`:1061`)

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
   - `releaseButton:100-105`
   - `scrollLeft/scrollRight:73-80` — currently no-op; you may want to add horizontal scroll via `LibFreeRDP.sendCursorEvent` with horizontal wheel flags.
2. `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpKeyboard.java`
   - `processLocalKeyEvent:36-89`
   - `sendMetaKey:91-119` (Ctrl+Alt+Del special case — file ends at line 120)
3. `remoteClientLib/src/main/java/com/undatech/opaque/input/RdpKeyboardMapper.java`
   - `keymapAndroid[]` initialization at `:241-444`
   - `processAndroidKeyEvent:458-534`
   - `processToggleButton:622-662`, `resetModifierKeysAfterInput:668-688`, `clearlAllModifiers:664-666`, `checkToggleModifierLock:712-730`
4. `remoteClientLib/src/main/java/com/undatech/opaque/input/RemoteKeyboardState.java`
   - `detectHardwareMetaState:36-192`
   - `shouldSendModifier:194-214`
   - `updateRemoteMetaState:216-222`
5. `remoteClientLib/src/main/java/com/undatech/opaque/input/RemoteKeyboard.java`
   - `convertEventMetaState:285-371`
   - `shouldDropModifierKeys:391-416`
   - `onScreen{Ctrl,Alt,Shift,Super}{Toggle,Off}:114-199`
   - `clearMetaState:213-215`, `getMetaState:201-203`, `lastDownMetaState:80`
6. `remoteClientLib/src/main/java/com/undatech/opaque/RdpCommunicator.java`
   - `writePointerEvent:156-167`
   - `writeKeyEvent:179-187`
   - `sendModifierKeys:222-234`
   - `processVirtualKey:239-251`
   - `processUnicodeKey:263-274`
   - `inputExecutor:62-69`, `sleepBetweenInputEvents:289-294`
7. `bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerGeneric.java`
   - `handleMouseActions:174-283`
   - `sendScrollEvents:288-310`
   - `onSingleTapConfirmed/onDoubleTap/onLongPress:316-369`
   - `dragMode` / `rightDragMode` / `middleDragMode` flags at `:58-67`
8. `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RemoteExtraKeysHandler.java:60-87`
9. `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ExtraKeysPagerAdapter.java:147-155`
10. `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteClientsInputListener.kt:52-152`
11. `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java:1207-1218`, `:745-756`, `:1148-1150`, `:1117-1179`
12. `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java:823-832`, `:738-744`, `:733-735`
13. `bVNC/src/main/java/com/iiordanov/bVNC/dialogs/MetaKeyDialog.java:251-254`, `:444-492`, `:494-514`, `:637-658`

---

## 9. Invariants the input rework must NOT break

(Recap from `DESIGN_PRINCIPLES.md` §INV; reproduced here because the input work has the highest blast radius.)

- INV-001 — every input event goes through `RdpCommunicator.inputExecutor`. Do not call `LibFreeRDP.sendKeyEvent` etc. from elsewhere.
- INV-003 — `UltraCompactBitmapData.updateBitmap` is `synchronized (mbitmap)`. Don't touch that lock.
- INV-006 — frame repaint throttle; bypass it and native bitmap-copy contention appears.
- INV-008 — modifier state leak on disconnect/reconnect. Fix must call `clearMetaState()` and reset both `hardwareMetaState` and `remoteKeyboardMetaState` (or unify them). `clearlAllModifiers()` exists as the reset entry point.
- INV-010 — sticky-modifier ↔ keyboard mirroring only via `ExtraKeysPagerAdapter.syncKeyboardModifierState`.
- INV-011 — `POINTER_DOWN_MASK = 0x8000` and the "release previous before new down" guard in `sendPointerEvent`. Removing this causes the remote OS to see two buttons held simultaneously.
- INV-013 — `RemoteKeyboardState.shouldSendModifier` deduplicates modifier sends.
- INV-014 — IME text pipe via `BaseInputConnection`.

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
