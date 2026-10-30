@echo off
setlocal
cd /d "%~dp0\.."

where powershell.exe >nul 2>nul
if errorlevel 1 (
  echo Windows PowerShell is required to bootstrap the local server.
  pause
  exit /b 1
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-local.ps1"
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
  echo.
  echo Aurora local startup failed with exit code %EXIT_CODE%.
  pause
)
exit /b %EXIT_CODE%
