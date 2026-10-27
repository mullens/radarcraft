package com.lucasmods.foodbuffs;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eating a modded food can raise saturation at most up to that food's own saturation value, so being able to eat
 * while full does not let anyone stack saturation to the max with cheap food. Never lowers saturation.
 */
public final class SaturationCap {
    private static final Map<UUID, Float> BEFORE = new ConcurrentHashMap<>();

    private SaturationCap() {}

    private static boolean applies(ItemStack stack) {
        if (!FoodConfig.saturationCap()) return false;
        if ("minecraft".equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace())) return false;
        return stack.get(DataComponents.FOOD) != null;
    }

    public static void onStart(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof Player p && !p.level().isClientSide && applies(event.getItem())) {
            BEFORE.put(p.getUUID(), p.getFoodData().getSaturationLevel());
        }
    }

    public static void onFinish(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof Player p) || p.level().isClientSide) return;
        Float before = BEFORE.remove(p.getUUID());
        int[] report = REPORT.remove(p.getUUID());
        if (before != null && applies(event.getItem())) {
            FoodProperties food = event.getItem().get(DataComponents.FOOD);
            FoodData data = p.getFoodData();
            float now = data.getSaturationLevel();
            float allowed = Math.max(before, Math.min(now, food.saturation()));
            if (now > allowed) data.setSaturation(allowed);
        }
        ensureBuffs(p, event.getItem());
        if (report != null) {
            FoodData data = p.getFoodData();
            StringBuilder effects = new StringBuilder();
            p.getActiveEffects().forEach(e -> effects.append(' ').append(BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).getPath())
                    .append(e.getAmplifier() > 0 ? "_" + (e.getAmplifier() + 1) : "").append('/').append(e.getDuration() / 20).append('s'));
            String line = String.format("[FoodBuffs test] ate %s: food %d -> %d, saturation %.1f -> %.1f (food's own saturation %.1f), effects:%s",
                    BuiltInRegistries.ITEM.getKey(event.getItem().getItem()), report[0], data.getFoodLevel(), report[1] / 10f,
                    data.getSaturationLevel(), event.getItem().get(DataComponents.FOOD) == null ? 0f : event.getItem().get(DataComponents.FOOD).saturation(), effects);
            FoodBuffs.LOGGER.info(line);
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
        }
    }

    /**
     * Some items finish with their own code and skip the food component's effects (Vinery's wines apply only their
     * own effect). After any configured food is used up, give each of its buffs that did not land.
     */
    private static void ensureBuffs(Player p, ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if ("minecraft".equals(id.getNamespace())) return;
        FoodConfig.Entry entry = FoodConfig.foods().get(id.toString());
        if (entry == null || entry.effects() == null) return;
        for (FoodConfig.Buff b : entry.effects()) {
            var effect = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(b.effect()));
            if (effect.isEmpty()) continue;
            int ticks = b.seconds() * 20;
            MobEffectInstance current = p.getEffect(effect.get());
            boolean landed = current != null && current.getAmplifier() >= b.amplifier() && (current.isInfiniteDuration() || current.getDuration() >= ticks - 40);
            if (!landed) p.addEffect(new MobEffectInstance(effect.get(), ticks, b.amplifier()));
        }
    }

    private static final Map<UUID, int[]> REPORT = new ConcurrentHashMap<>();

    /** Test hook: report food/saturation/effects when this player's next eat finishes. */
    public static void report(Player p, int food, float saturation) {
        REPORT.put(p.getUUID(), new int[]{food, Math.round(saturation * 10)});
    }
}
