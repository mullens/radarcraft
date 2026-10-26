"""Scan a Minecraft world for block / block-entity / entity ids per mod namespace, and flag scrapped mods.

    python modpack/scan_world.py <world folder>

Reads every region file (overworld, nether, end, entities) with a minimal NBT parser; no third-party libraries.
Useful before migrating a server: whatever is listed under a scrapped namespace will turn into air / vanish.
"""
import gzip, io, re, struct, sys, zlib, zipfile, collections
from pathlib import Path

HERE = Path(__file__).resolve().parent
INSTANCE = Path.home() / "curseforge/minecraft/Instances/Create Aeronautics - Skybound  Create Aeronautics & Create Addons with Quests"


# ---- minimal NBT ----
def read_nbt(data):
    pos = [0]
    def u8(): v = data[pos[0]]; pos[0] += 1; return v
    def i16(): v = struct.unpack_from(">h", data, pos[0])[0]; pos[0] += 2; return v
    def i32(): v = struct.unpack_from(">i", data, pos[0])[0]; pos[0] += 4; return v
    def i64(): v = struct.unpack_from(">q", data, pos[0])[0]; pos[0] += 8; return v
    def f32(): v = struct.unpack_from(">f", data, pos[0])[0]; pos[0] += 4; return v
    def f64(): v = struct.unpack_from(">d", data, pos[0])[0]; pos[0] += 8; return v
    def string():
        n = struct.unpack_from(">H", data, pos[0])[0]; pos[0] += 2
        s = data[pos[0]:pos[0] + n].decode("utf-8", "replace"); pos[0] += n; return s
    def payload(t):
        if t == 1: return u8()
        if t == 2: return i16()
        if t == 3: return i32()
        if t == 4: return i64()
        if t == 5: return f32()
        if t == 6: return f64()
        if t == 7: n = i32(); pos[0] += n; return None
        if t == 8: return string()
        if t == 9:
            lt = u8(); n = i32(); return [payload(lt) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = u8()
                if tt == 0: return d
                name = string(); d[name] = payload(tt)
        if t == 11: n = i32(); pos[0] += 4 * n; return None
        if t == 12: n = i32(); pos[0] += 8 * n; return None
        raise ValueError(f"bad tag {t}")
    t = u8(); string(); return payload(t)


def chunks(region_path):
    data = region_path.read_bytes()
    if len(data) < 8192:
        return
    for i in range(1024):
        off, cnt = struct.unpack_from(">I", data, i * 4)[0] >> 8, data[i * 4 + 3]
        if off == 0 or cnt == 0:
            continue
        start = off * 4096
        length = struct.unpack_from(">i", data, start)[0]
        comp = data[start + 4]
        raw = data[start + 5:start + 4 + length]
        try:
            if comp == 2: raw = zlib.decompress(raw)
            elif comp == 1: raw = gzip.decompress(raw)
            elif comp == 3: pass
            else: continue
            yield read_nbt(raw)
        except Exception:
            continue


def walk(node, out_blocks, out_be, out_ent):
    if isinstance(node, dict):
        if "block_states" in node and isinstance(node["block_states"], dict):
            for p in node["block_states"].get("palette") or []:
                if isinstance(p, dict) and "Name" in p:
                    out_blocks[p["Name"]] += 1
        for be in node.get("block_entities") or []:
            if isinstance(be, dict) and "id" in be:
                out_be[be["id"]] += 1
        for e in node.get("Entities") or []:
            if isinstance(e, dict) and "id" in e:
                out_ent[e["id"]] += 1
        for v in node.values():
            walk(v, out_blocks, out_be, out_ent)
    elif isinstance(node, list):
        for v in node:
            walk(v, out_blocks, out_be, out_ent)


def scrapped_namespaces():
    sys.path.insert(0, str(HERE))
    from check_deps import read_toml
    rm = [l.strip() for l in open(HERE / "out/server-remove.txt", encoding="utf-8") if l.strip()]
    ns = {}
    for jar in rm:
        p = INSTANCE / "mods" / jar
        if p.exists():
            prov, _ = read_toml(p)
            for mid in prov:
                ns[mid] = jar
    return ns


def main():
    world = Path(sys.argv[1]).resolve()
    blocks, bes, ents = collections.Counter(), collections.Counter(), collections.Counter()
    files = list(world.glob("region/*.mca")) + list(world.glob("DIM*/region/*.mca")) + list(world.glob("entities/*.mca")) + list(world.glob("DIM*/entities/*.mca")) + list(world.glob("dimensions/*/*/region/*.mca"))
    n = 0
    for f in files:
        for c in chunks(f):
            n += 1; walk(c, blocks, bes, ents)
    print(f"{len(files)} region files, {n} chunks")
    for title, cnt in (("blocks (palette entries)", blocks), ("block entities", bes), ("entities", ents)):
        ns = collections.Counter()
        for k, v in cnt.items():
            ns[k.split(":")[0]] += v
        print(f"\n== {title}: {len(cnt)} ids, namespaces: " + ", ".join(f"{k}={v}" for k, v in ns.most_common(25)))
    scrapped = scrapped_namespaces()
    hits = [(k, v) for cnt in (blocks, bes, ents) for k, v in cnt.items() if k.split(":")[0] in scrapped]
    print("\n== from SCRAPPED mods (will become air / vanish after migration):")
    if not hits:
        print("   none found in this world")
    for k, v in sorted(hits, key=lambda x: -x[1]):
        print(f"   {v:6}  {k}   ({scrapped[k.split(':')[0]]})")


if __name__ == "__main__":
    main()
