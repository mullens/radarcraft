"""Build modpack/checklist-grid.json for the Radarcraft RPG mod (mod id checklist), plus the generated squares.

Three areas side by side, each made of blocks of related squares (easiest first inside a block):
  biomes        one square per biome a normal world can generate, grouped by dimension (the Overworld by climate)
  achievements  the curated advancements below, grouped by mod
  photos        one square per mob type: an Exposure photo with that mob in the frame, grouped by mod
Biome and photo squares are advancements this script writes into the mod (data/checklist/advancement/...), each
gated on its mods being loaded. The biome and mob lists come from modpack/world-dump.json, written by the dev-driver
"dumpworld" command on a full-pack server with a normal world (scripts/driver/server-worlddump.txt).

Each curated entry: (advancement id, tier 1-5). Tier 1 = first hours, 5 = endgame.
Edit CURATED and re-run:  python modpack/make_grid.py
"""
import json, math, shutil, sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
DATA = ROOT / "checklist/src/main/resources/data/checklist/advancement"
LANG = ROOT / "checklist/src/main/resources/assets/checklist/lang/en_us.json"
HEARTS_EVERY = 0  # rewards are chosen on the Upgrades tab instead (checklist-upgrades.json); 1 point per square
AREA_WIDTH = {"biomes": 22, "achievements": 17, "photos": 26}
AREA_GAP = 2

