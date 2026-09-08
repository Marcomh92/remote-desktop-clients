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

> **All four wrappers fail today** with `'run-locked.bat' is not recognized` because `run-locked.bat` is not in the repo root. Tracked as `known_issues/BUG-001`. Workaround: invoke `gradlew.bat` directly with the same flags:
> - Build: `gradlew.bat assembleDebug --no-daemon --console=plain --quiet --warning-mode none`
> - All `:common` tests: `gradlew.bat :common:testDebugUnitTest --no-daemon --console=plain --quiet --warning-mode none`
> - Single class / package: same `:common:testDebugUnitTest` task with `--tests "<spec>"`.

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
| **RDP-only modifier UX** (`ModifierRowView`, `RdpModifierRowHandler`, `RdpExtraGridPanel`, `InputAreaState`, `RemoteCanvasActivity.setInputAreaState`) | Pure-logic extraction was deliberately skipped as low value; the interesting behavior is in the Android View lifecycle + listener firing order. | Manual smoke. Cover: tap → one-shot, double-tap → lock, tap when locked → OFF, `123` → grid, IME-hide while in `EXTRA` (grid survives), back-press collapses to NONE, modifier reset on disconnect. |
| **RDP-only touchpad gestures** (`TouchInputHandlerTouchpad.setRdp(true)` branch: fling, long-press=right-click, double-tap-hold=drag) | Touch event sequencing is not expressible as plain JUnit. | Manual smoke on a real device. Cover: fling damps and stops cleanly at the noise floor; long-press releases at the press position; double-tap-and-hold commits to drag at the 180 ms boundary. |
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
