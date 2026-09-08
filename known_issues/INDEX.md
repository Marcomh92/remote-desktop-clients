# Known Issues Index

This directory contains bug reports for known issues in this codebase.

## Active Issues

| ID | Title | Severity | Status |
|---|---|---|---|


## Closed Issues

| ID                                                                         | Title                                                                                 | Severity | Status | Date fixed |
|----------------------------------------------------------------------------|---------------------------------------------------------------------------------------|----------|--------|------------|
| [BUG-001](fixed/BUG-001-run-locked-bat-missing.md) | `run-locked.bat` was missing from the repo root; all documented build/test wrappers failed with `'run-locked.bat' is not recognized`. | High — breaks all build/test wrapper scripts | FIXED | 2026-09-08 |


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
