@echo off
setlocal enabledelayedexpansion

if "%1"=="" (
    echo Usage: %~nx0 "com.example.MyTest" [module]
    echo   module: optional Gradle module name, e.g., app
    echo   Do not run these scripts in parallel; prefer running the entire class/parent tests.
    exit /b 1
)

set "tempFile=%TEMP%\resolve-test-module-%RANDOM%%RANDOM%.txt"

if "%2"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "resolve-test-module.ps1" -TestSpec "%1" -TestTask "testDebugUnitTest" > "%tempFile%" 2>&1
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "resolve-test-module.ps1" -TestSpec "%1" -ModuleName "%2" -TestTask "testDebugUnitTest" > "%tempFile%" 2>&1
)

set "exitcode=%errorlevel%"

if %exitcode% neq 0 (
    type "%tempFile%"
    del "%tempFile%" >nul 2>&1
    exit /b %exitcode%
)

set /p gradleTask=<"%tempFile%"
del "%tempFile%" >nul 2>&1

call run-locked.bat gradlew.bat %gradleTask% --tests "%1" --no-daemon --console=plain --quiet --warning-mode none
if %errorlevel% neq 0 exit /b %errorlevel%
echo Tests passed
