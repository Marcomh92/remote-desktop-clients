# MASTER.md — Documentation Index

> **Repo:** forked `remote-desktop-clients` (iiordanov). Re-targeted: this fork focuses on the **aRDP** Android client (RDP-only). VNC, SPICE, and Opaque flavors live alongside but are out of immediate scope.
>
> **Read AGENTS.md first.** It records the build toolchain, module layout, MCP setup, and conventions that the docs here assume.

---

## 1. What this fork builds

Four products, eight APKs (paid + free per product) — all share one library and one codebase:

| Product | Paid APK (free counterpart) | Protocol |
|---|---|---|
| **bVNC** | `bVNC-app` (`freebVNC-app`) | VNC / RFB |
| **aRDP** | `aRDP-app` (`freeaRDP-app`) | RDP (FreeRDP) |
| **aSPICE** | `aSPICE-app` (`freeaSPICE-app`) | SPICE |
| **Opaque** | `Opaque-app` (no free) | oVirt / RHEV / Proxmox (oVirt REST + SPICE) |

Plus a Custom VNC viewer (`CustomVnc-app`) parameterized via `gradle.properties`.

`aRDP-app` is a **manifest-only wrapper**. Every Activity, Fragment, layout, and JNI shim used by aRDP lives in `:bVNC` or `:remoteClientLib`.

---

## 2. Documentation layout

```
docs/
├── MASTER.md                   this file (entry point)
├── ARCHITECTURE.md             module map + layer model
├── DESIGN_PRINCIPLES.md       cross-cutting rules and "facts" you must respect
├── PATTERNS.md                 recurring code idioms (input pipeline, Protocol-per-flavor, etc.)
├── TESTING.md                  where tests live, where they don't, how to add
├── PERFORMANCE.md              input throttle / frame throttle / GC notes
├── features/                   one file per subsystem
│   ├── INPUT_PIPELINE.md       mouse + keyboard + modifier-key flow (RDP focus)
│   ├── CONNECTION_LIFECYCLE.md session start, framebuffer, disconnect, multi-instance
│   ├── NATIVE_BRIDGE.md        JVM ↔ FreeRDP / SPICE JNI surface
│   └── UI_SHELL.md             activities, settings, DB, layouts
└── DECISIONS/                  ADRs (architecture decision records)
    └── ADR-0001-multi-module-flavor-strategy.md
```

### Read order

1. This file
2. `ARCHITECTURE.md` — where each thing lives
3. `DESIGN_PRINCIPLES.md` — what not to violate
4. `features/INPUT_PIPELINE.md` — **the main target of upcoming work**
5. Other feature docs as needed

---

## 3. Glossary

