# ARCHITECTURE.md

Module map, layer responsibilities, and the three flows every contributor should be able to draw from memory: **boot → connect → render**, **input → wire**, **disconnect → teardown**.

> **Conventions:** paths are repo-relative. Module names use Gradle notation (`:bVNC`). Plugin ids `com.android.library` and `com.android.application` are abbreviated `android-library` and `android-application`.

---

## 1. Module graph (12 modules)

```
:remoteClientLib:jni:libs:deps:FreeRDP:client:Android:Studio:freeRDPCore   (android-library)
        |
        v
:remoteClientLib                                          (android-library)  NDK/JNI wrappers + JVM-side protocol base
        |
        +-------------------------------+
        |                               |
:pubkeyGenerator                    :bVNC                  (android-library) SSH key UI util
:common  (db + utils; UNIT TESTS)   :bVNC  ------------+   (android-library) ALL shared UI; hosts App + RemoteCanvasActivity
        ^           ^                |    |   |   |
        |           |                |    |   |   +-- aRDP-app            (android-application)  PAID   wrapper - manifest only
        +--+--+-----+                |    |   +------ freeaRDP-app        (android-application)  FREE   wrapper
           |  |                      |    +---------- aSPICE-app          (android-application)  PAID   wrapper
           |  +----------------------+    +---------- freeaSPICE-app      (android-application)  FREE   wrapper
           +-------------------------------------- Opaque-app           (android-application)  PAID   wrapper (also depends on :remoteClientLib)
                                                       (NB: bVNC-app / freebVNC-app / CustomVnc-app not shown — see file)
```

### Module table

| Module | Plugin | Purpose | Module deps |
|---|---|---|---|
| `:remoteClientLib:jni:libs:deps:FreeRDP:client:Android:Studio:freeRDPCore` | android-library | Vendored FreeRDP Java API + prebuilt `libfreerdp-android.so` per ABI | (none) |
| `:remoteClientLib` | android-library | NDK/JNI for SPICE/virt-viewer + JVM-side protocol base (`RfbConnectable`, `Viewable`, abstract `RemotePointer`/`RemoteKeyboard`) | `:remoteClientLib:...:freeRDPCore` |
| `:pubkeyGenerator` | android-library | SSH key UI utility | `:common` |
| `:common` | android-library | Encrypted DB helpers + utilities. **Only module with unit tests.** | (none) |
| `:bVNC` | android-library | All shared UI: `App`, `RemoteCanvasActivity`, `aRDP` editor, layouts, preferences, RDP/SPICE/VNC subclasses | `:remoteClientLib`, `:remoteClientLib:...:freeRDPCore`, `:common`, `:pubkeyGenerator` |
| `:bVNC-app` | android-application | VNC client (paid), wrapper | `:bVNC`, `:pubkeyGenerator` |
| `:freebVNC-app` | android-application | VNC client (free), wrapper | `:bVNC`, `:pubkeyGenerator` |
| `:aRDP-app` | android-application | RDP client (paid), wrapper — manifest only | `:bVNC`, `:pubkeyGenerator` |
| `:freeaRDP-app` | android-application | RDP client (free), wrapper | `:bVNC`, `:pubkeyGenerator` |
| `:aSPICE-app` | android-application | SPICE client (paid), wrapper | `:bVNC`, `:pubkeyGenerator` |
| `:freeaSPICE-app` | android-application | SPICE client (free), wrapper | `:bVNC`, `:pubkeyGenerator` |
| `:Opaque-app` | android-application | oVirt/RHEV/Proxmox — the only wrapper that ALSO depends directly on `:remoteClientLib` (uses Vv-file and SPICE directly) | `:bVNC`, `:remoteClientLib` |
| `:CustomVnc-app` | android-application | Programmatically-customized VNC client. Manifest placeholders substituted from `gradle.properties`. | `:bVNC`, `:pubkeyGenerator` |

### Application class location