CURATED = [
    # --- Minecraft (26) ---
    ("minecraft:adventure/sleep_in_bed", 1), ("minecraft:story/enchant_item", 1), ("minecraft:story/enter_the_nether", 2),
    ("minecraft:adventure/fall_from_world_height", 2), ("minecraft:story/cure_zombie_villager", 3), ("minecraft:nether/return_to_sender", 3),
    ("minecraft:adventure/sniper_duel", 3), ("minecraft:adventure/totem_of_undying", 3), ("minecraft:story/enter_the_end", 4),
    ("minecraft:nether/explore_nether", 4), ("minecraft:nether/fast_travel", 4), ("minecraft:nether/uneasy_alliance", 4),
    ("minecraft:nether/summon_wither", 4), ("minecraft:adventure/hero_of_the_village", 4), ("minecraft:adventure/overoverkill", 4),
    ("minecraft:adventure/revaulting", 4), ("minecraft:husbandry/bred_all_animals", 4), ("minecraft:husbandry/balanced_diet", 4),
    ("minecraft:end/kill_dragon", 5), ("minecraft:end/elytra", 5), ("minecraft:end/respawn_dragon", 5), ("minecraft:nether/create_full_beacon", 5),
    ("minecraft:nether/all_potions", 5), ("minecraft:adventure/adventuring_time", 5), ("minecraft:adventure/kill_all_mobs", 5),
    ("minecraft:adventure/arbalistic", 5),
    # --- Create (18) ---
    ("create:windmill_maxed", 2), ("create:steam_engine", 2), ("create:mechanical_arm", 2), ("create:potato_cannon", 2),
    ("create:steam_engine_maxed", 3), ("create:crusher_maxed_0000", 3), ("create:arm_many_targets", 3), ("create:train", 3),
    ("create:self_deploying", 3), ("create:hose_pulley_lava", 3), ("create:ejector_maxed", 3), ("create:pulley_maxed", 3),
    ("create:pipe_organ", 3), ("create:factory_gauge", 3), ("create:long_train", 4), ("create:long_travel", 4),
    ("create:track_crafting_factory", 4), ("create:diving_suit_lava", 4),
    # --- Create addons ---
    ("create_enchantment_industry:blazing_enchantment", 2), ("create_enchantment_industry:a_shower_experience", 2),
    ("create_enchantment_industry:transcendent_overclock", 4), ("create_enchantment_industry:experienced_recycler", 5),
    # (Create: Structures Arise and Creating Space ship advancements in the pre-1.21 "advancements/" folder, which the
    #  game ignores, so they are not usable; replaced by live Create / Bumblezone / Bosses' Rise squares)
    ("create:cardboard_armor", 2), ("create:backtank", 2), ("create:cart_pickup", 3),
    ("aerowarptics:first_warp", 2), ("aerowarptics:gate_traveller", 3), ("aerowarptics:ship_summoned", 3),
    ("aerowarptics:long_haul", 4), ("aerowarptics:fissure_closed", 4), ("aerowarptics:blind_jump", 4),
    ("escalated:escalator_100", 3), ("escalated:escalator_100_nether", 4),
    ("numismatics:money_laundering", 2),
    ("trading_floor:drop_shipper", 3), ("trading_floor:happy_jeff", 5),
    ("the_bumblezone:essence/essence_raging", 5), ("block_factorys_bosses:no_hit_yeti", 5),
    # --- The Aether + Deep Aether ---
    ("aether:enter_aether", 2), ("aether:incubate_moa", 2), ("aether:aether_sleep", 2), ("aether:black_moa", 3),
    ("aether:bronze_dungeon", 3), ("aether:silver_dungeon", 4), ("aether:zephyr_hammer", 4), ("aether:gold_dungeon", 5),
    ("deep_aether:brass_dungeon", 4), ("deep_aether:explore_aether", 4), ("deep_aether:aether_champion", 5),
    # --- Eternal Starlight ---
    ("eternal_starlight:enter_starlight", 3), ("eternal_starlight:challenge_gatekeeper", 3), ("eternal_starlight:witness_meteor_shower", 3),
    ("eternal_starlight:tame_crystallized_moth", 3), ("eternal_starlight:kill_golem", 4), ("eternal_starlight:kill_lunar_monstrosity", 4),
    ("eternal_starlight:kill_permafrost", 5), ("eternal_starlight:all_starlight_biomes", 5),
    # --- The Undergarden ---
    ("undergarden:undergarden/enter_undergarden", 2), ("undergarden:undergarden/stoneborn_trade", 3), ("undergarden:undergarden/obtain_denizen_mask", 3),
    ("undergarden:undergarden/enter_depths", 4), ("undergarden:undergarden/kill_forgotten_guardian", 4),
    ("undergarden:undergarden/kill_all_rotspawn", 4), ("undergarden:undergarden/all_undergarden_biomes", 4),
    # --- Deeper and Darker ---
    ("deeperdarker:main/enter_otherside", 3), ("deeperdarker:main/find_ancient_city", 3), ("deeperdarker:main/find_ancient_temple", 4),
    ("deeperdarker:main/kill_all_sculk_mobs", 4), ("deeperdarker:main/explore_otherside", 4), ("deeperdarker:main/kill_warden", 5),
    # --- The Bumblezone ---
    ("the_bumblezone:teleportation/teleport_to_bumblezone_pearl", 2), ("the_bumblezone:the_queens_desire/the_beginning", 3),
    ("the_bumblezone:beehemoth/tamed_beehemoth", 3), ("the_bumblezone:beehemoth/queen_beehemoth", 4),
    ("the_bumblezone:structures/enter_all_structures", 4), ("the_bumblezone:biomes/discover_all_biomes", 4),
    ("the_bumblezone:the_queens_desire/journeys_end", 5), ("the_bumblezone:the_queens_desire/the_great_dragon_slayer", 5),
    # --- Ars Nouveau ---
    ("ars_nouveau:ritual_gravity", 2), ("ars_nouveau:familiar", 3), ("ars_nouveau:create_portal", 3),
    ("ars_nouveau:catch_lightning", 3), ("ars_nouveau:prismatic", 3), ("ars_nouveau:wilden_explosion", 3),
    # --- Bosses' Rise ---
    ("block_factorys_bosses:kill_yeti", 3), ("block_factorys_bosses:kill_sandworm", 3), ("block_factorys_bosses:kill_kraken", 4),
    ("block_factorys_bosses:kill_underworld_knight", 4), ("block_factorys_bosses:kill_dragon", 5),
    ("block_factorys_bosses:kill_all_bosses", 5), ("block_factorys_bosses:kill_boss_under_minute", 5),
    # --- Mowzie's Mobs ---
    ("mowziesmobs:kill_naga", 3), ("mowziesmobs:kill_ferrous_wroughtnaut", 3), ("mowziesmobs:steal_ice_crystal", 3),
    ("mowziesmobs:suns_blessing", 3), ("mowziesmobs:kill_frostmaw", 4), ("mowziesmobs:kill_umvuthi", 4), ("mowziesmobs:sculptor_challenge", 4),
    # --- Mutant Monsters ---
    ("mutantmonsters:you_da_bomby", 3), ("mutantmonsters:hulk_smash", 4), ("mutantmonsters:no_bones_about_it", 4),
    # --- Simply Swords (unique weapon finds) ---
    ("simplyswords:the_devourer", 4), ("simplyswords:dreadwhisper", 4),
    # --- Farmer's Delight ---
    ("farmersdelight:main/eat_nourishing_food", 1), ("farmersdelight:main/plant_all_crops", 4), ("farmersdelight:main/master_chef", 5),
    # --- Lootr ---
    ("lootr:social", 3), ("lootr:100loot", 4),
    # --- Exposure ---
    ("exposure:adventure/exposure", 1), ("exposure:adventure/family_portrait", 2), ("exposure:adventure/wildlife_archivist", 5),
    # --- Bountiful ---
    ("bountiful:bountiful/bounty_complete", 1), ("bountiful:bountiful/rush_order", 2), ("bountiful:bountiful/ninety_five_theses", 3),
    # --- Darker Depths ---
    ("darkerdepths:visited/structure/visited_catacombs", 2), ("darkerdepths:visited/molten_cavern", 2), ("darkerdepths:activities/elytra_boosted_by_geyser", 3),
    # --- YUNG's ---
    ("betterdeserttemples:temple_entry", 2), ("betterdungeons:skeleton_dungeon", 2), ("betterdeserttemples:temple_clear", 3), ("betterdungeons:all_dungeons", 4),
    # --- ShellBound airships ---
    ("shellbound_for_airship:airship/ship1", 2), ("shellbound_for_airship:airship/ship2", 2), ("shellbound_for_airship:airship/enter_airship", 4),
    # --- one each ---
    ("supplementaries:story/unenchanter", 3), ("artifacts:chest_slayer", 2), ("cyberspace:cyberspace_visited_achievement", 3),
    ("voidtotem:adventure/totem_of_void_undying", 2), ("biomesoplenty:biomesoplenty/all_biomes", 5), ("vinery:main/wine_somelier", 3),
    ("beachparty:main/get_all_cocktails", 3), ("ecologics:husbandry/breed_penguin", 2), ("naturalist:husbandry/feed_bear_honeycomb", 1),
    ("delighto_flight:main/use_weather_horn", 2), ("advancednetherite:nether/netherite_diamond_armor", 5),
    ("betterarcheology:all_fossils_found", 4), ("takesapillage:adventure/bastille", 2), ("minecraft:husbandry/uh_oh", 2),
    ("camping:main/get_all_backpacks", 3),
]


