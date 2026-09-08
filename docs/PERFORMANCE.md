# PERFORMANCE.md

Performance budgets, throttling mechanisms, and known bottlenecks. Performance hotspots all sit on the canvas view and the input pipeline.

> Read first: `ARCHITECTURE.md` §4 (thread model), `DESIGN_PRINCIPLES.md` §PER (rules), `features/INPUT_PIPELINE.md` (input flow).

---

## 1. Latency budgets (soft)

| Path | Target | Source |
|---|---|---|
| Touch → RDP wire event | < 16 ms (1 frame @ 60 Hz) | `RdpCommunicator.inputExecutor` + 3 ms throttle |
| Hardware key → RDP wire event | < 8 ms | Same executor + 5 ms key throttle |
| Frame → on screen | < 16 ms when ahead, ≤100 ms when behind | `RemoteCanvas.reDraw:681-696` throttle |
| Activity → first RDP frame | < 500 ms typical on local LAN | `GRAPHICS_FIRST_FRAME_RECEIVED` dismisses progress dialog |

These are not formally enforced; they are descriptive of current behavior and where to look if any one is violated.

---

## 2. Throttles

| ID | What | Value | Where |
|---|---|---|---|
| `PER-INPUT-3` | Minimum delay between RDP mouse events | `3 ms` | `RdpCommunicator.sleepBetweenInputEvents(...)` |
| `PER-INPUT-5` | Minimum delay between RDP keyboard events | `5 ms` | Same |
| `PER-FRAME-60` | Target reDraw rate | 16.6666 ms (60 Hz) | `RemoteCanvas.java:684` |
| `PER-FRAME-FALLBACK` | Coalesced invalidate when behind | `100 ms` | `RemoteCanvas.invalidateCanvasRunnable:153` |
| `PER-MENU-FRAME` | Menu-driven invalidate only | n/a | `RemoteCanvas.reDraw(float...)` |
| `PER-FLING-TICK` | Touchpad fling tick (RDP-only). Up to ~50 events/s. Per-tick damping is the runtime-configurable `flingDamp` instance field (default `FLING_DAMP=0.86f`; slider 6 → `0.92f - slider*0.01f` = 0.86 = legacy — round 4). The new `EdgePinRepeater` (drag-hold edge pin) reuses the same `FLING_TICK_MS=20` on `viewable.getHandler()` (main-thread UI handler), so the two stay in lockstep. | `20 ms` | `TouchInputHandlerTouchpad.FLING_TICK_MS=20`, `FLING_DAMP=0.86f` (now default of `flingDamp` field), `FLING_NOISE_PX_PER_S=200`, `EdgePinRepeater:686-740` |

Touch and key handlers use the same executor (see `DESIGN_PRINCIPLES.md` PAT-003), so the throttle is per channel, not per type.

---

## 3. Memory and GC

- The single framebuffer Bitmap (`AbstractBitmapData.mbitmap`, ARGB_8888) is allocated once per resolution and reallocated only on `OnSettingsChanged`. A 1920×1080 ARGB_8888 bitmap is ~8 MiB.
- `System.gc()` is called in three hot paths:
  - `ConnectionGridActivity.onResumeFragments:419`
  - `RemoteConnection.closeConnection:308` (via `writeScreenshotToFile` flow)
  - `AbstractBitmapData.dispose:248` / `AbstractBitmapDrawable.createInitialSoftCursor:336`
- Do not remove these calls without first running a leak probe — the explicit GC is doing real work here.
- `RemoteCanvasActivity.onDestroy:1236` also calls `System.gc()`.
- Bitmap recycling: `UltraCompactBitmapData.dispose` (line 248-261) recycles the bitmap on drawable teardown. `Bitmap.createBitmap` is NOT called per frame.

---

## 4. Native memory

- FreeRDP native render writes into the Java-owned Bitmap via JNI (`LibFreeRDP.updateGraphics`). The C side does NOT retain the bitmap reference. See INV-004.
- `GlobalApp.sessionMap` is statically `synchronizedMap`'d via `RdpCommunicator.patchFreeRdpCore:100-112`. Without this patch, multi-session would crash.

---

## 5. Known bottlenecks

| Issue | Where | Improvement idea |
|---|---|---|
| Bitmap has only one invalidation path (full repaint). | `RemoteCanvas.reDraw:681` | Region-based invalidation, but every RDP `OnGraphicsUpdate` is already a small rect; the cost is the `Matrix` transform + `drawBitmap`. |
| Touch handling allocates `MotionEvent` arrays in some handlers. | `TouchInputHandlerGeneric` | Profile before optimizing. |
| Frame throttle drops to 10 Hz under load. | `RemoteCanvas.invalidateCanvasRunnable:153` | Acceptable for RDP; would need re-thinking for VNC where pixels fly. |
| `inputExecutor` throttles 3-5 ms between events. | `RdpCommunicator.sleepBetweenInputEvents:289-294` | Tightening increases server-side queue depth; loosening adds visible lag. |
| FreeRDP thread is unsynchronized with the JVM render path. | `LibFreeRDP.OnGraphicsUpdate` callback | Mostly hidden by `UltraCompactBitmapData.updateBitmap`'s `synchronized (mbitmap)` block; if you see jank here, add a profiler. |

---

## 6. Frame pipeline invariants for performance work

- INV-006: bypass `reDraw` and you lose the throttle + native bitmap-copy coordination.
- INV-003: `UltraCompactBitmapData.updateBitmap` is the single shared bitmap object. Both sides observe it; locking is on `mbitmap`.
- INV-008: do not reset `hardwareMetaState` from a performance-optimization path without also resetting `onScreenMetaState` — phantom-modifier sends cause double input events.
- INV-002: `visibleHeight` shrinks when the IME opens but the framebuffer is NOT reallocated. Anything that recomputes from `canvas.getHeight()` (e.g. the zoom floor) will produce black borders at minimum zoom; use `canvas.visibleHeight>0 ? visibleHeight : height` (see INV-017).

---

## 7. Profiling

Standard Android Studio Profiler works. Specific points:

| Profile target | Tool |
|---|---|
| Touch latency | Trace `RemoteCanvasActivity.onTouchEvent` → `RemoteClientsInputListener.onTouchEvent` → `TouchInputHandler*` → `RemoteRdpPointer.sendPointerEvent` → `RdpCommunicator.writePointerEvent` |
| Frame latency | Method trace `RemoteCanvas.reDraw` |
| Native pixel push | Trace `LibFreeRDP.OnGraphicsUpdate` callback chain |
| Memory growth on long sessions | Compare `Bitmap` allocations across `OnSettingsChanged`; should be zero between settings changes |

---

## 8. Things deliberately slow

- `StrictMode.ThreadPolicy.permitAll()` in `RemoteCanvasActivity.onCreate` (DPP-008). Removing it triggers false-positive VM-policy violations.
- Hand-threaded single `SendRdpInputThread` executor (PAT-003). Reordering for "speed" loses the no-interleave guarantee.
- Manifest-level multi-ABI split with `universalApk true` (each APK is bigger, but installing one is enough).
