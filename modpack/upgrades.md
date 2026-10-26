# Checklist upgrades

The grid has 165 squares. Each completed square is one point. Each point buys one rank of an upgrade, and ranks can be
taken off again at any time, for free, from the same screen. The catalog has 186 ranks, so even a finished grid leaves a
few choices open. Everything here is pure attribute modifiers or permanent potion effects, which is what makes the free
respec trivial: unchoose a rank and the modifier is gone the same tick.

Where the ideas come from: Pufferfish's Skills and its Stronger Skill Tree pack (health, melee/ranged damage, attack
speed, sprint and jump, mining speed, luck, fortune), LevelZ (health, strength, agility, defense, stamina, luck), and
Project MMO's perks (reach, speed, fall damage, night vision, haste). Anything that gates blocks or crafting behind
levels was deliberately left out: in this pack the achievements are the gate.

| Category  | Upgrade          | Ranks | Per rank                                   |
|-----------|------------------|------:|--------------------------------------------|
| Vitality  | Hearts           |    12 | +1 heart                                   |
| Vitality  | Thick Skin       |    10 | +1 armor                                   |
| Vitality  | Sturdy           |     8 | +0.5 armor toughness                       |
| Vitality  | Hardy            |     8 | +10% knockback resistance                  |
| Vitality  | Fireproof        |     5 | burn 20% shorter; rank 5: Fire Resistance  |
| Vitality  | Deep Lungs       |     6 | more air; rank 6: Water Breathing          |
| Vitality  | Second Wind      |     1 | survive a killing blow, 5 min cooldown     |
| Mobility  | Fleet Foot       |    10 | +5% speed                                  |
| Mobility  | Spring Legs      |     8 | higher jump                                |
| Mobility  | Long Stride      |     2 | +0.5 step height                           |
| Mobility  | Featherfall      |     6 | +2 blocks safe fall                        |
| Mobility  | Cat Landing      |     4 | -15% fall damage                           |
| Mobility  | Swimmer          |     5 | faster swimming                            |
| Mobility  | Sneaky           |     3 | faster sneaking                            |
| Mobility  | Surefooted       |     4 | less slowdown on soul sand, honey, cobwebs |
| Mobility  | Blast Brace      |     6 | less explosion knockback                   |
| Mobility  | Moon Walker      |     5 | -6% gravity                                |
| Combat    | Brawn            |    10 | +1 melee damage                            |
| Combat    | Quick Hands      |     8 | +5% attack speed                           |
| Combat    | Heavy Hitter     |     5 | more knockback dealt                       |
| Combat    | Sweep            |     6 | stronger sweeping attacks                  |
| Combat    | Long Arms        |     5 | +0.5 entity reach                          |
| Gathering | Pickaxe Arm      |    10 | +10% break speed                           |
| Gathering | Builder's Reach  |     8 | +0.5 block reach                           |
| Gathering | Underwater Miner |     4 | faster mining submerged                    |
| Gathering | Lucky            |     8 | +1 Luck                                    |
| Gathering | Haste            |     2 | Haste I, then II                           |
| Gathering | Magnet           |     4 | items drift to you from further away       |
| Senses    | Night Eyes       |     1 | Night Vision                               |
| Senses    | Giant            |     6 | +10% size (excludes Smol)                  |
| Senses    | Smol             |     6 | -10% size (excludes Giant)                 |

Total: 186 ranks.

Tuning notes:
- Per-rank amounts live in `checklist-grid.json`'s sibling `checklist-upgrades.json`; no code change to rebalance.
- Hearts at 12 ranks means 22 hearts for a player who finished most of the grid, instead of 19 hearts a few hours in.
- "Giant" and "Smol" use the vanilla scale attribute, which also scales hitbox, reach and step height, so they are real
  trade-offs rather than cosmetics. They exclude each other.
- Second Wind and Magnet are the only two that need code; everything else is data.
- Ideas not taken, in case you want them later: XP boost (Level Loss Only already changes how XP works), extra hotbar
  or inventory rows (fights every inventory mod in the pack), fortune/looting as attributes (not attributes in 1.21.1,
  would need an enchantment-level hook), crop growth aura, villager discounts, flight (the pack has airships).
