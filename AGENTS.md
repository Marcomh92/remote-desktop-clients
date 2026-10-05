# AGENTS.md - Agent Directives

Remote-desktop-clients: bVNC, aRDP, aSPICE, Opaque for Android.
Forked from github.com/iiordanov/remote-desktop-clients; read [README.md](README.md) first for product context.

## Project shape

Multi-module Gradle Android project — **not** the single-module package-by-layer layout the bundled `.opencode/skills/android-module-structure` describes. That skill (and several others under `.opencode/skills/`) was copied from another project (PreDecide2) and is **not applicable here**. Ignore those skills.

12 Gradle modules (see [settings.gradle](settings.gradle)):

| Module               | Plugin            | Notes                                                                                  |
|----------------------|-------------------|----------------------------------------------------------------------------------------|
| `remoteClientLib`    | `android-library` | NDK/JNI: FreeRDP + SPICE/GStreamer + VirtViewer. Native build is heavy.                |
| `pubkeyGenerator`    | `android-library` | SSH keygen UI utility.                                                                  |
| `bVNC`               | `android-library` | Hosts `App` Application class and **all** shared UI code (bVNC/aRDP/aSPICE/Opaque).    |
| `common`             | `android-library` | DB + utilities. **Only module with unit tests.**                                        |
| `bVNC-app`           | `android-application` | Thin wrapper — manifest only; `:bVNC` is the applicationId source.                   |
| `freebVNC-app`       | `android-application` | Free flavor of bVNC.                                                                 |
| `aRDP-app`           | `android-application` | Thin wrapper. Manifest reuses `App` from `:bVNC`.                                     |
| `freeaRDP-app`       | `android-application` | Free flavor.                                                                         |
| `aSPICE-app`         | `android-application` | Thin wrapper.                                                                        |
| `freeaSPICE-app`     | `android-application` | Free flavor.                                                                         |
| `Opaque-app`         | `android-application` | oVirt/RHEV/Proxmox.                                                                   |
| `CustomVnc-app`      | `android-application` | Programmatically-customizable VNC client. See "Custom clients" below.                  |
| `remoteClientLib:jni:libs:deps:FreeRDP:client:Android:Studio:freeRDPCore` | `android-library` | Vendored FreeRDP core.                                                            |

Empty top-level `aRDP/` directory exists from the repo's parent-folder name — **not a Gradle module**.

Mostly Java (Views, no Compose); a thin layer of Kotlin utility/protocol classes. No Hilt, no Jetpack Compose, no `androidx.lifecycle.ViewModel`. Patterns differ from the `android-presentation-mvi` / `android-di-hilt` / `android-compose-ui` skills bundled in `.opencode/skills/` — they do not apply.

## Toolchain (mandatory, do not override)

