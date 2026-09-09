# docs/features/UI_SHELL.md

> Everything **outside** the canvas Activity: launcher, bookmark list, edit screens, settings, DB, layouts. Outside the canvas = where the connection is *configured*; inside the canvas (`RemoteCanvasActivity`) is where the connection is *consumed*.
>
> Read first: `MASTER.md`, `ARCHITECTURE.md` §1 (§7 for the manifest layout), `DESIGN_PRINCIPLES.md` DPP-006/009/010.

---

## 1. Application entry — which Activities exist where

### 1.1 `aRDP-app/src/main/AndroidManifest.xml`

The wrapper declares exactly **3** activities (`:33-94`):

| Activity | Role | Notes |
|---|---|---|
| `com.undatech.opaque.ConnectionGridActivity` | Launcher (HOME) | Has the LAUNCHER / MULTIWINDOW_LAUNCHER / LEANBACK_LAUNCHER intent filters |
| `com.iiordanov.bVNC.aRDP` | RDP bookmark editor | Not LAUNCHER but reachable from `ConnectionGridActivity` |
| `com.iiordanov.bVNC.RemoteCanvasActivity` | RDP session host | `android:exported="true"`, three intent filters (see §1.2) |

Permissions declared in the wrapper:
- `INTERNET`, `VIBRATE`, `ACCESS_NETWORK_STATE`, `MODIFY_AUDIO_SETTINGS`, `RECORD_AUDIO`
- The free variant (`freeaRDP-app/src/main/AndroidManifest.xml`) drops `MODIFY_AUDIO_SETTINGS` and `RECORD_AUDIO`. Paid-only features (sound recording, audio redirection) are also gated at runtime in `aRDP.java:131-136, 321-330` via `Utils.isFree()` checks.

The Application class is `com.iiordanov.bVNC.App` (`bVNC/src/main/AndroidManifest.xml`).

### 1.2 RDP intent filters (so RemoteCanvasActivity can be launched externally)

```text
1. rdp:// scheme                                             (line 64-71)
   ACTION_VIEW, category BROWSABLE
2. intent:application/vnd.rdp                               (line 72-80)
3. file:/content:application/rdp|application/x-rdp, .rdp   (line 81-93)
```

### 1.3 Activities declared in the library manifest (`bVNC/src/main/AndroidManifest.xml`)

`bVNC/src/main/AndroidManifest.xml` declares (re-used by every wrapper):
- `ConnectionGridActivity`
- `RemoteCanvasActivity` (`launchMode="singleInstance"`)
- `ConnectionListActivity` (legacy CREATE_SHORTCUT view)
- `GeneratePubkeyActivity` (`:pubkeyGenerator`)
- `GlobalPreferencesActivity`

### 1.4 Notable Activities in `:bVNC` not declared in a manifest

| Class | Role |
|---|---|
| `bVNC/src/main/java/com/iiordanov/bVNC/aRDP.java` (52–378) | The RDP-flavored bookmark editor. **Extends `MainConfiguration`.** Adds domain, gateway, RemoteFX/GFX/GFXH264/GlyphCache switches, RDP color depth, RDP geometry, RDP security, console mode, redirect SD card, desktop scale, remote sound type, prefer-sending-Unicode. Layout `main_rdp.xml`. |
| `bVNC/src/main/java/com/iiordanov/bVNC/MainConfiguration.java` (46–627) | Abstract base for bVNC/aRDP/aSPICE editors. Hosts connection-type spinner, SSH fields, common RDP/VNC/SPICE text fields, input-method/scaling spinners. |
| `bVNC/src/main/java/com/undatech/opaque/ConnectionSetupActivity.java` | Opaque/Virt flavor editor — **separate from the DB-backed MainConfiguration flow**; uses per-id SharedPreferences. Not on the aRDP path. |

There is **no Activity declared exclusively in a free flavor manifest**.

---

## 2. Settings and preferences

### 2.1 Global prefs (`bVNC/src/main/res/xml/global_preferences*.xml`)

Loaded by `bVNC/src/main/java/com/iiordanov/bVNC/GlobalPreferencesFragment.java`. The active tree is `global_preferences.xml` + flavor overlay `_vnc` / `_rdp` / `_spice`. For aRDP the overlay (`global_preferences_rdp.xml`) currently contains a visual spacer plus two SeekBarPreferences for round-6 modifier row sizing (see §2.2 / `PAT-016`). All RDP connection settings are per-connection; the row sizing prefs are global.

