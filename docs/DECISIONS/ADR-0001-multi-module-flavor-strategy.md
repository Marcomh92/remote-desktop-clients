# ADR-0001 — Multi-module wrapper-strategy for paid/free RDP/VNC/SPICE flavors

| Field | Value |
|---|---|
| Status | Accepted |
| Date | 2026-09-08 |
| Deciders | Maintainers of this fork |
| Source module area | `settings.gradle`, every `*-app` directory |
| Context tag | `ADR-0001` |

---

## Context

Upstream iiordanov/remote-desktop-clients publishes four products (bVNC, aRDP, aSPICE, Opaque) each in a paid and (for three of them) a free flavor. Forked by this repo, with focus on aRDP specifically.

A naive Android implementation would use Gradle product flavors (`flavorDimensions = ["product"]; productFlavors { paid {}; free {} }`) inside one or four `android-application` modules. We chose not to do this.

---

## Decision

Each combination of (product, paid/free) is its own `android-application` Gradle module whose `src/main/` contains only `AndroidManifest.xml` (and a possibly-override `res/`). All shared Java/Kotlin code lives in `:bVNC` (an `android-library`).

```
:bVNC-app       (com.iiordanov.bVNC)         manifest only
:freebVNC-app   (com.iiordanov.freebVNC)     manifest only
:aRDP-app       (com.iiordanov.aRDP)         manifest only
:freeaRDP-app   (com.iiordanov.freeaRDP)     manifest only
:aSPICE-app     (com.iiordanov.aSPICE)       manifest only
:freeaSPICE-app (com.iiordanov.freeaSPICE)   manifest only
:Opaque-app     (com.undatech.opaque)        manifest only
:CustomVnc-app  (com.iiordanov.<CUSTOM_VNC_APP_NAMESPACE>) placeholder
```

There are no `productFlavors` blocks. There is no `applicationIdSuffix`. There are no `src/free/` or `src/paid/` source-set splits. Paid vs. free is decided entirely by which `*-app` module is built.

The `Application` class lives in `:bVNC` (`bVNC/src/main/java/com/iiordanov/bVNC/App.java`) and is referenced from every wrapper manifest as `android:name="com.iiordanov.bVNC.App"`.

Flavor detection at runtime goes through `Utils.isRdp/isVnc/isSpice/isOpaque` (`bVNC/.../Utils.java:274-310`), which inspects the running app's package name.

The Custom-VNC variant reads four placeholders from `gradle.properties` (`CUSTOM_VNC_APP_NAME`, `CUSTOM_VNC_APP_ICON`, `CUSTOM_VNC_APP_NAMESPACE`) and substitutes them into the manifest.

---

## Alternatives considered

### A. Single module + product flavors

A single `bVNC-app` module with `flavorDimensions = ["product", "tier"]` and `productFlavors = { bVNC, aRDP, aSPICE, Opaque } × { paid, free }`. This generates eight variants from one module.

Pros:
- Smaller Gradle graph (1 module vs. 8).
- One `AndroidManifest.xml` to maintain.
- Standard idiomatic Android pattern.

Cons:
- Variant-specific manifest changes (different permissions for free vs. paid; intent-filters that target a specific flavor of `ConnectionGridActivity`) require `src/paid/AndroidManifest.xml` and `src/free/AndroidManifest.xml`, drifting from idiomatic.
- Per-flavor source sets cause the canvas Activity code to live behind a per-flavor `src/` boundary, which complicates the shared-canvas-Activity story.
- `applicationId` per flavor requires careful namespace-permutation logic that the Custom variant further complicates.

### B. Single wrapper per product, free vs. paid via `applicationIdSuffix`

`:bVNC-app` + `:freebVNC-app` where `:freebVNC-app` overrides `applicationIdSuffix = ".free"`. Pros: minimal duplication. Cons: paid and free share the same code, so distinguishing them at compile time requires runtime checks (`Utils.isFree()`), which is what we already do, so no benefit.

