<#
.SYNOPSIS
  Drives the Minecraft dev client with mouse clicks and key presses.
.DESCRIPTION
  Each action is "verb:argument". Coordinates are client-area pixels of the game window.
    click:X,Y        left click            rclick:X,Y     right click
    move:X,Y         move the cursor       wheel:N        scroll N notches (negative = down)
    type:TEXT        type literal text     key:{ENTER}    SendKeys sequence, e.g. {ESC} {F3} ^(q)
    hold:KEY,MS      hold a SendKeys key for MS ms (uses keybd_event for letters a-z)
    wait:MS          sleep
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/send-input.ps1 click:640,368 wait:800 key:{ESC}
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)] [string[]]$Actions,
    [Parameter()] [string]$TitleMatch = "Minecraft"
)
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\Win32.ps1"

$proc = Get-McWindow -TitleMatch $TitleMatch
Write-Warning "send-input.ps1 moves the REAL mouse and keyboard. Do not run it while someone is using the PC; prefer the in-game driver."
$r = Get-McClientRect $proc

function Move-Mouse([int]$x, [int]$y) {
    # SetCursorPos alone does not reach GLFW's cursor callback; a tiny relative mouse_event
    # nudge (+1 then -1 pixel) generates the WM_MOUSEMOVE the game needs, at the right spot.
    [void][McWin32]::SetCursorPos($x, $y)
    Start-Sleep -Milliseconds 40
    [McWin32]::mouse_event([McWin32]::MOVE, 1, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 40
    [McWin32]::mouse_event([McWin32]::MOVE, [uint32]::MaxValue, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 80
}

function Escape-SendKeys([string]$s) {
    # SendKeys treats + ^ % ~ ( ) { } [ ] as control characters; wrap them in braces.
    return ($s.ToCharArray() | ForEach-Object { if ('+^%~(){}[]' -contains $_) { "{$_}" } else { "$_" } }) -join ''
}

foreach ($action in $Actions) {
    $idx = $action.IndexOf(':')
    if ($idx -lt 1) { throw "Bad action '$action' (expected verb:argument)" }
    $verb = $action.Substring(0, $idx).ToLowerInvariant()
    $arg = $action.Substring($idx + 1)
    switch ($verb) {
        'click' {
            $p = $arg.Split(','); Move-Mouse ($r.X + [int]$p[0]) ($r.Y + [int]$p[1])
            [McWin32]::mouse_event([McWin32]::LEFTDOWN, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60
            [McWin32]::mouse_event([McWin32]::LEFTUP, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 150
        }
        'rclick' {
            $p = $arg.Split(','); Move-Mouse ($r.X + [int]$p[0]) ($r.Y + [int]$p[1])
            [McWin32]::mouse_event([McWin32]::RIGHTDOWN, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60
            [McWin32]::mouse_event([McWin32]::RIGHTUP, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 150
        }
        'move' {
            $p = $arg.Split(','); Move-Mouse ($r.X + [int]$p[0]) ($r.Y + [int]$p[1])
        }
        'wheel' {
            [McWin32]::mouse_event([McWin32]::WHEEL, 0, 0, [uint32]([int]$arg * 120), [UIntPtr]::Zero); Start-Sleep -Milliseconds 100
        }
        'type' {
            [System.Windows.Forms.SendKeys]::SendWait((Escape-SendKeys $arg)); Start-Sleep -Milliseconds 150
        }
        'key' {
            [System.Windows.Forms.SendKeys]::SendWait($arg); Start-Sleep -Milliseconds 150
        }
        'hold' {
            $p = $arg.Split(','); $vk = [byte][char]$p[0].ToUpperInvariant()
            [McWin32]::keybd_event($vk, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds ([int]$p[1])
            [McWin32]::keybd_event($vk, 0, 2, [UIntPtr]::Zero); Start-Sleep -Milliseconds 100
        }
        'wait' { Start-Sleep -Milliseconds ([int]$arg) }
        default { throw "Unknown verb '$verb'" }
    }
}
Write-Output "Done: $($Actions -join ' ')"
