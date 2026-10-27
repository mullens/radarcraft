"""Build a trimmed CurseForge modpack from the Skybound instance and the audit decisions.

Usage (from the repo root):
    python modpack/build_pack.py [--decisions modpack/decisions.json] [--name "..."] [--version 1.0.0]

Inputs
  modpack/mods-audit.json   every mod with group + recommendation + dependency ids
  modpack/decisions.json    optional overrides {"<curseforge project id>": "keep"|"scrap"|"auto"}
  the local CurseForge instance of the original pack (manifest.json, minecraftinstance.json, config/, kubejs/, ...)

Outputs (modpack/out/)
  <slug>-<version>.zip      importable in the CurseForge app (Create Custom Profile -> Import)
  pack/                     the unzipped contents (manifest.json, modlist.html, overrides/)
  report.md                 what was kept and scrapped, and why
  server-remove.txt         jar filenames to delete from the server's mods folder
"""
import argparse, json, os, re, shutil, sys, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_INSTANCE = Path.home() / "curseforge/minecraft/Instances/Create Aeronautics - Skybound  Create Aeronautics & Create Addons with Quests"
OVERRIDE_DIRS = ["config", "kubejs", "defaultconfigs", "datapacks"]
# Resource packs are aesthetic ("Realistic 3D ..." packs, loading backgrounds); shaders are optional and harmless.
KEEP_SECTIONS = {"mods", "shaderpacks"}
_col = HERE / "client-only-mods.txt"
CLIENT_ONLY_MODS = {l.strip().lower() for l in open(_col, encoding="utf-8") if l.strip() and not l.startswith("#")} if _col.exists() else {"keyboardkeybinds"}


def resolve(mods, decisions):
    """Return (kept_ids, reasons) applying decisions over recommendations and auto-resolving libraries."""
    state = {m["id"]: decisions.get(str(m["id"]), m["rec"]) for m in mods}
    dependents = {}
    for m in mods:
        for dep in m["requires"]:
            dependents.setdefault(dep, []).append(m["id"])
    kept = {m["id"] for m in mods if state[m["id"]] == "keep"}
    reasons = {}
    changed = True
    while changed:
        changed = False
        for m in mods:
            mid = m["id"]
            if state[mid] != "auto" or mid in kept:
                continue
            deps = dependents.get(mid, [])
            if not deps and not m["requires"]:
                kept.add(mid); reasons[mid] = "library with no declared dependents, kept to be safe"; changed = True
            elif any(d in kept for d in deps):
                kept.add(mid); reasons[mid] = "needed by " + ", ".join(name_of(mods, d) for d in deps if d in kept); changed = True
    for m in mods:
        mid = m["id"]
        if mid in kept and mid not in reasons:
            reasons[mid] = "kept"
        elif mid not in kept:
            reasons[mid] = "dropped: nothing kept needs it" if state[mid] == "auto" else "scrapped"
    return kept, reasons, state, dependents


def make_title_screen(overrides):
    """Replace the Skybound FancyMenu title screen with the Radarcraft one (same layout geometry, our art).

    Art comes from modpack/titlescreen/assets (drawn by titlescreen/make_art.py). The Skybound layout is reused
    with its image paths swapped and the BisectHosting ad button removed, so button positions stay as tuned.
    """
    fm = overrides / "config" / "fancymenu"
    src_layout = fm / "customization" / "fm_skybound.txt"
    art = HERE / "titlescreen" / "assets"
    if not src_layout.exists() or not (art / "bg.png").exists():
        print("WARNING: title screen not replaced (layout or art missing)"); return
    text = src_layout.read_text(encoding="utf-8")
    # drop the custom button block that opens the hosting sponsor link
    text = re.sub(r"\nelement \{\n(?:(?!\n\}).)*?openlink(?:(?!\n\}).)*?\n\}\n", "\n", text, flags=re.S)
    text = text.replace("/config/fancymenu/assets/1x/", "/config/fancymenu/assets/radarcraft/")
    (fm / "customization" / "fm_radarcraft.txt").write_text(text, encoding="utf-8")
    src_layout.unlink()
    if (fm / "assets" / "1x").exists():
        shutil.rmtree(fm / "assets" / "1x")
    shutil.copytree(art, fm / "assets" / "radarcraft", dirs_exist_ok=True, ignore=shutil.ignore_patterns("panorama_*.png"))
    # Vanilla's title panorama (trial chambers in 1.21) is also the blurred backdrop of every menu and loading screen.
    # KubeJS serves kubejs/assets as an always-on resource pack, so the Radarcraft panorama replaces it everywhere.
    pano_dir = overrides / "kubejs" / "assets" / "minecraft" / "textures" / "gui" / "title" / "background"
    pano_dir.mkdir(parents=True, exist_ok=True)
    for i in range(6):
        if (art / f"panorama_{i}.png").exists():
            shutil.copy2(art / f"panorama_{i}.png", pano_dir / f"panorama_{i}.png")
    print("title screen: fm_radarcraft.txt +", len(list(art.glob('*.png'))), "images")


