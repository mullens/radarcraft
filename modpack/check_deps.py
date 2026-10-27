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


def _parse_tomls(z):
    """Yield (toml text, manifest Implementation-Version) for a zip and its nested jars."""
    import io
    impl = None
    if "META-INF/MANIFEST.MF" in z.namelist():
        mm = re.search(r"^Implementation-Version:\s*(\S+)", z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace"), re.M)
        impl = mm.group(1) if mm else None
    for n in z.namelist():
        if n in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
            yield z.read(n).decode("utf-8", "replace"), impl
        elif n.endswith(".jar"):
            try:
                inner = zipfile.ZipFile(io.BytesIO(z.read(n)))
            except zipfile.BadZipFile:
                continue
            with inner:
                yield from _parse_tomls(inner)


def read_meta(jar):
    """({modid: version}, [(owner, dep, versionRange)]) for required deps, incl. nested jars."""
    versions, ranges = {}, []
    try:
        z = zipfile.ZipFile(jar)
    except zipfile.BadZipFile:
        return versions, ranges
    with z:
        for t, impl in _parse_tomls(z):
            for m in re.finditer(r'^\s*\[\[mods\]\][^\n]*$(.*?)(?=^\s*\[\[|\Z)', t, re.M | re.S):
                mid = re.search(r'^\s*modId\s*=\s*"([^"]+)"', m.group(1), re.M)
                ver = re.search(r'^\s*version\s*=\s*"([^"]+)"', m.group(1), re.M)
                if mid:
                    v = ver.group(1) if ver else None
                    if v and "${" in v:
                        v = impl
                    versions.setdefault(mid.group(1), v)
            for m in re.finditer(r'^\s*\[\[dependencies\.([^\]]+)\]\][^\n]*$(.*?)(?=^\s*\[\[|\Z)', t, re.M | re.S):
                body = m.group(2)
                dep = re.search(r'^\s*modId\s*=\s*"([^"]+)"', body, re.M)
                typ = re.search(r'^\s*type\s*=\s*"([^"]+)"', body, re.M)
                rng = re.search(r'^\s*versionRange\s*=\s*"([^"]+)"', body, re.M)
                if dep and rng and (typ is None or typ.group(1).lower() == "required"):
                    ranges.append((m.group(1).strip('"'), dep.group(1), rng.group(1)))
    return versions, ranges


def _vkey(v):
    v = re.sub(r"^1\.(1[6-9]|2\d)(\.\d+)?-(?=\d)", "", v.strip())  # "1.21.1-3.7.2" -> "3.7.2" (MC-prefixed versions)
    v = v.split("+")[0]
    v = re.sub(r"-(neoforge|forge|fabric)$", "", v)
    main, _, qual = v.partition("-")
    nums = tuple(int(x) for x in re.findall(r"\d+", main)) or (0,)
    nums = nums + (0,) * (4 - len(nums)) if len(nums) < 4 else nums
    q = qual.lower()
    rank = 0 if q.startswith("alpha") else 1 if q.startswith("beta") else 2 if q.startswith(("rc", "pre")) else 3
    qn = tuple(int(x) for x in re.findall(r"\d+", q)) if rank < 3 else ()
    return nums + (rank,) + qn


def in_range(version, rng):
    """Maven-style range check; '*' and unparseable ranges pass."""
    rng = rng.strip()
    if rng in ("*", "") or "${" in rng or version is None:
        return True
    if rng[0] not in "[(" and rng[-1] in ")]":
        rng = "[" + rng                                     # tolerate a missing opening bracket
    if rng[0] not in "[(":
        return _vkey(version) >= _vkey(rng)
    ok = False
    for part in re.findall(r"[\[(][^\])]*[\])]", rng):
        lo_inc, hi_inc = part[0] == "[", part[-1] == "]"
        lo, _, hi = part[1:-1].partition(",")
        if "," not in part:
            hi = lo
        v = _vkey(version)
        good = True
        if lo.strip():
            good &= v >= _vkey(lo) if lo_inc else v > _vkey(lo)
        if hi.strip():
            good &= v <= _vkey(hi) if hi_inc else v < _vkey(hi)
        ok |= good
    return ok


def main():
    mods = json.load(open(HERE / "mods-audit.json", encoding="utf-8"))
    scrapped_files = {l.strip() for l in open(HERE / "out/server-remove.txt", encoding="utf-8") if l.strip()}
    known_files = {m["file"] for m in mods}
    sys.path.insert(0, str(HERE))
    from packfiles import pack_jars
    resolved, _missing = pack_jars(include_client_only=True)
    kept_jars = [p for p, src in resolved if src != "overrides"]
    own_jars = [p for p, src in resolved if src == "overrides"]
    scrapped_jars = [INSTANCE / "mods" / m["file"] for m in mods if m["file"] in scrapped_files]

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
    # version ranges
    versions, all_ranges = {}, []
    for jar in kept_jars + own_jars:
        v, r = read_meta(jar)
        for k, val in v.items():  # JarJar loads the newest copy of a mod id, so keep the highest version seen
            if val and (k not in versions or versions[k] is None or _vkey(val) > _vkey(versions[k])):
                versions[k] = val
        all_ranges += [(jar.name, o, d, rg) for o, d, rg in r]
    for jar, owner, dep, rng in sorted(set(all_ranges)):
        if dep in BUILTIN or dep not in versions:
            continue
        if not in_range(versions[dep], rng):
            problems += 1
            print(f"VERSION: {jar} [{owner}] requires {dep} {rng}, pack has {versions[dep]}")
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
