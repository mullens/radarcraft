"""Build modpack/checklist-grid.json for the Checklist mod from the curated list below.

Each entry: (advancement id, tier 1-5). Tier 1 = first hours, 5 = endgame. Squares are laid out from the
centre of the grid outward by tier, grouped by mod inside each tier so related squares sit next to each other.
Edit CURATED and re-run:  python modpack/make_grid.py
"""
import json, math, sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
WIDTH, HEIGHT = 15, 11
HEARTS_EVERY = 0  # rewards are chosen on the Upgrades tab instead (checklist-upgrades.json); 1 point per square

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
    ("create_structures_arise:structures/pillagersteampunkairship", 2), ("create_structures_arise:structures/createlosttrainstation", 2),
    ("create_structures_arise:structures/createminiskyvillage", 3),
    ("aerowarptics:first_warp", 2), ("aerowarptics:gate_traveller", 3), ("aerowarptics:ship_summoned", 3),
    ("aerowarptics:long_haul", 4), ("aerowarptics:fissure_closed", 4), ("aerowarptics:blind_jump", 4),
    ("escalated:escalator_100", 3), ("escalated:escalator_100_nether", 4),
    ("numismatics:money_laundering", 2),
    ("trading_floor:drop_shipper", 3), ("trading_floor:happy_jeff", 5),
    ("creatingspace:reach_earth_orbit", 5), ("creatingspace:where_is_the_cheese", 5),
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


def main():
    known = {a["id"]: a for a in json.load(open(HERE / "advancements-all.json", encoding="utf-8"))}
    ids = [c[0] for c in CURATED]
    dupes = [k for k, v in Counter(ids).items() if v > 1]
    unknown = [i for i in ids if i not in known]
    if dupes or unknown:
        sys.exit(f"duplicates: {dupes}\nunknown ids: {unknown}")
    assert len(CURATED) <= WIDTH * HEIGHT, f"{len(CURATED)} entries do not fit {WIDTH}x{HEIGHT}"

    cx, cy = (WIDTH - 1) / 2, (HEIGHT - 1) / 2
    cells = sorted(((x, y) for y in range(HEIGHT) for x in range(WIDTH)),
                   key=lambda p: (math.hypot((p[0] - cx) / WIDTH * HEIGHT, p[1] - cy), p[1], p[0]))
    # tier first, then mod so related squares cluster inside a ring, then stable order
    order = sorted(range(len(CURATED)), key=lambda i: (CURATED[i][1], CURATED[i][0].split(":")[0], i))
    grid = [[None] * WIDTH for _ in range(HEIGHT)]
    for (x, y), i in zip(cells, order):
        grid[y][x] = CURATED[i][0]
    start = [[int(cx), int(cy)], [int(cx) - 1, int(cy)], [int(cx) + 1, int(cy)], [int(cx), int(cy) - 1], [int(cx), int(cy) + 1]]
    out = {
        "_comment": "Checklist grid for the Skybound fork. Rows top to bottom; null = empty square. Tier grows outward from the centre.",
        "grid": grid, "start": start, "hearts_every_levels": HEARTS_EVERY, "commands_on_level": {},
        "upgrades_file": "checklist-upgrades.json",
    }
    json.dump(out, open(HERE / "checklist-grid.json", "w", encoding="utf-8"), indent=1)

    by_mod = Counter(known[i]["mod"] for i in ids)
    print(f"{len(CURATED)} squares in a {WIDTH}x{HEIGHT} grid; {WIDTH*HEIGHT-len(CURATED)} empty; {len(CURATED)} upgrade points at most")
    print("tiers:", dict(sorted(Counter(t for _, t in CURATED).items())))
    for m, n in by_mod.most_common():
        print(f"{n:3}  {m}")
    # preview
    with open(HERE / "checklist-grid-preview.txt", "w", encoding="utf-8") as f:
        for row in grid:
            f.write(" | ".join((known[c]["title"][:14] if c else "").ljust(14) for c in row) + "\n")


if __name__ == "__main__":
    main()
