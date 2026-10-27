"""Design the Food Buffs config from the in-game dump (modpack/foodbuffs-dump.json, made with /foodbuffs dump).

    python modpack/foodbuffs_design.py

1. Difficulty: for every food, the cheapest way to make it, walking the recipe tree: each step costs by machine
   (crafting 1, furnace 1, cutting board 0.5, cooking pot 2, Create mixing 2, fermenting 3...), plus a share of each
   distinct ingredient's own cost, divided by how many servings one craft yields. Raw/foraged items cost 1, items
   from other dimensions 3, containers (bowl, bottle, bucket) nothing.
2. Tiers relative to vanilla: tier 0 = no harder than vanilla's everyday foods (no buff), up to tier 5 for the
   hardest dishes in the pack. Vanilla foods are scored too, as the yardstick, and never changed.
3. Theme: keywords in the food's id/name and in its ingredients pick the buffs (fish -> water breathing, spicy/Nether
   -> fire resistance, honey -> regeneration, coffee -> haste, sweets -> speed, meat -> strength, ...). Tier sets
   how many buffs, their level and duration.
4. Foods whose mod already gives them effects keep those effects (including negative flavor ones); ours are added
   only where the dish is under-rewarded for its difficulty. Golden Foods keep their own golden-apple design.
Writes modpack/foodbuffs.json (the mod's config) and modpack/food-buffs.md (the table to review).
"""
import json, math, re, collections
from pathlib import Path

HERE = Path(__file__).resolve().parent
D = json.load(open(HERE / "foodbuffs-dump.json", encoding="utf-8"))
FOODS = {f["id"]: f for f in D["foods"]}
PROD = collections.defaultdict(list)
for r in D["recipes"]:
    PROD[r["result"]].append(r)

STEP = {"minecraft:crafting": 1.0, "minecraft:smelting": 1.0, "minecraft:smoking": 1.0, "minecraft:campfire_cooking": 1.0,
        "farmersdelight:cutting": 0.5, "sliceanddice:cutting": 0.5, "refurbished_furniture:cutting_board_slicing": 0.5,
        "farmersdelight:cooking": 2.0, "create:mixing": 2.0, "create:compacting": 2.0, "create:filling": 1.5,
        "create:sequenced_assembly": 3.0, "create:haunting": 2.0, "vinery:wine_fermentation": 3.0,
        "vinery:apple_fermentation": 3.0, "createdieselgenerators:basin_fermenting": 2.5, "beachparty:palm_bar_mixing": 2.0,
        "aether:enchanting": 2.0, "undergarden:infusing": 2.0, "eternal_starlight:drying": 1.5}
FREE = {"minecraft:bowl", "minecraft:glass_bottle", "minecraft:bucket", "minecraft:water_bucket", "minecraft:stick",
        "minecraft:paper", "minecraft:potion"}
DIM = {"aether", "deep_aether", "the_bumblezone", "undergarden", "eternal_starlight", "deeperdarker", "cyberspace",
       "creatingspace", "endersdelight", "mynethersdelight"}
DIM_ITEM = re.compile(r"chorus|ender|shulker|blaze|magma|ghast|wither|nether_wart|crimson|warped|strider|hoglin")

memo = {}


def cost(item, depth=0, stack=()):
    if item in FREE:
        return 0.0
    if item in memo:
        return memo[item]
    if depth > 7:
        return 4.0
    if item in stack:
        return math.inf   # compression loop (tomato -> crate -> tomato): not a real way to make it
    raw = 1.0
    ns = item.split(":")[0]
    if ns in DIM or DIM_ITEM.search(item.split(":")[1]):
        raw = 3.0
    best = None
    for r in PROD.get(item, []):
        step = STEP.get(r["type"], 1.5)
        ings = []
        for opts in r["ingredients"]:
            opts = [o for o in opts if o != item]
            if not opts:
                continue
            ings.append(min(cost(o, depth + 1, stack + (item,)) for o in opts))
        if any(math.isinf(x) for x in ings):
            continue
        distinct = len(r["ingredients"])
        total = step + 0.6 * sum(ings) + max(ings + [0]) * 0.4 + 0.25 * max(0, distinct - 2)
        total /= max(1.0, r.get("count", 1)) ** 0.5
        if best is None or total < best:
            best = total
    if best is None and item in FOODS:
        # feast servings come from placing the feast block, not a recipe: a quarter of the feast's cost each
        ns2, path = item.split(':', 1)
        for block in (f'{ns2}:{path}_block', f'{ns2}:{path}_feast', f'{ns2}:{path.removesuffix("_slice")}', f'{ns2}:{path.removesuffix("_slice")}_block'):
            if block != item and block in PROD:
                best = cost(block, depth + 1, stack + (item,)) / 4 + 0.5
                break
    c = raw if best is None else max(best, 0.5) if raw <= 1 else max(best, raw)
    # vanilla materials that are not food (ice, sugar, wheat, melon...) are gathered, not crafted: never more than raw
    if ns == "minecraft" and item not in FOODS:
        c = min(c, raw)
    memo[item] = c
    return c


