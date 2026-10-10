@echo off
title PadGuard 重新部署（鉴权/密码修复）
cd /d "%~dp0"

echo ==========================================================
echo  本脚本用于部署「鉴权修复 + 密码邮件/自助改密」后的新版本：
echo    1. 停止当前占用 8090 端口的旧服务端进程
echo    2. 把新构建 build/libs/app.jar 复制到 deploy/app.jar
echo    3. 通过 start-server-guard.bat 拉起服务端（含看门狗自恢复）
echo  请右键本文件选择“以管理员身份运行”。
echo ==========================================================
echo.

echo [1/3] 释放 8090 端口（停止旧服务端进程）...
for /f "tokens=5" %%a in ('netstat -ano 2^>nul ^| findstr ":8090" ^| findstr "LISTENING"') do (
    echo   结束监听进程 PID %%a
    taskkill /PID %%a /F >nul 2>&1
)
timeout /t 3 >nul

echo [2/3] 部署新构建 build/libs/app.jar -^> deploy/app.jar ...
if not exist build\libs\app.jar (
    echo   [错误] 未找到 build/libs/app.jar，请先执行 gradlew bootJar 构建后再运行本脚本。
    pause
    exit /b 1
)
copy /Y build\libs\app.jar deploy\app.jar
echo   已部署新版本。

echo [3/3] 启动服务端（start-server-guard.bat：隐藏窗口 + 看门狗）...
start "" start-server-guard.bat

echo 等待服务端启动（约 12 秒）...
timeout /t 12 >nul

echo 健康检查：
curl -s -o nul -w "  /health  HTTP %{http_code}\n" http://127.0.0.1:8090/health

echo.
echo 完成。请在浏览器对后台地址执行 Ctrl+F5 强制刷新。
echo 用 admin / 当前口令 登录（当前口令仍是 admin123，请登录后立刻用右上角“修改密码”改成强口令）。
echo.
echo 如需回滚：先停止服务端，再把 deploy/app.jar.bak-20261009-preauthfix 复制回 deploy/app.jar 后重启。
echo.
pause
