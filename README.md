# Lucas's Minecraft mods

NeoForge mods for Minecraft **1.21.1**, one Gradle project per mod, sharing a root build.

| Mod | Folder | Side | What it does |
|-----|--------|------|--------------|
| **Level Loss Only** | `level-loss-only/` | server (clients need nothing) | On death you keep every item. Your XP still drops as orbs and your levels reset, exactly like vanilla without `keepInventory`. Curse of Vanishing items still vanish. |
| **Keyboard Keybinds** | `keyboard-keybinds/` | client only | Replaces *Options → Controls → Key Binds* with a visual keyboard. Every key shows what is bound to it; real conflicts are red, intentional overlaps are yellow. |

## Requirements

- Any JDK 17+ to run Gradle (Gradle downloads JDK 21 for the mod itself automatically).
- First build downloads and decompiles Minecraft: a few minutes and ~1 GB in `~/.gradle`.

## Build

```bash
./gradlew build
```

Jars land in `level-loss-only/build/libs/` and `keyboard-keybinds/build/libs/`.
Drop a jar into a `mods/` folder of any NeoForge 1.21.1 instance (CurseForge app profile, server, ...).

## Run the game from the repo

```bash
./gradlew :keyboard-keybinds:runClient          # client with Keyboard Keybinds
./gradlew :keyboard-keybinds:runClientScreen    # same, but opens the keyboard screen immediately
./gradlew :level-loss-only:runClient            # client with Level Loss Only
./gradlew :level-loss-only:runServer            # dedicated server with Level Loss Only
```

Each mod runs from its own `run/` folder (worlds, logs, options). Those folders are git-ignored.
`run/options.txt` is pre-seeded with `guiScale:2` and the first-launch onboarding disabled so the
window is predictable for the scripts below.

## Scripted dev loop (no hands on the mouse)

The point of this repo is that an AI assistant (or you) can change code, launch the game, and *look*
at the result without a human at the keyboard. The `dev-driver/` subproject is a dev-only client mod
that loads **both** real mods and executes a script of actions on the game's own tick, so it never
touches your real mouse or keyboard:

```bash
./gradlew :dev-driver:runClient -PdriverScript=scripts/driver/keybinds.txt
./gradlew :dev-driver:runClient -PdriverScript=scripts/driver/levelloss.txt
```

Scripts are one command per line (`wait`, `click X Y`, `key ENTER`, `type`, `chat /give @s diamond`,
`world NAME`, `respawn`, `screen keybinds|inventory|none`, `screenshot NAME`, `log`, `expect`, `quit`).
Screenshots are written by Minecraft itself to `dev-driver/run/screenshots/NAME.png`; results and
`PASS`/`FAIL` lines go to the game log, prefixed `[DevDriver]`. The full command list is in the
Javadoc of `dev-driver/src/main/java/com/lucasmods/devdriver/DevDriver.java`.

Typical loop: edit → run a driver script in the background → read the log → open the PNGs → fix →
repeat. The client window is launched at 1280×720 with GUI scale 2, so GUI coordinates (640×360)
stay stable between runs.

`scripts/screenshot.ps1` captures the game window from outside the game and `scripts/send-input.ps1`
injects real mouse and keyboard input. They exist for the rare state the driver cannot reach (a
crash screen, the loading splash), but they steal focus and move the real cursor, so prefer the driver.

## Keyboard Keybinds: how conflicts are judged

NeoForge gives every key mapping a *conflict context* (in-game, in menus, or everywhere) and an
optional modifier (Ctrl/Shift/Alt). The screen uses those to sort shared keys into tiers:

- **Red, conflict**: both mappings can fire from the same press in the same situation
  (same key, same modifier, overlapping contexts; or in-game `Ctrl+Q` next to plain `Q`).
- **Yellow, overlap (OK)**: the key is shared but the game keeps them apart. One is menu-only and
  the other in-game, the modifiers differ inside a menu, or one mapping's key is merely the
  other's modifier (Sneak on Shift next to `Shift+R`).
- A red pair you know is fine can be **marked intentional**; it turns yellow and the choice is
  saved in `config/keyboardkeybinds.json`.

Key labels come from the OS, so on a non-US keyboard each physical key shows what it really types.
The physical arrangement is US ANSI.

## Layout of the repo

```
build.gradle            shared config for every mod (NeoForge version, runs, metadata expansion)
gradle.properties       Minecraft / NeoForge / Parchment versions, author, license
settings.gradle         lists the mod subprojects
<mod>/gradle.properties mod id, name, version, description
<mod>/src/main/java     sources
<mod>/src/main/templates/META-INF/neoforge.mods.toml   mod metadata (placeholders filled by Gradle)
dev-driver/             dev-only mod that runs scripts/driver/*.txt against the client (never published)
scripts/driver/         driver scripts: keybinds.txt, levelloss.txt
scripts/*.ps1           PowerShell fallbacks for capturing / driving the window from outside the game
scripts/dev-options.txt options.txt seeded into every run/ folder on first launch
```

To add a third mod: copy a mod folder, change its `gradle.properties`, add it to `settings.gradle`.
