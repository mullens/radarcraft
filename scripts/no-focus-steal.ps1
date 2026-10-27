<#
no-focus-steal.ps1 -GamePid <pid> [-Seconds 420]
Watches a launching Minecraft process. Whenever one of its windows takes the foreground without the user having
clicked it (the early loading window, then the main window when it first appears), the previous foreground window
is restored and the game window is kept behind it. Stops once the main game window has been up for 20 seconds, or
after -Seconds. Started automatically by scripts/play.py.
#>
param([Parameter(Mandatory = $true)][int]$GamePid, [int]$Seconds = 420)

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class FG {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern short GetAsyncKeyState(int vk);
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, System.Text.StringBuilder s, int n);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
    public static string Title(IntPtr h) { var sb = new System.Text.StringBuilder(256); GetWindowText(h, sb, 256); return sb.ToString(); }
}
"@
$HWND_BOTTOM = [IntPtr]1; $SWP = 0x0001 -bor 0x0002 -bor 0x0010   # NOSIZE | NOMOVE | NOACTIVATE
$prev = [FG]::GetForegroundWindow()
$deadline = (Get-Date).AddSeconds($Seconds); $mainSeen = $null; $restored = 0
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 120
    if (-not (Get-Process -Id $GamePid -ErrorAction SilentlyContinue)) { break }
    $fg = [FG]::GetForegroundWindow(); $wpid = [uint32]0; [void][FG]::GetWindowThreadProcessId($fg, [ref]$wpid)
    if ($wpid -ne $GamePid) { $prev = $fg; continue }
    $title = [FG]::Title($fg)
    if ($title -like "Minecraft*" -and -not $mainSeen) { $mainSeen = Get-Date }
    # the user clicked into the game himself: leave it alone from now on
    if (([FG]::GetAsyncKeyState(0x01) -band 0x8000) -ne 0) { break }
    if ($mainSeen -and ((Get-Date) - $mainSeen).TotalSeconds -gt 20) { break }
    if ($prev -ne [IntPtr]::Zero -and $prev -ne $fg) {
        [void][FG]::SetWindowPos($fg, $HWND_BOTTOM, 0, 0, 0, 0, $SWP)
        $me = [FG]::GetCurrentThreadId(); $p2 = 0; $t = [FG]::GetWindowThreadProcessId($fg, [ref]$p2)
        [void][FG]::AttachThreadInput($me, $t, $true)
        [void][FG]::SetForegroundWindow($prev)
        [void][FG]::AttachThreadInput($me, $t, $false)
        $restored++
    }
}
"focus guard done: restored focus $restored time(s)" | Out-File -Append (Join-Path $env:TEMP "no-focus-steal.log")
