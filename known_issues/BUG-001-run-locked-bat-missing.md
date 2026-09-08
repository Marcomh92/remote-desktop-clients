# BUG-001 — `run-locked.bat` is missing from the repo root

## Summary

`run-locked.bat` is referenced as the very first executable step by every documented build/test wrapper (`compile.bat`, `test-all.bat`, `test-package.bat`, `test-class.bat`), but the file is not present in the repo root. Running any of those wrappers fails immediately with `'run-locked.bat' is not recognized as an internal or external command`. Build and test execution via the documented wrapper scripts is therefore broken.

## Location

- Missing file: `run-locked.bat` (repo root)
- Affected wrappers (first executable line in each): `compile.bat`, `test-all.bat`, `test-package.bat`, `test-class.bat`
- Documentation references: `docs/TESTING.md` §2 ("How to run"), `docs/ARCHITECTURE.md` §6 ("Build system" → "Scripts")

## Current vs Expected

**Current state** — first executable line of every wrapper:

```bat
@echo off
call run-locked.bat gradlew.bat assembleDebug --no-daemon --console=plain --quiet --warning-mode none
```

When the wrapper is executed, Windows resolves `run-locked.bat` against `PATH` (it is not in the repo root), fails to find it, and prints:

```
'run-locked.bat' is not recognized as an internal or external command,
operable program or batch file.
```

The remaining `--quiet` flags then suppress any further Gradle output, so the failure looks like a silent no-op after the `"Project compiled successfully"` echo at the end of `compile.bat` never fires.

**Expected state** — a `run-locked.bat` exists at the repo root and acts as a lock-and-forward wrapper:

```bat
@echo off
rem Lock-and-forward wrapper: serialize the build lock so two wrappers cannot run
rem gradle in parallel, then forward all arguments to the supplied gradlew binary.
rem Example usage from compile.bat / test-*.bat:
rem   call run-locked.bat gradlew.bat assembleDebug --no-daemon --console=plain --quiet --warning-mode none
```

The exact invocation contract from the existing wrappers is:

```
call run-locked.bat gradlew.bat <task> ... --no-daemon --console=plain --quiet --warning-mode none
```

So `run-locked.bat` must:

1. Acquire a global build lock (a `%SystemRoot%\Temp\gradle-build.lock` is the obvious choice).
2. Forward every argument after `%1` to `%1` (the gradlew binary supplied as the first argument), preserving the exact `--no-daemon --console=plain --quiet --warning-mode none` flags.
3. Release the lock on exit (clean and on error).
4. Exit with the gradlew exit code so the wrappers' `if %errorlevel% neq 0 exit /b %errorlevel%` chains behave correctly.

The wrappers already chain `errorlevel` correctly; the only missing piece is the script they call.

## Impact

- **Severity: High.** Every documented build / test wrapper fails out of the box. A new contributor who follows the docs cannot build the project or run the tests without first discovering the workaround below.
- **Runtime effect:** no fallback; the wrapper exits before invoking Gradle.
- **Documentation effect:** `docs/TESTING.md` and `docs/ARCHITECTURE.md` describe the wrappers as authoritative; this gap was also flagged in `DESIGN_PRINCIPLES.md` Known gaps and `ARCHITECTURE.md` §6.

## Fix

1. Create `run-locked.bat` at the repo root implementing the lock-and-forward contract above. A minimal implementation:

   ```bat
   @echo off
   setlocal
   set "LOCK=%SystemRoot%\Temp\gradle-build.lock"
   set "GRADLEW=%1"
   shift
   :waitlock
   1>nul 2>nul (echo lock && goto gotlock) || (ping -n 2 127.0.0.1 >nul && goto waitlock)
   :gotlock
   echo lock > "%LOCK%" 2>nul
   call "%GRADLEW%" %*
   set "RC=%errorlevel%"
   del "%LOCK%" 2>nul
   endlocal & exit /b %RC%
   ```

   Adjust the lock scheme (file lock, `fsutil`, etc.) to taste; the contract that matters is "serialize then forward". Wire this into the existing wrappers — no other changes required.

2. After the file is added, verify `.\test-all.bat` (or `.\compile.bat`) succeeds end-to-end without the workaround.

## Testing

1. **Lock behavior** — open two terminals and run `.\test-all.bat` in both. The second invocation must block until the first completes (or fails), then run normally. Without the lock, Gradle's own daemon handling would still serialize, but with `--no-daemon` two parallel builds will corrupt each other.
2. **Error-level propagation** — introduce a failing test (e.g. add a temporary `assert false` in any `:common` test) and confirm `.\test-class.bat "<failing test>"` exits non-zero and prints the test failure rather than `Tests passed`.
3. **Quiet flag passthrough** — `.\compile.bat` must produce the same Gradle output shape as `gradlew.bat assembleDebug --no-daemon --console=plain --quiet --warning-mode none` (i.e. nothing on success, errors on failure), and `compile.bat` must print `Project compiled successfully` on success.
4. **Re-run after fix** — re-read `docs/TESTING.md` §2 and `docs/ARCHITECTURE.md` §6. Both currently document the workaround ("invoke `gradlew.bat` directly..."). Once the wrappers work again, the workaround paragraphs should be deleted and the wrappers re-promoted to the recommended path. Update `DESIGN_PRINCIPLES.md` Known gaps table (remove the `run-locked.bat` row).

## Workaround (until fixed)

Invoke `gradlew.bat` directly with the same flags the wrappers would have used:

- Build: `gradlew.bat assembleDebug --no-daemon --console=plain --quiet --warning-mode none`
- All `:common` tests: `gradlew.bat :common:testDebugUnitTest --no-daemon --console=plain --quiet --warning-mode none`
- Single class: `gradlew.bat :common:testDebugUnitTest --tests "<fully-qualified-class>" --no-daemon --console=plain --quiet --warning-mode none`
- Single package: same with `--tests "<package>.*"`

`docs/TESTING.md` §2 and `docs/ARCHITECTURE.md` §6 currently document this workaround inline.
