package com.lucasmods.checklist;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which advancements each player has done, as last seen by the server (world file {@code data/checklist_squares.dat}),
 * so a party shares the squares of teammates who are offline. Online players are always read live; this is only the
 * fallback. A player the ledger has never seen is read once from their vanilla advancements file.
 */
public final class SquareLedger extends SavedData {
    private static final String NAME = "checklist_squares";
    private final Map<UUID, Entry> players = new HashMap<>();

    public record Entry(String name, Set<String> done) {}

    public static SquareLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(SquareLedger::new, SquareLedger::load, null), NAME);
    }

    /** Done advancement ids of a player who may be offline; empty if unknown. */
    public Entry entry(MinecraftServer server, UUID id) {
        Entry e = players.get(id);
        if (e == null) {
            e = new Entry(server.getProfileCache() != null ? server.getProfileCache().get(id).map(p -> p.getName()).orElse("?") : "?", readAdvancementFile(server, id));
            players.put(id, e);
            setDirty();
        }
        return e;
    }

    public void record(UUID id, String name, Set<String> done) {
        Entry old = players.get(id);
        if (old != null && old.name.equals(name) && old.done.equals(done)) return;
        players.put(id, new Entry(name, new LinkedHashSet<>(done)));
        setDirty();
    }

    private static Set<String> readAdvancementFile(MinecraftServer server, UUID id) {
        Set<String> done = new LinkedHashSet<>();
        Path file = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(id + ".json");
        if (!Files.isRegularFile(file)) return done;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("done")
                        && e.getValue().getAsJsonObject().get("done").getAsBoolean()) done.add(e.getKey());
            }
        } catch (Exception ex) {
            Checklist.LOGGER.warn("Could not read advancements of {} for team squares", id, ex);
        }
        return done;
    }

    private static SquareLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        SquareLedger ledger = new SquareLedger();
        CompoundTag players = tag.getCompound("players");
        for (String key : players.getAllKeys()) {
            CompoundTag p = players.getCompound(key);
            Set<String> done = new LinkedHashSet<>();
            ListTag list = p.getList("done", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) done.add(list.getString(i));
            ledger.players.put(UUID.fromString(key), new Entry(p.getString("name"), done));
        }
        return ledger;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag players = new CompoundTag();
        this.players.forEach((id, e) -> {
            CompoundTag p = new CompoundTag();
            p.putString("name", e.name);
            ListTag list = new ListTag();
            for (String s : e.done) list.add(StringTag.valueOf(s));
            p.put("done", list);
            players.put(id.toString(), p);
        });
        tag.put("players", players);
        return tag;
    }
}
