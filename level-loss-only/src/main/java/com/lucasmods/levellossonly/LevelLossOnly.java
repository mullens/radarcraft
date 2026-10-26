package com.lucasmods.levellossonly;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Level Loss Only: dying keeps your whole inventory, but your experience drops as orbs
 * and your levels reset to zero, exactly as vanilla does without keepInventory.
 *
 * All logic runs on the logical server, so clients joining a server with this mod
 * do not need it installed.
 */
@Mod(LevelLossOnly.MODID)
public final class LevelLossOnly {
    public static final String MODID = "levellossonly";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LevelLossOnly(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.register(DeathHandler.class);
        // No network payloads are registered, so NeoForge treats this mod as optional for clients joining a server.
        LOGGER.info("Level Loss Only loaded: inventory is kept on death, XP is not.");
    }
}
