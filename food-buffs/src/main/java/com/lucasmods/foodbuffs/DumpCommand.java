package com.lucasmods.foodbuffs;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code /foodbuffs dump}: writes config/foodbuffs-dump.json with every food item (incl. vanilla, as the baseline),
 * its stats and current effects, and every recipe in the game (type, result, ingredient options), so the buffs can be
 * designed from real difficulty data. {@code /foodbuffs reload} re-reads the config (tooltips and the saturation cap
 * follow it at once; effects need a restart because components are fixed at startup).
 */
public final class DumpCommand {
    private DumpCommand() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("foodbuffs").requires(s -> s.hasPermission(2))
                .then(Commands.literal("dump").executes(c -> dump(c.getSource())))
                .then(Commands.literal("eat").executes(c -> {
                    // test helper: right-click the main-hand item server-side, exactly like the player would, so the
                    // normal food checks, eating time, events and effects all run; the result is reported on finish
                    var player = c.getSource().getPlayerOrException();
                    var stack = player.getMainHandItem();
                    var before = player.getFoodData();
                    SaturationCap.report(player, before.getFoodLevel(), before.getSaturationLevel());
                    var result = stack.use(player.level(), player, net.minecraft.world.InteractionHand.MAIN_HAND);
                    boolean started = player.isUsingItem();
                    c.getSource().sendSuccess(() -> Component.literal("Food Buffs test: " + stack.getHoverName().getString()
                            + (started ? " - eating" : " - refused (" + result.getResult() + ")")), false);
                    return started ? 1 : 0;
                }))
                .then(Commands.literal("reload").executes(c -> {
                    FoodConfig.load();
                    c.getSource().sendSuccess(() -> Component.literal("Food Buffs: " + FoodConfig.foods().size() + " foods configured (effects change after a restart)."), true);
                    return 1;
                })));
    }

    private static int dump(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        JsonObject root = new JsonObject();
        JsonArray foods = new JsonArray();
        for (Item item : BuiltInRegistries.ITEM) {
            FoodProperties food = item.components().get(DataComponents.FOOD);
            if (food == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", BuiltInRegistries.ITEM.getKey(item).toString());
            o.addProperty("name", new ItemStack(item).getHoverName().getString());
            o.addProperty("nutrition", food.nutrition());
            o.addProperty("saturation", food.saturation());
            o.addProperty("always", food.canAlwaysEat());
            o.addProperty("seconds", food.eatSeconds());
            food.usingConvertsTo().ifPresent(s -> o.addProperty("converts_to", BuiltInRegistries.ITEM.getKey(s.getItem()).toString()));
            o.addProperty("stack", new ItemStack(item).getMaxStackSize());
            JsonArray eff = new JsonArray();
            for (FoodProperties.PossibleEffect pe : food.effects()) {
                MobEffectInstance e = pe.effect();
                JsonObject eo = new JsonObject();
                eo.addProperty("effect", BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).toString());
                eo.addProperty("amplifier", e.getAmplifier());
                eo.addProperty("seconds", e.getDuration() / 20);
                eo.addProperty("chance", pe.probability());
                eff.add(eo);
            }
            o.add("effects", eff);
            foods.add(o);
        }
        root.add("foods", foods);

        JsonArray recipes = new JsonArray();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            try {
                ItemStack result = holder.value().getResultItem(server.registryAccess());
                if (result.isEmpty()) continue;
                JsonObject r = new JsonObject();
                r.addProperty("id", holder.id().toString());
                r.addProperty("type", String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType())));
                r.addProperty("result", BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
                r.addProperty("count", result.getCount());
                JsonArray ings = new JsonArray();
                for (Ingredient ing : holder.value().getIngredients()) {
                    if (ing.isEmpty()) continue;
                    JsonArray opts = new JsonArray();
                    ItemStack[] items = ing.getItems();
                    for (int i = 0; i < Math.min(items.length, 12); i++) {
                        opts.add(BuiltInRegistries.ITEM.getKey(items[i].getItem()).toString());
                    }
                    ings.add(opts);
                }
                r.add("ingredients", ings);
                recipes.add(r);
            } catch (Exception ignored) {
                // some modded recipe types cannot produce a result item outside their machine; skip them
            }
        }
        root.add("recipes", recipes);

        Path out = FMLPaths.CONFIGDIR.get().resolve("foodbuffs-dump.json");
        try {
            Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            src.sendFailure(Component.literal("Food Buffs: could not write " + out + ": " + e));
            return 0;
        }
        int n = foods.size(), rn = recipes.size();
        src.sendSuccess(() -> Component.literal("Food Buffs: dumped " + n + " foods and " + rn + " recipes to " + out), true);
        return n;
    }
}
