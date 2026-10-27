package com.lucasmods.checklist;

import com.lucasmods.checklist.net.DoubleJumpPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.EnderManAngerEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Upgrades with code behind them, keyed by their catalog id. Every handler reads ranks through
 * {@link PlayerState#fastRank}, so an upgrade with rank 0 costs one map lookup.
 * Hearty Appetite lives in a mixin ({@code mixin.PlayerMixin}) because exhaustion has no event.
 */
public final class Specials {
    private static final long SECOND_WIND_COOLDOWN = 5 * 60 * 20;
    private static final int EFFECT_REFRESH_TICKS = 40;
    private static final int SHADOW_STILL_TICKS = 20;
    private static final int GREEN_THUMB_TICKS = 40;
    private static final int GREEN_THUMB_RADIUS = 6;

    /** Shadow Step: ticks spent sneaking without moving, and the last position. */
    private static final Map<ServerPlayer, Integer> stillTicks = new WeakHashMap<>();
    private static final Map<ServerPlayer, Vec3> lastPos = new WeakHashMap<>();
    private static final Set<ServerPlayer> shadowed = Collections.newSetFromMap(new WeakHashMap<>());
    /** Double Jump: players who used their air jump since they last stood on something. */
    private static final Set<ServerPlayer> airJumped = Collections.newSetFromMap(new WeakHashMap<>());
    /** Studious: fractional bonus XP carried to the next orb. */
    private static final Map<ServerPlayer, Double> studiousCarry = new WeakHashMap<>();

    private Specials() {}

    private static int rank(ServerPlayer player, String id) {
        return PlayerState.fastRank(player, id);
    }

    // ------------------------------------------------------------------ ticking specials

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % EFFECT_REFRESH_TICKS == 0) {
            PlayerState state = PlayerState.load(player);
            if (!state.ranks.isEmpty()) ChecklistServer.refreshEffects(player, state, ChecklistConfig.upgrades());
        }
        if (player.onGround() || player.isInWater() || player.onClimbable() || player.getAbilities().flying) airJumped.remove(player);
        magnet(player);
        coldBlooded(player);
        shadowStep(player);
        tinkerer(player);
        greenThumb(player);
    }

    /** Magnet: pulls nearby item entities toward the player. */
    private static void magnet(ServerPlayer player) {
        int magnet = rank(player, "magnet");
        if (magnet <= 0 || player.tickCount % 2 != 0) return;
        double radius = 1.5 * magnet;
        ServerLevel level = player.serverLevel();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(radius), i -> !i.hasPickUpDelay())) {
            Vec3 dir = player.position().add(0, 0.8, 0).subtract(item.position());
            double d = dir.length();
            if (d < 0.5 || d > radius) continue;
            item.setDeltaMovement(item.getDeltaMovement().scale(0.5).add(dir.normalize().scale(0.35)));
            item.hasImpulse = true;
        }
    }

    /** Cold Blooded: powder snow never freezes you. */
    private static void coldBlooded(ServerPlayer player) {
        if (player.getTicksFrozen() > 0 && rank(player, "cold_blooded") > 0) player.setTicksFrozen(0);
    }

    /** Shadow Step: sneak without moving for a second to turn invisible; mobs lose track of you until you move. */
    private static void shadowStep(ServerPlayer player) {
        if (rank(player, "shadow_step") <= 0) {
            if (shadowed.remove(player)) clearShadow(player);
            return;
        }
        Vec3 pos = player.position();
        Vec3 last = lastPos.put(player, pos);
        boolean still = player.isShiftKeyDown() && last != null && last.distanceToSqr(pos) < 1.0E-4;
        int ticks = still ? stillTicks.getOrDefault(player, 0) + 1 : 0;
        stillTicks.put(player, ticks);
        if (ticks >= SHADOW_STILL_TICKS) {
            if (ticks == SHADOW_STILL_TICKS || player.tickCount % 10 == 0) {
                player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 40, 0, true, false, false));
            }
            if (shadowed.add(player)) {
                player.serverLevel().sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 0.5, player.getZ(), 12, 0.3, 0.4, 0.3, 0.01);
            }
            for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(32), m -> m.getTarget() == player)) {
                mob.setTarget(null);
            }
        } else if (shadowed.remove(player)) {
            clearShadow(player);
        }
    }

    private static void clearShadow(ServerPlayer player) {
        MobEffectInstance current = player.getEffect(MobEffects.INVISIBILITY);
        if (current != null && current.isAmbient() && !current.isVisible()) player.removeEffect(MobEffects.INVISIBILITY);
    }

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() instanceof ServerPlayer player && shadowed.contains(player)) event.setCanceled(true);
    }

    /** Tinkerer: repairs one durability on worn armour and held items, every 60 s / rank. */
    private static void tinkerer(ServerPlayer player) {
        int r = rank(player, "tinkerer");
        if (r <= 0 || player.tickCount % Math.max(1, 1200 / r) != 0) return;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.ANIMAL_ARMOR) continue;
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isDamageableItem() && stack.isDamaged()) stack.setDamageValue(stack.getDamageValue() - 1);
        }
    }

    /** Green Thumb: random-ticks crops and saplings around the player, 2 per rank every 2 s. */
    private static void greenThumb(ServerPlayer player) {
        int r = rank(player, "green_thumb");
        if (r <= 0 || player.tickCount % GREEN_THUMB_TICKS != 0) return;
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        List<BlockPos> plants = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-GREEN_THUMB_RADIUS, -2, -GREEN_THUMB_RADIUS), origin.offset(GREEN_THUMB_RADIUS, 2, GREEN_THUMB_RADIUS))) {
            BlockState state = level.getBlockState(p);
            if ((state.is(BlockTags.CROPS) || state.is(BlockTags.SAPLINGS) || state.getBlock() instanceof BonemealableBlock && state.is(BlockTags.MAINTAINS_FARMLAND))
                    && state.isRandomlyTicking()) {
                plants.add(p.immutable());
            }
        }
        RandomSource random = level.getRandom();
        for (int i = 0; i < 2 * r && !plants.isEmpty(); i++) {
            BlockPos p = plants.get(random.nextInt(plants.size()));
            level.getBlockState(p).randomTick(level, p, random);
            if (random.nextInt(3) == 0) level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 2, 0.25, 0.25, 0.25, 0);
        }
    }

    // ------------------------------------------------------------------ damage and death

    /** Second Wind: a lethal hit leaves you at one health instead, once every five minutes. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncomingDamageSecondWind(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getSource().is(DamageTypes.FREEZE) && rank(player, "cold_blooded") > 0) {
            event.setCanceled(true);
            return;
        }
        if (event.getAmount() < player.getHealth()) return;
        if (event.getSource().is(DamageTypes.GENERIC_KILL)) return;
        if (trySecondWind(player)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDeathSecondWind(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getSource().is(DamageTypes.GENERIC_KILL)) return;
        if (trySecondWind(player)) event.setCanceled(true);
    }

    private static boolean trySecondWind(ServerPlayer player) {
        if (rank(player, "second_wind") <= 0) return false;
        PlayerState state = PlayerState.load(player);
        long now = player.level().getGameTime();
        if (now < state.secondWindReadyAt) return false;
        state.secondWindReadyAt = now + SECOND_WIND_COOLDOWN;
        state.save(player);
        player.setHealth(1.0f);
        player.level().playSound(null, player.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0f, 1.0f);
        player.serverLevel().sendParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1, player.getZ(), 60, 0.5, 0.8, 0.5, 0.3);
        player.sendSystemMessage(Component.literal("Second Wind! You cling to life. (5 minute cooldown)"));
        return true;
    }

    /** Thorny, Last Stand and Marksman: adjust melee and projectile damage around a player. */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        // Thorny: the player is hit in melee by a living attacker
        if (event.getEntity() instanceof ServerPlayer victim && source.getEntity() instanceof LivingEntity attacker
                && source.getDirectEntity() == attacker && attacker != victim && !source.is(DamageTypes.THORNS)) {
            int r = rank(victim, "thorny");
            if (r > 0) attacker.hurt(victim.damageSources().thorns(victim), event.getAmount() * 0.10f * r);
        }
        if (source.getEntity() instanceof ServerPlayer attacker && event.getEntity() != attacker) {
            float multiplier = 1.0f;
            int lastStand = rank(attacker, "last_stand");
            if (lastStand > 0 && attacker.getHealth() < attacker.getMaxHealth() * 0.3f) multiplier += 0.15f * lastStand;
            int marksman = rank(attacker, "marksman");
            if (marksman > 0 && source.getDirectEntity() instanceof Projectile projectile && projectile.getOwner() == attacker) multiplier += 0.10f * marksman;
            if (multiplier != 1.0f) event.setAmount(event.getAmount() * multiplier);
        }
    }

    /** Vampiric: killing a mob heals one health per rank. Soul Keeper: remember levels to keep. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Mob && event.getSource().getEntity() instanceof ServerPlayer killer) {
            int r = rank(killer, "vampiric");
            if (r > 0) killer.heal(r);
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            int r = rank(player, "soul_keeper");
            if (r <= 0 || player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
            int keep = (int) Math.floor(player.experienceLevel * Math.min(1.0, 0.10 * r));
            PlayerState state = PlayerState.load(player);
            state.soulKeptLevels = keep;
            state.save(player);
            Checklist.LOGGER.info("{} died at XP level {}: Soul Keeper {} keeps {} level(s)", player.getGameProfile().getName(), player.experienceLevel, r, keep);
        }
    }

    /** Soul Keeper: the orbs dropped on death shrink by the share that is kept. */
    @SubscribeEvent
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        int r = rank(player, "soul_keeper");
        if (r <= 0) return;
        event.setDroppedExperience((int) Math.round(event.getDroppedExperience() * Math.max(0.0, 1.0 - 0.10 * r)));
    }

    /** Soul Keeper: give the kept levels back after respawn (works next to Level Loss Only, which keeps items, not XP). */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() || !(event.getEntity() instanceof ServerPlayer player)) return;
        PlayerState old = PlayerState.load(event.getOriginal());
        if (old.soulKeptLevels <= 0) return;
        player.giveExperienceLevels(old.soulKeptLevels);
        PlayerState state = PlayerState.load(player);
        state.soulKeptLevels = 0;
        state.save(player);
        player.sendSystemMessage(Component.literal("Soul Keeper: you kept " + old.soulKeptLevels + " level" + (old.soulKeptLevels == 1 ? "" : "s") + "."));
    }

    // ------------------------------------------------------------------ effects, mobs, drops, XP

    /** Iron Stomach (no Hunger/Nausea from food) and Antidote (no Poison/Wither). */
    @SubscribeEvent
    public static void onEffectApplicable(MobEffectEvent.Applicable event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MobEffectInstance effect = event.getEffectInstance();
        if (effect == null) return;
        if ((effect.is(MobEffects.POISON) || effect.is(MobEffects.WITHER)) && rank(player, "antidote") > 0) {
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
            return;
        }
        if ((effect.is(MobEffects.HUNGER) || effect.is(MobEffects.CONFUSION)) && rank(player, "iron_stomach") > 0
                && player.isUsingItem() && player.getUseItem().has(DataComponents.FOOD)) {
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        }
    }

    /** Ender Calm: endermen do not mind being looked at. */
    @SubscribeEvent
    public static void onEndermanAnger(EnderManAngerEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && rank(player, "ender_calm") > 0) event.setCanceled(true);
    }

    /** Smelter's Touch: blocks broken with a tool (not sneaking, no Silk Touch) drop their smelted result. */
    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        if (!(event.getBreaker() instanceof ServerPlayer player) || rank(player, "smelters_touch") <= 0) return;
        ItemStack tool = event.getTool();
        if (player.isShiftKeyDown() || tool.isEmpty()) return;
        ServerLevel level = event.getLevel();
        var silk = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
        if (EnchantmentHelper.getItemEnchantmentLevel(silk, tool) > 0) return;
        boolean smelted = false;
        float xp = 0;
        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            SingleRecipeInput input = new SingleRecipeInput(stack);
            var recipe = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, input, level);
            if (recipe.isEmpty()) continue;
            ItemStack result = recipe.get().value().assemble(input, level.registryAccess());
            if (result.isEmpty()) continue;
            result.setCount(Math.min(result.getMaxStackSize(), result.getCount() * stack.getCount()));
            xp += recipe.get().value().getExperience() * stack.getCount();
            drop.setItem(result);
            smelted = true;
        }
        if (smelted) {
            BlockPos pos = event.getPos();
            level.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.25, 0.25, 0.25, 0.01);
            int whole = (int) xp + (level.getRandom().nextFloat() < xp - (int) xp ? 1 : 0);
            if (whole > 0) event.setDroppedExperience(event.getDroppedExperience() + whole);
        }
    }

    /** Studious: +10% XP per rank from every orb, fractions carried over. */
    @SubscribeEvent
    public static void onPickupXp(PlayerXpEvent.PickupXp event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        int r = rank(player, "studious");
        if (r <= 0) return;
        ExperienceOrb orb = event.getOrb();
        double bonus = orb.getValue() * 0.10 * r + studiousCarry.getOrDefault(player, 0.0);
        int whole = (int) bonus;
        studiousCarry.put(player, bonus - whole);
        if (whole > 0) player.giveExperiencePoints(whole);
    }

    // ------------------------------------------------------------------ double jump

    /** The client already jumped; validate once per airtime, clear fall damage, puff. */
    public static void handleDoubleJump(DoubleJumpPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (rank(player, "double_jump") <= 0 || player.getAbilities().flying || player.isFallFlying() || player.isInWater()) return;
            if (!airJumped.add(player)) return; // already used this airtime
            player.resetFallDistance();
            player.serverLevel().sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY(), player.getZ(), 8, 0.25, 0.05, 0.25, 0.02);
            player.level().playSound(null, player.blockPosition(), SoundEvents.WOOL_STEP, SoundSource.PLAYERS, 0.8f, 1.4f);
        });
    }
}
