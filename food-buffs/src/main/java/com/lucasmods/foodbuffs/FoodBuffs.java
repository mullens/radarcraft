package com.lucasmods.foodbuffs;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Food Buffs: modded foods give potion-like buffs scaled to how hard they are to make, can be eaten while full,
 * and eating never raises saturation above the food's own saturation. Vanilla foods are not touched.
 *
 * <p>Everything comes from {@code config/foodbuffs.json} (written from the bundled default on first start). The
 * food components are rewritten once at startup, so this must be installed on both client and server: the client
 * decides whether you may start eating while full.</p>
 */
@Mod(FoodBuffs.MODID)
public final class FoodBuffs {
    public static final String MODID = "foodbuffs";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FoodBuffs(IEventBus modBus, ModContainer container) {
        FoodConfig.load();
        modBus.addListener(EventPriority.LOWEST, FoodComponents::modify);
        NeoForge.EVENT_BUS.addListener(SaturationCap::onStart);
        NeoForge.EVENT_BUS.addListener(SaturationCap::onFinish);
        NeoForge.EVENT_BUS.addListener(DumpCommand::register);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForge.EVENT_BUS.addListener(FoodTooltips::onTooltip);
        }
        LOGGER.info("Food Buffs loaded: {} foods configured.", FoodConfig.foods().size());
    }
}
