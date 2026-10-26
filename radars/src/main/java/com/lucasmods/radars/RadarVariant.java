package com.lucasmods.radars;

import net.minecraft.world.BossEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The seven Radars plus two helper variants. Numbers 2..7 are bosses living at shrines;
 * 1 is the plain cat a player creates with a name tag. Stats grow with the tier.
 */
public enum RadarVariant {
    RADAR(1, "Radar", 1.0f, 20, 0, 0, 0.0, 0.30, 0, null),
    ROWDY(2, "Rowdy Radar", 4.0f, 150, 8, 4, 0.3, 0.34, 60, BossEvent.BossBarColor.RED),
    RADIATION(3, "Radiation Radar", 4.5f, 250, 10, 8, 0.5, 0.22, 90, BossEvent.BossBarColor.GREEN),
    RICOCHET(4, "Ricochet Radar", 3.5f, 350, 12, 2, 0.4, 0.42, 120, BossEvent.BossBarColor.YELLOW),
    RAIDER(5, "Radar Raider", 5.0f, 450, 14, 10, 0.7, 0.33, 150, BossEvent.BossBarColor.PURPLE),
    RUMBLING(6, "Rumbling Radar", 5.5f, 600, 16, 12, 1.0, 0.30, 200, BossEvent.BossBarColor.BLUE),
    RADIANT(7, "Radiant Radar", 6.0f, 800, 20, 14, 1.0, 0.35, 300, BossEvent.BossBarColor.WHITE),
    KITTEN(8, "Radar Kitten", 0.8f, 20, 4, 0, 0.0, 0.38, 5, null),
    MIRROR(9, "Radiant Mirror", 6.0f, 1, 6, 0, 1.0, 0.35, 0, null);

    public final int id;
    public final String displayName;
    public final float scale;
    public final int health;
    public final int attack;
    public final int armor;
    public final double knockbackResistance;
    public final double speed;
    public final int xp;
    @Nullable public final BossEvent.BossBarColor barColor;

    RadarVariant(int id, String displayName, float scale, int health, int attack, int armor,
                 double knockbackResistance, double speed, int xp, @Nullable BossEvent.BossBarColor barColor) {
        this.id = id;
        this.displayName = displayName;
        this.scale = scale;
        this.health = health;
        this.attack = attack;
        this.armor = armor;
        this.knockbackResistance = knockbackResistance;
        this.speed = speed;
        this.xp = xp;
        this.barColor = barColor;
    }

    public boolean isBoss() { return id >= 2 && id <= 7; }
    public boolean isMinion() { return this == KITTEN || this == MIRROR; }

    public static RadarVariant byId(int id) {
        for (RadarVariant v : values()) if (v.id == id) return v;
        return RADAR;
    }

    public static final int FIRST_SHRINE = 2;
    public static final int LAST_SHRINE = 7;
}
