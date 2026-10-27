package com.lucasmods.foodbuffs;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Rewrites the food component of every modded food once at startup. */
public final class FoodComponents {
    private FoodComponents() {}

    public static void modify(ModifyDefaultComponentsEvent event) {
        int changed = 0;
        for (Item item : event.getAllItems().toList()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if ("minecraft".equals(id.getNamespace())) continue; // vanilla is the baseline: never touched
            FoodProperties food = item.components().get(DataComponents.FOOD);
            if (food == null) continue;
            FoodConfig.Entry entry = FoodConfig.foods().get(id.toString());
            List<FoodConfig.Buff> buffs = entry == null ? List.of() : entry.effects();
            boolean alwaysEat = food.canAlwaysEat() || FoodConfig.alwaysEdibleModded();
            if (buffs.isEmpty() && alwaysEat == food.canAlwaysEat()) continue;

            List<FoodProperties.PossibleEffect> effects = new ArrayList<>();
            List<Holder<MobEffect>> replaced = new ArrayList<>();
            List<FoodProperties.PossibleEffect> ours = new ArrayList<>();
            for (FoodConfig.Buff b : buffs) {
                Optional<Holder.Reference<MobEffect>> effect = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(b.effect()));
                if (effect.isEmpty()) {
                    FoodBuffs.LOGGER.warn("Food Buffs: unknown effect {} for {}", b.effect(), id);
                    continue;
                }
                Holder<MobEffect> h = effect.get();
                replaced.add(h);
                int ticks = b.seconds() * 20, amp = b.amplifier();
                ours.add(new FoodProperties.PossibleEffect(() -> new MobEffectInstance(h, ticks, amp), 1.0F));
            }
            for (FoodProperties.PossibleEffect pe : food.effects()) {
                if (!replaced.contains(pe.effect().getEffect())) effects.add(pe);
            }
            effects.addAll(ours);
            FoodProperties rewritten = new FoodProperties(food.nutrition(), food.saturation(), alwaysEat, food.eatSeconds(),
                    food.usingConvertsTo(), List.copyOf(effects));
            event.modify(item, patch -> patch.set(DataComponents.FOOD, rewritten));
            changed++;
        }
        FoodBuffs.LOGGER.info("Food Buffs: rewrote {} modded foods.", changed);
    }
}
