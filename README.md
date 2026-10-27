# Radarcraft

A fork of the **Create Aeronautics - Skybound** modpack, plus five mods made for it. Minecraft 1.21.1, NeoForge.
This page is for the people playing on the server. (Developer notes: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).)

Radarcraft would not exist without [Create Aeronautics - Skybound](https://www.curseforge.com/minecraft/modpacks/create-aeronautics-skybound)
by **Lupin** (Team LUP), with contributions from Gabriele_333. The mod selection, configs, KubeJS scripts, quests and the
Aetherial Islands worldgen are theirs; this fork trims the list, retunes a few things and adds our own mods on top.
Go download their pack if you want the original.

## Install (Windows, CurseForge app)

1. Install the [CurseForge app](https://www.curseforge.com/download/app) and sign in to Minecraft in it once.
2. Download the pack zip from the [latest release folder](https://github.com/mullens/radarcraft/tree/main/releases/latest)
   (`radarcraft-<version>.zip`, click it, then the Download button).
3. In the CurseForge app: **Minecraft → Create Custom Profile → Import**, pick the zip, wait. It installs NeoForge and
   downloads about 390 mods; give it a few minutes.
4. **Play.** The server is already in your Multiplayer list.

Give the game at least 8 GB of memory (CurseForge app → Settings → Minecraft → Allocated Memory). The first start
takes two to three minutes; later starts are faster.

## Update

Download `Update-Radarcraft.ps1` from the [latest release folder](https://github.com/mullens/radarcraft/tree/main/releases/latest)
(right-click → Save link as on the Raw button), put it anywhere, double-click it. Takes seconds. It:

- removes mods that left the pack and old versions,
- downloads mods that were added or changed, straight from CurseForge,
- refreshes the pack's configs (your own settings, saves and screenshots are untouched).

A couple dozen mods don't allow downloads outside the CurseForge app. If one of *those* changed, the script tells you
which file to grab from its CurseForge page, or you simply import the new zip in the app again (a new profile; nothing is
lost, everything lives on the server).

If double-clicking opens Notepad instead of running: right-click → *Run with PowerShell*. If PowerShell refuses, run
`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` once in a PowerShell window.

## What's different from Skybound

- **41 mods removed**: most of the second tech tree (Modern Industrialization, Modern Dynamics, Security Craft, ...;
  Powah and Flux Networks stay), duplicate storage systems, the old skill trees with the hearts, pack-branding menus,
  Punchy, Crafting on a Stick, More Useful Copper and Freeze the Happy Ghasts (no version for the new Vanilla Backport).
- **Added**: Inventory Crafting Grid (3x3 crafting in your inventory), the Accessories Compatibility Layer (one
  accessory screen for everything: Curios trinkets, Aether gloves and pendants, all in the Accessories slots), and the
  five mods below.
- **Newest vanilla content**: Vanilla Backport is updated to 1.2.2, which brings the Copper Age (copper tools and
  armor, copper golem, copper chests, shelves), Mounts of Mayhem, Chase the Skies (happy ghasts) and more to 1.21.1.
- **Rarer loot**: modded chest loot, Artifacts and Simply Swords weapons are harder to find; goblin traders are ~10x rarer.
- **Sky islands, quests, Create and all the Aeronautics addons** are unchanged.
- **Radarcraft title screen** instead of the Skybound one; JourneyMap comes preconfigured the way Skybound had it;
  the bleed-out/revive mechanic (PlayerRevive) is gone.

## The custom mods

| Mod | What it does for you |
|-----|----------------------|
| **Checklist** | Replaces the skill trees. A hidden grid of 165 real achievements from across the pack (bosses, dimensions, exploration, Create feats). Each one you complete is a level and a point, and reveals the four squares next to it. Points buy upgrade ranks (hearts, speed, reach, mining, magnet, size...). Take ranks off again any time, for free. Open it from the **Checklist** button on the Advancements screen. |
| **Level Loss Only** | Dying keeps your whole inventory. Your XP levels still drop to zero and spill as orbs, like vanilla. |
| **Cherished Mobs** | Friendly mobs are tough and regenerate. Anything you name with a name tag can never die, not even in the void. `/kill` is the only way to remove a named mob. |
| **Radars** | Radar is a real tuxedo cat. Name any mob "Radar" with a name tag: it becomes a little tuxedo cat that points toward the first of six giant boss cats hidden across the world, each 500 to 1000 blocks beyond the last. Beat a boss and it turns back into a cat that points to the next one. |
| **Keyboard Keybinds** | *Options → Controls → Key Binds* shows a keyboard. Red keys are real conflicts, yellow ones are overlaps the game handles fine. Click a key to see and change what's on it. |

## Server

The server runs the same mod list minus client-only mods (shaders, animations, menus). The server admin updates it; don't
expect your client changes to matter there. If you get a "mod mismatch" screen when joining, run the updater first.

## Trouble

- **Game won't start, or crashes right away**: run the updater, then try again. One mod (Architectury) occasionally
  crashes during startup on the first attempt; a second launch usually works.
- **"Incompatible client" when joining**: your pack is out of date, run the updater (or re-import the newest zip).
- **Something else**: send the admin `logs/latest.log` from your profile folder
  (`%USERPROFILE%\curseforge\minecraft\Instances\<profile>\logs`).