def apply_config_patches(overrides):
    """Apply modpack/config-patches.json to the copied config files (TOML line replacement, JSON nested set)."""
    pfile = HERE / "config-patches.json"
    if not pfile.exists():
        return
    for patch in json.load(open(pfile, encoding="utf-8"))["patches"]:
        target = overrides / patch["file"]
        if not target.exists():
            print("WARNING: config patch target missing:", patch["file"]); continue
        if patch["format"] == "toml":
            text = target.read_text(encoding="utf-8")
            for key, val in patch["set"].items():
                new, n = re.subn(rf"^(\s*{re.escape(key)}\s*=\s*)[^\n#]*", lambda m: m.group(1) + str(val), text, count=0, flags=re.M)  # every section
                if n == 0:
                    print(f"WARNING: key {key} not found in {patch['file']}")
                text = new
            target.write_text(text, encoding="utf-8")
        elif patch["format"] == "json":
            data = json.load(open(target, encoding="utf-8"))
            for dotted, val in patch["set"].items():
                node = data
                parts = dotted.split(".")
                for p in parts[:-1]:
                    node = node.setdefault(p, {})
                node[parts[-1]] = val
            json.dump(data, open(target, "w", encoding="utf-8"), indent=2)
        print(f"patched {patch['file']}: {', '.join(patch['set'])}")


