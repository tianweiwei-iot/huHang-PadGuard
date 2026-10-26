#Requires -Version 5.1
# ============================================================
# PadGuard server maintenance script: backup + log rotation + restore
# Pure add-on tool, does not change any existing functionality.
# Schedule daily via Windows Task Scheduler.
# Usage:
#   Backup + rotate: maintain.ps1 -Backup -RotateLogs
#   Restore latest:  maintain.ps1 -RestoreLatest
#   Restore dir:     maintain.ps1 -RestoreFrom backups\padguard-backup-20261024-030000
# ============================================================

param(
    [switch]$Backup,
    [switch]$RotateLogs,
    [string]$Root = $PSScriptRoot,
    [string]$BackupDir = "",
    [int]$KeepN = 7,
    [double]$LogSizeMb = 10.0,
    [string]$RestoreFrom = "",
    [switch]$RestoreLatest
)

$ErrorActionPreference = 'Continue'
$root = Resolve-Path $Root
if (-not $BackupDir) { $BackupDir = Join-Path $root 'backups' }

function Log($m) {
    $t = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    Write-Output ($t + ' [maintain] ' + $m)
}

$noAction = (-not $Backup) -and (-not $RotateLogs) -and ($RestoreFrom -eq '') -and (-not $RestoreLatest)
if ($noAction) {
    Log "No action specified. Usage: maintain.ps1 -Backup [-RotateLogs] [-KeepN 7] | -RestoreLatest | -RestoreFrom [dir]"
    exit 0
}

if (($RestoreFrom -ne '') -or $RestoreLatest) {
    $fromDir = $RestoreFrom
    if ($RestoreLatest) {
        $latest = Get-ChildItem -Directory -Path $BackupDir -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'padguard-backup-*' } | Sort-Object CreationTime -Descending | Select-Object -First 1
        if (-not $latest) { Log "No recent backup to restore."; exit 1 }
        $fromDir = $latest.FullName
    }
    if (-not (Test-Path $fromDir)) { Log ("Restore failed: backup dir not found: " + $fromDir); exit 1 }

    $conn = Get-NetTCPConnection -LocalPort 8090 -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        $p = $conn[0].OwningProcess
        Log ("Stopping server PID " + $p + " before restore")
        Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 5
    }

    $srcData = Join-Path $fromDir 'padguard.mv.db'
    $srcUploads = Join-Path $fromDir 'uploads'
    $dataDir = Join-Path $root 'data'
    $uploadsDir = Join-Path $root 'uploads'
    if (Test-Path $srcData) {
        if (-not (Test-Path $dataDir)) { New-Item -ItemType Directory -Path $dataDir | Out-Null }
        Copy-Item -Path $srcData -Destination $dataDir -Force
        $srcTrace = Join-Path $fromDir 'padguard.trace.db'
        if (Test-Path $srcTrace) { Copy-Item -Path $srcTrace -Destination $dataDir -Force }
        Log "Restored H2 data file"
    }
    if (Test-Path $srcUploads) {
        if (-not (Test-Path $uploadsDir)) { New-Item -ItemType Directory -Path $uploadsDir | Out-Null }
        $rc = Get-Command robocopy -ErrorAction SilentlyContinue
        if ($rc) { & robocopy.exe $srcUploads $uploadsDir /E /R:1 /W:1 /NP | Out-Null }
        else { Copy-Item -Path (Join-Path $srcUploads '*') -Destination $uploadsDir -Recurse -Force -ErrorAction SilentlyContinue }
        Log "Restored uploads/ directory"
    }
    $guard = Join-Path $root 'start-server-guard.bat'
    if (Test-Path $guard) {
        Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', $guard -WorkingDirectory $root -WindowStyle Hidden | Out-Null
        Log "Restarted server via start-server-guard.bat"
    } else { Log "Guard script not found, please start the server manually" }
    exit 0
}

if ($Backup) {
    $dataDir = Join-Path $root 'data'
    $uploadsDir = Join-Path $root 'uploads'
    if (-not (Test-Path $dataDir)) {
        Log ("Skip backup: data dir not found: " + $dataDir + " (server may not have started yet)")
    } else {
        if (-not (Test-Path $BackupDir)) { New-Item -ItemType Directory -Path $BackupDir | Out-Null }
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $dest = Join-Path $BackupDir ('padguard-backup-' + $stamp)
        New-Item -ItemType Directory -Path $dest | Out-Null

        $h2 = Join-Path $dataDir 'padguard.mv.db'
        if (Test-Path $h2) {
            Copy-Item -Path $h2 -Destination $dest -Force
            $trace = Join-Path $dataDir 'padguard.trace.db'
            if (Test-Path $trace) { Copy-Item -Path $trace -Destination $dest -Force }
            Log ("Copied H2 data file to " + $dest)
        }
        if (Test-Path $uploadsDir) {
            $destU = Join-Path $dest 'uploads'
            $rc = Get-Command robocopy -ErrorAction SilentlyContinue
            if ($rc) { & robocopy.exe $uploadsDir $destU /E /R:1 /W:1 /NP | Out-Null }
            else { Copy-Item -Path (Join-Path $uploadsDir '*') -Destination $destU -Recurse -Force -ErrorAction SilentlyContinue }
        }
        Log ("Backup done: " + $dest)

        $all = Get-ChildItem -Directory -Path $BackupDir | Where-Object { $_.Name -like 'padguard-backup-*' } | Sort-Object CreationTime -Descending
        if ($all.Count -gt $KeepN) {
            $all | Select-Object -Skip $KeepN | ForEach-Object {
                Log ("Removing stale backup: " + $_.FullName)
                Remove-Item -Path $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

if ($RotateLogs) {
    $logDir = Join-Path $BackupDir 'logs'
    if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
    $limitBytes = [int]($LogSizeMb * 1024 * 1024)
    @('server_restart.log', 'guard.log') | ForEach-Object {
        $p = Join-Path $root $_
        if (-not (Test-Path $p)) { return }
        $sz = (Get-Item $p).Length
        if ($sz -lt $limitBytes) { return }
        $st = Get-Date -Format 'yyyyMMdd-HHmmss'
        $arc = Join-Path $logDir ($_ + '.' + $st)
        try {
            Move-Item -Path $p -Destination $arc -Force
            Log ("" + $_ + " (" + ('{0:N1}' -f ($sz/1MB)) + "MB) -> " + $arc)
        } catch {
            Log ("Log rotation failed: " + $_ + " (" + $_.Exception.Message + ")")
        }
    }
    $allLogs = Get-Item (Join-Path $logDir '*') -ErrorAction SilentlyContinue | Sort-Object CreationTime -Descending
    if ($allLogs.Count -gt $KeepN) {
        $allLogs | Select-Object -Skip $KeepN | ForEach-Object {
            Remove-Item -Path $_.FullName -Force -ErrorAction SilentlyContinue
        }
    }
}

Log "done."