| Term | Meaning |
|---|---|
| **Wrapper** | An `android-application` module whose `src/main/` contains only `AndroidManifest.xml` (and maybe a wrapper `res/`). Holds no logic. |
| **Flavor** | The runtime *protocol family* selected by package name: VNC, RDP, SPICE, Opaque. Flavor detection happens via `Utils.isVnc/isRdp/isSpice/isOpaque` on the running `App` context. |
| **`App`** | The single `MultiDexApplication` subclass in `:bVNC` (`bVNC/src/main/java/com/iiordanov/bVNC/App.java`). All eight wrapper manifests declare it. |
| **`RemoteCanvasActivity`** | The Activity that hosts the desktop view for **all four** flavors. Per-flavor behavior is selected at runtime by `Utils.isRdp(...)` checks. |
| **`RemoteCanvas`** | The custom `View` that paints frames. Despite the name, it is an `AppCompatImageView`, not a `SurfaceView`. Framebuffer is a single Bitmap set via `setImageDrawable`. |
| **`Viewable`** | Interface in `:remoteClientLib` that `RemoteCanvas` implements. The contract the protocol layer uses to talk to the View (frame size, pan offset, soft cursor, mouse mode). |
| **`RemotePointer` / `RemoteKeyboard`** | Abstract base classes in `:remoteClientLib`. Subclasses per protocol (`RemoteRdpPointer`, `RemoteRdpKeyboard`, etc.) live in `:bVNC`. |
| **`RdpCommunicator`** | The peer of the FreeRDP native side. Implements `RfbConnectable`; receives `OnGraphicsUpdate`/`OnSettingsChanged` static callbacks from `LibFreeRDP` static methods; runs a single-threaded `inputExecutor` that serializes every keyboard/mouse send. |
| **`LibFreeRDP`** | Vendored module under `remoteClientLib/jni/libs/deps/FreeRDP/client/Android/Studio/freeRDPCore`. Loads `libfreerdp-android.so`. Only the JNI surface is ours; the C side is upstream. |
| **`inputExecutor`** | Single-thread high-priority executor on every `RdpCommunicator`. Names the thread `SendRdpInputThread`. All keyboard/mouse output is funneled through it. |
| **`BaseInputConnection`** | Returned by `RemoteCanvas.onCreateInputConnection`. The single pipe for IME-committed text into the RDP keyboard pipeline. |
| **Soft modifier** | Sticky on-screen CTRL/ALT/SHIFT/SUPER buttons. Two implementations: the legacy 3-page pager (`ExtraKeysView`, VNC/SPICE/Opaque) and the RDP-only `ModifierRowView` (8-key horizontally-scrollable row above the IME). State mirrored into `RemoteRdpKeyboard.onScreenMetaState` via the INV-010 bridge. |
| **Hardware modifier** | Modifier state inferred from real hardware key events in `RemoteKeyboardState.detectHardwareMetaState`. Used to deduplicate modifier VK sends. |
| **`InputAreaState`** | RDP-only enum (`NONE` / `KEYBOARD` / `EXTRA`) owned by `RemoteCanvasActivity`. Drives the `@+id/rdpInputAreaContainer` lifecycle and the IME ↔ "123" grid transition. See `features/INPUT_PIPELINE.md` §4.3 and PAT-015. |
| **`ModifierRowView`** | RDP-only horizontally-scrollable row of 8 keys `[Win, Shift, Ctrl, Alt, Del, Esc, Tab, 123]`. Three-state per modifier (OFF / ON one-shot / LOCKED). 800 ms double-tap = LOCKED. Session-only scroll position. |
| **`RdpModifierRowHandler`** | RDP-only orchestrator that inflates `rdp_input_area.xml` into `rdpInputAreaContainer`, owns the row + grid, implements the INV-010 bridge, and registers a `RemoteRdpKeyboard.KeyDispatchedListener` for one-shot consumption. |
| **`KeyDispatchedListener`** | `RemoteRdpKeyboard.KeyDispatchedListener` — fires after every successful non-modifier `ACTION_DOWN` / `ACTION_MULTIPLE` dispatch. Used by `RdpModifierRowHandler` for one-shot consumption. See INV-016. |
| **Cover scale** | RDP-only zoom minimum: `max(viewW/fbW, viewH/fbH)` (with `viewH = canvas.rdpFullViewHeight>0 ? rdpFullViewHeight : canvas.getHeight()`). The floor uses the PHYSICAL full-screen height captured when the IME is closed — never the shrunk `visibleHeight` — so the floor overhangs the IME and `movePanToMakePointerVisible`'s pan gate (`fbHeight < getVisibleDesktopHeight()`) stays false. See PAT-014 / INV-017. |
| **`rdpFullViewHeight`** | RDP-only `RemoteCanvas` field (init `-1`, package-scope `getRdpFullViewHeight` / `setRdpFullViewHeight`). The physical (un-shrunk) canvas height captured by `RemoteCanvasActivity.recomputeRdpViewport` when the IME is closed. Used by `ZoomScaling.computeMinimumScale` to compute the cover floor. Never set to the shrunk viewport. See PAT-014 / INV-017. |
| **RDP IME insets listener** | RDP-only `ViewCompat.setOnApplyWindowInsetsListener` on `canvasLayout` (`RemoteCanvasActivity.java:315-338`). Reads `WindowInsetsCompat.Type.ime().bottom`, applies a bogus-IME guard (`ime > imeMaxHeight/2 → ime=0`), drives `InputAreaState` transitions (`KEYBOARD ↔ NONE` only), and calls `recomputeRdpViewport`. Returns insets unchanged so children still dispatch. Idempotent under both window-resize and non-resize IME models. |
| **`recomputeRdpViewport`** | RDP-only viewport owner (`RemoteCanvasActivity.java:1677-1708`). Single source of truth for `canvas.setVisibleDesktopHeight` and `rdpInputAreaContainer.setTranslationY` on RDP. Captures `rdpFullViewHeight` once when the IME is closed, computes usable viewport as `full − ime − container`, and positions the container above the IME via `imeOverlap = max(0, ime − (full − canvasH))` (one formula covers both window models). Called from the insets listener, the end of `relayoutViews` (RDP-gated), and `setInputAreaState` (RDP-gated). The legacy `relayoutViews` shrink block (`setVisibleDesktopHeight + relativePan`) is RDP-gated out. |
| **Adaptive double-tap (RDP touchpad)** | RDP-only `TouchInputHandlerTouchpad` state machine: `onDoubleTap` arms a `PENDING` state and sends NOTHING; `onTouchEvent` `ACTION_MOVE` past half the touch slop commits a left-button press-and-drag (no preceding click); `ACTION_UP` without movement emits two `performTapClick` pairs (a true double-click). `onFling` returns false while `PENDING` or `DRAGGING`; `ACTION_CANCEL` releases the held button; `onLongPress` clears `PENDING`/`DRAGGING` (mutual exclusion with the synthesized right click). Replaces the round-2 "click-then-drag" behaviour that maximized title bars via Windows DBLCLK on the 2nd DOWN. |
| **StrictMode** | `RemoteCanvasActivity.onCreate` calls `StrictMode.ThreadPolicy.permitAll()` because FreeRDP callbacks fire on native threads and would otherwise trip the bitmap-write detection. |
| **Strictly vendored** | `:remoteClientLib:jni:libs:deps:FreeRDP:client:Android:Studio:freeRDPCore` is in-tree (not a Git submodule). Do not edit; pull upstream and patch. |

