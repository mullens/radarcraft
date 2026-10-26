package com.lucasmods.keyboardkeybinds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.KeyMapping;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pairs of key mappings the user has declared to be an intentional overlap.
 * Stored in config/keyboardkeybinds.json as sorted "mapping.a|mapping.b" strings.
 */
public final class IgnoredConflicts {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FIELD = "intentionalOverlaps";

    private final Path file;
    private final Set<String> pairs = new HashSet<>();

    private IgnoredConflicts(Path file) {
        this.file = file;
    }

    public static IgnoredConflicts load() {
        IgnoredConflicts result = new IgnoredConflicts(FMLPaths.CONFIGDIR.get().resolve(KeyboardKeybinds.MODID + ".json"));
        if (Files.exists(result.file)) {
            try {
                JsonElement root = JsonParser.parseString(Files.readString(result.file, StandardCharsets.UTF_8));
                if (root.isJsonObject() && root.getAsJsonObject().has(FIELD)) {
                    for (JsonElement e : root.getAsJsonObject().getAsJsonArray(FIELD)) {
                        result.pairs.add(e.getAsString());
                    }
                }
            } catch (IOException | RuntimeException e) {
                KeyboardKeybinds.LOGGER.warn("Could not read {}: {}", result.file, e.toString());
            }
        }
        return result;
    }

    public void save() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (String pair : new TreeSet<>(pairs)) array.add(pair);
        root.add(FIELD, array);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            KeyboardKeybinds.LOGGER.warn("Could not write {}: {}", file, e.toString());
        }
    }

    public boolean isIgnored(KeyMapping a, KeyMapping b) {
        return pairs.contains(id(a, b));
    }

    /** Flips the pair and persists immediately. */
    public void toggle(KeyMapping a, KeyMapping b) {
        String id = id(a, b);
        if (!pairs.remove(id)) pairs.add(id);
        save();
    }

    private static String id(KeyMapping a, KeyMapping b) {
        String x = a.getName();
        String y = b.getName();
        return x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x;
    }
}
