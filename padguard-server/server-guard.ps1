#Requires -Version 5.1
<#
    PadGuard server guard: keeps the server alive.

    Why this exists: the server is a plain java process started by a .bat. If the
    window is closed, the JVM exits on OOM, or the machine reboots, nothing brings
    it back and the parent app simply "cannot connect". This loop probes the health
    endpoint and restarts the server when it stops answering.

    Detection is deliberately NOT a TCP port check. The failure mode seen in
    practice is a half-dead JVM: the port still accepts connections while every
    request returns 500. A port check calls that "alive". /health runs a real
    SELECT 1, so it reports DOWN for that state.
#>

param(
    [int]$Port = 8090,
    [int]$CheckIntervalSec = 15,
    # Restart only after several consecutive failures. A single miss is usually
    # just a slow request during startup; restarting on the first miss would race
    # with the booting instance over the H2 file lock and turn one slow start
    # into an endless restart loop.
    [int]$FailThreshold = 3,
    # One-shot mode: run a single check, then exit.
    #
    # This exists because a long-lived guard process is itself fragile - it can be
    # closed by the operator or reaped by a sandbox. A scheduled task that fires
    # this script every minute spawns a fresh process each time, so there is
    # nothing to close and nothing to reap. Consecutive-failure counting is
    # persisted to a state file so restart still waits for FailThreshold misses.
    [switch]$Once
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $root

$logFile  = Join-Path $root 'guard.log'
$lockFile = Join-Path $root 'guard.lock'

function Write-GuardLog {
    param([string]$Message)
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Add-Content -Path $logFile -Value $line -Encoding UTF8
    Write-Host $line
}

# ---------------------------------------------------------------- single instance
# Two guards would fight each other: each would see the other's restart as a
# failure and restart again. Claim a lock file holding our own PID; if an existing
# lock belongs to a live process, step aside.
if (-not $Once) {
    if (Test-Path $lockFile) {
        $oldPid = (Get-Content $lockFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        $owner = if ($oldPid -match '^\d+$') { Get-Process -Id ([int]$oldPid) -ErrorAction SilentlyContinue } else { $null }
        if ($owner) {
            Write-Host "Another guard is already running (PID $oldPid). Exiting."
            exit 0
        }
        Remove-Item $lockFile -Force -ErrorAction SilentlyContinue
    }
    Set-Content -Path $lockFile -Value $PID -Encoding UTF8
}

function Invoke-HealthProbe {
    try {
        $req = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$Port/health")
        # The machine has a local HTTP proxy configured; without this, requests to
        # 127.0.0.1 get routed through it and fail with a bogus 502.
        $req.Proxy = $null
        $req.Timeout = 5000
        $req.Method = 'GET'
        $resp = $req.GetResponse()
        $code = [int]$resp.StatusCode
        $resp.Close()
        return ($code -eq 200)
    } catch {
        return $false
    }
}

function Get-ServerProcessId {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($conn) { return $conn[0].OwningProcess }
    return $null
}

function Restart-Server {
    param([string]$Reason)

    $existing = Get-ServerProcessId
    if ($existing) {
        Write-GuardLog "restart: stopping PID $existing ($Reason)"
        Stop-Process -Id $existing -Force -ErrorAction SilentlyContinue
        # H2 holds an exclusive file lock; starting before it is released makes
        # the new instance abort on boot with "database may be already in use".
        Start-Sleep -Seconds 5
    } else {
        Write-GuardLog "restart: no process on port $Port ($Reason)"
    }

    Write-GuardLog 'restart: launching server'
    # Hidden window: the old failure mode was the operator closing the visible
    # console window, which IS the server process. No window means nothing to close.
    Start-Process -FilePath 'cmd.exe' `
        -ArgumentList '/c', 'start-server.bat' `
        -WorkingDirectory $root `
        -WindowStyle Hidden | Out-Null
}

# ------------------------------------------------------------------ one-shot mode
if ($Once) {
    $stateFile = Join-Path $root 'guard.state'
    $misses = 0
    if (Test-Path $stateFile) {
        $raw = Get-Content $stateFile -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($raw -match '^\d+$') { $misses = [int]$raw }
    }

    if (Invoke-HealthProbe) {
        if ($misses -gt 0) { Write-GuardLog 'one-shot: healthy again, miss counter reset' }
        Set-Content -Path $stateFile -Value 0 -Encoding UTF8
        exit 0
    }

    $misses++
    if ($misses -lt $FailThreshold) {
        Write-GuardLog "one-shot: unhealthy ($misses/$FailThreshold) - waiting for threshold"
        Set-Content -Path $stateFile -Value $misses -Encoding UTF8
        exit 0
    }

    Write-GuardLog "one-shot: unhealthy ($misses/$FailThreshold) - restarting"
    Set-Content -Path $stateFile -Value 0 -Encoding UTF8
    Restart-Server 'one-shot: unhealthy'
    exit 0
}

Write-GuardLog "guard started (PID $PID, port $Port, every ${CheckIntervalSec}s, fail after $FailThreshold misses)"

$failCount = 0
$restarts  = 0

# Boot once if nothing is answering, so a double-click alone is enough.
if (-not (Invoke-HealthProbe)) {
    Restart-Server 'startup: server not answering'
    $restarts++
}

while ($true) {
    Start-Sleep -Seconds $CheckIntervalSec

    if (Invoke-HealthProbe) {
        if ($failCount -gt 0) { Write-GuardLog 'recovered: server is healthy again' }
        $failCount = 0
        continue
    }

    $failCount++
    Write-GuardLog "health check FAILED ($failCount/$FailThreshold)"
    if ($failCount -lt $FailThreshold) { continue }

    $restarts++
    Write-GuardLog "restart #$restarts"
    Restart-Server 'unresponsive'
    $failCount = 0

    # Give it time to boot (cold start is ~8s) before probing again; otherwise the
    # next few checks fail during a normal startup and trigger another restart.
    Start-Sleep -Seconds 25
}
