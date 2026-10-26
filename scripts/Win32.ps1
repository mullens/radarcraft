# Shared Win32 helpers for driving and capturing the Minecraft dev client.
# Dot-source this file: . "$PSScriptRoot\Win32.ps1"

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms

if (-not ([System.Management.Automation.PSTypeName]'McWin32').Type -or -not ([McWin32].GetField('ABSOLUTE'))) {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class McWin32 {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr hWnd, ref POINT p);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    public const uint MOVE = 0x01, LEFTDOWN = 0x02, LEFTUP = 0x04, RIGHTDOWN = 0x08, RIGHTUP = 0x10, WHEEL = 0x0800, VIRTUALDESK = 0x4000, ABSOLUTE = 0x8000;
}
"@
}
[void][McWin32]::SetProcessDPIAware()

function Get-McWindow {
    # Only Java processes whose window title *starts* with the match, so a browser tab about
    # Minecraft is never mistaken for the game. The NeoForge loading splash is skipped too.
    param([string]$TitleMatch = "Minecraft")
    $proc = Get-Process -Name java, javaw -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like "$TitleMatch*" -and $_.MainWindowTitle -notlike "*Loading*" } |
        Select-Object -First 1
    if (-not $proc) { throw "No Java window with a title starting with '$TitleMatch' was found. Is the client running and past the loading screen?" }
    return $proc
}

function Get-McClientRect {
    param($Proc)
    $rect = New-Object McWin32+RECT
    [void][McWin32]::GetClientRect($Proc.MainWindowHandle, [ref]$rect)
    $origin = New-Object McWin32+POINT
    [void][McWin32]::ClientToScreen($Proc.MainWindowHandle, [ref]$origin)
    return [pscustomobject]@{ X = $origin.X; Y = $origin.Y; Width = $rect.Right - $rect.Left; Height = $rect.Bottom - $rect.Top }
}

function Focus-McWindow {
    param($Proc)
    # Windows only lets a process steal focus after it has sent input; a bare Alt press does the trick.
    [System.Windows.Forms.SendKeys]::SendWait("%")
    [void][McWin32]::ShowWindow($Proc.MainWindowHandle, 9) # SW_RESTORE
    [void][McWin32]::SetForegroundWindow($Proc.MainWindowHandle)
    Start-Sleep -Milliseconds 300
}