---

## 4. Recent work (aRDP session-UX parity with the Microsoft RDP app)

The aRDP flavor now ships the Microsoft RDP Android app's session UX: a software keyboard with a floating toggle, a modifier row above the IME with one-shot / lock semantics, a "123" extra-keys page replacing the IME, no black borders at minimum zoom (cover-scale clamp over the **physical** full-screen height), touchpad cursor acceleration + fling + right-click long-press, and an adaptive double-tap that disambiguates drag from double-click. VNC / SPICE / Opaque are byte-identical to the pre-RDP UX.

Four rounds of changes have landed:

| Round | Theme | Headline changes |
|---|---|---|
| 1 | Initial MS-RDP UX | `InputAreaState` state machine (NONE/KEYBOARD/EXTRA); `ModifierRowView` + `RdpExtraGridPanel` + `RdpModifierRowHandler`; cover-scale zoom floor; RDP-only touchpad fling + long-press=right-click + double-tap-hold=immediate-press-and-drag; floating `keyboardToggleButton`. |
| 2 | Polish | One-shot consumption hook (INV-016); pointer bug fix across flavors; legacy `clearMetaState` path retained; `ModifierRowView` LOCKED semantics. |
| 3 | IME ↔ viewport math + adaptive double-tap | RDP IME insets listener + `recomputeRdpViewport` (single viewport owner); cover floor now uses the new `rdpFullViewHeight` field (not the shrunk `visibleHeight`); adaptive double-tap (nothing sent on the 2nd DOWN — wait for MOVE-vs-UP); touchpad sensitivity coefficient `(slider+1) * 0.6f` (was `0.4f`, too slow on 560dpi). |
| 4 | Touchpad tunables + drag-hold edge pinning + smaller modifiers | Three new RDP-only sliders — **Edge Pan Threshold** (`edgeThresholdDp`), **Mouse Acceleration** (`mouseAccelerationStrength`), **Fling Resistance** (`flingResistance`) — pushed into `RemoteCanvas.setEdgeThresholdDp`, `RemotePointer.setAccelerationStrength`, and `TouchInputHandlerTouchpad.setFlingDamp` from `RemoteCanvasActivity` (onCreate + onResume + on every `getInputHandlerById`). Defaults reproduce prior hardcoded values (35 dp / strength 1.0 / damp 0.86). Drag-hold edge pinning (`EdgePinRepeater` inner class on the touchpad handler) slowly keeps the cursor moving while a committed double-click+drag is pinned in the 24 dp canvas-edge band — RDP-only. Modifier row keys shrank (`rdp_input_area.xml` 40 dp, `ModifierRowView.makeButton` 56 dp wide, minHeight zeroed). `rdpTouchSlop` is now a fixed `DRAG_THRESHOLD_DP = 2f` px-clamped threshold, not `getScaledTouchSlop()/2`. |
| 5 | RDP session-UX polish | Four user-requested UX fixes plus one verified-no-change, all RDP-only. **"123" grid render**: `RdpExtraGridPanel.init` adds the `ExtraKeysView` with `MATCH_PARENT × MATCH_PARENT` `LinearLayout.LayoutParams` so the GridLayout's FILL rowSpecs distribute the parent height; `ExtraKeysView` / `ExtraKeysPagerAdapter` are byte-identical. **Double back-press disconnects**: `RemoteCanvasActivity.onBackPressed` adds an RDP-gated branch (after the existing IME/EXTRA collapse block) — first press stamps `lastBackPressForDisconnect` and toasts `back_press_to_disconnect`; second press within 2 000 ms calls `disconnectAndFinishActivity`. Timestamp is cleared in `onPause`. TV branch and non-RDP back-keystroke forward unchanged. **Viewport follows the cursor during touchpad fling**: `TouchInputHandlerTouchpad.Flinger.run` calls `viewable.movePanToMakePointerVisible()` after each per-tick `moveMouse(...)`, mirroring `onScroll` / `performTapClick`. `EdgePinRepeater` deliberately untouched (INV-023). **Floating keyboard-toggle button restyle**: new `bg_keyboard_toggle.xml` shape drawable (13 dp corners, `#40000000` 25 % black), explicit 40 dp × 40 dp, 5 dp padding, Material hamburger icon (`ic_baseline_menu_48`, tint `#A0A0A0`); `canvas.xml` and `layout-large/canvas.xml` are byte-identical. **Modifier row smaller + "123" uncolored**: row height 40 → 27 dp; per-button width 56 → 42 dp; `setTextSize(12sp)` on all 8 keys; `refreshToggleVisual` rest state reuses `COLOR_OFF_BG` / `COLOR_OFF_FG` (matching Del/Esc/Tab); the teal `COLOR_TOGGLE_OFF_BG/FG` constants were deleted; the EXTRA-state highlight (`COLOR_TOGGLE_ON_BG/FG`, grid open) is preserved as state feedback. **Verified no-code-change**: pinch-to-zoom on RDP is already clamped by `ZoomScaling.changeZoom:131-144` to `[computeMinimumScale, 4.0]`; `TouchInputHandlerTouchpad` super-feeds `MyScaleGestureDetector` into `TouchInputHandlerGeneric.onTouchEvent:421`; fresh RDP connections default to `ScaleType.MATRIX` / `ZoomScaling`. Only caveat: a user override of the global scaling pref to `FIT_CENTER` / `CENTER` makes pinch a no-op (pre-existing). |

