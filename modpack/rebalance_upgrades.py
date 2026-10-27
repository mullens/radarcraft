"""One-off: per-rank point costs, Second Wind at 3, 20 new abilities, rank counts rebalanced to the grid's 165 points.

    python modpack/rebalance_upgrades.py      (rewrites modpack/checklist-upgrades.json)
"""
import json
from collections import Counter
from pathlib import Path

P = Path(__file__).resolve().parent / "checklist-upgrades.json"
d = json.load(open(P, encoding="utf-8"))
up = {u["id"]: u for u in d["upgrades"]}

ranks = {"hearts": 10, "thick_skin": 6, "sturdy": 4, "hardy": 4, "fireproof": 3, "deep_lungs": 3, "second_wind": 1,
         "fleet_foot": 6, "spring_legs": 4, "long_stride": 2, "featherfall": 3, "cat_landing": 2, "swimmer": 3, "sneaky": 2,
         "surefooted": 2, "blast_brace": 3, "moon_walker": 3, "brawn": 6, "quick_hands": 5, "heavy_hitter": 3, "sweep": 3,
         "long_arms": 4, "pickaxe_arm": 6, "builders_reach": 5, "underwater_miner": 2, "lucky": 4, "haste": 2, "magnet": 3,
         "night_eyes": 1, "giant": 4, "smol": 4}
for k, r in ranks.items():
    up[k]["ranks"] = r

# fewer ranks, bigger steps, same ceilings
retune = {
    "fireproof": ("Burn 33% shorter per rank. At rank 3, permanent Fire Resistance.", -0.33),
    "deep_lungs": ("Hold your breath much longer per rank. At rank 3, permanent Water Breathing.", 2.0),
    "featherfall": ("Fall 3 blocks further per rank before taking damage.", 3.0),
    "cat_landing": ("-25% fall damage per rank.", -0.25),
    "sturdy": ("+1 armor toughness per rank: strong hits hurt less.", 1.0),
    "hardy": ("+20% knockback resistance per rank.", 0.2),
    "blast_brace": ("Explosions throw you around 30% less per rank.", 0.3),
    "lucky": ("+2 Luck per rank: better fishing and loot tables that care about luck.", 2.0),
}
for k, (desc, amount) in retune.items():
    up[k]["description"] = desc
    up[k]["modifiers"][0]["amount"] = amount

up["second_wind"]["cost"] = 3
up["second_wind"]["description"] = "Once every 5 minutes, a hit that would kill you leaves you at half a heart instead."
up["night_eyes"]["cost"] = 2
up["haste"]["cost"] = 2