`SharedPreferences` file name: `"generalSettings"`.

### 2.2 Preference key catalog (relevant entries)

| Key | Default | Purpose | Where |
|---|---|---|---|
| `disableImmersive` | false | Disable immersive mode | `global_preferences.xml:10`; `Constants.disableImmersiveTag` |
| `keepScreenOn` | false | Keep screen awake during session | `:14`; `Constants.keepScreenOnTag` |
| `forceLandscape` | false | Force landscape | `:18`; `Constants.forceLandscapeTag` |
| `leftHandedModeTag` | false | FABs on the left | `:22` |
| `moreDebugLoggingTag` | false | Verbose logging | `:26` |
| `doNotShowDesktopThumbnails` | false | Hide thumbnails in grid | `:30` |
| `showOnlyConnectionNicknames` | false | Compact grid | `:34` |
| `softwareKeyboardType` | `TYPE_NULL` | Soft-kb type | `:40`; `Constants.softwareKeyboardType` |
| `scrollSpeed` | `6` | Scroll sensitivity (`(slider+1)/7f`) | `:44`; `Constants.scrollSpeed`; `DEFAULT_SCROLL_SPEED=6` |
| `touchpadSensitivity` | `4` | Touchpad sensitivity (`(slider+1)*0.4f`) | `:49`; `Constants.touchpadSensitivity`; `DEFAULT_TOUCHPAD_SENSITIVITY=4` |
| `disablePointerOffsetCalculation` | false | Disables pointer offset correction | `:54` |
| `localToRemoteClipboardIntegration` | true | Local → remote clipboard | `:58`; `Constants.localToRemoteClipboardIntegration` |
| `remoteToLocalClipboardIntegration` | true | Remote → local clipboard | `:62`; `Constants.remoteToLocalClipboardIntegration` |
| `openDefaultConnectionSettings` | — | Opens the invisible-template editor | `:67` |
| `extraKeysTourShown` | false | One-shot tour flag for bottom pager | per `Constants.EXTRA_KEYS_TOUR_SHOWN` |
| `masterPasswordEnabledTag` | — | Master password toggle | `Constants.masterPasswordEnabledTag` |
| `preferSendingUnicode` | true | Send ASCII keys as Unicode | `Constants.preferSendingUnicode`; `preferSendingUnicodeDefaultValue=true` |
| `permissionsRequested` | — | First-run permission request idempotency | `Constants.permissionsRequested` |
| `positionToolbarLastUsed` | — | Toolbar position memory | `Constants.positionToolbarLastUsed` |
| `rAltAsIsoL3Shift` | true (VNC overlay only) | Right-Alt → ISO L3 Shift | `global_preferences_vnc.xml:5` |
| **`rdpModifierKeyHeightDp`** (round 6, RDP overlay only) | `27` → max `56` | Modifier row container height (dp); pushes `setRowHeightdp` on `ModifierRowView` | `global_preferences_rdp.xml:5-9`; `Constants.rdpModifierKeyHeightDp` / `DEFAULT_RDP_MODIFIER_KEY_HEIGHT_DP` (`Constants.java:178,199`); `RemoteCanvasActivity.getRdpModifierKeyHeightDp:1332-1334`; consumed via `RdpModifierRowHandler.applyModifierRowSizing:243-246` |
| **`rdpModifierKeySizeDp`** (round 6, RDP overlay only) | `42` → max `72` | Per-button width (dp) on every modifier / action / toggle button; label text scales as `12sp × dp/42`, 8 sp floor | `global_preferences_rdp.xml:10-14`; `Constants.rdpModifierKeySizeDp` / `DEFAULT_RDP_MODIFIER_KEY_SIZE_DP` (`Constants.java:179,200`); `RemoteCanvasActivity.getRdpModifierKeySizeDp:1336-1338`; consumed via `applyModifierRowSizing:243-246` |
| `PREF_USE_LAST_POSITION_TOOLBAR` + `_X` `_Y` `_MOVED` | — | Legacy per-id toolbar prefs; migrated to DB columns in `DBV_2_2_4` | `Database.java:604` `migrateToolbarPrefsToDb()` |

### 2.3 Per-connection columns relevant to aRDP

See §5 below.

---

