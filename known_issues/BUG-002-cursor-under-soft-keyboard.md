# BUG-002 — Cursor travels underneath the soft keyboard instead of the viewport panning to follow it

**Status: ROOT CAUSE IDENTIFIED, FIX LANDED 2026-09-08 (uncommitted; verify on device).**

## Summary

On the aRDP flavor, when the soft keyboard is shown, dragging the cursor past the
bottom of the visible viewport causes the cursor to keep moving in framebuffer
coordinates — but the canvas does NOT pan to keep the cursor visible. The cursor
ends up underneath the keyboard with no visual feedback.

The viewport *itself* is correctly shrunk (`setVisibleDesktopHeight: 3216 -> 1886`
fires when the IME comes up; `recomputeRdpViewport` reports the right `usableH`).
The bug is that the pan function is silently no-op'ing because of an over-eager
early-exit that does not account for the IME shrinking `visibleDesktopHeight`
below the framebuffer height. The "Mouse @" (`itemCenterMouse`) action works
correctly because it bypasses the pan function and computes the target directly
from `getVisibleDesktopHeight()`.

## Root cause

`RemoteCanvas.movePanToMakePointerVisible()` had this early-exit at
`RemoteCanvas.java:546-547` (pre-fix):

```java
// We only pan if the current scaling is able to pan.
if (canvasZoomer != null && !canvasZoomer.isAbleToPan())
    return;
```

`AbstractScaling.isAbleToPan()` is a hard-coded constant per scaling subclass
(`bVNC/.../AbstractScaling.java:122` declares it abstract; the three concrete
classes are `ZoomScaling:57`, `OneToOneScaling:60`, `FitToScreenScaling:61`).
`FitToScreenScaling.isAbleToPan()` is `return false;` — it does **not** inspect
`fbDim`, `visibleDesktopHeight`, the zoom factor, or any runtime dimension.

When the surface perfectly fits the desktop (e.g. 1440×3216 surface ==
1440×3216 desktop, which is the common aRDP case on modern phones), the user
is naturally in `FitToScreenScaling` mode. The `isAbleToPan()` gate then
short-circuits the function — even though the IME has shrunk
`visibleDesktopHeight` from 3216 to 1886 and the framebuffer is now taller
than the visible area. The dimension gate *inside* the function
(`RemoteCanvas.java:534-543`) is correct: it computes
`panX/panY = fbDim < visDim ? false : true` and the post-IME values
(`fbH=3216 > visH=1886`) would yield `panY=true`. But that gate never runs
because the earlier `isAbleToPan()` check returns first.

This also explains why the diagnostic log emitted `movePanToMakePointerVisible`
zero times across a 24-second reproduction: the function returned silently
before the `Log.d` statement was reached.

The touch handlers (`TouchInputHandlerTouchpad.onScroll:195`,
`TouchInputHandlerDirectDragPan.onScroll:125`, `TouchInputHandlerGeneric`
at 11 sites) all *do* call `viewable.movePanToMakePointerVisible()` after
each pointer event. They were correctly routing through the function; the
function was the broken link.

## Location

