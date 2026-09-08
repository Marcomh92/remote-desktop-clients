# docs/features/NATIVE_BRIDGE.md

> The JVM ↔ native boundary. Where Java turns into JNI and JNI turns into a real wire event.
>
> Read first: `ARCHITECTURE.md` §1 (module graph), `features/CONNECTION_LIFECYCLE.md` (frame pipeline), `features/INPUT_PIPELINE.md` (input pipeline).
>
> **Out of scope for this doc:** the C sources inside `remoteClientLib/jni/` and the vendored `freeRDPCore/`. Both are upstream-leaning and not edited in this fork. Document them only at the level of integration.

---

## 1. Native modules summary

| Module | Plugin | Purpose |
|---|---|---|
| `:remoteClientLib` | android-library | SPICE/virt-viewer Android.mk build + JVM-side protocol base |
| `:remoteClientLib:...:freeRDPCore` | android-library | Vendored FreeRDP: Java API + prebuilt `libfreerdp-android.so` per ABI |

`:remoteClientLib/build.gradle` disables AGP's built-in NDK build (`:51-58`, `:64-68`). It consumes the `.so` files from `:remoteClientLib:...:freeRDPCore/src/main/jniLibs/{abi}/`. The prebuilt archives are produced by either `./download-prebuilt-dependencies.sh` or `./bVNC/prepare_project.sh` from upstream releases.

### Supported ABIs

`remoteClientLib/build.gradle` declares `ndk.abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'`. No MIPS, no RISC-V.

### Prebuilt `.so` files inside `:remoteClientLib:...:freeRDPCore`

Per ABI: `libfreerdp-android.so`, `libfreerdp2.so`, `libfreerdp-client2.so`, `libwinpr2.so`, `libssl.so`, `libcrypto.so`, `libopenh264.so` (and similar). Loaded lazily by `LibFreeRDP` static initializer.

---

## 2. JNI surface from `:remoteClientLib`

### 2.1 `SpiceCommunicator` (the SPICE bridge)

`remoteClientLib/src/main/java/com/undatech/opaque/SpiceCommunicator.java:64-67` loads `libgstreamer_android.so` and `libspice.so`. Native methods declared (file-local numbering by line):

| Native method | Line | JNI symbol |
|---|---|---|
| `SpiceClientConnect(host, port, password, callback, ..., sslSession, unixSocket)` | `:269` | `Java_com_undatech_opaque_SpiceCommunicator_SpiceClientConnect` |
| `SpiceClientDisconnect()` | | `Java_..._SpiceClientDisconnect` |
| `CreateOvirtSession(...)` | | `Java_..._CreateOvirtSession` |
| `StartSessionFromVvFile(String, boolean)` | | `Java_..._StartSessionFromVvFile` |
| `SpiceButtonEvent(int x, int y, int metaState, int button, boolean isWheel)` | | `Java_..._SpiceButtonEvent` |
| `SpiceKeyEvent(boolean down, int hwKeycode)` | | `Java_..._SpiceKeyEvent` |
| `UpdateBitmap(Bitmap, int x, int y, int w, int h)` | | `Java_..._UpdateBitmap` |
| `SpiceRequestResolution(int w, int h)` | | `Java_..._SpiceRequestResolution` |
| `SpiceAttachUsbDeviceByFileDescriptor(int)` | | `Java_..._SpiceAttachUsbDeviceByFileDescriptor` |
| `SpiceDetachUsbDeviceByFileDescriptor(int)` | | `Java_..._SpiceDetachUsbDeviceByFileDescriptor` |
| `SpiceClientCutText(String text)` | | `Java_..._SpiceClientCutText` |

UI callbacks (C → Java): `uiCallbackInvalidate`, `uiCallbackSettingsChanged`, `uiCallbackMouseMode`, `uiCallbackShowMessage`.

JNI implementations:
- `remoteClientLib/src/main/cpp/android/android-service.c` — `Connect`/`Disconnect`/`CreateOvirtSession`/`StartSessionFromVvFile`/`SpiceAttachUsbDeviceByFileDescriptor`/`SpiceDetachUsbDeviceByFileDescriptor` and lifecycle.
- `remoteClientLib/src/main/cpp/android/android-io.c` — `SpiceButtonEvent`, `SpiceKeyEvent`, `UpdateBitmap`, `SpiceRequestResolution`, `SpiceClientCutText`, and the `uiCallback*` C-to-Java callbacks.
- `win32key2spice()` in `android-io.c:59` maps Android keycodes → X11 XTKeyboard codes via the generated `win32keymap.h` (built by `keymap-gen-with-android.pl` + `keymaps-with-android.csv`).
- `remoteClientLib/src/main/cpp/android/android-spicy.c` + `android-spice-widget.c` + `android-clipboard.c` — connection state, widget, clipboard glue.
- `remoteClientLib/src/main/cpp/virt-viewer/virt-viewer-file.c` + `virt-viewer-util.c` — virt-viewer shim.
- `dummy.cpp` — STL linkage stub.

### 2.2 `RdpCommunicator` (JVM-side; no own native methods)