All eight `android-application` modules declare `android:name="com.iiordanov.bVNC.App"` even though the `<application>` lives in `:bVNC`. The actual `App` class is `bVNC/src/main/java/com/iiordanov/bVNC/App.java`. This pattern lets every wrapper APK share one Application instance and one set of utilities.

---

## 2. Layer model

```
[ Activities (RemoteCanvasActivity, aRDP editor, ConnectionGridActivity, ... ) ]
                          |
                          v
[ Input adapters     ] [ UI helpers     ] [ Session glue (RemoteConnection, RemoteCanvasHandler) ]
       (Touch*, RemotePointer, RemoteKeyboard, ExtraKeys, MetaKeyDialog)
                          |
                          v
[ Protocol base      ]  <-- :remoteClientLib (RfbConnectable, Viewable, abstract Pointer/Keyboard)
                          |
                          v
[ Protocol per-flavor ]  <-- :bVNC/protocol/* (RemoteRdpConnection + RdpCommunicator)
                          |
              +-----------+-----------+
              v                       v
[ Native bridge  ]            [ Native bridge  ]
   (RDP via LibFreeRDP)        (SPICE via SpiceCommunicator + libspice.so)
```

### Layer responsibilities

| Layer | Owns | Forbidden from |
|---|---|---|
| **Activities** | Lifecycle, layout inflation, user input capture dispatch, Android permissions | Holding protocol state longer than the Activity |
| **Input adapters** | Touch + key + IME + hardware-mouse event interpretation; gesture → protocol-method mapping | Direct native calls; UI thread blocking |
| **Protocol base** | `Viewable`/`RfbConnectable`/`InputCarriable` interfaces; thread + executor ownership | Flavor-specific wire formats |
| **Per-flavor protocol** | Wire format encoding, FreeRDP/SPICE session config, auth handshake | UI |
| **Native bridge** | Loading `.so`, marshalling Java↔native | UI / activity lifecycle |

---

## 3. Three flows

### 3.1 Boot → connect → render

| Step | Owner | File |
|---|---|---|
| Launcher icon tap | Android OS → `ConnectionGridActivity` | `bVNC/src/main/AndroidManifest.xml` |
| Bookmark load | `ConnectionGridActivity.onCreate` → `ConnectionLoader.loadFromDatabase` | `bVNC/src/main/java/com/undatech/opaque/util/ConnectionLoader.java:41` |
| Build connection | `RemoteConnectionFactory.build` (selects `RemoteRdpConnection` when `Utils.isRdp`) | `bVNC/src/main/java/com/iiordanov/bVNC/protocol/RemoteConnectionFactory.kt:35` |
| Construct canvas | `RemoteCanvasActivity.onCreate` → `setContentView(...)` of `canvas.xml` | `bVNC/src/main/res/layout/canvas.xml` |
| Boot message | Handler posts `REINIT_SESSION` → `RemoteRdpConnection.initializeConnection` → `startRdpConnection` → `RdpCommunicator.connect` → `LibFreeRDP.connect` → `freerdp_connect` (native) | `bVNC/src/main/java/com/iiordanov/bVNC/protocol/RemoteRdpConnection.kt:46` |
| First frame | Native → `LibFreeRDP.OnGraphicsUpdate` static → `RdpCommunicator.OnGraphicsUpdate` → `UltraCompactBitmapData.updateBitmap` → `RemoteCanvas.reDraw` → `postInvalidate` | `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java:681` |
| Progress dialog dismissed | `RemoteCanvasHandler.handleMessage` `GRAPHICS_FIRST_FRAME_RECEIVED` | `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteCanvasHandler.java:643` |

### 3.2 Input → wire (mouse and keyboard)

This is the central flow for upcoming work. See `docs/features/INPUT_PIPELINE.md` for full detail. Quick reference:

