@echo off
title PadGuard Server - RUNNING, DO NOT CLOSE THIS WINDOW
cd /d "%~dp0"

REM =====================================================================
REM RULE 1 - NEVER run "java -jar build/libs/app.jar" directly.
REM
REM Rebuilding (gradlew bootJar) overwrites build/libs/app.jar IN PLACE.
REM If the server is running from that exact file, the live JVM keeps the
REM file open and the overwrite corrupts its class-loading view
REM (NoClassDefFoundError / "Recursion detected") so every request returns
REM 500 while the port still listens - a silent zombie.
REM
REM RULE 2 - ALWAYS start the server from THIS script.
REM
REM This script runs a STABLE snapshot at deploy/app.jar. A rebuild only
REM touches build/libs/app.jar and can never corrupt the running instance.
REM The guard (server-guard.ps1) also calls this script, so both paths are
REM safe.
REM =====================================================================

REM --- safety net: kill any server still running from build/libs/app.jar ---
REM This is the dangerous pattern above. If such an instance exists, stop it
REM before we start the safe one. The deploy snapshot is explicitly excluded.
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { ($_.CommandLine -like '*build*libs*app.jar*') -and ($_.CommandLine -notlike '*deploy*app.jar*') } | ForEach-Object { Write-Host ('killing dangerous build-path instance PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" >nul 2>&1

REM Always work from this .bat's own folder so that the relative paths
REM build/libs/app.jar, deploy/app.jar, ./data/padguard and server_restart.log are correct.
echo ==========================================================
echo   PadGuard server is starting, ready in about 8 seconds.
echo.
echo   *** IMPORTANT: KEEP THIS WINDOW OPEN ***
echo   This window IS the server process itself.
echo   Closing it or pressing Ctrl+C STOPS the server,
echo   and the parent app will lose connection immediately.
echo.
echo   TIP: use start-server-guard.bat instead. It restarts the
echo   server automatically if it ever stops responding.
echo ==========================================================
echo.

REM ---------------------------------------------------------------------
REM Why copy to deploy/app.jar first:
REM   Rebuilding (gradle bootJar) overwrites build/libs/app.jar IN PLACE.
REM   If the server is still running from that very file, the running JVM
REM   ends up with a corrupted class-loading view (kotlin-reflect
REM   NoClassDefFoundError / "Recursion detected in a lazy value") and
REM   every request returns 500. Copying the fresh build to a STABLE
REM   deploy/app.jar and running from there means a rebuild only touches
REM   build/libs/app.jar and can never corrupt the running instance.
REM ---------------------------------------------------------------------
if not exist deploy mkdir deploy
if exist build\libs\app.jar (
    copy /Y build\libs\app.jar deploy\app.jar >nul
    if errorlevel 1 (
        echo ERROR: failed to copy build/libs/app.jar to deploy/app.jar
        pause
        exit /b 1
    )
    echo Deployed fresh build
) else (
    echo ERROR: build/libs/app.jar not found. Run "gradlew bootJar" first.
    pause
    exit /b 1
)
REM Free port 8090 first: a stale instance holding the port would make the
REM new jar fail to bind. This makes the script an idempotent "restart".
REM (found by listening port, force-stopped via a PowerShell one-liner)
powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8090 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }" >nul 2>&1
timeout /t 1 >nul
echo.
REM ---------------------------------------------------------------------
REM Locate a Java 17 runtime automatically (no hard-coded absolute path).
REM Priority: 1) %JAVA_HOME%  2) Gradle toolchain cache  3) common install
REM dirs  4) PATH. Falls back with a clear error instead of a broken
REM hard-coded path (e.g. E:/dev/jdk-17) that does not exist everywhere.
REM ---------------------------------------------------------------------
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /d %%a in ("%USERPROFILE%\.gradle\jdks\*") do (
    if not defined JAVA_EXE for /d %%b in ("%%a\*") do (
        if exist "%%b\bin\java.exe" set "JAVA_EXE=%%b\bin\java.exe"
    )
)
if not defined JAVA_EXE for %%p in (
    "C:\Program Files\Eclipse Adoptium\jdk-17*\bin\java.exe"
    "C:\Program Files\Java\jdk-17*\bin\java.exe"
    "C:\Program Files\Amazon Corretto\jdk17*\bin\java.exe"
) do ( if not defined JAVA_EXE set "JAVA_EXE=%%~p" )
if not defined JAVA_EXE for /f "delims=" %%i in ('where java 2^>nul') do ( if not defined JAVA_EXE set "JAVA_EXE=%%i" )
if not defined JAVA_EXE (
    echo ERROR: no Java 17 runtime found. Set JAVA_HOME or install JDK 17.
    pause
    exit /b 1
)
echo Using Java runtime: %JAVA_EXE%

REM JVM flags:
REM  -XX:+ExitOnOutOfMemoryError
REM      Without this, an OOM leaves the JVM alive but broken: the port still
REM      listens while every request fails. A guard that checks the port cannot
REM      detect that state, so the server stays "up" and dead at the same time.
REM      Exiting turns a silent zombie into a clean restart.
REM  -Xmx512m
REM      Bounds memory on a family PC. The dataset here is tiny, so a small
REM      heap is plenty and avoids starving the whole machine.
REM  -Dlogging.register-bridge-handler=false
REM      Required. Otherwise the JUL-to-SLF4J bridge throws
REM      NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy
REM      inside request threads and takes down unlock / live-screen endpoints.
REM --spring.mvc.async.request-timeout=600000
REM      Fixes the live screen being cut every 30 seconds: the live stream is a
REM      long-lived connection, and Spring MVC's default 30s async timeout kills
REM      it even while frames are flowing normally.
"%JAVA_EXE%" -Xmx512m -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -Dlogging.register-bridge-handler=false -jar deploy/app.jar --spring.profiles.active=local --server.port=8090 --spring.datasource.url="jdbc:h2:file:./data/padguard;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" --spring.mvc.async.request-timeout=600000 > server_restart.log 2>&1
