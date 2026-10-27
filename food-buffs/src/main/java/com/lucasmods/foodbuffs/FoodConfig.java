package com.lucasmods.foodbuffs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config/foodbuffs.json}:
 * <pre>{
 *   "always_edible_modded": true,      // every modded food can be eaten while full
 *   "saturation_cap": true,            // eating never pushes saturation above that food's own saturation
 *   "foods": {
 *     "farmersdelight:beef_stew": { "tier": 3, "effects": [ {"effect": "minecraft:strength", "amplifier": 0, "seconds": 240} ] }
 *   }
 * }</pre>
 * Listed effects are added to the food's own effects (an effect the food already has is replaced by the listed one).
 */
public final class FoodConfig {
    public record Buff(String effect, int amplifier, int seconds) {}
    public record Entry(int tier, List<Buff> effects) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Entry> FOODS = new LinkedHashMap<>();
    private static boolean alwaysEdibleModded = true;
    private static boolean saturationCap = true;

    private FoodConfig() {}

    public static Map<String, Entry> foods() { return FOODS; }
    public static boolean alwaysEdibleModded() { return alwaysEdibleModded; }
    public static boolean saturationCap() { return saturationCap; }

    public static void load() {
        Path file = FMLPaths.CONFIGDIR.get().resolve(FoodBuffs.MODID + ".json");
        try {
            if (!Files.exists(file)) {
                try (InputStream in = FoodConfig.class.getResourceAsStream("/data/foodbuffs/default_foodbuffs.json")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            if (!Files.exists(file)) return;
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject root = GSON.fromJson(r, JsonObject.class);
                if (root.has("always_edible_modded")) alwaysEdibleModded = root.get("always_edible_modded").getAsBoolean();
                if (root.has("saturation_cap")) saturationCap = root.get("saturation_cap").getAsBoolean();
                FOODS.clear();
                JsonObject foods = root.getAsJsonObject("foods");
                if (foods == null) return;
                for (Map.Entry<String, JsonElement> e : foods.entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    List<Buff> buffs = new ArrayList<>();
                    JsonArray arr = o.getAsJsonArray("effects");
                    if (arr != null) {
                        for (JsonElement b : arr) {
                            JsonObject bo = b.getAsJsonObject();
                            buffs.add(new Buff(bo.get("effect").getAsString(),
                                    bo.has("amplifier") ? bo.get("amplifier").getAsInt() : 0,
                                    bo.has("seconds") ? bo.get("seconds").getAsInt() : 60));
                        }
                    }
                    FOODS.put(e.getKey(), new Entry(o.has("tier") ? o.get("tier").getAsInt() : 0, buffs));
                }
            }
        } catch (Exception ex) {
            FoodBuffs.LOGGER.error("Could not read {}: {}", file, ex.toString());
        }
    }
}