## 3. The can`if`/wh`en` `Utils` flavor-detection helpers

`bVNC/src/main/java/com/iiordanov/bVNC/Utils.java` (881 lines). Central flavor detection lives at `:274-310`:

```text
Utils.isVnc(context)    -> flavor name contains "bvnc" or package is in {com.iiordanov.bVNC, com.iiordanov.freebVNC}
Utils.isRdp(context)    -> flavor name contains "ardp"
Utils.isSpice(context)  -> flavor name contains "aspice"
Utils.isOpaque(context) -> package name starts with com.undatech.opaque
Utils.isFree(context)   -> flavor name contains "free"
Utils.isCustom(context) -> namespace contains "customvnc"
```

Helpers `Utils.getConnectionSetupClass(this)` returns the right setup activity:
- aRDP / freeaRDP → `com.iiordanov.bVNC.aRDP.class`
- bVNC / freebVNC → `com.iiordanov.bVNC.bVNC.class`
- aSPICE / freeaSPICE → `com.iiordanov.bVNC.aSPICE.class`
- Opaque → `com.undatech.opaque.ConnectionSetupActivity.class`
- Custom → see Opaque path

The `connectionScheme` is per flavor: `rdp` for aRDP (default port `3389`), `vnc` for bVNC (default port `5900`), `spice` for aSPICE, `vv` for Opaque. `Utils.getFileExtension` returns the matching default file extension.

---

## 4. Database

`bVNC/src/main/java/com/iiordanov/bVNC/Database.java` — `class Database extends SQLiteOpenHelper` from `net.sqlcipher`. DB name `"VncDatabase"`. Current schema `CURRVERS = DBV_2_2_5 = 641`.

Migrations begin at `DBV_0_5_0 = 12` and walk upward in `onUpgrade()` (`:283-596`). One special case is `migrateToolbarPrefsToDb()` (`:604-633`) which pulls per-id SharedPreferences into DB columns at `DBV_2_2_4`.

Tables:
- `CONNECTION_BEAN` (~107 columns) — one row per saved connection. See §5.
- `MostRecentBean` — MRU list (`:166`).
- `MetaList` — meta-keys list (`:167`).
- `AbstractMetaKeyBean` — meta-keys per list (`:168`).
- `SentTextBean` — sent-text buffer (`:169`).

The Opaque flavor uses its own `SharedPreferences`-per-connection file instead (`ConnectionSettings.java`).

---

## 5. `AbstractConnectionBean` columns relevant to aRDP

`bVNC/src/main/java/com/iiordanov/bVNC/AbstractConnectionBean.java`. Total of ~107 columns. The RDP-relevant subset:

| Column | Default | Purpose | Line |
|---|---|---|---|
| `_id` | autoincrement | PK | `GEN_FIELD__ID` |
| `NICKNAME` | | Display name | `:41` |
| `CONNECTIONTYPE` | `0` (PLAIN) | `0=PLAIN, 1=SSH, ...` | `:43` |
| `ADDRESS` | | Host / IP | `:97` |
| `PORT` | `3389` | Default port | `:99`; `Constants.DEFAULT_RDP_PORT` |
| `USERNAME` | | RDP user | `:145` |
| `PASSWORD` | | RDP password | `:109` |
| `KEEPPASSWORD` | false | Persist password | `:133` |
| `RDPDOMAIN` | | RDP domain | `:147` (added DBV_1_9_0→2_0_0) |
| `RDPRESTYPE` | `0` | RDP resolution preset (NATIVE_LANDSCAPE / PORTRAIT / CUSTOM / AUTO) | `:155` |
| `RDPWIDTH` | | Custom width (used when RDPRESTYPE=CUSTOM) | `:157` |
| `RDPHEIGHT` | | Custom height | `:159` |
| `RDPCOLOR` | `16` | RDP color depth | `:161`; `DEFAULT_RDP_COLOR_MODE=16` |
| `REMOTEFX` | false | RemoteFX codec | `:163` |
| `DESKTOPBACKGROUND` | true | Visual experience flag | `:165` |
| `FONTSMOOTHING` | true | Visual experience flag | `:167` |
| `DESKTOPCOMPOSITION` | true | Visual experience flag | `:169` |
| `WINDOWCONTENTS` | true | Visual experience flag | `:171` |
| `MENUANIMATION` | true | Visual experience flag | `:173` |
| `VISUALSTYLES` | true | Visual experience flag | `:175` |
| `REDIRECTSDCARD` | false | Folder redirection | `:177` |
| `CONSOLEMODE` | false | Connect to console session | `:179` |
| `ENABLESOUND` | false | Audio redirection | `:181` |
| `ENABLERECORDING` | false | Recording | `:183` |
| `REMOTESOUNDTYPE` | `0` | `0=on device, 1=on server, 2=disabled` | `:185`; `REMOTE_SOUND_*` |
| `VIEWONLY` | false | View-only mode | `:187` |
| `LAYOUTMAP` | `English (US)` | Keyboard layout | `:189` |
| `FILENAME` | | Saved bookmark file | `:192` |
| `X509KEYSIGNATURE` | | Cert verify | `:194` |
| `SCREENSHOTFILENAME` | | Thumbnail filename | `:196` |
| `ENABLEGFX` | false | RDP 8+ GFX codec | `:199` |
| `ENABLEGFXH264` | false | H.264 codec | `:201` |
| `PREFERSENDINGUNICODE` | true | Send Unicode over RDP | `:204`; `preferSendingUnicodeDefaultValue` |
| `RDPGATEWAYENABLED` | false | Use RD Gateway | `:214` (added DBV_2_1_9→2_2_0) |
| `RDPGATEWAYHOSTNAME` | | Gateway host | `:216` |
| `RDPGATEWAYPORT` | `443` | Gateway port | `:218`; `DEFAULT_RDP_GATEWAY_PORT` |
| `RDPGATEWAYUSERNAME` `RDPGATEWAYDOMAIN` `RDPGATEWAYPASSWORD` | | Gateway credentials | `:220-224` |
| `KEEPRDPGATEWAYPASSWORD` | false | Persist gateway password | `:226` |
| `DESKTOPSCALEPERCENTAGE` | `100` | 100–300 | `:228`; `MIN_/MAX_/DEFAULT_DESKTOP_SCALE_PERCENTAGE` |
| `RDPSECURITY` | `0` | `0=auto-negotiate` default | `:230` |
| `ENABLEGLYPHCACHE` | false | Glyph cache (default `ENABLE_GLYPH_CACHE_DEFAULT=false`) | `:232` |
| `INVISIBLE` | false | Hidden default-template row | `:234`; `INVISIBLE_DEFAULT=false` |
| `USELASTPOSITIONTOOLBAR` `X` `Y` `MOVED` | — | Per-id toolbar drag persistence | `:238-244` (migrated DBV_2_2_4) |
| `USELOCALCURSOR` | `CURSOR_AUTO=0` | `0=AUTO, 1=FORCE_LOCAL, 2=FORCE_DISABLE` | `Constants.CURSOR_*` |
| `CLIENTAUTH*` (PUBKEY/PRIVKEY/PASSPHRASE/ENABLED) | — | SSH client-auth (used for RD Gateway tunnels) | `:247-253` |
| `SVNCENABLED` `SVNCPASSPHRASE` `KEEPSVNCPASSPHRASE` | — | sVNC | `:255-259` |
| `SSH*` columns (10+) | — | SSH settings: server, port, user, password, pubkey, privkey, ... | `:45-95` |
| `EXTERNALID` `REQUIRESVPN` `VPNURISCHEME` | — | External integration | `:207-211` |

### 5.1 How to add a new per-connection setting

1. Add a `GEN_FIELD_*` constant + column in `GEN_CREATE` (in `AbstractConnectionBean.java`).
2. Add a `DBV_*` constant + migration step in `Database.java:onUpgrade`.
3. Add a getter/setter to `AbstractConnectionBean`/`ConnectionBean`.
4. Add a UI field to `aRDP.java` and `main_rdp.xml`.
5. Wire to native code: `LibFreeRDP.setConnectionInfo()` in `features/NATIVE_BRIDGE.md` §4.

---

## 6. Layouts affecting the canvas toolbar / sidebar

### 6.1 `bVNC/src/main/res/layout/canvas.xml` (180 lines)