# ------------------------------------------------------------------ achievements: blocks by mod

ACH_GROUP = {  # namespace -> block; any other namespace with fewer than 3 squares goes to "misc"
    "create_enchantment_industry": "create", "aerowarptics": "create", "escalated": "create", "numismatics": "create",
    "trading_floor": "create", "deep_aether": "aether", "betterdeserttemples": "yung", "betterdungeons": "yung",
    "mowziesmobs": "bosses", "mutantmonsters": "bosses", "block_factorys_bosses": "bosses", "simplyswords": "bosses",
}


# ------------------------------------------------------------------ biomes

DIM_NAMES = {
    "minecraft:overworld": "the Overworld", "minecraft:the_nether": "the Nether", "minecraft:the_end": "the End",
    "aether:the_aether": "the Aether", "undergarden:undergarden": "the Undergarden", "eternal_starlight:starlight": "Eternal Starlight",
    "the_bumblezone:the_bumblezone": "the Bumblezone", "deeperdarker:otherside": "the Otherside", "creatingspace:mars": "Mars",
    "creatingspace:the_moon": "the Moon", "creatingspace:venus": "Venus", "creatingspace:earth_orbit": "space",
    "cyberspace:cyberspace_dimension": "Cyberspace",
}
DIM_TIER = {"minecraft:overworld": 1, "minecraft:the_nether": 2, "aether:the_aether": 2, "undergarden:undergarden": 2,
            "minecraft:the_end": 3, "eternal_starlight:starlight": 3, "the_bumblezone:the_bumblezone": 3,
            "cyberspace:cyberspace_dimension": 3, "deeperdarker:otherside": 4, "creatingspace:mars": 4,
            "creatingspace:the_moon": 4, "creatingspace:venus": 5, "creatingspace:earth_orbit": 4}
