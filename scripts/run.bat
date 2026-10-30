@echo off
setlocal
cd /d "%~dp0\.."

where docker >nul 2>nul
if errorlevel 1 (
  echo Docker is required. Install Docker Desktop with the Compose plugin, then run this file again.
  echo If Docker Desktop is already installed, start it and wait until it is running.
  pause
  exit /b 1
)

docker compose up --build
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
  echo.
  echo Aurora server exited with code %EXIT_CODE%.
  pause
)
exit /b %EXIT_CODE%
