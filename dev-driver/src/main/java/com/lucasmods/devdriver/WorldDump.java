package com.lucasmods.devdriver;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Biomes (which dimensions can generate them, climate, tags) and mob types (spawn egg, natural spawning), for building grid squares. */
final class WorldDump {
    private WorldDump() {}

    static int dump(MinecraftServer server, Path out) {
        JsonObject root = new JsonObject();
        Map<ResourceLocation, Set<String>> dims = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Holder<Biome> b : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
                b.unwrapKey().ifPresent(k -> dims.computeIfAbsent(k.location(), x -> new HashSet<>()).add(level.dimension().location().toString()));
            }
        }
        Set<ResourceLocation> naturalSpawns = new HashSet<>();
        JsonArray biomes = new JsonArray();
        var registry = server.registryAccess().registryOrThrow(Registries.BIOME);
        for (var entry : registry.holders().toList()) {
            ResourceLocation id = entry.key().location();
            Biome biome = entry.value();
            for (MobCategory c : MobCategory.values()) {
                for (MobSpawnSettings.SpawnerData d : biome.getMobSettings().getMobs(c).unwrap()) naturalSpawns.add(BuiltInRegistries.ENTITY_TYPE.getKey(d.type));
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", id.toString());
            JsonArray d = new JsonArray();
            for (String dim : dims.getOrDefault(id, Set.of())) d.add(dim);
            o.add("dims", d);
            o.addProperty("temperature", biome.getBaseTemperature());
            o.addProperty("precipitation", biome.hasPrecipitation());
            JsonArray tags = new JsonArray();
            entry.tags().forEach(t -> tags.add(t.location().toString()));
            o.add("tags", tags);
            biomes.add(o);
        }
        root.add("biomes", biomes);

        JsonArray mobs = new JsonArray();
        ServerLevel overworld = server.overworld();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            boolean mob = false;
            String error = null;
            try {
                Entity e = type.create(overworld);
                mob = e instanceof Mob;
                if (e != null) e.discard();
            } catch (Throwable t) {
                error = t.getClass().getSimpleName();
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", id.toString());
            o.addProperty("mob", mob);
            if (error != null) o.addProperty("error", error);
            o.addProperty("category", type.getCategory().getName());
            o.addProperty("name_key", type.getDescriptionId());
            SpawnEggItem egg = SpawnEggItem.byId(type);
            if (egg != null) o.addProperty("egg", BuiltInRegistries.ITEM.getKey(egg).toString());
            o.addProperty("spawns_naturally", naturalSpawns.contains(id));
            o.addProperty("summonable", type.canSummon());
            mobs.add(o);
        }
        root.add("mobs", mobs);
        try {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return biomes.size() + mobs.size();
    }
}
