"""Assemble the trimmed Skybound pack into dev-driver/run-fullpack so the dev client can boot the whole
pack with this repo's mods on the classpath. Run from the repo root:  python scripts/assemble-fullpack.py

Sources: the local CurseForge instance (jars by name, matched to modpack/out/pack/manifest.json through
minecraftinstance.json), plus modpack/out/pack/overrides (mods, config, kubejs, defaultconfigs).
Client-only mods are included. Our own mods are skipped (they come from the Gradle run itself)."""
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INSTANCE = Path(r"C:\Users\lucas\curseforge\minecraft\Instances\Create Aeronautics - Skybound  Create Aeronautics & Create Addons with Quests")
PACK = ROOT / "modpack" / "out" / "pack"
RUN = ROOT / "dev-driver" / "run-fullpack"
OUR_MODS = ("levellossonly", "keyboardkeybinds", "cherishedmobs", "radars", "checklist", "devdriver")

manifest = json.load(open(PACK / "manifest.json", encoding="utf-8"))
instance = json.load(open(INSTANCE / "minecraftinstance.json", encoding="utf-8"))
by_project = {a["addonID"]: a for a in instance["installedAddons"]}

mods = RUN / "mods"
if mods.exists():
    shutil.rmtree(mods)
mods.mkdir(parents=True)

copied, missing, skipped = 0, [], []
for f in manifest["files"]:
    a = by_project.get(f["projectID"])
    if not a:
        missing.append(f"projectID {f['projectID']} (not in local instance)")
        continue
    if a["categorySection"]["path"] != "mods":
        continue
    name = a["fileNameOnDisk"]
    src = INSTANCE / "mods" / name
    if any(name.lower().startswith(m) for m in OUR_MODS):
        skipped.append(name)
        continue
    if not src.exists():
        missing.append(name)
        continue
    shutil.copy2(src, mods / name)
    copied += 1

for jar in (PACK / "overrides" / "mods").glob("*.jar"):
    if any(jar.name.lower().startswith(m) for m in OUR_MODS):
        skipped.append(jar.name)
        continue
    shutil.copy2(jar, mods / jar.name)
    copied += 1

for sub in ("config", "kubejs", "defaultconfigs"):
    dst = RUN / sub
    if dst.exists():
        shutil.rmtree(dst)
    src = PACK / "overrides" / sub
    if src.exists():
        shutil.copytree(src, dst)

shutil.copy2(ROOT / "scripts" / "dev-options.txt", RUN / "options.txt")
(RUN / "config").mkdir(exist_ok=True)

print(f"copied {copied} jars into {mods}")
print(f"skipped our own mods: {skipped}")
if missing:
    print(f"MISSING {len(missing)}:")
    for m in missing:
        print("  ", m)
sys.exit(1 if missing else 0)
