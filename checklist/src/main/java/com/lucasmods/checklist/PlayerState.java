package com.lucasmods.checklist;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A player's chosen upgrade ranks, stored in the NeoForge "PlayerPersisted" tag so it survives
 * death and server restarts. The level itself is never stored: it is always recomputed from the
 * player's advancements.
 */
public final class PlayerState {
    private static final String TAG = "checklist";

    public final Map<String, Integer> ranks = new LinkedHashMap<>();
    /** Order in which ranks were taken, newest last; used to strip ranks when the level drops. */
    public final List<String> order = new ArrayList<>();
    public final List<Integer> rewardedLevels = new ArrayList<>();
    public long secondWindReadyAt = 0;

    public static PlayerState load(Player player) {
        PlayerState state = new PlayerState();
        CompoundTag tag = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getCompound(TAG);
        CompoundTag ranks = tag.getCompound("ranks");
        for (String key : ranks.getAllKeys()) {
            int r = ranks.getInt(key);
            if (r > 0) state.ranks.put(key, r);
        }
        ListTag order = tag.getList("order", Tag.TAG_STRING);
        for (int i = 0; i < order.size(); i++) state.order.add(order.getString(i));
        for (int level : tag.getIntArray("rewarded")) state.rewardedLevels.add(level);
        state.secondWindReadyAt = tag.getLong("second_wind_ready_at");
        return state;
    }

    public void save(Player player) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        CompoundTag tag = new CompoundTag();
        CompoundTag ranksTag = new CompoundTag();
        ranks.forEach((k, v) -> { if (v > 0) ranksTag.putInt(k, v); });
        tag.put("ranks", ranksTag);
        ListTag orderTag = new ListTag();
        for (String s : order) orderTag.add(StringTag.valueOf(s));
        tag.put("order", orderTag);
        tag.putIntArray("rewarded", rewardedLevels.stream().mapToInt(Integer::intValue).toArray());
        tag.putLong("second_wind_ready_at", secondWindReadyAt);
        persisted.put(TAG, tag);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }

    public int rank(String id) { return ranks.getOrDefault(id, 0); }

    public int spent() {
        int n = 0;
        for (int v : ranks.values()) n += v;
        return n;
    }

    public void setRank(String id, int rank) {
        int old = rank(id);
        if (rank <= 0) {
            ranks.remove(id);
            order.removeIf(id::equals);
        } else {
            ranks.put(id, rank);
            order.removeIf(id::equals);
            for (int i = 0; i < rank; i++) order.add(id); // one entry per rank, newest last
        }
        if (old != rank) {
            // keep "order" to exactly rank entries per id
            int count = (int) order.stream().filter(id::equals).count();
            while (count > rank) { order.remove(order.lastIndexOf(id)); count--; }
        }
    }

    /** Removes the most recently chosen ranks until spent points fit within the level. */
    public boolean enforceBudget(int level) {
        boolean changed = false;
        while (spent() > level && !order.isEmpty()) {
            String last = order.remove(order.size() - 1);
            int r = rank(last) - 1;
            if (r <= 0) ranks.remove(last); else ranks.put(last, r);
            changed = true;
        }
        if (spent() > level) { // order list out of sync: drop everything that is left
            ranks.clear();
            order.clear();
            changed = true;
        }
        return changed;
    }
}
