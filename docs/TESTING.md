# TESTING.md

> Read first: `MASTER.md` (overview), `ARCHITECTURE.md` §1 (module graph). Tests live almost entirely in `:common`. The rest of the codebase has no unit tests and minimal manual smoke coverage.

---

## 1. Where tests live

| Module | `src/test/` (JVM unit) | `src/androidTest/` (instrumented) |
|---|---|---|
| `:common` | Yes — JUnit 4 (`junit:junit:4.13.2`) | Yes — `androidx.test.ext:junit:1.1.3`, `espresso-core:3.4.0`, runner `androidx.test.runner.AndroidJUnitRunner` |
| `:bVNC` | No | No |
| `:remoteClientLib` | No | No |
| `:remoteClientLib:...:freeRDPCore` | No | No |
| `:pubkeyGenerator` | No | No |
| `:*-app` | No | No |

**To enumerate test files**: `gradle :common:dependencies --configuration testRuntimeClasspath` and list `common/src/test/java/...`.

---

## 2. How to run

Wrapper scripts in the repo root:

| Command | Effect |
|---|---|
| `.\test-all.bat` | Runs all `:common` tests once. Prints `All tests passed` on success. |
| `.\test-class.bat "com.example.MyTest"` | Runs a single class. Prints `Tests passed`. |
| `.\test-package.bat "com.example.*"` | Runs all tests in a package. Optional second arg picks the module if a name collides across modules. |

> All four wrappers `call run-locked.bat` (paired with `run-with-lock.ps1`) as their first line; that script serializes builds via a `PowerShell` file lock and forwards the rest of the arguments to `gradlew.bat`. Wrappers work as documented.

AGENTS.md mentions `compile.bat` and a `TestListener` in `app/build.gradle.kts`. **Neither exists on disk.** Use `gradlew.bat assembleDebug` directly; the test scripts manage their own output.

---

## 3. What `:common` covers

`:common` holds encrypted-DB helpers, `SqliteElement`, and small utilities used by `:bVNC`'s import/export flow. Test coverage is concentrated here because that was where pure logic lived. Tests are plain JUnit 4 (no Mockito, no Robolectric by default — though the `androidTest` config supports it).

**To extend tests:** add new tests in `common/src/test/java/.../<YourClass>Test.java`. Use `junit:junit:4.13.2`. Don't introduce new test dependencies without checking that Gradle can resolve them offline.

---

## 4. What is NOT tested (and why this matters)

