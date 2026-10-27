"""Migrate a server folder (a snapshot or the live server's files) to the trimmed pack.

    python modpack/migrate_server.py <server folder>            # dry run: prints what would change
    python modpack/migrate_server.py <server folder> --apply    # do it (a backup of mods/ and config/ is made first)

What it does
  1. backs up <server>/mods and <server>/config to <server>/_backup-<timestamp>/ (apply only)
  2. deletes the scrapped jars listed in modpack/out/server-remove.txt
  3. copies our own server-side jars (modpack/out/server-add.txt) from */build/libs
  4. adds any jar that the trimmed pack has and the server lacks (from the local CurseForge instance), e.g. Inventory Crafting Grid
  5. copies the Checklist config files (checklist-grid.json, checklist-upgrades.json) into <server>/config
  6. leaves the world, server.properties and everything else alone
Then start the server yourself (or with modpack/boot_server.py) and read the log.
"""
import json, shutil, sys, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
INSTANCE = Path.home() / "curseforge/minecraft/Instances/Create Aeronautics - Skybound  Create Aeronautics & Create Addons with Quests"
_col = HERE / "client-only-mods.txt"
CLIENT_ONLY = tuple(l.strip().lower() for l in open(_col, encoding="utf-8") if l.strip() and not l.startswith("#")) if _col.exists() else ("keyboardkeybinds",)


def is_client_only(name):
    return name.lower().startswith(CLIENT_ONLY)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    server = Path(sys.argv[1]).resolve()
    apply = "--apply" in sys.argv
    mods_dir = server / "mods"
    if not mods_dir.is_dir():
        sys.exit(f"no mods folder in {server}")
    out = HERE / "out"
    remove = [l.strip() for l in open(out / "server-remove.txt", encoding="utf-8") if l.strip()]
    add_own = [l.strip() for l in open(out / "server-add.txt", encoding="utf-8") if l.strip()]
    manifest = json.load(open(out / "pack/manifest.json", encoding="utf-8"))
    instance = json.load(open(INSTANCE / "minecraftinstance.json", encoding="utf-8"))
    by_project = {a["addonID"]: a for a in instance["installedAddons"]}
    extras = json.load(open(HERE / "extra-mods.json", encoding="utf-8")) if (HERE / "extra-mods.json").exists() else []

    present = {p.name for p in mods_dir.glob("*.jar")}
    to_delete = [j for j in remove if j in present]
    # kept pack mods the server lacks (compare by jar name; CurseForge keeps the same file names)
    kept_names = []
    for f in manifest["files"]:
        a = by_project.get(f["projectID"])
        if a and a["categorySection"]["path"] == "mods":
            kept_names.append(a["fileNameOnDisk"])
    to_add_pack = [n for n in kept_names if n not in present and (INSTANCE / "mods" / n).exists() and not is_client_only(n)]
    client_on_server = sorted(n for n in present if is_client_only(n))
    if client_on_server:
        print(f"  NOTE: {len(client_on_server)} client-only jars are on this server and should be deleted too: {', '.join(client_on_server)}")
        to_delete = sorted(set(to_delete) | set(client_on_server))
    to_add_extra = [e for e in extras if e.get("file") and e["file"] not in present]
    to_add_own = []
    for j in add_own:
        src = next(iter((HERE.parent).glob(f"*/build/libs/{j}")), None)
        if src:
            to_add_own.append(src)
    configs = [HERE / n for n in ("checklist-grid.json", "checklist-upgrades.json") if (HERE / n).exists()]

    print(f"server: {server}\n  jars now: {len(present)}")
    print(f"  delete ({len(to_delete)}):"); [print("   -", j) for j in to_delete]
    print(f"  add from pack ({len(to_add_pack)}):"); [print("   +", j) for j in to_add_pack]
    print(f"  add new mods ({len(to_add_extra)}) (download these from CurseForge first, they are not in the local instance):")
    [print("   +", e["file"], e["url"]) for e in to_add_extra]
    print(f"  add own mods ({len(to_add_own)}):"); [print("   +", p.name) for p in to_add_own]
    print(f"  configs ({len(configs)}):"); [print("   +", c.name) for c in configs]
    if not apply:
        print("\ndry run; add --apply to change the folder")
        return

    stamp = time.strftime("%Y%m%d-%H%M%S")
    backup = server / f"_backup-{stamp}"
    backup.mkdir()
    shutil.copytree(mods_dir, backup / "mods")
    if (server / "config").is_dir():
        shutil.copytree(server / "config", backup / "config")
    print(f"backup: {backup}")
    for j in to_delete:
        (mods_dir / j).unlink(); print("deleted", j)
    for j in to_add_pack:
        shutil.copy2(INSTANCE / "mods" / j, mods_dir / j); print("added", j)
    for p in to_add_own:
        for old in mods_dir.glob(p.name.split("-")[0] + "-*.jar"):
            old.unlink(); print("replaced", old.name)
        shutil.copy2(p, mods_dir / p.name); print("added", p.name)
    (server / "config").mkdir(exist_ok=True)
    for c in configs:
        shutil.copy2(c, server / "config" / c.name); print("config", c.name)
    # server.properties: flight must be allowed or airships/paragliders get players kicked
    props = server / "server.properties"
    if props.exists():
        import re as _re
        text = props.read_text(encoding="utf-8")
        if _re.search(r"^allow-flight=false", text, _re.M):
            props.write_text(_re.sub(r"^allow-flight=false", "allow-flight=true", text, flags=_re.M), encoding="utf-8")
            print("server.properties: allow-flight=true")
    print("done. New mods that need a download:", [e["file"] for e in to_add_extra] or "none")


if __name__ == "__main__":
    main()
