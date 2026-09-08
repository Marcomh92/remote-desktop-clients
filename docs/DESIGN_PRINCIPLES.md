# DESIGN_PRINCIPLES.md

Cross-cutting rules and "facts" every change must respect. Each rule has a stable identifier so it can be referenced from code review, PR descriptions, and the rest of the docs (`PAT-*`, `PER-*`, `INV-*`, `DPP-*`, `ADR-*`).

> Read first: `MASTER.md`, `ARCHITECTURE.md`. The rules below are derived from the codebase, not invented for greenfield design.

---

## DPP — Design principles

| ID | Rule | Why |
|---|---|---|
| DPP-001 | **No Hilt, Koin, or Dagger.** Field injection and DI frameworks are not used. Wiring is explicit in the Activity. | Project shape (per AGENTS.md): Java-Views, no Compose, no annotation-processing DI. Adding one would balloon every flavor. |
| DPP-002 | **No Jetpack Compose.** UI is `android.view.View` and `androidx.appcompat.widget.*`. | Same rationale as DPP-001. Migrating eight wrappers off Views would be months of churn. |
| DPP-003 | **No `androidx.lifecycle.ViewModel`.** State lives on the Activity or in the protocol layer. | Same rationale. |
| DPP-004 | **No TypeScript-style DI Hilt-style `HiltViewModel` abstractions over RemoteConnection.** Use `RemoteConnection` plus its handler directly. | Project predates Jetpack. Adding it would not simplify the existing handwritten wiring. |
| DPP-005 | **Style is Java-first with a thin layer of Kotlin.** New utility/protocol code can be Kotlin, but Activities, Fragments, dialogs, custom Views stay Java. | Half-Kotlin/half-Java is the intentional state. Reverting either direction is out of scope. |
| DPP-006 | **The four flavors (VNC/RDP/SPICE/Opaque) share the canvas Activity.** No flavor-specific Activity subclasses; selection happens at runtime via `Utils.isRdp/isVnc/isSpice/isOpaque` checks. | Eight wrapper modules let us publish separate APKs without forking Activities. |
| DPP-007 | **Native libraries are loaded lazily and statically.** `LibFreeRDP` loads `libfreerdp-android.so` in its static initializer; `SpiceCommunicator` loads `libspice.so` + `libgstreamer_android.so`. No `System.loadLibrary` calls elsewhere. | Native search paths and side effects are centralized. |
| DPP-008 | **StrictMode is disabled on the canvas path.** `RemoteCanvasActivity.onCreate` calls `StrictMode.ThreadPolicy.permitAll()` because FreeRDP callbacks fire on native threads. | Without it, the bitmap-write path trips violations even though we synchronize carefully. Removing this requires a wider review of every cross-thread bitmap access. |
| DPP-009 | **Encrypted SQLite is the connection DB.** SQLCipher via `net.sqlcipher.database.SQLiteOpenHelper`. DB name `VncDatabase`. | Master-password protection is a documented feature. Plain SQLite breaks the master-password flow. |
| DPP-010 | **Per-flavor wrappers are manifest-only.** No `src/free/` source set; no `productFlavors` block. Paid/free is module choice. | Keeps each APK's build graph small and lets free flavor drop permissions at the manifest layer. |
| DPP-011 | **Pointer/keyboard language: "modifier" = `KeyEvent.META_*_ON` bit set.** "VK" = Windows Virtual Key (RDP wire), "scancode" = PC AT Set-1 scancode, "keysym" = X11 keysym (VNC), "Unicode" = UTF-16 code point. | Five terms — see PAT-002 for which codebase uses which. |
| DPP-012 | **All RDP keyboard and mouse output is funneled through one per-`RdpCommunicator` single-thread executor.** See INV-001. | Deterministic ordering, no interleaving from different UI threads. |
| DPP-013 | **The vendored FreeRDP core (`:remoteClientLib:...:freeRDPCore`) is upstream code.** Do not edit its C sources; treat the in-tree Java + prebuilt `.so` files as the contract. | Keeping it in-tree-vendored means we can patch the JNI surface and apply `gitnexus_query` reachability; touching the C side breaks the upgrade story. |

---

## INV — Invariants (things you must NOT break)

