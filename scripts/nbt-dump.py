"""Dump a (gzipped or raw) NBT file as indented text, optionally only the subtrees whose path matches a substring.
    python scripts/nbt-dump.py <file.dat> [path-substring ...]
Example: python scripts/nbt-dump.py devworld/playerdata/<uuid>.dat curios accessories"""
import gzip, struct, sys

TAG_NAMES = {0: "End", 1: "Byte", 2: "Short", 3: "Int", 4: "Long", 5: "Float", 6: "Double", 7: "ByteArray",
             8: "String", 9: "List", 10: "Compound", 11: "IntArray", 12: "LongArray"}


class Reader:
    def __init__(self, data):
        self.d, self.p = data, 0

    def take(self, fmt):
        v = struct.unpack_from(">" + fmt, self.d, self.p)
        self.p += struct.calcsize(">" + fmt)
        return v[0]

    def string(self):
        n = self.take("H")
        s = self.d[self.p:self.p + n].decode("utf-8", "replace")
        self.p += n
        return s

    def payload(self, t):
        if t == 1: return self.take("b")
        if t == 2: return self.take("h")
        if t == 3: return self.take("i")
        if t == 4: return self.take("q")
        if t == 5: return self.take("f")
        if t == 6: return self.take("d")
        if t == 7:
            n = self.take("i"); v = list(self.d[self.p:self.p + n]); self.p += n; return v
        if t == 8: return self.string()
        if t == 9:
            et = self.take("b"); n = self.take("i")
            return [self.payload(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                ct = self.take("b")
                if ct == 0: return out
                name = self.string()
                out[name] = self.payload(ct)
        if t == 11:
            n = self.take("i"); return [self.take("i") for _ in range(n)]
        if t == 12:
            n = self.take("i"); return [self.take("q") for _ in range(n)]
        raise ValueError(f"bad tag type {t} at {self.p}")


def load(path):
    raw = open(path, "rb").read()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    r = Reader(raw)
    t = r.take("b"); name = r.string()
    return name, r.payload(t)


def dump(node, indent=0, path=""):
    pad = "  " * indent
    if isinstance(node, dict):
        for k, v in node.items():
            p = f"{path}/{k}"
            if isinstance(v, (dict, list)) and v:
                print(f"{pad}{k}:")
                dump(v, indent + 1, p)
            else:
                print(f"{pad}{k}: {v!r}")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            p = f"{path}[{i}]"
            if isinstance(v, (dict, list)) and v:
                print(f"{pad}[{i}]:")
                dump(v, indent + 1, p)
            else:
                print(f"{pad}[{i}]: {v!r}")


def find(node, needles, path="", hits=None):
    hits = [] if hits is None else hits
    if isinstance(node, dict):
        for k, v in node.items():
            p = f"{path}/{k}"
            if any(n.lower() in k.lower() for n in needles):
                hits.append((p, v))
            else:
                find(v, needles, p, hits)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            find(v, needles, f"{path}[{i}]", hits)
    return hits


if __name__ == "__main__":
    name, root = load(sys.argv[1])
    needles = sys.argv[2:]
    if not needles:
        dump(root)
    else:
        for p, v in find(root, needles):
            print(f"== {p}")
            dump(v, 1, p) if isinstance(v, (dict, list)) else print(f"  {v!r}")
