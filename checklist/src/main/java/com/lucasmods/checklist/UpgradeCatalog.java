package com.lucasmods.checklist;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The upgrade catalog from config/checklist-upgrades.json: one point buys one rank, ranks can be
 * removed again at any time. Everything is attribute modifiers and permanent potion effects, except
 * the two "special" upgrades (second_wind, magnet) which have code behind them.
 */
public final class UpgradeCatalog {
    public record Modifier(ResourceLocation attribute, AttributeModifier.Operation operation, double amountPerRank) {}
    public record Effect(ResourceLocation effect, int amplifier) {}

    public static final class Upgrade {
        public final String id;
        public final String name;
        public final String category;
        public final int ranks;
        public final String description;
        public final List<Modifier> modifiers;
        public final List<Effect> effects;
        public final List<Effect> effectsAtMax;
        public final List<String> excludes;
        @Nullable public final String special;

        Upgrade(String id, String name, String category, int ranks, String description, List<Modifier> modifiers,
                List<Effect> effects, List<Effect> effectsAtMax, List<String> excludes, @Nullable String special) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.ranks = ranks;
            this.description = description;
            this.modifiers = modifiers;
            this.effects = effects;
            this.effectsAtMax = effectsAtMax;
            this.excludes = excludes;
            this.special = special;
        }

        /** Deterministic modifier id for the n-th modifier of this upgrade. */
        public ResourceLocation modifierId(int index) {
            return ResourceLocation.fromNamespaceAndPath(Checklist.MODID, index == 0 ? id : id + "_" + index);
        }
    }

    private final Map<String, Upgrade> upgrades;
    private final List<String> categories;
    public final int totalRanks;

    private UpgradeCatalog(Map<String, Upgrade> upgrades) {
        this.upgrades = Collections.unmodifiableMap(upgrades);
        List<String> cats = new ArrayList<>();
        int total = 0;
        for (Upgrade u : upgrades.values()) {
            if (!cats.contains(u.category)) cats.add(u.category);
            total += u.ranks;
        }
        this.categories = Collections.unmodifiableList(cats);
        this.totalRanks = total;
    }

    public static UpgradeCatalog parse(JsonObject root) {
        Map<String, Upgrade> map = new LinkedHashMap<>();
        for (JsonElement el : root.getAsJsonArray("upgrades")) {
            JsonObject o = el.getAsJsonObject();
            String id = o.get("id").getAsString();
            List<Modifier> mods = new ArrayList<>();
            if (o.has("modifiers")) {
                for (JsonElement m : o.getAsJsonArray("modifiers")) {
                    JsonObject mo = m.getAsJsonObject();
                    mods.add(new Modifier(ResourceLocation.parse(mo.get("attribute").getAsString()),
                            parseOperation(mo.get("operation").getAsString()), mo.get("amount").getAsDouble()));
                }
            }
            List<String> excludes = new ArrayList<>();
            if (o.has("excludes")) for (JsonElement e : o.getAsJsonArray("excludes")) excludes.add(e.getAsString());
            map.put(id, new Upgrade(id, o.get("name").getAsString(), o.has("category") ? o.get("category").getAsString() : "General",
                    Math.max(1, o.get("ranks").getAsInt()), o.has("description") ? o.get("description").getAsString() : "",
                    mods, parseEffects(o, "effects"), parseEffects(o, "effects_at_max"), excludes,
                    o.has("special") ? o.get("special").getAsString() : null));
        }
        return new UpgradeCatalog(map);
    }

    private static List<Effect> parseEffects(JsonObject o, String key) {
        List<Effect> list = new ArrayList<>();
        if (o.has(key)) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                JsonObject eo = e.getAsJsonObject();
                list.add(new Effect(ResourceLocation.parse(eo.get("effect").getAsString()), eo.has("amplifier") ? eo.get("amplifier").getAsInt() : 0));
            }
        }
        return list;
    }

    private static AttributeModifier.Operation parseOperation(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "add_multiplied_base", "multiply_base" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "add_multiplied_total", "multiply_total" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> AttributeModifier.Operation.ADD_VALUE;
        };
    }

    public Map<String, Upgrade> all() { return upgrades; }
    @Nullable public Upgrade get(String id) { return upgrades.get(id); }
    public List<String> categories() { return categories; }
}