SPACE = {"creatingspace:mars", "creatingspace:the_moon", "creatingspace:venus", "creatingspace:earth_orbit"}
# first matching keyword picks the icon (vanilla items only, so an icon never fails to load)
ICON_WORDS = [
    (("ocean", "sea", "reef", "shallow"), "minecraft:tropical_fish"), (("river",), "minecraft:water_bucket"),
    (("beach", "shore", "dune"), "minecraft:sand"),
    (("frozen", "ice", "icy", "snow", "tundra", "frost", "permafrost", "wintry"), "minecraft:snowball"),
    (("desert",), "minecraft:cactus"), (("badlands", "mesa", "wasteland"), "minecraft:red_sand"),
    (("jungle", "rainforest", "tropics", "bamboo"), "minecraft:jungle_sapling"),
    (("swamp", "bog", "marsh", "bayou", "wetland", "muskeg", "mangrove", "floodplain"), "minecraft:lily_pad"),
    (("mushroom", "fungal", "shroom", "mycelium"), "minecraft:red_mushroom"),
    (("cherry", "blossom", "jacaranda", "lavender", "auroral"), "minecraft:pink_petals"),
    (("crimson",), "minecraft:crimson_fungus"), (("warped",), "minecraft:warped_fungus"), (("soul",), "minecraft:soul_sand"),
    (("basalt",), "minecraft:basalt"), (("volcan", "hot_springs", "molten", "inferno"), "minecraft:magma_block"),
    (("ominous", "dead", "dark"), "minecraft:dark_oak_sapling"), (("birch", "aspen"), "minecraft:birch_sapling"),
    (("taiga", "coniferous", "fir", "pine", "spruce", "redwood", "grove"), "minecraft:spruce_sapling"),
    (("savanna", "scrub", "dry", "prairie", "steppe", "shrub"), "minecraft:acacia_sapling"),
    (("cave", "cavern", "grotto", "dripstone", "catacomb", "deep", "abyss", "depths", "chasm"), "minecraft:pointed_dripstone"),
    (("peak", "crag", "cliff", "highland", "stony", "slope"), "minecraft:stone"),
    (("pumpkin",), "minecraft:pumpkin"),
    (("flower", "field", "meadow", "garden", "pasture", "plains", "greens", "glade", "moor"), "minecraft:poppy"),
    (("forest", "wood", "orchard", "maple", "seasonal", "undergrowth"), "minecraft:oak_sapling"),
]
DIM_ICON = {"minecraft:the_nether": "minecraft:netherrack", "minecraft:the_end": "minecraft:end_stone",
            "aether:the_aether": "minecraft:feather", "undergarden:undergarden": "minecraft:mossy_cobblestone",
            "eternal_starlight:starlight": "minecraft:amethyst_shard", "the_bumblezone:the_bumblezone": "minecraft:honeycomb",
            "deeperdarker:otherside": "minecraft:sculk", "creatingspace:mars": "minecraft:red_sand",
            "creatingspace:the_moon": "minecraft:white_concrete_powder", "creatingspace:venus": "minecraft:magma_block",
            "creatingspace:earth_orbit": "minecraft:end_rod", "cyberspace:cyberspace_dimension": "minecraft:observer"}


def pretty(path):
    return path.rsplit("/", 1)[-1].replace("_", " ").title()