| Z-order | Element | Role |
|---|---|---|
| Top | `toolbarToggleButton @+id/toolbarToggleButton` | **Always-visible action-bar toggle FAB** (round 7). Lives at `right|center`; the toolbar's `leftHandedModeTag` gravity is mirrored onto the FAB at activity start (`RemoteCanvasActivity.continueConnecting:477-485`), after which the FAB can be dragged independently. Drag-vs-tap `OnTouchListener` (`setupToolbarToggleButton:1783-1830`): tap toggles toolbar expand/collapse (`toggleToolbarExpansion:1839-1850`); drag persists X/Y to the legacy `USELASTPOSITIONTOOLBAR_X` / `_Y` / `_MOVED` columns via `handler.post(...)` off the UI thread (`saveToolbarTogglePosition:1928-1938`). Position re-applied on every layout pass (`restoreToolbarTogglePosition:1910-1926`, called from `offsetOrRestoreSavedToolbarPosition:702-704`); out-of-bounds positions leave the FAB at its gravity default. 32 dp × 32 dp, 8 dp padding, `@drawable/bg_keyboard_toggle.xml` background, `@drawable/ic_overflow_vertical_24.xml` icon (Material vertical 3-dot, tint `#A0A0A0`). `contentDescription=@string/show_menu`. Out-of-bounds saves are silently dropped — no DB write. The legacy `moveToolbar` drag-handle menu item is gone (the toggle FAB fills the role for the whole toolbar). |
| ^ | `keyboardToggleButton @+id/keyboardToggleButton` | **Always-visible keyboard FAB** on every flavor (round 7). Cycle: RDP `InputAreaState` `NONE → KEYBOARD → EXTRA → NONE` via `onKeyboardToggleButtonClicked:1757-1773`; non-RDP flavors just call `showKeyboard()` / `hideKeyboard()`. Drag-vs-tap distinguished by an inline `OnTouchListener` (`RemoteCanvasActivity.onCreateOptionsMenu:1131-1174`); **session-only** position (no persistence — round-7 FAB asymmetry, see PAT-007). **Round 5 restyle:** explicit 40 dp × 40 dp (was `wrap_content` ≈ 48 dp), 5 dp padding (was 6 dp), Material hamburger icon (`@drawable/ic_baseline_menu_48`, tint `#A0A0A0`), background `@drawable/bg_keyboard_toggle.xml` (13 dp corners, `#40000000` 25 % black; was flat `#80000000` 50 %). **Round 6 visual delta:** 40 dp → 32 dp, padding 5 dp → 4 dp; `bg_keyboard_toggle.xml` corners 13 dp → 10 dp, fill `#40000000` → `#33000000`. **Round 7 behavior change:** no `android:visibility` attribute (visible by default on every flavor; `Utils.isRdp(this)` is no longer gating); the legacy `actionShowKeyboard` toolbar menu item is gone (the FAB fills the role). |
| ^ | `singleHandOpts` overlay | `RelativeLayout` (gone by default; visible only in `TouchInputHandlerSingleHanded`). Six buttons: `singleDrag`, `singleMiddle`, `singleRight`, `singleScroll`, `singleZoom`, `singleCancel`. |
| ^ | `extraKeysToolbar` (`ViewPager @id/extraKeysToolbar`) | Legacy bottom pager (3 pages: SendText / Sticky mods / F-keys). Suppressed on RDP (`RemoteCanvasActivity.onGlobalLayout:537-541`); kept for VNC/SPICE/Opaque. |
| ^ | `extraKeysPageIndicator` | Dots under the pager. |
| ^ | `keyboardIconForAndroidTv` | Animated icon for TV. Gone otherwise. |
| ^ | `RemoteToolbar @id/toolbar` | Right-side floating action bar. Set as support action bar (`setSupportActionBar(toolbar)` in `continueConnecting:475`). ColorPrimary background. **Default `visibility="gone"`** (round 7); shown only when the user taps `toolbarToggleButton`. At expand time the toolbar is anchored to the FAB via `positionToolbarNextToToggle:1859-1901` (preferred left of the FAB; falls back to right if no room; clamped to canvas bounds). |
| ^ | `rdpInputAreaContainer @+id/rdpInputAreaContainer` | RDP-only `FrameLayout` at bottom-anchored, `gone` by default. Inflated at runtime with `rdp_input_area.xml` (modifier row + "123" extra-keys grid). Visibility is driven by `InputAreaState`. |
| Bottom | `RemoteCanvas @id/canvas` | The drawing surface, fills parent. |

### 6.2 Toolbar menu (`bVNC/src/main/res/menu/canvasactivitymenu.xml`)

