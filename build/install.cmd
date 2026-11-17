@echo off
setlocal
if not exist "%~dp0dist\CodexRM-Setup.exe" (
  echo Native installer not found: "%~dp0dist\CodexRM-Setup.exe"
  exit /b 1
)
"%~dp0dist\CodexRM-Setup.exe" %*
exit /b %errorlevel%
