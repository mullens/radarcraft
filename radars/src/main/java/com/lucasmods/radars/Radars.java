package com.lucasmods.radars;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * Radars: name any mob "Radar" and it becomes Radar the cat, who points toward six giant boss cats
 * hidden across the world. Defeat each one to tame it back down and learn where the next lives.
 */
@Mod(Radars.MODID)
public final class Radars {
    public static final String MODID = "radars";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<RadarEntity>> RADAR = ENTITIES.register("radar",
            () -> EntityType.Builder.of(RadarEntity::new, MobCategory.MONSTER)
                    .sized(0.6f, 0.7f)
                    .clientTrackingRange(10)
                    .build("radar"));

    public static final DeferredHolder<EntityType<?>, EntityType<HairballEntity>> HAIRBALL = ENTITIES.register("hairball",
            () -> EntityType.Builder.<HairballEntity>of(HairballEntity::new, MobCategory.MISC)
                    .sized(0.4f, 0.4f)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build("hairball"));

    public Radars(IEventBus modBus, ModContainer container) {
        ENTITIES.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent event) -> event.put(RADAR.get(), RadarEntity.createAttributes().build()));
        NeoForge.EVENT_BUS.register(RadarsManager.class);
        NeoForge.EVENT_BUS.register(RadarsCommands.class);
        LOGGER.info("Radars loaded: seven cats, six fights, one name tag.");
    }
}