THEMES = [  # (name, regex on id + name + ingredients, primary effect, secondary effect)
    ("aether",   r"aether|ambrosium|swet|gummy|cloud|skyroot|moa|aerclo|blueberry_pie|enchanted_berry", "minecraft:slow_falling", "minecraft:jump_boost"),
    ("end",      r"chorus|ender_|endersdelight|shulker|void|dragon|purpur", "minecraft:slow_falling", "minecraft:resistance"),
    ("deep",     r"undergarden|gloom|sculk|deeperdarker|bloom|glowshroom|dark|ink", "minecraft:night_vision", "minecraft:resistance"),
    ("bee",      r"honey|(^|[:_ ])bee([_ s]|$)|pollen|royal_jelly|honeycomb|bumblezone", "minecraft:regeneration", "minecraft:absorption"),
    ("fire",     r"spicy|chili|pepper|hot|curry|blaze|magma|nether|crimson|warped|strider|hoglin|ghast|fire|lava|ember|scorch|lava", "minecraft:fire_resistance", "minecraft:strength"),
    ("sea",      r"fish|cod|salmon|squid|kelp|sushi|crab|shrimp|lobster|clam|oyster|tuna|sea|seaweed|calamari|eel|ocean|aquaculture|fillet|roe|nautilus", "minecraft:water_breathing", "minecraft:dolphins_grace"),
    ("stars",    r"starlight|lunar|astral|star|moon|eternal_starlight|aurora", "minecraft:night_vision", "minecraft:luck"),
    ("brew",     r"coffee|tea|cocoa|espresso|energy|latte|mocha|matcha|soda|cola", "minecraft:haste", "minecraft:speed"),
    ("wine",     r"wine|beer|cider|mead|ale|liquor|cocktail|vinery|grapejuice|juice|smoothie|punch|daiquiri|mojito|colada", "minecraft:luck", "minecraft:haste"),
    ("sweet",    r"cake|pie|cookie|candy|chocolate|sugar|sweet|ice_cream|icecream|pudding|tart|jam|jelly|donut|muffin|waffle|pancake|cupcake|fudge|caramel|syrup|gummy|popsicle|sorbet|cheesecake|brownie|glaze", "minecraft:speed", "minecraft:jump_boost"),
    ("glow",     r"glow|carrot|eye|lumi|bright", "minecraft:night_vision", "minecraft:speed"),
    ("meat",     r"steak|beef|mutton|pork|bacon|ham|meat|roast|burger|sausage|shank|rib|chop|venison|jerky|kebab|barbecue|bbq|drumstick|wing|chicken|turkey|lamb|bison|boar|deer|duck|patty", "minecraft:strength", "minecraft:resistance"),
    ("stew",     r"stew|soup|broth|porridge|chowder|bisque|chili_con|goulash|congee|ramen|noodle", "minecraft:regeneration", "minecraft:resistance"),
    ("bread",    r"bread|sandwich|toast|wrap|burrito|taco|pasta|pizza|dumpling|bun|roll|bagel|pastry|pie_crust|quiche|lasagna|rice|risotto|omelette|egg|breakfast", "minecraft:haste", "minecraft:absorption"),
    ("garden",   r"salad|vegetable|veggie|cabbage|tomato|onion|lettuce|greens|herb|mushroom|fungus|berry|fruit|apple|melon|pumpkin|potato|beet|corn|grape|banana|coconut|mango|pineapple", "minecraft:luck", "minecraft:regeneration"),
]
GENERAL_TOP = "minecraft:health_boost"   # tier 5 third buff: a well-fed feeling

TIER_SHAPE = {  # tier -> list of (which, amplifier, seconds); "p" primary, "s" secondary, "g" general
    1: [("p", 0, 45)],
    2: [("p", 0, 120)],
    3: [("p", 0, 240), ("s", 0, 150)],
    4: [("p", 0, 420), ("s", 0, 300)],
    5: [("p", 1, 360), ("s", 0, 480), ("g", 0, 480)],
}
NEGATIVE = {"minecraft:nausea", "minecraft:poison", "minecraft:hunger", "minecraft:wither", "minecraft:blindness",
            "minecraft:slowness", "minecraft:weakness", "minecraft:mining_fatigue", "minecraft:levitation", "minecraft:darkness"}


def theme_of(fid):
    f = FOODS[fid]
    text = (fid + " " + f.get("name", "")).lower()
    ing_text = " ".join(o for r in PROD.get(fid, [])[:3] for opts in r["ingredients"] for o in opts[:2]).lower()
    for name, rx, p, s in THEMES:
        if re.search(rx, text):
            return name, p, s
    for name, rx, p, s in THEMES:
        if re.search(rx, ing_text):
            return name, p, s
    return "plain", "minecraft:absorption", "minecraft:regeneration"