Read `docs/features/INPUT_PIPELINE.md` for the canonical map. Key entry points:

| Surface | Where |
|---|---|
| RDP-only state machine (NONE / KEYBOARD / EXTRA) | `bVNC/src/main/java/com/iiordanov/bVNC/input/InputAreaState.java`, `RemoteCanvasActivity.setInputAreaState:1648-1664` |
| Modifier row + 123 grid + INV-010 bridge | `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/{ModifierRowView,RdpModifierRowHandler,RdpExtraGridPanel}.java`, `bVNC/src/main/res/layout/rdp_input_area.xml` |
| One-shot modifier consumption hook | `RemoteRdpKeyboard.KeyDispatchedListener:29-38`, `fireKeyDispatchedIfApplicable:118-135` |
| **RDP IME insets listener** (round 3) | `RemoteCanvasActivity.onCreate:322-...` (`ViewCompat.setOnApplyWindowInsetsListener` on `canvasLayout`) |
| **`recomputeRdpViewport`** (round 3, single viewport owner) | `RemoteCanvasActivity.recomputeRdpViewport:1677-1708` |
| **`rdpFullViewHeight` field** (round 3) | `RemoteCanvas.java:117` (init), `:911-920` (accessors) |
| **Cover-scale clamp** (round 3: uses `rdpFullViewHeight`, not `visibleHeight`) | `ZoomScaling.computeMinimumScale:223-235`; pinch-to-zoom clamp at `ZoomScaling.changeZoom:131-144` |
| Floating keyboard toggle (drag-vs-tap) | `RemoteCanvasActivity.onCreateOptionsMenu:1132-1168` (drag-vs-tap listener), `onKeyboardToggleButtonClicked:1735-1748` |
| **RDP-only touchpad gestures** (round 3: fling / long-press=right-click / **adaptive** double-tap=press-and-drag OR double-click) | `TouchInputHandlerTouchpad.setRdp(boolean):119-...`, `onFling:247-280`, `onLongPress:311-344`, `onDoubleTap:359-371`, `onTouchEvent:382-442` |
| **Touchpad sensitivity coefficient** (round 3: `0.6f`, was `0.4f`) | `RemoteCanvasActivity.getTouchpadSensitivityMultiplier:1303-...` |
| RDP-only IME-visible canvas resize (window `setSoftInputMode(SOFT_INPUT_ADJUST_RESIZE)`) | `RemoteCanvasActivity.onCreate:300-302` |
| Cross-flavor pointer bug fix (right/middle drag release) | `RemoteRdpPointer.moveMouseButtonDown:88-92`, `RemoteVncPointer.moveMouseButtonDown:101-104`, `RemoteSpicePointer.moveMouseButtonDown:100-103` |
| **RDP-only runtime tunables** (round 4: edge-pan / acceleration / fling) | `RemoteCanvasActivity.onCreate:311` + `onResume:905-918` + `getInputHandlerById:1237-1268` (push `setEdgeThresholdDp` / `setAccelerationStrength` / `setFlingDamp`); prefs `global_preferences.xml:52-66` |
| **Edge-pan threshold** (round 4: replaces hardcoded `Constants.H/W_THRESH`) | `RemoteCanvas.java:68-71` (default `EDGE_THRESH_DP = 35f` retained as field default), `:915-917` (`setEdgeThresholdDp`), `:560-568` (consumed in `movePanToMakePointerVisible`) |
| **Touchpad acceleration strength** (round 4: 0 = none, 1 = legacy, >1 = exaggerated) | `remoteClientLib/.../RemotePointer.java:47` (field `accelerationStrength`, default `1.0f`), `:318-319` (setter); consumed in `TouchInputHandlerTouchpad.computeAcceleration:552-568` |
| **Fling resistance** (round 4: `0.92f - slider*0.01f`, default slider 6 → damp 0.86) | `TouchInputHandlerTouchpad.java:65` (`flingDamp` field), `:111-113` (`setFlingDamp`); consumed in `Flinger.run:637-638` |
| **Drag-hold edge pinning** (round 4: laptop-touchpad behavior, RDP-only) | `TouchInputHandlerTouchpad.EdgePinRepeater:689-...`; started/updated from `onTouchEvent:433-438` (`ACTION_MOVE` while `rdpDoubleTapDragging`); 24 dp band at `:55` (`EDGE_PIN_BAND_DP`), 100 dp/s at `:57` (`EDGE_PIN_SPEED_DP_PER_S`) |
| **Drag-commit threshold** (round 4: fixed 2 dp, was `scaledTouchSlop/2`) | `TouchInputHandlerTouchpad.java:53` (`DRAG_THRESHOLD_DP`), `:103` (`rdpTouchSlop = max(2, 2*density)`), consumed at `:410` |
| **Modifier row sizing** (round 4: 48→40 dp row, 72→56 dp button width; round 5: 40→27 dp row, 56→42 dp button, 12 sp text, "123" rest = action-key palette) | `rdp_input_area.xml:12`, `ModifierRowView.java:206-211` (`buttonLayoutParams`), `:189-204` (`makeButton` 12 sp text), `:353-363` (`refreshToggleVisual`) |
| **"123" extra-keys grid render** (round 5: `MATCH_PARENT × MATCH_PARENT` on the inner `ExtraKeysView` so the GridLayout's FILL rowSpecs distribute the 192 dp parent height) | `RdpExtraGridPanel.init:93-95` |
| **Double back-press disconnects** (round 5: RDP-gated, 2 000 ms window) | `RemoteCanvasActivity.onBackPressed:1785-1794`; constant `:174` (`DOUBLE_BACK_DISCONNECT_WINDOW_MS = 2000L`); field `:175` (`lastBackPressForDisconnect`); cleared in `onPause:895`; new string `back_press_to_disconnect` in `bVNC/src/main/res/values/strings.xml:83` |
| **Viewport follows the cursor during touchpad fling** (round 5: `movePanToMakePointerVisible()` after each fling tick, mirroring `onScroll` / `performTapClick`) | `TouchInputHandlerTouchpad.Flinger.run:664-668` |
| **Floating keyboard-toggle button restyle** (round 5: new `bg_keyboard_toggle.xml`, 40 dp × 40 dp, 5 dp padding, hamburger icon) | `bVNC/src/main/res/drawable/bg_keyboard_toggle.xml`; `bVNC/src/main/res/layout/canvas.xml:154-165` (mirrored byte-identical in `layout-large/canvas.xml:154-165`) |

---

## 5. Conventions used in these docs

- **No code blocks.** Every implementation detail is identified by file path + class + line range. The reader is expected to follow the pointer, not paste snippets.
- **Identifier-prefixed rules.** Rules are named so they can be referenced from PRs and code review: `DPP-*` (design principle), `PAT-*` (pattern), `PER-*` (performance), `INV-*` (invariant), `ADR-*` (decision).
- **Match `AGENTS.md` style.** Concise, table-heavy, file-paths-in-backticks.
- **Repo-relative paths.** Always `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java`, never absolute.
- **Out of scope for these docs:** step-by-step build tutorials, public Play-Store-facing copy, user tutorials. The docs serve code authors.