`remoteClientLib/src/main/java/com/undatech/opaque/RdpCommunicator.java` has **no `native` declarations**. All native interaction is through the vendored `LibFreeRDP` (see §3).

### 2.3 `Viewable` / `RfbConnectable` / abstract `RemotePointer` / `RemoteKeyboard`

Live in `:remoteClientLib` and are pure JVM. No JNI.

### 2.4 No Binder / IPC

There is no `Service` or AIDL binder. The connection runs in-process. (Confirmed via grep: no `extends Service` in `:remoteClientLib` or `:bVNC`.)

---

## 3. FreeRDP bridge — `LibFreeRDP`

`remoteClientLib/jni/libs/deps/FreeRDP/client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java:1-688`. The static initializer (`:76-108`) loads `libfreerdp-android.so` (via `System.loadLibrary`), then calls `freerdp_get_jni_version()` and verifies `>= 2.5.1`. Throws `RuntimeException` if not.

### 3.1 Native methods declared on `LibFreeRDP`

| Native method | Purpose |
|---|---|
| `freerdp_new(Context)` | allocate a new FreeRDP instance; returns `long` instance id |
| `freerdp_free(long)` | free the instance |
| `freerdp_parse_arguments(...)` | build FreeRDP CLI args from a `StringBuilder` |
| `freerdp_connect(long)` | begin connect |
| `freerdp_disconnect(long)` | tear down |
| `freerdp_update_graphics(long, Bitmap, int x, int y, int w, int h)` | native writes pixels into the Java-owned Bitmap |
| `freerdp_send_cursor_event(...)` | pointer event |
| `freerdp_send_key_event(...)` | key event (VK-based) |
| `freerdp_send_unicodekey_event(...)` | key event (Unicode) |
| `freerdp_send_clipboard_data(...)` | clipboard |
| `freerdp_get_last_error_string(...)` | last error |
| `freerdp_get_version()` | version string |
| `freerdp_get_build_date()` | build date |
| `freerdp_get_build_revision()` | git revision |
| `freerdp_get_build_config()` | config flags |
| `freerdp_has_h264()` | H.264 codec availability |

JNI implementations are inside the vendored module's `cpp/` tree (upstream Thincast/FreeRDP). Not edited in this fork.

### 3.2 Static callbacks (C → Java)

These are the wire-back-into-JVM channels:

| Callback | Meaning | Who handles on the JVM side |
|---|---|---|
| `LibFreeRDP.OnConnectionSuccess(long)` | Connection established | `RdpCommunicator.OnConnectionSuccess:413-418` |
| `LibFreeRDP.OnConnectionFailure(long)` | Failed | `RdpCommunicator.OnConnectionFailure:421-424` |
| `LibFreeRDP.OnPreConnect(long)` | Pre-connect hook | `RdpCommunicator` |
| `LibFreeRDP.OnDisconnecting(long)` | Disconnecting | `RdpCommunicator.OnDisconnecting:427-452` |
| `LibFreeRDP.OnDisconnected(long)` | Done | `RdpCommunicator.OnDisconnected:455-464` |
| `LibFreeRDP.OnSettingsChanged(int w, int h, int bpp, ...)` | Server pushed new dims | `RdpCommunicator.OnSettingsChanged:467-478` |
| `LibFreeRDP.OnGraphicsUpdate(long inst, int x, int y, int w, int h)` | New pixels | `RdpCommunicator.OnGraphicsUpdate:574` (uses `GlobalApp.getSession(inst)`) |
| `LibFreeRDP.OnAuthenticate(...)` | Server demands creds | `RdpCommunicator.OnAuthenticate:503`; if `authenticationAttempted`, posts `GET_RDP_CREDENTIALS` |
| `LibFreeRDP.OnVerifyCertificate(...)` | Unknown cert | `RdpCommunicator.OnVerifiyCertificate:524` |
| `LibFreeRDP.OnGatewayAuthenticate(...)` | Gateway auth | `RdpCommunicator.OnGatewayAuthenticate:555` |

All UI-side effects are posted back to the main thread via `RemoteCanvasHandler` (covered in `features/CONNECTION_LIFECYCLE.md`).

---

## 4. How FreeRDP CLI args are built

`LibFreeRDP.setConnectionInfo(Context, long, BookmarkBase)` (`LibFreeRDP.java:243-417`) builds the FreeRDP argument string from the per-connection `ConnectionBean` and calls `freerdp_parse_arguments(...)`. Argument list (typical):

| Argument | Source | Default |
|---|---|---|
| `/v:host` | `connection.address` | |
| `/port:` | `connection.port` | `3389` |
| `/u:` | `connection.username` | |
| `/p:` | `connection.password` | |
| `/d:` | `connection.rdpDomain` | |
| `/g:host:port` | `connection.rdpGatewayHostname:Port` (when `rdpGatewayEnabled`) | |
| `/gu:` `/gd:` `/gp:` | gateway user/domain/password | |
| `/sec-nla` `/sec-tls` `/sec-rdp` | `connection.rdpSecurity` (`:300-313`) | NLA if available |
| `/bpp:` | `connection.rdpColor` (default `16`) | `16` |
| `/gdi:sw` | (always) | software GDI |
| `/rfx` `/gfx` `/gfx:AVC444` | `connection.enableGfx*` flags | |
| `/audio-mode:` | `connection.remoteSoundType` | |
| `/drive:sdcard,...` | `connection.redirectSDCard` | folder redirection |
| `/scale-desktop:` | `connection.desktopScalePercentage` | `100` |
| `/+glyph-cache` / `/-glyph-cache` | `connection.enableGlyphCache` (default `false`) | |