- AGP `8.13.2`, Kotlin `2.2.21` ([build.gradle](build.gradle))
- Gradle wrapper `8.13` ([gradle/wrapper/gradle-wrapper.properties](gradle/wrapper/gradle-wrapper.properties))
- **JDK 21 is forced** via `org.gradle.java.home=C:/Users/marco/.jdks/jbr-21.0.11` in [gradle.properties](gradle.properties). The system comment says JBR 21 ships with Android Studio. JDK 25 fails — AGP cannot read its bytecode. Use JBR 21.
- `compileSdkVersion=36`, `targetSdkVersion=36`, `minSdkVersion=21` by default ([build.gradle](build.gradle)).
- `local.properties` is checked in (ignored-by-Android-Studio's gitignore but committed here): points at `C:\Users\marco\AppData\Local\Android\Sdk`. Update for your own machine.

## Native dependencies

`remoteClientLib` and friends need FreeRDP/SPICE/GStreamer. Two paths:

1. **Prebuilt (fast):** `./download-prebuilt-dependencies.sh` then `./bVNC/prepare_project.sh --skip-build libs nopath`.
2. **From scratch (slow, hours):** `./bVNC/prepare_project.sh <PROJECT> <ANDROID_SDK>` after installing Ubuntu deps `gnome-common gobject-introspection nasm gtk-doc-tools python-is-python3` and Android NDK/CMake.

Custom VNC clients: see README §III. Requires editing `gradle.properties` (`CUSTOM_VNC_APP_NAME`, `CUSTOM_VNC_APP_ICON`, `CUSTOM_VNC_APP_NAMESPACE`) plus a yaml config in `bVNC/src/main/assets/`.

## MCP

[opencode.json](opencode.json) configures `mobile-mcp` for live-device interaction. `ANDROID_HOME` is hard-coded for this machine — adjust if you move.

## Bug Report Management

Bug reports live in `known_issues/`.

- **When fixing:** Check `known_issues/*.md`, read if found, mark **FIXED** with date after resolving, move to `known_issues/fixed/`
- **When creating:** Check `known_issues/` AND `known_issues/fixed/` for duplicates, use next `BUG-xxx` number, name: `BUG-xxx-short-description.md`

## GitNexus — Code Intelligence

GitNexus gives a structural view of the codebase: who calls what, what breaks on a change, and which files a rename must touch. It is a snapshot of the last `gitnexus analyze` — git remains ground truth for what changed on disk.

**`REPO` is this project's GitNexus repo name. Every `remote-desktop-clients` below means the value of `REPO`.** This project is indexed as `remote-desktop-clients`. All gitnexus_* MCP tools are invoked directly, **never** via the bash tool.

### Enabled Tools

| Tool | Use for |
| --- | --- |
| `gitnexus_impact` | Depth-ordered blast radius + risk grade before editing a shared symbol |
| `gitnexus_context` | Callers, callees, implements/extends for one symbol |
| `gitnexus_rename` | Graph-aware multi-file rename — always `dry_run: true` first |
| `gitnexus_detect_changes` | Changed symbols and impacted flows for a diff |
| `gitnexus_list_repos` | Discovering the repo name |

Not available: `gitnexus_query`, `gitnexus_cypher`, `gitnexus://` MCP resources.

### Always Do

- Resolve the symbol before analyzing it — `grep` for the name to get its real `file_path`. Users name the wrong class often enough that a name-only call returns a confident answer about the wrong symbol.
- Run `gitnexus_impact({target, file_path, direction: "upstream", summaryOnly: true, repo: "remote-desktop-clients"})` before editing any existing symbol — function, class, method, property, or constant — and report the risk grade to the user.
- Warn the user before editing when impact returns HIGH or CRITICAL.
- Run `gitnexus_detect_changes({scope: "all", repo: "remote-desktop-clients"})` before committing.
- Use `summaryOnly: true` on the first `impact` call for any symbol — a full dump on a hub symbol floods context.
- Treat an empty impact result as unproven, not safe. Zero d=1 records also means "not in the index" — cross-check with `grep`.
- `grep` for the old name after every rename — the graph cannot see string literals, comments, annotation values, or resource IDs.

### Never Do

- NEVER edit a symbol without first running `gitnexus_impact` on it.
- NEVER rename with find-and-replace — use `gitnexus_rename`.
- NEVER trust the graph for which files changed — `gitnexus_detect_changes` misses untracked files.
- NEVER invoke gitnexus_* MCP tools via the bash tool.

### Index Maintenance

Check freshness from the project root with `gitnexus status`. It compares the indexed commit against the current one and prints `Status: up-to-date` or marks it stale. If stale, rebuild with `gitnexus analyze`. These two commands are the only shell exceptions to the no-bash rule for gitnexus MCP tools.

## Build & Test

Each `.bat` file in the project root is a thin wrapper that runs the corresponding `gradlew` command with `--quiet` and `--no-daemon` and prints a success message on completion. On failure, Gradle prints error details directly (configured via `TestListener` in `app/build.gradle.kts`).

Running the tests attempts to compile the project first. No need to run compile.bat before running tests.

Execute the bash tool with a 4 minute timeout.

### Available scripts

| Script | Purpose | Example |
|--------|---------|---------|
| `compile.bat` | Compile debug APK | `.\compile.bat` |
| `test-all.bat` | Run all unit tests | `.\test-all.bat` |
| `test-package.bat` | Run tests matching a filter | `.\test-package.bat "com.example.package.*"` |
| `test-class.bat` | Run a single test class | `.\test-class.bat "com.example.MyTest"` |

`test-package.bat` and `test-class.bat` automatically discover the Gradle module that owns the requested test. If the test exists in multiple modules, specify the module as the second argument, e.g., `.\test-class.bat "com.example.MyTest" "app"`.

### Parallel execution

Do not run these compile & test scripts in parallel. Each script acquires a Gradle build lock and will block if another build or test is already in progress. If you need to run multiple tests, prefer running the entire class or a parent package instead of invoking the script multiple times.

### Behavior

- **Success**: Prints a single-line message (`Project compiled successfully`, `All tests passed`, or `Tests passed`).
- **Failure**: Gradle prints the error details (compilation errors or per-test failures with stack traces) and the script exits with a non-zero code — no success message is printed.
- **Quiet mode**: All scripts pass `--quiet` to `gradlew`, so Gradle's own task logging is suppressed.


## Submodules

`remote-desktop-clients-store-metadata/` is a Git submodule (store-listing metadata only). Don't edit its contents from this repo; update upstream and pull.

## Branch / commit policy

Never commit unless the user explicitly asks. Working tree is clean on `master`. Inspect changes with `git diff`/`git status` before any commit. `gitnexus_detect_changes` is the required pre-commit sanity check.
