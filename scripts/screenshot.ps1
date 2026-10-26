<#
.SYNOPSIS
  Captures the Minecraft dev client window to a PNG.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/screenshot.ps1 -Out screenshots/title.png
#>
param(
    [string]$Out = "screenshots/shot.png",
    [string]$TitleMatch = "Minecraft",
    [switch]$NoFocus
)
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\Win32.ps1"

$proc = Get-McWindow -TitleMatch $TitleMatch
if (-not $NoFocus) { Focus-McWindow $proc }
$r = Get-McClientRect $proc
if ($r.Width -le 0 -or $r.Height -le 0) { throw "Window has no client area (minimized?)" }

$bmp = New-Object System.Drawing.Bitmap $r.Width, $r.Height
$gfx = [System.Drawing.Graphics]::FromImage($bmp)
$gfx.CopyFromScreen($r.X, $r.Y, 0, 0, $bmp.Size)
$dir = Split-Path -Parent $Out
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
$full = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Out))
$bmp.Save($full, [System.Drawing.Imaging.ImageFormat]::Png)
$gfx.Dispose(); $bmp.Dispose()
Write-Output "Saved $full ($($r.Width)x$($r.Height), window '$($proc.MainWindowTitle)')"
