# One-click installer for Eclipse Temurin JDK 17 (LTS) on Windows x64.
# Usage: right-click this file -> "Run with PowerShell" (as Administrator).

$ErrorActionPreference = "Stop"

$url = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
$msi = Join-Path $env:TEMP "temurin17-jdk.msi"

Write-Host "[1/3] Downloading JDK 17 from Adoptium ..." -ForegroundColor Cyan
Invoke-WebRequest -Uri $url -OutFile $msi -UseBasicParsing

Write-Host "[2/3] Installing silently (requires Administrator) ..." -ForegroundColor Cyan
Start-Process msiexec.exe -ArgumentList "/i", "`"$msi`"", "/quiet", "/norestart" -Wait

Write-Host "[3/3] Done." -ForegroundColor Green
Write-Host "Default install path: C:\Program Files\Eclipse Adoptium\jdk-17" -ForegroundColor Yellow
Write-Host "Next steps in IntelliJ IDEA:" -ForegroundColor Yellow
Write-Host "  1. File -> Project Structure -> SDKs -> + -> Add JDK -> select the path above"
Write-Host "  2. Settings -> Build, Execution, Deployment -> Build Tools -> Gradle -> set 'Gradle JVM' to JDK 17"
Write-Host "  3. Click 'Reload Gradle Project'"
