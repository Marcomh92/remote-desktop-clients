# ADR-0002 — Opt out of predictive-back via `android:enableOnBackInvokedCallback="false"`

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-09-09 |
| Deciders | Maintainers of this fork |
| Source module area | `bVNC/src/main/AndroidManifest.xml` (library manifest), inherited by every wrapper |
| Context tag | `ADR-0002` |

---

## Context

The fork targets `targetSdk=36`. On Android 13+ (API 33+) the framework dispatches back presses through `OnBackPressedDispatcher` / `OnBackInvokedCallback` rather than `Activity.onBackPressed`. Predictive-back animation is the visible side effect; the silent side effect is that the legacy `onBackPressed` override is **no longer called**, regardless of whether any Activity registers a dispatcher.

Round-5 added the RDP-only double-back-to-disconnect flow in `RemoteCanvasActivity.onBackPressed:1808-1817` (the disconnect branch) — first back-press when the IME is hidden stamps `lastBackPressForDisconnect`, toasts `R.string.back_press_to_disconnect`, and a second press within `DOUBLE_BACK_DISCONNECT_WINDOW_MS = 2000L` calls `disconnectAndFinishActivity()`. With predictive-back active the branch was unreachable: the OS dispatched back through the dispatcher and the Activity override never ran. The user could no longer disconnect an RDP session by pressing back.

A grep of the codebase confirms there is no `OnBackPressedDispatcher` or `OnBackInvokedCallback` registration anywhere in the project — no Activity uses the modern dispatch path. The codebase is wholly on the legacy `onBackPressed` override.

---

## Decision

Declare `android:enableOnBackInvokedCallback="false"` on `<application>` in `bVNC/src/main/AndroidManifest.xml:29`. This restores the legacy `Activity.onBackPressed` dispatch path across the whole application, which inherits to every wrapper module that merges in the library manifest.

```xml
<application
    android:enableOnBackInvokedCallback="false">
```

The flag is a deliberate, scoped opt-out. It exists for one reason: keep round-5's double-back disconnect reachable on Android 13+.

---

## Alternatives considered

### A. Migrate to `OnBackPressedDispatcher` + `OnBackInvokedCallback`

Convert `RemoteCanvasActivity` (and the other affected Activities: `ConnectionListActivity`, `GlobalPreferencesActivity`) to register `OnBackInvokedCallback`s that branch on the same logic now in `onBackPressed`.

Pros:
- Modern Android idiom.
- Removes the project-wide opt-out, restoring predictive-back animations globally.
- Aligns with future Android deprecations of the legacy `onBackPressed` override.

Cons:
- App-wide churn: every wrapper inherits the canvas Activity; the launcher (`ConnectionGridActivity`), `GlobalPreferencesActivity`, `ConnectionListActivity`, the bookmark editors, and the `pubkeyGenerator` activity would all need a reviewed dispatch handler.
- Larger risk surface — predictive-back has its own animation gating (`OnBackInvokedDispatcher`), gesture-nav handling, and focus-order semantics that don't exist in the legacy override.
- One bug in any Activity regresses the round-5 disconnect path.

### B. Adopted — one-line manifest opt-out

Pros:
- One attribute, one file. Already verified to restore the legacy path on a device running Android 13+.
- Manifest merger inherits the flag into all 8 wrapper manifests automatically (`aRDP-app`, `freeaRDP-app`, `bVNC-app`, `freebVNC-app`, `aSPICE-app`, `freeaSPICE-app`, `Opaque-app`, `CustomVnc-app`).
- Pre-Android-13 devices silently ignore the flag, so no behavior change there.
- Zero code refactor — the round-5 disconnect flow runs unchanged.

Cons:
- `ConnectionListActivity` and `GlobalPreferencesActivity` also lose the predictive-back animation on Android 13+. Both still handle back correctly via their legacy `onBackPressed` overrides (the animation is cosmetic only).
- Future Android releases may deprecate `enableOnBackInvokedCallback` itself and force migration to option A. Until that happens, this flag is the smallest diff that keeps the round-5 flow working.

---

## Consequences

### Positive

- Round-5's double-back-to-disconnect flow runs on Android 13+ devices. Users can disconnect an RDP session the same way they could before `targetSdk=36`.
- No code refactor required — the opt-out is purely declarative.
- The flag is a single attribute on the shared library manifest; one change reaches all eight wrappers.

### Negative

- `ConnectionListActivity` and `GlobalPreferencesActivity` lose the predictive-back animation on Android 13+ devices. Their back-press behaviour is unchanged (legacy `onBackPressed` overrides still handle the press correctly) — only the visual animation is missing.
- A future Android API level may deprecate or remove `enableOnBackInvokedCallback`, forcing migration to option A. Treat that as a forced revisit trigger.

### Mitigations

- Document the flag in `features/INPUT_PIPELINE.md` §4.3.6 and `features/UI_SHELL.md` §6.5 so the next implementer understands why it is set.
- The flag is added on `<application>`, not on a per-Activity basis — if predictive-back is wanted on a non-RDP-only Activity in the future, the per-Activity toggle is to move the attribute down one level.

---

## Compliance

- INV-025 (round-6) is the rule that captures this opt-out: predictive-back is disabled project-wide for the sole purpose of keeping `Activity.onBackPressed` reachable.
- `MASTER.md` glossary does not need a separate entry; the manifest attribute is discoverable in `bVNC/src/main/AndroidManifest.xml:29`.
- If option A (proper `OnBackPressedDispatcher` migration) lands, INV-025 must be re-evaluated and this ADR superseded — see "Revisit triggers" below.

---

## Implementation reference

- `bVNC/src/main/AndroidManifest.xml:29-30` — the `<application android:enableOnBackInvokedCallback="false">` declaration.
- `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java:1808-1817` — the round-5 double-back disconnect branch that this flag protects.
- `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java:174-175` — `DOUBLE_BACK_DISCONNECT_WINDOW_MS = 2000L` constant and `lastBackPressForDisconnect` field.
- `bVNC/src/main/java/com/iiordanov/bVNC/RemoteCanvasActivity.java:895` — `lastBackPressForDisconnect = 0` reset in `onPause`.
- `bVNC/src/main/res/values/strings.xml:83` — `back_press_to_disconnect` toast string.
- Every wrapper `AndroidManifest.xml` (in `aRDP-app/`, `freeaRDP-app/`, `bVNC-app/`, `freebVNC-app/`, `aSPICE-app/`, `freeaSPICE-app/`, `Opaque-app/`, `CustomVnc-app/`) — manifests merged against the library manifest and inherit the flag.

---

## Revisit triggers

- If `OnBackPressedDispatcher` support is added properly to `RemoteCanvasActivity` (and reviewed for `ConnectionListActivity` / `GlobalPreferencesActivity`), remove `android:enableOnBackInvokedCallback="false"` and supersede this ADR.
- If Android deprecates or removes the attribute, migration to option A becomes mandatory regardless of cost.
- If `targetSdk` is ever lowered below 33, the flag becomes a no-op and may be deleted.