def biome_icon(bid, dim):
    name = bid.split(":")[1]
    if dim in ("minecraft:overworld", "minecraft:the_nether"):
        for words, icon in ICON_WORDS:
            if any(w in name for w in words):
                return icon
    return DIM_ICON.get(dim, "minecraft:grass_block")


def overworld_group(b):
    name, tags = b["id"].split(":")[1], set(b["tags"])
    water = {"minecraft:is_ocean", "minecraft:is_deep_ocean", "minecraft:is_river", "minecraft:is_beach", "c:is_ocean", "c:is_river", "c:is_beach"}
    if tags & water or any(w in name for w in ("ocean", "river", "beach", "shore")):
        return "Overworld: water", 1
    if tags & {"c:is_cave", "c:is_underground"} or any(w in name for w in ("cave", "grotto", "deep_dark", "catacomb", "cavern")):
        return "Overworld: underground", 2
    if b["temperature"] < 0.3:
        return "Overworld: cold", 2
    if b["temperature"] >= 1.0:
        return "Overworld: hot", 2
    return "Overworld: temperate", 1


def biome_squares(dump):
    groups = defaultdict(list)
    for b in dump["biomes"]:
        if not b["dims"]:
            continue  # no dimension of a normal world generates it
        dim = sorted(b["dims"])[0]
        if dim in SPACE:
            name, tier = "Space", DIM_TIER[dim]
        elif dim == "minecraft:overworld":
            name, tier = overworld_group(b)
        else:
            name, tier = DIM_NAMES.get(dim, dim), DIM_TIER.get(dim, 3)
        ns, path = b["id"].split(":")
        groups[name].append({
            "id": f"checklist:biome/{ns}/{path}", "tier": tier, "kind": "biome", "target": b["id"], "mods": [ns],
            "title": {"translate": f"biome.{ns}.{path}", "fallback": pretty(path)},
            "description": f"Stand in this biome ({DIM_NAMES.get(dim, dim)})",
            "icon": biome_icon(b["id"], dim), "frame": "task",
        })
    order = ["Overworld: temperate", "Overworld: water", "Overworld: cold", "Overworld: hot", "Overworld: underground",
             "the Nether", "the End", "the Aether", "the Undergarden", "Eternal Starlight", "the Bumblezone", "the Otherside", "Space", "Cyberspace"]
    return [(g, sorted(groups[g], key=lambda s: s["target"])) for g in order if g in groups] + \
           [(g, v) for g, v in groups.items() if g not in order]


# ------------------------------------------------------------------ photos

NOT_MOBS = {"undergarden:minion", "aether:fire_minion", "mutantmonsters:creeper_minion", "rpg_potions:decoy",
            "eternal_starlight:tangled_skull", "block_factorys_bosses:pile_of_bones", "minecraft:giant"}
BOSSES = {"minecraft:ender_dragon", "minecraft:wither", "minecraft:warden", "minecraft:elder_guardian",
          "undergarden:forgotten_guardian", "aether:slider", "aether:valkyrie_queen", "aether:sun_spirit",
          "eternal_starlight:the_gatekeeper", "eternal_starlight:starlight_golem", "eternal_starlight:lunar_monstrosity",
          "mowziesmobs:ferrous_wroughtnaut", "mowziesmobs:frostmaw", "mowziesmobs:umvuthi", "mowziesmobs:sculptor",
          "the_bumblezone:bee_queen"}
BOSS_MODS = {"block_factorys_bosses"}
MOB_GROUP = {"deep_aether": "aether", "mutantmonsters": "mowziesmobs"}
PASSIVE = ("creature", "water_creature", "ambient", "water_ambient", "axolotls", "underground_water_creature")


