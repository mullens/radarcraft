package com.lucasmods.checklist.net;

import com.lucasmods.checklist.Checklist;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client asks the server to set an upgrade to a rank. The server validates everything. */
public record SetRankPayload(String upgradeId, int rank) implements CustomPacketPayload {
    public static final Type<SetRankPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Checklist.MODID, "set_rank"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetRankPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SetRankPayload::upgradeId,
            ByteBufCodecs.VAR_INT, SetRankPayload::rank,
            SetRankPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
