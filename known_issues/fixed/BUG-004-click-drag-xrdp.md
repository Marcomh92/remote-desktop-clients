# BUG-004 — Click-and-drag is broken against Linux/xrdp RDP servers

**Status: FIX LANDED 2026-09-12 (verify on user's Linux/xrdp server).**

## Summary

Click-and-drag works correctly against Windows RDP targets but fails against Linux/xrdp targets: a press, drag, release on an xrdp window does not move the remote cursor in a way that registers with the X11 pointer grab, so file-pane moves, text selections, and window drags all silently fail. Plain moves and discrete clicks are unaffected.

## Location

| Path | Why it matters |
|---|---|
| `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpPointer.java:110-139` (`sendPointerEvent`) | Emits one or two `protocomm.writePointerEvent` PDUs per pointer event. The MOVE-flagged PDU was emitted unconditionally, producing byte-identical duplicates whenever `pointerMask` already carried the MOVE bit. |

## Current vs Expected

**Suspected root cause (before fix)** — `sendPointerEvent` always emitted two PDUs:

```java
protocomm.writePointerEvent(pointerX, pointerY, combinedMetaState, MOUSE_BUTTON_MOVE | pointerMask, false);
protocomm.writePointerEvent(pointerX, pointerY, combinedMetaState, pointerMask, false);
```

When `pointerMask` already had `MOUSE_BUTTON_MOVE` set (drag ticks `0x9800`, plain moves `0x0800`), the two writes were byte-identical. RDP/xrdp keep no persistent button state — every `BUTTON1` PDU is treated as a click edge. xrdp's X11 pointer grab therefore saw a duplicated DOWN edge on every drag tick, plus a redundant MOVE per tick, and the X11 grab semantics (passive grab on `ButtonPress`, release on `ButtonRelease`) broke: by the time the user lifted the finger, xrdp had already lost the grab.

| Tick type | Before fix (PDUs emitted) | After fix |
|---|---|---|
| Plain move (`pointerMask = 0x0800`) | two identical `0x0800` PDUs | one `0x0800` PDU |
| Drag tick (`pointerMask = 0x9800`) | two identical `0x9800` PDUs | one `0x9800` PDU |
| Button press (`pointerMask = 0x9000`) | one `0x9800` MOVE+down + one `0x9000` down (intentional pair) | one `0x9800` + one `0x9000` (unchanged) |
| Release (`pointerMask = 0x0800`) | one `0x0800` MOVE + one `0x0800` MOVE (pair; second is the press-clearing emit) | unchanged |
| Scroll (`pointerMask = 0x0278` / `0x0378`) | one `0x0878` MOVE+scroll + one `0x0278` / `0x0378` scroll | unchanged |

**Expected** — the MOVE-flagged PDU should be emitted only when it would carry new information, i.e. when `(pointerMask & MOUSE_BUTTON_MOVE) == 0`. Button-press sequences and the INV-011 release-previous-button guard stay intact; only the redundant MOVE PDU is dropped.

## Impact

- **Severity:** Medium — functional gap on every RDP session where the server is Linux/xrdp (and any other RDP server that interprets each BUTTON1 PDU as a fresh click edge rather than as a state update). Plain moves and discrete clicks are unaffected.
- **Reproduction:** Press → drag → release on an xrdp window. The drag never registers with the X11 server; releasing the finger does not lift the implicit grab; subsequent clicks land at the press point.
- **Non-reproduction:** Windows RDP servers (and any RDP server that maintains persistent button state) tolerate the duplicate PDUs and continue to honour the drag. The bug therefore often goes unnoticed when development happens against a Windows server.

## Fix

In `bVNC/src/main/java/com/iiordanov/bVNC/input/RemoteRdpPointer.java:130-136`:

```java
// Send the MOUSE_BUTTON_MOVE-flagged event first only when it differs from pointerMask.
// If pointerMask already contains MOUSE_BUTTON_MOVE, the two writes would be byte-identical
// duplicates, so emit just the single pointerMask event.
if ((pointerMask & MOUSE_BUTTON_MOVE) == 0) {
    protocomm.writePointerEvent(pointerX, pointerY, combinedMetaState, MOUSE_BUTTON_MOVE | pointerMask, false);
}
protocomm.writePointerEvent(pointerX, pointerY, combinedMetaState, pointerMask, false);
```

The change is RDP-only — `RemoteVncPointer` and `RemoteSpicePointer` are untouched. The INV-011 release-previous-button guard (`prevPointerMask != 0 && prevPointerMask != pointerMask` → emit an UP PDU before the new DOWN) is unchanged. Coordinates, the `combinedMetaState`, the canvas invalidation calls, and the pointer-mask bookkeeping all stay the same.

## Residual uncertainty

Drag ticks still carry `DOWN` on every tick (`pointerMask = MOUSE_BUTTON_MOVE | POINTER_DOWN_MASK | button`, e.g. `0x9800`). xrdp may or may not tolerate the per-tick DOWN repeats once the duplicate-PDU bug is gone. If xrdp still churns, a follow-up MOVE-coalescing change may be needed (suppress the `DOWN` bit on drag ticks that follow the initial press, so the wire sees only `MOVE | button` after the first tick). That follow-up is out of scope for this fix and has not been written.

## Testing-or-Verification

**Not yet verified on device.** No live-device run was performed in this round against either the user's Linux server or any test server. Verification on user hardware is required to confirm:

1. Press, drag, release on an xrdp window now moves the remote cursor and honours the X11 grab (file-pane drag in a file manager; text selection; window drag).
2. The same gesture continues to work against a Windows RDP server (no regression).
3. Plain moves and discrete clicks are unchanged on both server types.
4. Scroll (`scrollUp` / `scrollDown` from `RdpModifierRowHandler`) still emits both PDUs and still scrolls the remote target.

Build verification: `:bVNC:compileDebugJavaWithJavac` and `:aRDP-app:assembleDebug` both green. Unit-test side-effects: none — `RemoteRdpPointer` is not unit-covered (it requires a live `RfbConnectable` + `protocomm.writePointerEvent`); the dedup is small enough that a regression would surface immediately under manual smoke.