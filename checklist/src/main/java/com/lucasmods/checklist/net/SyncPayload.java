package com.lucasmods.checklist.net;

import com.lucasmods.checklist.Checklist;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Server tells the client its square level, the grid total, its Renown (level and progress to the next one), its
 * chosen ranks and whether the grid is dev-revealed.
 */
public record SyncPayload(int level, int total, boolean revealAll, Map<String, Integer> ranks,
                          int renownLevel, long renownInto, long renownNeed) implements CustomPacketPayload {
    public static final Type<SyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Checklist.MODID, "sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC = StreamCodec.of(SyncPayload::write, SyncPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SyncPayload p) {
        buf.writeVarInt(p.level);
        buf.writeVarInt(p.total);
        buf.writeBoolean(p.revealAll);
        buf.writeMap(p.ranks, FriendlyByteBuf::writeUtf, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(p.renownLevel);
        buf.writeVarLong(p.renownInto);
        buf.writeVarLong(p.renownNeed);
    }

    private static SyncPayload read(RegistryFriendlyByteBuf buf) {
        int level = buf.readVarInt();
        int total = buf.readVarInt();
        boolean reveal = buf.readBoolean();
        Map<String, Integer> ranks = buf.readMap(HashMap::new, FriendlyByteBuf::readUtf, FriendlyByteBuf::readVarInt);
        return new SyncPayload(level, total, reveal, ranks, buf.readVarInt(), buf.readVarLong(), buf.readVarLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
