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
| **Soft modifier** | Sticky on-screen CTRL/ALT/SHIFT/SUPER buttons in the bottom pager of the canvas Activity. State mirrored into `RemoteRdpKeyboard.onScreenMetaState`. |
| **Hardware modifier** | Modifier state inferred from real hardware key events in `RemoteKeyboardState.detectHardwareMetaState`. Used to deduplicate modifier VK sends. |
| **StrictMode** | `RemoteCanvasActivity.onCreate` calls `StrictMode.ThreadPolicy.permitAll()` because FreeRDP callbacks fire on native threads and would otherwise trip the bitmap-write detection. |
| **Strictly vendored** | `:remoteClientLib:jni:libs:deps:FreeRDP:client:Android:Studio:freeRDPCore` is in-tree (not a Git submodule). Do not edit; pull upstream and patch. |

---

## 4. Upcoming focus (aRDP input mod)

Work in progress: **modify mouse behavior, keyboard handling, and modifier keys** while connected to a remote desktop. The hot surface area is:

| Concern | Primary file(s) |
|---|---|
| RDP mouse wire-format / press-release sequencing | `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpPointer.java` |
| RDP keyboard pipeline + Ctrl+Alt+Del | `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpKeyboard.java` |
| Keycode → VK translation, modifier lock/reset | `remoteClientLib/src/main/java/com/undatech/opaque/input/RdpKeyboardMapper.java` |
| Hardware-scancode modifier dedup | `remoteClientLib/src/main/java/com/undatech/opaque/input/RemoteKeyboardState.java` |
| MetaState plumbing, sticky on-screen modifiers | `remoteClientLib/src/main/java/com/undatech/opaque/input/RemoteKeyboard.java` |
| Touch / gesture → pointer dispatch | `bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerGeneric.java` |
| Sticky modifier buttons and `MetaKeyDialog` | `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RemoteExtraKeysHandler.java` and `bVNC/src/main/java/com/iiordanov/bVNC/dialogs/MetaKeyDialog.java` |
| Touch event fan-in (USB mouse, IME, touchscreen) | `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteClientsInputListener.kt` |
| Native JNI sink for input | `RdpCommunicator.processVirtualKey` / `processUnicodeKey` → `LibFreeRDP.sendKeyEvent` / `sendUnicodeKeyEvent` |
| Settings/preferences wired to input (scroll speed, touchpad sensitivity, input mode) | `bVNC/src/main/res/xml/global_preferences*.xml`, `bVNC/src/main/java/com/iiordanov/bVNC/Constants.java` |

> **`docs/features/INPUT_PIPELINE.md` is the canonical map for this work.** Read it before editing any of the files above.

---

## 5. Conventions used in these docs

- **No code blocks.** Every implementation detail is identified by file path + class + line range. The reader is expected to follow the pointer, not paste snippets.
- **Identifier-prefixed rules.** Rules are named so they can be referenced from PRs and code review: `DPP-*` (design principle), `PAT-*` (pattern), `PER-*` (performance), `INV-*` (invariant), `ADR-*` (decision).
- **Match `AGENTS.md` style.** Concise, table-heavy, file-paths-in-backticks.
- **Repo-relative paths.** Always `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java`, never absolute.
- **Out of scope for these docs:** step-by-step build tutorials, public Play-Store-facing copy, user tutorials. The docs serve code authors.
