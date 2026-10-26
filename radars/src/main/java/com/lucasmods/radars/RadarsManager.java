package com.lucasmods.radars;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server-side orchestration: builds shrines when players approach, spawns and respawns bosses,
 * and turns any mob named "Radar" into Radar the cat.
 */
public final class RadarsManager {
    private static final double BUILD_DISTANCE = 128;
    private static final double SPAWN_DISTANCE = 64;

    private RadarsManager() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) return;
        ServerLevel level = event.getServer().overworld();
        if (level.players().isEmpty()) return;
        RadarsData data = RadarsData.get(level);
        for (RadarsData.Shrine shrine : data.all()) {
            if (shrine.pos == null) {
                if (nearestPlayerDistance(level, shrine.targetX + 0.5, shrine.targetZ + 0.5) <= BUILD_DISTANCE) {
                    shrine.pos = ShrineBuilder.resolveAndBuild(level, shrine.targetX, shrine.targetZ);
                    data.setDirty();
                    Radars.LOGGER.info("Built shrine for {}", shrine.describe());
                }
                continue;
            }
            if (!level.areEntitiesLoaded(ChunkPos.asLong(shrine.pos))) continue;
            if (nearestPlayerDistance(level, shrine.pos.getX() + 0.5, shrine.pos.getZ() + 0.5) > SPAWN_DISTANCE) continue;

            if (shrine.defeated) {
                Entity cat = shrine.catId == null ? null : level.getEntity(shrine.catId);
                if (cat == null || !cat.isAlive()) {
                    Radars.LOGGER.info("The tamed cat of Radar #{} is gone; the boss will return", shrine.index);
                    shrine.defeated = false;
                    shrine.catId = null;
                    data.setDirty();
                }
            } else {
                Entity boss = shrine.bossId == null ? null : level.getEntity(shrine.bossId);
                if (boss == null || !boss.isAlive()) {
                    spawnBoss(level, data, shrine);
                }
            }
        }
    }

    private static void spawnBoss(ServerLevel level, RadarsData data, RadarsData.Shrine shrine) {
        RadarEntity boss = RadarEntity.spawn(level, RadarVariant.byId(shrine.index), false, shrine.pos, shrine.index);
        if (boss == null) return;
        shrine.bossId = boss.getUUID();
        data.setDirty();
        Radars.LOGGER.info("{} appeared at its shrine {}", boss.getVariant().displayName, shrine.pos.toShortString());
    }

    private static double nearestPlayerDistance(ServerLevel level, double x, double z) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - x, dz = p.getZ() - z;
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
        }
        return best;
    }

    // ------------------------------------------------------------------ naming a mob "Radar"

    /** Using a name tag that reads "Radar" on any mob turns that mob into Radar the cat. */
    @SubscribeEvent
    public static void onNameTag(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || !(event.getTarget() instanceof Mob mob) || mob instanceof RadarEntity) return;
        ItemStack stack = event.getItemStack();
        if (!stack.is(Items.NAME_TAG) || !stack.has(DataComponents.CUSTOM_NAME)) return;
        if (!isRadarName(stack.getHoverName().getString())) return;
        convertToRadar((ServerLevel) event.getLevel(), mob);
        if (!event.getEntity().getAbilities().instabuild) stack.shrink(1);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /** Covers mobs that arrive already named, e.g. summoned with a CustomName. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob) || mob instanceof RadarEntity) return;
        if (!mob.hasCustomName() || !isRadarName(mob.getCustomName().getString())) return;
        ServerLevel level = (ServerLevel) event.getLevel();
        level.getServer().execute(() -> {
            if (mob.isAlive()) convertToRadar(level, mob);
        });
    }

    static boolean isRadarName(String name) {
        return name.trim().equalsIgnoreCase("Radar");
    }

    private static void convertToRadar(ServerLevel level, Mob mob) {
        BlockPos at = mob.blockPosition();
        mob.discard();
        RadarEntity cat = RadarEntity.spawn(level, RadarVariant.RADAR, true, at, 1);
        if (cat != null) {
            Radars.LOGGER.info("{} at {} became Radar the cat", mob.getType().toShortString(), at.toShortString());
            for (ServerPlayer p : level.players()) {
                if (p.distanceToSqr(cat) < 64 * 64) {
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal("Radar purrs and turns to face something far away..."));
                }
            }
        }
    }
}
