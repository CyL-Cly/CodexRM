@echo off
setlocal
rem Usage: build.bat [nopause] [-BuildOnly] [-KeepVersion] [-PublishOnly] [-ReleaseNotes "notes"]
set "CODEXRM_ANDROID_NOPAUSE="
if /i "%~1"=="nopause" set "CODEXRM_ANDROID_NOPAUSE=1"
if /i "%~1"=="-Help" set "CODEXRM_ANDROID_NOPAUSE=1"
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\build\scripts\release-android.ps1" %*
set "codexrm_android_exit=%errorlevel%"
if not defined CODEXRM_ANDROID_NOPAUSE pause
exit /b %codexrm_android_exit%
