"""Generates the six Radar loot tables (data/radars/loot_table/chests/radar_2..7.json), vanilla items only.
Re-run after editing the TIERS below:  python scripts/gen-radar-loot.py

A single chest holds 27 stacks and vanilla silently throws away anything beyond that when the loot is
unpacked, so the maximum number of rolls per tier must stay <= 27 (the script asserts it)."""
import json
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "radars/src/main/resources/data/radars/loot_table/chests"


def item(name, weight=1, count=None, functions=None):
    e = {"type": "minecraft:item", "name": name, "weight": weight}
    f = list(functions or [])
    if count:
        lo, hi = count if isinstance(count, tuple) else (count, count)
        f.insert(0, {"function": "minecraft:set_count", "count": {"min": lo, "max": hi} if lo != hi else lo})
    if f:
        e["functions"] = f
    return e


def enchant_levels(lo, hi):
    return {"function": "minecraft:enchant_with_levels", "levels": {"min": lo, "max": hi}}


def enchant_random():
    return {"function": "minecraft:enchant_randomly"}


def pool(rolls, entries, bonus=None):
    p = {"rolls": {"min": rolls[0], "max": rolls[1]} if isinstance(rolls, tuple) else rolls, "entries": entries}
    if bonus:
        p["bonus_rolls"] = bonus
    return p


COMMON = [
    item("minecraft:iron_ingot", 10, (3, 8)),
    item("minecraft:gold_ingot", 8, (2, 6)),
    item("minecraft:arrow", 8, (8, 24)),
    item("minecraft:cooked_beef", 8, (4, 10)),
    item("minecraft:golden_carrot", 6, (3, 8)),
    item("minecraft:experience_bottle", 7, (3, 9)),
    item("minecraft:ender_pearl", 4, (1, 4)),
    item("minecraft:emerald", 5, (2, 6)),
    item("minecraft:lapis_lazuli", 5, (6, 16)),
]
BETTER = [
    item("minecraft:diamond", 6, (1, 4)),
    item("minecraft:iron_block", 3, (1, 2)),
    item("minecraft:gold_block", 2, (1, 2)),
    item("minecraft:experience_bottle", 6, (8, 16)),
    item("minecraft:blaze_rod", 3, (2, 6)),
    item("minecraft:ghast_tear", 2, (1, 3)),
]
GEAR = [
    item("minecraft:diamond_sword", 3, functions=[enchant_levels(20, 30)]),
    item("minecraft:diamond_pickaxe", 3, functions=[enchant_levels(20, 30)]),
    item("minecraft:bow", 3, functions=[enchant_levels(20, 30)]),
    item("minecraft:crossbow", 2, functions=[enchant_levels(20, 30)]),
    item("minecraft:netherite_scrap", 4, (1, 3)),
    item("minecraft:name_tag", 3, (1, 2)),
    item("minecraft:saddle", 2),
]
ARMOUR = [
    item("minecraft:diamond_helmet", 1, functions=[enchant_levels(30, 30)]),
    item("minecraft:diamond_chestplate", 1, functions=[enchant_levels(30, 30)]),
    item("minecraft:diamond_leggings", 1, functions=[enchant_levels(30, 30)]),
    item("minecraft:diamond_boots", 1, functions=[enchant_levels(30, 30)]),
]

TIERS = {
    2: [
        pool((6, 9), COMMON),
        pool(1, [item("minecraft:book", 1, functions=[enchant_random()])]),
    ],
    3: [
        pool((7, 10), COMMON),
        pool((2, 3), BETTER),
        pool(1, [item("minecraft:book", 1, functions=[enchant_levels(15, 25)])]),
    ],
    4: [
        pool((7, 10), COMMON),
        pool((3, 4), BETTER),
        pool((2, 3), GEAR),
        pool(1, [item("minecraft:book", 1, functions=[enchant_levels(20, 30)])]),
    ],
    5: [
        pool((7, 9), COMMON),
        pool((3, 5), BETTER),
        pool((2, 3), GEAR),
        pool((1, 2), ARMOUR),
        pool((2, 4), [item("minecraft:golden_apple", 1, (1, 2))]),
        pool(1, [item("minecraft:totem_of_undying", 1), {"type": "minecraft:empty", "weight": 2}]),
        pool(1, [item("minecraft:book", 1, functions=[enchant_levels(30, 30)])]),
    ],
    6: [
        pool((6, 8), COMMON),
        pool((4, 5), BETTER),
        pool(3, GEAR),
        pool(2, ARMOUR),
        pool(2, [item("minecraft:golden_apple", 1, (2, 4))]),
        pool((1, 2), [item("minecraft:netherite_ingot", 1, (2, 4))]),
        pool(1, [item("minecraft:enchanted_golden_apple", 1)]),
        pool(1, [item("minecraft:totem_of_undying", 1), {"type": "minecraft:empty", "weight": 1}]),
        pool(1, [item("minecraft:elytra", 1), {"type": "minecraft:empty", "weight": 2}]),
        pool((1, 2), [item("minecraft:book", 1, functions=[enchant_levels(30, 30)])]),
    ],
    7: [
        pool((4, 6), COMMON),
        pool((4, 5), BETTER),
        pool(3, GEAR),
        pool(4, ARMOUR),
        pool(1, [item("minecraft:golden_apple", 1, (4, 8))]),
        pool(1, [item("minecraft:netherite_ingot", 1, (3, 6))]),
        pool(1, [item("minecraft:enchanted_golden_apple", 1, (2, 3))]),
        pool(1, [item("minecraft:totem_of_undying", 1)]),
        pool(1, [item("minecraft:trident", 1, functions=[enchant_levels(30, 30)])]),
        pool(1, [item("minecraft:elytra", 1)]),
        pool(1, [item("minecraft:lead", 1, functions=[
            {"function": "minecraft:set_name", "target": "custom_name",
             "name": {"text": "Radar's Collar", "color": "gold", "italic": False, "bold": True}},
            {"function": "minecraft:set_lore", "mode": "replace_all", "lore": [
                {"text": "Worn by the Radiant Radar.", "color": "gray", "italic": True},
                {"text": "All seven Radars are at peace.", "color": "aqua", "italic": False},
            ]},
        ])]),
        pool(2, [item("minecraft:book", 1, functions=[enchant_levels(30, 30)])]),
    ],
}

OUT.mkdir(parents=True, exist_ok=True)
for tier, pools in TIERS.items():
    max_rolls = sum(p["rolls"]["max"] if isinstance(p["rolls"], dict) else p["rolls"] for p in pools)
    assert max_rolls <= 27, f"tier {tier} can roll {max_rolls} stacks, a chest holds 27"
    data = {"type": "minecraft:chest", "pools": pools}
    (OUT / f"radar_{tier}.json").write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    print(f"radar_{tier}.json: {len(pools)} pools, at most {max_rolls} stacks")
