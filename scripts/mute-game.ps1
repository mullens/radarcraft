<#
.SYNOPSIS
  Mutes the Windows audio session of the running Minecraft dev client (best-effort backstop; the
  primary mute is soundCategory_*:0.0 in run/options.txt, re-applied before every launch).
.EXAMPLE
  powershell -File scripts/mute-game.ps1          # mute every java.exe session
  powershell -File scripts/mute-game.ps1 -Unmute
#>
param([switch]$Unmute, [string]$ProcessName = "java")
$ErrorActionPreference = "Stop"

if (-not ([System.Management.Automation.PSTypeName]'CoreAudioMute').Type) {
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorComObject {}

[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator {
    int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
    int GetDevice(string id, out IMMDevice device);
    int RegisterEndpointNotificationCallback(IntPtr client);
    int UnregisterEndpointNotificationCallback(IntPtr client);
}

[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice {
    int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object interfacePointer);
    int OpenPropertyStore(int access, out IntPtr properties);
    int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
    int GetState(out int state);
}

[Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioSessionManager2 {
    int GetAudioSessionControl(ref Guid sessionGuid, int streamFlags, out IntPtr sessionControl);
    int GetSimpleAudioVolume(ref Guid sessionGuid, int streamFlags, out IntPtr audioVolume);
    int GetSessionEnumerator(out IAudioSessionEnumerator sessionEnum);
    int RegisterSessionNotification(IntPtr sessionNotification);
    int UnregisterSessionNotification(IntPtr sessionNotification);
    int RegisterDuckNotification(string sessionId, IntPtr duckNotification);
    int UnregisterDuckNotification(IntPtr duckNotification);
}

[Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioSessionEnumerator {
    int GetCount(out int count);
    int GetSession(int index, out IAudioSessionControl2 session);
}

[Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioSessionControl2 {
    // IAudioSessionControl
    int GetState(out int state);
    int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);
    int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string name, ref Guid eventContext);
    int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);
    int SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string path, ref Guid eventContext);
    int GetGroupingParam(out Guid groupingParam);
    int SetGroupingParam(ref Guid groupingParam, ref Guid eventContext);
    int RegisterAudioSessionNotification(IntPtr notification);
    int UnregisterAudioSessionNotification(IntPtr notification);
    // IAudioSessionControl2
    int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
    int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
    int GetProcessId(out uint pid);
    int IsSystemSoundsSession();
    int SetDuckingPreference(bool optOut);
}

[Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface ISimpleAudioVolume {
    int SetMasterVolume(float level, ref Guid eventContext);
    int GetMasterVolume(out float level);
    int SetMute(bool mute, ref Guid eventContext);
    int GetMute(out bool mute);
}

public static class CoreAudioMute {
    public static int SetMuteForProcesses(uint[] pids, bool mute) {
        int changed = 0;
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
        IMMDevice device;
        enumerator.GetDefaultAudioEndpoint(0 /* eRender */, 1 /* eMultimedia */, out device);
        Guid iid = typeof(IAudioSessionManager2).GUID;
        object managerObj;
        device.Activate(ref iid, 23 /* CLSCTX_ALL */, IntPtr.Zero, out managerObj);
        var manager = (IAudioSessionManager2)managerObj;
        IAudioSessionEnumerator sessions;
        manager.GetSessionEnumerator(out sessions);
        int count;
        sessions.GetCount(out count);
        for (int i = 0; i < count; i++) {
            IAudioSessionControl2 session;
            sessions.GetSession(i, out session);
            uint pid;
            session.GetProcessId(out pid);
            if (Array.IndexOf(pids, pid) < 0) continue;
            var volume = (ISimpleAudioVolume)session;
            Guid ctx = Guid.Empty;
            volume.SetMute(mute, ref ctx);
            changed++;
        }
        return changed;
    }
}
"@
}

$pids = @(Get-Process -Name $ProcessName -ErrorAction SilentlyContinue | ForEach-Object { [uint32]$_.Id })
if ($pids.Count -eq 0) { Write-Output "No $ProcessName process running"; exit 0 }
$n = [CoreAudioMute]::SetMuteForProcesses([uint32[]]$pids, -not $Unmute)
Write-Output ("{0} {1} audio session(s) of {2} process(es)" -f ($(if ($Unmute) { "Unmuted" } else { "Muted" })), $n, $pids.Count)