Always-on items: `extraKeysToggle`, `actionScrollWheel`.

Overflow submenu items: `itemInputMode` (`itemInputTouchPanZoomMouse` / `itemInputDragPanZoomMouse` / `itemInputTouchpad` / `itemInputSingleHanded`), `itemScaling` (`itemZoomable` / `itemFitToScreen` / `itemOneToOne`), `itemDisconnect`, `itemSpecialKeys`, `itemSendKeyAgain`, `itemCtrlAltDel`, `itemEnterText`, `itemColorMode`, `itemCenterMouse`, `itemInfo`, `itemHelpInputMode`.

**Round 7 menu deltas.** Two always-on items were removed:

- `moveToolbar` — drag-handle for repositioning the action bar. The toolbar is no longer directly user-draggable; the `toolbarToggleButton` FAB now fills that role for the whole element (drag the FAB), and the FAB itself is draggable. The toolbar position at expand time is anchored to the FAB via `positionToolbarNextToToggle`.
- `actionShowKeyboard` — toolbar entry point for the IME on non-RDP flavors. The keyboard FAB (`@+id/keyboardToggleButton`) is now visible on every flavor, so this menu item had no remaining user.

(See `features/INPUT_PIPELINE.md` §6 for the settings/preferences that drive these items.)

### 6.3 `bVNC/src/main/res/layout/main_rdp.xml`

The aRDP-specific bookmark editor layout. Top MaterialCardView (nickname, optional SSH fields), middle card (RDP IP/port, username, **rdpDomain**, password, keep-password), gateway card (toggle + nested form), advanced card (color depth, geometry, desktop scale SeekBar, scaling, input method spinner, preferSendingUnicode, RemoteFX/GFX/GFXH264/GlyphCache switches, visual-experience flags, audio group, recording, console mode, redirect SD card).

### 6.4 Other relevant layouts

| Layout | Role |
|---|---|
| `bVNC/src/main/res/layout/grid_view_activity.xml` | Launcher grid (`GridView @id/gridView`, `search`, `addNewConnection`, `popUpMenuButton`) |
| `bVNC/src/main/res/menu/grid_view_activity_actions.xml` | Home-screen toolbar: battery optimizations, export/import, master password, logcat copy, new connection, Morpheusly, rate/share, default-settings editor |
| `bVNC/src/main/res/menu/connectionsetupmenu.xml` | Bookmark editor toolbar: help, save, save-as-copy, reset-defaults (only when editing invisible template) |
| `bVNC/src/main/res/layout/metakey.xml` | MetaKeyDialog body |
| `bVNC/src/main/res/layout/canvasactivitymenu.xml` | Canvas toolbar menu |
| `bVNC/src/main/res/layout/rdp_input_area.xml` | RDP-only modifier row + "123" extra-keys grid; inflated into `rdpInputAreaContainer`. Not present in `layout-large/rdp_input_area.xml` (single layout for all configurations). Round 5: `ModifierRowView` height 40 dp → 27 dp; the inner grid panel (`RdpExtraGridPanel`) height stays at 192 dp so its GridLayout's FILL rowSpecs distribute the parent height. |
| `bVNC/src/main/res/drawable/bg_keyboard_toggle.xml` | Floating keyboard-toggle button shape drawable. Round 5: `<shape>` with `corners android:radius="13dp"` + `<solid android:color="#40000000" />` (25 % black). **Round 6 visual delta:** corners 13 dp → 10 dp, fill `#40000000` → `#33000000` (≈ 20 % black). |
| `bVNC/src/main/res/layout-large/canvas.xml` | Large-screen variant of `canvas.xml` — byte-identical to the default layout. Both floating buttons (`keyboardToggleButton`, `toolbarToggleButton`) and the RDP-only `rdpInputAreaContainer` are declared at the same IDs in both. |
| `bVNC/src/main/res/drawable/ic_overflow_vertical_24.xml` | Material vertical 3-dot vector, 24 dp × 24 dp viewport, tint `#A0A0A0`. Round-7 icon for the `@+id/toolbarToggleButton` FAB (action-bar toggle). |

### 6.5 Back-press behavior (RDP-only)

`RemoteCanvasActivity.onBackPressed:1793-1821` follows this order:

