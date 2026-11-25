@echo off
setlocal
set "CODEXRM_NOPAUSE="
if /i "%~1"=="nopause" (
    set "CODEXRM_NOPAUSE=1"
    shift /1
)
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\release-server.ps1" %1 %2 %3 %4 %5 %6 %7 %8 %9
set "codexrm_exit=%errorlevel%"
if not defined CODEXRM_NOPAUSE pause
exit /b %codexrm_exit%
