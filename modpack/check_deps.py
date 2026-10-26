"""Check the trimmed mod set against the dependencies the jars themselves declare (META-INF/neoforge.mods.toml).

    python modpack/check_deps.py

Reports: required mod ids that no kept jar provides, kept jars that require a scrapped jar, jars in the instance
that the pack manifest does not know about, and duplicate mod ids.
"""
import json, re, sys, zipfile, collections
from pathlib import Path

HERE = Path(__file__).resolve().parent
INSTANCE = Path.home() / "curseforge/minecraft/Instances/Create Aeronautics - Skybound  Create Aeronautics & Create Addons with Quests"
BUILTIN = {"neoforge", "minecraft", "forge", "fabric", "java"}


def read_toml(jar):
    """Return (provided mod ids, {modid: [required dep ids]}) for one jar, including jar-in-jar nested jars."""
    provided, requires = set(), collections.defaultdict(set)
    try:
        z = zipfile.ZipFile(jar)
    except zipfile.BadZipFile:
        return provided, requires
    with z:
        tomls = [n for n in z.namelist() if n in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml")]
        for n in tomls:
            t = z.read(n).decode("utf-8", "replace")
            # [[mods]] blocks: modId="x"; dependency blocks: [[dependencies.x]] ... modId="y" type="required"
            for m in re.finditer(r'^\s*\[\[mods\]\][^\n]*$(.*?)(?=^\s*\[\[|\Z)', t, re.M | re.S):
                mid = re.search(r'^\s*modId\s*=\s*"([^"]+)"', m.group(1), re.M)
                if mid:
                    provided.add(mid.group(1))
            for m in re.finditer(r'^\s*\[\[dependencies\.([^\]]+)\]\][^\n]*$(.*?)(?=^\s*\[\[|\Z)', t, re.M | re.S):
                owner, body = m.group(1).strip('"'), m.group(2)
                dep = re.search(r'^\s*modId\s*=\s*"([^"]+)"', body, re.M)
                typ = re.search(r'^\s*type\s*=\s*"([^"]+)"', body, re.M)
                side = re.search(r'^\s*side\s*=\s*"([^"]+)"', body, re.M)
                if dep and (typ is None or typ.group(1).lower() == "required"):
                    requires[owner].add((dep.group(1), side.group(1).upper() if side else "BOTH"))
        # jar-in-jar
        for n in z.namelist():
            if n.endswith(".jar"):
                import io
                try:
                    inner = zipfile.ZipFile(io.BytesIO(z.read(n)))
                except zipfile.BadZipFile:
                    continue
                with inner:
                    for t2 in [x for x in inner.namelist() if x in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml")]:
                        t = inner.read(t2).decode("utf-8", "replace")
                        for m in re.finditer(r'^\s*\[\[mods\]\][^\n]*$(.*?)(?=^\s*\[\[|\Z)', t, re.M | re.S):
                            mid = re.search(r'^\s*modId\s*=\s*"([^"]+)"', m.group(1), re.M)
                            if mid:
                                provided.add(mid.group(1))
    return provided, requires


def main():
    mods = json.load(open(HERE / "mods-audit.json", encoding="utf-8"))
    scrapped_files = {l.strip() for l in open(HERE / "out/server-remove.txt", encoding="utf-8") if l.strip()}
    known_files = {m["file"] for m in mods}
    kept_jars = [INSTANCE / "mods" / m["file"] for m in mods if m["file"] not in scrapped_files]
    scrapped_jars = [INSTANCE / "mods" / m["file"] for m in mods if m["file"] in scrapped_files]
    own_jars = [p for p in (HERE.parent).glob("*/build/libs/*.jar") if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]

    provided_by = collections.defaultdict(list)
    requires = {}
    for jar in kept_jars + own_jars:
        prov, req = read_toml(jar)
        for p in prov:
            provided_by[p].append(jar.name)
        for owner, deps in req.items():
            requires[(jar.name, owner)] = deps
    scrapped_provides = {}
    for jar in scrapped_jars:
        prov, _ = read_toml(jar)
        for p in prov:
            scrapped_provides[p] = jar.name

    print(f"kept jars: {len(kept_jars)} (+{len(own_jars)} own), scrapped: {len(scrapped_jars)}, mod ids provided: {len(provided_by)}")
    problems = 0
    for (jar, owner), deps in sorted(requires.items()):
        for dep, side in sorted(deps):
            if dep in BUILTIN or dep in provided_by:
                continue
            problems += 1
            where = f"SCRAPPED ({scrapped_provides[dep]})" if dep in scrapped_provides else "not in the pack at all"
            print(f"MISSING: {jar} [{owner}] requires '{dep}' ({side}) -> {where}")
    dupes = {k: v for k, v in provided_by.items() if len(v) > 1}
    for k, v in sorted(dupes.items()):
        print(f"DUPLICATE mod id '{k}': {v}")
    stray = sorted(p.name for p in (INSTANCE / "mods").glob("*.jar") if p.name not in known_files)
    for s in stray:
        prov, _ = read_toml(INSTANCE / "mods" / s)
        print(f"STRAY jar not in the CurseForge manifest: {s} (provides {sorted(prov)})")
    print("OK: no missing required dependencies" if not problems else f"{problems} missing dependencies")


if __name__ == "__main__":
    main()
