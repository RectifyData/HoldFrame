@echo off
cd /d "%~dp0"
if not exist "dist\HoldFrame.jar" (
  echo HoldFrame is not built yet. Install JDK 17+ and run build.ps1 first.
  pause
  exit /b 1
)
start "" javaw -jar "dist\HoldFrame.jar"
