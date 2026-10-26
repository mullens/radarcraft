# For friends: installing and updating the pack (Windows)

## First time
1. Install the CurseForge app (curseforge.com/download) and sign in to Minecraft in it once.
2. Get the pack zip from Lucas (the GitHub release page, or he sends it).
3. In the CurseForge app: Minecraft -> Create Custom Profile -> Import -> pick the zip. Let it download
   everything (it installs NeoForge and about 390 mods; give it a few minutes).
4. Play. The server is already in your Multiplayer list.

## Updates
- "Run the updater": double-click `Update-Skybound.ps1` (right-click -> Run with PowerShell if double-click
  opens Notepad). It swaps in the newest versions of Lucas's own mods and the Checklist configs. Takes seconds.
- "Re-import the pack": when Lucas changed which CurseForge mods are in the pack, he sends a new zip. Import it
  the same way as the first time; it becomes a new profile. Nothing is lost, your stuff lives on the server.

## If PowerShell refuses to run the script
Open PowerShell once and run:

    Set-ExecutionPolicy -Scope CurrentUser RemoteSigned

then try again.
