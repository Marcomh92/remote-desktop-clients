@echo off
setlocal enabledelayedexpansion

if "%~1"=="" (
    echo Usage: %~nx0 ^<command^>
    exit /b 1
)

set "TempFile=%TEMP%\run-locked-%RANDOM%%RANDOM%.cmd"
echo %* > "%TempFile%"

powershell -ExecutionPolicy Bypass -File "run-with-lock.ps1" -OperationFile "%TempFile%" -LockFile ".build-lock"
set "exitcode=%errorlevel%"

del "%TempFile%" >nul 2>&1

if %exitcode% equ 100 (
    echo.
    echo Unable to compile and/or run the tests: another agent already has a build or test operation in progress.
    echo Please wait a minute and try again.
    echo If the script keeps failing, abort the operation and inform the user that you were not able
    echo to compile and/or run the tests because another agent already had a build or test operation
    echo in progress.
    exit /b 1
)

del "%TempFile%" >nul 2>&1
exit /b %exitcode%
