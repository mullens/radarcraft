package com.lucasmods.checklist;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A player's Radarcraft data, stored in the NeoForge "PlayerPersisted" tag (key {@code checklist}, kept from the
 * mod's first name so existing data survives) so it survives death and server restarts. The square level is never
 * stored: it is always recomputed from the player's advancements.
 */
public final class PlayerState {
    private static final String TAG = "checklist";
    /** Hot paths (exhaustion, damage, ticks) read ranks from here instead of parsing NBT each time. */
    private static final Map<Player, Map<String, Integer>> RANK_CACHE = new WeakHashMap<>();

    /** False when the player had no Radarcraft data at all: their first login with the mod installed. */
    public boolean existed;
    public final Map<String, Integer> ranks = new LinkedHashMap<>();
    /** Order in which ranks were taken, newest last; used to strip ranks when the points drop. */
    public final List<String> order = new ArrayList<>();
    public final List<Integer> rewardedLevels = new ArrayList<>();
    public long secondWindReadyAt = 0;
    /** Grid squares (advancement ids) already counted for this player, so old ones never announce themselves. */
    public final Set<String> credited = new LinkedHashSet<>();
    /** False for data written before the credited list existed. */
    public boolean hasCredited;
    /** Renown: lifetime XP earned, never lost. */
    public long renownXp;
    public boolean renownSeeded;
    /** Soul Keeper: XP levels to give back after respawn. */
    public int soulKeptLevels;

    public static PlayerState load(Player player) {
        PlayerState state = new PlayerState();
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        state.existed = persisted.contains(TAG, Tag.TAG_COMPOUND);
        CompoundTag tag = persisted.getCompound(TAG);
        CompoundTag ranks = tag.getCompound("ranks");
        for (String key : ranks.getAllKeys()) {
            int r = ranks.getInt(key);
            if (r > 0) state.ranks.put(key, r);
        }
        ListTag order = tag.getList("order", Tag.TAG_STRING);
        for (int i = 0; i < order.size(); i++) state.order.add(order.getString(i));
        for (int level : tag.getIntArray("rewarded")) state.rewardedLevels.add(level);
        state.secondWindReadyAt = tag.getLong("second_wind_ready_at");
        state.hasCredited = tag.contains("credited", Tag.TAG_LIST);
        ListTag credited = tag.getList("credited", Tag.TAG_STRING);
        for (int i = 0; i < credited.size(); i++) state.credited.add(credited.getString(i));
        state.renownSeeded = tag.contains("renown_xp", Tag.TAG_LONG);
        state.renownXp = tag.getLong("renown_xp");
        state.soulKeptLevels = tag.getInt("soul_kept_levels");
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
        ListTag creditedTag = new ListTag();
        for (String s : credited) creditedTag.add(StringTag.valueOf(s));
        tag.put("credited", creditedTag);
        if (renownSeeded) tag.putLong("renown_xp", renownXp);
        if (soulKeptLevels > 0) tag.putInt("soul_kept_levels", soulKeptLevels);
        persisted.put(TAG, tag);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        existed = true;
        hasCredited = true;
        RANK_CACHE.put(player, new HashMap<>(ranks));
    }

    /** Removes all Radarcraft data from a player, as if they never had the mod (admin/testing). */
    public static void forget(Player player) {
        CompoundTag persisted = player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        persisted.remove(TAG);
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        RANK_CACHE.remove(player);
    }

    /** Fast rank lookup for code that runs every tick or every hit. */
    public static int fastRank(Player player, String id) {
        Map<String, Integer> cached = RANK_CACHE.get(player);
        if (cached == null) {
            cached = new HashMap<>(load(player).ranks);
            RANK_CACHE.put(player, cached);
        }
        return cached.getOrDefault(id, 0);
    }

    public int rank(String id) { return ranks.getOrDefault(id, 0); }

    /** Points spent, using each upgrade's cost per rank. */
    public int spent(UpgradeCatalog catalog) {
        return catalog.spent(ranks);
    }

    public void setRank(String id, int rank) {
        if (rank <= 0) {
            ranks.remove(id);
            order.removeIf(id::equals);
            return;
        }
        int old = rank(id);
        ranks.put(id, rank);
        for (int i = old; i < rank; i++) order.add(id); // new ranks are the newest
        int count = (int) order.stream().filter(id::equals).count();
        while (count > rank) { order.remove(order.lastIndexOf(id)); count--; }
    }

    /** Removes the most recently chosen ranks until the spent points fit within the budget. */
    public boolean enforceBudget(int budget, UpgradeCatalog catalog) {
        boolean changed = false;
        while (spent(catalog) > budget && !order.isEmpty()) {
            String last = order.remove(order.size() - 1);
            int r = rank(last) - 1;
            if (r <= 0) ranks.remove(last); else ranks.put(last, r);
            changed = true;
        }
        if (spent(catalog) > budget) { // order list out of sync: drop everything that is left
            ranks.clear();
            order.clear();
            changed = true;
        }
        return changed;
    }
}
