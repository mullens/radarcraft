<#
Update-Skybound.ps1  -  double-click to pull the latest custom mods + configs into your CurseForge profile.

What it does
  1. finds your CurseForge profile folder for the pack (name set below)
  2. downloads the latest GitHub release's files (our own mod jars and the Checklist config files)
  3. replaces the old versions in <profile>\mods and <profile>\config
It does NOT touch the CurseForge mods themselves. When the pack adds or removes CurseForge mods, Lucas will
send a new pack zip: in the CurseForge app choose Create Custom Profile -> Import -> pick the zip.

Setup: nothing, unless the profile name below does not match yours (open the CurseForge app, My Modpacks,
the name under the pack picture).
#>
$ProfileName = "Create Aeronautics - Skybound (Lucas fork)"
$Repo        = "OWNER/REPO"          # GitHub repo of the pack, e.g. lucas/vibecraft
$Token       = ""                    # only needed while the repo is private: a read-only token Lucas gives you

$ErrorActionPreference = "Stop"
function Say($m) { Write-Host $m -ForegroundColor Cyan }

if ($Repo -eq "OWNER/REPO") { Write-Host "Repo not set in this script yet. Ask Lucas for the right file." -ForegroundColor Red; pause; exit 1 }

$instances = Join-Path $env:USERPROFILE "curseforge\minecraft\Instances"
$profileDir = Get-ChildItem $instances -Directory | Where-Object { $_.Name -like "$ProfileName*" } | Select-Object -First 1
if (-not $profileDir) {
    Write-Host "No CurseForge profile starting with '$ProfileName' found under $instances." -ForegroundColor Red
    Write-Host "Import the pack zip in the CurseForge app first, or fix `$ProfileName at the top of this script."
    pause; exit 1
}
Say "Profile: $($profileDir.FullName)"

$headers = @{ "User-Agent" = "Update-Skybound" }
if ($Token) { $headers["Authorization"] = "Bearer $Token" }
$release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers $headers
Say "Latest release: $($release.tag_name)  ($($release.name))"

$modsDir = Join-Path $profileDir.FullName "mods"
$cfgDir  = Join-Path $profileDir.FullName "config"
New-Item -ItemType Directory -Force $modsDir, $cfgDir | Out-Null
$tmp = Join-Path $env:TEMP "skybound-update"
New-Item -ItemType Directory -Force $tmp | Out-Null

foreach ($asset in $release.assets) {
    $name = $asset.name
    $dl = Join-Path $tmp $name
    $dlHeaders = $headers.Clone(); $dlHeaders["Accept"] = "application/octet-stream"
    $url = if ($Token) { $asset.url } else { $asset.browser_download_url }
    Invoke-WebRequest -Uri $url -Headers $dlHeaders -OutFile $dl
    if ($name -like "*.jar") {
        # replace any older build of the same mod: "<modid>-<mc>-<version>.jar" -> match on "<modid>-"
        $modid = $name.Split("-")[0]
        Get-ChildItem $modsDir -Filter "$modid-*.jar" | Remove-Item -Force
        Copy-Item $dl (Join-Path $modsDir $name) -Force
        Say "  mod   $name"
    } elseif ($name -like "checklist-*.json") {
        Copy-Item $dl (Join-Path $cfgDir $name) -Force
        Say "  config $name"
    } else {
        Say "  (skipped $name)"
    }
}
Say "Done. Launch the pack from the CurseForge app as usual."
pause