| Step | Mouse | Keyboard |
|---|---|---|
| Capture | `RemoteCanvasActivity.onTouchEvent` (`RemoteCanvasActivity.java:1280`) | `RemoteClientsInputListener.onKey` (`RemoteClientsInputListener.kt:52`, set via `canvas.setOnKeyListener(inputListener)` in `RemoteCanvasActivity.setInputHandler:1207`) |
| Gesture / IME interpret | `TouchInputHandlerGeneric` / `TouchInputHandler*` | `RemoteRdpKeyboard.processLocalKeyEvent` + `RemoteKeyboardState.detectHardwareMetaState` |
| Protocol method | `pointer.leftButtonDown(...)` etc. | `RdpKeyboardMapper.processAndroidKeyEvent` |
| Native send | `RdpCommunicator.writePointerEvent` (queued in `inputExecutor`) | `RdpCommunicator.processVirtualKey` → `LibFreeRDP.sendKeyEvent` (queued in `inputExecutor`) |

### 3.3 Disconnect → teardown

| Step | Owner | File |
|---|---|---|
| User taps disconnect, or another window comes forward | `RemoteCanvasActivity.disconnectAndFinishActivity` | `RemoteCanvasActivity.java:1181` |
| Tear down | `remoteConnection.closeConnection` — sets `maintainConnection=false`, calls `keyboard.clearMetaState` + dummy key-up, `rfbConn.close`, interrupts `connectionThread`, writes screenshot | `RemoteConnection.java:278` |
| Activity destruction | `RemoteCanvasActivity.onDestroy` → `closeConnection` + `System.gc` | `RemoteCanvasActivity.java:1231` |

---

## 4. Where each thread runs

| Thread | Owns |
|---|---|
| **UI (main)** | All Activities, Fragments, View updates, `RemoteCanvas.reDraw` |
| **`SendRdpInputThread`** | `RdpCommunicator.inputExecutor` — every keyboard/mouse event sent to FreeRDP. `Thread.MAX_PRIORITY`. |
| **`DisconnectThread`** | Spawned inside `RdpCommunicator.close` to wait for `freerdp_disconnect`; single-shot, never explicitly joined. |
| **`connectionThread`** | Spawned by `RemoteRdpConnection.initializeConnection`; runs `startRdpConnection` to completion. |
| **FreeRDP native render** | Driven by FreeRDP; calls back into JVM via `LibFreeRDP.On*` static methods. Posts UI updates to the main handler. |
| **FreeRDP native render (Java side updates)** | `RemoteCanvasHandler.handleMessage` (main handler) processes UI events posted by the protocol. |

> **Race warning:** `RemoteConnection.handler` is a **static** field. Two `RemoteCanvasActivity` instances racing through it is theoretically possible; the codebase currently relies on not having two concurrent RDP sessions. `RdpCommunicator.patchFreeRdpCore()` synchronizes `GlobalApp.sessionMap` to support multiple sessions.

---

## 5. Persistence

| Store | Library | Where |
|---|---|---|
| Connection table | SQLCipher | `bVNC/src/main/java/com/iiordanov/bVNC/Database.java` (DB name `VncDatabase`, current schema `DBV_2_2_5`) |
| Per-connection columns (~110) | SQLCipher | `bVNC/src/main/java/com/iiordanov/bVNC/AbstractConnectionBean.java` |
| Global settings | SharedPreferences file `"generalSettings"` | `bVNC/src/main/java/com/iiordanov/bVNC/Utils.java` |
| Recent / meta-keys / sent-text | SQLCipher | `Database.java` (`MostRecentBean`, `AbstractMetaKeyBean`, `SentTextBean`) |
| Opaque-flavor connections | SharedPreferences (per-id files) | `bVNC/src/main/java/com/undatech/opaque/ConnectionSettings.java` |
| SSH public-key store | SQLCipher | `pubkeyGenerator` module |

Schema migrations live at `Database.java:283-596`. Each `DBV_*` constant corresponds to one migration step; per-id toolbar-position SharedPreferences were migrated into DB columns in `DBV_2_2_4` (`migrateToolbarPrefsToDb`, line 604).

---