| ID | Invariant | Where documented |
|---|---|---|
| INV-001 | Every keyboard/mouse event for RDP is enqueued on `RdpCommunicator.inputExecutor`. Do not call `LibFreeRDP.sendKeyEvent` / `sendUnicodeKeyEvent` / `sendCursorEvent` from the UI thread or another worker. | `remoteClientLib/src/main/java/com/undatech/opaque/RdpCommunicator.java:62` |
| INV-002 | `RemoteCanvas` extends `AppCompatImageView` (not `SurfaceView`). The framebuffer is a `Bitmap` swapped via `setImageDrawable`. Do not replace this with a GL/Surface-backed renderer without revisiting every input coord-transform. | `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java:62` |
| INV-003 | `UltraCompactBitmapData.updateBitmap(Bitmap, x, y, w, h)` is `synchronized (mbitmap)`. The same `Bitmap` instance is shared with the native renderer (`LibFreeRDP.updateGraphics`). Both sides observe the same memory object. | `bVNC/src/main/java/com/iiordanov/bVNC/UltraCompactBitmapData.java:82` |
| INV-004 | The native `Bitmap` reference is **owned by Java**. FreeRDP writes pixels into it via JNI, but does not own or recycle it. Do not call `Bitmap.recycle()` from native code. | `remoteClientLib/jni/libs/deps/FreeRDP/client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java` |
| INV-005 | `RemoteConnection.handler` is a **static** field. Two concurrent `RemoteCanvasActivity` instances race on it. See GAP-INV-005 below. | `bVNC/src/main/java/com/iiordanov/bVNC/protocol/RemoteConnection.java` |
| INV-006 | `RemoteCanvas.reDraw` rate-limits invalidate to ~60 Hz, falling back to 10 Hz when behind. Do not bypass this throttle to call `view.invalidate()` directly — the throttle and the `mbitmap` synchronization are paired. | `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java:681` |
| INV-007 | `preferSendingUnicode` default is `true` (`preferSendingUnicodeDefaultValue` in `Constants.java`). When true, ASCII keys are sent via `processUnicodeKey` instead of `processVirtualKey`. | `bVNC/src/main/java/com/iiordanov/bVNC/Constants.java` |
| INV-008 | `RdpCommunicator.close()` sets `isInNormalProtocol=false` and starts `DisconnectThread`. It does NOT reset `RemoteKeyboardState.hardwareMetaState` or `RemoteKeyboardMetaState`. The RDP modifier row clears its own state via `RdpModifierRowHandler.resetRowState()` (called from `RemoteCanvasActivity.hideKeyboardAndExtraKeys`), which also calls `keyboard.clearMetaState()`. The legacy `clearMetaState()` site in `RemoteConnection.closeConnection` is retained. The hardware-meta leak across reconnects within the same Activity instance is **still not addressed** — that is the remaining gap. | `remoteClientLib/src/main/java/com/undatech/opaque/RdpCommunicator.java:204`, gap noted in §"Known gaps" below |
| INV-009 | `RdpKeyboardMapper.clearlAllModifiers()` exists publicly but is **not called from anywhere in the codebase**. It is the correct entry point for a modifier-reset refactor. | `remoteClientLib/src/main/java/com/undatech/opaque/input/RdpKeyboardMapper.java:664` (uncovered, by inspection) |
| INV-010 | The on-screen (sticky) modifier state is mirrored between the modifier UI widgets and `RemoteKeyboard.onScreenMetaState` exclusively via the canonical `clearMetaState` + `onScreen*Toggle` sequence. Two callers implement this bridge: legacy `ExtraKeysPagerAdapter.syncKeyboardModifierState(view)` and RDP `RdpModifierRowHandler.syncRowStateToKeyboard`. Clearing the panel without one of these leaves state drifted. | `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ExtraKeysPagerAdapter.java:147`, `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RdpModifierRowHandler.java:308` |
| INV-011 | Touch handlers generate `pointerMask` with a "down" bit (`POINTER_DOWN_MASK = 0x8000`). Each non-moving `sendPointerEvent` clears that bit and re-sends an "up" before the new "down", only if `prevPointerMask != 0`. This is the RDP wire-format guard that prevents a multi-button drag from confusing the remote. | `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpPointer.java:107` |
| INV-012 | `RemoteRdpPointer.scrollLeft` and `scrollRight` are **TODO stubs** — the RDP wire format here sends nothing. Horizontal scroll input events arrive at the handler and are silently dropped. | `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpPointer.java:73,79` |
| INV-013 | `RemoteKeyboardState.shouldSendModifier` suppresses duplicate modifier sends — once a hardware modifier is sent as a VK, the same modifier is never re-sent through `RdpCommunicator.sendModifierKeys` until state changes. This is paired with `RdpCommunicator.modifierMap` (`sendModifierKeys:222-234`). | `remoteClientLib/src/main/java/com/undatech/opaque/input/RemoteKeyboardState.java:194` |
| INV-014 | IME-committed text reaches RDP via `RemoteCanvas.onCreateInputConnection` returning a `BaseInputConnection`. The committed characters are split per-char inside `RemoteRdpKeyboard.processLocalKeyEvent` and forwarded as `KeyEvent(ACTION_MULTIPLE)` per char. | `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java:823`, `RemoteRdpKeyboard.java:70` |
| INV-015 | `KEYCODE_MENU` is handled twice: `RemoteRdpKeyboard.processLocalKeyEvent` returns true (consumed) and `RemoteClientsInputListener.onKey` explicitly delegates back to `activity.onKeyDown/onKeyUp` for the Activity's normal menu handling. | `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteClientsInputListener.kt:52` |
| INV-016 | `RemoteRdpKeyboard.KeyDispatchedListener` fires only on `ACTION_DOWN` / `ACTION_MULTIPLE`, only for non-modifier keycodes, only after `RdpKeyboardMapper.processAndroidKeyEvent` (or the per-char IME path) returns. The ACTION_UP replay uses `lastDownMetaState` so listeners must not see it. Listeners must NOT clear LOCKED modifiers; only ON (non-locked) modifiers may be consumed. | `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpKeyboard.java:29-38, 118-135` |
| INV-017 | `ZoomScaling.computeMinimumScale` returns `max(viewW/fbW, viewH/fbH)` for RDP (`viewH = canvas.visibleHeight>0 ? visibleHeight : height`) so no black borders appear at minimum zoom; non-RDP returns `canvas.getMinimumScale()` unchanged. The floor is recomputed on every call so it tracks the live viewport (the IME hides part of the canvas and `visibleHeight` shrinks; see INV-002 for the reallocation rule). | `bVNC/src/main/java/com/iiordanov/bVNC/ZoomScaling.java:213-234` |
| INV-018 | `InputAreaState` (RDP-only) is the single source of truth for the IME / "123" grid / hidden state of `@+id/rdpInputAreaContainer`. `RemoteCanvasActivity.setInputAreaState` is the only mutator; visibility is reapplied after the state is set. Anything that hides the IME on RDP must NOT collapse `EXTRA → NONE` — the grid must survive the IME hiding. | `bVNC/src/main/java/com/iiordanov/bVNC/input/InputAreaState.java`, `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java:1562-1570` |
| INV-019 | All RDP-only UX elements are gated by `Utils.isRdp(this)`: `ModifierRowView`, `RdpExtraGridPanel`, `RdpModifierRowHandler`, `keyboardToggleButton`, `rdpInputAreaContainer`, RDP touchpad gestures (`TouchInputHandlerTouchpad.setRdp(true)`). Non-RDP flavors (bVNC/aSPICE/Opaque) must remain byte-identical to the pre-RDP UX. | `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java:454-540, 626-627, 1048-1103, 1350-1356, 1562-1570` |

