<#
Update-Radarcraft.ps1  -  double-click to bring your CurseForge profile up to date with the latest pack release.

What it does, every time
  1. finds your CurseForge profile for the pack (name below)
  2. downloads the latest release's pack zip + mods.lock.json from GitHub
  3. makes your mods folder match the lock: removes jars that are no longer in the pack (removed mods, old versions),
     downloads added/upgraded/downgraded mods straight from CurseForge, and copies the pack's own mods out of the zip
  4. refreshes config/, kubejs/ and defaultconfigs/ from the zip (your options.txt, saves and screenshots are untouched)
Mods whose authors forbid third-party downloads (about 24 of 385) cannot be fetched by a script: if one of those
changed, the script lists them with their CurseForge page so you can drop the file into the mods folder, or you
re-import the new zip in the CurseForge app instead.
Radarcraft is a fork of Create Aeronautics - Skybound by Lupin (Team LUP).

Setup: nothing, unless the profile name below does not match the one shown in the CurseForge app.
Testing: .\Update-Radarcraft.ps1 -LocalRelease C:\path\to\dist\1.0.0 -ProfileName "Radarcraft"
#>
param(
    [string]$ProfileName = "Radarcraft",
    [string]$Repo = "mullens/radarcraft",
    [string]$LocalRelease = "",     # folder holding the zip + mods.lock.json instead of GitHub (for testing)
    [switch]$DryRun
)
$ErrorActionPreference = "Stop"
function Say($m) { Write-Host $m -ForegroundColor Cyan }
function Warn($m) { Write-Host $m -ForegroundColor Yellow }
Add-Type -AssemblyName System.IO.Compression.FileSystem

$instances = Join-Path $env:USERPROFILE "curseforge\minecraft\Instances"
$profileDir = Get-ChildItem $instances -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "$ProfileName*" } | Select-Object -First 1
if (-not $profileDir) {
    Write-Host "No CurseForge profile starting with '$ProfileName' under $instances." -ForegroundColor Red
    Write-Host "Import the pack zip in the CurseForge app first (Create Custom Profile -> Import)."
    if (-not $LocalRelease) { pause }; exit 1
}
Say "Profile: $($profileDir.FullName)"
$modsDir = Join-Path $profileDir.FullName "mods"
New-Item -ItemType Directory -Force $modsDir | Out-Null

# --- fetch release files ---
$tmp = Join-Path $env:TEMP "radarcraft-update"
New-Item -ItemType Directory -Force $tmp | Out-Null
if ($LocalRelease) {
    $zipPath = (Get-ChildItem $LocalRelease -Filter "*.zip" | Select-Object -First 1).FullName
    $lockPath = Join-Path $LocalRelease "mods.lock.json"
    Say "Local release: $LocalRelease"
} else {
    $headers = @{ "User-Agent" = "Update-Radarcraft" }
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers $headers
    Say "Latest release: $($release.tag_name)  ($($release.name))"
    $zipAsset = $release.assets | Where-Object { $_.name -like "*.zip" } | Select-Object -First 1
    $lockAsset = $release.assets | Where-Object { $_.name -eq "mods.lock.json" } | Select-Object -First 1
    if (-not $zipAsset -or -not $lockAsset) { Write-Host "Release is missing the pack zip or mods.lock.json." -ForegroundColor Red; pause; exit 1 }
    $zipPath = Join-Path $tmp $zipAsset.name; $lockPath = Join-Path $tmp "mods.lock.json"
    Invoke-WebRequest -Uri $zipAsset.browser_download_url -Headers $headers -OutFile $zipPath
    Invoke-WebRequest -Uri $lockAsset.browser_download_url -Headers $headers -OutFile $lockPath
}
$lock = Get-Content $lockPath -Raw | ConvertFrom-Json
Say "Pack $($lock.name) $($lock.version): $($lock.mods.Count) jars expected"

# --- reconcile mods folder ---
$wanted = @{}
foreach ($m in $lock.mods) { $wanted[$m.file] = $m }
$present = @{}
Get-ChildItem $modsDir -Filter "*.jar" | ForEach-Object { $present[$_.Name] = $_ }

$toRemove = @($present.Keys | Where-Object { -not $wanted.ContainsKey($_) })
$toAdd = @($lock.mods | Where-Object { -not $present.ContainsKey($_.file) })
Say ("Remove: {0}   Add/replace: {1}   Unchanged: {2}" -f $toRemove.Count, $toAdd.Count, ($present.Count - $toRemove.Count))
if ($DryRun) { $toRemove | ForEach-Object { "  - $_" }; $toAdd | ForEach-Object { "  + $($_.file) [$($_.source)]" }; Say "dry run, nothing changed"; exit 0 }

foreach ($name in $toRemove) { Remove-Item (Join-Path $modsDir $name) -Force; Say "  removed  $name" }

$zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
$manual = @()
try {
    foreach ($m in $toAdd) {
        $dest = Join-Path $modsDir $m.file
        if ($m.source -eq "zip") {
            $entry = $zip.Entries | Where-Object { $_.FullName -eq "overrides/mods/$($m.file)" } | Select-Object -First 1
            if ($entry) { [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true); Say "  added    $($m.file) (from zip)" }
            else { Warn "  missing in zip: $($m.file)" }
        } elseif ($m.url) {
            Invoke-WebRequest -Uri $m.url -OutFile $dest -Headers @{ "User-Agent" = "Update-Radarcraft" }
            Say "  added    $($m.file) (CurseForge)"
        } else {
            $manual += $m
        }
    }
    # configs: replace pack-managed folders wholesale
    foreach ($folder in @("config", "kubejs", "defaultconfigs")) {
        $entries = $zip.Entries | Where-Object { $_.FullName -like "overrides/$folder/*" -and $_.Name }
        if ($entries.Count -eq 0) { continue }
        $target = Join-Path $profileDir.FullName $folder
        if (Test-Path $target) { Remove-Item $target -Recurse -Force }
        foreach ($e in $entries) {
            $rel = $e.FullName.Substring("overrides/".Length)
            $out = Join-Path $profileDir.FullName $rel
            New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $out, $true)
        }
        Say "  refreshed $folder/ ($($entries.Count) files)"
    }
    # journeymap settings: overwrite files only, never delete the folder (it holds the player's map data)
    $jm = $zip.Entries | Where-Object { $_.FullName -like "overrides/journeymap/*" -and $_.Name }
    foreach ($e in $jm) {
        $out = Join-Path $profileDir.FullName $e.FullName.Substring("overrides/".Length)
        New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $out, $true)
    }
    if ($jm.Count -gt 0) { Say "  refreshed journeymap settings ($($jm.Count) files)" }
    $srv = $zip.Entries | Where-Object { $_.FullName -eq "overrides/servers.dat" } | Select-Object -First 1
    if ($srv -and -not (Test-Path (Join-Path $profileDir.FullName "servers.dat"))) {
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($srv, (Join-Path $profileDir.FullName "servers.dat"), $true)
    }
} finally { $zip.Dispose() }

if ($manual.Count -gt 0) {
    Warn ""
    Warn "These $($manual.Count) mods changed but their authors do not allow script downloads. Either:"
    Warn "  (a) download each file from its page and put it in $modsDir, or"
    Warn "  (b) import the new pack zip in the CurseForge app (Create Custom Profile -> Import)."
    foreach ($m in $manual) { Warn ("  {0}  ->  {1}   (file: {2})" -f $m.name, $m.page, $m.file) }
} else {
    Say "Done. Launch the pack from the CurseForge app as usual."
}
if (-not $LocalRelease) { pause }
