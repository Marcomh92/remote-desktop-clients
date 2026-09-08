# AGENTS.md - Agent Directives

This file defines **operating constraints and initial procedures** for AI agents working on this Android codebase.

For system architecture, patterns, and technical details, see the documentation in `docs/`.

## MANDATORY: PREFLIGHT CHECKLIST

> **CRITICAL: DO NOT PROCEED WITH ANY USER REQUEST UNTIL ALL ITEMS BELOW ARE COMPLETED**
>
> Failure to complete this checklist will result in context loss and incorrect implementation.

### Phase 1: System Context (REQUIRED)

You MUST read these files in EXACT order:

| #  | File                        | Purpose                        |
|----|-----------------------------|--------------------------------|
| 1  | `docs/MASTER.md`            | System overview and doc index  |
| 2  | `docs/DESIGN_PRINCIPLES.md` | Core architectural principles  |
| 3  | `docs/ARCHITECTURE.md`      | Layer responsibilities         |
| 4  | `docs/PATTERNS.md`          | Code patterns and standards    |

After completing the core files above, **discover and read** all remaining documentation relevant to your task:

1. **List** `docs/` to discover any additional project-level documents
2. **List** `docs/features/` to discover feature-specific subsystem documentation
3. **List** `docs/DECISIONS/` to discover Architecture Decision Records (ADRs)
4. **Use `docs/MASTER.md`** as the authoritative index — it catalogs all documentation files and contains the glossary of domain terms
5. **Read** Selectively read additional project-level, feature-level, and ADR documents relevant to your assigned task

## Bug Report Management

Bug reports live in `known_issues/`.

- **When fixing:** Check `known_issues/*.md`, read if found, mark **FIXED** with date after resolving, move to `known_issues/fixed/`
- **When creating:** Check `known_issues/` AND `known_issues/fixed/` for duplicates, use next `BUG-xxx` number, name: `BUG-xxx-short-description.md`

## GitNexus — Code Intelligence

Gitnexus can be used to get a deep architectural view of the codebase so you are less likely to miss dependencies, break call chains, and ship blind edits.

This project is indexed by GitNexus as repo **aRDP**. All gitnexus_* tools are MCP tool calls — invoke them directly, **never** via the bash tool. Always pass `repo: "aRDP"` explicitly.

#### Index maintenance (escape hatch — only when needed)

The only gitnexus action that uses the bash tool is rebuilding a stale index. Verify staleness first with `gitnexus_query({query: "project overview", repo: "aRDP"})`. If it reports a stale or missing index, run from the project root:

```
gitnexus analyze
```

Skip this step if `project overview` returns current results.

### Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream", repo: "aRDP"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes({repo: "aRDP"})` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept", repo: "aRDP"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName", repo: "aRDP"})`.
- **MUST pass `repo: "aRDP"` in every gitnexus_* tool call** — the parameter is technically optional with one indexed repo, but omitting it produces errors in this environment.

### Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.
- NEVER invoke gitnexus_* tools via the bash tool — they are MCP tools. The single bash exception is `gitnexus analyze` for rebuilding a stale index.

### Quick Reference

> Every example below includes `repo: "aRDP"`. Do not omit it.

#### Discover Repositories
```
gitnexus_list_repos()
```

#### Codebase Overview & Staleness Check
```
gitnexus_query({query: "project overview", repo: "aRDP"})
```

#### Functional Areas (Clusters)
```
gitnexus_cypher({query: "MATCH (c:Community) RETURN c.heuristicLabel, c.symbolCount, c.cohesion ORDER BY c.symbolCount DESC", repo: "aRDP"})
```

#### Execution Flows (Processes)
```
gitnexus_cypher({query: "MATCH (p:Process) RETURN p.heuristicLabel, p.stepCount, p.processType ORDER BY p.stepCount DESC", repo: "aRDP"})
```

#### Step-by-Step Execution Trace
```
gitnexus_cypher({query: "MATCH (s)-[r:CodeRelation {type: 'STEP_IN_PROCESS'}]->(p:Process) WHERE p.heuristicLabel = 'ProcessName' RETURN s.name, r.step ORDER BY r.step", repo: "aRDP"})
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
