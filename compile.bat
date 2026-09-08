@echo off
call run-locked.bat gradlew.bat assembleDebug --no-daemon --console=plain --quiet --warning-mode none
if %errorlevel% neq 0 exit /b %errorlevel%
echo Project compiled successfully
