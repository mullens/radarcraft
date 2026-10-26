package com.lucasmods.checklist.net;

import com.lucasmods.checklist.Checklist;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** Server tells the client its level, the grid total, its chosen ranks and whether the grid is dev-revealed. */
public record SyncPayload(int level, int total, boolean revealAll, Map<String, Integer> ranks) implements CustomPacketPayload {
    public static final Type<SyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Checklist.MODID, "sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncPayload::level,
            ByteBufCodecs.VAR_INT, SyncPayload::total,
            ByteBufCodecs.BOOL, SyncPayload::revealAll,
            ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT), SyncPayload::ranks,
            SyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