This is the **single configuration point** for all FreeRDP wire options. Adding a new RD feature means: add a column on `AbstractConnectionBean`, add a branch in `setConnectionInfo`, and an entry in `aRDP.java` / `main_rdp.xml`.

---

## 5. Threading across the JVM↔native boundary

| Direction | Thread |
|---|---|
| Java → native mouse/keyboard | `RdpCommunicator.inputExecutor` (`SendRdpInputThread`, MAX_PRIORITY) |
| Java → native connect/disconnect | `connectionThread` (RDP) / `RdpCommunicator`'s thread (SPICE) |
| Native → Java: callbacks (On*) | C-side FreeRDP threads; `LibFreeRDP.On*` static methods fire on those threads |
| Native → Java: graphics update | Same. `RdpCommunicator.OnGraphicsUpdate` synchronizes on `mbitmap` before posting `reDraw` |
| Native → Java: certificate wait | `while (!certificateAccepted) this.wait()` inside the static callback. Resume: `setCertificateAcceptedAndNotifyListeners` |

UI-side posts go through `RemoteCanvasHandler`'s `Handler` (main thread) — never directly touch Views from a callback thread.

---

## 6. Patch points to remember

| Patch | Where | Why |
|---|---|---|
| `RdpCommunicator.patchFreeRdpCore()` (`:100-112`) | Replaces `GlobalApp.sessionMap` with `synchronizedMap` | Multi-instance support. Should go upstream. |
| `RemoteCanvasActivity.onCreate` installs `StrictMode.permitAll()` | `RemoteCanvasActivity.java:294-297` | Native bitmap-write path would otherwise trip detection |
| `disableAGPNdkBuild` (kill switch) | `remoteClientLib/build.gradle:51-58, 64-68` | Prevents AGP from rebuilding the `.so` during local builds |
| `GlobalApp.getSession(long)` synchronization | `RdpCommunicator.patchFreeRdpCore` | SessionState lookup by instance id |

---

## 7. Build-time constraints

- JDK 21 (JBR) is forced via `gradle.properties`. JDK 25 fails.
- AGP `8.13.2`, Kotlin `2.2.21`, Gradle `8.13`.
- `compileApi=36, targetApi=36, minApi=21` from `build.gradle` root ext.
- `local.properties` is checked in and points at `C:\Users\marco\AppData\Local\Android\Sdk`. Update per machine.
- Native deps from `./download-prebuilt-dependencies.sh` (fast) or `./bVNC/prepare_project.sh` (slow from scratch).
- `:remoteClientLib:...:freeRDPCore` is **in-tree** vendored, not a Git submodule. The only true Git submodule in this repo is `remote-desktop-clients-store-metadata/`.

---

## 8. Licensing summary (relevant to Android play-store publishing)

| Component | License |
|---|---|
| `:bVNC` Java | Apache-2.0 |
| `:remoteClientLib` Java | Apache-2.0 |
| `:remoteClientLib/jni/cpp/android/*` | **GPLv3+** (Iordan Iordanov, 2013) |
| `:remoteClientLib:...:freeRDPCore` (`LibFreeRDP.java` etc.) | **MPL-2.0** (Thincast / Martin Fleisz, 2013) |
| FreeRDP C | Apache-2.0 |
| SPICE | LGPL |
| GStreamer | LGPL |
| VirtViewer | LGPL |

See `remoteClientLib/LICENSE` and `remoteClientLib/jni/LICENSE` for the per-component notices. Play-Store listing must include attribution and offer source.

---

## 9. Quick lookup table

| You want to… | Look at |
|---|---|
| Add a new FreeRDP wire option (e.g., a new codec flag) | `LibFreeRDP.setConnectionInfo` (column at the end of the `args` builder) + add a column on `AbstractConnectionBean` + add a UI field on `aRDP.java` / `main_rdp.xml` |
| Add a new SPICE wire option | `SpiceCommunicator` + `android-service.c` + `Android.mk` |
| Receive a new C-side callback into Java | Declare the static native method on `LibFreeRDP` (for FreeRDP) or `SpiceCommunicator` (for SPICE) and look up the implementation pattern in `android-io.c` |
| Send a new event into FreeRDP | Add a method on `RdpCommunicator`, queue it on `inputExecutor`, call the matching `freerdp_send_*` (or add one to `LibFreeRDP` and `cpp/`) |
| Debug a JNI crash | `adb logcat | grep freerdp-android`. Class names: `LibFreeRDP`, `RdpCommunicator`. |
