#Requires -Version 5.1
<#
    Register (or remove) the PadGuard server guard as a Windows logon task.

    Run INSTALL-AUTOSTART.BAT as the same Windows account that normally logs in.
    Run INSTALL-AUTOSTART.BAT -uninstall to remove it.

    NOTE: this cannot be done from an automated/sandboxed session - scheduled task
    writes get isolated and silently discarded. It has to be run interactively.
#>

param([switch]$Uninstall)

$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $MyInvocation.MyCommand.Definition
$task   = 'PadGuardServerGuard'
$vbs    = Join-Path $root 'start-server-guard-silent.vbs'

function Write-Step([string]$Message) { Write-Host $Message }

if (-not ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()
        ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host ''
    Write-Host 'NOTE: not running as Administrator.' -ForegroundColor Yellow
    Write-Host 'If registration fails, right-click INSTALL-AUTOSTART.BAT and choose' -ForegroundColor Yellow
    Write-Host '"Run as administrator", then run it again.' -ForegroundColor Yellow
    Write-Host ''
}

if ($Uninstall) {
    try {
        Unregister-ScheduledTask -TaskName $task -Confirm:$false -ErrorAction Stop
        Write-Step "Removed scheduled task '$task'. Server guard will no longer start at logon."
    } catch {
        Write-Step "Task '$task' was not registered (nothing to remove)."
    }
    exit 0
}

if (-not (Test-Path $vbs)) {
    Write-Host "ERROR: $vbs not found." -ForegroundColor Red
    exit 1
}

try {
    # Run the guard in one-shot mode every minute rather than as a resident loop.
    # A resident process can be closed by the operator or reaped by a sandbox;
    # a scheduled task spawns a fresh process each tick, so there is nothing to
    # close and nothing to reap. Consecutive-failure counting is persisted in
    # guard.state, so it still waits for 3 misses before restarting.
    $guardPs1 = Join-Path $root 'server-guard.ps1'
    $action   = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$guardPs1`" -Once"

    # AtLogOn (not AtStartup): the server must run under the logged-in account so
    # it can read ./data and write logs in its own folder with normal permissions.
    # The repeating trigger keeps it alive afterwards.
    $trigger  = @(
        (New-ScheduledTaskTrigger -AtLogOn),
        (New-ScheduledTaskTrigger -Once -At ((Get-Date).AddMinutes(1)) `
            -RepetitionInterval (New-TimeSpan -Minutes 1) `
            -RepetitionDuration (New-TimeSpan -Days 9999))
    )
    $settings = New-ScheduledTaskSettingsSet `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries `
        -StartWhenAvailable `
        -RestartCount 3 `
        -RestartInterval (New-TimeSpan -Minutes 1) `
        -ExecutionTimeLimit (New-TimeSpan -Days 9999)

    Register-ScheduledTask -TaskName $task -Action $action -Trigger $trigger `
        -Settings $settings -Description 'Keeps the PadGuard server running' -Force | Out-Null

    Write-Step ''
    Write-Step "Registered '$task'." -ForegroundColor Green
    Write-Step 'The server will now start automatically every time you log in to Windows.'

    # Start it right now too, so there is no need to wait for the next logon.
    Start-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    Write-Step 'Guard started now as well - the server is coming up.'
} catch {
    Write-Host ''
    Write-Host "FAILED: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host 'Right-click INSTALL-AUTOSTART.BAT and choose "Run as administrator".' -ForegroundColor Yellow
    exit 1
}

Write-Host ''
Write-Host 'Verify with: schtasks /query /tn PadGuardServerGuard'
exit 0