| Area | Why no tests | Practical alternative |
|---|---|---|
| **Input pipeline** (`RemoteRdpKeyboard`, `RemotePointer`, `TouchInputHandler*`, `RemoteExtraKeysHandler`) | Depends on Android `KeyEvent`, `MotionEvent`, hardware-key timing, IME `BaseInputConnection`. Not expressible as plain JUnit. | Manual smoke on a real device. Plan via `gitnexus_impact` before touching; instrument `App.debugLog` (boolean in `App.java`) when running. |
| **RDP-only modifier UX** (`ModifierRowView`, `RdpModifierRowHandler`, `RdpExtraGridPanel`, `InputAreaState`, `RemoteCanvasActivity.setInputAreaState`) | Pure-logic extraction was deliberately skipped as low value; the interesting behavior is in the Android View lifecycle + listener firing order. | Manual smoke. Cover: tap → one-shot, double-tap → lock, tap when locked → OFF, `123` → grid, IME-hide while in `EXTRA` (grid survives), back-press collapses to NONE, modifier reset on disconnect. **Round 5 additions**: row height visually shrinks to 27 dp (no longer crowding the IME); per-button width 42 dp + 12 sp text fits all 8 keys without ellipsis; "123" rest state matches Del/Esc/Tab palette (not teal); tapping `123` opens the grid with the teal `COLOR_TOGGLE_ON_BG/FG` highlight and collapses the rest. |
| **"123" extra-keys grid render** (round 5: `RdpExtraGridPanel.init:93-95` `MATCH_PARENT × MATCH_PARENT` insert) | GridLayout measurement happens on a background JNI drawable; the empty-slab bug only surfaces once a frame is allocated. | Manual smoke. Cover: open `EXTRA` (tap `123`); grid renders 24 buttons in three 8-column rows (ESC/F1-F6/DEL, TAB/F7-F12/BKSP, HOME/END/PGUP/PGDN/INS/LEFT/DOWN/RIGHT) with no empty area, no translucent slab, full 192 dp height; tap any F-key and confirm it reaches the remote; tap the backspace / arrow keys; toggle back to KEYBOARD and confirm the grid hides cleanly. |
| **Double back-press disconnects** (round 5: `RemoteCanvasActivity.onBackPressed:1785-1794`) | Time-window logic is per-instance; `SystemClock.uptimeMillis()` is non-deterministic in tests. | Manual smoke. Cover: from a connected session with no IME visible, first back-press shows the "Press back again to disconnect" toast and does NOT forward to the remote; second back-press within 2 s disconnects the session; waiting > 2 s and pressing back again re-arms (first press is again a no-op with a fresh toast). Confirm `Utils.isRdp(this)` gating: open bVNC / aSPICE / Opaque, press back, and confirm it still forwards as KEYCODE_BACK. Confirm TV devices still immediately disconnect on the first press. |
| **Viewport follows the cursor during RDP touchpad fling** (round 5: `TouchInputHandlerTouchpad.Flinger.run:667`) | Per-tick viewport pan relies on the same `movePanToMakePointerVisible` gate the drag path uses; verifying it only on a fling is a small delta over the existing drag path. | Manual smoke on a real device. Cover: open the IME so the canvas shrinks; perform a flick in touchpad mode; confirm the viewport pans to keep the flung cursor visible while the IME stays open, and the fling still stops at the noise floor / edge clamp. Confirm the fling doesn't pan when the cursor is already inside the visible area (no jitter). |
| **Floating keyboard-toggle button visual** (round 5: restyle — `canvas.xml:154-165` + new `bg_keyboard_toggle.xml`) | Static layout / drawable change — visual only. | Manual smoke. Cover: on aRDP, confirm the button shows up as a 40 dp × 40 dp rounded-rect (13 dp corners, 25 % black) with the hamburger icon (Material `ic_baseline_menu_48`); drag-vs-tap still works (drag moves the button, tap cycles `NONE → KEYBOARD → EXTRA`); confirm bVNC / aSPICE / Opaque do NOT show the button (gating intact). |
| **Pinch-to-zoom clamp** (round 5 — verified no change: `ZoomScaling.changeZoom:131-144`) | Already covered by `ZoomScaling` / `TouchInputHandlerGeneric`; round 5 just verified the clamp is in place. | Manual smoke. Cover: with a fresh RDP connection (default `ScaleType.MATRIX`), pinch out — the floor is the cover scale for the current `rdpFullViewHeight`; pinch in — the cap is `4.0`; rotating the device re-captures `rdpFullViewHeight` so the floor shifts with the new orientation. Caveat: a user override to `FIT_CENTER` / `CENTER` makes pinch a no-op (pre-existing, unrelated). |
| **RDP-only touchpad gestures** (`TouchInputHandlerTouchpad.setRdp(true)` branch: fling, long-press=right-click, **adaptive** double-tap=press-and-drag-or-double-click) | Touch event sequencing is not expressible as plain JUnit. | Manual smoke on a real device. Cover: fling damps and stops cleanly at the noise floor; long-press produces a right click while the finger is still down (no held-button-until-lift); adaptive double-tap — quick lift yields a true double-click (2 click pairs), held-and-drag yields a press-and-drag from the tap point with NO preceding click (Windows must not see a DBLCLK on the 2nd DOWN, otherwise title bars maximize); fling is suppressed while the double-tap is `PENDING` or `DRAGGING`; `ACTION_CANCEL` releases the held button; long-press clears the double-tap state (mutual exclusion). |
| **RDP-only viewport + IME math** (round 3: `recomputeRdpViewport`, the IME insets listener, `rdpFullViewHeight`, cover-scale floor) | View tree / window-resize behaviour is not expressible as plain JUnit. | Manual smoke on at least one device where the window does NOT resize when the IME opens (the round-2 bug surfaced there). Cover: open the IME — canvas shrinks so the IME never overlaps desktop content; the modifier row sits above the IME with no off-screen translation; the cover-scale zoom floor still lets the cursor-follow pan reach the hidden area; closing the IME restores the full canvas; rotating the device re-captures `rdpFullViewHeight` correctly. Confirm the legacy shrink path is untouched on bVNC / aSPICE / Opaque. |
| **Native bridge** (`RdpCommunicator`, `LibFreeRDP`, `SpiceCommunicator`) | Calls into JNI. Requires a connected server. | CI is not set up. Manual test plans live informally in `known_issues/*.md`. |
| **Activities** (`RemoteCanvasActivity`, `ConnectionGridActivity`, `MetaKeyDialog`) | UI tests would need `androidx.test.espresso` per Activity. Setup not done. | Manual smoke. |
| **Database migrations** (`Database.onUpgrade`) | Real schema migrations require a populated DB; not unit-tested. | Backwards-compat is verified when releasing; see `Database.java:283-596`. |
| **`RemoteKeyboardState.detectHardwareMetaState`** | Heuristic that maps raw scancodes + keycodes to modifier masks. | Manual test plan on a hardware keyboard. Capture with `App.debugLog = true`. |

---

## 5. The `App.debugLog` toggle

`App.debugLog` is a single boolean that several classes read to enable verbose input-pipeline logging. To turn on locally:

1. `adb shell run-as com.iiordanov.bvnc sh -c 'mkdir -p /data/data/com.iiordanov.bvnc/shared_prefs && cat > /data/data/com.iiordanov.bvnc/shared_prefs/generalSettings.xml' <<EOF`
2. Insert `<boolean name="debugLog" value="true" />` (the actual key name varies — grep for `debugLog` in `Constants.java` to confirm).

Or simply add it programmatically in `App.onCreate` for a debug build.

Classes that respect the flag:
- `RemoteRdpKeyboard.processLocalKeyEvent:37`
- `RemotePointer.*` (via `GeneralUtils.debugLog`)
- `RdpCommunicator.writeKeyEvent`, `processVirtualKey`, `processUnicodeKey`
- `MetaKeyDialog.onKeyDown/Up`

---

## 6. What about tests in the wrappers?

Wrapper modules (`*app`) have no tests. The thinness of these modules (manifest only) means there is no logic to test there.

---

## 7. Pre-commit sanity check

Before opening a PR, run:

1. `.\test-all.bat` — `:common` must be green.
2. Manual smoke if you touched input handling — see §4 alternative for "input pipeline" rows.
3. `gitnexus_detect_changes` — required by AGENTS.md.

---

## 8. Open items (gaps)

- No CI integration; tests run only when someone runs the bat script.
- No instrumentation tests for `RemoteCanvasActivity`. Adding one would require `androidx.test.espresso` setup in the `:bVNC` module.
- DB migration tests are missing. A test that constructs each `DBV_*` schema and runs `onUpgrade` to the current version would be a high-value addition.
