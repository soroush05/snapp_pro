@echo off
cd /d "%~dp0"
where node >nul 2>nul
if %errorlevel% neq 0 (
  echo Node.js is required for the web companion.
  pause
  exit /b 1
)
node static-server.js