def photo_squares(dump):
    groups = defaultdict(list)
    for m in dump["mobs"]:
        if not m["mob"] or m["id"] in NOT_MOBS or not ("egg" in m or m["spawns_naturally"]):
            continue
        ns, path = m["id"].split(":")
        boss = m["id"] in BOSSES or (ns in BOSS_MODS and path not in ("pirate_rook", "crossbow_pirate", "soul_skeleton"))
        tier = 5 if boss else 1 if m["spawns_naturally"] and m["category"] in PASSIVE else 2 if m["spawns_naturally"] else 3
        groups[MOB_GROUP.get(ns, ns)].append({
            "id": f"checklist:photo/{ns}/{path}", "tier": tier, "kind": "photo", "target": m["id"], "mods": ["exposure", ns],
            "title": {"translate": "checklist.square.photo", "with": [{"translate": m["name_key"], "fallback": pretty(path)}]},
            "description": "Take a photo with this mob in the frame (Exposure camera)",
            "icon": m.get("egg", "exposure:camera"), "frame": "challenge" if boss else "task",
        })
    big = [(g, v) for g, v in groups.items() if len(v) >= 4]
    small = [s for g, v in groups.items() if len(v) < 4 for s in v]
    big.sort(key=lambda gv: (gv[0] != "minecraft", -len(gv[1])))
    if small:
        big.append(("other mods", small))
    return [(g, sorted(v, key=lambda s: (s["tier"], s["target"]))) for g, v in big]


def write_advancements(areas):
    if DATA.exists():
        shutil.rmtree(DATA)
    roots = {
        "biome": ("Radarcraft: Biomes", "Every biome is a square on the Radarcraft grid", "minecraft:filled_map",
                  "minecraft:textures/gui/advancements/backgrounds/adventure.png", []),
        "photo": ("Radarcraft: Photos", "Every kind of mob is a square on the Radarcraft grid: photograph it", "exposure:camera",
                  "minecraft:textures/gui/advancements/backgrounds/husbandry.png", ["exposure"]),
    }
    for kind, (title, desc, icon, bg, mods) in roots.items():
        write(DATA / kind / "root.json", {
            **conditions(mods),
            "display": {"icon": {"id": icon}, "title": title, "description": desc, "background": bg,
                        "show_toast": False, "announce_to_chat": False},
            "criteria": {"tick": {"trigger": "minecraft:tick"}},
        })
    n = 0
    for _, groups in areas:
        for _, squares in groups:
            for s in squares:
                if s["kind"] == "biome":
                    criteria = {"visit": {"trigger": "minecraft:location", "conditions": {"player": [{
                        "condition": "minecraft:entity_properties", "entity": "this",
                        "predicate": {"location": {"biomes": s["target"]}}}]}}}
                elif s["kind"] == "photo":
                    criteria = {"photo": {"trigger": "exposure:frame_exposed", "conditions": {
                        "frame": {"entities_in_frame": [{"type": s["target"]}]}}}}
                else:
                    continue
                write(DATA / (s["id"].split(":")[1] + ".json"), {
                    **conditions(s["mods"]),
                    "parent": f"checklist:{s['kind']}/root",
                    "display": {"icon": {"id": s["icon"]}, "title": s["title"], "description": s["description"],
                                "frame": s["frame"], "show_toast": True, "announce_to_chat": False, "hidden": True},
                    "criteria": criteria,
                })
                n += 1
    return n


def conditions(mods):
    mods = sorted({m for m in mods if m != "minecraft"})
    if not mods:
        return {}
    return {"neoforge:conditions": [{"type": "neoforge:mod_loaded", "modid": m} for m in mods]}


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")


# ------------------------------------------------------------------ layout

def block_shape(n, max_w):
    w = max(3, min(max_w, round(math.sqrt(n * 1.6))))
    return w, math.ceil(n / w)


