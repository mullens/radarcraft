<#
.SYNOPSIS
  Captures the Minecraft dev client window to a PNG WITHOUT focusing it, using PrintWindow
  (works for unfocused and off-screen windows; a hidden window cannot be captured, use the
  driver's in-game `screenshot` command for that).
.EXAMPLE
  powershell -File scripts/screenshot.ps1 -Out screenshots/title.png
#>
param(
    [string]$Out = "screenshots/shot.png",
    [string]$TitleMatch = "Minecraft"
)
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\Win32.ps1"

if (-not ([System.Management.Automation.PSTypeName]'McPrint').Type) {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class McPrint {
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdc, uint flags);
}
"@
}

$proc = Get-McWindow -TitleMatch $TitleMatch
$r = Get-McClientRect $proc
if ($r.Width -le 0 -or $r.Height -le 0) { throw "Window has no client area (minimized or hidden?)" }

$bmp = New-Object System.Drawing.Bitmap $r.Width, $r.Height
$gfx = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $gfx.GetHdc()
$ok = [McPrint]::PrintWindow($proc.MainWindowHandle, $hdc, 0x3)  # PW_CLIENTONLY | PW_RENDERFULLCONTENT
$gfx.ReleaseHdc($hdc)
$dir = Split-Path -Parent $Out
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
$full = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Out))
$bmp.Save($full, [System.Drawing.Imaging.ImageFormat]::Png)
$gfx.Dispose(); $bmp.Dispose()
if (-not $ok) { Write-Warning "PrintWindow reported failure; the image may be black. Prefer the driver's in-game screenshot command." }
Write-Output "Saved $full ($($r.Width)x$($r.Height), window '$($proc.MainWindowTitle)', not focused)"
