# BUG-002 — Cursor travels underneath the soft keyboard instead of the viewport panning to follow it

**Status: FIX LANDED 2026-09-08 (verify on device). Diagnostic logging used to identify the root cause has since been removed at user request.**

## Summary

On the aRDP flavor, when the soft keyboard is shown, dragging the cursor past the
bottom of the visible viewport causes the cursor to keep moving in framebuffer
coordinates — but the canvas does NOT pan to keep the cursor visible. The cursor
ends up underneath the keyboard with no visual feedback.

The viewport *itself* is correctly shrunk (`setVisibleDesktopHeight(3216 -> 1886)`
fires when the IME comes up; `recomputeRdpViewport` reports the right `usableH`).
The bug was that the pan function was silently no-op'ing because of an over-eager
early-exit that did not account for the IME shrinking `visibleDesktopHeight`
below the framebuffer height. The "Mouse @" (`itemCenterMouse`) action worked
correctly because it bypasses the pan function and computes the target directly
from `getVisibleDesktopHeight()`.

## Root cause

`RemoteCanvas.movePanToMakePointerVisible()` had this early-exit:

```java
// We only pan if the current scaling is able to pan.
if (canvasZoomer != null && !canvasZoomer.isAbleToPan())
    return;
```

`AbstractScaling.isAbleToPan()` is a hard-coded constant per scaling subclass
(`AbstractScaling.java:122` declares it abstract; the three concrete classes
are `ZoomScaling:57`, `OneToOneScaling:60`, `FitToScreenScaling:61`).
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

The touch handlers (`TouchInputHandlerTouchpad.onScroll:195`,
`TouchInputHandlerDirectDragPan.onScroll:125`, `TouchInputHandlerGeneric`
at 11 sites) all *do* call `viewable.movePanToMakePointerVisible()` after
each pointer event. They were correctly routing through the function; the
function was the broken link.

## Location

