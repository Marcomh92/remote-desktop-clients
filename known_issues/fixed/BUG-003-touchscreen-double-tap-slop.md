# BUG-003: Touchscreen double-tap is unreliable due to Android stock GestureDetector slop rejection

## Summary

On real touchscreens, double-tap gestures (e.g. select-word-in-text-doc, maximize-window-by-double-clicking-the-title-bar) frequently fail to register as double-clicks at the remote server. On a virtual Android device driven by a physical mouse — where there is zero drift between the two taps — the same gesture always works.

## Location

| Path | Why it matters |
|---|---|
| `bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerGeneric.java:125` | Constructs a stock `GestureDetector` with default `ViewConfiguration` thresholds. |
| `bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerGeneric.java:335` (`onDoubleTap`) | Fires two raw clicks immediately. All non-touchpad modes (DirectDragPan, DirectSwipePan) and non-RDP touchpad sessions route here. Vulnerable. |
| `bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerTouchpad.java:358` (RDP-only `onDoubleTap`) | Has an adaptive double-tap state machine that handles "second-tap-then-drag-vs-click", but it is gated by the same stock slop check — if stock rejects the pair, the adaptive path is never entered. |

## Current vs Expected Code

**Current:** Stock `GestureDetector` rejects the second tap when it drifts more than `ViewConfiguration.getScaledDoubleTapSlop()` (~8 dp on most devices) from the first tap's UP. A real-finger double-tap routinely drifts > 8 dp; the system reads it as two single taps (two `onSingleTapConfirmed` events). The remote sees two single clicks → caret placement + caret placement, instead of one double-click → word selection / window maximize.

**Expected:** Two taps within a relaxed slop and within the double-tap timeout should register as one double-click at the server.

## Impact

- **Severity:** Medium — functional gap that affects every touchscreen user across all input modes (touchpad, direct drag pan, direct swipe pan) and all protocols (RDP, VNC, SPICE, Opaque).
- **Reproduction:** On a physical touchscreen device, double-tap a word in a remote text editor or a window title bar. The bug repros when the second tap's down position drifts > 8 dp from the first tap's up position. Frequency depends on finger steadiness; observed majority of attempts on a phone-sized screen.
- **Non-reproduction:** A virtual Android device driven by a physical mouse (zero drift) does not repro, because the mouse keeps the second tap within `getScaledDoubleTapSlop()`.

## Fix

In `TouchInputHandlerGeneric` (base class for all input modes):

1. Buffer every `onSingleTapUp`. If a second `onSingleTapUp` arrives within `ViewConfiguration.getDoubleTapTimeout()` (or the user's configured timeout) and within a **relaxed slop (~24 dp default, 3× stock)**, treat it as a double-tap and call a new `protected void notifyDoubleTap(MotionEvent e)` hook.
2. `TouchInputHandlerTouchpad`'s existing adaptive state machine for RDP is untouched — it still runs when stock accepts the pair (which means the adaptive state machine still handles drag-vs-click on MOVE/UP). The new manual detector only fires for relaxed-slop pairs that stock rejected.
3. Suppress the stock detector's own `onDoubleTap` callback from double-emitting when the manual detector fired (tracked via a `stockDoubleTapFired` flag set in our `onDoubleTap` override).

New global preferences (SharedPreferences, set via `SeekBarPreference` in `global_preferences.xml`, same pattern as `flingResistance` / `touchpadSensitivity`):

| Key | Default | Range | Meaning |
|---|---|---|---|
| `doubleTapSlopDp` | 24 dp | 4–48 dp | Max distance between the two taps' UPs. |
| `doubleTapTimeoutMs` | 300 ms | 100–800 ms | Max time between the two taps' UPs. |

Defaults match the user's "sensible defaults that likely fix the issue" answer.

Wired via two new methods on `TouchInputDelegate` (`getDoubleTapSlopDp()` / `getDoubleTapTimeoutMs()`), implemented in `RemoteCanvasActivity` via `Utils.querySharedPreferencesInt`.

## Testing

Manual verification on device:

1. Open a remote desktop (RDP/VNC/SPICE). Double-tap on a word in a text editor. Verify the word is selected (single double-click → one double-click at server).
2. Double-tap a window title bar. Verify the window maximizes.
3. Single-tap a folder icon. Verify only one click fires (no spurious double-click).
4. Tap, drag, lift (single-finger scroll). Verify scroll works and no double-click fires.
5. Triple-tap on a word. Verify double-click + single-click (3 clicks total), not 4.
6. Open global preferences → adjust slop slider. Re-test step 1 with both minimum and maximum values.

Build verification:

- `compile.bat` succeeds.
- Existing test suites still pass (none exercise touch input; only `common` has unit tests per AGENTS.md).

## Status

**FIXED 2026-09-10** — implementation landed across 9 files:

| File | Change |
|---|---|
| `bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerGeneric.java` | Core fix: new `notifyDoubleTap(MotionEvent)` hook, `onSingleTapUp` override that detects a relaxed-slop double-tap when stock has rejected the pair, refactored `onDoubleTap` to track the `stockDoubleTapFired` flag and clear the buffer, `onSingleTapConfirmed` override that suppresses a stale single-click after a manual double-tap. Setters `setDoubleTapSlopPx` / `setDoubleTapTimeoutMs` (also exposed via the `TouchInputHandler` interface) push mid-session pref changes. Floors slop at 8 dp (stock) and timeout at 100 ms so sliders cannot disable the manual detector. |
| `bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandlerTouchpad.java` | Defensive: RDP `onDoubleTap` now also sets `stockDoubleTapFired = true` and clears the buffer, so a future Android `GestureDetector` that routes UP2 through `onSingleTapUp` (instead of `onDoubleTapEvent`) cannot make the base class fire on top of the state machine. |
| `bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputHandler.java` | Two new interface methods so `RemoteCanvasActivity` can dispatch the setters via the `TouchInputHandler[]` array type. |
| `bVNC/src/main/java/com/iiordanov/bVNC/input/TouchInputDelegate.java` | Two new methods (`getDoubleTapSlopDp()`, `getDoubleTapTimeoutMs()`). |
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java` | Implements the two delegate methods via `Utils.querySharedPreferencesInt`; pushes the values to the active handler in `onResume` and to every pre-built input-mode handler in `getInputHandlerById` (matches the existing `setFlingDamp` propagation pattern). |
| `bVNC/src/main/java/com/iiordanov/bVNC/Constants.java` | Two new keys (`doubleTapSlopDp`, `doubleTapTimeoutMs`) and two defaults (`DEFAULT_DOUBLE_TAP_SLOP_DP = 24`, `DEFAULT_DOUBLE_TAP_TIMEOUT_MS = 300`). |
| `bVNC/src/main/res/xml/global_preferences.xml` | Two `SeekBarPreference` entries (slop defaultValue=24, max=48; timeout defaultValue=300, max=800), placed before the "applies to new connections only" category so they apply immediately to all sessions. |
| `bVNC/src/main/res/values/strings.xml` | Labels `double_tap_slop_dp` and `double_tap_timeout_ms`. |

Build verified: `:bVNC:compileDebugJavaWithJavac`, `:aRDP-app:assembleDebug`, `:common:testDebugUnitTest` all green. Reviewer confidence: High after one review-improve cycle addressing Major #1 (mid-session pref propagation) and Minors #1–3 (triple-tap, defensive invariant, slider floor).
