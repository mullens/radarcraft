# Lucas's Minecraft mods

NeoForge mods for Minecraft **1.21.1**, one Gradle project per mod, sharing a root build.

| Mod | Folder | Side | What it does |
|-----|--------|------|--------------|
| **Level Loss Only** | `level-loss-only/` | server (clients need nothing) | On death you keep every item. Your XP still drops as orbs and your levels reset, exactly like vanilla without `keepInventory`. Curse of Vanishing items still vanish. |
| **Keyboard Keybinds** | `keyboard-keybinds/` | client only | Replaces *Options → Controls → Key Binds* with a visual keyboard. Every key shows what is bound to it; real conflicts are red, intentional overlaps are yellow. |
| **Cherished Mobs** | `cherished-mobs/` | server (clients need nothing) | Friendly mobs take 20% damage (like a full Protection V set) and regenerate. Any name-tagged mob is invincible, cannot die, and is pulled back if it falls out of the world. |
| **Radars** | `radars/` | both | Name any mob "Radar" and it becomes Radar the cat, who points toward six giant boss cats hidden across the world. Beat one to tame it back down and learn where the next one lives. |

## Requirements

- Any JDK 17+ to run Gradle (Gradle downloads JDK 21 for the mod itself automatically).
- First build downloads and decompiles Minecraft: a few minutes and ~1 GB in `~/.gradle`.

## Build

```bash
./gradlew build
```

Jars land in each mod's `build/libs/` folder (`level-loss-only`, `keyboard-keybinds`, `cherished-mobs`, `radars`).
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
./gradlew :dev-driver:runClient -PdriverScript=scripts/driver/cherished.txt
./gradlew :dev-driver:runClient -PdriverScript=scripts/driver/radars.txt
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

## Cherished Mobs: rules and decisions

- **Friendly** means any mob that is not hostile (`Enemy`), plus everything tamable (wolves, cats, parrots, horses, ...).
  Villagers, golems and animals count; zombies, hoglins and piglins do not.
- Friendly mobs ignore a percentage of incoming damage (default 80%, the vanilla Protection cap) and carry a permanent,
  particle-free Regeneration I. Damage types that bypass enchantments in vanilla (void, starvation, `/kill`) bypass this too.
- **Named** means `hasCustomName()`: a name tag, a renamed spawn egg, or a `CustomName` from `/summon`. A named mob gets the
  vanilla invulnerable flag (so lava, arrows, cacti and fall damage never even register), is flagged persistent so it never
  despawns, every remaining damage path is cancelled, and death is cancelled with a full heal as a last resort.
- A named mob that drops more than 32 blocks below the world floor is teleported to the last spot it stood on solid ground
  (or the surface at its column, or world spawn), with its momentum cleared. It never falls forever.
- **`/kill` still works** on named mobs (`killCommandRemovesNamedMobs` in the server config), so admins can clean up.
  Everything else is blocked.
- Config: `serverconfig/cherishedmobs-server.toml` inside the world folder (`damageReductionPercent`, `regeneration`,
  `killCommandRemovesNamedMobs`).

## Radars: the seven-cat progression

Radar is a real tuxedo cat. In the game, every Radar is the vanilla cat model with the tuxedo texture; the bosses are the
same cat scaled up with the `generic.scale` attribute.

1. **Radar #1**: put a name tag reading `Radar` (any case) on any mob. The mob is replaced by a small, invincible, sitting
   cat named Radar. The named mob *becomes* the cat; it is not kept. A mob that arrives already named Radar (for example via
   `/summon ... {CustomName:'"Radar"'}`) converts too.
2. Every tamed Radar **points** at the next shrine: it faces it, every 3 seconds it emits a short line of end-rod particles
   toward it, and right-clicking it says something like *"Radar stares north-east, about 500 blocks away. Rowdy Radar waits
   there."* After Radar #7 is tamed, its cat points to world spawn and says Radar is at peace.
3. **Shrine locations** come from the world seed: #2 is 500 blocks from world spawn at a random angle, and each next shrine is
   100 blocks further from the previous shrine than the last hop (#3 is 600 from #2, #4 700 from #3, ... #7 1000 from #6).
   When a player comes within 128 blocks of a target, the mod searches up to 48 blocks around it for solid, dry ground and
   builds a 9x9 deepslate/tuff shrine with four lantern pillars and a lodestone in the middle. In an ocean or over the void it
   builds the shrine anyway, at the column's surface or at y=100.
4. **Bosses** spawn on the lodestone when a player is within 64 blocks, with a boss bar, never despawn, teleport home if they
   wander more than 48 blocks, and heal to full if nobody has been within 48 blocks for 20 seconds. All of them hit harder
   below half health. The shrines are independent: you can skip ahead if you find one.
5. **Defeating** a boss drops XP and a *Whisker of ...* name tag, and spawns its tamed cat on the shrine, pointing to the next
   one. If that cat is ever lost (killed with `/kill`, chunk reset), the shrine is marked undefeated and the boss returns.

| # | Name | Size | HP | Fight |
|---|------|------|----|-------|
| 2 | Rowdy Radar | 4x | 150 | Pounces from up to 14 blocks, claws up close, zig-zag sprints. The tutorial fight. |
| 3 | Radiation Radar | 4.5x | 250 | Slow and armoured. Every 4 s a green pulse hits everyone within 8 blocks with damage, Poison and Wither, and it leaves poison clouds where it walks. Kite it. |
| 4 | Ricochet Radar | 3.5x | 350 | Fast, little armour. Launches itself at you and bounces off walls; spits hairballs that bounce up to three times. |
| 5 | Radar Raider | 5x | 450 | Howls to summon waves of hostile Radar kittens (and buffs them). Shoot it and it blinks behind you. |
| 6 | Rumbling Radar | 5.5x | 600 | Leaps and ground-slams: a shockwave throws everyone within 7 blocks up with Slowness and shakes everyone within 10. Cannot be knocked back. |
| 7 | Radiant Radar | 6x | 800 | Telegraphed laser beam (sparks first, then the beam), blinding flash (Blindness + Darkness), two one-hit mirror images. Below half health it is shielded except while charging a beam. |

Commands (operator level 2): `/radars status`, `/radars locate <2-7>`, `/radars tp <2-7>` (builds the shrine if needed),
`/radars reset <2-7>`, `/radars spawn <1-9>` (1 = tamed Radar, 2-7 = bosses in front of you, 8 = kitten, 9 = mirror).

Decisions made without a spec: glow instead of per-variant tint (the glow outline needs no extra textures); Radar #1 is a
plain cat with 20 HP that is invulnerable anyway; bosses target survival players only, so creative testing is safe; the
tamed cats answer any right-click, sneaking or not.

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
scripts/driver/         driver scripts: keybinds.txt, levelloss.txt, cherished.txt, cherished-void.txt, radars.txt
scripts/*.ps1           PowerShell fallbacks for capturing / driving the window from outside the game
scripts/dev-options.txt options.txt seeded into every run/ folder on first launch
```

To add a third mod: copy a mod folder, change its `gradle.properties`, add it to `settings.gradle`.
