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

Gitnexus can be used to get a deep architectural view of the codebase so you are less likely to miss dependencies, break call chains, and ship blind edits.

This project is indexed by GitNexus as repo **remote-desktop-clients**. All gitnexus_* tools are MCP tool calls — invoke them directly, **never** via the bash tool. Always pass `repo: "remote-desktop-clients"` explicitly.

#### Index maintenance (escape hatch — only when needed)

The only gitnexus action that uses the bash tool is rebuilding a stale index. Verify staleness first with `gitnexus_query({query: "project overview", repo: "remote-desktop-clients"})`. If it reports a stale or missing index, run from the project root:

```
gitnexus analyze
```

Skip this step if `project overview` returns current results.

### Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream", repo: "remote-desktop-clients"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes({repo: "remote-desktop-clients"})` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept", repo: "remote-desktop-clients"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName", repo: "remote-desktop-clients"})`.
- **MUST pass `repo: "remote-desktop-clients"` in every gitnexus_* tool call** — the parameter is technically optional with one indexed repo, but omitting it produces errors in this environment.

### Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.
- NEVER invoke gitnexus_* tools via the bash tool — they are MCP tools. The single bash exception is `gitnexus analyze` for rebuilding a stale index.

### Quick Reference

> Every example below includes `repo: "remote-desktop-clients"`. Do not omit it.

#### Discover Repositories
```
gitnexus_list_repos()
```

#### Codebase Overview & Staleness Check
```
gitnexus_query({query: "project overview", repo: "remote-desktop-clients"})
```

#### Functional Areas (Clusters)
```
gitnexus_cypher({query: "MATCH (c:Community) RETURN c.heuristicLabel, c.symbolCount, c.cohesion ORDER BY c.symbolCount DESC", repo: "remote-desktop-clients"})
```

#### Execution Flows (Processes)
```
gitnexus_cypher({query: "MATCH (p:Process) RETURN p.heuristicLabel, p.stepCount, p.processType ORDER BY p.stepCount DESC", repo: "remote-desktop-clients"})
```

#### Step-by-Step Execution Trace
```
gitnexus_cypher({query: "MATCH (s)-[r:CodeRelation {type: 'STEP_IN_PROCESS'}]->(p:Process) WHERE p.heuristicLabel = 'ProcessName' RETURN s.name, r.step ORDER BY r.step", repo: "remote-desktop-clients"})
```

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
