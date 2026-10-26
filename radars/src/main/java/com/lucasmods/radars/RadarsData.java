package com.lucasmods.radars;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Per-world progression state, stored with the overworld.
 * Shrine targets are derived from the world seed the first time the data is created:
 * #2 is 500 blocks from spawn at a random angle, and every next one is 100 blocks further
 * from the previous shrine than the previous hop (#3 at 600 from #2, ... #7 at 1000 from #6).
 */
public final class RadarsData extends SavedData {
    private static final String NAME = "radars";
    private static final long SEED_SALT = 0x5241444152L; // "RADAR"

    public static final class Shrine {
        public final int index;
        public final int targetX;
        public final int targetZ;
        @Nullable public BlockPos pos;      // centre of the built shrine (first air block above the lodestone), once placed
        public boolean defeated;
        @Nullable public UUID bossId;
        @Nullable public UUID catId;

        Shrine(int index, int targetX, int targetZ) {
            this.index = index;
            this.targetX = targetX;
            this.targetZ = targetZ;
        }

        /** Where things should point: the real shrine if built, else the planned column. */
        public Vec3 pointTarget() {
            return pos != null ? Vec3.atCenterOf(pos) : new Vec3(targetX + 0.5, 64, targetZ + 0.5);
        }

        public String describe() {
            return String.format("Radar #%d (%s): target %d, %d; shrine %s; %s",
                    index, RadarVariant.byId(index).displayName, targetX, targetZ,
                    pos == null ? "not placed yet" : pos.toShortString(),
                    defeated ? "defeated" : "undefeated");
        }
    }

    private final Map<Integer, Shrine> shrines = new TreeMap<>();

    public static RadarsData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        RadarsData data = overworld.getDataStorage().computeIfAbsent(new Factory<>(RadarsData::new, RadarsData::load), NAME);
        if (data.shrines.isEmpty()) {
            data.generate(overworld);
        }
        return data;
    }

    private void generate(ServerLevel overworld) {
        RandomSource random = RandomSource.create(overworld.getSeed() ^ SEED_SALT);
        BlockPos spawn = overworld.getSharedSpawnPos();
        double x = spawn.getX();
        double z = spawn.getZ();
        int distance = 500;
        for (int i = RadarVariant.FIRST_SHRINE; i <= RadarVariant.LAST_SHRINE; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            x += Math.cos(angle) * distance;
            z += Math.sin(angle) * distance;
            shrines.put(i, new Shrine(i, (int) Math.round(x), (int) Math.round(z)));
            distance += 100;
        }
        setDirty();
        Radars.LOGGER.info("Generated Radar shrine targets for seed {}: {}", overworld.getSeed(), shrines.values().stream().map(Shrine::describe).toList());
    }

    public Shrine get(int index) {
        return shrines.get(index);
    }

    public Collection<Shrine> all() {
        return shrines.values();
    }

    private static RadarsData load(CompoundTag tag, HolderLookup.Provider registries) {
        RadarsData data = new RadarsData();
        ListTag list = tag.getList("shrines", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Shrine s = new Shrine(t.getInt("index"), t.getInt("targetX"), t.getInt("targetZ"));
            s.pos = NbtUtils.readBlockPos(t, "pos").orElse(null);
            s.defeated = t.getBoolean("defeated");
            s.bossId = t.hasUUID("boss") ? t.getUUID("boss") : null;
            s.catId = t.hasUUID("cat") ? t.getUUID("cat") : null;
            data.shrines.put(s.index, s);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Shrine s : shrines.values()) {
            CompoundTag t = new CompoundTag();
            t.putInt("index", s.index);
            t.putInt("targetX", s.targetX);
            t.putInt("targetZ", s.targetZ);
            if (s.pos != null) t.put("pos", NbtUtils.writeBlockPos(s.pos));
            t.putBoolean("defeated", s.defeated);
            if (s.bossId != null) t.putUUID("boss", s.bossId);
            if (s.catId != null) t.putUUID("cat", s.catId);
            list.add(t);
        }
        tag.put("shrines", list);
        return tag;
    }
}
