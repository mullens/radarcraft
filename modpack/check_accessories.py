"""Does every accessory item in the pack have a slot a player can put it in?

    python modpack/check_accessories.py

Scans every pack jar (including the data packs built into jars: packs/<x>/data, resourcepacks/<x>/data) plus the
pack's kubejs/data for Curios and Accessories slot definitions, the slots given to players, and the item tags that
put items into slots (resolving nested #tags). Then, per ITEM, lists the player slots it can go in. With the
Accessories Compatibility Layer, Curios slots and Accessories slots are one system, so either kind counts.
Built-in packs that may be disabled are reported as "(optional pack)".
"""
import json, re, sys, zipfile, collections
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from packfiles import pack_jars

PREFIX = re.compile(r"^(?:(?:packs|resourcepacks)/([^/]+)/)?data/")
PLAYERS = {"player", "minecraft:player", "#accessories:defaulted_targets"}


def sources():
    jars, _ = pack_jars(include_client_only=True)
    for p, _ in jars:
        try:
            z = zipfile.ZipFile(p)
        except zipfile.BadZipFile:
            continue
        with z:
            for n in z.namelist():
                if not n.endswith(".json"):
                    continue
                m = PREFIX.match(n)
                if not m:
                    continue
                try:
                    data = json.loads(z.read(n).decode("utf-8", "replace") or "{}")
                except Exception:
                    continue
                yield p.name, m.group(1), "data/" + n[m.end():], data
    root = HERE / "out/pack/overrides/kubejs"
    if root.exists():
        for f in root.rglob("*.json"):
            m = re.search(r"(data/.+)$", f.relative_to(root).as_posix())
            if m:
                try:
                    yield "kubejs", None, m.group(1), json.loads(f.read_text(encoding="utf-8", errors="replace") or "{}")
                except Exception:
                    pass


def main():
    slots = {}                                   # "curios:back" / "accessories:back" -> (source, pack)
    player_slots = collections.defaultdict(set)  # slot key -> {(source, pack)}
    item_tags = collections.defaultdict(set)     # "ns:path" -> values
    for src, pack, path, data in sources():
        m = re.match(r"^data/([^/]+)/curios/slots/(.+)\.json$", path)
        if m:
            slots.setdefault("curios:" + m.group(2), (src, pack)); continue
        m = re.match(r"^data/([^/]+)/accessories/slot/(.+)\.json$", path)
        if m:
            slots.setdefault("accessories:" + m.group(2).split("/")[-1], (src, pack)); continue
        m = re.match(r"^data/([^/]+)/(curios/entities|accessories/entity)/(.+)\.json$", path)
        if m:
            kind = "curios" if m.group(2).startswith("curios") else "accessories"
            if {e.lower() for e in data.get("entities", [])} & PLAYERS:
                for s in data.get("slots", []):
                    player_slots[f"{kind}:{s.split(':')[-1].split('/')[-1]}"].add((src, pack))
            continue
        m = re.match(r"^data/([^/]+)/tags/items?/(.+)\.json$", path)
        if m:
            for v in data.get("values", []):
                item_tags[f"{m.group(1)}:{m.group(2)}"].add(v if isinstance(v, str) else v.get("id", ""))

    def resolve(tag, seen=None):
        seen = seen or set()
        out = set()
        for v in item_tags.get(tag, ()):
            if v.startswith("#"):
                t = v[1:]
                if t not in seen:
                    seen.add(t); out |= resolve(t, seen)
            elif v:
                out.add(v)
        return out

    # slot tags: curios:<slot> and accessories:<slot>
    item_slots = collections.defaultdict(set)
    for tag in item_tags:
        ns, slot = tag.split(":", 1)
        if ns not in ("curios", "accessories"):
            continue
        key = f"{ns}:{slot.split('/')[-1]}"
        for item in resolve(tag):
            item_slots[item].add(key)

    def usable(key):
        if key in ("curios:curio", "accessories:any", "accessories:all"):
            return "generic"
        if key not in slots:
            return None
        return "player" if key in player_slots else None

    unplaced, generic_only = [], []
    for item, keys in sorted(item_slots.items()):
        ok = [k for k in keys if usable(k) == "player"]
        gen = [k for k in keys if usable(k) == "generic"]
        if not ok and not gen:
            unplaced.append((item, sorted(keys)))
        elif not ok:
            generic_only.append(item)
    optional = sorted({f"{s}:{p}" for k, v in player_slots.items() for s, p in v if p})
    print(f"{len(item_slots)} accessory items; slots defined: {len(slots)}; slots given to players: {len(player_slots)}")
    print("player slots:", ", ".join(sorted(player_slots)))
    if optional:
        print("slot grants that come from built-in packs:", ", ".join(optional))
    if generic_only:
        print(f"{len(generic_only)} items fit only the generic Curios 'curio' slot (fine, any slot):", ", ".join(generic_only[:8]))
    if not unplaced:
        print("OK: every accessory item has at least one slot a player can put it in")
    for item, keys in unplaced:
        print(f"NO SLOT: {item}  (tagged for {', '.join(keys)})")


if __name__ == "__main__":
    main()