---

## PER — Performance rules

| ID | Rule | Why |
|---|---|---|
| PER-001 | Throttle between successive RDP input events: `sleepBetweenInputEvents` = 3ms (`RdpCommunicator.java:289-294`). Do not raise above 5ms without retesting for visible lag. Avoid dropping below 1ms — FreeRDP render queue saturates. | Empirically tuned for servers; values are persisted as constants. |
| PER-002 | Frame repaint throttle (~60 Hz → 10 Hz under load): `RemoteCanvas.reDraw` (`RemoteCanvas.java:681`). Bypassing it causes native bitmap-copy contention. | See INV-006. |
| PER-003 | Touch input handling is offloaded to a `Handler` from `RemoteClientsInputListener`. Do not block the input thread on network or DB I/O. | Visible jank. |
| PER-004 | No Compose, no animations beyond what `RemoteToolbar` and `appcompat` provide. Do not add `Animator` chains that mutate canvas-affecting state. | Every animation step tends to enter the input pipeline via invalidation. |
| PER-005 | `Bitmap.createBitmap(w, h, ARGB_8888)` is the only allocation path for the RDP framebuffer (per `UltraCompactBitmapData`). Resize only via `OnSettingsChanged` (server-driven). | Allocation cost dominates startup; one bitmap reuses across the session. |
| PER-006 | RDP-only touchpad fling emits up to ~50 move events/s via `TouchInputHandlerTouchpad.Flinger` (`FLING_TICK_MS=20`). Velocity decays at `FLING_DAMP=0.86` per tick and the loop stops when either axis drops below `FLING_NOISE_PX_PER_S=200`. The flinger reuses `RdpCommunicator.inputExecutor`-equivalent sending for pointer events. | Keeps fling on the same single-thread ordering as the rest of the input pipeline. Doubling the tick rate would double the load on `inputExecutor` with no perceptible gain. |

