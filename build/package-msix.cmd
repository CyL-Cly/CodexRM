@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\package-msix.ps1" %*
set "codexrm_exit=%errorlevel%"
pause
exit /b %codexrm_exit%
