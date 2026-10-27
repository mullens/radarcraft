package com.lucasmods.cherishedmobs;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class MobHandler {
    /** Marks mobs whose invulnerable flag was set by this mod, so we only ever clear our own. */
    private static final String INVULNERABLE_TAG = "cherishedmobs_invulnerable";
    /** Last position where a named mob stood on solid ground; used to pull it back out of the void. */
    private static final String SAFE_POS_TAG = "cherishedmobs_safe_pos";
    /** How far below the world floor a mob may fall before it is rescued. */
    private static final int VOID_MARGIN = 32;

    private MobHandler() {}

    // ------------------------------------------------------------------ classification

    /** Anything that is a mob but not a hostile one, plus everything tamable (wolves, cats, horses...). */
    static boolean isFriendly(LivingEntity entity) {
        if (!(entity instanceof Mob)) return false;
        if (entity instanceof TamableAnimal || entity instanceof OwnableEntity) return true;
        return !(entity instanceof Enemy);
    }

    /** A mob that carries a name tag. Players are never "cherished" by this mod. */
    static boolean isCherished(LivingEntity entity) {
        return !(entity instanceof Player) && entity.hasCustomName();
    }

    private static boolean isKillCommand(DamageSource source) {
        return source.is(DamageTypes.GENERIC_KILL) && CherishedMobs.SPEC.isLoaded() && CherishedMobs.KILL_COMMAND_REMOVES_NAMED_MOBS.get();
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof LivingEntity entity)) return;
        refresh(entity);
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || entity instanceof Player || entity.level().isClientSide()) return;
        if (entity.tickCount % 100 == 0) refresh(entity); // name tags can be added or removed at any time
        slowHeal(entity);
        if (!isCherished(entity)) return;
        int floor = entity.level().getMinBuildHeight();
        if (entity.tickCount % 20 == 0 && entity.onGround() && entity.getY() > floor) rememberSafePosition(entity);
        if (entity.getY() < floor - VOID_MARGIN) rescueFromVoid(entity);
    }

    /** First line of defence: named mobs simply do not receive damage (except /kill, if allowed). */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide() || !isCherished(entity)) return;
        if (isKillCommand(event.getSource())) return;
        event.setCanceled(true);
    }

    /** Friendly mobs: emulate a full set of Protection V as a flat percentage reduction. */
    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Pre event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide() || !isFriendly(entity) || !CherishedMobs.SPEC.isLoaded()) return;
        if (event.getSource().is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) return; // same exemptions as real Protection
        float factor = 1.0f - CherishedMobs.FRIENDLY_DAMAGE_REDUCTION_PERCENT.get() / 100.0f;
        float reduced = event.getNewDamage() * factor;
        CherishedMobs.LOGGER.debug("{} takes {} instead of {} ({})", entity.getName().getString(), reduced, event.getNewDamage(), event.getSource().getMsgId());
        event.setNewDamage(reduced);
    }

    /** Backstop: should anything slip through, a named mob refuses to die and is healed instead. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide() || !isCherished(entity)) return;
        if (isKillCommand(event.getSource())) return;
        event.setCanceled(true);
        entity.setHealth(entity.getMaxHealth());
        CherishedMobs.LOGGER.info("Kept {} alive (source: {})", describe(entity), event.getSource().getMsgId());
    }

    // ------------------------------------------------------------------ helpers

    private static void refresh(LivingEntity entity) {
        applyRegeneration(entity);
        syncInvulnerable(entity);
    }

    /** Older versions gave friendly mobs an infinite, hidden Regeneration I. It healed far too fast: take it off again. */
    private static void applyRegeneration(LivingEntity entity) {
        MobEffectInstance regen = entity.getEffect(MobEffects.REGENERATION);
        if (regen != null && regen.isInfiniteDuration() && regen.getAmplifier() == 0 && regen.isAmbient() && !regen.isVisible()) {
            entity.removeEffect(MobEffects.REGENERATION);
        }
    }

    /** Friendly mobs heal half a heart every regenerationSeconds (default 15 s, six times slower than Regeneration I). */
    private static void slowHeal(LivingEntity entity) {
        if (!CherishedMobs.SPEC.isLoaded() || !CherishedMobs.FRIENDLY_REGENERATION.get()) return;
        int period = CherishedMobs.FRIENDLY_REGEN_SECONDS.get() * 20;
        if (entity.tickCount % period != 0 || entity.getHealth() >= entity.getMaxHealth() || entity.isDeadOrDying()) return;
        if (!isFriendly(entity)) return;
        entity.heal(1.0F);
    }

    /** Keeps the vanilla invulnerable flag in step with the name tag, touching only flags we set ourselves. */
    private static void syncInvulnerable(LivingEntity entity) {
        CompoundTag data = entity.getPersistentData();
        boolean cherished = isCherished(entity);
        // A named mob must never despawn. Vanilla only flags persistence when a name tag item is used,
        // not for mobs that arrive already named (/summon, spawn eggs with NBT, other mods).
        if (cherished && entity instanceof Mob mob && !mob.isPersistenceRequired()) mob.setPersistenceRequired();
        if (cherished && !entity.isInvulnerable()) {
            entity.setInvulnerable(true);
            data.putBoolean(INVULNERABLE_TAG, true);
        } else if (!cherished && data.getBoolean(INVULNERABLE_TAG)) {
            entity.setInvulnerable(false);
            data.remove(INVULNERABLE_TAG);
        }
    }

    private static void rememberSafePosition(LivingEntity entity) {
        CompoundTag pos = new CompoundTag();
        pos.putDouble("x", entity.getX());
        pos.putDouble("y", entity.getY());
        pos.putDouble("z", entity.getZ());
        entity.getPersistentData().put(SAFE_POS_TAG, pos);
    }

    private static void rescueFromVoid(LivingEntity entity) {
        ServerLevel level = (ServerLevel) entity.level();
        CompoundTag safe = entity.getPersistentData().getCompound(SAFE_POS_TAG);
        Vec3 target;
        if (safe.contains("y")) {
            target = new Vec3(safe.getDouble("x"), safe.getDouble("y"), safe.getDouble("z"));
        } else {
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(entity.getX(), 0, entity.getZ()));
            if (top.getY() <= level.getMinBuildHeight()) {
                // nothing under it at all (End void, sky islands): go to the world spawn instead
                top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level.getSharedSpawnPos());
            }
            target = Vec3.atBottomCenterOf(top);
        }
        entity.teleportTo(target.x, target.y + 0.1, target.z);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.resetFallDistance();
        entity.setHealth(entity.getMaxHealth());
        CherishedMobs.LOGGER.info("Pulled {} out of the void to {}", describe(entity), target);
    }

    private static String describe(LivingEntity entity) {
        return entity.getName().getString() + " the " + entity.getType().toShortString();
    }
}
