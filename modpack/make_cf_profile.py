"""Create a CurseForge app profile of the trimmed pack by cloning the existing Skybound instance (no downloads).

    python modpack/make_cf_profile.py [--name "Skybound Lucas Fork"] [--add-server "Local test" localhost:25565]

Copies the kept mod jars from the original instance, adds our jars and the pack's (patched) config/kubejs,
writes a minecraftinstance.json that the CurseForge app accepts as an unlocked custom profile, and pre-adds a
server to the multiplayer list. Restart the CurseForge app afterwards; the profile appears under My Modpacks.
Re-running replaces the profile's mods/config but keeps its saves, options and logs.
"""
import argparse, json, shutil, struct, sys, uuid, gzip, io
from pathlib import Path

HERE = Path(__file__).resolve().parent
INSTANCES = Path.home() / "curseforge/minecraft/Instances"
SRC = INSTANCES / "Create Aeronautics - Skybound  Create Aeronautics & Create Addons with Quests"


# ---- tiny NBT writer/reader for servers.dat (uncompressed) ----
def _w_str(s):
    b = s.encode("utf-8"); return struct.pack(">H", len(b)) + b


def _write_tag(t, v):
    if t == 1: return struct.pack(">b", v)
    if t == 8: return _w_str(v)
    if t == 9:
        lt = 10; return struct.pack(">bi", lt, len(v)) + b"".join(_write_compound_body(x) for x in v)
    if t == 10: return _write_compound_body(v)
    raise ValueError(t)


def _write_compound_body(d):
    out = b""
    for k, (t, v) in d.items():
        out += struct.pack(">b", t) + _w_str(k) + _write_tag(t, v)
    return out + b"\x00"


def read_servers(path):
    sys.path.insert(0, str(HERE))
    from scan_world import read_nbt
    data = path.read_bytes()
    return read_nbt(data)


def write_servers(path, servers):
    body = {"servers": (9, [{"name": (8, s["name"]), "ip": (8, s["ip"]), "hidden": (1, 0)} for s in servers])}
    path.write_bytes(b"\x0a" + _w_str("") + _write_compound_body(body))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="Skybound Lucas Fork")
    ap.add_argument("--add-server", nargs=2, metavar=("NAME", "IP"), default=["Local test server", "localhost:25565"])
    a = ap.parse_args()

    pack = HERE / "out" / "pack"
    if not (pack / "manifest.json").exists():
        sys.exit("build the pack first: python modpack/build_pack.py")
    manifest = json.load(open(pack / "manifest.json", encoding="utf-8"))
    src_inst = json.load(open(SRC / "minecraftinstance.json", encoding="utf-8"))
    by_project = {ad["addonID"]: ad for ad in src_inst["installedAddons"]}

    dst = INSTANCES / a.name
    dst.mkdir(parents=True, exist_ok=True)
    (dst / "mods").mkdir(exist_ok=True)
    for old in (dst / "mods").glob("*.jar"):
        old.unlink()

    kept_addons = []
    n = 0
    for f in manifest["files"]:
        ad = by_project.get(f["projectID"])
        if not ad:
            print("WARNING: not in local instance, the app must download it:", f); continue
        sec = ad["categorySection"]["path"]
        srcfile = SRC / sec / ad["fileNameOnDisk"]
        if sec == "mods":
            if srcfile.exists():
                shutil.copy2(srcfile, dst / "mods" / srcfile.name); n += 1
        elif sec == "shaderpacks":
            (dst / sec).mkdir(exist_ok=True)
            if srcfile.exists():
                shutil.copy2(srcfile, dst / sec / srcfile.name)
        else:
            continue
        ad2 = json.loads(json.dumps(ad))
        ad2["modFolderPath"] = str(dst / sec)
        ad2["instanceID"] = ad2.get("instanceID")
        kept_addons.append(ad2)
    for jar in (pack / "overrides" / "mods").glob("*.jar"):
        shutil.copy2(jar, dst / "mods" / jar.name); n += 1
    for sub in ("config", "kubejs", "defaultconfigs", "datapacks"):
        if (pack / "overrides" / sub).exists():
            if (dst / sub).exists():
                shutil.rmtree(dst / sub)
            shutil.copytree(pack / "overrides" / sub, dst / sub)
    for fn in ("options.txt", "defaultoptions.journal.json"):
        if (SRC / fn).exists() and not (dst / fn).exists():
            shutil.copy2(SRC / fn, dst / fn)

    # servers.dat: original list + the extra server
    servers = []
    if (SRC / "servers.dat").exists():
        try:
            for s in read_servers(SRC / "servers.dat").get("servers", []):
                servers.append({"name": s.get("name", "?"), "ip": s.get("ip", "")})
        except Exception as e:
            print("could not read servers.dat:", e)
    if a.add_server and not any(s["ip"] == a.add_server[1] for s in servers):
        servers.insert(0, {"name": a.add_server[0], "ip": a.add_server[1]})
    write_servers(dst / "servers.dat", servers)

    inst = json.loads(json.dumps(src_inst))
    inst["installedAddons"] = kept_addons
    inst["name"] = a.name
    inst["installPath"] = str(dst) + "\\"
    inst["guid"] = str(uuid.uuid4())
    inst["isUnlocked"] = True
    inst["projectID"] = 0
    inst["fileID"] = 0
    inst["installedModpack"] = None
    inst["manifest"] = manifest
    inst["cachedScans"] = []
    inst["wasNameManuallyChanged"] = True
    inst["lastPlayed"] = "0001-01-01T00:00:00"
    inst["playedCount"] = 0
    inst["timePlayed"] = 0
    inst["customAuthor"] = "Lucas"
    inst["profileImagePath"] = None
    json.dump(inst, open(dst / "minecraftinstance.json", "w", encoding="utf-8"), indent=2)
    json.dump(manifest, open(dst / "manifest.json", "w", encoding="utf-8"), indent=2)
    print(f"profile '{a.name}' at {dst}: {n} jars, {len(kept_addons)} addons, servers: {[s['name'] for s in servers]}")
    print("restart the CurseForge app, open My Modpacks, Play")


if __name__ == "__main__":
    main()
