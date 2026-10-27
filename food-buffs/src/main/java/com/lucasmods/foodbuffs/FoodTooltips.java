package com.lucasmods.foodbuffs;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** Lists a modded food's buffs in its tooltip, like a potion, plus its tier. */
public final class FoodTooltips {
    private FoodTooltips() {}

    public static void onTooltip(ItemTooltipEvent event) {
        var stack = event.getItemStack();
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if ("minecraft".equals(id.getNamespace())) return;
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) return;
        FoodConfig.Entry entry = FoodConfig.foods().get(id.toString());
        if (entry == null || entry.effects().isEmpty()) return;
        // some mods (Farmer's Delight) already print effects for their foods: add only the lines that aren't there yet
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (Component c : event.getToolTip()) existing.add(c.getString());
        for (FoodProperties.PossibleEffect pe : food.effects()) {
            MobEffectInstance e = pe.effect();
            Component name = Component.translatable(e.getDescriptionId());
            if (e.getAmplifier() > 0) name = Component.translatable("potion.withAmplifier", name, Component.translatable("potion.potency." + e.getAmplifier()));
            Component line = Component.translatable("potion.withDuration", name, StringUtil.formatTickDuration(e.getDuration(), 20f));
            if (existing.add(line.getString())) {
                event.getToolTip().add(line.copy().withStyle(e.getEffect().value().getCategory().getTooltipFormatting()));
            }
        }
        event.getToolTip().add(Component.translatable("foodbuffs.tier." + Math.max(1, Math.min(5, entry.tier()))).withStyle(ChatFormatting.DARK_GRAY));
    }
}
