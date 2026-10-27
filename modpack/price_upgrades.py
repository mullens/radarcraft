"""Price the upgrade catalog for a two-source budget: 165 grid squares + Renown levels (capped at 165).

    python modpack/price_upgrades.py

Core power stats cost 2 points per rank so growth stays slow (the Skybound skill trees handed out hearts too fast).
Prints the totals; the target is a catalog of roughly 300 points against a 330-point theoretical maximum, so a
player who has done most of the grid and earned a lot of Renown can own nearly everything, and nobody else can.
"""
import json
from collections import Counter
from pathlib import Path

P = Path(__file__).resolve().parent / "checklist-upgrades.json"
d = json.load(open(P, encoding="utf-8"))
up = {u["id"]: u for u in d["upgrades"]}

double = ["hearts", "thick_skin", "sturdy", "brawn", "quick_hands", "fleet_foot", "pickaxe_arm", "marksman",
          "vampiric", "last_stand", "long_arms", "builders_reach", "lucky", "studious", "spring_legs", "hardy"]
for k in double:
    up[k]["cost"] = 2
up["haste"]["cost"] = 3
up["second_wind"]["cost"] = 4
up["troll_blood"]["cost"] = 8
up["double_jump"]["cost"] = 6
d["upgrades"] = list(up.values())
json.dump(d, open(P, "w", encoding="utf-8"), indent=1, ensure_ascii=False)

cost = lambda u: u.get("cost", 1) * u["ranks"]
total = sum(cost(u) for u in d["upgrades"])
reach = total - min(cost(up["giant"]), cost(up["smol"]))
c = Counter()
for u in d["upgrades"]:
    c[u["category"]] += cost(u)
print(f"{len(d['upgrades'])} upgrades, catalog {total} points ({reach} reachable); budget max 165 squares + 165 Renown = 330")
print(dict(c))
