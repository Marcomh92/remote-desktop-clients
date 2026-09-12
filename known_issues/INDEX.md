# Known Issues Index

This directory contains bug reports for known issues in this codebase.

## Active Issues

_None at the moment._


## Closed Issues

| ID                                                                         | Title                                                                                 | Severity | Status | Date fixed |
|----------------------------------------------------------------------------|---------------------------------------------------------------------------------------|----------|--------|------------|
| [BUG-001](fixed/BUG-001-run-locked-bat-missing.md) | `run-locked.bat` was missing from the repo root; all documented build/test wrappers failed with `'run-locked.bat' is not recognized`. | High — breaks all build/test wrapper scripts | FIXED | 2026-09-08 |
| [BUG-002](BUG-002-cursor-under-soft-keyboard.md) | RDP-only: cursor travels underneath the soft keyboard instead of the viewport panning to follow it. Fix landed 2026-09-08; verify on device. | Medium — functional gap on aRDP only | FIXED (2026-09-08) | 2026-09-08 |
| [BUG-003](fixed/BUG-003-touchscreen-double-tap-slop.md) | Touchscreen double-tap rejected by stock `GestureDetector` slop (~8 dp); a real-finger double-tap frequently drifts above that and is misread as two single taps. Fix adds manual double-tap detection with a relaxed slop + two new global preferences (`doubleTapSlopDp`, `doubleTapTimeoutMs`). | Medium — affects every touchscreen user across all input modes and protocols | FIXED | 2026-09-10 |
| [BUG-003 follow-up](fixed/BUG-003-touchscreen-double-tap-slop.md#follow-up-2026-09-12--manual-detector-replaced-original-fix-was-structurally-unreachable) | The round-3 manual detector (buffered `onSingleTapUp` in `TouchInputHandlerGeneric`) was structurally unreachable — stock `GestureDetector` distance-rejects every pair that would have reached `onSingleTapUp`, while the `doubleTapSlopDp` SeekBar capped at 48 dp. Round 9 replaces it with `DoubleTapPairTracker` (raw primary-pointer DOWN/UP in `onTouchEvent`, framework-floored thresholds, fed into `onManualDoubleTap`). Also: `DRAG_THRESHOLD_DP` moved 2 dp → 8 dp because finger jitter was converting intended double-clicks into drags. Device verification still pending. | Medium — same as BUG-003 | FIXED (follow-up, 2026-09-12) | 2026-09-12 |
| [BUG-004](fixed/BUG-004-click-drag-xrdp.md) | Click-and-drag works against Windows RDP but breaks against Linux/xrdp. `RemoteRdpPointer.sendPointerEvent` emitted two PDUs per pointer event, byte-identical whenever `pointerMask` already had the MOVE bit (drag ticks `0x9800,0x9800`; plain moves `0x0800,0x0800`); xrdp treats every BUTTON1 PDU as a click edge and the duplicated down-edges per tick break the X11 grab. Fix: emit `MOVE \| pointerMask` only when it differs from `pointerMask` (`(pointerMask & MOUSE_BUTTON_MOVE) == 0`); press/release/scroll sequences and the INV-011 guard unchanged. RDP-only. Device verification pending. | Medium — functional gap on Linux/xrdp RDP servers | FIXED | 2026-09-12 |


## Severity Legend

- **High:** Compilation failure or critical functional bug
- **Medium:** Functional gap or tests not verifying real behavior
- **Low-Medium:** Validation bypass or data quality issue
- **Low:** Documentation mismatch or API ergonomics issue

---

## How to Use This Directory

1. **Before starting work:** Check if your issue is already documented
2. **When fixing:** Move the bug report to [fixed/](fixed/) and add resolution details
3. **When discovering:** Create a new bug report following the template format

## Bug Report Template

When creating a new bug report, include:
- **Summary:** Brief description of the issue
- **Location:** File path and line numbers
- **Current vs Expected Code:** Show the problem and solution
- **Impact:** Severity, compilation/runtime effects
- **Fix:** Detailed steps to resolve
- **Testing:** How to verify the fix
