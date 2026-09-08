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
| **`recomputeRdpViewport`** | RDP-only viewport owner (`RemoteCanvasActivity.java:1641-1672`). Single source of truth for `canvas.setVisibleDesktopHeight` and `rdpInputAreaContainer.setTranslationY` on RDP. Captures `rdpFullViewHeight` once when the IME is closed, computes usable viewport as `full − ime − container`, and positions the container above the IME via `imeOverlap = max(0, ime − (full − canvasH))` (one formula covers both window models). Called from the insets listener, the end of `relayoutViews` (RDP-gated), and `setInputAreaState` (RDP-gated). The legacy `relayoutViews` shrink block (`setVisibleDesktopHeight + relativePan`) is RDP-gated out. |
| **Adaptive double-tap (RDP touchpad)** | RDP-only `TouchInputHandlerTouchpad` state machine: `onDoubleTap` arms a `PENDING` state and sends NOTHING; `onTouchEvent` `ACTION_MOVE` past half the touch slop commits a left-button press-and-drag (no preceding click); `ACTION_UP` without movement emits two `performTapClick` pairs (a true double-click). `onFling` returns false while `PENDING` or `DRAGGING`; `ACTION_CANCEL` releases the held button; `onLongPress` clears `PENDING`/`DRAGGING` (mutual exclusion with the synthesized right click). Replaces the round-2 "click-then-drag" behaviour that maximized title bars via Windows DBLCLK on the 2nd DOWN. |
| **StrictMode** | `RemoteCanvasActivity.onCreate` calls `StrictMode.ThreadPolicy.permitAll()` because FreeRDP callbacks fire on native threads and would otherwise trip the bitmap-write detection. |
| **Strictly vendored** | `:remoteClientLib:jni:libs:deps:FreeRDP:client:Android:Studio:freeRDPCore` is in-tree (not a Git submodule). Do not edit; pull upstream and patch. |

---

## 4. Recent work (aRDP session-UX parity with the Microsoft RDP app)

The aRDP flavor now ships the Microsoft RDP Android app's session UX: a software keyboard with a floating toggle, a modifier row above the IME with one-shot / lock semantics, a "123" extra-keys page replacing the IME, no black borders at minimum zoom (cover-scale clamp over the **physical** full-screen height), touchpad cursor acceleration + fling + right-click long-press, and an adaptive double-tap that disambiguates drag from double-click. VNC / SPICE / Opaque are byte-identical to the pre-RDP UX.

Three rounds of changes have landed:

| Round | Theme | Headline changes |
|---|---|---|
| 1 | Initial MS-RDP UX | `InputAreaState` state machine (NONE/KEYBOARD/EXTRA); `ModifierRowView` + `RdpExtraGridPanel` + `RdpModifierRowHandler`; cover-scale zoom floor; RDP-only touchpad fling + long-press=right-click + double-tap-hold=immediate-press-and-drag; floating `keyboardToggleButton`. |
| 2 | Polish | One-shot consumption hook (INV-016); pointer bug fix across flavors; legacy `clearMetaState` path retained; `ModifierRowView` LOCKED semantics. |
| 3 | IME ↔ viewport math + adaptive double-tap | RDP IME insets listener + `recomputeRdpViewport` (single viewport owner); cover floor now uses the new `rdpFullViewHeight` field (not the shrunk `visibleHeight`); adaptive double-tap (nothing sent on the 2nd DOWN — wait for MOVE-vs-UP); touchpad sensitivity coefficient `(slider+1) * 0.6f` (was `0.4f`, too slow on 560dpi). |

Read `docs/features/INPUT_PIPELINE.md` for the canonical map. Key entry points:

| Surface | Where |
|---|---|
| RDP-only state machine (NONE / KEYBOARD / EXTRA) | `bVNC/src/main/java/com/iiordanov/bVNC/input/InputAreaState.java`, `RemoteCanvasActivity.setInputAreaState:1612-1628` |
| Modifier row + 123 grid + INV-010 bridge | `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/{ModifierRowView,RdpModifierRowHandler,RdpExtraGridPanel}.java`, `bVNC/src/main/res/layout/rdp_input_area.xml` |
| One-shot modifier consumption hook | `RemoteRdpKeyboard.KeyDispatchedListener:29-38`, `fireKeyDispatchedIfApplicable:118-135` |
| **RDP IME insets listener** (round 3) | `RemoteCanvasActivity.onCreate:315-339` (`ViewCompat.setOnApplyWindowInsetsListener` on `canvasLayout`) |
| **`recomputeRdpViewport`** (round 3, single viewport owner) | `RemoteCanvasActivity.recomputeRdpViewport:1641-1672` |
| **`rdpFullViewHeight` field** (round 3) | `RemoteCanvas.java:109` (init), `:876-882` (accessors) |
| **Cover-scale clamp** (round 3: uses `rdpFullViewHeight`, not `visibleHeight`) | `ZoomScaling.computeMinimumScale:223-235` |
| Floating keyboard toggle (drag-vs-tap) | `RemoteCanvasActivity.java:1048-1103`, `onKeyboardToggleButtonClicked:1699-1712` |
| **RDP-only touchpad gestures** (round 3: fling / long-press=right-click / **adaptive** double-tap=press-and-drag OR double-click) | `TouchInputHandlerTouchpad.setRdp(boolean):77-101`, `onFling:204-237`, `onLongPress:268-300`, `onDoubleTap:315-327`, `onTouchEvent:338-381` |
| **Touchpad sensitivity coefficient** (round 3: `0.6f`, was `0.4f`) | `RemoteCanvasActivity.getTouchpadSensitivityMultiplier:1282-1286` |
| RDP-only IME-visible canvas resize (window `setSoftInputMode(SOFT_INPUT_ADJUST_RESIZE)`) | `RemoteCanvasActivity.onCreate:295-298` |
| Cross-flavor pointer bug fix (right/middle drag release) | `RemoteRdpPointer.moveMouseButtonDown:88-92`, `RemoteVncPointer.moveMouseButtonDown:101-104`, `RemoteSpicePointer.moveMouseButtonDown:100-103` |

---

## 5. Conventions used in these docs

- **No code blocks.** Every implementation detail is identified by file path + class + line range. The reader is expected to follow the pointer, not paste snippets.
- **Identifier-prefixed rules.** Rules are named so they can be referenced from PRs and code review: `DPP-*` (design principle), `PAT-*` (pattern), `PER-*` (performance), `INV-*` (invariant), `ADR-*` (decision).
- **Match `AGENTS.md` style.** Concise, table-heavy, file-paths-in-backticks.
- **Repo-relative paths.** Always `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java`, never absolute.
- **Out of scope for these docs:** step-by-step build tutorials, public Play-Store-facing copy, user tutorials. The docs serve code authors.
