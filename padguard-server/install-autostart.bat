@echo off
title PadGuard - Install auto start
cd /d "%~dp0"
echo ==========================================================
echo   PadGuard server - start automatically at Windows login
echo.
echo   This registers the server guard so the server comes up
echo   by itself every time you log in to Windows, and restarts
echo   itself if it ever stops responding.
echo ==========================================================
echo.

if /i "%1"=="/uninstall" (
    echo Removing auto start...
    powershell -NoProfile -ExecutionPolicy Bypass -File "install-autostart.ps1" -Uninstall
) else (
    echo Installing auto start...
    powershell -NoProfile -ExecutionPolicy Bypass -File "install-autostart.ps1"
)

echo.
echo Press any key to close.
pause > nul