def pack_area(groups, width):
    """Shelf-packs one block per group, in order: a shelf takes its first block's height, later blocks on it are as wide
    as that height needs. Returns {(x, y): id}, each block's first square, the area height."""
    cells, starts = {}, []
    x = y = shelf_h = 0
    for _, squares in groups:
        n = len(squares)
        w, h = block_shape(n, width)
        if shelf_h:
            w2 = math.ceil(n / shelf_h)
            if x + w2 <= width:
                w, h = w2, shelf_h
            else:
                x, y, shelf_h = 0, y + shelf_h, 0
        if not shelf_h:
            shelf_h = h
        for i, s in enumerate(squares):
            cells[(x + i // h, y + i % h)] = s["id"]  # column by column, so a short last column sits at the block's right edge
        starts.append((x, y))  # the block's easiest square starts revealed, so every block can be found
        x += w
    return cells, starts, y + shelf_h


def main():
    known = {a["id"]: a for a in json.load(open(HERE / "advancements-all.json", encoding="utf-8"))}
    ids = [c[0] for c in CURATED]
    dupes = [k for k, v in Counter(ids).items() if v > 1]
    unknown = [i for i in ids if i not in known]
    dead = [i for i in ids if i in known and known[i].get("legacy_folder")]
    if dupes or unknown or dead:
        sys.exit(f"duplicates: {dupes}\nunknown ids: {unknown}\nin a legacy advancements/ folder (ignored by 1.21): {dead}")

    ach = defaultdict(list)
    for aid, tier in CURATED:
        ns = aid.split(":")[0]
        ach[ACH_GROUP.get(ns, ns)].append({"id": aid, "tier": tier, "kind": "achievement"})
    for g in [g for g, v in ach.items() if len(v) < 3]:
        ach["misc"].extend(ach.pop(g))
    ach_groups = sorted(ach.items(), key=lambda gv: (gv[0] == "misc", gv[0] != "minecraft", min(s["tier"] for s in gv[1]), -len(gv[1])))
    ach_groups = [(g, sorted(v, key=lambda s: s["tier"])) for g, v in ach_groups]

    dump = json.load(open(HERE / "world-dump.json", encoding="utf-8"))
    areas = [("biomes", biome_squares(dump)), ("achievements", ach_groups), ("photos", photo_squares(dump))]
    generated = write_advancements(areas)

    packed = [(name, *pack_area(groups, AREA_WIDTH[name])) for name, groups in areas]
    height = max(h for *_, h in packed)
    width = sum(AREA_WIDTH[n] for n, *_ in packed) + AREA_GAP * (len(packed) - 1)
    grid = [[None] * width for _ in range(height)]
    start, focus, ox = [], None, 0
    for name, cells, starts, h in packed:
        oy = (height - h) // 2
        for (x, y), sid in cells.items():
            grid[oy + y][ox + x] = sid
        start += [[ox + x, oy + y] for x, y in starts]
        if name == "achievements":
            focus = [ox + AREA_WIDTH[name] // 2, oy + h // 2]
        ox += AREA_WIDTH[name] + AREA_GAP
    out = {
        "_comment": "Radarcraft grid, generated by modpack/make_grid.py: biomes | achievements | photos, in blocks of related squares. null = empty.",
        "grid": grid, "start": start, "focus": focus, "hearts_every_levels": HEARTS_EVERY, "commands_on_level": {},
        "upgrades_file": "checklist-upgrades.json",
    }
    json.dump(out, open(HERE / "checklist-grid.json", "w", encoding="utf-8"), indent=1)
    shutil.copy(HERE / "checklist-grid.json", ROOT / "checklist/src/main/resources/assets/checklist/defaults/checklist-grid.json")

    lang = json.load(open(LANG, encoding="utf-8"))
    lang["checklist.square.photo"] = "Photograph: %s"
    json.dump(lang, open(LANG, "w", encoding="utf-8"), indent=2, ensure_ascii=False)

    total = sum(len(v) for _, groups in areas for _, v in groups)
    print(f"{total} squares in a {width}x{height} grid ({generated} generated advancements), {len(start)} blocks")
    for name, groups in areas:
        print(f"  {name}: {sum(len(v) for _, v in groups)} in {len(groups)} blocks: " + ", ".join(f"{g} {len(v)}" for g, v in groups))
    with open(HERE / "checklist-grid-preview.txt", "w", encoding="utf-8") as f:
        for row in grid:
            f.write("".join("." if c is None else "B" if ":biome/" in c else "P" if ":photo/" in c else "A" for c in row) + "\n")


if __name__ == "__main__":
    main()