## 6. Build system

| File | Role |
|---|---|
| `build.gradle` (root) | AGP `8.13.2`, Kotlin `2.2.21`; `ext { toolsVersion=35.0.0, compileApi=36, targetApi=36, minApi=21 }` |
| `gradle.properties` | `SDK_VERSION=21`, custom-client toggles, `org.gradle.java.home=C:/Users/marco/.jdks/jbr-21.0.11` (JDK 21 forced; JDK 25 fails) |
| `settings.gradle` | 12 module includes (full list in `MASTER.md` §1) |
| `local.properties` | Checked in. `sdk.dir=C:\Users\marco\AppData\Local\Android\Sdk`. Adjust per machine. |
| `opencode.json` | MCP `mobile-mcp` only |

### Scripts (`.bat` files)

| Script | Wrapper for | Notes |
|---|---|---|
| `gradlew.bat` | Standard Gradle wrapper | — |
| `test-all.bat` | `run-locked.bat gradlew.bat test --no-daemon --console=plain --quiet` | Prints `All tests passed` on success |
| `test-package.bat <pkg-spec>` | Resolves module via `resolve-test-module.ps1`, runs `gradlew.bat <task> --tests %1` | Optional module arg if class lives in more than one |
| `test-class.bat <class>` | Same as `test-package.bat` but for a single class | Prints `Tests passed` |

> **AGENTS.md notes `compile.bat`.** That file does not exist on disk; use `gradlew.bat assembleDebug` directly. Treat the AGENTS.md table as illustrative of intended scripts, not authoritative.

### Custom VNC viewer

`CustomVnc-app` reads four placeholders from `gradle.properties`:
- `CUSTOM_VNC_APP_NAME` → `${custom_vnc_app_name}` in manifest
- `CUSTOM_VNC_APP_ICON` → `${custom_vnc_app_icon}` (drawable resource)
- `CUSTOM_VNC_APP_NAMESPACE` → namespace suffix
- A yaml config in `bVNC/src/main/assets/` named `${pName}.yaml` is loaded by `App.java`

### Native dependencies

Two paths (mutually exclusive per build):

| Path | Speed | When to use |
|---|---|---|
| `./download-prebuilt-dependencies.sh` then `./bVNC/prepare_project.sh --skip-build libs nopath` | Fast (minutes) | Default. Standard development. |
| `./bVNC/prepare_project.sh <PROJECT> <ANDROID_SDK>` after installing Ubuntu deps (`gnome-common gobject-introspection nasm gtk-doc-tools python-is-python3`) and Android NDK/CMake | Slow (hours) | Only if the prebuilt `.so` files are out of date or you need to change FreeRDP itself. The `:remoteClientLib/build.gradle` kills AGP's built-in NDK build (lines 51-58, 64-68) — only the prebuilt `.so` files in `:remoteClientLib:...:freeRDPCore/src/main/jniLibs/{abi}/` are packaged. |

---

## 7. Submodules

| Path | Type | Action |
|---|---|---|
| `remote-desktop-clients-store-metadata/` | **Git submodule** (store-listing metadata only) | Do not edit from this repo; update upstream and pull. |

The `:remoteClientLib:jni:libs:deps/FreeRDP:client:Android:Studio/freeRDPCore/` module is **in-tree vendoring**, not a Git submodule. AGENTS.md's table lists only `remote-desktop-clients-store-metadata/` as a submodule — keep that distinction clear.

---

## 8. Where this doc ties into others

- See `DESIGN_PRINCIPLES.md` for the rules the architecture above is built around (no Hilt, no Compose, no `androidx.lifecycle.ViewModel`, etc.).
- See `PATTERNS.md` for the idioms (Protocol-per-flavor, single input executor, sticky modifier singleton in extras view).
- See `features/INPUT_PIPELINE.md` for the part of the architecture the upcoming work targets.
- See `DECISIONS/ADR-0001-multi-module-flavor-strategy.md` for why the wrappers exist at all.
