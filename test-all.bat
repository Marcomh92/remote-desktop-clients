@echo off
call run-locked.bat gradlew.bat test --no-daemon --console=plain --quiet --warning-mode none
if %errorlevel% neq 0 exit /b %errorlevel%
echo All tests passed
