package com.lucasmods.cherishedmobs;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Cherished Mobs.
 * <ul>
 *   <li>Friendly mobs (anything that is not hostile, plus every tamable animal) take far less damage,
 *       as if wearing a full set of Protection V, and slowly regenerate.</li>
 *   <li>Any mob carrying a name tag becomes invincible: no damage, no death, and if it ever falls
 *       out of the world it is put back on solid ground. Only /kill can remove it (configurable).</li>
 * </ul>
 * All logic is server-side; clients do not need the mod.
 */
@Mod(CherishedMobs.MODID)
public final class CherishedMobs {
    public static final String MODID = "cherishedmobs";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue FRIENDLY_DAMAGE_REDUCTION_PERCENT;
    public static final ModConfigSpec.BooleanValue FRIENDLY_REGENERATION;
    public static final ModConfigSpec.BooleanValue KILL_COMMAND_REMOVES_NAMED_MOBS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("friendly");
        FRIENDLY_DAMAGE_REDUCTION_PERCENT = b
                .comment("Percentage of incoming damage friendly mobs ignore. 80 equals a full set of Protection V (the vanilla cap).")
                .defineInRange("damageReductionPercent", 80, 0, 100);
        FRIENDLY_REGENERATION = b
                .comment("Give friendly mobs permanent Regeneration I (1 heart every 2.5 seconds).")
                .define("regeneration", true);
        b.pop();
        b.push("named");
        KILL_COMMAND_REMOVES_NAMED_MOBS = b
                .comment("Let /kill remove a name-tagged mob so admins can clean up. Everything else is blocked regardless.")
                .define("killCommandRemovesNamedMobs", true);
        b.pop();
        SPEC = b.build();
    }

    public CherishedMobs(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
        NeoForge.EVENT_BUS.register(MobHandler.class);
        LOGGER.info("Cherished Mobs loaded: friendly mobs are tough, named mobs are forever.");
    }
}