def name_of(mods, mid):
    for m in mods:
        if m["id"] == mid:
            return m["name"]
    return str(mid)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--instance", default=str(DEFAULT_INSTANCE))
    ap.add_argument("--decisions", default=str(HERE / "decisions.json"))
    ap.add_argument("--name", default="Radarcraft")
    ap.add_argument("--version", default="1.0.0")
    ap.add_argument("--out", default=str(HERE / "out"))
    ap.add_argument("--no-local-mods", action="store_true", help="don't bundle this repo's own mod jars")
    args = ap.parse_args()

    inst = Path(args.instance).resolve()
    if os.name == "nt" and not str(inst).startswith("\\\\?\\"):
        inst = Path("\\\\?\\" + str(inst))
    mods = json.load(open(HERE / "mods-audit.json", encoding="utf-8"))
    decisions = {}
    if Path(args.decisions).exists():
        decisions = json.load(open(args.decisions, encoding="utf-8"))
    kept, reasons, state, dependents = resolve(mods, decisions)

    manifest = json.load(open(inst / "manifest.json", encoding="utf-8"))
    instance = json.load(open(inst / "minecraftinstance.json", encoding="utf-8"))
    addons = {a["addonID"]: a for a in instance["installedAddons"]}

    # Warn about kept mods whose required dependency got scrapped.
    warnings = []
    for m in mods:
        if m["id"] in kept:
            missing = [name_of(mods, d) for d in m["requires"] if d in addons and d not in kept]
            if missing:
                warnings.append(f"{m['name']} is kept but needs scrapped: {', '.join(missing)}")

    files_out = []
    for f in manifest["files"]:
        pid = f["projectID"]
        addon = addons.get(pid)
        section = addon["categorySection"]["path"] if addon else "mods"
        if section == "mods":
            if pid in kept:
                files_out.append(f)
        elif section in KEEP_SECTIONS:
            files_out.append(f)

    # Mods added on top of the original pack: modpack/extra-mods.json, a list of
    # {"projectID": int, "fileID": int, "name": str, "url": str}
    extra_path = HERE / "extra-mods.json"
    extras = json.load(open(extra_path, encoding="utf-8")) if extra_path.exists() else []
    # An extra whose projectID is already in the pack is an UPGRADE/DOWNGRADE of that mod: swap the file id in place.
    upgrades = {}
    for e in extras:
        existing = next((f for f in files_out if f["projectID"] == e["projectID"]), None)
        if existing:
            existing["fileID"] = e["fileID"]
            upgrades[e["projectID"]] = e
        else:
            files_out.append({"projectID": e["projectID"], "fileID": e["fileID"], "required": True})

    out = Path(args.out).resolve()
    if os.name == "nt" and not str(out).startswith("\\\\?\\"):
        out = Path("\\\\?\\" + str(out))  # long-path mode: config trees get deeper than MAX_PATH
    pack = out / "pack"
    if pack.exists():
        shutil.rmtree(pack)
    (pack / "overrides").mkdir(parents=True)
    new_manifest = dict(manifest)
    new_manifest["name"] = args.name
    new_manifest["version"] = args.version
    new_manifest["author"] = "mullens"
    new_manifest["files"] = files_out
    json.dump(new_manifest, open(pack / "manifest.json", "w", encoding="utf-8"), indent=2)

    for d in OVERRIDE_DIRS:
        src = inst / d
        if src.exists():
            shutil.copytree(src, pack / "overrides" / d, ignore=shutil.ignore_patterns("*.OLD", "*.bak"))
    apply_config_patches(pack / "overrides")
    make_title_screen(pack / "overrides")

    # JourneyMap settings (not the map data): friends start with the same minimap/fullmap/waypoint setup as Skybound
    jm = inst / "journeymap" / "config"
    if jm.exists():
        shutil.copytree(jm, pack / "overrides" / "journeymap" / "config")

    # the multiplayer server list, so friends see the server pre-added
    if (inst / "servers.dat").exists():
        shutil.copy2(inst / "servers.dat", pack / "overrides" / "servers.dat")

    # Jars that live in the instance's mods folder but are NOT CurseForge projects (the original pack shipped them
    # as overrides), e.g. the Aetherial Islands worldgen datapack-jar that makes the pack "Skybound". Keep them.
    manifest_files = {a["fileNameOnDisk"] for a in addons.values() if a["categorySection"]["path"] == "mods"}
    manifest_files |= {e["file"] for e in extras if e.get("file")}
    override_jars = []
    for jar in sorted((inst / "mods").glob("*.jar")):
        if jar.name not in manifest_files:
            (pack / "overrides" / "mods").mkdir(exist_ok=True)
            shutil.copy2(jar, pack / "overrides" / "mods" / jar.name)
            override_jars.append(jar.name)

    # Checklist mod grid (authored by modpack/make_grid.py) ships as a config file on both sides
    for name in ("checklist-grid.json", "checklist-upgrades.json", "foodbuffs.json"):
        src = HERE / name
        if src.exists():
            (pack / "overrides" / "config").mkdir(exist_ok=True)
            shutil.copy2(src, pack / "overrides" / "config" / name)

    # Our own mods, built by Gradle in this repo: <subproject>/build/libs/<modid>-<mc>-<ver>.jar
    local_jars = []
    if not args.no_local_mods:
        for jar in sorted(HERE.parent.glob("*/build/libs/*.jar")):
            if jar.name.endswith(("-sources.jar", "-javadoc.jar")) or jar.parent.parent.parent.name == "dev-driver":
                continue
            (pack / "overrides" / "mods").mkdir(exist_ok=True)
            shutil.copy2(jar, pack / "overrides" / "mods" / jar.name)
            local_jars.append(jar.name)

    # modlist.html like the CurseForge app writes it
    rows = []
    for f in files_out:
        a = addons.get(f["projectID"])
        if a:
            rows.append(f'<li><a href="{a.get("webSiteURL", "")}">{a["name"]} (by {a.get("primaryAuthor", "")})</a></li>')
    for e in extras:
        rows.append(f'<li><a href="{e.get("url", "")}">{e["name"]} (added)</a></li>')
    (pack / "modlist.html").write_text("<ul>\n" + "\n".join(rows) + "\n</ul>\n", encoding="utf-8")

    # mods.lock.json: the exact jar set of this pack version, for the friends' updater (reconcile + download)
    lock = {"name": args.name, "version": args.version, "neoforge": manifest["minecraft"]["modLoaders"][0]["id"], "mods": []}
    for f in files_out:
        a = addons.get(f["projectID"])
        if a and a["categorySection"]["path"] == "mods" and f["projectID"] in upgrades:
            e = upgrades[f["projectID"]]
            lock["mods"].append({"file": e["file"], "name": a["name"], "projectID": f["projectID"], "fileID": f["fileID"],
                                 "url": e.get("download"), "page": a.get("webSiteURL", ""), "source": "curseforge"})
        elif a and a["categorySection"]["path"] == "mods":
            inst_file = a.get("installedFile", {})
            lock["mods"].append({"file": a["fileNameOnDisk"], "name": a["name"], "projectID": f["projectID"], "fileID": f["fileID"],
                                 "url": inst_file.get("downloadUrl") if a.get("allowModDistribution") is not False else None,
                                 "page": a.get("webSiteURL", ""), "source": "curseforge"})
        elif not a:
            e = next((x for x in extras if x["projectID"] == f["projectID"]), None)
            if e:
                lock["mods"].append({"file": e.get("file", ""), "name": e["name"], "projectID": f["projectID"], "fileID": f["fileID"],
                                     "url": e.get("download"), "page": e.get("url", ""), "source": "curseforge"})
    for jar in sorted((pack / "overrides" / "mods").glob("*.jar")):
        lock["mods"].append({"file": jar.name, "name": jar.stem, "projectID": 0, "fileID": 0, "url": None, "page": "", "source": "zip"})
    json.dump(lock, open(out / "mods.lock.json", "w", encoding="utf-8"), indent=1)
    restricted = [m["name"] for m in lock["mods"] if m["source"] == "curseforge" and not m["url"]]
    print(f"mods.lock.json: {len(lock['mods'])} jars, {len(restricted)} without a direct download URL")

    slug = re.sub(r"[^a-z0-9]+", "-", args.name.lower()).strip("-")
    zip_path = out / f"{slug}-{args.version}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for p in pack.rglob("*"):
            if p.is_file():
                z.write(p, p.relative_to(pack).as_posix())

    # report + server removal list
    scrapped = [m for m in mods if m["id"] not in kept]
    kept_mods = [m for m in mods if m["id"] in kept]
    groups = {}
    for m in scrapped:
        groups.setdefault(m["group"], []).append(m)
    lines = [f"# {args.name} {args.version}", "",
             f"Kept {len(kept_mods)} mods, scrapped {len(scrapped)} of {len(mods)}.", ""]
    if warnings:
        lines += ["## Warnings", *[f"- {w}" for w in warnings], ""]
    lines.append("## Scrapped")
    for g, ms in sorted(groups.items()):
        lines.append(f"### {g}")
        lines += [f"- {m['name']} ({reasons[m['id']]})" for m in ms]
        lines.append("")
    if extras:
        lines += ["## Added", *[f"- {e['name']} ({e.get('url', '')})" for e in extras], ""]
    if local_jars:
        lines += ["## Our own mods (bundled in overrides/mods)", *[f"- {j}" for j in local_jars], ""]
    if override_jars:
        lines += ["## Non-CurseForge jars carried over from the original pack (overrides/mods)", *[f"- {j}" for j in override_jars], ""]
    # client-only mods never go to the dedicated server
    server_jars = [j for j in local_jars if not any(j.lower().startswith(c) for c in CLIENT_ONLY_MODS)]
    (out / "server-add.txt").write_text("\n".join(server_jars) + "\n", encoding="utf-8")
    lines.append("## Kept libraries (auto-resolved)")
    lines += [f"- {m['name']}: {reasons[m['id']]}" for m in kept_mods if m["rec"] == "auto"]
    (out / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (out / "server-remove.txt").write_text("\n".join(m["file"] for m in scrapped) + "\n", encoding="utf-8")

    print(f"kept {len(kept_mods)} mods, scrapped {len(scrapped)}; manifest files {len(files_out)} (was {len(manifest['files'])}); bundled own jars: {local_jars or 'none built yet'}")
    print(f"pack zip: {zip_path}")
    for w in warnings:
        print("WARNING:", w)


if __name__ == "__main__":
    main()
