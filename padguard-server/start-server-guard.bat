@echo off
title PadGuard Server Guard - DO NOT CLOSE THIS WINDOW
cd /d "%~dp0"
echo ==========================================================
echo   PadGuard server guard
echo.
echo   Keeps the server running. It checks every 15 seconds and
echo   restarts the server automatically if it stops responding.
echo.
echo   *** KEEP THIS WINDOW OPEN ***
echo   Closing it stops the guard (the server keeps running,
echo   but will no longer be restarted if it dies).
echo ==========================================================
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "server-guard.ps1"
echo.
echo Guard exited. Press any key to close.
pause > nul