| File | Line | What is there |
|---|---|---|
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java` | `534-543` | Dimension gate: `panX/panY = fbDim < visDim ? false : true`. This is the *correct* gate; it sets the pan direction flags based on the relationship between framebuffer and visible-desktop dimensions. |
| `bVNC/.../AbstractScaling.java` | `118-122` | Abstract `isAbleToPan()`. |
| `bVNC/.../FitToScreenScaling.java` | `61-64` | `return false;` — the constant that blocked the pan (removed in the fix). |
| `bVNC/.../ZoomScaling.java` | `57-60` | `return true;`. |
| `bVNC/.../OneToOneScaling.java` | `60-63` | `return true;`. |
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java` | `315-348` | RDP-only `ViewCompat.setOnApplyWindowInsetsListener` on `canvasLayout`. Drives `InputAreaState` and `recomputeRdpViewport`. |
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java` | `recomputeRdpViewport()` | Single RDP viewport owner; sets `canvas.setVisibleDesktopHeight(usable)` where `usable = rdpFullViewHeight - ime - container`. This was already correct. |
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java` | `471-618` | `relayoutViews()` — the legacy onGlobalLayout path. The legacy `setVisibleDesktopHeight + relativePan` shrink block is RDP-gated out at `:514-517`. |
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java` | `itemCenterMouse` handler | `movePointer(absX + visW/2, absY + visH/2)`. Was already keyboard-aware (uses `getVisibleDesktopHeight()` directly). |
| `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java` | `setVisibleDesktopHeight` / `getVisibleDesktopHeight` / `setRdpFullViewHeight` | The actual stored values. |
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

## Historical: Diagnostic logging (removed)

The root cause was originally identified with the help of a dedicated log tag
`RdpViewport` that was added across every site participating in the cursor /
viewport / IME state machine (IME insets listener, `recomputeRdpViewport`,
`setInputAreaState`, the legacy 19% `relayoutViews` heuristic,
`itemCenterMouse`, `setVisibleDesktopHeight` / `setRdpFullViewHeight` setters,
`movePanToMakePointerVisible`, and the `RemotePointer` paths). Per-field-change
dedup kept the stream readable; cursor movement was throttled to a minimum delta
of 8 framebuffer pixels in `RemotePointer` and 16 px in
`RemoteCanvas.movePanToMakePointerVisible`.

The smoking-gun line was: `movePanToMakePointerVisible` never fired across a
24-second reproduction, because the early-exit returned before the log
statement. Once that was removed, the line fired on every cursor Y change of
≥16 px and the pan followed the cursor correctly.

All `RdpViewport` diagnostic logging has since been removed at user request
("they are quite spammy"). The `logViewportState` helper in
`RemoteCanvasActivity` was removed along with its log lines.

## Fix (landed)

### `RemoteCanvas.movePanToMakePointerVisible` — remove the `isAbleToPan()` early-exit

The dimension gate at the top of the function already does the right thing —
it sets `panX`/`panY` to `false` whenever the framebuffer dimension is smaller
than the visible-desktop dimension, so panning is geometrically a no-op in
those directions. The earlier `isAbleToPan()` check was redundant at best
(correct in non-fit-to-screen modes) and buggy in fit-to-screen mode (where
the constant `false` blocked the pan even when the IME had made the visible
area smaller than the framebuffer).

The two-line early-exit was replaced with a comment explaining why the
function relies on the dimension gate alone.

### Enhancement 1: tighter pan edge threshold (~8dp, density-aware)

`RemoteCanvas.movePanToMakePointerVisible()` previously used
`Constants.H_THRESH` / `Constants.W_THRESH` (50 px) as the edge threshold —
i.e. the viewport only started panning once the cursor was within 50 px of
the visible-area edge. On xxxhdpi panels that's ~16.6dp, which felt coarse:
the cursor had to push visibly against the edge before the viewport
followed.

Fix: replace the px constant with a density-aware `8.0f dp` value, computed
as `(int)(EDGE_THRESH_DP * getResources().getDisplayMetrics().density + 0.5f)`.
At xxxhdpi that's 32 px (still well under `TOP_MARGIN=110` px), at xhdpi
16 px, at mdpi 8 px. The viewport now starts panning as soon as the cursor
is ~8dp from the edge on every density.

The `Constants.H_THRESH` / `Constants.W_THRESH` constants are no longer
referenced from `RemoteCanvas.movePanToMakePointerVisible`. They remain
defined for any future caller; this fix intentionally avoids touching them
to keep the change local.

### Enhancement 2: reset viewport when the visible area expands

When the IME hides, `recomputeRdpViewport()` calls
`canvas.setVisibleDesktopHeight(rdpFull)` (e.g. 1886 → 3216 in the original
logcat). The framebuffer-to-viewport pan that the IME-up fix introduced is
left in place — so the viewport stays scrolled to wherever the cursor was
when the IME came down, and the user sees the bottom of the desktop instead
of the top. This is wrong: hiding the IME should restore the original
top-left viewport.

Fix: in `setVisibleDesktopHeight`, when `newHeight > visibleHeight` AND the
viewport is currently scrolled (`absoluteXPosition != 0 || absoluteYPosition != 0`),
reset to the origin and call `resetScroll()`. The check is gated so:

- Initial setVisibleDesktopHeight(-1 → 3216) is a no-op (viewport already at origin).
- Screen rotation that happens to leave the viewport at the origin is a no-op.
- The actual IME-hide case (1886 → 3216 with the viewport scrolled down) does the reset.

The reset is in `setVisibleDesktopHeight` (not the IME insets listener)
because that method is the single setter for the visible-area height and is
called from both the RDP path (`recomputeRdpViewport()`) and the non-RDP
legacy path (RDP-gated out via `if (!Utils.isRdp(this))`). The non-RDP path
covers VNC/SPICE/Opaque when the extra-keys toolbar is toggled — those cases
will also reset, which is the desired consistent behavior.

## Repro

1. Build: `gradlew.bat assembleDebug --no-daemon --console=plain --quiet --warning-mode none` (or run `.\compile.bat` once the `run-with-lock.ps1` regression of BUG-001 is resolved).
2. Install on a device.
3. Connect to an RDP server. Bring up the software keyboard.
4. Drag the cursor down toward the bottom of the viewport.
5. **Expected (post-fix):** the viewport starts scrolling to follow the cursor as soon as the cursor crosses ~8dp from the bottom edge; the cursor stays visible above the keyboard.
6. **Bug (pre-fix):** the cursor travels past the visible area and visually goes under the keyboard; the viewport does not scroll.
7. Hide the keyboard.
8. **Expected (post-fix):** the viewport resets to the top-left of the desktop; the user sees the full desktop again.

## Impact

- **Severity: Medium.** Functionality gap on aRDP only; VNC / SPICE / Opaque flavors are unaffected. The cursor did travel under the keyboard. No data loss; no crash.
- **Behavioral surface:** RDP only. INV-019 (gate all RDP-only UX behind `Utils.isRdp(this)`) is preserved.
- **Scope of fix:** one production line removed in `RemoteCanvas`, two density-aware behavior changes added (8dp threshold + viewport reset). No interface change. No API change. Other scaling modes (`OneToOneScaling`, `ZoomScaling`) are unaffected because their `isAbleToPan()` already returned `true`.

## Testing

Manual on-device verification on aRDP:

1. Open an RDP session. Confirm the cursor follows the touchpad normally when the keyboard is hidden.
2. Bring up the software keyboard. Drag the cursor down past the middle of the screen toward the bottom of the viewport. **Expected:** the viewport pans down to follow the cursor; the cursor stays in view (≥8dp from the bottom edge of the visible area, never underneath the keyboard).
3. Continue dragging past the previously-visible bottom edge. **Expected:** the viewport continues to scroll down; the cursor stays 8dp from the bottom edge.
4. Hide the keyboard. **Expected:** the viewport snaps back to the top-left of the desktop; the full desktop is visible again.
5. Repeat with the extra-keys toolbar (if visible): toggling the toolbar should also reset the viewport to the top-left.

To check the tighter threshold on its own (no IME): in fit-to-screen mode, drag the cursor toward any edge — the viewport should start panning when the cursor is within roughly 8dp of the edge (much sooner than the previous 50-px threshold on xxxhdpi panels, which was ~16.6dp).

## Files touched

- `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvas.java`:
  - Removed the `isAbleToPan()` early-exit in `movePanToMakePointerVisible`.
  - Added the `EDGE_THRESH_DP = 8.0f` constant and replaced `Constants.H_THRESH` / `Constants.W_THRESH` with the density-aware `EDGE_THRESH_DP * density` computation in `movePanToMakePointerVisible`.
  - Added the viewport-reset branch to `setVisibleDesktopHeight` when `newHeight > visibleHeight` and the viewport is scrolled.
- (Diagnostic-only changes — added in the round that identified the root cause, then removed at user request since they were spammy: 12 `RdpViewport` log sites across `RemoteCanvasActivity`, `RemoteCanvas`, and `RemotePointer`; the `logViewportState` helper; per-field-change dedup fields. These lived in commit `9309b1c3` together with the actual fix.)

Build: `gradlew.bat assembleDebug` exits 0. Tests: `:common:testDebugUnitTest` exits 0.
