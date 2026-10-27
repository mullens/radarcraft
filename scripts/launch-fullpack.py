"""Launch the PRODUCTION NeoForge client (the same jars the CurseForge app runs) against
dev-driver/run-fullpack, with this repo's mod jars and the dev-driver jar dropped into its mods folder,
and drive it with a driver script. Dev runs recompile Minecraft and break mods whose mixins target
compiler-generated names, so a faithful full-pack test has to use the real game.

    python scripts/launch-fullpack.py scripts/driver/fullpack.txt [--visible] [--xmx 8G] [--timeout 30]

Uses the CurseForge install (libraries, assets, vanilla jar) read-only, runs offline as player "Dev",
and writes only inside dev-driver/run-fullpack. Output goes to dev-driver/run-fullpack/launch.log.
"""
import json
import os
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INSTALL = Path(r"C:\Users\lucas\curseforge\minecraft\Install")
INSTANCE = Path(r"C:\Users\lucas\curseforge\minecraft\Instances\Create Aeronautics - Skybound  Create Aeronautics & Create Addons with Quests")
GAME_DIR = ROOT / "dev-driver" / "run-fullpack"
JAVA = next(Path(r"C:\Users\lucas\.gradle\jdks").glob("*21*/bin/java.exe"))
OUR_JARS = [
    ROOT / "level-loss-only/build/libs/levellossonly-1.21.1-1.0.0.jar",
    ROOT / "keyboard-keybinds/build/libs/keyboardkeybinds-1.21.1-1.0.0.jar",
    ROOT / "cherished-mobs/build/libs/cherishedmobs-1.21.1-1.0.0.jar",
    ROOT / "radars/build/libs/radars-1.21.1-1.0.0.jar",
    ROOT / "checklist/build/libs/checklist-1.21.1-1.0.0.jar",
    ROOT / "dev-driver/build/libs/devdriver-1.21.1-0.0.0.jar",
]

args = sys.argv[1:]
script = ROOT / args[0] if args and not args[0].startswith("--") else None
visible = "--visible" in args
xmx = args[args.index("--xmx") + 1] if "--xmx" in args else "8G"
timeout_min = int(args[args.index("--timeout") + 1]) if "--timeout" in args else 30

instance = json.load(open(INSTANCE / "minecraftinstance.json", encoding="utf-8"))
neo = json.loads(instance["baseModLoader"]["versionJson"])
vanilla = json.load(open(INSTALL / "versions" / neo["inheritsFrom"] / f"{neo['inheritsFrom']}.json", encoding="utf-8"))


def allowed(entry):
    rules = entry.get("rules")
    if not rules:
        return True
    ok = False
    for r in rules:
        matches = "os" not in r or r["os"].get("name") == "windows"
        if matches:
            ok = r["action"] == "allow"
    return ok


libs = []
missing = []
for entry in vanilla["libraries"] + neo["libraries"]:
    if not allowed(entry):
        continue
    art = entry.get("downloads", {}).get("artifact")
    if not art:
        continue
    p = INSTALL / "libraries" / art["path"]
    if not p.exists():
        missing.append(str(p))
    if str(p) not in libs:  # vanilla and NeoForge both list some libraries (gson, guava...)
        libs.append(str(p))
# The vanilla client jar is NOT put on the classpath: NeoForge uses its patched client jar from the library
# directory, and the official launcher only lists the vanilla jar under a name FML ignores.
if missing:
    print("MISSING libraries:\n  " + "\n  ".join(missing))
    sys.exit(2)

natives = GAME_DIR / "natives"
natives.mkdir(parents=True, exist_ok=True)
(GAME_DIR / "mods").mkdir(exist_ok=True)
for jar in OUR_JARS:
    shutil.copy2(jar, GAME_DIR / "mods" / jar.name)
shutil.copy2(ROOT / "scripts" / "dev-options.txt", GAME_DIR / "options.txt")

subst = {
    "natives_directory": str(natives),
    "launcher_name": "radarcraft-dev",
    "launcher_version": "1",
    "classpath": os.pathsep.join(libs),
    "library_directory": str(INSTALL / "libraries"),
    "classpath_separator": os.pathsep,
    "version_name": neo["id"],
    "auth_player_name": "Dev",
    "game_directory": str(GAME_DIR),
    "assets_root": str(INSTALL / "assets"),
    "assets_index_name": vanilla["assetIndex"]["id"],
    "auth_uuid": str(uuid.uuid3(uuid.NAMESPACE_DNS, "OfflinePlayer:Dev")).replace("-", ""),
    "auth_access_token": "0",
    "clientid": "0",
    "auth_xuid": "0",
    "user_type": "legacy",
    "version_type": "release",
    "resolution_width": "1280",
    "resolution_height": "720",
}


def expand(values):
    out = []
    for v in values:
        if isinstance(v, dict):
            if not allowed(v):
                continue
            v = v["value"]
        for s in (v if isinstance(v, list) else [v]):
            for k, val in subst.items():
                s = s.replace("${" + k + "}", val)
            out.append(s)
    return out


jvm = [a for a in expand(vanilla["arguments"]["jvm"]) if a != "-XstartOnFirstThread"] + expand(neo["arguments"]["jvm"])
game = expand(vanilla["arguments"]["game"]) + expand(neo["arguments"]["game"])
game = [g for g in game if g not in ("--demo",)]
if "--width" not in game:
    game += ["--width", "1280", "--height", "720"]
extra = [f"-Xmx{xmx}", "-Xms2G"]
if script:
    extra.append(f"-Ddevdriver.script={script}")
if visible:
    extra.append("-Ddevdriver.visible=true")

cmd = [str(JAVA)] + extra + jvm + [neo["mainClass"]] + game
log = GAME_DIR / "launch.log"
print(f"launching {neo['id']} with {len(libs)} classpath entries, script={script}")
print(f"log: {log}")
start = time.time()
with open(log, "w", encoding="utf-8", errors="replace") as out:
    out.write(" ".join(cmd) + "\n\n")
    out.flush()
    proc = subprocess.Popen(cmd, cwd=str(GAME_DIR), stdout=out, stderr=subprocess.STDOUT)
    (GAME_DIR / "game.pid").write_text(str(proc.pid))  # so tooling can stop exactly this process
    try:
        code = proc.wait(timeout=timeout_min * 60)
    except subprocess.TimeoutExpired:
        # a hung game is the interesting case: keep a thread dump before killing it
        dump = GAME_DIR / "threaddump.txt"
        with open(dump, "w", encoding="utf-8", errors="replace") as d:
            subprocess.run([str(JAVA.parent / "jstack.exe"), str(proc.pid)], stdout=d, stderr=subprocess.STDOUT, timeout=120)
        proc.kill()
        code = -1
        print(f"TIMEOUT: killed the game after {timeout_min} minutes; thread dump in {dump}")
print(f"exit code {code} after {time.time() - start:.0f} s")
sys.exit(0 if code == 0 else 1)
