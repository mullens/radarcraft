"""One-off (release 1.0.5): every upgrade twice as hard again, for the 793-square grid.

Upgrades with many ranks keep 1 point per rank but get twice the ranks at half the effect each (finer steps, same
ceiling). Toggles and the effect-level upgrades (Haste, Village Favorite) cost twice the points.

    python modpack/double_upgrades.py      (rewrites modpack/checklist-upgrades.json and the mod's bundled copy)
"""
import json
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent
P = HERE / "checklist-upgrades.json"
BUNDLED = HERE.parent / "checklist/src/main/resources/assets/checklist/defaults/checklist-upgrades.json"
d = json.load(open(P, encoding="utf-8"))

DESCRIPTIONS = {
    "hearts": "+an eighth of a heart per point (8 points = 1 heart, 80 points = 10 hearts).",
    "thick_skin": "+0.125 armor per point, worn or not (8 points = 1 armor).",
    "sturdy": "+0.125 armor toughness per point: strong hits hurt less.",
    "hardy": "+2.5% knockback resistance per point.",
    "fireproof": "Burn 2.75% shorter per point. All 36 points: permanent Fire Resistance.",
    "deep_lungs": "Hold your breath longer with every point. All 24 points: permanent Water Breathing.",
    "soul_keeper": "Keep 1.25% of your XP levels per point when you die; the rest still drops.",
    "hearty_appetite": "Get hungry 3.75% slower per point.",
    "fleet_foot": "+0.625% movement speed per point.",
    "spring_legs": "Jump a little higher with every point (all 32: clear a fence).",
    "long_stride": "Walk up an eighth of a block more per point without jumping (8 points: full blocks).",
    "featherfall": "Fall 0.75 blocks further per point before taking damage.",
    "cat_landing": "-6.25% fall damage per point.",
    "swimmer": "Move a little faster in water per point.",
    "sneaky": "Sneak a little faster per point.",
    "surefooted": "Soul sand, honey and cobwebs slow you a little less per point.",
    "blast_brace": "Explosions throw you around 7.5% less per point.",
    "moon_walker": "-1.5% gravity per point: floatier jumps and slower falls.",
    "brawn": "+0.0625 melee damage per point (all 48: +3, like Sharpness V).",
    "quick_hands": "+0.625% attack speed per point.",
    "heavy_hitter": "Your hits knock enemies back a little further per point.",
    "sweep": "Sweeping attacks deal +2.5% of your damage per point to everything they hit.",
    "long_arms": "Hit and interact with mobs a sixteenth of a block further per point.",
    "vampiric": "Every mob you kill heals a sixteenth of a heart per point.",
    "thorny": "Reflect 2.5% of melee damage you take per point back at the attacker.",
    "last_stand": "+1.875% damage per point while below 30% health.",
    "marksman": "+1.25% arrow, trident and other projectile damage per point.",
    "pickaxe_arm": "+1.25% block breaking speed per point.",
    "builders_reach": "Place and break blocks a sixteenth of a block further per point.",
    "underwater_miner": "Mine a little faster while submerged per point.",
    "lucky": "+0.125 Luck per point: better fishing and loot tables that care about luck.",
    "magnet": "Dropped items drift to you from 0.375 blocks further per point.",
    "tinkerer": "Worn and held gear slowly repairs itself: 1 durability every 8 minutes per point.",
    "green_thumb": "Crops and saplings within 6 blocks grow faster, more with every point.",
    "giant": "Grow 2.5% per point. Bigger reach and stride, bigger target. Cannot be combined with Smol.",
    "smol": "Shrink 2.5% per point. Fit through gaps, harder to hit, weaker stride. Cannot be combined with Giant.",
    "studious": "+1.25% experience from orbs per point.",
}

before = sum(u.get("cost", 1) * u["ranks"] for u in d["upgrades"])
for u in d["upgrades"]:
    if u["ranks"] > 1 and not u.get("effects"):  # finer steps: twice the ranks, half the effect per rank
        u["ranks"] *= 2
        for m in u.get("modifiers", []):
            m["amount"] = m["amount"] / 2
        if "amount" in u:
            u["amount"] = u["amount"] / 2
        u["description"] = DESCRIPTIONS[u["id"]]
    else:  # toggles and effect levels: twice the points
        u["cost"] = u.get("cost", 1) * 2
missing = [u["id"] for u in d["upgrades"] if u["ranks"] > 1 and not u.get("effects") and u["id"] not in DESCRIPTIONS]
assert not missing, missing
json.dump(d, open(P, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
shutil.copy(P, BUNDLED)

after = sum(u.get("cost", 1) * u["ranks"] for u in d["upgrades"])
print(f"catalog: {before} -> {after} points")
