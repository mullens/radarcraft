package com.lucasmods.checklist;

/**
 * Renown: a permanent level from every XP point a player ever earns (kills, bosses, mining, smelting, breeding,
 * fishing...), never lost on death. It uses Minecraft's own XP-to-level curve, capped at {@link #MAX_LEVEL}.
 * Each Renown level is one upgrade point, on top of one point per done grid square.
 */
public final class Renown {
    public static final int MAX_LEVEL = 165;

    private Renown() {}

    /** Total XP needed to reach a level, vanilla formula. */
    public static long xpForLevel(int level) {
        if (level <= 16) return (long) level * level + 6L * level;
        if (level <= 31) return (long) Math.floor(2.5 * level * level - 40.5 * level + 360);
        return (long) Math.floor(4.5 * level * level - 162.5 * level + 2220);
    }

    public static int level(long xp) {
        int level = 0;
        while (level < MAX_LEVEL && xpForLevel(level + 1) <= xp) level++;
        return level;
    }

    /** XP gathered since the current level began. */
    public static long into(long xp) {
        return xp - xpForLevel(level(xp));
    }

    /** XP from the current level to the next one (0 at the cap). */
    public static long need(long xp) {
        int level = level(xp);
        return level >= MAX_LEVEL ? 0 : xpForLevel(level + 1) - xpForLevel(level);
    }
}