---

## PAT — Cross-references to pattern docs

See `PATTERNS.md` for the implementation idioms. Headlines:

- PAT-001 — `Protocol-per-flavor`: `RemoteRdpConnection` / `RemoteVncConnection` / `RemoteSpiceConnection` / `RemoteOvirtConnection` selected at runtime by `Utils.isRdp`.
- PAT-002 — `Modifier state lives in three places`: hardware / on-screen / sent-state. See `features/INPUT_PIPELINE.md`.
- PAT-003 — Single input executor per `RdpCommunicator`.
- PAT-004 — Sticky modifier UI synced to keyboard via the INV-010 bridge. Two callers (legacy pager + RDP modifier row).
- PAT-013 — RDP-only touchpad gestures (fling, long-press=right-click, double-tap-hold=drag) gated by `setRdp(boolean)`.
- PAT-014 — RDP cover-scale zoom minimum (no black borders at minimum zoom).
- PAT-015 — RDP-only `InputAreaState` state machine for the IME / "123" grid UX.

---

## Known gaps

These are real issues observed in the codebase; not yet ADRs. They are the most likely targets for upcoming refactors.

| Gap | Where | Why it matters |
|---|---|---|
| `compile.bat` and `app/build.gradle.kts` are referenced in `AGENTS.md` but do not exist on disk. | `AGENTS.md` §"Build & Test" | Documentation drift; treat AGENTS.md as illustrative for build scripts. |
| `run-locked.bat` referenced by every `.bat` wrapper does not exist on disk. | `compile.bat`, `test-all.bat`, `test-package.bat`, `test-class.bat` | Every documented wrapper fails with `'run-locked.bat' is not recognized`. Tracked as `known_issues/BUG-001`. |
| `RemoteConnection.handler` is static (GAP-INV-005). | `RemoteConnection.java` | Multi-instance RDP races. Workaround exists in `RdpCommunicator.patchFreeRdpCore` for `sessionMap` only. |
| `clearlAllModifiers()` unused (GAP-INV-009). | `RdpKeyboardMapper.java:664` | Modifier state can leak across reconnects if the user returns with a sticky modifier active. |
| Horizontal scroll wheel drops events silently (GAP-INV-012). | `RemoteRdpPointer.java:73,79` | Two-finger horizontal swipe → nothing on remote. |
| `GlobalApp.sessionMap` was `HashMap` upstream; synchronized via reflection patch. | `RdpCommunicator.patchFreeRdpCore:100-112` | Brittle — fix upstream instead. |
| `inputExecutor` and `DisconnectThread` are never explicitly shut down. | `RdpCommunicator.java:62, ~210` | Threads leak until process death (small leak; OK in practice). |
| Hardware-meta and remote-meta leak across reconnects within the same Activity instance. | `RemoteKeyboardState.detectHardwareMetaState`, `RemoteConnection.closeConnection:278-310` | RDP modifier-row clears its own state on disconnect (INV-008) but `hardwareMetaState` / `remoteKeyboardMetaState` still leak. |
| No unit tests for the new RDP-only view layer. | `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ModifierRowView.java`, `RdpModifierRowHandler.java`, `RdpExtraGridPanel.java`, `bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerTouchpad.java` (RDP-only gestures), `bVNC/src/main/java/com/iiordanov/bVNC/input/InputAreaState.java` | `:common` is the only module with JUnit 4 (see `TESTING.md` §1). New logic is embedded in Android View classes; pure-logic extraction was deliberately skipped as low value. Manual smoke on a real device is the practical alternative. |
| AGENTS.md mentions `compile.bat` but it doesn't exist. | `AGENTS.md` table | Use `gradlew.bat assembleDebug` directly. |

---

## What "out of scope" means here

These are decisions not to relitigate in PRs:

- Migrating from Java Views to Compose.
- Adding `ViewModel` to RemoteCanvasActivity.
- Replacing the per-flavor wrapper-module structure with `productFlavors`.
- Replacing `AppCompatImageView` with a GL or `Surface` canvas.
- Direct edits inside the vendored `:remoteClientLib:...:freeRDPCore` C sources.
