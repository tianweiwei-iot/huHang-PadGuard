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
REM  -Dfile.encoding=UTF-8 (+ stdout/stderr)
REM      日志是排障的第一现场。Windows 默认 file.encoding=GBK(cp936)，于是
REM      server_restart.log 里所有中文都是 GBK 字节：用 grep/tail/VSCode/日志平台
REM      按 UTF-8 读全是乱码，连"启动中止：xxx 配置项"这种关键报错都搜不到，
REM      只能先 iconv 转码 —— 而排障时最缺的恰恰是耐心。
REM      这里把三处编码都钉成 UTF-8。注意：重定向到文件时控制台不参与，所以
REM      不会出现"窗口里中文乱码"；若哪天改成前台运行，cmd 窗口(cp936)会显示乱码，
REM      届时用 chcp 65001 即可，不要为了窗口好看把日志改回 GBK。
REM  --spring.mvc.async.request-timeout
REM      ---- 刻意不在这里传 ----
REM      The live screen is a long-lived streaming response; Spring MVC's 30s
REM      default async timeout kills it mid-stream. The value now lives ONLY in
REM      application.yml (1800000 = 30 min) so there is exactly one source of
REM      truth. Do NOT re-add a command-line override: command-line beats yml,
REM      and the two values WILL drift (this script previously said 600000 while
REM      the running instance used 1800000, so every restart via this script
REM      silently cut live screen back to 10 minutes).
REM -Duser.timezone=Asia/Shanghai
REM     家庭/本机部署时链路中的重要一环：不加的话 JVM 默认时区取 Windows 系统时区，
REM     家长电脑时区一旦不是北京时间，后台时间显示、统计日切、cron 清理任务全部偏 8 小时。
REM     显式钉死后，无论本机系统时区如何（哪怕装机默认 UTC），服务端恒按北京时间运行。
REM --padguard.amap.web-key=%PADGUARD_AMAP_WEB_KEY%
REM     高德 Web 服务 Key（逆地理编码）。从用户环境变量读取，不在脚本中写明文；
REM     缺省为空时服务端不做地址反查，定位坐标照常上报。运维用 setx 注入一次即可持久生效。
REM
REM     兜底：若当前会话未继承到该环境变量（老终端 / IDE 集成终端不会在每次命令时
REM     重新读取 HKCU 注册表），直接从注册表取出此前 setx 持久化的值，避免以空 Key 启动
REM     导致逆地理编码静默失效。Key 仍不写死在脚本里，只在此处读取。
if not defined PADGUARD_AMAP_WEB_KEY (
    for /f "usebackq delims=" %%k in (`powershell -NoProfile -Command "(Get-ItemProperty -Path 'HKCU:\Environment' -Name PADGUARD_AMAP_WEB_KEY -ErrorAction SilentlyContinue).PADGUARD_AMAP_WEB_KEY"`) do set "PADGUARD_AMAP_WEB_KEY=%%k"
)
REM ---------------------------------------------------------------------
REM 安全基线：本启动器用于「家庭/私域发布包」，激活的是 **home** 档位，不是 local。
REM
REM 为什么必须是 home：old local 档位在 ProdConfigAssertion.DEV_PROFILES 里，
REM 一旦用它跑发布包，下面四条生产红线闸门会全部失效（曾经就是如此）：
REM   1) padguard.billing.mock-pay-enabled=true 不再中止启动 —— 等于放行所有支付回调；
REM   2) 后台「系统设置」允许运营把 mockPay 打开（isDevProfile=true）—— 同上，可白嫖会员；
REM   3) 短信免密登录闸门被当成开发环境放行（AuthService）；
REM   4) 演示账号/测试账号的播种条件退化为"只要开关打开就播种"。
REM home 档位与 local 的差别只有档位名（同样 H2 文件库、同样 8090、同样关 MQTT），
REM 数据仍然落在 ./data/padguard，从 local 切过来不丢任何绑定关系。
REM 需要联调请用 start-local.bat（显式 local + 演示账号），不要在这里改回 local。
REM
REM 另外：本脚本**不要**再传 --spring.mvc.async.request-timeout。看屏流的超时
REM 唯一权威来源是 application.yml（30 分钟）；此前脚本里写着 600000（10 分钟），
REM 而运行实例是手工用 1800000 起的 —— 谁用脚本重启一次，看屏就被悄悄砍回 10 分钟，
REM 且没有任何提示。命令行参数优先级高于 yml，这类"两处各写一个值"的坑必须从源头删掉。
REM ---------------------------------------------------------------------
"%JAVA_EXE%" -Xmx512m -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -Dlogging.register-bridge-handler=false -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar deploy/app.jar --spring.profiles.active=home --padguard.amap.web-key=%PADGUARD_AMAP_WEB_KEY% > server_restart.log 2>&1