new = [
    # toggles: one rank, several points
    {"id": "conduit_heart", "name": "Conduit Heart", "category": "Vitality", "ranks": 1, "cost": 3,
     "description": "Permanent Conduit Power: breathe, see and mine underwater as if a conduit were near.",
     "effects": [{"effect": "minecraft:conduit_power", "amplifier": 0}]},
    {"id": "troll_blood", "name": "Troll Blood", "category": "Vitality", "ranks": 1, "cost": 6,
     "description": "Permanent Regeneration I.", "effects": [{"effect": "minecraft:regeneration", "amplifier": 0}]},
    {"id": "iron_stomach", "name": "Iron Stomach", "category": "Vitality", "ranks": 1, "cost": 2,
     "description": "Food never gives you Hunger or Nausea: rotten flesh, raw chicken and pufferfish are safe.", "special": "iron_stomach"},
    {"id": "antidote", "name": "Antidote", "category": "Vitality", "ranks": 1, "cost": 4,
     "description": "Immune to Poison and Wither.", "special": "antidote"},
    {"id": "cold_blooded", "name": "Cold Blooded", "category": "Vitality", "ranks": 1, "cost": 1,
     "description": "Powder snow and freezing never hurt you.", "special": "cold_blooded"},
    {"id": "dolphin_kick", "name": "Dolphin Kick", "category": "Mobility", "ranks": 1, "cost": 2,
     "description": "Permanent Dolphin's Grace: swim like a dolphin.", "effects": [{"effect": "minecraft:dolphins_grace", "amplifier": 0}]},
    {"id": "double_jump", "name": "Double Jump", "category": "Mobility", "ranks": 1, "cost": 5,
     "description": "Press jump again in mid-air for a second jump. Recharges when you land.", "special": "double_jump"},
    {"id": "shadow_step", "name": "Shadow Step", "category": "Senses", "ranks": 1, "cost": 3,
     "description": "Turn invisible while sneaking and standing still.", "special": "shadow_step"},
    {"id": "ender_calm", "name": "Ender Calm", "category": "Senses", "ranks": 1, "cost": 2,
     "description": "Endermen don't mind being looked at.", "special": "ender_calm"},
    {"id": "smelters_touch", "name": "Smelter's Touch", "category": "Gathering", "ranks": 1, "cost": 4,
     "description": "Ores you mine drop smelted results, logs drop charcoal, sand drops glass. Sneak to mine normally.", "special": "smelters_touch"},
    # several points per rank
    {"id": "village_favorite", "name": "Village Favorite", "category": "Senses", "ranks": 3, "cost": 2,
     "description": "Villagers give you hero prices: a bigger discount per rank.",
     "effects": [{"effect": "minecraft:hero_of_the_village", "amplifier": 0}]},
    {"id": "soul_keeper", "name": "Soul Keeper", "category": "Vitality", "ranks": 4, "cost": 2,
     "description": "Keep 25% of your XP levels per rank when you die; the rest still drops.", "special": "soul_keeper"},
    {"id": "tinkerer", "name": "Tinkerer", "category": "Gathering", "ranks": 3, "cost": 2,
     "description": "Worn and held gear slowly repairs itself: 1 durability every 20 seconds per rank.", "special": "tinkerer"},
    # one point per rank
    {"id": "studious", "name": "Studious", "category": "Senses", "ranks": 5,
     "description": "+10% experience from orbs per rank.", "special": "studious"},
    {"id": "vampiric", "name": "Vampiric", "category": "Combat", "ranks": 3,
     "description": "Heal half a heart per rank for every mob you kill.", "special": "vampiric"},
    {"id": "thorny", "name": "Thorny", "category": "Combat", "ranks": 3,
     "description": "Reflect 10% of melee damage you take per rank back at the attacker.", "special": "thorny"},
    {"id": "last_stand", "name": "Last Stand", "category": "Combat", "ranks": 3,
     "description": "+15% damage per rank while below 30% health.", "special": "last_stand"},
    {"id": "marksman", "name": "Marksman", "category": "Combat", "ranks": 5,
     "description": "+10% arrow, trident and other projectile damage per rank.", "special": "marksman"},
    {"id": "green_thumb", "name": "Green Thumb", "category": "Gathering", "ranks": 3,
     "description": "Crops and saplings within 6 blocks grow faster, more per rank.", "special": "green_thumb"},
    {"id": "hearty_appetite", "name": "Hearty Appetite", "category": "Vitality", "ranks": 4,
     "description": "Get hungry 15% slower per rank.", "special": "hearty_appetite"},
]
assert len(new) == 20
order = ["Vitality", "Mobility", "Combat", "Gathering", "Senses"]
merged = []
for cat in order:
    merged += [u for u in list(up.values()) + new if u["category"] == cat]
ids = [u["id"] for u in merged]
assert len(ids) == len(set(ids))
d["upgrades"] = merged
d["_comment"] = ("Upgrade catalog for Radarcraft RPG (mod id checklist). One point per completed grid square. 'cost' = points "
                 "per rank (default 1); a one-rank upgrade with cost > 1 is a toggle. Ranks can be removed at any time for free. "
                 "'amount' and 'amplifier' are per rank. operation: add_value | add_multiplied_base | add_multiplied_total.")
json.dump(d, open(P, "w", encoding="utf-8"), indent=1, ensure_ascii=False)

cost = lambda u: u.get("cost", 1) * u["ranks"]
total = sum(cost(u) for u in merged)
reach = total - min(cost(up["giant"]), cost(up["smol"]))
print(f"{len(merged)} upgrades, total {total} points ({reach} reachable, Giant/Smol exclusive); the grid gives 165")
c = Counter()
for u in merged:
    c[u["category"]] += cost(u)
print(dict(c))
print("toggles:", [(u["name"], u["cost"]) for u in merged if u["ranks"] == 1 and u.get("cost", 1) > 1])
print("specials:", sorted({u["special"] for u in merged if u.get("special")}))
