"""Launch the real game for Lucas from his CurseForge profile, visible, with sound, offline-authenticated.

    python scripts/play.py [--profile "Skybound Lucas Fork"] [--name Mullenovo] [--xmx 10G]

Same jars the CurseForge app uses (its Install folder, read-only), but started from here so that the assistant can
launch it without the CurseForge app. Offline auth: fine for the local test server (online-mode=false) and
singleplayer; the real server needs the CurseForge app's Microsoft login. The process is detached; the pid is
written to <profile>/game.pid. Nothing is hidden or muted here, unlike the test launcher.
"""
import json, os, subprocess, sys, uuid
from pathlib import Path

INSTALL = Path(r"C:\Users\lucas\curseforge\minecraft\Install")
INSTANCES = Path(r"C:\Users\lucas\curseforge\minecraft\Instances")
JAVA = next(Path(r"C:\Users\lucas\.gradle\jdks").glob("*21*/bin/java.exe"))

args = sys.argv[1:]
def opt(name, default):
    return args[args.index(name) + 1] if name in args else default
profile = INSTANCES / opt("--profile", "Skybound Lucas Fork")
player = opt("--name", "Mullenovo")
xmx = opt("--xmx", "10G")

instance = json.load(open(profile / "minecraftinstance.json", encoding="utf-8"))
neo = json.loads(instance["baseModLoader"]["versionJson"])
vanilla = json.load(open(INSTALL / "versions" / neo["inheritsFrom"] / f"{neo['inheritsFrom']}.json", encoding="utf-8"))


def allowed(entry):
    rules = entry.get("rules")
    if not rules:
        return True
    ok = False
    for r in rules:
        if "os" not in r or r["os"].get("name") == "windows":
            ok = r["action"] == "allow"
    return ok


libs = []
for entry in vanilla["libraries"] + neo["libraries"]:
    art = entry.get("downloads", {}).get("artifact")
    if allowed(entry) and art:
        p = str(INSTALL / "libraries" / art["path"])
        if p not in libs:
            libs.append(p)
natives = profile / "natives"; natives.mkdir(exist_ok=True)
subst = {
    "natives_directory": str(natives), "launcher_name": "radarcraft", "launcher_version": "1",
    "classpath": os.pathsep.join(libs), "library_directory": str(INSTALL / "libraries"), "classpath_separator": os.pathsep,
    "version_name": neo["id"], "auth_player_name": player, "game_directory": str(profile),
    "assets_root": str(INSTALL / "assets"), "assets_index_name": vanilla["assetIndex"]["id"],
    "auth_uuid": str(uuid.uuid3(uuid.NAMESPACE_DNS, "OfflinePlayer:" + player)).replace("-", ""),
    "auth_access_token": "0", "clientid": "0", "auth_xuid": "0", "user_type": "legacy", "version_type": "release",
    "resolution_width": "1600", "resolution_height": "900",
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
game = [g for g in expand(vanilla["arguments"]["game"]) + expand(neo["arguments"]["game"]) if g != "--demo"]
cmd = [str(JAVA), f"-Xmx{xmx}", "-Xms2G"] + jvm + [neo["mainClass"]] + game
log = open(profile / "launch.log", "w", encoding="utf-8", errors="replace")
log.write(" ".join(cmd) + "\n\n"); log.flush()
flags = 0x00000008 | 0x00000200 if os.name == "nt" else 0  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
proc = subprocess.Popen(cmd, cwd=str(profile), stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
(profile / "game.pid").write_text(str(proc.pid))
print(f"launched {neo['id']} as {player} from {profile.name} (pid {proc.pid}); log: {profile / 'launch.log'}")