### C. **Adopted:** One wrapper module per (product, tier) pair, no flavors.

Pros:
- Each APK is independently installable (different `applicationId`s).
- Manifest permission and intent-filter differences are localized per wrapper file.
- Per-flavor build flags (e.g., removing audio permissions from free variant) require no source-set hackery.
- The Custom VNC variant is a separate module with placeholder substitution — no need to invent a flavor dimension.
- Compatible with multi-ABI splits (`splits.abi` + `universalApk true`) per wrapper without per-flavor config.

Cons:
- 8 application modules vs. 1 flavored module. The duplication is mostly trivial (`AndroidManifest.xml` ~96 lines per wrapper).
- A new product/tier pair needs a new module. Acceptable given how rarely products are added.
- Per-module resource caching could in theory create a slightly larger APK set, but `universalApk true` keeps each wrapper APK single-ABI.

---

## Consequences

### Positive

- **Clear ownership.** Each wrapper is a file you can `git diff` against another wrapper; the diff tells you exactly what is per-flavor vs. shared.
- **Per-flavor crash attribution.** Per-wrapper namespaces mean Crashlytics / Sentry receives clean `(product, paid/free)` combinations in the stack frame.
- **Independent release cadence.** Wrappers can be versioned independently if needed (today they share `versionCode=116490`).
- **Easy customization.** Forks (Custom VNC, vendor-specific rebrandings) are a manifest copy + `gradle.properties` change.

### Negative

- **Module count grows linearly** with products/tiers. Currently 8 `android-application` modules; manageable.
- **Risky to add `productFlavors` later** — the existing wrappers do not use them, and adding flavors would require convincing the existing modules to fold into one (or stay split, which negates the point).
- **One duplicated `AndroidManifest.xml` per pair.** Mitigated by templates/checklists.

### Mitigations

- Each wrapper `AndroidManifest.xml` follows the same template (see `aRDP-app/src/main/AndroidManifest.xml` for the canonical form).
- All wrappers depend only on `:bVNC` and (where relevant) `:pubkeyGenerator`, so the build graph stays shallow.
- The :Opaque wrapper additionally depends on `:remoteClientLib` directly (uses `.vv` files and SPICE).
- The Custom wrapper uses Gradle manifest placeholders so adding a new fork does not require a code change.

---

## Compatibility notes

- **Do not** convert any wrapper to `productFlavors`. The build module count is the source of truth.
- **Do not** add `applicationIdSuffix` inside `buildTypes` of an existing wrapper.
- **Do** update every wrapper's `versionCode` / `versionName` together when bumping (currently all synchronized).
- **AGENTS.md drift.** AGENTS.md mentions `compile.bat` and a `TestListener` in `app/build.gradle.kts` that do not exist on disk. Treat AGENTS.md as illustrative for build scripts; authoritative references are `gradle.properties`, `*.bat` files, and `settings.gradle`.

---

## Implementation reference

- `settings.gradle` — module include list.
- `build.gradle` (root) — `ext` defaults for compile/min/target API.
- `gradle.properties` — `SDK_VERSION`, `org.gradle.java.home`, custom-client placeholders.
- `aRDP-app/src/main/AndroidManifest.xml:1-96` — canonical paid-RDP wrapper.
- `freeaRDP-app/src/main/AndroidManifest.xml` — free variant (drops `MODIFY_AUDIO_SETTINGS` and `RECORD_AUDIO`).
- `bVNC/src/main/java/com/iiordanov/bVNC/App.java` — shared `Application` class.
- `bVNC/src/main/java/com/iiordanov/bVNC/Utils.java:274-310` — runtime flavor detection.

---

## Revisit triggers

- If a new product/tier pair is needed, follow this template; do not introduce `productFlavors`.
- If the project adopts `productFlavors` for some unrelated reason (e.g., lite/complete), revisit which scope it applies to and document any wrapper that becomes redundant.
- If the multi-module build becomes slow (>5 minutes cold), this ADR might warrant a "compress to one module + flavors" follow-up — but only after measurement.