| File | Line | What is there |
|---|---|---|
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java` | `534-543` | Dimension gate: `panX/panY = fbDim < visDim ? false : true`. This is the *correct* gate; it sets the pan direction flags based on the relationship between framebuffer and visible-desktop dimensions. |
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java` | `546-547` (pre-fix) | **Bug**: early-exit `if (canvasZoomer != null && !canvasZoomer.isAbleToPan()) return;`. The `isAbleToPan()` constant returns false in fit-to-screen mode and silently no-ops the function regardless of whether the IME has shrunk the visible area. |
| `bVNC/.../AbstractScaling.java` | `118-122` | Abstract `isAbleToPan()`. |
| `bVNC/.../FitToScreenScaling.java` | `61-64` | `return false;` — the constant that blocks the pan. |
| `bVNC/.../ZoomScaling.java` | `57-60` | `return true;`. |
| `bVNC/.../OneToOneScaling.java` | `60-63` | `return true;`. |
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java` | `315-348` | RDP-only `ViewCompat.setOnApplyWindowInsetsListener` on `canvasLayout`. Drives `InputAreaState` and `recomputeRdpViewport`. |
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java` | `1641-1756` | `recomputeRdpViewport()` — single RDP viewport owner; sets `canvas.setVisibleDesktopHeight(usable)` where `usable = rdpFullViewHeight - ime - container`. This was already correct. |
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java` | `471-618` | `relayoutViews()` — the legacy onGlobalLayout path. The legacy `setVisibleDesktopHeight + relativePan` shrink block is RDP-gated out at `:514-517`. |
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java` | `1347-1382` | `itemCenterMouse` handler — `movePointer(absX + visW/2, absY + visH/2)`. Was already keyboard-aware (uses `getVisibleDesktopHeight()` directly). |
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java` | `872-892` | `setVisibleDesktopHeight` / `getVisibleDesktopHeight` / `setRdpFullViewHeight` — the actual stored values. |
| `bVNC/.../input/TouchInputHandlerTouchpad.java` | `195` | `viewable.movePanToMakePointerVisible()` after each `moveMouse`. |
| `bVNC/.../input/TouchInputHandlerDirectDragPan.java` | `125` | Same pattern. |
| `bVNC/.../input/TouchInputHandlerGeneric.java` | 11 sites | Same pattern (after every `*Down` / `releaseButton` / `moveMouse*`). |
| `remoteClientLib/.../Viewable.java` | `269` | Interface declaration `void movePanToMakePointerVisible();`. |

## Current vs Expected

**Current state (pre-fix)** — with the keyboard up, dragging the cursor toward the
bottom of the viewport (e.g. scrolling a long page) the cursor travels *past*
the visible area in framebuffer coordinates. The canvas pan does not move, so
the cursor visually goes under the keyboard.

**Expected state (post-fix)** — when the cursor Y (in framebuffer coords) would
land inside the IME-occluded region (i.e. `pointerY >= absoluteYPosition +
visibleDesktopHeight`), the viewport pans upward so the cursor stays visible —
same behavior the Microsoft RDP Android app exhibits.

## Diagnostic logging (landed)

A dedicated log tag `RdpViewport` was added to make this bug observable from
logcat. The dedup in each emitter is per-field-change so the stream stays
readable; cursor movement is throttled to a minimum delta of 8 framebuffer
pixels in `RemotePointer` and 16 px in `RemoteCanvas.movePanToMakePointerVisible`.

Filter with `adb logcat -s RdpViewport:V` or, in Android Studio logcat,
`package:com.iiordanov.aRDP & (tag:RdpViewport | tag:RemoteCanvasActivity | tag:RemoteCanvas | tag:RemotePointer)`.

| Site | File:line | What gets logged |
|---|---|---|
| IME insets listener | `RemoteCanvasActivity.java:~344` | `imeInsets: rawIme=... guardMax=... prevLastImeHeightPx=... prevInputArea=...` — captures the bogus-IME guard threshold and the previous height / state. |
| `recomputeRdpViewport` | `RemoteCanvasActivity.java:~1750` | `recomputeRdpViewport \| canvasW=... canvasH=... rdpFullH=... imeH=... containerH=... usableH=... visibleDesktopH=... inputArea=... softKbdUp=... ptrX=... ptrY=...` — consolidated viewport state. **Post-fix**: `visibleDesktopH` now correctly reflects the IME-shrunk value (was previously reporting `canvasH/zoom`, see "Logging fix" below). |
| `setInputAreaState` | `RemoteCanvasActivity.java:~1683` | `inputAreaState: NONE -> KEYBOARD (imeH=... softKbdUp=...)` — the RDP input-area state machine transition. |
| `relayoutViews` softKbd detection | `RemoteCanvasActivity.java:~588`, `:601` | `relayoutViews.softKbd: true -> false` / `false -> true` — the legacy 19% heuristic, in parallel with the IME insets listener. |
| `itemCenterMouse` (Mouse @) | `RemoteCanvasActivity.java:~1360-1380` | `itemCenterMouse: before` + `itemCenterMouse: after` — captures the visible area, target, ime, and landsInVisibleY flag. |
| `setVisibleDesktopHeight` | `RemoteCanvas.java:~907` | `setVisibleDesktopHeight: prev -> new (canvasH=... rdpFullH=...)` — on change. |
| `setRdpFullViewHeight` | `RemoteCanvas.java:~922` | `setRdpFullViewHeight: prev -> new (canvasH=... visibleH=...)` — on change. |
| `movePanToMakePointerVisible` | `RemoteCanvas.java:~590` | `movePanToMakePointerVisible ptr=(...) abs=(...) visW=... visH=... panX=... panY=... panned=... newX=... newY=... cursorInVisibleX=... cursorInVisibleY=...` — full pan decision. **Post-fix**: this line fires on every cursor Y change of ≥16 px (or on a real pan / gate-close). Pre-fix, it never fired because the early-exit returned first. |
| `setNewPointerPosition` | `remoteClientLib/.../RemotePointer.java:~387` | `setNewPointerPosition: (prevX,prevY) -> (ptrX,ptrY) delta=(dx,dy) abs=(...) vis=(WxH) visRangeY=[top..bot) inVisX=... inVisY=... fb=(WxH)` — throttled to ≥8 px delta. |
| `movePointer` (called by itemCenterMouse) | `remoteClientLib/.../RemotePointer.java:~129` | `movePointer: (prevX,prevY) -> (x,y) visibleW=... visibleH=... absX=... absY=...`. |
| `movePointerToMakeVisible` | `remoteClientLib/.../RemotePointer.java:~160` | `movePointerToMakeVisible: off-screen ptr=... abs=... vis=... -> center=...`. |
| `setY` | `remoteClientLib/.../RemotePointer.java:~113` | `setY: prev -> newY (delta=..., X=...)` — throttled to ≥8 px delta. |

## Fix (landed)

### `RemoteCanvas.movePanToMakePointerVisible` — remove the `isAbleToPan()` early-exit

The dimension gate at the top of the function (lines 534-543) already does
the right thing — it sets `panX`/`panY` to `false` whenever the framebuffer
dimension is smaller than the visible-desktop dimension, so panning is
geometrically a no-op in those directions. The earlier `isAbleToPan()` check
was redundant at best (correct in non-fit-to-screen modes) and buggy in
fit-to-screen mode (where the constant `false` blocked the pan even when
the IME had made the visible area smaller than the framebuffer).

The two-line early-exit was replaced with a comment explaining why the
function relies on the dimension gate alone.

### Logging fix: `RemoteCanvasActivity.logViewportState` use `getVisibleDesktopHeight()`

The diagnostic helper at `RemoteCanvasActivity.java:1764-1766` was computing
`visibleH` as `(int)(canvas.getHeight() / canvas.getZoomFactor())`, which at
zoom=1.0 returns `canvasH` (3216 in the reproduction) — i.e. it ignored the
stored `visibleHeight` field that the IME insets listener just set to 1886.
So `recomputeRdpViewport | ... visibleDesktopH=3216` was being logged while
the actual stored value was 1886. The `setVisibleDesktopHeight: 3216 -> 1886`
line one row above captured the right value, so the bug was visible but the
log line was confusing.

Fix: replace the local computation with `canvas.getVisibleDesktopHeight()`
which reads the stored `visibleHeight` field and divides by zoom.

### Files touched

- `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java` — remove the early-exit (and add an explanatory comment), add the 16-px noise floor to the `movePanToMakePointerVisible` log gate.
- `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java` — fix the `visibleH` computation in `logViewportState`.

Build: `gradlew.bat assembleDebug` exits 0. Tests: `:common:testDebugUnitTest` exits 0 (the only module with unit tests; this fix doesn't affect `:common`).

## Repro / how to read the logs

1. Build: `gradlew.bat assembleDebug --no-daemon --console=plain --quiet --warning-mode none` (or run `.\compile.bat` once the `run-with-lock.ps1` regression of BUG-001 is resolved).
2. Install on a device.
3. Connect to an RDP server. Bring up the software keyboard.
4. Run `adb logcat -c && adb logcat -s RdpViewport:V` (or use the Android Studio filter from above).
5. Reproduce: drag the cursor to the bottom of the viewport.
6. Look for:
   - `imeInsets: rawIme=...` firing on keyboard show / hide.
   - `recomputeRdpViewport | ... rdpFullH=3216 imeH=1162 usableH=1886 visibleDesktopH=1886 ...` (post-fix: `visibleDesktopH=1886` matches the stored value, not `3216`).
   - `setVisibleDesktopHeight: 3216 -> 1886` firing on IME show.
   - `movePanToMakePointerVisible panX=false panY=true panned=true ... newY=1325 ...` firing as the cursor crosses past `visH=1886` and the viewport scrolls down by the cursor's overshoot.
   - `setNewPointerPosition: ... inVisY=true` after the pan — the cursor should now be back inside the visible area.
   - `itemCenterMouse: after landsInVisibleY=true imeOverlap=1162` — "Mouse @" already worked pre-fix and continues to work post-fix.

## Impact

- **Severity: Medium.** Functionality gap on aRDP only; VNC / SPICE / Opaque flavors are unaffected. The cursor did travel under the keyboard. No data loss; no crash.
- **Behavioral surface:** RDP only. INV-019 (gate all RDP-only UX behind `Utils.isRdp(this)`) is preserved.
- **Scope of fix:** one production line removed in `RemoteCanvas`, one computation corrected in `RemoteCanvasActivity`. No interface change. No API change. Other scaling modes (`OneToOneScaling`, `ZoomScaling`) are unaffected because their `isAbleToPan()` already returns `true`.

## Testing

After the fix, verify in logcat:

1. Keyboard show → `imeInsets: rawIme=1162` fires once.
2. `recomputeRdpViewport` line shows `usableH=1886 < rdpFullH=3216` (i.e. shrunk) and `imeH=1162 > 0`, and `visibleDesktopH=1886` (matches the stored value).
3. `setVisibleDesktopHeight: 3216 -> 1886` fires.
4. Cursor drag down: `setNewPointerPosition: ... inVisY=true` until the cursor crosses `visH=1886`. Then a `movePanToMakePointerVisible panY=true panned=true newY=1325` line that moves `absoluteYPosition` up; subsequent `setNewPointerPosition: inVisY=true` showing the cursor is back in view.
5. Continue dragging: the viewport follows the cursor (each new cursor position triggers a fresh `movePanToMakePointerVisible panned=true` with the cursor near the bottom of the visible area).
6. Keyboard hide → `recomputeRdpViewport` line shows `usableH=3216 == rdpFullH` and `setVisibleDesktopHeight: 1886 -> 3216` fires; viewport scrolls back to show the top of the desktop.
