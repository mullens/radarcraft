"""Resolve the trimmed pack's mod jars to files on disk (shared by boot_server, make_cf_profile, migrate_server).

A jar comes from, in order: modpack/tools/downloads (mods added or upgraded via extra-mods.json), the original
CurseForge instance, or the built pack's overrides/mods (our mods, Aetherial Islands). Needs a built pack
(python modpack/build_pack.py) for manifest.json and overrides.
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
INSTANCE = Path.home() / "curseforge/minecraft/Instances/Create Aeronautics - Skybound  Create Aeronautics & Create Addons with Quests"
DOWNLOADS = HERE / "tools" / "downloads"


def client_only_prefixes():
    p = HERE / "client-only-mods.txt"
    return tuple(l.strip().lower() for l in open(p, encoding="utf-8") if l.strip() and not l.startswith("#")) if p.exists() else ()


def is_client_only(name):
    return name.lower().startswith(client_only_prefixes())


def pack_jars(include_client_only=True):
    """Return (list of (Path, source), list of missing descriptions)."""
    pack = HERE / "out" / "pack"
    manifest = json.load(open(pack / "manifest.json", encoding="utf-8"))
    instance = json.load(open(INSTANCE / "minecraftinstance.json", encoding="utf-8"))
    by_project = {a["addonID"]: a for a in instance["installedAddons"]}
    extras = {e["projectID"]: e for e in json.load(open(HERE / "extra-mods.json", encoding="utf-8"))} if (HERE / "extra-mods.json").exists() else {}
    jars, missing = [], []
    for f in manifest["files"]:
        a = by_project.get(f["projectID"])
        if a and a["categorySection"]["path"] != "mods":
            continue
        e = extras.get(f["projectID"])
        if e and e.get("file"):
            p = DOWNLOADS / e["file"]
            if p.exists():
                jars.append((p, "download")); continue
            if not a or a.get("installedFile", {}).get("id") != f["fileID"]:
                missing.append(f"{e['name']}: {e['file']} not in {DOWNLOADS} (download it from {e.get('url', '')})"); continue
        if a:
            p = INSTANCE / "mods" / a["fileNameOnDisk"]
            if p.exists():
                jars.append((p, "instance"))
            else:
                missing.append(f"{a['name']}: {p.name} not in the local instance")
    for jar in sorted((pack / "overrides" / "mods").glob("*.jar")):
        jars.append((jar, "overrides"))
    if not include_client_only:
        jars = [(p, s) for p, s in jars if not is_client_only(p.name)]
    return jars, missing
