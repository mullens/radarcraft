<#
.SYNOPSIS
  Blocks until the Minecraft dev client window is up (past the NeoForge loading splash), then waits a little longer for the first screen.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/wait-for-game.ps1 -TimeoutSec 240 -SettleSec 8
#>
param(
    [int]$TimeoutSec = 240,
    [int]$SettleSec = 8,
    [string]$TitleMatch = "Minecraft"
)
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$found = $null
while ((Get-Date) -lt $deadline) {
    $found = Get-Process -Name java, javaw -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like "$TitleMatch*" -and $_.MainWindowTitle -notlike "*Loading*" } |
        Select-Object -First 1
    if ($found) { break }
    Start-Sleep -Seconds 3
}
if (-not $found) { Write-Error "Game window did not appear within $TimeoutSec s"; exit 1 }
Start-Sleep -Seconds $SettleSec
Write-Output "Game window ready: '$($found.MainWindowTitle)' pid $($found.Id)"
