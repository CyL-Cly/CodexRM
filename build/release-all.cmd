@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\release-all.ps1" %*
set "codexrm_exit=%errorlevel%"
if /i not "%~1"=="nopause" pause
exit /b %codexrm_exit%