| Order | Source check | Action |
|---|---|---|
| 1 | `GeneralUtils.isTv(this)` (`:1794`) | Immediate `disconnectAndFinishActivity()` + super — TV branch unchanged. |
| 2 | `Utils.isRdp(this) && inputAreaState != NONE` (`:1803-1807`) | `setInputAreaState(NONE)` to collapse IME / "123" grid. |
| 3 | `Utils.isRdp(this)` (`:1808-1817`) — round 5 | Stamp `lastBackPressForDisconnect` + toast `back_press_to_disconnect`; second press within `DOUBLE_BACK_DISCONNECT_WINDOW_MS = 2000L` calls `disconnectAndFinishActivity`. Press is NOT forwarded to the remote. Timestamp cleared in `onPause:895`. |
| 4 | Non-RDP (`:1818-1820`) | Forward `KEYCODE_BACK` to `inputListener.onKey(...)`. Unchanged. |

The toast string is `back_press_to_disconnect` (`bVNC/src/main/res/values/strings.xml:83`). The round-5 disconnect flow requires both (a) the manifest opt-out `android:enableOnBackInvokedCallback="false"` on `<application>` (`bVNC/src/main/AndroidManifest.xml:29`) so the framework dispatches BACK to `Activity.onBackPressed`, AND (b) the canvas View's `OnKeyListener` (`RemoteClientsInputListener.kt:60-62`) returning `false` for `KEYCODE_BACK` on RDP (and TV) so the press falls through to the Activity instead of being forwarded to the remote as `VK_ESCAPE`; see ADR-0002 and `features/INPUT_PIPELINE.md` §4.3.1, §4.3.6. Without the flag, the predictive-back dispatcher swallows the press before `onBackPressed` runs; without the listener pass-through, the View consumes BACK even when the framework would otherwise dispatch it.

---

## 7. Dialogs

`bVNC/src/main/java/com/iiordanov/bVNC/dialogs/`:

| Dialog | Role |
|---|---|
| `GetTextFragment` | Generic text prompt |
| `IntroTextDialog` | First-run / changelog |
| `ImportExportDialog` | Backup/restore connections (uses `SqliteElement` from `:common`) |
| `RateOrShareFragment` | App rating / share |
| `DiscoveryBottomSheet` | LAN server discovery |
| `MorpheuslyBottomSheet` | Morpheusly integration entry point |
| `DefaultSettingsBottomSheet` | Default-connection template editor (operates on `INVISIBLE` row) |
| `MetaKeyDialog` | Sticky-modifier + named-keys preset dialog (see `features/INPUT_PIPELINE.md` §4.8) |
| `NetworkDiscovery` | LAN scan |

---

## 8. Hot spots for UI shell work

| Touch | Why |
|---|---|
| `bVNC/src/main/res/layout/main_rdp.xml` + `bVNC/src/main/java/com/iiordanov/bVNC/aRDP.java` | aRDP bookmark editor |
| `bVNC/src/main/java/com/iiordanov/bVNC/MainConfiguration.java` | Editor base, shared with bVNC / aSPICE |
| `bVNC/src/main/java/com/undatech/opaque/ConnectionGridActivity.java` | Launcher home |
| `bVNC/src/main/java/com/iiordanov/bVNC/GlobalPreferencesFragment.java` + `bVNC/src/main/res/xml/global_preferences*.xml` | Global prefs UI |
| `bVNC/src/main/java/com/iiordanov/bVNC/Utils.java` | Flavor detection, SharedPreferences I/O, dialogs |
| `bVNC/src/main/java/com/iiordanov/bVNC/Database.java` + `AbstractConnectionBean.java` | Encrypted DB + per-connection columns |
| `bVNC/src/main/res/layout/canvas.xml` + `canvasactivitymenu.xml` | Canvas toolbar/sidebar |
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java` | Canvas host activity |
| `aRDP-app/src/main/AndroidManifest.xml` | The only meaningful file in the wrapper |

---

## 9. Where this doc ties into others

- For the toolbar buttons / menu items that drive input behavior → `features/INPUT_PIPELINE.md` §6.
- For the DB-backed per-connection settings feeding native `LibFreeRDP.setConnectionInfo` arguments → `features/NATIVE_BRIDGE.md` §4.
- For the canonical "what you must NOT do" rule list (no Compose, etc.) → `DESIGN_PRINCIPLES.md`.
- For the multi-module / flavor strategy → `DECISIONS/ADR-0001-multi-module-flavor-strategy.md`.
