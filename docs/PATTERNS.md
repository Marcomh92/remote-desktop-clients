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

## PAT-004 — Sticky modifier singleton synced into the keyboard

**Rule.** The sticky CTRL/ALT/SHIFT/SUPER buttons live on `ExtraKeysView` (page 1 of the bottom pager). State is held in `SpecialButton` + `SpecialButtonState` and mirrored into `RemoteRdpKeyboard.onScreenMetaState` whenever the UI changes.

**Flow.**
1. User taps an on-screen CTRL. `ExtraKeysView` fires `setOnClickListener` on the button.
2. `ExtraKeysPagerAdapter.syncKeyboardModifierState(view)` reads `view.getSpecialButton(...)` for each of CTRL/ALT/SHIFT/SUPER.
3. The adapter calls `keyboard.clearMetaState()` then `keyboard.onScreen{Ctrl,Alt,Shift,Super}Toggle()` to mirror state.
4. The next non-modifier key event applies the modifier. `RemoteExtraKeysHandler.sendKey` (`:79-87`) reads-and-clears in one pass for `autoSetInActive=true` keys.

**Locking.** Double-tap within 800ms = lock the modifier ON (per `RdpKeyboardMapper.checkToggleModifierLock:712-730`). Locked modifiers are preserved across keys.

**Where.**
- `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ExtraKeysView.java`
- `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ExtraKeysPagerAdapter.java:106-155`
- `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/RemoteExtraKeysHandler.java:60-87`
- `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/SpecialButton.java`
- `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/SpecialButtonState.java`
- `bVNC/src/main/java/com/iiordanov/bVNC/extrakeys/ExtraKeysConstants.java:40-69`

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
| Top of stack | `singleHandOpts` overlay (visible only in single-handed input mode) |
| ^ | `extraKeysToolbar` ViewPager (bottom, hidden unless extra keys are on) |
| ^ | `keyboardIconForAndroidTv` (TV only) |
| ^ | `RemoteToolbar` (set as support action bar; right side) |
| Bottom | `RemoteCanvas` (fills parent) |

This stack order is fixed in `bVNC/src/main/res/layout/canvas.xml`. Adding overlays requires editing that XML in the same order.

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

## Pointer convention

`docs/*.md` files use **repo-relative paths** (no leading `/`), backticks for filenames (`bVNC/src/main/java/com/...`). When pointing to a method, include the line range as `:startLine-endLine` to make agent-driven lookups trivial. Example: `RemoteRdpPointer.sendPointerEvent:107-134`.
