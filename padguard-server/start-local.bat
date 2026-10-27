@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ===========================================================================
REM  PadGuard 服务端 —— 本地一键启动脚本（免 Docker）
REM
REM  用法：
REM     start-local.bat                 默认：local profile，端口 8080，关闭 MQTT
REM     start-local.bat 8090            指定端口
REM     start-local.bat 8080 mqtt       额外开启 MQTT（需本机/局域网已有 broker）
REM
REM  为什么用 local profile：
REM  H2 文件库 + 关闭 MQTT + 关闭 Flyway，只依赖本机 JDK 17，
REM  不需要 Docker / PostgreSQL / EMQX，够跑完"绑定 → 拉策略 → 下发指令"的主链路。
REM
REM  已经踩过的两个坑（脚本里都已规避）：
REM  1. 必须显式传 --server.port。某些终端/IDE 会注入 SERVER__PORT 环境变量，
REM     Spring Boot 的宽松绑定会把它解析成 server.port，导致服务监听到意外端口
REM     （曾表现为 "Port 64269 was already in use" 启动失败）。
REM     命令行参数优先级最高，可覆盖环境变量。
REM  2. base-url 保持留空。**不要**写成 localhost，也不要沿用 application.yml 里的部署地址：
REM     - 沿用部署地址（如 http://8.148.68.183:8090）：文件实际存在本机 uploads 下，
REM       客户端拿这个地址去那台机器下载必然 404 —— 孩子端表现为「视频加载失败」。
REM     - 写成 http://localhost:PORT：平板上的 localhost 是平板自己，同样是坏链。
REM     留空后服务端下发相对路径 /v1/files/{id}，由客户端用自己连的那个服务端补齐 origin，
REM     服务端换 IP / 换环境都不会失效。
REM ===========================================================================

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"

set "JAR=build\libs\app.jar"
set "PORT=%~1"
if "%PORT%"=="" set "PORT=8080"
set "MQTT_ARG=%~2"
set "PROFILE=local"

REM 是否启用 MQTT（默认关闭：没有 broker 时强连会让启动卡在重连循环里）
set "MQTT_FLAG=--padguard.mqtt.enabled=false"
if /i "%MQTT_ARG%"=="mqtt" (
    set "MQTT_FLAG=--padguard.mqtt.enabled=true --padguard.mqtt.broker-url=tcp://127.0.0.1:1883"
)

echo ===========================================================================
echo   PadGuard 服务端 · 本地启动
echo   profile = %PROFILE%    port = %PORT%    MQTT = %MQTT_ARG%
echo ===========================================================================
echo.

REM ---- 1. 校验 JDK ----
java -version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未检测到 java。请先安装 JDK 17 并确保已加入 PATH。
    echo       项目编译目标锁定 JDK 17，更高版本（如 JDK 25）Gradle 8.8 不支持。
    goto :fail
)

for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JV=%%~v"
echo [OK] 检测到 Java：%JV%

REM ---- 2. 校验 Jar（没有就顺手构建一次）----
if not exist "%JAR%" (
    echo [提示] 未找到 %JAR%，开始构建...
    call gradlew.bat bootJar
    if errorlevel 1 (
        echo [错误] 构建失败，请查看上方 Gradle 输出。
        goto :fail
    )
)
echo [OK] 使用 %JAR%

REM ---- 3. 端口占用预检 ----
netstat -ano | findstr "LISTENING" | findstr ":%PORT% " >nul 2>&1
if not errorlevel 1 (
    echo [警告] 端口 %PORT% 已被占用：
    netstat -ano | findstr "LISTENING" | findstr ":%PORT% "
    echo        若这是上一轮没退干净的服务端实例，建议先停掉它再启动。
    echo.
    choice /c YN /n /t 8 /d Y /m "    仍要继续尝试启动吗？(Y=继续 / N=退出)"
    if errorlevel 2 goto :eof
)

REM ---- 4. 打印联调要用的地址 ----
echo.
echo [联调地址] 真机/平板请用下面这些，不要用 localhost（手机上 localhost 是它自己）：
echo            健康检查： http://localhost:%PORT%/health
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
    set "IP=%%a"
    set "IP=!IP: =!"
    if not "!IP!"=="" echo            服务端地址： http://!IP!:%PORT%
)
echo            注意：模拟器请用 10.0.2.2 指代宿主机，写 127.0.0.1 会连到模拟器自己。
echo.
echo [停止服务] Ctrl+C
echo [访问日志] tomcat-work\logs\padguard-access*.log
echo            （排查"请求到底有没有到达服务端"时先看它）
echo ---------------------------------------------------------------------------

REM base-url 保持留空（不再写死 localhost）：
REM   写死 localhost 时，平板拿到的文件地址是 http://localhost:PORT/...，
REM   而平板上的 localhost 是平板自己 → 同样全是坏链。
REM   留空后服务端下发相对路径 /v1/files/{id}，由孩子端 MediaUrlResolver /
REM   家长端 retrofit.baseUrl().resolve() 各自用"自己连的那个服务端"拼 origin，
REM   服务端换 IP、换部署环境都不会失效。
REM 确需固定对外地址时用环境变量注入：set PADGUARD_BASE_URL=https://files.example.com
java -jar "%JAR%" ^
    --spring.profiles.active=%PROFILE% ^
    --server.port=%PORT% ^
    --padguard.base-url= ^
    --padguard.demo-accounts-enabled=true ^
    %MQTT_FLAG%

echo.
echo ---------------------------------------------------------------------------
echo 服务端已退出。
if errorlevel 1 goto :fail
goto :eof

:fail
echo.
echo 启动未完成，请按上方提示排查。
pause