def main():
    scores = {fid: cost(fid) for fid in FOODS}
    vanilla = sorted(v for k, v in scores.items() if k.startswith("minecraft:"))
    # anchors: everyday vanilla food (bread, baked potato, cooked meat) ~ median; cake / rabbit stew / golden carrot ~ top
    v_med = vanilla[len(vanilla) // 2]
    v_top = vanilla[int(len(vanilla) * 0.9)]
    modded = sorted((v, k) for k, v in scores.items() if not k.startswith("minecraft:"))
    hard = [v for v, k in modded if v > v_top]
    cut = [hard[int(len(hard) * q)] for q in (0.5, 0.85, 0.96)] if hard else [v_top + 1, v_top + 2, v_top + 3]

    def tier(v):
        if v <= v_med: return 0
        if v <= v_top: return 1 if v <= (v_med + v_top) / 2 else 2
        if v <= cut[0]: return 3
        if v <= cut[1]: return 4
        return 5 if v > cut[2] else 4

    out, rows = {}, []
    for fid in sorted(FOODS):
        if fid.startswith("minecraft:"):
            continue
        f = FOODS[fid]
        t = tier(scores[fid])
        if fid.startswith("golden_foods:") or "golden" in fid:
            t = max(t, 3)
        theme, p, s = theme_of(fid)
        own = {e["effect"] for e in f["effects"] if e.get("chance", 1) >= 0.5}
        own_pos = own - NEGATIVE
        effects = []
        if fid.startswith("golden_foods:") or fid.startswith("eat_an_omelette:golden") or fid.startswith("eat_an_omelette:enchanted"):
            shape = []   # golden foods: their own golden-apple design is the point
        elif len(own_pos) >= len(TIER_SHAPE.get(t, [])):
            shape = []   # the mod already rewards this dish at least as much
        else:
            shape = TIER_SHAPE.get(t, [])[len(own_pos):] if own_pos else TIER_SHAPE.get(t, [])
        for which, amp, sec in shape:
            eff = {"p": p, "s": s, "g": GENERAL_TOP}[which]
            if eff in own or any(e["effect"] == eff for e in effects):
                eff = GENERAL_TOP if which != "g" and GENERAL_TOP not in own else None
            if eff and not any(e["effect"] == eff for e in effects):
                effects.append({"effect": eff, "amplifier": amp, "seconds": sec})
        out[fid] = {"tier": t, "effects": effects}
        rows.append((t, fid, f.get("name", fid), round(scores[fid], 1), theme, sorted(own), effects))

    cfg = {"_comment": "Food Buffs config, generated by modpack/foodbuffs_design.py from the in-game dump. Effects listed here "
                       "are ADDED to each food's own effects. Tier 0 = no harder than everyday vanilla food.",
           "always_edible_modded": True, "saturation_cap": True, "foods": out}
    json.dump(cfg, open(HERE / "foodbuffs.json", "w", encoding="utf-8"), indent=1, ensure_ascii=False)

    names = {"minecraft:" + k: k.replace("_", " ") for k in ()}
    def eff_txt(es):
        return ", ".join(f"{e['effect'].split(':')[1].replace('_', ' ')}{' II' if e['amplifier'] else ''} {e['seconds']//60}:{e['seconds']%60:02d}" for e in es) or "-"
    lines = ["# Food Buffs: generated design", "",
             f"Vanilla yardstick: everyday food scores {v_med:.1f}, vanilla's hardest (cake, rabbit stew...) {v_top:.1f}. "
             f"Tier cut-offs above vanilla: {cut[0]:.1f} / {cut[1]:.1f} / {cut[2]:.1f}.", "",
             "| Tier | Food | Score | Theme | Mod's own effects | Added |", "|---:|---|---:|---|---|---|"]
    for t, fid, name, sc, theme, own, eff in sorted(rows, key=lambda r: (-r[0], r[1])):
        lines.append(f"| {t} | {name} (`{fid}`) | {sc} | {theme} | {', '.join(e.split(':')[1] for e in own) or '-'} | {eff_txt(eff)} |")
    (HERE / "food-buffs.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    tc = collections.Counter(r[0] for r in rows)
    print("vanilla median", round(v_med, 2), "vanilla p90", round(v_top, 2), "cuts", [round(c, 2) for c in cut])
    print("modded foods per tier:", dict(sorted(tc.items())))
    print("foods given new effects:", sum(1 for r in rows if r[6]))
    print("vanilla anchors:", {k: round(scores[k], 1) for k in ("minecraft:bread", "minecraft:cooked_beef", "minecraft:baked_potato",
          "minecraft:pumpkin_pie", "minecraft:cake", "minecraft:rabbit_stew", "minecraft:golden_carrot", "minecraft:mushroom_stew", "minecraft:cookie") if k in scores})


if __name__ == "__main__":
    main()
